#ifdef DEBUG
#define TAG "EffectDSPMain"
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,TAG,__VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,TAG,__VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,TAG,__VA_ARGS__)
#include "MemoryUsage.h"
#endif
#include <string.h>
#include <stdio.h>
#include <math.h>
#include "essential.h"
// Effect section
#include "jdsp/jdsp_header.h"
#ifdef DSP_FUSION_STREAMING
#include "ConvolverControl.h"
#include "jdsp/Effects/presetCrossfeed.h"
#include "RoomReverb.h"
#include "Timbre.h"
#include "Compander.h"
#include "DdcProcessor.h"
#include "LiveprogCapture.h"
#include "EqCapture.h"
typedef struct {
    JamesDSPLib *plan;
    uint32_t channels, frames;
    int32_t crc;
    float *scratch;
} FusionConvJob;
uint32_t FusionConvJobFrames(void *opaque) {
    FusionConvJob *job=opaque;return job && job->plan ? job->plan->impulseResponseStorage.impulseLengthActual:0;
}
uint32_t FusionConvJobChannels(void *opaque) {
    FusionConvJob *job=opaque;return job && job->plan ? job->plan->impulseResponseStorage.impChannels:0;
}
void FusionConvJobInputs(void *opaque,float **left,float **right) {
    FusionConvJob *job=opaque;*left=job->scratch;*right=job->scratch+8192;
}
void FusionConvProcessJob(void *opaque,size_t n) {
    FusionConvJob *job=opaque;
    if(job && job->plan && job->plan->conv.process && n<=8192)job->plan->conv.process(job->plan,n);
}
int FusionConvPrepareJob(void *opaque) {
    FusionConvJob *job = (FusionConvJob *)opaque;
    if (!job || !job->plan) return -EINVAL;
    job->scratch=calloc(16384,sizeof(float));
    if(!job->scratch)return -ENOMEM;
    job->plan->tmpBuffer[0]=job->scratch;job->plan->tmpBuffer[1]=job->scratch+8192;
    /* CRC and FFT preparation both stay off the processing lock. */
    int32_t crc = (int32_t)0xFFFFFFFF;
    for (size_t i=0;i<(size_t)job->channels*job->frames;++i) {
        int32_t raw;memcpy(&raw,job->plan->impulseResponseStorage.impulseResponse+i,4);crc^=raw;
        for (unsigned bit=0;bit<8;++bit){int32_t mask=-(crc&1);crc=(crc>>1)^((int32_t)0xEDB88320&mask);}
    }
    job->crc=~crc;
    int result = Convolver1DLoadImpulseResponse(job->plan, job->plan->impulseResponseStorage.impulseResponse, job->channels, job->frames, 0);
    return result > 0 ? 0 : -ENOMEM;
}
void FusionConvDestroyJob(void *opaque) {
    FusionConvJob *job = (FusionConvJob *)opaque;
    if (!job) return;
    if (job->plan) {
        Convolver1DDestructor(job->plan);
        free(job->plan->impulseResponseStorage.impulseResponse);
        free(job->plan);
    }
    free(job->scratch);
    free(job);
}

