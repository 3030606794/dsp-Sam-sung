// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <array>
#include <cstddef>
#include <cstdint>

namespace dspfusion {
// The caller serializes configuration, diagnostics and process(). Fixed storage:
// neither parameter preparation nor the audio callback allocates or frees memory.
class ToneProcessor {
public:
    static constexpr size_t advancedWords=80, toolsWords=164, statusWords=64, responseWords=200;
    ToneProcessor();
    void configure(uint32_t sampleRate);
    bool setParameters(const int32_t* values, size_t count);
    void process(float* interleaved, uint32_t frames);
    void reset();
    static bool validateAdvanced(const int32_t* values,size_t count);
    static bool validateTools(const int32_t* values,size_t count);
    static std::array<int32_t,advancedWords> defaults();
    bool setAdvanced(const int32_t* values,size_t count);
    bool setTools(const int32_t* values,size_t count);
    void clearTools();
    void disable();
    void status(int32_t* values,size_t count=statusWords) const;
    void response(int32_t* values,size_t count=responseWords) const;
private:
    using Settings=std::array<int32_t,advancedWords>;
    struct Coeff { double b0=1,b1=0,b2=0,a1=0,a2=0; };
    struct Filter {
        Coeff now{},target{};
        double z1[2]{},z2[2]{};
        double tick(double input,unsigned channel);
        void advance(double alpha);
        void clear();
    };
    struct Kernel {
        Settings settings{};
        std::array<Filter,10> filters{};
        std::array<int32_t,10> knobs{};
        std::array<double,10> values{};
        uint32_t rate=48000;
        bool enabled=false,neutral=true;
        double alpha=0,wet=0,wetTarget=0,preamp=1,preampTarget=1;
        double punch=0,punchTarget=0,transient=0,transientTarget=0;
        double low[2]{},fast=0,slow=0,bassFast=0,bassSlow=0;
        double lowAlpha=0,fastAlpha=0,slowAlpha=0,bassFastAlpha=0,bassSlowAlpha=0;
        double punchSensitivity=1,transientSensitivity=1,punchMaximum=3,transientMaximum=3;
        double weakFloor=0,mix=1,output=1,manual=1,mixTarget=1,outputTarget=1,manualTarget=1,punchDb=0,transientDb=0;
        bool restoring=false;
        double restorePunchCap=0,restoreTransientCap=0;
        std::array<double,10> actualHz{},actualQ{};
        void configure(uint32_t sampleRate);
        void assign(const Settings& parameters,bool resetState);
        void design();
        void reset();
        void tick(const float* input,float* outputSamples);
    };
    static Coeff peak(double rate,double hz,double q,double db);
    static Coeff shelf(double rate,double hz,double db,bool high,double slope=1);
    static double magnitude(const Coeff&,double omega);
    static bool sameSound(const Settings&,const Settings&);
    static bool neutralSettings(const Settings&);
    void begin(const Settings& settings);
    void publish();
    void clearToolsInternal();
    std::array<Kernel,6> kernels_{};
    Settings saved_{};
    int active_=0,pending_=1;
    uint32_t sampleRate_=48000,pendingFrames_=0,pendingWarmFrames_=0,fadeFrames_=0,fadePosition_=0;
    bool pendingValid_=false;
    std::array<int32_t,toolsWords> tools_{};
    uint32_t compareFrames_=0,compareWarmFrames_=0,measureFrames_=0;
    int stage_=0;
    bool comparisonPrepared_=false,comparisonWarmed_=false,retiredCompare_=false;
    double energyA_=0,energyB_=0,attenuationA_=1,attenuationB_=1;
    std::array<double,4> routeWeights_{{0,1,0,0}};
    double inputPeak_[2]{},outputPeak_[2]{},inputEnergy_[2]{},outputEnergy_[2]{};
    uint64_t meterFrames_=0;
    uint32_t clips_=0,epoch_=0;
    std::array<int32_t,statusWords> runtime_{};
    std::array<int32_t,responseWords> response_{};
};
}
