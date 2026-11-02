// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
#define DDC_ADVANCED_WORDS 64
#define DDC_TOOLS_WORDS 132
#define DDC_RUNTIME_WORDS 80
#define DDC_RESPONSE_WORDS 232
#define DDC_MAX_SOS 128
#define DDC_MAX_FILE_BYTES (256 * 1024)
#define DDC_MIN_SAMPLE_RATE 8000u
#define DDC_MAX_SAMPLE_RATE 768000u
// Text coefficients use feedback signs; parsed arrays are normalized b0,b1,b2,a1,a2.
int DdcParse(const char*,size_t,double*,double*,size_t*);
void DdcSha256(const char*,size_t,int32_t*);
void* DdcCreate(void);
void DdcDestroy(void*);
int DdcValidate(const int32_t*,size_t);
int DdcToolsValidate(const int32_t*,size_t);
void DdcDefaults(int32_t*);
int DdcSet(void*,const int32_t*,size_t);
int DdcTools(void*,const int32_t*,size_t);
void DdcClear(void*);
void DdcEnabled(void*,int);
void DdcOriginal(void*);
int DdcNeedsProcessing(const void*);
void DdcConfigure(void*,uint32_t);
void DdcHostRate(void*,uint32_t);
void DdcFileError(void*,int);
int DdcFile(void*,const double*,const double*,size_t,const char*,size_t);
void DdcReset(void*);
void DdcProcess(void*,float*,float*,size_t);
void DdcStatus(const void*,int32_t*);
void DdcResponse(const void*,int32_t*);
#ifdef DSP_FUSION_TEST_ALLOCATIONS
void DdcFailNextAllocationForTest(void);
void DdcFailFileAllocationForTest(int);
uint64_t DdcPreparationCountForTest(void);
#endif
#ifdef __cplusplus
}
#endif
