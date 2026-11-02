// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <array>
#include <atomic>
#include <cstdint>
#include <mutex>

namespace dspfusion {
// Temporary audition state. No measured gain or generator trigger is a preset.
class SurroundListeningTools {
public:
    static constexpr size_t kWords=13,kRuntimeWords=24;
    bool setParameters(const int32_t*,size_t);
    void cancel(bool invalidate=false); // invalidate requires host exclusion
    void begin(uint32_t rate,bool enabled,int audition);
    void inject(double& left,double& right);
    double match(double dryL,double dryR,double left,double right,bool settled);
    void publish();
    bool snapshot(int32_t*) const;
private:
    using Packet=std::array<int32_t,kWords>;
    Packet pending_{1,0,60,3000,1,0,-300,1000,200,8000,5000,100,0},config_=pending_;
    std::mutex mutex_;
    std::atomic<bool> cancel_{false};
    std::atomic<int32_t> blockedToken_{0};
    std::array<std::atomic<int32_t>,kRuntimeWords> runtime_{};
    std::atomic<uint32_t> epoch_{0};
    uint32_t rate_=48000;
    bool enabled_=false;
    int audition_=0,measuring_=0,mode_=0,lastToken_=0;
    uint64_t count_=0,settle_=0,position_=0,total_=0,fade_=4800,tail_=0;
    std::array<bool,2> ready_{};
    std::array<double,2> ratios_{},gains_{};
    double inPower_=0,outPower_=0,applied_=1,alpha_=0,phase_=0,amplitude_=0;
    double hp_=0,lp_=0,hpB0_=0,lpB0_=0,hpA1_=0,lpA1_=0,noiseGain_=1,envelope_=0,tailFrom_=0;
    uint32_t random_=0x6d2b79f5;
    int low_=200,high_=8000,tone_=1000,progress_=0;
    bool limited_=false,quiet_=false;
    void clearMatch();
    void clearGenerator();
};
}
