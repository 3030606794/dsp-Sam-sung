// SPDX-License-Identifier: GPL-3.0-or-later
#include "ToneProcessor.h"
#include <algorithm>
#include <cmath>
#include <complex>
#include <climits>
#include <cstring>

namespace dspfusion {
namespace {
constexpr double pi=3.14159265358979323846;
double coefficient(double seconds,double rate) { return 1.0-std::exp(-1.0/(seconds*rate)); }
double gain(double db) { return std::pow(10.0,db/20.0); }
double clean(double value) { return std::isfinite(value)?std::clamp(value,-64.0,64.0):0.0; }
int32_t db100(double amplitude) { return amplitude>1e-12&&std::isfinite(amplitude)?int32_t(std::clamp(std::lround(2000*std::log10(amplitude)),-18000l,18000l)):INT_MIN; }
int32_t graphDb100(double amplitude) { return std::clamp(db100(amplitude),-18000,18000); }
int32_t attenuation100(double amplitude) { return std::clamp(db100(amplitude),-18000,0); }
constexpr std::array<int32_t,80> initial{{1,0,0,100,0,0,0,0,0,0,0,0,0,0,0,1000,0,0,0,0,0,0,0,0,0,0,0,180,3500,60,110,350,1800,3500,5500,8500,12000,80,75,80,100,110,110,1000,1000,1000,1000,1000,1000,0,1000,1000,1000,150,50,70,20,45,1000,1000,30,30,0,-800,0,1000,0,0,0,0,0,0,0,0,0,0,0,0,0,0}};
constexpr std::array<int32_t,80> minimum{{1,0,0,20,-100,-100,-100,-100,-100,-100,-100,-100,-100,-100,0,0,-2000,-2000,-2000,-2000,-2000,-2000,-2000,-2000,-2000,-2000,0,20,20,20,20,20,20,20,20,20,20,25,25,25,25,25,25,250,250,250,250,0,0,0,0,0,0,20,1,5,1,5,250,250,0,0,0,-1200,0,0,0,-240,0,-240,0,0,0,0,0,0,0,0,0,0}};
constexpr std::array<int32_t,80> maximum{{1,1,1,100,100,100,100,100,100,100,100,100,100,100,1,2000,2000,2000,2000,2000,2000,2000,2000,2000,2000,2000,1,20000,20000,20000,20000,20000,20000,20000,20000,20000,20000,400,400,400,400,400,400,1000,1000,1000,1000,2000,2000,1,2000,2000,2000,1000,500,500,500,500,4000,4000,120,120,1,-300,1,1000,1,0,1,0,1,1,0,0,0,0,0,0,0,0}};
constexpr std::array<double,10> defaultHz{{180,3500,60,110,350,1800,3500,5500,8500,12000}};
constexpr std::array<double,6> defaultQ{{.8,.75,.8,1,1.1,1.1}};
}
std::array<int32_t,ToneProcessor::advancedWords> ToneProcessor::defaults() { return initial; }
bool ToneProcessor::validateAdvanced(const int32_t* s,size_t count) {
    if(!s||count!=advancedWords)return false;
    for(size_t i=0;i<count;++i)if(s[i]<minimum[i]||s[i]>maximum[i])return false;
    if(s[3]!=20&&s[3]!=40&&s[3]!=100)return false;
    for(size_t i=4;i<14;++i)if(std::abs(s[i])>s[3])return false;
    return s[54]<s[55]*10&&s[56]<s[57]*10;
}
bool ToneProcessor::validateTools(const int32_t* s,size_t count) {
    return s&&count==toolsWords&&s[0]>=0&&s[0]<=6&&s[1]>=0&&s[1]<=1&&s[2]>=0&&
      (s[0]<4||s[2]>0)&&s[3]==0&&validateAdvanced(s+4,80)&&validateAdvanced(s+84,80);
}
ToneProcessor::ToneProcessor() {
    saved_=initial;
    for(auto& kernel:kernels_){kernel.settings=initial;kernel.configure(48000);}
    publish();
}
void ToneProcessor::configure(uint32_t rate) {
    const auto actual=std::clamp(rate,8000u,768000u);
    if(actual==sampleRate_)return;
    sampleRate_=actual;
    kernels_[active_].settings=saved_;kernels_[active_].configure(actual);
    kernels_[pending_].settings=saved_;kernels_[pending_].configure(actual);
    pendingValid_=false;clearToolsInternal();routeWeights_={0,1,0,0};retiredCompare_=false;
    publish();
}
void ToneProcessor::Filter::clear() { z1[0]=z1[1]=z2[0]=z2[1]=0; }
void ToneProcessor::Filter::advance(double a) {
    now.b0+=a*(target.b0-now.b0);now.b1+=a*(target.b1-now.b1);
    now.b2+=a*(target.b2-now.b2);now.a1+=a*(target.a1-now.a1);
    now.a2+=a*(target.a2-now.a2);
}
double ToneProcessor::Filter::tick(double input,unsigned c) {
    const double output=now.b0*input+z1[c];
    z1[c]=now.b1*input-now.a1*output+z2[c];z2[c]=now.b2*input-now.a2*output;
    if(std::abs(z1[c])<1e-24)z1[c]=0;
    if(std::abs(z2[c])<1e-24)z2[c]=0;
    if(!std::isfinite(output)||!std::isfinite(z1[c])||!std::isfinite(z2[c])){z1[c]=z2[c]=0;return 0;}
    return output;
}
ToneProcessor::Coeff ToneProcessor::peak(double rate,double hz,double q,double db) {
    if(db==0)return {};
    const double w=2*pi*std::min(hz,rate*.43)/rate;
    const double a=std::pow(10.,db/40.),cs=std::cos(w),al=std::sin(w)/(2*q),d=1+al/a;
    return {(1+al*a)/d,-2*cs/d,(1-al*a)/d,-2*cs/d,(1-al/a)/d};
}
ToneProcessor::Coeff ToneProcessor::shelf(double rate,double hz,double db,bool high,double slope) {
    if(db==0)return {};
    const double w=2*pi*std::min(hz,rate*.43)/rate;
    const double a=std::pow(10.,db/40.),cs=std::cos(w);
    // S=1 uses the exact frozen arithmetic; S<=1 shelves remain monotonic.
    const double t=slope==1?std::sqrt(2*a)*std::sin(w):std::sin(w)*std::sqrt(a)*std::sqrt((a+1/a)*(1/slope-1)+2);
    if(high){const double d=(a+1)-(a-1)*cs+t;
      return {a*((a+1)+(a-1)*cs+t)/d,-2*a*((a-1)+(a+1)*cs)/d,a*((a+1)+(a-1)*cs-t)/d,2*((a-1)-(a+1)*cs)/d,((a+1)-(a-1)*cs-t)/d};}
    const double d=(a+1)+(a-1)*cs+t;
    return {a*((a+1)-(a-1)*cs+t)/d,2*a*((a-1)-(a+1)*cs)/d,a*((a+1)-(a-1)*cs-t)/d,-2*((a-1)+(a+1)*cs)/d,((a+1)+(a-1)*cs-t)/d};
}
double ToneProcessor::magnitude(const Coeff& c,double w) {
    const auto z=std::polar(1.,-w);
    return std::abs((c.b0+c.b1*z+c.b2*z*z)/(1.+c.a1*z+c.a2*z*z));
}
bool ToneProcessor::neutralSettings(const Settings& s) {
    if(!s[1])return true;
    if(s[14]){if(s[15]!=1000)return false;for(size_t i=16;i<26;++i)if(s[i])return false;}
    if(s[26])for(size_t i=27;i<=48;++i)if(s[i]!=initial[i])return false;
    if(s[49])for(size_t i=50;i<=63;++i)if(s[i]!=initial[i])return false;
    if(s[64]&&s[65]!=1000)return false;
    if(s[66]&&s[67])return false;
    if(s[68]&&s[69])return false;
    return true;
}
bool ToneProcessor::sameSound(const Settings& a,const Settings& b) {
    auto normalize=[](Settings s){
      s[0]=1;s[3]=100;s[70]=s[71]=0;
      if(!s[1]){for(size_t i=14;i<80;++i)s[i]=initial[i];}
      else {for(const auto pair:std::array<std::array<int,3>,6>{{{{14,15,25}},{{26,27,48}},{{49,50,63}},{{64,65,65}},{{66,67,67}},{{68,69,69}}}})
        if(!s[pair[0]])for(int i=pair[1];i<=pair[2];++i)s[i]=initial[i];}
      if(neutralSettings(s)){s[1]=0;for(size_t i=14;i<80;++i)s[i]=initial[i];}
      return s;
    };
    return normalize(a)==normalize(b);
}
void ToneProcessor::Kernel::configure(uint32_t sampleRate) {rate=sampleRate;alpha=coefficient(.020,rate);design();reset();}
void ToneProcessor::Kernel::assign(const Settings& s,bool resetState) {
    const bool restore=!resetState&&settings[1]&&!s[1]&&!neutral;
    const double oldPunchCap=settings[1]&&settings[49]?std::min(3*std::abs(punchTarget),punchMaximum):3*std::abs(punchTarget);
    const double oldTransientCap=settings[1]&&settings[49]?std::min(3*std::abs(transientTarget),transientMaximum):3*std::abs(transientTarget);
    settings=s;design();
    if(restore){restoring=true;restorePunchCap=std::max(oldPunchCap,3*std::abs(punchTarget));restoreTransientCap=std::max(oldTransientCap,3*std::abs(transientTarget));}
    if(resetState)reset();
    else if(wet==0&&wetTarget==0){mix=mixTarget;output=outputTarget;manual=manualTarget;restoring=false;}
}
void ToneProcessor::Kernel::design() {
    enabled=settings[2];neutral=ToneProcessor::neutralSettings(settings);
    const bool pro=settings[1],core=pro&&settings[14],shape=pro&&settings[26],dynamic=pro&&settings[49];
    for(size_t i=0;i<10;++i){knobs[i]=settings[4+i];values[i]=knobs[i]/100.;
      if(core&&(settings[15]!=1000||settings[16+i]!=0))values[i]=std::clamp(knobs[i]+settings[16+i]/10.,-100.,100.)/100.*(settings[15]/1000.);}
    for(size_t i=0;i<10;++i)actualHz[i]=std::min(shape?double(settings[27+i]):defaultHz[i],rate*.43);
    for(size_t i=0;i<6;++i)actualQ[i+3]=shape?settings[37+i]/100.:defaultQ[i];
    const auto value=[this](size_t i){return values[i];};
    const double lowWeight=shape?settings[47]/1000.:1,highWeight=shape?settings[48]/1000.:1;
    const double elasticStatic=dynamic?settings[50]/1000.:1;
    const std::array<double,10> db{{4*value(0)*lowWeight,-4*value(0)*highWeight,6*value(1),3*value(2)*elasticStatic,4*value(3),4*value(4),3*value(5),4*value(6),4*value(7),4*value(9)}};
    filters[0].target=ToneProcessor::shelf(rate,actualHz[0],db[0],false,shape?settings[43]/1000.:1);
    filters[1].target=ToneProcessor::shelf(rate,actualHz[1],db[1],true,shape?settings[44]/1000.:1);
    filters[2].target=ToneProcessor::shelf(rate,actualHz[2],db[2],false,shape?settings[45]/1000.:1);
    for(size_t i=3;i<=8;++i)filters[i].target=ToneProcessor::peak(rate,actualHz[i],actualQ[i],db[i]);
    filters[9].target=ToneProcessor::shelf(rate,actualHz[9],db[9],true,shape?settings[46]/1000.:1);
    punchTarget=value(2)*(dynamic?settings[51]/1000.:1);
    transientTarget=value(8)*(dynamic?settings[52]/1000.:1);
    punchSensitivity=dynamic?settings[58]/1000.:1;transientSensitivity=dynamic?settings[59]/1000.:1;
    punchMaximum=dynamic?settings[60]/10.:3*std::abs(punchTarget);
    transientMaximum=dynamic?settings[61]/10.:3*std::abs(transientTarget);
    weakFloor=dynamic&&settings[62]?gain(settings[63]/10.):0;
    mixTarget=pro&&settings[64]?settings[65]/1000.:1;
    outputTarget=pro&&settings[66]?gain(settings[67]/10.):1;
    manualTarget=pro&&settings[68]?gain(settings[69]/10.):1;
    lowAlpha=1.-std::exp(-2.*pi*(dynamic?std::min(double(settings[53]),rate*.43):150.)/rate);
    fastAlpha=coefficient(dynamic?settings[56]/10000.:.002,rate);
    slowAlpha=coefficient(dynamic?settings[57]/1000.:.045,rate);
    bassFastAlpha=coefficient(dynamic?settings[54]/10000.:.005,rate);
    bassSlowAlpha=coefficient(dynamic?settings[55]/1000.:.070,rate);
    const bool any=std::any_of(values.begin(),values.end(),[](double x){return x!=0;});
    wetTarget=enabled&&(any||outputTarget!=1||manualTarget!=1)?1.:0.;
    double maximumResponse=1;
    bool shapeChanged=false;if(shape)for(size_t i=27;i<=48;++i)if(settings[i]!=initial[i])shapeChanged=true;
    if(shapeChanged){
      // Exact analytic individual maxima bound their cascade at every frequency.
      // Peaking EQ maxima are max(0,dB); S<=1 shelves are monotonic. A sparse
      // sampled plot cannot safely bound high-Q peaks, hence the conservative sum.
      double upperDb=0;for(const double d:db)upperDb+=std::max(0.,d);
      maximumResponse=gain(upperDb);
    }else for(unsigned i=0;i<=192;++i){
      const double f=20*std::pow((rate*.499)/20,i/192.);
      double response=1;for(const auto& filter:filters)response*=ToneProcessor::magnitude(filter.target,2*pi*f/rate);
      maximumResponse=std::max(maximumResponse,response);
    }
    const double punchBound=dynamic?std::min(3*std::abs(punchTarget),punchMaximum):3*std::abs(punchTarget);
    const double transientBound=dynamic?std::min(3*std::abs(transientTarget),transientMaximum):3*std::abs(transientTarget);
    if(neutral)preampTarget=1.0/(maximumResponse*gain(3*std::abs(punchTarget)+3*std::abs(transientTarget)));
    else preampTarget=1./(maximumResponse*gain(punchBound+transientBound));
}
void ToneProcessor::Kernel::reset() {
    for(auto& filter:filters){filter.clear();filter.now=filter.target;}
    low[0]=low[1]=fast=slow=bassFast=bassSlow=0;
    preamp=preampTarget;punch=punchTarget;transient=transientTarget;wet=wetTarget;punchDb=transientDb=0;
    mix=mixTarget;output=outputTarget;manual=manualTarget;restoring=false;restorePunchCap=restoreTransientCap=0;
}
void ToneProcessor::Kernel::tick(const float* input,float* outputSamples) {
    if(wetTarget==0&&wet==0){outputSamples[0]=input[0];outputSamples[1]=input[1];punchDb=transientDb=0;return;}
    wet+=alpha*(wetTarget-wet);preamp+=alpha*(preampTarget-preamp);
    punch+=alpha*(punchTarget-punch);transient+=alpha*(transientTarget-transient);
    mix+=alpha*(mixTarget-mix);output+=alpha*(outputTarget-output);manual+=alpha*(manualTarget-manual);
    if(std::abs(mix-mixTarget)<1e-12)mix=mixTarget;
    if(std::abs(output-outputTarget)<1e-12)output=outputTarget;
    if(std::abs(manual-manualTarget)<1e-12)manual=manualTarget;
    if(restoring&&std::abs(punch-punchTarget)<1e-10&&std::abs(transient-transientTarget)<1e-10&&mix==mixTarget&&output==outputTarget&&manual==manualTarget)restoring=false;
    for(auto& filter:filters)filter.advance(alpha);
    const double dry[2]={clean(input[0]),clean(input[1])};
    double x[2]={dry[0]*preamp,dry[1]*preamp};
    for(unsigned c=0;c<2;++c){for(auto& filter:filters)x[c]=filter.tick(x[c],c);low[c]+=lowAlpha*(x[c]-low[c]);}
    const double level=std::max(std::abs(x[0]),std::abs(x[1])),bass=std::max(std::abs(low[0]),std::abs(low[1]));
    fast+=fastAlpha*(level-fast);slow+=slowAlpha*(level-slow);
    bassFast+=bassFastAlpha*(bass-bassFast);bassSlow+=bassSlowAlpha*(bass-bassSlow);
    double onset=std::clamp((fast-slow)/(slow+1e-5),0.,1.);
    double bassOnset=std::clamp((bassFast-bassSlow)/(bassSlow+1e-5),0.,1.);
    const bool dynamic=settings[1]&&settings[49];
    if(dynamic){onset=std::clamp(onset*transientSensitivity,0.,1.);bassOnset=std::clamp(bassOnset*punchSensitivity,0.,1.);
      if(weakFloor>0){if(level<weakFloor)onset=0;if(bass<weakFloor)bassOnset=0;}}
    punchDb=3*punch*bassOnset;transientDb=3*transient*onset;
    if(dynamic){punchDb=std::clamp(punchDb,-punchMaximum,punchMaximum);transientDb=std::clamp(transientDb,-transientMaximum,transientMaximum);}
    else if(restoring){punchDb=std::clamp(punchDb,-restorePunchCap,restorePunchCap);transientDb=std::clamp(transientDb,-restoreTransientCap,restoreTransientCap);}
    const double punchGain=gain(punchDb)-1,transientGain=gain(transientDb);
    for(unsigned c=0;c<2;++c){
      const double effect=(x[c]+punchGain*low[c])*transientGain;
      // The frozen path preserves its exact operation order, including float cast.
      if(neutral&&!restoring&&mix==1&&output==1&&manual==1)outputSamples[c]=static_cast<float>(clean(dry[c]+wet*(effect-dry[c])));
      else {const double mixed=dry[c]+mix*(effect-dry[c]);const double adjusted=mixed*output*manual;
        outputSamples[c]=static_cast<float>(clean(dry[c]+wet*(adjusted-dry[c])));}
    }
    if(wetTarget==0&&wet<1e-7){wet=0;for(auto& filter:filters)filter.clear();low[0]=low[1]=fast=slow=bassFast=bassSlow=0;
      mix=mixTarget;output=outputTarget;manual=manualTarget;restoring=false;}
}
void ToneProcessor::begin(const Settings& parameters) {
    auto& current=kernels_[active_];
    if(sameSound(current.settings,parameters)||(neutralSettings(current.settings)&&neutralSettings(parameters))){
      current.assign(parameters,false);pendingValid_=false;pendingFrames_=fadePosition_=0;return;
    }
    auto& candidate=kernels_[pending_];candidate.rate=sampleRate_;candidate.alpha=coefficient(.020,sampleRate_);candidate.assign(parameters,true);
    // Five longest envelope constants also cover filter settling at supported Q.
    // Low-frequency, high-Q filters require actual pole-radius settling, not a
    // fixed warm-up duration independent of their frequency.
    double warmSeconds=.100;
    const bool dynamic=parameters[1]&&parameters[49];
    warmSeconds=std::max(warmSeconds,5.*(dynamic?std::max(parameters[55],parameters[57])/1000.:.070));
    for(const auto& f:candidate.filters){
      const auto roots=std::sqrt(std::complex<double>(f.target.a1*f.target.a1-4*f.target.a2,0));
      const double r=std::max(std::abs((-f.target.a1+roots)/2.),std::abs((-f.target.a1-roots)/2.));
      if(r>0&&r<1)warmSeconds=std::max(warmSeconds,std::min(30.,std::log(1e-5)/std::log(r)/sampleRate_));
    }
    pendingWarmFrames_=uint32_t(std::ceil(std::min(warmSeconds,30.)*sampleRate_-1e-9));
    fadeFrames_=std::max(1u,sampleRate_/50);pendingFrames_=fadePosition_=0;pendingValid_=true;
}
bool ToneProcessor::setParameters(const int32_t* values,size_t count) {
    if(!values||count!=11)return false;
    auto s=saved_;s[2]=values[0]!=0;s[3]=100;
    for(size_t i=0;i<10;++i)s[i+4]=std::clamp(values[i+1],-100,100);
    const bool changed=!sameSound(saved_,s),powerChanged=saved_[2]!=s[2];saved_=s;
    if(powerChanged)clearToolsInternal();else if(changed){tools_[0]=tools_[1]=0;}
    if(!s[2]){pendingValid_=false;pendingFrames_=fadePosition_=0;kernels_[active_].assign(s,false);}
    else begin(s);
    publish();return true;
}
bool ToneProcessor::setAdvanced(const int32_t* values,size_t count) {
    if(!validateAdvanced(values,count))return false;
    Settings s;std::copy_n(values,80,s.begin());
    // Normal session synchronisation resends this full packet. The rate/reset
    // paths already rebuild the kernel; identical controls need no filter or
    // protection-response design while the real-time wrapper lock is held.
    if(s==saved_)return true;
    const bool changed=!sameSound(saved_,s),powerChanged=saved_[2]!=s[2];
    const bool preservePending=pendingValid_&&!changed&&!powerChanged;
    const bool metersChanged=saved_[70]!=s[70]||saved_[1]!=s[1];saved_=s;
    if(powerChanged||!s[2]||!s[1])clearToolsInternal();else if(changed){tools_[0]=tools_[1]=0;}
    if(metersChanged){clips_=0;meterFrames_=0;}
    if(!s[2]||!s[1]){
      pendingValid_=false;pendingFrames_=fadePosition_=0;kernels_[active_].assign(s,false);
    }else if(preservePending)kernels_[pending_].settings=s;
    else begin(s);
    publish();return true;
}
void ToneProcessor::clearToolsInternal() {
    tools_.fill(0);compareFrames_=compareWarmFrames_=measureFrames_=0;stage_=0;comparisonPrepared_=comparisonWarmed_=false;
    energyA_=energyB_=0;attenuationA_=attenuationB_=1;
}
void ToneProcessor::clearTools(){clearToolsInternal();publish();}
void ToneProcessor::disable(){
    saved_[2]=0;pendingValid_=false;pendingFrames_=fadePosition_=0;
    kernels_[active_].assign(saved_,false);clearToolsInternal();publish();
}
void ToneProcessor::reset() {
    pendingValid_=false;pendingFrames_=fadePosition_=0;
    kernels_[active_].settings=saved_;kernels_[active_].configure(sampleRate_);
    clearToolsInternal();routeWeights_={0,1,0,0};retiredCompare_=false;clips_=0;meterFrames_=0;publish();
}
bool ToneProcessor::setTools(const int32_t* values,size_t count) {
    if(!validateTools(values,count))return false;
    if(values[0]==0){clearTools();return true;}
    if(!saved_[2]||!saved_[1]){clearTools();return false;}
    const bool needSnapshots=values[0]>=4;
    const bool same=tools_[2]==values[2]&&tools_[2]>0&&std::equal(values+4,values+164,tools_.begin()+4);
    if(needSnapshots&&(!same||!comparisonPrepared_)){
      if(!retiredCompare_&&(std::abs(routeWeights_[2])+std::abs(routeWeights_[3])>1e-6)){
        kernels_[4]=kernels_[2];kernels_[5]=kernels_[3];retiredCompare_=true;
      }
      Settings a,b;std::copy_n(values+4,80,a.begin());std::copy_n(values+84,80,b.begin());
      for(unsigned i=0;i<2;++i){auto& k=kernels_[i+2];k.rate=sampleRate_;k.alpha=coefficient(.020,sampleRate_);k.assign(i==0?a:b,true);}
      compareFrames_=measureFrames_=0;energyA_=energyB_=0;attenuationA_=attenuationB_=1;stage_=1;comparisonPrepared_=true;comparisonWarmed_=false;
      double seconds=.100;
      for(unsigned i=2;i<4;++i){const auto& k=kernels_[i];const bool dyn=k.settings[1]&&k.settings[49];
        seconds=std::max(seconds,5*(dyn?std::max(k.settings[55],k.settings[57])/1000.:.070));
        for(const auto& f:k.filters){const auto roots=std::sqrt(std::complex<double>(f.target.a1*f.target.a1-4*f.target.a2,0));
          const double radius=std::max(std::abs((-f.target.a1+roots)/2.),std::abs((-f.target.a1-roots)/2.));
          if(radius>0&&radius<1)seconds=std::max(seconds,std::min(30.,std::log(1e-5)/std::log(radius)/sampleRate_));}}
      compareWarmFrames_=uint32_t(std::ceil(std::min(seconds,30.)*sampleRate_-1e-9));
    }
    if(!needSnapshots&&!same){comparisonPrepared_=comparisonWarmed_=false;stage_=0;compareFrames_=measureFrames_=0;energyA_=energyB_=0;attenuationA_=attenuationB_=1;}
    if(values[0]==6&&same&&comparisonPrepared_&&comparisonWarmed_&&stage_!=3&&tools_[0]!=6){
      stage_=2;measureFrames_=0;energyA_=energyB_=0;attenuationA_=attenuationB_=1;
    }
    std::copy_n(values,164,tools_.begin());publish();return true;
}
void ToneProcessor::process(float* data,uint32_t frames) {
    if(!data||!frames)return;
    const auto& current=kernels_[active_];
    if(!pendingValid_&&!comparisonPrepared_&&!retiredCompare_&&tools_[0]==0&&
       !(saved_[1]&&saved_[70])&&current.wet==0&&current.wetTarget==0&&
       routeWeights_[0]==0&&routeWeights_[1]==1&&routeWeights_[2]==0&&routeWeights_[3]==0){
      meterFrames_=0;publish();return;
    }
    inputPeak_[0]=inputPeak_[1]=outputPeak_[0]=outputPeak_[1]=0;
    inputEnergy_[0]=inputEnergy_[1]=outputEnergy_[0]=outputEnergy_[1]=0;meterFrames_=frames;
    const double routeAlpha=coefficient(.020,sampleRate_);
    for(uint32_t i=0;i<frames;++i){
      const float raw[2]={data[2*i],data[2*i+1]};float effect[2];kernels_[active_].tick(raw,effect);
      if(pendingValid_){float next[2];kernels_[pending_].tick(raw,next);++pendingFrames_;
        if(pendingFrames_>=pendingWarmFrames_){const double blend=std::min(1.,double(++fadePosition_)/fadeFrames_);
          for(unsigned c=0;c<2;++c)effect[c]=float(effect[c]+blend*(next[c]-effect[c]));
          if(fadePosition_>=fadeFrames_){std::swap(active_,pending_);pendingValid_=false;}}}
      float a[2]{},b[2]{};
      const bool compare=tools_[2]>0&&comparisonPrepared_,tail=std::abs(routeWeights_[2])+std::abs(routeWeights_[3])>1e-12;
      if(compare||(tail&&!retiredCompare_)){kernels_[2].tick(raw,a);kernels_[3].tick(raw,b);}
      float routeA[2]={a[0],a[1]},routeB[2]={b[0],b[1]};
      if(retiredCompare_){kernels_[4].tick(raw,routeA);kernels_[5].tick(raw,routeB);
        if(std::abs(routeWeights_[2])+std::abs(routeWeights_[3])<1e-6){routeWeights_[2]=routeWeights_[3]=0;retiredCompare_=false;}}
      if(compare){
        if(stage_==1){if(++compareFrames_>=compareWarmFrames_){comparisonWarmed_=true;stage_=tools_[0]==6?2:0;}}
        else if(stage_==2&&tools_[0]==6){energyA_+=double(a[0])*a[0]+double(a[1])*a[1];energyB_+=double(b[0])*b[0]+double(b[1])*b[1];
          if(++measureFrames_>=sampleRate_*2){const double rmsA=std::sqrt(energyA_/(measureFrames_*2.)),rmsB=std::sqrt(energyB_/(measureFrames_*2.));
            if(rmsA<1e-6||rmsB<1e-6||!std::isfinite(rmsA)||!std::isfinite(rmsB)){stage_=4;attenuationA_=attenuationB_=1;}
            else {stage_=3;const double minimumRms=std::min(rmsA,rmsB);attenuationA_=minimumRms/rmsA;attenuationB_=minimumRms/rmsB;}}}
      }
      std::array<double,4> target{{0,1,0,0}};
      if(saved_[2]&&saved_[1])switch(tools_[0]){
        case 1:target={1,0,0,0};break;case 3:target={-1,1,0,0};break;
        case 4:if(comparisonWarmed_&&(!tools_[1]||stage_==3)&&!retiredCompare_)target={0,0,tools_[1]?attenuationA_:1.,0};break;
        case 5:if(comparisonWarmed_&&(!tools_[1]||stage_==3)&&!retiredCompare_)target={0,0,0,tools_[1]?attenuationB_:1.};break;default:break;}
      for(size_t j=0;j<4;++j){routeWeights_[j]+=routeAlpha*(target[j]-routeWeights_[j]);if(std::abs(routeWeights_[j]-target[j])<1e-12)routeWeights_[j]=target[j];}
      for(unsigned c=0;c<2;++c){
        // Exact normal route avoids sanitizing a legacy neutral/bypassed stream.
        if(routeWeights_[0]==0&&routeWeights_[1]==1&&routeWeights_[2]==0&&routeWeights_[3]==0)data[2*i+c]=effect[c];
        else data[2*i+c]=float(clean(routeWeights_[0]*clean(raw[c])+routeWeights_[1]*clean(effect[c])+routeWeights_[2]*clean(routeA[c])+routeWeights_[3]*clean(routeB[c])));
        const double in=clean(raw[c]),out=clean(data[2*i+c]);inputPeak_[c]=std::max(inputPeak_[c],std::abs(in));outputPeak_[c]=std::max(outputPeak_[c],std::abs(out));
        inputEnergy_[c]+=in*in;outputEnergy_[c]+=out*out;
      }
      if(std::max(std::abs(data[2*i]),std::abs(data[2*i+1]))>=1&&clips_<INT_MAX)++clips_;
    }
    publish();
}
void ToneProcessor::publish() {
    epoch_=(epoch_+2)&0x7ffffffeu;runtime_.fill(0);response_.fill(0);
    const auto& k=kernels_[active_];const auto& s=k.settings;
    runtime_[0]=1;runtime_[1]=int32_t(epoch_);runtime_[2]=int32_t(sampleRate_);runtime_[3]=saved_[2];runtime_[4]=saved_[1];
    runtime_[5]=tools_[0];runtime_[6]=tools_[1];runtime_[7]=stage_;runtime_[8]=int32_t(uint64_t(measureFrames_)*1000/sampleRate_);
    runtime_[9]=attenuation100(attenuationA_);runtime_[10]=attenuation100(attenuationB_);runtime_[11]=tools_[2];
    runtime_[12]=saved_[1]&&saved_[70];runtime_[13]=1;runtime_[14]=s[3];runtime_[15]=s[1]&&s[14];
    for(unsigned i=16;i<24;++i)runtime_[i]=INT_MIN;
    if(runtime_[12]&&meterFrames_){for(unsigned c=0;c<2;++c){runtime_[16+c]=db100(inputPeak_[c]);runtime_[18+c]=db100(outputPeak_[c]);runtime_[20+c]=db100(std::sqrt(inputEnergy_[c]/meterFrames_));runtime_[22+c]=db100(std::sqrt(outputEnergy_[c]/meterFrames_));}}
    runtime_[24]=runtime_[12]?int32_t(clips_):0;runtime_[25]=s[1]&&s[49];runtime_[26]=s[1]&&s[26];
    runtime_[27]=attenuation100(k.preamp);runtime_[28]=s[1]&&s[68]?s[69]:0;
    runtime_[29]=int32_t(std::clamp(std::lround(k.punchDb*100),-1200l,1200l));runtime_[30]=int32_t(std::clamp(std::lround(k.transientDb*100),-1200l,1200l));runtime_[31]=2000;
    runtime_[32]=s[1]&&s[64]?s[65]:1000;runtime_[33]=s[1]&&s[66]?s[67]:0;
    runtime_[34]=int32_t(std::lround(std::min(s[1]&&s[49]?double(s[53]):150.,sampleRate_*.43)));
    runtime_[35]=runtime_[25]?s[54]:50;runtime_[36]=runtime_[25]?s[55]:70;runtime_[37]=runtime_[25]?s[56]:20;runtime_[38]=runtime_[25]?s[57]:45;
    runtime_[39]=runtime_[25]?s[63]:-800;runtime_[40]=runtime_[25]&&s[62];
    for(unsigned i=0;i<10;++i){runtime_[41+i]=int32_t(std::clamp(std::lround(k.values[i]*1000),-2000l,2000l));runtime_[51+i]=int32_t(std::lround(k.actualHz[i]));}
    runtime_[61]=int32_t(uint64_t(tools_[2]?compareFrames_:pendingFrames_)*1000/sampleRate_);
    runtime_[62]=int32_t(uint64_t(tools_[2]?compareWarmFrames_:(pendingValid_?pendingWarmFrames_:0))*1000/sampleRate_);
    response_[0]=1;response_[1]=int32_t(epoch_);response_[2]=int32_t(sampleRate_);response_[4]=32;
    response_[3]=saved_[1]&&saved_[2]&&saved_[71];
    if(response_[3])for(unsigned i=0;i<32;++i){const int32_t hz=int32_t(std::lround(20*std::pow((sampleRate_*.43)/20,i/31.)));
      const auto z=std::polar(1.,-2*pi*hz/sampleRate_);std::complex<double> h=1;
      for(const auto& filter:k.filters){const auto& c=filter.now;h*=(c.b0+c.b1*z+c.b2*z*z)/(1.+c.a1*z+c.a2*z*z);}
      const auto staticEffect=h*k.preamp;const auto mixed=(1.-k.mix+k.mix*staticEffect)*k.manual*k.output;
      const auto combined=1.-k.wet+k.wet*mixed;
      const unsigned offset=8+6*i;response_[offset]=hz;response_[offset+1]=graphDb100(std::abs(h));response_[offset+2]=graphDb100(std::abs(combined));response_[offset+5]=1;
    }
}
void ToneProcessor::status(int32_t* out,size_t count) const {if(out&&count>=runtime_.size())std::copy(runtime_.begin(),runtime_.end(),out);}
void ToneProcessor::response(int32_t* out,size_t count) const {if(out&&count>=response_.size())std::copy(response_.begin(),response_.end(),out);}
}
