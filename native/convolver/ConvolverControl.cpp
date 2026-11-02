#include "ConvolverControl.h"
#include "ConvolverAdvanced.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <limits>
#include <new>

namespace {
constexpr int32_t unknown=std::numeric_limits<int32_t>::min();
constexpr size_t ringSize=153601, maxBlock=8192; // 200 ms at the 768 kHz host-rate maximum.
constexpr std::array<int32_t,10> defaults{1000,0,0,0,0,0,0,0,0,0};
int32_t db(double value) {return value>0 && std::isfinite(value)?int32_t(std::lround(20*std::log10(value)*10)):unknown;}
struct Meter {
    double ll=0,rr=0,mm=0,meanL=0,meanR=0,varianceL=0,varianceR=0,covariance=0,peak=0;unsigned count=0;bool invalid=false;
    std::array<int32_t,4> reading{unknown,unknown,unknown,unknown};
    void resetWindow(){ll=rr=mm=meanL=meanR=varianceL=varianceR=covariance=peak=0;count=0;invalid=false;}
    void clear(){resetWindow();reading.fill(unknown);}
    void add(float l,float r){
        if(!std::isfinite(l)||!std::isfinite(r)){invalid=true;return;}
        ll+=double(l)*l;rr+=double(r)*r;const double mid=(double(l)+r)*.5;mm+=mid*mid;
        // Welford's centered covariance preserves Pearson accuracy with a large DC offset.
        const double dl=l-meanL,dr=r-meanR;meanL+=dl/++count;meanR+=dr/count;
        varianceL+=dl*(l-meanL);varianceR+=dr*(r-meanR);covariance+=dl*(r-meanR);
        peak=std::max(peak,std::max(std::abs(double(l)),std::abs(double(r))));
    }
    void finish(unsigned n){
        if(invalid){reading.fill(unknown);resetWindow();return;}
        reading[0]=db(peak);reading[1]=db(std::sqrt((ll+rr)/(2*n)));
        reading[2]=varianceL>0 && varianceR>0?int32_t(std::lround(std::clamp(covariance/std::sqrt(varianceL*varianceR),-1.,1.)*10000)):unknown;
        const double stereo=ll+rr,mid=2*mm;
        reading[3]=stereo<=0?unknown:(mid<=0?unknown+1:int32_t(std::lround(10*std::log10(mid/stereo)*10)));
        resetWindow();
    }
};
struct Control {
    convolution::Advanced advanced;
    std::array<int32_t,10> params=defaults;
    uint32_t fs=48000,channels=0,frames=0,delay=0,cursor=0,count=0,lastWindow=0;
    int32_t crc=0,lastAudition=0,audition=0;bool enabled=false,ready=false;
    std::array<float,ringSize> wetL{},wetR{};
    std::array<float,maxBlock> dryL{},dryR{};
    std::array<float,4> weights{1,0,0,0},start{1,0,0,0};unsigned fade=0,fadeLength=720;
    // wet gain, output gain, LL/LR/RL/RR routing gains, linear wet mix.
    std::array<float,7> gains{1,1,1,0,0,1,1},gainStart=gains,gainTarget=gains;unsigned gainFade=0;
    struct Tap {unsigned sample=0;float weight=0;};
    std::array<Tap,ringSize> taps{};unsigned tapCount=1,delayFade=0;
    Meter input,wet,output;
    Control(){taps[0]={0,1};}
    void clearMeters(){count=lastWindow=0;ready=false;input.clear();wet.clear();output.clear();}
    void clear(){advanced.reset();wetL.fill(0);wetR.fill(0);cursor=0;clearMeters();audition=0;weights=start={1,0,0,0};fade=0;gains=gainStart=gainTarget;gainFade=delayFade=0;tapCount=1;taps[0]={delay,1};}
    void configure(uint32_t rate){advanced.configure(rate);if(rate!=fs){fs=rate;delay=uint32_t(std::llround(double(params[3])*fs/1e6));fadeLength=std::max(1u,uint32_t(std::llround(fs*.015)));clear();}}
    void setDelay(unsigned target){
        if(target==delay)return;
        if(delayFade){
            const float t=1-float(delayFade)/fadeLength;
            for(unsigned i=0;i<tapCount;++i)taps[i].weight*=1-t;
            if(t>0){unsigned where=0;for(;where<tapCount && taps[where].sample!=delay;++where){}
                if(where==tapCount){taps[tapCount++]={delay,0};}taps[where].weight+=t;}
        }else {tapCount=1;taps[0]={delay,1};}
        delay=target;delayFade=fadeLength;
    }
    void begin(const float* l,const float* r,size_t n){if(n>maxBlock)return;std::copy_n(l,n,dryL.data());std::copy_n(r,n,dryR.data());}
    void end(float* l,float* r,size_t n){
        if(n>maxBlock)return;
        for(size_t i=0;i<n;++i){
            const float sourceL=l[i],sourceR=r[i];wetL[cursor]=sourceL;wetR[cursor]=sourceR;
            const size_t tap=(cursor+ringSize-delay)%ringSize;float wl=wetL[tap],wr=wetR[tap];
            if(delayFade){
                float fromL=0,fromR=0;for(unsigned j=0;j<tapCount;++j){const size_t a=(cursor+ringSize-taps[j].sample)%ringSize;fromL+=wetL[a]*taps[j].weight;fromR+=wetR[a]*taps[j].weight;}
                const float t=1-float(--delayFade)/fadeLength;wl=fromL*(1-t)+wl*t;wr=fromR*(1-t)+wr*t;
                if(!delayFade){tapCount=1;taps[0]={delay,1};}
            }
            cursor=(cursor+1)%ringSize;
            advanced.filter(wl,wr);
            float alignedL=0,alignedR=0;advanced.align(dryL[i],dryR[i],alignedL,alignedR);
            if(gainFade){const float t=1-float(--gainFade)/fadeLength;for(unsigned j=0;j<gains.size();++j)gains[j]=gainStart[j]+(gainTarget[j]-gainStart[j])*t;if(!gainFade)gains=gainTarget;}
            const float routedL=wl*gains[2]+wr*gains[3],routedR=wl*gains[4]+wr*gains[5];wl=routedL;wr=routedR;
            if(gains[0]!=1){wl*=gains[0];wr*=gains[0];}
            float normalL=wl,normalR=wr;
            if(gains[6]==0){normalL=alignedL;normalR=alignedR;}
            else if(gains[6]!=1){const float mix=gains[6];normalL=alignedL*(1-mix)+wl*mix;normalR=alignedR*(1-mix)+wr*mix;}
            if(gains[1]!=1){normalL*=gains[1];normalR*=gains[1];}
            const float compare=advanced.compensation();if(compare!=1){normalL*=compare;normalR*=compare;}
            input.add(dryL[i],dryR[i]);wet.add(wl,wr);output.add(normalL,normalR);
            if(++count>=std::max(1u,fs/10)){input.finish(count);wet.finish(count);output.finish(count);lastWindow=count;count=0;ready=true;}
            if(fade){const float t=1-float(--fade)/fadeLength;for(unsigned j=0;j<4;++j)weights[j]=start[j]*(1-t)+(j==unsigned(audition)?t:0);}
            const bool neutral=params==defaults && !gainFade && !delayFade && !advanced.bank.count && !advanced.filterRemaining && advanced.actualCommon==0 && compare==1;
            if(audition==0 && !fade){if(!neutral){l[i]=normalL;r[i]=normalR;}} // Exact legacy samples after additions settle to neutral.
            else if(audition==1 && !fade){l[i]=alignedL;r[i]=alignedR;}
            else if(audition==2 && !fade){l[i]=wl;r[i]=wr;}
            else if(audition==3 && !fade){l[i]=normalL-alignedL;r[i]=normalR-alignedR;}
            else {l[i]=normalL*weights[0]+alignedL*weights[1]+wl*weights[2]+(normalL-alignedL)*weights[3];r[i]=normalR*weights[0]+alignedR*weights[1]+wr*weights[2]+(normalR-alignedR)*weights[3];}
        }
    }
    void snapshot(int32_t* out){
        std::fill_n(out,32,0);out[0]=1;out[2]=fs;out[3]=(frames?1:0)|(enabled?2:0)|(enabled&&frames?4:0)|(ready?8:0);
        out[4]=channels;out[5]=frames;out[6]=crc;out[7]=delay;out[8]=int32_t(std::llround(double(delay)*1e6/fs));out[9]=advanced.actualCommon;out[10]=audition;
        out[11]=params[0];out[12]=params[1];out[13]=params[2];out[14]=params[4];out[15]=params[5];out[16]=params[6];out[17]=params[7];out[18]=params[8];
        std::copy(input.reading.begin(),input.reading.end(),out+19);std::copy(wet.reading.begin(),wet.reading.end(),out+23);std::copy(output.reading.begin(),output.reading.end(),out+27);out[31]=lastWindow;
    }
};
}
extern "C" {
void* FusionConvCreate(){return new(std::nothrow)Control;}
void FusionConvDestroy(void* p){delete static_cast<Control*>(p);}
void FusionConvConfigure(void* p,uint32_t fs){if(p && fs>=8000 && fs<=768000)static_cast<Control*>(p)->configure(fs);}
int FusionConvSet(void* p,const int32_t* values,size_t n){
    if(!p||!values||n!=10)return 0;
    constexpr int32_t low[]{0,-240,-240,0,-120,-120,0,0,0,0},high[]{1000,120,0,200000,120,120,1,1,1,0};
    for(unsigned i=0;i<10;++i)if(values[i]<low[i]||values[i]>high[i])return 0;
    auto& c=*static_cast<Control*>(p);
    if(std::equal(c.params.begin(),c.params.end(),values))return 1;
    std::copy_n(values,10,c.params.begin());c.clearMeters();
    const float left=std::pow(10.f,values[4]/200.f)*(values[6]?-1.f:1.f),right=std::pow(10.f,values[5]/200.f)*(values[7]?-1.f:1.f);
    c.gainStart=c.gains;c.gainTarget={std::pow(10.f,values[1]/200.f),std::pow(10.f,values[2]/200.f),values[8]?0.f:left,values[8]?left:0.f,values[8]?right:0.f,values[8]?0.f:right,values[0]/1000.f};
    c.gainFade=c.fadeLength;c.setDelay(uint32_t(std::llround(double(values[3])*c.fs/1e6)));return 1;
}
int FusionConvAudition(void* p,int32_t mode){if(!p||mode<0||mode>3)return 0;auto& c=*static_cast<Control*>(p);if(mode==c.lastAudition)return 1;c.lastAudition=mode;if(mode && (!c.enabled||!c.frames))return 1;c.start=c.weights;c.audition=mode;c.fade=c.fadeLength;return 1;}
void FusionConvClear(void* p){if(p)static_cast<Control*>(p)->clear();}
void FusionConvEnabled(void* p,int enabled){if(!p)return;auto& c=*static_cast<Control*>(p);if(!enabled)c.clear();c.enabled=enabled!=0;}
void FusionConvLoaded(void* p,uint32_t channels,uint32_t frames,int32_t crc){if(!p)return;auto& c=*static_cast<Control*>(p);const bool preserve=c.frames && c.advanced.p[13];c.advanced.loadedCommon();c.channels=channels;c.frames=frames;c.crc=crc;if(!preserve)c.clear();else{c.clearMeters();c.audition=0;c.weights=c.start={1,0,0,0};c.fade=0;}}
void FusionConvBegin(void* p,const float* l,const float* r,size_t n){if(p)static_cast<Control*>(p)->begin(l,r,n);}
void FusionConvEnd(void* p,float* l,float* r,size_t n){if(p)static_cast<Control*>(p)->end(l,r,n);}
void FusionConvSnapshot(void* p,int32_t* out){if(p&&out)static_cast<Control*>(p)->snapshot(out);}
int FusionConvSetAdvanced(void* p,const int32_t* v,size_t n){if(!p||!convolution::validAdvanced(v,n))return 0;auto& c=*static_cast<Control*>(p);if(std::equal(c.advanced.p.begin(),c.advanced.p.end(),v))return 1;if(!c.advanced.set(v,n))return 0;c.clearMeters();return 1;}
void FusionConvAdvancedSnapshot(void* p,int32_t* out){if(p&&out){auto& c=*static_cast<Control*>(p);c.advanced.snapshot(out);out[18]=c.crc;}}
void FusionConvPrepareInput(void* p,float* l,float* r,size_t n){if(p&&n<=maxBlock)static_cast<Control*>(p)->advanced.inputs(l,r,n);}
void FusionConvMixTransition(void* p,float* l,float* r,size_t n){if(p&&n<=maxBlock)static_cast<Control*>(p)->advanced.mix(l,r,n);}
int FusionConvAttachRetired(void* p,void* job,uint32_t frames,uint32_t channels){return p && static_cast<Control*>(p)->advanced.attach(job,frames,channels);}
void* FusionConvReap(void* p,int force){return p?static_cast<Control*>(p)->advanced.reap(force!=0):nullptr;}
void* FusionConvDrain(void* p){return p?static_cast<Control*>(p)->advanced.drain():nullptr;}
int FusionConvBusy(void* p){return p && static_cast<Control*>(p)->advanced.active();}
}
