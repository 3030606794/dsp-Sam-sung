// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <ctime>

// GET-only observation: no audio switch, coefficient, audition or filter state is changed.
namespace eqcapture {
constexpr uint32_t kGet=131256u;
constexpr int32_t kCapability=65536;
constexpr unsigned kHeader=32,kFrames=1024,kPlanes=4,kWords=kHeader+kFrames*kPlanes;
constexpr uint32_t kLeaseMs=1500;
constexpr uint32_t kReady=1,kModuleEnabled=2,kTransitioning=4,kConfigurationChanged=8,
                   kNonFinite=64,kLeaseActive=128;
constexpr int32_t kMagic=0x4d455143;
static_assert(std::atomic<uint32_t>::is_always_lock_free,"multimodal EQ PCM requires lock-free publication");

inline uint32_t nowMs(){timespec t{};clock_gettime(CLOCK_MONOTONIC,&t);return uint32_t(uint64_t(t.tv_sec)*1000u+t.tv_nsec/1000000u);}
inline bool alive(uint32_t end,uint32_t now){return end && int32_t(end-now)>0;}
inline uint32_t identity(){static std::atomic<uint32_t> ticket{0};return (ticket.fetch_add(1,std::memory_order_relaxed)+1u)&0x7fffffffu;}
inline uint32_t epochValue(uint64_t value){return uint32_t(value)&0x7fffffffu;}
inline uint32_t hash(const int32_t* data,size_t count){
    uint32_t value=0x811c9dc5u;
    for(size_t i=0;i<count;++i)for(unsigned shift=0;shift<32;shift+=8)
        value=(value^((uint32_t(data[i])>>shift)&255u))*16777619u;
    return value;
}
inline void empty(int32_t* out,uint32_t rate=0){
    std::fill_n(out,kWords,0);out[0]=1;out[1]=kMagic;out[4]=rate;
    out[7]=kPlanes;out[8]=kFrames;out[9]=1;out[20]=kWords;out[21]=1;out[22]=1;out[27]=1;out[31]=rate;
}

class Capture {
public:
    Capture():captureId(identity()){fillHeader(scratch.data(),0,0,false);publish(kHeader);}

    // The existing multimodal EQ control mutex serializes configuration writers. Only atomics
    // cross into the audio/GET threads; neither reader acquires that mutex.
    void configuration(uint64_t revision,bool enabled,uint32_t parameterHash,uint32_t filterType,uint32_t interpolation){
        configurationEpoch.fetch_add(1,std::memory_order_acq_rel);
        requestedRevision.store(epochValue(revision),std::memory_order_relaxed);
        requestedEnabled.store(enabled?1u:0u,std::memory_order_relaxed);
        requestedHash.store(parameterHash,std::memory_order_relaxed);requestedFilter.store(filterType,std::memory_order_relaxed);requestedInterpolation.store(interpolation,std::memory_order_relaxed);
        configurationEpoch.fetch_add(1,std::memory_order_release);
    }
    // May run on the existing configuration thread or an interrupted audio callback.
    // The next audio block owns all actual capture-buffer invalidation.
    void discontinuity(){discontinuityEpoch.fetch_add(1,std::memory_order_release);}

    bool begin(uint32_t newRate,uint64_t appliedRevision,bool transitioning){
        Configuration current;if(!configurationSnapshot(current)){collecting=false;return false;}
        const auto reset=discontinuityEpoch.load(std::memory_order_acquire);
        const auto now=nowMs();
        if(rate!=newRate || reset!=resetEpoch){
            rate=newRate;resetEpoch=reset;collecting=false;count=0;
            generation=identity();lastWindowMs=0;
        }
        if(collecting && (current.parameterHash!=foundation.parameterHash || current.enabled!=foundation.enabled ||
                          windowApplied!=epochValue(appliedRevision))){
            scratch[6]|=kConfigurationChanged;
        }
        foundation=current;
        if(!alive(deadline.load(std::memory_order_acquire),now)){
            collecting=false;count=0;return false;
        }
        if(collecting){
            scratch[11]=foundation.parameterHash;scratch[13]=foundation.revision;scratch[24]=foundation.enabled;scratch[25]=foundation.filterType;scratch[26]=foundation.interpolation;
            scratch[6]=(scratch[6]&~kModuleEnabled)|(foundation.enabled?kModuleEnabled:0u);
            return true;
        }
        // Keep the latest complete window throughout the short observation lease,
        // rather than collecting only a stale post-GET window while a slider moves.
        collecting=true;count=0;windowApplied=epochValue(appliedRevision);
        if(lastWindowMs && uint32_t(now-lastWindowMs)>kLeaseMs)generation=identity();
        lastWindowMs=now;
        fillHeader(scratch.data(),rate,windowApplied,transitioning);
        scratch[6]|=kReady;
        scratch[14]=uint32_t(position);scratch[15]=uint32_t(position>>32);
        const auto last=position+kFrames-1;scratch[16]=uint32_t(last);scratch[17]=uint32_t(last>>32);
        return true;
    }
    void push(float inputL,float inputR,float outputL,float outputR,bool transitioning){
        if(!collecting)return;
        if(transitioning)scratch[6]|=kTransitioning;
        const std::array<float,kPlanes> values{inputL,inputR,outputL,outputR};
        for(unsigned plane=0;plane<kPlanes;++plane){
            float value=values[plane];if(!std::isfinite(value)){value=0;scratch[6]|=kNonFinite;}
            std::memcpy(scratch.data()+kHeader+plane*kFrames+count,&value,sizeof(value));
        }
        if(++count!=kFrames)return;
        sequence=(sequence+1u)&0x7fffffffu;scratch[2]=sequence;scratch[5]=kFrames;scratch[23]=15;
        publish(kWords);collecting=false;
    }
    unsigned remaining() const {return collecting?kFrames-count:0;}
    void advance(uint32_t frames){position+=frames;}

