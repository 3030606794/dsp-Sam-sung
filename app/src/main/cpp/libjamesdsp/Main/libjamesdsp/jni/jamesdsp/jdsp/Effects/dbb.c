#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <float.h>
#include "../jdsp_header.h"
static void fht16(float A[16])
{
	float alpha, beta, beta2, alpha1, alpha2, y1, y2, y3;
	alpha = A[0];
	beta = A[1];
	beta2 = A[2];
	alpha1 = A[3];
	alpha2 = alpha + beta;
	y1 = alpha - beta;
	y2 = beta2 + alpha1;
	y3 = beta2 - alpha1;
	A[0] = alpha2 + y2;
	A[2] = alpha2 - y2;
	A[1] = y1 + y3;
	A[3] = y1 - y3;
	alpha = A[4];
	beta = A[5];
	beta2 = A[6];
	alpha1 = A[7];
	alpha2 = alpha + beta;
	y1 = alpha - beta;
	y2 = beta2 + alpha1;
	y3 = beta2 - alpha1;
	A[4] = alpha2 + y2;
	A[6] = alpha2 - y2;
	A[5] = y1 + y3;
	A[7] = y1 - y3;
	alpha = A[8];
	beta = A[9];
	beta2 = A[10];
	alpha1 = A[11];
	alpha2 = alpha + beta;
	y1 = alpha - beta;
	y2 = beta2 + alpha1;
	y3 = beta2 - alpha1;
	A[8] = alpha2 + y2;
	A[10] = alpha2 - y2;
	A[9] = y1 + y3;
	A[11] = y1 - y3;
	alpha = A[12];
	beta = A[13];
	beta2 = A[14];
	alpha1 = A[15];
	alpha2 = alpha + beta;
	y1 = alpha - beta;
	y2 = beta2 + alpha1;
	y3 = beta2 - alpha1;
	A[12] = alpha2 + y2;
	A[14] = alpha2 - y2;
	A[13] = y1 + y3;
	A[15] = y1 - y3;
	alpha = A[0];
	beta = A[4];
	A[0] = alpha + beta;
	A[4] = alpha - beta;
	alpha = A[2];
	beta = A[6];
	A[2] = alpha + beta;
	A[6] = alpha - beta;
	alpha = A[1];
	beta = 0.707106769f*(A[5] + A[7]);
	beta2 = 0.707106769f*(A[5] - A[7]);
	A[1] = alpha + beta;
	A[5] = alpha - beta;
	alpha = A[3];
	A[3] = alpha + beta2;
	A[7] = alpha - beta2;
	alpha = A[8];
	beta = A[12];
	A[8] = alpha + beta;
	A[12] = alpha - beta;
	alpha = A[10];
	beta = A[14];
	A[10] = alpha + beta;
	A[14] = alpha - beta;
	alpha = A[9];
	beta = 0.707106769f*(A[13] + A[15]);
	beta2 = 0.707106769f*(A[13] - A[15]);
	A[9] = alpha + beta;
	A[13] = alpha - beta;
	alpha = A[11];
	A[11] = alpha + beta2;
	A[15] = alpha - beta2;
	alpha = A[0];
	beta = A[8];
	A[0] = alpha + beta;
	A[8] = alpha - beta;
	alpha = A[4];
	beta = A[12];
	A[4] = alpha + beta;
	A[12] = alpha - beta;
	alpha1 = A[1];
	alpha2 = A[7];
	beta = A[9] * 0.923879504f + A[15] * 0.382683426f;
	beta2 = A[9] * 0.382683426f - A[15] * 0.923879504f;
	A[1] = alpha1 + beta;
	A[9] = alpha1 - beta;
	A[7] = alpha2 + beta2;
	A[15] = alpha2 - beta2;
	alpha1 = A[10] * 0.707106769f;
	alpha2 = A[14] * 0.707106769f;
	beta = alpha1 + alpha2;
	beta2 = alpha1 - alpha2;
	alpha1 = A[2];
	alpha2 = A[6];
	A[2] = alpha1 + beta;
	A[10] = alpha1 - beta;
	A[6] = alpha2 + beta2;
	A[14] = alpha2 - beta2;
	alpha1 = A[3];
	alpha2 = A[5];
	beta = A[11] * 0.382683426f + A[13] * 0.923879504f;
	beta2 = A[11] * 0.923879504f - A[13] * 0.382683426f;
	A[3] = alpha1 + beta;
	A[11] = alpha1 - beta;
	A[5] = alpha2 + beta2;
	A[13] = alpha2 - beta2;
}
void integerDelayLineDoubleP_clear(integerDelayLine *delayLine)
{
	for (unsigned int i = 0; i < delayLine->allocateLen; i++)
		delayLine->inputs[i] = 0;
}
void integerDelayLineInit(integerDelayLine *delayLine, unsigned int allocateLen)
{
	if (allocateLen > 1024)
		allocateLen = 1024;
	delayLine->allocateLen = allocateLen;
	integerDelayLineDoubleP_clear(delayLine);
	delayLine->inPoint = 0;
	delayLine->outPoint = delayLine->allocateLen >> 1;
}
void integerDelayLine_setDelay(integerDelayLine *delayLine, unsigned int lag)
{
	if (lag > delayLine->allocateLen - 1)
		delayLine->outPoint = delayLine->inPoint + 1;
	else
		delayLine->outPoint = delayLine->inPoint - lag;
	while (delayLine->outPoint < 0)
		delayLine->outPoint += delayLine->allocateLen;
}
float integerDelayLineProcess(integerDelayLine *delayLine, float sample)
{
	delayLine->inputs[delayLine->inPoint++] = sample;
	if (delayLine->inPoint == delayLine->allocateLen)
		delayLine->inPoint -= delayLine->allocateLen;
	float lastOutput = delayLine->inputs[delayLine->outPoint++];
	if (delayLine->outPoint >= delayLine->allocateLen)
		delayLine->outPoint -= delayLine->allocateLen;
	return lastOutput;
}
//#define DEBUG_DBB
#ifdef DEBUG_DBB
extern FILE *tele;
extern FILE *tele2;
#endif
enum SVFType {
	SVFLowpass = 0,
	SVFBandpass,
	SVFHighpass,
	SVFUnitGainBandpass,
	SVFPeak,
	SVFNotch,
	SVFAllpass
};
void InitStateVariable2ndOrder(StateVariable2ndOrder *svf)
{
	svf->filterType = SVFPeak;
	svf->gCoeff = 1.0f;
	svf->RCoeff = 1.0f;
	svf->KCoeff = 0.0f;
	svf->precomputeCoeff1 = 0.0f;
	svf->precomputeCoeff2 = 0.0f;
	svf->precomputeCoeff3 = 0.0f;
	svf->precomputeCoeff4 = 0.0f;
	svf->z1_A = svf->z2_A = 0.0f;
}
const double resonanceToQ(const double resonance)
{
	return 1.0 / (2.0 * (1.0 - resonance));
}
static inline void refreshStateVariable2ndOrder(StateVariable2ndOrder *svf, const double fs, const double cutoffFreq, const double Q, const double shelfGain)
{
	const double T = 1.0 / fs;
	const double wa = (2.0 / T) * tan((cutoffFreq * 2.0 * M_PI) * T / 2.0);
	const double gCoeff = wa * T / 2.0;
	const double RCoeff = 1.0 / (2.0 * Q);
	const double KCoeff = shelfGain - 1.0;
	svf->gCoeff = (float)gCoeff;
	svf->RCoeff = (float)RCoeff;
	svf->KCoeff = (float)KCoeff;
	svf->precomputeCoeff1 = (float)(2.0 * RCoeff + gCoeff);
	svf->precomputeCoeff2 = (float)(1.0 / (1.0 + (2.0 * RCoeff * gCoeff) + gCoeff * gCoeff));
	svf->precomputeCoeff3 = (float)(2.0 * RCoeff);
	svf->precomputeCoeff4 = (float)(4.0 * RCoeff);
}
float ProcessStateVariable2ndOrder(StateVariable2ndOrder *svf, const float x)
{
	const float HP = (x - svf->precomputeCoeff1 * svf->z1_A - svf->z2_A) * svf->precomputeCoeff2;
	const float BP = HP * svf->gCoeff + svf->z1_A;
	const float LP = BP * svf->gCoeff + svf->z2_A;
	const float UBP = svf->precomputeCoeff3 * BP;
	const float Peak = x + UBP * svf->KCoeff;
	const float Notch = x - UBP;
	const float AP = x - svf->precomputeCoeff4 * BP;
	svf->z1_A = svf->gCoeff * HP + BP;
	svf->z2_A = svf->gCoeff * BP + LP;
	switch (svf->filterType)
	{
	case SVFLowpass:
		return LP;
		break;
	case SVFBandpass:
		return BP;
		break;
	case SVFHighpass:
		return HP;
		break;
	case SVFUnitGainBandpass:
		return UBP;
		break;
	case SVFPeak:
		return Peak;
		break;
	case SVFNotch:
		return Notch;
		break;
	case SVFAllpass:
		return AP;
		break;
	default:
		return 0.0f;
		break;
	}
}
static inline void ProcessStateVariable2ndOrderStereo(StateVariable2ndOrder *svf0, StateVariable2ndOrder *svf1, const float x1, const float x2, float *y1, float *y2)
{
	const float HPL = (x1 - svf0->precomputeCoeff1 * svf0->z1_A - svf0->z2_A) * svf0->precomputeCoeff2;
	const float BPL = HPL * svf0->gCoeff + svf0->z1_A;
	const float LPL = BPL * svf0->gCoeff + svf0->z2_A;
	const float UBPL = svf0->precomputeCoeff3 * BPL;
	const float PeakL = x1 + UBPL * svf0->KCoeff;
	const float NotchL = x1 - UBPL;
	const float APL = x1 - svf0->precomputeCoeff4 * BPL;
	svf0->z1_A = svf0->gCoeff * HPL + BPL;
	svf0->z2_A = svf0->gCoeff * BPL + LPL;
	const float HPR = (x2 - svf0->precomputeCoeff1 * svf1->z1_A - svf1->z2_A) * svf0->precomputeCoeff2;
	const float BPR = HPR * svf0->gCoeff + svf1->z1_A;
	const float LPR = BPR * svf0->gCoeff + svf1->z2_A;
	const float UBPR = svf0->precomputeCoeff3 * BPR;
	const float PeakR = x2 + UBPR * svf0->KCoeff;
	const float NotchR = x2 - UBPR;
	const float APR = x2 - svf0->precomputeCoeff4 * BPR;
	svf1->z1_A = svf0->gCoeff * HPR + BPR;
	svf1->z2_A = svf0->gCoeff * BPR + LPR;
	switch (svf0->filterType)
	{
	case SVFLowpass:
		*y1 = LPL;
		*y2 = LPR;
		break;
	case SVFBandpass:
		*y1 = BPL;
		*y2 = BPR;
		break;
	case SVFHighpass:
		*y1 = HPL;
		*y2 = HPR;
		break;
	case SVFUnitGainBandpass:
		*y1 = UBPL;
		*y2 = UBPR;
		break;
	case SVFPeak:
		*y1 = PeakL;
		*y2 = PeakR;
		break;
	case SVFNotch:
		*y1 = NotchL;
		*y2 = NotchR;
		break;
	case SVFAllpass:
		*y1 = APL;
		*y2 = APR;
		break;
	default:
		*y1 = 0.0f;
		*y2 = 0.0f;
		break;
	}
}
static void DBBCoefficients(DBB *dbb)
{
    // The envelope updates once per decimated sample, not at the audio rate.
    const double dt = dbb->downsampler.factor / dbb->fs;
    if (dbb->advanced)
    {
        dbb->maxSmoothingFactor = (float)-expm1(-dt / (dbb->trackingMs * .001));
        dbb->gainSmoothingFactor = (float)-expm1(-dt / (dbb->riseMs * .001));
        dbb->fallSmoothingFactor = (float)-expm1(-dt / (dbb->fallMs * .001));
    }
    else
    {
        // Preserve the legacy effective response until the advanced packet is used.
        dbb->maxSmoothingFactor = (float)-expm1(-1.0 / (.0005 * dbb->fs));
        dbb->gainSmoothingFactor = (float)-expm1(-1.0 / (.002 * dbb->fs));
        dbb->fallSmoothingFactor = dbb->gainSmoothingFactor;
    }
    dbb->minusmaxSmoothingFactor = 1.f - dbb->maxSmoothingFactor;
    dbb->minusgainSmoothingFactor = 1.f - dbb->gainSmoothingFactor;
}

