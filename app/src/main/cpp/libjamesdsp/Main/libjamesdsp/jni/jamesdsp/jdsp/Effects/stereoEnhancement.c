#include <string.h>
#include <stdlib.h>
#include <math.h>
#include "../jdsp_header.h"
void StereoEnhancementRefreshLocked(JamesDSPLib *jdsp)
{
	StereoWidthConfigure(&jdsp->sterEnh.professionalWidth,jdsp->fs);
	WarpedPFB *subband0 = (WarpedPFB *)jdsp->sterEnh.subband[0];
	WarpedPFB *subband1 = (WarpedPFB *)jdsp->sterEnh.subband[1];
	initWarpedPFB(subband0, jdsp->fs, 5, 2);
	assignPtrWarpedPFB(subband1, 5, 2);
	float ms = 1.2f; // 1.2 ms
	for (unsigned int i = 0; i < 5; i++)
		jdsp->sterEnh.emaAlpha[i] = 1.0f - powf(10.0f, (log10f(0.5f) / (ms / 1000.0f) / (jdsp->fs / (float)subband0->Sk[i])));
}
void StereoEnhancementRefresh(JamesDSPLib *jdsp)
{
  jdsp_lock(jdsp);
  StereoEnhancementRefreshLocked(jdsp);
  jdsp_unlock(jdsp);
}
void StereoEnhancementSetParam(JamesDSPLib *jdsp, float mix)
{
  jdsp_lock(jdsp);
	jdsp->sterEnh.mix = mix;
	jdsp->sterEnh.minusMix = 1.0f - jdsp->sterEnh.mix;
	if (jdsp->sterEnh.mix > 0.5f)
		jdsp->sterEnh.gain = 3.0f - jdsp->sterEnh.mix * 2.0f;
	else
		jdsp->sterEnh.gain = jdsp->sterEnh.mix * 2.0f + 1.0f;
  jdsp_unlock(jdsp);
}
void StereoEnhancementConstructor(JamesDSPLib *jdsp)
{
	StereoWidthInit(&jdsp->sterEnh.professionalWidth,jdsp->fs);
	StereoEnhancementRefresh(jdsp);
}
void StereoEnhancementDestructor(JamesDSPLib *jdsp)
{
}
void StereoEnhancementEnable(JamesDSPLib *jdsp)
{
	jdsp->sterEnh.professionalWidth.enabled=1;
	jdsp->sterEnhEnabled = 1;
}
void StereoEnhancementDisable(JamesDSPLib *jdsp)
{
	jdsp->sterEnh.professionalWidth.enabled=0;
	StereoWidthClear(&jdsp->sterEnh.professionalWidth);
	jdsp->sterEnhEnabled = 0;
}
void StereoEnhancementProcess(JamesDSPLib *jdsp, size_t n)
{
	stereoEnhancement *snh = &jdsp->sterEnh;
	WarpedPFB *subband0 = (WarpedPFB *)snh->subband[0];
	WarpedPFB *subband1 = (WarpedPFB *)snh->subband[1];
	unsigned int *samplingPeriod = subband0->decimationCounter;
	unsigned int *Sk = subband0->Sk;
	float *bandLeft = subband0->subbandData;
	float *bandRight = subband1->subbandData;
	float y1, y2;
	for (size_t i = 0; i < n; i++)
	{
		const float inputL=jdsp->tmpBuffer[0][i],inputR=jdsp->tmpBuffer[1][i];
		analysisWarpedPFBStereo(subband0, subband1, &jdsp->tmpBuffer[0][i], &jdsp->tmpBuffer[1][i]);
		for (int j = 0; j < 5; j++)
		{
			if (samplingPeriod[j] == Sk[j])
			{
				float sum = bandLeft[j] + bandRight[j];
				float diff = bandLeft[j] - bandRight[j];
				float sumSq = sum * sum;
				float diffSq = diff * diff;
				snh->sumStates[j] = snh->sumStates[j] * (1.0f - snh->emaAlpha[j]) + sumSq * snh->emaAlpha[j];
				snh->diffStates[j] = snh->diffStates[j] * (1.0f - snh->emaAlpha[j]) + diffSq * snh->emaAlpha[j];
				float centre = 0.0f;
				if (sumSq > FLT_EPSILON)
					centre = (0.5f - sqrtf(snh->diffStates[j] / snh->sumStates[j]) * 0.5f) * sum;
				bandLeft[j] = (bandLeft[j] - centre) * snh->mix + centre * snh->minusMix;
				bandRight[j] = (bandRight[j] - centre) * snh->mix + centre * snh->minusMix;
			}
		}
		synthesisWarpedPFBStereo(subband0, subband1, &y1, &y2);
		jdsp->tmpBuffer[0][i] = y1 * snh->gain;
		jdsp->tmpBuffer[1][i] = y2 * snh->gain;
		StereoWidthFrame(&snh->professionalWidth,inputL,inputR,&jdsp->tmpBuffer[0][i],&jdsp->tmpBuffer[1][i]);
	}
}