    bool read(int32_t* out){
        const auto now=nowMs();const bool previous=alive(deadline.load(std::memory_order_acquire),now);
        deadline.store(now+kLeaseMs,std::memory_order_release);
        for(unsigned attempt=0;attempt<3;++attempt){
            const auto before=publicationEpoch.load(std::memory_order_acquire);if(before&1u)continue;
            for(unsigned i=0;i<kWords;++i)out[i]=published[i].load(std::memory_order_relaxed);
            std::atomic_thread_fence(std::memory_order_acquire);
            if(before!=publicationEpoch.load(std::memory_order_relaxed))continue;
            Configuration current;if(!configurationSnapshot(current))continue;
            const auto reset=discontinuityEpoch.load(std::memory_order_acquire);
            out[18]=kLeaseMs;out[6]|=kLeaseActive;
            // New targets, rate/reset boundaries and a renewed dormant lease cannot
            // relabel an old PCM window as the newly selected configuration.
            if(!previous || uint32_t(out[12])!=reset ||
               uint32_t(out[11])!=current.parameterHash || uint32_t(out[24])!=current.enabled){
                out[5]=0;out[6]&=~kReady;out[23]=0;
                out[6]|=kConfigurationChanged;
                out[11]=current.parameterHash;out[12]=reset;out[13]=current.revision;out[24]=current.enabled;out[25]=current.filterType;out[26]=current.interpolation;out[27]=1;
                out[6]=(out[6]&~kModuleEnabled)|(current.enabled?kModuleEnabled:0u);
            }
            return true;
        }
        return false;
    }
private:
    struct Configuration{uint32_t serial=0,revision=0,enabled=0,parameterHash=0,filterType=0,interpolation=0;};
    bool configurationSnapshot(Configuration& out) const {
        for(unsigned attempt=0;attempt<3;++attempt){
            const auto before=configurationEpoch.load(std::memory_order_acquire);if(before&1u)continue;
            out={before,requestedRevision.load(std::memory_order_relaxed),requestedEnabled.load(std::memory_order_relaxed),requestedHash.load(std::memory_order_relaxed),requestedFilter.load(std::memory_order_relaxed),requestedInterpolation.load(std::memory_order_relaxed)};
            std::atomic_thread_fence(std::memory_order_acquire);
            if(before==configurationEpoch.load(std::memory_order_relaxed))return true;
        }
        return false;
    }
    void fillHeader(int32_t* out,uint32_t sampleRate,uint32_t applied,bool transitioning){
        std::fill_n(out,kHeader,0);out[0]=1;out[1]=kMagic;out[2]=sequence;out[3]=generation;out[4]=sampleRate;
        out[6]=(foundation.enabled?kModuleEnabled:0u)|(transitioning?kTransitioning:0u)|
            (applied!=foundation.revision?kConfigurationChanged:0u);
        out[7]=kPlanes;out[8]=kFrames;out[9]=1;out[10]=captureId;out[11]=foundation.parameterHash;
        out[12]=resetEpoch;out[13]=foundation.revision;out[19]=applied;out[20]=kWords;out[21]=1;out[22]=1;
        out[24]=foundation.enabled;out[25]=foundation.filterType;out[26]=foundation.interpolation;out[27]=1;out[31]=sampleRate;
    }
    void publish(unsigned count){
        publicationEpoch.fetch_add(1,std::memory_order_acq_rel);
        for(unsigned i=0;i<count;++i)published[i].store(scratch[i],std::memory_order_relaxed);
        publicationEpoch.fetch_add(1,std::memory_order_release);
    }
    const uint32_t captureId;
    std::array<std::atomic<int32_t>,kWords> published{};
    std::array<int32_t,kWords> scratch{};
    std::atomic<uint32_t> deadline{0},publicationEpoch{0};
    std::atomic<uint32_t> configurationEpoch{0},requestedRevision{0},requestedEnabled{0},requestedHash{0},requestedFilter{0},requestedInterpolation{0},discontinuityEpoch{0};
    Configuration foundation{};
    uint32_t generation=identity(),sequence=0,lastWindowMs=0,rate=0,resetEpoch=0,windowApplied=0;
    uint64_t position=0;
    unsigned count=0;
    bool collecting=false;
};
}
