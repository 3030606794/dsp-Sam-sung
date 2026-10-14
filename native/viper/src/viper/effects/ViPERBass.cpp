#include "ViPERBass.h"
#include "../constants.h"
#include <cmath>

// Iscle: Verified with the latest version at 13/12/2022

ViPERBass::ViPERBass() : polyphase(2), waveBuffer(2, 4096) {
    this->speaker = 60;
    this->enable = false;
    this->processMode = ProcessMode::NATURAL_BASS;
    this->antiPop = 0.0;
    this->bassFactor = 0.0;
    this->samplingRate = VIPER_DEFAULT_SAMPLING_RATE;
    this->samplingRatePeriod = 1.0 / VIPER_DEFAULT_SAMPLING_RATE;

    for (auto &biquad : this->biquad) {
        biquad.Reset();
        biquad.SetLowPassParameter((float) this->speaker, this->samplingRate, 0.53);
    }
    this->subwoofer.SetBassGain(this->samplingRate, 0.0);
    Reset();
}

void ViPERBass::Process(float *samples, uint32_t size) {
    if (!this->enable) return;
    if (size == 0) return;

    // Iscle: TODO: Maybe we could attenuate the effect instead of the entire sample
    if (this->antiPop < 1.0) {
        for (uint32_t i = 0; i < size * 2; i += 2) {
            samples[i] *= this->antiPop;
            samples[i + 1] *= this->antiPop;
            
            float x = this->antiPop + this->samplingRatePeriod;
            if (x > 1.0) x = 1.0;
            this->antiPop = x;
        }
    }

    switch (this->processMode) {
        case ProcessMode::NATURAL_BASS: {
            for (uint32_t i = 0; i < size * 2; i += 2) {
                AdvanceTuning();
                samples[i] += (float) this->biquad[0].ProcessSample(samples[i]) * this->currentFactor;
                samples[i + 1] += (float) this->biquad[1].ProcessSample(samples[i + 1]) * this->currentFactor;
            }
            break;
        }
        case ProcessMode::PURE_BASS_PLUS: {
            if (this->waveBuffer.PushSamples(samples, size)) {
                float *buffer = this->waveBuffer.GetBuffer();
                uint32_t bufferOffset = this->waveBuffer.GetBufferOffset();

                for (uint32_t i = 0; i < size * 2; i += 2) {
                    AdvanceTuning();
                    buffer[(bufferOffset - size) * 2 + i] = (float) this->biquad[0].ProcessSample(samples[i]) * this->currentFactor;
                    buffer[(bufferOffset - size) * 2 + i + 1] = (float) this->biquad[1].ProcessSample(samples[i + 1]) * this->currentFactor;
                }

                if (this->polyphase.Process(samples, size) == size) {
                    for (uint32_t i = 0; i < size * 2; i += 2) {
                        samples[i] += buffer[i];
                        samples[i + 1] += buffer[i + 1];
                    }
                    this->waveBuffer.PopSamples(size, true);
                }
            }
            break;
        }
        case ProcessMode::SUBWOOFER: {
            this->subwoofer.Process(samples, size);
            break;
        }
    }
}

void ViPERBass::Reset() {
    currentFrequency = static_cast<float>(speaker);
    currentQ = q;
    currentFactor = bassFactor;
    tuningTick = 0;
    factorSmoothing = -std::expm1(-1.f / (.015f * samplingRate));
    filterSmoothing = -std::expm1(-16.f / (.015f * samplingRate));
    this->polyphase.SetSamplingRate(this->samplingRate);
    this->polyphase.Reset();
    this->waveBuffer.Reset();
    this->waveBuffer.PushZeros(this->polyphase.GetLatency());
    this->subwoofer.SetBassGain(this->samplingRate, this->bassFactor * 2.5f);
    this->subwoofer.Reset();
    this->biquad[0].SetLowPassParameter(currentFrequency, this->samplingRate, q);
    this->biquad[1].SetLowPassParameter(currentFrequency, this->samplingRate, q);
    this->samplingRatePeriod = 1.0f / (float) this->samplingRate;
    this->antiPop = 0.0f;
}

void ViPERBass::SetBassFactor(float bassFactor) {
    if (!std::isfinite(bassFactor) || bassFactor < 0.f) return;
    if (this->bassFactor != bassFactor) {
        this->bassFactor = bassFactor;
        this->subwoofer.SetBassGain(this->samplingRate, this->bassFactor * 2.5f);
    }
}

void ViPERBass::SetEnable(bool enable) {
    if (this->enable != enable) {
        if (enable) Reset();
        this->enable = enable;
    }
}

void ViPERBass::SetProcessMode(ProcessMode processMode) {
    if (this->processMode != processMode) {
        this->processMode = processMode;
        Reset();
    }
}

void ViPERBass::SetSamplingRate(uint32_t samplingRate) {
    if (this->samplingRate != samplingRate) {
        this->samplingRate = samplingRate;
        factorSmoothing = -std::expm1(-1.f / (.015f * samplingRate));
        filterSmoothing = -std::expm1(-16.f / (.015f * samplingRate));
        this->samplingRatePeriod = 1.0f / (float) samplingRate;
        this->polyphase.SetSamplingRate(this->samplingRate);
        this->biquad[0].SetLowPassParameter(currentFrequency, this->samplingRate, currentQ, false);
        this->biquad[1].SetLowPassParameter(currentFrequency, this->samplingRate, currentQ, false);
        this->subwoofer.SetBassGain(this->samplingRate, this->bassFactor * 2.5f);
    }
}

void ViPERBass::SetSpeaker(uint32_t speaker) {
    if (this->speaker != speaker) {
        this->speaker = speaker;
    }
}

void ViPERBass::SetQ(float value) {
    if (std::isfinite(value) && value >= .3f && value <= 2.f) q = value;
}

void ViPERBass::SetSubwooferTuning(unsigned parameter, float value) {
    subwoofer.SetTuning(parameter, value);
}

void ViPERBass::AdvanceTuning() {
    const float previousFactor = currentFactor;
    currentFactor += factorSmoothing * (bassFactor - currentFactor);
    if (currentFactor == previousFactor) currentFactor = bassFactor;
    if (tuningTick++ % 16 != 0 || (currentFrequency == speaker && currentQ == q)) return;
    const float previousFrequency = currentFrequency, previousQ = currentQ;
    currentFrequency += filterSmoothing * (speaker - currentFrequency);
    currentQ += filterSmoothing * (q - currentQ);
    if (currentFrequency == previousFrequency || std::abs(speaker - currentFrequency) < 1.e-4f) currentFrequency = static_cast<float>(speaker);
    if (currentQ == previousQ || std::abs(q - currentQ) < 1.e-5f) currentQ = q;
    for (auto &filter : biquad) filter.SetLowPassParameter(currentFrequency, samplingRate, currentQ, false);
}
