// SPDX-License-Identifier: GPL-3.0-or-later
#include "../convolver/ConvolverControl.h"
#include "../viper/src/viper/utils/HeadphoneCrossfeed.h"
#include "../viper/src/viper/utils/ProfessionalReverb.h"
#include "../viper/src/viper/effects/DiffSurround.h"
extern "C" {
#include "jdsp/jdsp_header.h"
}
#undef min
#undef max
#include <algorithm>
#include <array>
#include <cassert>
#include <cmath>
#include <cstdio>
#include <memory>
#include <vector>
namespace {
void nativeRates(){
    auto core=std::make_unique<JamesDSPLib>();JamesDSPInit(core.get(),512,48000);
    JLimiterSetCoefficients(core.get(),-1.,1.5);
    for(unsigned rate:{44100u,48000u,96000u,192000u,384000u,768000u,48000u}){
        JamesDSPSetSampleRate(core.get(),float(rate),0);
        assert(core->fs==rate&&core->trueSampleRate==rate&&!core->enableASRC);
        assert(core->limiter.releaseMs==1.5);
        const auto expected=float(std::exp(-1000./(1.5*rate)));
        assert(core->limiter.relCoef==expected);
    }
    JamesDSPFree(core.get());
    std::puts("HiFi: real host==James processing Fs44.1..768k, ASRC disabled above44.1k and limiter release invariant across live rate changes passed.");
}
void wetDelay(){
    for(unsigned rate:{48000u,96000u,192000u,384000u,768000u}){
        auto s=FusionConvCreate();assert(s);FusionConvConfigure(s,rate);FusionConvLoaded(s,2,18824,345625875);FusionConvEnabled(s,1);
        const std::array<int32_t,10> p{1000,0,0,200000,0,0,0,0,0,0};assert(FusionConvSet(s,p.data(),p.size()));
        std::array<float,512> l{},r{},zero{};
        for(unsigned count=0;count<rate/50+512;count+=512){FusionConvBegin(s,zero.data(),zero.data(),512);FusionConvEnd(s,l.data(),r.data(),512);}
        const unsigned delay=rate/5,frames=delay+4;std::vector<float> left(frames),right(frames),dry(frames);left[0]=.5;right[0]=-.25;
        for(unsigned i=0;i<frames;i+=511){const unsigned n=std::min(511u,frames-i);FusionConvBegin(s,dry.data()+i,dry.data()+i,n);FusionConvEnd(s,left.data()+i,right.data()+i,n);}
        for(unsigned i=0;i<frames;++i){assert(left[i]==(i==delay?.5f:0.f));assert(right[i]==(i==delay?-.25f:0.f));}
        std::array<int32_t,32> status{};FusionConvSnapshot(s,status.data());assert(status[2]==int(rate)&&status[7]==int(delay)&&status[8]==200000&&status[6]==345625875);
        FusionConvDestroy(s);
    }
    std::puts("HiFi convolution: exact200ms wet-path delay48..768k including153600 samples, preserved actual frames/zero-kernel CRC and bounded callback chunks passed.");
}
void crossfeedDelay(){
    for(unsigned rate:{48000u,96000u,192000u,384000u,768000u}){
        headphone::Kernel k;auto settings=headphone::defaults;settings[1]=1;settings[5]=500;settings[7]=1;k.configure(settings,rate);
        const unsigned n=rate/2000;bool heard=false;
        for(unsigned i=0;i<n+20;++i){const float in[2]{i==0?.1f:0.f,0};float out[2],cross[2];k.process(in,out,cross);
            for(auto x:out)assert(std::isfinite(x));for(auto x:cross)assert(std::isfinite(x));
            if(i<n)assert(cross[1]==0);else heard|=cross[1]!=0;
        }
        assert(heard);
    }
    std::puts("HiFi headphone: real500us cross-path delay48..768k, no256-sample ring alias and finite output passed.");
}
void rateStorage(){
    for(unsigned rate:{48000u,96000u,192000u,384000u,768000u}){
        DiffSurround diff;diff.SetSamplingRate(rate);diff.SetDelayTime(50);diff.SetEnable(true);
        std::vector<float> data(2*(rate/20+4096));data[0]=.01f;data[1]=-.02f;
        for(size_t i=0;i<data.size()/2;i+=509)diff.Process(data.data()+2*i,uint32_t(std::min(size_t(509),data.size()/2-i)));
        assert(diff.GetRuntime(1)==int(rate/20*1000));for(float x:data)assert(std::isfinite(x));
        const std::array<int32_t,7> basic{1,2000,0,1000,1,150,12000};assert(diff.SetAdvanced(basic.data(),basic.size()));
        auto advanced=diffadvanced::defaults;advanced[0]=1;advanced[3]=80;assert(diff.SetSecondRound(advanced.data(),advanced.size()));
        std::fill(data.begin(),data.end(),0.f);data[0]=.01f;
        for(size_t i=0;i<data.size()/2;i+=509)diff.Process(data.data()+2*i,uint32_t(std::min(size_t(509),data.size()/2-i)));
        for(float x:data)assert(std::isfinite(x));
        reverbtuning::Kernel reverb;auto settings=reverbtuning::defaults;settings[1]=1;settings[7]=1;settings[8]=2000;reverb.configure(settings,rate);
        for(unsigned i=0;i<rate/5+4096;++i){const float in[2]{i==0?.01f:0.f,0};float out[2],wet[2];reverb.process(in,out,wet);for(float x:out)assert(std::isfinite(x));for(float x:wet)assert(std::isfinite(x));}
    }
    std::puts("HiFi bounded storage: differential50ms history/FFT and professionally-scaled reverb200ms predelay finite at48..768k passed.");
}
}
int main(){JamesDSPGlobalMemoryAllocation();nativeRates();wetDelay();crossfeedDelay();rateStorage();JamesDSPGlobalMemoryDeallocation();}
