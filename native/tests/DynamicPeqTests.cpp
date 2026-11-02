// SPDX-License-Identifier: GPL-3.0-or-later
#include "../dynamic_peq/DynamicPeqProcessor.h"
#include <algorithm>
#include <array>
#include <atomic>
#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <new>
#include <thread>
#include <vector>
using dspfusion::DynamicPeqProcessor;
namespace {std::atomic<unsigned> allocations{0};std::atomic<bool> watchAllocations{false};}
void* operator new(std::size_t n){if(watchAllocations.load())++allocations;if(auto* p=std::malloc(n))return p;throw std::bad_alloc();}
void* operator new[](std::size_t n){return ::operator new(n);}
void operator delete(void* p) noexcept{std::free(p);}void operator delete[](void* p) noexcept{std::free(p);}
void operator delete(void* p,std::size_t) noexcept{std::free(p);}void operator delete[](void* p,std::size_t) noexcept{std::free(p);}
namespace {
constexpr double pi=3.14159265358979323846;
using Packet=std::array<int32_t,15>;
Packet packet(int32_t frequency=1000000,int32_t gain=0){return {1,1,1,0,1,frequency,gain,2000,1,-24000,4000,12000,5000,100000,0};}
std::array<int32_t,40> status(DynamicPeqProcessor& p){std::array<int32_t,40> words{};p.readStatus(words.data());return words;}
int32_t fingerprint(const int32_t* words,size_t count){uint32_t hash=1;for(size_t i=0;i<count;++i)hash=31u*hash+uint32_t(words[i]);return int32_t(hash);}
void tone(DynamicPeqProcessor& p,unsigned rate,double frequency,double amplitude,unsigned frames,bool anti=false){
    std::array<float,512*2> block{};for(unsigned start=0;start<frames;start+=512){const auto n=std::min(512u,frames-start);
        for(unsigned i=0;i<n;++i){const float x=float(amplitude*std::sin(2*pi*frequency*(i+start)/rate));block[2*i]=x;block[2*i+1]=anti?-x:x;}
        p.process(block.data(),n);for(unsigned i=0;i<2*n;++i)assert(std::isfinite(block[i]));
    }
}
double measure(DynamicPeqProcessor& p,unsigned rate,double frequency,double amplitude){
    std::vector<float> block(rate*2);for(unsigned i=0;i<rate;++i)block[2*i]=block[2*i+1]=float(amplitude*std::sin(2*pi*frequency*i/rate));
    p.process(block.data(),rate);double power=0;for(unsigned i=rate/2;i<rate;++i)power+=double(block[2*i])*block[2*i];
    return std::sqrt(power/(rate/2));
}
void bypassAndValidation(){
    DynamicPeqProcessor p;std::array<float,8> data{0.f,-0.f,1e-38f,-1e-38f,.25f,-.25f,1.f,-1.f},original=data;
    p.process(data.data(),4);assert(std::memcmp(data.data(),original.data(),sizeof(data))==0);assert(status(p)[4]==0);
    auto cfg=packet();assert(p.setParameters(cfg.data(),cfg.size()));tone(p,48000,1000,.5,48000);
    const auto before=status(p);assert(before[24]>6000&&before[24]<=12000);
    for(unsigned word=0;word<cfg.size();++word){auto bad=cfg;bad[word]=INT32_MIN;assert(!p.setParameters(bad.data(),bad.size()));}
    assert(!p.setParameters(cfg.data(),cfg.size()-1));assert(!p.setParameters(nullptr,0));
    const auto after=status(p);assert(before==after);cfg[1]=0;assert(p.setParameters(cfg.data(),cfg.size()));
    data=original;p.process(data.data(),4);assert(std::memcmp(data.data(),original.data(),sizeof(data))==0);assert(status(p)[4]==before[4]);
    cfg[1]=1;cfg[2]=0;assert(p.setParameters(cfg.data(),3));p.process(data.data(),4);assert(std::memcmp(data.data(),original.data(),sizeof(data))==0);
}
void dynamicResponse(){
    DynamicPeqProcessor quiet,loud,away,antiphase;auto cfg=packet();
    for(auto* p:{&quiet,&loud,&away,&antiphase})assert(p->setParameters(cfg.data(),cfg.size()));
    tone(quiet,48000,1000,.005,48000);assert(status(quiet)[24]==0);
    tone(loud,48000,1000,.5,48000);const auto driven=status(loud);assert(driven[24]>=9000&&driven[24]<=12000);
    tone(away,48000,100,.1,48000);assert(status(away)[24]<1000);
    tone(antiphase,48000,1000,.5,48000,true);assert(std::abs(status(antiphase)[24]-driven[24])<5);
    tone(loud,48000,1000,0,96000);assert(status(loud)[24]<20);
    // Detector does not see the band's own static gain.
    DynamicPeqProcessor boost,cut;auto positive=cfg,negative=cfg;positive[6]=12000;negative[6]=-12000;
    assert(boost.setParameters(positive.data(),positive.size())&&cut.setParameters(negative.data(),negative.size()));
    tone(boost,48000,1000,.1,48000);tone(cut,48000,1000,.1,48000);
    assert(status(boost)[8]==status(cut)[8]&&status(boost)[24]==status(cut)[24]);
    // Above-threshold change must attenuate the target substantially while
    // preserving distant material and preserving the stereo ratio.
    DynamicPeqProcessor center,distant;assert(center.setParameters(cfg.data(),cfg.size()));assert(distant.setParameters(cfg.data(),cfg.size()));
    const double centerRms=measure(center,48000,1000,.5),distantRms=measure(distant,48000,100,.1);
    assert(centerRms<.12);assert(std::abs(distantRms-.1/std::sqrt(2.))<.003);
    // An earlier static boost must not feed the second band's sidechain.
    DynamicPeqProcessor cascade;std::array<int32_t,27> pair{1,1,2};auto first=cfg;first[6]=24000;first[8]=0;
    std::copy(first.begin()+3,first.end(),pair.begin()+3);std::copy(cfg.begin()+3,cfg.end(),pair.begin()+15);
    assert(cascade.setParameters(pair.data(),pair.size()));tone(cascade,48000,1000,.1,48000);
    assert(status(cascade)[9]==status(boost)[8]&&status(cascade)[25]==status(boost)[24]);
    // Stereo linking preserves inter-channel balance rather than independently
    // compressing the louder channel or moving the stereo image.
    std::vector<float> stereo(96000);for(unsigned i=0;i<48000;++i){stereo[2*i]=float(.5*std::sin(2*pi*1000*i/48000));stereo[2*i+1]=-.5f*stereo[2*i];}
    center.process(stereo.data(),48000);for(unsigned i=24000;i<48000;++i)assert(std::abs(stereo[2*i+1]+.5f*stereo[2*i])<1e-6f);
}
void timingAndKnee(){
    DynamicPeqProcessor fast,slow;auto cfg=packet(),other=cfg;cfg[12]=100;other[12]=500000;
    assert(fast.setParameters(cfg.data(),cfg.size())&&slow.setParameters(other.data(),other.size()));
    tone(fast,48000,1000,.5,480);tone(slow,48000,1000,.5,480);assert(status(fast)[24]>status(slow)[24]+6000);
    DynamicPeqProcessor shortRelease,longRelease;cfg=packet();other=cfg;cfg[13]=5000;other[13]=3000000;
    assert(shortRelease.setParameters(cfg.data(),cfg.size())&&longRelease.setParameters(other.data(),other.size()));
    tone(shortRelease,48000,1000,.5,48000);tone(longRelease,48000,1000,.5,48000);
    tone(shortRelease,48000,1000,0,4800);tone(longRelease,48000,1000,0,4800);assert(status(shortRelease)[24]==0&&status(longRelease)[24]>8000);
    DynamicPeqProcessor hard,soft;cfg=packet();other=cfg;other[14]=24000;
    assert(hard.setParameters(cfg.data(),cfg.size())&&soft.setParameters(other.data(),other.size()));
    tone(hard,48000,1000,.03,48000);tone(soft,48000,1000,.03,48000);
    assert(status(hard)[24]==0&&status(soft)[24]>100&&status(soft)[24]<3000);
}
void staticFiltersAndRates(){
    for(unsigned rate:{8000u,44100u,48000u,96000u,192000u,768000u})for(int type=0;type<3;++type){
        DynamicPeqProcessor p;p.configure(rate);auto cfg=packet(1000000,6000);cfg[3]=type;cfg[8]=0;
        assert(p.setParameters(cfg.data(),cfg.size()));const double rms=measure(p,rate,1000,.01);
        const double expectedDb=type==0?6.:3.;assert(std::abs(20*std::log10(rms/(.01/std::sqrt(2.)))-expectedDb)<.15);
        assert(status(p)[3]==int(rate)&&status(p)[24]==0);
    }
    DynamicPeqProcessor p;p.configure(8000);auto cfg=packet(20000000,6000);assert(p.setParameters(cfg.data(),cfg.size()));
    std::array<float,2048> input{};input.fill(.01f);const auto saved=input;p.process(input.data(),1024);
    assert(input==saved&&status(p)[4]==0&&status(p)[8]==-120000);
    p.configure(48000);tone(p,48000,20000,.1,48000);assert(status(p)[4]>0&&status(p)[8]>-120000);
    p.configure(8000);p.process(input.data(),1024);assert(input==saved&&status(p)[8]==-120000);
    p.reset();p.process(input.data(),1024);assert(input==saved);
}
void snapshotAndRealtime(){
    DynamicPeqProcessor p;auto cfg=packet();assert(p.setParameters(cfg.data(),cfg.size()));tone(p,48000,1000,.1,48000);
    const auto before=status(p);assert(p.setParameters(cfg.data(),cfg.size()));
    // Repeated identical SET and GET cannot reset envelope or advance frames.
    for(unsigned i=0;i<200;++i){assert(p.setParameters(cfg.data(),cfg.size()));assert(status(p)==before);}
    std::array<float,1024> block{};block.fill(.01f);
    allocations=0;watchAllocations=true;for(unsigned i=0;i<100;++i)p.process(block.data(),512);watchAllocations=false;assert(allocations==0);
    std::atomic<bool> done{false};std::thread reader([&]{while(!done){const auto s=status(p);assert(s[0]==1&&s[2]>=0&&s[2]<=16);for(unsigned i=24;i<40;++i)assert(s[i]>=0&&s[i]<=24000);}});
    for(unsigned i=0;i<500;++i){auto edit=cfg;edit[5]=int32_t(20000+(i*19237)%19980000);edit[6]=int32_t((i%49)*1000-24000);edit[7]=int32_t(100+(i*1381)%29900);assert(p.setParameters(edit.data(),edit.size()));p.process(block.data(),512);for(float x:block)assert(std::isfinite(x));}
    done=true;reader.join();
    // All 16 bands at protocol extremes, silence, rapid reset/rate edits.
    std::array<int32_t,DynamicPeqProcessor::kMaxWords> full{1,1,16};
    for(unsigned i=0;i<16;++i){auto band=packet();band[3]=i%3;band[5]=i%2?20000:20000000;band[6]=i%2?24000:-24000;band[7]=i%2?100:30000;band[9]=-90000;band[10]=20000;band[11]=24000;band[12]=100;band[13]=5000;band[14]=24000;std::copy(band.begin()+3,band.end(),full.begin()+3+i*12);}
    assert(p.setParameters(full.data(),full.size()));for(unsigned rate:{8000u,48000u,192000u,768000u}){p.configure(rate);p.reset();tone(p,rate,19.9,.000001,rate/10);tone(p,rate,1000,0,rate/10);}
}
void appliedFingerprintAndDormantUpdates(){
    DynamicPeqProcessor p;auto cfg=packet();std::array<int32_t,3> empty{1,1,0};
    assert(p.setParameters(empty.data(),empty.size()));std::array<float,1024> block{};
    p.process(block.data(),512);auto idle=status(p);assert(idle[4]==0&&idle[7]==fingerprint(empty.data(),empty.size()));
    assert(p.setParameters(cfg.data(),cfg.size()));assert(status(p)[7]==idle[7]);
    p.process(block.data(),512);assert(status(p)[7]==0);
    p.process(block.data(),512);assert(status(p)[7]==fingerprint(cfg.data(),cfg.size()));
    auto edit=cfg;edit[5]=2000000;assert(p.setParameters(edit.data(),edit.size()));assert(status(p)[7]==fingerprint(cfg.data(),cfg.size()));
    p.process(block.data(),512);assert(status(p)[7]==0);p.process(block.data(),512);assert(status(p)[7]==fingerprint(edit.data(),edit.size()));
    // No-op/disabled-band and above-Nyquist configurations must never trap the
    // next active snapshot behind a transition that cannot advance.
    DynamicPeqProcessor unavailable;auto absent=cfg;absent[4]=0;assert(unavailable.setParameters(absent.data(),absent.size()));unavailable.process(block.data(),512);
    assert(status(unavailable)[4]==0&&status(unavailable)[7]==fingerprint(absent.data(),absent.size()));
    assert(unavailable.setParameters(cfg.data(),cfg.size()));unavailable.process(block.data(),512);unavailable.process(block.data(),512);assert(status(unavailable)[7]==fingerprint(cfg.data(),cfg.size()));
}
}
int main(){bypassAndValidation();dynamicResponse();timingAndKnee();staticFiltersAndRates();snapshotAndRealtime();appliedFingerprintAndDormantUpdates();std::puts("Dynamic PEQ: atomic protocol rejection, exact bypass, selective stereo-linked RMS compression, attack/release/soft-knee, pre-module gain-independent detector, RBJ three-type response, rates/Nyquist/reset, state-preserving sync, no callback allocations, telemetry/fingerprint/dormant updates and rapid edits passed.");}
