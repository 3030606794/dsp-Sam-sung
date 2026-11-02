#include "RoomReverb.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <new>
extern "C" {
#include "../../app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/jdsp_header.h"
void sf_presetreverb(sf_reverb_state_st*,int,sf_reverb_preset);
}
#undef min
#undef max
namespace {
using Settings=std::array<int32_t,ROOM_REVERB_WORDS>;
constexpr int delayCapacity=40002;
constexpr double pi=3.14159265358979323846;
struct Base {float rt,early,earlyWidth,width,damp;};
constexpr Base bases[19]={
 {3.2f,1.6f,.7f,1.f,7000},{2.1f,1.f,.7f,1.f,9000},{2.3f,1.f,.7f,1.f,7000},
 {2.8f,1.2f,.7f,1.f,8000},{2.9f,1.2f,.7f,1.f,6000},{3.8f,1.4f,.7f,1.f,9000},
 {4.2f,1.5f,.7f,1.f,5000},{.5f,.7f,-.4f,.8f,18000},{.5f,.8f,.6f,.9f,10000},
 {.8f,1.2f,-.4f,.8f,18000},{1.2f,1.2f,.6f,.9f,10000},{1.8f,2.2f,-.4f,.9f,16000},
 {1.9f,2.2f,.6f,.9f,9000},{.8f,1.2f,-.4f,.8f,18000},{1.2f,1.2f,.6f,.9f,10000},
 {1.8f,1.f,1.f,1.f,16000},{1.9f,1.f,1.f,1.f,9000},{12.f,1.f,.1f,1.f,10000},
 {30.f,1.f,.1f,1.f,9000}};
Settings defaults(int base=15){const auto& b=bases[base];return {1,0,base,0,int(std::lround(b.rt*100)),int(std::lround(b.early*100)),0,0,0,0,0,0,0,0,0,int(std::lround(b.earlyWidth*1000)),int(std::lround(b.width*1000)),0,0,0,int(b.damp),0,0,0};}
bool visibleBase(int b){return b==0||b==1||b==2||b==4||b==5||b==7||b==8||b==9||b==11||b==15||b==16||b==17||b==18;}
float clean(float v){return std::isfinite(v)?v:0.f;}
int db(double v){if(!(v>0))return INT32_MIN;return std::clamp(int(std::lround(2000*std::log10(v))),-20000,20000);}
#ifdef DSP_FUSION_TEST_ALLOCATIONS
int failAllocation=-1;
#endif
struct OnePole {
 double previous=0,state=0;
 double low(double x,double pole){const double y=(1-pole)*.5*(x+previous)+pole*state;previous=x;state=y;return y;}
 double high(double x,double pole){return x-low(x,pole);}
};
struct Coefficients {
 float loop,ap1,ap2,bass1,bass2,bass3,early1,early2,wet1,wet2,a2,b1,b2;
 static Coefficients get(const sf_reverb_state_st& s){return {s.loopdecay,s.dampap1L.decay,s.dampap2L.decay,s.cbassap1L.decay1,s.cbassap1L.decay2,s.cbassap2L.decay2,s.earlyref.wet1,s.earlyref.wet2,s.wet1,s.wet2,s.damplpL.a2,s.damplpL.b1,s.damplpL.b2};}
 void put(sf_reverb_state_st& s)const{
  s.loopdecay=loop;s.dampap1L.decay=s.dampap1R.decay=ap1;s.dampap2L.decay=s.dampap2R.decay=ap2;
  s.cbassap1L.decay1=s.cbassap1R.decay1=bass1;s.cbassap1L.decay2=s.cbassap1R.decay2=bass2;
  s.cbassap2L.decay1=s.cbassap2R.decay1=bass1;s.cbassap2L.decay2=s.cbassap2R.decay2=bass3;
  s.cbassap2L.decay3=s.cbassap2R.decay3=bass2;s.earlyref.wet1=early1;s.earlyref.wet2=early2;s.wet1=wet1;s.wet2=wet2;
  s.damplpL.a2=s.damplpR.a2=a2;s.damplpL.b1=s.damplpR.b1=b1;s.damplpL.b2=s.damplpR.b2=b2;
 }
 void approach(const Coefficients& t,float a){
  // Explicit fields avoid treating a struct as an array across padding/object boundaries.
#define MOVE(x) {const float previous=x;x+=(t.x-x)*a;if(x==previous||std::abs(t.x-x)<1e-7f)x=t.x;}
  MOVE(loop);MOVE(ap1);MOVE(ap2);MOVE(bass1);MOVE(bass2);MOVE(bass3);MOVE(early1);MOVE(early2);MOVE(wet1);MOVE(wet2);MOVE(a2);MOVE(b1);MOVE(b2);
#undef MOVE
 }
};
struct Model {
 sf_reverb_state_st state{};
 sf_room_split_st split{};
 Settings settings{};
 int rate=48000;
 bool legacy=true,neutral=true,bassLimited=false,timeSettling=false;
 float originalBass=0;
 Coefficients coeff{},targetCoeff{},originalCoeff{};
 std::array<std::array<float,delayCapacity>,4> delay{};
 int position=0;
 double alpha=.002,delayFrames=0,targetDelay=0;
 std::array<double,3> gains{1,1,1},targetGains{1,1,1};
 double linked=0,targetLinked=0,mix=0,targetMix=0,output=1,targetOutput=1;
 double hpPole=.5,lpPole=.5,targetHpPole=.5,targetLpPole=.5,hpBlend=0,lpBlend=0,targetHpBlend=0,targetLpBlend=0;
 std::array<std::array<OnePole,2>,4> hp{},lp{};
 void init(const Settings& s,int fs){rate=fs;settings=s;sf_presetreverb(&state,fs,sf_reverb_preset(s[2]));sf_room_split_init(&split,&state,fs);originalBass=state.bassb;alpha=1-std::exp(-1./(.01*fs));coeff=targetCoeff=originalCoeff=Coefficients::get(state);retune(s,true);}
 void retune(const Settings& s,bool reset=false){
  const bool wasTime=settings[1]&&settings[3],wasNeutral=neutral;
  const auto& b=bases[s[2]];settings=s;legacy=!s[1];
  timeSettling=(s[1]&&s[3])||wasTime||timeSettling;
  neutral=legacy||(!s[3]&&!s[6]&&!s[8]&&!s[12]&&!s[14]&&!s[17]&&!s[21]);
  const bool pro=s[1];
  const float rt=pro&&s[3]?s[4]/100.f:b.rt;
  const float ew=pro&&s[14]?s[15]/1000.f:b.earlyWidth,lw=pro&&s[14]?s[16]/1000.f:b.width;
  const float damp=pro&&s[17]?std::min(s[20],int(rate*.45)):b.damp;
  // Each disabled group retains its immutable original coefficients exactly.
  const auto old=Coefficients::get(state);
  sf_room_retune(&state,rt,ew,lw,rate,damp);
  const auto tuned=Coefficients::get(state);
  targetCoeff=originalCoeff;
  if(pro&&s[3]){targetCoeff.loop=tuned.loop;targetCoeff.ap1=tuned.ap1;targetCoeff.ap2=tuned.ap2;
   targetCoeff.bass1=tuned.bass1;targetCoeff.bass2=tuned.bass2;targetCoeff.bass3=tuned.bass3;}
  if(pro&&s[14]){targetCoeff.early1=tuned.early1;targetCoeff.early2=tuned.early2;targetCoeff.wet1=tuned.wet1;targetCoeff.wet2=tuned.wet2;}
  if(pro&&s[17]){targetCoeff.a2=tuned.a2;targetCoeff.b1=tuned.b1;targetCoeff.b2=tuned.b2;}
  if(!reset)old.put(state);else{coeff=targetCoeff;coeff.put(state);}
  split.targetEarlyRatio=pro&&s[3]?s[5]/(100.f*b.early):1.f;
  targetDelay=pro&&s[6]?s[7]*rate/10000.:0;
  for(unsigned i=0;i<3;++i)targetGains[i]=pro&&s[8]?std::pow(10.,s[9+i]/200.):1;
  targetLinked=pro&&s[12]?1:0;targetMix=s[13]/1000.;targetOutput=pro&&s[21]?std::pow(10.,s[22]/200.):1;
  const double hc=std::min(double(s[18]),rate*.45),lc=std::min(double(s[19]),rate*.45);
  targetHpBlend=pro&&s[17]&&s[18]>0?1:0;targetLpBlend=pro&&s[17]&&s[19]>0?1:0;
  auto pole=[this](double hz){const double k=std::tan(pi*std::clamp(hz,1.,rate*.45)/rate);return (1-k)/(1+k);};
  targetHpPole=pole(hc);targetLpPole=pole(lc);
  if(reset){gains=targetGains;linked=targetLinked;mix=targetMix;output=targetOutput;delayFrames=targetDelay;
   hpPole=targetHpPole;lpPole=targetLpPole;hpBlend=targetHpBlend;lpBlend=targetLpBlend;split.earlyRatio=split.targetEarlyRatio;}
  if(neutral&&!legacy&&!wasNeutral){
   // The exact legacy fast path has no separated wet samples. Retire optional wet
   // history on the control thread so later unrelated groups cannot revive frozen audio.
   for(auto& channel:delay)channel.fill(0);position=0;hp={};lp={};
   gains=targetGains;linked=targetLinked;mix=targetMix;output=targetOutput;delayFrames=targetDelay;
   hpPole=targetHpPole;lpPole=targetLpPole;hpBlend=targetHpBlend;lpBlend=targetLpBlend;
  }
  if(reset)updateBass();
 }
 void updateBass(){
  const bool restored=coeff.loop==targetCoeff.loop&&coeff.ap1==targetCoeff.ap1&&coeff.ap2==targetCoeff.ap2&&coeff.bass1==targetCoeff.bass1&&coeff.bass2==targetCoeff.bass2&&coeff.bass3==targetCoeff.bass3;
  if(!(settings[1]&&settings[3])&&restored)timeSettling=false;
  state.bassb=originalBass;
  if(timeSettling)state.bassb=std::min(originalBass,std::max(0.f,.995f/state.loopdecay-1.f));
  bassLimited=state.bassb+1e-7f<originalBass;
 }
 float delayed(unsigned c,float x){
  delay[c][position]=x;
  const int whole=int(delayFrames);const double frac=delayFrames-whole;
  int a=position-whole;if(a<0)a+=delayCapacity;int b=a-1;if(b<0)b+=delayCapacity;
  return float(delay[c][a]+frac*(delay[c][b]-delay[c][a]));
 }
 float filter(unsigned c,float x){
  const double h1=hp[c][0].high(x,hpPole),h2=hp[c][1].high(h1,hpPole);
  double y=x+hpBlend*(h2-x);
  const double l1=lp[c][0].low(y,lpPole),l2=lp[c][1].low(l1,lpPole);
  return float(y+lpBlend*(l2-y));
 }
 void sample(float l,float r,float* result,bool partsNeeded=false){
  if(legacy){sf_reverb_process(&state,l,r,result,result+1);result[2]=result[3]=result[4]=result[5]=0;result[6]=result[0];result[7]=result[1];return;}
  coeff.approach(targetCoeff,float(alpha));coeff.put(state);
  updateBass();
  if(neutral&&!partsNeeded){sf_reverb_process(&state,l,r,result,result+1);sf_room_track_original(&split,l,r);
   result[2]=result[3]=result[4]=result[5]=result[6]=result[7]=0;return;}
  float parts[8];sf_room_process_parts(&state,&split,l,r,parts);
  for(unsigned i=0;i<3;++i)gains[i]+=(targetGains[i]-gains[i])*alpha;
  linked+=(targetLinked-linked)*alpha;mix+=(targetMix-mix)*alpha;output+=(targetOutput-output)*alpha;
  delayFrames+=(targetDelay-delayFrames)*alpha;hpPole+=(targetHpPole-hpPole)*alpha;lpPole+=(targetLpPole-lpPole)*alpha;
  hpBlend+=(targetHpBlend-hpBlend)*alpha;lpBlend+=(targetLpBlend-lpBlend)*alpha;
  std::array<float,4> wet;
  for(unsigned c=0;c<4;++c)wet[c]=filter(c,delayed(c,parts[c+2]));position=(position+1)%delayCapacity;
  for(unsigned c=0;c<2;++c){
   const double early=wet[c],late=wet[c+2],dry=parts[c];
   const double independent=dry*gains[0]+early*gains[1]+late*gains[2];
   const double original=c?r:l;
   const double standard=original*(1-mix)+(early+late)*mix;
   result[c]=neutral?parts[c+6]:clean(float((linked==1?standard:independent+linked*(standard-independent))*output));
   const double auditionEarly=early*((1-linked)*gains[1]+linked*mix);
   const double auditionLate=late*((1-linked)*gains[2]+linked*mix);
   result[c+2]=clean(float(auditionEarly*output));result[c+4]=clean(float(auditionLate*output));result[c+6]=clean(float((auditionEarly+auditionLate)*output));
  }
 }
};
std::unique_ptr<Model> prepare(const Settings& s,int rate){
#ifdef DSP_FUSION_TEST_ALLOCATIONS
 if(failAllocation>=0){if(failAllocation==0){failAllocation=-1;return {};}--failAllocation;}
#endif
 auto model=std::unique_ptr<Model>(new(std::nothrow) Model);if(model)model->init(s,rate);return model;
}
struct Engine {
 Settings settings=defaults();
 std::unique_ptr<Model> main,old,pending,a,b;
 bool pendingReady=false;
 int rate=48000,mode=0,stage=0,comparisonEpoch=0,epoch=2;
 bool module=false,ready=false,match=false,processed=false;
 unsigned fade=0,fadeLength=2400;
 uint64_t frames=0,warmup=0,measuring=0;
 double energyA=0,energyB=0,energyDry=0,gainA=1,gainB=1,gainDry=1;
 std::array<double,2> peaks{},squares{};int clips=0;
 void bump(){epoch=epoch>=INT32_MAX-2?2:epoch+2;}
 void clear(){mode=0;stage=0;match=false;comparisonEpoch=0;frames=warmup=measuring=0;energyA=energyB=energyDry=0;gainA=gainB=gainDry=1;peaks={};squares={};clips=0;bump();}
 void settleControl(){
  if(pendingReady){main=std::move(pending);pendingReady=false;}
  old.reset();fade=0;processed=false;
 }
 bool set(const Settings& s,int fs){
  if(main&&ready&&s==settings&&rate==fs)return true;
  if(pendingReady&&ready&&rate==fs&&s[2]==settings[2]&&s[1]==settings[1])pending->retune(s);
  else if(!main||!ready||rate!=fs||s[2]!=settings[2]||s[1]!=settings[1]){
   auto next=prepare(s,fs);if(!next)return false;
   if(main&&ready&&module&&processed&&rate==fs&&fade){pending=std::move(next);pendingReady=true;}
   else {
    pending.reset();pendingReady=false;
    if(main&&ready&&module&&processed&&rate==fs){old=std::move(main);fadeLength=unsigned(fs*.05);fade=fadeLength;}else{old.reset();fade=0;}
    main=std::move(next);
   }
  }else main->retune(s);
  rate=fs;settings=s;ready=true;clear();return true;
 }
 bool tools(const int32_t* v){
  if(!module||!settings[1]){clear();return v[0]==0;}
  bool snapshotsChanged=false;
  if(v[0]>=5){
   Settings sa,sb;std::copy_n(v+4,24,sa.begin());std::copy_n(v+28,24,sb.begin());
   if(!a||!b||comparisonEpoch!=v[2]||a->settings!=sa||b->settings!=sb||a->rate!=rate||b->rate!=rate){
    auto na=prepare(sa,rate),nb=prepare(sb,rate);if(!na||!nb)return false;
    snapshotsChanged=true;a=std::move(na);b=std::move(nb);stage=0;gainA=gainB=gainDry=1;frames=measuring=0;energyA=energyB=energyDry=0;
   }
  }
  if(!snapshotsChanged && mode==v[0] && comparisonEpoch==v[2] && match==(v[1]!=0))return true;
  comparisonEpoch=v[2];mode=v[0];match=v[1]!=0;
  if(mode==0){clear();return true;}
  if(mode==7){
   if(!a||!b)return false;
   stage=1;frames=measuring=0;energyA=energyB=energyDry=0;gainA=gainB=gainDry=1;
   auto rt=[](const Settings& s){return s[1]&&s[3]?s[4]/100.:bases[s[2]].rt;};
   const double seconds=std::max(rt(a->settings),rt(b->settings))+.5+std::max(a->targetDelay,b->targetDelay)/rate;
   warmup=uint64_t(std::clamp(seconds,1.,31.)*rate);
  }
  bump();return true;
 }
 void refresh(int fs){
  if(rate==fs&&ready)return;
  auto next=prepare(settings,fs);if(!next){ready=false;clear();return;}
  main=std::move(next);old.reset();pending.reset();pendingReady=false;fade=0;rate=fs;ready=true;clear();
 }
 void process(const float* l,const float* r,float* outL,float* outR,size_t n){
  if(!module||!ready||!main){if(outL!=l)std::memmove(outL,l,n*sizeof(float));if(outR!=r)std::memmove(outR,r,n*sizeof(float));return;}
  const double meterAlpha=1-std::exp(-1./(.1*rate)),peakDecay=std::exp(-1./(.6*rate));
  for(size_t i=0;i<n;++i){
   const float inputL=clean(l[i]),inputR=clean(r[i]);float normal[8],previous[8],result[2],ra[8],rb[8];
   // Complete the audible transition before adopting the latest queued room.
   // Rotate owned pointers without deleting any model on the audio thread.
   if(!fade&&pendingReady){old.swap(main);main.swap(pending);pendingReady=false;fade=fadeLength;}
   processed=true;main->sample(inputL,inputR,normal,mode>=2&&mode<=4);
   result[0]=normal[0];result[1]=normal[1];
   if(fade&&old){old->sample(inputL,inputR,previous);const float blend=1-float(fade)/fadeLength;
    for(unsigned c=0;c<2;++c)result[c]=previous[c]+blend*(normal[c]-previous[c]);--fade;
    // The retired model is reclaimed on the next control command, never on this callback.
   }
   if(settings[1]){
    if(mode>=5&&a&&b){a->sample(inputL,inputR,ra);b->sample(inputL,inputR,rb);}
    if(mode==7&&a&&b){
     if(stage==1&&++frames>=warmup){stage=2;measuring=0;}
     else if(stage==2){
      energyA+=double(ra[0])*ra[0]+double(ra[1])*ra[1];energyB+=double(rb[0])*rb[0]+double(rb[1])*rb[1];
      energyDry+=double(inputL)*inputL+double(inputR)*inputR;++measuring;
      if(measuring>=uint64_t(rate)*2){
       if(energyDry/(measuring*2)<1e-8||energyA/(measuring*2)<1e-12||energyB/(measuring*2)<1e-12){stage=4;gainA=gainB=gainDry=1;}
       else {const double target=std::min({energyDry,energyA,energyB});gainA=std::sqrt(target/energyA);gainB=std::sqrt(target/energyB);gainDry=std::sqrt(target/energyDry);stage=3;}
      }
     }
    }
    if(mode==1){result[0]=inputL;result[1]=inputR;if(match&&stage==3){result[0]*=gainDry;result[1]*=gainDry;}}
    else if(mode==2||mode==3||mode==4){const unsigned offset=mode==2?2:mode==3?4:6;result[0]=normal[offset];result[1]=normal[offset+1];}
    else if(mode==5&&a){result[0]=ra[0];result[1]=ra[1];if(match&&stage==3){result[0]*=gainA;result[1]*=gainA;}}
    else if(mode==6&&b){result[0]=rb[0];result[1]=rb[1];if(match&&stage==3){result[0]*=gainB;result[1]*=gainB;}}
   }
   outL[i]=clean(result[0]);outR[i]=clean(result[1]);
   if(settings[1]&&settings[23]){for(unsigned c=0;c<2;++c){const double value=c?outR[i]:outL[i];peaks[c]=std::max(std::abs(value),peaks[c]*peakDecay);squares[c]+=(value*value-squares[c])*meterAlpha;}
    if((std::abs(outL[i])>1||std::abs(outR[i])>1)&&clips<INT32_MAX)++clips;}
  }
  bump();
 }
};
}
extern "C" {
int RoomReverbValidate(const int32_t* v,size_t n){
 if(!v||n!=24||!visibleBase(v[2]))return 0;
 constexpr int lo[]={1,0,0,0,10,50,0,0,0,-240,-240,-240,0,0,0,-1000,0,0,0,0,100,0,-120,0};
 constexpr int hi[]={1,1,18,1,3000,250,1,2000,1,120,120,120,1,1000,1,1000,1000,1,1000,20000,20000,1,0,1};
 for(unsigned i=0;i<24;++i)if(v[i]<lo[i]||v[i]>hi[i])return 0;
 if(v[18]>0&&v[19]>0&&v[18]>=v[19])return 0;return 1;
}
int RoomReverbToolsValidate(const int32_t* v,size_t n){return v&&n==52&&v[0]>=0&&v[0]<=7&&v[1]>=0&&v[1]<=1&&v[2]>=0&&v[3]==0&&RoomReverbValidate(v+4,24)&&RoomReverbValidate(v+28,24)&&(v[0]<5||v[2]>0);}
void* RoomReverbCreate(){return new(std::nothrow) Engine;}
void RoomReverbDestroy(void* p){delete static_cast<Engine*>(p);}
int RoomReverbSet(void* p,void* dsp,const int32_t* v,size_t n){
 if(!p||!dsp||!RoomReverbValidate(v,n))return 0;Settings s;std::copy_n(v,24,s.begin());
 return static_cast<Engine*>(p)->set(s,int(static_cast<JamesDSPLib*>(dsp)->fs));
}
int RoomReverbTools(void* p,void* dsp,const int32_t* v,size_t n){if(!p||!dsp||!RoomReverbToolsValidate(v,n))return 0;return static_cast<Engine*>(p)->tools(v);}
void RoomReverbClear(void* p){if(p){auto& e=*static_cast<Engine*>(p);e.settleControl();e.clear();}}
void RoomReverbRefresh(void* p,void* dsp){if(p&&dsp)static_cast<Engine*>(p)->refresh(int(static_cast<JamesDSPLib*>(dsp)->fs));}
int RoomReverbBase(void* p,void* dsp,int base){
 if(!p||!dsp||base<0||base>18)return 0;auto& e=*static_cast<Engine*>(p);
 if(e.settings[1])return 1;auto s=defaults(base);return e.set(s,int(static_cast<JamesDSPLib*>(dsp)->fs));
}
void RoomReverbEnabled(void* p,int enabled){if(!p)return;auto& e=*static_cast<Engine*>(p);if(!enabled){e.settleControl();e.clear();}e.module=enabled!=0;e.bump();}
void RoomReverbProcess(void* p,const float* l,const float* r,float* outL,float* outR,size_t n){if(p)static_cast<Engine*>(p)->process(l,r,outL,outR,n);}
void RoomReverbStatus(const void* p,int32_t* v){
 std::fill_n(v,32,0);v[0]=1;v[2]=48000;v[22]=15;v[23]=16000;v[25]=2000;for(unsigned i=16;i<20;++i)v[i]=INT32_MIN;if(!p)return;
 const auto& e=*static_cast<const Engine*>(p);const auto& s=e.settings;
 v[1]=e.epoch;v[2]=e.rate;v[3]=e.module;v[4]=s[1];v[5]=e.mode;v[6]=e.match;v[7]=e.stage;
 v[8]=int(e.measuring*1000/e.rate);v[9]=db(e.gainA);v[10]=db(e.gainB);v[11]=e.comparisonEpoch;v[12]=s[1]&&s[23];
 v[13]=s[1]&&s[6]?s[7]:0;v[14]=s[1]&&s[17]?std::min(s[18],int(e.rate*.45)):0;v[15]=s[1]&&s[17]?std::min(s[19],int(e.rate*.45)):0;
 if(e.module&&e.ready&&s[1]&&s[23]){v[16]=db(e.peaks[0]);v[17]=db(e.peaks[1]);v[18]=db(std::sqrt(e.squares[0]));v[19]=db(std::sqrt(e.squares[1]));}
 v[20]=e.clips;v[21]=e.ready;v[22]=s[2];v[23]=int(s[1]&&s[17]?std::min(s[20],int(e.rate*.45)):bases[s[2]].damp);
 v[24]=int(e.warmup*1000/e.rate);v[25]=2000;
 if(e.main){v[26]=e.main->bassLimited;v[27]=int(std::lround(e.main->state.bassb*1000000));}
}
#ifdef DSP_FUSION_TEST_ALLOCATIONS
void RoomReverbFailNextAllocationForTest(){failAllocation=0;}
void RoomReverbAllocationFailureForTest(int after){failAllocation=after;}
int RoomReverbQueuedForTest(void* p){if(!p)return 0;auto& e=*static_cast<Engine*>(p);return e.fade!=0||e.pendingReady;}
void RoomReverbSetNoiseForTest(void* p,const void* noise){if(p&&noise){auto& e=*static_cast<Engine*>(p);if(e.main)std::memcpy(&e.main->state.noise,noise,sizeof(sf_rv_noise_st));}}
void RoomReverbCopyNoiseForTest(void* p,void* noise){if(p&&noise){auto& e=*static_cast<Engine*>(p);if(e.main)std::memcpy(noise,&e.main->state.noise,sizeof(sf_rv_noise_st));}}
void RoomReverbCopyStateForTest(void* p,void* state){if(p&&state){auto& e=*static_cast<Engine*>(p);if(e.main)std::memcpy(state,&e.main->state,sizeof(sf_reverb_state_st));}}

#endif
}
