#include "TopEndControls.h"
#include "ProfessionalTuning.h"
#include <algorithm>
#include <cmath>

void SelectiveSharpening::Configure(uint32_t fs) {
    rate=fs;alpha=professional::alpha(rate);filterAlpha=professional::alpha(rate,16);tick=0;
    low=professional::frequency(settings[1],rate);high=professional::frequency(settings[2],rate);
    q=settings[3]/1000.f;mix=settings[0]?1.f:0.f;availability=low<high?1.f:0.f;
    for(unsigned ch=0;ch<2;++ch) {hp[ch].RefreshFilter(MultiBiquad::HIGH_PASS,0,low,rate,q,false);lp[ch].RefreshFilter(MultiBiquad::LOW_PASS,0,high,rate,q,false);}
}
void SelectiveSharpening::Set(const int32_t* values) {std::copy_n(values,4,settings.begin());}
int32_t SelectiveSharpening::Frequency(unsigned index) const {
    const float l=professional::frequency(settings[1],rate),h=professional::frequency(settings[2],rate);
    return l<h?static_cast<int32_t>(index?h:l):0;
}
void SelectiveSharpening::Process(const float dry[2],float selected[2]) {
    if(tick++%16==0) {
        const float nextLow=professional::advance(low,professional::frequency(settings[1],rate),filterAlpha);
        const float nextHigh=professional::advance(high,professional::frequency(settings[2],rate),filterAlpha);
        const float nextQ=professional::advance(q,settings[3]/1000.f,filterAlpha);
        if(nextLow!=low || nextHigh!=high || nextQ!=q) {
            low=nextLow;high=nextHigh;q=nextQ;
            for(unsigned ch=0;ch<2;++ch) {hp[ch].RefreshFilter(MultiBiquad::HIGH_PASS,0,low,rate,q,false,false);lp[ch].RefreshFilter(MultiBiquad::LOW_PASS,0,high,rate,q,false,false);}
        }
    }
    mix=professional::advance(mix,settings[0]?1.f:0.f,alpha);
    availability=professional::advance(availability,Frequency(0)!=0?1.f:0.f,alpha);
    for(unsigned ch=0;ch<2;++ch) {
        const float band=static_cast<float>(lp[ch].ProcessSample(hp[ch].ProcessSample(dry[ch])));
        selected[ch]=dry[ch]+mix*(availability*band-dry[ch]);
    }
}

DynamicTreble::DynamicTreble(bool isDeesser):deesser(isDeesser) {
    if(deesser) {const int32_t defaults[9]={0,4500,10000,707,-240,60,60,10,80};Set(defaults);}
}
void DynamicTreble::TimeCoefficients() {
    attack=-std::expm1(-1.f/(attackMs*.001f*rate));release=-std::expm1(-1.f/(releaseMs*.001f*rate));
}
void DynamicTreble::Set(const int32_t* values) {
    enabled=values[0]!=0;lowTarget=values[1];
    const unsigned offset=deesser?1:0;
    if(deesser)highTarget=values[2];
    qTarget=values[2+offset]/1000.f;thresholdTarget=values[3+offset]/10.f;
    ratioTarget=values[4+offset]/10.f;maxTarget=values[5+offset]/10.f;
    attackMs=values[6+offset]/10.f;releaseMs=values[7+offset];TimeCoefficients();
}
void DynamicTreble::Detector(bool reset) {
    for(unsigned ch=0;ch<2;++ch) {
        detectorHP[ch].RefreshFilter(MultiBiquad::HIGH_PASS,0,low,rate,q,false,reset);
        detectorLP[ch].RefreshFilter(MultiBiquad::LOW_PASS,0,high,rate,q,false,reset);
    }
}
void DynamicTreble::Configure(uint32_t fs) {
    rate=fs;alpha=professional::alpha(rate);filterAlpha=professional::alpha(rate,16);TimeCoefficients();
    low=professional::frequency(lowTarget,rate);high=professional::frequency(highTarget,rate);q=qTarget;
    threshold=thresholdTarget;ratio=ratioTarget;maximum=maxTarget;mix=enabled?1.f:0.f;availability=Frequency()!=0?1.f:0.f;
    envelope=0;appliedReduction=0;tick=0;wasProcessing=false;Detector(true);
    for(auto& filter:attenuation)filter.RefreshFilter(MultiBiquad::HIGH_SHELF,0,low,rate,1.f,false);
}
int32_t DynamicTreble::Frequency(unsigned index) const {
    const float l=professional::frequency(lowTarget,rate),h=professional::frequency(highTarget,rate);
    if(deesser && l>=h)return 0;
    return static_cast<int32_t>(index?h:l);
}
int32_t DynamicTreble::Reduction() const {return enabled?static_cast<int32_t>(std::lround(appliedReduction*100)):0;}
void DynamicTreble::Process(float samples[2]) {
    const unsigned frame=tick++;
    mix=professional::advance(mix,enabled?1.f:0.f,alpha);
    if(mix==0.f && !enabled) {if(wasProcessing)Configure(rate);return;}
    wasProcessing=true;
    // A band collapsing at Nyquist fades out instead of abruptly dropping its gain.
    availability=professional::advance(availability,Frequency()!=0?1.f:0.f,alpha);
    threshold=professional::advance(threshold,thresholdTarget,alpha);
    ratio=professional::advance(ratio,ratioTarget,alpha);maximum=professional::advance(maximum,maxTarget,alpha);
    if(frame%16==0) {
        const float l=professional::advance(low,professional::frequency(lowTarget,rate),filterAlpha);
        const float h=professional::advance(high,professional::frequency(highTarget,rate),filterAlpha);
        const float nextQ=professional::advance(q,qTarget,filterAlpha);
        if(l!=low || h!=high || nextQ!=q) {low=l;high=h;q=nextQ;Detector(false);}
    }
    double peak=0;
    for(unsigned ch=0;ch<2;++ch) {
        double band=detectorHP[ch].ProcessSample(samples[ch]);
        if(deesser)band=detectorLP[ch].ProcessSample(band);
        peak=std::max(peak,std::abs(band));
    }
    envelope+=(peak>envelope?attack:release)*(peak-envelope);
    const float level=static_cast<float>(20*std::log10(std::max(1.e-12,envelope)));
    const float desired=std::clamp((level-threshold)*(1.f-1.f/ratio),0.f,maximum)*mix*availability;
    if(frame%4==0) {
        appliedReduction=desired;
        const float center=deesser?std::sqrt(low*high):low;
        const float eqQ=deesser?std::clamp(center/std::max(1.f,high-low),.1f,20.f):1.f;
        for(auto& filter:attenuation)filter.RefreshFilter(deesser?MultiBiquad::PEAK:MultiBiquad::HIGH_SHELF,-appliedReduction,center,rate,eqQ,false,false);
    }
    for(unsigned ch=0;ch<2;++ch)samples[ch]=static_cast<float>(attenuation[ch].ProcessSample(samples[ch]));
}
