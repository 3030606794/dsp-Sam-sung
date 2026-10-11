#include <array>
#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <limits>
#include <vector>
#include "essential.h"
extern "C" audio_effect_library_t AELI;
constexpr effect_uuid_t uuid{0x30306067,0x9400,0x4d53,0x9000,{0,0,0,0,0,1}};

int cmd(effect_handle_t h,uint32_t code,uint32_t size=0,void* data=nullptr) {
    int reply=-999; uint32_t replySize=4;
    int rc=(*h)->command(h,code,size,data,&replySize,&reply);
    return rc?rc:reply;
}
int setRaw(effect_handle_t h,uint32_t id,const void* data,size_t size) {
    std::vector<uint32_t> packet((sizeof(effect_param_t)+4+size+3)/4);
    auto* p=reinterpret_cast<effect_param_t*>(packet.data());
    p->status=0;p->psize=4;p->vsize=size;
    std::memcpy(p->data,&id,4); std::memcpy(p->data+4,data,size);
    return cmd(h,EFFECT_CMD_SET_PARAM,sizeof(effect_param_t)+4+size,p);
}
int set(effect_handle_t h,uint32_t id,int value) { return setRaw(h,id,&value,4); }
int get(effect_handle_t h,uint32_t id) {
    std::array<uint32_t,5> packet{0,4,4,id,0},reply{}; uint32_t size=20;
    assert((*h)->command(h,EFFECT_CMD_GET_PARAM,16,packet.data(),&size,reply.data())==0);
    assert(size==20 && reply[0]==0 && reply[1]==4 && reply[2]==4 && reply[3]==id);
    return reply[4];
}
effect_config_t config(uint32_t rate,uint8_t format=AUDIO_FORMAT_PCM_FLOAT,bool accumulate=false) {
    effect_config_t c{};
    c.inputCfg.samplingRate=c.outputCfg.samplingRate=rate;
    c.inputCfg.channels=c.outputCfg.channels=AUDIO_CHANNEL_OUT_STEREO;
    c.inputCfg.format=c.outputCfg.format=format;
    c.inputCfg.accessMode=EFFECT_BUFFER_ACCESS_READ;
    c.outputCfg.accessMode=accumulate?EFFECT_BUFFER_ACCESS_ACCUMULATE:EFFECT_BUFFER_ACCESS_WRITE;
    c.inputCfg.mask=c.outputCfg.mask=EFFECT_CONFIG_ALL;
    return c;
}
int process(effect_handle_t h,void* in,void* out,size_t frames) {
    audio_buffer_t a{},b{};a.frameCount=b.frameCount=frames;a.raw=in;b.raw=out;
    return (*h)->process(h,&a,&b);
}
int main() {
    effect_handle_t h=nullptr;
    effect_uuid_t bad=uuid;bad.timeLow=0;
    assert(AELI.create_effect(&bad,0,0,&h)==-ENOENT);
    assert(AELI.create_effect(&uuid,0,0,&h)==0 && h);
    assert(get(h,131080)==1 && get(h,131081)==7);
    assert(get(h,20002)>0 && get(h,20001)==48000);
    assert(set(h,131072,0)==0 && set(h,131073,0)==0);
    assert(cmd(h,EFFECT_CMD_ENABLE)==0);
    std::array<float,2054> input{},output{};
    for(size_t i=0;i<input.size();++i) input[i]=std::sin(i*.023f)*.2f;
    assert(process(h,input.data(),output.data(),input.size()/2)==0);
    assert(input==output); // Multiple chunks, odd final frame count, exact dry bypass.
    assert(process(h,input.data(),input.data(),input.size()/2)==0);
    assert(input==output); // In-place buffers.

    auto c=config(44100,AUDIO_FORMAT_PCM_FLOAT,true);
    assert(cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(c),&c)==0);
    output.fill(.125f);
    assert(process(h,input.data(),output.data(),input.size()/2)==0);
    for(size_t i=0;i<input.size();++i) assert(std::abs(output[i]-(input[i]+.125f))<1e-6f);
    c=config(48000,AUDIO_FORMAT_PCM_16_BIT);
    assert(cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(c),&c)==0);
    std::array<int16_t,8> pcm{-32768,32767,-12345,12345,0,1,-1,0},pcmOut{};
    assert(process(h,pcm.data(),pcmOut.data(),4)==0 && pcm==pcmOut);
    c=config(48000,AUDIO_FORMAT_PCM_24_BIT_PACKED);
    assert(cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(c),&c)==0);
    std::array<uint8_t,12> packed{0,0,128,255,255,127,0,0,0,1,0,0},packedOut{};
    assert(process(h,packed.data(),packedOut.data(),2)==0 && packed==packedOut);
    for(uint8_t format:{uint8_t(AUDIO_FORMAT_PCM_32_BIT),uint8_t(AUDIO_FORMAT_PCM_8_24_BIT)}) {
        c=config(48000,format); assert(cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(c),&c)==0);
        std::array<int32_t,4> wide{0,65536,-65536,1048576},wideOut{};
        assert(process(h,wide.data(),wideOut.data(),2)==0 && wide==wideOut);
    }

    c=config(48000); assert(cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(c),&c)==0);
    c.outputCfg.samplingRate=44100; assert(cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(c),&c)==-EINVAL);
    assert(get(h,20001)==48000); // Rejected config does not alter current rate.
    c=config(48000); c.inputCfg.channels=1; assert(cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(c),&c)==-EINVAL);
    assert((*h)->command(h,EFFECT_CMD_SET_PARAM,0,nullptr,nullptr,nullptr)==-EINVAL);
    std::array<uint32_t,4> truncated{0,4,0xffffffffu,65560}; int reply=0;uint32_t size=4;
    assert((*h)->command(h,EFFECT_CMD_SET_PARAM,16,truncated.data(),&size,&reply)==-EINVAL);
    assert(set(h,65560,101)==-EINVAL);
    assert(set(h,65544,1)==-ENOSYS); // Incomplete upstream VHE must not report success.
    assert(set(h,65565,1)==-ENOSYS);
    std::array<int32_t,2> badEq{10,0};assert(setRaw(h,65552,badEq.data(),8)==-EINVAL);
    std::array<float,3> nanLimiter{0,std::numeric_limits<float>::quiet_NaN(),0};
    assert(setRaw(h,1500,nanLimiter.data(),12)==-EINVAL);
    std::array<int32_t,4> badIr{1,0,0,1};assert(setRaw(h,9999,badIr.data(),16)==-EINVAL);
    int16_t one=1;assert(setRaw(h,10004,&one,2)==-EINVAL);
    std::array<float,4096> ir{};assert(setRaw(h,12000,ir.data(),sizeof(ir))==-EINVAL);

    // Exercise real upstream reverb and its parameter bridge, not a mock.
    assert(set(h,131073,1)==0);
    assert(set(h,65560,70)==0 && set(h,65561,80)==0 && set(h,65562,40)==0);
    assert(set(h,65563,50)==0 && set(h,65564,0)==0 && set(h,65559,1)==0);
    std::array<float,1024> impulse{},tail{};impulse[0]=impulse[1]=.1f;
    double lateEnergy=0;
    for(int block=0;block<32;++block) {
        assert(process(h,impulse.data(),tail.data(),512)==0);
        for(float sample:tail) { assert(std::isfinite(sample)); if(block>4) lateEnergy+=sample*sample; }
        impulse.fill(0);
    }
    assert(lateEnergy>1e-9);
    assert(set(h,65559,0)==0);
    assert(set(h,65576,70)==0 && set(h,65577,200)==0 && set(h,65575,1)==0);
    assert(set(h,65550,56)==0 && set(h,65558,500)==0 && set(h,65580,100)==0);
    const std::array<uint32_t,12> effects{65548,65551,65553,65557,65569,65574,65578,65581,65583,65584,65603,65559};
    for(auto effect:effects) {
        assert(set(h,effect,1)==0);
        for(int block=0;block<6;++block) {
            for(size_t i=0;i<impulse.size();++i) impulse[i]=std::sin((block*1024+i)*.021f)*.05f;
            assert(process(h,impulse.data(),tail.data(),512)==0);
            for(float sample:tail) assert(std::isfinite(sample) && std::abs(sample)<=1);
        }
        assert(set(h,effect,0)==0);
    }
    assert(set(h,131073,0)==0 && set(h,131072,1)==0);
    assert(setRaw(h,1203,&one,2)==0); // Original James reverb enable protocol remains compatible.
    for(uint32_t rate:{44100u,48000u,96000u}) {
        c=config(rate); assert(cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(c),&c)==0);
        // The legacy James query reports its internal processing rate. James
        // intentionally resamples 96 kHz to 48 kHz, then returns to the host rate.
        assert(get(h,20001)==static_cast<int>(rate==96000?48000:rate));
        effect_config_t actual{}; uint32_t configSize=sizeof(actual);
        assert((*h)->command(h,EFFECT_CMD_GET_CONFIG,0,nullptr,&configSize,&actual)==0);
        assert(actual.inputCfg.samplingRate==rate && actual.outputCfg.samplingRate==rate);
        assert(process(h,impulse.data(),tail.data(),512)==0);
        for(float sample:tail) assert(std::isfinite(sample));
    }
    assert(set(h,131072,0)==0);
    std::array<int32_t,11> tone{1,70,50,50,20,40,30,-20,-30,40,50};
    assert(setRaw(h,196608,tone.data(),sizeof(tone))==0);
    for(size_t i=0;i<impulse.size();++i) impulse[i]=std::sin(i*.12f)*.1f;
    assert(process(h,impulse.data(),tail.data(),512)==0);
    assert(impulse!=tail);
    for(float sample:tail) assert(std::isfinite(sample));
    assert(cmd(h,EFFECT_CMD_DISABLE)==0);
    assert(process(h,impulse.data(),tail.data(),512)==-ENODATA && impulse==tail);
    assert(AELI.release_effect(h)==0);
    std::puts("Fusion integration passed: lifecycle, protocols, five PCM formats supported, bypass, accumulation, invalid packets, real reverb, James/Tone stages.");
}
