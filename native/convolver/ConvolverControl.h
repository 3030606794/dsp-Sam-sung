#pragma once
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
void* FusionConvCreate(void);
void FusionConvDestroy(void*);
void FusionConvConfigure(void*, uint32_t);
int FusionConvSet(void*, const int32_t*, size_t);
int FusionConvAudition(void*, int32_t);
void FusionConvClear(void*);
void FusionConvEnabled(void*, int);
void FusionConvLoaded(void*, uint32_t channels, uint32_t frames, int32_t crc);
void FusionConvBegin(void*, const float*, const float*, size_t);
void FusionConvEnd(void*, float*, float*, size_t);
void FusionConvSnapshot(void*, int32_t*);
int FusionConvSetAdvanced(void*, const int32_t*, size_t);
void FusionConvAdvancedSnapshot(void*, int32_t*);
void FusionConvPrepareInput(void*, float*, float*, size_t);
void FusionConvMixTransition(void*, float*, float*, size_t);
int FusionConvAttachRetired(void*, void*, uint32_t, uint32_t);
void* FusionConvReap(void*, int force);
void* FusionConvDrain(void*);
int FusionConvBusy(void*);
uint32_t FusionConvJobFrames(void*);
uint32_t FusionConvJobChannels(void*);
void FusionConvJobInputs(void*, float**, float**);
void FusionConvProcessJob(void*, size_t);
/* Opaque jobs own uploaded samples and prepared/retired convolution plans. */
int FusionConvPrepareJob(void*);
void FusionConvDestroyJob(void*);
#define FUSION_CONV_TAKE 0x434f4e54u
#define FUSION_CONV_COMMIT 0x434f4e43u
#define FUSION_CONV_CLEAR 0x434f4e52u
#define FUSION_CONV_REAP 0x434f4e50u
#define FUSION_CONV_FORCE_REAP 0x434f4e46u
#define FUSION_CONV_DRAIN 0x434f4e44u
#ifdef __cplusplus
}
#endif
