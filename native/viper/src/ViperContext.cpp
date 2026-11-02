#include <cerrno>
#include <cstring>
#include <cmath>
#include <chrono>
#include "ViperContext.h"
#include "log.h"
#include "viper/constants.h"
#include "viper/effects/DynamicTuning.h"

#define SET(type, ptr, value) (*(type *) (ptr) = (value))

ViperContext::ViperContext() :
        config({}),
        disableReason(DisableReason::NONE),
        buffer(std::vector<float>()),
        bufferFrameCount(0),
        enabled(false) {
    VIPER_LOGI("ViperContext created");
}

void ViperContext::copyBufferConfig(buffer_config_t *dest, buffer_config_t *src) {
    if (src->mask & EFFECT_CONFIG_BUFFER) {
        dest->buffer = src->buffer;
    }

    if (src->mask & EFFECT_CONFIG_SMP_RATE) {
        dest->samplingRate = src->samplingRate;
    }

    if (src->mask & EFFECT_CONFIG_CHANNELS) {
        dest->channels = src->channels;
    }

    if (src->mask & EFFECT_CONFIG_FORMAT) {
        dest->format = src->format;
    }

    if (src->mask & EFFECT_CONFIG_ACC_MODE) {
        dest->accessMode = src->accessMode;
    }

    if (src->mask & EFFECT_CONFIG_PROVIDER) {
        dest->bufferProvider = src->bufferProvider;
    }

    dest->mask |= src->mask;
}

void ViperContext::handleSetConfig(effect_config_t *newConfig) {
    VIPER_LOGI("Checking input and output configuration ...");

    VIPER_LOGI("Input mask: 0x%04X", newConfig->inputCfg.mask);
    VIPER_LOGI("Input buffer frame count: %zu", newConfig->inputCfg.buffer.frameCount);
    VIPER_LOGI("Input sampling rate: %d", newConfig->inputCfg.samplingRate);
    VIPER_LOGI("Input channels: %d", newConfig->inputCfg.channels);
    VIPER_LOGI("Input format: %d", newConfig->inputCfg.format);
    VIPER_LOGI("Input access mode: %d", newConfig->inputCfg.accessMode);
    VIPER_LOGI("Output mask: 0x%04X", newConfig->outputCfg.mask);
    VIPER_LOGI("Output buffer frame count: %zu", newConfig->outputCfg.buffer.frameCount);
    VIPER_LOGI("Output sampling rate: %d", newConfig->outputCfg.samplingRate);
    VIPER_LOGI("Output channels: %d", newConfig->outputCfg.channels);
    VIPER_LOGI("Output format: %d", newConfig->outputCfg.format);
    VIPER_LOGI("Output access mode: %d", newConfig->outputCfg.accessMode);

    setDisableReason(DisableReason::UNKNOWN);

    copyBufferConfig(&config.inputCfg, &newConfig->inputCfg);
    copyBufferConfig(&config.outputCfg, &newConfig->outputCfg);

    if (config.inputCfg.buffer.frameCount != config.outputCfg.buffer.frameCount) {
        VIPER_LOGE("ViPER4Android disabled, reason [in.FC = %zu, out.FC = %zu]",
                   config.inputCfg.buffer.frameCount, config.outputCfg.buffer.frameCount);
        setDisableReason(DisableReason::INVALID_FRAME_COUNT, "Input and output frame count mismatch");
        return;
    }

    if (config.inputCfg.samplingRate != config.outputCfg.samplingRate) {
        VIPER_LOGE("ViPER4Android disabled, reason [in.SR = %d, out.SR = %d]",
                   config.inputCfg.samplingRate, config.outputCfg.samplingRate);
        setDisableReason(DisableReason::INVALID_SAMPLING_RATE, "Input and output sampling rate mismatch");
        return;
    }

//    if (config.inputCfg.samplingRate > 48000) {
//        VIPER_LOGE("ViPER4Android disabled, reason [SR out of range]");
//        setDisableReason(DisableReason::INVALID_SAMPLING_RATE, "Sampling rate out of range: " + std::to_string(config.inputCfg.samplingRate));
//        return;
//    }

    if (config.inputCfg.channels != config.outputCfg.channels) {
        VIPER_LOGE("ViPER4Android disabled, reason [in.CH = %d, out.CH = %d]",
                   config.inputCfg.channels, config.outputCfg.channels);
        setDisableReason(DisableReason::INVALID_CHANNEL_COUNT, "Input and output channel count mismatch");
        return;
    }

    if (config.inputCfg.channels != AUDIO_CHANNEL_OUT_STEREO) {
        VIPER_LOGE("ViPER4Android disabled, reason [CH != 2]");
        setDisableReason(DisableReason::INVALID_CHANNEL_COUNT, "Invalid channel count: " + std::to_string(config.inputCfg.channels));
        return;
    }

    if (config.inputCfg.format != AUDIO_FORMAT_PCM_16_BIT &&
        config.inputCfg.format != AUDIO_FORMAT_PCM_32_BIT &&
        config.inputCfg.format != AUDIO_FORMAT_PCM_FLOAT) {
        VIPER_LOGE("ViPER4Android disabled, reason [in.FMT = %d]", config.inputCfg.format);
        VIPER_LOGE("We only accept AUDIO_FORMAT_PCM_16_BIT, AUDIO_FORMAT_PCM_32_BIT and AUDIO_FORMAT_PCM_FLOAT input format!");
        setDisableReason(DisableReason::INVALID_FORMAT, "Invalid input format: " + std::to_string(config.inputCfg.format));
        return;
    }

    if (config.outputCfg.format != AUDIO_FORMAT_PCM_16_BIT &&
        config.outputCfg.format != AUDIO_FORMAT_PCM_32_BIT &&
        config.outputCfg.format != AUDIO_FORMAT_PCM_FLOAT) {
        VIPER_LOGE("ViPER4Android disabled, reason [out.FMT = %d]", config.outputCfg.format);
        VIPER_LOGE("We only accept AUDIO_FORMAT_PCM_16_BIT, AUDIO_FORMAT_PCM_32_BIT and AUDIO_FORMAT_PCM_FLOAT output format!");
        setDisableReason(DisableReason::INVALID_FORMAT, "Invalid output format: " + std::to_string(config.outputCfg.format));
        return;
    }

    VIPER_LOGI("Input and output configuration checked.");
    setDisableReason(DisableReason::NONE);

    // Processing buffer
    buffer.resize(config.inputCfg.buffer.frameCount * 2);
    bufferFrameCount = config.inputCfg.buffer.frameCount;

    // ViPER
    viper.samplingRate = config.inputCfg.samplingRate;
    viper.resetAllEffects();
}

