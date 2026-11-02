#include "ViPERClarity.h"
#include "../constants.h"
#include "../utils/ProfessionalTuning.h"

ViPERClarity::ViPERClarity() {
    enable=false;processMode=ClarityMode::NATURAL;
    clarityGainPercent=0.f;samplingRate=VIPER_DEFAULT_SAMPLING_RATE;
    Reset();
}
void ViPERClarity::AdvanceShelf() {
    if(tuningTick++%16) return;
    const float frequency=professional::frequency(static_cast<float>(settings[1]),samplingRate);
    const float slope=settings[2]/100.f;
    if(currentFrequency==frequency && currentSlope==slope && appliedGain==currentGain) return;
    currentFrequency=professional::advance(currentFrequency,frequency,filterSmoothing);
    currentSlope=professional::advance(currentSlope,slope,filterSmoothing);
    appliedGain=currentGain;
    for(auto& shelf:highShelf) shelf.Configure(currentFrequency,1.f+currentGain,currentSlope,samplingRate,false);
}
void ViPERClarity::Process(float* samples,uint32_t size) {
    if(!enable) return;
    if(processMode==ClarityMode::XHIFI) hifi.Process(samples,size);
    for(uint32_t frame=0;frame<size;++frame) {
        currentGain=professional::advance(currentGain,clarityGainPercent,smoothing);
        if(processMode==ClarityMode::NATURAL) {
            // Preserve the upstream differentiator/two-tap smoothing, including its zero setting.
            noiseSharpening.SetGain(currentGain);
            float selected[2];selective.Process(samples+frame*2,selected);
            if(selective.Bypassed())noiseSharpening.Process(samples+frame*2,1);
            else noiseSharpening.ProcessSelected(samples+frame*2,selected);
        } else if(processMode==ClarityMode::OZONE) {
            AdvanceShelf();
            samples[2*frame]=static_cast<float>(highShelf[0].Process(samples[2*frame]));
            samples[2*frame+1]=static_cast<float>(highShelf[1].Process(samples[2*frame+1]));
        }
        dynamicTreble.Process(samples+frame*2);deesser.Process(samples+frame*2);
        currentOutput=professional::advance(currentOutput,outputTarget,smoothing);
        samples[2*frame]*=currentOutput;samples[2*frame+1]*=currentOutput;
    }
}
void ViPERClarity::Reset() {
    currentGain=clarityGainPercent;appliedGain=currentGain;
    currentFrequency=professional::frequency(static_cast<float>(settings[1]),samplingRate);
    currentSlope=settings[2]/100.f;currentOutput=outputTarget;
    smoothing=professional::alpha(samplingRate);filterSmoothing=professional::alpha(samplingRate,16);tuningTick=0;
    noiseSharpening.SetSamplingRate(samplingRate);noiseSharpening.SetGain(currentGain);
    for(auto& shelf:highShelf) shelf.Configure(currentFrequency,1.f+currentGain,currentSlope,samplingRate,true);
    hifi.SetClarity(1.f+clarityGainPercent);
    hifi.SetSamplingRate(samplingRate);hifi.Reset();
    selective.Configure(samplingRate);ClearDynamics();
}
void ViPERClarity::SetClarity(float gainPercent) {
    if(!std::isfinite(gainPercent) || gainPercent<0.f) return;
    clarityGainPercent=gainPercent;
    SetClarityToFilter();
}
void ViPERClarity::SetClarityToFilter() {hifi.SetClarity(1.f+clarityGainPercent);}
void ViPERClarity::SetEnable(bool value) {
    if(!value)ClearDynamics();
    if(enable!=value) {if(value) Reset();enable=value;}
}
void ViPERClarity::SetProcessMode(ClarityMode mode) {
    if(processMode!=mode) {processMode=mode;Reset();}
}
void ViPERClarity::SetSamplingRate(uint32_t rate) {
    if(samplingRate!=rate) {samplingRate=rate;Reset();}
}
bool ViPERClarity::SetAdvanced(const int32_t* values,size_t count) {
    if(!professional::validClarity(values,count)) return false;
    std::copy_n(values,settings.size(),settings.begin());
    SetClarity(values[0]/100.f);
    const float trims[3]={values[5]/10.f,values[6]/10.f,values[7]/10.f};
    hifi.SetTuning(static_cast<float>(values[3]),static_cast<float>(values[4]),trims,values[9],values[10]);
    outputTarget=professional::amplitude(values[8]/10.f);
    return true;
}
int32_t ViPERClarity::GetEffective(unsigned index) const {
    if(index==0) return static_cast<int32_t>(professional::frequency(static_cast<float>(settings[1]),samplingRate));
    if(index==1 || index==2) return static_cast<int32_t>(professional::frequency(static_cast<float>(settings[index+2]),samplingRate));
    if(index==3) return static_cast<int32_t>(professional::delayFrames(samplingRate,settings[9]));
    if(index==4) return static_cast<int32_t>(professional::delayFrames(samplingRate,settings[10]));
    return 0;
}
bool ViPERClarity::SetSelective(const int32_t* values,size_t count) {
    if(!professional::validSelective(values,count))return false;
    selective.Set(values);return true;
}
bool ViPERClarity::SetDynamic(const int32_t* values,size_t count) {
    if(!professional::validDynamic(values,count))return false;
    dynamicTreble.Set(values);return true;
}
bool ViPERClarity::SetDeess(const int32_t* values,size_t count) {
    if(!professional::validDeess(values,count))return false;
    deesser.Set(values);return true;
}
int32_t ViPERClarity::GetTopEnd(unsigned index) const {
    if(index<2)return selective.Frequency(index);
    if(index==2)return dynamicTreble.Frequency();
    if(index==3 || index==4)return deesser.Frequency(index-3);
    if(!enable)return 0;
    return index==5?dynamicTreble.Reduction():index==6?deesser.Reduction():0;
}
void ViPERClarity::ClearDynamics() {dynamicTreble.Configure(samplingRate);deesser.Configure(samplingRate);}
