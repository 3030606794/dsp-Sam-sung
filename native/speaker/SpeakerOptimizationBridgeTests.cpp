// SPDX-License-Identifier: GPL-3.0-or-later
#include "SpeakerOptimizationProcessor.h"
#include "../expansion/ExpansionEffectsProcessor.h"
#include "essential.h"
#include <array>
#include <algorithm>
#include <cassert>
#include <cerrno>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>
extern "C" audio_effect_library_t AELI;
namespace {
using Processor=dspfusion::SpeakerOptimizationProcessor;using Packet=std::array<int32_t,128>;
constexpr effect_uuid_t uuid{0x30306067,0x9400,0x4d53,0x9000,{0,0,0,0,0,1}};
Packet settings(bool on=false,bool route=false){return Packet{1,on?1:0,1000,80000,1000,13500000,1000,420000,3880,1000,500,0,0,route?1000:0};}
struct Engine {
    effect_handle_t handle{};
    Engine(){assert(AELI.create_effect(&uuid,0,0,&handle)==0);effect_config_t c{};c.inputCfg.samplingRate=c.outputCfg.samplingRate=48000;c.inputCfg.channels=c.outputCfg.channels=AUDIO_CHANNEL_OUT_STEREO;c.inputCfg.format=c.outputCfg.format=AUDIO_FORMAT_PCM_FLOAT;c.inputCfg.accessMode=EFFECT_BUFFER_ACCESS_READ;c.outputCfg.accessMode=EFFECT_BUFFER_ACCESS_WRITE;c.inputCfg.mask=c.outputCfg.mask=EFFECT_CONFIG_ALL;assert(command(EFFECT_CMD_SET_CONFIG,sizeof(c),&c)==0);int32_t off=0;assert(set(131072,&off,4)==0&&set(131073,&off,4)==0&&command(EFFECT_CMD_ENABLE)==0);}
    ~Engine(){AELI.release_effect(handle);}
    int command(uint32_t code,uint32_t bytes=0,void* p=nullptr){int32_t status=0;uint32_t count=4;int result=(*handle)->command(handle,code,bytes,p,&count,&status);return result?result:status;}
    int set(uint32_t id,const void* p,uint32_t bytes){std::vector<uint32_t> packet((16+bytes+3)/4);packet[1]=4;packet[2]=bytes;packet[3]=id;if(bytes)std::memcpy(packet.data()+4,p,bytes);return command(EFFECT_CMD_SET_PARAM,16+bytes,packet.data());}
    int set(const Packet& p){return set(Processor::kSet,p.data(),sizeof(p));}
    int getRaw(uint32_t id,unsigned count,std::vector<int32_t>& result){std::array<uint32_t,4> packet{0,4,count*4,id};result.resize(4+count);uint32_t bytes=uint32_t(result.size()*4);const int code=(*handle)->command(handle,EFFECT_CMD_GET_PARAM,16,packet.data(),&bytes,result.data());return code?code:result[0];}
    std::vector<int32_t> get(uint32_t id,unsigned count){std::vector<int32_t> result;assert(getRaw(id,count,result)==0);return {result.begin()+4,result.end()};}
    std::vector<float> render(const std::vector<float>& input){std::vector<float> output(input.size());for(size_t start=0;start<input.size()/2;start+=333){audio_buffer_t a{},b{};a.frameCount=b.frameCount=std::min(size_t(333),input.size()/2-start);a.f32=const_cast<float*>(input.data()+start*2);b.f32=output.data()+start*2;assert((*handle)->process(handle,&a,&b)==0);}return output;}
};
std::vector<float> tone(size_t frames=9600){std::vector<float>x(frames*2);for(size_t n=0;n<frames;++n)x[2*n]=x[2*n+1]=float(.04*std::sin(6.283185307179586*420*n/48000));return x;}
}
int main(){
    Engine dry,wet;assert(wet.get(Processor::kCapabilityGet,1)[0]==1);assert(wet.get(dspfusion::ExpansionEffectsProcessor::kCapabilityGet,1)[0]==1);
    std::printf("Existing extended capability 131234 actual=%d (independent speaker capability 132044=1)\n",wet.get(131234,1)[0]);
    const int32_t disabled=0;assert(wet.set(65603,&disabled,4)==-ENOSYS);
    auto signal=tone();assert(dry.render(signal)==wet.render(signal));
    auto p=settings();assert(wet.set(p)==0);auto bad=p;bad[14]=1;assert(wet.set(bad)==-EINVAL);bad=p;bad[3]=19999;assert(wet.set(bad)==-EINVAL);assert(wet.set(Processor::kSet,p.data(),sizeof(p)-4)==-EINVAL);
    std::vector<int32_t> invalid;assert(wet.getRaw(Processor::kStatusGet,127,invalid)==-EINVAL);assert(wet.getRaw(Processor::kCapabilityGet,2,invalid)==-EINVAL);
    // New non-contiguous command IDs cannot overwrite the adjacent match profile.
    std::array<float,64> profile{};for(size_t i=0;i<profile.size();++i)profile[i]=float((i+1)*1e-10);assert(wet.set(196761,profile.data(),sizeof(profile))==0);
    p=settings(true,true);assert(wet.set(p)==0);auto output=wet.render(signal);double difference=0;for(size_t i=2000;i<output.size();++i)difference+=std::abs(output[i]-signal[i]);assert(difference>1);
    const auto saved=wet.get(132042,64);assert(!std::memcmp(profile.data(),saved.data(),sizeof(profile)));auto s=wet.get(Processor::kStatusGet,128);assert(s[0]==1&&s[1]==1&&s[2]==48000&&s[3]>0&&s[4]!=0&&s[17]==1000);
    p[13]=0;p[11]=12000;assert(wet.set(p)==0);output=wet.render(signal);assert(output==signal);s=wet.get(Processor::kStatusGet,128);assert(s[17]==0&&s[15]==0&&s[19]==0);
    assert(wet.command(EFFECT_CMD_DISABLE)==0);s=wet.get(Processor::kStatusGet,128);assert(s[11]==0&&s[17]==0&&s[19]==0&&s[5]==-120000);
    assert(wet.command(EFFECT_CMD_ENABLE)==0&&wet.command(EFFECT_CMD_RESET)==0);s=wet.get(Processor::kStatusGet,128);assert(s[3]==0&&s[4]==0);wet.render(signal);const auto stillSaved=wet.get(132042,64);assert(!std::memcmp(profile.data(),stillSaved.data(),sizeof(profile)));
    std::puts("Speaker Fusion bridge PASS: independent capability/packets/audio, exact route bypass, master/reset invalidation, unchanged match profile and legacy65603 rejection.");
}