int32_t ViperContext::handleSetParam(effect_param_t *pCmdParam, void *pReplyData) {
    // The value offset of an effect parameter is computed by rounding up
    // the parameter size to the next 32 bit alignment.
    uint32_t vOffset = ((pCmdParam->psize + sizeof(int32_t) - 1) / sizeof(int32_t)) * sizeof(int32_t);

    *(int *) pReplyData = 0;

    int param = *(int *) (pCmdParam->data);
    int *intValues = (int *) (pCmdParam->data + vOffset);
    if(param==65655 || param==65656){
        const size_t words=param==65655?dynamictuning::kWords:dynamictuning::kToolsWords;
        const bool valid=pCmdParam->psize==4 && pCmdParam->vsize==words*4 &&
            (param==65655?viper.dynamicSystem.SetProfessional(intValues,words):viper.dynamicSystem.SetTools(intValues,words));
        *(int*)pReplyData=valid?0:-EINVAL;return 0;
    }
    if(param==65657){
        if(pCmdParam->psize!=4 || pCmdParam->vsize!=4 || intValues[0]!=0){*(int*)pReplyData=-EINVAL;return 0;}
        viper.dynamicSystem.ClearTools();return 0;
    }
    if(param==65652 || param==65653){
        const size_t words=param==65652?analogtuning::kWords:analogtuning::kToolsWords;
        const bool valid=pCmdParam->psize==4 && pCmdParam->vsize==words*4 &&
            (param==65652?viper.analogX.SetProfessional(intValues,words):viper.analogX.SetTools(intValues,words));
        *(int*)pReplyData=valid?0:-EINVAL;return 0;
    }
    if(param==65654){
        if(pCmdParam->psize!=4 || pCmdParam->vsize!=4 || intValues[0]!=0){*(int*)pReplyData=-EINVAL;return 0;}
        viper.analogX.ClearTools();return 0;
    }
    if(param==65649 || param==65650){
        const size_t words=param==65649?tubetuning::kWords:tubetuning::kToolsWords;
        const bool valid=pCmdParam->psize==4 && pCmdParam->vsize==words*4 &&
            (param==65649?viper.tubeSimulator.SetProfessional(intValues,words):viper.tubeSimulator.SetTools(intValues,words));
        *(int*)pReplyData=valid?0:-EINVAL;return 0;
    }
    if(param==65651){
        if(pCmdParam->psize!=4 || pCmdParam->vsize!=4 || intValues[0]!=0){*(int*)pReplyData=-EINVAL;return 0;}
        viper.tubeSimulator.ClearTools();return 0;
    }
    if(param==65648){
        const bool valid=pCmdParam->vsize%4==0 && viper.reverberation.SetAdvanced(intValues,pCmdParam->vsize/4);
        *(int*)pReplyData=valid?0:-EINVAL;return 0;
    }
    if(param==65645 || param==65646){
        const bool valid=pCmdParam->vsize%4==0 && (param==65645?viper.reverberation.SetProfessional(intValues,pCmdParam->vsize/4):viper.reverberation.SetTools(intValues,pCmdParam->vsize/4));
        *(int*)pReplyData=valid?0:-EINVAL;return 0;
    }
    if(param==65647){
        if(pCmdParam->vsize!=4 || intValues[0]!=0){*(int*)pReplyData=-EINVAL;return 0;}
        viper.reverberation.ClearTools();return 0;
    }
    if(param==65643 || param==65644) {
        const bool valid=pCmdParam->vsize%4==0 && (param==65643?
            viper.cure.SetProfessional2(intValues,pCmdParam->vsize/4):viper.cure.SetTools2(intValues,pCmdParam->vsize/4));
        *(int*)pReplyData=valid?0:-EINVAL;return 0;
    }
    if(param==65640 || param==65641) {
        const bool valid=pCmdParam->vsize%4==0 && (param==65640?
            viper.cure.SetAdvanced(intValues,pCmdParam->vsize/4):viper.cure.SetTools(intValues,pCmdParam->vsize/4));
        *(int*)pReplyData=valid?0:-EINVAL;return 0;
    }
    if(param==65642) {
        if(pCmdParam->vsize!=4 || intValues[0]!=0){*(int*)pReplyData=-EINVAL;return 0;}
        viper.cure.ClearTools();return 0;
    }
    if(param==65635) {
        const bool valid=pCmdParam->vsize==21*sizeof(int32_t) && viper.diffSurround.SetSecondRound(intValues,21);
        *(int*)pReplyData=valid?0:-EINVAL;return 0;
    }
    if(param==65636) {
        if(pCmdParam->vsize!=4 || intValues[0]<0 || intValues[0]>4){*(int*)pReplyData=-EINVAL;return 0;}
        viper.diffSurround.SetAudition(intValues[0]);return 0;
    }
    if(param==65632) {
        const bool valid=pCmdParam->vsize==7*sizeof(int32_t) && viper.diffSurround.SetAdvanced(intValues,7);
        *(int*)pReplyData=valid?0:-EINVAL;return 0;
    }
    if(param==65633) {
        if(pCmdParam->vsize!=4 || intValues[0]<0 || intValues[0]>2) {*(int*)pReplyData=-EINVAL;return 0;}
        viper.diffSurround.SetAudition(intValues[0]);return 0;
    }
    if(param==65634) {
        if(pCmdParam->vsize!=4 || intValues[0]!=0) {*(int*)pReplyData=-EINVAL;return 0;}
        viper.diffSurround.ClearRuntime();return 0;
    }
    if(param>=65627 && param<=65630) {
        const bool valid=pCmdParam->vsize%sizeof(int32_t)==0 &&
            viper.colorfulMusic.SetAdvancedProcessing(param,intValues,pCmdParam->vsize/sizeof(int32_t));
        *(int*)pReplyData=valid?0:-EINVAL;return 0;
    }
    if(param==65631) {
        if(pCmdParam->vsize!=4 || intValues[0]<0 || intValues[0]>1) {*(int*)pReplyData=-EINVAL;return 0;}
        viper.colorfulMusic.SetDifferenceAudition(intValues[0]!=0);return 0;
    }
    if(param==65608) {
        const bool valid=pCmdParam->vsize==13*sizeof(int32_t) && viper.colorfulMusic.SetAdvanced(intValues,13);
        *(int*)pReplyData=valid?0:-EINVAL;return 0;
    }
    if(param==65609) { // Internal imaging lifecycle cleanup; not a public SET.
        if(pCmdParam->vsize!=4 || intValues[0]!=0) {*(int*)pReplyData=-EINVAL;return 0;}
        viper.colorfulMusic.ClearImagingRuntime();return 0;
    }
    if(param>=65604 && param<=65607) {
        const size_t counts[4]={4,4,8,9};
        const size_t words=counts[param-65604];
        bool valid=false;
        if(pCmdParam->vsize==words*sizeof(int32_t)) {
            switch(param) {
                case 65604:valid=viper.viperClarity.SetSelective(intValues,words);break;
                case 65605:valid=viper.spectrumExtend.SetNonlinear(intValues,words);break;
                case 65606:valid=viper.viperClarity.SetDynamic(intValues,words);break;
                case 65607:valid=viper.viperClarity.SetDeess(intValues,words);break;
            }
        }
        *(int*)pReplyData=valid?0:-EINVAL;return 0;
    }
    if(param==65600 || param==65601) {
        const size_t words=param==65600?11:7;
        const bool valid=pCmdParam->vsize==words*sizeof(int32_t) &&
            (param==65600?viper.viperClarity.SetAdvanced(intValues,words):viper.spectrumExtend.SetAdvanced(intValues,words));
        *(int*)pReplyData=valid?0:-EINVAL;
        return 0;
    }
    if(param==65602) { // Internal lifecycle cleanup; Fusion does not expose this SET.
        if(pCmdParam->vsize!=4 || intValues[0]!=0) {*(int*)pReplyData=-EINVAL;return 0;}
        viper.spectrumExtend.ClearAudition();viper.viperClarity.ClearDynamics();return 0;
    }
    switch (pCmdParam->vsize) {
        case sizeof(int): {
            viper.DispatchCommand(param, intValues[0], 0, 0, 0, 0, nullptr);
            return 0;
        }
        case sizeof(int) * 2: {
            viper.DispatchCommand(param, intValues[0], intValues[1], 0, 0, 0, nullptr);
            return 0;
        }
        case sizeof(int) * 3: {
            viper.DispatchCommand(param, intValues[0], intValues[1], intValues[2], 0, 0, nullptr);
            return 0;
        }
        case sizeof(int) * 4: {
            viper.DispatchCommand(param, intValues[0], intValues[1], intValues[2], intValues[3], 0, nullptr);
            return 0;
        }
        case 256:
        case 1024: {
            uint32_t arrSize = *(uint32_t *) (pCmdParam->data + vOffset);
            signed char *arr = (signed char *) (pCmdParam->data + vOffset + sizeof(uint32_t));
            viper.DispatchCommand(param, 0, 0, 0, 0, arrSize, arr);
            return 0;
        }
        case 8192: {
            int value1 = *(int *) (pCmdParam->data + vOffset);
            uint32_t arrSize = *(uint32_t *) (pCmdParam->data + vOffset + sizeof(int));
            signed char *arr = (signed char *) (pCmdParam->data + vOffset + sizeof(int) + sizeof(uint32_t));
            viper.DispatchCommand(param, value1, 0, 0, 0, arrSize, arr);
            return 0;
        }
        default: {
            return -EINVAL;
        }
    }
}

