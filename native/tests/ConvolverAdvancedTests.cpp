#include "../convolver/ConvolverAdvanced.h"
#include "essential.h"
#include <cassert>
#include <cstdio>
#include <cstring>
#include <vector>
#include <atomic>
#include <thread>
#include <new>
#include <cstdlib>
extern "C" audio_effect_library_t AELI;
thread_local bool watchNew=false;
thread_local unsigned newCount=0;
void* operator new(std::size_t n){if(watchNew)++newCount;if(auto* p=std::malloc(n))return p;throw std::bad_alloc();}
void* operator new[](std::size_t n){return ::operator new(n);}
void operator delete(void* p)noexcept{std::free(p);}
void operator delete[](void* p)noexcept{std::free(p);}
void operator delete(void* p,std::size_t)noexcept{std::free(p);}
void operator delete[](void* p,std::size_t)noexcept{std::free(p);}
namespace {
constexpr double pi=3.14159265358979323846;
struct State {
    void* p=FusionConvCreate();
    explicit State(unsigned fs){assert(p);FusionConvConfigure(p,fs);FusionConvLoaded(p,2,1,1);FusionConvEnabled(p,1);}
    ~State(){FusionConvDestroy(p);}
    void set(const std::array<int32_t,19>& a){assert(FusionConvSetAdvanced(p,a.data(),a.size()));}
    std::vector<float> render(const std::vector<float>& input,unsigned block=511) {
        auto l=input,r=input;
        for(size_t i=0;i<input.size();i+=block){const auto n=std::min(size_t(block),input.size()-i);watchNew=true;
            FusionConvBegin(p,input.data()+i,input.data()+i,n);FusionConvEnd(p,l.data()+i,r.data()+i,n);watchNew=false;assert(!newCount);}
        return l;
    }
};
void filters() {
    unsigned cases=0;
    for(unsigned fs:{8000u,44100u,48000u,96000u,192000u})for(unsigned kind=0;kind<2;++kind)for(unsigned slope=0;slope<3;++slope)for(double f:{100.,300.,2000.}) {
        State s(fs);auto p=convolution::advancedDefaults;const auto on=kind==0?1:4;p[on]=1;p[on+1]=kind==0?300:1000;p[on+2]=slope;s.set(p);
        std::vector<float> x(fs/2);for(size_t n=0;n<x.size();++n)x[n]=.01f*std::sin(2*pi*f*n/fs);
        const auto y=s.render(x);double xx=0,yy=0;for(size_t n=fs/4;n<x.size();++n){xx+=double(x[n])*x[n];yy+=double(y[n])*y[n];}
        const double ratio=std::tan(pi*f/fs)/std::tan(pi*p[on+1]/fs),power=std::pow(ratio,2*(2u<<slope));
        const double expected=kind==0?std::sqrt(power/(1+power)):1/std::sqrt(1+power);
        assert(std::abs(std::sqrt(yy/xx)-expected)<.002);++cases;
    }
    for(unsigned kind:{7u,10u}){State s(48000);auto p=convolution::advancedDefaults;p[kind]=1;p[kind+2]=60;s.set(p);
        std::vector<float> x(48000,.01);if(kind==10)for(size_t n=1;n<x.size();n+=2)x[n]=-.01;
        const auto y=s.render(x);for(size_t n=30000;n<x.size();++n)assert(std::abs(y[n]/x[n]-std::pow(10.,.3))<.001);}
    State s(8000);auto p=convolution::advancedDefaults;p[4]=1;p[5]=20000;p[10]=1;p[11]=16000;s.set(p);std::array<int32_t,20> status{};FusionConvAdvancedSnapshot(s.p,status.data());assert(status[11]==3600 && status[13]==3600);
    const auto before=status;
    for(unsigned i=0;i<19;++i){auto invalid=p;invalid[i]=INT32_MAX;assert(!FusionConvSetAdvanced(s.p,invalid.data(),19));FusionConvAdvancedSnapshot(s.p,status.data());assert(status==before);}
    assert(!FusionConvSetAdvanced(s.p,p.data(),18));
    std::printf("Convolution round two: %u independent bilinear Butterworth tone checks, both shelf limits, Fs clamps and atomic strict packets passed.\n",cases);
}
void alignment() {
    for(unsigned delay:{1u,16u})for(unsigned fs:{8000u,48000u,192000u}) {
        State s(fs);auto advanced=convolution::advancedDefaults;advanced[18]=delay;s.set(advanced);
        std::array<int32_t,32> pending{};FusionConvSnapshot(s.p,pending.data());assert(pending[9]==0);
        FusionConvLoaded(s.p,2,1,2);
        std::array<int32_t,10> original{0,0,0,0,0,0,0,0,0,0};assert(FusionConvSet(s.p,original.data(),10));
        s.render(std::vector<float>(fs/20));std::vector<float> x(100);x[0]=.03;const auto y=s.render(x,1);
        for(unsigned i=0;i<100;++i)assert(y[i]==(i==delay?.03f:0.f));
        std::array<int32_t,32> snapshot{};FusionConvSnapshot(s.p,snapshot.data());assert(snapshot[9]==int32_t(delay));
    }
    auto render=[](unsigned block){State s(48000);std::vector<float> result;auto p=convolution::advancedDefaults;
        for(unsigned edit=0;edit<32;++edit){p[1]=edit%2;p[2]=20+edit*25;p[3]=edit%3;p[17]=int(edit)-16;s.set(p);
            std::vector<float> x(257);for(unsigned i=0;i<257;++i)x[i]=.01f*std::sin((edit*257+i)*.07f);
            const auto y=s.render(x,block);result.insert(result.end(),y.begin(),y.end());}return result;};
    assert(render(1)==render(511));
    State quick(48000);auto hp=convolution::advancedDefaults;quick.render(std::vector<float>(2000,.01));hp[1]=1;hp[2]=300;quick.set(hp);
    const auto before=quick.render(std::vector<float>(300,.01));hp[1]=0;hp[4]=1;quick.set(hp);
    const auto after=quick.render(std::vector<float>(1000,.01));assert(std::abs(after.front()-before.back())<.0001);
    std::puts("Convolution round two: dry/bypass common-latency impulses, rapid filter/compensation edits, block 1/511 equality and zero C++ callback allocations passed.");
}
constexpr effect_uuid_t uuid{0x30306067,0x9400,0x4d53,0x9000,{0,0,0,0,0,1}};
struct Engine {
    effect_handle_t h=nullptr;
    explicit Engine(unsigned fs=48000){assert(AELI.create_effect(&uuid,0,0,&h)==0);effect_config_t c{};c.inputCfg.samplingRate=c.outputCfg.samplingRate=fs;c.inputCfg.channels=c.outputCfg.channels=AUDIO_CHANNEL_OUT_STEREO;c.inputCfg.format=c.outputCfg.format=AUDIO_FORMAT_PCM_FLOAT;c.inputCfg.accessMode=EFFECT_BUFFER_ACCESS_READ;c.outputCfg.accessMode=EFFECT_BUFFER_ACCESS_WRITE;c.inputCfg.mask=c.outputCfg.mask=EFFECT_CONFIG_ALL;assert(command(EFFECT_CMD_SET_CONFIG,sizeof(c),&c)==0);int32_t off=0;assert(raw(131073,&off,4)==0);assert(command(EFFECT_CMD_ENABLE)==0);}
    ~Engine(){AELI.release_effect(h);}
    int command(uint32_t id,uint32_t n=0,void* p=nullptr){int32_t status=0;uint32_t bytes=4;const auto r=(*h)->command(h,id,n,p,&bytes,&status);return r?r:status;}
    int raw(uint32_t id,const void* data,unsigned n){std::vector<uint32_t> packet((16+n+3)/4);packet[1]=4;packet[2]=n;packet[3]=id;std::memcpy(packet.data()+4,data,n);return command(EFFECT_CMD_SET_PARAM,16+n,packet.data());}
    void ir(std::vector<float> x){const unsigned count=(x.size()+4095)/4096;std::array<int32_t,4> header{int32_t(x.size()),1,0,int32_t(count)};assert(raw(9999,header.data(),16)==0);x.resize(count*4096);for(unsigned i=0;i<count;++i)assert(raw(12000,x.data()+4096*i,16384)==0);int16_t on=1;assert(raw(10004,&on,2)==0);assert(raw(1205,&on,2)==0);}
    void settings(const std::array<int32_t,19>& p){assert(raw(1402,p.data(),76)==0);}
    std::array<int32_t,20> get(){std::array<uint32_t,4> request{0,4,80,131214};std::array<uint32_t,24> response{};uint32_t size=96;assert((*h)->command(h,EFFECT_CMD_GET_PARAM,16,request.data(),&size,response.data())==0);assert(size==96 && !response[0]);std::array<int32_t,20> result{};std::memcpy(result.data(),response.data()+4,80);return result;}
    std::vector<float> render(const std::vector<float>& x,unsigned block=509){std::vector<float> output(x.size());for(size_t i=0;i<x.size()/2;i+=block){audio_buffer_t a{},b{};a.frameCount=b.frameCount=std::min(size_t(block),x.size()/2-i);a.f32=const_cast<float*>(x.data()+2*i);b.f32=output.data()+2*i;assert((*h)->process(h,&a,&b)==0);}return output;}
};
void transitions() {
    for(unsigned fs:{8000u,48000u,192000u})for(int mode:{1,2}) {
        Engine e(fs);const unsigned actualRate=e.get()[2];std::vector<float> ir(actualRate/10+1);ir[0]=.5;ir.back()=.3;e.ir(ir);
        auto p=convolution::advancedDefaults;p[13]=mode;p[14]=5000;p[15]=200;p[16]=10000;e.settings(p);
        std::vector<float> before(fs/10);before[0]=before[1]=.02;e.render(before);
        e.ir({.75f});auto snapshot=e.get();assert(snapshot[3]==2 && snapshot[5]==mode && snapshot[8]==int32_t(ir.size()));
        const auto output=e.render(std::vector<float>(fs/2));const unsigned at=fs/20;
        if(fs==actualRate) {
            for(unsigned n=0;n<output.size()/2;++n)assert(std::abs(output[2*n]-(mode==2 && n==at?.006f:0.f))<1e-6);
        } else {
            double sum=0;unsigned peak=0;for(unsigned n=0;n<output.size()/2;++n){sum+=output[2*n];if(std::abs(output[2*n])>std::abs(output[2*peak]))peak=n;}
            assert(std::abs(sum-(mode==2?.006:0.))<.0001);
            if(mode==2)assert(std::abs(int(peak)-int(at))<128);
        }
        snapshot=e.get();assert(snapshot[3]==1 && snapshot[5]==0 && !snapshot[7]);
    }
    for(int mode:{1,2}){Engine sameEngine;sameEngine.ir({.5f});sameEngine.render(std::vector<float>(3000,.02));auto parameters=convolution::advancedDefaults;parameters[13]=mode;parameters[14]=500000;sameEngine.settings(parameters);sameEngine.ir({.5f});
        const auto same=sameEngine.render(std::vector<float>(60000,.02));for(float v:same)assert(std::abs(v-.01f)<2e-7);}
    Engine e;e.ir({.5f});auto p=convolution::advancedDefaults;
    p[13]=2;p[14]=5000;e.settings(p);
    std::atomic<bool> done=false;std::thread audio([&]{while(!done)e.render(std::vector<float>(1024,.001));});
    std::thread monitor([&]{while(!done){std::array<uint32_t,4> q{0,4,80,131214};std::array<uint32_t,24> r{};uint32_t size=96;
        const auto status=(*e.h)->command(e.h,EFFECT_CMD_GET_PARAM,16,q.data(),&size,r.data());assert(status==0 || status==-EAGAIN);
        if(!status){assert(r[4]==1 && !(r[5]&1) && r[7]>=1 && r[7]<=2);}}});
    for(unsigned n=0;n<8;++n){std::vector<float> h(4000+n*101);h[0]=.5;h.back()=.01;e.ir(h);}
    done=true;audio.join();monitor.join();assert(e.get()[3]<=2);
    assert(e.command(EFFECT_CMD_DISABLE)==0);assert(e.get()[3]==1);
    auto invalid=p;invalid[0]=0;assert(e.raw(1402,invalid.data(),76)==-EINVAL);assert(e.raw(1402,p.data(),72)==-EINVAL);
    std::puts("Convolution round two: real dual-plan crossfades through 8/48/192k host rates (actual James Fs respected), old impulse tails, identical-IR no-rise, rapid concurrent swaps and off/retirement cleanup passed.");
}
}
int main(){filters();alignment();transitions();}
