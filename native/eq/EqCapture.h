#pragma once
#include <stddef.h>
#include <stdint.h>
#define EQ_PCM_GET 131256u
#define EQ_PCM_CAPABILITY 65536
#define EQ_PCM_WORDS (32+4*1024)
// Child-only discontinuity command. Never exposed as an audio parameter.
#define EQ_PCM_RESET 0x4d455152u
#ifdef __cplusplus
extern "C" {
#endif
void* EqCaptureCreate(void);
void EqCaptureDestroy(void*);
void EqCaptureReset(void*);
void EqCaptureBegin(void*,uint32_t,int,int,int,const double*,const double*,const float*,const float*,size_t);
void EqCaptureEnd(void*,const float*,const float*,size_t);
int EqCaptureRead(void*,int32_t*);
#ifdef __cplusplus
}
#endif
