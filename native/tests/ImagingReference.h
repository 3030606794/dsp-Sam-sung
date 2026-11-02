#pragma once
// Frozen 06f2560 ColorfulMusic equations. The only defined replacement for the
// old modff/aliased count write is its observed slot-zero, one-sample behavior.
// No production ColorfulMusic, DepthSurround, Stereo3DSurround or delay is used.
#include <algorithm>
#include <cmath>
#include "../viper/src/viper/utils/Biquad.h"

struct ImagingReference {
    Biquad filter;
    float previous[2]{},delay[2]{},leftCoefficient=.5f,rightCoefficient=.5f,gain=0;
    int strength=0;
    ImagingReference(unsigned rate,float widen,float middle,int depth):strength(depth) {
        const float tmp=widen+1.f,x=tmp+1.f,y=x<2.f?.5f:1.f/x;
        leftCoefficient=middle*y;rightCoefficient=tmp*y;
        gain=depth?std::min(1.f,static_cast<float>(std::pow(10.,((depth/1000.)*10.-15.)/20.))):0.f;
        filter.SetHighPassParameter(800.f,rate,-11.f,.72f,0.);
    }
    void Process(float* data,unsigned frames) {
        for(unsigned i=0;i<frames;++i) {
            float left=data[2*i],right=data[2*i+1];
            if(strength!=0) {
                const float delayedL=delay[0];delay[0]=left+previous[1];previous[0]=gain*delayedL;
                const float delayedR=delay[1];delay[1]=right+previous[0];previous[1]=(strength<500?gain:-gain)*delayedR;
                const float l=previous[0]+left,r=previous[1]+right;
                const float diff=(l-r)/2.f,avg=(l+r)/2.f,filtered=static_cast<float>(filter.ProcessSample(diff));
                left=avg+(diff-filtered);right=avg-(diff-filtered);
            }
            const float a=leftCoefficient*(left+right),b=rightCoefficient*(right-left);
            data[2*i]=a-b;data[2*i+1]=a+b;
        }
    }
};
