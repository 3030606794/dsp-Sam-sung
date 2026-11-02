// A single Android AudioEffect owns both upstream engines. Their library entry
// points are private and never appear in the system audio-effects configuration.
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <new>
#include <vector>
#include <chrono>
#include <thread>
#include "OutputSafetyLimiter.h"
#include "../convolver/ConvolverAdvanced.h"
#include "essential.h"
#include "../tone/ToneProcessor.h"
#include "../ddc/DdcProcessor.h"
#include "../ddc/DdcPcmTelemetry.h"
#include "../peq/PeqProcessor.h"
#include "../dynamic_peq/DynamicPeqProcessor.h"
#include "../creative/CreativeEffectsProcessor.h"
#include "../expansion/ExpansionEffectsProcessor.h"
#include "../speaker/SpeakerOptimizationProcessor.h"
#include "../surround/SurroundProcessor.h"
#include "../viper/src/viper/utils/ProfessionalTuning.h"
#include "../viper/src/viper/utils/VisualizationTelemetry.h"
#include "../viper/src/viper/utils/HeadphoneCrossfeed.h"
#include "../viper/src/viper/utils/ProfessionalReverb.h"
#include "../viper/src/viper/effects/TubeTuning.h"
#include "../viper/src/viper/utils/TubePcmTelemetry.h"
#include "../viper/src/viper/effects/AnalogTuning.h"
#include "../viper/src/viper/utils/AnalogPcmTelemetry.h"
#include "../viper/src/viper/effects/DynamicTuning.h"
#include "../viper/src/viper/utils/DynamicPcmTelemetry.h"
#include "../../app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/Effects/presetCrossfeed.h"
#include "../viper/src/viper/effects/DiffSurround.h"
#include "../convolver/ConvolverControl.h"
#include "../convolver/ConvolverPcmTelemetry.h"
#include "../liveprog/LiveprogPcmTelemetry.h"
#include "../eq/EqCapture.h"
#include "../eq/EqPcmTelemetry.h"
#include "../room/RoomReverb.h"
#include "../room/RoomReverbCapture.h"
#include "../timbre/Timbre.h"
#include "../timbre/TimbrePcmTelemetry.h"
#include "../viper/src/viper/utils/SpectrumPcmTelemetry.h"
#include "../compander/Compander.h"
#include "../compander/CompanderPcmTelemetry.h"
#include "../../app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/Effects/stereoWidthControl.h"

extern "C" audio_effect_library_t JDSP_AELI;
extern "C" audio_effect_library_t VIPER_AELI;

