#include "DiffFirstRoundReference.h"
#include "../viper/src/viper/effects/DiffSurround.h"
#include <cassert>
#include <cmath>
#include <complex>
#include <cstdio>
#include <cstdlib>
#include <new>
#include <vector>

static bool watch=false;static unsigned allocations=0;
void* operator new(std::size_t n){if(watch)++allocations;if(void* p=std::malloc(n))return p;throw std::bad_alloc();}
void operator delete(void* p) noexcept{std::free(p);}void operator delete(void* p,std::size_t) noexcept{std::free(p);}
namespace {
constexpr double pi=3.14159265358979323846;
using Basic=std::array<int32_t,7>;using Advanced=diffadvanced::Settings;
constexpr Basic basicDefault{1,500,0,1000,0,150,12000};
std::vector<float> noise(unsigned n){std::vector<float> data(2*n);uint32_t seed=37;for(float& x:data){seed=seed*1664525+1013904223;x=(int32_t(seed>>8)-8388608)/10485760.f;}return data;}
std::vector<float> tone(unsigned fs,unsigned n,double hz,int kind=0){std::vector<float> data(2*n);for(unsigned i=0;i<n;++i){data[2*i]=.4f*std::sin(2*pi*hz*i/fs);data[2*i+1]=kind==1?-data[2*i]:kind==2?0:data[2*i];}return data;}
template<class T>void render(T& e,std::vector<float>& data,unsigned block=127){for(size_t pos=0;pos<data.size()/2;pos+=block)e.Process(data.data()+2*pos,std::min<size_t>(block,data.size()/2-pos));}
void setup(DiffSurround& e,unsigned fs,Basic b=basicDefault,Advanced a=diffadvanced::defaults){e.SetSamplingRate(fs);assert(e.SetAdvanced(b.data(),7));assert(e.SetSecondRound(a.data(),a.size()));e.SetEnable(true);}
void firstRound() {
    unsigned cases=0;
    for(unsigned fs:{8000u,11025u,44100u,48000u,88200u,96000u,192000u})for(int ticks:{0,1,30,123,500,1999,2000})for(int channel:{0,1})for(int mix:{0,500,1000})for(int band:{0,1}) {
        Basic b=basicDefault;b[1]=ticks;b[2]=channel;b[3]=mix;b[4]=band;
        DiffSurround current;setup(current,fs,b);DiffFirstRoundReference frozen;frozen.SetSamplingRate(fs);assert(frozen.SetAdvanced(b.data(),7));frozen.SetEnable(true);
        auto actual=noise(fs/10+1025),expected=actual;render(current,actual,73);render(frozen,expected,511);assert(actual==expected);++cases;
    }
    std::printf("Diff second round: %u first-round configurations match frozen 8939d79 bit-for-bit.\n",cases);
}
void precision() {
    unsigned cases=0;
    for(unsigned fs:{8000u,11025u,44100u,48000u,96000u,192000u})for(int micro:{0,1,17,5000,19999,20000})for(int quality:{0,1})for(int channel:{0,1}) {
        auto a=diffadvanced::defaults;a[0]=quality;a[1]=1;a[2]=micro;auto b=basicDefault;b[2]=channel;
        DiffSurround e;setup(e,fs,b,a);std::vector<float> impulse((fs/20+20)*2);impulse[0]=impulse[1]=1;render(e,impulse);
        const float delay=float(fs*double(micro)/1.e6);const unsigned n=delay;const float f=delay-n;
        const float cubic[4]={-f*(f-1)*(f-2)/6,(1+f)*(f-1)*(f-2)/2,-(1+f)*f*(f-2)/2,(1+f)*f*(f-1)/6};
        for(unsigned i=0;i<impulse.size()/2;++i){const unsigned selected=channel?0:1;const float expected=quality?(i>=n && i<n+4?cubic[i-n]:0):i==n?1-f:i==n+1?f:0;assert(std::abs(impulse[2*i+selected]-expected)<1.e-6);assert(impulse[2*i+1-selected]==(i==unsigned(quality)?1.f:0.f));}
        assert(e.GetRuntime(0)==micro && e.GetRuntime(3)==quality);++cases;
    }
    // Actual frequency response at a half-sample fractional delay, not merely more decimal digits.
    double ratios[2]{};
    for(int q:{0,1}){auto a=diffadvanced::defaults;a[0]=q;DiffSurround e;setup(e,44100,basicDefault,a);auto data=tone(44100,44100,10000);render(e,data);double energy=0;for(unsigned i=22050;i<44100;++i)energy+=double(data[2*i+1])*data[2*i+1];ratios[q]=std::sqrt(energy/(22050*.08));}
    assert(ratios[0]>.75 && ratios[0]<.76 && ratios[1]>ratios[0]+.1);
    std::printf("Diff precision: %u impulse cases, linear/cubic 10kHz amplitude %.6f / %.6f.\n",cases,ratios[0],ratios[1]);
}
void windowsAndLevels() {
    for(unsigned fs:{8000u,44100u,48000u,192000u})for(int window:{20,40,80})for(int q:{0,1}) {
        auto a=diffadvanced::defaults;a[3]=window;a[0]=q;auto b=basicDefault;b[1]=0;b[4]=1;
        DiffSurround e;setup(e,fs,b,a);auto data=noise(fs/5),source=data;render(e,data);
        unsigned block=1;while(block<fs/375.f)block*=2;
        const unsigned common=std::lround(fs*(window/2000.))+block+q;
        assert(e.GetRuntime(3)==int(common));
        for(unsigned i=common;i<data.size()/2;++i){assert(std::abs(data[2*i]-source[2*(i-common)])<2.e-6);assert(std::abs(data[2*i+1]-source[2*(i-common)+1])<2.e-6);}
    }
    auto a=diffadvanced::defaults;a[4]=-60;a[5]=-30;auto b=basicDefault;b[1]=0;
    DiffSurround e;setup(e,48000,b,a);auto data=noise(10000),original=data;render(e,data);
    const float output=std::pow(10.f,-3.f/20),wet=std::pow(10.f,-6.f/20);
    for(unsigned i=0;i<10000;++i){assert(std::abs(data[2*i]-original[2*i]*output)<1.e-7);assert(std::abs(data[2*i+1]-original[2*i+1]*wet*output)<1.e-7);}
    std::puts("Diff FIR 20/40/80ms: both channels align at zero difference across rates; separate wet/output levels match their definitions.");
}
void guardsAndTransients() {
    for(int mode:{0,1,2}) {
        auto a=diffadvanced::defaults;a[6]=1;a[7]=mode;DiffSurround e;setup(e,48000,basicDefault,a);
        auto data=tone(48000,48000,100);render(e,data);
        assert(e.GetSecondRound(7)>990 && e.GetSecondRound(6)<10);
        assert(e.GetSecondRound(12)<-9990 && e.GetRuntime(8)>9990 && e.GetRuntime(10)>-10);
        auto stable=tone(48000,48000,100);render(e,stable);assert(e.GetSecondRound(7)>990); // Candidate prevents output-driven chatter.
        auto recovered=tone(48000,48000*3,200);render(e,recovered);assert(e.GetSecondRound(7)<10);
    }
    auto a=diffadvanced::defaults;a[6]=1;a[11]=500;DiffSurround e;setup(e,48000,basicDefault,a);auto data=tone(48000,48000,100);render(e,data);assert(std::abs(e.GetSecondRound(7)-500)<=1);
    auto b=basicDefault;b[1]=0;DiffSurround anti;setup(anti,48000,b,a);auto inverse=tone(48000,24000,100,1);render(anti,inverse);assert(anti.GetSecondRound(7)==0);
    a=diffadvanced::defaults;a[14]=1;DiffSurround transient;setup(transient,48000,basicDefault,a);auto burst=tone(48000,24000,2000);for(unsigned i=0;i<9600;++i)burst[i]=0;
    int maximum=0;for(unsigned i=0;i<24000;++i){transient.Process(burst.data()+2*i,1);maximum=std::max(maximum,transient.GetSecondRound(8));}
    assert(maximum>100 && maximum<=500 && transient.GetRuntime(0)==5000 && transient.GetRuntime(3)==0);
    std::puts("Diff protection: three detector modes, finite withdrawal limit, candidate-based stability/recovery, original antiphase unchanged, transient intervention without retuning delay.");
}
void complexResponse() {
    unsigned cases=0;double maximum=0;
    for(unsigned fs:{8000u,48000u,192000u})for(int window:{20,40,80})for(int q:{0,1})for(int micro:{17,5001})for(int hz:{100,1000,3000}) {
        auto a=diffadvanced::defaults;a[0]=q;a[1]=1;a[2]=micro;a[3]=window;a[4]=-30;a[5]=-17;
        auto b=basicDefault;b[3]=725;b[4]=1;
        DiffSurround e;setup(e,fs,b,a);auto data=tone(fs,fs*3/10,hz);render(e,data);
        const double w=2*pi*hz/fs;const unsigned m=std::lround(fs*window/2000.);unsigned p=1;while(p<fs/375.)p*=2;
        const double lo=150,hi=std::min(12000.,fs/2.-100);
        double band=2*(hi-lo)/fs;
        for(unsigned k=1;k<=m;++k)band+=2*(std::sin(2*pi*hi*k/fs)-std::sin(2*pi*lo*k/fs))/(pi*k)*(1-k/double(m+1))*std::cos(w*k);
        const float delay=float(fs*double(micro)/1.e6);const unsigned n=delay;const double f=delay-n;
        const double weights[4]={q?-f*(f-1)*(f-2)/6:1-f,q?(1+f)*(f-1)*(f-2)/2:f,q?-(1+f)*f*(f-2)/2:0,q?(1+f)*f*(f-1)/6:0};
        std::complex<double> d{};for(unsigned k=0;k<4;++k)d+=weights[k]*std::polar(1.,-w*(n+double(k)-q));
        const auto phase=std::polar(std::pow(10.,-1.7/20),-w*(m+p+q));
        const std::complex<double> expected[2]={phase,phase*(1.+.725*band*(std::pow(10.,-3./20)*d-1.))};
        for(unsigned channel=0;channel<2;++channel) {
            double real=0,imag=0;for(unsigned i=fs/5;i<fs*3/10;++i){real+=data[2*i+channel]*std::sin(w*i);imag+=data[2*i+channel]*std::cos(w*i);}
            const auto actual=std::complex<double>(real,imag)*(2./(.4*(fs/10)));
            const double error=std::abs(actual-expected[channel]);maximum=std::max(maximum,error);assert(error<4.e-4);
        }
        ++cases;
    }
    std::printf("Diff advanced response: %u actual stereo tone renders versus independent double-precision FIR/interpolation/trim formula; maximum complex error %.9g.\n",cases,maximum);
}
void monitoringAndListening() {
    auto a=diffadvanced::defaults;auto b=basicDefault;b[4]=1;a[0]=1;a[1]=1;a[2]=5001;
    DiffSurround normal,aligned,difference;setup(normal,48000,b,a);setup(aligned,48000,b,a);setup(difference,48000,b,a);
    aligned.SetAudition(3);difference.SetAudition(4);
    auto raw=noise(24000),out=raw,reference=raw,delta=raw;render(normal,out);render(aligned,reference);render(difference,delta);
    const unsigned common=normal.GetRuntime(3);
    for(unsigned i=12000;i<24000;++i)for(unsigned c=0;c<2;++c){assert(reference[2*i+c]==raw[2*(i-common)+c]);assert(std::abs(delta[2*i+c]-(out[2*i+c]-reference[2*i+c]))<1.e-7);}
    for(unsigned i=11;i<60;++i){assert(normal.GetSecondRound(i)==aligned.GetSecondRound(i));assert(normal.GetSecondRound(i)==difference.GetSecondRound(i));}
    for(int kind:{0,1,2,3}) {
        b=basicDefault;b[1]=0;DiffSurround m;setup(m,48000,b);
        auto signal=kind==3?std::vector<float>(9600):tone(48000,4800,1000,kind);m.Process(signal.data(),4799);assert(m.GetSecondRound(21)==0);m.Process(signal.data()+9598,1);assert(m.GetSecondRound(21)==1);
        for(unsigned band=0;band<3;++band){const unsigned offset=22+band*12;for(unsigned field=0;field<4;++field)assert(m.GetSecondRound(offset+field)==m.GetSecondRound(offset+8+field));if(kind==3)for(unsigned field=0;field<12;++field)assert(m.GetSecondRound(offset+field)==ImagingMeter::invalid);}
    }
    for(int audition:{3,4}){normal.SetAudition(0);normal.SetAudition(audition);assert(normal.GetRuntime(18)==audition);normal.ClearRuntime();normal.SetAudition(audition);assert(normal.GetRuntime(18)==0);normal.SetAudition(0);normal.SetAudition(audition);normal.Reset();normal.SetAudition(audition);assert(normal.GetRuntime(18)==0);}
    std::puts("Diff monitoring/listening: three matched bands, coherent windows/quiet sentinels, aligned bypass and full-module difference, pre-audition meters and stale request cancellation.");
}
void rapidEdits() {
    for(unsigned fs:{8000u,44100u,192000u}) {
        DiffSurround x,y;setup(x,fs);setup(y,fs);auto a=diffadvanced::defaults;
        for(unsigned edit=0;edit<12;++edit){a[0]=edit%2;a[1]=1;a[2]=edit*1555;a[3]=edit%3==0?20:edit%3==1?40:80;a[6]=edit%2;a[14]=edit%2;a[4]=-int(edit)*10;a[19]=80+edit*30;
            assert(x.SetSecondRound(a.data(),a.size()) && y.SetSecondRound(a.data(),a.size()));auto data=noise(1701),other=data;
            watch=true;allocations=0;render(x,data,1);render(y,other,511);watch=false;assert(allocations==0 && data==other);for(float v:data)assert(std::isfinite(v) && std::abs(v)<4);
        }
        auto bad=a;bad[3]=60;assert(!x.SetSecondRound(bad.data(),bad.size()));assert(!x.SetSecondRound(a.data(),20));
    }
    std::puts("Diff rapid edits: per-sample/block equivalence, no callback allocation, bounded finite output and atomic invalid-parameter rejection.");
}
}
int main(){firstRound();precision();windowsAndLevels();guardsAndTransients();complexResponse();monitoringAndListening();rapidEdits();}
