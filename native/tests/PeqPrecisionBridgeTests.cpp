// SPDX-License-Identifier: GPL-3.0-or-later
#include "../peq/PeqProcessor.h"
#include "essential.h"
#include <algorithm>
#include <array>
#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <limits>
#include <vector>
extern "C" audio_effect_library_t AELI;
namespace {
constexpr effect_uuid_t uuid{0x30306067,0x9400,0x4d53,0x9000,{0,0,0,0,0,1}};
int32_t floatWord(float value){int32_t bits;std::memcpy(&bits,&value,4);return bits;}
struct Engine {
    effect_handle_t h{};
    explicit Engine(uint32_t rate){
        assert(AELI.create_effect(&uuid,0,0,&h)==0);effect_config_t c{};
        c.inputCfg.samplingRate=c.outputCfg.samplingRate=rate;
        c.inputCfg.channels=c.outputCfg.channels=AUDIO_CHANNEL_OUT_STEREO;
        c.inputCfg.format=c.outputCfg.format=AUDIO_FORMAT_PCM_FLOAT;
        c.inputCfg.accessMode=EFFECT_BUFFER_ACCESS_READ;c.outputCfg.accessMode=EFFECT_BUFFER_ACCESS_WRITE;
        c.inputCfg.mask=c.outputCfg.mask=EFFECT_CONFIG_ALL;
        assert(command(EFFECT_CMD_SET_CONFIG,sizeof(c),&c)==0);
        assert(set(131072,0)==0&&set(131073,0)==0&&command(EFFECT_CMD_ENABLE)==0);
    }
    ~Engine(){AELI.release_effect(h);}
    int command(uint32_t code,unsigned n=0,void* p=nullptr){
        int32_t status=0;uint32_t bytes=4;const int result=(*h)->command(h,code,n,p,&bytes,&status);return result?result:status;
    }
    int raw(uint32_t id,const void* data,unsigned n){
        std::vector<uint32_t> p((16+n+3)/4);p[1]=4;p[2]=n;p[3]=id;std::memcpy(p.data()+4,data,n);
        return command(EFFECT_CMD_SET_PARAM,16+n,p.data());
    }
    int set(uint32_t id,int32_t value){return raw(id,&value,4);}
    int get(uint32_t id){
        std::array<uint32_t,4> q{0,4,4,id};std::array<int32_t,5> response{};uint32_t bytes=20;
        assert((*h)->command(h,EFFECT_CMD_GET_PARAM,16,q.data(),&bytes,response.data())==0);
        assert(bytes==20&&response[0]==0);return response[4];
    }
    std::vector<float> render(std::vector<float> input){
        std::vector<float> result(input.size());
        for(size_t frame=0;frame<input.size()/2;frame+=333){
            audio_buffer_t in{},out{};in.frameCount=out.frameCount=std::min(size_t(333),input.size()/2-frame);
            in.f32=input.data()+frame*2;out.f32=result.data()+frame*2;assert((*h)->process(h,&in,&out)==0);
        }
        return result;
    }
};
}
std::array<int32_t,9> precise(int type,double q,int32_t frequency=453000){
    uint64_t bits;std::memcpy(&bits,&q,8);
    return {3,1,1,type,1,frequency,100000,int32_t(bits),int32_t(bits>>32)};
}
int main(){
    unsigned cases=0;
    for(uint32_t rate:{48000u,96000u,192000u,384000u,768000u})for(int type=0;type<=6;++type)
        for(double q:{.0001,100.,29999.0001,30000.}){
            std::fprintf(stderr,"ABI case rate=%u type=%d Q=%.9g create\n",rate,type,q);
            Engine engine(rate);assert((engine.get(131234)&15)==15&&engine.get(131080)==5);
            assert(engine.get(131082)==int(rate)&&engine.get(20001)==int(rate));
            std::fprintf(stderr,"ABI configured\n");
            auto p=precise(type,q);assert(engine.raw(1250,p.data(),sizeof(p))==0);
            const double nan=std::numeric_limits<double>::quiet_NaN();uint64_t bits;std::memcpy(&bits,&nan,8);
            auto invalid=p;invalid[7]=int32_t(bits);invalid[8]=int32_t(bits>>32);
            assert(engine.raw(1250,invalid.data(),sizeof(invalid))==-EINVAL);
            assert(engine.raw(1250,p.data(),sizeof(p)-1)==-EINVAL);
            dspfusion::PeqProcessor reference;reference.configure(rate);assert(reference.setParameters(p.data(),p.size()));
            const unsigned frames=rate/50+4096;std::vector<float> input(frames*2);
            for(unsigned i=0;i<frames;++i){input[2*i]=float(1e-10*std::sin(6.283185307179586*453*i/rate));input[2*i+1]=float(1e-10*std::cos(6.283185307179586*731*i/rate));}
            auto expected=input;for(size_t frame=0;frame<input.size()/2;frame+=333)reference.process(expected.data()+2*frame,uint32_t(std::min(size_t(333),input.size()/2-frame)));
            std::fprintf(stderr,"ABI reference rendered\n");
            const auto actual=engine.render(input);
            std::fprintf(stderr,"ABI actual rendered\n");
            for(size_t i=0;i<actual.size();++i){assert(std::isfinite(actual[i]));assert(std::abs(double(actual[i])-expected[i])<1e-9);}
            ++cases;
        }
    Engine legacy(48000);const std::array<int32_t,8> p{1,1,1,0,1,453000,12000,1000};
    assert(legacy.raw(1250,p.data(),sizeof(p))==0);
    auto schema2=p;schema2[0]=2;schema2[7]=floatWord(.0001f);assert(legacy.raw(1250,schema2.data(),sizeof(schema2))==0);
    std::vector<int32_t> full{3,1,170};for(unsigned i=0;i<170;++i){const auto b=precise(int(i%7),.0001);full.insert(full.end(),b.begin()+3,b.end());}
    assert(full.size()*4==4092&&legacy.raw(1250,full.data(),uint32_t(full.size()*4))==0);
    const auto output=legacy.render(std::vector<float>(8192,.000001f));for(float sample:output)assert(std::isfinite(sample));
    // Test Nyquist bypass independently of the prior intentional 170-band
    // overload: its final output protector correctly retains a release tail.
    Engine nyquist(48000);
    auto future=precise(0,100.,768000000);assert(nyquist.raw(1250,future.data(),sizeof(future))==0);
    const auto bypass=nyquist.render(std::vector<float>(12000,.000001f));assert(bypass.back()==.000001f);
    std::printf("PEQ precision ABI: capability bit8, schemas1/2 legacy, full4092-byte schema3 DoubleQ, real Nyquist bypass, invalid atomic rejection and %u seven-type live-command/processor waveform matches at48..768k passed.\n",cases);
}
