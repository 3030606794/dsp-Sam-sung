#include <array>
#include <algorithm>
#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <vector>
#include "ProfessionalReference.h"
#include "../viper/src/viper/effects/ViPERClarity.h"
#include "../viper/src/viper/effects/SpectrumExtend.h"

namespace {
constexpr double pi=3.14159265358979323846;
using ClarityValues=std::array<int32_t,11>;
using SpectrumValues=std::array<int32_t,7>;
const ClarityValues clarityDefault{0,8250,100,120,1200,0,0,0,0,5000,2500};
const SpectrumValues spectrumDefault{560,7600,717,0,717,0,0};

std::vector<float> signal(unsigned rate,unsigned frames,float amplitude=.2f) {
    std::vector<float> data(frames*2);uint32_t state=971;
    for(unsigned i=0;i<frames;++i) {
        state=1664525u*state+1013904223u;
        const float noise=(int32_t(state>>8)-8388608)/8388608.f;
        for(unsigned ch=0;ch<2;++ch) data[2*i+ch]=amplitude*(.3f*noise+
            .2f*std::sin(2*pi*71*i/rate+ch*.37)+.2f*std::sin(2*pi*733*i/rate)+
            .3f*std::sin(2*pi*std::min(11003.,rate*.35)*i/rate+ch*.67));
    }
    return data;
}
template<class Effect> void render(Effect& effect,std::vector<float>& data,unsigned block) {
    for(size_t pos=0;pos<data.size()/2;pos+=block) {
        const unsigned count=std::min<size_t>(block,data.size()/2-pos);
        effect.Process(data.data()+2*pos,count);
    }
}
template<class Reference> void renderReference(Reference& effect,std::vector<float>& data,unsigned block) {
    for(size_t pos=0;pos<data.size()/2;pos+=block) {
        const unsigned count=std::min<size_t>(block,data.size()/2-pos);
        effect.process(data.data()+2*pos,count);
    }
}
double difference(const std::vector<float>& a,const std::vector<float>& b,size_t start=0) {
    assert(a.size()==b.size());double sum=0;
    for(size_t i=start;i<a.size();++i) {assert(std::isfinite(a[i])&&std::isfinite(b[i]));sum+=std::abs(double(a[i])-b[i]);}
    return sum/(a.size()-start);
}
void assertAudioEqual(const std::vector<float>& a,const std::vector<float>& b,double tolerance=2.e-6) {
    assert(a.size()==b.size());double largest=0;
    for(size_t i=0;i<a.size();++i) {assert(std::isfinite(a[i])&&std::isfinite(b[i]));largest=std::max(largest,std::abs(double(a[i])-b[i]));}
    if(largest>tolerance) std::fprintf(stderr,"audio mismatch maximum %.9g (limit %.9g)\n",largest,tolerance);
    assert(largest<=tolerance);
}
void configure(ViPERClarity& effect,unsigned rate,int mode,const ClarityValues& values) {
    effect.SetSamplingRate(rate);effect.SetProcessMode(static_cast<ViPERClarity::ClarityMode>(mode));
    assert(effect.SetAdvanced(values.data(),values.size()));effect.SetEnable(true);
}
void configure(SpectrumExtend& effect,unsigned rate,const SpectrumValues& values) {
    effect.SetSamplingRate(rate);assert(effect.SetAdvanced(values.data(),values.size()));effect.SetEnable(true);
    // Audition is deliberately not preserved across enable; request a new explicit edge if required.
    if(values[6]) {auto off=values;off[6]=0;assert(effect.SetAdvanced(off.data(),off.size()));assert(effect.SetAdvanced(values.data(),values.size()));}
}

void legacyEquivalence() {
    for(unsigned rate:{44100u,48000u,96000u,192000u}) for(unsigned block:{17u,127u,504u,1024u}) {
        const auto source=signal(rate,8193);
        for(int mode=0;mode<3;++mode) for(float gain:{0.f,2.f,4.5f}) {
            ViPERClarity actual;actual.SetSamplingRate(rate);actual.SetProcessMode(static_cast<ViPERClarity::ClarityMode>(mode));
            actual.SetClarity(gain);actual.SetEnable(true);
            legacyReference::Clarity reference(rate,mode,gain);
            auto a=source,b=source;render(actual,a,block);renderReference(reference,b,block);assertAudioEqual(a,b);
            auto values=clarityDefault;values[0]=int(gain*100);
            ViPERClarity advanced;configure(advanced,rate,mode,values);auto c=source;render(advanced,c,block);assertAudioEqual(c,b);
        }
        SpectrumExtend actual;actual.SetSamplingRate(rate);actual.SetExciter(.56f);actual.SetEnable(true);
        legacyReference::Spectrum reference(rate);auto a=source,b=source;
        render(actual,a,block);renderReference(reference,b,block);assertAudioEqual(a,b,3.e-7);
        SpectrumExtend advanced;configure(advanced,rate,spectrumDefault);auto c=source;render(advanced,c,block);assertAudioEqual(c,b,3.e-7);
    }
    // Independent old queues, extended with exactly the extra requested samples, prove the
    // new 2048-frame ring does not alias a 1920-frame delay at the highest supported rate.
    auto values=clarityDefault;values[0]=150;values[9]=10000;values[10]=5000;
    ViPERClarity effect;configure(effect,192000,2,values);
    legacyReference::HiFi reference(192000,2.5f);
    reference.lpBuffer.PushZeros(960);reference.bpBuffer.PushZeros(480);
    auto a=signal(192000,14003),b=a;render(effect,a,113);renderReference(reference,b,113);assertAudioEqual(a,b);
    assert(effect.GetEffective(3)==1920&&effect.GetEffective(4)==960);
    std::puts("Professional defaults: independent a60 reference matches all modes/legacy+advanced APIs, 44.1/48/96/192 kHz and four block sizes; maximum delays match old queues.");
}

void parameterResponse() {
    const auto source=signal(48000,48000);
    auto baseline=clarityDefault;baseline[0]=200;
    const int modes[11]={0,1,1,2,2,2,2,2,1,2,2};
    const int altered[11]={201,4000,25,300,4000,-60,-60,-60,-60,10000,5000};
    for(unsigned field=0;field<11;++field) {
        auto changed=baseline;changed[field]=altered[field];
        ViPERClarity first,second;configure(first,48000,modes[field],baseline);configure(second,48000,modes[field],changed);
        auto a=source,b=source;render(first,a,127);render(second,b,127);
        const double delta=difference(a,b,24000);
        if(delta<=1.e-7) std::fprintf(stderr,"clarity field %u has no response: %.9g\n",field,delta);
        assert(delta>1.e-7);
    }
    const int spectralAltered[7]={561,3000,1500,10000,1500,-60,1};
    for(unsigned field=0;field<7;++field) {
        auto changed=spectrumDefault;changed[field]=spectralAltered[field];
        SpectrumExtend first,second;configure(first,48000,spectrumDefault);configure(second,48000,changed);
        auto a=source,b=source;render(first,a,113);render(second,b,113);
        const double delta=difference(a,b,24000);
        if(delta<=1.e-8) std::fprintf(stderr,"spectrum field %u has no response: %.9g\n",field,delta);
        assert(delta>1.e-8);
    }
    // Wet audition removes only dry; the fixed nonlinear branch is identical after its crossfade.
    SpectrumExtend normal,wet;auto listen=spectrumDefault;listen[6]=1;
    configure(normal,48000,spectrumDefault);configure(wet,48000,listen);
    auto a=source,b=source;render(normal,a,97);render(wet,b,97);
    for(size_t i=source.size()/2;i<source.size();++i) assert(std::abs((a[i]-b[i])-source[i])<3.e-6);
    std::puts("Professional response: all 11 clarity and 7 spectrum controls change audio, including 0.01 g / 0.1% increments; wet audition isolates precisely the excitation path.");
}

void continuousEdits() {
    const auto source=signal(48000,32000);
    auto clarity=clarityDefault;clarity[0]=200;
    for(int mode=0;mode<3;++mode) {
        ViPERClarity stable,repeated;configure(stable,48000,mode,clarity);configure(repeated,48000,mode,clarity);
        auto a=source,b=source;render(stable,a,97);
        for(size_t i=0;i<b.size()/2;i+=97) {
            assert(repeated.SetAdvanced(clarity.data(),clarity.size()));
            repeated.Process(b.data()+i*2,std::min<size_t>(97,b.size()/2-i));
        }
        assertAudioEqual(a,b,0);
    }
    SpectrumExtend stable,repeated;configure(stable,48000,spectrumDefault);configure(repeated,48000,spectrumDefault);
    auto a=source,b=source;render(stable,a,97);
    for(size_t i=0;i<b.size()/2;i+=97) {
        assert(repeated.SetAdvanced(spectrumDefault.data(),spectrumDefault.size()));
        repeated.Process(b.data()+i*2,std::min<size_t>(97,b.size()/2-i));
    }
    assertAudioEqual(a,b,0);
    // A changed coefficient preserves harmonic/filter history: no reintroduced 200-sample mute.
    SpectrumExtend changed;configure(changed,48000,spectrumDefault);
    auto prefix=signal(48000,2000);render(changed,prefix,127);
    auto values=spectrumDefault;values[1]=12000;assert(changed.SetAdvanced(values.data(),values.size()));
    auto tail=signal(48000,100),original=tail;render(changed,tail,100);
    assert(difference(tail,original)>1.e-6);
    // Fixed edit frame, different block splits: glides and delay transitions use samples, not calls.
    for(unsigned rate:{8000u,44100u,48000u,192000u}) for(int mode=0;mode<4;++mode) {
        const auto input=signal(rate,16000);auto first=input,second=input;
        auto cv=clarity;cv[1]=16000;cv[2]=25;cv[3]=400;cv[4]=5000;cv[5]=-120;cv[6]=60;cv[7]=60;cv[8]=-60;cv[9]=10000;cv[10]=0;
        auto sv=spectrumDefault;sv[0]=1700;sv[1]=16000;sv[2]=1500;sv[3]=24000;sv[4]=300;sv[5]=-60;
        for(unsigned block:{1u,509u}) {
            auto& out=block==1?first:second;
            ViPERClarity c;SpectrumExtend s;configure(c,rate,mode%3,clarity);configure(s,rate,spectrumDefault);
            for(size_t pos=0;pos<out.size()/2;) {
                if(pos==4000) {assert(c.SetAdvanced(cv.data(),cv.size()));assert(s.SetAdvanced(sv.data(),sv.size()));}
                const size_t boundary=pos<4000?4000:out.size()/2;
                const unsigned count=std::min<size_t>(block,boundary-pos);
                if(mode<3)c.Process(out.data()+2*pos,count);else s.Process(out.data()+2*pos,count);
                pos+=count;
            }
        }
        assertAudioEqual(first,second,0);
    }
    std::puts("Professional continuity: repeated sync is bit-identical; changed filters retain excitation history; all glides/delay fades are block-independent through live edits.");
}

void extremesAndValidation() {
    const int32_t clow[11]={0,2000,25,60,500,-120,-120,-120,-120,0,0};
    const int32_t chigh[11]={450,16000,100,400,5000,60,60,60,0,10000,5000};
    const int32_t slow[7]={0,1000,300,0,300,-120,0},shigh[7]={5600,16000,1500,24000,1500,0,1};
    for(unsigned rate:{8000u,11025u,22050u,44100u,48000u,96000u,192000u}) {
        ViPERClarity c[3];for(int mode=0;mode<3;++mode)configure(c[mode],rate,mode,clarityDefault);
        SpectrumExtend s;configure(s,rate,spectrumDefault);
        auto cv=clarityDefault;auto sv=spectrumDefault;uint32_t state=1664;
        for(unsigned edit=0;edit<240;++edit) {
            for(unsigned i=0;i<cv.size();++i)cv[i]=((edit+i)%2)?clow[i]:chigh[i];
            for(unsigned i=0;i<sv.size();++i)sv[i]=((edit+i)%2)?slow[i]:shigh[i];
            if(edit%3==0) {sv[2]=sv[4]=1500;sv[0]=5600;}
            for(auto& effect:c)assert(effect.SetAdvanced(cv.data(),cv.size()));assert(s.SetAdvanced(sv.data(),sv.size()));
            const unsigned count=17+edit%137;std::vector<float> original(count*2);
            for(auto& sample:original) {
                state=1664525u*state+1013904223u;
                sample=edit%3==0?((state&1)?1.f:-1.f):edit%3==1?(int32_t(state>>8)-8388608)/8388608.f:1.f;
            }
            for(auto& effect:c) {auto out=original;effect.Process(out.data(),count);for(float value:out)assert(std::isfinite(value));}
            auto out=original;s.Process(out.data(),count);for(float value:out)assert(std::isfinite(value));
        }
        for(unsigned field=0;field<cv.size();++field)for(int invalid:{clow[field]-1,chigh[field]+1}) {
            auto invalidValues=clarityDefault;invalidValues[field]=invalid;
            for(auto& effect:c)assert(!effect.SetAdvanced(invalidValues.data(),invalidValues.size()));
        }
        for(unsigned field=0;field<sv.size();++field)for(int invalid:{slow[field]-1,shigh[field]+1}) {
            auto invalidValues=spectrumDefault;invalidValues[field]=invalid;assert(!s.SetAdvanced(invalidValues.data(),invalidValues.size()));
        }
        sv=spectrumDefault;sv[3]=7999;assert(!s.SetAdvanced(sv.data(),sv.size()));
        assert(!s.SetAdvanced(spectrumDefault.data(),6));assert(!c[0].SetAdvanced(clarityDefault.data(),10));
        assert(!s.SetAdvanced(nullptr,7));assert(!c[0].SetAdvanced(nullptr,11));
    }
    // Invalid packets cannot partially overwrite earlier valid fields or audio state.
    ViPERClarity a,b;configure(a,48000,1,clarityDefault);configure(b,48000,1,clarityDefault);
    auto bad=clarityDefault;bad[1]=16000;bad[10]=5001;assert(!a.SetAdvanced(bad.data(),bad.size()));
    auto first=signal(48000,4000),second=first;render(a,first,113);render(b,second,113);assertAudioEqual(first,second,0);
    SpectrumExtend x,y;configure(x,48000,spectrumDefault);configure(y,48000,spectrumDefault);
    auto invalid=spectrumDefault;invalid[1]=16000;invalid[6]=2;assert(!x.SetAdvanced(invalid.data(),invalid.size()));
    first=signal(48000,4000);second=first;render(x,first,113);render(y,second,113);assertAudioEqual(first,second,0);
    SpectrumExtend audition;configure(audition,48000,spectrumDefault);auto on=spectrumDefault;on[6]=1;
    assert(audition.SetAdvanced(on.data(),on.size())&&audition.GetEffective(2)==1);
    audition.Reset();assert(audition.GetEffective(2)==0);
    assert(audition.SetAdvanced(on.data(),on.size())&&audition.GetEffective(2)==0);
    assert(audition.SetAdvanced(spectrumDefault.data(),spectrumDefault.size()));
    assert(audition.SetAdvanced(on.data(),on.size())&&audition.GetEffective(2)==1);
    audition.SetEnable(false);assert(audition.GetEffective(2)==0);
    std::puts("Professional limits: full-scale alternating/DC/noise remain finite during rapid extreme edits at 8/11.025/22.05/44.1/48/96/192 kHz; invalid packets are atomic; audition cancellation rejects stale requests.");
}
}
int main() {legacyEquivalence();parameterResponse();continuousEdits();extremesAndValidation();return 0;}