int32_t ViperContext::handleGetParam(effect_param_t *pCmdParam, effect_param_t *pReplyParam, uint32_t *pReplySize) {
    // The value offset of an effect parameter is computed by rounding up
    // the parameter size to the next 32 bit alignment.
    uint32_t vOffset = ((pCmdParam->psize + sizeof(int32_t) - 1) / sizeof(int32_t)) * sizeof(int32_t);

    memcpy(pReplyParam, pCmdParam, sizeof(effect_param_t) + pCmdParam->psize);

    const uint32_t professionalId=*(uint32_t*)pCmdParam->data;
    if(professionalId==131228 || professionalId==131229){
        const unsigned words=professionalId==131228?dynamictuning::kRuntimeWords:dynamictuning::kResponseWords;
        const unsigned bytes=words*4;
        if(pCmdParam->psize!=4 || pCmdParam->vsize!=bytes || *pReplySize<16+bytes)return -EINVAL;
        pReplyParam->status=0;pReplyParam->vsize=bytes;
        std::array<int32_t,dynamictuning::kResponseWords> values{};
        if(professionalId==131228)viper.dynamicSystem.GetRuntime(values.data());else viper.dynamicSystem.GetResponse(values.data());
        std::memcpy(pReplyParam->data+vOffset,values.data(),bytes);*pReplySize=16+bytes;return 0;
    }
    if(professionalId==131226){
        constexpr unsigned bytes=analogtuning::kRuntimeWords*4;
        if(pCmdParam->psize!=4 || pCmdParam->vsize!=bytes || *pReplySize<16+bytes)return -EINVAL;
        pReplyParam->status=0;pReplyParam->vsize=bytes;
        std::array<int32_t,analogtuning::kRuntimeWords> values{};viper.analogX.GetRuntime(values.data());
        std::memcpy(pReplyParam->data+vOffset,values.data(),bytes);*pReplySize=16+bytes;return 0;
    }
    if(professionalId==131225){
        constexpr unsigned bytes=tubetuning::kRuntimeWords*4;
        if(pCmdParam->psize!=4 || pCmdParam->vsize!=bytes || *pReplySize<16+bytes)return -EINVAL;
        pReplyParam->status=0;pReplyParam->vsize=bytes;
        std::array<int32_t,tubetuning::kRuntimeWords> values{};viper.tubeSimulator.GetRuntime(values.data());
        std::memcpy(pReplyParam->data+vOffset,values.data(),bytes);*pReplySize=16+bytes;return 0;
    }
    if(professionalId==131223){
        constexpr unsigned bytes=reverbtuning::kDecayWords*4;
        if(pCmdParam->psize!=4 || pCmdParam->vsize!=bytes || *pReplySize<16+bytes)return -EINVAL;
        pReplyParam->status=0;pReplyParam->vsize=bytes;
        std::array<int32_t,reverbtuning::kDecayWords> values{};viper.reverberation.GetDecay(values.data());
        std::memcpy(pReplyParam->data+vOffset,values.data(),bytes);*pReplySize=16+bytes;return 0;
    }
    if(professionalId==131222){
        constexpr unsigned bytes=reverbtuning::kRuntimeWords*4;
        if(pCmdParam->psize!=4 || pCmdParam->vsize!=bytes || *pReplySize<16+bytes)return -EINVAL;
        pReplyParam->status=0;pReplyParam->vsize=bytes;
        std::array<int32_t,reverbtuning::kRuntimeWords> values{};viper.reverberation.GetRuntime(values.data());
        std::memcpy(pReplyParam->data+vOffset,values.data(),bytes);*pReplySize=16+bytes;return 0;
    }
    if(professionalId==131221) {
        constexpr unsigned bytes=headphone::kDiagnosticWords*4;
        if(pCmdParam->psize!=4 || pCmdParam->vsize!=bytes || *pReplySize<16+bytes)return -EINVAL;
        pReplyParam->status=0;pReplyParam->vsize=bytes;
        std::array<int32_t,headphone::kDiagnosticWords> values{};viper.cure.GetDiagnostics(values.data());
        std::memcpy(pReplyParam->data+vOffset,values.data(),bytes);*pReplySize=16+bytes;return 0;
    }
    if(professionalId==131219) {
        if(pCmdParam->psize!=4 || pCmdParam->vsize!=80 || *pReplySize<96)return -EINVAL;
        pReplyParam->status=0;pReplyParam->vsize=80;
        std::array<int32_t,headphone::kRuntimeWords> values{};viper.cure.GetRuntime(values.data());
        std::memcpy(pReplyParam->data+vOffset,values.data(),80);*pReplySize=96;return 0;
    }
    if(professionalId>=131152 && professionalId<=131211) {
        if(pCmdParam->psize!=4 || pCmdParam->vsize!=4 || *pReplySize<20)return -EINVAL;
        pReplyParam->status=0;pReplyParam->vsize=4;
        *(int32_t*)(pReplyParam->data+vOffset)=viper.diffSurround.GetSecondRound(professionalId-131152);
        *pReplySize=20;return 0;
    }
    if(professionalId>=131128 && professionalId<=131151) {
        if(pCmdParam->psize!=4 || pCmdParam->vsize!=4 || *pReplySize<20) return -EINVAL;
        pReplyParam->status=0;pReplyParam->vsize=4;
        *(int32_t*)(pReplyParam->data+vOffset)=viper.diffSurround.GetRuntime(professionalId-131128);
        *pReplySize=20;return 0;
    }
    if(professionalId>=131112 && professionalId<=131125) {
        if(pCmdParam->psize!=4 || pCmdParam->vsize!=4 || *pReplySize<20) return -EINVAL;
        pReplyParam->status=0;pReplyParam->vsize=4;
        *(int32_t*)(pReplyParam->data+vOffset)=viper.colorfulMusic.GetAdvancedImaging(professionalId-131112);
        *pReplySize=20;return 0;
    }
    if(professionalId>=131103 && professionalId<=131111) {
        if(pCmdParam->psize!=4 || pCmdParam->vsize!=4 || *pReplySize<20) return -EINVAL;
        pReplyParam->status=0;pReplyParam->vsize=4;
        *(int32_t*)(pReplyParam->data+vOffset)=viper.colorfulMusic.GetImaging(professionalId-131103);
        *pReplySize=20;return 0;
    }
    if(professionalId>=131086 && professionalId<=131093) {
        if(pCmdParam->psize!=4 || pCmdParam->vsize!=4 || *pReplySize<20) return -EINVAL;
        const unsigned index=professionalId-131086;
        pReplyParam->status=0;pReplyParam->vsize=4;
        *(int32_t*)(pReplyParam->data+vOffset)=index<5?viper.viperClarity.GetEffective(index):viper.spectrumExtend.GetEffective(index-5);
        *pReplySize=20;return 0;
    }

    if(professionalId>=131094 && professionalId<=131102) {
        if(pCmdParam->psize!=4 || pCmdParam->vsize!=4 || *pReplySize<20) return -EINVAL;
        const unsigned index=professionalId-131094;
        pReplyParam->status=0;pReplyParam->vsize=4;
        const int32_t value=index<5?viper.viperClarity.GetTopEnd(index):
            index<7?viper.spectrumExtend.GetNonlinear(index-5):viper.viperClarity.GetTopEnd(index-2);
        *(int32_t*)(pReplyParam->data+vOffset)=value;
        *pReplySize=20;return 0;
    }

    VIPER_LOGD("handleGetParam() EFFECT_CMD_GET_PARAM called with data = %d, psize = %d, vsize = %d", *(uint32_t *) pCmdParam->data, pCmdParam->psize, pCmdParam->vsize);

    switch (*(uint32_t *) pCmdParam->data) {
        case PARAM_GET_ENABLED: {
            pReplyParam->status = 0;
            pReplyParam->vsize = sizeof(int32_t);
            *(int32_t *) (pReplyParam->data + vOffset) = enabled;
            *pReplySize = sizeof(effect_param_t) + pReplyParam->psize + vOffset + pReplyParam->vsize;
            return 0;
        }
        case PARAM_GET_CONFIGURE: {
            pReplyParam->status = 0;
            pReplyParam->vsize = sizeof(int32_t);
            *(int32_t *) (pReplyParam->data + vOffset) = disableReason == DisableReason::NONE;
            *pReplySize = sizeof(effect_param_t) + pReplyParam->psize + vOffset + pReplyParam->vsize;
            return 0;
        }
        case PARAM_GET_STREAMING: { // Is processing
            auto now = std::chrono::system_clock::now();
            auto now_ms = std::chrono::time_point_cast<std::chrono::milliseconds>(now);

            uint64_t currentMs = now_ms.time_since_epoch().count();
            uint64_t lastProcessTime = viper.processTimeMs;

            bool isProcessing;
            if (currentMs >= lastProcessTime) {
                isProcessing = currentMs - lastProcessTime < 5000;
            } else {
                isProcessing = false;
            }

            pReplyParam->status = 0;
            pReplyParam->vsize = sizeof(int32_t);
            *(int32_t *) (pReplyParam->data + vOffset) = isProcessing;
            *pReplySize = sizeof(effect_param_t) + pReplyParam->psize + vOffset + pReplyParam->vsize;
            return 0;
        }
        case PARAM_GET_SAMPLING_RATE: {
            pReplyParam->status = 0;
            pReplyParam->vsize = sizeof(uint32_t);
            *(uint32_t *) (pReplyParam->data + vOffset) = viper.samplingRate;
            *pReplySize = sizeof(effect_param_t) + pReplyParam->psize + vOffset + pReplyParam->vsize;
            return 0;
        }
        case PARAM_GET_CONVOLUTION_KERNEL_ID: {
            pReplyParam->status = 0;
            pReplyParam->vsize = sizeof(uint32_t);
            *(uint32_t *) (pReplyParam->data + vOffset) = viper.convolver.GetKernelID();
            *pReplySize = sizeof(effect_param_t) + pReplyParam->psize + vOffset + pReplyParam->vsize;
            return 0;
        }
        case PARAM_GET_DRIVER_VERSION_CODE: {
            pReplyParam->status = 0;
            pReplyParam->vsize = sizeof(uint32_t);
            *(int32_t *) (pReplyParam->data + vOffset) = VERSION_CODE;
            *pReplySize = sizeof(effect_param_t) + pReplyParam->psize + vOffset + pReplyParam->vsize;
            return 0;
        }
        case PARAM_GET_DRIVER_VERSION_NAME: {
            pReplyParam->status = 0;
            pReplyParam->vsize = strlen(VERSION_NAME);
            memcpy(pReplyParam->data + vOffset, VERSION_NAME, pReplyParam->vsize);
            *pReplySize = sizeof(effect_param_t) + pReplyParam->psize + vOffset + pReplyParam->vsize;
            return 0;
        }
        case PARAM_GET_DISABLE_REASON: {
            pReplyParam->status = 0;
            pReplyParam->vsize = sizeof(int32_t);
            *(int32_t *) (pReplyParam->data + vOffset) = static_cast<int32_t>(disableReason);
            *pReplySize = sizeof(effect_param_t) + pReplyParam->psize + vOffset + pReplyParam->vsize;
            return 0;
        }
        case PARAM_GET_DISABLE_REASON_MESSAGE: {
            pReplyParam->status = 0;
            pReplyParam->vsize = disableReasonMessage.size();
            memcpy(pReplyParam->data + vOffset, disableReasonMessage.c_str(), pReplyParam->vsize);
            *pReplySize = sizeof(effect_param_t) + pReplyParam->psize + vOffset + pReplyParam->vsize;
            return 0;
        }
        case PARAM_GET_CONFIG: {
            pReplyParam->status = 0;
            pReplyParam->vsize = 40;
            // Input
            SET(uint64_t, pReplyParam->data + vOffset, config.inputCfg.buffer.frameCount);
            SET(uint32_t, pReplyParam->data + vOffset + 8, config.inputCfg.samplingRate);
            SET(uint32_t, pReplyParam->data + vOffset + 12, config.inputCfg.channels);
            SET(uint8_t, pReplyParam->data + vOffset + 16, config.inputCfg.format);
            SET(uint8_t, pReplyParam->data + vOffset + 17, config.inputCfg.accessMode);
            SET(uint16_t, pReplyParam->data + vOffset + 18, config.inputCfg.mask);
            // Output
            SET(uint64_t, pReplyParam->data + vOffset + 20, config.outputCfg.buffer.frameCount);
            SET(uint32_t, pReplyParam->data + vOffset + 28, config.outputCfg.samplingRate);
            SET(uint32_t, pReplyParam->data + vOffset + 32, config.outputCfg.channels);
            SET(uint8_t, pReplyParam->data + vOffset + 36, config.outputCfg.format);
            SET(uint8_t, pReplyParam->data + vOffset + 37, config.outputCfg.accessMode);
            SET(uint16_t, pReplyParam->data + vOffset + 38, config.outputCfg.mask);
            *pReplySize = sizeof(effect_param_t) + pReplyParam->psize + vOffset + pReplyParam->vsize;
            return 0;
        }
        case PARAM_GET_ARCHITECTURE: {
            pReplyParam->status = 0;
            pReplyParam->vsize = sizeof(VIPER_ARCHITECTURE) - 1; // Exclude null terminator
            memcpy(pReplyParam->data + vOffset, VIPER_ARCHITECTURE, pReplyParam->vsize);
            *pReplySize = sizeof(effect_param_t) + pReplyParam->psize + vOffset + pReplyParam->vsize;
            return 0;
        }
        default: {
            return -EINVAL;
        }
    }
}

