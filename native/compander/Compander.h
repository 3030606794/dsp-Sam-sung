#pragma once
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
#define COMPANDER_WORDS 72
#define COMPANDER_TOOLS_WORDS 148
#define COMPANDER_RUNTIME_WORDS 80
#define COMPANDER_RESPONSE_WORDS 200
int CompanderValidate(const int32_t*,size_t);
int CompanderToolsValidate(const int32_t*,size_t);
void* CompanderCreate(void);
void CompanderDestroy(void*);
int CompanderSet(void*,void*,const int32_t*,size_t);
int CompanderTools(void*,void*,const int32_t*,size_t);
void CompanderClear(void*);
void CompanderStatus(const void*,int32_t*);
void CompanderResponse(const void*,int32_t*);
void CompanderProcess(void*,void*,size_t);
void CompanderRefresh(void*,void*);
void CompanderEnable(void*,void*,int);
void CompanderSetLegacyParam(void*,void*,float,int,int);
void CompanderSetLegacyCurve(void*,void*,const double*,const double*);
#ifdef DSP_FUSION_TEST_ALLOCATIONS
void CompanderAllocationFailureForTest(int);
int CompanderQueuedForTest(void*);
#endif
#ifdef __cplusplus
}
#endif
