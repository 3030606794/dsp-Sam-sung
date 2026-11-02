#include <algorithm>
#include <array>
#include <cassert>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>
#include "essential.h"
extern "C" audio_effect_library_t AELI;
namespace {
constexpr effect_uuid_t uuid{0x30306067,0x9400,0x4d53,0x9000,{0,0,0,0,0,1}};
constexpr double pi=3.14159265358979323846;
int command(effect_handle_t h,uint32_t cmd,uint32_t bytes=0,void* data=nullptr){int status=-1;uint32_t size=4;int rc=(*h)->command(h,cmd,bytes,data,&size,&status);return rc?rc:status;}
void set(effect_handle_t h,uint32_t id,int32_t value){std::array<uint32_t,5> p{0,4,4,id,uint32_t(value)};assert(command(h,EFFECT_CMD_SET_PARAM,sizeof(p),p.data())==0);}
int get(effect_handle_t h,uint32_t id){std::array<uint32_t,5> p{0,4,4,id,0},r{};uint32_t size=20;assert((*h)->command(h,EFFECT_CMD_GET_PARAM,16,p.data(),&size,r.data())==0&&r[0]==0&&size==20);return int(r[4]);}
void configure(effect_handle_t h,unsigned rate,uint8_t format=AUDIO_FORMAT_PCM_FLOAT,uint8_t access=EFFECT_BUFFER_ACCESS_WRITE){effect_config_t c{};c.inputCfg.samplingRate=c.outputCfg.samplingRate=rate;c.inputCfg.channels=c.outputCfg.channels=AUDIO_CHANNEL_OUT_STEREO;c.inputCfg.format=c.outputCfg.format=format;c.inputCfg.accessMode=EFFECT_BUFFER_ACCESS_READ;c.outputCfg.accessMode=access;c.inputCfg.mask=c.outputCfg.mask=EFFECT_CONFIG_ALL;assert(command(h,EFFECT_CMD_SET_CONFIG,sizeof(c),&c)==0);}
std::vector<float> render(unsigned rate,unsigned block,unsigned bassMode,int session){
    effect_handle_t h=nullptr;assert(AELI.create_effect(&uuid,session,0,&h)==0);configure(h,rate);
    assert(get(h,131082)==int(rate)&&get(h,20001)==int(rate));
    // Retain both real engines; bass is downstream of James' limiter.
    set(h,65574,1);set(h,65575,bassMode);set(h,65576,70);set(h,65577,336);
    assert(command(h,EFFECT_CMD_ENABLE)==0);
    std::vector<float> output(rate*4),input(block*2+16),part(block*2+16);
    double maxMs=0;const auto started=std::chrono::steady_clock::now();
    for(unsigned at=0;at<rate*2;){unsigned n=std::min(block,rate*2-at);
        std::fill(input.begin(),input.end(),12345.f);std::fill(part.begin(),part.end(),12345.f);
        for(unsigned i=0;i<n;++i){const float v=.75f*std::sin(2*pi*55*(at+i)/rate)+.05f*std::sin(2*pi*9000*(at+i)/rate);input[2*i]=v;input[2*i+1]=v*.9f;}
        audio_buffer_t a{},b{};a.frameCount=b.frameCount=n;a.f32=input.data();b.f32=part.data();const auto t=std::chrono::steady_clock::now();assert((*h)->process(h,&a,&b)==0);maxMs=std::max(maxMs,std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-t).count());
        for(unsigned i=0;i<n*2;++i)assert(std::isfinite(part[i])&&std::abs(part[i])<=1.f);
        for(unsigned i=n*2;i<part.size();++i)assert(part[i]==12345.f&&input[i]==12345.f);
        std::copy_n(part.data(),n*2,output.data()+at*2);at+=n;
    }
    assert(get(h,131083)==int(rate*2));
    const auto ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-started).count();
    double peak=0;unsigned flat=0;for(float v:output){peak=std::max(peak,std::abs(double(v)));flat+=std::abs(v)>=1;}
    assert(peak>.05&&peak<1&&flat==0);
    // Changing rates must retain host==engine, including global48 and HD192.
    configure(h,rate==48000?192000:48000);assert(get(h,20001)==(rate==48000?192000:48000));
    assert(AELI.release_effect(h)==0);
    std::printf("AELI session=%d rate=%u block=%u bass=%u peak=%.7f hardclipped=%u audioSeconds=2 elapsedMs=%.2f maxCallbackMs=%.3f PASS\n",session,rate,block,bassMode,peak,flat,ms,maxMs);fflush(stdout);return output;
}
void accumulatedOutput(){
    for(unsigned rate:{48000u,192000u})for(uint8_t format:{uint8_t(AUDIO_FORMAT_PCM_FLOAT),uint8_t(AUDIO_FORMAT_PCM_16_BIT)}){
        effect_handle_t h=nullptr;assert(AELI.create_effect(&uuid,4041,0,&h)==0);
        configure(h,rate,format,EFFECT_BUFFER_ACCESS_ACCUMULATE);set(h,131072,0);set(h,131073,0);
        assert(command(h,EFFECT_CMD_ENABLE)==0);
        std::array<float,2> in{.8f,.4f},out{.7f,.35f};std::array<int16_t,2> in16{26214,13107},out16{22938,11469};
        audio_buffer_t a{},b{};a.frameCount=b.frameCount=1;
        if(format==AUDIO_FORMAT_PCM_FLOAT){a.f32=in.data();b.f32=out.data();}else{a.s16=in16.data();b.s16=out16.data();}
        assert((*h)->process(h,&a,&b)==0);
        const double left=format==AUDIO_FORMAT_PCM_FLOAT?out[0]:out16[0]/32768.,right=format==AUDIO_FORMAT_PCM_FLOAT?out[1]:out16[1]/32768.;
        assert(left>.999&&left<1. && std::abs(right-left*.5)<4.e-5);
        assert(AELI.release_effect(h)==0);
    }
    std::puts("ACCUMULATE:48/192k float+PCM16 sum protected before conversion; stereo ratio preserved without integer hard clipping.");
}
}
int main(){
    for(unsigned rate:{48000u,192000u})for(unsigned mode=0;mode<3;++mode){auto a=render(rate,512,mode,rate==48000?0:4041);auto b=render(rate,rate/50,mode,rate==48000?0:4041);double error=0;for(size_t i=0;i<a.size();++i)error=std::max(error,std::abs(double(a[i])-b[i]));assert(error<2e-6);}
    // Higher rates exercise the exact same endpoint protection, not a 48k proxy.
    render(384000,7680,0,4041);render(768000,15360,0,4041);
    accumulatedOutput();
}