int32_t ViperContext::handleCommand(uint32_t cmdCode, uint32_t cmdSize, void *pCmdData, uint32_t *pReplySize, void *pReplyData) {
    uint32_t replySize = pReplySize == nullptr ? 0 : *pReplySize;
    if(cmdCode==0x44494650u) { // Fusion's private, already-validated prepared-plan handoff.
        if(cmdSize!=sizeof(std::shared_ptr<DiffSurround::Prepared>) || !pCmdData ||
           replySize!=sizeof(int32_t) || !pReplyData)return -EINVAL;
        auto& prepared=*static_cast<std::shared_ptr<DiffSurround::Prepared>*>(pCmdData);
        SET(int32_t,pReplyData,viper.diffSurround.ApplyPrepared(prepared)?0:-EAGAIN);
        return 0;
    }
    switch (cmdCode) {
        case EFFECT_CMD_INIT: {
            if (replySize != sizeof(int32_t) || pReplyData == nullptr) {
                VIPER_LOGE("EFFECT_CMD_INIT called with invalid replySize = %d, pReplyData = %p, expected replySize = %zu", replySize, pReplyData, sizeof(int32_t));
                return -EINVAL;
            }
            SET(int32_t, pReplyData, 0);
            return 0;
        }
        case EFFECT_CMD_SET_CONFIG: {
            if (cmdSize < sizeof(effect_config_t) || pCmdData == nullptr || replySize != sizeof(int32_t) || pReplyData == nullptr) {
                VIPER_LOGE("EFFECT_CMD_SET_CONFIG called with invalid cmdSize = %d, pCmdData = %p, replySize = %d, pReplyData = %p, expected cmdSize = %zu, replySize = %zu", cmdSize, pCmdData, replySize, pReplyData, sizeof(effect_config_t), sizeof(int32_t));
                return -EINVAL;
            }
            handleSetConfig((effect_config_t *) pCmdData);
            SET(int32_t, pReplyData, 0);
            return 0;
        }
        case EFFECT_CMD_RESET: {
            if (replySize != sizeof(int32_t) || pReplyData == nullptr) {
                VIPER_LOGE("EFFECT_CMD_RESET called with invalid replySize = %d, pReplyData = %p, expected replySize = %zu", replySize, pReplyData, sizeof(int32_t));
                return -EINVAL;
            }
            viper.resetAllEffects();
            SET(int32_t, pReplyData, 0);
            return 0;
        }
        case EFFECT_CMD_ENABLE: {
            if (replySize != sizeof(int32_t) || pReplyData == nullptr) {
                VIPER_LOGE("EFFECT_CMD_ENABLE called with invalid replySize = %d, pReplyData = %p, expected replySize = %zu", replySize, pReplyData, sizeof(int32_t));
                return -EINVAL;
            }
            enabled = true;
            SET(int32_t, pReplyData, 0);
            return 0;
        }
        case EFFECT_CMD_DISABLE: {
            if (replySize != sizeof(int32_t) || pReplyData == nullptr) {
                VIPER_LOGE("EFFECT_CMD_DISABLE called with invalid replySize = %d, pReplyData = %p, expected replySize = %zu", replySize, pReplyData, sizeof(int32_t));
                return -EINVAL;
            }
            enabled = false;
            viper.tubeSimulator.ClearTools();
            viper.analogX.ClearTools();
            viper.dynamicSystem.ClearTools();
            SET(int32_t, pReplyData, 0);
            return 0;
        }
        case EFFECT_CMD_SET_PARAM: {
            if (cmdSize < sizeof(effect_param_t) || pCmdData == nullptr || replySize != sizeof(int32_t) || pReplyData == nullptr) {
                VIPER_LOGE("EFFECT_CMD_SET_PARAM called with invalid cmdSize = %d, pCmdData = %p, replySize = %d, pReplyData = %p, expected cmdSize = %zu, replySize = %zu", cmdSize, pCmdData, replySize, pReplyData, sizeof(effect_param_t), sizeof(int32_t));
                return -EINVAL;
            }
            return handleSetParam((effect_param_t *) pCmdData, pReplyData);
        }
        case EFFECT_CMD_GET_PARAM: {
            if (cmdSize < sizeof(effect_param_t) || pCmdData == nullptr || replySize < sizeof(effect_param_t) || pReplyData == nullptr) {
                VIPER_LOGE("EFFECT_CMD_GET_PARAM called with invalid cmdSize = %d, pCmdData = %p, replySize = %d, pReplyData = %p, expected cmdSize = %zu, replySize = %zu", cmdSize, pCmdData, replySize, pReplyData, sizeof(effect_param_t), sizeof(effect_param_t));
                return -EINVAL;
            }
            return handleGetParam((effect_param_t *) pCmdData, (effect_param_t *) pReplyData, pReplySize);
        }
        case EFFECT_CMD_GET_CONFIG: {
            if (replySize != sizeof(effect_config_t) || pReplyData == nullptr) {
                VIPER_LOGE("EFFECT_CMD_GET_CONFIG called with invalid replySize = %d, pReplyData = %p, expected replySize = %zu", replySize, pReplyData, sizeof(effect_config_t));
                return -EINVAL;
            }
            *(effect_config_t *) pReplyData = config;
            return 0;
        }
        default: {
            VIPER_LOGE("handleCommand called with unknown command: %d", cmdCode);
            return -EINVAL;
        }
    }
}

