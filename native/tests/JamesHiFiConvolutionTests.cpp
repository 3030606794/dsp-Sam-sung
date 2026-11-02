// SPDX-License-Identifier: GPL-3.0-or-later
extern "C" {
#include "jdsp/jdsp_header.h"
#include "jdsp/Effects/presetCrossfeed.h"
void FFTConvolver2x2TestThreadFailure(int);
void JamesDSPRefreshBlobLegacyReference(JamesDSPLib*,double);
}
#undef min
#undef max
#include <algorithm>
#include <cassert>
#include <cmath>
#include <complex>
#include <cstdio>
#include <cstring>
#include <memory>
#include <thread>
#include <vector>
namespace {
constexpr double pi=3.14159265358979323846;
void stereoWorkerLifecycle(){
    auto conv=std::make_unique<TwoStageFFTConvolver2x2>();
    TwoStageFFTConvolver2x2Init(conv.get());
    for(unsigned fs:{48000u,192000u,768000u})for(unsigned chunk:{3840u,509u,256u}){
        const unsigned size=unsigned(std::ceil(16383.*fs/44100.)),tail=fs>192000?8192:4096;
        std::vector<float> l(size),r(size);l[0]=.4f;r[0]=-.3f;l[511]=.07f;r[1537]=.03f;l[size-1]=.05f;r[size-1]=-.04f;
        int loaded=0;
        std::thread control([&]{loaded=TwoStageFFTConvolver2x2LoadImpulseResponse(conv.get(),512,tail,l.data(),r.data(),size);});control.join();assert(loaded==1&&conv->workerStarted);
        double error=0;
        std::thread audio([&]{
            std::vector<float> inL(chunk),inR(chunk),outL(chunk+8),outR(chunk+8);
            for(unsigned at=0;at<size+tail;){const unsigned n=std::min(chunk,size+tail-at);
                std::fill(inL.begin(),inL.end(),0.f);std::fill(inR.begin(),inR.end(),0.f);
                std::fill(outL.begin(),outL.end(),12345.f);std::fill(outR.begin(),outR.end(),12345.f);
                if(at==0)inL[0]=inR[0]=1.f;
                TwoStageFFTConvolver2x2Process(conv.get(),inL.data(),inR.data(),outL.data(),outR.data(),n);
                for(unsigned i=0;i<n;++i){assert(std::isfinite(outL[i])&&std::isfinite(outR[i]));error=std::max(error,std::abs(double(outL[i])-(at+i<size?l[at+i]:0.f)));error=std::max(error,std::abs(double(outR[i])-(at+i<size?r[at+i]:0.f)));}
                for(unsigned i=n;i<outL.size();++i)assert(outL[i]==12345.f&&outR[i]==12345.f);at+=n;
            }
        });audio.join();assert(error<2e-6);
        // Disposal may occur on a third control thread; no mutex is inherited
        // from the loader or the audio thread. Repeated free must be harmless.
        std::thread cleanup([&]{TwoStageFFTConvolver2x2Free(conv.get());TwoStageFFTConvolver2x2Free(conv.get());});cleanup.join();assert(!conv->workerStarted);
        std::printf("Stereo long IR Fs%u len%u chunk%u maxError%.9g cross-thread load/process/free passed\n",fs,size,chunk,error);
    }
    std::vector<float> l(20000);l[0]=l.back()=.1f;
    for(int stage=0;stage<4;++stage){FFTConvolver2x2TestThreadFailure(stage);assert(!TwoStageFFTConvolver2x2LoadImpulseResponse(conv.get(),512,4096,l.data(),l.data(),l.size()));assert(!conv->workerStarted&&!conv->_tailInput[0]&&!conv->_tailOutput[0]);TwoStageFFTConvolver2x2Free(conv.get());}
    FFTConvolver2x2TestThreadFailure(-1);
    assert(TwoStageFFTConvolver2x2LoadImpulseResponse(conv.get(),512,4096,l.data(),l.data(),l.size()));
    TwoStageFFTConvolver2x2Free(conv.get());
    std::puts("Stereo worker: all four partial-init failures unwind and a subsequent load recovers.");
}
const float* channel(const JamesDSPLib* x,int group,int ch){
    if(group==3)return x->hrtfblobsResampled[ch];
    switch(ch){case 0:return x->blobsCh1[group];case 1:return x->blobsCh2[group];case 2:return x->blobsCh3[group];default:return x->blobsCh4[group];}
}
int length(const JamesDSPLib* x,int group){return group==3?x->frameLenSVirResampled:x->blobsResampledLen;}
double response(const float* x,int n,double frequency,double fs){
    std::complex<double> sum{};const auto step=std::polar(1.,-2*pi*frequency/fs);std::complex<double> phase{1,0};
    for(int i=0;i<n;++i){sum+=double(x[i])*phase;phase*=step;}return std::abs(sum);
}
void builtInImpulseGain(){
    auto base=std::make_unique<JamesDSPLib>();auto current=std::make_unique<JamesDSPLib>();
    JamesDSPInit(base.get(),512,48000);JamesDSPInit(current.get(),512,48000);
    for(unsigned fs:{44100u,48000u}){
        JamesDSPSetSampleRate(current.get(),float(fs),0);JamesDSPRefreshBlobLegacyReference(base.get(),fs);
        for(int group=0;group<4;++group){assert(length(base.get(),group)==length(current.get(),group));for(int ch=0;ch<4;++ch)assert(!std::memcmp(channel(base.get(),group,ch),channel(current.get(),group,ch),length(base.get(),group)*sizeof(float)));}
        std::printf("Built-in FIR Fs%u bit-exact with frozen legacy blob resampler passed\n",fs);
    }
    for(unsigned fs:{96000u,192000u,768000u}){
        JamesDSPSetSampleRate(current.get(),float(fs),0);double maxDb=0;
        for(int group=0;group<4;++group)for(int ch=0;ch<4;++ch)for(double f:{100.,1000.,6000.}){
            const double ref=response(channel(base.get(),group,ch),length(base.get(),group),f,48000.);
            const double hi=response(channel(current.get(),group,ch),length(current.get(),group),f,fs);
            assert(std::isfinite(hi));if(ref>1e-6){double db=std::abs(20*std::log10(hi/ref));maxDb=std::max(maxDb,db);if(db>.25)std::printf("gain mismatch Fs%u group%d ch%d f%.0f %.6fdB\n",fs,group,ch,f,db);assert(db<.25);}
        }
        std::printf("Built-in FIR Fs%u vs48k max passband magnitude difference %.6fdB passed\n",fs,maxDb);
    }
    JamesDSPFree(current.get());JamesDSPFree(base.get());
}
void hrtfDryAlignment(){
    for(unsigned fs:{48000u,192000u,768000u})for(int mode:{0,2,5}){
        auto dsp=std::make_unique<JamesDSPLib>();JamesDSPInit(dsp.get(),512,float(fs));
        CrossfeedChangeMode(dsp.get(),mode);CrossfeedEnable(dsp.get(),1);
        const int32_t settings[]{1,1,mode,700,60,0,0,0,0};
        assert(PresetCrossfeedSet(dsp->advXF.professional,dsp.get(),settings,9));
        auto process=[&](unsigned at,unsigned n,bool impulse){
            std::fill_n(dsp->tmpBuffer[0],n,0.f);std::fill_n(dsp->tmpBuffer[1],n,0.f);
            if(impulse && at==0){dsp->tmpBuffer[0][0]=.125f;dsp->tmpBuffer[1][0]=-.0625f;}
            CrossfeedProcess(dsp.get(),n);
        };
        for(unsigned at=0;at<fs/20;at+=509)process(at,std::min(509u,fs/20-at),false);
        const unsigned expectedDelay=fs>48000 && mode>=2?64*fs/48000:0;
        for(unsigned at=0;at<expectedDelay+1024;){unsigned n=std::min(509u,expectedDelay+1024-at);process(at,n,true);
            for(unsigned i=0;i<n;++i){assert(std::abs(dsp->tmpBuffer[0][i]-(at+i==expectedDelay?.125f:0.f))<1e-7);assert(std::abs(dsp->tmpBuffer[1][i]-(at+i==expectedDelay?-.0625f:0.f))<1e-7);}at+=n;
        }
        JamesDSPFree(dsp.get());std::printf("Professional crossfeed Fs%u mode%d zero-wet dry delay%u exact samples passed\n",fs,mode,expectedDelay);
    }
}
}
int main(){std::setvbuf(stdout,nullptr,_IONBF,0);stereoWorkerLifecycle();builtInImpulseGain();hrtfDryAlignment();std::puts("James HiFi convolution and built-in IR regressions passed.");}
