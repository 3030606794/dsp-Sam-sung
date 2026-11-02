#include "../viper/src/viper/utils/HiFi.h"
#include "../viper/src/viper/effects/Reverberation.h"
#include "../viper/src/viper/effects/ViPERBass.h"
#include <algorithm>
#include <array>
#include <cassert>
#include <cmath>
#include <complex>
#include <cstdio>
#include <vector>

namespace {
constexpr double pi=3.14159265358979323846;
void hifiDelay(){
    unsigned cases=0;
    for(unsigned rate:{48000u,192000u,384000u,768000u})for(bool mid:{false,true}){
        const unsigned delay=rate/(mid?200:100),count=rate/20+delay;
        const float trims[3]{mid?-120.f:0.f,mid?0.f:-120.f,-120.f};
        HiFi delayed,reference;delayed.SetSamplingRate(rate);reference.SetSamplingRate(rate);
        delayed.SetTuning(120,1200,trims,mid?0:10000,mid?5000:0);
        reference.SetTuning(120,1200,trims,0,0);delayed.Reset();reference.Reset();
        std::vector<float>a(count*2),b(count*2);a[0]=b[0]=1;a[1]=b[1]=-.5f;
        delayed.Process(a.data(),count);reference.Process(b.data(),count);
        double largest=0;
        for(unsigned i=0;i<count;++i)for(unsigned c=0;c<2;++c){
            assert(std::isfinite(a[2*i+c]));
            const float expected=i>=delay?b[2*(i-delay)+c]:0;
            largest=std::max(largest,std::abs(double(a[2*i+c])-expected));
        }
        // The two unused paths are attenuated by the legal -120 dB trim, rather
        // than changing production filters to construct a test-only pure path.
        assert(largest<2.e-6);
        assert((mid?delayed.MidDelayFrames():delayed.LowDelayFrames())==delay);
        ++cases;
    }
    std::printf("XHiFi: %u stereo impulse shifts match exact 5/10ms delay at48/192/384/768k; no2048-frame wrap.\n",cases);
}
void configure(Reverberation& r,unsigned rate){
    r.SetSamplingRate(rate);r.SetRoomSize(.37f);r.SetWidth(.61f);r.SetDamp(.42f);
    r.SetWet(.13f);r.SetDry(.47f);r.SetEnable(true);
}
void echoTime(){
    for(unsigned rate:{44100u,48000u,192000u,384000u,768000u}){
        Reverberation r;configure(r,rate);r.SetDry(0.f);
        std::vector<float> data(rate/10*2);data[0]=1;r.Process(data.data(),data.size()/2);
        unsigned first=0;while(first<data.size()/2&&data[2*first]==0.f)++first;
        assert(first==unsigned(std::lround(1116.*rate/48000.)));
        assert(std::abs(first*1000./rate-23.25)<=1000./rate);
        for(float value:data)assert(std::isfinite(value));
    }
    std::puts("Legacy reverb:48k original1116-frame echo preserved, physical23.25ms maintained44.1..768k.");
}
void dampingAndResponse(){
    using C=std::complex<double>;
    constexpr unsigned comb[]{1116,1188,1277,1356,1422,1491,1557,1617},pass[]{556,441,341,225};
    unsigned cases=0;
    for(unsigned rate:{48000u,192000u,384000u,768000u})for(double frequency:{55.,1000.}){
        Reverberation effect;configure(effect,rate);
        const float oldDamp=.42f*.4f;
        const float pole=rate==48000?oldDamp:float(std::pow(oldDamp,48000./rate));
        const float feedback=.37f*.28f+.7f,wet=.13f*3.f;
        const float first=wet*(.61f/2.f+.5f),second=wet*(1.f-.61f)/2.f,dry=.47f*2.f;
        const C z=std::polar(1.,-2*pi*frequency/rate);
        std::array<C,2> channel{};
        for(unsigned c=0;c<2;++c){
            for(unsigned length:comb){const auto frames=unsigned(std::lround((length+23*c)*rate/48000.));
                const C delay=std::pow(z,frames);channel[c]+=delay/(1.-double(feedback)*(1.-pole)/(1.-double(pole)*z)*delay);}
            for(unsigned length:pass){const auto frames=unsigned(std::lround((length+23*c)*rate/48000.));
                const C delay=std::pow(z,frames);channel[c]*=(1.5*delay-1.)/(1.-.5*delay);}
        }
        const C expected=double(dry)+double(.015f)*(channel[0]*double(first)+channel[1]*double(second));
        const unsigned warm=rate,window=rate/5;C measured{};
        for(unsigned i=0;i<warm+window;++i){float input[2]{float(.1*std::cos(2*pi*frequency*i/rate)),0};effect.Process(input,1);
            if(i>=warm)measured+=double(input[0])*std::polar(1.,-2*pi*frequency*i/rate);}
        measured*=2/(.1*window);
        if(std::abs(measured-expected)>=3.e-4)std::fprintf(stderr,"Reverb rate%u f%.0f response error%.9g\n",rate,frequency,std::abs(measured-expected));
        assert(std::abs(measured-expected)<3.e-4);
        ++cases;
    }
    std::printf("Legacy reverb: %u independent complex comb/allpass transfer checks, scaled damping pole and delay time passed.\n",cases);
}
void blockAndRateLifecycle(){
    for(unsigned rate:{48000u,192000u,384000u,768000u}){
        std::vector<float>a(rate/5*2),b;
        for(unsigned i=0;i<a.size()/2;++i){a[2*i]=.1f*std::sin(2*pi*337*i/rate);a[2*i+1]=.07f*std::cos(2*pi*991*i/rate);}
        b=a;Reverberation first,second;configure(first,rate);configure(second,rate);
        for(unsigned at=0;at<a.size()/2;){const unsigned count=std::min<unsigned>(512,a.size()/2-at);first.Process(a.data()+2*at,count);at+=count;}
        for(unsigned at=0;at<b.size()/2;){const unsigned count=std::min<unsigned>({512,unsigned(b.size()/2-at),3840-at%3840});second.Process(b.data()+2*at,count);at+=count;}
        assert(a==b);
        // A duplicate config retains the tail; a real Fs change must invalidate
        // old delay/filter histories, never reintroduce their nonzero damping store.
        second.SetSamplingRate(rate);float tail[2]{};bool heard=false;
        for(unsigned i=0;i<rate/20;++i){tail[0]=tail[1]=0;second.Process(tail,1);heard|=tail[0]!=0||tail[1]!=0;}assert(heard);
        second.SetSamplingRate(rate==48000?192000:48000);
        for(unsigned i=0;i<10000;++i){tail[0]=tail[1]=0;second.Process(tail,1);assert(tail[0]==0&&tail[1]==0);}
    }
    std::puts("Legacy reverb:512 and3840-host tail blocks identical, duplicate rate keeps tails, real rate changes clear stale histories.");
}
void comparisonWarmup(){
    int reference=0;
    for(unsigned rate:{48000u,192000u,384000u,768000u}){
        reverbtuning::Engine engine;engine.setRate(rate);auto normal=reverbtuning::defaults;normal[1]=1;normal[17]=1;
        assert(engine.set(normal.data(),normal.size()));auto legacy=normal;legacy[1]=0;legacy[2]=900;
        std::array<int32_t,39> tools{};tools[0]=6;tools[2]=1;
        std::copy(legacy.begin(),legacy.end(),tools.begin()+3);std::copy(normal.begin(),normal.end(),tools.begin()+21);
        assert(engine.setTools(tools.data(),tools.size()));std::array<int32_t,32> runtime{};engine.runtime(runtime.data(),true);
        if(!reference)reference=runtime[25];assert(std::abs(runtime[25]-reference)<=1);
    }
    std::puts("Legacy A/B snapshots: physical tail warmup remains invariant48/192/384/768k.");
}
void pureBassChunks(){
    for(unsigned rate:{48000u,192000u,384000u,768000u}){
        std::vector<float> reference;
        for(unsigned host:{512u,rate/50,17u}){
            ViPERBass bass;bass.SetSamplingRate(rate);bass.SetSpeaker(70);bass.SetBassFactor(3.36f);
            bass.SetProcessMode(ViPERBass::PURE_BASS_PLUS);bass.SetEnable(true);
            std::vector<float> data(rate*4);for(unsigned i=0;i<rate*2;++i)data[2*i]=data[2*i+1]=.1f*std::sin(2*pi*61.9*i/rate);
            for(unsigned at=0;at<rate*2;){const unsigned n=std::min({512u,rate*2-at,host-at%host});bass.Process(data.data()+2*at,n);at+=n;}
            for(float v:data)assert(std::isfinite(v));
            if(reference.empty())reference=std::move(data);else assert(reference==data);
        }
    }
    std::puts("PURE_BASS_PLUS:48/192/384/768k full startup+steady samples identical for512,20ms and17-frame hosts; no1008-FIR underflow dry spurts.");
}
}
int main(){hifiDelay();echoTime();dampingAndResponse();blockAndRateLifecycle();comparisonWarmup();pureBassChunks();}
