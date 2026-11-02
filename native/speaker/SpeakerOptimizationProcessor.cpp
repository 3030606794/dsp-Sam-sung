// SPDX-License-Identifier: GPL-3.0-or-later
// Algorithm derived from AndroidAudioMods/ViPERFX_RE, commit
// eb65772bb01ef9040f8be034bd9315460f31394c, SpeakerCorrection.cpp.
// See README.md here for the exact topology, attribution and extensions.
#include "SpeakerOptimizationProcessor.h"
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <complex>
#include <mutex>

namespace dspfusion { namespace {
constexpr double pi=3.1415926535897932384626433832795;
constexpr size_t N=2048,Bands=48,Channels=4;
using Words=std::array<int32_t,128>;
using Coefficients=std::array<double,5>;
constexpr std::array<int32_t,12> defaults{1000,80000,1000,13500000,1000,420000,3880,1000,500,0,0,0};
constexpr std::array<int32_t,12> minimum{0,20000,500,2000000,500,100000,300,0,0,-24000,0,0};
constexpr std::array<int32_t,12> maximum{1000,1000000,2000,22000000,2000,4000000,10000,2000,1000,12000,1000,1000};
uint32_t nowMs(){return uint32_t(std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count());}
bool alive(uint32_t deadline,uint32_t now){return deadline&&int32_t(deadline-now)>0;}
double finite(float v){return std::isfinite(v)?double(v):0.;}
int32_t scaled(double v){return std::isfinite(v)?int32_t(std::clamp(std::round(v*1000.),-2147483647.,2147483647.)):0;}
int32_t peakDb(double v){return scaled(std::max(-120.,20*std::log10(std::max(v,1.e-6))));}
uint32_t hash(const Words& p){uint32_t h=1;for(size_t i=0;i<p.size();++i)h=h*31+(i==13?0u:uint32_t(p[i]));return h;}

// The original direct-form-I recurrence and arithmetic order are retained.
// Convex interpolation of stable normalized second-order denominators keeps
// edits stable without resetting sample history or allocating another engine.
struct Filter {
    Coefficients c{1,0,0,0,0},target=c,step{};
    std::array<double,2> x1{},x2{},y1{},y2{};
    uint32_t left=0;
    void clear(){x1={};x2={};y1={};y2={};}
    void set(const Coefficients& next,uint32_t samples){target=next;left=samples;if(!samples)c=next;else for(size_t i=0;i<c.size();++i)step[i]=(next[i]-c[i])/samples;}
    void advance(){if(left){if(!--left)c=target;else for(size_t i=0;i<c.size();++i)c[i]+=step[i];}}
    double tick(double x,size_t ch){double y=x*c[0]+x1[ch]*c[1]+x2[ch]*c[2]+y1[ch]*c[3]+y2[ch]*c[4];x2[ch]=x1[ch];x1[ch]=x;y2[ch]=y1[ch];y1[ch]=std::isfinite(y)?y:0.;return y1[ch];}
};
Coefficients coefficients(int type,float frequency,float q,uint32_t sr){
    const double w=2*pi*double(frequency)/sr,s=std::sin(w),co=std::cos(w),a=s/(double(q)+double(q));
    const double a0=1+a;
    double b0,b1,b2;
    if(type==0){b0=(1-co)/2;b1=1-co;b2=b0;}
    else if(type==1){b0=(1+co)/2;b1=-(1+co);b2=b0;}
    else {b0=s/2;b1=0;b2=-b0;} // Legacy Biquad BP has peak Q, not unit peak.
    return {b0/a0,b1/a0,b2/a0,-(-2*co/a0),-((1-a)/a0)};
}
struct Ramp {
    double value=0,target=0,step=0;uint32_t left=0;
    void set(double v,uint32_t n){target=v;left=n;if(!n)value=v;else step=(v-value)/n;}
    void advance(){if(left){if(!--left)value=target;else value+=step;}}
};
}

struct SpeakerOptimizationProcessor::Impl {
    std::mutex writer;
    std::array<std::atomic<int32_t>,128> requested{};
    std::atomic<uint32_t> revision{0},lease{0},observationEpoch{0},publishedObservationEpoch{0},publishedEpoch{0},ratePublished{48000},lastAudioMs{0};
    std::array<std::atomic<int32_t>,128> published{};
    Words current{};uint32_t seen=0,observedEpoch=0,rate=48000,frames=0,windows=0;
    Filter lp,hp,bp;Ramp mix,output,base,body;
    bool active=false,configured=false,clamped=false,observing=false,spectrumReady=false;
    std::array<float,12> p{};
    std::array<std::array<float,N>,Channels> ring{};
    std::array<float,N> window{};
    std::array<std::complex<double>,N> work{};
    std::array<std::complex<double>,N/2> twiddle{};
    std::array<size_t,N> reversed{};
    std::array<size_t,Bands> bins{};
    std::array<double,Bands*2> powers{};
    std::array<int32_t,Bands*2> spectrum{};
    size_t cursor=0,filled=0,hop=0;double windowSum=0;

