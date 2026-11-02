// SPDX-License-Identifier: GPL-3.0-or-later
#include "../ddc/DdcProcessor.h"
#include "essential.h"
extern "C" {
#include "jdsp/jdsp_header.h"
extern audio_effect_library_t AELI;
}
#undef min
#undef max
#include <algorithm>
#include <array>
#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>
#include <string>

namespace {
// A known non-identity file makes a silently bypassed real processing path fail.
char source[]="SR_44100:0.5,0,0,0,0\nSR_48000:0.5,0,0,0,0";
std::array<int32_t,8> sha{};
void checkStatus(const std::array<int32_t,80>& s,unsigned rate) {
    assert(s[3]&&s[5]&&!s[50]&&s[2]==int(rate)&&s[16]==int(rate)&&s[17]==int(rate));
    assert(s[18]==1);
    assert(std::equal(sha.begin(),sha.end(),s.begin()+36));
    assert(std::equal(sha.begin(),sha.end(),s.begin()+62));
}
void renderJames(JamesDSPLib* core) {
    std::array<float,512> left{},right{},outLeft{},outRight{};
    for(size_t i=0;i<left.size();++i){left[i]=float(.01*std::sin(.07*i));right[i]=float(.008*std::cos(.13*i));}
    core->processFloatDeinterleaved(core,left.data(),right.data(),outLeft.data(),outRight.data(),left.size());
    for(size_t i=0;i<left.size();++i){
        assert(std::isfinite(outLeft[i])&&std::isfinite(outRight[i]));
        assert(std::abs(outLeft[i]-.5f*left[i])<1e-6f);
        assert(std::abs(outRight[i]-.5f*right[i])<1e-6f);
    }
}
void jamesEntry() {
    for(unsigned initial:{48000u,768000u}) {
        auto core=std::make_unique<JamesDSPLib>();JamesDSPInit(core.get(),512,float(initial));
        assert(DDCEnable(core.get(),1)==-1&&!core->ddcEnabled);
        assert(DDCStringParser(core.get(),source)==1);
        assert(DDCEnable(core.get(),1)==1&&core->ddcEnabled);
        for(unsigned rate:{initial,44100u,48000u,96000u,192000u,384000u,768000u,48000u}) {
            JamesDSPSetSampleRate(core.get(),float(rate),0);
            // SET_CONFIG alone must retain power; do not conceal a lost enable by resending it.
            assert(core->ddcEnabled&&core->fs==rate&&core->trueSampleRate==rate&&!core->enableASRC);
            std::array<int32_t,80> status{};DdcStatus(core->vdcFl.professional,status.data());checkStatus(status,rate);
            renderJames(core.get());
            core->ddcForceRefresh=1;
            assert(DDCEnable(core.get(),1)==1&&!core->ddcForceRefresh);
            renderJames(core.get());
        }
        JamesDSPFree(core.get());
    }
    std::puts("DDC real James entry: first upload at768k, legacy enable, control refresh, live44.1..768k configuration, exact half-gain audible processing and unchanged source SHA passed.");
}
struct Effect {
    effect_handle_t handle{};
    Effect(){effect_uuid_t uuid{0x30306067,0x9400,0x4d53,0x9000,{0,0,0,0,0,1}};assert(AELI.create_effect(&uuid,0,0,&handle)==0);}
    ~Effect(){AELI.release_effect(handle);}
    int command(unsigned id,unsigned bytes=0,void* data=nullptr){int32_t result=0;unsigned n=4;const int rc=(*handle)->command(handle,id,bytes,data,&n,&result);return rc?rc:result;}
    int set(unsigned id,const void* data,unsigned bytes){std::array<uint32_t,80> p{};p[1]=4;p[2]=bytes;p[3]=id;std::memcpy(p.data()+4,data,bytes);return command(EFFECT_CMD_SET_PARAM,16+bytes,p.data());}
    void configure(unsigned rate){effect_config_t c{};c.inputCfg.samplingRate=c.outputCfg.samplingRate=rate;c.inputCfg.channels=c.outputCfg.channels=AUDIO_CHANNEL_OUT_STEREO;c.inputCfg.format=c.outputCfg.format=AUDIO_FORMAT_PCM_FLOAT;c.inputCfg.accessMode=EFFECT_BUFFER_ACCESS_READ;c.outputCfg.accessMode=EFFECT_BUFFER_ACCESS_WRITE;c.inputCfg.mask=c.outputCfg.mask=EFFECT_CONFIG_ALL;assert(command(EFFECT_CMD_SET_CONFIG,sizeof(c),&c)==0);}
    std::array<int32_t,80> status(){std::array<uint32_t,4> q{0,4,320,131238};std::array<uint32_t,84> reply{};unsigned bytes=sizeof(reply);assert((*handle)->command(handle,EFFECT_CMD_GET_PARAM,sizeof(q),q.data(),&bytes,reply.data())==0);assert(!reply[0]&&bytes==sizeof(reply));std::array<int32_t,80>s{};std::memcpy(s.data(),reply.data()+4,320);return s;}
    void render(){std::array<float,1024> input{},output{};for(size_t i=0;i<input.size();++i)input[i]=float(.01*std::sin(.07*i));audio_buffer_t a{},b{};a.frameCount=b.frameCount=512;a.f32=input.data();b.f32=output.data();assert((*handle)->process(handle,&a,&b)==0);for(float x:output)assert(std::isfinite(x));}
};
void effectEntry() {
    Effect effect;effect.configure(768000);assert(effect.command(EFFECT_CMD_ENABLE)==0);
    const std::array<int32_t,2> metadata{1,256};std::array<char,256> block{};std::memcpy(block.data(),source,(sizeof(source)-1));
    const int16_t zero=0,one=1;
    assert(effect.set(8888,metadata.data(),8)==0&&effect.set(12001,block.data(),256)==0&&effect.set(10009,&zero,2)==0);
    assert(effect.set(1212,&one,2)==0);
    for(unsigned rate:{768000u,48000u,96000u,192000u,384000u,768000u,44100u}) {
        effect.configure(rate);effect.render();checkStatus(effect.status(),rate);
    }
    assert(effect.set(1212,&zero,2)==0&&!effect.status()[3]);
    std::puts("DDC real Fusion command entry: 8888/12001/10009 upload,1212 power,131238 CRC/rate/ready and SET_CONFIG power retention at44.1..768k passed.");
}
}
int main(){std::setvbuf(stdout,nullptr,_IONBF,0);DdcSha256(source,(sizeof(source)-1),sha.data());JamesDSPGlobalMemoryAllocation();jamesEntry();effectEntry();JamesDSPGlobalMemoryDeallocation();}
