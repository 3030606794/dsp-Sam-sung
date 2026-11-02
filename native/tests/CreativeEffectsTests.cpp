// SPDX-License-Identifier: GPL-3.0-or-later
#include "../creative/CreativeEffectsProcessor.h"
#include <algorithm>
#include <array>
#include <atomic>
#include <cassert>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <new>
#include <thread>
#include <vector>
using dspfusion::CreativeEffectsProcessor;
namespace {std::atomic<unsigned> allocations{0};std::atomic<bool> watch{false};}
void* operator new(std::size_t n){if(watch.load())++allocations;if(auto* p=std::malloc(n))return p;throw std::bad_alloc();}
void* operator new[](std::size_t n){return ::operator new(n);}
void operator delete(void* p)noexcept{std::free(p);}void operator delete[](void* p)noexcept{std::free(p);}
void operator delete(void* p,std::size_t)noexcept{std::free(p);}void operator delete[](void* p,std::size_t)noexcept{std::free(p);}
namespace {
constexpr double pi=3.14159265358979323846;using Packet=std::array<int32_t,12>;
const Packet defaults[3]={{1,0,350,300,80,18,120,700,250,300,700,10000},{1,0,500,0,0,12000,120,600,0,120,700,12000},{1,0,300,650,6500,30,1200,500,7000,800,700,120}};
std::array<int32_t,32> status(CreativeEffectsProcessor& p,unsigned kind){std::array<int32_t,32>s{};p.readStatus(kind,s.data());return s;}
int32_t fingerprint(const Packet& p){uint32_t h=1;for(int32_t x:p)h=31*h+uint32_t(x);return int32_t(h);}
void set(CreativeEffectsProcessor& p,unsigned k,const Packet& c){assert(p.setParameters(k,c.data(),c.size()));}
std::vector<float> sine(unsigned rate,double hz,double seconds,double amplitude=.2){const unsigned frames=unsigned(rate*seconds);std::vector<float>x(frames*2);for(unsigned i=0;i<frames;++i)x[2*i]=x[2*i+1]=float(amplitude*std::sin(2*pi*hz*i/rate));return x;}
void render(CreativeEffectsProcessor& p,std::vector<float>& x){for(size_t f=0;f<x.size()/2;f+=257){watch=true;p.process(x.data()+2*f,uint32_t(std::min(size_t(257),x.size()/2-f)));watch=false;}for(float v:x)assert(std::isfinite(v));}
double rms(const std::vector<float>& x,size_t start=0){double power=0;for(size_t i=start*2;i<x.size();i+=2)power+=double(x[i])*x[i];return std::sqrt(power/std::max(size_t(1),x.size()/2-start));}
double component(const std::vector<float>& x,unsigned sr,double hz,size_t start=0){double real=0,imag=0;const size_t n=x.size()/2-start;for(size_t i=0;i<n;++i){const double w=.5-.5*std::cos(2*pi*i/n),v=x[2*(i+start)]*w;real+=v*std::cos(2*pi*hz*i/sr);imag+=v*std::sin(2*pi*hz*i/sr);}return 4*std::hypot(real,imag)/n;}
void bypassValidation(){CreativeEffectsProcessor p;std::array<float,8>x{0,-0.f,1e-38f,-1e-38f,.25f,-.25f,1,-1},original=x;p.process(x.data(),4);assert(std::memcmp(x.data(),original.data(),sizeof(x))==0);
    for(unsigned k=0;k<3;++k){auto c=defaults[k];c[1]=1;c[2]=0;set(p,k,c);x=original;p.process(x.data(),4);assert(std::memcmp(x.data(),original.data(),sizeof(x))==0);assert(status(p,k)[4]==fingerprint(c));for(unsigned word=0;word<c.size();++word){auto bad=c;bad[word]=INT32_MIN;assert(!p.setParameters(k,bad.data(),bad.size()));}assert(!p.setParameters(k,c.data(),11));c[1]=0;set(p,k,c);}
    assert(!p.setParameters(3,defaults[0].data(),12));assert(!p.setParameters(0,nullptr,12));
    // Enabled freeze with no captured material is transparent at any mix.
    auto c=defaults[1];c[1]=1;c[2]=1000;set(p,1,c);auto dry=sine(48000,997,.2),out=dry;render(p,out);assert(out==dry);assert(status(p,1)[8]==0);
}
void granularAudio(){CreativeEffectsProcessor p;auto c=defaults[0];c[1]=1;c[2]=1000;c[3]=100;c[4]=200;c[5]=10;c[6]=0;c[7]=1200;c[8]=0;c[9]=0;c[10]=0;c[11]=18000;set(p,0,c);
    auto input=sine(48000,440,1.2),output=input;render(p,output);const double shifted=component(output,48000,880,24000),original=component(output,48000,440,24000);std::printf("granular shifted880=%.7f original440=%.7f rms=%.6f\n",shifted,original,rms(output,24000));assert(shifted>.01&&shifted>original*5);assert(status(p,0)[8]>0&&status(p,0)[7]>-60000);
    c[8]=1000;c[7]=-700;c[6]=170;c[9]=850;set(p,0,c);auto reverse=sine(48000,713,.8);render(p,reverse);assert(rms(reverse,12000)>.001);
    c[1]=0;set(p,0,c);auto settle=sine(48000,220,.1);render(p,settle);auto dry=sine(48000,220,.1),off=dry;render(p,off);assert(off==dry);
    c[1]=1;set(p,0,c);std::vector<float>silence(48000);render(p,silence);assert(rms(silence)<1e-8); // No old grains after re-enable.
}
void freezeAudio(unsigned sr){CreativeEffectsProcessor p;p.configure(sr);auto c=defaults[1];c[1]=1;c[2]=1000;c[3]=1;c[4]=1;c[5]=60000;c[6]=20;c[7]=100;c[8]=0;c[9]=0;c[10]=1000;c[11]=18000;set(p,1,c);
    auto tone=sine(sr,1000,.35);render(p,tone);const auto held=status(p,1);assert(held[8]==1&&held[9]==1&&held[2]==int32_t(sr));assert(*std::max_element(held.begin()+12,held.begin()+28)>-40000);
    std::vector<float>tail(sr);render(p,tail);const double captured=component(tail,sr,1000,sr/10);std::printf("freeze %u held1000=%.7f rms=%.6f\n",sr,captured,rms(tail,sr/10));assert(captured>.02&&rms(tail,sr/10)>.01);
    // Long RT60 remains long at high rates (no uint32 sr*60000 overflow).
    const double first=rms(tail,0);std::vector<float>later(sr);render(p,later);assert(rms(later)>first*.70);
    c[8]=1200;set(p,1,c);std::vector<float>pitched(sr);render(p,pitched);assert(component(pitched,sr,2000,sr/5)>.005);
    c[4]=0;set(p,1,c);auto release=sine(sr,600,.5);render(p,release);auto dry=sine(sr,600,.2),released=dry;render(p,released);double error=0;for(size_t i=0;i<dry.size();++i)error=std::max(error,double(std::abs(dry[i]-released[i])));assert(error<1e-5&&status(p,1)[8]==0);
    // Rate reset consumes the existing capture token; a new token is required.
    c[4]=1;set(p,1,c);p.configure(sr==48000?96000:48000);auto noCapture=sine(sr==48000?96000:48000,1000,.2);render(p,noCapture);assert(status(p,1)[8]==0);
    ++c[3];set(p,1,c);auto recapture=sine(sr==48000?96000:48000,1000,.2);render(p,recapture);assert(status(p,1)[8]==1&&status(p,1)[9]==2);
    p.reset();auto afterReset=sine(sr==48000?96000:48000,1000,.2);render(p,afterReset);assert(status(p,1)[8]==0);
}
void reverseHistoryBoundary(){CreativeEffectsProcessor p;p.configure(8000);auto c=defaults[0];c[1]=1;c[2]=1000;c[3]=2000;c[4]=250;c[5]=64;c[6]=1000;c[7]=2400;c[8]=1000;c[9]=0;set(p,0,c);std::vector<float> history(8000*5*2);render(p,history);auto recent=sine(8000,500,1.9);render(p,recent);assert(rms(recent)<1e-8);}
void shimmerAudio(unsigned sr){CreativeEffectsProcessor p,plain;p.configure(sr);plain.configure(sr);auto c=defaults[2];c[1]=1;c[2]=1000;c[4]=12000;c[5]=0;c[7]=1000;c[8]=18000;c[11]=20;set(p,2,c);auto noShift=c;noShift[7]=0;set(plain,2,noShift);
    auto in=sine(sr,500,1.5),processed=in,reference=in;render(p,processed);render(plain,reference);double difference=0;for(size_t i=sr;i<processed.size();++i)difference+=std::abs(processed[i]-reference[i]);assert(difference>1&&rms(processed,sr/2)>.003);const double octave=component(processed,sr,1000,sr/2),plainOctave=component(reference,sr,1000,sr/2);assert(octave>.001&&octave>plainOctave*10);
    std::vector<float> tail(sr*2);render(p,tail);std::printf("shimmer %u tail rms=%.6f shifted1000=%.7f\n",sr,rms(tail),component(processed,sr,1000,sr/2));assert(rms(tail)>.0001);assert(status(p,2)[8]==8&&status(p,2)[7]>-100000);
    c[3]=1000;c[4]=20000;c[6]=-1200;c[7]=1000;c[8]=20000;set(p,2,c);auto loud=sine(sr,120,2.,1);render(p,loud);assert(rms(loud)<8);
    c[1]=0;set(p,2,c);std::vector<float> drain(sr/5);render(p,drain);c[1]=1;set(p,2,c);std::vector<float> quiet(sr);render(p,quiet);assert(rms(quiet)<1e-8);
}
void concurrentControl(){CreativeEffectsProcessor p;std::atomic<bool>done{false};std::thread writer([&]{for(int i=0;i<3000;++i){auto c=defaults[0];c[1]=1;c[7]=i%2401;c[2]=i%1001;set(p,0,c);}done=true;});std::array<float,512>x{};while(!done){p.process(x.data(),256);const auto a=status(p,0);assert(a[0]==1&&a[2]==48000);}writer.join();}
void invalidAudioRecovery(){CreativeEffectsProcessor p;auto c=defaults[1];c[1]=1;c[2]=1000;c[3]=1;c[4]=1;set(p,1,c);std::vector<float>extreme(12000,3e38f);render(p,extreme);c[4]=0;set(p,1,c);std::vector<float>release(96000);render(p,release);c[3]=2;c[4]=1;set(p,1,c);auto normal=sine(48000,500,.3);render(p,normal);std::vector<float>tail(48000);render(p,tail);assert(rms(tail)>.001&&rms(tail)<1);assert(component(tail,48000,500,12000)>.001);}
}
int main(){const auto start=std::chrono::steady_clock::now();bypassValidation();granularAudio();reverseHistoryBoundary();for(unsigned sr:{44100u,48000u,96000u}){freezeAudio(sr);shimmerAudio(sr);}invalidAudioRecovery();concurrentControl();assert(allocations==0);std::printf("Creative effects: silence/bypass, strict schemas, octave granular audio, maximum reverse history boundary, true spectral hold/pitch/release, high-rate long decay, shimmer feedback/octave/tail, stale-state reset, finite-overload recovery, concurrency, zero callback allocations passed in %.3fs.\n",std::chrono::duration<double>(std::chrono::steady_clock::now()-start).count());}
