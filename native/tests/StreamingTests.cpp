#include <algorithm>
#include <array>
#include <atomic>
#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <thread>
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
    int get(uint32_t id) {
        std::array<uint32_t,5> packet{0,4,4,id,0},reply{};uint32_t bytes=sizeof(reply);
        assert(!(*handle)->command(handle,EFFECT_CMD_GET_PARAM,16,packet.data(),&bytes,reply.data()));
        assert(bytes==sizeof(reply) && reply[0]==0 && reply[1]==4 && reply[2]==4 && reply[3]==id);
        return static_cast<int32_t>(reply[4]);
    }
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
void testStatusPollingKeepsWetAudio() {
    Engine engine(true,false);
    engine.impulse({.5f},1);
    assert(engine.get(19998)==0 && engine.get(19998)==1);
    assert(engine.get(20001)==48000 && engine.get(20002)>0);
    assert(engine.set(25000,int32_t(1234567))==0 && engine.get(30000)==1234567);
    const auto input=signal(960);
    std::atomic<bool> stop{false};std::atomic<unsigned> ready{0},reads{0};
    std::array<std::thread,3> pollers;
    for(auto& poller:pollers)poller=std::thread([&]{
        ++ready;
        while(!stop.load(std::memory_order_relaxed))
            for(uint32_t id:{19999u,20000u,20001u,20002u,30000u,30001u,30002u,30003u,131241u,131080u,131081u}) {
                engine.get(id);reads.fetch_add(1,std::memory_order_relaxed);
            }
    });
    while(ready.load()!=pollers.size() || !reads.load())std::this_thread::yield();
    // A control-thread GET must never make the audio try_lock choose dry bypass.
    // A half-gain IR distinguishes that glitch from the committed wet stream.
    for(unsigned block=0;block<600;++block) {
        const auto output=engine.render(input,960);
        for(size_t i=0;i<input.size();++i)assert(std::abs(output[i]-.5f*input[i])<1e-7f);
    }
    stop.store(true,std::memory_order_relaxed);
    for(auto& poller:pollers)poller.join();
    assert(engine.get(19998)==2 && engine.get(30000)==1234567 && reads.load()>0);
    assert(!engine.command(EFFECT_CMD_INIT) && engine.get(19998)==3);
    assert(!engine.command(EFFECT_CMD_RESET) && engine.get(19998)==4);
    effect_config_t config{};
    config.inputCfg.samplingRate=config.outputCfg.samplingRate=44100;
    config.inputCfg.mask=config.outputCfg.mask=EFFECT_CONFIG_SMP_RATE;
    assert(!engine.command(EFFECT_CMD_SET_CONFIG,sizeof(config),&config));
    assert(engine.get(19998)==5 && engine.get(20001)==44100);
    std::printf("Concurrent legacy-status polling: %u reads, no wet/dry discontinuities.\n",reads.load());
}
void testRepeatedHostConfigKeepsHistory() {
    Engine reference(true,false),reconfigured(true,false);
    std::vector<float> ir(16383);ir[0]=.5f;ir[777]=.2f;ir[8000]=-.1f;
    reference.impulse(ir,1);reconfigured.impulse(ir,1);
    const auto input=signal(30000);const auto expected=reference.render(input,960);
    std::vector<float> output(input.size());
    effect_config_t config{};
    config.inputCfg.samplingRate=config.outputCfg.samplingRate=48000;
    config.inputCfg.channels=config.outputCfg.channels=AUDIO_CHANNEL_OUT_STEREO;
    config.inputCfg.format=config.outputCfg.format=AUDIO_FORMAT_PCM_FLOAT;
    config.inputCfg.accessMode=EFFECT_BUFFER_ACCESS_READ;
    config.outputCfg.accessMode=EFFECT_BUFFER_ACCESS_WRITE;
    config.inputCfg.mask=config.outputCfg.mask=EFFECT_CONFIG_ALL;
    unsigned configurations=0;
    for(size_t offset=0;offset<input.size()/2;offset+=960) {
        config.inputCfg.buffer.frameCount=config.outputCfg.buffer.frameCount=configurations++%2?512:960;
        assert(!reconfigured.command(EFFECT_CMD_SET_CONFIG,sizeof(config),&config));
        audio_buffer_t a{},b{};a.frameCount=b.frameCount=std::min(size_t(960),input.size()/2-offset);
        a.f32=const_cast<float*>(input.data()+2*offset);b.f32=output.data()+2*offset;
        assert(!(*reconfigured.handle)->process(reconfigured.handle,&a,&b));
    }
    assert(error(output,expected)<1e-7);
    std::printf("Repeated same-rate host configuration: %u changes, long-IR history error %.9g.\n",configurations,error(output,expected));
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
    // Prepared loading owns the incoming storage: fail channel-pointer array, each channel and FFT object.
    // The production Android library has no fault-injection hook.
    for(int channels:{1,2,4}) for(int failAfter=0;failAfter<channels+2;++failAfter) {
        engine.impulse({.5f},1);
        engine.prepareImpulse(std::vector<float>(channels,.25f),channels);
        JamesDSPTestFailIRAllocationAfter(failAfter);
        assert(engine.set(10004,int16_t(1))==-ENOMEM);
        JamesDSPTestFailIRAllocationAfter(-1);
        assert(engine.set(1205,int16_t(1))==0);
        const auto retained=engine.render(input,960);
        for(size_t i=0;i<input.size();++i)assert(std::abs(retained[i]-.5f*input[i])<1e-7);
        engine.impulse({.5f},1);
        const auto recovered=engine.render(input,960);
        for(size_t i=0;i<input.size();++i) assert(std::abs(recovered[i]-.5f*input[i])<1e-7);
    }
    std::puts("IR loader allocation-failure injection passed (mono/stereo/four-channel, retained committed IR and retry).");
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
    auto extreme=peq;extreme[6]=100000;extreme[7]=100000;
    assert(engine.raw(1250,extreme.data(),sizeof(extreme))==0);
    const auto extremeLimited=engine.render(input,960);
    for(float s:extremeLimited) assert(std::isfinite(s)&&std::abs(s)<=.502f);
    extreme[6]=-100000;assert(engine.raw(1250,extreme.data(),sizeof(extreme))==0);
    extreme[7]=100001;assert(engine.raw(1250,extreme.data(),sizeof(extreme))==-EINVAL);
    extreme[7]=100000;extreme[6]=-100001;
    assert(engine.raw(1250,extreme.data(),sizeof(extreme))==-EINVAL);
    std::vector<int32_t> full{1,1,170};
    for(int i=0;i<170;++i) full.insert(full.end(),{i%3,1,1000000,100000,100000});
    assert(full.size()==853 && engine.raw(1250,full.data(),uint32_t(full.size()*4))==0);
    const auto fullLimited=engine.render(input,960);
    for(float s:fullLimited) assert(std::isfinite(s)&&std::abs(s)<=.502f);
    const std::array<int32_t,3> off{1,0,0};assert(engine.raw(1250,off.data(),sizeof(off))==0);
    std::puts("PEQ +/-100 dB/Q100 wire validation, independent stage and placement before James limiter passed.");
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
void testSurroundLimiterOrder() {
    constexpr double pi=3.14159265358979323846;
    for(int mode:{0,1,2}) for(int delay:{20,210}) {
        Engine engine(true,false);
        const std::array<int32_t,6> surround{1,1,10,16000,500,delay};
        assert(engine.raw(196609,surround.data(),sizeof(surround))==0);
        const std::array<float,3> limiter{-6.f,60.f,0.f};
        assert(engine.raw(1500,limiter.data(),sizeof(limiter))==0);
        std::vector<float> input(48000*2);
        for(size_t frame=0;frame<input.size()/2;++frame) {
            const auto sample=float(.9*std::sin(2*pi*1000*frame/48000));
            input[frame*2]=sample;
            input[frame*2+1]=mode==0?sample:(mode==1?-sample:0.f);
        }
        const auto limited=engine.render(input,960);
        for(float sample:limited) assert(std::isfinite(sample)&&std::abs(sample)<=.502f);
        assert(energy(limited)>1e-5);
    }
    // Bypass James' limiter to exercise the final stereo-linked envelope.
    // A quiet, independently rendered copy provides the linear surround
    // waveform: clipping before that envelope would distort this shape even
    // if a later gain stage brought every output sample back below full scale.
    Engine finalOnly(false,false),quietReference(false,false);
    const std::array<int32_t,6> strongest{1,1,10,16000,500,20};
    for(auto* engine:{&finalOnly,&quietReference})
        assert(engine->raw(196609,strongest.data(),sizeof(strongest))==0);
    std::vector<float> input(48000*2);
    for(size_t frame=0;frame<input.size()/2;++frame) {
        input[frame*2]=float(.9*std::sin(2*pi*1000*frame/48000));
        input[frame*2+1]=-input[frame*2];
    }
    const auto limited=finalOnly.render(input,960);
    for(auto& sample:input)sample*=.0625f;
    const auto quiet=quietReference.render(input,960);
    double peak=0,referencePeak=0,referenceEnergy=0,cross=0;
    for(size_t i=0;i<limited.size();++i) {
        assert(std::isfinite(limited[i])&&std::abs(limited[i])<=.999501f);
        assert(std::isfinite(quiet[i]));
        peak=std::max(peak,std::abs(double(limited[i])));
        if(i>=limited.size()*3/4) {
            referencePeak=std::max(referencePeak,std::abs(double(quiet[i])));
            referenceEnergy+=double(quiet[i])*quiet[i];
            cross+=double(limited[i])*quiet[i];
        }
    }
    assert(peak>.99 && peak<1 && referencePeak*16>1.05 && referencePeak<.9);
    assert(referenceEnergy>1e-5);
    const double gain=cross/referenceEnergy;
    assert(gain>0 && gain<16);
    double shapeError=0;
    for(size_t i=limited.size()*3/4;i<limited.size();++i)
        shapeError=std::max(shapeError,std::abs(double(limited[i])-gain*quiet[i]));
    assert(shapeError<3e-5);
    std::printf("RACE final envelope: peak %.9g, unprotected peak %.9g, steady waveform error %.9g\n",peak,referencePeak*16,shapeError);
    std::puts("RACE precedes James limiter; final linked envelope also protects with James bypassed, without flat tops or intermediate hard clipping.");
}
}
void runStreamingTests() {
    std::setvbuf(stdout,nullptr,_IONBF,0);
#ifdef DSP_FUSION_TEST_ALLOCATIONS
    testImpulseAllocationFailures();
#endif
    testConvolutionStreaming();testStatusPollingKeepsWetAudio();testRepeatedHostConfigKeepsHistory();testCrossfeedStreaming();testRemovedEffectsAndBalance();testAnalogAndTube();testPeqProtocolAndChainOrder();testSurroundLimiterOrder();
    std::puts("Streaming, removed algorithms, balance, analog, and retained James limiter passed.");
}
