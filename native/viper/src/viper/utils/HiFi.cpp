#include "HiFi.h"
#include "../constants.h"
#include "ProfessionalTuning.h"
#include <algorithm>

HiFi::HiFi() {
    samplingRate=VIPER_DEFAULT_SAMPLING_RATE;
    for(auto& filter:filters) {
        filter.lowpass=new IIR_NOrder_BW_LH(1);
        filter.highpass=new IIR_NOrder_BW_LH(3);
        filter.bandpass=new IIR_NOrder_BW_BP(3);
    }
    Reset();
}
HiFi::~HiFi() {
    for(auto& filter:filters) {delete filter.lowpass;delete filter.highpass;delete filter.bandpass;}
}
void HiFi::RefreshFilters(bool resetState) {
    for(auto& filter:filters) {
        filter.lowpass->setLPF(currentLow,samplingRate);
        filter.highpass->setHPF(currentHigh,samplingRate);
        filter.bandpass->setBPF(currentLow,currentHigh,samplingRate);
        if(resetState) {filter.lowpass->Mute();filter.highpass->Mute();filter.bandpass->Mute();}
    }
}
void HiFi::Reset() {
    currentGain=gain;currentTrim=trim;
    currentLow=professional::frequency(lowFrequency,samplingRate);
    currentHigh=professional::frequency(highFrequency,samplingRate);
    smoothing=professional::alpha(samplingRate);filterSmoothing=professional::alpha(samplingRate,16);
    delayStep=1.f/(.01f*samplingRate);
    RefreshFilters(true);
    lowRing={};midRing={};position=tick=0;
    lowDelay={LowDelayFrames(),LowDelayFrames(),1.f};
    midDelay={MidDelayFrames(),MidDelayFrames(),1.f};
}
void HiFi::AdvanceTuning() {
    currentGain=professional::advance(currentGain,gain,smoothing);
    for(unsigned i=0;i<3;++i) currentTrim[i]=professional::advance(currentTrim[i],trim[i],smoothing);
    if(tick++%16) return;
    const float low=professional::frequency(lowFrequency,samplingRate),high=professional::frequency(highFrequency,samplingRate);
    if(currentLow==low && currentHigh==high) return;
    currentLow=professional::advance(currentLow,low,filterSmoothing);
    currentHigh=professional::advance(currentHigh,high,filterSmoothing);
    RefreshFilters(false);
}
unsigned HiFi::LowDelayFrames() const {return professional::delayFrames(samplingRate,lowDelayUs);}
unsigned HiFi::MidDelayFrames() const {return professional::delayFrames(samplingRate,midDelayUs);}
void HiFi::StartDelay(Delay& delay,unsigned wanted) {
    // Complete a transition before following the latest edit; repeated drags never restart it.
    if(delay.mix>=1.f && delay.to!=wanted) {delay.from=delay.to;delay.to=wanted;delay.mix=0.f;}
}
float HiFi::Delayed(const Ring& ring,const Delay& delay,unsigned channel) const {
    const float first=ring[(position+capacity-delay.from)%capacity][channel];
    const float second=ring[(position+capacity-delay.to)%capacity][channel];
    return first+(second-first)*delay.mix;
}
void HiFi::Process(float* samples,uint32_t size) {
    for(uint32_t frame=0;frame<size;++frame) {
        AdvanceTuning();
        StartDelay(lowDelay,LowDelayFrames());StartDelay(midDelay,MidDelayFrames());
        for(unsigned channel=0;channel<2;++channel) {
            const float input=samples[2*frame+channel];
            const float low=do_filter_lh(filters[channel].lowpass,input);
            const float high=do_filter_lh(filters[channel].highpass,input);
            const float mid=do_filter_bp(filters[channel].bandpass,input);
            lowRing[position][channel]=low;midRing[position][channel]=mid;
            // Keep the original three-path summing order and 1.2 high-frequency factor.
            const float hp=high*currentGain*1.2f*currentTrim[2];
            const float bp=Delayed(midRing,midDelay,channel)*currentGain*currentTrim[1];
            samples[2*frame+channel]=hp+bp+Delayed(lowRing,lowDelay,channel)*currentTrim[0];
        }
        position=(position+1)%capacity;
        lowDelay.mix=std::min(1.f,lowDelay.mix+delayStep);
        midDelay.mix=std::min(1.f,midDelay.mix+delayStep);
    }
}
void HiFi::SetClarity(float value) {gain=value;}
void HiFi::SetSamplingRate(uint32_t rate) {
    if(rate!=samplingRate) {samplingRate=rate;Reset();}
}
void HiFi::SetTuning(float low,float high,const float trimDb[3],uint32_t lowUs,uint32_t midUs) {
    lowFrequency=low;highFrequency=high;lowDelayUs=lowUs;midDelayUs=midUs;
    for(unsigned i=0;i<3;++i) trim[i]=professional::amplitude(trimDb[i]);
}