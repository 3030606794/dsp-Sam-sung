#include "OversampledHarmonics.h"
#include <algorithm>
#include <cmath>

OversampledHarmonics::OversampledHarmonics(unsigned multiplier,bool oldPath):factor(multiplier),legacy(oldPath) {
    taps=factor==1?1:32*factor+1;
    if(factor>1) {
        constexpr double pi=3.14159265358979323846;
        const double cutoff=.45/factor;double sum=0;
        for(unsigned i=0;i<taps;++i) {
            const double position=static_cast<double>(i)-(taps-1)*.5;
            const double sinc=position==0?2*cutoff:std::sin(2*pi*cutoff*position)/(pi*position);
            const double window=.42-.5*std::cos(2*pi*i/(taps-1))+.08*std::cos(4*pi*i/(taps-1));
            sum+=(coefficients[i]=sinc*window);
        }
        for(unsigned i=0;i<taps;++i)coefficients[i]/=sum;
    }
    Reset();
}
void OversampledHarmonics::Shape(float even,float highOrder,bool reset) {
    if(legacy) {even=0.f;highOrder=1.f;}
    float weights[10];weights[0]=.02f;weights[1]=.02f*even;
    for(unsigned i=2;i<10;++i)weights[i]=.02f*highOrder*(i%2?even:1.f);
    for(auto& channel:channels) {
        if(reset) {channel.harmonic.SetOversampling(factor);channel.harmonic.SetHarmonics(weights);}
        else channel.harmonic.UpdateCoeffs(weights);
    }
    previousEven=even;previousHigh=highOrder;
}
void OversampledHarmonics::Reset(float even,float highOrder) {
    tick=0;
    for(auto& channel:channels) {channel.input={};channel.shaped={};channel.inputPosition=channel.shapedPosition=0;}
    Shape(even,highOrder,true);
}
double OversampledHarmonics::Nonlinear(Channel& channel,double value,float drive) {
    if(!legacy) {
        // Drive acts before the polynomial. Positive gain has a continuous soft
        // shoulder; bounded polynomial input keeps the new H10 path well behaved
        // even for resonant HP/interpolator overshoot. Only the legacy path is exempt.
        value=value*drive/(1.0+std::max(0.f,drive-1.f)*std::abs(value));
        value=std::clamp(value,-1.0,1.0);
    }
    return channel.harmonic.Process(value);
}
void OversampledHarmonics::Process(const double input[2],double output[2],float drive,float even,float highOrder) {
    if(!legacy && tick++%16==0 && (even!=previousEven || highOrder!=previousHigh))Shape(even,highOrder,false);
    for(unsigned ch=0;ch<2;++ch) {
        auto& state=channels[ch];
        if(factor==1) {output[ch]=Nonlinear(state,input[ch],drive);continue;}
        state.input[state.inputPosition]=input[ch];
        for(unsigned phase=0;phase<factor;++phase) {
            double upsampled=0;
            for(unsigned k=phase,delay=0;k<taps;k+=factor,++delay)
                upsampled+=coefficients[k]*state.input[(state.inputPosition+inputFrames-delay)%inputFrames];
            state.shaped[state.shapedPosition]=Nonlinear(state,upsampled*factor,drive);
            if(phase==0) {
                double decimated=0;
                for(unsigned k=0;k<taps;++k)decimated+=coefficients[k]*state.shaped[(state.shapedPosition+taps-k)%taps];
                output[ch]=decimated;
            }
            state.shapedPosition=(state.shapedPosition+1)%taps;
        }
        state.inputPosition=(state.inputPosition+1)%inputFrames;
    }
}
