// SPDX-License-Identifier: GPL-3.0-or-later
#include "CreativeEffectsProcessor.h"
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <complex>
#include <mutex>
#include <vector>

namespace dspfusion {
namespace {
constexpr double pi=3.14159265358979323846;
constexpr size_t N=2048,H=N/4,B=N/2+1;
using Words=std::array<int32_t,CreativeEffectsProcessor::kWords>;
using Spectrum=std::array<std::complex<float>,N>;
const Words defaults[3]={
    {1,0,350,300,80,18,120,700,250,300,700,10000},
    {1,0,500,0,0,12000,120,600,0,120,700,12000},
    {1,0,300,650,6500,30,1200,500,7000,800,700,120}
};
const int32_t lo[3][10]={
    {0,10,10,1,0,-2400,0,0,0,500},
    {0,0,0,500,5,20,-1200,0,0,500},
    {0,0,500,0,-1200,0,500,0,0,20}
};
const int32_t hi[3][10]={
    {1000,2000,250,64,1000,2400,1000,850,1000,20000},
    {1000,INT32_MAX,1,60000,2000,5000,1200,1000,1000,20000},
    {1000,1000,20000,250,2400,1000,20000,1000,1000,1000}
};
float clean(float x){return std::isfinite(x)?x:0.f;}
float safe(float x){return std::isfinite(x)?std::clamp(x,-64.f,64.f):0.f;}
float feedbackSafe(float x){x=clean(x);const float a=std::abs(x);if(a<1e-20f)return 0;return a<=4.f?x:std::copysign(4.f+(a-4.f)/(1.f+a-4.f),x);}
int32_t db(float x){return int32_t(std::lround(std::clamp(20000.*std::log10(std::max(double(x),1e-6)),-120000.,48000.)));}
uint32_t hash(const Words& w){uint32_t h=1;for(int32_t v:w)h=h*31u+uint32_t(v);return h;}
float coefficient(double hz,double sr){return float(1.-std::exp(-2*pi*std::clamp(hz,1.,sr*.45)/sr));}
float smooth(float state,float target,float speed){return state+(target-state)*speed;}
float wrap(float x,float length){while(x<0)x+=length;while(x>=length)x-=length;return x;}
float read(const std::vector<float>& ring,double at){
    const auto size=ring.size();while(at<0)at+=size;while(at>=size)at-=size;
    const size_t first=size_t(at),second=(first+1==size?0:first+1);const float t=float(at-first);
    return ring[first]+(ring[second]-ring[first])*t;
}
float readValid(const std::vector<float>& ring,double at,size_t cursor,uint64_t filled){double lag=double(cursor)-at;while(lag<0)lag+=ring.size();while(lag>=ring.size())lag-=ring.size();return lag+1>double(filled)?0.f:read(ring,at);}
struct Snapshot {
    std::mutex writer;
    std::array<std::atomic<int32_t>,12> words{};
    std::atomic<uint32_t> epoch{0};
    void put(const Words& w){std::lock_guard<std::mutex> lock(writer);epoch.fetch_add(1,std::memory_order_acq_rel);for(size_t i=0;i<w.size();++i)words[i].store(w[i],std::memory_order_relaxed);epoch.fetch_add(1,std::memory_order_release);}
    // One attempt per audio block: a busy writer never delays the callback.
    bool get(Words& out)const{const uint32_t e=epoch.load(std::memory_order_acquire);if(e&1)return false;Words copy{};for(size_t i=0;i<copy.size();++i)copy[i]=words[i].load(std::memory_order_relaxed);std::atomic_thread_fence(std::memory_order_acquire);if(e!=epoch.load(std::memory_order_acquire))return false;out=copy;return true;}
};
struct Telemetry {
    std::array<std::atomic<int32_t>,32> words{};
    std::atomic<uint32_t> epoch{0};
    void put(const std::array<int32_t,32>& w){epoch.fetch_add(1,std::memory_order_acq_rel);for(size_t i=0;i<w.size();++i)words[i].store(w[i],std::memory_order_relaxed);epoch.fetch_add(1,std::memory_order_release);}
    void get(int32_t* w)const{for(;;){const uint32_t e=epoch.load(std::memory_order_acquire);if(e&1)continue;for(size_t i=0;i<words.size();++i)w[i]=words[i].load(std::memory_order_relaxed);std::atomic_thread_fence(std::memory_order_acquire);if(e==epoch.load(std::memory_order_acquire))return;}}
};
struct Stage {
    Words p{};uint64_t frames=0;float mix=0,inPeak=0,outPeak=0,wetPeak=0;int32_t activity=0,aux=0,phase=0;
    std::array<int32_t,16> spectrum{};
    void begin(){inPeak=outPeak=wetPeak=0;spectrum.fill(-120000);}
    void peaks(float l,float r,float oL,float oR,float wL,float wR){inPeak=std::max({inPeak,std::abs(l),std::abs(r)});outPeak=std::max({outPeak,std::abs(oL),std::abs(oR)});wetPeak=std::max({wetPeak,std::abs(wL),std::abs(wR)});}
};
struct Grain {double position=0,step=1;uint32_t age=0,length=0;float pan=0;};
struct Granular {
    std::array<std::vector<float>,2> ring;size_t cursor=0;uint64_t filled=0;std::array<Grain,32> grains{};
    uint32_t random=0x29a35e81;double until=0,ratio=1;float cutoff=1;float wetL=0,wetR=0,lpL=0,lpR=0;std::array<float,1025> window{};
    float rnd(){random^=random<<13;random^=random>>17;random^=random<<5;return float(random>>8)/16777216.f;}
    // Maximum reverse look-back: 2 s delay + 1 s scatter +
    // (4x playback + advancing write head) * .25 s grain = 4.25 s.
    void configure(uint32_t sr){for(auto& r:ring)r.assign(size_t(sr)*45/10+8,0);for(size_t i=0;i<window.size();++i)window[i]=float(.5-.5*std::cos(2*pi*i/1024.));reset();}
    void reset(){cursor=0;filled=0;grains={};until=0;wetL=wetR=lpL=lpR=0;}
    void prepare(const Words& p,uint32_t sr){ratio=std::exp2(p[7]/1200.);cutoff=coefficient(std::min(double(p[11]),sr*.45/std::max(1.,ratio)),sr);}
    void tick(const Words& p,uint32_t sr,float l,float r,float& outL,float& outR){
        ring[0][cursor]=feedbackSafe(l+wetL*(p[9]*.001f));ring[1][cursor]=feedbackSafe(r+wetR*(p[9]*.001f));
        if(until<=0){
            for(auto& g:grains)if(g.age>=g.length){
                g.length=std::max(2u,uint32_t(sr*p[4]*.001));g.age=0;
                g.step=rnd()<p[8]*.001?-ratio:ratio;
                const double delay=sr*(p[3]+rnd()*p[6])*.001+std::max(0.,g.step-1)*g.length+2;
                g.position=double(cursor)-delay;g.pan=(rnd()*2-1)*p[10]*.001f;break;
            }
            until+=double(sr)/p[5];
        }
        --until;float sumL=0,sumR=0,weightsL=0,weightsR=0;
        for(auto& g:grains)if(g.age<g.length){
            const size_t wi=std::min(size_t(1024),size_t(uint64_t(g.age)*1024/g.length));const float w=window[wi];
            const float wl=w*(1.f-.5f*g.pan),wr=w*(1.f+.5f*g.pan);
            sumL+=readValid(ring[0],g.position,cursor,filled+1)*wl;sumR+=readValid(ring[1],g.position,cursor,filled+1)*wr;weightsL+=wl;weightsR+=wr;
            g.position+=g.step;++g.age;
        }
        lpL=smooth(lpL,sumL/std::max(1.f,weightsL),cutoff);lpR=smooth(lpR,sumR/std::max(1.f,weightsR),cutoff);
        outL=wetL=safe(lpL);outR=wetR=safe(lpR);cursor=(cursor+1)%ring[0].size();++filled;
    }
    int count()const{int n=0;for(const auto& g:grains)if(g.age<g.length)++n;return n;}
};
struct Freeze {
    std::array<std::array<float,N>,2> input{};
    std::array<std::array<float,N*2>,2> ola{};
    std::array<uint32_t,N*2> olaGeneration{};uint32_t generation=0;
    std::array<std::array<float,B>,2> previousPhase{},magnitude{},phase{},advance{};
    std::array<Spectrum,2> fftWork{};
    std::array<float,N> window{};std::array<std::complex<float>,N/2> twiddle{};
    std::array<int32_t,16> spectrum{};
    size_t inputAt=0,outputAt=0,hop=0;uint64_t filled=0;int32_t capturedToken=0,seenToken=0,pendingToken=0;
    bool captured=false,phaseHistory=false;float envelope=0,level=1,lpL=0,lpR=0,attack=1,release=1,cutoff=1;
    Freeze(){for(size_t i=0;i<N;++i)window[i]=float(.5-.5*std::cos(2*pi*i/N));for(size_t i=0;i<N/2;++i)twiddle[i]=std::polar(1.f,float(-2*pi*i/N));reset(0);}
    void reset(int32_t token){if(++generation==0){olaGeneration={};generation=1;}inputAt=outputAt=hop=0;filled=0;phaseHistory=false;captured=false;envelope=0;level=1;lpL=lpR=0;capturedToken=0;seenToken=token;pendingToken=0;spectrum.fill(-120000);}
    void prepare(const Words& p,uint32_t sr){attack=float(1.-std::exp(-std::log(1000.)/(sr*p[6]*.001)));release=float(1.-std::exp(-std::log(1000.)/(sr*p[7]*.001)));cutoff=coefficient(p[11],sr);}
    void fft(Spectrum& a,bool inverse){
        for(size_t i=1,j=0;i<N;++i){size_t bit=N>>1;for(;j&bit;bit>>=1)j^=bit;j^=bit;if(i<j)std::swap(a[i],a[j]);}
        for(size_t len=2;len<=N;len<<=1)for(size_t i=0;i<N;i+=len)for(size_t j=0;j<len/2;++j){const auto root=twiddle[j*N/len];const auto odd=a[i+j+len/2]*(inverse?std::conj(root):root),even=a[i+j];a[i+j]=even+odd;a[i+j+len/2]=even-odd;}
        if(inverse)for(auto& x:a)x/=float(N);
    }
    void frame(const Words& p,uint32_t sr){
        float energy=0;
        for(size_t c=0;c<2;++c){auto& a=fftWork[c];for(size_t i=0;i<N;++i){const float x=input[c][(inputAt+i)%N];energy+=x*x;a[i]={x*window[i],0};}fft(a,false);}
        const bool doCapture=pendingToken!=0 && p[4] && energy>1e-10f && phaseHistory;
        for(size_t c=0;c<2;++c)for(size_t k=0;k<B;++k){
            const float current=std::arg(fftWork[c][k]);const float expected=float(2*pi*k*H/N);
            const float residual=std::remainder(current-previousPhase[c][k]-expected,float(2*pi));
            if(doCapture){magnitude[c][k]=std::abs(fftWork[c][k]);phase[c][k]=current;advance[c][k]=expected+residual;}
            previousPhase[c][k]=current;
        }
        phaseHistory=energy>1e-10f;
        if(doCapture){captured=true;capturedToken=pendingToken;pendingToken=0;level=1;}
        if(!captured)return;
        const float ratio=float(std::exp2(p[8]/1200.));const float blur=p[9]*.00025f;
        spectrum.fill(-120000);
        for(size_t i=0;i<N;++i){const size_t at=(outputAt+i)%(N*2);if(olaGeneration[at]!=generation){ola[0][at]=ola[1][at]=0;olaGeneration[at]=generation;}}
        for(size_t c=0;c<2;++c){auto& a=fftWork[c];a.fill({});
            for(size_t k=1;k<N/2;++k){
                const float source=k/ratio;if(source>=N/2-1)continue;const size_t loBin=size_t(source),hiBin=loBin+1;const float t=source-loBin;
                const auto smag=[&](size_t b){return (1-2*blur)*magnitude[c][b]+blur*(magnitude[c][b?b-1:0]+magnitude[c][std::min(B-1,b+1)]);};
                const float mag=(smag(loBin)*(1-t)+smag(hiBin)*t)*level;
                // Destination phases are accumulated from measured instantaneous
                // source frequencies; a held spectrum is not a repeated PCM block.
                const float delta=(advance[c][loBin]*(1-t)+advance[c][hiBin]*t)*ratio;
                phase[c][k]=std::remainder(phase[c][k]+delta,float(2*pi));a[k]=std::polar(mag,phase[c][k]);a[N-k]=std::conj(a[k]);
                const size_t bin=std::min(size_t(15),size_t(std::log2(1.+double(k))/std::log2(double(B))*16));
                spectrum[bin]=std::max(spectrum[bin],db(mag*4.f/N));
            }
            fft(a,true);for(size_t i=0;i<N;++i)ola[c][(outputAt+i)%(N*2)]+=a[i].real()*window[i]*(2.f/3.f);
        }
        level*=float(std::exp(-std::log(1000.)*H/(double(sr)*p[5]*.001)));
    }
    void tick(const Words& p,uint32_t sr,float l,float r,float& wL,float& wR){
        if(p[3]!=seenToken){seenToken=p[3];if(p[3]&&p[4])pendingToken=p[3];}
        if(!p[4])pendingToken=0;
        input[0][inputAt]=l;input[1][inputAt]=r;inputAt=(inputAt+1)%N;++filled;
        if(++hop==H){hop=0;if(filled>=N)frame(p,sr);}
        const float target=(captured&&p[4])?1.f:0.f;
        envelope=smooth(envelope,target,target>envelope?attack:release);
        if(!p[4]&&envelope<1e-5f){captured=false;level=0;}
        lpL=smooth(lpL,olaGeneration[outputAt]==generation?ola[0][outputAt]:0.f,cutoff);lpR=smooth(lpR,olaGeneration[outputAt]==generation?ola[1][outputAt]:0.f,cutoff);
        const float mid=(lpL+lpR)*.5f,side=(lpL-lpR)*.5f*p[10]*.001f;
        wL=mid+side;wR=mid-side;
        ola[0][outputAt]=ola[1][outputAt]=0;outputAt=(outputAt+1)%(N*2);
    }
};
struct Allpass {std::vector<float> ring;size_t at=0;uint64_t filled=0;float tick(float x,float g){const float z=filled>=ring.size()?ring[at]:0.f,y=z-g*x;ring[at]=x+g*y;at=(at+1)%ring.size();++filled;return y;}void reset(){at=0;filled=0;}};
struct Shimmer {
    std::array<std::vector<float>,8> lines;std::array<size_t,8> cursor{};std::array<float,8> damp{},delay{};
    std::array<Allpass,4> ap;std::array<std::vector<float>,2> pre,pitch;size_t preAt=0,pitchAt=0;uint64_t filled=0;
    std::array<float,1025> window{};float pitchPhase=0,hpL=0,hpR=0,preDelay=0;
    float move=0,hp=0,lowpass=0,pitchStep=0;std::array<float,8> targetDelay{},targetGain{},gain{};
    static constexpr double times[8]={.0297,.0371,.0411,.0437,.0531,.0617,.0719,.0793};
    void configure(uint32_t sr){for(auto& l:lines)l.assign(size_t(sr)*22/100+8,0);for(auto& r:pre)r.assign(size_t(sr)*26/100+8,0);for(auto& r:pitch)r.assign(size_t(sr)*10/100+8,0);for(size_t i=0;i<4;++i)ap[i].ring.assign(std::max(size_t(2),size_t(sr*(.0031+i*.0017))),0);for(size_t i=0;i<window.size();++i)window[i]=float(.5-.5*std::cos(2*pi*i/1024.));reset(sr);}
    void reset(uint32_t sr){for(auto& a:ap)a.reset();cursor={};damp={};gain={};for(size_t i=0;i<8;++i)delay[i]=float(sr*times[i]*1.8175);preAt=pitchAt=0;filled=0;pitchPhase=0;hpL=hpR=preDelay=0;}
    void prepare(const Words& p,uint32_t sr){move=float(1.-std::exp(-1./(.03*sr)));hp=coefficient(p[11],sr);lowpass=coefficient(p[8],sr);pitchStep=float((1.-std::exp2(p[6]/1200.))/(sr*.06));for(size_t i=0;i<8;++i){targetDelay[i]=float(sr*times[i]*(.55+1.95*p[3]*.001));targetGain[i]=float(std::pow(.001,targetDelay[i]/sr/(p[4]*.001)));}}
    float shift(const std::vector<float>& ring,float ph,double span)const{const float b=wrap(ph+.5f,1.f);const float w=window[std::min(size_t(1024),size_t(ph*1024))];return readValid(ring,double(pitchAt)-4-ph*span,pitchAt,filled+1)*w+readValid(ring,double(pitchAt)-4-b*span,pitchAt,filled+1)*(1-w);}
    void tick(const Words& p,uint32_t sr,float l,float r,float& outL,float& outR){
        hpL=smooth(hpL,l,hp);hpR=smooth(hpR,r,hp);l-=hpL;r-=hpR;
        pre[0][preAt]=l;pre[1][preAt]=r;preDelay=smooth(preDelay,float(sr*p[5]*.001),move);
        l=readValid(pre[0],double(preAt)-preDelay,preAt,filled+1);r=readValid(pre[1],double(preAt)-preDelay,preAt,filled+1);preAt=(preAt+1)%pre[0].size();
        const float diffuse=.1f+.65f*p[10]*.001f;l=ap[1].tick(ap[0].tick(l,diffuse),diffuse);r=ap[3].tick(ap[2].tick(r,diffuse),diffuse);
        std::array<float,8> v{};
        for(size_t i=0;i<8;++i){delay[i]=smooth(delay[i],targetDelay[i],move);const float z=readValid(lines[i],double(cursor[i])-delay[i],cursor[i],filled);v[i]=damp[i]=smooth(damp[i],z,lowpass);}
        // Orthogonal projections and an orthonormal Hadamard feedback matrix.
        // Pitch injection uses the same projection; convex interpolation keeps
        // its loop norm <= 1 before the strictly sub-unity RT60 coefficient.
        float sumL=0,sumR=0;for(size_t i=0;i<8;++i){sumL+=v[i]*.3535533906f;sumR+=v[i]*(i&1?-.3535533906f:.3535533906f);}
        pitch[0][pitchAt]=sumL;pitch[1][pitchAt]=sumR;
        const double span=sr*.06;const float shL=shift(pitch[0],pitchPhase,span),shR=shift(pitch[1],pitchPhase,span);
        pitchAt=(pitchAt+1)%pitch[0].size();pitchPhase=wrap(pitchPhase+pitchStep,1.f);
        auto matrix=v;for(size_t width=1;width<8;width*=2)for(size_t block=0;block<8;block+=2*width)for(size_t j=0;j<width;++j){const float a=matrix[block+j],b=matrix[block+j+width];matrix[block+j]=a+b;matrix[block+j+width]=a-b;}
        const float amount=p[7]*.001f*.65f;
        for(size_t i=0;i<8;++i){gain[i]=smooth(gain[i],targetGain[i],move);const float pitched=(shL+(i&1?-shR:shR))*.3535533906f;const float feedback=((1-amount)*matrix[i]*.3535533906f+amount*pitched)*gain[i];const float input=(l+(i&1?-r:r))*.18f;lines[i][cursor[i]]=feedbackSafe(input+feedback);cursor[i]=(cursor[i]+1)%lines[i].size();}
        const float mid=(sumL+sumR)*.5f,side=(sumL-sumR)*.5f*p[9]*.001f;outL=safe(mid+side);outR=safe(mid-side);++filled;
    }
};
}
struct CreativeEffectsProcessor::Impl {
    std::array<Snapshot,3> requested;std::array<Telemetry,3> status;std::array<Stage,3> stages;
    Granular granular;Freeze freeze;Shimmer shimmer;uint32_t rate=48000;
    Impl(){for(size_t i=0;i<3;++i){requested[i].put(defaults[i]);stages[i].p=defaults[i];}configure(rate);}
    void publish(unsigned i){auto& s=stages[i];std::array<int32_t,32> w{};w[0]=1;w[1]=s.p[1];w[2]=rate;w[3]=int32_t(s.frames&0x7fffffff);w[4]=int32_t(hash(s.p));w[5]=db(s.inPeak);w[6]=db(s.outPeak);w[7]=db(s.wetPeak);w[8]=s.activity;w[9]=s.aux;w[10]=s.phase;std::copy(s.spectrum.begin(),s.spectrum.end(),w.begin()+12);status[i].put(w);}
    void configure(uint32_t sr){rate=sr;granular.configure(sr);shimmer.configure(sr);reset();}
    void reset(){granular.reset();shimmer.reset(rate);Words latest=stages[1].p;requested[1].get(latest);freeze.reset(latest[3]);for(unsigned i=0;i<3;++i){auto& s=stages[i];requested[i].get(s.p);s.frames=0;s.mix=0;s.activity=s.aux=s.phase=0;s.begin();publish(i);}}
    void process(unsigned kind,float* samples,uint32_t frames){auto& s=stages[kind];Words next=s.p;requested[kind].get(next);s.p=next;s.begin();
        if(!s.p[1]&&s.mix<1e-5f){s.mix=0;s.activity=s.aux=s.phase=0;if(kind==0)granular.reset();else if(kind==1)freeze.reset(next[3]);else shimmer.reset(rate);publish(kind);return;}
        if(kind==0)granular.prepare(s.p,rate);else if(kind==1)freeze.prepare(s.p,rate);else shimmer.prepare(s.p,rate);
        const float ramp=float(1.-std::exp(-std::log(1000.)/(.02*rate)));
        for(uint32_t f=0;f<frames;++f){const float l=safe(samples[2*f]),r=safe(samples[2*f+1]);float wL=0,wR=0;
            if(kind==0)granular.tick(s.p,rate,l,r,wL,wR);else if(kind==1)freeze.tick(s.p,rate,l,r,wL,wR);else shimmer.tick(s.p,rate,l,r,wL,wR);
            const float target=s.p[1]?s.p[2]*.001f:0.f;s.mix=smooth(s.mix,target,ramp);if(std::abs(s.mix-target)<1e-5f)s.mix=target;const float mix=s.mix*(kind==1?freeze.envelope:1.f);
            const float oL=safe(l+(wL-l)*mix),oR=safe(r+(wR-r)*mix);if(mix!=0){samples[2*f]=oL;samples[2*f+1]=oR;}s.peaks(l,r,oL,oR,kind==1?wL*freeze.envelope:wL,kind==1?wR*freeze.envelope:wR);
        }
        s.frames+=frames;if(kind==0){s.activity=granular.count();s.aux=1000000/s.p[5];s.phase=int32_t(std::clamp(1.-granular.until/(double(rate)/s.p[5]),0.,1.)*1000);}else if(kind==1){s.activity=freeze.captured;s.aux=freeze.capturedToken;s.phase=int32_t(freeze.envelope*1000);s.spectrum=freeze.spectrum;}else{s.activity=8;s.aux=s.p[6];s.phase=int32_t(shimmer.pitchPhase*1000);}publish(kind);
    }
};
CreativeEffectsProcessor::CreativeEffectsProcessor():impl_(std::make_unique<Impl>()){}
CreativeEffectsProcessor::~CreativeEffectsProcessor()=default;
bool CreativeEffectsProcessor::setParameters(unsigned kind,const int32_t* words,size_t count){if(kind>=3||!words||count!=kWords||words[0]!=1||(words[1]!=0&&words[1]!=1))return false;Words next{};for(size_t i=0;i<count;++i)next[i]=words[i];for(size_t i=0;i<10;++i)if(next[i+2]<lo[kind][i]||next[i+2]>hi[kind][i])return false;impl_->requested[kind].put(next);return true;}
void CreativeEffectsProcessor::configure(uint32_t sampleRate){if(sampleRate>=8000&&sampleRate<=768000&&sampleRate!=impl_->rate)impl_->configure(sampleRate);}
void CreativeEffectsProcessor::reset(){impl_->reset();}
void CreativeEffectsProcessor::process(float* samples,uint32_t frames){if(!samples||!frames)return;for(unsigned kind=0;kind<3;++kind)impl_->process(kind,samples,frames);}
void CreativeEffectsProcessor::readStatus(unsigned kind,int32_t* words)const{if(kind<3&&words)impl_->status[kind].get(words);}
}
