#include "../convolver/ConvolverControl.h"
#include "essential.h"
#include <algorithm>
#include <array>
#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <limits>
#include <cstdlib>
#include <new>
#include <random>
#include <thread>
#include <atomic>
#include <vector>
#ifdef _WIN32
#include <windows.h>
#endif
extern "C" audio_effect_library_t AELI;
thread_local bool watchAllocations=false;
thread_local unsigned watchedAllocations=0;
void* operator new(std::size_t n){if(watchAllocations)++watchedAllocations;if(void* p=std::malloc(n))return p;throw std::bad_alloc();}
void* operator new[](std::size_t n){return ::operator new(n);}
void operator delete(void* p)noexcept{std::free(p);}
void operator delete[](void* p)noexcept{std::free(p);}
void operator delete(void* p,std::size_t)noexcept{std::free(p);}
void operator delete[](void* p,std::size_t)noexcept{std::free(p);}
namespace {
constexpr int32_t missing=std::numeric_limits<int32_t>::min();
constexpr std::array<int32_t,10> defaults{1000,0,0,0,0,0,0,0,0,0};
struct State {
    void* state=FusionConvCreate();
    State(unsigned fs=48000){assert(state);FusionConvConfigure(state,fs);FusionConvLoaded(state,2,20,123);FusionConvEnabled(state,1);}
    ~State(){FusionConvDestroy(state);}
    std::array<int32_t,32> get(){std::array<int32_t,32> r{};FusionConvSnapshot(state,r.data());return r;}
    void prime(){const unsigned n=get()[2]/50+1;std::vector<float> a(n),b(n),zero(n);run(a,b,zero,zero);}
    void run(std::vector<float>& l,std::vector<float>& r,const std::vector<float>& dl,const std::vector<float>& dr,size_t block=512){
        for(size_t i=0;i<l.size();i+=block){size_t n=std::min(block,l.size()-i);FusionConvBegin(state,dl.data()+i,dr.data()+i,n);FusionConvEnd(state,l.data()+i,r.data()+i,n);}
    }
};
void core(){
    std::mt19937 rng(81);std::uniform_real_distribution<float> random(-.1,.1);
    unsigned cases=0;
    for(unsigned fs:{8000u,44100u,48000u,96000u,192000u}) {
        std::vector<float> l(5001),r(5001),dl(5001),dr(5001);for(size_t i=0;i<l.size();++i){l[i]=random(rng);r[i]=random(rng);dl[i]=random(rng);dr[i]=random(rng);}
        const auto originalL=l,originalR=r;State s(fs);s.run(l,r,dl,dr,509);assert(l==originalL&&r==originalR);++cases;
        for(int32_t us:{0,1,10,23,1000,199999,200000}) {
            State p(fs);auto values=defaults;values[3]=us;assert(FusionConvSet(p.state,values.data(),10));
            p.prime();
            const unsigned samples=unsigned(std::llround(double(us)*fs/1e6));std::vector<float> il(samples+10),ir(samples+10),zero(samples+10);il[0]=.5;ir[0]=-.25;p.run(il,ir,zero,zero,1);
            for(size_t i=0;i<il.size();++i){assert(il[i]==(i==samples?.5f:0.f));assert(ir[i]==(i==samples?-.25f:0.f));}
            assert(p.get()[7]==int32_t(samples)&&p.get()[8]==std::llround(double(samples)*1e6/fs));++cases;
        }
        for(int32_t mix:{0,1,500,999,1000}){
            State p(fs);auto v=defaults;v[0]=mix;v[1]=-60;v[2]=-30;v[4]=20;v[5]=-20;v[6]=1;v[8]=1;assert(FusionConvSet(p.state,v.data(),10));
            p.prime();
            std::vector<float> wl(400,.02),wr(400,-.03),dL(400,.07),dR(400,-.08);p.run(wl,wr,dL,dR,17);
            const double a=mix/1000.,wg=std::pow(10.,-.3),og=std::pow(10.,-.15);
            const double el=(.07*(1-a)+.03*std::pow(10.,.1)*wg*a)*og,er=(-.08*(1-a)+.02*std::pow(10.,-.1)*wg*a)*og;
            for(size_t i=0;i<wl.size();++i){assert(std::abs(wl[i]-el)<2e-8);assert(std::abs(wr[i]-er)<2e-8);}++cases;
        }
    }
    State invalid;auto before=invalid.get();for(unsigned field=0;field<10;++field){auto v=defaults;v[field]=std::numeric_limits<int32_t>::max();assert(!FusionConvSet(invalid.state,v.data(),10));assert(invalid.get()==before);}assert(!FusionConvSet(invalid.state,defaults.data(),9));
    std::puts("Convolver: neutral bit-exact, single-sample quantization, mix/trim/polarity/swap formulas and strict atomic validation passed.");
    std::printf("Convolver core audio configurations: %u\n",cases);
}
void auditionAndMeters(){
    for(int mode:{1,2,3}){
        State s;std::vector<float> l(6000,.04),r(6000,.04),dl(6000,.1),dr(6000,.1);assert(FusionConvAudition(s.state,mode));s.run(l,r,dl,dr,37);
        const float expected=mode==1?.1f:mode==2?.04f:-.06f;for(size_t i=1000;i<l.size();++i)assert(std::abs(l[i]-expected)<1e-8);
        auto v=s.get();assert((v[3]&8)&&v[10]==mode&&v[31]==4800);assert(v[19]==-200&&v[20]==-200&&v[21]==missing&&v[22]==0);assert(v[23]==-280&&v[25]==missing&&v[26]==0);assert(v[27]==-280&&v[29]==missing&&v[30]==0);
        FusionConvClear(s.state);assert(FusionConvAudition(s.state,mode));assert(s.get()[10]==0);assert(FusionConvAudition(s.state,0));assert(FusionConvAudition(s.state,mode));assert(s.get()[10]==mode);
    }
    State s;std::vector<float> l(4800,.04),r(4800,-.04),zero(4800);s.run(l,r,l,r,19);auto v=s.get();assert(v[21]==missing&&v[22]==missing+1);
    FusionConvEnabled(s.state,0);v=s.get();assert(!(v[3]&14));for(unsigned i=19;i<=30;++i)assert(v[i]==missing);
    std::puts("Convolver: 15ms temporary auditions, non-rearming stale commands, same-window pre-audition metering, mono/silent lifecycle passed.");
    State centered;for(unsigned i=0;i<4800;++i){l[i]=.1f+.01f*std::sin(i*6.283185307179586/48);r[i]=.2f+.02f*std::cos(i*6.283185307179586/48);}centered.run(l,r,l,r,127);assert(std::abs(centered.get()[21])<=1);
    State dc;for(unsigned i=0;i<4800;++i){l[i]=.1f+1e-6f*std::sin(i*6.283185307179586/48);r[i]=.2f+2e-6f*std::sin(i*6.283185307179586/48);}dc.run(l,r,l,r,127);assert(dc.get()[21]==10000);
    State small;std::fill(l.begin(),l.end(),1e-12f);std::fill(r.begin(),r.end(),1e-12f);small.run(l,r,l,r);assert(small.get()[19]==-2400&&small.get()[20]==-2400);
    State nearAnti;std::fill(l.begin(),l.end(),1e-4f);std::fill(r.begin(),r.end(),std::nextafter(-1e-4f,0.f));nearAnti.run(l,r,l,r);assert(nearAnti.get()[22]<-1200&&nearAnti.get()[22]!=missing+1);
}
std::vector<float> rapid(unsigned fs,unsigned block){
    State s(fs);const unsigned length=fs/5;std::vector<float> output(length*2),l(block),r(block),dl(block),dr(block);std::array<int32_t,10> p=defaults;
    const unsigned editStep=std::max(1u,fs/2000),lastEdit=fs/20;unsigned nextEdit=0;
    for(unsigned i=0;i<length;){
        if(i==nextEdit && i<lastEdit){p=defaults;if((i/editStep)%3){p[0]=300+(i/editStep)%700;p[1]=-60;p[2]=-20;p[3]=100+(i/editStep)*13;p[4]=30;p[5]=-10;p[6]=(i/editStep)&1;p[8]=(i/editStep)&1;}assert(FusionConvSet(s.state,p.data(),10));nextEdit+=editStep;}
        const unsigned n=std::min({block,length-i,i<lastEdit?nextEdit-i:length-i});assert(n);
        for(unsigned j=0;j<n;++j){l[j]=.03f*std::sin((i+j)*.031);r[j]=.04f*std::cos((i+j)*.017);dl[j]=.08f*std::sin((i+j)*.01);dr[j]=-.06f*std::cos((i+j)*.011);}
        watchAllocations=true;FusionConvBegin(s.state,dl.data(),dr.data(),n);FusionConvEnd(s.state,l.data(),r.data(),n);watchAllocations=false;assert(!watchedAllocations);
        for(unsigned j=0;j<n;++j){assert(std::isfinite(l[j])&&std::isfinite(r[j]));output[2*(i+j)]=l[j];output[2*(i+j)+1]=r[j];}i+=n;
    }
    return output;
}
void smoothing(){
    for(unsigned fs:{8000u,48000u,192000u}){const auto a=rapid(fs,1),b=rapid(fs,511);assert(a==b);}
    State s;auto p=defaults;p[0]=0;assert(FusionConvSet(s.state,p.data(),10));std::vector<float> l(1000,.1),r(1000,.1),dry(1000,.2);s.run(l,r,dry,dry);assert(l[0]>.1f&&l[0]<.101f);assert(l[719]==.2f&&l.back()==.2f);
    assert(FusionConvSet(s.state,defaults.data(),10));std::fill(l.begin(),l.end(),.1f);std::fill(r.begin(),r.end(),.1f);s.run(l,r,dry,dry);assert(l[0]>.199f&&l[0]<.2f);assert(l[719]==.1f&&l.back()==.1f);
    State d;std::vector<float> wl(1000,.1),wr(1000,.1),zero(1000);d.run(wl,wr,zero,zero);p=defaults;p[3]=200000;assert(FusionConvSet(d.state,p.data(),10));std::fill(wl.begin(),wl.end(),.1f);std::fill(wr.begin(),wr.end(),.1f);d.run(wl,wr,zero,zero);assert(wl[0]<.1f&&wl[0]>.099f);assert(wl[719]==0&&wl.back()==0);
    std::puts("Convolver smoothing: 15ms gain/mix/routing and fixed-tap delay transitions, rapid overlapping edits, exact neutral restoration, block 1/511 equality and no C++ callback allocations passed.");
}
constexpr effect_uuid_t uuid{0x30306067,0x9400,0x4d53,0x9000,{0,0,0,0,0,1}};
struct Engine {
    effect_handle_t h=nullptr;
    audio_effect_library_t* library;
    Engine(audio_effect_library_t* lib=&AELI,unsigned fs=48000,bool hostConfigured=true):library(lib){assert(library->create_effect(&uuid,0,0,&h)==0);if(hostConfigured){configure(fs);assert(command(EFFECT_CMD_ENABLE)==0);}assert(set(131073,0)==0);}
    ~Engine(){library->release_effect(h);}
    int command(uint32_t id,uint32_t n=0,void* p=nullptr){int32_t status=0;uint32_t bytes=4;auto r=(*h)->command(h,id,n,p,&bytes,&status);return r?r:status;}
    int raw(uint32_t id,const void* data,unsigned n){std::vector<uint32_t> p((16+n+3)/4);p[1]=4;p[2]=n;p[3]=id;std::memcpy(p.data()+4,data,n);return command(EFFECT_CMD_SET_PARAM,16+n,p.data());}
    int set(uint32_t id,int32_t v){return raw(id,&v,4);}
    void configure(unsigned fs){effect_config_t config{};config.inputCfg.samplingRate=config.outputCfg.samplingRate=fs;config.inputCfg.channels=config.outputCfg.channels=AUDIO_CHANNEL_OUT_STEREO;config.inputCfg.format=config.outputCfg.format=AUDIO_FORMAT_PCM_FLOAT;config.inputCfg.accessMode=EFFECT_BUFFER_ACCESS_READ;config.outputCfg.accessMode=EFFECT_BUFFER_ACCESS_WRITE;config.inputCfg.mask=config.outputCfg.mask=EFFECT_CONFIG_ALL;assert(command(EFFECT_CMD_SET_CONFIG,sizeof(config),&config)==0);}
    std::array<int32_t,32> get(){std::array<uint32_t,4> req{0,4,128,131213};std::array<uint32_t,36> res{};uint32_t n=sizeof(res);assert((*h)->command(h,EFFECT_CMD_GET_PARAM,16,req.data(),&n,res.data())==0);assert(n==144&&res[0]==0);std::array<int32_t,32> r{};std::memcpy(r.data(),res.data()+4,128);return r;}
    void ir(std::vector<float> values,int channels){const unsigned chunks=(values.size()+4095)/4096;std::array<int32_t,4> head{int32_t(values.size()),channels,0,int32_t(chunks)};assert(raw(9999,head.data(),16)==0);values.resize(chunks*4096);for(unsigned i=0;i<chunks;++i)assert(raw(12000,values.data()+i*4096,16384)==0);int16_t yes=1;assert(raw(10004,&yes,2)==0);assert(raw(1205,&yes,2)==0);}
    std::vector<float> render(const std::vector<float>& x,size_t block=509){std::vector<float> y(x.size());for(size_t i=0;i<x.size()/2;i+=block){audio_buffer_t a{},b{};a.frameCount=b.frameCount=std::min(block,x.size()/2-i);a.f32=const_cast<float*>(x.data()+2*i);b.f32=y.data()+2*i;assert((*h)->process(h,&a,&b)==0);}return y;}
};
void bridge(){
    Engine e;assert(e.get()[0]==1&&e.get()[4]==0&&e.get()[6]==0);e.ir({.5f},1);auto r=e.get();assert(r[4]==1&&r[5]==1&&(r[3]&7)==7&&!(r[1]&1));
    const auto loadedCrc=r[6];assert(e.set(25003,1729)==0);assert(e.get()[6]==loadedCrc);
    auto p=defaults;p[0]=500;assert(e.raw(1400,p.data(),40)==0);
    std::vector<float> x(12000);for(size_t i=0;i<x.size();++i)x[i]=.08f*std::sin(i*.031f);
    auto y=e.render(x);for(size_t i=2000;i<x.size();++i)assert(std::abs(y[i]-.75f*x[i])<3e-7);
    r=e.get();assert(r[3]&8);assert(e.set(1401,1)==0);y=e.render(x);for(size_t i=2000;i<x.size();++i)assert(y[i]==x[i]);
    assert(e.command(EFFECT_CMD_DISABLE)==0);r=e.get();assert(!(r[3]&12)&&r[10]==0);for(unsigned i=19;i<=30;++i)assert(r[i]==missing);
    assert(e.command(EFFECT_CMD_ENABLE)==0);assert(e.set(1401,1)==0);assert(e.get()[10]==0);
    assert(e.set(1401,0)==0);assert(e.set(1401,3)==0);y=e.render(x);for(size_t i=2000;i<x.size();++i)assert(std::abs(y[i]+.25f*x[i])<3e-7);
    p[9]=1;assert(e.raw(1400,p.data(),40)==-EINVAL);assert(e.raw(1400,defaults.data(),36)==-EINVAL);assert(e.set(1401,4)==-EINVAL);assert(e.set(131213,0)==-EINVAL);assert(e.command(FUSION_CONV_COMMIT)==-EINVAL);
    assert(e.set(131072,0)==0);r=e.get();assert(!(r[3]&12)&&r[10]==0);
    std::puts("Convolver bridge: real loaded metadata/CRC, module-local mix, auditions, read-only bulk, strict protocol, private command isolation and master-off invalidation passed.");
}
void concurrentPreparation(){
    Engine e;e.ir({.5f},1);std::atomic<bool> done=false;std::atomic<unsigned> callbacks=0,reads=0;
    std::thread audio([&]{std::vector<float> input(1024,.01);while(!done){const auto out=e.render(input,512);for(float v:out)assert(std::isfinite(v)&&std::abs(v)<1);++callbacks;std::this_thread::yield();}});
    std::thread monitor([&]{while(!done){std::array<uint32_t,4> req{0,4,128,131213};std::array<uint32_t,36> res{};uint32_t n=sizeof(res);const auto status=(*e.h)->command(e.h,EFFECT_CMD_GET_PARAM,16,req.data(),&n,res.data());assert(status==0||status==-EAGAIN);if(!status){assert(n==144&&res[4]==1&&!(res[5]&1));++reads;}std::this_thread::yield();}});
    for(unsigned iteration=0;iteration<8;++iteration){
        std::vector<float> ir((48000+iteration*509)*2);ir[0]=.4;ir[1]=.3;ir[ir.size()-2]=.05;ir[ir.size()-1]=-.03;e.ir(ir,2);
        auto p=defaults;p[0]=700+iteration;p[3]=iteration*23;assert(e.raw(1400,p.data(),40)==0);
    }
    done=true;audio.join();monitor.join();assert(callbacks>0&&reads>0);assert(e.get()[5]==48000+7*509);
    std::printf("Convolver concurrent prepare: 8 large IR swaps, %u callbacks and %u nonblocking coherent bulk reads passed.\n",unsigned(callbacks),unsigned(reads));
}
int32_t workCrc(const std::vector<float>& samples){
    int32_t crc=-1;
    for(float sample:samples){int32_t bits;std::memcpy(&bits,&sample,4);crc^=bits;for(unsigned bit=0;bit<8;++bit)crc=(crc>>1)^(int32_t(0xEDB88320u)&-(crc&1));}
    return ~crc;
}
void stagedAndMutedKernels(){
    unsigned cases=0;
    for(unsigned channels:{1u,2u,4u})for(unsigned frames:{1u,16383u,17832u,18824u,48000u}){
        Engine e(&AELI,48000,false);std::vector<float> silent(frames*channels);
        e.ir(silent,channels);auto status=e.get();
        assert(status[2]==0 && (status[3]&1) && !(status[3]&12));
        assert(status[4]==int32_t(channels) && status[5]==int32_t(frames) && status[6]==workCrc(silent));
        if(frames==18824 && channels==2)assert(status[6]==345625875); // Exact failing device hash.
        e.configure(48000);assert(e.command(EFFECT_CMD_ENABLE)==0);
        const auto output=e.render(std::vector<float>(12000,.02f));
        for(float sample:output)assert(sample==0.f);
        status=e.get();assert(status[2]==48000 && (status[3]&7)==7 && status[6]==workCrc(silent));
        std::vector<float> live(frames*channels);live[0]=.5f; // Remaining paths stay muted.
        e.ir(live,channels);status=e.get();assert(status[5]==int32_t(frames) && status[6]==workCrc(live));
        // A new valid silent kernel replaces the old plan without changing the declared length.
        e.ir(silent,channels);status=e.get();assert(status[5]==int32_t(frames) && status[6]==workCrc(silent));
        const auto replaced=e.render(std::vector<float>(12000,.02f));for(float sample:replaced)assert(sample==0.f);
        ++cases;
    }
    Engine canceled;std::array<int32_t,4> header{32766,2,0,8};std::array<float,4096> chunk{};
    assert(canceled.raw(9999,header.data(),16)==0 && canceled.raw(12000,chunk.data(),16384)==0);
    int16_t yes=1;assert(canceled.raw(10004,&yes,2)==-EINVAL);
    std::vector<float> latest(18824*2);canceled.ir(latest,2);assert(canceled.get()[6]==345625875);
    std::printf("Convolver IR staging: %u no-stream long/silent and muted-path kernels retain original frames/CRC, wake with zero output, replace plans and restart incomplete transfers.\n",cases);
}
void stagedHighRateKernels(){
    unsigned cases=0;
    for(unsigned fs:{48000u,96000u,192000u,384000u,768000u})for(unsigned channels:{2u,4u}){
        const unsigned frames=unsigned(std::llround(16383.*fs/44100.));
        Engine e(&AELI,48000,false);std::vector<float> silent(frames*channels);
        e.ir(silent,channels);auto status=e.get();
        assert(status[2]==0&&(status[3]&1)&&status[5]==int(frames)&&status[6]==workCrc(silent));
        e.configure(fs);assert(e.command(EFFECT_CMD_ENABLE)==0);
        auto output=e.render(std::vector<float>(2*(fs/50+4096),.01f));for(float x:output)assert(x==0.f);
        status=e.get();assert(status[2]==int(fs)&&status[5]==int(frames)&&status[6]==workCrc(silent));
        std::vector<float> live(frames*channels);live[0]=.5f;
        e.ir(live,channels);status=e.get();assert(status[5]==int(frames)&&status[6]==workCrc(live));
        ++cases;
    }
    std::printf("Convolver HiFi: %u staged2/4ch long kernels corresponding44.1k16383frames ->48..768k, padded4096float transfer, immediate workCRC and actual-Fs wake passed.\n",cases);
}
void frozen(const char* reference){
#ifdef _WIN32
    const auto module=LoadLibraryA(reference);assert(module);auto* old=reinterpret_cast<audio_effect_library_t*>(GetProcAddress(module,"AELI"));assert(old);
    unsigned scenarios=0;std::mt19937 rng(51);std::uniform_real_distribution<float> random(-.025,.025);
    for(unsigned fs:{8000u,44100u,48000u,96000u,192000u})for(unsigned channels:{1u,2u,4u})for(unsigned frames:{1u,127u,4097u})for(unsigned block:{509u,960u}){
        Engine previous(old,fs),current(&AELI,fs);std::vector<float> ir(frames*channels),input(24000);for(float& v:ir)v=random(rng);for(float& v:input)v=random(rng);previous.ir(ir,channels);current.ir(ir,channels);
        const auto a=previous.render(input,block),b=current.render(input,block);assert(std::memcmp(a.data(),b.data(),a.size()*4)==0);++scenarios;
    }
    std::printf("Convolver frozen baseline: %u mono/stereo/matrix, sample-rate, IR length and streaming configurations match previous complete driver bit-for-bit.\n",scenarios);FreeLibrary(module);
#else
    (void)reference;
#endif
}
}
int main(int argc,char** argv){core();auditionAndMeters();smoothing();bridge();concurrentPreparation();stagedAndMutedKernels();stagedHighRateKernels();if(argc>1)frozen(argv[1]);}
