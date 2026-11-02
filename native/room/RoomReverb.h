#pragma once
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
#define ROOM_REVERB_WORDS 24
#define ROOM_REVERB_TOOLS_WORDS 52
#define ROOM_REVERB_RUNTIME_WORDS 32
int RoomReverbValidate(const int32_t*,size_t);
int RoomReverbToolsValidate(const int32_t*,size_t);
void* RoomReverbCreate(void);
void RoomReverbDestroy(void*);
int RoomReverbSet(void*,void*,const int32_t*,size_t);
int RoomReverbTools(void*,void*,const int32_t*,size_t);
void RoomReverbClear(void*);
void RoomReverbRefresh(void*,void*);
int RoomReverbBase(void*,void*,int);
void RoomReverbEnabled(void*,int);
void RoomReverbProcess(void*,const float*,const float*,float*,float*,size_t);
void RoomReverbStatus(const void*,int32_t*);
#ifdef DSP_FUSION_TEST_ALLOCATIONS
void RoomReverbFailNextAllocationForTest(void);
void RoomReverbAllocationFailureForTest(int);
int RoomReverbQueuedForTest(void*);
void RoomReverbCopyStateForTest(void*,void*);
void RoomReverbCopyNoiseForTest(void*,void*);
void RoomReverbSetNoiseForTest(void*,const void*);
#endif
#ifdef __cplusplus
}
#endif
