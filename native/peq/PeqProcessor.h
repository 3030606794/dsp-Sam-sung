// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
#include <mutex>

namespace dspfusion {
// Stereo, independently implemented RBJ biquads. setParameters() may run on
// the control thread concurrently with process(); configure/reset require the
// caller to exclude process(), as Android SET_CONFIG/RESET already do.
class PeqProcessor {
public:
    static constexpr size_t kMaxBands=170;
    static constexpr size_t kMaxWords=3+kMaxBands*5;
    enum Type : int32_t { Peak, LowShelf, HighShelf, LowPass, HighPass, Notch, BandPass };
    bool setParameters(const int32_t* words,size_t count);
    void configure(uint32_t sampleRate);
    void reset();
    void process(float* interleaved,uint32_t frames);
private:
    struct Band { int32_t type=0,enabled=0,frequency=1000000,gain=0,q=707; };
    struct Settings { bool enabled=false; size_t count=0; std::array<Band,kMaxBands> bands{}; };
    struct Coeff {
        double b0=1,b1=0,b2=0,a1=0,a2=0;
        bool operator==(const Coeff&) const = default;
    };
    struct Prepared { size_t count=0; std::array<Coeff,kMaxBands> coefficients{}; };
    struct State { double l1=0,l2=0,r1=0,r2=0; };
    struct Bank {
        Prepared filters{};
        std::array<State,kMaxBands> states{};
        void tick(double& left,double& right);
        void flushDenormals();
    };
    static bool design(const Band&,uint32_t rate,Coeff& result);
    Prepared prepare(const Settings&) const;
    void adoptPending();
    // Audio never waits for this lock: it keeps the previous bank if a new
    // snapshot is being prepared. No audio callback allocates memory.
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
