// A single Android AudioEffect owns both upstream engines. Their library entry
// points are private and never appear in the system audio-effects configuration.
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <new>
#include <vector>
#include "essential.h"
#include "../tone/ToneProcessor.h"

extern "C" audio_effect_library_t JDSP_AELI;
extern "C" audio_effect_library_t VIPER_AELI;

namespace {
constexpr effect_uuid_t kUuid{0x30306067, 0x9400, 0x4d53, 0x9000, {0,0,0,0,0,1}};
constexpr effect_uuid_t kType{0x30306067, 0x9400, 0x4d53, 0x9000, {0,0,0,0,0,0}};
constexpr effect_uuid_t kJamesUuid{0xf27317f4,0xc984,0x4de6,0x9a90,{0x54,0x57,0x59,0x49,0x5b,0xf2}};
constexpr effect_uuid_t kViperUuid{0x90380da3,0x8536,0x4744,0xa6a3,{0x57,0x31,0x97,0x0e,0x64,0x0f}};
constexpr size_t kBlockFrames=512;
constexpr size_t kMaxTransfer=32*1024*1024;
constexpr uint32_t kJamesEnable=131072, kViperEnable=131073;
constexpr uint32_t kBalance=131074;
constexpr uint32_t kProtocol=131080, kCapabilities=131081, kTone=196608;
// Retain the upstream engines' nominal policy charges (1 KB each). This legacy
// descriptor field is admission accounting, not measured process RSS: a 1024 KB
// estimate exceeds Android's global effects budget and prevents any instance.
constexpr uint16_t kPolicyMemoryKb=2;
const effect_descriptor_t kDescriptor{
    kType,kUuid,EFFECT_CONTROL_API_VERSION,
    EFFECT_FLAG_TYPE_INSERT | EFFECT_FLAG_INSERT_LAST | EFFECT_FLAG_INPUT_DIRECT | EFFECT_FLAG_OUTPUT_DIRECT,
    30,kPolicyMemoryKb,"DSP Fusion 0.1","James Fung / ViPER authors / DSP Fusion"
};

int32_t childCommand(effect_handle_t child,uint32_t cmd,uint32_t size=0,void* data=nullptr) {
    int32_t status=0; uint32_t replySize=sizeof(status);
    const auto result=(*child)->command(child,cmd,size,data,&replySize,&status);
    return result ? result : status;
}
bool supportedFormat(uint8_t f) {
    return f==AUDIO_FORMAT_PCM_FLOAT || f==AUDIO_FORMAT_PCM_16_BIT || f==AUDIO_FORMAT_PCM_32_BIT ||
           f==AUDIO_FORMAT_PCM_24_BIT_PACKED || f==AUDIO_FORMAT_PCM_8_24_BIT;
}
float clean(float v) { return std::isfinite(v) ? v : 0.f; }
float readSample(const void* raw,size_t i,uint8_t format) {
    switch(format) {
        case AUDIO_FORMAT_PCM_FLOAT: return clean(static_cast<const float*>(raw)[i]);
        case AUDIO_FORMAT_PCM_16_BIT: return static_cast<const int16_t*>(raw)[i]/32768.f;
        case AUDIO_FORMAT_PCM_32_BIT: return static_cast<float>(static_cast<const int32_t*>(raw)[i]/2147483648.0);
        case AUDIO_FORMAT_PCM_8_24_BIT: return static_cast<const int32_t*>(raw)[i]/8388608.f;
        case AUDIO_FORMAT_PCM_24_BIT_PACKED: {
            const auto* p=static_cast<const uint8_t*>(raw)+3*i;
            int32_t v=static_cast<int32_t>(uint32_t(p[0]) | (uint32_t(p[1])<<8) | (uint32_t(p[2])<<16));
            if(v&0x800000) v-=0x1000000;
            return v/8388608.f;
        }
        default: return 0;
    }
}
void writeSample(void* raw,size_t i,uint8_t format,float sample,bool accumulate) {
    double value=clean(sample);
    if(accumulate) value+=readSample(raw,i,format);
    if(format==AUDIO_FORMAT_PCM_FLOAT) {
        static_cast<float*>(raw)[i]=clean(static_cast<float>(value)); return;
    }
    value=std::clamp(value,-1.0,1.0);
    switch(format) {
        case AUDIO_FORMAT_PCM_16_BIT:
            static_cast<int16_t*>(raw)[i]=static_cast<int16_t>(std::clamp(std::llround(value*32768.0),-32768LL,32767LL)); break;
        case AUDIO_FORMAT_PCM_32_BIT:
            static_cast<int32_t*>(raw)[i]=static_cast<int32_t>(std::clamp(std::llround(value*2147483648.0),-2147483648LL,2147483647LL)); break;
        case AUDIO_FORMAT_PCM_8_24_BIT:
            static_cast<int32_t*>(raw)[i]=static_cast<int32_t>(std::clamp(std::llround(value*8388608.0),-8388608LL,8388607LL)); break;
        case AUDIO_FORMAT_PCM_24_BIT_PACKED: {
            auto v=static_cast<int32_t>(std::clamp(std::llround(value*8388608.0),-8388608LL,8388607LL));
            auto* p=static_cast<uint8_t*>(raw)+3*i;
            p[0]=static_cast<uint8_t>(v); p[1]=static_cast<uint8_t>(v>>8); p[2]=static_cast<uint8_t>(v>>16); break;
        }
    }
}
struct Fusion {
    const effect_interface_s* iface; // Android effect_handle_t ABI: first member.
    effect_handle_t james=nullptr, viper=nullptr;
    std::mutex mutex;
    effect_config_t config{};
    std::atomic<uint32_t> ioFormat{AUDIO_FORMAT_PCM_FLOAT | (AUDIO_FORMAT_PCM_FLOAT<<8)};
    std::atomic<bool> enabled{false};
    bool jamesEnabled=true, viperEnabled=true, configured=false;
    int32_t balance=0;
    float balanceCurrent=0.f, balanceSmoothing=.002f;
    uint32_t stringChunks=0,stringWritten=0,impulseChunks=0,impulseWritten=0;
    dspfusion::ToneProcessor tone;
    std::array<float,kBlockFrames*2> first{},second{};
    ~Fusion() {
        if(james) JDSP_AELI.release_effect(james);
        if(viper) VIPER_AELI.release_effect(viper);
    }
};

void mergeConfig(buffer_config_t& target,const buffer_config_t& source) {
    if(source.mask&EFFECT_CONFIG_BUFFER) target.buffer=source.buffer;
    if(source.mask&EFFECT_CONFIG_SMP_RATE) target.samplingRate=source.samplingRate;
    if(source.mask&EFFECT_CONFIG_CHANNELS) target.channels=source.channels;
    if(source.mask&EFFECT_CONFIG_FORMAT) target.format=source.format;
    if(source.mask&EFFECT_CONFIG_ACC_MODE) target.accessMode=source.accessMode;
    if(source.mask&EFFECT_CONFIG_PROVIDER) target.bufferProvider=source.bufferProvider;
    target.mask|=source.mask;
}
int32_t setConfig(Fusion& f,const effect_config_t& cfg) {
    effect_config_t next=f.config;
    mergeConfig(next.inputCfg,cfg.inputCfg); mergeConfig(next.outputCfg,cfg.outputCfg);
    const auto& in=next.inputCfg; const auto& out=next.outputCfg;
    if(in.samplingRate<8000 || in.samplingRate>192000 || in.samplingRate!=out.samplingRate ||
       in.channels!=AUDIO_CHANNEL_OUT_STEREO || out.channels!=AUDIO_CHANNEL_OUT_STEREO ||
       !supportedFormat(in.format) || !supportedFormat(out.format) ||
       (out.accessMode!=EFFECT_BUFFER_ACCESS_WRITE && out.accessMode!=EFFECT_BUFFER_ACCESS_ACCUMULATE)) return -EINVAL;
    effect_config_t internal=next;
    internal.inputCfg.format=internal.outputCfg.format=AUDIO_FORMAT_PCM_FLOAT;
    internal.inputCfg.accessMode=EFFECT_BUFFER_ACCESS_READ;
    internal.outputCfg.accessMode=EFFECT_BUFFER_ACCESS_WRITE;
    internal.inputCfg.buffer={}; internal.outputCfg.buffer={};
    internal.inputCfg.buffer.frameCount=internal.outputCfg.buffer.frameCount=kBlockFrames;
    internal.inputCfg.mask=internal.outputCfg.mask=EFFECT_CONFIG_ALL;
    internal.inputCfg.bufferProvider={}; internal.outputCfg.bufferProvider={};
    int32_t ret=childCommand(f.james,EFFECT_CMD_SET_CONFIG,sizeof(internal),&internal);
    if(ret) return ret;
    ret=childCommand(f.viper,EFFECT_CMD_SET_CONFIG,sizeof(internal),&internal);
    if(ret) return ret;
    f.tone.configure(in.samplingRate);
    f.balanceSmoothing=1.f-std::exp(-1.f/(.01f*in.samplingRate));
    f.config=next;
    f.ioFormat.store(in.format | (uint32_t(out.format)<<8) | (uint32_t(out.accessMode==EFFECT_BUFFER_ACCESS_ACCUMULATE)<<16));
    f.configured=true;
    // Reserve upstream James block memory at configuration time, not its first
    // callback. The fixed wrapper work buffers are allocated with the instance.
    std::fill(f.first.begin(),f.first.end(),0.f);
    audio_buffer_t a{},b{}; a.frameCount=b.frameCount=kBlockFrames;
    a.f32=f.first.data(); b.f32=f.second.data();
    (*f.james)->process(f.james,&a,&b);
    return 0;
}
int32_t statusReply(uint32_t* size,void* data,int32_t status) {
    if(!size || !data || *size<sizeof(status)) return -EINVAL;
    std::memcpy(data,&status,sizeof(status)); *size=sizeof(status); return 0;
}
bool finiteValues(const uint8_t* bytes,size_t length) {
    for(size_t i=0;i<length;i+=4) { float v; std::memcpy(&v,bytes+i,4); if(!std::isfinite(v)) return false; }
    return true;
}
template<class T> T valueAt(const void* bytes,size_t index=0) {
    T value; std::memcpy(&value,static_cast<const uint8_t*>(bytes)+sizeof(T)*index,sizeof(T)); return value;
}

// Bound all packets before entering upstream implementations, which predate
// strict effect_param_t validation. These checks also prevent partial transfers
// from being committed and make shared DDC/IR/LiveProg uploads deterministic.
int validateJames(Fusion& f,uint32_t id,const uint8_t* data,uint32_t n) {
    if(id==8888) {
        if(n!=8) return -EINVAL;
        int32_t count=valueAt<int32_t>(data),unit=valueAt<int32_t>(data,1);
        if(count<=0 || unit!=256 || uint64_t(count)*unit>kMaxTransfer) return -EINVAL;
        f.stringChunks=count; f.stringWritten=0; return 0;
    }
    if(id==12001) {
        if(n!=256 || f.stringWritten>=f.stringChunks) return -EINVAL;
        ++f.stringWritten; return 0;
    }
    if(id==10006 || id==10009 || id==10010) {
        if(n!=2 || !f.stringChunks || f.stringWritten!=f.stringChunks) return -EINVAL;
        f.stringChunks=f.stringWritten=0; return 0;
    }
    if(id==9999) {
        if(n!=16) return -EINVAL;
        int32_t length=valueAt<int32_t>(data),channels=valueAt<int32_t>(data,1),chunks=valueAt<int32_t>(data,3);
        if(length<=0 || (channels!=1 && channels!=2 && channels!=4) || chunks<=0 ||
           length%channels || uint64_t(chunks)*4096*4>kMaxTransfer ||
           uint64_t(length)>uint64_t(chunks)*4096 || uint64_t(chunks-1)*4096>=uint64_t(length)) return -EINVAL;
        f.impulseChunks=chunks; f.impulseWritten=0; return 0;
    }
    if(id==12000) {
        if(n!=16384 || f.impulseWritten>=f.impulseChunks || !finiteValues(data,n)) return -EINVAL;
        ++f.impulseWritten; return 0;
    }
    if(id==10004) {
        if(n!=2 || !f.impulseChunks || f.impulseWritten!=f.impulseChunks) return -EINVAL;
        f.impulseChunks=f.impulseWritten=0; return 0;
    }
    if(id>=25000 && id<=25003) return n==4 ? 0 : -EINVAL;
    if(id==1500 || id==115 || id==116) {
        const uint32_t expected=id==1500?12:(id==115?68:128);
        if(n!=expected || !finiteValues(data,n)) return -EINVAL;
        if(id==115) {
            if(valueAt<float>(data)<=0 || valueAt<float>(data)>10000 || valueAt<float>(data,1)<0 ||
               valueAt<float>(data,1)>4 || valueAt<float>(data,2)<0 || valueAt<float>(data,2)>3) return -EINVAL;
        }
        if(id==116 && (valueAt<float>(data)<0 || valueAt<float>(data)>5)) return -EINVAL;
        if(id==115 || id==116) {
            const size_t count=id==115?7:15,offset=id==115?3:2;
            float previous=0;
            for(size_t i=0;i<count;++i) {
                float freq=valueAt<float>(data,offset+i),gain=valueAt<float>(data,offset+count+i);
                if(freq<=previous || freq>192000 || std::abs(gain)>100) return -EINVAL;
                previous=freq;
            }
        }
        return 0;
    }
    if(n!=2) return -EINVAL;
    int16_t v=valueAt<int16_t>(data);
    switch(id) {
        case 128: return v>=0 && v<=18 ? 0 : -EINVAL;
        case 137: return v>=0 && v<=100 ? 0 : -EINVAL;
        case 150: return v>=-3000 && v<=12000 ? 0 : -EINVAL;
        case 188: return v>=0 && v<=5 ? 0 : -EINVAL;
        case 112: return v>=0 && v<=30 ? 0 : -EINVAL;
        case 1200: case 1201: case 1202: case 1203: case 1204: case 1205:
        case 1206: case 1208: case 1210: case 1212: case 1213: return (v==0 || v==1)?0:-EINVAL;
        default: return -EINVAL;
    }
}
int validateViper(uint32_t id,const uint8_t* data,uint32_t n) {
    // Removed effects must not silently accept values from an older app.
    if(id==65551 || id==65552 || (id>=65586 && id<=65588) || id==65603) return -ENOSYS;
    if(id==65544) return n==4 && valueAt<int32_t>(data)==0 ? 0 : -ENOSYS;
    if(id==65538 || (id>=65540 && id<=65542) || id==65545 || id==65547 ||
       (id>=65565 && id<=65568) || (id>=65610 && id<=65626)) return -ENOSYS;
    const bool pair=id==65570 || id==65571 || id==65572;
    if(n!=(pair?8u:4u)) return -EINVAL;
    int v=valueAt<int32_t>(data),second=pair?valueAt<int32_t>(data,1):0;
    switch(id) {
        case 65546: case 65548: case 65553: case 65557: case 65559:
        case 65569: case 65574: case 65578: case 65581: case 65583: case 65584:
        case 0x9002: return v==0 || v==1 ? 0 : -EINVAL;
        case 0x9003: return 0;
        case 65543: case 65560: case 65561: case 65562: case 65563: case 65564:
            return v>=0 && v<=100 ? 0 : -EINVAL;
        case 65549: return v>=100 && v<=20000 ? 0 : -EINVAL;
        case 65550: return v>=0 && v<=560 ? 0 : -EINVAL;
        case 65554: case 65555: return v>=0 && v<=1000 ? 0 : -EINVAL;
        case 65556: return v>=0 && v<=32767 ? 0 : -EINVAL;
        case 65558: return v>=0 && v<=5000 ? 0 : -EINVAL;
        case 65570: case 65571: return v>0 && v<=24000 && second>0 && second<=24000 ? 0 : -EINVAL;
        case 65572: return v>=0 && v<=1000 && second>=0 && second<=1000 ? 0 : -EINVAL;
        case 65573: case 65577: case 65580: return v>=0 && v<=2100 ? 0 : -EINVAL;
        case 65575: case 65579: case 65582: case 65585: return v>=0 && v<=2 ? 0 : -EINVAL;
        case 65576: return v>=15 && v<=500 ? 0 : -EINVAL;
        default: return -EINVAL;
    }
}

int32_t parameter(Fusion& f,bool set,uint32_t size,void* data,uint32_t* replySize,void* reply) {
    if(!data || size<sizeof(effect_param_t)+4 || !replySize || !reply) return -EINVAL;
    auto* packet=static_cast<effect_param_t*>(data);
    if(packet->psize!=4 || packet->vsize>kMaxTransfer) return -EINVAL;
    uint32_t id=valueAt<uint32_t>(packet->data);
    const auto* values=reinterpret_cast<const uint8_t*>(packet->data+4);
    if(set) {
        if(packet->vsize>size-sizeof(effect_param_t)-4 || *replySize<4) return -EINVAL;
        int32_t status=0;
        if(id==kJamesEnable || id==kViperEnable) {
            if(packet->vsize!=4 || (valueAt<int32_t>(values)!=0 && valueAt<int32_t>(values)!=1)) status=-EINVAL;
            else if(id==kJamesEnable) f.jamesEnabled=valueAt<int32_t>(values)!=0;
            else f.viperEnabled=valueAt<int32_t>(values)!=0;
        } else if(id==kBalance) {
            if(packet->vsize!=4 || valueAt<int32_t>(values)<-100 || valueAt<int32_t>(values)>100) status=-EINVAL;
            else f.balance=valueAt<int32_t>(values);
        } else if(id==kTone) {
            std::array<int32_t,11> parameters{};
            if(packet->vsize!=sizeof(parameters)) status=-EINVAL;
            else {
                std::memcpy(parameters.data(),values,sizeof(parameters));
                if(!f.tone.setParameters(parameters.data(),parameters.size())) status=-EINVAL;
            }
        } else {
            bool isViper=(id>=65538 && id<=65626) || id==0x9002 || id==0x9003;
            status=isViper ? validateViper(id,values,packet->vsize) : validateJames(f,id,values,packet->vsize);
            if(!status) {
                uint32_t childReplySize=4;
                auto handle=isViper?f.viper:f.james;
                int32_t result=(*handle)->command(handle,EFFECT_CMD_SET_PARAM,size,data,&childReplySize,&status);
                if(result) status=result;
            }
        }
        return statusReply(replySize,reply,status);
    }
    if(packet->vsize!=4 || *replySize<sizeof(effect_param_t)+8) return -EINVAL;
    if(id==kProtocol || id==kCapabilities || id==kJamesEnable || id==kViperEnable || id==kBalance) {
        auto* result=static_cast<effect_param_t*>(reply);
        result->status=0; result->psize=result->vsize=4;
        int32_t value=id==kProtocol?2:(id==kCapabilities?7:(id==kBalance?f.balance:(id==kJamesEnable?f.jamesEnabled:f.viperEnabled)));
        std::memcpy(result->data,&id,4); std::memcpy(result->data+4,&value,4);
        *replySize=sizeof(effect_param_t)+8; return 0;
    }
    // Old James queries, including driver PID, hashes and block/rate reporting.
    if((id>=19998 && id<=20002) || (id>=30000 && id<=30003))
        return (*f.james)->command(f.james,EFFECT_CMD_GET_PARAM,size,data,replySize,reply);
    return -EINVAL;
}

int32_t process(effect_handle_t self,audio_buffer_t* in,audio_buffer_t* out) {
    if(!self || !in || !out || !in->raw || !out->raw || in->frameCount!=out->frameCount ||
       !in->frameCount || in->frameCount>1048576) return -EINVAL;
    auto& f=*reinterpret_cast<Fusion*>(self);
    const uint32_t io=f.ioFormat.load();
    const uint8_t inputFormat=io&255,outputFormat=(io>>8)&255;
    const bool accumulate=(io>>16)!=0;
    const bool active=f.enabled.load();
    std::unique_lock<std::mutex> lock(f.mutex,std::try_to_lock);
    if(!lock.owns_lock() || !active || !f.configured) {
        // Heavy filter/IR changes may own the control mutex. Dry bypass keeps
        // the audio thread running without racing upstream filter state.
        if(inputFormat==outputFormat && !accumulate) {
            const size_t bytes=inputFormat==AUDIO_FORMAT_PCM_16_BIT?2:(inputFormat==AUDIO_FORMAT_PCM_24_BIT_PACKED?3:4);
            std::memmove(out->raw,in->raw,in->frameCount*2*bytes);
        } else {
            for(size_t i=0;i<in->frameCount*2;++i)
                writeSample(out->raw,i,outputFormat,readSample(in->raw,i,inputFormat),accumulate);
        }
        return active?0:-ENODATA;
    }
    for(size_t offset=0;offset<in->frameCount;offset+=kBlockFrames) {
        const size_t count=std::min(kBlockFrames,in->frameCount-offset);
        for(size_t i=0;i<count*2;++i) f.first[i]=readSample(in->raw,offset*2+i,inputFormat);
        audio_buffer_t a{},b{}; a.frameCount=b.frameCount=count;
        a.f32=f.first.data(); b.f32=f.second.data();
        float* current=f.first.data();
        if(f.jamesEnabled && !(*f.james)->process(f.james,&a,&b)) current=f.second.data();
        for(size_t i=0;i<count*2;++i) current[i]=clean(current[i]);
        f.tone.process(current,static_cast<uint32_t>(count));
        if(f.viperEnabled) {
            a.f32=current; b.f32=current==f.first.data()?f.second.data():f.first.data();
            if(!(*f.viper)->process(f.viper,&a,&b)) current=b.f32;
        }
        for(size_t frame=0;frame<count;++frame) {
            const float target=f.balance/100.f;
            f.balanceCurrent+=(target-f.balanceCurrent)*f.balanceSmoothing;
            if(std::abs(target-f.balanceCurrent)<1e-6f) f.balanceCurrent=target;
            const float left=1.f-std::max(0.f,f.balanceCurrent);
            const float right=1.f+std::min(0.f,f.balanceCurrent);
            writeSample(out->raw,(offset+frame)*2,outputFormat,std::clamp(clean(current[frame*2])*left,-1.f,1.f),accumulate);
            writeSample(out->raw,(offset+frame)*2+1,outputFormat,std::clamp(clean(current[frame*2+1])*right,-1.f,1.f),accumulate);
        }
    }
    return 0;
}
int32_t commandInternal(effect_handle_t self,uint32_t code,uint32_t size,void* data,uint32_t* replySize,void* reply) {
    if(!self) return -EINVAL;
    auto& f=*reinterpret_cast<Fusion*>(self);
    std::lock_guard<std::mutex> lock(f.mutex);
    switch(code) {
        case EFFECT_CMD_INIT: return statusReply(replySize,reply,0);
        case EFFECT_CMD_SET_CONFIG:
            if(size!=sizeof(effect_config_t) || !data || !replySize || !reply || *replySize<4) return -EINVAL;
            return statusReply(replySize,reply,setConfig(f,*static_cast<effect_config_t*>(data)));
        case EFFECT_CMD_GET_CONFIG:
            if(!replySize || !reply || *replySize<sizeof(effect_config_t)) return -EINVAL;
            std::memcpy(reply,&f.config,sizeof(f.config)); *replySize=sizeof(f.config); return 0;
        case EFFECT_CMD_ENABLE: case EFFECT_CMD_DISABLE:
            if(!replySize || !reply || *replySize<4) return -EINVAL;
            f.enabled.store(code==EFFECT_CMD_ENABLE); return statusReply(replySize,reply,0);
        case EFFECT_CMD_RESET:
            childCommand(f.james,EFFECT_CMD_RESET); childCommand(f.viper,EFFECT_CMD_RESET); f.tone.reset();
            return replySize && reply ? statusReply(replySize,reply,0) : 0;
        case EFFECT_CMD_SET_PARAM: case EFFECT_CMD_GET_PARAM:
            return parameter(f,code==EFFECT_CMD_SET_PARAM,size,data,replySize,reply);
        case EFFECT_CMD_SET_DEVICE: case EFFECT_CMD_SET_AUDIO_MODE: case EFFECT_CMD_SET_AUDIO_SOURCE: return 0;
        case EFFECT_CMD_SET_VOLUME:
            if(replySize && reply && data && *replySize>=size) { std::memcpy(reply,data,size); *replySize=size; }
            return 0;
        case EFFECT_CMD_OFFLOAD: return statusReply(replySize,reply,-ENOSYS);
        default: return -EINVAL;
    }
}
int32_t command(effect_handle_t self,uint32_t code,uint32_t size,void* data,uint32_t* replySize,void* reply) {
    try { return commandInternal(self,code,size,data,replySize,reply); }
    catch(const std::bad_alloc&) { return -ENOMEM; }
}
int32_t descriptor(effect_handle_t self,effect_descriptor_t* result) {
    if(!self || !result) return -EINVAL;
    *result=kDescriptor; return 0;
}
const effect_interface_s kInterface{process,command,descriptor,nullptr};
int32_t create(const effect_uuid_t* uuid,int32_t session,int32_t io,effect_handle_t* handle) {
    if(!uuid || !handle) return -EINVAL;
    *handle=nullptr;
    if(std::memcmp(uuid,&kUuid,sizeof(kUuid))) return -ENOENT;
    try {
        auto owner=std::make_unique<Fusion>();
        auto* f=owner.get();
        f->iface=&kInterface;
        int32_t status=JDSP_AELI.create_effect(&kJamesUuid,session,io,&f->james);
        if(!status) status=VIPER_AELI.create_effect(&kViperUuid,session,io,&f->viper);
        if(!status) status=childCommand(f->james,EFFECT_CMD_INIT);
        if(!status) status=childCommand(f->viper,EFFECT_CMD_INIT);
        if(!status) status=childCommand(f->james,EFFECT_CMD_ENABLE);
        if(!status) status=childCommand(f->viper,EFFECT_CMD_ENABLE);
        if(status) return status;
        f->config.inputCfg.samplingRate=f->config.outputCfg.samplingRate=48000;
        f->config.inputCfg.channels=f->config.outputCfg.channels=AUDIO_CHANNEL_OUT_STEREO;
        f->config.inputCfg.format=f->config.outputCfg.format=AUDIO_FORMAT_PCM_FLOAT;
        f->config.inputCfg.accessMode=EFFECT_BUFFER_ACCESS_READ;
        f->config.outputCfg.accessMode=EFFECT_BUFFER_ACCESS_WRITE;
        f->config.inputCfg.mask=f->config.outputCfg.mask=EFFECT_CONFIG_ALL;
        status=setConfig(*f,f->config);
        if(status) return status;
        *handle=reinterpret_cast<effect_handle_t>(owner.release()); return 0;
    } catch(const std::bad_alloc&) { return -ENOMEM; }
}
int32_t release(effect_handle_t handle) {
    if(!handle) return -EINVAL;
    delete reinterpret_cast<Fusion*>(handle); return 0;
}
int32_t getDescriptor(const effect_uuid_t* uuid,effect_descriptor_t* result) {
    if(!uuid || !result) return -EINVAL;
    if(std::memcmp(uuid,&kUuid,sizeof(kUuid))) return -ENOENT;
    *result=kDescriptor; return 0;
}
}
extern "C" {
__attribute__((visibility("default"))) audio_effect_library_t AUDIO_EFFECT_LIBRARY_INFO_SYM={
    .tag=AUDIO_EFFECT_LIBRARY_TAG,.version=EFFECT_LIBRARY_API_VERSION,
    .name="DSP Fusion: JamesDSP + ViPER + Tone",.implementor="DSP Fusion and upstream contributors",
    .create_effect=create,.release_effect=release,.get_descriptor=getDescriptor
};
}