static void DBBParam(DBB *dbb, double fs, float maxG)
{
    if (!isfinite(fs) || fs < 8000 || fs > 192000 || !isfinite(maxG)) return;
    dbb->maxGain = fmaxf(0.f, maxG);
    if (!dbb->initialized || dbb->fs != fs)
    {
        dbb->fs = fs;
        const int factor = (int)round(fs / 500.0);
        oversample_makeSmp(&dbb->downsampler, factor);
        const double analysisRate = fs / factor;
        for (int i = 0; i < 9; ++i) dbb->freq[i] = (float)(i * analysisRate / 16.0);
        memset(dbb->originalBuf, 0, sizeof(dbb->originalBuf));
        memset(dbb->delayLine, 0, sizeof(dbb->delayLine));
        memset(dbb->smoothFFTBuffer, 0, sizeof(dbb->smoothFFTBuffer));
        dbb->downsamplerPos = 0;
        dbb->smoothMaxFreq = dbb->boostdB = 0.f;
        dbb->currentQ = dbb->q;
        dbb->processed = 0;
        for (int ch = 0; ch < 2; ++ch)
        {
            InitStateVariable2ndOrder(&dbb->svf[ch]);
            integerDelayLineInit(&dbb->dL[ch], 1024);
            // Fixed legacy alignment. Changing envelope times must not clear/move audio.
            integerDelayLine_setDelay(&dbb->dL[ch], (unsigned)(factor + .002 * fs));
        }
        dbb->initialized = 1;
    }
    DBBCoefficients(dbb);
}