namespace {
constexpr effect_uuid_t kUuid{0x30306067, 0x9400, 0x4d53, 0x9000, {0,0,0,0,0,1}};
constexpr effect_uuid_t kType{0x30306067, 0x9400, 0x4d53, 0x9000, {0,0,0,0,0,0}};
constexpr effect_uuid_t kJamesUuid{0xf27317f4,0xc984,0x4de6,0x9a90,{0x54,0x57,0x59,0x49,0x5b,0xf2}};
constexpr effect_uuid_t kViperUuid{0x90380da3,0x8536,0x4744,0xa6a3,{0x57,0x31,0x97,0x0e,0x64,0x0f}};
constexpr size_t kBlockFrames=512;
constexpr size_t kMaxTransfer=32*1024*1024;
constexpr uint32_t kJamesEnable=131072, kViperEnable=131073;
constexpr uint32_t kBalance=131074;
constexpr uint32_t kProtocol=131080, kCapabilities=131081, kTone=196608;
constexpr uint32_t kExtendedCapabilities=131234,kToneAdvanced=196640,kToneTools=196641,kToneClear=196642,kToneOff=196643;
constexpr uint32_t kDdcAdvanced=196648,kDdcTools=196649,kDdcClear=196650,kDdcOff=196651,kDdcOriginal=196652;
constexpr uint32_t kLiveprogParameter=196656,kLiveprogStatus=131241;
constexpr uint32_t kVisualizationArm=196657,kVisualizationEnergy=131242,kVisualizationField=131243;
constexpr int32_t kVisualizationCapability=16;
constexpr uint32_t kSurround=196609;
constexpr uint32_t kSurroundProfessional=196610,kSurroundAudition=196611,kSurroundRuntime=131217;
constexpr uint32_t kSurroundListening=196612,kSurroundListeningRuntime=131218;
constexpr uint32_t kHeadphoneRuntime=131219;
// Optional protocol-5 diagnostics. Unlike James GET 20001, this is the host I/O rate.
constexpr uint32_t kHostRate=131082, kProcessedFrames=131083;
constexpr uint32_t kBassFrequency=131084, kBassGain=131085;
constexpr uint32_t kPeq=1250;
constexpr uint32_t kCommitPreparedDiff=0x44494650u; // Private child command; never accepted by Fusion's public interface.
// Retain the upstream engines' nominal policy charges (1 KB each). This legacy
// descriptor field is admission accounting, not measured process RSS: a 1024 KB
// estimate exceeds Android's global effects budget and prevents any instance.
constexpr uint16_t kPolicyMemoryKb=2;
const effect_descriptor_t kDescriptor{
    kType,kUuid,EFFECT_CONTROL_API_VERSION,
    EFFECT_FLAG_TYPE_INSERT | EFFECT_FLAG_INSERT_LAST | EFFECT_FLAG_INPUT_DIRECT | EFFECT_FLAG_OUTPUT_DIRECT,
    30,kPolicyMemoryKb,"DSP Fusion 0.1","James Fung / ViPER authors / DSP Fusion"
};

int32_t childCommand(effect_handle_t child,uint32_t cmd,uint32_t size=0,void* data=nullptr) {
    int32_t status=0; uint32_t replySize=sizeof(status);
    const auto result=(*child)->command(child,cmd,size,data,&replySize,&status);
    return result ? result : status;
}
bool supportedFormat(uint8_t f) {
    return f==AUDIO_FORMAT_PCM_FLOAT || f==AUDIO_FORMAT_PCM_16_BIT || f==AUDIO_FORMAT_PCM_32_BIT ||
           f==AUDIO_FORMAT_PCM_24_BIT_PACKED || f==AUDIO_FORMAT_PCM_8_24_BIT;
}
float clean(float v) { return std::isfinite(v) ? v : 0.f; }
float readSample(const void* raw,size_t i,uint8_t format) {
    switch(format) {
        case AUDIO_FORMAT_PCM_FLOAT: return clean(static_cast<const float*>(raw)[i]);
        case AUDIO_FORMAT_PCM_16_BIT: return static_cast<const int16_t*>(raw)[i]/32768.f;
        case AUDIO_FORMAT_PCM_32_BIT: return static_cast<float>(static_cast<const int32_t*>(raw)[i]/2147483648.0);
        case AUDIO_FORMAT_PCM_8_24_BIT: return static_cast<const int32_t*>(raw)[i]/8388608.f;
        case AUDIO_FORMAT_PCM_24_BIT_PACKED: {
            const auto* p=static_cast<const uint8_t*>(raw)+3*i;
            int32_t v=static_cast<int32_t>(uint32_t(p[0]) | (uint32_t(p[1])<<8) | (uint32_t(p[2])<<16));
            if(v&0x800000) v-=0x1000000;
            return v/8388608.f;
        }
        default: return 0;
    }
}
void writeSample(void* raw,size_t i,uint8_t format,float sample,bool accumulate) {
    double value=clean(sample);
    if(accumulate) value+=readSample(raw,i,format);
    if(format==AUDIO_FORMAT_PCM_FLOAT) {
        static_cast<float*>(raw)[i]=clean(static_cast<float>(value)); return;
    }
    value=std::clamp(value,-1.0,1.0);
    switch(format) {
        case AUDIO_FORMAT_PCM_16_BIT:
            static_cast<int16_t*>(raw)[i]=static_cast<int16_t>(std::clamp(std::llround(value*32768.0),-32768LL,32767LL)); break;
        case AUDIO_FORMAT_PCM_32_BIT:
            static_cast<int32_t*>(raw)[i]=static_cast<int32_t>(std::clamp(std::llround(value*2147483648.0),-2147483648LL,2147483647LL)); break;
        case AUDIO_FORMAT_PCM_8_24_BIT:
            static_cast<int32_t*>(raw)[i]=static_cast<int32_t>(std::clamp(std::llround(value*8388608.0),-8388608LL,8388607LL)); break;
        case AUDIO_FORMAT_PCM_24_BIT_PACKED: {
            auto v=static_cast<int32_t>(std::clamp(std::llround(value*8388608.0),-8388608LL,8388607LL));
            auto* p=static_cast<uint8_t*>(raw)+3*i;
            p[0]=static_cast<uint8_t>(v); p[1]=static_cast<uint8_t>(v>>8); p[2]=static_cast<uint8_t>(v>>16); break;
        }
    }
}
bool imagingUnavailableValue(uint32_t id) {
    return (id>=131106 && id<=131109) || id==131112 || (id>=131121 && id<=131124);
}
struct Fusion {
    const effect_interface_s* iface; // Android effect_handle_t ABI: first member.
    effect_handle_t james=nullptr, viper=nullptr;
    std::mutex mutex;
    effect_config_t config{};
    std::atomic<uint32_t> ioFormat{AUDIO_FORMAT_PCM_FLOAT | (AUDIO_FORMAT_PCM_FLOAT<<8)};
    std::atomic<bool> enabled{false};
    std::atomic<bool> spectrumPcmParentEnabled{true};
    std::atomic<bool> liveprogPcmParentEnabled{true};
    std::atomic<uint32_t> hostRate{0}, processedFrames{0};
    std::array<std::atomic<uint32_t>,3> visualizationRequests{};
    visualization::Requests visualizationApplied{};
    uint32_t visualizationRate=0,visualizationRefreshFrames=0;
    std::array<std::atomic<int32_t>,visualization::kEnergyWords> visualizationEnergy{};
    std::array<std::atomic<int32_t>,visualization::kFieldWords> visualizationField{};
    std::atomic<uint32_t> visualizationEpoch{0};
    // Legacy status is polled on every UI refresh too. Reading the child under
    // mutex would make a coinciding audio callback fall back to dry audio.
    std::array<std::atomic<int32_t>,9> legacyStatus{};
    std::atomic<uint32_t> initializeQueries{0};
    std::atomic<int32_t> bassFrequency{0}, bassGain{0};
    std::array<std::atomic<int32_t>,40> professionalStatus{};
    std::atomic<uint32_t> imagingSnapshotEpoch{0};
    // Independent publication epoch: imaging is upstream of interchannel delay.
    std::array<std::atomic<int32_t>,84> diffStatus{};
    std::atomic<uint32_t> diffSnapshotEpoch{0};
    std::array<std::atomic<int32_t>,32> convolverStatus{};
    std::array<std::atomic<int32_t>,20> convolutionAdvancedStatus{};
    std::atomic<uint32_t> convolverEpoch{0};
    std::array<std::atomic<int32_t>,32> widthStatus{};
    std::array<std::atomic<int32_t>,176> widthAdvancedStatus{};
    std::atomic<uint32_t> widthEpoch{0};
    std::array<std::atomic<int32_t>,20> headphoneStatus{};
    std::array<std::atomic<int32_t>,headphone::kDiagnosticWords> headphoneDiagnostics{};
    std::atomic<uint32_t> headphoneEpoch{0};
    std::array<std::atomic<int32_t>,24> presetCrossfeedStatus{};
    std::atomic<uint32_t> presetCrossfeedEpoch{0};
    std::array<std::atomic<int32_t>,reverbtuning::kRuntimeWords> reverbStatus{};
    std::array<std::atomic<int32_t>,reverbtuning::kDecayWords> reverbDecay{};
    std::atomic<uint32_t> reverbEpoch{0};
    std::array<std::atomic<int32_t>,ROOM_REVERB_RUNTIME_WORDS> roomReverbStatus{};
    std::atomic<uint32_t> roomReverbEpoch{0};
    std::array<std::atomic<int32_t>,TIMBRE_RUNTIME_WORDS> timbreStatus{};
    std::atomic<uint32_t> timbreEpoch{0};
    std::array<std::atomic<int32_t>,COMPANDER_RUNTIME_WORDS> companderStatus{};
    std::array<std::atomic<int32_t>,COMPANDER_RESPONSE_WORDS> companderResponse{};
    std::atomic<uint32_t> companderEpoch{0};
    std::array<std::atomic<int32_t>,64> toneStatus{};
    std::array<std::atomic<int32_t>,200> toneResponse{};
    std::atomic<uint32_t> toneEpoch{0};
    std::array<std::atomic<int32_t>,DDC_RUNTIME_WORDS> ddcStatus{};
    std::array<std::atomic<int32_t>,DDC_RESPONSE_WORDS> ddcResponse{};
    std::atomic<uint32_t> ddcEpoch{0};
    std::array<std::atomic<int32_t>,tubetuning::kRuntimeWords> tubeStatus{};
    std::atomic<uint32_t> tubeEpoch{0};
    std::array<std::atomic<int32_t>,analogtuning::kRuntimeWords> analogStatus{};
    std::atomic<uint32_t> analogEpoch{0};
    std::array<std::atomic<int32_t>,dynamictuning::kRuntimeWords> dynamicStatus{};
    std::array<std::atomic<int32_t>,dynamictuning::kResponseWords> dynamicResponse{};
    std::atomic<uint32_t> dynamicEpoch{0};
    uint64_t convolverRevision=0;
    std::array<int32_t,7> diffSettings{0,500,0,1000,0,150,12000};
    std::array<int32_t,7> desiredDiffSettings=diffSettings;
    diffadvanced::Settings diffAdvanced=diffadvanced::defaults,desiredDiffAdvanced=diffadvanced::defaults;
    bool hasDiffSettings=false;
    uint64_t diffRequestRevision=0;
    bool jamesEnabled=true, viperEnabled=true, configured=false;
    int32_t balance=0;
    float balanceCurrent=0.f, balanceSmoothing=.002f;
    uint32_t stringChunks=0,stringWritten=0,impulseChunks=0,impulseWritten=0;
    dspfusion::ToneProcessor tone;
    dspfusion::PeqProcessor peq;
    dspfusion::DynamicPeqProcessor dynamicPeq;
    dspfusion::CreativeEffectsProcessor creative;
    dspfusion::ExpansionEffectsProcessor expansion;
    dspfusion::SpeakerOptimizationProcessor speaker;
    dspfusion::SurroundProcessor surround;
    dspfusion::OutputSafetyLimiter outputSafety;
    std::array<float,kBlockFrames*2> first{},second{};
    Fusion() {
        for(unsigned index=0;index<professionalStatus.size();++index)
            if(imagingUnavailableValue(131086+index))professionalStatus[index].store(std::numeric_limits<int32_t>::min());
        for(unsigned index=0;index<diffStatus.size();++index)
            if((index>=7 && index<=16) || (index>=24 && diffadvanced::signalReading(index-24)))
                diffStatus[index].store(std::numeric_limits<int32_t>::min());
        convolverStatus[0].store(1);
        convolverStatus[11].store(1000);
        for(unsigned index=19;index<=30;++index)convolverStatus[index].store(std::numeric_limits<int32_t>::min());
        tubeStatus[0].store(1);tubeStatus[13].store(1);tubeStatus[29].store(1000);tubeStatus[30].store(2000);
        for(unsigned index=16;index<=23;++index)tubeStatus[index].store(std::numeric_limits<int32_t>::min());
        timbreStatus[0].store(1);timbreStatus[13].store(1);timbreStatus[31].store(2000);
        for(unsigned i=16;i<=23;++i)timbreStatus[i].store(std::numeric_limits<int32_t>::min());
        timbreStatus[27].store(std::numeric_limits<int32_t>::min());timbreStatus[28].store(std::numeric_limits<int32_t>::min());
        for(unsigned i=40;i<=43;++i)timbreStatus[i].store(std::numeric_limits<int32_t>::min());
        companderStatus[0].store(1);companderStatus[31].store(2000);companderStatus[45].store(200);companderStatus[48].store(20);
        for(unsigned i=16;i<24;++i)companderStatus[i].store(std::numeric_limits<int32_t>::min());
        companderStatus[41].store(std::numeric_limits<int32_t>::min());
        companderResponse[0].store(1);companderResponse[5].store(32);
        toneStatus[0].store(1);toneStatus[14].store(100);toneStatus[31].store(2000);
        for(unsigned i=16;i<24;++i)toneStatus[i].store(std::numeric_limits<int32_t>::min());
        toneResponse[0].store(1);toneResponse[4].store(32);
        std::array<int32_t,DDC_RUNTIME_WORDS> ddcDefaults{};DdcStatus(nullptr,ddcDefaults.data());
        for(unsigned i=0;i<ddcDefaults.size();++i)ddcStatus[i].store(ddcDefaults[i]);
        ddcResponse[0].store(1);ddcResponse[4].store(32);
        dynamicStatus[0].store(1);dynamicStatus[31].store(2000);dynamicStatus[47].store(3);
        for(unsigned i=16;i<=23;++i)dynamicStatus[i].store(std::numeric_limits<int32_t>::min());
        dynamicStatus[28].store(std::numeric_limits<int32_t>::min());dynamicStatus[45].store(std::numeric_limits<int32_t>::min());
        dynamicResponse[0].store(1);dynamicResponse[6].store(32);
        analogStatus[0].store(1);analogStatus[13].store(1);analogStatus[29].store(1000);analogStatus[30].store(2000);
        for(unsigned index=16;index<=23;++index)analogStatus[index].store(std::numeric_limits<int32_t>::min());
    }
    ~Fusion() {
        if(james) JDSP_AELI.release_effect(james);
        if(viper) VIPER_AELI.release_effect(viper);
    }
};

void armVisualization(Fusion& f) {
    visualization::Requests request;
    for(unsigned i=0;i<3;++i)request.token[i]=f.visualizationRequests[i].load(std::memory_order_acquire);
    const auto rate=f.hostRate.load(std::memory_order_relaxed);
    if(request.token==f.visualizationApplied.token && rate==f.visualizationRate)return;
    if(!childCommand(f.viper,visualization::kArmCommand,sizeof(request),&request)) {
        f.visualizationApplied=request;f.visualizationRate=rate;
        f.visualizationRefreshFrames=0;
    }
}
void clearVisualization(Fusion& f) {
    visualization::Requests disabled;
    childCommand(f.viper,visualization::kArmCommand,sizeof(disabled),&disabled);
    f.visualizationApplied=disabled;f.visualizationRate=f.visualizationRefreshFrames=0;
    f.visualizationEpoch.fetch_add(1,std::memory_order_acq_rel);
    for(auto& word:f.visualizationEnergy)word.store(0,std::memory_order_relaxed);
    for(auto& word:f.visualizationField)word.store(0,std::memory_order_relaxed);
    f.visualizationEpoch.fetch_add(1,std::memory_order_release);
}
void snapshotVisualization(Fusion& f,uint32_t frames) {
    bool armed=false;
    for(const auto token:f.visualizationApplied.token)armed|=(token&1u)!=0;
    if(!armed)return;
    f.visualizationRefreshFrames+=frames;
    const auto window=std::max(1u,f.hostRate.load(std::memory_order_relaxed)/20u);
    if(f.visualizationRefreshFrames<window)return;
    f.visualizationRefreshFrames%=window;
    visualization::Snapshot snapshot;uint32_t bytes=sizeof(snapshot);
    if((*f.viper)->command(f.viper,visualization::kReadCommand,0,nullptr,&bytes,&snapshot) || bytes!=sizeof(snapshot))return;
    std::array<int32_t,visualization::kEnergyWords> energy{};
    energy[0]=1;energy[2]=f.hostRate.load(std::memory_order_relaxed);
    energy[3]=snapshot.clarity[1];energy[4]=snapshot.spectrum[1];
    energy[6]=snapshot.clarity[2];energy[7]=snapshot.spectrum[2];
    energy[8]=snapshot.clarity[6];energy[9]=snapshot.spectrum[6];
    energy[10]=snapshot.clarity[5];energy[11]=snapshot.spectrum[5];
    if(f.enabled.load() && f.viperEnabled) {
        if((snapshot.clarity[4]&7)==3)energy[5]|=1;
        if((snapshot.spectrum[4]&7)==3)energy[5]|=2;
        if(snapshot.clarity[4]&1)energy[5]|=4;
        if(snapshot.spectrum[4]&1)energy[5]|=8;
        if(snapshot.clarity[4]&4)energy[5]|=16;
        if(snapshot.spectrum[4]&4)energy[5]|=32;
    } else snapshot.field[4]=0;
    f.visualizationEpoch.fetch_add(1,std::memory_order_acq_rel);
    for(unsigned i=0;i<energy.size();++i)f.visualizationEnergy[i].store(energy[i],std::memory_order_relaxed);
    if(f.visualizationApplied.token[2]&1u)
        for(unsigned i=0;i<snapshot.field.size();++i)f.visualizationField[i].store(snapshot.field[i],std::memory_order_relaxed);
    f.visualizationEpoch.fetch_add(1,std::memory_order_release);
}

int legacyStatusIndex(uint32_t id) {
    if(id>=19999 && id<=20002)return static_cast<int>(id-19999);
    if(id>=30000 && id<=30003)return static_cast<int>(id-30000+4);
    return id==kLiveprogStatus?8:-1;
}
void snapshotLegacyStatus(Fusion& f) {
    for(uint32_t id:{19999u,20000u,20001u,20002u,30000u,30001u,30002u,30003u,kLiveprogStatus}) {
        std::array<uint32_t,5> request{0,4,4,id,0},response{};uint32_t bytes=sizeof(response);
        if(!(*f.james)->command(f.james,EFFECT_CMD_GET_PARAM,16,request.data(),&bytes,response.data()) &&
           bytes==sizeof(response) && !response[0])
            f.legacyStatus[legacyStatusIndex(id)].store(static_cast<int32_t>(response[4]),std::memory_order_relaxed);
    }
}

void snapshotConvolver(Fusion& f) {
    std::array<uint32_t,36> response{};
    std::array<uint32_t,4> request{0,4,128,131213};uint32_t bytes=sizeof(response);
    if((*f.james)->command(f.james,EFFECT_CMD_GET_PARAM,sizeof(request),request.data(),&bytes,response.data()) || bytes!=sizeof(response) || response[0])return;
    auto* values=reinterpret_cast<int32_t*>(response.data()+4);
    if(!f.configured || !f.hostRate.load())values[2]=0;
    if(!f.enabled.load() || !f.jamesEnabled || !(values[3]&2) || !values[2]) {
        values[3]&=~12;values[10]=0;values[31]=0;
        std::fill(values+19,values+31,std::numeric_limits<int32_t>::min());
    }
    const auto epoch=f.convolverEpoch.fetch_add(1,std::memory_order_acq_rel)+2;
    values[1]=epoch&0x7fffffffu;
    for(unsigned i=0;i<32;++i)f.convolverStatus[i].store(values[i],std::memory_order_relaxed);
    std::array<uint32_t,4> advancedRequest{0,4,80,131214};std::array<uint32_t,24> advancedReply{};uint32_t advancedBytes=sizeof(advancedReply);
    if(!(*f.james)->command(f.james,EFFECT_CMD_GET_PARAM,sizeof(advancedRequest),advancedRequest.data(),&advancedBytes,advancedReply.data()) && advancedBytes==sizeof(advancedReply)) {
        auto* advanced=reinterpret_cast<int32_t*>(advancedReply.data()+4);advanced[1]=epoch&0x7fffffffu;advanced[2]=values[2];
        for(unsigned i=0;i<20;++i)f.convolutionAdvancedStatus[i].store(advanced[i],std::memory_order_relaxed);
    }
    f.convolverEpoch.fetch_add(1,std::memory_order_release);
}
void clearConvolver(Fusion& f){childCommand(f.james,FUSION_CONV_CLEAR);snapshotConvolver(f);}

void snapshotWidth(Fusion& f) {
    std::array<uint32_t,4> request{0,4,128,131215};std::array<uint32_t,36> response{};uint32_t bytes=sizeof(response);
    if((*f.james)->command(f.james,EFFECT_CMD_GET_PARAM,sizeof(request),request.data(),&bytes,response.data()) || bytes!=sizeof(response) || response[0])return;
    auto* v=reinterpret_cast<int32_t*>(response.data()+4);
    if(!f.configured || !f.hostRate.load())v[2]=0;
    if(!f.enabled.load() || !f.jamesEnabled || !v[3]) {
        v[3]=v[24]=v[25]=v[26]=v[30]=0;
        for(unsigned i=5;i<=14;++i)v[i]=std::numeric_limits<int32_t>::min();v[31]=std::numeric_limits<int32_t>::min();
    }
    const auto epoch=f.widthEpoch.fetch_add(1,std::memory_order_acq_rel)+2;v[1]=epoch&0x7fffffffu;
    for(unsigned i=0;i<32;++i)f.widthStatus[i].store(v[i],std::memory_order_relaxed);
    std::array<uint32_t,4> advancedRequest{0,4,704,131216};std::array<uint32_t,180> advancedReply{};uint32_t advancedBytes=sizeof(advancedReply);
    if(!(*f.james)->command(f.james,EFFECT_CMD_GET_PARAM,sizeof(advancedRequest),advancedRequest.data(),&advancedBytes,advancedReply.data()) && advancedBytes==sizeof(advancedReply)) {
        auto* a=reinterpret_cast<int32_t*>(advancedReply.data()+4);a[1]=v[1];a[2]=v[2];
        if(!v[3]){a[3]=a[5]=a[6]=a[44]=0;for(int i=7;i<19;i++)a[i]=std::numeric_limits<int32_t>::min();std::fill(a+48,a+176,0);}
        for(unsigned i=0;i<176;i++)f.widthAdvancedStatus[i].store(a[i],std::memory_order_relaxed);
    }
    f.widthEpoch.fetch_add(1,std::memory_order_release);
}
void clearWidth(Fusion& f){childCommand(f.james,FUSION_WIDTH_CLEAR);snapshotWidth(f);}
void snapshotPresetCrossfeed(Fusion& f){
    std::array<uint32_t,4> request{0,4,96,131220};std::array<uint32_t,28> response{};uint32_t bytes=sizeof(response);
    std::array<int32_t,24> values{};values[0]=1;values[20]=values[21]=std::numeric_limits<int32_t>::min();
    if(f.configured && !(*f.james)->command(f.james,EFFECT_CMD_GET_PARAM,16,request.data(),&bytes,response.data()) && bytes==sizeof(response) && !response[0])std::memcpy(values.data(),response.data()+4,96);
    if(!f.enabled.load() || !f.jamesEnabled){values[3]=values[5]=values[6]=values[8]=values[9]=0;values[20]=values[21]=std::numeric_limits<int32_t>::min();}
    f.presetCrossfeedEpoch.fetch_add(1,std::memory_order_acq_rel);
    for(unsigned i=0;i<24;++i)f.presetCrossfeedStatus[i].store(values[i],std::memory_order_relaxed);
    f.presetCrossfeedEpoch.fetch_add(1,std::memory_order_release);
}
void clearPresetCrossfeed(Fusion& f){childCommand(f.james,FUSION_PRESET_XF_CLEAR);snapshotPresetCrossfeed(f);}

void snapshotRoomReverb(Fusion& f) {
    std::array<uint32_t,4> request{0,4,ROOM_REVERB_RUNTIME_WORDS*4,131224};
    std::array<uint32_t,ROOM_REVERB_RUNTIME_WORDS+4> response{};uint32_t bytes=sizeof(response);
    std::array<int32_t,ROOM_REVERB_RUNTIME_WORDS> values{};values[0]=1;
    std::fill(values.begin()+16,values.begin()+20,std::numeric_limits<int32_t>::min());
    if(f.configured && !(*f.james)->command(f.james,EFFECT_CMD_GET_PARAM,16,request.data(),&bytes,response.data()) && bytes==sizeof(response) && !response[0])
        std::memcpy(values.data(),response.data()+4,ROOM_REVERB_RUNTIME_WORDS*4);
    if(!f.configured || !f.hostRate.load()){values[2]=0;values[21]=0;}
    if(!f.enabled.load() || !f.jamesEnabled || !values[3] || !values[2]) {
        values[3]=values[5]=values[6]=values[7]=values[8]=values[9]=values[10]=values[20]=0;
        std::fill(values.begin()+16,values.begin()+20,std::numeric_limits<int32_t>::min());
    }
    const auto epoch=f.roomReverbEpoch.fetch_add(1,std::memory_order_acq_rel)+2;values[1]=epoch&0x7fffffffu;
    for(unsigned i=0;i<values.size();++i)f.roomReverbStatus[i].store(values[i],std::memory_order_relaxed);
    f.roomReverbEpoch.fetch_add(1,std::memory_order_release);
}
void clearRoomReverb(Fusion& f) {
    std::array<uint32_t,5> packet{0,4,4,1426,0};
    childCommand(f.james,EFFECT_CMD_SET_PARAM,sizeof(packet),packet.data());snapshotRoomReverb(f);
}

void snapshotTone(Fusion& f) {
    std::array<int32_t,64> values{};f.tone.status(values.data(),values.size());
    std::array<int32_t,200> graph{};f.tone.response(graph.data(),graph.size());
    if(!f.configured || !f.hostRate.load()) {
        values[2]=values[13]=values[34]=0;std::fill(values.begin()+51,values.begin()+61,0);
        graph={};graph[0]=1;graph[4]=32;
    }
    if(!f.enabled.load()) {
        values[3]=values[5]=values[6]=values[7]=values[8]=values[11]=values[12]=values[13]=0;
        std::fill(values.begin()+16,values.begin()+24,std::numeric_limits<int32_t>::min());
        graph={};graph[0]=1;graph[2]=values[2];graph[4]=32;
    }
    const auto epoch=f.toneEpoch.fetch_add(1,std::memory_order_acq_rel)+2;values[1]=graph[1]=epoch&0x7fffffffu;
    for(unsigned i=0;i<values.size();++i)f.toneStatus[i].store(values[i],std::memory_order_relaxed);
    for(unsigned i=0;i<graph.size();++i)f.toneResponse[i].store(graph[i],std::memory_order_relaxed);
    f.toneEpoch.fetch_add(1,std::memory_order_release);
}
void clearTone(Fusion& f) {f.tone.clearTools();snapshotTone(f);}

void snapshotDdc(Fusion& f) {
    std::array<int32_t,DDC_RUNTIME_WORDS> values{};DdcStatus(nullptr,values.data());
    std::array<int32_t,DDC_RESPONSE_WORDS> graph{};graph[0]=1;graph[4]=32;
    std::array<uint32_t,4> request{0,4,DDC_RUNTIME_WORDS*4,131238};
    std::array<uint32_t,DDC_RESPONSE_WORDS+4> reply{};uint32_t bytes=16+DDC_RUNTIME_WORDS*4;
    if(f.configured && !(*f.james)->command(f.james,EFFECT_CMD_GET_PARAM,16,request.data(),&bytes,reply.data()) && bytes==16+DDC_RUNTIME_WORDS*4 && !reply[0])std::memcpy(values.data(),reply.data()+4,DDC_RUNTIME_WORDS*4);
    request[2]=DDC_RESPONSE_WORDS*4;request[3]=131239;bytes=sizeof(reply);
    if(f.configured && !(*f.james)->command(f.james,EFFECT_CMD_GET_PARAM,16,request.data(),&bytes,reply.data()) && bytes==sizeof(reply) && !reply[0])std::memcpy(graph.data(),reply.data()+4,DDC_RESPONSE_WORDS*4);
    values[16]=int32_t(f.hostRate.load());
    if(!f.configured || !values[16]){
        values[2]=values[15]=values[16]=values[17]=0;std::fill(values.begin()+44,values.begin()+48,0);
        graph={};graph[0]=1;graph[4]=32;
    }
    if(!f.enabled.load() || !f.jamesEnabled || !values[3] || !values[2]) {
        values[3]=values[6]=values[7]=values[8]=values[9]=values[10]=values[11]=values[12]=values[13]=values[14]=values[15]=0;
        std::fill(values.begin()+20,values.begin()+28,std::numeric_limits<int32_t>::min());
        graph={};graph[0]=1;graph[2]=values[2];graph[4]=32;
    }
    const auto epoch=f.ddcEpoch.fetch_add(1,std::memory_order_acq_rel)+2;values[1]=graph[1]=epoch&0x7fffffffu;
    for(unsigned i=0;i<values.size();++i)f.ddcStatus[i].store(values[i],std::memory_order_relaxed);
    for(unsigned i=0;i<graph.size();++i)f.ddcResponse[i].store(graph[i],std::memory_order_relaxed);
    f.ddcEpoch.fetch_add(1,std::memory_order_release);
}
void clearDdc(Fusion& f) {
    std::array<uint32_t,5> packet{0,4,4,kDdcClear,0};childCommand(f.james,EFFECT_CMD_SET_PARAM,sizeof(packet),packet.data());snapshotDdc(f);
}

void snapshotCompander(Fusion& f) {
    std::array<int32_t,COMPANDER_RUNTIME_WORDS> values{};CompanderStatus(nullptr,values.data());
    std::fill(values.begin()+16,values.begin()+24,std::numeric_limits<int32_t>::min());values[41]=std::numeric_limits<int32_t>::min();
    std::array<int32_t,COMPANDER_RESPONSE_WORDS> graph{};graph[0]=1;graph[5]=32;
    std::array<uint32_t,4> request{0,4,COMPANDER_RUNTIME_WORDS*4,131231};
    std::array<uint32_t,COMPANDER_RESPONSE_WORDS+4> reply{};uint32_t bytes=16+COMPANDER_RUNTIME_WORDS*4;
    if(f.configured && !(*f.james)->command(f.james,EFFECT_CMD_GET_PARAM,16,request.data(),&bytes,reply.data()) && bytes==16+COMPANDER_RUNTIME_WORDS*4 && !reply[0])std::memcpy(values.data(),reply.data()+4,COMPANDER_RUNTIME_WORDS*4);
    request[2]=COMPANDER_RESPONSE_WORDS*4;request[3]=131232;bytes=sizeof(reply);
    if(f.configured && !(*f.james)->command(f.james,EFFECT_CMD_GET_PARAM,16,request.data(),&bytes,reply.data()) && bytes==sizeof(reply) && !reply[0])std::memcpy(graph.data(),reply.data()+4,COMPANDER_RESPONSE_WORDS*4);
    values[26]=f.hostRate.load();
    if(!f.configured || !values[26]){values[2]=values[25]=0;std::fill(values.begin()+64,values.begin()+78,0);}
    if(!f.enabled.load() || !f.jamesEnabled || !values[3] || !values[2]) {
        values[3]=values[5]=values[6]=values[7]=values[8]=values[9]=values[10]=values[24]=values[25]=values[27]=values[28]=0;
        std::fill(values.begin()+16,values.begin()+24,std::numeric_limits<int32_t>::min());values[41]=std::numeric_limits<int32_t>::min();
        graph={};graph[0]=1;graph[5]=32;
    }
    const auto epoch=f.companderEpoch.fetch_add(1,std::memory_order_acq_rel)+2;values[1]=graph[1]=epoch&0x7fffffffu;
    for(unsigned i=0;i<values.size();++i)f.companderStatus[i].store(values[i],std::memory_order_relaxed);
    for(unsigned i=0;i<graph.size();++i)f.companderResponse[i].store(graph[i],std::memory_order_relaxed);
    f.companderEpoch.fetch_add(1,std::memory_order_release);
}
void clearCompander(Fusion& f) {
    std::array<uint32_t,5> packet{0,4,4,1432,0};childCommand(f.james,EFFECT_CMD_SET_PARAM,sizeof(packet),packet.data());snapshotCompander(f);
}

void snapshotTimbre(Fusion& f) {
    constexpr unsigned words=TIMBRE_RUNTIME_WORDS;
    std::array<uint32_t,4> request{0,4,words*4,131227};
    std::array<uint32_t,words+4> response{};uint32_t bytes=sizeof(response);
    std::array<int32_t,words> values{};values[0]=1;values[13]=1;values[31]=2000;
    std::fill(values.begin()+16,values.begin()+24,std::numeric_limits<int32_t>::min());
    values[27]=values[28]=std::numeric_limits<int32_t>::min();
    std::fill(values.begin()+40,values.begin()+44,std::numeric_limits<int32_t>::min());
    if(f.configured && !(*f.james)->command(f.james,EFFECT_CMD_GET_PARAM,16,request.data(),&bytes,response.data()) && bytes==sizeof(response) && !response[0])
        std::memcpy(values.data(),response.data()+4,words*4);
    values[26]=f.hostRate.load();
    if(!f.configured || !values[26]){
        values[2]=0;values[25]=0;
        std::fill(values.begin()+32,values.begin()+39,0);
    }
    if(!f.enabled.load() || !f.jamesEnabled || !values[3] || !values[2]) {
        values[3]=values[5]=values[6]=values[7]=values[8]=values[9]=values[10]=values[24]=values[25]=0;
        values[46]=0;
        std::fill(values.begin()+16,values.begin()+24,std::numeric_limits<int32_t>::min());
        values[27]=values[28]=std::numeric_limits<int32_t>::min();
        std::fill(values.begin()+40,values.begin()+44,std::numeric_limits<int32_t>::min());
    }
    const auto epoch=f.timbreEpoch.fetch_add(1,std::memory_order_acq_rel)+2;values[1]=epoch&0x7fffffffu;
    for(unsigned i=0;i<words;++i)f.timbreStatus[i].store(values[i],std::memory_order_relaxed);
    f.timbreEpoch.fetch_add(1,std::memory_order_release);
}
void clearTimbre(Fusion& f) {
    std::array<uint32_t,5> packet{0,4,4,1429,0};
    childCommand(f.james,EFFECT_CMD_SET_PARAM,sizeof(packet),packet.data());snapshotTimbre(f);
}

void snapshotProfessional(Fusion& f) {
    std::array<int32_t,40> next{};
    bool imagingChanged=false;
    for(unsigned index=0;index<f.professionalStatus.size();++index) {
        const uint32_t id=131086+index;
        std::array<uint32_t,5> request{0,4,4,id,0},response{};
        uint32_t bytes=sizeof(response);
        const bool imagingLevel=imagingUnavailableValue(id);
        int32_t value=imagingLevel?std::numeric_limits<int32_t>::min():0;
        if(f.configured && f.hostRate.load()!=0 && !(*f.viper)->command(f.viper,EFFECT_CMD_GET_PARAM,16,request.data(),&bytes,response.data()) &&
            bytes==sizeof(response) && response[0]==0) value=static_cast<int32_t>(response[4]);
        // Targets remain visible while stopped; actual processing state must not stay stale.
        if(((id>=131099 && id<=131102) || (id>=131106 && id<=131115) || id>=131121) && (!f.enabled.load() || !f.viperEnabled))
            value=imagingLevel?std::numeric_limits<int32_t>::min():0;
        next[index]=value;
        if(id>=131103 && value!=f.professionalStatus[index].load(std::memory_order_relaxed))imagingChanged=true;
    }
    if(imagingChanged)f.imagingSnapshotEpoch.fetch_add(1,std::memory_order_acq_rel);
    for(unsigned index=0;index<next.size();++index)f.professionalStatus[index].store(next[index],std::memory_order_relaxed);
    if(imagingChanged)f.imagingSnapshotEpoch.fetch_add(1,std::memory_order_release);
    std::array<int32_t,84> diffNext{};
    bool diffChanged=false;
    for(unsigned index=0;index<diffNext.size();++index) {
        if(index==20)continue; // 131148 is this bridge's publication epoch.
        const uint32_t id=131128+index;
        const bool meter=(index>=7 && index<=16) || (index>=24 && diffadvanced::signalReading(index-24));
        int32_t value=meter?std::numeric_limits<int32_t>::min():0;
        std::array<uint32_t,5> request{0,4,4,id,0},response{};
        uint32_t bytes=sizeof(response);
        if(f.configured && f.hostRate.load()!=0 &&
           !(*f.viper)->command(f.viper,EFFECT_CMD_GET_PARAM,16,request.data(),&bytes,response.data()) &&
           bytes==sizeof(response) && response[0]==0)value=static_cast<int32_t>(response[4]);
        if((meter || (index>=17 && index<=19) || (index>=24 && diffadvanced::activeReading(index-24))) && (!f.enabled.load() || !f.viperEnabled))
            value=meter?std::numeric_limits<int32_t>::min():0;
        diffNext[index]=value;
        if(value!=f.diffStatus[index].load(std::memory_order_relaxed))diffChanged=true;
    }
    if(diffChanged)f.diffSnapshotEpoch.fetch_add(1,std::memory_order_acq_rel);
    for(unsigned index=0;index<diffNext.size();++index)f.diffStatus[index].store(diffNext[index],std::memory_order_relaxed);
    if(diffChanged)f.diffSnapshotEpoch.fetch_add(1,std::memory_order_release);
}
void snapshotHeadphone(Fusion& f) {
    std::array<uint32_t,4> request{0,4,80,kHeadphoneRuntime};
    std::array<uint32_t,24> response{};uint32_t bytes=sizeof(response);
    std::array<int32_t,20> next{};next[0]=1;
    if(f.configured && !(*f.viper)->command(f.viper,EFFECT_CMD_GET_PARAM,16,request.data(),&bytes,response.data()) &&
       bytes==sizeof(response) && !response[0])std::memcpy(next.data(),response.data()+4,80);
    if(!f.enabled.load() || !f.viperEnabled){next[3]=0;next[5]=0;next[6]=0;next[8]=next[9]=0;}
    f.headphoneEpoch.fetch_add(1,std::memory_order_acq_rel);
    for(unsigned i=0;i<20;++i)f.headphoneStatus[i].store(next[i],std::memory_order_relaxed);
    constexpr unsigned diagnosticBytes=headphone::kDiagnosticWords*4;
    std::array<uint32_t,4> diagnosticRequest{0,4,diagnosticBytes,131221};
    std::array<uint32_t,headphone::kDiagnosticWords+4> diagnosticReply{};uint32_t diagnosticSize=sizeof(diagnosticReply);
    std::array<int32_t,headphone::kDiagnosticWords> diagnostic{};diagnostic[0]=1;
    if(f.configured && !(*f.viper)->command(f.viper,EFFECT_CMD_GET_PARAM,16,diagnosticRequest.data(),&diagnosticSize,diagnosticReply.data()) && diagnosticSize==sizeof(diagnosticReply) && !diagnosticReply[0])std::memcpy(diagnostic.data(),diagnosticReply.data()+4,diagnosticBytes);
    if(!f.enabled.load() || !f.viperEnabled)diagnostic[5]=diagnostic[6]=std::numeric_limits<int32_t>::min();
    for(unsigned i=0;i<diagnostic.size();++i)f.headphoneDiagnostics[i].store(diagnostic[i],std::memory_order_relaxed);
    f.headphoneEpoch.fetch_add(1,std::memory_order_release);
}
void clearHeadphone(Fusion& f) {
    std::array<uint32_t,5> packet{0,4,4,65642,0};
    childCommand(f.viper,EFFECT_CMD_SET_PARAM,sizeof(packet),packet.data());snapshotHeadphone(f);
}
void snapshotReverb(Fusion& f){
    constexpr unsigned words=reverbtuning::kRuntimeWords;
    std::array<uint32_t,4> request{0,4,words*4,131222};std::array<uint32_t,words+4> response{};uint32_t bytes=sizeof(response);
    std::array<int32_t,words> values{};values[0]=1;std::fill(values.begin()+16,values.begin()+22,std::numeric_limits<int32_t>::min());
    if(f.configured && !(*f.viper)->command(f.viper,EFFECT_CMD_GET_PARAM,16,request.data(),&bytes,response.data()) && bytes==sizeof(response) && !response[0])std::memcpy(values.data(),response.data()+4,words*4);
    if(!f.configured || !f.hostRate.load())values[2]=0;
    if(!f.enabled.load() || !f.viperEnabled){values[3]=values[5]=values[6]=values[7]=values[8]=values[22]=values[24]=values[30]=0;std::fill(values.begin()+16,values.begin()+22,std::numeric_limits<int32_t>::min());}
    f.reverbEpoch.fetch_add(1,std::memory_order_acq_rel);
    for(unsigned i=0;i<words;++i)f.reverbStatus[i].store(values[i],std::memory_order_relaxed);
    constexpr unsigned decayWords=reverbtuning::kDecayWords;
    std::array<uint32_t,4> decayRequest{0,4,decayWords*4,131223};
    std::array<uint32_t,decayWords+4> decayReply{};uint32_t decayBytes=sizeof(decayReply);
    std::array<int32_t,decayWords> decay{};decay[0]=1;
    if(f.configured && !(*f.viper)->command(f.viper,EFFECT_CMD_GET_PARAM,16,decayRequest.data(),&decayBytes,decayReply.data()) &&
       decayBytes==sizeof(decayReply) && !decayReply[0])std::memcpy(decay.data(),decayReply.data()+4,decayWords*4);
    if(!f.configured || !f.hostRate.load())decay[2]=0;
    if(!f.enabled.load() || !f.viperEnabled){decay[3]=0;decay[13]=0;}
    for(unsigned i=0;i<decayWords;++i)f.reverbDecay[i].store(decay[i],std::memory_order_relaxed);
    f.reverbEpoch.fetch_add(1,std::memory_order_release);
}
void clearReverb(Fusion& f){std::array<uint32_t,5> packet{0,4,4,65647,0};childCommand(f.viper,EFFECT_CMD_SET_PARAM,sizeof(packet),packet.data());snapshotReverb(f);}
void snapshotTube(Fusion& f) {
    constexpr unsigned words=tubetuning::kRuntimeWords;
    std::array<uint32_t,4> request{0,4,words*4,131225};
    std::array<uint32_t,words+4> response{};uint32_t bytes=sizeof(response);
    std::array<int32_t,words> values{};values[0]=1;values[13]=1;values[29]=1000;values[30]=2000;
    std::fill(values.begin()+16,values.begin()+24,std::numeric_limits<int32_t>::min());
    if(f.configured && !(*f.viper)->command(f.viper,EFFECT_CMD_GET_PARAM,16,request.data(),&bytes,response.data()) && bytes==sizeof(response) && !response[0])
        std::memcpy(values.data(),response.data()+4,words*4);
    if(!f.configured || !f.hostRate.load()){values[2]=0;values[25]=0;}
    if(!f.enabled.load() || !f.viperEnabled || !values[3] || !values[2]) {
        values[3]=values[5]=values[6]=values[7]=values[8]=values[9]=values[10]=values[24]=values[25]=values[28]=0;
        std::fill(values.begin()+16,values.begin()+24,std::numeric_limits<int32_t>::min());
    }
    const auto epoch=f.tubeEpoch.fetch_add(1,std::memory_order_acq_rel)+2;values[1]=epoch&0x7fffffffu;
    for(unsigned i=0;i<words;++i)f.tubeStatus[i].store(values[i],std::memory_order_relaxed);
    f.tubeEpoch.fetch_add(1,std::memory_order_release);
}
void clearTube(Fusion& f) {
    std::array<uint32_t,5> packet{0,4,4,65651,0};
    childCommand(f.viper,EFFECT_CMD_SET_PARAM,sizeof(packet),packet.data());snapshotTube(f);
}
void snapshotAnalog(Fusion& f) {
    constexpr unsigned words=analogtuning::kRuntimeWords;
    std::array<uint32_t,4> request{0,4,words*4,131226};
    std::array<uint32_t,words+4> response{};uint32_t bytes=sizeof(response);
    std::array<int32_t,words> values{};values[0]=1;values[13]=1;values[29]=1000;values[30]=2000;
    std::fill(values.begin()+16,values.begin()+24,std::numeric_limits<int32_t>::min());
    if(f.configured && !(*f.viper)->command(f.viper,EFFECT_CMD_GET_PARAM,16,request.data(),&bytes,response.data()) && bytes==sizeof(response) && !response[0])
        std::memcpy(values.data(),response.data()+4,words*4);
    if(!f.configured || !f.hostRate.load()){values[2]=0;values[25]=0;}
    if(!f.enabled.load() || !f.viperEnabled || !values[3] || !values[2]) {
        values[3]=values[5]=values[6]=values[7]=values[8]=values[9]=values[10]=values[24]=values[25]=values[28]=0;
        std::fill(values.begin()+16,values.begin()+24,std::numeric_limits<int32_t>::min());
    }
    const auto epoch=f.analogEpoch.fetch_add(1,std::memory_order_acq_rel)+2;values[1]=epoch&0x7fffffffu;
    for(unsigned i=0;i<words;++i)f.analogStatus[i].store(values[i],std::memory_order_relaxed);
    f.analogEpoch.fetch_add(1,std::memory_order_release);
}
void clearAnalog(Fusion& f) {
    std::array<uint32_t,5> packet{0,4,4,65654,0};
    childCommand(f.viper,EFFECT_CMD_SET_PARAM,sizeof(packet),packet.data());snapshotAnalog(f);
}
void snapshotDynamic(Fusion& f) {
    constexpr unsigned words=dynamictuning::kRuntimeWords, responseWords=dynamictuning::kResponseWords;
    std::array<uint32_t,4> request{0,4,words*4,131228};
    std::array<uint32_t,responseWords+4> reply{};uint32_t bytes=16+words*4;
    std::array<int32_t,words> values{};values[0]=1;values[31]=2000;values[47]=3;
    std::fill(values.begin()+16,values.begin()+24,std::numeric_limits<int32_t>::min());
    values[28]=values[45]=std::numeric_limits<int32_t>::min();
    if(f.configured && !(*f.viper)->command(f.viper,EFFECT_CMD_GET_PARAM,16,request.data(),&bytes,reply.data()) && bytes==16+words*4 && !reply[0])
        std::memcpy(values.data(),reply.data()+4,words*4);
    std::array<int32_t,responseWords> curve{};curve[0]=1;curve[6]=32;
    request[2]=responseWords*4;request[3]=131229;bytes=16+responseWords*4;reply.fill(0);
    if(f.configured && !(*f.viper)->command(f.viper,EFFECT_CMD_GET_PARAM,16,request.data(),&bytes,reply.data()) && bytes==16+responseWords*4 && !reply[0])
        std::memcpy(curve.data(),reply.data()+4,responseWords*4);
    values[26]=f.hostRate.load();
    if(!f.configured || !values[26]) {
        values[2]=values[25]=0;std::fill(values.begin()+32,values.begin()+38,0);values[43]=values[44]=0;
        curve.fill(0);curve[0]=1;curve[6]=32;
    }
    if(!f.enabled.load() || !f.viperEnabled || !values[3] || !values[2]) {
        values[3]=values[5]=values[6]=values[7]=values[8]=values[9]=values[10]=values[24]=values[25]=values[27]=0;
        std::fill(values.begin()+16,values.begin()+24,std::numeric_limits<int32_t>::min());
        values[28]=values[45]=std::numeric_limits<int32_t>::min();curve[5]=0;
    }
    const auto epoch=f.dynamicEpoch.fetch_add(1,std::memory_order_acq_rel)+2;values[1]=curve[1]=epoch&0x7fffffffu;
    for(unsigned i=0;i<words;++i)f.dynamicStatus[i].store(values[i],std::memory_order_relaxed);
    for(unsigned i=0;i<responseWords;++i)f.dynamicResponse[i].store(curve[i],std::memory_order_relaxed);
    f.dynamicEpoch.fetch_add(1,std::memory_order_release);
}
void clearDynamic(Fusion& f) {
    std::array<uint32_t,5> packet{0,4,4,65657,0};
    childCommand(f.viper,EFFECT_CMD_SET_PARAM,sizeof(packet),packet.data());snapshotDynamic(f);
}
void clearAudition(Fusion& f) {
    std::array<uint32_t,5> packet{0,4,4,65602,0};
    childCommand(f.viper,EFFECT_CMD_SET_PARAM,sizeof(packet),packet.data());
    packet[3]=65609;
    childCommand(f.viper,EFFECT_CMD_SET_PARAM,sizeof(packet),packet.data());
    packet[3]=65634;
    childCommand(f.viper,EFFECT_CMD_SET_PARAM,sizeof(packet),packet.data());
    snapshotProfessional(f);
    clearHeadphone(f);
    clearReverb(f);
    clearTube(f);
    clearAnalog(f);clearDynamic(f);
}

void mergeConfig(buffer_config_t& target,const buffer_config_t& source) {
    if(source.mask&EFFECT_CONFIG_BUFFER) target.buffer=source.buffer;
    if(source.mask&EFFECT_CONFIG_SMP_RATE) target.samplingRate=source.samplingRate;
    if(source.mask&EFFECT_CONFIG_CHANNELS) target.channels=source.channels;
    if(source.mask&EFFECT_CONFIG_FORMAT) target.format=source.format;
    if(source.mask&EFFECT_CONFIG_ACC_MODE) target.accessMode=source.accessMode;
    if(source.mask&EFFECT_CONFIG_PROVIDER) target.bufferProvider=source.bufferProvider;
    target.mask|=source.mask;
}
int32_t setConfig(Fusion& f,const effect_config_t& cfg,bool* processingChanged=nullptr) {
    if(processingChanged) *processingChanged=false;
    effect_config_t next=f.config;
    mergeConfig(next.inputCfg,cfg.inputCfg); mergeConfig(next.outputCfg,cfg.outputCfg);
    const auto& in=next.inputCfg; const auto& out=next.outputCfg;
    if(in.samplingRate<8000 || in.samplingRate>768000 || in.samplingRate!=out.samplingRate ||
       in.channels!=AUDIO_CHANNEL_OUT_STEREO || out.channels!=AUDIO_CHANNEL_OUT_STEREO ||
       !supportedFormat(in.format) || !supportedFormat(out.format) ||
       (out.accessMode!=EFFECT_BUFFER_ACCESS_WRITE && out.accessMode!=EFFECT_BUFFER_ACCESS_ACCUMULATE)) return -EINVAL;
    // The children always process stereo float in fixed-size blocks. A host
    // frame-count, PCM format or access-mode change only affects this wrapper;
    // reconfiguring the children at the same rate clears ViPER's delay/filter
    // history and interrupts an otherwise continuous stream.
    const bool rebuild=!f.configured || f.config.inputCfg.samplingRate!=in.samplingRate;
    if(rebuild) {
        effect_config_t internal=next;
        internal.inputCfg.format=internal.outputCfg.format=AUDIO_FORMAT_PCM_FLOAT;
        internal.inputCfg.accessMode=EFFECT_BUFFER_ACCESS_READ;
        internal.outputCfg.accessMode=EFFECT_BUFFER_ACCESS_WRITE;
        internal.inputCfg.buffer={}; internal.outputCfg.buffer={};
        internal.inputCfg.buffer.frameCount=internal.outputCfg.buffer.frameCount=kBlockFrames;
        internal.inputCfg.mask=internal.outputCfg.mask=EFFECT_CONFIG_ALL;
        internal.inputCfg.bufferProvider={}; internal.outputCfg.bufferProvider={};
        int32_t ret=childCommand(f.james,EFFECT_CMD_SET_CONFIG,sizeof(internal),&internal);
        if(ret) return ret;
        ret=childCommand(f.viper,EFFECT_CMD_SET_CONFIG,sizeof(internal),&internal);
        if(ret) return ret;
        f.tone.configure(in.samplingRate);
        f.peq.configure(in.samplingRate);
        f.dynamicPeq.configure(in.samplingRate);
        f.creative.configure(in.samplingRate);
        f.expansion.configure(in.samplingRate);
        f.speaker.configure(in.samplingRate);
        f.surround.configure(in.samplingRate);
        f.outputSafety.configure(in.samplingRate);
        f.balanceSmoothing=1.f-std::exp(-1.f/(.01f*in.samplingRate));
    }
    f.config=next;
    f.ioFormat.store(in.format | (uint32_t(out.format)<<8) | (uint32_t(out.accessMode==EFFECT_BUFFER_ACCESS_ACCUMULATE)<<16));
    f.configured=true;
    if(!rebuild) return 0;
    f.bassFrequency.store(0); f.bassGain.store(0);
    // Reserve upstream James block memory at configuration time, not its first
    // callback. The fixed wrapper work buffers are allocated with the instance.
    std::fill(f.first.begin(),f.first.end(),0.f);
    audio_buffer_t a{},b{}; a.frameCount=b.frameCount=kBlockFrames;
    a.f32=f.first.data(); b.f32=f.second.data();
    (*f.james)->process(f.james,&a,&b);
    if(processingChanged) *processingChanged=true;
    return 0;
}
int32_t statusReply(uint32_t* size,void* data,int32_t status) {
    if(!size || !data || *size<sizeof(status)) return -EINVAL;
    std::memcpy(data,&status,sizeof(status)); *size=sizeof(status); return 0;
}
bool finiteValues(const uint8_t* bytes,size_t length) {
    for(size_t i=0;i<length;i+=4) { float v; std::memcpy(&v,bytes+i,4); if(!std::isfinite(v)) return false; }
    return true;
}
template<class T> T valueAt(const void* bytes,size_t index=0) {
    T value; std::memcpy(&value,static_cast<const uint8_t*>(bytes)+sizeof(T)*index,sizeof(T)); return value;
}

// Bound all packets before entering upstream implementations, which predate
// strict effect_param_t validation. These checks also prevent partial transfers
// from being committed and make shared DDC/IR/LiveProg uploads deterministic.
int validateJames(Fusion& f,uint32_t id,const uint8_t* data,uint32_t n) {
    if(id==kLiveprogParameter) {
        // Fixed wire layout: zero-padded ASCII identifier[64], followed by a
        // little-endian IEEE double. Never create arbitrary VM variables.
        if(n!=72 || !data[0])return -EINVAL;
        unsigned end=0;
        for(;end<64 && data[end];++end) {
            const unsigned c=data[end];
            if(!((c>='a' && c<='z') || (c>='A' && c<='Z') || c=='_' || (end && c>='0' && c<='9')))return -EINVAL;
        }
        if(end==64)return -EINVAL;
        for(unsigned i=end;i<64;++i)if(data[i])return -EINVAL;
        std::array<char,64> name{};
        for(unsigned i=0;i<end;++i)name[i]=data[i]>='A' && data[i]<='Z'?data[i]+('a'-'A'):data[i];
        if(!std::strcmp(name.data(),"srate") || !std::strcmp(name.data(),"spl0") || !std::strcmp(name.data(),"spl1"))return -EINVAL;
        double value;std::memcpy(&value,data+64,8);
        return std::isfinite(value)?0:-EINVAL;
    }
    if(id==kDdcAdvanced || id==kDdcTools) {
        const unsigned words=id==kDdcAdvanced?DDC_ADVANCED_WORDS:DDC_TOOLS_WORDS;
        if(n!=words*4)return -EINVAL;
        std::array<int32_t,DDC_TOOLS_WORDS> v{};std::memcpy(v.data(),data,n);
        return (id==kDdcAdvanced?DdcValidate(v.data(),words):DdcToolsValidate(v.data(),words))?0:-EINVAL;
    }
    if(id==kDdcClear || id==kDdcOff || id==kDdcOriginal)return n==4&&valueAt<int32_t>(data)==0?0:-EINVAL;
    if(id==1430 || id==1431) {
        const unsigned words=id==1430?COMPANDER_WORDS:COMPANDER_TOOLS_WORDS;
        if(n!=words*4)return -EINVAL;
        std::array<int32_t,COMPANDER_TOOLS_WORDS> values{};std::memcpy(values.data(),data,n);
        return (id==1430?CompanderValidate(values.data(),words):CompanderToolsValidate(values.data(),words))?0:-EINVAL;
    }
    if(id==1432)return n==4 && valueAt<int32_t>(data)==0?0:-EINVAL;
    if(id==1424 || id==1425){
        const unsigned words=id==1424?ROOM_REVERB_WORDS:ROOM_REVERB_TOOLS_WORDS;
        if(n!=words*4)return -EINVAL;
        std::array<int32_t,ROOM_REVERB_TOOLS_WORDS> v{};std::memcpy(v.data(),data,n);
        return (id==1424?RoomReverbValidate(v.data(),words):RoomReverbToolsValidate(v.data(),words))?0:-EINVAL;
    }
    if(id==1426)return n==4 && valueAt<int32_t>(data)==0?0:-EINVAL;
    if(id==1427 || id==1428){
        const unsigned words=id==1427?TIMBRE_WORDS:TIMBRE_TOOLS_WORDS;
        if(n!=words*4)return -EINVAL;
        std::array<int32_t,TIMBRE_TOOLS_WORDS> values{};std::memcpy(values.data(),data,n);
        return (id==1427?TimbreValidate(values.data(),words):TimbreToolsValidate(values.data(),words))?0:-EINVAL;
    }
    if(id==1429)return n==4 && valueAt<int32_t>(data)==0?0:-EINVAL;
    if(id==1422 || id==1423){
        const unsigned words=id==1422?10:21;
        if(n!=words*4)return -EINVAL;std::array<int32_t,21> v{};std::memcpy(v.data(),data,n);
        return (id==1422?PresetCrossfeedAdvancedValidate(v.data(),words):PresetCrossfeedAdvancedToolsValidate(v.data(),words))?0:-EINVAL;
    }
    if(id==1420 || id==1421){
        const unsigned words=id==1420?PRESET_XF_WORDS:PRESET_XF_TOOLS_WORDS;
        if(n!=words*4)return -EINVAL;std::array<int32_t,PRESET_XF_TOOLS_WORDS> v{};std::memcpy(v.data(),data,n);
        return (id==1420?PresetCrossfeedValidate(v.data(),words):PresetCrossfeedToolsValidate(v.data(),words))?0:-EINVAL;
    }
    if(id==1410) {
        if(n!=104)return -EINVAL;std::array<int32_t,26> v{};std::memcpy(v.data(),data,104);return StereoWidthValidate(v.data(),v.size())?0:-EINVAL;
    }
    if(id==1412) {
        if(n!=240)return -EINVAL;std::array<int32_t,60> v{};std::memcpy(v.data(),data,240);return StereoWidthAdvancedValidate(v.data(),v.size())?0:-EINVAL;
    }
    if(id==1413){int32_t v=n==4?valueAt<int32_t>(data):-1;return n==4 && (v==0 || (v>=4 && v<=9))?0:-EINVAL;}
    if(id==1411)return n==4 && valueAt<int32_t>(data)>=0 && valueAt<int32_t>(data)<=3?0:-EINVAL;
    if(id==1402) {
        if(n!=76)return -EINVAL;std::array<int32_t,19> v{};std::memcpy(v.data(),data,76);return convolution::validAdvanced(v.data(),19)?0:-EINVAL;
    }
    if(id==1400) {
        constexpr int32_t low[]{0,-240,-240,0,-120,-120,0,0,0,0},high[]{1000,120,0,200000,120,120,1,1,1,0};
        if(n!=40)return -EINVAL;
        for(unsigned i=0;i<10;++i)if(valueAt<int32_t>(data,i)<low[i]||valueAt<int32_t>(data,i)>high[i])return -EINVAL;
        return 0;
    }
    if(id==1401)return n==4 && valueAt<int32_t>(data)>=0 && valueAt<int32_t>(data)<=3?0:-EINVAL;
    if(id==1300) {
        if(n!=28) return -EINVAL;
        const int32_t lower[7]={0,0,1,50,5,5,5}, upper[7]={150,249,250,400,1000,2000,5000};
        for(size_t i=0;i<7;++i)
            if(valueAt<int32_t>(data,i)<lower[i] || valueAt<int32_t>(data,i)>upper[i]) return -EINVAL;
        return valueAt<int32_t>(data,1)<valueAt<int32_t>(data,2)?0:-EINVAL;
    }
    if(id==8888) {
        if(n!=8) return -EINVAL;
        int32_t count=valueAt<int32_t>(data),unit=valueAt<int32_t>(data,1);
        if(count<=0 || unit!=256 || uint64_t(count)*unit>kMaxTransfer) return -EINVAL;
        f.stringChunks=count; f.stringWritten=0; return 0;
    }
    if(id==12001) {
        if(n!=256 || f.stringWritten>=f.stringChunks) return -EINVAL;
        ++f.stringWritten; return 0;
    }
    if(id==10006 || id==10009 || id==10010) {
        if(n!=2 || !f.stringChunks || f.stringWritten!=f.stringChunks) return -EINVAL;
        f.stringChunks=f.stringWritten=0; return 0;
    }
    if(id==9999) {
        if(n!=16) return -EINVAL;
        int32_t length=valueAt<int32_t>(data),channels=valueAt<int32_t>(data,1),chunks=valueAt<int32_t>(data,3);
        if(length<=0 || (channels!=1 && channels!=2 && channels!=4) || chunks<=0 ||
           length%channels || uint64_t(chunks)*4096*4>kMaxTransfer ||
           uint64_t(length)>uint64_t(chunks)*4096 || uint64_t(chunks-1)*4096>=uint64_t(length)) return -EINVAL;
        f.impulseChunks=chunks; f.impulseWritten=0; return 0;
    }
    if(id==12000) {
        if(n!=16384 || f.impulseWritten>=f.impulseChunks || !finiteValues(data,n)) return -EINVAL;
        ++f.impulseWritten; return 0;
    }
    if(id==10004) {
        if(n!=2 || !f.impulseChunks || f.impulseWritten!=f.impulseChunks) return -EINVAL;
        f.impulseChunks=f.impulseWritten=0; return 0;
    }
    if(id>=25000 && id<=25003) return n==4 ? 0 : -EINVAL;
    if(id==1500 || id==115 || id==116) {
        const uint32_t expected=id==1500?12:(id==115?68:128);
        if(n!=expected || !finiteValues(data,n)) return -EINVAL;
        if(id==115) {
            if(valueAt<float>(data)<.06f || valueAt<float>(data)>.3f || valueAt<float>(data,1)<0 ||
               valueAt<float>(data,1)>4 || valueAt<float>(data,2)<0 || valueAt<float>(data,2)>3) return -EINVAL;
        }
        if(id==116 && (valueAt<float>(data)<0 || valueAt<float>(data)>5)) return -EINVAL;
        if(id==115 || id==116) {
            const size_t count=id==115?7:15,offset=id==115?3:2;
            float previous=0;
            for(size_t i=0;i<count;++i) {
                float freq=valueAt<float>(data,offset+i),gain=valueAt<float>(data,offset+count+i);
                if(freq<=previous || freq>(id==115?23999.999f:192000.f) || std::abs(gain)>(id==115?1.2f:100.f)) return -EINVAL;
                previous=freq;
            }
        }
        return 0;
    }
    if(n!=2) return -EINVAL;
    int16_t v=valueAt<int16_t>(data);
    switch(id) {
        case 128: return v>=0 && v<=18 ? 0 : -EINVAL;
        case 137: return v>=0 && v<=100 ? 0 : -EINVAL;
        case 150: return v>=-3000 && v<=12000 ? 0 : -EINVAL;
        case 188: return v>=0 && v<=5 ? 0 : -EINVAL;
        case 112: return v>=0 && v<=30 ? 0 : -EINVAL;
        case 1200: case 1201: case 1202: case 1203: case 1204: case 1205:
        case 1206: case 1208: case 1210: case 1212: case 1213: return (v==0 || v==1)?0:-EINVAL;
        default: return -EINVAL;
    }
}
int validateViper(uint32_t id,const uint8_t* data,uint32_t n) {
    if(id==65649 || id==65650) {
        const unsigned words=id==65649?tubetuning::kWords:tubetuning::kToolsWords;
        if(n!=words*4)return -EINVAL;
        std::array<int32_t,tubetuning::kToolsWords> values{};std::memcpy(values.data(),data,n);
        return (id==65649?tubetuning::valid(values.data(),words):tubetuning::validTools(values.data(),words))?0:-EINVAL;
    }
    if(id==65651)return n==4 && valueAt<int32_t>(data)==0?0:-EINVAL;
    if(id==65652 || id==65653) {
        const unsigned words=id==65652?analogtuning::kWords:analogtuning::kToolsWords;
        if(n!=words*4)return -EINVAL;
        std::array<int32_t,analogtuning::kToolsWords> values{};std::memcpy(values.data(),data,n);
        return (id==65652?analogtuning::valid(values.data(),words):analogtuning::validTools(values.data(),words))?0:-EINVAL;
    }
    if(id==65654)return n==4 && valueAt<int32_t>(data)==0?0:-EINVAL;
    if(id==65655 || id==65656) {
        const unsigned words=id==65655?dynamictuning::kWords:dynamictuning::kToolsWords;
        if(n!=words*4)return -EINVAL;
        std::array<int32_t,dynamictuning::kToolsWords> values{};std::memcpy(values.data(),data,n);
        return (id==65655?dynamictuning::valid(values.data(),words):dynamictuning::validTools(values.data(),words))?0:-EINVAL;
    }
    if(id==65657)return n==4 && valueAt<int32_t>(data)==0?0:-EINVAL;
    if(id==65645 || id==65646 || id==65648){
        if((id==65645 && n!=reverbtuning::kWords*4) || (id==65648 && n!=reverbtuning::kAdvancedWords*4) ||
           (id==65646 && n!=reverbtuning::kToolsWords*4 && n!=reverbtuning::kAdvancedToolsWords*4))return -EINVAL;
        const unsigned words=n/4;std::array<int32_t,reverbtuning::kAdvancedToolsWords> values{};std::memcpy(values.data(),data,n);
        return (id==65645?reverbtuning::valid(values.data(),words):id==65648?reverbtuning::validAdvanced(values.data(),words):reverbtuning::validTools(values.data(),words))?0:-EINVAL;
    }
    if(id==65647)return -EINVAL;
    if(id==65643 || id==65644){
        const unsigned words=id==65643?headphone::kAdvancedWords:headphone::kAdvancedToolsWords;
        if(n!=words*4)return -EINVAL;std::array<int32_t,headphone::kAdvancedToolsWords> v{};std::memcpy(v.data(),data,n);
        return (id==65643?headphone::validAdvanced(v.data(),words):headphone::validAdvancedTools(v.data(),words))?0:-EINVAL;
    }
    if(id==65640 || id==65641){
        const size_t words=id==65640?headphone::kWords:headphone::kToolsWords;
        if(n!=words*4)return -EINVAL;
        std::array<int32_t,headphone::kToolsWords> values{};std::memcpy(values.data(),data,n);
        return (id==65640?headphone::valid(values.data(),words):headphone::validTools(values.data(),words))?0:-EINVAL;
    }
    if(id==65642)return -EINVAL;
    if(id==65635) {
        diffadvanced::Settings values{};if(n!=sizeof(values))return -EINVAL;
        std::memcpy(values.data(),data,n);return diffadvanced::valid(values.data(),values.size())?0:-EINVAL;
    }
    if(id==65636)return n==4 && valueAt<int32_t>(data)>=0 && valueAt<int32_t>(data)<=4?0:-EINVAL;
    if(id==65632) {
        std::array<int32_t,7> values{};
        if(n!=sizeof(values))return -EINVAL;
        std::memcpy(values.data(),data,n);
        const int32_t low[7]={0,0,0,0,0,40,2000},high[7]={1,2000,1,1000,1,2000,20000};
        for(unsigned index=0;index<values.size();++index)
            if(values[index]<low[index] || values[index]>high[index])return -EINVAL;
        return values[5]<values[6]?0:-EINVAL;
    }
    if(id==65633)return n==sizeof(int32_t) && valueAt<int32_t>(data)>=0 && valueAt<int32_t>(data)<=2?0:-EINVAL;
    if(id==65634)return -EINVAL; // Only Fusion's lifecycle cleanup may issue this.
    if(id>=65627 && id<=65630) {
        std::array<int32_t,8> values{};
        if(n>sizeof(values) || n%sizeof(int32_t)!=0)return -EINVAL;
        std::memcpy(values.data(),data,n);
        return professional::validImagingAdvanced(id,values.data(),n/sizeof(int32_t))?0:-EINVAL;
    }
    if(id==65631)return n==sizeof(int32_t) && valueAt<int32_t>(data)>=0 && valueAt<int32_t>(data)<=1?0:-EINVAL;
    if(id==65608) {
        std::array<int32_t,13> values{};
        if(n!=sizeof(values)) return -EINVAL;
        std::memcpy(values.data(),data,n);
        return professional::validImaging(values.data(),values.size())?0:-EINVAL;
    }
    if(id>=65604 && id<=65607) {
        const size_t counts[4]={4,4,8,9};
        const size_t count=counts[id-65604];
        if(n!=count*sizeof(int32_t)) return -EINVAL;
        std::array<int32_t,9> values{};std::memcpy(values.data(),data,n);
        const bool valid=id==65604?professional::validSelective(values.data(),count):
            id==65605?professional::validNonlinear(values.data(),count):
            id==65606?professional::validDynamic(values.data(),count):professional::validDeess(values.data(),count);
        return valid?0:-EINVAL;
    }
    if(id==65600 || id==65601) {
        const size_t count=id==65600?11:7;
        if(n!=count*sizeof(int32_t)) return -EINVAL;
        std::array<int32_t,11> values{};std::memcpy(values.data(),data,n);
        return (id==65600?professional::validClarity(values.data(),count):professional::validSpectrum(values.data(),count))?0:-EINVAL;
    }
    // Removed effects must not silently accept values from an older app.
    if(id==65551 || id==65552 || (id>=65586 && id<=65588) || id==65603) return -ENOSYS;
    if(id==65544) return n==4 && valueAt<int32_t>(data)==0 ? 0 : -ENOSYS;
    if(id==65538 || (id>=65540 && id<=65542) || id==65545 || id==65547 ||
       (id>=65565 && id<=65568) || (id>=65610 && id<=65626)) return -ENOSYS;
    const bool pair=id==65570 || id==65571 || id==65572;
    if(n!=(pair?8u:4u)) return -EINVAL;
    int v=valueAt<int32_t>(data),second=pair?valueAt<int32_t>(data,1):0;
    switch(id) {
        case 65546: case 65548: case 65553: case 65557: case 65559:
        case 65569: case 65574: case 65578: case 65581: case 65583: case 65584:
        case 0x9002: return v==0 || v==1 ? 0 : -EINVAL;
        case 0x9003: return 0;
        case 65543: case 65560: case 65561: case 65562: case 65563: case 65564:
            return v>=0 && v<=100 ? 0 : -EINVAL;
        case 65549: return v>=100 && v<=20000 ? 0 : -EINVAL;
        case 65550: return v>=0 && v<=560 ? 0 : -EINVAL;
        case 65554: case 65555: return v>=0 && v<=1000 ? 0 : -EINVAL;
        case 65556: return v>=0 && v<=32767 ? 0 : -EINVAL;
        case 65558: return v>=0 && v<=5000 ? 0 : -EINVAL;
        case 65570: case 65571: return v>0 && v<=24000 && second>0 && second<=24000 ? 0 : -EINVAL;
        case 65572: return v>=0 && v<=1000 && second>=0 && second<=1000 ? 0 : -EINVAL;
        case 65573: case 65577: case 65580: return v>=0 && v<=2100 ? 0 : -EINVAL;
        case 65575: case 65579: case 65582: case 65585: return v>=0 && v<=2 ? 0 : -EINVAL;
        case 65576: return v>=15 && v<=500 ? 0 : -EINVAL;
        case 65590: return v>=30 && v<=200 ? 0 : -EINVAL;
        case 65591: return v>=20 && v<=80 ? 0 : -EINVAL;
        case 65592: return v>=60 && v<=160 ? 0 : -EINVAL;
        case 65593: case 65594: return v>=-120 && v<=120 ? 0 : -EINVAL;
        case 65595: case 65596: return v>=10 && v<=200 ? 0 : -EINVAL;
        default: return -EINVAL;
    }
}

int32_t parameter(Fusion& f,bool set,uint32_t size,void* data,uint32_t* replySize,void* reply) {
    if(!data || size<sizeof(effect_param_t)+4 || !replySize || !reply) return -EINVAL;
    auto* packet=static_cast<effect_param_t*>(data);
    if(packet->psize!=4 || packet->vsize>kMaxTransfer) return -EINVAL;
    uint32_t id=valueAt<uint32_t>(packet->data);
    const auto* values=reinterpret_cast<const uint8_t*>(packet->data+4);
    if(set) {
        if(packet->vsize>size-sizeof(effect_param_t)-4 || *replySize<4) return -EINVAL;
        if(id==kVisualizationArm) {
            if(packet->vsize!=8 || size!=sizeof(effect_param_t)+12)return statusReply(replySize,reply,-EINVAL);
            const int32_t module=valueAt<int32_t>(values),enabled=valueAt<int32_t>(values,1);
            if(module<1 || module>3 || (enabled!=0 && enabled!=1))return statusReply(replySize,reply,-EINVAL);
            auto& request=f.visualizationRequests[module-1];
            auto before=request.load(std::memory_order_relaxed);
            while((before&1u)!=static_cast<uint32_t>(enabled)) {
                const auto next=((before+2u)&0x7ffffffeu)|static_cast<uint32_t>(enabled);
                if(request.compare_exchange_weak(before,next,std::memory_order_acq_rel))break;
            }
            return statusReply(replySize,reply,0);
        }
        if((id==kLiveprogParameter || id==10010 || id==1213 || id==25002 || (id>=kDdcAdvanced && id<=kDdcOriginal) || id==8888 || id==12001 || id==10009 || id==1212 || id==25001 || (id>=kToneAdvanced && id<=kToneOff) || id==kTone || (id>=1430 && id<=1432) || (id>=1424 && id<=1429) || (id>=65649 && id<=65657) || id==1422 || id==1423 || id==65648 || id==1420 || id==1421 || id==65645 || id==65646 || id==65643 || id==65644 || id==65640 || id==65641 || id==1410 || id==1411 || id==1412 || id==1413 || id==1400 || id==1401 || id==1402 || id==1300 || id==65600 || id==65601 || (id>=65604 && id<=65608) || (id>=65627 && id<=65636) || (id>=65590 && id<=65596)) && size!=sizeof(effect_param_t)+4+packet->vsize)
            return statusReply(replySize,reply,-EINVAL);
        int32_t status=0;
        if(id==kJamesEnable || id==kViperEnable) {
            if(packet->vsize!=4 || (valueAt<int32_t>(values)!=0 && valueAt<int32_t>(values)!=1)) status=-EINVAL;
            else if(id==kJamesEnable) {
                if(f.jamesEnabled!=(valueAt<int32_t>(values)!=0)){childCommand(f.james,LIVEPROG_PCM_RESET);childCommand(f.james,EQ_PCM_RESET);}
                f.jamesEnabled=valueAt<int32_t>(values)!=0;
                f.liveprogPcmParentEnabled.store(f.jamesEnabled,std::memory_order_release);
                if(!f.jamesEnabled) { f.bassFrequency.store(0); f.bassGain.store(0); clearConvolver(f);clearWidth(f);clearPresetCrossfeed(f);clearRoomReverb(f);clearTimbre(f);clearCompander(f);clearDdc(f); }
            }
            else {f.viperEnabled=valueAt<int32_t>(values)!=0;f.spectrumPcmParentEnabled.store(f.viperEnabled,std::memory_order_release);if(!f.viperEnabled) {clearAudition(f);clearVisualization(f);}}
        } else if(id==kBalance) {
            if(packet->vsize!=4 || valueAt<int32_t>(values)<-100 || valueAt<int32_t>(values)>100) status=-EINVAL;
            else f.balance=valueAt<int32_t>(values);
        } else if(id==dspfusion::SpeakerOptimizationProcessor::kSet) {
            std::array<int32_t,dspfusion::SpeakerOptimizationProcessor::kWords> parameters{};
            if(packet->vsize!=sizeof(parameters))status=-EINVAL;
            else {std::memcpy(parameters.data(),values,sizeof(parameters));if(!f.speaker.setParameters(parameters.data(),parameters.size()))status=-EINVAL;}
        } else if(id>=dspfusion::ExpansionEffectsProcessor::kSetFirst && id<=dspfusion::ExpansionEffectsProcessor::kSetLast) {
            std::array<int32_t,dspfusion::ExpansionEffectsProcessor::kWords> parameters{};
            if(packet->vsize!=sizeof(parameters) || size!=sizeof(effect_param_t)+4+packet->vsize)status=-EINVAL;
            else {std::memcpy(parameters.data(),values,sizeof(parameters));if(!f.expansion.setParameters(id-dspfusion::ExpansionEffectsProcessor::kSetFirst,parameters.data(),parameters.size()))status=-EINVAL;}
        } else if(id==dspfusion::ExpansionEffectsProcessor::kProfileSet) {
            std::array<float,dspfusion::ExpansionEffectsProcessor::kProfileWords> profile{};
            if(packet->vsize!=sizeof(profile) || size!=sizeof(effect_param_t)+4+packet->vsize)status=-EINVAL;
            else {std::memcpy(profile.data(),values,sizeof(profile));if(!f.expansion.setMatchProfile(profile.data(),profile.size()))status=-EINVAL;}
        } else if(id>=dspfusion::CreativeEffectsProcessor::kSetFirst && id<=dspfusion::CreativeEffectsProcessor::kSetLast) {
            std::array<int32_t,dspfusion::CreativeEffectsProcessor::kWords> parameters{};
            if(packet->vsize!=sizeof(parameters) || size!=sizeof(effect_param_t)+4+packet->vsize)status=-EINVAL;
            else {std::memcpy(parameters.data(),values,sizeof(parameters));if(!f.creative.setParameters(id-dspfusion::CreativeEffectsProcessor::kSetFirst,parameters.data(),parameters.size()))status=-EINVAL;}
        } else if(id==dspfusion::DynamicPeqProcessor::kSet) {
            std::array<int32_t,dspfusion::DynamicPeqProcessor::kMaxWords> parameters{};
            if(packet->vsize<12 || packet->vsize>sizeof(parameters) || packet->vsize%4 || size!=sizeof(effect_param_t)+4+packet->vsize)status=-EINVAL;
            else {
                std::memcpy(parameters.data(),values,packet->vsize);
                if(!f.dynamicPeq.setParameters(parameters.data(),packet->vsize/4))status=-EINVAL;
            }
        } else if(id==kPeq) {
            std::array<int32_t,dspfusion::PeqProcessor::kMaxWords> parameters{};
            if(packet->vsize<12 || packet->vsize>sizeof(parameters) || packet->vsize%4) status=-EINVAL;
            else {
                std::memcpy(parameters.data(),values,packet->vsize);
                if(!f.peq.setParameters(parameters.data(),packet->vsize/4)) status=-EINVAL;
            }
        } else if(id==kSurroundProfessional) {
            std::array<int32_t,dspfusion::SurroundProcessor::kProfessionalWords> p{};
            if(packet->vsize!=sizeof(p))status=-EINVAL;
            else {std::memcpy(p.data(),values,sizeof(p));if(!f.surround.setProfessionalParameters(p.data(),p.size()))status=-EINVAL;}
        } else if(id==kSurroundListening) {
            std::array<int32_t,dspfusion::SurroundListeningTools::kWords> p{};
            if(packet->vsize!=sizeof(p))status=-EINVAL;
            else {std::memcpy(p.data(),values,sizeof(p));if(!f.surround.setListeningTools(p.data(),p.size()))status=-EINVAL;}
            if(!f.enabled.load())f.surround.cancelAudition();
        } else if(id==kSurroundAudition) {
            if(packet->vsize!=4 || !f.surround.setAudition(valueAt<int32_t>(values)))status=-EINVAL;
            if(!f.enabled.load())f.surround.cancelAudition();
        } else if(id==kSurround) {
            std::array<int32_t,dspfusion::SurroundProcessor::kWords> parameters{};
            if(packet->vsize!=sizeof(parameters)) status=-EINVAL;
            else {
                std::memcpy(parameters.data(),values,sizeof(parameters));
                if(!f.surround.setParameters(parameters.data(),parameters.size())) status=-EINVAL;
            }
        } else if(id==kToneAdvanced || id==kToneTools) {
            const size_t words=id==kToneAdvanced?80:164;
            if(packet->vsize!=words*4)status=-EINVAL;
            else {
                std::array<int32_t,164> parameters{};std::memcpy(parameters.data(),values,words*4);
                const bool valid=id==kToneAdvanced?dspfusion::ToneProcessor::validateAdvanced(parameters.data(),words):dspfusion::ToneProcessor::validateTools(parameters.data(),words);
                if(!valid)status=-EINVAL;
                else if(!(id==kToneAdvanced?f.tone.setAdvanced(parameters.data(),words):f.tone.setTools(parameters.data(),words)))status=-EINVAL;
                if(!status){if(!f.enabled.load())clearTone(f);else snapshotTone(f);}
            }
        } else if(id==kToneOff) {
            if(packet->vsize!=4 || valueAt<int32_t>(values)!=0)status=-EINVAL;
            else {f.tone.disable();snapshotTone(f);}
        } else if(id==kToneClear) {
            if(packet->vsize!=4 || valueAt<int32_t>(values)!=0)status=-EINVAL;else clearTone(f);
        } else if(id==kTone) {
            std::array<int32_t,11> parameters{};
            if(packet->vsize!=sizeof(parameters)) status=-EINVAL;
            else {
                std::memcpy(parameters.data(),values,sizeof(parameters));
                if(!f.tone.setParameters(parameters.data(),parameters.size())) status=-EINVAL;
                if(!status){if(!f.enabled.load())clearTone(f);else snapshotTone(f);}
            }
        } else {
            bool isViper=(id>=65538 && id<=65657) || id==0x9002 || id==0x9003;
            status=isViper ? validateViper(id,values,packet->vsize) : validateJames(f,id,values,packet->vsize);
            if(!status) {
                uint32_t childReplySize=4;
                auto handle=isViper?f.viper:f.james;
                int32_t result=(*handle)->command(handle,EFFECT_CMD_SET_PARAM,size,data,&childReplySize,&status);
                if(result) status=result;
                // A rejected script/file can still publish an error status.
                if(!isViper)snapshotLegacyStatus(f);
                if((id>=kDdcAdvanced && id<=kDdcOriginal) || id==10009 || id==1212) {
                    if(!f.enabled.load() || !f.jamesEnabled)clearDdc(f);else snapshotDdc(f);
                }
                if(!status && ((id>=65649 && id<=65651) || id==65583)) {
                    if(!f.enabled.load() || !f.viperEnabled)clearTube(f);else snapshotTube(f);
                }
                if(!status && ((id>=65655 && id<=65657) || (id>=65569 && id<=65573))) {
                    if(!f.enabled.load() || !f.viperEnabled)clearDynamic(f);else snapshotDynamic(f);
                }
                if(!status && ((id>=65652 && id<=65654) || id==65584 || id==65585)) {
                    if(!f.enabled.load() || !f.viperEnabled)clearAnalog(f);else snapshotAnalog(f);
                }
                if(!status && (id==65648 || id==65645 || id==65646 || (id>=65559 && id<=65564))){
                    if(!f.enabled.load() || !f.viperEnabled)clearReverb(f);else snapshotReverb(f);
                }
                if(!status && (id==65643 || id==65644 || id==65640 || id==65641 || id==65581 || id==65582)){
                    if(!f.enabled.load() || !f.viperEnabled)clearHeadphone(f);else snapshotHeadphone(f);
                }
                if(!status && ((id>=1424 && id<=1426) || id==1203 || id==128)) {
                    if(!f.enabled.load() || !f.jamesEnabled)clearRoomReverb(f);else snapshotRoomReverb(f);
                }
                if(!status && ((id>=1430 && id<=1432) || id==1200 || id==115)) {
                    if(!f.enabled.load() || !f.jamesEnabled)clearCompander(f);else snapshotCompander(f);
                }
                if(!status && ((id>=1427 && id<=1429) || id==1206 || id==150)) {
                    if(!f.enabled.load() || !f.jamesEnabled)clearTimbre(f);else snapshotTimbre(f);
                }
                if(!status && id==9999)++f.convolverRevision;
                if(!status && (id==1422 || id==1423 || id==1420 || id==1421 || id==1208 || id==188)){
                    if(!f.enabled.load() || !f.jamesEnabled)clearPresetCrossfeed(f);else snapshotPresetCrossfeed(f);
                }
                if(!status && (id==1410 || id==1411 || id==1412 || id==1413 || id==1204)) {
                    if(!f.enabled.load() || !f.jamesEnabled)clearWidth(f);else snapshotWidth(f);
                }
                if(!status && (id==1400 || id==1401 || id==1402 || id==1205 || id==10004 || id==25003)) {
                    if(!f.enabled.load() || !f.jamesEnabled)clearConvolver(f);else snapshotConvolver(f);
                }
                if(!status && id==1201 && valueAt<int16_t>(values)==0) {
                    f.bassFrequency.store(0); f.bassGain.store(0);
                }
                if(!status && (id==65600 || id==65601 || (id>=65604 && id<=65608) || (id>=65627 && id<=65636) || (id>=65553 && id<=65558) || id==65549 || id==65548 || id==65578 || id==65579)) {
                    if(!f.enabled.load() || !f.viperEnabled) clearAudition(f);
                    else snapshotProfessional(f);
                }
            }
        }
        return statusReply(replySize,reply,status);
    }
    if(id==dspfusion::SpeakerOptimizationProcessor::kCapabilityGet) {
        if(packet->vsize!=4 || size!=16 || *replySize<20)return -EINVAL;
        auto* result=static_cast<effect_param_t*>(reply);result->status=0;result->psize=result->vsize=4;
        const int32_t supported=1;std::memcpy(result->data,&id,4);std::memcpy(result->data+4,&supported,4);*replySize=20;return 0;
    }
    if(id==dspfusion::SpeakerOptimizationProcessor::kStatusGet) {
        constexpr uint32_t words=dspfusion::SpeakerOptimizationProcessor::kStatusWords,bytes=16+words*4;
        if(packet->vsize!=words*4 || size!=16 || *replySize<bytes)return -EINVAL;
        auto* result=static_cast<effect_param_t*>(reply);result->status=0;result->psize=4;result->vsize=words*4;
        std::memcpy(result->data,&id,4);auto* values=reinterpret_cast<int32_t*>(result->data+4);
        const bool active=f.enabled.load(std::memory_order_acquire);f.speaker.readStatus(values,active);values[11]=active?1:0;
        *replySize=bytes;return 0;
    }
    if(id==dspfusion::ExpansionEffectsProcessor::kCapabilityGet) {
        if(packet->vsize!=4 || size!=16 || *replySize<20)return -EINVAL;
        auto* result=static_cast<effect_param_t*>(reply);result->status=0;result->psize=result->vsize=4;
        const int32_t supported=1;std::memcpy(result->data,&id,4);std::memcpy(result->data+4,&supported,4);*replySize=20;return 0;
    }
    if(id>=dspfusion::ExpansionEffectsProcessor::kStatusFirst && id<=dspfusion::ExpansionEffectsProcessor::kStatusLast) {
        constexpr uint32_t words=dspfusion::ExpansionEffectsProcessor::kStatusWords,bytes=16+words*4;
        if(packet->vsize!=words*4 || size!=16 || *replySize<bytes)return -EINVAL;
        auto* result=static_cast<effect_param_t*>(reply);result->status=0;result->psize=4;result->vsize=words*4;
        std::memcpy(result->data,&id,4);auto* values=reinterpret_cast<int32_t*>(result->data+4);f.expansion.readStatus(id-dspfusion::ExpansionEffectsProcessor::kStatusFirst,values);
        if(!f.enabled.load(std::memory_order_acquire)){values[5]=values[6]=values[7]=-120000;values[8]=0;values[11]=0;values[12]=values[13]=values[14]=-120000;values[15]=0;}
        *replySize=bytes;return 0;
    }
    if(id==dspfusion::ExpansionEffectsProcessor::kProfileGet) {
        constexpr uint32_t words=dspfusion::ExpansionEffectsProcessor::kProfileWords,bytes=16+words*4;
        if(packet->vsize!=words*4 || size!=16 || *replySize<bytes)return -EINVAL;
        auto* result=static_cast<effect_param_t*>(reply);result->status=0;result->psize=4;result->vsize=words*4;
        std::memcpy(result->data,&id,4);std::array<float,words> profile{};f.expansion.readMatchProfile(profile.data());std::memcpy(result->data+4,profile.data(),sizeof(profile));*replySize=bytes;return 0;
    }
    if(id==dspfusion::CreativeEffectsProcessor::kCapabilityGet) {
        if(packet->vsize!=4 || size!=16 || *replySize<20)return -EINVAL;
        auto* result=static_cast<effect_param_t*>(reply);result->status=0;result->psize=result->vsize=4;
        const int32_t supported=1;std::memcpy(result->data,&id,4);std::memcpy(result->data+4,&supported,4);*replySize=20;return 0;
    }
    if(id>=dspfusion::CreativeEffectsProcessor::kStatusFirst && id<=dspfusion::CreativeEffectsProcessor::kStatusLast) {
        constexpr uint32_t words=dspfusion::CreativeEffectsProcessor::kStatusWords,bytes=16+words*4;
        if(packet->vsize!=words*4 || size!=16 || *replySize<bytes)return -EINVAL;
        auto* result=static_cast<effect_param_t*>(reply);result->status=0;result->psize=4;result->vsize=words*4;
        std::memcpy(result->data,&id,4);auto* values=reinterpret_cast<int32_t*>(result->data+4);f.creative.readStatus(id-dspfusion::CreativeEffectsProcessor::kStatusFirst,values);
        if(!f.enabled.load(std::memory_order_acquire)){values[5]=values[6]=values[7]=-120000;values[8]=values[10]=0;std::fill(values+12,values+28,-120000);}
        *replySize=bytes;return 0;
    }
    if(id==dspfusion::DynamicPeqProcessor::kCapabilityGet) {
        if(packet->vsize!=4 || size!=16 || *replySize<20)return -EINVAL;
        auto* result=static_cast<effect_param_t*>(reply);result->status=0;result->psize=result->vsize=4;
        const int32_t supported=1;std::memcpy(result->data,&id,4);std::memcpy(result->data+4,&supported,4);*replySize=20;return 0;
    }
    if(id==dspfusion::DynamicPeqProcessor::kStatusGet) {
        constexpr uint32_t words=dspfusion::DynamicPeqProcessor::kStatusWords,bytes=16+words*4;
        if(packet->vsize!=words*4 || size!=16 || *replySize<bytes)return -EINVAL;
        auto* result=static_cast<effect_param_t*>(reply);result->status=0;result->psize=4;result->vsize=words*4;
        std::memcpy(result->data,&id,4);auto* values=reinterpret_cast<int32_t*>(result->data+4);f.dynamicPeq.readStatus(values);
        if(!f.enabled.load(std::memory_order_acquire)){
            values[5]=values[6]=-120000;std::fill(values+8,values+24,-120000);std::fill(values+24,values+40,0);
        }
        *replySize=bytes;return 0;
    }
    if(id==kExtendedCapabilities) {
        if(packet->vsize!=4 || *replySize<20)return -EINVAL;
        auto* result=static_cast<effect_param_t*>(reply);result->status=0;result->psize=result->vsize=4;
        const int32_t caps=7|dspfusion::PeqProcessor::kPrecisionCapability|kVisualizationCapability|ROOM_REVERB_PCM_CAPABILITY|tubetuning::kPcmCapability|analogtuning::kPcmCapability|spectrumcapture::kCapability|TIMBRE_PCM_CAPABILITY|dynamictuning::kPcmCapability|COMPANDER_PCM_CAPABILITY|DDC_PCM_CAPABILITY|FUSION_CONV_PCM_CAPABILITY|LIVEPROG_PCM_CAPABILITY|peqcapture::kCapability|EQ_PCM_CAPABILITY;std::memcpy(result->data,&id,4);std::memcpy(result->data+4,&caps,4);*replySize=20;return 0;
    }
    if(id==peqcapture::kGet) {
        constexpr unsigned bytes=16+peqcapture::kWords*4;
        if(packet->vsize!=peqcapture::kWords*4 || size!=16 || *replySize<bytes)return -EINVAL;
        auto* result=static_cast<effect_param_t*>(reply);
        auto* words=reinterpret_cast<int32_t*>(result->data+4);
        const auto host=f.hostRate.load(std::memory_order_acquire);
        result->status=0;result->psize=4;result->vsize=peqcapture::kWords*4;std::memcpy(result->data,&id,4);
        if(!f.enabled.load(std::memory_order_acquire) || !f.peq.readPcm(words) ||
           !f.enabled.load(std::memory_order_acquire) || host!=f.hostRate.load(std::memory_order_acquire) ||
           (words[5] && uint32_t(words[4])!=host))peqcapture::empty(words,host);
        else words[31]=host;
        *replySize=bytes;return 0;
    }
    if(id==EQ_PCM_GET) {
        constexpr unsigned bytes=16+EQ_PCM_WORDS*4;
        if(packet->vsize!=EQ_PCM_WORDS*4 || size!=16 || *replySize<bytes)return -EINVAL;
        auto* result=static_cast<effect_param_t*>(reply);
        const auto host=f.hostRate.load(std::memory_order_acquire);
        auto available=[&]{return f.enabled.load(std::memory_order_acquire) && f.liveprogPcmParentEnabled.load(std::memory_order_acquire);};
        if(!available()) {
            result->status=0;result->psize=4;result->vsize=EQ_PCM_WORDS*4;std::memcpy(result->data,&id,4);
            eqcapture::empty(reinterpret_cast<int32_t*>(result->data+4),host);*replySize=bytes;return 0;
        }
        const auto status=(*f.james)->command(f.james,EFFECT_CMD_GET_PARAM,size,data,replySize,reply);
        if(status)return status;
        if(*replySize!=bytes || result->status)return -EINVAL;
        auto* words=reinterpret_cast<int32_t*>(result->data+4);
        if(!available() || f.hostRate.load(std::memory_order_acquire)!=host)eqcapture::empty(words,host);
        else words[31]=host;
        return 0;
    }
    if(id==LIVEPROG_PCM_GET) {
        constexpr unsigned bytes=16+LIVEPROG_PCM_WORDS*4;
        if(packet->vsize!=LIVEPROG_PCM_WORDS*4 || size!=16 || *replySize<bytes)return -EINVAL;
        auto* result=static_cast<effect_param_t*>(reply);
        const auto host=f.hostRate.load(std::memory_order_acquire);
        auto available=[&]{return f.enabled.load(std::memory_order_acquire) && f.liveprogPcmParentEnabled.load(std::memory_order_acquire);};
        if(!available()) {
            result->status=0;result->psize=4;result->vsize=LIVEPROG_PCM_WORDS*4;std::memcpy(result->data,&id,4);
            liveprogcapture::empty(reinterpret_cast<int32_t*>(result->data+4),host);*replySize=bytes;return 0;
        }
        const auto status=(*f.james)->command(f.james,EFFECT_CMD_GET_PARAM,size,data,replySize,reply);
        if(status)return status;
        if(*replySize!=bytes || result->status)return -EINVAL;
        auto* words=reinterpret_cast<int32_t*>(result->data+4);
        if(!available() || f.hostRate.load(std::memory_order_acquire)!=host)liveprogcapture::empty(words,host);
        else words[31]=host;
        return 0;
    }
    if(id==FUSION_CONV_PCM_GET) {
        constexpr unsigned bytes=16+FUSION_CONV_PCM_WORDS*4;
        if(packet->vsize!=FUSION_CONV_PCM_WORDS*4 || size!=16 || *replySize<bytes)return -EINVAL;
        auto* result=static_cast<effect_param_t*>(reply);
        const auto host=f.hostRate.load(std::memory_order_acquire);
        auto available=[&]{return f.enabled.load(std::memory_order_acquire) && (f.convolverStatus[3].load(std::memory_order_acquire)&4)!=0;};
        if(!available()) {
            result->status=0;result->psize=4;result->vsize=FUSION_CONV_PCM_WORDS*4;std::memcpy(result->data,&id,4);
            ircapture::empty(reinterpret_cast<int32_t*>(result->data+4),host);*replySize=bytes;return 0;
        }
        const auto status=(*f.james)->command(f.james,EFFECT_CMD_GET_PARAM,size,data,replySize,reply);
        if(status)return status;
        if(*replySize!=bytes || result->status)return -EINVAL;
        auto* words=reinterpret_cast<int32_t*>(result->data+4);
        if(!available() || f.hostRate.load(std::memory_order_acquire)!=host)ircapture::empty(words,host);
        else words[31]=host; // Host I/O rate; the observed PCM cadence remains internal words[4].
        return 0;
    }
    if(id==DDC_PCM_GET) {
        constexpr unsigned bytes=16+DDC_PCM_WORDS*4;
        if(packet->vsize!=DDC_PCM_WORDS*4 || size!=16 || *replySize<bytes)return -EINVAL;
        auto* result=static_cast<effect_param_t*>(reply);
        auto available=[&]{return f.enabled.load(std::memory_order_acquire) && f.ddcStatus[3].load(std::memory_order_acquire)!=0;};
        if(!available()) {
            result->status=0;result->psize=4;result->vsize=DDC_PCM_WORDS*4;std::memcpy(result->data,&id,4);
            ddccapture::empty(reinterpret_cast<int32_t*>(result->data+4),f.hostRate.load());*replySize=bytes;return 0;
        }
        const auto status=(*f.james)->command(f.james,EFFECT_CMD_GET_PARAM,size,data,replySize,reply);
        if(status)return status;
        if(*replySize!=bytes || result->status)return -EINVAL;
        if(!available())ddccapture::empty(reinterpret_cast<int32_t*>(result->data+4),f.hostRate.load());
        return 0;
    }
    if(id==COMPANDER_PCM_GET || id==COMPANDER_GAIN_GET) {
        const unsigned words=id==COMPANDER_PCM_GET?COMPANDER_PCM_WORDS:COMPANDER_GAIN_WORDS;
        const unsigned bytes=16+words*4;
        if(packet->vsize!=words*4 || size!=16 || *replySize<bytes)return -EINVAL;
        auto* result=static_cast<effect_param_t*>(reply);
        auto available=[&]{return f.enabled.load(std::memory_order_acquire) && f.companderStatus[3].load(std::memory_order_acquire)!=0;};
        auto empty=[&]{auto* out=reinterpret_cast<int32_t*>(result->data+4);
            if(id==COMPANDER_PCM_GET)compandercapture::empty(out,f.hostRate.load());else compandercapture::emptyGain(out,f.hostRate.load());};
        if(!available()) {
            result->status=0;result->psize=4;result->vsize=words*4;std::memcpy(result->data,&id,4);
            empty();*replySize=bytes;return 0;
        }
        const auto status=(*f.james)->command(f.james,EFFECT_CMD_GET_PARAM,size,data,replySize,reply);
        if(status)return status;
        if(*replySize!=bytes || result->status)return -EINVAL;
        if(!available())empty();return 0;
    }
    if(id==dynamictuning::kPcmGet) {
        constexpr unsigned bytes=16+dynamictuning::kPcmWords*4;
        if(packet->vsize!=dynamictuning::kPcmWords*4 || size!=16 || *replySize<bytes)return -EINVAL;
        auto* result=static_cast<effect_param_t*>(reply);
        auto available=[&]{return f.enabled.load(std::memory_order_acquire) && f.dynamicStatus[3].load(std::memory_order_acquire)!=0;};
        if(!available()) {
            result->status=0;result->psize=4;result->vsize=dynamictuning::kPcmWords*4;
            std::memcpy(result->data,&id,4);dynamiccapture::empty(reinterpret_cast<int32_t*>(result->data+4),f.hostRate.load());
            *replySize=bytes;return 0;
        }
        const auto status=(*f.viper)->command(f.viper,EFFECT_CMD_GET_PARAM,size,data,replySize,reply);
        if(status)return status;
        if(*replySize!=bytes || result->status)return -EINVAL;
        if(!available())dynamiccapture::empty(reinterpret_cast<int32_t*>(result->data+4),f.hostRate.load());
        return 0;
    }
    if(id==spectrumcapture::kGet || id==TIMBRE_PCM_GET) {
        constexpr unsigned bytes=16+TIMBRE_PCM_WORDS*4;
        if(packet->vsize!=TIMBRE_PCM_WORDS*4 || size!=16 || *replySize<bytes)return -EINVAL;
        const bool spectrum=id==spectrumcapture::kGet;
        auto* result=static_cast<effect_param_t*>(reply);
        auto available=[&]{return f.enabled.load(std::memory_order_acquire) &&
            (spectrum?f.spectrumPcmParentEnabled.load(std::memory_order_acquire):f.timbreStatus[3].load(std::memory_order_acquire)!=0);};
        auto empty=[&]{auto* values=reinterpret_cast<int32_t*>(result->data+4);
            if(spectrum)spectrumcapture::empty(values,f.hostRate.load());else timbrecapture::empty(values,f.hostRate.load());};
        if(!available()) {
            result->status=0;result->psize=4;result->vsize=TIMBRE_PCM_WORDS*4;
            std::memcpy(result->data,&id,4);empty();*replySize=bytes;return 0;
        }
        const auto child=spectrum?f.viper:f.james;
        const auto status=(*child)->command(child,EFFECT_CMD_GET_PARAM,size,data,replySize,reply);
        if(status)return status;
        if(*replySize!=bytes || result->status)return -EINVAL;
        if(!available())empty();
        return 0;
    }
    if(id==analogtuning::kPcmGet) {
        constexpr unsigned bytes=16+analogtuning::kPcmWords*4;
        if(packet->vsize!=analogtuning::kPcmWords*4 || size!=16 || *replySize<bytes)return -EINVAL;
        auto* result=static_cast<effect_param_t*>(reply);
        if(!f.enabled.load(std::memory_order_acquire) || !f.analogStatus[3].load(std::memory_order_acquire)) {
            result->status=0;result->psize=4;result->vsize=analogtuning::kPcmWords*4;
            std::memcpy(result->data,&id,4);analogcapture::empty(reinterpret_cast<int32_t*>(result->data+4),f.hostRate.load());
            *replySize=bytes;return 0;
        }
        const auto status=(*f.viper)->command(f.viper,EFFECT_CMD_GET_PARAM,size,data,replySize,reply);
        if(status)return status;
        if(*replySize!=bytes || result->status)return -EINVAL;
        if(!f.enabled.load(std::memory_order_acquire) || !f.analogStatus[3].load(std::memory_order_acquire))
            analogcapture::empty(reinterpret_cast<int32_t*>(result->data+4),f.hostRate.load());
        return 0;
    }
    if(id==tubetuning::kPcmGet) {
        constexpr unsigned bytes=16+tubetuning::kPcmWords*4;
        if(packet->vsize!=tubetuning::kPcmWords*4 || size!=16 || *replySize<bytes)return -EINVAL;
        auto* result=static_cast<effect_param_t*>(reply);
        if(!f.enabled.load(std::memory_order_acquire) || !f.tubeStatus[3].load(std::memory_order_acquire)) {
            result->status=0;result->psize=4;result->vsize=tubetuning::kPcmWords*4;
            std::memcpy(result->data,&id,4);tubecapture::empty(reinterpret_cast<int32_t*>(result->data+4),f.hostRate.load());
            *replySize=bytes;return 0;
        }
        // GET renews only an observation lease. It never holds the processing
        // mutex, changes tube settings, or switches the native audition route.
        const auto status=(*f.viper)->command(f.viper,EFFECT_CMD_GET_PARAM,size,data,replySize,reply);
        if(status)return status;
        if(*replySize!=bytes || result->status)return -EINVAL;
        if(!f.enabled.load(std::memory_order_acquire) || !f.tubeStatus[3].load(std::memory_order_acquire))
            tubecapture::empty(reinterpret_cast<int32_t*>(result->data+4),f.hostRate.load());
        return 0;
    }
    if(id==ROOM_REVERB_PCM_GET) {
        constexpr unsigned bytes=16+ROOM_REVERB_PCM_WORDS*4;
        if(packet->vsize!=ROOM_REVERB_PCM_WORDS*4 || size!=16 || *replySize<bytes)return -EINVAL;
        auto* result=static_cast<effect_param_t*>(reply);
        // No processing mutex: the child GET only renews atomic observer requests
        // and reads a fixed, atomically published snapshot.
        if(!f.enabled.load(std::memory_order_acquire) || !f.roomReverbStatus[3].load(std::memory_order_acquire)) {
            result->status=0;result->psize=4;result->vsize=ROOM_REVERB_PCM_WORDS*4;
            std::memcpy(result->data,&id,4);roomcapture::empty(reinterpret_cast<int32_t*>(result->data+4),f.hostRate.load());
            *replySize=bytes;return 0;
        }
        const auto status=(*f.james)->command(f.james,EFFECT_CMD_GET_PARAM,size,data,replySize,reply);
        if(status)return status;
        if(*replySize!=bytes || result->status)return -EINVAL;
        if(!f.enabled.load(std::memory_order_acquire) || !f.roomReverbStatus[3].load(std::memory_order_acquire))
            roomcapture::empty(reinterpret_cast<int32_t*>(result->data+4),f.hostRate.load());
        return 0;
    }
    if(id==kVisualizationEnergy || id==kVisualizationField) {
        const unsigned words=id==kVisualizationEnergy?visualization::kEnergyWords:visualization::kFieldWords;
        if(packet->vsize!=words*4 || *replySize<16+words*4)return -EINVAL;
        std::array<int32_t,visualization::kFieldWords> values{};
        for(unsigned attempt=0;attempt<3;++attempt) {
            const auto before=f.visualizationEpoch.load(std::memory_order_acquire);if(before&1u)continue;
            std::array<uint32_t,3> requests;
            for(unsigned i=0;i<3;++i)requests[i]=f.visualizationRequests[i].load(std::memory_order_acquire);
            for(unsigned i=0;i<words;++i)values[i]=(id==kVisualizationEnergy?f.visualizationEnergy[i]:f.visualizationField[i]).load(std::memory_order_relaxed);
            std::atomic_thread_fence(std::memory_order_acquire);
            if(before!=f.visualizationEpoch.load(std::memory_order_relaxed))continue;
            bool changed=false;
            for(unsigned i=0;i<3;++i)changed|=requests[i]!=f.visualizationRequests[i].load(std::memory_order_acquire);
            if(changed)continue;
            values[0]=1;
            const auto hostRate=f.hostRate.load(std::memory_order_relaxed);
            if(id==kVisualizationEnergy) {
                values[1]=before&0x7fffffffu;
                if(!f.enabled.load() || values[2]!=static_cast<int32_t>(hostRate))values[5]=0;
                for(unsigned i=0;i<2;++i)if(!(requests[i]&1u) || values[3+i]!=static_cast<int32_t>(requests[i])) {
                    values[5]&=~((1<<i)|(4<<i)|(16<<i));values[8+i]=visualization::kUnavailable;values[6+i]=0;
                }
                values[3]=requests[0];values[4]=requests[1];
            } else if(!f.enabled.load() || !(requests[2]&1u) || values[1]!=static_cast<int32_t>(requests[2]) || values[3]!=static_cast<int32_t>(hostRate)) {
                values.fill(0);values[0]=1;values[1]=requests[2];values[3]=hostRate;
            }
            auto* result=static_cast<effect_param_t*>(reply);result->status=0;result->psize=4;result->vsize=words*4;
            std::memcpy(result->data,&id,4);std::memcpy(result->data+4,values.data(),words*4);*replySize=16+words*4;return 0;
        }
        return -EAGAIN;
    }
    if(id==131238 || id==131239 || id==131240) {
        const unsigned words=id==131238?DDC_RUNTIME_WORDS:id==131239?DDC_RESPONSE_WORDS:DDC_RUNTIME_WORDS+DDC_RESPONSE_WORDS;
        if(packet->vsize!=words*4 || *replySize<16+words*4)return -EINVAL;
        std::array<int32_t,DDC_RUNTIME_WORDS+DDC_RESPONSE_WORDS> values{};
        for(unsigned attempt=0;attempt<3;++attempt){
            const auto before=f.ddcEpoch.load(std::memory_order_acquire);if(before&1)continue;
            for(unsigned i=0;i<words;++i){
                if(id==131240)values[i]=(i<DDC_RUNTIME_WORDS?f.ddcStatus[i]:f.ddcResponse[i-DDC_RUNTIME_WORDS]).load(std::memory_order_relaxed);
                else values[i]=(id==131238?f.ddcStatus[i]:f.ddcResponse[i]).load(std::memory_order_relaxed);
            }
            std::atomic_thread_fence(std::memory_order_acquire);const auto after=f.ddcEpoch.load(std::memory_order_relaxed);
            if(before!=after || (after&1))continue;values[1]=after&0x7fffffffu;
            if(id==131240)values[DDC_RUNTIME_WORDS+1]=values[1];
            auto* result=static_cast<effect_param_t*>(reply);result->status=0;result->psize=4;result->vsize=words*4;
            std::memcpy(result->data,&id,4);std::memcpy(result->data+4,values.data(),words*4);*replySize=16+words*4;return 0;
        }
        return -EAGAIN;
    }
    if(id==131235 || id==131236 || id==131237) {
        const unsigned words=id==131235?64:id==131236?200:264;
        if(packet->vsize!=words*4 || *replySize<16+words*4)return -EINVAL;
        std::array<int32_t,264> values{};
        for(unsigned attempt=0;attempt<3;++attempt) {
            const auto before=f.toneEpoch.load(std::memory_order_acquire);if(before&1)continue;
            for(unsigned i=0;i<words;++i) {
                if(id==131237)values[i]=(i<64?f.toneStatus[i]:f.toneResponse[i-64]).load(std::memory_order_relaxed);
                else values[i]=(id==131235?f.toneStatus[i]:f.toneResponse[i]).load(std::memory_order_relaxed);
            }
            std::atomic_thread_fence(std::memory_order_acquire);const auto after=f.toneEpoch.load(std::memory_order_relaxed);
            if(before!=after || (after&1))continue;values[1]=after&0x7fffffffu;
            if(id==131237)values[65]=values[1];
            auto* result=static_cast<effect_param_t*>(reply);result->status=0;result->psize=4;result->vsize=words*4;
            std::memcpy(result->data,&id,4);std::memcpy(result->data+4,values.data(),words*4);*replySize=16+words*4;return 0;
        }
        return -EAGAIN;
    }
    if(id==131231 || id==131232 || id==131233) {
        constexpr unsigned combinedWords=COMPANDER_RUNTIME_WORDS+COMPANDER_RESPONSE_WORDS;
        const unsigned words=id==131231?COMPANDER_RUNTIME_WORDS:id==131232?COMPANDER_RESPONSE_WORDS:combinedWords;
        if(packet->vsize!=words*4 || *replySize<16+words*4)return -EINVAL;
        std::array<int32_t,combinedWords> values{};
        for(unsigned attempt=0;attempt<3;++attempt) {
            const auto before=f.companderEpoch.load(std::memory_order_acquire);if(before&1)continue;
            for(unsigned i=0;i<words;++i) {
                if(id==131233)values[i]=(i<COMPANDER_RUNTIME_WORDS?f.companderStatus[i]:f.companderResponse[i-COMPANDER_RUNTIME_WORDS]).load(std::memory_order_relaxed);
                else values[i]=(id==131231?f.companderStatus[i]:f.companderResponse[i]).load(std::memory_order_relaxed);
            }
            std::atomic_thread_fence(std::memory_order_acquire);const auto after=f.companderEpoch.load(std::memory_order_relaxed);
            if(before!=after || (after&1))continue;values[1]=after&0x7fffffffu;
            if(id==131233)values[COMPANDER_RUNTIME_WORDS+1]=values[1];
            auto* result=static_cast<effect_param_t*>(reply);result->status=0;result->psize=4;result->vsize=words*4;
            std::memcpy(result->data,&id,4);std::memcpy(result->data+4,values.data(),words*4);*replySize=16+words*4;return 0;
        }
        return -EAGAIN;
    }
    if(id==131228 || id==131229 || id==131230) {
        constexpr unsigned combinedWords=dynamictuning::kRuntimeWords+dynamictuning::kResponseWords;
        const unsigned words=id==131228?dynamictuning::kRuntimeWords:id==131229?dynamictuning::kResponseWords:combinedWords;
        if(packet->vsize!=words*4 || *replySize<16+words*4)return -EINVAL;
        std::array<int32_t,combinedWords> values{};
        for(unsigned attempt=0;attempt<3;++attempt) {
            const auto before=f.dynamicEpoch.load(std::memory_order_acquire);if(before&1)continue;
            for(unsigned i=0;i<words;++i) {
                if(id==131230)values[i]=(i<dynamictuning::kRuntimeWords?f.dynamicStatus[i]:f.dynamicResponse[i-dynamictuning::kRuntimeWords]).load(std::memory_order_relaxed);
                else values[i]=(id==131228?f.dynamicStatus[i]:f.dynamicResponse[i]).load(std::memory_order_relaxed);
            }
            std::atomic_thread_fence(std::memory_order_acquire);const auto after=f.dynamicEpoch.load(std::memory_order_relaxed);
            if(before!=after || (after&1))continue;values[1]=after&0x7fffffffu;
            if(id==131230)values[dynamictuning::kRuntimeWords+1]=values[1];
            auto* result=static_cast<effect_param_t*>(reply);result->status=0;result->psize=4;result->vsize=words*4;
            std::memcpy(result->data,&id,4);std::memcpy(result->data+4,values.data(),words*4);*replySize=16+words*4;return 0;
        }
        return -EAGAIN;
    }
    if(id==131227) {
        constexpr unsigned words=TIMBRE_RUNTIME_WORDS;
        if(packet->vsize!=words*4 || *replySize<16+words*4)return -EINVAL;
        std::array<int32_t,words> values{};
        for(unsigned attempt=0;attempt<3;++attempt) {
            const auto before=f.timbreEpoch.load(std::memory_order_acquire);if(before&1)continue;
            for(unsigned i=0;i<words;++i)values[i]=f.timbreStatus[i].load(std::memory_order_relaxed);
            std::atomic_thread_fence(std::memory_order_acquire);const auto after=f.timbreEpoch.load(std::memory_order_relaxed);
            if(before!=after || (after&1))continue;values[1]=after&0x7fffffffu;
            auto* result=static_cast<effect_param_t*>(reply);result->status=0;result->psize=4;result->vsize=words*4;
            std::memcpy(result->data,&id,4);std::memcpy(result->data+4,values.data(),words*4);*replySize=16+words*4;return 0;
        }
        return -EAGAIN;
    }
    if(id==131226) {
        constexpr unsigned words=analogtuning::kRuntimeWords;
        if(packet->vsize!=words*4 || *replySize<16+words*4)return -EINVAL;
        std::array<int32_t,words> values{};
        for(unsigned attempt=0;attempt<3;++attempt) {
            const auto before=f.analogEpoch.load(std::memory_order_acquire);if(before&1)continue;
            for(unsigned i=0;i<words;++i)values[i]=f.analogStatus[i].load(std::memory_order_relaxed);
            std::atomic_thread_fence(std::memory_order_acquire);const auto after=f.analogEpoch.load(std::memory_order_relaxed);
            if(before!=after || (after&1))continue;values[1]=after&0x7fffffffu;
            auto* result=static_cast<effect_param_t*>(reply);result->status=0;result->psize=4;result->vsize=words*4;
            std::memcpy(result->data,&id,4);std::memcpy(result->data+4,values.data(),words*4);*replySize=16+words*4;return 0;
        }
        return -EAGAIN;
    }
    if(id==131225) {
        constexpr unsigned words=tubetuning::kRuntimeWords;
        if(packet->vsize!=words*4 || *replySize<16+words*4)return -EINVAL;
        std::array<int32_t,words> values{};
        for(unsigned attempt=0;attempt<3;++attempt) {
            const auto before=f.tubeEpoch.load(std::memory_order_acquire);if(before&1)continue;
            for(unsigned i=0;i<words;++i)values[i]=f.tubeStatus[i].load(std::memory_order_relaxed);
            std::atomic_thread_fence(std::memory_order_acquire);const auto after=f.tubeEpoch.load(std::memory_order_relaxed);
            if(before!=after || (after&1))continue;values[1]=after&0x7fffffffu;
            auto* result=static_cast<effect_param_t*>(reply);result->status=0;result->psize=4;result->vsize=words*4;
            std::memcpy(result->data,&id,4);std::memcpy(result->data+4,values.data(),words*4);*replySize=16+words*4;return 0;
        }
        return -EAGAIN;
    }
    if(id==131224){
        constexpr unsigned words=ROOM_REVERB_RUNTIME_WORDS;
        if(packet->vsize!=words*4 || *replySize<16+words*4)return -EINVAL;
        std::array<int32_t,words> values{};
        for(unsigned attempt=0;attempt<3;++attempt){
            const auto before=f.roomReverbEpoch.load(std::memory_order_acquire);if(before&1)continue;
            for(unsigned i=0;i<words;++i)values[i]=f.roomReverbStatus[i].load(std::memory_order_relaxed);
            std::atomic_thread_fence(std::memory_order_acquire);const auto after=f.roomReverbEpoch.load(std::memory_order_relaxed);
            if(before!=after || (after&1))continue;values[1]=after&0x7fffffffu;
            auto* result=static_cast<effect_param_t*>(reply);result->status=0;result->psize=4;result->vsize=words*4;
            std::memcpy(result->data,&id,4);std::memcpy(result->data+4,values.data(),words*4);*replySize=16+words*4;return 0;
        }
        return -EAGAIN;
    }
    if(id==131220 || id==131221 || id==131222 || id==131223){
        const unsigned words=id==131220?24:id==131223?reverbtuning::kDecayWords:id==131222?reverbtuning::kRuntimeWords:headphone::kDiagnosticWords;
        if(packet->vsize!=words*4 || *replySize<16+words*4)return -EINVAL;
        std::array<int32_t,headphone::kDiagnosticWords> values{};
        auto& epoch=id==131220?f.presetCrossfeedEpoch:(id==131222 || id==131223)?f.reverbEpoch:f.headphoneEpoch;
        for(unsigned attempt=0;attempt<3;++attempt){
            const auto before=epoch.load(std::memory_order_acquire);if(before&1)continue;
            for(unsigned i=0;i<words;++i)values[i]=(id==131220?f.presetCrossfeedStatus[i]:id==131223?f.reverbDecay[i]:id==131222?f.reverbStatus[i]:f.headphoneDiagnostics[i]).load(std::memory_order_relaxed);
            std::atomic_thread_fence(std::memory_order_acquire);const auto after=epoch.load(std::memory_order_relaxed);
            if(before!=after || (after&1))continue;values[1]=after&0x7fffffffu;
            auto* result=static_cast<effect_param_t*>(reply);result->status=0;result->psize=4;result->vsize=words*4;
            std::memcpy(result->data,&id,4);std::memcpy(result->data+4,values.data(),words*4);*replySize=16+words*4;return 0;
        }
        return -EAGAIN;
    }
    if(id==kHeadphoneRuntime) {
        if(packet->vsize!=80 || *replySize<96)return -EINVAL;
        std::array<int32_t,20> values{};
        for(unsigned attempt=0;attempt<3;++attempt){
            const auto before=f.headphoneEpoch.load(std::memory_order_acquire);if(before&1)continue;
            for(unsigned i=0;i<20;++i)values[i]=f.headphoneStatus[i].load(std::memory_order_relaxed);
            std::atomic_thread_fence(std::memory_order_acquire);
            const auto after=f.headphoneEpoch.load(std::memory_order_relaxed);if(before!=after || (after&1))continue;
            values[1]=after&0x7fffffffu;
            auto* result=static_cast<effect_param_t*>(reply);result->status=0;result->psize=4;result->vsize=80;
            std::memcpy(result->data,&id,4);std::memcpy(result->data+4,values.data(),80);*replySize=96;return 0;
        }
        return -EAGAIN;
    }
    if(id==kSurroundListeningRuntime) {
        if(packet->vsize!=96 || *replySize<112)return -EINVAL;
        std::array<int32_t,24> values{};if(!f.surround.listeningSnapshot(values.data()))return -EAGAIN;
        auto* r=static_cast<effect_param_t*>(reply);r->status=0;r->psize=4;r->vsize=96;
        std::memcpy(r->data,&id,4);std::memcpy(r->data+4,values.data(),96);*replySize=112;return 0;
    }
    if(id==kSurroundRuntime) {
        if(packet->vsize!=128 || *replySize<144)return -EINVAL;
        std::array<int32_t,32> values{};if(!f.surround.snapshot(values.data()))return -EAGAIN;
        auto* r=static_cast<effect_param_t*>(reply);r->status=0;r->psize=4;r->vsize=128;
        std::memcpy(r->data,&id,4);std::memcpy(r->data+4,values.data(),128);*replySize=144;return 0;
    }
    if(id==131216) {
        if(packet->vsize!=704 || *replySize<720)return -EINVAL;
        std::array<int32_t,176> values{};
        for(unsigned attempt=0;attempt<3;++attempt) {
            const auto before=f.widthEpoch.load(std::memory_order_acquire);if(before&1)continue;
            for(unsigned i=0;i<176;++i)values[i]=f.widthAdvancedStatus[i].load(std::memory_order_relaxed);
            std::atomic_thread_fence(std::memory_order_acquire);const auto after=f.widthEpoch.load(std::memory_order_relaxed);
            if(before!=after || (after&1))continue;values[1]=after&0x7fffffffu;
            auto* result=static_cast<effect_param_t*>(reply);result->status=0;result->psize=4;result->vsize=704;
            std::memcpy(result->data,&id,4);std::memcpy(result->data+4,values.data(),704);*replySize=720;return 0;
        }
        return -EAGAIN;
    }
    if(id==131215) {
        if(packet->vsize!=128 || *replySize<144)return -EINVAL;
        std::array<int32_t,32> values{};
        for(unsigned attempt=0;attempt<3;++attempt) {
            const auto before=f.widthEpoch.load(std::memory_order_acquire);if(before&1)continue;
            for(unsigned i=0;i<32;++i)values[i]=f.widthStatus[i].load(std::memory_order_relaxed);
            std::atomic_thread_fence(std::memory_order_acquire);const auto after=f.widthEpoch.load(std::memory_order_relaxed);
            if(before!=after || (after&1))continue;values[1]=after&0x7fffffffu;
            auto* result=static_cast<effect_param_t*>(reply);result->status=0;result->psize=4;result->vsize=128;
            std::memcpy(result->data,&id,4);std::memcpy(result->data+4,values.data(),128);*replySize=144;return 0;
        }
        return -EAGAIN;
    }
    if(id==131214) {
        if(packet->vsize!=80 || *replySize<96)return -EINVAL;
        std::array<int32_t,20> values{};
        for(unsigned attempt=0;attempt<3;++attempt) {
            const auto before=f.convolverEpoch.load(std::memory_order_acquire);if(before&1)continue;
            for(unsigned i=0;i<20;++i)values[i]=f.convolutionAdvancedStatus[i].load(std::memory_order_relaxed);
            std::atomic_thread_fence(std::memory_order_acquire);
            const auto after=f.convolverEpoch.load(std::memory_order_relaxed);if(before!=after || (after&1))continue;
            values[1]=after&0x7fffffffu;
            auto* result=static_cast<effect_param_t*>(reply);result->status=0;result->psize=4;result->vsize=80;
            std::memcpy(result->data,&id,4);std::memcpy(result->data+4,values.data(),80);*replySize=96;return 0;
        }
        return -EAGAIN;
    }
    if(id==131213) {
        constexpr unsigned words=32;
        if(packet->vsize!=128 || *replySize<144)return -EINVAL;
        std::array<int32_t,words> values{};
        for(unsigned attempt=0;attempt<3;++attempt) {
            const auto before=f.convolverEpoch.load(std::memory_order_acquire);if(before&1)continue;
            for(unsigned i=0;i<words;++i)values[i]=f.convolverStatus[i].load(std::memory_order_relaxed);
            std::atomic_thread_fence(std::memory_order_acquire);
            const auto after=f.convolverEpoch.load(std::memory_order_relaxed);if(before!=after||(after&1))continue;
            values[1]=after&0x7fffffffu;
            auto* result=static_cast<effect_param_t*>(reply);result->status=0;result->psize=4;result->vsize=128;
            std::memcpy(result->data,&id,4);std::memcpy(result->data+4,values.data(),128);*replySize=144;return 0;
        }
        return -EAGAIN;
    }
    if(id==131212) {
        constexpr unsigned words=84;
        if(packet->vsize!=words*4 || *replySize<sizeof(effect_param_t)+4+words*4)return -EINVAL;
        std::array<int32_t,words> values{};
        for(unsigned attempt=0;attempt<3;++attempt) {
            const auto before=f.diffSnapshotEpoch.load(std::memory_order_acquire);
            if(before&1)continue;
            for(unsigned i=0;i<words;++i)values[i]=f.diffStatus[i].load(std::memory_order_relaxed);
            // Keep all payload loads before the closing epoch on ARM as well.
            std::atomic_thread_fence(std::memory_order_acquire);
            const auto after=f.diffSnapshotEpoch.load(std::memory_order_relaxed);
            if(before!=after || (after&1))continue;
            values[20]=after&0x7fffffffu;
            auto* result=static_cast<effect_param_t*>(reply);result->status=0;result->psize=4;result->vsize=words*4;
            std::memcpy(result->data,&id,4);std::memcpy(result->data+4,values.data(),sizeof(values));
            *replySize=sizeof(effect_param_t)+4+sizeof(values);return 0;
        }
        return -EAGAIN;
    }
    if(packet->vsize!=4 || *replySize<sizeof(effect_param_t)+8) return -EINVAL;
    if(id>=131128 && id<=131211) {
        auto* result=static_cast<effect_param_t*>(reply);
        result->status=0;result->psize=result->vsize=4;
        const int32_t value=id==131148?static_cast<int32_t>(f.diffSnapshotEpoch.load(std::memory_order_acquire)&0x7fffffffu):
            f.diffStatus[id-131128].load(std::memory_order_relaxed);
        std::memcpy(result->data,&id,4);std::memcpy(result->data+4,&value,4);
        *replySize=sizeof(effect_param_t)+8;return 0;
    }
    if((id>=131086 && id<=131125) || id==131127) {
        auto* result=static_cast<effect_param_t*>(reply);
        result->status=0;result->psize=result->vsize=4;
        const int32_t value=id==131127?static_cast<int32_t>(f.imagingSnapshotEpoch.load(std::memory_order_acquire)&0x7fffffffu):
            f.professionalStatus[id-131086].load(std::memory_order_relaxed);
        std::memcpy(result->data,&id,4);std::memcpy(result->data+4,&value,4);
        *replySize=sizeof(effect_param_t)+8;return 0;
    }
    if(id==kProtocol || id==kCapabilities || id==kJamesEnable || id==kViperEnable || id==kBalance ||
        id==kHostRate || id==kProcessedFrames || id==kBassFrequency || id==kBassGain) {
        auto* result=static_cast<effect_param_t*>(reply);
        result->status=0; result->psize=result->vsize=4;
        int32_t value=id==kBassFrequency?(f.enabled.load()?f.bassFrequency.load():0):
            (id==kBassGain?(f.enabled.load()?f.bassGain.load():0):
            (id==kHostRate?static_cast<int32_t>(f.hostRate.load()):
            (id==kProcessedFrames?static_cast<int32_t>(f.processedFrames.load()&0x7fffffffu):
            (id==kProtocol?5:(id==kCapabilities?2147483647:(id==kBalance?f.balance:(id==kJamesEnable?f.jamesEnabled:f.viperEnabled)))))));
        std::memcpy(result->data,&id,4); std::memcpy(result->data+4,&value,4);
        *replySize=sizeof(effect_param_t)+8; return 0;
    }
    // Preserve the old first-read counter without touching the child. All
    // remaining legacy queries are snapshots published by their state owner.
    const int legacyIndex=legacyStatusIndex(id);
    if(id==19998 || legacyIndex>=0) {
        auto* result=static_cast<effect_param_t*>(reply);
        result->status=0;result->psize=result->vsize=4;
        const int32_t value=id==19998?static_cast<int32_t>(f.initializeQueries.fetch_add(1,std::memory_order_relaxed)):
            f.legacyStatus[legacyIndex].load(std::memory_order_relaxed);
        std::memcpy(result->data,&id,4);std::memcpy(result->data+4,&value,4);
        *replySize=sizeof(effect_param_t)+8;return 0;
    }
    return -EINVAL;
}

int32_t process(effect_handle_t self,audio_buffer_t* in,audio_buffer_t* out) {
    if(!self || !in || !out || !in->raw || !out->raw || in->frameCount!=out->frameCount ||
       !in->frameCount || in->frameCount>1048576) return -EINVAL;
    auto& f=*reinterpret_cast<Fusion*>(self);
    const uint32_t io=f.ioFormat.load();
    const uint8_t inputFormat=io&255,outputFormat=(io>>8)&255;
    const bool accumulate=(io>>16)!=0;
    const bool active=f.enabled.load();
    std::unique_lock<std::mutex> lock(f.mutex,std::try_to_lock);
    if(!lock.owns_lock() || !active || !f.configured) {
        f.peq.resetPcmObservation();
        // Heavy filter/IR changes may own the control mutex. Dry bypass keeps
        // the audio thread running without racing upstream filter state.
        if(inputFormat==outputFormat && !accumulate) {
            const size_t bytes=inputFormat==AUDIO_FORMAT_PCM_16_BIT?2:(inputFormat==AUDIO_FORMAT_PCM_24_BIT_PACKED?3:4);
            std::memmove(out->raw,in->raw,in->frameCount*2*bytes);
        } else {
            for(size_t i=0;i<in->frameCount*2;++i)
                writeSample(out->raw,i,outputFormat,readSample(in->raw,i,inputFormat),accumulate);
        }
        return active?0:-ENODATA;
    }
    armVisualization(f);
    for(size_t offset=0;offset<in->frameCount;offset+=kBlockFrames) {
        const size_t count=std::min(kBlockFrames,in->frameCount-offset);
        for(size_t i=0;i<count*2;++i) f.first[i]=readSample(in->raw,offset*2+i,inputFormat);
        f.peq.process(f.first.data(),static_cast<uint32_t>(count));
        f.dynamicPeq.process(f.first.data(),static_cast<uint32_t>(count));
        f.creative.process(f.first.data(),static_cast<uint32_t>(count));
        f.expansion.process(f.first.data(),static_cast<uint32_t>(count));
        // RACE may boost anti-phase content. Place it before James' existing
        // output limiter rather than relying on the final emergency hard clamp.
        f.surround.process(f.first.data(),static_cast<uint32_t>(count));
        audio_buffer_t a{},b{}; a.frameCount=b.frameCount=count;
        a.f32=f.first.data(); b.f32=f.second.data();
        float* current=f.first.data();
        if(f.jamesEnabled && !(*f.james)->process(f.james,&a,&b)) current=f.second.data();
        for(size_t i=0;i<count*2;++i) current[i]=clean(current[i]);
        f.tone.process(current,static_cast<uint32_t>(count));
        if(f.viperEnabled) {
            a.f32=current; b.f32=current==f.first.data()?f.second.data():f.first.data();
            if(!(*f.viper)->process(f.viper,&a,&b)) current=b.f32;
        }
        // Independent speaker correction stays before final output protection.
        f.speaker.process(current,static_cast<uint32_t>(count));
        for(size_t frame=0;frame<count;++frame) {
            const float target=f.balance/100.f;
            f.balanceCurrent+=(target-f.balanceCurrent)*f.balanceSmoothing;
            if(std::abs(target-f.balanceCurrent)<1e-6f) f.balanceCurrent=target;
            const float left=1.f-std::max(0.f,f.balanceCurrent);
            const float right=1.f+std::min(0.f,f.balanceCurrent);
            float outputLeft=clean(current[frame*2])*left;
            float outputRight=clean(current[frame*2+1])*right;
            if(accumulate) {
                outputLeft+=readSample(out->raw,(offset+frame)*2,outputFormat);
                outputRight+=readSample(out->raw,(offset+frame)*2+1,outputFormat);
            }
            f.outputSafety.process(outputLeft,outputRight);
            writeSample(out->raw,(offset+frame)*2,outputFormat,outputLeft,false);
            writeSample(out->raw,(offset+frame)*2+1,outputFormat,outputRight,false);
        }
    }
    snapshotVisualization(f,static_cast<uint32_t>(in->frameCount));
    // Snapshot the child while owning its process lock. UI polling only reads atomics.
    for(uint32_t id:{kBassFrequency,kBassGain}) {
        std::array<uint32_t,5> request{0,4,4,id,0},response{};
        uint32_t bytes=sizeof(response);
        int32_t value=0;
        if(f.jamesEnabled && !(*f.james)->command(f.james,EFFECT_CMD_GET_PARAM,16,request.data(),&bytes,response.data()) &&
            bytes==sizeof(response) && response[0]==0) value=static_cast<int32_t>(response[4]);
        (id==kBassFrequency?f.bassFrequency:f.bassGain).store(value,std::memory_order_relaxed);
    }
    snapshotProfessional(f);
    snapshotConvolver(f);
    snapshotWidth(f);
    snapshotHeadphone(f);
    snapshotPresetCrossfeed(f);
    snapshotReverb(f);
    snapshotRoomReverb(f);
    snapshotTimbre(f);snapshotCompander(f);snapshotTone(f);snapshotDdc(f);
    snapshotTube(f);
    snapshotAnalog(f);
    snapshotDynamic(f);
    snapshotLegacyStatus(f);
    f.processedFrames.fetch_add(static_cast<uint32_t>(in->frameCount),std::memory_order_relaxed);
    return 0;
}
int32_t commandInternal(effect_handle_t self,uint32_t code,uint32_t size,void* data,uint32_t* replySize,void* reply) {
    if(!self) return -EINVAL;
    auto& f=*reinterpret_cast<Fusion*>(self);
    if(code==EFFECT_CMD_SET_PARAM && data && size>=sizeof(effect_param_t)+4) {
        const auto* packet=static_cast<const effect_param_t*>(data);
        const uint32_t id=valueAt<uint32_t>(packet->data);
        if(packet->psize==4 && id==10004) {
            if(!replySize || !reply || *replySize<4 || packet->vsize!=2 || size!=18)return -EINVAL;
            // Finish/reap an earlier transition before allocating another FFT plan.
            // No audio mutex is held during waiting or FFT destruction. At most two plans exist.
            auto lastProgress=std::chrono::steady_clock::now();uint32_t observed=f.processedFrames.load();
            for(;;) {
                void* retired=nullptr;int busy=0;
                {
                    std::lock_guard<std::mutex> lock(f.mutex);
                    busy=childCommand(f.james,FUSION_CONV_REAP,sizeof(retired),&retired);
                    const auto now=std::chrono::steady_clock::now();const auto frames=f.processedFrames.load();
                    if(frames!=observed){observed=frames;lastProgress=now;}
                    if(busy==-EAGAIN && now-lastProgress>std::chrono::milliseconds(100))
                        busy=childCommand(f.james,FUSION_CONV_FORCE_REAP,sizeof(retired),&retired);
                }
                FusionConvDestroyJob(retired);
                if(!busy)break;
                if(busy!=-EAGAIN)return statusReply(replySize,reply,busy);
                std::this_thread::sleep_for(std::chrono::milliseconds(2));
            }
            void* raw=nullptr;uint64_t revision=0;uint32_t rate=0;int status=0;
            {
                std::lock_guard<std::mutex> lock(f.mutex);
                status=validateJames(f,id,reinterpret_cast<const uint8_t*>(packet->data+4),2);
                if(status)return statusReply(replySize,reply,status);
                revision=f.convolverRevision;rate=f.config.inputCfg.samplingRate;
                status=childCommand(f.james,FUSION_CONV_TAKE,sizeof(raw),&raw);
            }
            std::unique_ptr<void,decltype(&FusionConvDestroyJob)> job(raw,FusionConvDestroyJob);
            if(status)return statusReply(replySize,reply,status);
            status=FusionConvPrepareJob(raw);
            {
                std::lock_guard<std::mutex> lock(f.mutex);
                if(revision!=f.convolverRevision || rate!=f.config.inputCfg.samplingRate)return statusReply(replySize,reply,-EAGAIN);
                if(!status){status=childCommand(f.james,FUSION_CONV_COMMIT,sizeof(raw),&raw);if(!raw)job.release();}
                if(!status){if(!f.enabled.load()||!f.jamesEnabled)clearConvolver(f);else snapshotConvolver(f);}
            }
            return statusReply(replySize,reply,status);
        }
        if(packet->psize==4 && (id==65632 || id==65635)) {
            if(!replySize || !reply || *replySize<4)return -EINVAL;
            const unsigned words=id==65632?7:21;
            if(packet->vsize!=words*4 || size!=sizeof(effect_param_t)+4+packet->vsize)
                return statusReply(replySize,reply,-EINVAL);
            if(validateViper(id,reinterpret_cast<const uint8_t*>(packet->data+4),packet->vsize))
                return statusReply(replySize,reply,-EINVAL);
            std::array<int32_t,7> basic{};diffadvanced::Settings advanced{};
            uint64_t revision=0;
            {
                std::lock_guard<std::mutex> lock(f.mutex);
                if(id==65632)std::memcpy(f.desiredDiffSettings.data(),packet->data+4,7*4);
                else std::memcpy(f.desiredDiffAdvanced.data(),packet->data+4,21*4);
                basic=f.desiredDiffSettings;advanced=f.desiredDiffAdvanced;
                revision=++f.diffRequestRevision;
                if(f.hasDiffSettings && f.diffSettings==basic && f.diffAdvanced==advanced)
                    return parameter(f,true,size,data,replySize,reply);
            }
            for(unsigned attempt=0;attempt<3;++attempt) {
                uint32_t rate=48000;
                {
                    std::lock_guard<std::mutex> lock(f.mutex);
                    if(revision!=f.diffRequestRevision)return statusReply(replySize,reply,0);
                    rate=f.config.inputCfg.samplingRate;
                }
                auto prepared=DiffSurround::PrepareConfiguration(basic,advanced,rate);
                if(!prepared)return statusReply(replySize,reply,-EINVAL);
                int32_t status=0;
                {
                    std::lock_guard<std::mutex> lock(f.mutex);
                    if(revision!=f.diffRequestRevision)return statusReply(replySize,reply,0);
                    if(f.config.inputCfg.samplingRate!=rate)continue;
                    status=childCommand(f.viper,kCommitPreparedDiff,sizeof(prepared),&prepared);
                    if(!status) {
                        f.diffSettings=basic;f.diffAdvanced=advanced;f.hasDiffSettings=true;
                        if(!f.enabled.load() || !f.viperEnabled)clearAudition(f);
                        else snapshotProfessional(f);
                    }
                }
                // The replaced large plan is released after the audio lock.
                return statusReply(replySize,reply,status);
            }
            return statusReply(replySize,reply,-EAGAIN);
        }
    }

    // Runtime polling must never contend with the audio processing mutex.
    if(code==EFFECT_CMD_GET_PARAM && data && size>=sizeof(effect_param_t)+4) {
        const auto* packet=static_cast<const effect_param_t*>(data);
        const auto id=valueAt<uint32_t>(packet->data);
        if(packet->psize==4 && id==131214) {
            void* retired=nullptr;
            childCommand(f.james,FUSION_CONV_DRAIN,sizeof(retired),&retired);
            FusionConvDestroyJob(retired);
            return parameter(f,false,size,data,replySize,reply);
        }
        if(packet->psize==4 && (id==peqcapture::kGet || id==EQ_PCM_GET || id==LIVEPROG_PCM_GET || id==FUSION_CONV_PCM_GET || id==DDC_PCM_GET || id==COMPANDER_PCM_GET || id==COMPANDER_GAIN_GET || id==dynamictuning::kPcmGet || id==spectrumcapture::kGet || id==TIMBRE_PCM_GET || id==analogtuning::kPcmGet || id==tubetuning::kPcmGet || id==ROOM_REVERB_PCM_GET || id==kVisualizationEnergy || id==kVisualizationField || id==19998 || legacyStatusIndex(id)>=0 || id==kProtocol || id==kCapabilities || id==dspfusion::DynamicPeqProcessor::kCapabilityGet || id==dspfusion::DynamicPeqProcessor::kStatusGet || id==dspfusion::SpeakerOptimizationProcessor::kCapabilityGet || id==dspfusion::SpeakerOptimizationProcessor::kStatusGet || id==dspfusion::ExpansionEffectsProcessor::kCapabilityGet || id==dspfusion::ExpansionEffectsProcessor::kProfileGet || (id>=dspfusion::ExpansionEffectsProcessor::kStatusFirst && id<=dspfusion::ExpansionEffectsProcessor::kStatusLast) || id==dspfusion::CreativeEffectsProcessor::kCapabilityGet || (id>=dspfusion::CreativeEffectsProcessor::kStatusFirst && id<=dspfusion::CreativeEffectsProcessor::kStatusLast) || id==kExtendedCapabilities || id==kHostRate || id==kProcessedFrames || id==kBassFrequency || id==kBassGain || (id>=131086 && id<=131125) || id==131127 || (id>=131128 && id<=131240)))
            return parameter(f,false,size,data,replySize,reply);
    }
    // PEQ and surround own pending-snapshot locks. Preparing new filters must
    // not make the audio-thread try_lock emit dry audio while dragging.
    if(code==EFFECT_CMD_SET_PARAM && data && size>=sizeof(effect_param_t)+4) {
        const auto* packet=static_cast<const effect_param_t*>(data);
        if(packet->psize==4 && (valueAt<uint32_t>(packet->data)==kVisualizationArm || valueAt<uint32_t>(packet->data)==dspfusion::SpeakerOptimizationProcessor::kSet || valueAt<uint32_t>(packet->data)==dspfusion::DynamicPeqProcessor::kSet || (valueAt<uint32_t>(packet->data)>=dspfusion::CreativeEffectsProcessor::kSetFirst && valueAt<uint32_t>(packet->data)<=dspfusion::CreativeEffectsProcessor::kSetLast) || (valueAt<uint32_t>(packet->data)>=dspfusion::ExpansionEffectsProcessor::kSetFirst && valueAt<uint32_t>(packet->data)<=dspfusion::ExpansionEffectsProcessor::kProfileSet) || valueAt<uint32_t>(packet->data)==kPeq || valueAt<uint32_t>(packet->data)==kSurround || valueAt<uint32_t>(packet->data)==kSurroundProfessional || valueAt<uint32_t>(packet->data)==kSurroundAudition || valueAt<uint32_t>(packet->data)==kSurroundListening))
            return parameter(f,true,size,data,replySize,reply);
    }
    std::lock_guard<std::mutex> lock(f.mutex);
    switch(code) {
        case EFFECT_CMD_INIT: return statusReply(replySize,reply,0);
        case EFFECT_CMD_SET_CONFIG:
            if(size!=sizeof(effect_config_t) || !data || !replySize || !reply || *replySize<4) return -EINVAL;
            {
                bool processingChanged=false;
                const int32_t status=setConfig(f,*static_cast<effect_config_t*>(data),&processingChanged);
                if(!status) {
                    f.hostRate.store(f.config.inputCfg.samplingRate);
                    snapshotLegacyStatus(f);
                    if(processingChanged) {childCommand(f.james,LIVEPROG_PCM_RESET);childCommand(f.james,EQ_PCM_RESET);clearVisualization(f);++f.convolverRevision;clearConvolver(f);clearWidth(f);clearHeadphone(f);clearPresetCrossfeed(f);clearReverb(f);clearRoomReverb(f);clearTimbre(f);clearCompander(f);clearDdc(f);clearTone(f);clearTube(f);clearAnalog(f);clearDynamic(f);f.surround.cancelAudition(true);snapshotProfessional(f);}
                }
                return statusReply(replySize,reply,status);
            }
        case EFFECT_CMD_GET_CONFIG:
            if(!replySize || !reply || *replySize<sizeof(effect_config_t)) return -EINVAL;
            std::memcpy(reply,&f.config,sizeof(f.config)); *replySize=sizeof(f.config); return 0;
        case EFFECT_CMD_ENABLE: case EFFECT_CMD_DISABLE:
            if(!replySize || !reply || *replySize<4) return -EINVAL;
            if(f.enabled.load()!=(code==EFFECT_CMD_ENABLE)){f.peq.resetPcmObservation();f.dynamicPeq.reset();f.creative.reset();f.expansion.reset();f.speaker.reset();}
            if(f.enabled.load()!=(code==EFFECT_CMD_ENABLE)){childCommand(f.james,LIVEPROG_PCM_RESET);childCommand(f.james,EQ_PCM_RESET);}
            f.enabled.store(code==EFFECT_CMD_ENABLE);
            if(code==EFFECT_CMD_DISABLE) { clearVisualization(f);f.bassFrequency.store(0); f.bassGain.store(0); clearAudition(f);clearConvolver(f);clearWidth(f);clearPresetCrossfeed(f);clearRoomReverb(f);clearTimbre(f);clearCompander(f);clearDdc(f);clearTone(f);f.surround.cancelAudition(true); }
            return statusReply(replySize,reply,0);
        case EFFECT_CMD_RESET:
            childCommand(f.james,LIVEPROG_PCM_RESET);childCommand(f.james,EQ_PCM_RESET);
            clearVisualization(f);
            clearWidth(f);
            clearPresetCrossfeed(f);
            clearRoomReverb(f);clearTimbre(f);clearCompander(f);clearDdc(f);
            f.bassFrequency.store(0); f.bassGain.store(0);
            childCommand(f.james,EQ_PCM_RESET);childCommand(f.james,EFFECT_CMD_RESET); childCommand(f.viper,EFFECT_CMD_RESET); f.tone.reset();clearTone(f);f.peq.reset();f.dynamicPeq.reset();f.creative.reset();f.expansion.reset();f.speaker.reset();f.surround.reset();f.outputSafety.reset();
            clearAudition(f);
            clearConvolver(f);
            snapshotLegacyStatus(f);
            return replySize && reply ? statusReply(replySize,reply,0) : 0;
        case EFFECT_CMD_SET_PARAM: case EFFECT_CMD_GET_PARAM:
            return parameter(f,code==EFFECT_CMD_SET_PARAM,size,data,replySize,reply);
        case EFFECT_CMD_SET_DEVICE: case EFFECT_CMD_SET_AUDIO_MODE: case EFFECT_CMD_SET_AUDIO_SOURCE: return 0;
        case EFFECT_CMD_SET_VOLUME:
            if(replySize && reply && data && *replySize>=size) { std::memcpy(reply,data,size); *replySize=size; }
            return 0;
        case EFFECT_CMD_OFFLOAD: return statusReply(replySize,reply,-ENOSYS);
        default: return -EINVAL;
    }
}
int32_t command(effect_handle_t self,uint32_t code,uint32_t size,void* data,uint32_t* replySize,void* reply) {
    try { return commandInternal(self,code,size,data,replySize,reply); }
    catch(const std::bad_alloc&) { return -ENOMEM; }
}
int32_t descriptor(effect_handle_t self,effect_descriptor_t* result) {
    if(!self || !result) return -EINVAL;
    *result=kDescriptor; return 0;
}
const effect_interface_s kInterface{process,command,descriptor,nullptr};
int32_t create(const effect_uuid_t* uuid,int32_t session,int32_t io,effect_handle_t* handle) {
    if(!uuid || !handle) return -EINVAL;
    *handle=nullptr;
    if(std::memcmp(uuid,&kUuid,sizeof(kUuid))) return -ENOENT;
    try {
        auto owner=std::make_unique<Fusion>();
        auto* f=owner.get();
        f->iface=&kInterface;
        int32_t status=JDSP_AELI.create_effect(&kJamesUuid,session,io,&f->james);
        if(!status) status=VIPER_AELI.create_effect(&kViperUuid,session,io,&f->viper);
        if(!status) status=childCommand(f->james,EFFECT_CMD_INIT);
        if(!status) status=childCommand(f->viper,EFFECT_CMD_INIT);
        if(!status) status=childCommand(f->james,EFFECT_CMD_ENABLE);
        if(!status) status=childCommand(f->viper,EFFECT_CMD_ENABLE);
        if(status) return status;
        f->config.inputCfg.samplingRate=f->config.outputCfg.samplingRate=48000;
        f->config.inputCfg.channels=f->config.outputCfg.channels=AUDIO_CHANNEL_OUT_STEREO;
        f->config.inputCfg.format=f->config.outputCfg.format=AUDIO_FORMAT_PCM_FLOAT;
        f->config.inputCfg.accessMode=EFFECT_BUFFER_ACCESS_READ;
        f->config.outputCfg.accessMode=EFFECT_BUFFER_ACCESS_WRITE;
        f->config.inputCfg.mask=f->config.outputCfg.mask=EFFECT_CONFIG_ALL;
        status=setConfig(*f,f->config);
        if(status) return status;
        snapshotTone(*f);snapshotDdc(*f);
        snapshotLegacyStatus(*f);
        *handle=reinterpret_cast<effect_handle_t>(owner.release()); return 0;
    } catch(const std::bad_alloc&) { return -ENOMEM; }
}
int32_t release(effect_handle_t handle) {
    if(!handle) return -EINVAL;
    delete reinterpret_cast<Fusion*>(handle); return 0;
}
int32_t getDescriptor(const effect_uuid_t* uuid,effect_descriptor_t* result) {
    if(!uuid || !result) return -EINVAL;
    if(std::memcmp(uuid,&kUuid,sizeof(kUuid))) return -ENOENT;
    *result=kDescriptor; return 0;
}
}
extern "C" {
__attribute__((visibility("default"))) audio_effect_library_t AUDIO_EFFECT_LIBRARY_INFO_SYM={
    .tag=AUDIO_EFFECT_LIBRARY_TAG,.version=EFFECT_LIBRARY_API_VERSION,
    .name="DSP Fusion: JamesDSP + ViPER + Tone",.implementor="DSP Fusion and upstream contributors",
    .create_effect=create,.release_effect=release,.get_descriptor=getDescriptor
};
}
