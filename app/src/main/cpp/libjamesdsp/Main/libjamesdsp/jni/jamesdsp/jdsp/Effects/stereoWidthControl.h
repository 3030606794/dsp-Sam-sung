#ifndef JDSP_STEREO_WIDTH_CONTROL_H
#define JDSP_STEREO_WIDTH_CONTROL_H
#include <stdint.h>
#include <stddef.h>
#ifdef __cplusplus
extern "C" {
#endif
#define WIDTH_WORDS 26
#define WIDTH_RUNTIME_WORDS 32
#include "widthAdvancedContract.h"
#define FUSION_WIDTH_CLEAR 0x5744434cu
typedef struct { double b0,b1,b2,a1,a2,z1,z2; } WidthBiquad;
typedef struct { WidthBiquad lo[2],hi[2],ap; int stages,sign; } WidthSplit;
typedef struct {
    WidthSplit first,second;
    WidthBiquad lowAlign,midAlign[2],mono[2][4];
    WidthSplit midFirst,midSecond;
    WidthBiquad midLowAlign,partMono[4][2][4];
    int monoStages;
} WidthBank;
typedef struct { double l,r,lr,m,s,peak; } WidthMeter;
typedef struct {
    int32_t settings[WIDTH2_WORDS];
    WidthBank monitor[2];
    WidthMeter bands[2][3],candidate[4];
    WidthBiquad eq[4][2],eqOld[4][2],eqPending[4][2];
    double eqFade; int eqWaiting;
    double dynamic[4],transient[4],guard[4],combined[4],envelopeM[4],envelopeS[4];
    double fast[4],slow[4],transientDb[4],rho[4],hold[4];
    double envelopeBase[4],envelopeExtra[4],envelopeCross[4];
    double dynAttack[4],dynRelease[4],transAttack,transRelease,guardAlpha,floorPower;
    double autoAttack,autoRelease,autoFloor,autoDeadband,autoHeldDb;
    double processingMix,processingTarget;
    double envelopeAlpha,fastAlpha,processedExtra[4];
    uint64_t meterSamples;
    int triggered[4],vectorIndex;
    double vectors[128];
} WidthAdvanced;
typedef struct {
    int32_t settings[WIDTH_WORDS];
    double fs,alpha,meterAlpha,guardAlpha,autoAlpha,attack,release,peakDecay;
    double modes[3],master,bands[3],monoAmount,midGain,mix,outputGain;
    double masterTarget,bandTarget[3],monoTarget,midTarget,mixTarget,outputTarget;
    WidthBank bank,previous,pending;
    double bankFade; int pendingBank;
    double guardAmount,guardStrength,autoGain,autoTarget,autoLimit;
    double candidateL,candidateR,candidateLR,matchInput,matchOutput;
    int guardTriggered,audition,auditionRequest,enabled;
    double auditionMix[10];
    WidthAdvanced advanced;
    WidthMeter input,output;
    uint64_t samples,tick;
} StereoWidthControl;
int StereoWidthValidate(const int32_t*,size_t);
void StereoWidthInit(StereoWidthControl*,double);
void StereoWidthConfigure(StereoWidthControl*,double);
int StereoWidthSet(StereoWidthControl*,const int32_t*,size_t);
void StereoWidthClear(StereoWidthControl*);
int StereoWidthAudition(StereoWidthControl*,int);
void StereoWidthFrame(StereoWidthControl*,double,double,float*,float*);
void StereoWidthSnapshot(const StereoWidthControl*,int32_t*);
int StereoWidthAdvancedValidate(const int32_t*,size_t);
int StereoWidthAdvancedSet(StereoWidthControl*,const int32_t*,size_t);
int StereoWidthAdvancedAudition(StereoWidthControl*,int);
void StereoWidthAdvancedSnapshot(const StereoWidthControl*,int32_t*);
#ifdef __cplusplus
}
#endif
#endif