static float DBBMagnitudeAt(const DBB *dbb, float frequency)
{
    const float index = frequency / dbb->freq[1];
    if (index <= 0.f) return dbb->smoothFFTBuffer[0];
    if (index >= 8.f) return dbb->smoothFFTBuffer[8];
    const int bin = (int)index;
    return dbb->smoothFFTBuffer[bin] + (index - bin) *
        (dbb->smoothFFTBuffer[bin + 1] - dbb->smoothFFTBuffer[bin]);
}

static void DBBAnalyse(DBB *dbb)
{
    // fht16 expects bit-reversed input.
    static const unsigned order[16] = {0,8,4,12,2,10,6,14,1,9,5,13,3,11,7,15};
    for (unsigned i = 0; i < 16; ++i) dbb->fftBuf[order[i]] = dbb->delayLine[i];
    fht16(dbb->fftBuf);
    dbb->smoothFFTBuffer[0] = fabsf(dbb->fftBuf[0]);
    float minimum = dbb->smoothFFTBuffer[0];
    for (int i = 1; i < 9; ++i)
    {
        const int symmetric = 16 - i;
        const float real = (dbb->fftBuf[i] + dbb->fftBuf[symmetric]) * .5f;
        const float imaginary = (dbb->fftBuf[i] - dbb->fftBuf[symmetric]) * .5f;
        dbb->smoothFFTBuffer[i] = hypotf(real, imaginary);
        minimum = fminf(minimum, dbb->smoothFFTBuffer[i]);
    }
    float maximum, frequency;
    if (dbb->minFreq == 0.f && dbb->maxFreq == 250.f)
    {
        // Default bounds preserve the original nine-bin peak selection, including DC.
        maximum = dbb->smoothFFTBuffer[0];
        frequency = dbb->freq[0];
        for (int i = 1; i < 9; ++i)
            if (dbb->smoothFFTBuffer[i] > maximum)
            {
                maximum = dbb->smoothFFTBuffer[i];
                frequency = dbb->freq[i];
            }
    }
    else
    {
        // Include interpolated boundaries: even a 1 Hz interval has a valid candidate.
        maximum = DBBMagnitudeAt(dbb, dbb->minFreq);
        frequency = dbb->minFreq;
        const float upper = DBBMagnitudeAt(dbb, dbb->maxFreq);
        if (upper > maximum) { maximum = upper; frequency = dbb->maxFreq; }
        for (int i = 0; i < 9; ++i)
            if (dbb->freq[i] >= dbb->minFreq && dbb->freq[i] <= dbb->maxFreq &&
                dbb->smoothFFTBuffer[i] > maximum)
            {
                maximum = dbb->smoothFFTBuffer[i];
                frequency = dbb->freq[i];
            }
    }
    // Bounds constrain tracking, while spectral contrast retains the original reference.
    const float contrast = 20.f * log10f((maximum + FLT_EPSILON) / (minimum + FLT_EPSILON));
    const float desired = fminf(dbb->maxGain, fmaxf(0.f, contrast));
    const float smoothing = desired > dbb->boostdB ? dbb->gainSmoothingFactor : dbb->fallSmoothingFactor;
    dbb->boostdB += smoothing * (desired - dbb->boostdB);
    dbb->smoothMaxFreq += dbb->maxSmoothingFactor * (frequency - dbb->smoothMaxFreq);
    // Clamp immediately after a bound edit; never report/boost outside the requested band.
    if (dbb->advanced)
        dbb->smoothMaxFreq = fminf(dbb->maxFreq, fmaxf(dbb->minFreq, dbb->smoothMaxFreq));
    dbb->currentQ += (float)-expm1(-dbb->downsampler.factor / (.015 * dbb->fs)) * (dbb->q - dbb->currentQ);
    refreshStateVariable2ndOrder(&dbb->svf[0], dbb->fs, dbb->smoothMaxFreq, dbb->currentQ, db2mag(dbb->boostdB));
    dbb->processed = 1;
}

