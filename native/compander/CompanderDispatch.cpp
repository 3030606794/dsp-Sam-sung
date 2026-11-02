#include "Compander.h"
#include <cmath>
#include <cstring>
extern "C" {
#include "../../app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/jdsp_header.h"
}
#undef min
#undef max

extern "C" {
void CompressorConstructor(JamesDSPLib* owner) {
    CompressorLegacyConstructor(owner);
    owner->companderNative=CompanderCreate();
    if(owner->companderNative)CompanderRefresh(owner->companderNative,owner);
}
void CompressorDestructor(JamesDSPLib* owner) {
    CompanderDestroy(owner->companderNative);owner->companderNative=nullptr;
    CompressorLegacyDestructor(owner);
}
void CompressorSetParam(JamesDSPLib* owner,float time,int granularity,int transform,char force) {
    if(!std::isfinite(time)||time<.06f||time>.3f||granularity<0||granularity>4||transform<0||transform>3)return;
    if(!owner->companderNative){CompressorLegacySetParam(owner,time,granularity,transform,force);return;}
    owner->comp.fgt_facT=time;owner->comp.granularity=granularity;owner->comp.tfresolution=transform;
    owner->compForceRefresh=0;
    if(force)CompanderRefresh(owner->companderNative,owner);
    CompanderSetLegacyParam(owner->companderNative,owner,time,granularity,transform);
}
void CompressorSetGain(JamesDSPLib* owner,double* freq,double* gain,char copy) {
    if(copy){
        if(!freq||!gain)return;
        double previous=0;
        float previousWire=0;
        for(unsigned i=0;i<7;++i){
            const float wireFrequency=static_cast<float>(freq[i]);
            if(!std::isfinite(freq[i])||!std::isfinite(gain[i])||freq[i]<=previous||freq[i]>=24000||
                !std::isfinite(wireFrequency)||wireFrequency<=previousWire||wireFrequency>=24000||
                gain[i]<-double(1.2f)||gain[i]>double(1.2f))return;
            previous=freq[i];
            previousWire=wireFrequency;
        }
    }
    if(!owner->companderNative){CompressorLegacySetGain(owner,freq,gain,copy);return;}
    if(copy){std::memcpy(owner->comp.freq2+1,freq,7*sizeof(double));std::memcpy(owner->comp.gains2+1,gain,7*sizeof(double));}
    CompanderSetLegacyCurve(owner->companderNative,owner,owner->comp.freq2+1,owner->comp.gains2+1);
}
void CompressorEnable(JamesDSPLib* owner,char enabled) {
    if(!owner->companderNative){CompressorLegacyEnable(owner,enabled);return;}
    if(enabled)owner->compEnabled=1;
    owner->compForceRefresh=0;
    CompanderEnable(owner->companderNative,owner,owner->compEnabled);
}
void CompressorDisable(JamesDSPLib* owner) {
    owner->compEnabled=0;
    if(owner->companderNative)CompanderEnable(owner->companderNative,owner,0);
    else CompressorLegacyDisable(owner);
}
void CompressorProcess(JamesDSPLib* owner,size_t count) {
    if(owner->companderNative)CompanderProcess(owner->companderNative,owner,count);
    else CompressorLegacyProcess(owner,count);
}
}
