#pragma once
#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>

namespace professional {
inline bool validImagingAdvanced(uint32_t id,const int32_t* v,size_t count) {
    if(!v)return false;
    if(id==65627) {
        const int32_t lo[6]={0,-480,10,0,1,50},hi[6]={1,-60,80,120,100,1500};
        if(count!=6)return false;
        for(size_t i=0;i<count;++i)if(v[i]<lo[i] || v[i]>hi[i])return false;
        return true;
    }
    if(id==65628) {
        const int32_t lo[6]={0,-30,2,10,200,0},hi[6]={1,50,20,100,2000,1000};
        if(count!=6)return false;
        for(size_t i=0;i<count;++i)if(v[i]<lo[i] || v[i]>hi[i])return false;
        return true;
    }
    if(id==65629) {
        const int32_t lo[4]={0,0,30,20},hi[4]={1,1000,180,300};
        if(count!=4)return false;
        for(size_t i=0;i<count;++i)if(v[i]<lo[i] || v[i]>hi[i])return false;
        return true;
    }
    if(id==65630) {
        const int32_t lo[8]={0,0,0,0,1,200,5,0},hi[8]={1,500,1000,1,4,8000,30,1000};
        if(count!=8)return false;
        for(size_t i=0;i<count;++i)if(v[i]<lo[i] || v[i]>hi[i])return false;
        return v[4]==1 || v[4]==2 || v[4]==4;
    }
    return false;
}
inline bool validImaging(const int32_t* v,size_t count) {
    const int32_t lo[13]={0,0,-120,0,100,2000,0,40,0,0,0,-120,0};
    const int32_t hi[13]={1,2000,60,1,2000,20000,1,500,1000,1,1000,0,3};
    if(!v || count!=13)return false;
    for(size_t i=0;i<count;++i)if(v[i]<lo[i] || v[i]>hi[i])return false;
    return v[4]<v[5];
}
inline bool validSelective(const int32_t* v,size_t count) {
    return v && count==4 && v[0]>=0 && v[0]<=1 && v[1]>=1000 && v[1]<=12000 &&
        v[2]>=2000 && v[2]<=20000 && v[1]<v[2] && v[3]>=300 && v[3]<=1500;
}
inline bool validNonlinear(const int32_t* v,size_t count) {
    return v && count==4 && v[0]>=-120 && v[0]<=180 && v[1]>=0 && v[1]<=100 &&
        v[2]>=0 && v[2]<=100 && (v[3]==1 || v[3]==2 || v[3]==4);
}
inline bool validDynamic(const int32_t* v,size_t count) {
    const int32_t lo[8]={0,1000,300,-600,10,0,1,5},hi[8]={1,16000,1500,0,200,180,1000,2000};
    if(!v || count!=8) return false;
    for(size_t i=0;i<count;++i)if(v[i]<lo[i] || v[i]>hi[i])return false;
    return true;
}
inline bool validDeess(const int32_t* v,size_t count) {
    const int32_t lo[9]={0,2000,3000,300,-600,10,0,1,5},hi[9]={1,12000,20000,1500,0,200,180,100,500};
    if(!v || count!=9) return false;
    for(size_t i=0;i<count;++i)if(v[i]<lo[i] || v[i]>hi[i])return false;
    return v[1]<v[2];
}
inline bool validClarity(const int32_t* values, size_t count) {
    const int32_t lo[11]={0,2000,25,60,500,-120,-120,-120,-120,0,0};
    const int32_t hi[11]={450,16000,100,400,5000,60,60,60,0,10000,5000};
    if(!values || count!=11) return false;
    for(size_t i=0;i<count;++i) if(values[i]<lo[i] || values[i]>hi[i]) return false;
    return true;
}
inline bool validSpectrum(const int32_t* values, size_t count) {
    const int32_t lo[7]={0,1000,300,0,300,-120,0};
    const int32_t hi[7]={5600,16000,1500,24000,1500,0,1};
    if(!values || count!=7) return false;
    for(size_t i=0;i<count;++i) if(values[i]<lo[i] || values[i]>hi[i]) return false;
    return values[3]==0 || values[3]>=8000;
}
inline float frequency(float request, uint32_t rate) {
    return std::clamp(request,1.f,rate*.5f-100.f);
}
inline float alpha(uint32_t rate, unsigned samples=1) {
    return -std::expm1(-static_cast<float>(samples)/(.015f*rate));
}
inline float advance(float current,float target,float smoothing) {
    const float next=current+smoothing*(target-current);
    return next==current || std::abs(target-next)<1.e-6f ? target : next;
}
inline float amplitude(float db) { return std::pow(10.f,db/20.f); }
inline unsigned delayFrames(uint32_t rate,uint32_t microseconds) {
    return static_cast<unsigned>(uint64_t(rate)*microseconds/1000000u);
}
}