#endif
typedef struct
{
	unsigned long long initializeForFirst;
	int32_t engineImpulseResponseHash;
	int32_t hashSlot[4];
	int mEnable;
	JamesDSPLib jdsp;
    float mSamplingRate;
    int formatFloatModeInt32Mode;
	char *stringEq;
	float *tempImpulseIncoming;
	float drcAtkRel[4];
	float boostingCon;
	int bbMaxGain;
	// Variables
	int numTime2Send, samplesInc, stringIndex;
	int16_t impChannels;
	int32_t impulseLengthActual, convolverNeedRefresh;
} EffectDSPMain;
typedef struct
{
	int32_t status;
	uint32_t psize;
	uint32_t vsize;
	int32_t cmd;
	int32_t data;
} reply1x4_1x4_t;
void EffectDSPMainConstructor(EffectDSPMain *dspmain)
{
	dspmain->initializeForFirst = 0;
	dspmain->tempImpulseIncoming = 0;
	dspmain->stringEq = 0;
	dspmain->boostingCon = 3;
	dspmain->bbMaxGain = 0;
	dspmain->numTime2Send = 0;
	dspmain->samplesInc = 0;
	dspmain->stringIndex = 0;
	JamesDSPInit(&dspmain->jdsp, 128, 48000.0f);
}
void EffectDSPMainDestructor(EffectDSPMain *dspmain)
{
	JamesDSPFree(&dspmain->jdsp);
	if (dspmain->tempImpulseIncoming)
		free(dspmain->tempImpulseIncoming);
	if (dspmain->stringEq)
		free(dspmain->stringEq);
#ifdef DEBUG
	LOGI("Buffer freed");
#endif
}
int32_t configure(EffectDSPMain *dspmain, void* pCmdData, effect_buffer_access_e* mAccessMode)
{
    effect_config_t *cfg = (effect_config_t*)pCmdData;
    buffer_config_t in = cfg->inputCfg;
    buffer_config_t out = cfg->outputCfg;
    /* Check that we aren't asked to do resampling. Note that audioflinger
     * always provides full setup info at initial configure time. */
#ifdef DEBUG
    	LOGI("Sample rate of In: %u and out: %u", in.samplingRate, out.samplingRate);
#endif
    if ((in.mask & EFFECT_CONFIG_SMP_RATE) && (out.mask & EFFECT_CONFIG_SMP_RATE))
    {
        if (out.samplingRate != in.samplingRate)
        {
#ifdef DEBUG
	LOGW("In/out sample rate doesn't match");
#endif
    return -EINVAL;
        }
        dspmain->mSamplingRate = (float)in.samplingRate;
    }
    if (in.mask & EFFECT_CONFIG_CHANNELS && out.mask & EFFECT_CONFIG_CHANNELS)
    {
        if (in.channels != AUDIO_CHANNEL_OUT_STEREO)
        {
#ifdef DEBUG
	LOGE("Input is non stereo signal. It's channel count is %u", in.channels);
#endif
            return -EINVAL;
        }
        if (out.channels != AUDIO_CHANNEL_OUT_STEREO)
        {
#ifdef DEBUG
	LOGE("Output is non stereo signal. It's channel count is %u", out.channels);
#endif
            return -EINVAL;
        }
    }
    else
    {
#ifdef DEBUG
	LOGE("In/out channel mask doesn't match");
#endif
    }
    if (in.mask & EFFECT_CONFIG_FORMAT)
    {
        if (in.format == AUDIO_FORMAT_PCM_FLOAT)
        	dspmain->formatFloatModeInt32Mode = 1;
        else if (in.format == AUDIO_FORMAT_PCM_32_BIT)
        	dspmain->formatFloatModeInt32Mode = 2;
        else if (in.format == AUDIO_FORMAT_PCM_24_BIT_PACKED)
        	dspmain->formatFloatModeInt32Mode = 3;
        else if (in.format == AUDIO_FORMAT_PCM_8_24_BIT)
        	dspmain->formatFloatModeInt32Mode = 4;
        else if (in.format == AUDIO_FORMAT_PCM_16_BIT)
        	dspmain->formatFloatModeInt32Mode = 0;
    }
    if (out.mask & EFFECT_CONFIG_FORMAT)
    {
        if (out.format == AUDIO_FORMAT_PCM_FLOAT)
        	dspmain->formatFloatModeInt32Mode = 1;
        else if (out.format == AUDIO_FORMAT_PCM_32_BIT)
        	dspmain->formatFloatModeInt32Mode = 2;
        else if (out.format == AUDIO_FORMAT_PCM_24_BIT_PACKED)
        	dspmain->formatFloatModeInt32Mode = 3;
        else if (out.format == AUDIO_FORMAT_PCM_8_24_BIT)
        	dspmain->formatFloatModeInt32Mode = 4;
        else if (out.format == AUDIO_FORMAT_PCM_16_BIT)
        	dspmain->formatFloatModeInt32Mode = 0;
    }
#ifdef DEBUG
	LOGW("I/O FMT = { %u, %u }", in.format, out.format);
#endif
    if (out.mask & EFFECT_CONFIG_ACC_MODE)
        *mAccessMode = (effect_buffer_access_e) out.accessMode;
    return 0;
}
int32_t EffectDSPMainCommand(EffectDSPMain *dspmain, uint32_t cmdCode, uint32_t cmdSize, void* pCmdData, uint32_t* replySize, void* pReplyData)
{
#ifdef DSP_FUSION_STREAMING
    if(cmdCode==EQ_PCM_RESET){
        if(cmdSize!=0||!replySize||!pReplyData||*replySize<4)return -EINVAL;
        EqCaptureReset(dspmain->jdsp.equalizerCapture);*(int32_t*)pReplyData=0;*replySize=4;return 0;
    }
    if(cmdCode==EFFECT_CMD_GET_PARAM && pCmdData && cmdSize==16 && ((effect_param_t*)pCmdData)->psize==4 && *(uint32_t*)((effect_param_t*)pCmdData)->data==EQ_PCM_GET){
        effect_param_t* p=pCmdData;
        if(!replySize||!pReplyData||p->vsize!=EQ_PCM_WORDS*4||*replySize<16+EQ_PCM_WORDS*4)return -EINVAL;
        effect_param_t* target=pReplyData;target->status=0;target->psize=4;target->vsize=EQ_PCM_WORDS*4;*(uint32_t*)target->data=EQ_PCM_GET;
        if(!EqCaptureRead(dspmain->jdsp.equalizerCapture,(int32_t*)(target->data+4)))return -EAGAIN;
        *replySize=16+EQ_PCM_WORDS*4;return 0;
    }
    if(cmdCode==LIVEPROG_PCM_RESET){
        if(cmdSize!=0||!replySize||!pReplyData||*replySize<4)return -EINVAL;
        LiveprogCaptureReset(dspmain->jdsp.liveprogCapture);*(int32_t*)pReplyData=0;*replySize=4;return 0;
    }
    if(cmdCode==EFFECT_CMD_GET_PARAM && pCmdData && cmdSize==16 && ((effect_param_t*)pCmdData)->psize==4 && *(uint32_t*)((effect_param_t*)pCmdData)->data==LIVEPROG_PCM_GET){
        effect_param_t* p=pCmdData;
        if(!replySize||!pReplyData||p->vsize!=LIVEPROG_PCM_WORDS*4||*replySize<16+LIVEPROG_PCM_WORDS*4)return -EINVAL;
        effect_param_t* target=pReplyData;target->status=0;target->psize=4;target->vsize=LIVEPROG_PCM_WORDS*4;*(uint32_t*)target->data=LIVEPROG_PCM_GET;
        if(!LiveprogCaptureRead(dspmain->jdsp.liveprogCapture,(int32_t*)(target->data+4)))return -EAGAIN;
        *replySize=16+LIVEPROG_PCM_WORDS*4;return 0;
    }
    if(cmdCode==EFFECT_CMD_GET_PARAM && pCmdData && cmdSize==16 && ((effect_param_t*)pCmdData)->psize==4 && *(uint32_t*)((effect_param_t*)pCmdData)->data==FUSION_CONV_PCM_GET){
        effect_param_t* p=pCmdData;
        if(!replySize||!pReplyData||p->vsize!=FUSION_CONV_PCM_WORDS*4||*replySize<16+FUSION_CONV_PCM_WORDS*4)return -EINVAL;
        effect_param_t* target=pReplyData;target->status=0;target->psize=4;target->vsize=FUSION_CONV_PCM_WORDS*4;*(uint32_t*)target->data=FUSION_CONV_PCM_GET;
        if(!FusionConvCaptureRead(dspmain->jdsp.convolverControl,(int32_t*)(target->data+4)))return -EAGAIN;
        *replySize=16+FUSION_CONV_PCM_WORDS*4;return 0;
    }
    if(cmdCode==EFFECT_CMD_GET_PARAM && pCmdData && cmdSize==16 && ((effect_param_t*)pCmdData)->psize==4 && *(uint32_t*)((effect_param_t*)pCmdData)->data==DDC_PCM_GET){
        effect_param_t* p=pCmdData;
        if(!replySize||!pReplyData||p->vsize!=DDC_PCM_WORDS*4||*replySize<16+DDC_PCM_WORDS*4)return -EINVAL;
        effect_param_t* target=pReplyData;target->status=0;target->psize=4;target->vsize=DDC_PCM_WORDS*4;*(uint32_t*)target->data=DDC_PCM_GET;
        if(!DdcCaptureRead(dspmain->jdsp.vdcFl.professional,(int32_t*)(target->data+4)))return -EAGAIN;
        *replySize=16+DDC_PCM_WORDS*4;return 0;
    }
    if(cmdCode==EFFECT_CMD_GET_PARAM && pCmdData && cmdSize==16 && ((effect_param_t*)pCmdData)->psize==4){
        effect_param_t* p=pCmdData;const uint32_t id=*(uint32_t*)p->data;
        if(id==COMPANDER_PCM_GET || id==COMPANDER_GAIN_GET){
            const uint32_t words=id==COMPANDER_PCM_GET?COMPANDER_PCM_WORDS:COMPANDER_GAIN_WORDS;
            if(!replySize||!pReplyData||p->vsize!=words*4||*replySize<16+words*4)return -EINVAL;
            effect_param_t* target=pReplyData;target->status=0;target->psize=4;target->vsize=words*4;*(uint32_t*)target->data=id;
            const int valid=id==COMPANDER_PCM_GET?CompanderCaptureRead(dspmain->jdsp.companderNative,(int32_t*)(target->data+4)):
                CompanderGainRead(dspmain->jdsp.companderNative,(int32_t*)(target->data+4));
            if(!valid)return -EAGAIN;*replySize=16+words*4;return 0;
        }
    }
    if(cmdCode==EFFECT_CMD_GET_PARAM && pCmdData && cmdSize==16 && ((effect_param_t*)pCmdData)->psize==4 && *(uint32_t*)((effect_param_t*)pCmdData)->data==TIMBRE_PCM_GET){
        effect_param_t* p=pCmdData;
        if(!replySize||!pReplyData||p->vsize!=TIMBRE_PCM_WORDS*4||*replySize<16+TIMBRE_PCM_WORDS*4)return -EINVAL;
        effect_param_t* target=pReplyData;target->status=0;target->psize=4;target->vsize=TIMBRE_PCM_WORDS*4;
        *(uint32_t*)target->data=TIMBRE_PCM_GET;
        if(!TimbreCaptureRead(dspmain->jdsp.timbre,(int32_t*)(target->data+4)))return -EAGAIN;
        *replySize=16+TIMBRE_PCM_WORDS*4;return 0;
    }
    if(cmdCode==EFFECT_CMD_GET_PARAM && pCmdData && cmdSize==16 && ((effect_param_t*)pCmdData)->psize==4 && *(uint32_t*)((effect_param_t*)pCmdData)->data==ROOM_REVERB_PCM_GET){
        effect_param_t* p=pCmdData;
        if(!replySize||!pReplyData||p->vsize!=ROOM_REVERB_PCM_WORDS*4||*replySize<16+ROOM_REVERB_PCM_WORDS*4)return -EINVAL;
        effect_param_t* target=pReplyData;target->status=0;target->psize=4;target->vsize=ROOM_REVERB_PCM_WORDS*4;
        *(uint32_t*)target->data=ROOM_REVERB_PCM_GET;
        if(!RoomReverbCaptureRead(dspmain->jdsp.roomReverb,(int32_t*)(target->data+4)))return -EAGAIN;
        *replySize=16+ROOM_REVERB_PCM_WORDS*4;return 0;
    }
    if(cmdCode==EFFECT_CMD_GET_PARAM && pCmdData && cmdSize==16 && ((effect_param_t*)pCmdData)->psize==4) {
        effect_param_t* p=pCmdData;uint32_t id=*(uint32_t*)p->data;
        if(id==131231 || id==131232) {
            unsigned words=id==131231?COMPANDER_RUNTIME_WORDS:COMPANDER_RESPONSE_WORDS;
            if(!replySize||!pReplyData||p->vsize!=words*4||*replySize<16+words*4)return -EINVAL;
            effect_param_t* out=pReplyData;out->status=0;out->psize=4;out->vsize=words*4;*(uint32_t*)out->data=id;
            if(id==131231)CompanderStatus(dspmain->jdsp.companderNative,(int32_t*)(out->data+4));
            else CompanderResponse(dspmain->jdsp.companderNative,(int32_t*)(out->data+4));
            *replySize=16+words*4;return 0;
        }
    }
    if(cmdCode==EFFECT_CMD_SET_PARAM && pCmdData && cmdSize>=16) {
        effect_param_t* p=pCmdData;uint32_t id=*(uint32_t*)p->data;
        if(p->psize==4 && id>=1430 && id<=1432) {
            if(!replySize||!pReplyData||*replySize<4)return -EINVAL;
            int valid=0;
            if(id==1430&&p->vsize==COMPANDER_WORDS*4&&cmdSize==16+COMPANDER_WORDS*4)
                valid=CompanderSet(dspmain->jdsp.companderNative,&dspmain->jdsp,(int32_t*)(p->data+4),COMPANDER_WORDS);
            if(id==1431&&p->vsize==COMPANDER_TOOLS_WORDS*4&&cmdSize==16+COMPANDER_TOOLS_WORDS*4)
                valid=CompanderTools(dspmain->jdsp.companderNative,&dspmain->jdsp,(int32_t*)(p->data+4),COMPANDER_TOOLS_WORDS);
            if(id==1432&&p->vsize==4&&cmdSize==20&&*(int32_t*)(p->data+4)==0){CompanderClear(dspmain->jdsp.companderNative);valid=1;}
            *(int32_t*)pReplyData=valid?0:-EINVAL;*replySize=4;return 0;
        }
    }
    if(cmdCode==EFFECT_CMD_GET_PARAM && pCmdData && cmdSize==16 && ((effect_param_t*)pCmdData)->psize==4){
        effect_param_t* request=pCmdData;uint32_t id=*(uint32_t*)request->data;
        if(id==131238 || id==131239){
            const uint32_t words=id==131238?DDC_RUNTIME_WORDS:DDC_RESPONSE_WORDS;
            if(!replySize||!pReplyData||*replySize<16+words*4||request->vsize!=words*4)return -EINVAL;
            effect_param_t* out=pReplyData;out->status=0;out->psize=4;out->vsize=words*4;*(uint32_t*)out->data=id;
            if(id==131238)DdcStatus(dspmain->jdsp.vdcFl.professional,(int32_t*)(out->data+4));
            else DdcResponse(dspmain->jdsp.vdcFl.professional,(int32_t*)(out->data+4));
            *replySize=16+words*4;return 0;
        }
    }
    if(cmdCode==EFFECT_CMD_SET_PARAM && pCmdData && cmdSize>=16){
        effect_param_t* p=pCmdData;uint32_t id=*(uint32_t*)p->data;
        if(p->psize==4&&id>=196648&&id<=196652){
            if(!replySize||!pReplyData||*replySize<4)return -EINVAL;
            int valid=0;
            jdsp_lock(&dspmain->jdsp);
            if(id==196648&&p->vsize==DDC_ADVANCED_WORDS*4&&cmdSize==16+DDC_ADVANCED_WORDS*4){
                const int32_t* values=(int32_t*)(p->data+4);
                valid=DdcSet(dspmain->jdsp.vdcFl.professional,values,DDC_ADVANCED_WORDS);
                if(valid)dspmain->jdsp.ddcEnabled=values[2];
            }
            if(id==196649&&p->vsize==DDC_TOOLS_WORDS*4&&cmdSize==16+DDC_TOOLS_WORDS*4)
                valid=DdcTools(dspmain->jdsp.vdcFl.professional,(int32_t*)(p->data+4),DDC_TOOLS_WORDS);
            if(id==196650&&p->vsize==4&&cmdSize==20&&*(int32_t*)(p->data+4)==0){DdcClear(dspmain->jdsp.vdcFl.professional);valid=1;}
            if(id==196651&&p->vsize==4&&cmdSize==20&&*(int32_t*)(p->data+4)==0){DDCDisable(&dspmain->jdsp);valid=1;}
            if(id==196652&&p->vsize==4&&cmdSize==20&&*(int32_t*)(p->data+4)==0){DdcOriginal(dspmain->jdsp.vdcFl.professional);valid=1;}
            jdsp_unlock(&dspmain->jdsp);
            *(int32_t*)pReplyData=valid?0:-EINVAL;*replySize=4;return 0;
        }
    }
    if(cmdCode==EFFECT_CMD_GET_PARAM && pCmdData && cmdSize>=16 && ((effect_param_t*)pCmdData)->psize==4 && *(uint32_t*)((effect_param_t*)pCmdData)->data==131227){
        if(!replySize || !pReplyData || *replySize<208 || ((effect_param_t*)pCmdData)->vsize!=192)return -EINVAL;
        effect_param_t* target=pReplyData;target->status=0;target->psize=4;target->vsize=192;*(uint32_t*)target->data=131227;
        TimbreStatus(dspmain->jdsp.timbre,(int32_t*)(target->data+4));*replySize=208;return 0;
    }
    if(cmdCode==EFFECT_CMD_SET_PARAM && pCmdData && cmdSize>=16){
        effect_param_t* p=pCmdData;uint32_t id=*(uint32_t*)p->data;
        if(p->psize==4 && id>=1427 && id<=1429){
            if(!replySize || !pReplyData || *replySize<4)return -EINVAL;
            int valid=0;
            if(id==1427 && p->vsize==128 && cmdSize==144)valid=TimbreSet(dspmain->jdsp.timbre,&dspmain->jdsp,(int32_t*)(p->data+4),32);
            if(id==1428 && p->vsize==272 && cmdSize==288)valid=TimbreTools(dspmain->jdsp.timbre,&dspmain->jdsp,(int32_t*)(p->data+4),68);
            if(id==1429 && p->vsize==4 && cmdSize==20 && *(int32_t*)(p->data+4)==0){TimbreClear(dspmain->jdsp.timbre);valid=1;}
            *(int32_t*)pReplyData=valid?0:-EINVAL;*replySize=4;return 0;
        }
    }
    if(cmdCode==EFFECT_CMD_GET_PARAM && pCmdData && cmdSize>=16 && ((effect_param_t*)pCmdData)->psize==4 && *(uint32_t*)((effect_param_t*)pCmdData)->data==131224){
        if(!replySize || !pReplyData || *replySize<144 || ((effect_param_t*)pCmdData)->vsize!=128)return -EINVAL;
        effect_param_t* target=pReplyData;target->status=0;target->psize=4;target->vsize=128;*(uint32_t*)target->data=131224;
        RoomReverbStatus(dspmain->jdsp.roomReverb,(int32_t*)(target->data+4));*replySize=144;return 0;
    }
    if(cmdCode==EFFECT_CMD_SET_PARAM && pCmdData && cmdSize>=16){
        effect_param_t* p=pCmdData;uint32_t id=*(uint32_t*)p->data;
        if(p->psize==4 && id>=1424 && id<=1426){
            if(!replySize || !pReplyData || *replySize<4)return -EINVAL;
            int valid=0;
            if(id==1424 && p->vsize==96 && cmdSize==112)valid=RoomReverbSet(dspmain->jdsp.roomReverb,&dspmain->jdsp,(int32_t*)(p->data+4),24);
            if(id==1425 && p->vsize==208 && cmdSize==224)valid=RoomReverbTools(dspmain->jdsp.roomReverb,&dspmain->jdsp,(int32_t*)(p->data+4),52);
            if(id==1426 && p->vsize==4 && cmdSize==20 && *(int32_t*)(p->data+4)==0){RoomReverbClear(dspmain->jdsp.roomReverb);valid=1;}
            *(int32_t*)pReplyData=valid?0:-EINVAL;*replySize=4;return 0;
        }
    }
    if(cmdCode==FUSION_PRESET_XF_CLEAR){
        if(!replySize || !pReplyData || *replySize<4)return -EINVAL;
        PresetCrossfeedClear(dspmain->jdsp.advXF.professional);*(int32_t*)pReplyData=0;*replySize=4;return 0;
    }
    if(cmdCode==EFFECT_CMD_GET_PARAM && pCmdData && cmdSize>=16 && ((effect_param_t*)pCmdData)->psize==4 && *(uint32_t*)((effect_param_t*)pCmdData)->data==131220){
        if(!replySize || !pReplyData || *replySize<112 || ((effect_param_t*)pCmdData)->vsize!=96)return -EINVAL;
        effect_param_t* target=pReplyData;target->status=0;target->psize=4;target->vsize=96;*(uint32_t*)target->data=131220;
        PresetCrossfeedSnapshot(dspmain->jdsp.advXF.professional,(int32_t*)(target->data+4));*replySize=112;return 0;
    }
    if(cmdCode==EFFECT_CMD_SET_PARAM && pCmdData && cmdSize>=16){
        effect_param_t* p=pCmdData;uint32_t id=*(uint32_t*)p->data;
        if(p->psize==4 && (id>=1420 && id<=1423)){
            if(!replySize || !pReplyData || *replySize<4)return -EINVAL;
            int valid=0;
            if(id==1420 && p->vsize==36 && cmdSize==52)valid=PresetCrossfeedSet(dspmain->jdsp.advXF.professional,&dspmain->jdsp,(int32_t*)(p->data+4),9);
            if(id==1421 && p->vsize==84 && cmdSize==100)valid=PresetCrossfeedTools(dspmain->jdsp.advXF.professional,&dspmain->jdsp,(int32_t*)(p->data+4),21);
            if(id==1422 && p->vsize==40 && cmdSize==56)valid=PresetCrossfeedAdvancedSet(dspmain->jdsp.advXF.professional,&dspmain->jdsp,(int32_t*)(p->data+4),10);
            if(id==1423 && p->vsize==84 && cmdSize==100)valid=PresetCrossfeedAdvancedTools(dspmain->jdsp.advXF.professional,&dspmain->jdsp,(int32_t*)(p->data+4),21);
            *(int32_t*)pReplyData=valid?0:-EINVAL;*replySize=4;return 0;
        }
    }
#endif
    if(cmdCode==FUSION_WIDTH_CLEAR) {
        if(!replySize || !pReplyData || *replySize<4)return -EINVAL;
        StereoWidthClear(&dspmain->jdsp.sterEnh.professionalWidth);*(int32_t*)pReplyData=0;*replySize=4;return 0;
    }
    if(cmdCode==EFFECT_CMD_GET_PARAM && pCmdData && cmdSize>=16 && ((effect_param_t*)pCmdData)->psize==4 && *(uint32_t*)((effect_param_t*)pCmdData)->data==131215) {
        if(!replySize || !pReplyData || *replySize<144 || ((effect_param_t*)pCmdData)->vsize!=128)return -EINVAL;
        effect_param_t* target=pReplyData;target->status=0;target->psize=4;target->vsize=128;*(uint32_t*)target->data=131215;
        StereoWidthSnapshot(&dspmain->jdsp.sterEnh.professionalWidth,(int32_t*)(target->data+4));*replySize=144;return 0;
    }
    if(cmdCode==EFFECT_CMD_GET_PARAM && pCmdData && cmdSize>=16 && ((effect_param_t*)pCmdData)->psize==4 && *(uint32_t*)((effect_param_t*)pCmdData)->data==131216) {
        if(!replySize || !pReplyData || *replySize<720 || ((effect_param_t*)pCmdData)->vsize!=704)return -EINVAL;
        effect_param_t* target=pReplyData;target->status=0;target->psize=4;target->vsize=704;*(uint32_t*)target->data=131216;
        StereoWidthAdvancedSnapshot(&dspmain->jdsp.sterEnh.professionalWidth,(int32_t*)(target->data+4));*replySize=720;return 0;
    }
    if(cmdCode==EFFECT_CMD_SET_PARAM && pCmdData && cmdSize>=16) {
        effect_param_t* p=pCmdData;uint32_t id=*(uint32_t*)p->data;
        if(p->psize==4 && (id==1410 || id==1411 || id==1412 || id==1413)) {
            if(!replySize || !pReplyData || *replySize<4)return -EINVAL;
            int valid=0;
            if(id==1410 && p->vsize==104 && cmdSize==120)valid=StereoWidthSet(&dspmain->jdsp.sterEnh.professionalWidth,(int32_t*)(p->data+4),26);
            if(id==1411 && p->vsize==4 && cmdSize==20)valid=StereoWidthAudition(&dspmain->jdsp.sterEnh.professionalWidth,*(int32_t*)(p->data+4));
            if(id==1412 && p->vsize==240 && cmdSize==256)valid=StereoWidthAdvancedSet(&dspmain->jdsp.sterEnh.professionalWidth,(int32_t*)(p->data+4),60);
            if(id==1413 && p->vsize==4 && cmdSize==20)valid=StereoWidthAdvancedAudition(&dspmain->jdsp.sterEnh.professionalWidth,*(int32_t*)(p->data+4));
            *(int32_t*)pReplyData=valid?0:-EINVAL;*replySize=4;return 0;
        }
    }
#ifdef DSP_FUSION_STREAMING
    if(cmdCode==FUSION_CONV_DRAIN) {
        if(!pCmdData || cmdSize!=sizeof(void*) || !pReplyData)return -EINVAL;
        *(void**)pCmdData=FusionConvDrain(dspmain->jdsp.convolverControl);*(int32_t*)pReplyData=0;return 0;
    }
    if(cmdCode==FUSION_CONV_REAP || cmdCode==FUSION_CONV_FORCE_REAP) {
        if(!pCmdData || cmdSize!=sizeof(void*) || !pReplyData)return -EINVAL;
        *(void**)pCmdData=FusionConvReap(dspmain->jdsp.convolverControl,cmdCode==FUSION_CONV_FORCE_REAP);
        *(int32_t*)pReplyData=FusionConvBusy(dspmain->jdsp.convolverControl)?-EAGAIN:0;return 0;
    }
    if (cmdCode == FUSION_CONV_CLEAR) {
        FusionConvClear(dspmain->jdsp.convolverControl);
        *(int32_t *)pReplyData = 0; return 0;
    }
    if (cmdCode == FUSION_CONV_TAKE || cmdCode == FUSION_CONV_COMMIT) {
        if (!pCmdData || cmdSize != sizeof(void *) || !pReplyData) return -EINVAL;
        FusionConvJob **slot = (FusionConvJob **)pCmdData;
        if (cmdCode == FUSION_CONV_TAKE) {
            if (!dspmain->tempImpulseIncoming) {*(int32_t *)pReplyData=-EINVAL;return 0;}
            FusionConvJob *job = (FusionConvJob *)calloc(1, sizeof(FusionConvJob));
            if (!job) {*(int32_t *)pReplyData=-ENOMEM;return 0;}
            job->plan = (JamesDSPLib *)calloc(1, sizeof(JamesDSPLib));
            if (!job->plan) {free(job);*(int32_t *)pReplyData=-ENOMEM;return 0;}
            job->channels=dspmain->impChannels;job->frames=dspmain->impulseLengthActual;
            job->plan->fs=dspmain->jdsp.fs;job->plan->blockSize=dspmain->jdsp.blockSize;
            job->plan->impulseResponseStorage.impulseResponse=dspmain->tempImpulseIncoming;
            job->plan->impulseResponseStorage.impChannels=job->channels;
            job->plan->impulseResponseStorage.impulseLengthActual=job->frames;
            dspmain->tempImpulseIncoming=NULL;dspmain->samplesInc=0;*slot=job;
            *(int32_t *)pReplyData=0;return 0;
        }
        FusionConvJob *job=*slot;
        if (!job || !job->plan || !job->plan->conv.process) {*(int32_t *)pReplyData=-EINVAL;return 0;}
        Convolver1D previous=dspmain->jdsp.conv;tmpIRData previousIR=dspmain->jdsp.impulseResponseStorage;
        dspmain->jdsp.conv=job->plan->conv;dspmain->jdsp.impulseResponseStorage=job->plan->impulseResponseStorage;
        job->plan->conv=previous;job->plan->impulseResponseStorage=previousIR;
        dspmain->engineImpulseResponseHash=job->crc;
        FusionConvLoaded(dspmain->jdsp.convolverControl, job->channels, job->frames, job->crc);
        if(FusionConvAttachRetired(dspmain->jdsp.convolverControl,job,job->frames,job->channels))*slot=NULL;
        *(int32_t *)pReplyData=0;return 0;
    }
    if(cmdCode==EFFECT_CMD_GET_PARAM && pCmdData && ((effect_param_t*)pCmdData)->psize==4 && *(uint32_t*)((effect_param_t*)pCmdData)->data==131214) {
        if(!replySize || !pReplyData || *replySize<96 || ((effect_param_t*)pCmdData)->vsize!=80)return -EINVAL;
        effect_param_t *target=pReplyData;target->status=0;target->psize=4;target->vsize=80;
        *(uint32_t*)target->data=131214;FusionConvAdvancedSnapshot(dspmain->jdsp.convolverControl,(int32_t*)(target->data+4));*replySize=96;return 0;
    }
    if (cmdCode == EFFECT_CMD_GET_PARAM && pCmdData && ((effect_param_t *)pCmdData)->psize==4 && *(uint32_t *)((effect_param_t *)pCmdData)->data==131213) {
        effect_param_t *target=(effect_param_t *)pReplyData;
        if (((effect_param_t *)pCmdData)->vsize!=128 || !replySize || *replySize<144) return -EINVAL;
        target->status=0;target->psize=4;target->vsize=128;*(uint32_t *)target->data=131213;
        FusionConvSnapshot(dspmain->jdsp.convolverControl, (int32_t *)(target->data+4));*replySize=144;return 0;
    }
    if (cmdCode == EFFECT_CMD_SET_PARAM && pCmdData && cmdSize>=16) {
        effect_param_t *packet=(effect_param_t *)pCmdData;
        const uint32_t id=*(uint32_t *)packet->data;
        if (packet->psize==4 && (id==1400 || id==1401 || id==1402)) {
            int okay=0;
            if (id==1400 && packet->vsize==40 && cmdSize==56)okay=FusionConvSet(dspmain->jdsp.convolverControl, (int32_t *)(packet->data+4), 10);
            if (id==1401 && packet->vsize==4 && cmdSize==20)okay=FusionConvAudition(dspmain->jdsp.convolverControl, *(int32_t *)(packet->data+4));
            if (id==1402 && packet->vsize==76 && cmdSize==92)okay=FusionConvSetAdvanced(dspmain->jdsp.convolverControl,(int32_t*)(packet->data+4),19);
            *(int32_t *)pReplyData=okay?0:-EINVAL;return 0;
        }
    }
#endif
#ifdef DEBUG
//		LOGI("Memory used: %f Mb", (float)getCurrentRSS() / 1024.0 / 1024.0);
#endif
	if (cmdCode == EFFECT_CMD_SET_CONFIG)
	{
		effect_buffer_access_e mAccessMode;
		int32_t *replyData = (int32_t *)pReplyData;
		int32_t ret = configure(dspmain, pCmdData, &mAccessMode);
		if (ret != 0)
		{
			*replyData = ret;
			return 0;
		}
		// Set sample rate
		JamesDSPSetSampleRate(&dspmain->jdsp, dspmain->mSamplingRate, 0);
#ifdef DSP_FUSION_STREAMING
        FusionConvConfigure(dspmain->jdsp.convolverControl, (uint32_t)dspmain->jdsp.fs);
#endif
		*replyData = 0;
		return 0;
	}
	if (cmdCode == EFFECT_CMD_GET_PARAM)
	{
		effect_param_t *cep = (effect_param_t *)pCmdData;
		if (cep->psize == 4 && cep->vsize == 4)
		{
			int32_t cmd = ((int32_t *)cep)[3];
			if (cmd == 131241)
			{
				if (!replySize || !pReplyData || *replySize < sizeof(reply1x4_1x4_t)) return -EINVAL;
				reply1x4_1x4_t *replyData = (reply1x4_1x4_t *)pReplyData;
				replyData->status = 0;
				replyData->psize = replyData->vsize = 4;
				replyData->cmd = cmd;
				replyData->data = LiveProgGetStatus(&dspmain->jdsp);
				*replySize = sizeof(reply1x4_1x4_t);
				return 0;
			}
			if (cmd == 131084 || cmd == 131085)
			{
				reply1x4_1x4_t *replyData = (reply1x4_1x4_t *)pReplyData;
				replyData->status = 0;
				replyData->psize = replyData->vsize = 4;
				replyData->cmd = cmd;
				const DBB *dbb = &dspmain->jdsp.dbb;
				const float value = cmd == 131084 ? dbb->smoothMaxFreq : dbb->boostdB;
				replyData->data = dspmain->jdsp.bassBoostEnabled && dbb->processed && isfinite(value) ? (int32_t)lroundf(value * 100.f) : 0;
				*replySize = sizeof(reply1x4_1x4_t);
				return 0;
			}
			if (cmd == 19998)
			{
				reply1x4_1x4_t *replyData = (reply1x4_1x4_t *)pReplyData;
				replyData->status = 0;
				replyData->psize = 4;
				replyData->vsize = 4;
				replyData->cmd = 19998;
				replyData->data = (int32_t)dspmain->initializeForFirst++;
				*replySize = sizeof(reply1x4_1x4_t);
				return 0;
			}
			if (cmd == 19999)
			{
				reply1x4_1x4_t *replyData = (reply1x4_1x4_t *)pReplyData;
				replyData->status = 0;
				replyData->psize = 4;
				replyData->vsize = 4;
				replyData->cmd = 19999;
				replyData->data = (int32_t)dspmain->jdsp.blockSize;
				*replySize = sizeof(reply1x4_1x4_t);
				return 0;
			}
			else if (cmd == 20000)
			{
				reply1x4_1x4_t *replyData = (reply1x4_1x4_t *)pReplyData;
				replyData->status = 0;
				replyData->psize = 4;
				replyData->vsize = 4;
				replyData->cmd = 20000;
				replyData->data = (int32_t)dspmain->jdsp.blockSizeMax;
				*replySize = sizeof(reply1x4_1x4_t);
				return 0;
			}
			else if (cmd == 20001)
			{
				reply1x4_1x4_t *replyData = (reply1x4_1x4_t *)pReplyData;
				replyData->status = 0;
				replyData->psize = 4;
				replyData->vsize = 4;
				replyData->cmd = 20001;
				replyData->data = (int32_t)dspmain->jdsp.fs;
				*replySize = sizeof(reply1x4_1x4_t);
				return 0;
			}
			else if (cmd == 20002)
			{
				reply1x4_1x4_t *replyData = (reply1x4_1x4_t *)pReplyData;
				replyData->status = 0;
				replyData->psize = 4;
				replyData->vsize = 4;
				replyData->cmd = 20002;
				replyData->data = (int32_t)getpid();
				*replySize = sizeof(reply1x4_1x4_t);
				return 0;
			}
			else if (cmd == 30000)
			{
				reply1x4_1x4_t *replyData = (reply1x4_1x4_t *)pReplyData;
				replyData->status = 0;
				replyData->psize = 4;
				replyData->vsize = 4;
				replyData->cmd = 30000;
				replyData->data = dspmain->hashSlot[0];
				*replySize = sizeof(reply1x4_1x4_t);
				return 0;
			}
			else if (cmd == 30001)
			{
				reply1x4_1x4_t *replyData = (reply1x4_1x4_t *)pReplyData;
				replyData->status = 0;
				replyData->psize = 4;
				replyData->vsize = 4;
				replyData->cmd = 30001;
				replyData->data = dspmain->hashSlot[1];
				*replySize = sizeof(reply1x4_1x4_t);
				return 0;
			}
			else if (cmd == 30002)
			{
				reply1x4_1x4_t *replyData = (reply1x4_1x4_t *)pReplyData;
				replyData->status = 0;
				replyData->psize = 4;
				replyData->vsize = 4;
				replyData->cmd = 30002;
				replyData->data = dspmain->hashSlot[2];
				*replySize = sizeof(reply1x4_1x4_t);
				return 0;
			}
			else if (cmd == 30003)
			{
				reply1x4_1x4_t *replyData = (reply1x4_1x4_t *)pReplyData;
				replyData->status = 0;
				replyData->psize = 4;
				replyData->vsize = 4;
				replyData->cmd = 30003;
				replyData->data = dspmain->hashSlot[3];
				*replySize = sizeof(reply1x4_1x4_t);
				return 0;
			}
		}
	}
	if (cmdCode == EFFECT_CMD_SET_PARAM)
	{
		effect_param_t *cep = (effect_param_t *)pCmdData;
		int32_t *replyData = (int32_t *)pReplyData;
		if (cep->psize == 4 && cmdSize >= sizeof(effect_param_t) + 4 && ((int32_t *)cep)[3] == 196656)
		{
			if (cep->vsize != 72 || cmdSize != sizeof(effect_param_t) + 76) { *replyData = -EINVAL; return 0; }
			const char *name = (const char *)cep->data + 4;
			const size_t length = strnlen(name, 64);
			if (!length || length == 64) { *replyData = -EINVAL; return 0; }
			for (size_t i = length; i < 64; ++i) if (name[i]) { *replyData = -EINVAL; return 0; }
			double value;
			memcpy(&value, cep->data + 68, sizeof(value));
			const int result = LiveProgSetParameter(&dspmain->jdsp, name, value);
			*replyData = result == 1 ? 0 : (result == -5 ? -ENOMEM : -EINVAL);
			return 0;
		}
		if (cep->psize == 4 && cmdSize >= sizeof(effect_param_t) + 4 && ((int32_t *)cep)[3] == 1300)
		{
			if (cep->vsize != 28 || cmdSize != sizeof(effect_param_t) + 32)
				*replyData = -EINVAL;
			else
				*replyData = BassBoostSetAdvanced(&dspmain->jdsp, (const int32_t *)(cep->data + 4)) ? 0 : -EINVAL;
			return 0;
		}
		if (cep->psize == 4 && cep->vsize == 2)
		{
			int32_t cmd = ((int32_t *)cep)[3];
			if (cmd == 128)
			{
				int reverbMode = (int)((int16_t *)cep)[8];
                if(reverbMode<0 || reverbMode>18){*replyData=-EINVAL;return 0;}
                if(!Reverb_SetParam(&dspmain->jdsp,reverbMode)){*replyData=-ENOMEM;return 0;}
#ifdef DEBUG
				LOGI("Reverb mode: %d", reverbMode);
#endif
				*replyData = 0;
				return 0;
			}
			else if (cmd == 137)
			{
				float agress = ((int16_t *)cep)[8] / 100.0f;
				StereoEnhancementSetParam(&dspmain->jdsp, agress);
#ifdef DEBUG
				LOGE("Stereo widen ag: %1.7f", agress);
#endif
				*replyData = 0;
				return 0;
			}
			else if (cmd == 188)
			{
				int16_t nMode = ((int16_t *)cep)[8];
				if (nMode < 0)
					nMode = 0;
				if (nMode > 5)
					nMode = 5;
				CrossfeedChangeMode(&dspmain->jdsp, nMode);
#ifdef DEBUG
				LOGI("Crossfeed mode: %d", nMode);
#endif
				*replyData = 0;
				return 0;
			}
			else if (cmd == 150)
			{
				float tubedrive = ((int16_t *)cep)[8] / 1000.0f;
#ifdef DEBUG
				LOGI("Tube drive: %1.7f", tubedrive);
#endif
				VacuumTubeSetGain(&dspmain->jdsp, tubedrive);
				*replyData = 0;
				return 0;
			}
			else if (cmd == 1200)
			{
				int16_t compressorEnabled = ((int16_t *)cep)[8];
				if (!compressorEnabled)
					CompressorDisable(&dspmain->jdsp);
				else
					CompressorEnable(&dspmain->jdsp, 1);
#ifdef DEBUG
				LOGE("Compressor enabled: %d", compressorEnabled);
#endif
				*replyData = 0;
				return 0;
			}
			else if (cmd == 1201)
			{
				int16_t bassBoostEnabled = ((int16_t *)cep)[8];
				if (!bassBoostEnabled)
					BassBoostDisable(&dspmain->jdsp);
				else
					BassBoostEnable(&dspmain->jdsp);
#ifdef DEBUG
				LOGE("Bass boost enabled: %d", bassBoostEnabled);
#endif
				*replyData = 0;
				return 0;
			}
			else if (cmd == 112)
			{
				float maxg = ((int16_t *)cep)[8];
#ifdef DEBUG
				LOGE("Bass boost max gain: %f", maxg);
#endif
				BassBoostSetParam(&dspmain->jdsp, maxg);
				*replyData = 0;
				return 0;
			}
			else if (cmd == 1202)
			{
				int16_t equalizerEnabled = ((int16_t *)cep)[8];
				if (!equalizerEnabled)
					MultimodalEqualizerDisable(&dspmain->jdsp);
				else
					MultimodalEqualizerEnable(&dspmain->jdsp, 1);
#ifdef DEBUG
				LOGE("FIR equalizer enabled: %d", equalizerEnabled);
#endif
				// Enable EQ
				*replyData = 0;
				return 0;
			}
			else if (cmd == 1203)
			{
				int16_t reverbEnabled = ((int16_t *)cep)[8];
				if (!reverbEnabled)
					ReverbDisable(&dspmain->jdsp);
				else
					ReverbEnable(&dspmain->jdsp);
#ifdef DEBUG
				LOGE("Reverb enabled: %d", reverbEnabled);
#endif
				*replyData = 0;
				return 0;
			}
			else if (cmd == 1204)
			{
				int16_t stereoWidenEnabled = ((int16_t *)cep)[8];
				if (!stereoWidenEnabled)
					StereoEnhancementDisable(&dspmain->jdsp);
				else
					StereoEnhancementEnable(&dspmain->jdsp);
#ifdef DEBUG
				LOGE("Stereo widen enabled: %d", stereoWidenEnabled);
#endif
				*replyData = 0;
				return 0;
			}
			else if (cmd == 1205)
			{
				int16_t convolverEnabled = ((int16_t *)cep)[8];
#ifdef DEBUG
				LOGE("Convolver enabled: %d", convolverEnabled);
#endif
				if (!convolverEnabled)
					Convolver1DDisable(&dspmain->jdsp);
				else
					Convolver1DEnable(&dspmain->jdsp);
				*replyData = 0;
				return 0;
			}
			else if (cmd == 1206)
			{
				int16_t analogueModelEnable = ((int16_t *)cep)[8];
				if (!analogueModelEnable)
					VacuumTubeDisable(&dspmain->jdsp);
				else
					VacuumTubeEnable(&dspmain->jdsp);
#ifdef DEBUG
				LOGE("Analogue modelling enabled: %d", analogueModelEnable);
#endif
				*replyData = 0;
				return 0;
			}
			else if (cmd == 1208)
			{
				int16_t bs2bEnabled = ((int16_t *)cep)[8];
				if (!bs2bEnabled)
					CrossfeedDisable(&dspmain->jdsp);
				else {
#ifdef DSP_FUSION_STREAMING
                    // Fine edits already update the active model. Repeated gate writes must
                    // not rebuild every legacy IR while the user drags a professional control.
                    if(!dspmain->jdsp.crossfeedEnabled || dspmain->jdsp.crossfeedForceRefresh)
#endif
					    CrossfeedEnable(&dspmain->jdsp, 1);
                }
#ifdef DEBUG
				LOGE("Crossfeed enabled: %d", bs2bEnabled);
#endif
                // A failed replacement may keep the old sound active; report the request failure.
                if(bs2bEnabled && (!dspmain->jdsp.crossfeedEnabled || dspmain->jdsp.crossfeedForceRefresh)) {
                    *replyData=-ENOMEM;return 0;
                }
				*replyData = 0;
				return 0;
			}
			else if (cmd == 1210)
			{
				int16_t arbitraryResponseEnabled = ((int16_t *)cep)[8];
				if (!arbitraryResponseEnabled)
					ArbitraryResponseEqualizerDisable(&dspmain->jdsp);
				else
					ArbitraryResponseEqualizerEnable(&dspmain->jdsp, 1);
#ifdef DEBUG
				LOGE("Arbitrary response eq enabled: %d", arbitraryResponseEnabled);
#endif
				*replyData = 0;
				return 0;
			}
			else if (cmd == 1212)
			{
				int16_t viperddcEnabled = ((int16_t *)cep)[8];
				int errCode = 0;
				if (!viperddcEnabled)
					DDCDisable(&dspmain->jdsp);
				else
					errCode = DDCEnable(&dspmain->jdsp, 1);
#ifdef DEBUG
				LOGE("viperddcEnabled: %d, success?: %d", viperddcEnabled, errCode);
#endif
				*replyData = 0;
				return 0;
			}
			else if (cmd == 1213)
			{
				int16_t EELEnabled = ((int16_t *)cep)[8];
				if (!EELEnabled)
					LiveProgDisable(&dspmain->jdsp);
				else
					LiveProgEnable(&dspmain->jdsp);
#ifdef DEBUG
				LOGE("Liveprog enabled: %d", EELEnabled);
#endif
				*replyData = 0;
				return 0;
			}
			else if (cmd == 10004)
			{
				// Load impulse
#ifdef DEBUG
				if (dspmain->samplesInc == dspmain->numTime2Send)
					LOGI("Buffer slices complete, chs: %d, impLen: %d", dspmain->impChannels, dspmain->impulseLengthActual);
				else
					LOGI("Ops! Buffer slices got %d missing holes", dspmain->numTime2Send - dspmain->samplesInc);
#endif
				dspmain->samplesInc = 0;
				int success = Convolver1DLoadImpulseResponse(&dspmain->jdsp, dspmain->tempImpulseIncoming, dspmain->impChannels, dspmain->impulseLengthActual, 1);
				union
				{
					int32_t raw;
					float f;
				} fltInt;
				int32_t crc = 0xFFFFFFFF;
				for (unsigned int i = 0; i < dspmain->impulseLengthActual * dspmain->impChannels; i++)
				{
					fltInt.f = dspmain->tempImpulseIncoming[i];
					crc = crc ^ fltInt.raw;
					for (int j = 7; j >= 0; j--)
					{
						int32_t mask = -(crc & 1);
						crc = (crc >> 1) ^ (0xEDB88320 & mask);
					}
				}
				if (success > 0)
					dspmain->engineImpulseResponseHash = ~crc;
				else
					Convolver1DDisable(&dspmain->jdsp);
#ifdef DSP_FUSION_STREAMING
                if (success>0)FusionConvLoaded(dspmain->jdsp.convolverControl,dspmain->impChannels,dspmain->impulseLengthActual,~crc);
#endif
				free(dspmain->tempImpulseIncoming);
				dspmain->tempImpulseIncoming = 0;
#ifdef DEBUG
				LOGI("FFT convolver errCode: %d", success);
#endif
				*replyData = success > 0 ? 0 : -ENOMEM;
				return 0;
			}
			else if (cmd == 10006)
			{
				dspmain->stringIndex = 0;
				if (dspmain->stringEq)
				{
#ifdef DEBUG
					LOGI("%s", dspmain->stringEq);
#endif
					ArbitraryResponseEqualizerStringParser(&dspmain->jdsp, dspmain->stringEq);
					free(dspmain->stringEq);
					dspmain->stringEq = 0;
#ifdef DEBUG
					LOGI("Arbitrary response initialized");
#endif
				}
				*replyData = 0;
				return 0;
			}
			else if (cmd == 10009)
			{
				const size_t ddcTransferred = (size_t)dspmain->stringIndex * 256;
				dspmain->stringIndex = 0;
				if (dspmain->stringEq)
				{
#ifdef DEBUG
					LOGI("%s", dspmain->stringEq);
#endif
					// A NUL is only transport padding; nonzero bytes after it are malformed.
                    const size_t ddcLength = strnlen(dspmain->stringEq, ddcTransferred);
                    int ddcPaddingValid = ddcTransferred > 0;
                    for (size_t i=ddcLength;i<ddcTransferred;++i)if(dspmain->stringEq[i]){ddcPaddingValid=0;break;}
                    const int ddcResult = ddcPaddingValid ? DDCStringParser(&dspmain->jdsp, dspmain->stringEq) : -1;
                    if (!ddcPaddingValid) DdcFileError(dspmain->jdsp.vdcFl.professional,1);
					free(dspmain->stringEq);
					dspmain->stringEq = 0;
					if (ddcResult < 0) { *replyData = -EINVAL; return 0; }
#ifdef DEBUG
					LOGI("DDC initialized");
#endif
				}
				*replyData = 0;
				return 0;
			}
			else if (cmd == 10010)
			{
				const size_t transferred = (size_t)dspmain->stringIndex * 256;
				dspmain->stringIndex = 0;
				int errorCode = -7;
				if (dspmain->stringEq && transferred)
				{
#ifdef DEBUG
					LOGI("%s", dspmain->stringEq);
#endif
					// Initialize EEL
					const size_t length = strnlen(dspmain->stringEq, transferred);
					int paddingValid = length > 0 && length < transferred;
					for (size_t i = length; i < transferred; ++i) if (dspmain->stringEq[i]) { paddingValid = 0; break; }
					if (paddingValid) errorCode = LiveProgStringParser(&dspmain->jdsp, dspmain->stringEq);
#ifdef DEBUG
					LOGI("Live prog error message: %s", checkErrorCode(errorCode));
#endif
				}
				free(dspmain->stringEq);
				dspmain->stringEq = 0;
				if (errorCode == -7) {
					dspmain->jdsp.eel.lastCompileResult = errorCode;
					snprintf(dspmain->jdsp.eel.lastError, sizeof(dspmain->jdsp.eel.lastError), "%s", "Malformed script transfer");
				}
				*replyData = errorCode == 1 ? 0 : -EINVAL;
				return 0;
			}
		}
		if (cep->psize == 4 && cep->vsize == 4)
		{
			int32_t cmd = ((int32_t *)cep)[3];
			if (cmd == 25000)
			{
				dspmain->hashSlot[0] = ((int32_t*)cep)[4];
#ifdef DEBUG
				LOGI("ArbEq hash: %d", dspmain->hashSlot[0]);
#endif
				*replyData = 0;
				return 0;
			}
			if (cmd == 25001)
			{
				dspmain->hashSlot[0 + 1] = ((int32_t*)cep)[4];
#ifdef DEBUG
				LOGI("DDC hash: %d", dspmain->hashSlot[0 + 1]);
#endif
				*replyData = 0;
				return 0;
			}
			if (cmd == 25002)
			{
				if (LiveProgGetStatus(&dspmain->jdsp) != 1) { *replyData = -EINVAL; return 0; }
				dspmain->hashSlot[0 + 2] = ((int32_t*)cep)[4];
#ifdef DEBUG
				LOGI("LiveProg hash: %d", dspmain->hashSlot[0 + 2]);
#endif
				*replyData = 0;
				return 0;
			}
			if (cmd == 25003)
			{
				dspmain->hashSlot[0 + 3] = ((int32_t*)cep)[4];
#ifdef DEBUG
				LOGI("Convolver app hash: %d, engine hash: %d", dspmain->hashSlot[0 + 3], dspmain->engineImpulseResponseHash);
#endif
				*replyData = 0;
				return 0;
			}
		}
		if (cep->psize == 4 && cep->vsize == 12)
		{
			int32_t cmd = ((int32_t *)cep)[3];
			if (cmd == 1500)
			{
				double limThreshold = (double)((float*)cep)[4];
				double limRelease = (double)((float*)cep)[5];
				double postgain = (double)((float*)cep)[6];
				if (limThreshold > -0.09)
					limThreshold = -0.09;
				if (limRelease < 0.15)
					limRelease = 0.15;
				if (postgain > 15.0)
					postgain = 15.0;
				if (postgain < -15.0)
					postgain = -15.0;
				JLimiterSetCoefficients(&dspmain->jdsp, limThreshold, limRelease);
				JamesDSPSetPostGain(&dspmain->jdsp, postgain);
#ifdef DEBUG
				LOGE("limThreshold: %f, limRelease: %f, postgain: %f", limThreshold, limRelease, postgain);
#endif
				*replyData = 0;
				return 0;
			}
		}
		if (cep->psize == 4 && cep->vsize == 68)
		{
			int32_t cmd = ((int32_t *)cep)[3];
			if (cmd == 115)
			{
                if(cmdSize!=84 || !replySize || !pReplyData || *replySize<4)return -EINVAL;
                const float* values=((float*)cep)+4;
                for(unsigned i=0;i<17;++i)if(!isfinite(values[i])){*replyData=-EINVAL;return 0;}
                if(values[0]<.06f||values[0]>.3f||values[1]<0||values[1]>4||values[2]<0||values[2]>3){*replyData=-EINVAL;return 0;}
                float previous=0;
                for(unsigned i=0;i<7;++i){if(values[3+i]<=previous||values[3+i]>=24000||fabsf(values[10+i])>1.2f){*replyData=-EINVAL;return 0;}previous=values[3+i];}
				float timeconstant = ((float*)cep)[4 + 0];
				int granularity = (int)roundf(((float*)cep)[4 + 1]);
				int tfresolution = (int)roundf(((float*)cep)[4 + 2]);
				float *ptrFreqAxis = &((float*)cep)[4 + 3];
				float *ptrGainAxis = ptrFreqAxis + 7;
				double param[14];
				for (int i = 0; i < 7; i++)
				{
					param[i] = (double)ptrFreqAxis[i];
					param[i + 7] = (double)ptrGainAxis[i];
				}
#ifdef DEBUG
				LOGI("Compander timeconstant = %1.8f", timeconstant);
				LOGI("Compander granularity = %d", granularity);
				LOGI("Compander tfresolution = %d", tfresolution);
				LOGI("Compander axis: ");
				for (int i = 0; i < 7; i++)
					LOGI("%1.7lf %1.7lf; ", param[i], param[i + 7]);
#endif
				CompressorSetParam(&dspmain->jdsp, timeconstant, granularity, tfresolution, 0);
				CompressorSetGain(&dspmain->jdsp, param, param + 7, 1);
				*replyData = 0;
				return 0;
			}
		}
		if (cep->psize == 4 && cep->vsize == 128)
		{
			int32_t cmd = ((int32_t *)cep)[3];
			if (cmd == 116)
			{
				int filtertype = roundf(((float*)cep)[4 + 0]);
				int interpolationMode = (((float*)cep)[4 + 1]) < 0.0f ? 0 : 1;
				float *ptrFreqAxis = &((float*)cep)[4 + 2];
				float *ptrGainAxis = ptrFreqAxis + 15;
				double param[30];
				for (int i = 0; i < 15; i++)
				{
					param[i] = (double)ptrFreqAxis[i];
					param[i + 15] = (double)ptrGainAxis[i];
				}
#ifdef DEBUG
				if (filtertype == 0)
					LOGI("filtertype: FIR Minimum phase");
				else if (filtertype == 1)
					LOGI("filtertype: IIR 4 order");
				else if (filtertype == 2)
					LOGI("filtertype: IIR 6 order");
				else if (filtertype == 3)
					LOGI("filtertype: IIR 8 order");
				else if (filtertype == 4)
					LOGI("filtertype: IIR 10 order");
				else
					LOGI("filtertype: IIR 12 order");
				LOGI("Eq axis: ");
				for (int i = 0; i < 15; i++)
					LOGI("%1.7lf %1.7lf; ", param[i], param[i + 15]);
#endif
				MultimodalEqualizerAxisInterpolation(&dspmain->jdsp, interpolationMode, filtertype, param, param + 15);
				*replyData = 0;
				return 0;
			}
		}
		if (cep->psize == 4 && cep->vsize == 8)
		{
			int32_t cmd = ((int32_t *)cep)[3];
			if (cmd == 8888) // Implemented
			{
				int32_t times = ((int32_t *)cep)[4];
				int32_t sizePerBuffer = ((int32_t *)cep)[5];
				int stringLength = times * sizePerBuffer;
#ifdef DEBUG
				LOGI("Allocate %d string length", stringLength);
#endif
				free(dspmain->stringEq);
				dspmain->stringIndex = 0;
				// A transfer may fill every byte; parsers still require a NUL.
				dspmain->stringEq = (char*)calloc((size_t)stringLength + 1, sizeof(char));
				if (!dspmain->stringEq) return -ENOMEM;
				*replyData = 0;
				return 0;
			}
		}
		if (cep->psize == 4 && cep->vsize == 16)
		{
			int32_t cmd = ((int32_t *)cep)[3];
			if (cmd == 9999) // Implemented
			{
				dspmain->impChannels = ((int32_t *)cep)[5];
				dspmain->impulseLengthActual = ((int32_t *)cep)[4] / dspmain->impChannels;
				float convGaindB = (float)((int32_t *)cep)[6] / 262144.0f;
				if (convGaindB > 50.0f)
					convGaindB = 50.0f;
				dspmain->numTime2Send = ((int32_t *)cep)[7];
				free(dspmain->tempImpulseIncoming);
				dspmain->samplesInc = 0;
				// Packet count already includes all interleaved channels.
				dspmain->tempImpulseIncoming = (float*)calloc((size_t)4096 * dspmain->numTime2Send, sizeof(float));
				if (!dspmain->tempImpulseIncoming) return -ENOMEM;
				*replyData = 0;
				return 0;
			}
		}
		if (cep->psize == 4 && cep->vsize == 16384)
		{
			int32_t cmd = ((int32_t *)cep)[3];
			if (cmd == 12000) // Implemented
			{
				memcpy(dspmain->tempImpulseIncoming + (dspmain->samplesInc++ * 4096), ((float*)cep) + 4, 4096 * sizeof(float));
				*replyData = 0;
				return 0;
			}
		}
		if (cep->psize == 4 && cep->vsize == 256)
		{
			int32_t cmd = ((int32_t *)cep)[3];
			if (cmd == 12001) // Implemented
			{
				memcpy(dspmain->stringEq + (dspmain->stringIndex++ * 256), ((char*)cep) + 16, 256 * sizeof(char));
				*replyData = 0;
				return 0;
			}
		}
		return -1;
	}
    switch (cmdCode)
    {
    case EFFECT_CMD_ENABLE:
    case EFFECT_CMD_DISABLE:
    {
        dspmain->mEnable = cmdCode == EFFECT_CMD_ENABLE;
        int32_t *replyData = (int32_t *) pReplyData;
        *replyData = 0;
        break;
    }
    case EFFECT_CMD_INIT:
    case EFFECT_CMD_SET_CONFIG:
    case EFFECT_CMD_SET_PARAM:
    case EFFECT_CMD_SET_PARAM_COMMIT:
    {
        int32_t *replyData = (int32_t *) pReplyData;
        *replyData = 0;
        break;
    }
    case EFFECT_CMD_RESET:
    case EFFECT_CMD_SET_PARAM_DEFERRED:
    case EFFECT_CMD_SET_DEVICE:
    case EFFECT_CMD_SET_AUDIO_MODE:
        break;
    case EFFECT_CMD_GET_PARAM:
    {
        effect_param_t *rep = (effect_param_t *) pReplyData;
        rep->status = -EINVAL;
        rep->psize = 0;
        rep->vsize = 0;
        *replySize = 12;
        break;
    }
    }
    return 0;
}

