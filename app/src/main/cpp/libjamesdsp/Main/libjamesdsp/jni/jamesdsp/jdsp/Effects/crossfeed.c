#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <float.h>
#include "../jdsp_header.h"
#ifdef DSP_FUSION_STREAMING
#include "presetCrossfeed.h"
#endif
void CrossfeedConstructor(JamesDSPLib *jdsp)
{
	memset(&jdsp->advXF, 0, sizeof(jdsp->advXF));
#ifdef DSP_FUSION_STREAMING
    jdsp->advXF.professional=PresetCrossfeedCreate();
#endif
}
static void CrossfeedRelease(Crossfeed *state)
{
    for (unsigned int i=0;i<3;++i) {
        if (state->conv[i]) { FFTConvolver2x4x2Free(state->conv[i]); free(state->conv[i]); state->conv[i]=0; }
    }
    if (state->convLong_T_S) { TwoStageFFTConvolver2x4x2Free(state->convLong_T_S); free(state->convLong_T_S); state->convLong_T_S=0; }
    if (state->convLong_S_S) { FFTConvolver2x4x2Free(state->convLong_S_S); free(state->convLong_S_S); state->convLong_S_S=0; }
    state->process=0; state->preparedRate=0;
}
void CrossfeedDestructor(JamesDSPLib *jdsp) { CrossfeedRelease(&jdsp->advXF); }
void CrossfeedProcessFFTConvolver2x4x2(JamesDSPLib *jdsp, size_t n)
{
	FFTConvolver2x4x2Process(jdsp->advXF.convLong_S_S, jdsp->tmpBuffer[0], jdsp->tmpBuffer[1], jdsp->tmpBuffer[0], jdsp->tmpBuffer[1], (unsigned int)n);
}
void CrossfeedProcessTwoStageFFTConvolver2x4x2(JamesDSPLib *jdsp, size_t n)
{
	TwoStageFFTConvolver2x4x2Process(jdsp->advXF.convLong_T_S, jdsp->tmpBuffer[0], jdsp->tmpBuffer[1], jdsp->tmpBuffer[0], jdsp->tmpBuffer[1], (unsigned int)n);
}
static void CrossfeedResetSelected(Crossfeed* state,int mode){
    if(mode>=2 && mode<5)FFTConvolver2x4x2ResetHistory(state->conv[mode-2]);
    if(mode==5){FFTConvolver2x4x2ResetHistory(state->convLong_S_S);TwoStageFFTConvolver2x4x2ResetHistory(state->convLong_T_S);}
}
void CrossfeedEnableLocked(JamesDSPLib *jdsp, char enable)
{
    unsigned int tail=0;
    const int single=selectConvPartitions(jdsp,jdsp->frameLenSVirResampled,&tail)==1;
    const int ready=jdsp->advXF.conv[0] && jdsp->advXF.conv[1] && jdsp->advXF.conv[2] &&
        (single ? jdsp->advXF.convLong_S_S!=0 : jdsp->advXF.convLong_T_S!=0) &&
        jdsp->advXF.preparedRate==jdsp->fs && jdsp->advXF.preparedBlock==(unsigned int)jdsp->blockSize &&
        jdsp->advXF.preparedShortLength==jdsp->blobsResampledLen && jdsp->advXF.preparedLongLength==jdsp->frameLenSVirResampled;
    const int refreshed=jdsp->crossfeedForceRefresh || !ready;
    if (refreshed) {
        // Prepare a complete replacement. A failed allocation/load cannot destroy the active cache.
        Crossfeed next={0};
        for (unsigned int i=0;i<3;++i) {
            next.conv[i]=(FFTConvolver2x4x2*)FFTConvolver2x4x2Allocate(1,sizeof(FFTConvolver2x4x2));
            if (!next.conv[i]) goto prepare_failed;
            FFTConvolver2x4x2Init(next.conv[i]);
            if (!FFTConvolver2x4x2LoadImpulseResponse(next.conv[i],(unsigned int)jdsp->blockSize,
                jdsp->blobsCh1[i],jdsp->blobsCh2[i],jdsp->blobsCh3[i],jdsp->blobsCh4[i],jdsp->blobsResampledLen)) goto prepare_failed;
        }
        if (single) {
            next.convLong_S_S=(FFTConvolver2x4x2*)FFTConvolver2x4x2Allocate(1,sizeof(FFTConvolver2x4x2));
            if (!next.convLong_S_S) goto prepare_failed;
            FFTConvolver2x4x2Init(next.convLong_S_S);
            if (!FFTConvolver2x4x2LoadImpulseResponse(next.convLong_S_S,(unsigned int)jdsp->blockSize,
                jdsp->hrtfblobsResampled[0],jdsp->hrtfblobsResampled[1],jdsp->hrtfblobsResampled[2],jdsp->hrtfblobsResampled[3],jdsp->frameLenSVirResampled)) goto prepare_failed;
            next.process=CrossfeedProcessFFTConvolver2x4x2;
        } else {
            next.convLong_T_S=(TwoStageFFTConvolver2x4x2*)FFTConvolver2x4x2Allocate(1,sizeof(TwoStageFFTConvolver2x4x2));
            if (!next.convLong_T_S) goto prepare_failed;
            TwoStageFFTConvolver2x4x2Init(next.convLong_T_S);
            if (!TwoStageFFTConvolver2x4x2LoadImpulseResponse(next.convLong_T_S,(unsigned int)jdsp->blockSize,tail,
                jdsp->hrtfblobsResampled[0],jdsp->hrtfblobsResampled[1],jdsp->hrtfblobsResampled[2],jdsp->hrtfblobsResampled[3],jdsp->frameLenSVirResampled)) goto prepare_failed;
            next.process=CrossfeedProcessTwoStageFFTConvolver2x4x2;
        }
        CrossfeedRelease(&jdsp->advXF);
        for (unsigned int i=0;i<3;++i) jdsp->advXF.conv[i]=next.conv[i];
        jdsp->advXF.convLong_S_S=next.convLong_S_S; jdsp->advXF.convLong_T_S=next.convLong_T_S; jdsp->advXF.process=next.process;
        jdsp->advXF.preparedRate=jdsp->fs; jdsp->advXF.preparedBlock=(unsigned int)jdsp->blockSize;
        jdsp->advXF.preparedShortLength=jdsp->blobsResampledLen; jdsp->advXF.preparedLongLength=jdsp->frameLenSVirResampled;
        jdsp->crossfeedForceRefresh=0;
        goto prepared;
prepare_failed:
        CrossfeedRelease(&next);
        // A cache built for a different format is not safe to use; same-format failures preserve playback.
        if (!ready) {
            jdsp->crossfeedEnabled=0;
#ifdef DSP_FUSION_STREAMING
            PresetCrossfeedActivate(jdsp->advXF.professional,jdsp,0);
#endif
        }
        return;
    }
prepared:
    if(!jdsp->crossfeedEnabled)CrossfeedResetSelected(&jdsp->advXF,jdsp->advXF.mode);
    if ((!jdsp->crossfeedEnabled || refreshed) && jdsp->advXF.mode<2) {
        memset(&jdsp->advXF.bs2b,0,sizeof(jdsp->advXF.bs2b));
        BS2BInit(&jdsp->advXF.bs2b[0],(unsigned int)jdsp->fs,BS2B_CMOY_CLEVEL);
        BS2BInit(&jdsp->advXF.bs2b[1],(unsigned int)jdsp->fs,BS2B_JMEIER_CLEVEL);
    }
    if (enable) jdsp->crossfeedEnabled=1;
#ifdef DSP_FUSION_STREAMING
    PresetCrossfeedActivate(jdsp->advXF.professional,jdsp,enable);
#endif
}
void CrossfeedEnable(JamesDSPLib *jdsp,char enable)
{
    jdsp_lock(jdsp);CrossfeedEnableLocked(jdsp,enable);jdsp_unlock(jdsp);
}
void CrossfeedDisable(JamesDSPLib *jdsp)
{
	jdsp->crossfeedEnabled = 0;
#ifdef DSP_FUSION_STREAMING
    PresetCrossfeedActivate(jdsp->advXF.professional,jdsp,0);
#endif
}
void CrossfeedChangeMode(JamesDSPLib *jdsp, int nMode)
{
	if (nMode < 0)
		nMode = 0;
	if (nMode > 5)
		nMode = 5;
	if (nMode == jdsp->advXF.mode) return;
    CrossfeedResetSelected(&jdsp->advXF,nMode);
	if (nMode < 2)
	{
		memset(&jdsp->advXF.bs2b, 0, sizeof(jdsp->advXF.bs2b));
		if (!nMode)
			BS2BInit(&jdsp->advXF.bs2b[0], (unsigned int)jdsp->fs, BS2B_CMOY_CLEVEL);
		else
			BS2BInit(&jdsp->advXF.bs2b[1], (unsigned int)jdsp->fs, BS2B_JMEIER_CLEVEL);
	}
	jdsp->advXF.mode = nMode;
}
static void CrossfeedProcessOriginal(JamesDSPLib *jdsp, size_t n)
{
	if (jdsp->advXF.mode < 2)
	{
		double tmpL, tmpR;
		for (size_t i = 0; i < n; i++)
		{
			tmpL = (double)jdsp->tmpBuffer[0][i];
			tmpR = (double)jdsp->tmpBuffer[1][i];
			BS2BProcess(&jdsp->advXF.bs2b[jdsp->advXF.mode], &tmpL, &tmpR);
			jdsp->tmpBuffer[0][i] = (float)tmpL;
			jdsp->tmpBuffer[1][i] = (float)tmpR;
		}
	}
	else if (jdsp->advXF.mode < 5)
		FFTConvolver2x4x2Process(jdsp->advXF.conv[jdsp->advXF.mode - 2], jdsp->tmpBuffer[0], jdsp->tmpBuffer[1], jdsp->tmpBuffer[0], jdsp->tmpBuffer[1], (unsigned int)n);
	else
		jdsp->advXF.process(jdsp, (unsigned int)n);
}
void CrossfeedProcess(JamesDSPLib *jdsp, size_t n)
{
#ifdef DSP_FUSION_STREAMING
    if(PresetCrossfeedActive(jdsp->advXF.professional)) {
        float rawL[512],rawR[512];float* left=jdsp->tmpBuffer[0];float* right=jdsp->tmpBuffer[1];
        for(size_t offset=0;offset<n;offset+=512){
            const size_t count=n-offset<512?n-offset:512;
            jdsp->tmpBuffer[0]=left+offset;jdsp->tmpBuffer[1]=right+offset;
            memcpy(rawL,jdsp->tmpBuffer[0],count*sizeof(float));memcpy(rawR,jdsp->tmpBuffer[1],count*sizeof(float));
            CrossfeedProcessOriginal(jdsp,count);
            PresetCrossfeedProcess(jdsp->advXF.professional,rawL,rawR,jdsp->tmpBuffer[0],jdsp->tmpBuffer[1],count);
        }
        jdsp->tmpBuffer[0]=left;jdsp->tmpBuffer[1]=right;return;
    }
#endif
    CrossfeedProcessOriginal(jdsp,n);
}
