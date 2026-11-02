#include "ImagingFirstRoundReference.h"
#include "../viper/src/viper/effects/ColorfulMusic.h"
#include <cassert>
#include <complex>
#include <cstdio>
#include <cstring>
#include <vector>

namespace {
constexpr double pi=3.14159265358979323846;
using Settings=std::array<int32_t,13>;
constexpr Settings neutral{1,1000,0,0,150,16000,0,120,1000,0,1000,0,0};
constexpr int32_t invalid=ImagingMeter::invalid;
std::vector<float> noise(unsigned frames) {
    uint32_t seed=1234;std::vector<float> data(frames*2);
    for(float& x:data){seed=1664525*seed+1013904223;x=(int32_t(seed>>8)-8388608)/8388608.f;}
    return data;
}
std::vector<float> tone(unsigned fs,unsigned frames,double hz=1000,float amplitude=.5,int type=1) {
    std::vector<float> data(2*frames);
    for(unsigned i=0;i<frames;++i){float v=amplitude*std::sin(2*pi*hz*i/fs);data[2*i]=v;data[2*i+1]=type==0?v:type==1?-v:type==2?0:amplitude*std::cos(2*pi*hz*i/fs);}
    return data;
}
template<class T>void render(T& effect,std::vector<float>& data,unsigned block=127) {
    for(size_t i=0;i<data.size()/2;i+=block)effect.Process(data.data()+i*2,std::min<size_t>(block,data.size()/2-i));
}
template<class T>void configure(T& effect,unsigned fs,const Settings& params=neutral) {
    effect.SetSamplingRate(fs);assert(effect.SetAdvanced(params.data(),13));effect.SetEnable(true);
}
template<size_t N>void set(ColorfulMusic& effect,uint32_t id,const std::array<int32_t,N>& values){assert(effect.SetAdvancedProcessing(id,values.data(),N));}
double energy(const std::vector<float>& data,unsigned first=0) {
    double sum=0;for(size_t i=first*2;i<data.size();++i){assert(std::isfinite(data[i]));sum+=double(data[i])*data[i];}return sum/(data.size()-first*2);
}
void referenceAndDifference() {
    for(unsigned fs:{8000u,11025u,44100u,48000u,96000u,192000u})for(int scenario=0;scenario<12;++scenario) {
        auto params=neutral;params[0]=scenario%2;params[1]=(scenario%5)*500;params[2]=scenario%3==0?60:-30;
        params[3]=scenario%2;params[4]=100+scenario*100;params[5]=2000+scenario*1500;
        params[6]=scenario%3==0;params[8]=scenario*80;params[9]=scenario%2;params[10]=scenario*90;params[11]=-scenario;
        ColorfulMusic actual;ImagingFirstRoundReference expected;configure(actual,fs,params);configure(expected,fs,params);
        auto a=noise(6000),b=a;render(actual,a,31);render(expected,b,511);assert(a==b);
        actual.SetDepthValue(501);expected.SetDepthValue(501);actual.SetWidenValue(.7);expected.SetWidenValue(.7);
        params[3]=1-params[3];params[8]=1000-params[8];assert(actual.SetAdvanced(params.data(),13));assert(expected.SetAdvanced(params.data(),13));
        a=noise(8000);b=a;render(actual,a,1);render(expected,b,257);assert(a==b);
    }
    ColorfulMusic actual,normal;configure(actual,48000);configure(normal,48000);
    std::array<int32_t,8> space{1,123,400,1,4,1200,15,350};set(actual,65630,space);set(normal,65630,space);
    actual.SetDifferenceAudition(true);assert(actual.GetImaging(7)==4);
    ImagingFirstRoundReference first;configure(first,48000);
    auto a=noise(48000),b=a,c=a;render(actual,a);render(normal,b);render(first,c);
    for(size_t i=24000;i<a.size();++i)assert(std::abs(a[i]-(b[i]-c[i]))<1.e-6);
    ColorfulMusic zero;configure(zero,48000);zero.SetDifferenceAudition(true);a=noise(24000);render(zero,a);for(size_t i=20000;i<a.size();++i)assert(a[i]==0);
    std::puts("Imager2 frozen first-round oracle: 72 scenarios plus live edits match bit-for-bit; difference equals processed minus independent first-round reference.");
}
void spaceResponse() {
    for(unsigned fs:{8000u,11025u,44100u,48000u,96000u,192000u})for(int ticks:{0,1,30,123,500}) {
        ColorfulMusic effect;configure(effect,fs);std::array<int32_t,8> s{1,ticks,1000,0,2,1200,10,150};set(effect,65630,s);effect.Reset();
        std::vector<float> impulse(2*(fs/50+2048));impulse[0]=1;impulse[1]=-1;render(effect,impulse);
        const double delay=fs*ticks/100000.;const unsigned integer=delay;const float frac=delay-integer;
        for(unsigned i=0;i<impulse.size()/2;++i){float expected=i==integer?1-frac:i==integer+1?frac:0;assert(std::abs(impulse[2*i]-expected)<7.e-5);assert(impulse[2*i]==-impulse[2*i+1]);}
        assert(effect.GetAdvancedImaging(4)==ticks*10);assert(std::abs(effect.GetAdvancedImaging(5)-std::lround(delay*1000))<=1);
        // Side-only space must never manufacture Side from an identical stereo pair.
        auto mono=tone(fs,fs/10,700,.8,0),original=mono;render(effect,mono);assert(mono==original);
        for(int stages:{1,2,4}) {
            s={0,30,150,1,stages,8000,30,1000};set(effect,65630,s);effect.Reset();
            const double hz=std::min(1200.,fs*.2);auto data=tone(fs,fs/2,hz,.4);render(effect,data);
            const auto source=tone(fs,fs/2,hz,.4);assert(std::abs(energy(data,fs/4)/energy(source,fs/4)-1)<.005);
            assert(effect.GetAdvancedImaging(7)>=1 && effect.GetAdvancedImaging(8)<=fs/2-100);
            assert(effect.GetAdvancedImaging(6)>=effect.GetAdvancedImaging(7) && effect.GetAdvancedImaging(6)<=effect.GetAdvancedImaging(8));
        }
    }
    ColorfulMusic effect;configure(effect,48000);std::array<int32_t,8> s{1,30,1000,0,2,1200,10,150};set(effect,65630,s);effect.Reset();
    auto warm=tone(48000,24000,3100);render(effect,warm);auto old=effect;s[1]=500;set(effect,65630,s);
    float a[2]={.25f,-.25f},b[2]={.25f,-.25f};effect.Process(a,1);old.Process(b,1);assert(a[0]==b[0] && a[1]==b[1]);
    auto tail=noise(24000);render(effect,tail);assert(energy(tail)<1);
    std::puts("Imager2 space: fractional impulse taps and microsecond/sample telemetry agree at six rates; allpass has unity settled magnitude; mono remains mono; new delay starts from previous stationary read tap.");
}
void dynamicAndProtection() {
    ColorfulMusic effect;configure(effect,48000);std::array<int32_t,6> d{1,-180,20,120,10,250};set(effect,65627,d);effect.Reset();
    auto data=tone(48000,96000,1000,.8);auto original=data;render(effect,data);
    const double expected=(20*std::log10(.8/std::sqrt(2.))+18)*.5;
    assert(std::abs(effect.GetAdvancedImaging(1)/100.-expected)<.03);
    assert(std::abs(10*std::log10(energy(data,48000)/energy(original,48000))+expected)<.04);
    // Compression acts on Side; the Mid sum remains unaffected.
    auto stereo=noise(24000),input=stereo;render(effect,stereo);for(size_t i=0;i<stereo.size();i+=2)assert(std::abs(stereo[i]+stereo[i+1]-input[i]-input[i+1])<5.e-7);
    d[0]=0;set(effect,65627,d);auto quiet=std::vector<float>(48000);render(effect,quiet);assert(effect.GetAdvancedImaging(1)==0);
    // Attack and release settings have a measurable effect, without resets.
    ColorfulMusic fast,slow;configure(fast,48000);configure(slow,48000);d={1,-300,80,120,1,50};set(fast,65627,d);d[4]=100;d[5]=1500;set(slow,65627,d);
    auto burst=tone(48000,4800,1000,.8),other=burst;render(fast,burst);render(slow,other);assert(fast.GetAdvancedImaging(1)>slow.GetAdvancedImaging(1)+300);
    burst=tone(48000,96000,1000,.8);other=burst;render(fast,burst);render(slow,other);
    burst.assign(24000,0);other=burst;render(fast,burst);render(slow,other);assert(slow.GetAdvancedImaging(1)>fast.GetAdvancedImaging(1)+300);
    auto p=neutral;p[1]=2000;ColorfulMusic guard;configure(guard,48000,p);std::array<int32_t,6> g{1,0,5,10,200,1000};set(guard,65628,g);
    auto mixed=tone(48000,144000,1000,.6,3);render(guard,mixed);assert(guard.GetAdvancedImaging(2)>990);assert(guard.GetImaging(3)>-50);
    guard.Reset();auto anti=tone(48000,96000,1000,.6);render(guard,anti);assert(guard.GetAdvancedImaging(0)==-10000);assert(guard.GetAdvancedImaging(12)==-10000);assert(guard.GetAdvancedImaging(2)==0);
    // The limit controls the withdrawn increment, not an invented target rho.
    g[5]=400;set(guard,65628,g);guard.Reset();mixed=tone(48000,96000,1000,.6,3);render(guard,mixed);
    assert(guard.GetAdvancedImaging(2)==400);assert(std::abs(guard.GetImaging(3)+4382)<15); // width 2 -> 1.6
    g[3]=100;g[5]=1000;set(guard,65628,g);guard.Reset();mixed=tone(48000,9600,1000,.6,3);render(guard,mixed);
    assert(std::abs(guard.GetAdvancedImaging(2)-632)<3); // 100 ms after the first 100 ms decision
    // Fast/slow transient protection only withdraws positive width and space.
    ColorfulMusic trans;configure(trans,48000,p);std::array<int32_t,4> t{1,1000,30,80};set(trans,65629,t);
    auto onset=tone(48000,4800,1000,.8);render(trans,onset);assert(trans.GetAdvancedImaging(3)>100);
    p[1]=500;ColorfulMusic narrow,plain;configure(narrow,48000,p);configure(plain,48000,p);set(narrow,65629,t);onset=tone(48000,2400);other=onset;render(narrow,onset);render(plain,other);
    for(size_t i=0;i<onset.size();++i)assert(std::abs(onset[i]-other[i])<1.e-7);
    for(unsigned fs:{8000u,11025u,44100u,48000u,96000u,192000u}) {
        ColorfulMusic highThreshold;configure(highThreshold,fs);t={1,1000,180,300};set(highThreshold,65629,t);
        std::vector<float> pulse(2*((fs+9)/10));pulse[0]=1;pulse[1]=-1;render(highThreshold,pulse);
        assert(highThreshold.GetAdvancedImaging(3)>10); // Even the 18 dB control is reachable.
        ColorfulMusic half;configure(half,fs);t[1]=500;set(half,65629,t);pulse.assign(pulse.size(),0);pulse[0]=1;pulse[1]=-1;render(half,pulse);
        assert(std::abs(2*half.GetAdvancedImaging(3)-highThreshold.GetAdvancedImaging(3))<=1);
    }
    std::puts("Imager2 dynamics: Side RMS dB calibration, separate attack/release, unchanged Mid sum, guard withdraws only added width and cannot repair pure anti-phase, transient protection leaves user narrowing intact.");
}
void telemetryAndLifecycle() {
    for(unsigned fs:{8000u,11025u,44100u,48000u,96000u,192000u})for(int kind:{0,1,2,3,4}) {
        ColorfulMusic effect;configure(effect,fs);auto data=kind==4?std::vector<float>(2*((fs+9)/10)):tone(fs,(fs+9)/10,1000,.5,kind);
        assert(effect.GetAdvancedImaging(13)==0);effect.Process(data.data(),data.size()/2-1);assert(effect.GetAdvancedImaging(13)==0);
        effect.Process(data.data()+data.size()-2,1);assert(effect.GetAdvancedImaging(13)==1 && effect.GetImaging(8)==1);
        if(kind==0)assert(effect.GetAdvancedImaging(11)==0);
        if(kind==1 || kind==4)assert(effect.GetAdvancedImaging(11)==invalid);
        if(kind==2 || kind==3)assert(std::abs(effect.GetAdvancedImaging(11)+301)<3);
        if(kind==2 || kind==4)assert(effect.GetAdvancedImaging(0)==invalid);
        effect.ClearImagingRuntime();for(unsigned index:{0u,9u,10u,11u,12u})assert(effect.GetAdvancedImaging(index)==invalid);assert(effect.GetAdvancedImaging(13)==0);
    }
    ColorfulMusic effect;configure(effect,48000);auto p=neutral;p[9]=1;p[10]=1000;assert(effect.SetAdvanced(p.data(),13));effect.SetDepthValue(700);effect.Reset();
    auto data=tone(48000,48000,1000,.5,0);render(effect,data);assert(effect.GetAdvancedImaging(0)==10000);
    effect.SetDifferenceAudition(true);assert(effect.GetImaging(7)==4);p[12]=0;effect.SetAdvanced(p.data(),13);assert(effect.GetImaging(7)==4);
    p[12]=3;effect.SetAdvanced(p.data(),13);assert(effect.GetImaging(7)==3);effect.SetDifferenceAudition(true);assert(effect.GetImaging(7)==3);
    effect.SetDifferenceAudition(false);effect.SetDifferenceAudition(true);assert(effect.GetImaging(7)==4);
    for(int lifecycle=0;lifecycle<3;++lifecycle) {
        if(lifecycle==0)effect.ClearImagingRuntime();if(lifecycle==1)effect.Reset();if(lifecycle==2){effect.SetEnable(false);effect.SetEnable(true);}
        effect.SetDifferenceAudition(true);assert(effect.GetImaging(7)==0);effect.SetDifferenceAudition(false);effect.SetDifferenceAudition(true);assert(effect.GetImaging(7)==4);
    }
    effect.SetEnable(false);effect.SetDifferenceAudition(false);effect.SetDifferenceAudition(true);effect.SetEnable(true);effect.SetDifferenceAudition(true);assert(effect.GetImaging(7)==0);
    std::puts("Imager2 telemetry: aligned full 100 ms windows, mono-fold calibration and undefined cases, input before legacy depth, both audition edge latches and lifecycle cleanup.");
}
void blocksAndEdits() {
    for(unsigned fs:{8000u,11025u,44100u,48000u,96000u,192000u}) {
        auto p=neutral;p[1]=2000;p[3]=1;p[6]=1;p[8]=250;
        ColorfulMusic a,b;configure(a,fs,p);configure(b,fs,p);
        std::array<int32_t,6> d{1,-480,80,120,1,50},g{1,50,2,10,200,1000};std::array<int32_t,4> t{1,1000,30,20};std::array<int32_t,8> s{1,500,1000,1,4,8000,30,1000};
        for(auto* e:{&a,&b}){set(*e,65627,d);set(*e,65628,g);set(*e,65629,t);set(*e,65630,s);}
        for(unsigned edit=0;edit<20;++edit) {
            s[1]=edit%2?0:500;s[4]=1u<<(edit%3);s[5]=edit%2?200:8000;s[6]=edit%2?5:30;
            set(a,65630,s);set(b,65630,s);auto x=noise(2000),y=x;render(a,x,1);render(b,y,511);assert(x==y);assert(energy(x)<10);
            for(unsigned i=0;i<14;++i)assert(a.GetAdvancedImaging(i)==b.GetAdvancedImaging(i));
        }
        auto invalidSpace=s;invalidSpace[4]=3;const int32_t before=a.GetAdvancedImaging(4);assert(!a.SetAdvancedProcessing(65630,invalidSpace.data(),8));assert(a.GetAdvancedImaging(4)==before);
        assert(!a.SetAdvancedProcessing(65630,s.data(),7));assert(!a.SetAdvancedProcessing(65630,nullptr,8));
    }
    std::puts("Imager2 stress: full-scale noise and rapid boundary edits at six rates remain finite; one-frame and 511-frame blocks match bit-for-bit; invalid packets are atomic.");
}
}
int main(){referenceAndDifference();spaceResponse();dynamicAndProtection();telemetryAndLifecycle();blocksAndEdits();std::puts("All imaging advanced audio tests passed.");}
