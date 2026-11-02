// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <atomic>
#include "SurroundProfessionalContract.h"
#include "SurroundListeningTools.h"

namespace dspfusion {
// Independent, band-limited recursive acoustic crosstalk cancellation (RACE).
// No Neutron code or binary is used. The 8 user presets live in the application.
// configure/reset require host exclusion; setParameters may run concurrently.
// cancelAudition(true) also requires host exclusion; plain cancellation is concurrent-safe.
class SurroundProcessor {
public:
    static constexpr size_t kWords=6;
    static constexpr size_t kProfessionalWords=27,kRuntimeWords=32;
    bool setParameters(const int32_t* words,size_t count);
    bool setProfessionalParameters(const int32_t* words,size_t count);
    bool setAudition(int32_t request);
    bool setListeningTools(const int32_t* w,size_t n){return tools_.setParameters(w,n);}
    bool listeningSnapshot(int32_t* w)const{return tools_.snapshot(w);}
    void cancelAudition(bool invalidateMeters=false);
    bool snapshot(int32_t* words) const;
    void configure(uint32_t sampleRate);
    void reset();
    void process(float* interleaved,uint32_t frames);
private:
    SurroundListeningTools tools_;
    struct Settings {
        bool enabled=false;
        int32_t lowHz=200,highHz=9000,attenuation=5500,delayUs=35;
        std::array<int32_t,21> professional=kSurroundProfessionalDefaults;
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
        bool professional=false;
        std::array<Coeff,4> sections{};
        size_t sectionCount=0;
        std::array<double,2> gains{},fractions{};
        std::array<size_t,2> delays{};
        double direct=1,mix=1;
        std::array<int32_t,9> metadata{};
        struct Dynamics {
            bool rms=false,attenuationOnly=true,peak=false;
            double limit=3,attackMs=80,releaseMs=800,ceiling=1,peakReleaseMs=100,output=1;
            double attackAlpha=0,releaseAlpha=0,peakReleaseAlpha=0;
        } dynamics{};
        bool operator==(const Prepared&) const;
    };
    struct State { double s1=0,s2=0; };
    struct Bank {
        static constexpr size_t kRingSize=256;
        Prepared filters{};
        std::array<std::array<State,4>,2> states{};
        std::array<std::array<double,kRingSize>,2> delayLine{};
        size_t position=0;
        void clear();
        void tick(double& left,double& right);
        double previous(size_t channel,size_t delay) const;
        double offset(size_t channel) const;
        double filter(size_t channel,double sample);
        void tickProfessional(double& left,double& right);
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
    Prepared::Dynamics dynamics_{};
    std::atomic<bool> monitoring_{false};
    int requestedAudition_=0,latchedAudition_=0;
    std::atomic<int> actualAudition_{0};
    std::array<double,3> auditionMix_{1,0,0};
    std::array<double,3> auditionFrom_{1,0,0};
    int auditionSelection_=0;
    uint32_t auditionFade_=0;
    uint64_t samples_=0;
    double rmsIn_=0,rmsOut_=0,autoDb_=0,peakGain_=1,outputGain_=1;
    double autoTargetDb_=0;
    uint32_t rmsTick_=0;
    struct Meter {double l=0,r=0,lr=0,peak=0;};
    std::array<Meter,2> meters_{};
    bool wasMonitoring_=false;
    std::array<std::atomic<int32_t>,kRuntimeWords> runtime_{};
    std::atomic<uint32_t> runtimeEpoch_{0};
    void publish(bool valid=true);
    void clearMonitoring();
};
}