    Impl(){
        Words d{};d[0]=1;std::copy(defaults.begin(),defaults.end(),d.begin()+2);
        for(size_t i=0;i<d.size();++i)requested[i].store(d[i]);revision.store(2);
        for(size_t i=0;i<N;++i){window[i]=float(.5-.5*std::cos(2*pi*i/N));windowSum+=window[i];size_t v=i,r=0;for(size_t j=1;j<N;j*=2){r=(r<<1)|(v&1);v>>=1;}reversed[i]=r;}
        for(size_t i=0;i<N/2;++i)twiddle[i]=std::polar(1.,-2*pi*i/N);
        clearObservation();
    }
    bool snapshot(Words& out,uint32_t& rev){
        const auto r=revision.load(std::memory_order_acquire);if((r&1)||r==seen)return false;
        Words candidate{};for(size_t i=0;i<candidate.size();++i)candidate[i]=requested[i].load(std::memory_order_relaxed);
        std::atomic_thread_fence(std::memory_order_acquire);if(revision.load(std::memory_order_relaxed)!=r)return false;
        out=candidate;rev=r;return true;
    }
    void clearObservation(){cursor=filled=hop=0;observing=false;spectrumReady=false;spectrum.fill(-120000);}
    void clearFilters(){lp.clear();hp.clear();bp.clear();mix.set(0,0);active=false;}
    void configureParameters(bool immediate){
        for(size_t i=0;i<p.size();++i)p[i]=float(double(current[i+2])/1000.);
        clamped=p[1]>rate*.48||p[3]>rate*.48||p[5]>rate*.48;
        p[1]=std::min(p[1],float(rate*.48));p[3]=std::min(p[3],float(rate*.48));p[5]=std::min(p[5],float(rate*.48));
        const auto ramp=immediate?0u:std::max(1u,rate/50);
        lp.set(coefficients(0,p[3],p[4],rate),ramp);hp.set(coefficients(1,p[1],p[2],rate),ramp);bp.set(coefficients(2,p[5],p[6],rate),ramp);
        output.set(std::pow(10.,p[9]/20.),ramp);base.set(p[8],ramp);
        body.set(double(3.88f)/p[6]*p[7],ramp);
        // Discard spectra from earlier settings instead of labelling them current.
        clearObservation();configured=true;
    }
    void fft(){
        for(size_t i=0;i<N;++i)if(i<reversed[i])std::swap(work[i],work[reversed[i]]);
        for(size_t length=2;length<=N;length*=2){const size_t stride=N/length;for(size_t start=0;start<N;start+=length)for(size_t j=0;j<length/2;++j){auto even=work[start+j],odd=work[start+j+length/2]*twiddle[j*stride];work[start+j]=even+odd;work[start+j+length/2]=even-odd;}}
    }
    void analyze(){
        powers.fill(0);
        for(size_t ch=0;ch<Channels;++ch){for(size_t i=0;i<N;++i)work[i]=double(ring[ch][(cursor+i)%N])*window[i];fft();for(size_t j=0;j<Bands;++j)powers[(ch/2)*Bands+j]+=2*std::norm(work[bins[j]])/(windowSum*windowSum);}
        // Per-side peak-amplitude calibrated spectra, stereo mean power. An
        // identical full-scale sine in L/R is 0dBFS; anti-phase is also 0dBFS.
        for(size_t j=0;j<powers.size();++j)spectrum[j]=scaled(std::clamp(10*std::log10(std::max(powers[j],1.e-12)),-120.,48.));
        ++windows;spectrumReady=true;
    }
    void observe(float l,float r,float ol,float orr){
        ring[0][cursor]=l;ring[1][cursor]=r;ring[2][cursor]=ol;ring[3][cursor]=orr;cursor=(cursor+1)%N;filled=std::min(filled+1,N);++hop;
        if(filled==N&&hop>=std::max(size_t(N),size_t(rate/20))){hop=0;analyze();}
    }
    void publish(double ip,double op,double wp){
        Words out{};out[0]=1;out[1]=current[1];out[2]=int32_t(rate);out[3]=int32_t(frames&0x7fffffffu);out[4]=int32_t(hash(current));out[11]=1;
        auto meter=[&](size_t i,double value){out[12+i]=scaled(value);};
        out[5]=out[12]=peakDb(ip);out[6]=out[13]=peakDb(op);out[7]=out[14]=active?peakDb(wp):-120000;
        meter(3,active?mix.value:0);out[8]=out[15];meter(4,p[11]);meter(5,active?1:0);meter(6,clamped?1:0);meter(7,observing&&spectrumReady?windows:0);
        meter(8,p[1]);meter(9,p[3]);meter(10,p[5]);meter(11,p[10]);meter(12,active?20*std::log10(std::max(output.value,1.e-12)):0);meter(13,base.value);meter(14,p[7]);meter(15,p[6]);meter(16,p[2]);meter(17,p[4]);
        for(size_t j=0;j<spectrum.size();++j)out[32+j]=spectrum[j];
        publishedEpoch.fetch_add(1,std::memory_order_acq_rel);for(size_t i=0;i<out.size();++i)published[i].store(out[i],std::memory_order_relaxed);publishedObservationEpoch.store(observedEpoch,std::memory_order_relaxed);publishedEpoch.fetch_add(1,std::memory_order_release);
    }
};

SpeakerOptimizationProcessor::SpeakerOptimizationProcessor():impl_(std::make_unique<Impl>()){configure(48000);}
SpeakerOptimizationProcessor::~SpeakerOptimizationProcessor()=default;
void SpeakerOptimizationProcessor::configure(uint32_t rate){auto& s=*impl_;s.rate=std::clamp(rate,8000u,768000u);s.ratePublished.store(s.rate);for(size_t j=0;j<Bands;++j){double f=20*std::pow(std::min(20000.,s.rate*.48)/20.,double(j)/(Bands-1));s.bins[j]=std::clamp(size_t(std::llround(f*N/s.rate)),size_t(1),N/2-1);}reset();}
void SpeakerOptimizationProcessor::reset(){auto& s=*impl_;s.clearFilters();s.clearObservation();s.configured=false;s.seen=0;s.frames=s.windows=0;s.lastAudioMs.store(0);Words blank{};blank[0]=1;blank[2]=int32_t(s.rate);blank[5]=blank[6]=blank[7]=-120000;blank[12]=blank[13]=blank[14]=-120000;for(size_t i=32;i<128;++i)blank[i]=-120000;s.publishedEpoch.fetch_add(1,std::memory_order_acq_rel);for(size_t i=0;i<blank.size();++i)s.published[i].store(blank[i],std::memory_order_relaxed);s.publishedEpoch.fetch_add(1,std::memory_order_release);}
bool SpeakerOptimizationProcessor::setParameters(const int32_t* words,size_t count){
    if(!words||count!=kWords||words[0]!=1||(words[1]!=0&&words[1]!=1))return false;
    for(size_t i=0;i<12;++i)if(words[i+2]<minimum[i]||words[i+2]>maximum[i])return false;
    if((words[12]!=0&&words[12]!=1000)||(words[13]!=0&&words[13]!=1000))return false;
    for(size_t i=14;i<count;++i)if(words[i]!=0)return false;
    auto& s=*impl_;std::lock_guard<std::mutex> lock(s.writer);s.revision.fetch_add(1,std::memory_order_acq_rel);for(size_t i=0;i<count;++i)s.requested[i].store(words[i],std::memory_order_relaxed);s.revision.fetch_add(1,std::memory_order_release);return true;
}
void SpeakerOptimizationProcessor::process(float* x,uint32_t n){
    if(!x||!n)return;auto& s=*impl_;Words next{};uint32_t rev=0;
    if(s.snapshot(next,rev)){bool changed=!s.configured;for(size_t i=2;i<12;++i)changed|=next[i]!=s.current[i];s.current=next;s.seen=rev;if(changed)s.configureParameters(!s.active);else{s.p[10]=next[12]*.001f;s.p[11]=next[13]*.001f;}}
    const bool permitted=s.current[1]!=0&&s.p[11]>.5f;
    if(!permitted){if(s.active){s.clearFilters();s.clearObservation();}}
    else if(!s.active){s.clearFilters();s.configureParameters(true);s.active=true;const auto fade=std::max(1u,s.rate/50);s.mix.set(s.p[0],fade);const double target=s.output.target;s.output.set(1,0);s.output.set(target,fade);}
    else if(s.mix.target!=double(s.p[0]))s.mix.set(s.p[0],std::max(1u,s.rate/50));
    const auto now=nowMs();
    const auto observationDeadline=s.lease.load(std::memory_order_acquire);
    const auto observationEpoch=s.observationEpoch.load(std::memory_order_acquire);
    const auto previousAudio=s.lastAudioMs.load(std::memory_order_acquire);
    // A paused audio callback cannot itself notice an expired observation
    // lease. Consume the GET-side epoch on resumption, even if that GET has
    // already renewed the deadline. Also reject windows spanning stream gaps.
    if(observationEpoch!=s.observedEpoch||(previousAudio&&uint32_t(now-previousAudio)>=300)){
        s.clearObservation();s.observedEpoch=observationEpoch;
    }
    const bool observing=permitted&&alive(observationDeadline,now);
    if(observing&&!s.observing){s.clearObservation();s.observing=true;}else if(!observing&&s.observing)s.clearObservation();
    double ip=0,op=0,wp=0;
    for(uint32_t frame=0;frame<n;++frame){
        const float originalL=x[frame*2],originalR=x[frame*2+1];double dry[2]{finite(originalL),finite(originalR)},out[2]{dry[0],dry[1]};
        if(permitted){s.lp.advance();s.hp.advance();s.bp.advance();s.mix.advance();s.output.advance();s.base.advance();s.body.advance();for(size_t ch=0;ch<2;++ch){double filtered=s.hp.tick(s.lp.tick(dry[ch],ch),ch)*s.base.value;double wet=filtered+s.bp.tick(filtered,ch)*s.body.value;wp=std::max(wp,std::abs(wet));out[ch]=s.mix.value==1?wet:s.mix.value==0?dry[ch]:dry[ch]+s.mix.value*(wet-dry[ch]);out[ch]*=s.output.value;if(s.mix.value!=0||s.output.value!=1)x[frame*2+ch]=std::isfinite(out[ch])?float(out[ch]):0.f;}}
        for(size_t ch=0;ch<2;++ch){ip=std::max(ip,std::abs(dry[ch]));op=std::max(op,std::abs(out[ch]));}
        if(observing)s.observe(float(dry[0]),float(dry[1]),float(out[0]),float(out[1]));
    }
    s.frames+=n;s.publish(ip,op,wp);s.lastAudioMs.store(now,std::memory_order_release);
}
void SpeakerOptimizationProcessor::readStatus(int32_t* out,bool renewObservation)const{
    if(!out)return;auto& s=*impl_;const auto now=nowMs();const bool previousLease=alive(s.lease.load(std::memory_order_acquire),now);
    if(renewObservation){if(!previousLease)s.observationEpoch.fetch_add(1,std::memory_order_release);s.lease.store(now+1500,std::memory_order_release);}
    bool success=false;uint32_t observation=0;for(unsigned attempt=0;attempt<4;++attempt){const auto e=s.publishedEpoch.load(std::memory_order_acquire);if(e&1)continue;for(size_t i=0;i<kStatusWords;++i)out[i]=s.published[i].load(std::memory_order_relaxed);observation=s.publishedObservationEpoch.load(std::memory_order_relaxed);std::atomic_thread_fence(std::memory_order_acquire);if(e==s.publishedEpoch.load(std::memory_order_relaxed)){success=true;break;}}
    if(!success){std::fill_n(out,kStatusWords,0);out[0]=1;out[2]=int32_t(s.ratePublished.load());}
    const auto last=s.lastAudioMs.load(std::memory_order_acquire);const bool fresh=last&&uint32_t(now-last)<300;
    if(!success||!previousLease||!fresh||!renewObservation||observation!=s.observationEpoch.load(std::memory_order_acquire)){out[19]=0;for(size_t i=32;i<128;++i)out[i]=-120000;}
    if(!fresh||!renewObservation){out[5]=out[6]=out[7]=out[12]=out[13]=out[14]=-120000;out[8]=out[15]=out[17]=0;}
}
}
