#ifndef WIDTH23_REFERENCE_H
#define WIDTH23_REFERENCE_H
#include <stdint.h>
#include <stddef.h>
#ifdef __cplusplus
extern "C" {
#endif
#define WIDTH23_WORDS 26
#define WIDTH23_RUNTIME_WORDS 32
#define FUSION_WIDTH23_CLEAR 0x5744434cu
typedef struct { double b0,b1,b2,a1,a2,z1,z2; } Width23Biquad;
typedef struct { Width23Biquad lo[2],hi[2],ap; int stages,sign; } Width23Split;
typedef struct {
    Width23Split first,second;
    Width23Biquad lowAlign,midAlign[2],mono[2][4];
    int monoStages;
} Width23Bank;
typedef struct { double l,r,lr,m,s,peak; } Width23Meter;
typedef struct {
    int32_t settings[WIDTH23_WORDS];
    double fs,alpha,meterAlpha,guardAlpha,autoAlpha,attack,release,peakDecay;
    double modes[3],master,bands[3],monoAmount,midGain,mix,outputGain;
    double masterTarget,bandTarget[3],monoTarget,midTarget,mixTarget,outputTarget;
    Width23Bank bank,previous,pending;
    double bankFade; int pendingBank;
    double guardAmount,guardStrength,autoGain,autoTarget,autoLimit;
    double candidateL,candidateR,candidateLR,matchInput,matchOutput;
    int guardTriggered,audition,auditionRequest,enabled;
    double auditionMix[4];
    Width23Meter input,output;
    uint64_t samples,tick;
} Width23Control;
int Width23Validate(const int32_t*,size_t);
void Width23Init(Width23Control*,double);
void Width23Configure(Width23Control*,double);
int Width23Set(Width23Control*,const int32_t*,size_t);
void Width23Clear(Width23Control*);
int Width23Audition(Width23Control*,int);
void Width23Frame(Width23Control*,double,double,float*,float*);
void Width23Snapshot(const Width23Control*,int32_t*);
#ifdef __cplusplus
}
#endif
#endif
