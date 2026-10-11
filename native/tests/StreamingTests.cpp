#include <algorithm>
#include <array>
#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>
#include "essential.h"
#ifdef NDEBUG
#error Audio regression tests must be built with assertions enabled.
#endif

extern "C" audio_effect_library_t AELI;
#ifdef DSP_FUSION_TEST_ALLOCATIONS
extern "C" void JamesDSPTestFailIRAllocationAfter(int successfulAllocations);
#endif
namespace {
constexpr effect_uuid_t uuid{0x30306067,0x9400,0x4d53,0x9000,{0,0,0,0,0,1}};
struct Engine {
    effect_handle_t handle=nullptr;
    Engine(bool james,bool viper,uint32_t rate=48000) {
        assert(AELI.create_effect(&uuid,0,0,&handle)==0);
        effect_config_t config{};
        config.inputCfg.samplingRate=config.outputCfg.samplingRate=rate;
        config.inputCfg.channels=config.outputCfg.channels=AUDIO_CHANNEL_OUT_STEREO;
        config.inputCfg.format=config.outputCfg.format=AUDIO_FORMAT_PCM_FLOAT;
        config.inputCfg.accessMode=EFFECT_BUFFER_ACCESS_READ;
        config.outputCfg.accessMode=EFFECT_BUFFER_ACCESS_WRITE;
        config.inputCfg.mask=config.outputCfg.mask=EFFECT_CONFIG_ALL;
        assert(command(EFFECT_CMD_SET_CONFIG,sizeof(config),&config)==0);
        assert(set(131072,int32_t(james))==0 && set(131073,int32_t(viper))==0);
        assert(command(EFFECT_CMD_ENABLE)==0);
    }
    ~Engine() { AELI.release_effect(handle); }
    int command(uint32_t code,uint32_t size=0,void* data=nullptr) {
        int32_t status=0;uint32_t replySize=4;
        auto ret=(*handle)->command(handle,code,size,data,&replySize,&status);
        return ret?ret:status;
    }
    int raw(uint32_t id,const void* values,size_t n) {
        std::vector<uint32_t> storage((sizeof(effect_param_t)+4+n+3)/4);
        auto* p=reinterpret_cast<effect_param_t*>(storage.data());
        p->psize=4;p->vsize=n;std::memcpy(p->data,&id,4);std::memcpy(p->data+4,values,n);
        return command(EFFECT_CMD_SET_PARAM,sizeof(effect_param_t)+4+n,p);
    }
    template<class T> int set(uint32_t id,T value) { return raw(id,&value,sizeof(value)); }
    std::vector<float> render(const std::vector<float>& input,size_t block) {
        std::vector<float> output(input.size());
        for(size_t frame=0;frame<input.size()/2;frame+=block) {
            audio_buffer_t in{},out{};in.frameCount=out.frameCount=std::min(block,input.size()/2-frame);
            in.f32=const_cast<float*>(input.data()+frame*2);out.f32=output.data()+frame*2;
            assert((*handle)->process(handle,&in,&out)==0);
        }
        return output;
    }
    void prepareImpulse(const std::vector<float>& samples,int channels) {
        const int32_t parts=(samples.size()+4095)/4096;
        const std::array<int32_t,4> info{int32_t(samples.size()),channels,0,parts};
        assert(raw(9999,info.data(),sizeof(info))==0);
        for(int32_t part=0;part<parts;++part) {
            std::array<float,4096> block{};
            const auto count=std::min(size_t(4096),samples.size()-part*4096);
            std::copy_n(samples.data()+part*4096,count,block.data());
            assert(raw(12000,block.data(),sizeof(block))==0);
        }
    }
    void impulse(const std::vector<float>& samples,int channels) {
        prepareImpulse(samples,channels);
        assert(set(10004,int16_t(1))==0);
        assert(set(1205,int16_t(1))==0);
    }
};
double energy(const std::vector<float>& x) { double sum=0;for(float s:x){assert(std::isfinite(s));sum+=double(s)*s;}return sum; }
double error(const std::vector<float>& a,const std::vector<float>& b) {
    assert(a.size()==b.size());double max=0;
    for(size_t i=0;i<a.size();++i) {assert(std::isfinite(a[i])&&std::isfinite(b[i]));max=std::max(max,std::abs(double(a[i])-b[i]));}
    return max;
}
std::vector<float> signal(size_t frames,bool leftOnly=false) {
    std::vector<float> x(frames*2);
    for(size_t i=0;i<frames;++i) {
        x[i*2]=.03f*std::sin(i*.117f)+.02f*std::sin(i*.531f);
        x[i*2+1]=leftOnly?0.f:.025f*std::cos(i*.079f);
    }
    return x;
}
void testConvolutionStreaming() {
    // Stereo, odd-length kernel spanning many packets, matching the structure
    // of the reported 44.1 kHz IRS without including anyone's personal file.
    constexpr size_t frames=16383;
    std::vector<float> ir(frames*2);
    ir[0]=.4f;ir[1]=.3f;ir[777*2]=.2f;ir[777*2+1]=-.1f;
    ir[8000*2]=.01f;ir[8000*2+1]=.02f;
    const auto input=signal(30000);
    std::vector<float> expected(input.size());
    for(size_t i=0;i<input.size()/2;++i) for(size_t c=0;c<2;++c) {
        expected[i*2+c]=input[i*2+c]*ir[c];
        for(size_t delay:{777u,8000u}) if(i>=delay)
            expected[i*2+c]+=input[(i-delay)*2+c]*ir[delay*2+c];
    }
    Engine regular(true,false),phoneBlock(true,false);
    regular.impulse(ir,2);phoneBlock.impulse(ir,2);
    auto a=regular.render(input,512),b=phoneBlock.render(input,960);
    std::printf("IR streaming max error vs FIR: %.9g / %.9g\n",error(a,expected),error(b,expected));
    assert(error(a,expected)<3e-5 && error(b,expected)<3e-5);
    // Mono upload and true stereo cross-channel mapping also exercise updates
    // on an existing instance instead of only its first IR allocation.
    phoneBlock.impulse({.5f},1);
    auto mono=phoneBlock.render(input,960);
    for(size_t i=0;i<input.size();++i) assert(std::abs(mono[i]-.5f*input[i])<3e-5);
    phoneBlock.impulse({0.f,1.f,1.f,0.f},4);
    auto swap=phoneBlock.render(input,960);
    for(size_t i=0;i<input.size();i+=2) {
        assert(std::abs(swap[i]-input[i+1])<3e-5);
        assert(std::abs(swap[i+1]-input[i])<3e-5);
    }
}
void testCrossfeedStreaming() {
    const auto input=signal(16384,true);
    for(int16_t mode=0;mode<6;++mode) {
        Engine a(true,false),b(true,false);
        for(auto* engine:{&a,&b}) {
            assert(engine->set(1208,int16_t(1))==0);
            assert(engine->set(188,int16_t(1))==0);
            assert(engine->set(188,mode)==0); // zero must initialize the used filter.
        }
        const auto regular=a.render(input,512),phone=b.render(input,960);
        double right=0;for(size_t i=1;i<phone.size();i+=2) right+=double(phone[i])*phone[i];
        std::printf("Crossfeed %d: energy %.9g / right %.9g / chunk error %.9g\n",mode,energy(phone),right,error(regular,phone));
        assert(energy(phone)>1e-5 && right>1e-7);
        assert(error(regular,phone)<3e-5);
    }
}
#ifdef DSP_FUSION_TEST_ALLOCATIONS
void testImpulseAllocationFailures() {
    const auto input=signal(2048);
    Engine engine(true,false);
    // Fail storage, channel-pointer array, each channel, and the FFT object.
    // The production Android library has no fault-injection hook.
    for(int channels:{1,2,4}) for(int failAfter=0;failAfter<channels+3;++failAfter) {
        engine.impulse({.5f},1);
        engine.prepareImpulse(std::vector<float>(channels,.25f),channels);
        JamesDSPTestFailIRAllocationAfter(failAfter);
        assert(engine.set(10004,int16_t(1))==-ENOMEM);
        JamesDSPTestFailIRAllocationAfter(-1);
        assert(engine.set(1205,int16_t(1))==0);
        assert(error(engine.render(input,960),input)<1e-7);
        engine.impulse({.5f},1);
        const auto recovered=engine.render(input,960);
        for(size_t i=0;i<input.size();++i) assert(std::abs(recovered[i]-.5f*input[i])<1e-7);
    }
    std::puts("IR loader allocation-failure injection passed (mono/stereo/four-channel, dry fallback and retry).");
}
#endif
void testRemovedEffectsAndBalance() {
    const auto input=signal(8192);
    Engine engine(false,true);
    // No residual ViPER limiter delay, gain, equalizer, or speaker filtering.
    assert(engine.render(input,960)==input);
    for(uint32_t id:{65551u,65552u,65586u,65587u,65588u,65603u}) assert(engine.set(id,int32_t(0))==-ENOSYS);
    assert(engine.set(131074,int32_t(101))==-EINVAL);
    for(int32_t viper:{0,1}) for(int32_t pan:{-100,0,100}) {
        assert(engine.set(131073,viper)==0 && engine.set(131074,pan)==0);
        const auto out=engine.render(input,960);
        for(size_t i=7000;i<input.size()/2;++i) {
            const float left=pan>0?0.f:input[i*2],right=pan<0?0.f:input[i*2+1];
            assert(std::abs(out[i*2]-left)<1e-6 && std::abs(out[i*2+1]-right)<1e-6);
        }
    }
    assert(engine.set(131074,int32_t(0))==0);engine.render(input,960);
    assert(engine.render(input,960)==input);
}
void testPeqProtocolAndChainOrder() {
    Engine engine(false,false);
    const std::array<int32_t,8> peq{1,1,1,0,1,1000000,12000,1000};
    assert(engine.raw(1250,peq.data(),sizeof(peq))==0);
    auto malformed=peq;malformed[2]=170;
    assert(engine.raw(1250,malformed.data(),sizeof(malformed))==-EINVAL);
    assert(engine.raw(1250,peq.data(),sizeof(peq)-1)==-EINVAL);
    constexpr double pi=3.14159265358979323846;
    std::vector<float> input(48000*2);
    for(size_t i=0;i<input.size()/2;++i) input[i*2]=input[i*2+1]=float(.01*std::sin(2*pi*1000*i/48000));
    const auto boosted=engine.render(input,960);
    double dry=0,wet=0;
    for(size_t i=24000*2;i<input.size();++i) {dry+=double(input[i])*input[i];wet+=double(boosted[i])*boosted[i];}
    assert(std::abs(10*std::log10(wet/dry)-12)<.002);
    assert(engine.set(131072,int32_t(1))==0);
    const std::array<float,3> limiter{-6.f,60.f,0.f};
    assert(engine.raw(1500,limiter.data(),sizeof(limiter))==0);
    for(auto& s:input) s*=20;
    const auto limited=engine.render(input,960);
    // If PEQ ran after James, the +12 dB peak could escape the -6 dB limiter.
    for(float s:limited) assert(std::isfinite(s)&&std::abs(s)<=.502f);
    const std::array<int32_t,3> off{1,0,0};assert(engine.raw(1250,off.data(),sizeof(off))==0);
    std::puts("PEQ wire validation, independent stage and placement before James limiter passed.");
}
void testAnalogAndTube() {
    const auto input=signal(16384);
    for(uint32_t rate:{44100u,48000u,96000u}) {
        std::array<std::vector<float>,3> modes;
        for(int32_t mode=0;mode<3;++mode) {
            Engine engine(false,true,rate);
            assert(engine.set(65585,mode)==0 && engine.set(65584,int32_t(1))==0);
            modes[mode]=engine.render(input,960);
            // No quarter-second mute after enable/reset; zero means the first
            // analogue model, not a zero gain or disabled effect.
            assert(energy(std::vector<float>(modes[mode].begin(),modes[mode].begin()+960))>1e-7);
            assert(error(modes[mode],input)>1e-4 && energy(modes[mode])>1e-5);
        }
        assert(error(modes[0],modes[1])>1e-5 && error(modes[1],modes[2])>1e-5);
        Engine tube(false,true,rate);assert(tube.set(65583,int32_t(1))==0);
        auto processed=tube.render(input,960);
        assert(error(processed,input)>1e-4 && energy(processed)>1e-5);
    }
    Engine james(true,false);
    const std::array<float,3> limiter{-6.f,60.f,0.f};
    assert(james.raw(1500,limiter.data(),sizeof(limiter))==0);
    std::vector<float> loud(4096,2.f);const auto limited=james.render(loud,960);
    for(float s:limited) assert(std::isfinite(s) && std::abs(s)<=.502f);
}
}
void runStreamingTests() {
    std::setvbuf(stdout,nullptr,_IONBF,0);
#ifdef DSP_FUSION_TEST_ALLOCATIONS
    testImpulseAllocationFailures();
#endif
    testConvolutionStreaming();testCrossfeedStreaming();testRemovedEffectsAndBalance();testAnalogAndTube();testPeqProtocolAndChainOrder();
    std::puts("Streaming, removed algorithms, balance, analog, and retained James limiter passed.");
}
