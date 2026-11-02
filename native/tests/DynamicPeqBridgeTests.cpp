// SPDX-License-Identifier: GPL-3.0-or-later
#include "../dynamic_peq/DynamicPeqProcessor.h"
#include "essential.h"
#include <algorithm>
#include <array>
#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>
extern "C" audio_effect_library_t AELI;
namespace {
constexpr effect_uuid_t uuid{0x30306067,0x9400,0x4d53,0x9000,{0,0,0,0,0,1}};
struct Engine {
    effect_handle_t handle{};
    Engine(){assert(AELI.create_effect(&uuid,0,0,&handle)==0);effect_config_t c{};
        c.inputCfg.samplingRate=c.outputCfg.samplingRate=48000;c.inputCfg.channels=c.outputCfg.channels=AUDIO_CHANNEL_OUT_STEREO;
        c.inputCfg.format=c.outputCfg.format=AUDIO_FORMAT_PCM_FLOAT;c.inputCfg.accessMode=EFFECT_BUFFER_ACCESS_READ;c.outputCfg.accessMode=EFFECT_BUFFER_ACCESS_WRITE;
        c.inputCfg.mask=c.outputCfg.mask=EFFECT_CONFIG_ALL;assert(command(EFFECT_CMD_SET_CONFIG,sizeof(c),&c)==0);
        const int32_t off=0;assert(set(131072,&off,4)==0&&set(131073,&off,4)==0&&command(EFFECT_CMD_ENABLE)==0);
    }
    ~Engine(){AELI.release_effect(handle);}
    int command(uint32_t code,uint32_t n=0,void* p=nullptr){int32_t status=0;uint32_t bytes=4;const int result=(*handle)->command(handle,code,n,p,&bytes,&status);return result?result:status;}
    int set(uint32_t id,const int32_t* words,uint32_t bytes){std::vector<uint32_t> packet((16+bytes+3)/4);packet[1]=4;packet[2]=bytes;packet[3]=id;std::memcpy(packet.data()+4,words,bytes);return command(EFFECT_CMD_SET_PARAM,16+bytes,packet.data());}
    std::vector<int32_t> get(uint32_t id,unsigned words){std::array<uint32_t,4> packet{0,4,words*4,id};std::vector<int32_t> reply(4+words);uint32_t bytes=uint32_t(reply.size()*4);assert((*handle)->command(handle,EFFECT_CMD_GET_PARAM,16,packet.data(),&bytes,reply.data())==0);assert(bytes==reply.size()*4&&reply[0]==0);return {reply.begin()+4,reply.end()};}
    std::vector<float> render(const std::vector<float>& input){std::vector<float> out(input.size());for(unsigned start=0;start<input.size()/2;start+=333){audio_buffer_t a{},b{};a.frameCount=b.frameCount=std::min(size_t(333),input.size()/2-start);a.f32=const_cast<float*>(input.data()+2*start);b.f32=out.data()+2*start;assert((*handle)->process(handle,&a,&b)==0);}return out;}
};
}
int main(){
    Engine original,dynamic;assert(dynamic.get(132000,1)[0]==1&&dynamic.get(131080,1)[0]==5);
    const std::array<int32_t,8> peq{1,1,1,0,1,1000000,3000,1000};
    assert(original.set(1250,peq.data(),sizeof(peq))==0&&dynamic.set(1250,peq.data(),sizeof(peq))==0);
    std::array<int32_t,15> dyn{1,0,1,0,1,1000000,0,2000,1,-24000,4000,12000,5000,100000,0};
    assert(dynamic.set(196720,dyn.data(),sizeof(dyn))==0);
    std::vector<float> input(96000);for(unsigned i=0;i<48000;++i)input[2*i]=input[2*i+1]=float(.1*std::sin(6.283185307179586*1000*i/48000));
    assert(original.render(input)==dynamic.render(input));assert(dynamic.get(132001,40)[4]==0);
    dyn[1]=1;assert(dynamic.set(196720,dyn.data(),sizeof(dyn))==0);auto malformed=dyn;malformed[7]=0;assert(dynamic.set(196720,malformed.data(),sizeof(malformed))==-EINVAL);
    const auto baseline=original.render(input),processed=dynamic.render(input);double base=0,wet=0;for(unsigned i=48000;i<input.size();++i){base+=baseline[i]*baseline[i];wet+=processed[i]*processed[i];}assert(wet<base*.7);
    const auto snapshot=dynamic.get(132001,40);assert(snapshot[0]==1&&snapshot[1]==1&&snapshot[2]==1&&snapshot[3]==48000&&snapshot[4]>0&&snapshot[24]>1000);
    for(unsigned i=0;i<100;++i)assert(dynamic.get(132001,40)==snapshot);
    assert(dynamic.command(EFFECT_CMD_DISABLE)==0);const auto disabled=dynamic.get(132001,40);assert(disabled[4]==snapshot[4]&&disabled[8]==-120000&&disabled[24]==0);
    assert(dynamic.command(EFFECT_CMD_ENABLE)==0&&dynamic.command(EFFECT_CMD_RESET)==0);
    std::puts("Dynamic PEQ Fusion bridge: independent capability/protocol5 compatibility, separate static PEQ unchanged while disabled, strict packet validation, active selective attenuation, fixed40word read-only status and lifecycle passed.");
}
