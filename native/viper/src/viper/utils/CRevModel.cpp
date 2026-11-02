#include "CRevModel.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <memory>

CRevModel::CRevModel() : gain(0), roomSize(0), internalRoomSize(0), damp(0),
        internalDamp(0), wet(0), unknown1(0), unknown2(0), dry(0), width(0), mode(0) {
    SetSamplingRate(48000);

    allpassL[0].SetFeedback(0.5);
    allpassR[0].SetFeedback(0.5);
    allpassL[1].SetFeedback(0.5);
    allpassR[1].SetFeedback(0.5);
    allpassL[2].SetFeedback(0.5);
    allpassR[2].SetFeedback(0.5);
    allpassL[3].SetFeedback(0.5);
    allpassR[3].SetFeedback(0.5);

    SetWet(0.167);
    SetRoomSize(0.5);
    SetDry(0.25);
    SetDamp(0.5);
    SetWidth(1.0);
    SetMode(0.0);

    Reset();
}

CRevModel::~CRevModel() {
    for (auto &buffer : buffers) {
        delete[] buffer;
    }
}

void CRevModel::SetSamplingRate(uint32_t rate) {
    rate=std::clamp(rate,8000u,768000u);
    if(rate==samplingRate)return;
    // The original 48 kHz sound is the time reference. Prepare every line before
    // replacing live storage; an allocation failure leaves the old bank intact.
    constexpr unsigned original[]{1116,1139,1188,1211,1277,1300,1356,1379,
        1422,1445,1491,1514,1557,1580,1617,1640,556,579,441,464,341,364,225,248};
    std::array<unsigned,24> lengths{};
    std::array<std::unique_ptr<float[]>,24> prepared;
    for(unsigned i=0;i<24;++i){
        lengths[i]=std::max(1u,static_cast<unsigned>(std::lround(double(original[i])*rate/48000.)));
        prepared[i]=std::make_unique<float[]>(lengths[i]);
    }
    for(unsigned i=0;i<24;++i){delete[] buffers[i];buffers[i]=prepared[i].release();}
    for(unsigned i=0;i<8;++i){combL[i].SetBuffer(buffers[2*i],lengths[2*i]);combR[i].SetBuffer(buffers[2*i+1],lengths[2*i+1]);}
    for(unsigned i=0;i<4;++i){allpassL[i].SetBuffer(buffers[16+2*i],lengths[16+2*i]);allpassR[i].SetBuffer(buffers[17+2*i],lengths[17+2*i]);}
    samplingRate=rate;
    UpdateCoeffs();
    ResetComparisonState();
}

float CRevModel::GetDamp() {
    return damp / 0.4f;
}

float CRevModel::GetDry() {
    return dry / 2.0f;
}

float CRevModel::GetMode() {
    if (this->mode >= 0.5) {
        return 1.0;
    }
    return this->mode;
}

float CRevModel::GetRoomSize() {
    return (this->roomSize - 0.7f) / 0.28f;
}

float CRevModel::GetWet() {
    return wet / 3.0f;
}

float CRevModel::GetWidth() {
    return width;
}

void CRevModel::Mute() {
    if (GetMode() >= 0.5) return;

    for (int i = 0; i < 8; i++) {
        combL[i].Mute();
        combR[i].Mute();
    }

    for (int i = 0; i < 4; i++) {
        allpassL[i].Mute();
        allpassR[i].Mute();
    }
}

void CRevModel::ProcessReplace(float *bufL, float *bufR, uint32_t size) {
    for (uint32_t idx = 0; idx < size * 2; idx += 2) {
        float outL = 0.0;
        float outR = 0.0;
        float input = (bufL[idx] + bufR[idx]) * this->gain;

        for (uint32_t i = 0; i < 8; i++) {
            outL += this->combL[i].Process(input);
            outR += this->combR[i].Process(input);
        }

        for (uint32_t i = 0; i < 4; i++) {
            outL = this->allpassL[i].Process(outL);
            outR = this->allpassR[i].Process(outR);
        }

        bufL[idx] = outL * this->unknown1 + outR * this->unknown2 + bufL[idx] * this->dry;
        bufR[idx] = outR * this->unknown1 + outL * this->unknown2 + bufR[idx] * this->dry;
    }
}

void CRevModel::Reset() {
    if (GetMode() >= 0.5) return;

    for (int i = 0; i < 8; i++) {
        combL[i].Mute();
        combR[i].Mute();
    }

    for (int i = 0; i < 4; i++) {
        allpassL[i].Mute();
        allpassR[i].Mute();
    }
}

void CRevModel::SetDamp(float damp) {
    this->damp = damp * 0.4f;
    UpdateCoeffs();
}
void CRevModel::ResetComparisonState(){for(auto& filter:combL)filter.ResetState();for(auto& filter:combR)filter.ResetState();for(auto& filter:allpassL)filter.ResetState();for(auto& filter:allpassR)filter.ResetState();}

void CRevModel::SetDry(float dry) {
    this->dry = dry * 2.0f;
}

void CRevModel::SetMode(float mode) {
    this->mode = mode;
    UpdateCoeffs();
}

void CRevModel::SetRoomSize(float roomSize) {
    this->roomSize = (roomSize * 0.28f) + 0.7f;
    UpdateCoeffs();
}

void CRevModel::SetWet(float wet) {
    this->wet = wet * 3.0f;
    UpdateCoeffs();
}

void CRevModel::SetWidth(float width) {
    this->width = width;
    UpdateCoeffs();
}

void CRevModel::UpdateCoeffs() {
    this->unknown1 = this->wet * (this->width / 2.0f + 0.5f);
    this->unknown2 = this->wet * (1.0f - this->width) / 2.0f;

    if (this->mode >= 0.5) {
        this->internalRoomSize = 1.0;
        this->internalDamp = 0.0;
        this->gain = 0.0;
    } else {
        this->internalRoomSize = this->roomSize;
        this->internalDamp = this->damp;
        this->gain = 0.015;
    }

    // Preserve the original pole exactly at 48 kHz, and its time constant at
    // other actual rates. Scaling delay lines alone would change the damping.
    const float rateDamp = samplingRate == 48000 || internalDamp == 0.f ? internalDamp :
        static_cast<float>(std::pow(internalDamp,48000. / samplingRate));
    for (int i = 0; i < 8; i++) {
        this->combL[i].SetFeedback(this->internalRoomSize);
        this->combL[i].SetDamp(rateDamp);
        this->combR[i].SetFeedback(this->internalRoomSize);
        this->combR[i].SetDamp(rateDamp);
    }
}
