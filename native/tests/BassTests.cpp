#include <algorithm>
#include <array>
#include <cassert>
#include <cmath>
#include <complex>
#include <cstdio>
#include <vector>
#include "../viper/src/viper/effects/ViPERBass.h"

extern "C" void runBassCoreTests();
namespace {
constexpr double pi=3.14159265358979323846;
void fill(std::vector<float>& audio,size_t position,uint32_t rate,double frequency) {
    for(size_t i=0;i<audio.size()/2;++i)
        audio[2*i]=audio[2*i+1]=.005f*std::sin(2*pi*frequency*(position+i)/rate);
}
double render(ViPERBass& bass,uint32_t rate,double frequency,size_t frames,size_t& position) {
    double energy=0;
    while(frames) {
        const size_t n=std::min(frames,size_t(504));
        std::vector<float> audio(n*2);fill(audio,position,rate,frequency);
        bass.Process(audio.data(),n);
        for(float value:audio) {assert(std::isfinite(value)&&std::abs(value)<4.f);energy+=double(value)*value;}
        position+=n;frames-=n;
    }
    return energy;
}
double response(int mode,float q,int parameter=-1,float value=0,double tone=70,uint32_t rate=48000) {
    ViPERBass bass;
    bass.SetSamplingRate(rate);bass.SetSpeaker(70);bass.SetBassFactor(2);
    bass.SetQ(q);if(parameter>=0) bass.SetSubwooferTuning(parameter,value);
    bass.SetProcessMode(static_cast<ViPERBass::ProcessMode>(mode));bass.SetEnable(true);
    size_t position=0;render(bass,rate,tone,rate*2,position);
    return render(bass,rate,tone,rate,position);
}
double naturalReference(double frequency,uint32_t rate) {
    const double omega=2*pi*70/rate,alpha=std::sin(omega)/(2*.53),c=std::cos(omega);
    const std::complex<double> z=std::exp(std::complex<double>(0,-2*pi*frequency/rate));
    const auto lowpass=((1-c)/2+(1-c)*z+(1-c)/2*z*z)/(1+alpha-2*c*z+(1-alpha)*z*z);
    return std::norm(1.0+2.0*lowpass);
}
void defaultsAndParameters() {
    for(uint32_t rate:{8000u,44100u,48000u,96000u,192000u}) {
        const double measured=response(0,.53f,-1,0,70,rate);
        const double dry=.005*.005*rate; // two channels, sine RMS squared = amplitude squared / 2.
        assert(std::abs(measured/dry-naturalReference(70,rate))<.003);
        for(int mode:{0,1,2}) assert(response(mode,.53f,-1,0,70,rate)>0);
    }
    for(int mode:{0,1}) {
        const double original=response(mode,.53f),changed=response(mode,2.f);
        assert(std::abs(changed/original-1)> .1);
    }
    // Every new low-frequency-band control must affect actual rendered audio.
    const std::array<double,6> tones{44,80,44,80,53,95};
    const std::array<float,6> changes{64,120,6,6,1.8f,1.8f};
    for(int parameter=0;parameter<6;++parameter) {
        const double before=response(2,.53f,-1,0,tones[parameter]);
        const double after=response(2,.53f,parameter,changes[parameter],tones[parameter]);
        assert(std::abs(after/before-1)>.01);
    }
}
void subwooferLegacyDefault() {
    Subwoofer modern;
    modern.SetBassGain(48000,5.f);modern.Reset();
    MultiBiquad peak[2],upper[2],low[2];
    for(int ch=0;ch<2;++ch) {
        peak[ch].RefreshFilter(MultiBiquad::PEAK,20.f*std::log10(5.f),44,48000,.75f,true);
        upper[ch].RefreshFilter(MultiBiquad::PEAK,20.f*std::log10(5.f/8.f),80,48000,.2f,true);
        low[ch].RefreshFilter(MultiBiquad::LOW_PASS,0,380,48000,.6f,false);
    }
    for(size_t position=0;position<96000;position+=128) {
        std::vector<float> audio(256),reference;fill(audio,position,48000,53.7);reference=audio;
        modern.Process(audio.data(),128);
        for(size_t i=0;i<reference.size();++i) {
            const int ch=i%2;const float dry=reference[i];
            double wet=peak[ch].ProcessSample(dry);wet=upper[ch].ProcessSample(wet);wet=low[ch].ProcessSample(wet-dry);
            reference[i]=dry*.5f+static_cast<float>(wet)*.6f;
            assert(std::abs(audio[i]-reference[i])<2.e-6f);
        }
    }
}
void continuousTuning() {
    for(uint32_t rate:{44100u,48000u,96000u,192000u}) for(int mode:{0,1,2}) {
        ViPERBass bass,untouched;
        for(auto* engine:{&bass,&untouched}) {
            engine->SetSamplingRate(rate);engine->SetBassFactor(2);engine->SetSpeaker(70);
            engine->SetProcessMode(static_cast<ViPERBass::ProcessMode>(mode));engine->SetEnable(true);
        }
        size_t position=0;
        for(int block=0;block<400;++block) {
            std::vector<float> audio(256);fill(audio,position,rate,61.9);auto reference=audio;
            // Repeated UI synchronization must be a no-op, preserving FIR and IIR histories.
            bass.SetBassFactor(2);bass.SetSpeaker(70);bass.SetQ(.53f);
            const float defaults[6]={44,80,0,0,.75f,.2f};
            for(unsigned p=0;p<6;++p) bass.SetSubwooferTuning(p,defaults[p]);
            bass.Process(audio.data(),128);untouched.Process(reference.data(),128);
            for(size_t i=0;i<audio.size();++i) assert(std::abs(audio[i]-reference[i])<1.e-7f);
            position+=128;
        }
        for(int block=0;block<800;++block) {
            const bool high=block%2;
            bass.SetBassFactor(high?6.f:0.f);bass.SetSpeaker(high?150:15);bass.SetQ(high?2.f:.3f);
            const float values[6]={high?80.f:20.f,high?160.f:60.f,high?12.f:-12.f,high?-12.f:12.f,high?2.f:.1f,high?.1f:2.f};
            for(unsigned p=0;p<6;++p) bass.SetSubwooferTuning(p,values[p]);
            std::vector<float> audio(64);fill(audio,position,rate,61.9);bass.Process(audio.data(),32);position+=32;
            for(float value:audio) assert(std::isfinite(value)&&std::abs(value)<4.f);
        }
    }
    // Zero coefficient must remain finite even before the first sample, with all corrections.
    for(float gain:{0.f,1.e-6f,52.5f}) {
        Subwoofer sub;sub.SetBassGain(48000,gain);sub.SetTuning(2,12);sub.SetTuning(3,-12);sub.Reset();
        for(int i=0;i<100;++i) {std::vector<float> audio(512);fill(audio,i*256,48000,44);sub.Process(audio.data(),256);for(float x:audio)assert(std::isfinite(x));}
    }
}
}
int main() {
    runBassCoreTests();defaultsAndParameters();subwooferLegacyDefault();continuousTuning();
    std::puts("ViPER bass audio passed: legacy default response, Q/frequency/gain/bandwidth affect audio, continuous edits retain state, zero strength is finite.");
}
