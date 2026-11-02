#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <limits>
#include <memory>
#include <new>
#include <vector>
extern "C" {
#include "../../app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/jdsp_header.h"
#include "../../app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/Effects/presetCrossfeed.h"
}
#undef min
#undef max
namespace {
constexpr unsigned block=512;
using Settings=std::array<int32_t,PRESET_XF_WORDS>;
using Advanced=std::array<int32_t,PRESET_XF_ADVANCED_WORDS>;
constexpr Advanced advancedDefaults{1,0,1,0,0,0,3000,1,0,0};
constexpr Settings defaults{1,0,0,700,60,100,0,0,0};
float clean(float x){return std::isfinite(x)?x:0.f;}
int db(double v){return v>0?int(std::llround(2000*std::log10(v))):std::numeric_limits<int32_t>::min();}
struct Convolver {
    FFTConvolver2x4x2 shortConv{};
    TwoStageFFTConvolver2x4x2 longConv{};
    bool useLong=false,loaded=false;
    Convolver(){FFTConvolver2x4x2Init(&shortConv);TwoStageFFTConvolver2x4x2Init(&longConv);}
    ~Convolver(){FFTConvolver2x4x2Free(&shortConv);TwoStageFFTConvolver2x4x2Free(&longConv);}
    bool load(JamesDSPLib* dsp,const std::array<std::vector<float>,4>& ir){
        FFTConvolver2x4x2Free(&shortConv);TwoStageFFTConvolver2x4x2Free(&longConv);
        unsigned tail=0;useLong=selectConvPartitions(dsp,unsigned(ir[0].size()),&tail)!=1;
        loaded=useLong?TwoStageFFTConvolver2x4x2LoadImpulseResponse(&longConv,unsigned(dsp->blockSize),tail,
            ir[0].data(),ir[1].data(),ir[2].data(),ir[3].data(),unsigned(ir[0].size())):
            FFTConvolver2x4x2LoadImpulseResponse(&shortConv,unsigned(dsp->blockSize),ir[0].data(),ir[1].data(),ir[2].data(),ir[3].data(),unsigned(ir[0].size()));
        return loaded;
    }
    void process(const float* l,const float* r,float* outL,float* outR,unsigned n){
        if(useLong)TwoStageFFTConvolver2x4x2Process(&longConv,l,r,outL,outR,n);
        else FFTConvolver2x4x2Process(&shortConv,l,r,outL,outR,n);
    }
};
struct Kernel {
    Settings settings=defaults;
    Advanced advanced=advancedDefaults;
    double pathGain[2]{1,1},targetPath[2]{1,1},centerGain=1,targetCenter=1;
    double filterBlend=0,targetFilter=0,secondBlend=0,targetSecond=0,pole=.5,targetPole=.5;
    std::array<std::array<double,2>,2> filterState{},filterInput{};
    void tuneAdvanced(JamesDSPLib* dsp,const Advanced& v,bool reset=false){
        advanced=v;
        // The destination channel determines the cross path: right -> left is index 0.
        targetPath[0]=v[1]?std::pow(10.,(v[2]?v[3]:v[4])/200.):1.;
        targetPath[1]=v[1]?std::pow(10.,v[3]/200.):1.;
        targetCenter=v[8]?std::pow(10.,v[9]/200.):1.;targetFilter=v[5]?1.:0.;targetSecond=v[7]==2?1.:0.;
        const double k=std::tan(3.14159265358979323846*std::min(double(v[6]),dsp->fs*.45)/dsp->fs);
        targetPole=(1-k)/(1+k);
        if(reset){pathGain[0]=targetPath[0];pathGain[1]=targetPath[1];centerGain=targetCenter;
            filterBlend=targetFilter;secondBlend=targetSecond;pole=targetPole;filterState={};filterInput={};}
    }
    void processAdvanced(std::array<std::array<float,block>,4>& out,unsigned n){
        // A literal bypass preserves all old samples when every added group is disabled.
        if(!advanced[1] && !advanced[5] && !advanced[8] && filterBlend<1e-12 &&
            std::abs(pathGain[0]-1)<1e-12 && std::abs(pathGain[1]-1)<1e-12 && std::abs(centerGain-1)<1e-12)return;
        for(unsigned i=0;i<n;++i){
            pole+=(targetPole-pole)*alpha;filterBlend+=(targetFilter-filterBlend)*alpha;
            secondBlend+=(targetSecond-secondBlend)*alpha;centerGain+=(targetCenter-centerGain)*alpha;
            for(unsigned c=0;c<2;++c){
                pathGain[c]+=(targetPath[c]-pathGain[c])*alpha;
                const double original=out[c+2][i];auto& state=filterState[c];auto& previous=filterInput[c];
                const double b=(1-pole)*.5;
                state[0]=b*(original+previous[0])+pole*state[0];previous[0]=original;
                state[1]=b*(state[0]+previous[1])+pole*state[1];previous[1]=state[0];
                const double filtered=state[0]+secondBlend*(state[1]-state[0]);
                const double adjusted=(original+filterBlend*(filtered-original))*pathGain[c];
                out[c][i]=clean(float(double(out[c][i])-original+adjusted));out[c+2][i]=clean(float(adjusted));
            }
            const double correction=(double(out[0][i])+out[1][i])*.5*(centerGain-1);
            out[0][i]=clean(float(out[0][i]+correction));out[1][i]=clean(float(out[1][i]+correction));
        }
    }
    t_bs2bdp bs{},targetBs{};
    static constexpr unsigned crossHistorySize=512,dryHistorySize=2048;
    std::array<std::array<double,crossHistorySize>,2> history{};
    std::array<std::array<float,dryHistorySize>,2> dryHistory{};
    unsigned dryPosition=0;
    double dryDelay=0;
    unsigned position=0,irFrames=0;
    double mix=1,trim=1,delay=0,spatial=1,targetMix=1,targetTrim=1,targetDelay=0,targetSpatial=1,alpha=.002;
    std::unique_ptr<Convolver> whole,cross;
    bool ready=false;
    void retune(JamesDSPLib* dsp,const Settings& s){
        settings=s;targetMix=s[5]/100.;targetTrim=std::pow(10.,s[7]/200.);targetDelay=s[6]*dsp->fs/1e6;
        targetSpatial=std::pow(10.,s[8]/200.);targetBs={};
        if(s[2]<2)BS2BInit(&targetBs,unsigned(dsp->fs),BS2BCalculateflevel(s[3],s[4]));
    }
    void advance(){
        mix+=(targetMix-mix)*alpha;trim+=(targetTrim-trim)*alpha;delay+=(targetDelay-delay)*alpha;spatial+=(targetSpatial-spatial)*alpha;
        if(settings[2]<2){bs.a0_lo+=(targetBs.a0_lo-bs.a0_lo)*alpha;bs.b1_lo+=(targetBs.b1_lo-bs.b1_lo)*alpha;
            bs.a0_hi+=(targetBs.a0_hi-bs.a0_hi)*alpha;bs.a1_hi+=(targetBs.a1_hi-bs.a1_hi)*alpha;
            bs.b1_hi+=(targetBs.b1_hi-bs.b1_hi)*alpha;bs.gain+=(targetBs.gain-bs.gain)*alpha;}
    }
    bool configure(JamesDSPLib* dsp,const Settings& s){
        std::unique_ptr<Convolver> nextWhole,nextCross;unsigned length=0;
        if(s[2]>=2){
            std::array<const float*,4> source{};
            if(s[2]<5){const unsigned index=s[2]-2;source={dsp->blobsCh1[index],dsp->blobsCh2[index],dsp->blobsCh3[index],dsp->blobsCh4[index]};length=dsp->blobsResampledLen;}
            else{for(unsigned c=0;c<4;++c)source[c]=dsp->hrtfblobsResampled[c];length=dsp->frameLenSVirResampled;}
            if(!length || length>unsigned(dsp->fs*10) || std::any_of(source.begin(),source.end(),[](const auto* p){return !p;}))return false;
            std::array<std::vector<float>,4> ir;
            for(unsigned c=0;c<4;++c)ir[c].assign(source[c],source[c]+length);
            nextWhole=std::make_unique<Convolver>();nextCross=std::make_unique<Convolver>();
            if(!nextWhole->load(dsp,ir))return false;
            std::fill(ir[0].begin(),ir[0].end(),0.f);std::fill(ir[3].begin(),ir[3].end(),0.f);
            if(!nextCross->load(dsp,ir))return false;
        }
        // Commit only after all allocations and IR preparations succeed.
        whole=std::move(nextWhole);cross=std::move(nextCross);
        settings=s;bs={};history={};position=0;irFrames=length;
        dryHistory={};dryPosition=0;dryDelay=0;
        if(s[2]>=2 && dsp->fs>48000.f){
            const double referenceFs=std::fmod(double(dsp->fs),44100.)==0.?44100.:48000.;
            dryDelay=64.*dsp->fs/referenceFs;
        }
        alpha=1-std::exp(-1/(.01*dsp->fs));retune(dsp,s);mix=targetMix;trim=targetTrim;delay=targetDelay;spatial=targetSpatial;bs=targetBs;
        tuneAdvanced(dsp,advanced,true);ready=true;return true;
    }
    void process(const float* l,const float* r,std::array<std::array<float,block>,4>& out,unsigned n){
        if(settings[2]<2){
            for(unsigned i=0;i<n;++i){
                advance();const unsigned integer=unsigned(delay);const double fraction=delay-integer;
                const double input[2]={l[i],r[i]};
                for(unsigned c=0;c<2;++c){
                    bs.lfs.lo[c]=bs.a0_lo*input[c]+bs.b1_lo*bs.lfs.lo[c];
                    bs.lfs.hi[c]=bs.a0_hi*input[c]+bs.a1_hi*bs.lfs.asis[c]+bs.b1_hi*bs.lfs.hi[c];
                    bs.lfs.asis[c]=input[c];history[c][position]=bs.lfs.lo[c];
                }
                for(unsigned c=0;c<2;++c){const auto& ring=history[1-c];
                    const double crossValue=ring[(position+crossHistorySize-integer)%crossHistorySize]*(1-fraction)+ring[(position+crossHistorySize-1-integer)%crossHistorySize]*fraction;
                    out[c][i]=float((bs.lfs.hi[c]+crossValue)*bs.gain);out[c+2][i]=float(crossValue*bs.gain);}
                for(unsigned c=0;c<2;++c){out[c][i]=clean(float(((1-mix)*input[c]+mix*out[c][i])*trim));out[c+2][i]=clean(float(out[c+2][i]*mix*trim));}
                position=(position+1)%crossHistorySize;
            }
        }else{
            whole->process(l,r,out[0].data(),out[1].data(),n);
            cross->process(l,r,out[2].data(),out[3].data(),n);
            for(unsigned i=0;i<n;++i){
                advance();const unsigned integer=unsigned(delay);const double fraction=delay-integer;
                for(unsigned c=0;c<2;++c){
                    auto& ring=history[c];const float originalCross=out[c+2][i];ring[position]=originalCross;
                    const double adjusted=(ring[(position+crossHistorySize-integer)%crossHistorySize]*(1-fraction)+ring[(position+crossHistorySize-1-integer)%crossHistorySize]*fraction)*spatial;
                    const double wet=(delay==0 && spatial==1)?out[c][i]:out[c][i]-double(originalCross)+adjusted;
                    const float input=c?r[i]:l[i];
                    // High-rate HRTF SRC has a coherent 64-reference-frame
                    // pad delay. Align the added dry mix to that same delay;
                    // no new comb response is introduced at mix < 100%.
                    dryHistory[c][dryPosition]=input;
                    const unsigned dryInteger=unsigned(dryDelay);
                    const double dryFraction=dryDelay-dryInteger;
                    const double dry=dryHistory[c][(dryPosition+dryHistorySize-dryInteger)%dryHistorySize]*(1-dryFraction)+
                        dryHistory[c][(dryPosition+dryHistorySize-1-dryInteger)%dryHistorySize]*dryFraction;
                    out[c][i]=clean(float(((1-mix)*dry+mix*wet)*trim));out[c+2][i]=clean(float(adjusted*mix*trim));
                }
                position=(position+1)%crossHistorySize;
                dryPosition=(dryPosition+1)%dryHistorySize;
            }
        }
        processAdvanced(out,n);
    }
};
struct Engine {
    Settings settings=defaults,a=defaults,b=defaults;
    Advanced advanced=advancedDefaults,advancedA=advancedDefaults,advancedB=advancedDefaults;
    int advancedEpoch=0;bool advancedSnapshotsChanged=false;
    Kernel main[2],comparisonA,comparisonB;
    std::array<std::array<std::array<float,block>,4>,4> work{};
    unsigned rate=48000,fadeLength=480,fade=0,active=0,measurementFrames=0,warmFrames=4800;
    int mode=0,epoch=0,measurement=0;
    bool match=false,module=false,configured=false;
    double blend=0,energyIn=0,energyA=0,energyB=0;
    float gainA=1,gainB=1,peakDecay=.99995f;
    std::array<float,2> last{},peaks{};
    unsigned toolFade=0;
    void clear(){if(mode)toolFade=fadeLength;mode=epoch=measurement=0;measurementFrames=0;match=false;gainA=gainB=1;energyIn=energyA=energyB=0;}
    void refresh(JamesDSPLib* dsp){
        rate=unsigned(std::clamp(dsp->fs,8000.f,768000.f));fadeLength=std::max(1u,rate/100);peakDecay=float(std::exp(-1./(.5*rate)));clear();peaks={};
        configured=false;blend=0;fade=0;active=0;
        if(settings[1]){main[0].advanced=advanced;configured=main[0].configure(dsp,settings);}
    }
    bool set(JamesDSPLib* dsp,const Settings& next){
        if(next==settings && (!next[1] || configured)){if(dsp->advXF.mode!=next[2])CrossfeedChangeMode(dsp,next[2]);return true;}
        if(next[1]){
            if(configured && settings[1] && main[fade?1-active:active].settings[2]==next[2])main[fade?1-active:active].retune(dsp,next);
            else{const unsigned target=configured?1-active:active;
                main[target].advanced=advanced;
                if(!main[target].configure(dsp,next))return false;
                if(configured)fade=fadeLength;}
            configured=true;
        }
        settings=next;clear();CrossfeedChangeMode(dsp,next[2]);return true;
    }
    bool tools(JamesDSPLib* dsp,const int32_t* v){
        if(v[0] && (!settings[1] || !module || !configured))return false;
        Settings nextA,nextB;std::copy_n(v+3,9,nextA.begin());std::copy_n(v+12,9,nextB.begin());
        const bool changed=epoch!=v[2] || nextA!=a || nextB!=b || advancedSnapshotsChanged;
        if(v[0]>=4 && advancedEpoch && advancedEpoch!=v[2])return false;
        if(v[0]>=4 && v[0]<=5 && v[1] && (changed || measurement!=2))return false;
        if(v[0]>=4 && (changed || v[0]==6 && mode!=6)){
            Kernel preparedA,preparedB;preparedA.advanced=advancedA;preparedB.advanced=advancedB;
            if(!preparedA.configure(dsp,nextA) || !preparedB.configure(dsp,nextB))return false;
            comparisonA=std::move(preparedA);comparisonB=std::move(preparedB);
        }
        if(changed){clear();epoch=v[2];a=nextA;b=nextB;advancedSnapshotsChanged=false;}
        if(v[0]==6 && mode!=6){measurement=1;measurementFrames=0;energyIn=energyA=energyB=0;gainA=gainB=1;
            warmFrames=std::max({rate/10,comparisonA.irFrames,comparisonB.irFrames});}
        if(v[0]!=mode)toolFade=fadeLength;
        if(mode==6 && v[0]!=6 && measurement==1)measurement=0;
        mode=v[0];match=v[1];return true;
    }
    void process(const float* l,const float* r,float* outputL,float* outputR,unsigned n){
        const bool crossfade=fade!=0;
        main[active].process(l,r,work[0],n);
        if(fade)main[1-active].process(l,r,work[1],n);
        if(mode>=4){comparisonA.process(l,r,work[2],n);comparisonB.process(l,r,work[3],n);}
        for(unsigned i=0;i<n;++i){
            const float input[2]={l[i],r[i]};float normal[2],cross[2];
            for(unsigned c=0;c<2;++c){normal[c]=work[0][c][i];cross[c]=work[0][c+2][i];
                if(crossfade){const float t=fade?1-float(fade)/fadeLength:1;normal[c]+=(work[1][c][i]-normal[c])*t;cross[c]+=(work[1][c+2][i]-cross[c])*t;}}
            if(fade && --fade==0)active=1-active;
            float result[2]={normal[0],normal[1]};
            if(mode==1)std::copy_n(input,2,result);
            if(mode==2)for(unsigned c=0;c<2;++c)result[c]=(normal[c]-input[c])*.5f;
            if(mode==3)std::copy_n(cross,2,result);
            if(mode==4 || mode==5)for(unsigned c=0;c<2;++c)result[c]=work[mode-2][c][i]*(match?(mode==4?gainA:gainB):1.f);
            if(mode==6 && measurement==1){
                ++measurementFrames;
                if(measurementFrames>warmFrames)for(unsigned c=0;c<2;++c){energyIn+=double(input[c])*input[c];energyA+=double(work[2][c][i])*work[2][c][i];energyB+=double(work[3][c][i])*work[3][c][i];}
                if(measurementFrames>=warmFrames+rate*2){
                    const double floor=rate*4.*1e-8;
                    if(std::min({energyIn,energyA,energyB})>floor){const double target=std::min({energyIn,energyA,energyB});gainA=float(std::sqrt(target/energyA));gainB=float(std::sqrt(target/energyB));measurement=2;}
                    else measurement=3;
                }
            }
            blend=settings[1]?std::min(1.,blend+1./fadeLength):std::max(0.,blend-1./fadeLength);
            for(unsigned c=0;c<2;++c){
                last[c]=toolFade?last[c]+(clean(result[c])-last[c])/toolFade:clean(result[c]);
                float& output=c?outputR[i]:outputL[i];output=clean(float(output+(last[c]-output)*blend));
                peaks[c]=std::max(std::abs(output),peaks[c]*peakDecay);
            }
            if(toolFade)--toolFade;
        }
    }
};
}
extern "C" {
int PresetCrossfeedAdvancedValidate(const int32_t* v,size_t n){
    if(!v || n!=10)return 0;
    constexpr int lo[]={1,0,0,-120,-120,0,300,1,0,-60},hi[]={1,1,1,60,60,1,20000,2,1,60};
    for(unsigned i=0;i<10;++i)if(v[i]<lo[i] || v[i]>hi[i])return 0;return 1;
}
int PresetCrossfeedAdvancedToolsValidate(const int32_t* v,size_t n){
    return v && n==21 && v[0]>=0 && PresetCrossfeedAdvancedValidate(v+1,10) && PresetCrossfeedAdvancedValidate(v+11,10);
}
int PresetCrossfeedAdvancedSet(void* p,void* dsp,const int32_t* v,size_t n){
    if(!p || !dsp || !PresetCrossfeedAdvancedValidate(v,n))return 0;
    auto& e=*static_cast<Engine*>(p);Advanced next;std::copy_n(v,10,next.begin());
    if(next!=e.advanced){e.advanced=next;e.clear();for(auto& kernel:e.main)kernel.tuneAdvanced(static_cast<JamesDSPLib*>(dsp),next);}
    return 1;
}
int PresetCrossfeedAdvancedTools(void* p,void* dsp,const int32_t* v,size_t n){
    if(!p || !dsp || !PresetCrossfeedAdvancedToolsValidate(v,n))return 0;
    auto& e=*static_cast<Engine*>(p);Advanced a,b;std::copy_n(v+1,10,a.begin());std::copy_n(v+11,10,b.begin());
    if(e.advancedEpoch!=v[0] || a!=e.advancedA || b!=e.advancedB){
        e.clear();e.advancedEpoch=v[0];e.advancedA=a;e.advancedB=b;e.advancedSnapshotsChanged=true;
    }
    return 1;
}
int PresetCrossfeedValidate(const int32_t* v,size_t n){
    if(!v || n!=9)return 0;
    constexpr int lo[]={1,0,0,300,10,0,0,-120,-120},hi[]={1,1,5,2000,150,100,500,0,60};
    for(unsigned i=0;i<9;++i)if(v[i]<lo[i] || v[i]>hi[i])return 0;return 1;
}
int PresetCrossfeedToolsValidate(const int32_t* v,size_t n){
    return v && n==21 && v[0]>=0 && v[0]<=6 && v[1]>=0 && v[1]<=1 && v[2]>=0 &&
        PresetCrossfeedValidate(v+3,9) && PresetCrossfeedValidate(v+12,9) && (v[0]<4 || (v[2]>0 && v[4]==1 && v[13]==1));
}
void* PresetCrossfeedCreate(){return new(std::nothrow) Engine;}
void PresetCrossfeedDestroy(void* p){delete static_cast<Engine*>(p);}
void PresetCrossfeedRefresh(void* p,void* dsp){if(p && dsp)try{static_cast<Engine*>(p)->refresh(static_cast<JamesDSPLib*>(dsp));}catch(...){static_cast<Engine*>(p)->configured=false;}}
int PresetCrossfeedSet(void* p,void* dsp,const int32_t* v,size_t n){
    if(!p || !dsp || !PresetCrossfeedValidate(v,n))return 0;Settings next;std::copy_n(v,9,next.begin());
    try{return static_cast<Engine*>(p)->set(static_cast<JamesDSPLib*>(dsp),next);}catch(...){return 0;}
}
int PresetCrossfeedTools(void* p,void* dsp,const int32_t* v,size_t n){
    if(!p || !dsp || !PresetCrossfeedToolsValidate(v,n))return 0;
    try{return static_cast<Engine*>(p)->tools(static_cast<JamesDSPLib*>(dsp),v);}catch(...){return 0;}
}
void PresetCrossfeedActivate(void* p,void* dsp,int enabled){
    if(!p)return;auto& e=*static_cast<Engine*>(p);if(!enabled){e.clear();e.peaks={};e.blend=0;}
    else if(!e.module && e.settings[1])PresetCrossfeedRefresh(p,dsp);e.module=enabled!=0;
}
void PresetCrossfeedClear(void* p){if(p)static_cast<Engine*>(p)->clear();}
int PresetCrossfeedActive(const void* p){if(!p)return 0;const auto& e=*static_cast<const Engine*>(p);return e.configured && (e.settings[1] || e.blend>0);}
void PresetCrossfeedProcess(void* p,const float* l,const float* r,float* outL,float* outR,size_t n){
    if(!PresetCrossfeedActive(p) || n>block)return;static_cast<Engine*>(p)->process(l,r,outL,outR,unsigned(n));
}
void PresetCrossfeedSnapshot(const void* p,int32_t* v){
    std::fill_n(v,24,0);v[0]=1;v[20]=v[21]=std::numeric_limits<int32_t>::min();if(!p)return;
    const auto& e=*static_cast<const Engine*>(p);v[2]=e.rate;v[3]=e.module;v[4]=e.settings[1];v[5]=e.mode;v[6]=e.measurement;
    v[7]=int(e.measurementFrames*1000ull/e.rate);v[8]=db(e.gainA);v[9]=db(e.gainB);v[10]=e.epoch;v[11]=e.fade!=0;
    for(unsigned i=2;i<9;++i)v[10+i]=e.settings[i];v[19]=e.match;
    if(e.module && e.configured && e.settings[1]){v[20]=db(e.peaks[0]);v[21]=db(e.peaks[1]);}
    v[22]=int(e.main[e.active].irFrames);v[23]=e.configured;
}
}
