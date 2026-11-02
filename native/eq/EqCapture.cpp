#include "EqCapture.h"
#include "EqPcmTelemetry.h"
#include <new>

namespace {
class ObservedEq {
public:
    void begin(uint32_t rate,int enabled,int filterType,int interpolation,
               const double* frequencies,const double* gains,const float* left,const float* right,size_t frames) {
        take=0;
        if(!left||!right||!frequencies||!gains||rate<8000||rate>768000)return;
        // Hash the actual float-valued control axes already applied by the original EQ.
        // No coefficient, original control value or processing state is changed here.
        std::array<int32_t,33> words{};
        words[0]=enabled!=0;words[1]=filterType;words[2]=interpolation;
        for(unsigned i=0;i<15;++i){
            const float hz=float(frequencies[i]),gain=float(gains[i]);
            std::memcpy(words.data()+3+i,&hz,4);std::memcpy(words.data()+18+i,&gain,4);
        }
        const uint32_t signature=eqcapture::hash(words.data(),words.size());
        if(!configured||signature!=parameterHash){
            configured=true;parameterHash=signature;revision=(revision+1u)&0x7fffffffu;
            tap.configuration(revision,enabled!=0,signature,uint32_t(filterType),uint32_t(interpolation));
        }
        if(tap.begin(rate,revision,false)) {
            take=std::min<size_t>(frames,tap.remaining());
            std::copy_n(left,take,inputLeft.data());std::copy_n(right,take,inputRight.data());
        }
    }
    void end(const float* left,const float* right,size_t frames){
        if(left&&right)for(size_t i=0;i<std::min(take,frames);++i)
            tap.push(inputLeft[i],inputRight[i],left[i],right[i],false);
        tap.advance(uint32_t(frames));take=0;
    }
    eqcapture::Capture tap;
private:
    std::array<float,eqcapture::kFrames> inputLeft{},inputRight{};
    size_t take=0;
    uint32_t parameterHash=0,revision=0;
    bool configured=false;
};
}

extern "C" {
void* EqCaptureCreate(){return new(std::nothrow)ObservedEq;}
void EqCaptureDestroy(void* opaque){delete static_cast<ObservedEq*>(opaque);}
void EqCaptureReset(void* opaque){if(opaque)static_cast<ObservedEq*>(opaque)->tap.discontinuity();}
void EqCaptureBegin(void* opaque,uint32_t rate,int enabled,int filterType,int interpolation,
                    const double* frequencies,const double* gains,const float* left,const float* right,size_t frames){
    if(opaque)static_cast<ObservedEq*>(opaque)->begin(rate,enabled,filterType,interpolation,frequencies,gains,left,right,frames);
}
void EqCaptureEnd(void* opaque,const float* left,const float* right,size_t frames){
    if(opaque)static_cast<ObservedEq*>(opaque)->end(left,right,frames);
}
int EqCaptureRead(void* opaque,int32_t* output){
    if(!output)return 0;
    if(!opaque){eqcapture::empty(output);return 1;}
    return static_cast<ObservedEq*>(opaque)->tap.read(output);
}
}
