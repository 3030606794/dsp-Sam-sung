// SPDX-License-Identifier: GPL-3.0-or-later
// Frozen pre-HiFi-gain implementation; test oracle for unchanged 44.1/48 kHz.
#include <math.h>
#include "jdsp/jdsp_header.h"
void JamesDSPOfflineResampling(const float*,float*,size_t,size_t,int,double);
#include "jdsp/Effects/eel2/dr_flac.h"
// Binary blobs
extern const int hrtfLenPerChannel;
extern const int hrtfFs;
extern const int compressedLen_jdspImp;
extern const unsigned char jdspImp[7172];
extern const int compressedLen_CCConv;
extern const double ccconv1Gain;
extern const double ccconv2Gain;
extern const double ccconv3Gain;
extern const double ccconv4Gain;
extern const unsigned char CCConv[202119];
void JamesDSPRefreshBlobLegacyReference(JamesDSPLib *jdsp, double targetFs)
{
	const int channelsBlobsShort = 4;
	double ratio = targetFs / (double)hrtfFs;
	int outLen = (int)ceil(hrtfLenPerChannel * ratio);
	int i;
	if (jdsp->blobsCh1[0])
	{
		for (i = 0; i < 3; i++)
		{
			free(jdsp->blobsCh1[i]);
			free(jdsp->blobsCh2[i]);
			free(jdsp->blobsCh3[i]);
			free(jdsp->blobsCh4[i]);
		}
	}
	for (i = 0; i < 3; i++)
	{
		jdsp->blobsCh1[i] = (float *)malloc(outLen * sizeof(float));
		jdsp->blobsCh2[i] = (float *)malloc(outLen * sizeof(float));
		jdsp->blobsCh3[i] = (float *)malloc(outLen * sizeof(float));
		jdsp->blobsCh4[i] = (float *)malloc(outLen * sizeof(float));
	}
	jdsp->blobsResampledLen = outLen;
	float *tmpBuf = (float *)malloc(outLen * channelsBlobsShort * sizeof(float));
	memset(tmpBuf, 0, outLen * channelsBlobsShort * sizeof(float));

	drflac *pFlac = drflac_open_memory((void *)jdspImp, compressedLen_jdspImp, 0);
	size_t totalSmps = pFlac->totalPCMFrameCount * (size_t)pFlac->channels;
	float *pFrameImpulse = (float *)malloc(totalSmps * sizeof(float));
	size_t sampleCount = pFlac->totalPCMFrameCount;
	drflac_read_pcm_frames_f32(pFlac, totalSmps, pFrameImpulse);
	size_t copySize = (hrtfLenPerChannel << 2) * sizeof(float);
	float *CorredHRTF_Surround1 = (float *)malloc(copySize);
	memcpy(CorredHRTF_Surround1, pFrameImpulse, copySize);
	float *CorredHRTF_Surround2 = (float *)malloc(copySize);
	memcpy(CorredHRTF_Surround2, pFrameImpulse + (hrtfLenPerChannel << 2), copySize);
	float *CorredHRTFCrossfeed = (float *)malloc(copySize);
	memcpy(CorredHRTFCrossfeed, pFrameImpulse + ((hrtfLenPerChannel << 2) << 1), copySize);
	drflac_close(pFlac);
	free(pFrameImpulse);

	float *ptr1[4] = { jdsp->blobsCh1[0], jdsp->blobsCh2[0], jdsp->blobsCh3[0], jdsp->blobsCh4[0] };
	JamesDSPOfflineResampling(CorredHRTFCrossfeed, tmpBuf, hrtfLenPerChannel, outLen, channelsBlobsShort, ratio);
	free(CorredHRTFCrossfeed);
	channel_splitFloat(tmpBuf, outLen, ptr1, channelsBlobsShort);
	float *ptr2[4] = { jdsp->blobsCh1[1], jdsp->blobsCh2[1], jdsp->blobsCh3[1], jdsp->blobsCh4[1] };
	memset(tmpBuf, 0, outLen * channelsBlobsShort * sizeof(float));
	JamesDSPOfflineResampling(CorredHRTF_Surround1, tmpBuf, hrtfLenPerChannel, outLen, channelsBlobsShort, ratio);
	free(CorredHRTF_Surround1);
	channel_splitFloat(tmpBuf, outLen, ptr2, channelsBlobsShort);
	float *ptr3[4] = { jdsp->blobsCh1[2], jdsp->blobsCh2[2], jdsp->blobsCh3[2], jdsp->blobsCh4[2] };
	memset(tmpBuf, 0, outLen * channelsBlobsShort * sizeof(float));
	JamesDSPOfflineResampling(CorredHRTF_Surround2, tmpBuf, hrtfLenPerChannel, outLen, channelsBlobsShort, ratio);
	free(CorredHRTF_Surround2);
	channel_splitFloat(tmpBuf, outLen, ptr3, channelsBlobsShort);
	free(tmpBuf);

	pFlac = drflac_open_memory((void *)CCConv, compressedLen_CCConv, 0);
	ratio = targetFs / (double)(pFlac->sampleRate);
	totalSmps = pFlac->totalPCMFrameCount * (size_t)pFlac->channels;
	pFrameImpulse = (float *)malloc(totalSmps * sizeof(float));
	sampleCount = pFlac->totalPCMFrameCount;
	drflac_read_pcm_frames_f32(pFlac, totalSmps, pFrameImpulse);
	drflac_close(pFlac);
	outLen = (int)ceil(sampleCount * ratio);
	if (jdsp->hrtfblobsResampled[0])
		for (i = 0; i < 4; i++)
			free(jdsp->hrtfblobsResampled[i]);
	for (i = 0; i < 4; i++)
		jdsp->hrtfblobsResampled[i] = (float *)malloc(outLen * sizeof(float));
	jdsp->frameLenSVirResampled = outLen;
	tmpBuf = (float *)malloc(outLen * channelsBlobsShort * sizeof(float));
	memset(tmpBuf, 0, outLen * channelsBlobsShort * sizeof(float));
	JamesDSPOfflineResampling(pFrameImpulse, tmpBuf, sampleCount, outLen, channelsBlobsShort, ratio);
	free(pFrameImpulse);
	channel_splitFloat(tmpBuf, outLen, jdsp->hrtfblobsResampled, channelsBlobsShort);
	free(tmpBuf);
	for (i = 0; i < outLen; i++)
	{
		jdsp->hrtfblobsResampled[0][i] = (float)((double)jdsp->hrtfblobsResampled[0][i] * ccconv1Gain);
		jdsp->hrtfblobsResampled[1][i] = (float)((double)jdsp->hrtfblobsResampled[1][i] * ccconv2Gain);
		jdsp->hrtfblobsResampled[2][i] = (float)((double)jdsp->hrtfblobsResampled[2][i] * ccconv3Gain);
		jdsp->hrtfblobsResampled[3][i] = (float)((double)jdsp->hrtfblobsResampled[3][i] * ccconv4Gain);
	}
}
