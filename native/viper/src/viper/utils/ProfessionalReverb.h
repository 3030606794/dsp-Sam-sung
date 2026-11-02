#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
#include <memory>

namespace reverbtuning {
constexpr unsigned kWords=18,kToolsWords=39,kAdvancedWords=15,kAdvancedToolsWords=69,kRuntimeWords=32,kDecayWords=16;
using Settings=std::array<int32_t,kWords>;
using Advanced=std::array<int32_t,kAdvancedWords>;
inline constexpr Advanced advancedDefaults{1,0,150,0,100,100,250,4000,0,-240,90,20,350,0,0};
inline constexpr Settings defaults{1,0,0,0,0,0,500,0,0,0,80,0,12000,0,0,0,0,0};
bool valid(const int32_t*,size_t);
bool validAdvanced(const int32_t*,size_t);
bool validTools(const int32_t*,size_t);

// Storage is allocated once when the effect is constructed. Retuning and rendering allocate nothing.
class Kernel {
public:
    Kernel();
    ~Kernel();
    Kernel(const Kernel&)=delete;
    Kernel& operator=(const Kernel&)=delete;
    void configure(const Settings&,uint32_t,bool comparison=false,const Advanced& advanced=advancedDefaults);
    void retune(const Settings&,const Advanced& advanced=advancedDefaults);
    void decay(int32_t*)const;
    void process(const float*,float*,float*);
private:
    struct State;
    std::unique_ptr<State> state;
};

class Engine {
public:
    Engine();
    bool set(const int32_t*,size_t);
    bool setAdvanced(const int32_t*,size_t);
    bool setTools(const int32_t*,size_t);
    void setRate(uint32_t);
    void reset();
    void clearTools();
    void process(const float*,float*);
    void runtime(int32_t*,bool moduleEnabled)const;
    void decay(int32_t*,bool moduleEnabled)const;
    bool enabled()const{return settings[1]!=0;}
private:
    Settings settings=defaults,a=defaults,b=defaults;
    Advanced advanced=advancedDefaults,advancedA=advancedDefaults,advancedB=advancedDefaults;
    Kernel normal,comparisonA,comparisonB;
    uint32_t rate=48000,fadeLength=480,toolFade=0,warmFrames=0,warmTarget=0,measureFrames=0,processedFrames=0;
    int mode=0,epoch=0,measurement=0;
    bool match=false;
    double energyIn=0,energyA=0,energyB=0;
    float gainA=1,gainB=1,peakDecay=.99995f;
    std::array<float,6> peaks{};
    std::array<float,2> last{},fadeFrom{};
    uint32_t clips=0;
};
}