static void DBBProcess(DBB *dbb, float *x1, float *x2, float *y1, float *y2, size_t n)
{
    if (!dbb->initialized) return;
    int pos = dbb->downsamplerPos;
    for (size_t frame = 0; frame < n; ++frame)
    {
        const float left = x1[frame], right = x2[frame];
        dbb->originalBuf[pos++] = (left + right) * .5f;
        if (pos == dbb->downsampler.factor)
        {
            memmove(dbb->delayLine + 1, dbb->delayLine, sizeof(float) * 15);
            dbb->delayLine[0] = oversample_stepdownSmpFloat(&dbb->downsampler, dbb->originalBuf);
            DBBAnalyse(dbb);
            pos = 0;
        }
        // Apply each new coefficient at its actual analysis timestamp, independent of block size.
        ProcessStateVariable2ndOrderStereo(&dbb->svf[0], &dbb->svf[1],
            integerDelayLineProcess(&dbb->dL[0], left), integerDelayLineProcess(&dbb->dL[1], right),
            &y1[frame], &y2[frame]);
    }
    dbb->downsamplerPos = pos;
}

void BassBoostEnable(JamesDSPLib *jdsp) { jdsp->bassBoostEnabled = 1; }
void BassBoostDisable(JamesDSPLib *jdsp) { jdsp->bassBoostEnabled = 0; jdsp->dbb.processed = 0; }

