// SPDX-License-Identifier: GPL-3.0-or-later
#include "SpeakerOptimizationProcessor.h"
#include "../viper/src/viper/utils/Biquad.h"
#include "../viper/src/viper/utils/MultiBiquad.h"
#include <algorithm>
#include <array>
#include <atomic>
#include <cassert>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <new>
#include <thread>
#include <vector>
namespace {std::atomic<unsigned> allocations{0};thread_local bool watch=false;}
void* operator new(std::size_t n){if(watch)++allocations;if(auto*p=std::malloc(n))return p;throw std::bad_alloc();}
void* operator new[](std::size_t n){return ::operator new(n);}
void operator delete(void*p)noexcept{std::free(p);}void operator delete[](void*p)noexcept{std::free(p);}
void operator delete(void*p,std::size_t)noexcept{std::free(p);}void operator delete[](void*p,std::size_t)noexcept{std::free(p);}
namespace {
using Processor=dspfusion::SpeakerOptimizationProcessor;using Packet=std::array<int32_t,128>;
constexpr double pi=3.1415926535897932384626433832795;
Packet settings(bool on=true,bool route=true){Packet p{1,on?1:0,1000,80000,1000,13500000,1000,420000,3880,1000,500,0,0,route?1000:0};return p;}
Packet status(Processor& p,bool renew=true){Packet s{};p.readStatus(s.data(),renew);return s;}
int32_t fingerprint(const Packet& p){uint32_t h=1;for(size_t i=0;i<p.size();++i)h=h*31+(i==13?0u:uint32_t(p[i]));return int32_t(h);}
std::vector<float> tone(unsigned rate,double seconds,double frequency=420,bool anti=false){std::vector<float>x(size_t(seconds*rate)*2);for(size_t i=0;i<x.size()/2;++i){x[2*i]=float(.1*std::sin(2*pi*frequency*i/rate));x[2*i+1]=(anti?-1.f:1.f)*x[2*i];}return x;}
void render(Processor&p,std::vector<float>&x,unsigned block=257){for(size_t start=0;start<x.size()/2;start+=block){watch=true;p.process(x.data()+start*2,uint32_t(std::min(size_t(block),x.size()/2-start)));watch=false;}}
double rms(const std::vector<float>&x,size_t first=0){double sum=0;for(size_t i=first*2;i<x.size();i+=2)sum+=double(x[i])*x[i];return std::sqrt(sum/(x.size()/2-first));}
void finite(const std::vector<float>&x){for(float v:x)assert(std::isfinite(v));}
// Exact upstream SpeakerCorrection::Reset/Process, using the existing original
// ViPER Biquad classes, independent of the new processor's coefficient code.
struct LegacyReference {
    std::array<Biquad,2> lp,bp;std::array<MultiBiquad,2> hp;
    explicit LegacyReference(unsigned sr){for(size_t c=0;c<2;++c){hp[c].RefreshFilter(MultiBiquad::HIGH_PASS,0.f,80.f,sr,1.f,false);lp[c].SetLowPassParameter(13500.f,sr,1.f);bp[c].SetBandPassParameter(420.f,sr,3.88f);}}
    void render(std::vector<float>&x){for(size_t n=0;n<x.size();n+=2)for(size_t c=0;c<2;++c){double out=lp[c].ProcessSample(x[n+c]);out=hp[c].ProcessSample(out);out/=2.;out+=bp[c].ProcessSample(out);x[n+c]=float(out);}}
};
void classic(){
    for(unsigned sr:{44100u,48000u,96000u,192000u}){
        Processor p;p.configure(sr);const auto config=settings();assert(p.setParameters(config.data(),config.size()));auto x=tone(sr,.7,420,true);for(size_t i=0;i<x.size()/2;++i){x[2*i]+=float(.04*std::sin(2*pi*5100*i/sr));x[2*i+1]+=float(.03*std::cos(2*pi*73*i/sr));}auto expected=x;LegacyReference reference(sr);reference.render(expected);render(p,x);
        double maxError=0;for(size_t i=sr/20*2;i<x.size();++i)maxError=std::max(maxError,std::abs(double(x[i])-expected[i]));std::printf("classic upstream %u Hz max error %.12g\n",sr,maxError);assert(maxError<1e-7);
        const auto s=status(p);assert(s[4]==fingerprint(config)&&s[17]==1000&&s[18]==0);
    }
}
void bypassAndRoute(){
    for(int gainDb:{-24000,12000}){Processor fade;auto config=settings();config[11]=gainDb;assert(fade.setParameters(config.data(),128));std::array<float,2> first{.25f,.25f};fade.process(first.data(),1);assert(std::abs(first[0]-.25f)<.002f&&std::abs(first[1]-.25f)<.002f);}
    Processor p;std::vector<float>x{0,-0.f,1e-38f,-1e-38f,.2f,-.4f,1,-1},original=x;render(p,x);assert(!std::memcmp(x.data(),original.data(),x.size()*4));
    auto c=settings(true,false);c[11]=12000;assert(p.setParameters(c.data(),128));x=original;render(p,x);assert(!std::memcmp(x.data(),original.data(),x.size()*4));auto s=status(p);assert(s[16]==0&&s[17]==0&&s[24]==0);
    c=settings();assert(p.setParameters(c.data(),128));auto warm=tone(48000,.1);render(p,warm);const int32_t oldHash=status(p)[4];c[13]=0;assert(p.setParameters(c.data(),128));x=original;render(p,x);s=status(p);assert(!std::memcmp(x.data(),original.data(),x.size()*4)&&s[17]==0&&s[19]==0&&s[4]==oldHash);
    c[13]=1000;assert(p.setParameters(c.data(),128));std::vector<float> silence(2048);render(p,silence);assert(rms(silence)==0); // no stale filter tail after route reconnect
    c[2]=0;assert(p.setParameters(c.data(),128));warm=tone(48000,.05);render(p,warm);x=original;render(p,x);assert(!std::memcmp(x.data(),original.data(),x.size()*4));
    c[11]=6000;assert(p.setParameters(c.data(),128));warm=tone(48000,.05);render(p,warm);auto gain=tone(48000,.05);original=gain;render(p,gain);assert(std::abs(rms(gain)/rms(original)-std::pow(10.,.3))<1e-6);
    c[1]=0;assert(p.setParameters(c.data(),128));x=original;render(p,x);assert(x==original);
    std::puts("default/off/route/mix bypass exact; route reconnection clears history; mix0 preserves output gain");
}
void controlsAndRates(){
    // At the compensation center, Q controls width but not branch peak strength.
    std::array<double,4> levels{};size_t qindex=0;
    for(int q:{300,1000,3880,10000}){Processor p;auto c=settings();c[8]=q;assert(p.setParameters(c.data(),128));auto x=tone(48000,1.2);render(p,x);levels[qindex++]=rms(x,48000/2);}
    for(double a:levels)assert(std::abs(a/levels[0]-1)<1e-5);
    for(unsigned rate:{8000u,16000u,24000u,32000u,44100u,48000u,96000u,192000u,768000u}){Processor p;p.configure(rate);auto c=settings();assert(p.setParameters(c.data(),128));auto x=tone(rate,.12,700);render(p,x);finite(x);auto s=status(p);assert(s[18]==(13500>rate*.48?1000:0));assert(s[21]==int32_t(std::lround(std::min(13500.,rate*.48)*1000)));
        for(int maximum=0;maximum<2;++maximum){c[3]=maximum?1000000:20000;c[4]=maximum?2000:500;c[5]=maximum?22000000:2000000;c[6]=maximum?2000:500;c[7]=maximum?4000000:100000;c[8]=maximum?10000:300;c[9]=maximum?2000:0;c[10]=maximum?1000:0;c[11]=maximum?12000:-24000;assert(p.setParameters(c.data(),128));x=tone(rate,.08,1100);render(p,x,79);finite(x);for(float y:x)assert(std::abs(y)<100);}
    }
    std::puts("independent body Q/amount peak; cutoff adaptation and extreme controls finite at 9 rates");
}
void observation(){
    Processor p;auto c=settings();assert(p.setParameters(c.data(),128));auto x=tone(48000,.15,937.5,true);render(p,x);assert(status(p)[19]==0); // processing without GET did not run FFT
    std::vector<float> partial(2048);render(p,partial);assert(status(p)[19]==0);
    x=tone(48000,.16,937.5,true);render(p,x);auto s=status(p);assert(s[19]>0);
    double strongest=-120;size_t jmax=0;for(size_t j=0;j<48;++j)if(s[32+j]>strongest*1000){strongest=s[32+j]/1000.;jmax=j;}
    // Pick a reported bin, avoiding accidental assertions about interpolation.
    const double wanted=20*std::pow(1000.,double(jmax)/47);const size_t bin=size_t(std::llround(wanted*2048/48000));
    x=tone(48000,.16,bin*48000./2048,true);render(p,x);s=status(p);assert(std::abs(s[32+jmax]/1000.+20)<.02); // stereo anti-phase .1 peak remains -20dBFS
    const auto firstCount=s[19];auto repeated=status(p);assert(repeated[19]==firstCount&&repeated[3]==s[3]);
    c[9]=500;assert(p.setParameters(c.data(),128));std::vector<float> shortAudio(200);render(p,shortAudio);assert(status(p)[19]==0);
    x=tone(48000,.13);render(p,x);assert(status(p)[19]>0);
    std::this_thread::sleep_for(std::chrono::milliseconds(1550));x=tone(48000,.1);render(p,x);assert(status(p)[19]==0);render(p,shortAudio);assert(status(p)[19]==0);render(p,x);assert(status(p)[19]>0);
    // Unlike the preceding case, no callback notices lease expiry during the
    // pause. A GET renews before resumption; it must not revive the old window.
    std::this_thread::sleep_for(std::chrono::milliseconds(1550));assert(status(p)[19]==0);render(p,shortAudio);assert(status(p)[19]==0);render(p,x);assert(status(p)[19]>0);
    // Even a still-live observation lease cannot join audio across a stream gap.
    std::this_thread::sleep_for(std::chrono::milliseconds(350));assert(status(p)[19]==0);render(p,shortAudio);assert(status(p)[19]==0);render(p,x);assert(status(p)[19]>0);
    auto masterOff=status(p,false);assert(masterOff[17]==0&&masterOff[19]==0&&masterOff[5]==-120000);
    std::puts("FFT is GET-leased, window-gated, stereo-power calibrated, fresh per edit and observational on GET");
}
void validationAndContinuity(){
    Processor p;auto c=settings();assert(p.setParameters(c.data(),128));auto wrong=c;wrong[0]=2;assert(!p.setParameters(wrong.data(),128));wrong=c;wrong[1]=2;assert(!p.setParameters(wrong.data(),128));wrong=c;wrong[127]=1;assert(!p.setParameters(wrong.data(),128));wrong=c;wrong[12]=500;assert(!p.setParameters(wrong.data(),128));wrong=c;wrong[13]=1;assert(!p.setParameters(wrong.data(),128));assert(!p.setParameters(c.data(),127));
    Processor a,b;assert(a.setParameters(c.data(),128)&&b.setParameters(c.data(),128));auto x=tone(48000,.3,1100),y=x;render(a,x,1);render(b,y,512);assert(x==y);
    for(int i=0;i<20;++i){assert(a.setParameters(c.data(),128));x=tone(48000,.01,700);y=x;render(a,x);render(b,y);assert(x==y);}
    std::atomic<bool>done{false};std::thread writer([&]{for(int i=0;i<300;++i){auto q=c;q[8]=300+(i*31)%9701;q[13]=(i%4)?1000:0;assert(p.setParameters(q.data(),128));}done=true;});std::array<float,1024> block{};while(!done){watch=true;p.process(block.data(),512);watch=false;for(float v:block)assert(std::isfinite(v));}writer.join();
    p.reset();auto s=status(p);assert(s[3]==0&&s[4]==0&&s[19]==0);
    std::puts("packet validation, exact block-size invariance, repeated sync continuity, concurrent setters and reset pass");
}
}
int main(){classic();bypassAndRoute();controlsAndRates();observation();validationAndContinuity();assert(allocations==0);std::printf("Speaker optimization DSP PASS; audio-thread allocations=%u\n",allocations.load());}
