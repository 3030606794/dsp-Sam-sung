#include <algorithm>
#include <array>
#include <atomic>
#include <cassert>
#include <cmath>
#include <complex>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <new>
#include <thread>
#include <vector>
#include "../viper/src/viper/effects/Cure.h"
#include "essential.h"
extern "C" audio_effect_library_t AELI;
namespace {
constexpr double pi=3.14159265358979323846;
std::atomic<bool> countNew{false};std::atomic<unsigned> allocations{0};
using headphone::Settings;
auto precise(){auto p=headphone::defaults;p[1]=1;p[7]=1;return p;}
std::array<int32_t,19> tools(int mode,const Settings& a,const Settings& b,int token=1,bool match=false){
    std::array<int32_t,19> v{};v[0]=mode;v[1]=match;v[2]=token;
    std::copy(a.begin(),a.end(),v.begin()+3);std::copy(b.begin(),b.end(),v.begin()+11);return v;
}
std::vector<float> signal(unsigned rate,unsigned n){
    std::vector<float> v(n*2);uint32_t seed=123;
    for(unsigned i=0;i<n;++i){seed=seed*1664525+1013904223;const float noise=(int32_t(seed)/2147483648.f)*.01f;
        v[2*i]=.13f*std::sin(2*pi*500*i/rate)+noise;v[2*i+1]=.09f*std::cos(2*pi*1200*i/rate)-noise;}
    return v;
}
void legacy(){
    for(unsigned rate:{44100,48000,88200,96000,192000})for(unsigned tier=0;tier<3;++tier){
        Cure actual;actual.SetSamplingRate(rate);actual.SetEnable(true);
        Crossfeed reference;PassFilter pass;reference.SetSamplingRate(rate);pass.SetSamplingRate(rate);
        Crossfeed::Preset p{uint16_t(tier?700:650),uint16_t(tier==0?95:tier==1?60:45)};
        actual.SetPreset(p);reference.SetPreset(p);
        auto disabled=headphone::defaults;disabled[2]=1817;disabled[3]=137;disabled[4]=23;disabled[5]=377;disabled[6]=-83;disabled[7]=1;
        assert(actual.SetAdvanced(disabled.data(),8));
        auto input=signal(rate,4096),a=input,b=input;
        for(unsigned pos=0;pos<4096;){const unsigned n=std::min(4096-pos,(pos%3==0?1u:pos%3==1?13u:511u));
            actual.Process(a.data()+pos*2,n);reference.ProcessFrames(b.data()+pos*2,n);pass.ProcessFrames(b.data()+pos*2,n);pos+=n;}
        assert(a==b);
    }
    std::puts("legacy: 15 tier/rate cases remain bit-identical with disabled professional parameters");
}
// Independent frequency-domain oracle; includes the fractional cross-path interpolation and output poles.
std::complex<double> response(const Settings& p,unsigned rate,double hz,bool crossOnly){
    using C=std::complex<double>;const C z=std::exp(C(0,-2*pi*hz/rate));
    const double level=p[3]/10.,gl=std::pow(10.,(-level*5/6-3)/20),gh=1-std::pow(10.,(level/6-3)/20);
    const double fh=p[2]*std::pow(2.,((-level*5/6-3)-20*std::log10(gh))/12);
    const double bl=std::exp(-2*pi*p[2]/rate),bh=std::exp(-2*pi*fh/rate),gain=1/(1-gh+gl);
    const double delay=p[5]*rate/1e6;const int n=int(delay);const double f=delay-n;
    C h=crossOnly?gl*(1-bl)/(1.-bl*z)*std::pow(z,n)*(1-f+f*z):
        ((1-gh*(1-bh))-bh*z)/(1.-bh*z);
    h*=gain;
    if(p[7]==0){
        const double th=std::tan(pi*10/rate),tl=std::tan(pi*std::min(18000.,rate*.45)/rate);
        h*=((1.-z)/(1+th))/(1.-(1-th)/(1+th)*z);
        h*=std::pow((tl/(1+tl))*(1.+z)/(1.-(1-tl)/(1+tl)*z),3);
    }
    h=h*(p[4]/100.);if(!crossOnly)h+=1-p[4]/100.;
    return h*std::pow(10.,p[6]/200.);
}
void referenceMatrix(){
    unsigned cases=0;
    for(unsigned rate:{8000,44100,48000,96000,192000})for(int level:{10,45,46,60,95,150})for(int filter:{0,1})for(int cutoff:{300,700,2000}){
        Settings p=precise();p[2]=cutoff;p[3]=level;p[4]=37;p[5]=cases%3==0?0:cases%3==1?137:500;p[6]=-17;p[7]=filter;
        headphone::Kernel actual;actual.configure(p,rate);const double hz=cases%3==0?50:cases%3==1?500:rate*.25,omega=2*pi*hz/rate;
        std::complex<double> measuredL=0,measuredR=0;const unsigned count=rate/5;
        for(unsigned i=0;i<rate+count;++i){
            float in[2]={float(.1*std::cos(omega*i)),0},out[2],cross[2];actual.process(in,out,cross);
            if(i>=rate){const auto ph=std::exp(std::complex<double>(0,-omega*i));measuredL+=double(out[0])*ph;measuredR+=double(out[1])*ph;}
        }
        measuredL*=2/(.1*count);measuredR*=2/(.1*count);
        assert(std::abs(measuredL-response(p,rate,hz,false))<2e-5);
        assert(std::abs(measuredR-response(p,rate,hz,true))<2e-5);++cases;
    }
    auto p=precise();headphone::Kernel a,b;a.configure(p,48000);p[3]++;b.configure(p,48000);
    float difference=0;auto input=signal(48000,5000);
    for(unsigned i=0;i<5000;++i){float x[2],y[2],cross[2];a.process(input.data()+i*2,x,cross);b.process(input.data()+i*2,y,cross);difference+=std::abs(x[0]-y[0]);}
    assert(difference>0.01f);
    std::printf("independent response matrix: %u cases, including 0.1 dB and sub-sample delay\n",cases);
}
void auditions(){
    for(unsigned rate:{8000,44100,48000,96000,192000}){
        auto p=precise(),a=p,b=p;a[3]=95;b[3]=45;b[6]=-30;b[5]=177;
        headphone::Engine e;e.setRate(rate);assert(e.set(p.data(),8));
        auto v=tools(6,a,b);assert(e.setTools(v.data(),19));
        const auto input=signal(rate,rate*3);float out[2];int32_t r[20];
        for(unsigned i=0;i<rate*3;++i)e.process(input.data()+i*2,out);
        e.runtime(r,true);assert(r[6]==2 && r[8]<=0 && r[9]<=0);
        double energies[2]={};
        for(unsigned choice=0;choice<2;++choice){v=tools(4+choice,a,b,1,true);assert(e.setTools(v.data(),19));
            for(unsigned i=0;i<rate*3;++i){e.process(input.data()+i*2,out);if(i>=rate)energies[choice]+=double(out[0])*out[0]+double(out[1])*out[1];}}
        assert(std::abs(10*std::log10(energies[0]/energies[1]))<.02);
        e.clearTools();e.runtime(r,true);assert(r[5]==0 && r[6]==0 && r[8]==0 && r[9]==0 && r[10]==0);
        v=tools(4,a,b,1,true);assert(!e.setTools(v.data(),19));
        v=tools(6,a,b);assert(e.setTools(v.data(),19));float silence[2]={};
        for(unsigned i=0;i<rate*3;++i)e.process(silence,out);
        e.runtime(r,true);assert(r[6]==3);
        e.setRate(rate==48000?44100:48000);e.runtime(r,true);assert(r[5]==0 && r[6]==0);
    }
    headphone::Engine normal,listening;auto p=precise();assert(normal.set(p.data(),8));assert(listening.set(p.data(),8));
    auto input=signal(48000,10000);float x[2],y[2];
    for(int mode=1;mode<=3;++mode){
        auto t=tools(mode,p,p);assert(listening.setTools(t.data(),19));
        for(unsigned i=0;i<10000;++i){normal.process(input.data()+i*2,x);listening.process(input.data()+i*2,y);
            if(i>1000 && mode==1)assert(y[0]==input[i*2] && y[1]==input[i*2+1]);
            if(i>1000 && mode==2)for(unsigned c=0;c<2;++c)assert(std::abs(y[c]-(x[c]-input[i*2+c])*.5f)<2e-6);}
    }
    allocations=0;countNew=true;for(unsigned i=0;i<10000;++i)listening.process(input.data()+i*2,y);countNew=false;assert(allocations==0);
    auto stop=tools(0,p,p,99);const float before[2]={y[0],y[1]};assert(listening.setTools(stop.data(),19));
    normal.process(input.data(),x);listening.process(input.data(),y);
    for(unsigned c=0;c<2;++c)assert(std::abs(y[c]-before[c])<=std::abs(x[c]-before[c])/480.f+2e-6f);
    std::puts("audition: five rates, fixed attenuation A/B, silence rejection, cleanup and zero callback allocation");
}
int cmd(effect_handle_t h,uint32_t code,uint32_t size=0,void* data=nullptr){int reply=-999;uint32_t n=4;const int rc=(*h)->command(h,code,size,data,&n,&reply);return rc?rc:reply;}
int send(effect_handle_t h,uint32_t id,const int32_t* v,unsigned n){
    std::array<uint32_t,64> packet{};packet[1]=4;packet[2]=n*4;packet[3]=id;if(n)std::memcpy(packet.data()+4,v,n*4);
    return cmd(h,EFFECT_CMD_SET_PARAM,16+n*4,packet.data());
}
void protocol(){
    constexpr effect_uuid_t uuid{0x30306067,0x9400,0x4d53,0x9000,{0,0,0,0,0,1}};
    effect_handle_t h=nullptr;assert(AELI.create_effect(&uuid,0,0,&h)==0);auto p=precise();
    assert(send(h,65640,p.data(),8)==0);assert(send(h,65640,p.data(),7)==-EINVAL);
    const int lo[8]={1,0,300,10,0,0,-120,0},hi[8]={1,1,2000,150,100,500,0,1};
    for(unsigned i=0;i<8;++i)for(int invalid:{lo[i]-1,hi[i]+1}){auto bad=p;bad[i]=invalid;assert(send(h,65640,bad.data(),8)==-EINVAL);}
    int32_t on=1,off=0;assert(send(h,65642,&off,1)==-EINVAL);
    assert(cmd(h,EFFECT_CMD_ENABLE)==0);assert(send(h,65581,&on,1)==0);
    auto t=tools(1,p,p);assert(send(h,65641,t.data(),19)==0);
    auto get=[&](){std::array<uint32_t,4> q{0,4,80,131219};std::array<uint32_t,24> v{};uint32_t n=96;
        const int rc=(*h)->command(h,EFFECT_CMD_GET_PARAM,16,q.data(),&n,v.data());assert(rc==0 || rc==-EAGAIN);
        std::array<int32_t,20> r{};if(rc==0){assert(n==96 && v[0]==0);std::memcpy(r.data(),v.data()+4,80);assert(!(r[1]&1));}return r;};
    assert(get()[5]==1);
    assert(send(h,65581,&off,1)==0);assert(get()[5]==0);assert(send(h,65641,t.data(),19)==-EINVAL);
    assert(send(h,65581,&on,1)==0);assert(send(h,65641,t.data(),19)==0);assert(cmd(h,EFFECT_CMD_DISABLE)==0);assert(get()[5]==0);
    AELI.release_effect(h);
    std::puts("Fusion protocol: strict payload/bounds, gated tools and coherent runtime lifecycle");
}
}
void* operator new(std::size_t size){if(countNew)++allocations;if(void* p=std::malloc(size))return p;throw std::bad_alloc();}
void operator delete(void* p)noexcept{std::free(p);}
void operator delete(void* p,std::size_t)noexcept{std::free(p);}
int main(){legacy();referenceMatrix();auditions();protocol();std::puts("Headphone crossfeed round 1 passed");}
