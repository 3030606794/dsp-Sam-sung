#pragma once
#include <array>
#include "Harmonic.h"

// FIR interpolation uses only its non-zero input phases; decimation evaluates
// a convolution only for the one retained output per host frame.
class OversampledHarmonics {
public:
    explicit OversampledHarmonics(unsigned factor=1,bool legacy=false);
    void Reset(float even=0.f,float highOrder=1.f);
    void Process(const double input[2],double output[2],float drive,float even,float highOrder);
    unsigned Factor() const {return factor;}
    unsigned Latency() const {return factor==1?0:32;}
private:
    static constexpr unsigned maxTaps=129,inputFrames=33;
    struct Channel {
        std::array<double,inputFrames> input{};
        std::array<double,maxTaps> shaped{};
        unsigned inputPosition=0,shapedPosition=0;
        Harmonic harmonic;
    };
    std::array<Channel,2> channels;
    std::array<double,maxTaps> coefficients{};
    unsigned factor=1,taps=1,tick=0;bool legacy=false;
    float previousEven=0.f,previousHigh=1.f;
    void Shape(float even,float highOrder,bool reset);
    double Nonlinear(Channel& channel,double value,float drive);
};
