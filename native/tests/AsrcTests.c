#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "jdsp/jdsp_header.h"

static void guardedStreaming(JamesDSPLib *dsp)
{
    const size_t blocks[]={1,2,17,127,128,129,255,511,512,513,1025};
    const double ratio=dsp->fs/dsp->trueSampleRate;
    const size_t internal=(size_t)ceil(dsp->blockSizeMax*ratio);
    const size_t returned=(size_t)ceil(internal/ratio);
    const size_t stage=internal>returned?internal:returned;
    float *original[6],*guarded[6];size_t capacity[6];
    const size_t guard=16;const float sentinel=12345.25f;
    for(size_t i=0;i<6;++i)original[i]=dsp->tmpBuffer[i];
    for(size_t i=0;i<5;++i)capacity[i]=(size_t)(original[i+1]-original[i]);
    capacity[5]=dsp->pw2BlockMemSize;
    assert(capacity[0]>=dsp->blockSizeMax&&capacity[1]>=dsp->blockSizeMax);
    if(dsp->enableASRC) {
        for(size_t i=0;i<4;++i)assert(capacity[i]>=stage);
        assert(capacity[4]>=returned&&capacity[5]>=returned);
        assert((capacity[4]&(capacity[4]-1))==0); // Ring size must remain a power of two.
    }
    for(size_t i=0;i<6;++i) {
        guarded[i]=(float*)malloc((capacity[i]+2*guard)*sizeof(float));assert(guarded[i]);
        for(size_t k=0;k<capacity[i]+2*guard;++k)guarded[i][k]=sentinel;
        memset(guarded[i]+guard,0,capacity[i]*sizeof(float));dsp->tmpBuffer[i]=guarded[i]+guard;
    }
    float input[2050],output[2050];unsigned long long position=0;
    for(unsigned repeat=0;repeat<4;++repeat) for(size_t index=0;index<sizeof(blocks)/sizeof(blocks[0]);++index) {
        const size_t n=blocks[index];if(n>dsp->blockSizeMax)continue;
        for(size_t i=0;i<n;++i)input[2*i]=input[2*i+1]=.1f*sinf((float)(2*3.141592653589793*331*(position+i)/dsp->trueSampleRate));
        dsp->processFloatMultiplexd(dsp,input,output,n);position+=n;
        for(size_t i=0;i<2*n;++i)assert(isfinite(output[i]));
        for(size_t i=0;i<6;++i)for(size_t k=0;k<guard;++k) {
            assert(guarded[i][k]==sentinel);
            assert(guarded[i][guard+capacity[i]+k]==sentinel);
        }
    }
    for(size_t i=0;i<6;++i){dsp->tmpBuffer[i]=original[i];free(guarded[i]);}
}
void runAsrcTests(void)
{
    const unsigned rates[]={8000,11025,16000,22050,32000,44100,48000,96000,192000};
    JamesDSPLib *dsp=(JamesDSPLib*)calloc(1,sizeof(JamesDSPLib));assert(dsp);
    for(size_t i=0;i<sizeof(rates)/sizeof(rates[0]);++i) {
        JamesDSPInit(dsp,129,(float)rates[i]);guardedStreaming(dsp); // Initial allocation.
        JamesDSPReallocateBlock(dsp,1025);guardedStreaming(dsp); // Audio block growth.
        JamesDSPSetSampleRate(dsp,48000.f,0);guardedStreaming(dsp);
        JamesDSPSetSampleRate(dsp,(float)rates[i],0);guardedStreaming(dsp); // Live rate change.
        JamesDSPSetSampleRate(dsp,44100.f,0);guardedStreaming(dsp);
        JamesDSPFree(dsp);
    }
    free(dsp);
    puts("ASRC buffers: six per-channel canaries, initialization/growth/reconfiguration, tiny and 512+1 blocks, 8/11.025/16/22.05/32/44.1/48/96/192 kHz round trips passed.");
}
