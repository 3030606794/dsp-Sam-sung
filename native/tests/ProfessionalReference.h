#pragma once
// Frozen equations and signal routing from a60dd326 (before professional controls).
// Keep this independent of the new HighShelf/HiFi/Clarity/Spectrum classes.
#include <algorithm>
#include <cmath>
#include "../viper/src/viper/utils/NoiseSharpening.h"
#include "../viper/src/viper/utils/IIR_NOrder_BW_LH.h"
#include "../viper/src/viper/utils/IIR_NOrder_BW_BP.h"
#include "../viper/src/viper/utils/WaveBuffer.h"
#include "../viper/src/viper/utils/MultiBiquad.h"
#include "ProfessionalHarmonicReference.h"

namespace legacyReference {
struct Shelf {
    double x1=0,x2=0,y1=0,y2=0,a0,a1,a2,b0,b1,b2;
    Shelf(uint32_t rate,float amplitude) {
        const double gain=20.0*log10(double(amplitude));
        const double x=2*3.14159265358979323846*8250.0/rate;
        const double y=exp(gain*log(10.0)/40.0),z=sqrt(y*2.0)*sin(x);
        const double a=(y-1.0)*cos(x),b=(y+1.0)-a,d=(y+1.0)*cos(x),e=(y+1.0)+a;
        a0=1.0/(z+b);a1=((y-1.0)-d)*2.0;a2=b-z;
        b0=(e+z)*y;b1=-y*2.0*((y-1.0)+d);b2=(e-z)*y;
    }
    float process(float input) {
        const double output=(((x1*b1+input*b0+b2*x2)-y1*a1)-a2*y2)*a0;
        y2=y1;y1=output;x2=x1;x1=input;return float(output);
    }
};
struct HiFi {
    struct Filter {IIR_NOrder_BW_LH lp{1},hp{3};IIR_NOrder_BW_BP bp{3};} filters[2];
    WaveBuffer bpBuffer{2,2048},lpBuffer{2,2048};
    float gain;
    HiFi(uint32_t rate,float amplitude):gain(amplitude) {
        for(auto& f:filters) {f.lp.setLPF(120,rate);f.hp.setHPF(1200,rate);f.bp.setBPF(120,1200,rate);}
        bpBuffer.PushZeros(rate/400);lpBuffer.PushZeros(rate/200);
    }
    void process(float* samples,uint32_t count) {
        float* bp=bpBuffer.PushZerosGetBuffer(count);float* lp=lpBuffer.PushZerosGetBuffer(count);
        for(uint32_t i=0;i<count*2;++i) {
            auto& f=filters[i%2];const float low=do_filter_lh(&f.lp,samples[i]);
            const float high=do_filter_lh(&f.hp,samples[i]);const float mid=do_filter_bp(&f.bp,samples[i]);
            samples[i]=high;lp[i]=low;bp[i]=mid;
        }
        bp=bpBuffer.GetBuffer();lp=lpBuffer.GetBuffer();
        for(uint32_t i=0;i<count*2;++i) {const float high=samples[i]*gain*1.2f;const float mid=bp[i]*gain;samples[i]=high+mid+lp[i];}
        bpBuffer.PopSamples(count,false);lpBuffer.PopSamples(count,false);
    }
};
struct Clarity {
    NoiseSharpening natural;Shelf shelf[2];HiFi hifi;int mode;
    Clarity(uint32_t rate,int selected,float gain):shelf{Shelf(rate,gain+1.f),Shelf(rate,gain+1.f)},hifi(rate,gain+1.f),mode(selected) {
        natural.SetSamplingRate(rate);natural.SetGain(gain);
    }
    void process(float* samples,uint32_t count) {
        if(mode==0) natural.Process(samples,count);
        else if(mode==1) for(uint32_t i=0;i<count*2;++i) samples[i]=shelf[i%2].process(samples[i]);
        else hifi.process(samples,count);
    }
};
struct Spectrum {
    MultiBiquad hp[2],lp[2];FrozenHarmonic harmonics[2];float amount;
    Spectrum(uint32_t rate,float strength=.56f):amount(strength) {
        const float coefficients[10]={.02f,0,.02f,0,.02f,0,.02f,0,.02f,0};
        for(unsigned ch=0;ch<2;++ch) {
            hp[ch].RefreshFilter(MultiBiquad::HIGH_PASS,0,float(std::min(7600u,rate/2-100)),rate,.717f,false);
            lp[ch].RefreshFilter(MultiBiquad::LOW_PASS,0,rate/2.f-2000.f,rate,.717f,false);
            harmonics[ch].SetHarmonics(coefficients);
        }
    }
    void process(float* samples,uint32_t count) {
        for(uint32_t i=0;i<count*2;++i) {
            const unsigned ch=i%2;double wet=hp[ch].ProcessSample(samples[i]);
            wet=harmonics[ch].Process(wet);wet=lp[ch].ProcessSample(wet*amount);
            samples[i]+=float(wet);
        }
    }
};
}
