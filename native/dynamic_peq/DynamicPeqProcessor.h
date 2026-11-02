// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <mutex>

namespace dspfusion {
// Independent of PeqProcessor: the detector always observes this module's dry
// input, so static boost and preceding dynamic bands cannot retrigger it.
class DynamicPeqProcessor {
public:
    static constexpr uint32_t kSet=196720,kCapabilityGet=132000,kStatusGet=132001;
    static constexpr size_t kMaxBands=16,kBandWords=12,kMaxWords=3+kMaxBands*kBandWords,kStatusWords=40;
    DynamicPeqProcessor();
    bool setParameters(const int32_t* words,size_t count);
    // The Android host excludes process() while configuring or resetting.
    void configure(uint32_t sampleRate);
    void reset();
    void process(float* interleaved,uint32_t frames);
    void readStatus(int32_t* words) const;
private:
    struct Band {
        int32_t type=0,enabled=0,frequency=1000000,gain=0,q=1000,dynamic=1;
        int32_t threshold=-18000,ratio=4000,range=12000,attack=10000,release=150000,knee=6000;
        bool operator==(const Band&) const = default;
    };
    struct Settings {
        bool enabled=false;size_t count=0;std::array<Band,kMaxBands> bands{};
        bool operator==(const Settings&) const = default;
    };
    struct Circuit {
        double g=0,k=1,high=1,mid=1,low=1;
        void approach(const Circuit& target,double amount);
    };
    struct State {double l1=0,l2=0,r1=0,r2=0;};
    struct PreparedBand {
        Band band{};bool available=false;double g=0,k=1,attack=0,release=0;Circuit detector{},initial{};
    };
    struct Prepared {
        Settings settings{};std::array<PreparedBand,kMaxBands> bands{};bool active=false;uint32_t fingerprint=0;
    };
    struct RuntimeBand {
        State filter{},detector{};Circuit circuit{},target{};
        double envelope=0,reduction=0,reductionTarget=0;uint32_t control=0;
    };
    struct Bank {
        Prepared prepared{};std::array<RuntimeBand,kMaxBands> bands{};
        void tick(double dryLeft,double dryRight,double& left,double& right);
        void clearDenormals();
    };
    static Circuit circuit(const PreparedBand& band,double gainDb);
    static double channel(const Circuit&,double input,double& first,double& second);
    static double gainReduction(const Band&,double levelDb);
    Prepared prepare(const Settings&) const;
    void adoptPending();
    void publish(double inputPeak,double outputPeak,bool active);
    std::mutex mutex_;
    Settings settings_{};Prepared pending_{};
    std::atomic<int32_t> requestedEnabled_{0},requestedCount_{0},publishedRate_{48000};
    uint64_t revision_=0,acceptedRevision_=0;
    uint32_t rate_=48000,fadeFrames_=960,fadePosition_=0;uint64_t processedFrames_=0;
    std::array<Bank,2> banks_{};unsigned active_=0;bool transitioning_=false;
    std::array<std::atomic<int32_t>,kStatusWords> status_{};
    std::atomic<uint32_t> epoch_{0};
};
}
