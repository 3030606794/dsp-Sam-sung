#include <string.h>
#include <stdlib.h>
#include <errno.h>
#include <math.h>
#include <float.h>
#include "../jdsp_header.h"
#ifdef DSP_FUSION_TEST_ALLOCATIONS
// Host tests inject failures only into this loader's own allocations. Android
// builds omit both the hook and this symbol; FFT internals use their allocators.
static int convolverAllocationFailureAfter = -1;
__attribute__((visibility("default"))) void JamesDSPTestFailIRAllocationAfter(int successfulAllocations)
{
    convolverAllocationFailureAfter = successfulAllocations;
}
static void *convolverAllocate(size_t bytes)
{
    if (convolverAllocationFailureAfter == 0)
    {
        convolverAllocationFailureAfter = -1;
        return NULL;
    }
    if (convolverAllocationFailureAfter > 0) --convolverAllocationFailureAfter;
    return malloc(bytes);
}
#else
#define convolverAllocate malloc
#endif
void Convolver1DEnable(JamesDSPLib *jdsp)
{
	if (jdsp->conv.process)
		jdsp->convolverEnabled = 1;
}
void Convolver1DDisable(JamesDSPLib *jdsp)
{
	jdsp->convolverEnabled = 0;
}
void Convolver1DConstructor(JamesDSPLib *jdsp)
{
	memset(&jdsp->conv, 0, sizeof(jdsp->conv));
}
void Convolver1DDestructor(JamesDSPLib *jdsp)
{
	if (jdsp->conv.conv1d2x2_S_S)
	{
		FFTConvolver2x2Free(jdsp->conv.conv1d2x2_S_S);
		free(jdsp->conv.conv1d2x2_S_S);
	}
	if (jdsp->conv.conv1d2x2_T_S)
	{
		TwoStageFFTConvolver2x2Free(jdsp->conv.conv1d2x2_T_S);
		free(jdsp->conv.conv1d2x2_T_S);
	}
	if (jdsp->conv.conv1d2x4x2_S_S)
	{
		FFTConvolver2x4x2Free(jdsp->conv.conv1d2x4x2_S_S);
		free(jdsp->conv.conv1d2x4x2_S_S);
	}
	if (jdsp->conv.conv1d2x4x2_T_S)
	{
		TwoStageFFTConvolver2x4x2Free(jdsp->conv.conv1d2x4x2_T_S);
		free(jdsp->conv.conv1d2x4x2_T_S);
	}
}
void Convolver1DProcessFFTConvolver2x2(JamesDSPLib *jdsp, size_t n)
{
	FFTConvolver2x2Process(jdsp->conv.conv1d2x2_S_S, jdsp->tmpBuffer[0], jdsp->tmpBuffer[1], jdsp->tmpBuffer[0], jdsp->tmpBuffer[1], (unsigned int)n);
}
void Convolver1DProcessTwoStageFFTConvolver2x2(JamesDSPLib *jdsp, size_t n)
{
	TwoStageFFTConvolver2x2Process(jdsp->conv.conv1d2x2_T_S, jdsp->tmpBuffer[0], jdsp->tmpBuffer[1], jdsp->tmpBuffer[0], jdsp->tmpBuffer[1], (unsigned int)n);
}
void Convolver1DProcessFFTConvolver2x4x2(JamesDSPLib *jdsp, size_t n)
{
	FFTConvolver2x4x2Process(jdsp->conv.conv1d2x4x2_S_S, jdsp->tmpBuffer[0], jdsp->tmpBuffer[1], jdsp->tmpBuffer[0], jdsp->tmpBuffer[1], (unsigned int)n);
}
void Convolver1DProcessTwoStageFFTConvolver2x4x2(JamesDSPLib *jdsp, size_t n)
{
	TwoStageFFTConvolver2x4x2Process(jdsp->conv.conv1d2x4x2_T_S, jdsp->tmpBuffer[0], jdsp->tmpBuffer[1], jdsp->tmpBuffer[0], jdsp->tmpBuffer[1], (unsigned int)n);
}
int Convolver1DLoadImpulseResponse(JamesDSPLib *jdsp, float *tempImpulseFloat, unsigned int impChannels, size_t impulseLengthActual, char updateOld)
{
	jdsp_lock(jdsp);
	Convolver1DDestructor(jdsp);
	Convolver1DConstructor(jdsp);
	float **finalImpulse = NULL;
	int ret = 0;
	if (updateOld)
	{
		float *storedImpulse = (float *)convolverAllocate(impChannels * impulseLengthActual * sizeof(float));
		if (!storedImpulse) goto bufDeleteAndUnlock;
		memcpy(storedImpulse, tempImpulseFloat, impChannels * impulseLengthActual * sizeof(float));
		free(jdsp->impulseResponseStorage.impulseResponse);
		jdsp->impulseResponseStorage.impulseResponse = storedImpulse;
		jdsp->impulseResponseStorage.impChannels = impChannels;
		jdsp->impulseResponseStorage.impulseLengthActual = impulseLengthActual;
	}
	finalImpulse = (float**)convolverAllocate(impChannels * sizeof(float*));
	if (!finalImpulse) goto bufDeleteAndUnlock;
	memset(finalImpulse, 0, impChannels * sizeof(float*));
	ret = 1;
	for (unsigned int i = 0; i < impChannels; i++)
	{
		float* channelbuf = (float*)convolverAllocate(impulseLengthActual * sizeof(float));
		if (!channelbuf)
		{
			ret = 0;
			goto bufDeleteAndUnlock;
		}
		float* p = tempImpulseFloat + i;
		for (unsigned int j = 0; j < impulseLengthActual; j++)
			channelbuf[j] = p[j * impChannels];
		finalImpulse[i] = channelbuf;
	}
	unsigned int seg2Len = 0;
	if (selectConvPartitions(jdsp, impulseLengthActual, &seg2Len) == 1)
	{
		if (impChannels == 1)
		{
			jdsp->conv.conv1d2x2_S_S = (FFTConvolver2x2*)convolverAllocate(sizeof(FFTConvolver2x2));
			if (!jdsp->conv.conv1d2x2_S_S)
			{
				ret = 0;
				goto bufDeleteAndUnlock;
			}
			FFTConvolver2x2Init(jdsp->conv.conv1d2x2_S_S);
			ret = FFTConvolver2x2LoadImpulseResponse(jdsp->conv.conv1d2x2_S_S, (unsigned int)jdsp->blockSize, finalImpulse[0], finalImpulse[0], impulseLengthActual);
			if (!ret)
			{
				FFTConvolver2x2Free(jdsp->conv.conv1d2x2_S_S);
				free(jdsp->conv.conv1d2x2_S_S);
				jdsp->conv.conv1d2x2_S_S = 0;
				goto bufDeleteAndUnlock;
			}
			jdsp->conv.process = Convolver1DProcessFFTConvolver2x2;
		}
		if (impChannels == 2)
		{
			jdsp->conv.conv1d2x2_S_S = (FFTConvolver2x2*)convolverAllocate(sizeof(FFTConvolver2x2));
			if (!jdsp->conv.conv1d2x2_S_S)
			{
				ret = 0;
				goto bufDeleteAndUnlock;
			}
			FFTConvolver2x2Init(jdsp->conv.conv1d2x2_S_S);
			ret = FFTConvolver2x2LoadImpulseResponse(jdsp->conv.conv1d2x2_S_S, (unsigned int)jdsp->blockSize, finalImpulse[0], finalImpulse[1], impulseLengthActual);
			if (!ret)
			{
				FFTConvolver2x2Free(jdsp->conv.conv1d2x2_S_S);
				free(jdsp->conv.conv1d2x2_S_S);
				jdsp->conv.conv1d2x2_S_S = 0;
				goto bufDeleteAndUnlock;
			}
			jdsp->conv.process = Convolver1DProcessFFTConvolver2x2;
		}
		if (impChannels == 4)
		{
			jdsp->conv.conv1d2x4x2_S_S = (FFTConvolver2x4x2*)convolverAllocate(sizeof(FFTConvolver2x4x2));
			if (!jdsp->conv.conv1d2x4x2_S_S)
			{
				ret = 0;
				goto bufDeleteAndUnlock;
			}
			FFTConvolver2x4x2Init(jdsp->conv.conv1d2x4x2_S_S);
			ret = FFTConvolver2x4x2LoadImpulseResponse(jdsp->conv.conv1d2x4x2_S_S, (unsigned int)jdsp->blockSize, finalImpulse[0], finalImpulse[1], finalImpulse[2], finalImpulse[3], impulseLengthActual);
			if (!ret)
			{
				FFTConvolver2x4x2Free(jdsp->conv.conv1d2x4x2_S_S);
				free(jdsp->conv.conv1d2x4x2_S_S);
				jdsp->conv.conv1d2x4x2_S_S = 0;
				goto bufDeleteAndUnlock;
			}
			jdsp->conv.process = Convolver1DProcessFFTConvolver2x4x2;
		}
	}
	else
	{
		if (seg2Len < 1)
			seg2Len = 1;
		if (impChannels == 1)
		{
			jdsp->conv.conv1d2x2_T_S = (TwoStageFFTConvolver2x2*)convolverAllocate(sizeof(TwoStageFFTConvolver2x2));
			if (!jdsp->conv.conv1d2x2_T_S)
			{
				ret = 0;
				goto bufDeleteAndUnlock;
			}
			TwoStageFFTConvolver2x2Init(jdsp->conv.conv1d2x2_T_S);
			ret = TwoStageFFTConvolver2x2LoadImpulseResponse(jdsp->conv.conv1d2x2_T_S, (unsigned int)jdsp->blockSize, seg2Len, finalImpulse[0], finalImpulse[0], impulseLengthActual);
			if (!ret)
			{
				TwoStageFFTConvolver2x2Free(jdsp->conv.conv1d2x2_T_S);
				free(jdsp->conv.conv1d2x2_T_S);
				jdsp->conv.conv1d2x2_T_S = 0;
				goto bufDeleteAndUnlock;
			}
			jdsp->conv.process = Convolver1DProcessTwoStageFFTConvolver2x2;
		}
		if (impChannels == 2)
		{
			jdsp->conv.conv1d2x2_T_S = (TwoStageFFTConvolver2x2*)convolverAllocate(sizeof(TwoStageFFTConvolver2x2));
			if (!jdsp->conv.conv1d2x2_T_S)
			{
				ret = 0;
				goto bufDeleteAndUnlock;
			}
			TwoStageFFTConvolver2x2Init(jdsp->conv.conv1d2x2_T_S);
			ret = TwoStageFFTConvolver2x2LoadImpulseResponse(jdsp->conv.conv1d2x2_T_S, (unsigned int)jdsp->blockSize, seg2Len, finalImpulse[0], finalImpulse[1], impulseLengthActual);
			if (!ret)
			{
				TwoStageFFTConvolver2x2Free(jdsp->conv.conv1d2x2_T_S);
				free(jdsp->conv.conv1d2x2_T_S);
				jdsp->conv.conv1d2x2_T_S = 0;
				goto bufDeleteAndUnlock;
			}
			jdsp->conv.process = Convolver1DProcessTwoStageFFTConvolver2x2;
		}
		if (impChannels == 4)
		{
			jdsp->conv.conv1d2x4x2_T_S = (TwoStageFFTConvolver2x4x2*)convolverAllocate(sizeof(TwoStageFFTConvolver2x4x2));
			if (!jdsp->conv.conv1d2x4x2_T_S)
			{
				ret = 0;
				goto bufDeleteAndUnlock;
			}
			TwoStageFFTConvolver2x4x2Init(jdsp->conv.conv1d2x4x2_T_S);
			ret = TwoStageFFTConvolver2x4x2LoadImpulseResponse(jdsp->conv.conv1d2x4x2_T_S, (unsigned int)jdsp->blockSize, seg2Len, finalImpulse[0], finalImpulse[1], finalImpulse[2], finalImpulse[3], impulseLengthActual);
			if (!ret)
			{
				TwoStageFFTConvolver2x4x2Free(jdsp->conv.conv1d2x4x2_T_S);
				free(jdsp->conv.conv1d2x4x2_T_S);
				jdsp->conv.conv1d2x4x2_T_S = 0;
				goto bufDeleteAndUnlock;
			}
			jdsp->conv.process = Convolver1DProcessTwoStageFFTConvolver2x4x2;
		}
	}
bufDeleteAndUnlock:
	if (finalImpulse)
	{
		for (unsigned int i = 0; i < impChannels; i++)
			free(finalImpulse[i]);
		free(finalImpulse);
	}
	if (!ret)
	{
		Convolver1DDestructor(jdsp);
		Convolver1DConstructor(jdsp);
		Convolver1DDisable(jdsp);
	}
	jdsp_unlock(jdsp);
	if (!ret)
		return -2;
	else
		return 1;
}
