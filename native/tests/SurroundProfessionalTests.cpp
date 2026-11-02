#include "../surround/SurroundProcessor.h"
#include "Surround24Reference.h"
#include <cassert>
#include <cmath>
#include <complex>
#include <cstdio>
#include <cstring>
#include <vector>
#include <algorithm>
#include <thread>
#include <cstdlib>
#include <new>
#include "essential.h"
extern "C" audio_effect_library_t AELI;
static std::atomic<bool> watchAllocations=false;
static std::atomic<unsigned> callbackAllocations=0;
void* operator new(std::size_t n){if(watchAllocations.load())++callbackAllocations;if(void* p=std::malloc(n?n:1))return p;throw std::bad_alloc();}
void* operator new[](std::size_t n){return ::operator new(n);}
void operator delete(void* p) noexcept{std::free(p);}
void operator delete[](void* p) noexcept{std::free(p);}
void operator delete(void* p,std::size_t) noexcept{std::free(p);}
void operator delete[](void* p,std::size_t) noexcept{std::free(p);}
using dspfusion::SurroundProcessor;
using C=std::complex<double>;
static constexpr double pi=3.14159265358979323846;
static std::array<int32_t,27> packet(){std::array<int32_t,27> p{1,1,200,9000,5500,35};std::copy(dspfusion::kSurroundProfessionalDefaults.begin(),dspfusion::kSurroundProfessionalDefaults.end(),p.begin()+6);return p;}
static void frozenDefaults(){
    int cases=0;
    for(int fs:{8000,44100,48000,96000,192000})for(int slot=0;slot<8;++slot)for(int monitoring=0;monitoring<2;++monitoring){
        SurroundProcessor s;dspfusion::Surround24Reference old;s.configure(fs);old.configure(fs);auto p=packet();p[2]=std::min(800,200+100*slot);p[4]=5500-500*slot;p[5]=slot==7?30:35;p[26]=monitoring;
        assert(s.setProfessionalParameters(p.data(),p.size()));assert(old.setParameters(p.data(),6));
        for(int block=0;block<40;++block){std::array<float,514> a,b;for(size_t i=0;i<a.size();++i)a[i]=float(.03*std::sin((block*a.size()+i)*.103));b=a;s.process(a.data(),257);old.process(b.data(),257);assert(std::memcmp(a.data(),b.data(),sizeof(a))==0);}
        ++cases;
    }
    printf("Frozen ui24 RACE defaults and meter-only mode: %d cases bit-identical\n",cases);
}
// Independent bilinear Butterworth polynomial; does not reuse native biquad coefficients.
static C butter(double fs,double hz,double fc,int order,bool hp){
    const C z=std::polar(1.,-2*pi*hz/fs),s=1.-z,t=std::tan(pi*fc/fs)*(1.+z);
    C den;
    if(order==1)den=s+t;
    else if(order==2)den=s*s+std::sqrt(2.)*s*t+t*t;
    else den=std::pow(s,4)+2.613125929752753*std::pow(s,3)*t+3.414213562373095*s*s*t*t+2.613125929752753*s*std::pow(t,3)+std::pow(t,4);
    return std::pow(hp?s:t,order)/den;
}
static C path(const std::array<int32_t,27>& p,int fs,double hz,int ch){
    const double scale=std::min(1.,fs*.45/p[3]);const C b=butter(fs,hz,p[2]*scale,1<<p[16],true)*butter(fs,hz,p[3]*scale,1<<p[17],false);
    const int direction=p[11]?0:ch;double d=fs*p[14+direction]/1e7,whole=std::floor(d),fraction=d-whole;
    const C z=std::polar(1.,-2*pi*hz/fs);return std::pow(10.,-p[12+direction]/20000.)*b*(1-fraction+fraction*z)*std::pow(z,whole);
}
static void matrixResponse(){
    int cases=0;double worst=0;
    for(int fs:{8000,44100,48000,96000,192000})for(int hp=0;hp<3;++hp)for(int lp=0;lp<3;++lp)for(int input=0;input<2;++input){
        auto p=packet();p[7]=1;p[8]=730;p[9]=-30;p[10]=20;p[11]=0;p[12]=2200;p[13]=7100;p[14]=351;p[15]=1179;p[16]=hp;p[17]=lp;
        SurroundProcessor s;s.configure(fs);assert(s.setProfessionalParameters(p.data(),p.size()));
        const int window=fs/5;double hz=std::round(std::min(1000.,fs*.2)*window/fs)*fs/window;std::array<C,2> measured{};std::array<float,512> data{};
        const int settle=fs/2,total=settle+window;
        for(int at=0;at<total;){int n=std::min(256,total-at);for(int i=0;i<n;++i){data[2*i]=data[2*i+1]=0;data[2*i+input]=float(.02*std::cos(2*pi*hz*(at+i)/fs));}s.process(data.data(),n);for(int i=0;i<n;++i)if(at+i>=settle)for(int ch=0;ch<2;++ch)measured[ch]+=double(data[2*i+ch])*std::polar(1.,-2*pi*hz*(at+i)/fs);at+=n;}
        const C a=path(p,fs,hz,0),b=path(p,fs,hz,1),den=1.-a*b;const double gain=std::pow(10.,(p[9]+p[10])/200.),mix=p[8]/1000.;
        for(int ch=0;ch<2;++ch){C h=ch==input?1./den:-(input==0?a:b)/den;C expected=gain*((ch==input?1.-mix:0.)+mix*h);double error=std::abs(measured[ch]*(2./(.02*window))-expected);worst=std::max(worst,error);assert(error<2e-6);}
        ++cases;
    }
    printf("Professional RACE: %d asymmetric matrix responses match independent Butterworth/delay oracle, max error %.9g\n",cases,worst);
}
static void levelsAndLifecycle(){
    SurroundProcessor s;s.configure(48000);auto p=packet();p[7]=1;p[12]=p[13]=500;p[18]=1;p[19]=30;p[20]=10;p[21]=100;p[23]=1;p[24]=-60;p[25]=100;p[26]=1;assert(s.setProfessionalParameters(p.data(),p.size()));
    std::array<float,512> data{};double peak=0;
    for(int at=0;at<144000;at+=256){for(int i=0;i<256;++i){data[2*i]=float(.5*std::sin(2*pi*1000*(at+i)/48000.));data[2*i+1]=-data[2*i];}s.process(data.data(),256);if(at>48000)for(float x:data)peak=std::max(peak,std::abs(double(x)));}
    assert(peak<=std::pow(10.,-.3)+1e-7);int32_t v[32];assert(s.snapshot(v) && v[5] && v[13]>=-31 && v[13]<=0 && v[14]<=0 && v[2]==48000);
    assert(s.setAudition(1));s.process(data.data(),256);assert(s.snapshot(v) && v[24]==1);
    s.cancelAudition(true);assert(s.snapshot(v) && !v[5] && !v[24]);assert(s.setAudition(1));s.process(data.data(),256);assert(s.snapshot(v) && v[24]==0);
    assert(s.setAudition(0) && s.setAudition(2));s.process(data.data(),256);assert(s.snapshot(v) && v[24]==2);
    assert(!s.setAudition(5));s.reset();assert(s.snapshot(v) && !v[24] && !v[5]);
    p[1]=0;assert(s.setProfessionalParameters(p.data(),p.size()));for(int i=0;i<10;++i)s.process(data.data(),256);assert(s.snapshot(v) && !v[3] && !v[5] && !v[24]);
    puts("RACE RMS bounds, linked sample-peak ceiling, stopped meters and edge-latched audition lifecycle passed");
}
static void boundsAndStability(){
    SurroundProcessor s;auto p=packet();for(int i=0;i<21;++i){auto invalid=p;invalid[6+i]=dspfusion::kSurroundProfessionalLow[i]-1;assert(!s.setProfessionalParameters(invalid.data(),invalid.size()));invalid[6+i]=dspfusion::kSurroundProfessionalHigh[i]+1;assert(!s.setProfessionalParameters(invalid.data(),invalid.size()));}assert(!s.setProfessionalParameters(p.data(),26));assert(!s.setProfessionalParameters(nullptr,27));
    for(int fs:{8000,44100,48000,96000,192000}){s.configure(fs);p=packet();p[7]=1;p[11]=0;p[12]=p[13]=500;p[14]=200;p[15]=201;p[16]=p[17]=2;p[26]=1;assert(s.setProfessionalParameters(p.data(),p.size()));std::array<float,512> data{};double largest=0;for(int b=0;b<200;++b){for(int i=0;i<256;++i){data[2*i]=float(.02*std::sin((b*256+i)*.031));data[2*i+1]=float(.01*std::cos((b*256+i)*.141));}s.process(data.data(),256);for(float f:data){assert(std::isfinite(f));largest=std::max(largest,std::abs(double(f)));}}assert(largest<2);int32_t v[32];assert(s.snapshot(v));assert(v[17]<=fs*.45+1 && v[18]==200 && v[19]==201);}
    puts("Professional packet validation, 8-192kHz fractional delays and low-attenuation fourth-order stability passed");
}
static void chunksAndAllocations(){
    SurroundProcessor a,b;a.configure(44100);b.configure(44100);auto p=packet();p[7]=1;p[11]=0;p[14]=351;p[15]=1179;p[16]=2;p[17]=0;p[18]=1;p[23]=1;p[26]=1;assert(a.setProfessionalParameters(p.data(),27) && b.setProfessionalParameters(p.data(),27));
    std::array<float,8192> x{},y{};for(size_t i=0;i<x.size();++i)x[i]=float(.1*std::sin(i*.091)+.03*std::cos(i*.017));y=x;
    callbackAllocations=0;watchAllocations=true;
    for(size_t i=0;i<x.size()/2;++i)a.process(x.data()+i*2,1);
    for(size_t i=0;i<y.size()/2;){size_t n=std::min(size_t(257),y.size()/2-i);b.process(y.data()+i*2,n);i+=n;}
    watchAllocations=false;assert(callbackAllocations==0);assert(std::memcmp(x.data(),y.data(),sizeof(x))==0);
    puts("Professional RACE: all features process without callback allocations; 1/257-frame blocks are bit-identical");
}
static int command(effect_handle_t h,uint32_t code,uint32_t bytes=0,void* data=nullptr){int32_t result=0;uint32_t n=4;int rc=(*h)->command(h,code,bytes,data,&n,&result);return rc?rc:result;}
static int set(effect_handle_t h,uint32_t id,const void* data,size_t bytes){std::vector<uint32_t> p((16+bytes+3)/4);p[1]=4;p[2]=bytes;p[3]=id;std::memcpy(p.data()+4,data,bytes);return command(h,EFFECT_CMD_SET_PARAM,16+bytes,p.data());}
static std::array<int32_t,32> get(effect_handle_t h){std::array<uint32_t,4> request{0,4,128,131217};std::array<uint32_t,36> reply{};for(int i=0;i<1000;++i){uint32_t bytes=sizeof(reply);int rc=(*h)->command(h,EFFECT_CMD_GET_PARAM,16,request.data(),&bytes,reply.data());if(rc==-EAGAIN){std::this_thread::yield();continue;}assert(rc==0 && bytes==144);std::array<int32_t,32> v{};std::memcpy(v.data(),reply.data()+4,128);assert(v[0]==1 && !(v[1]&1));return v;}assert(false);return {};}
static void bridge(){
    constexpr effect_uuid_t uuid{0x30306067,0x9400,0x4d53,0x9000,{0,0,0,0,0,1}};effect_handle_t h=nullptr;assert(!AELI.create_effect(&uuid,0,0,&h));
    effect_config_t cfg{};cfg.inputCfg.samplingRate=cfg.outputCfg.samplingRate=44100;cfg.inputCfg.channels=cfg.outputCfg.channels=AUDIO_CHANNEL_OUT_STEREO;cfg.inputCfg.format=cfg.outputCfg.format=AUDIO_FORMAT_PCM_FLOAT;cfg.inputCfg.accessMode=EFFECT_BUFFER_ACCESS_READ;cfg.outputCfg.accessMode=EFFECT_BUFFER_ACCESS_WRITE;cfg.inputCfg.mask=cfg.outputCfg.mask=EFFECT_CONFIG_ALL;assert(!command(h,EFFECT_CMD_SET_CONFIG,sizeof(cfg),&cfg));
    int32_t off=0;assert(!set(h,131072,&off,4) && !set(h,131073,&off,4));assert(!command(h,EFFECT_CMD_ENABLE));
    auto p=packet();p[7]=1;p[11]=0;p[12]=2200;p[13]=7100;p[14]=351;p[15]=1179;p[26]=1;assert(!set(h,196610,p.data(),108));assert(set(h,196610,p.data(),104)==-EINVAL);auto bad=p;bad[14]=199;assert(set(h,196610,bad.data(),108)==-EINVAL);
    std::array<float,1024> input{},output{};for(int i=0;i<512;++i){input[2*i]=float(.02*std::sin(2*pi*1000*i/44100));input[2*i+1]=float(.01*std::cos(2*pi*500*i/44100));}
    auto process=[&]{audio_buffer_t a{},b{};a.frameCount=b.frameCount=512;a.f32=input.data();b.f32=output.data();assert(!(*h)->process(h,&a,&b));};for(int i=0;i<60;++i)process();auto v=get(h);assert(v[2]==44100 && v[3] && v[4] && v[5] && v[18]==351 && v[19]==1179 && v[20]==2200 && v[21]==7100);
    std::atomic<bool> running=true;std::thread control([&]{for(int i=0;i<300;++i){auto q=p;if(i%2){q[12]=3000;q[13]=8000;q[14]=2010;q[15]=369;}assert(!set(h,196610,q.data(),108));}running=false;});
    std::thread reader([&]{for(int i=0;i<3000 || running;++i){auto x=get(h);assert((x[18]==351 && x[19]==1179 && x[20]==2200 && x[21]==7100) || (x[18]==2010 && x[19]==369 && x[20]==3000 && x[21]==8000));}});
    for(int i=0;i<300;++i)process();control.join();reader.join();
    int32_t audition=1;assert(!set(h,196611,&audition,4));process();assert(get(h)[24]==1);assert(!command(h,EFFECT_CMD_DISABLE));assert(!get(h)[5] && !get(h)[24]);assert(!set(h,196611,&audition,4));assert(!command(h,EFFECT_CMD_ENABLE));process();assert(get(h)[24]==0);
    audition=0;assert(!set(h,196611,&audition,4));audition=2;assert(!set(h,196611,&audition,4));process();assert(get(h)[24]==2);assert(!command(h,EFFECT_CMD_SET_CONFIG,sizeof(cfg),&cfg));assert(!get(h)[5] && !get(h)[24]);assert(!set(h,196611,&audition,4));process();assert(!get(h)[24]);assert(!command(h,EFFECT_CMD_RESET));assert(!get(h)[5] && !get(h)[24]);assert(!AELI.release_effect(h));
    puts("Fusion RACE bridge: strict 108-byte writes, coherent concurrent snapshots and disable/reset audition cancellation passed");
}
int main(){frozenDefaults();matrixResponse();levelsAndLifecycle();boundsAndStability();chunksAndAllocations();bridge();puts("Professional surround checks passed");}
