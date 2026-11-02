#include <string.h>
#include <stdlib.h>
#include <errno.h>
#include <math.h>
#include <float.h>
#include "../jdsp_header.h"
double SOS_DF2Process(DirectForm2 *df2, double x1)
{
	double w1 = x1 - df2->a1*df2->v1L - df2->a2*df2->v2L;
	double y1 = df2->b0*w1 + df2->b1*df2->v1L + df2->b2*df2->v2L;
	df2->v2L = df2->v1L;
	df2->v1L = w1;
	return y1;
}
void SOS_DF2_StereoProcess(DirectForm2 *df2, double x1, double x2, double *Out_y1, double *Out_y2)
{
	double w1 = x1 - df2->a1*df2->v1L - df2->a2*df2->v2L;
	double y1 = df2->b0*w1 + df2->b1*df2->v1L + df2->b2*df2->v2L;
	df2->v2L = df2->v1L;
	df2->v1L = w1;
	*Out_y1 = y1;
	double w2 = x2 - df2->a1*df2->v1R - df2->a2*df2->v2R;
	double y2 = df2->b0*w2 + df2->b1*df2->v1R + df2->b2*df2->v2R;
	df2->v2R = df2->v1R;
	df2->v1R = w2;
	*Out_y2 = y2;
}

#include "DdcProcessor.h"
#ifdef DSP_FUSION_TEST_ALLOCATIONS
static int ddcAllocationFailure = 0;
void DdcFailFileAllocationForTest(int step) { ddcAllocationFailure = step; }
#endif
static void *ddcAllocate(size_t count, size_t size, int zero)
{
#ifdef DSP_FUSION_TEST_ALLOCATIONS
    if (ddcAllocationFailure > 0 && --ddcAllocationFailure == 0) return NULL;
#endif
    return zero ? calloc(count,size) : malloc(count*size);
}
// File preparation runs on the control thread. Nothing is released until both
// coefficient banks and the original text have been prepared successfully.
int DDCParser(char *text, DirectForm2 **bank441, DirectForm2 **bank480)
{
    if (!text || !bank441 || !bank480) return 0;
    size_t length = strnlen(text, DDC_MAX_FILE_BYTES + 1);
    double c441[DDC_MAX_SOS * 5], c480[DDC_MAX_SOS * 5];
    size_t count = 0;
    if (!DdcParse(text, length, c441, c480, &count)) return 0;
    DirectForm2 *a = (DirectForm2*)ddcAllocate(count, sizeof(DirectForm2),1);
    DirectForm2 *b = (DirectForm2*)ddcAllocate(count, sizeof(DirectForm2),1);
    if (!a || !b) { free(a); free(b); return 0; }
    for (size_t i = 0; i < count; ++i) {
        a[i].b0 = c441[i*5]; a[i].b1 = c441[i*5+1]; a[i].b2 = c441[i*5+2]; a[i].a1 = c441[i*5+3]; a[i].a2 = c441[i*5+4];
        b[i].b0 = c480[i*5]; b[i].b1 = c480[i*5+1]; b[i].b2 = c480[i*5+2]; b[i].a1 = c480[i*5+3]; b[i].a2 = c480[i*5+4];
    }
    *bank441 = a; *bank480 = b; return (int)count;
}
void DDCConstructor(JamesDSPLib *jdsp)
{
    memset(&jdsp->vdcFl, 0, sizeof(jdsp->vdcFl));
    jdsp->vdcFl.professional = DdcCreate();
    DdcConfigure(jdsp->vdcFl.professional, (uint32_t)jdsp->fs);
    DdcHostRate(jdsp->vdcFl.professional, (uint32_t)jdsp->trueSampleRate);
}
void DDCDestructor(JamesDSPLib *jdsp)
{
    free(jdsp->vdcFl.sos441); free(jdsp->vdcFl.sos480);
    free(jdsp->vdcFl.oldFile); DdcDestroy(jdsp->vdcFl.professional);
    memset(&jdsp->vdcFl, 0, sizeof(jdsp->vdcFl));
}
int DDCRefresh(JamesDSPLib *jdsp, char *unused)
{
    (void)unused;
    // Keep legacy banks exact at their source rates. At other rates the
    // professional processor owns an inverse-BLT mapped bank; a NULL legacy
    // pointer therefore does not mean the working DDC kernel is unavailable.
    if (jdsp->fs == 44100.0f) jdsp->vdcFl.sosPointer = jdsp->vdcFl.sos441;
    else if (jdsp->fs == 48000.0f) jdsp->vdcFl.sosPointer = jdsp->vdcFl.sos480;
    else jdsp->vdcFl.sosPointer = NULL;
    jdsp->vdcFl.usedSOSCount = jdsp->vdcFl.sosPointer ? jdsp->vdcFl.fileSOSCount : 0;
    if (jdsp->vdcFl.sosPointer)
        for (int i = 0; i < jdsp->vdcFl.usedSOSCount; ++i) {
            jdsp->vdcFl.sosPointer[i].v1L = jdsp->vdcFl.sosPointer[i].v2L = 0;
            jdsp->vdcFl.sosPointer[i].v1R = jdsp->vdcFl.sosPointer[i].v2R = 0;
        }
    DdcConfigure(jdsp->vdcFl.professional, (uint32_t)jdsp->fs);
    DdcHostRate(jdsp->vdcFl.professional, (uint32_t)jdsp->trueSampleRate);
    if (jdsp->vdcFl.professional) {
        int32_t status[DDC_RUNTIME_WORDS];
        DdcStatus(jdsp->vdcFl.professional, status);
        return !status[50] && status[61] > 0 && (status[5] || status[60]);
    }
    return jdsp->vdcFl.usedSOSCount > 0;
}
int DDCEnable(JamesDSPLib *jdsp, char enable)
{
    if (!enable) { DDCDisable(jdsp); return 1; }
    if (jdsp->ddcForceRefresh) {
        DDCRefresh(jdsp, jdsp->vdcFl.oldFile); jdsp->ddcForceRefresh = 0;
    }
    if (jdsp->vdcFl.professional) {
        // Binding the accepted file prepares its mapped kernel on this control
        // thread. A valid warming handoff is also an enabled DDC, not a failure.
        int32_t status[DDC_RUNTIME_WORDS];
        DdcEnabled(jdsp->vdcFl.professional, 1);
        DdcStatus(jdsp->vdcFl.professional, status);
        if (status[50] || status[61] <= 0 || (!status[5] && !status[60])) {
            jdsp->ddcEnabled = 0; DdcEnabled(jdsp->vdcFl.professional, 0); return -1;
        }
        jdsp->ddcEnabled = 1; return 1;
    }
    if (!jdsp->vdcFl.usedSOSCount || !jdsp->vdcFl.sosPointer) {
        jdsp->ddcEnabled = 0; DdcEnabled(jdsp->vdcFl.professional, 0); return -1;
    }
    jdsp->ddcEnabled = 1; DdcEnabled(jdsp->vdcFl.professional, 1); return 1;
}
void DDCDisable(JamesDSPLib *jdsp)
{
    jdsp->ddcEnabled = 0; DdcEnabled(jdsp->vdcFl.professional, 0);
}
int DDCStringParser(JamesDSPLib *jdsp, char *text)
{
    if (!text) return -1;
    const size_t length = strnlen(text, DDC_MAX_FILE_BYTES + 1);
    if (!length || length > DDC_MAX_FILE_BYTES) { DdcFileError(jdsp->vdcFl.professional,1); return -1; }
    jdsp_lock(jdsp);
    if (jdsp->vdcFl.oldFile && !strcmp(jdsp->vdcFl.oldFile, text)) { DdcFileError(jdsp->vdcFl.professional,0); jdsp_unlock(jdsp); return 0; }
    DirectForm2 *a = NULL, *b = NULL;
    const int count = DDCParser(text, &a, &b);
    char *copy = count ? (char*)ddcAllocate(length+1,1,0) : NULL;
    if (!count || !copy) { free(a); free(b); DdcFileError(jdsp->vdcFl.professional,1); jdsp_unlock(jdsp); return -1; }
    memcpy(copy, text, length+1);
    double c441[DDC_MAX_SOS*5], c480[DDC_MAX_SOS*5];
    for (int i=0;i<count;++i) {
        c441[i*5]=a[i].b0; c441[i*5+1]=a[i].b1; c441[i*5+2]=a[i].b2; c441[i*5+3]=a[i].a1; c441[i*5+4]=a[i].a2;
        c480[i*5]=b[i].b0; c480[i*5+1]=b[i].b1; c480[i*5+2]=b[i].b2; c480[i*5+3]=b[i].a1; c480[i*5+4]=b[i].a2;
    }
    if (jdsp->vdcFl.professional && !DdcFile(jdsp->vdcFl.professional,c441,c480,(size_t)count,text,length)) {
        free(a); free(b); free(copy); DdcFileError(jdsp->vdcFl.professional,2); jdsp_unlock(jdsp); return -1;
    }
    DirectForm2 *old441=jdsp->vdcFl.sos441, *old480=jdsp->vdcFl.sos480;
    char *oldText=jdsp->vdcFl.oldFile;
    jdsp->vdcFl.sos441=a; jdsp->vdcFl.sos480=b; jdsp->vdcFl.oldFile=copy; jdsp->vdcFl.fileSOSCount=count;
    if (jdsp->fs==44100.0f) jdsp->vdcFl.sosPointer=a;
    else if (jdsp->fs==48000.0f) jdsp->vdcFl.sosPointer=b;
    else jdsp->vdcFl.sosPointer=NULL;
    jdsp->vdcFl.usedSOSCount=jdsp->vdcFl.sosPointer?count:0;
    jdsp->ddcForceRefresh=0;
    free(old441); free(old480); free(oldText);
    jdsp_unlock(jdsp); return 1;
}
void DDCProcess(JamesDSPLib *jdsp, size_t n)
{
    if (jdsp->vdcFl.professional) {
        DdcProcess(jdsp->vdcFl.professional, jdsp->tmpBuffer[0], jdsp->tmpBuffer[1], n); return;
    }
    if (jdsp->vdcFl.usedSOSCount)
        for (size_t i = 0; i < n; i++) {
            double sampleOutL = (double)jdsp->tmpBuffer[0][i], sampleOutR = (double)jdsp->tmpBuffer[1][i];
            for (int j = 0; j < jdsp->vdcFl.usedSOSCount; j++)
                SOS_DF2_StereoProcess(&jdsp->vdcFl.sosPointer[j], sampleOutL, sampleOutR, &sampleOutL, &sampleOutR);
            jdsp->tmpBuffer[0][i] = (float)sampleOutL; jdsp->tmpBuffer[1][i] = (float)sampleOutR;
        }
}
