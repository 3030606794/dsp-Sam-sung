// SPDX-License-Identifier: GPL-3.0-or-later
#include "SurroundProcessor.h"
#include <algorithm>
#include <cmath>

namespace dspfusion {
namespace {
constexpr double pi=3.1415926535897932384626433832795;
// Circuit breakers for malformed upstream floats, not an audible limiter.
double finiteState(double value) {
    if(!std::isfinite(value)) return 0.;
    if(std::abs(value)<1e-30) return 0.;
    return std::clamp(value,-1e12,1e12);
}
double input(float value) { return std::isfinite(value)?double(value):0.; }
float output(double value) { return std::isfinite(value)?float(std::clamp(value,-1e6,1e6)):0.f; }
bool validLegacy(const int32_t* w,size_t n) {
    return w && n==6 && w[0]==1 && (w[1]==0 || w[1]==1) && w[2]>=10 && w[2]<=800 &&
        w[3]>=4000 && w[3]<=16000 && w[4]>=500 && w[4]<=10000 && w[5]>=20 && w[5]<=210;
}
double smoothing(double rate,double ms){return 1-std::exp(-1/(rate*ms*.001));}
double follow(double old,double target,double alpha){double v=old+alpha*(target-old);return std::abs(v-target)<1e-10?target:v;}
int32_t dbPower(double v){return v>1e-24?int32_t(std::llround(100*std::log10(v))):INT32_MIN;}
}

bool SurroundProcessor::Prepared::operator==(const Prepared& b) const {
    if(enabled!=b.enabled)return false;
    if(!enabled)return true;
    if(professional!=b.professional)return false;
    if(!professional)return highPass==b.highPass && lowPass==b.lowPass && gain==b.gain && fraction==b.fraction && delay==b.delay;
    return sections==b.sections && sectionCount==b.sectionCount && gains==b.gains && fractions==b.fractions && delays==b.delays && direct==b.direct && mix==b.mix;
}

SurroundProcessor::Prepared SurroundProcessor::prepare(const Settings& settings) const {
    Prepared result;
    if(!settings.enabled) return result;
    result.enabled=true;
    // Keep both corner frequencies in the representable band at low host rates,
    // preserving their ratio instead of allowing inverted/equal corners.
    const double scale=std::min(1.,sampleRate_*.45/settings.highHz);
    const auto design=[&](double hz,bool highPass) {
        const double omega=2*pi*hz/sampleRate_,cs=std::cos(omega);
        const double alpha=std::sin(omega)/std::sqrt(2.);
        const double inv=1/(1+alpha);
        const double b=(highPass?(1+cs):(1-cs))*.5*inv;
        return Coeff{b,(highPass?-2:2)*b,b,-2*cs*inv,(1-alpha)*inv};
    };
    result.highPass=design(settings.lowHz*scale,true);
    result.lowPass=design(settings.highHz*scale,false);
    result.gain=std::pow(10.,-settings.attenuation/20000.);
    const double delay=sampleRate_*settings.delayUs/1000000.;
    result.delay=static_cast<size_t>(std::floor(delay));
    result.fraction=delay-result.delay;
    const auto& p=settings.professional;
    result.professional=p[1]!=0;
    result.dynamics.rms=p[12]!=0;result.dynamics.limit=p[13]/10.;result.dynamics.attackMs=p[14];result.dynamics.releaseMs=p[15];result.dynamics.attenuationOnly=p[16]!=0;
    result.dynamics.peak=p[17]!=0;result.dynamics.ceiling=std::pow(10.,p[18]/200.);result.dynamics.peakReleaseMs=p[19];
    result.dynamics.attackAlpha=smoothing(sampleRate_,p[14]);result.dynamics.releaseAlpha=smoothing(sampleRate_,p[15]);result.dynamics.peakReleaseAlpha=smoothing(sampleRate_,p[19]);
    result.dynamics.output=result.professional?std::pow(10.,p[4]/200.):1;
    result.metadata={int32_t(std::llround(settings.lowHz*scale)),int32_t(std::llround(settings.highHz*scale)),settings.delayUs*10,settings.delayUs*10,settings.attenuation,settings.attenuation,1,1,0};
    if(result.professional) {
        result.direct=std::pow(10.,p[3]/200.);result.mix=p[2]/1000.;
        const auto section=[&](double hz,bool hp,double q,bool first) {
            const double w=2*pi*hz/sampleRate_;
            if(first){const double k=std::tan(w*.5),d=1+k,b=hp?1/d:k/d;return Coeff{b,hp?-b:b,0,(k-1)/d,0};}
            const double c=std::cos(w),a=std::sin(w)/(2*q),inv=1/(1+a),b=(hp?(1+c):(1-c))*.5*inv;
            return Coeff{b,(hp?-2:2)*b,b,-2*c*inv,(1-a)*inv};
        };
        for(int edge=0;edge<2;++edge) {
            const int slope=p[10+edge];const double hz=(edge?settings.highHz:settings.lowHz)*scale;
            if(slope==2){result.sections[result.sectionCount++]=section(hz,!edge,.541196100146197,false);result.sections[result.sectionCount++]=section(hz,!edge,1.306562964876377,false);}
            else result.sections[result.sectionCount++]=section(hz,!edge,1/std::sqrt(2.),slope==0);
        }
        for(int ch=0;ch<2;++ch) {
            const int route=p[5]?0:ch,att=p[6+route],us=p[8+route];
            result.gains[ch]=std::pow(10.,-att/20000.);const double d=sampleRate_*us/10000000.;result.delays[ch]=size_t(std::floor(d));result.fractions[ch]=d-result.delays[ch];
            result.metadata[2+ch]=us;result.metadata[4+ch]=att;
        }
        result.metadata[6]=p[10];result.metadata[7]=p[11];result.metadata[8]=p[3];
    }
    return result;
}

bool SurroundProcessor::setParameters(const int32_t* words,size_t count) {
    if(!validLegacy(words,count))return false;
    Settings next{words[1]!=0,words[2],words[3],words[4],words[5]};
    std::lock_guard<std::mutex> lock(updateMutex_);
    settings_=next;pending_=prepare(next);monitoring_.store(false);requestedAudition_=0;tools_.cancel();++revision_;
    return true;
}

bool SurroundProcessor::setProfessionalParameters(const int32_t* w,size_t count) {
    if(!w || count!=kProfessionalWords || !validLegacy(w,6))return false;
    for(size_t i=0;i<21;++i)if(w[6+i]<kSurroundProfessionalLow[i] || w[6+i]>kSurroundProfessionalHigh[i])return false;
    Settings next{w[1]!=0,w[2],w[3],w[4],w[5]};std::copy_n(w+6,21,next.professional.begin());
    std::lock_guard<std::mutex> lock(updateMutex_);settings_=next;pending_=prepare(next);monitoring_.store(w[26]!=0);
    if(!next.enabled){requestedAudition_=0;tools_.cancel();}
    ++revision_;return true;
}

bool SurroundProcessor::setAudition(int32_t request) {
    if(request<0 || request>4)return false;
    std::lock_guard<std::mutex> lock(updateMutex_);
    if(!request)requestedAudition_=0;
    else if(request!=latchedAudition_ && pending_.enabled)requestedAudition_=request;
    latchedAudition_=request;return true;
}
void SurroundProcessor::clearMonitoring(){meters_={};samples_=0;wasMonitoring_=false;}
void SurroundProcessor::cancelAudition(bool invalidate) {
    tools_.cancel(invalidate);
    std::lock_guard<std::mutex> lock(updateMutex_);requestedAudition_=0;
    if(invalidate){actualAudition_.store(0);auditionMix_={1,0,0};auditionFrom_=auditionMix_;auditionSelection_=0;auditionFade_=fadeFrames_;rmsIn_=rmsOut_=autoDb_=autoTargetDb_=0;rmsTick_=0;peakGain_=outputGain_=1;clearMonitoring();publish(false);}
}

void SurroundProcessor::configure(uint32_t sampleRate) {
    std::lock_guard<std::mutex> lock(updateMutex_);
    if(sampleRate<8000 || sampleRate>768000 || sampleRate==sampleRate_) return;
    tools_.cancel(true);sampleRate_=sampleRate;fadeFrames_=std::max(1u,sampleRate/50);
    pending_=prepare(settings_);++revision_;
    for(auto& bank:banks_) {bank.filters={};bank.clear();}
    active_=0;transitioning_=false;fadePosition_=0;
    requestedAudition_=0;actualAudition_.store(0);auditionMix_={1,0,0};auditionFrom_=auditionMix_;auditionSelection_=0;auditionFade_=fadeFrames_;rmsIn_=rmsOut_=autoDb_=autoTargetDb_=0;rmsTick_=0;peakGain_=outputGain_=1;clearMonitoring();publish(false);
}

void SurroundProcessor::reset() {
    tools_.cancel(true);
    std::lock_guard<std::mutex> lock(updateMutex_);
    for(auto& bank:banks_) {bank.filters={};bank.clear();}
    active_=0;transitioning_=false;fadePosition_=0;++revision_;
    requestedAudition_=0;actualAudition_.store(0);auditionMix_={1,0,0};auditionFrom_=auditionMix_;auditionSelection_=0;auditionFade_=fadeFrames_;rmsIn_=rmsOut_=autoDb_=autoTargetDb_=0;rmsTick_=0;peakGain_=outputGain_=1;clearMonitoring();publish(false);
}

void SurroundProcessor::adoptPending() {
    std::unique_lock<std::mutex> lock(updateMutex_,std::try_to_lock);
    if(!lock.owns_lock())return;
    actualAudition_.store(pending_.enabled?requestedAudition_:0);
    if(transitioning_ || revision_==acceptedRevision_)return;
    acceptedRevision_=revision_;
    dynamics_=pending_.dynamics;
    if(banks_[active_].filters==pending_){banks_[active_].filters.metadata=pending_.metadata;return;}
    auto& target=banks_[1-active_];target.filters=pending_;target.clear();
    fadePosition_=0;transitioning_=true;
}

void SurroundProcessor::Bank::clear() {
    states={};delayLine={};position=0;
}

double SurroundProcessor::Bank::previous(size_t channel,size_t delay) const {
    return delayLine[channel][(position+kRingSize-delay)%kRingSize];
}

double SurroundProcessor::Bank::offset(size_t channel) const {
    if(filters.professional){double v=0;for(size_t i=0;i<filters.sectionCount;++i)v=filters.sections[i].b0*v+states[channel][i].s1;return v;}
    // Cascade output = highPass.b0*lowPass.b0*input + this state offset.
    return filters.lowPass.b0*states[channel][0].s1+states[channel][1].s1;
}

double SurroundProcessor::Bank::filter(size_t channel,double sample) {
    if(filters.professional){for(size_t i=0;i<filters.sectionCount;++i){const auto& c=filters.sections[i];auto& s=states[channel][i];const double y=finiteState(c.b0*sample+s.s1);s.s1=finiteState(c.b1*sample-c.a1*y+s.s2);s.s2=finiteState(c.b2*sample-c.a2*y);sample=y;}return sample;}
    const std::array<Coeff,2> coefficients{filters.highPass,filters.lowPass};
    for(size_t i=0;i<2;++i) {
        const auto& c=coefficients[i];auto& state=states[channel][i];
        const double value=finiteState(c.b0*sample+state.s1);
        state.s1=finiteState(c.b1*sample-c.a1*value+state.s2);
        state.s2=finiteState(c.b2*sample-c.a2*value);
        sample=value;
    }
    return sample;
}

void SurroundProcessor::Bank::tick(double& left,double& right) {
    if(!filters.enabled) return;
    if(filters.professional){tickProfessional(left,right);return;}
    const auto delayedOffset=[&](size_t channel) {
        return (1-filters.fraction)*(filters.delay?previous(channel,filters.delay):offset(channel))+
               filters.fraction*previous(channel,filters.delay+1);
    };
    const double l=left-filters.gain*delayedOffset(1);
    const double r=right-filters.gain*delayedOffset(0);
    // A sub-sample delay contains today's filtered output. Solve both channels
    // together, rather than rounding 20 us to zero or adding a hidden sample.
    const double cross=filters.delay?0:filters.gain*(1-filters.fraction)*
        filters.highPass.b0*filters.lowPass.b0;
    const double denominator=1-cross*cross;
    left=finiteState((l-cross*r)/denominator);
    right=finiteState((r-cross*l)/denominator);
    delayLine[0][position]=filter(0,left);
    delayLine[1][position]=filter(1,right);
    position=(position+1)%kRingSize;
}

void SurroundProcessor::Bank::tickProfessional(double& left,double& right) {
    const double dryL=left*filters.direct,dryR=right*filters.direct;left=dryL;right=dryR;
    const auto delayed=[&](size_t ch){return (1-filters.fractions[ch])*(filters.delays[ch]?previous(ch,filters.delays[ch]):offset(ch))+filters.fractions[ch]*previous(ch,filters.delays[ch]+1);};
    double b0=1;for(size_t i=0;i<filters.sectionCount;++i)b0*=filters.sections[i].b0;
    const double cl=filters.delays[1]?0:filters.gains[1]*(1-filters.fractions[1])*b0,cr=filters.delays[0]?0:filters.gains[0]*(1-filters.fractions[0])*b0;
    const double l=left-filters.gains[1]*delayed(1),r=right-filters.gains[0]*delayed(0),den=1-cl*cr;
    left=finiteState((l-cl*r)/den);right=finiteState((r-cr*l)/den);
    delayLine[0][position]=filter(0,left);delayLine[1][position]=filter(1,right);position=(position+1)%kRingSize;
    left=dryL+filters.mix*(left-dryL);right=dryR+filters.mix*(right-dryR);
}

void SurroundProcessor::process(float* samples,uint32_t frames) {
    if(!samples || !frames) return;
    adoptPending();
    tools_.begin(sampleRate_,banks_[transitioning_?1-active_:active_].filters.enabled,actualAudition_.load());
    if(!transitioning_ && !banks_[active_].filters.enabled){clearMonitoring();publish(false);return;} // Exact dry bytes.
    const bool monitoring=monitoring_.load();if(monitoring!=wasMonitoring_){clearMonitoring();wasMonitoring_=monitoring;}
    const double detector=smoothing(sampleRate_,100),peakDecay=std::exp(-1./sampleRate_),smooth=smoothing(sampleRate_,20);
    const int aud=actualAudition_.load(),selected=aud==1?1:(aud==2?2:0);
    if(selected!=auditionSelection_){auditionFrom_=auditionMix_;auditionSelection_=selected;auditionFade_=0;}
    for(uint32_t frame=0;frame<frames;++frame) {
        if(!transitioning_ && !banks_[active_].filters.enabled){publish(false);return;}
        double dryL=input(samples[2*frame]),dryR=input(samples[2*frame+1]);tools_.inject(dryL,dryR);
        double left=dryL,right=dryR;
        banks_[active_].tick(left,right);
        double referenceGain=banks_[active_].filters.direct;
        if(transitioning_) {
            double nextL=dryL,nextR=dryR;banks_[1-active_].tick(nextL,nextR);
            const double mix=double(fadePosition_+1)/fadeFrames_;
            left+=(nextL-left)*mix;right+=(nextR-right)*mix;
            referenceGain+=mix*(banks_[1-active_].filters.direct-referenceGain);
            if(++fadePosition_>=fadeFrames_) {active_=1-active_;transitioning_=false;}
        }
        rmsIn_+=(((dryL*dryL+dryR*dryR)*.5*referenceGain*referenceGain)-rmsIn_)*detector;
        rmsOut_+=(((left*left+right*right)*.5)-rmsOut_)*detector;
        if(!dynamics_.rms)autoTargetDb_=0;
        else if(rmsTick_++%16==0)autoTargetDb_=rmsIn_>1e-9 && rmsOut_>1e-9?std::clamp(10*std::log10(rmsIn_/rmsOut_),-dynamics_.limit,dynamics_.attenuationOnly?0:dynamics_.limit):0;
        autoDb_=follow(autoDb_,autoTargetDb_,dynamics_.rms?(autoTargetDb_<autoDb_?dynamics_.attackAlpha:dynamics_.releaseAlpha):smooth);
        autoDb_=std::clamp(autoDb_,-6.,6.);outputGain_=follow(outputGain_,dynamics_.output,smooth);
        if(autoDb_!=0 || outputGain_!=1){double gain=std::pow(10.,autoDb_/20.)*outputGain_;left*=gain;right*=gain;}
        const double peak=std::max(std::abs(left),std::abs(right)),ceiling=dynamics_.peak?dynamics_.ceiling:1;
        const double peakTarget=dynamics_.peak && peak>ceiling?ceiling/peak:1;
        peakGain_=peakTarget<peakGain_?peakTarget:follow(peakGain_,peakTarget,dynamics_.peak?dynamics_.peakReleaseAlpha:smooth);
        if(peakGain_!=1){left*=peakGain_;right*=peakGain_;}
        if(monitoring)for(size_t i=0;i<2;++i){const double l=i?left:dryL,r=i?right:dryR;auto& m=meters_[i];m.l+=(l*l-m.l)*detector;m.r+=(r*r-m.r)*detector;m.lr+=(l*r-m.lr)*detector;m.peak=std::max(std::max(std::abs(l),std::abs(r)),m.peak*peakDecay);}
        ++samples_;
        if(auditionFade_<fadeFrames_){const double t=double(++auditionFade_)/fadeFrames_;for(int i=0;i<3;++i)auditionMix_[i]=auditionFrom_[i]+t*((i==selected?1.:0.)-auditionFrom_[i]);}
        // Preserve default output bits, including signed zero.
        const bool normal=auditionMix_[0]==1 && auditionMix_[1]==0 && auditionMix_[2]==0;
        const double al=normal?left:auditionMix_[0]*left+auditionMix_[1]*dryL+auditionMix_[2]*(left-dryL),ar=normal?right:auditionMix_[0]*right+auditionMix_[1]*dryR+auditionMix_[2]*(right-dryR);
        const double matchGain=tools_.match(dryL,dryR,left,right,!transitioning_);
        samples[2*frame]=output(matchGain==1?al:al*matchGain);samples[2*frame+1]=output(matchGain==1?ar:ar*matchGain);
    }
    publish();tools_.publish();
}

void SurroundProcessor::publish(bool valid) {
    const auto& p=banks_[transitioning_?1-active_:active_].filters;std::array<int32_t,kRuntimeWords> v{};
    v[0]=1;v[2]=sampleRate_;v[3]=p.enabled;v[4]=p.professional;v[5]=valid && p.enabled && monitoring_.load() && samples_>=uint64_t(sampleRate_*.1);v[6]=samples_&0x7fffffff;
    for(int i=7;i<=12;++i)v[i]=INT32_MIN;
    if(v[5])for(int i=0;i<2;++i){const auto& m=meters_[i];v[7+i]=dbPower((m.l+m.r)*.5);v[9+i]=dbPower(m.peak*m.peak);v[11+i]=m.l>1e-20 && m.r>1e-20?int32_t(std::llround(std::clamp(m.lr/std::sqrt(m.l*m.r),-1.,1.)*10000)):INT32_MIN;}
    v[13]=int32_t(std::llround(autoDb_*10));v[14]=dbPower(peakGain_*peakGain_);v[15]=int32_t(std::llround(p.mix*1000));
    std::copy(p.metadata.begin(),p.metadata.begin()+8,v.begin()+16);v[24]=actualAudition_.load();v[25]=p.metadata[8];v[26]=dbPower(outputGain_*outputGain_);v[27]=dynamics_.rms;v[28]=dynamics_.peak;v[29]=monitoring_.load();v[30]=acceptedRevision_&0x7fffffff;
    runtimeEpoch_.fetch_add(1);for(size_t i=0;i<v.size();++i)runtime_[i].store(v[i]);runtimeEpoch_.fetch_add(1);
}
bool SurroundProcessor::snapshot(int32_t* words) const {
    if(!words)return false;
    for(int attempt=0;attempt<3;++attempt){const auto first=runtimeEpoch_.load();if(first&1)continue;for(size_t i=0;i<kRuntimeWords;++i)words[i]=runtime_[i].load();const auto last=runtimeEpoch_.load();if(first==last && !(last&1)){words[1]=last&0x7fffffff;return true;}}
    return false;
}
}
