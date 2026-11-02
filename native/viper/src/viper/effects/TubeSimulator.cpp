#include "TubeSimulator.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <new>
namespace tubetuning {
bool valid(const int32_t* v,size_t n){
 if(!v||n!=kWords)return false;
 for(unsigned i=0;i<kWords;++i)if(v[i]<minimum[i]||v[i]>maximum[i])return false;
 if(v[16]!=2&&v[16]!=4)return false;
 if((v[8]!=0&&v[8]<20)||(v[9]!=0&&v[9]<20)||(v[10]!=0&&v[10]<1000))return false;
 return true;
}
bool validTools(const int32_t* v,size_t n){
 return v&&n==kToolsWords&&v[0]>=0&&v[0]<=6&&v[1]>=0&&v[1]<=1&&v[2]>=0&&v[3]==0&&
  valid(v+4,kWords)&&valid(v+24,kWords)&&(v[0]<4||v[2]>0);
}
#ifdef DSP_FUSION_TEST_ALLOCATIONS
int allocationFailure=-1;
void failAllocationForTest(int after){allocationFailure=after;}
#endif
}
namespace {
using Settings=tubetuning::Settings;
constexpr double pi=3.14159265358979323846;
constexpr unsigned ringSize=128,maxFir=125,latency=31;
double cleanSample(double x){return std::isfinite(x)?x:0.;}
float sampleFloat(double x){return std::isfinite(x)&&std::abs(x)<=std::numeric_limits<float>::max()?float(x):0.f;}
int32_t db(double x){return x>0?int32_t(std::clamp(std::lround(2000.*std::log10(x)),-20000l,20000l)):INT32_MIN;}
int effectiveFactor(const Settings& s){return s[1]&&s[2]&&s[15]?s[16]:1;}
bool neutral(const Settings& s){return !s[1]||(!s[2]&&!s[6]&&!s[11]&&!s[13]);}
Settings effectiveSettings(const Settings& s){
 if(neutral(s))return tubetuning::defaults;
 auto p=s;p[17]=0;
 if(!p[2]){p[3]=0;p[4]=500;p[5]=0;p[9]=0;p[15]=0;p[16]=2;}
 if(!p[6]){p[7]=1000;p[8]=p[9]=p[10]=0;}
 if(!p[11])p[12]=1000;
 if(!p[13])p[14]=0;
 if(!p[15])p[16]=2;
 return p;
}
bool audioEqual(const Settings& a,const Settings& b){return effectiveSettings(a)==effectiveSettings(b);}
struct StereoRing {
 std::array<std::array<double,ringSize>,2> data{};unsigned position=0;
 void push(double l,double r){data[0][position]=l;data[1][position]=r;}
 double read(unsigned c,unsigned delay)const{return data[c][(position+ringSize-std::min(delay,ringSize-1))%ringSize];}
 void advance(){position=(position+1)%ringSize;}
 void clear(){data={};position=0;}
};
struct OnePole {
 double previous=0,state=0;
 double low(double x,double pole){const double y=(1-pole)*.5*(x+previous)+pole*state;previous=x;state=y;return y;}
 double high(double x,double pole){return x-low(x,pole);}
};
struct DcBlock {
 double previous=0,state=0;
 double process(double x,double pole){state=x-previous+pole*state;previous=x;return state;}
};
struct Fir {
 unsigned factor=1,length=1,position=0;
 std::array<double,maxFir> taps{};
 std::array<std::array<double,32>,2> input{};unsigned inputPosition=0;
 std::array<std::array<double,maxFir>,2> output{};
 void configure(unsigned f){
  factor=f;length=f==1?1:31*f+1;position=inputPosition=0;input={};output={};taps={};
  if(f==1){taps[0]=1;return;}
  const double center=(length-1)*.5,cutoff=.45/f;double sum=0;
  for(unsigned i=0;i<length;++i){const double t=double(i)-center;
   const double sinc=t==0?2*cutoff:std::sin(2*pi*cutoff*t)/(pi*t);
   const double w=.42-.5*std::cos(2*pi*i/(length-1))+.08*std::cos(4*pi*i/(length-1));taps[i]=sinc*w;sum+=taps[i];}
  for(unsigned i=0;i<length;++i)taps[i]/=sum;
 }
 void inputFrame(double l,double r){input[0][inputPosition]=l;input[1][inputPosition]=r;}
 double up(unsigned c,unsigned phase)const{
  double sum=0;unsigned age=0;
  for(unsigned j=phase;j<length;j+=factor,++age)sum+=taps[j]*input[c][(inputPosition+32-age)%32];
  return sum*factor;
 }
 void put(double l,double r){output[0][position]=l;output[1][position]=r;}
 double down(unsigned c)const{double sum=0;for(unsigned j=0;j<length;++j)sum+=taps[j]*output[c][(position+length-j)%length];return sum;}
 void advance(){position=(position+1)%length;}
 void nextInput(){inputPosition=(inputPosition+1)%32;}
};
struct Frame {std::array<double,2> normal{},effect{},original{};};
struct Kernel {
 Settings settings=tubetuning::defaults;
 uint32_t rate=48000;unsigned factor=1,delay=0;
 std::array<double,2> oldAcc{},toneAcc{};
 StereoRing baseRing,rawRing;
 Fir fir;
 std::array<OnePole,2> hp{},post{};std::array<DcBlock,2> dc{};
 double alpha=.002,sat=0,targetSat=0,gain=1,targetGain=1,softness=.5,targetSoftness=.5,bias=0,targetBias=0;
 double softMix=1,targetSoftMix=1,pole=.5,targetPole=.5,hpPole=.9,targetHpPole=.9,postPole=.5,targetPostPole=.5;
 double hpBlend=0,targetHpBlend=0,postBlend=0,targetPostBlend=0,mix=1,targetMix=1,out=1,targetOut=1,dcPole=.999;
 void reset(){oldAcc={};toneAcc={};baseRing.clear();rawRing.clear();hp={};post={};dc={};fir.configure(factor);retune(settings,true);}
 void configure(const Settings& s,uint32_t fs){rate=fs;factor=unsigned(effectiveFactor(s));delay=factor==1?0:latency;alpha=1-std::exp(-1./(.01*fs));fir.configure(factor);retune(s,true);}
 void inherit(const Kernel& old){oldAcc=old.oldAcc;toneAcc=old.toneAcc;baseRing=old.baseRing;rawRing=old.rawRing;}
 double lowPole(int hz,unsigned fs)const{const double k=std::tan(pi*std::clamp(double(hz),1.,fs*.45)/fs);return (1-k)/(1+k);}
 void retune(const Settings& s,bool immediate=false){
  const bool wasNeutral=neutral(settings);settings=s;
  const bool pro=s[1];targetSat=pro&&s[2]?1:0;targetGain=std::pow(10.,s[3]/200.);targetSoftness=s[4]/1000.;targetBias=s[5]/2000.;
  targetSoftMix=pro&&s[6]?s[7]/1000.:1;
  if(pro&&s[6]&&s[8]>0){const double c=2-std::cos(2*pi*std::min(double(s[8]),rate*.45)/rate);targetPole=c-std::sqrt(c*c-1);}
  else targetPole=.5;
  targetHpBlend=pro&&s[2]&&s[6]&&s[9]>0?1:0;targetHpPole=lowPole(s[9],rate*factor);
  targetPostBlend=pro&&s[6]&&s[10]>0?1:0;targetPostPole=lowPole(s[10],rate);
  targetMix=pro&&s[11]?s[12]/1000.:1;targetOut=pro&&s[13]?std::pow(10.,s[14]/200.):1;
  dcPole=std::exp(-2*pi*5/(rate*factor));
  if(immediate||(!wasNeutral&&neutral(s))){sat=targetSat;gain=targetGain;softness=targetSoftness;bias=targetBias;softMix=targetSoftMix;pole=targetPole;
   hpPole=targetHpPole;postPole=targetPostPole;hpBlend=targetHpBlend;postBlend=targetPostBlend;mix=targetMix;out=targetOut;
   if(!immediate){hp={};post={};dc={};fir.configure(factor);toneAcc=oldAcc;}}
 }
 void approach(){
#define MOVE(x) {const double previous=x;x+=(target##x-x)*alpha;if(x==previous||std::abs(target##x-x)<1e-12)x=target##x;}
  // Explicit spelling keeps target member names readable and avoids dependence on layout.
#undef MOVE
  auto step=[this](double& x,double t){const double previous=x;x+=(t-x)*alpha;if(x==previous||std::abs(t-x)<1e-12)x=t;};
  step(sat,targetSat);step(gain,targetGain);step(softness,targetSoftness);step(bias,targetBias);step(softMix,targetSoftMix);step(pole,targetPole);
  step(hpPole,targetHpPole);step(postPole,targetPostPole);step(hpBlend,targetHpBlend);step(postBlend,targetPostBlend);step(mix,targetMix);step(out,targetOut);
 }
 double shape(double x,double bTanh,double invSoftNorm)const{
  const double hard=(std::clamp(gain*x+bias,-1.,1.)-bias)/gain;
  if(softness==0)return hard;
  const double soft=(std::tanh(gain*x+bias)-bTanh)*invSoftNorm;
  return hard+softness*(soft-hard);
 }
 Frame process(double l,double r){
  const std::array<double,2> input{cleanSample(l),cleanSample(r)};Frame f;
  // This literal legacy arithmetic is the reference path, including meter-only operation.
  for(unsigned c=0;c<2;++c)oldAcc[c]=(oldAcc[c]+input[c])/2.;
  if(neutral(settings)){
   toneAcc=oldAcc;for(unsigned c=0;c<2;++c)f.normal[c]=f.effect[c]=oldAcc[c];f.original=input;
   baseRing.push(oldAcc[0],oldAcc[1]);baseRing.advance();rawRing.push(input[0],input[1]);rawRing.advance();return f;
  }
  approach();std::array<double,2> base{},residual{};
  for(unsigned c=0;c<2;++c){
   if(pole==.5&&targetPole==.5)toneAcc[c]=oldAcc[c];else toneAcc[c]=(1-pole)*input[c]+pole*toneAcc[c];
   base[c]=input[c]+softMix*(toneAcc[c]-input[c]);
  }
  baseRing.push(base[0],base[1]);rawRing.push(input[0],input[1]);
  const double bTanh=bias==0?0:std::tanh(bias),invSoftNorm=1/(gain*(1-bTanh*bTanh));
  auto colour=[&](unsigned c,double x){const double high=hp[c].high(x,hpPole),feed=x+hpBlend*(high-x);
   return dc[c].process(shape(feed,bTanh,invSoftNorm)-feed,dcPole)*sat;};
  if(factor==1){for(unsigned c=0;c<2;++c)residual[c]=colour(c,base[c]);}
  else {
   fir.inputFrame(base[0],base[1]);
   for(unsigned phase=0;phase<factor;++phase){fir.put(colour(0,fir.up(0,phase)),colour(1,fir.up(1,phase)));
    if(phase==0){residual[0]=fir.down(0);residual[1]=fir.down(1);}fir.advance();}
   fir.nextInput();
  }
  for(unsigned c=0;c<2;++c){const double coloured=baseRing.read(c,delay)+residual[c];
   const double low=post[c].low(coloured,postPole),wet=coloured+postBlend*(low-coloured),original=rawRing.read(c,delay);
   f.original[c]=original;f.effect[c]=cleanSample(wet*out);f.normal[c]=cleanSample((mix==0?original:mix==1?wet:original+mix*(wet-original))*out);}
  baseRing.advance();rawRing.advance();return f;
 }
};
std::unique_ptr<Kernel> prepare(const Settings& s,uint32_t rate){
#ifdef DSP_FUSION_TEST_ALLOCATIONS
 if(tubetuning::allocationFailure>=0){if(tubetuning::allocationFailure==0){tubetuning::allocationFailure=-1;return {};}--tubetuning::allocationFailure;}
#endif
 auto k=std::unique_ptr<Kernel>(new(std::nothrow) Kernel);if(k)k->configure(s,rate);return k;
}
struct Alignment {
 StereoRing normal,effect,original;
 Frame process(const Frame& frame,unsigned extra){
  normal.push(frame.normal[0],frame.normal[1]);effect.push(frame.effect[0],frame.effect[1]);original.push(frame.original[0],frame.original[1]);Frame out;
  for(unsigned c=0;c<2;++c){out.normal[c]=normal.read(c,extra);out.effect[c]=effect.read(c,extra);out.original[c]=original.read(c,extra);}
  normal.advance();effect.advance();original.advance();return out;
 }
};
}
struct TubeSimulator::Engine {
 Settings settings=tubetuning::defaults;
 uint32_t rate=48000;bool enabled=false,ready=false,processed=false,pendingReady=false;
 std::unique_ptr<Kernel> normal,old,pending,a,b;
 Alignment normalAlign,oldAlign,aAlign,bAlign;
 StereoRing rawHistory;
 unsigned fade=0,fadeLength=960,routeFade=0,routeLength=240,lastCommon=0;
 std::array<double,2> last{},routeFrom{};
 int mode=0,epoch=0,stage=0,revision=2;bool match=false;
 uint64_t warmFrames=0,warmRequired=24000,measureFrames=0;
 double energyIn=0,energyA=0,energyB=0,gainA=1,gainB=1,gainOriginal=1;
 std::array<double,4> peaks{},squares{};int32_t clips=0;double peakDecay=std::exp(-1./(.6*48000)),meterAlpha=1-std::exp(-1./(.1*48000));
 void bump(){revision=revision>=INT32_MAX-2?2:revision+2;}
 void clearMeters(){peaks={};squares={};clips=0;}
 void clearInternalTools(){a.reset();b.reset();aAlign={};bAlign={};mode=0;epoch=0;stage=0;match=false;warmFrames=measureFrames=0;energyIn=energyA=energyB=0;gainA=gainB=gainOriginal=1;bump();}
 void routeChange(){if(processed){routeFrom=last;routeFade=routeLength;}}
 void settle(){if(pendingReady){normal=std::move(pending);normalAlign={};pendingReady=false;}old.reset();fade=0;processed=false;routeFade=0;lastCommon=normal?normal->delay:0;}
 void clearControl(){settle();clearInternalTools();clearMeters();}
 void reset(){settle();if(normal)normal->reset();if(a)a->reset();if(b)b->reset();normalAlign={};oldAlign={};aAlign={};bAlign={};rawHistory.clear();last={};clearInternalTools();clearMeters();}
 bool set(const Settings& s){
  const Kernel* candidate=pendingReady?pending.get():normal.get();
  if(ready&&candidate&&settings==s&&candidate->rate==rate&&candidate->factor==unsigned(effectiveFactor(s)))return true;
  const bool changed=!audioEqual(settings,s);
  const bool rebuild=!ready||!candidate||candidate->rate!=rate||candidate->factor!=unsigned(effectiveFactor(s));
  if(rebuild){
   auto next=prepare(s,rate);if(!next)return false;
   if(normal&&normal->rate==rate&&enabled&&processed)next->inherit(*normal);
   if(enabled&&processed&&ready&&normal&&normal->rate==rate){
    if(fade){pending=std::move(next);pendingReady=true;}
    else {old=std::move(normal);oldAlign=normalAlign;normal=std::move(next);normalAlign={};fade=fadeLength;routeChange();}
   }else {normal=std::move(next);old.reset();pending.reset();pendingReady=false;normalAlign={};fade=0;}
  }else if(pendingReady)pending->retune(s,true);else normal->retune(s,!processed||!enabled);
  settings=s;ready=true;
  if(changed){clearInternalTools();routeChange();}
  if(!settings[17])clearMeters();bump();return true;
 }
 void setRate(uint32_t fs){
  if(fs<8000||fs>768000)return;
  if(fs==rate&&ready&&normal&&normal->rate==rate)return;
  rate=fs;fadeLength=std::max(1u,fs/50);routeLength=std::max(1u,fs/200);peakDecay=std::exp(-1./(.6*fs));meterAlpha=1-std::exp(-1./(.1*fs));warmRequired=fs/2;
  auto next=prepare(settings,rate);if(next){normal=std::move(next);ready=true;}else ready=false;
  old.reset();pending.reset();pendingReady=false;fade=routeFade=0;processed=false;normalAlign={};oldAlign={};aAlign={};bAlign={};rawHistory.clear();last={};clearInternalTools();clearMeters();
 }
 bool tools(const int32_t* v){
  if(!enabled||!settings[1]||!ready){clearInternalTools();return v[0]==0;}
  if(v[0]==0){if(mode!=0)routeChange();clearInternalTools();return true;}
  bool snapshotsChanged=false;
  if(v[0]>=4){Settings sa,sb;std::copy_n(v+4,20,sa.begin());std::copy_n(v+24,20,sb.begin());
   if(!a||!b||a->settings!=sa||b->settings!=sb||a->rate!=rate||b->rate!=rate||epoch!=v[2]){
    auto na=prepare(sa,rate),nb=prepare(sb,rate);if(!na||!nb)return false;
    a=std::move(na);b=std::move(nb);aAlign={};bAlign={};snapshotsChanged=true;stage=0;gainA=gainB=gainOriginal=1;warmFrames=measureFrames=0;
   }
  }
  if(!snapshotsChanged&&mode==v[0]&&match==(v[1]!=0)&&epoch==v[2])return true;
  const bool newMeasurement=v[0]==6&&(snapshotsChanged||mode!=6||epoch!=v[2]);
  const bool audibleRoute=mode!=v[0]||epoch!=v[2]||((mode==1||mode==4||mode==5)&&stage==3&&match!=(v[1]!=0));
  mode=v[0];match=v[1]!=0;epoch=v[2];if(audibleRoute)routeChange();
  if(newMeasurement){stage=1;warmFrames=measureFrames=0;energyIn=energyA=energyB=0;gainA=gainB=gainOriginal=1;
   warmRequired=rate/2+std::max({normal->delay,a?a->delay:0u,b?b->delay:0u});}
  bump();return true;
 }
 unsigned commonDelay()const{
  unsigned common=normal?normal->delay:0;
  if(fade&&old)common=std::max(common,old->delay);
  if(pendingReady&&pending)common=std::max(common,pending->delay);
  if(mode&&epoch>0&&a&&b){common=std::max({common,a->delay,b->delay});}
  return common;
 }
 void process(float* buffer,uint32_t frames){
  if(!enabled||!ready||!normal)return;
  for(uint32_t i=0;i<frames;++i){
   const std::array<double,2> input{cleanSample(buffer[2*i]),cleanSample(buffer[2*i+1])};
   if(!fade&&pendingReady){old.swap(normal);normal.swap(pending);oldAlign=normalAlign;normalAlign={};pendingReady=false;fade=fadeLength;routeChange();}
   const unsigned common=commonDelay();if(common!=lastCommon){routeChange();lastCommon=common;}
   rawHistory.push(input[0],input[1]);std::array<double,2> raw{rawHistory.read(0,common),rawHistory.read(1,common)};rawHistory.advance();
   const auto current=normalAlign.process(normal->process(input[0],input[1]),common-normal->delay);std::array<double,2> result=current.normal;
   if(fade&&old){const auto previous=oldAlign.process(old->process(input[0],input[1]),common-old->delay);const double blend=1-double(fade)/fadeLength;
    for(unsigned c=0;c<2;++c)result[c]=previous.normal[c]+blend*(result[c]-previous.normal[c]);--fade;}
   Frame fa{},fb{};
   if(mode&&a&&b&&epoch>0){fa=aAlign.process(a->process(input[0],input[1]),common-a->delay);fb=bAlign.process(b->process(input[0],input[1]),common-b->delay);}
   if(mode==6&&a&&b){
    if(stage==1){if(++warmFrames>=warmRequired){stage=2;measureFrames=0;}}
    else if(stage==2){for(unsigned c=0;c<2;++c){energyIn+=raw[c]*raw[c];energyA+=fa.normal[c]*fa.normal[c];energyB+=fb.normal[c]*fb.normal[c];}++measureFrames;
     if(measureFrames>=uint64_t(rate)*2){
      if(energyIn/(measureFrames*2)<1e-8||energyA/(measureFrames*2)<1e-12||energyB/(measureFrames*2)<1e-12){stage=4;gainA=gainB=gainOriginal=1;}
      else {const double target=std::min({energyIn,energyA,energyB});gainA=std::sqrt(target/energyA);gainB=std::sqrt(target/energyB);gainOriginal=std::sqrt(target/energyIn);stage=3;}
     }
    }
   }
   if(mode==1){result=raw;if(match&&stage==3)for(auto& x:result)x*=gainOriginal;}
   else if(mode==2)result=current.effect;
   else if(mode==3)for(unsigned c=0;c<2;++c)result[c]=current.effect[c]-raw[c];
   else if(mode==4&&a){result=fa.normal;if(match&&stage==3)for(auto& x:result)x*=gainA;}
   else if(mode==5&&b){result=fb.normal;if(match&&stage==3)for(auto& x:result)x*=gainB;}
   if(routeFade){const double blend=1-double(routeFade)/routeLength;for(unsigned c=0;c<2;++c)result[c]=routeFrom[c]+blend*(result[c]-routeFrom[c]);--routeFade;}
   for(unsigned c=0;c<2;++c)buffer[2*i+c]=sampleFloat(result[c]);last={buffer[2*i],buffer[2*i+1]};processed=true;
   if(settings[1]&&settings[17]){for(unsigned c=0;c<2;++c){const double values[2]={input[c],last[c]};for(unsigned p=0;p<2;++p){const unsigned n=p*2+c;peaks[n]=std::max(std::abs(values[p]),peaks[n]*peakDecay);squares[n]+=(values[p]*values[p]-squares[n])*meterAlpha;}}
    if((std::abs(last[0])>1||std::abs(last[1])>1)&&clips<INT32_MAX)++clips;}
  }
  bump();
 }
 void runtime(int32_t* v)const{
  std::fill_n(v,32,0);v[0]=1;v[1]=revision;v[2]=rate;v[3]=enabled;v[4]=settings[1];v[5]=mode;v[6]=match;v[7]=stage;
  v[8]=int(measureFrames*1000/rate);v[9]=gainA>0?std::max(-18000,db(gainA)):0;v[10]=gainB>0?std::max(-18000,db(gainB)):0;v[11]=epoch;v[12]=settings[1]&&settings[17];
  const Kernel* selected=mode==4&&a?a.get():mode==5&&b?b.get():normal.get();
  const auto& s=selected?selected->settings:settings;v[13]=selected?selected->factor:effectiveFactor(s);v[14]=commonDelay();
  v[15]=int(std::lround(s[1]&&s[6]&&s[8]>0?std::min(double(s[8]),rate*.45):std::acos(.75)*rate/(2*pi)));
  for(unsigned i=16;i<24;++i)v[i]=INT32_MIN;
  if(enabled&&ready&&settings[1]&&settings[17])for(unsigned i=0;i<4;++i){v[16+i]=db(peaks[i]);v[20+i]=db(std::sqrt(std::max(0.,squares[i])));}
  v[24]=clips;v[25]=ready;v[26]=s[1]&&s[2]&&s[6]?int(std::min(double(s[9]),rate*.45)):0;
  v[27]=s[1]&&s[6]?int(std::min(double(s[10]),rate*.45)):0;
  v[28]=int(warmFrames*1000/rate);v[29]=int(warmRequired*1000/rate);v[30]=2000;
 }
};
TubeSimulator::TubeSimulator():engine(new(std::nothrow) Engine){if(engine){engine->normal=prepare(tubetuning::defaults,48000);engine->ready=engine->normal!=nullptr;}}
TubeSimulator::~TubeSimulator()=default;
void TubeSimulator::Reset(){if(engine)engine->reset();}
void TubeSimulator::SetEnable(bool enabled){if(!engine)return;if(engine->enabled!=enabled){if(enabled)engine->reset();else engine->clearControl();engine->enabled=enabled;engine->bump();}}
void TubeSimulator::SetSamplingRate(uint32_t rate){if(engine)engine->setRate(rate);}
bool TubeSimulator::SetProfessional(const int32_t* v,size_t n){if(!engine||!tubetuning::valid(v,n))return false;Settings s;std::copy_n(v,20,s.begin());return engine->set(s);}
bool TubeSimulator::SetTools(const int32_t* v,size_t n){return engine&&tubetuning::validTools(v,n)&&engine->tools(v);}
void TubeSimulator::ClearTools(){if(engine)engine->clearControl();}
void TubeSimulator::TubeProcess(float* buffer,uint32_t size){if(engine)engine->process(buffer,size);}
void TubeSimulator::GetRuntime(int32_t* v)const{if(engine)engine->runtime(v);else{std::fill_n(v,32,0);v[0]=1;v[2]=48000;v[13]=1;v[15]=5521;v[29]=500;v[30]=2000;for(unsigned i=16;i<24;++i)v[i]=INT32_MIN;}}
