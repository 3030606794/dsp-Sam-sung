#include "DynamicSystem.h"
#include "../constants.h"

DynamicSystem::DynamicSystem() {
    this->samplingRate = VIPER_DEFAULT_SAMPLING_RATE;
    this->enable = false;
    this->dynamicBass.SetSamplingRate(this->samplingRate);
    this->dynamicBass.Reset();
}

void DynamicSystem::Process(float *samples, uint32_t size) {
    if (!this->enable) return;
    if(configured)tuning.Process(samples,size);
    else this->dynamicBass.FilterSamples(samples, size);
}

void DynamicSystem::Reset() {
    tuning.Reset();
    this->dynamicBass.SetSamplingRate(this->samplingRate);
    this->dynamicBass.Reset();
}

void DynamicSystem::SetBassGain(float gain) {
    this->dynamicBass.SetBassGain(gain);
}

void DynamicSystem::SetEnable(bool enable) {
    if (this->enable != enable) {
        if (enable) {
            Reset();
        }
        this->enable = enable;
    }
    tuning.SetEnable(enable);
}

void DynamicSystem::SetSamplingRate(uint32_t samplingRate) {
    if (this->samplingRate != samplingRate) {
        this->samplingRate = samplingRate;
        this->dynamicBass.SetSamplingRate(samplingRate);
        tuning.SetSamplingRate(samplingRate);
    }
}

void DynamicSystem::SetSideGain(float gainX, float gainY) {
    this->dynamicBass.SetSideGain(gainX, gainY);
}

void DynamicSystem::SetXCoeffs(uint32_t low, uint32_t high) {
    this->dynamicBass.SetFilterXPassFrequency(low, high);
}

void DynamicSystem::SetYCoeffs(uint32_t low, uint32_t high) {
    this->dynamicBass.SetFilterYPassFrequency(low, high);
}

bool DynamicSystem::SetProfessional(const int32_t* v,size_t n){if(!tuning.SetProfessional(v,n))return false;configured=true;return true;}
bool DynamicSystem::SetTools(const int32_t* v,size_t n){return tuning.SetTools(v,n);}
void DynamicSystem::ClearTools(){tuning.ClearTools();}
void DynamicSystem::GetRuntime(int32_t* v)const{tuning.GetRuntime(v);}
void DynamicSystem::GetResponse(int32_t* v)const{tuning.GetResponse(v);}