int32_t EffectDSPMainProcess(EffectDSPMain *dspmain, audio_buffer_t *in, audio_buffer_t *out)
{
	size_t actualFrameCount = in->frameCount;
	switch (dspmain->formatFloatModeInt32Mode)
	{
	case 0:
		dspmain->jdsp.processInt16Multiplexd(&dspmain->jdsp, in->s16, out->s16, actualFrameCount);
		break;
	case 1:
		dspmain->jdsp.processFloatMultiplexd(&dspmain->jdsp, in->f32, out->f32, actualFrameCount);
		break;
	case 2:
		dspmain->jdsp.processInt32Multiplexd(&dspmain->jdsp, in->s32, out->s32, actualFrameCount);
		break;
	case 3:
		dspmain->jdsp.processInt24PackedMultiplexd(&dspmain->jdsp, (uint8_t*)in->raw, (uint8_t*)out->raw, actualFrameCount);
		break;
	case 4:
		dspmain->jdsp.processInt8_24Multiplexd(&dspmain->jdsp, in->s32, out->s32, actualFrameCount);
		break;
	}
	return dspmain->mEnable ? 0 : -ENODATA;
}
// Effect section end
static effect_descriptor_t jamesdsp_descriptor =
{
	{ 0xf98765f4, 0xc321, 0x5de6, 0x9a45, { 0x12, 0x34, 0x59, 0x49, 0x5a, 0xb2 } },
	{ 0xf27317f4, 0xc984, 0x4de6, 0x9a90, { 0x54, 0x57, 0x59, 0x49, 0x5b, 0xf2 } }, // own UUID
	EFFECT_CONTROL_API_VERSION,
	EFFECT_FLAG_TYPE_INSERT | EFFECT_FLAG_INSERT_FIRST,
	10,
	1,
	"JamesDSP v4.01",
	"James Fung"
};
__attribute__((constructor)) static void initialize(void)
{
	JamesDSPGlobalMemoryAllocation();
#ifdef DEBUG
	LOGI("Initialization: DLL loaded");
#endif
}
__attribute__((destructor)) static void destruction(void)
{
	JamesDSPGlobalMemoryDeallocation();
#ifdef DEBUG
	LOGI("Initialization: DLL unloaded");
#endif
}
// Library mandatory methods
struct effect_module_s
{
	const struct effect_interface_s *itfe;
	EffectDSPMain effect;
	effect_descriptor_t *descriptor;
};
static int32_t generic_process(effect_handle_t self, audio_buffer_t *in, audio_buffer_t *out)
{
	struct effect_module_s *e = (struct effect_module_s *) self;
	return EffectDSPMainProcess(&e->effect, in, out);
}
static int32_t generic_command(effect_handle_t self, uint32_t cmdCode, uint32_t cmdSize, void *pCmdData, uint32_t *replySize, void *pReplyData)
{
	struct effect_module_s *e = (struct effect_module_s *) self;
	return EffectDSPMainCommand(&e->effect, cmdCode, cmdSize, pCmdData, replySize, pReplyData);
}
static int32_t generic_getDescriptor(effect_handle_t self, effect_descriptor_t *pDescriptor)
{
	struct effect_module_s *e = (struct effect_module_s *) self;
	memcpy(pDescriptor, e->descriptor, sizeof(effect_descriptor_t));
	return 0;
}
static const struct effect_interface_s generic_interface =
{
	generic_process,
	generic_command,
	generic_getDescriptor,
	NULL
};
int32_t EffectCreate(const effect_uuid_t *uuid, int32_t sessionId, int32_t ioId, effect_handle_t *pEffect)
{
	(void)uuid;
	(void)ioId;
	struct effect_module_s *e = (struct effect_module_s *) calloc(1, sizeof(struct effect_module_s));
	if (!e) return -ENOMEM;
	e->itfe = &generic_interface;
#ifdef DEBUG
	LOGI("JamesDSP: Creating effect with %d sessionId", sessionId);
#endif
	EffectDSPMainConstructor(&e->effect);
	e->descriptor = &jamesdsp_descriptor;
	*pEffect = (effect_handle_t)e;
#ifdef DEBUG
	LOGI("JamesDSP: Effect created");
#endif
	return 0;
}
int32_t EffectRelease(effect_handle_t ei)
{
	struct effect_module_s *e = (struct effect_module_s *) ei;
	EffectDSPMainDestructor(&e->effect);
	free(e);
	return 0;
}
int32_t EffectGetDescriptor(const effect_uuid_t *uuid, effect_descriptor_t *pDescriptor)
{
#ifdef DEBUG
	LOGI("JamesDSP: Copying descriptor info");
#endif
	if (pDescriptor == NULL || uuid == NULL)
	{
#ifdef DEBUG
		LOGI("JamesDSP: EffectGetDescriptor() called with NULL pointer");
#endif
		return -EINVAL;
	}
	*pDescriptor = jamesdsp_descriptor;
	return 0;
}
__attribute__((visibility("default"))) audio_effect_library_t AUDIO_EFFECT_LIBRARY_INFO_SYM =
{
	.tag = AUDIO_EFFECT_LIBRARY_TAG,
	.version = EFFECT_LIBRARY_API_VERSION,
#if __aarch64__ == 1
		.name = "James Audio DSP arm64",
#elif __ARM_ARCH_7A__ == 1
		.name = "James Audio DSP arm32",
#elif __i386__ == 1
		.name = "James Audio DSP x86",
#elif __x86_64__ == 1
		.name = "James Audio DSP x64",
#endif
		.implementor = "James Fung",
		.create_effect = EffectCreate,
		.release_effect = EffectRelease,
		.get_descriptor = EffectGetDescriptor,
};