template <typename T>
void pcmToFloat(float* dst, const T* src, size_t frameCount) {
    constexpr float max_val = static_cast<float>(std::numeric_limits<T>::max());
    for (size_t i = 0; i < frameCount * 2; i++) {
        dst[i] = static_cast<float>(src[i]) / max_val;
    }
}

template <typename T>
static const T& clamp(const T& v, const T& lo, const T& hi) {
    return std::min(std::max(v, lo), hi);
}

static void floatToFloat(float *dst, const float *src, size_t frameCount, bool accumulate) {
    if (accumulate) {
        for (size_t i = 0; i < frameCount * 2; i++) {
            dst[i] = clamp(dst[i] + src[i], -1.0f, 1.0f);
        }
    } else {
        memcpy(dst, src, frameCount * 2 * sizeof(float));
    }
}

template <typename T, typename U>
void floatToPcm(T *dst, const float *src, size_t frameCount, bool accumulate) {
    constexpr T max_val = std::numeric_limits<T>::max();
    constexpr T min_val = std::numeric_limits<T>::min();

    for (size_t i = 0; i < frameCount * 2; i++) {
        T pcm = static_cast<T>(src[i] * static_cast<float>(max_val));
        if (accumulate) {
            U temp = static_cast<U>(dst[i]) + pcm;
            dst[i] = static_cast<T>(clamp(temp, static_cast<U>(min_val), static_cast<U>(max_val)));
        } else {
            dst[i] = pcm;
        }
    }
}

