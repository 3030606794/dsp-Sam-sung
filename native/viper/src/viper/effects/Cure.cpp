#include "Cure.h"
#include <algorithm>

// Iscle: Verified with the latest version at 13/12/2022

Cure::Cure() {
    this->enabled = false;
    Reset();
}

uint16_t Cure::GetCutoff() {
    return this->crossfeed.GetCutoff();
}

float Cure::GetFeedback() {
    return this->crossfeed.GetFeedback();
}

float Cure::GetLevelDelay() {
    return this->crossfeed.GetLevelDelay();
}

// TODO: Fix with crossfeed
struct Crossfeed::Preset Cure::GetPreset() {
    return this->crossfeed.GetPreset();
}

void Cure::Process(float *buffer, uint32_t size) {
    if (!this->enabled) return;

    if(professional.enabled() || professionalBlend>0.f){
        const float target=professional.enabled()?1.f:0.f;
        for(uint32_t i=0;i<size;++i){
            float input[2]={buffer[2*i],buffer[2*i+1]},legacy[2]={input[0],input[1]},output[2];
            crossfeed.ProcessFrames(legacy,1);passFilter.ProcessFrames(legacy,1);professional.process(input,output);
            professionalBlend=target>professionalBlend?std::min(target,professionalBlend+blendStep):std::max(target,professionalBlend-blendStep);
            for(unsigned c=0;c<2;++c)buffer[2*i+c]=professionalBlend==1.f?output[c]:legacy[c]+(output[c]-legacy[c])*professionalBlend;
        }
        return;
    }

    this->crossfeed.ProcessFrames(buffer, size);
    this->passFilter.ProcessFrames(buffer, size);
}

void Cure::Reset() {
    this->crossfeed.Reset();
    this->passFilter.Reset();
    professional.reset();professionalBlend=professional.enabled()?1.f:0.f;
}

void Cure::SetCutoff(uint16_t cutoff) {
    this->crossfeed.SetCutoff(cutoff);
}

void Cure::SetEnable(bool enabled) {
    if (this->enabled != enabled) {
        if (enabled) {
            Reset();
        }
        this->enabled = enabled;
        if(!enabled)professional.clearTools();
    }
}

void Cure::SetFeedback(float feedback) {
    this->crossfeed.SetFeedback(feedback);
}

void Cure::SetPreset(struct Crossfeed::Preset preset) {
    const auto previous=crossfeed.GetPreset();
    if(previous.cutoff!=preset.cutoff || previous.feedback!=preset.feedback)professional.clearTools();
    this->crossfeed.SetPreset(preset);
}

void Cure::SetSamplingRate(uint32_t samplingRate) {
    this->crossfeed.SetSamplingRate(samplingRate);
    this->passFilter.SetSamplingRate(samplingRate);
    professional.setRate(samplingRate);blendStep=100.f/std::max(8000u,samplingRate);
}

bool Cure::SetAdvanced(const int32_t* v,size_t n){return professional.set(v,n);}
bool Cure::SetTools(const int32_t* v,size_t n){if(v && v[0]!=0 && !enabled)return false;return professional.setTools(v,n);}
void Cure::ClearTools(){professional.clearTools();}
void Cure::GetRuntime(int32_t* v)const{professional.runtime(v,enabled);}
bool Cure::SetProfessional2(const int32_t* v,size_t n){return professional.setAdvanced(v,n);}
bool Cure::SetTools2(const int32_t* v,size_t n){if(v && v[0]!=0 && !enabled)return false;return professional.setAdvancedTools(v,n);}
void Cure::GetDiagnostics(int32_t* v)const{professional.diagnostics(v,enabled);}
