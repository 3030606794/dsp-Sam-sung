#include "Reverberation.h"
#include "../constants.h"
#include <algorithm>

Reverberation::Reverberation() {
    this->model.SetRoomSize(0.0);
    this->model.SetWidth(0.0);
    this->model.SetDamp(0.0);
    this->model.SetWet(0.0);
    this->model.SetDry(0.5);
    this->model.Reset();

    this->enable = false;
}

void Reverberation::Process(float *buffer, uint32_t size) {
    if (this->enable) {
        if(professional.enabled() || professionalBlend>0.f){
            const float target=professional.enabled()?1.f:0.f;
            for(uint32_t i=0;i<size;++i){
                float input[2]{buffer[i*2],buffer[i*2+1]},legacy[2]{input[0],input[1]},output[2];
                model.ProcessReplace(legacy,legacy+1,1);professional.process(input,output);
                professionalBlend=target>professionalBlend?std::min(target,professionalBlend+blendStep):std::max(target,professionalBlend-blendStep);
                for(unsigned c=0;c<2;++c)buffer[i*2+c]=professionalBlend==1.f?output[c]:legacy[c]+(output[c]-legacy[c])*professionalBlend;
            }
            return;
        }
        this->model.ProcessReplace(buffer, buffer + 1, size);
    }
}

void Reverberation::Reset() {
    this->model.Reset();
    professional.reset();professionalBlend=professional.enabled()?1.f:0.f;
}

void Reverberation::SetDamp(float value) {
    this->model.SetDamp(value);
}

void Reverberation::SetDry(float value) {
    this->model.SetDry(value);
}

void Reverberation::SetEnable(bool enable) {
    if (this->enable != enable) {
        if (!this->enable) {
            Reset();
        }
        this->enable = enable;
        if(!enable)professional.clearTools();
    }
}

void Reverberation::SetRoomSize(float value) {
    this->model.SetRoomSize(value);
}

void Reverberation::SetWet(float value) {
    this->model.SetWet(value);
}

void Reverberation::SetWidth(float value) {
    this->model.SetWidth(value);
}
void Reverberation::SetSamplingRate(uint32_t rate){model.SetSamplingRate(rate);professional.setRate(rate);blendStep=100.f/std::clamp(rate,8000u,768000u);}
bool Reverberation::SetProfessional(const int32_t* v,size_t n){return professional.set(v,n);}
bool Reverberation::SetTools(const int32_t* v,size_t n){if(!reverbtuning::validTools(v,n) || (v[0] && !enable))return false;return professional.setTools(v,n);}
void Reverberation::ClearTools(){professional.clearTools();}
void Reverberation::GetRuntime(int32_t* v)const{professional.runtime(v,enable);v[12]|=professionalBlend>0.f&&professionalBlend<1.f;}

bool Reverberation::SetAdvanced(const int32_t* v,size_t n){return professional.setAdvanced(v,n);}
void Reverberation::GetDecay(int32_t* v)const{professional.decay(v,enable);}
