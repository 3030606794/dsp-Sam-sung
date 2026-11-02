#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
#include "IIR_1st.h"

namespace headphone {
constexpr unsigned kWords=8, kToolsWords=19, kRuntimeWords=20;
constexpr unsigned kAdvancedWords=11,kAdvancedToolsWords=41,kResponsePoints=32,kDiagnosticWords=212;
using Settings=std::array<int32_t,kWords>;
using Advanced=std::array<int32_t,kAdvancedWords>;
inline constexpr Settings defaults{1,0,700,60,100,0,0,0};
inline constexpr Advanced advancedDefaults{1,0,100,0,60,60,0,0,0,2,0};
bool valid(const int32_t* values,size_t count);
bool validTools(const int32_t* values,size_t count);
bool validAdvanced(const int32_t*,size_t);
bool validAdvancedTools(const int32_t*,size_t);

// Fixed storage: configuring, rendering and publishing never allocate in the audio callback.
class Kernel {
public:
    void configure(const Settings& values,uint32_t rate,const Advanced& advanced=advancedDefaults);
    void process(const float* input,float* output,float* cross);
    void response(int32_t* values) const;
private:
    Settings settings=defaults;
    Advanced advanced=advancedDefaults;
    uint32_t rate=48000;
    double a0lo=0,b1lo=0,a0hi=0,a1hi=0,b1hi=0,gain=1,mix=1,trim=1,delay=0;
    std::array<double,2> crossGain{1,1},pathDelay{};
    std::array<std::array<double,3>,2> extraLow{};
    unsigned stages=1;
    std::array<double,2> previous{},low{},high{};
    std::array<std::array<float,512>,2> history{};
    std::array<std::array<IIR_1st,4>,2> post{},crossPost{};
    unsigned position=0;
};

class Engine {
public:
    Engine();
    bool set(const int32_t* values,size_t count);
    bool setTools(const int32_t* values,size_t count);
    bool setAdvanced(const int32_t*,size_t);
    bool setAdvancedTools(const int32_t*,size_t);
    void setRate(uint32_t rate);
    void reset();
    void clearTools();
    void process(const float* input,float* output);
    void runtime(int32_t* result,bool moduleEnabled) const;
    void diagnostics(int32_t* result,bool moduleEnabled) const;
    bool enabled() const {return settings[1]!=0;}
    int audition() const {return mode;}
private:
    Settings settings=defaults,a=defaults,b=defaults;
    Advanced advanced=advancedDefaults,aAdvanced=advancedDefaults,bAdvanced=advancedDefaults;
    std::array<int32_t,kResponsePoints*6> responseValues{};
    std::array<float,2> peaks{};
    float peakDecay=.99995f;
    std::array<Kernel,2> main{};
    Kernel comparisonA,comparisonB;
    uint32_t rate=48000,fadeLength=480,fade=0,measurementFrames=0;
    unsigned active=0;
    int mode=0,epoch=0,measurement=0;
    bool match=false;
    double energyIn=0,energyA=0,energyB=0;
    float gainA=1,gainB=1;
    std::array<float,2> last{};
    uint32_t toolFade=0;
};
}
