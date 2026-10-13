// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
#include <mutex>

namespace dspfusion {
// Independent, band-limited recursive acoustic crosstalk cancellation (RACE).
// No Neutron code or binary is used. The 8 user presets live in the application.
// configure/reset require host exclusion; setParameters may run concurrently.
class SurroundProcessor {
public:
    static constexpr size_t kWords=6;
    bool setParameters(const int32_t* words,size_t count);
    void configure(uint32_t sampleRate);
    void reset();
    void process(float* interleaved,uint32_t frames);
private:
    struct Settings {
        bool enabled=false;
        int32_t lowHz=200,highHz=9000,attenuation=5500,delayUs=35;
    };
    struct Coeff {
        double b0=1,b1=0,b2=0,a1=0,a2=0;
        bool operator==(const Coeff&) const = default;
    };
    struct Prepared {
        bool enabled=false;
        Coeff highPass{},lowPass{};
        double gain=0,fraction=0;
        size_t delay=0;
        bool operator==(const Prepared&) const = default;
    };
    struct State { double s1=0,s2=0; };
    struct Bank {
        static constexpr size_t kRingSize=256;
        Prepared filters{};
        std::array<std::array<State,2>,2> states{};
        std::array<std::array<double,kRingSize>,2> delayLine{};
        size_t position=0;
        void clear();
        void tick(double& left,double& right);
        double previous(size_t channel,size_t delay) const;
        double offset(size_t channel) const;
        double filter(size_t channel,double sample);
    };
    Prepared prepare(const Settings&) const;
    void adoptPending();
    std::mutex updateMutex_;
    Settings settings_{};
    Prepared pending_{};
    uint64_t revision_=0,acceptedRevision_=0;
    uint32_t sampleRate_=48000,fadeFrames_=960,fadePosition_=0;
    std::array<Bank,2> banks_{};
    unsigned active_=0;
    bool transitioning_=false;
};
}
