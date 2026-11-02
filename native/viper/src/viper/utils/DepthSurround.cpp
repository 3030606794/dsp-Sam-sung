#include "DepthSurround.h"
#include <cmath>
#include "../constants.h"
#include "ProfessionalTuning.h"

DepthSurround::DepthSurround() {
    this->strength = 0;
    this->enabled = false;
    this->strengthAtLeast500 = false;
    this->gain = 0;
    for (auto &prev : this->prev) {
        prev = 0.0f;
    }
    this->SetSamplingRate(VIPER_DEFAULT_SAMPLING_RATE);
    this->RefreshStrength(this->strength);
}

void DepthSurround::Process(float *samples, uint32_t size) {
    running=true;
    for(uint32_t i=0;i<size;++i) {
        gain=professional::advance(gain,gainTarget,alpha);
        polarity=professional::advance(polarity,polarityTarget,alpha);
        activeMix=professional::advance(activeMix,enabled?1.f:0.f,alpha);
        if(activeMix==0.f)continue;
        const float left=samples[2*i],right=samples[2*i+1];
        prev[0]=gain*timeConstDelay[0].ProcessSample(left+prev[1]);
        prev[1]=(polarity*gain)*timeConstDelay[1].ProcessSample(right+prev[0]);
        const float l=prev[0]+left,r=prev[1]+right;
        const float diff=(l-r)/2.f,avg=(l+r)/2.f;
        const float filtered=static_cast<float>(highpass.ProcessSample(diff));
        const float wetL=avg+(diff-filtered),wetR=avg-(diff-filtered);
        samples[2*i]=activeMix==1.f?wetL:left+activeMix*(wetL-left);
        samples[2*i+1]=activeMix==1.f?wetR:right+activeMix*(wetR-right);
    }
}

void DepthSurround::RefreshStrength(short strength) {
    this->strengthAtLeast500 = strength >= 500;
    this->enabled = strength != 0;
    if (strength != 0) {
        float gain = (float) pow(10.0, ((strength / 1000.0) * 10.0 - 15.0) / 20.0);
        if (gain > 1.0) {
            gain = 1.0;
        }
        this->gainTarget = (float) gain;
    } else {
        this->gainTarget = 0.0;
    }
    polarityTarget=strengthAtLeast500?-1.f:1.f;
    if(!running){gain=gainTarget;polarity=polarityTarget;activeMix=enabled?1.f:0.f;}
}

void DepthSurround::SetSamplingRate(uint32_t samplingRate) {
    alpha=professional::alpha(samplingRate);running=false;
    gain=gainTarget;polarity=polarityTarget;activeMix=enabled?1.f:0.f;
    this->timeConstDelay[0].SetParameters(samplingRate, 0.02);
    this->timeConstDelay[1].SetParameters(samplingRate, 0.014);
    this->highpass.SetHighPassParameter(800.0f, samplingRate, -11.0f, 0.72f, 0.0);
    for (auto &prev : this->prev) {
        prev = 0.0f;
    }
}

void DepthSurround::SetStrength(short strength) {
    this->strength = strength;
    this->RefreshStrength(strength);
}
