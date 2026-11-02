#pragma once
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
#define PRESET_XF_WORDS 9
#define PRESET_XF_ADVANCED_WORDS 10
#define PRESET_XF_ADVANCED_TOOLS_WORDS 21
#define PRESET_XF_TOOLS_WORDS 21
#define PRESET_XF_RUNTIME_WORDS 24
#define FUSION_PRESET_XF_CLEAR 0x5846434cu
int PresetCrossfeedAdvancedValidate(const int32_t*,size_t);
int PresetCrossfeedAdvancedToolsValidate(const int32_t*,size_t);
int PresetCrossfeedAdvancedSet(void*,void*,const int32_t*,size_t);
int PresetCrossfeedAdvancedTools(void*,void*,const int32_t*,size_t);
void* PresetCrossfeedCreate(void);
void PresetCrossfeedDestroy(void*);
int PresetCrossfeedValidate(const int32_t*,size_t);
int PresetCrossfeedToolsValidate(const int32_t*,size_t);
int PresetCrossfeedSet(void*,void*,const int32_t*,size_t);
int PresetCrossfeedTools(void*,void*,const int32_t*,size_t);
void PresetCrossfeedRefresh(void*,void*);
void PresetCrossfeedActivate(void*,void*,int);
void PresetCrossfeedClear(void*);
int PresetCrossfeedActive(const void*);
void PresetCrossfeedProcess(void*,const float*,const float*,float*,float*,size_t);
void PresetCrossfeedSnapshot(const void*,int32_t*);
#ifdef __cplusplus
}
#endif
