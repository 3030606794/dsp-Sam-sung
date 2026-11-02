#pragma once
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
#define TIMBRE_WORDS 32
#define TIMBRE_TOOLS_WORDS 68
#define TIMBRE_RUNTIME_WORDS 48
// Settings[4]: total coloration in0.1%,0..10000 (legacy0..2000 unchanged).
// Tools[0]=11 sums the four pre-mix coloration lanes. Tools[3] is temporary
// audition gain in0.1dB,0..180, applied only to Solo modes7..11. Runtime[46]
// reports that active gain; runtime[47] remains reserved zero.
int TimbreValidate(const int32_t*,size_t);
int TimbreToolsValidate(const int32_t*,size_t);
void* TimbreCreate(void);
void TimbreDestroy(void*);
int TimbreSet(void*,void*,const int32_t*,size_t);
int TimbreTools(void*,void*,const int32_t*,size_t);
void TimbreClear(void*);
void TimbreStatus(const void*,int32_t*);
void TimbreProcess(void*,void*,size_t);
void TimbreRefresh(void*,void*);
void TimbreEnable(void*,void*,int);
void TimbreSetLegacyDrive(void*,void*,double);
#ifdef DSP_FUSION_TEST_ALLOCATIONS
void TimbreAllocationFailureForTest(int);
int TimbreQueuedForTest(void*);
void TimbreFirImpulseForTest(int,float*,size_t);
#endif
#ifdef __cplusplus
}
#endif
