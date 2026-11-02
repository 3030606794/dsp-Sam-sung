#pragma once
#include <array>
#include <cstdint>
#include <limits>

// Bilinear one-pole LP. Its complementary HP is exactly x-LP(x).
class ImagingLowpass {
public:
    void Configure(float hz,uint32_t rate);
    void Reset(){state=0;}
    double Process(double sample);
private:
    double b=.5,a=0,state=0;
};

class ImagingMeter {
public:
    static constexpr int32_t invalid=std::numeric_limits<int32_t>::min();
    void Reset(uint32_t rate);
    void Process(float left,float right);
    int32_t Get(unsigned index) const;
private:
    uint32_t window=4800,count=0;
    double meanL=0,meanR=0,varL=0,varR=0,cov=0,midEnergy=0,sideEnergy=0,peak=0;
    std::array<int32_t,4> result{invalid,invalid,invalid,invalid};
    bool ready=false;
    void ClearWindow();
};