static audio_buffer_t *getBuffer(buffer_config_s *config, audio_buffer_t *buffer) {
    if (buffer != nullptr) return buffer;
    if (config->mask & EFFECT_CONFIG_BUFFER) return &config->buffer;
    // EFFECT_CONFIG_PROVIDER not implemented, it's not used by any known effect
    return nullptr;
}

int32_t ViperContext::process(audio_buffer_t *inBuffer, audio_buffer_t *outBuffer) {
    if (disableReason != DisableReason::NONE) {
        return -EINVAL;
    }

    if (!enabled) {
        return -ENODATA;
    }
    
    inBuffer = getBuffer(&config.inputCfg, inBuffer);
    outBuffer = getBuffer(&config.outputCfg, outBuffer);
    if (inBuffer == nullptr || outBuffer == nullptr ||
        inBuffer->raw == nullptr || outBuffer->raw == nullptr ||
        inBuffer->frameCount != outBuffer->frameCount ||
        inBuffer->frameCount == 0) {
        return -EINVAL;
    }

    size_t frameCount = inBuffer->frameCount;
    if (frameCount > bufferFrameCount) {
        // This should never happen, but just in case
        buffer.resize(frameCount * 2);
        bufferFrameCount = frameCount;
    }

    switch (config.inputCfg.format) {
        case AUDIO_FORMAT_PCM_16_BIT:
            pcmToFloat<int16_t>(buffer.data(), inBuffer->s16, frameCount);
            break;
        case AUDIO_FORMAT_PCM_32_BIT:
            pcmToFloat<int32_t>(buffer.data(), inBuffer->s32, frameCount);
            break;
        case AUDIO_FORMAT_PCM_FLOAT:
            floatToFloat(buffer.data(), inBuffer->f32, frameCount, false);
            break;
        default:
            return -EINVAL;
    }

    viper.process(buffer, frameCount);

    const bool accumulate = config.outputCfg.accessMode == EFFECT_BUFFER_ACCESS_ACCUMULATE;
    switch (config.outputCfg.format) {
        case AUDIO_FORMAT_PCM_16_BIT:
            floatToPcm<int16_t, int32_t>(outBuffer->s16, buffer.data(), frameCount, accumulate);
            break;
        case AUDIO_FORMAT_PCM_32_BIT:
            floatToPcm<int32_t, int64_t>(outBuffer->s32, buffer.data(), frameCount, accumulate);
            break;
        case AUDIO_FORMAT_PCM_FLOAT:
            floatToFloat(outBuffer->f32, buffer.data(), frameCount, accumulate);
            break;
        default:
            return -EINVAL;
    }

    return 0;
}

void ViperContext::setDisableReason(DisableReason reason) {
    setDisableReason(reason, "");
}

void ViperContext::setDisableReason(DisableReason reason, std::string message) {
    this->disableReason = reason;
    this->disableReasonMessage = std::move(message);
}