void BassBoostConstructor(JamesDSPLib *jdsp)
{
    DBB *dbb = &jdsp->dbb;
    memset(dbb, 0, sizeof(*dbb));
    dbb->maxGain = 5.f;
    dbb->minFreq = 0.f;
    dbb->maxFreq = 250.f;
    dbb->q = dbb->currentQ = 2.f;
    dbb->trackingMs = 48.f;
    dbb->riseMs = dbb->fallMs = 192.f;
    InitStateVariable2ndOrder(&dbb->svf[0]);
    InitStateVariable2ndOrder(&dbb->svf[1]);
}

void BassBoostSetParam(JamesDSPLib *jdsp, float maxG)
{
    DBBParam(&jdsp->dbb, jdsp->fs, maxG);
}

int BassBoostSetAdvanced(JamesDSPLib *jdsp, const int32_t values[7])
{
    static const int32_t lower[7] = {0,0,1,50,5,5,5};
    static const int32_t upper[7] = {150,249,250,400,1000,2000,5000};
    if (!values) return 0;
    for (int i = 0; i < 7; ++i)
        if (values[i] < lower[i] || values[i] > upper[i]) return 0;
    if (values[1] >= values[2]) return 0;
    DBB *dbb = &jdsp->dbb;
    dbb->advanced = 1;
    dbb->minFreq = (float)values[1];
    dbb->maxFreq = (float)values[2];
    dbb->q = values[3] * .01f;
    dbb->trackingMs = (float)values[4];
    dbb->riseMs = (float)values[5];
    dbb->fallMs = (float)values[6];
    DBBParam(dbb, jdsp->fs, values[0] * .1f);
    return 1;
}

void BassBoostProcess(JamesDSPLib *jdsp, size_t n)
{
    DBBProcess(&jdsp->dbb, jdsp->tmpBuffer[0], jdsp->tmpBuffer[1], jdsp->tmpBuffer[0], jdsp->tmpBuffer[1], n);
}
