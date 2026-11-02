#include "AnalogX.h"
#include "../utils/Harmonic.h"
#include "../utils/MultiBiquad.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <new>
namespace analogtuning {
bool valid(const int32_t* v,size_t n){
 if(!v||n!=kWords)return false;
 for(unsigned i=0;i<kWords;++i)if(v[i]<minimum[i]||v[i]>maximum[i])return false;
 if(v[23]!=2&&v[23]!=4)return false;
 return true;
}
bool validTools(const int32_t* v,size_t n){
 return v&&n==kToolsWords&&v[0]>=0&&v[0]<=6&&v[1]>=0&&v[1]<=1&&v[2]>=0&&v[3]==0&&
  valid(v+4,kWords)&&valid(v+32,kWords)&&(v[0]<4||v[2]>0);
}
#ifdef DSP_FUSION_TEST_ALLOCATIONS
int allocationFailure=-1;
void failAllocationForTest(int after){allocationFailure=after;}
#endif
}
namespace {
using Settings=analogtuning::Settings;
constexpr double pi=3.14159265358979323846;
constexpr unsigned ringSize=128,maxFir=125,latency=31;
double cleanSample(double x){return std::isfinite(x)?x:0.;}
float sampleFloat(double x){return std::isfinite(x)&&std::abs(x)<=std::numeric_limits<float>::max()?float(x):0.f;}
int32_t db(double x){return x>0?int32_t(std::clamp(std::lround(2000.*std::log10(x)),-20000l,20000l)):INT32_MIN;}
constexpr std::array<float,3> baseGains{.6f,1.2f,2.4f};
constexpr std::array<int,3> baseCuts{19650,18233,16307};
int effectiveFactor(const Settings& s){return s[1]&&s[22]?s[23]:1;}
Settings effectiveSettings(const Settings& s){
 auto p=s;p[24]=0;
 if(!p[1]){const int base=p[2];p=analogtuning::defaults;p[2]=base;return p;}
 if(!p[3]){p[4]=0;p[5]=1000;for(unsigned i=6;i<=9;++i)p[i]=0;}
 if(!p[10])for(unsigned i=11;i<=17;++i)p[i]=analogtuning::defaults[i];
 if(!p[18])p[19]=1000;
 if(!p[20])p[21]=0;
 if(!p[22])p[23]=2;
 // Equivalent neutral groups and saved controls cannot disturb the legacy stream.
 p[3]=p[4]!=0||p[5]!=1000||p[6]!=0||p[7]!=0||p[8]!=0||p[9]!=0;
 p[10]=false;for(unsigned i=11;i<=17;++i)if(p[i]!=analogtuning::defaults[i])p[10]=true;
 p[18]=p[19]!=1000;p[20]=p[21]!=0;
 p[1]=p[3]||p[10]||p[18]||p[20]||p[22];
 return p;
}
bool audioEqual(const Settings& a,const Settings& b){return effectiveSettings(a)==effectiveSettings(b);}
// Preserve every valid original coefficient. Values at/above Nyquist are moved
// to .45 Fs, leaving a finite margin for the cookbook biquads and octave BW.
float safeFrequency(double hz,unsigned fs){return float(hz>=fs*.5?fs*.45:hz);}
float safePeak(double hz,unsigned fs){return float(std::min(hz,fs*.45));}
struct StereoRing {
 std::array<std::array<double,ringSize>,2> data{};unsigned position=0;
 void push(double l,double r){data[0][position]=l;data[1][position]=r;}
 double read(unsigned c,unsigned delay)const{return data[c][(position+ringSize-std::min(delay,ringSize-1))%ringSize];}
 void advance(){position=(position+1)%ringSize;}
 void clear(){data={};position=0;}
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
 Settings settings=analogtuning::defaults;
 uint32_t rate=48000;unsigned factor=1,delay=0;
 std::array<MultiBiquad,2> highPass,lowPass,peak;
 std::array<Harmonic,2> harmonic;
 std::array<float,10> coefficients{};
 StereoRing rawRing;
 Fir fir;
 double drive=1,amount=1,mix=1,out=1;
 float gain=.6f,hpFrequency=240,lpFrequency=19650,peakFrequency=633,hpQ=.717f,lpQ=.717f,peakGain=.58f,peakBw=6.28f;
 bool clampDomain=false;
 uint64_t frames=0;unsigned harmonicWarm=200,warmRequired=200;bool rampBranch=false;
 bool warmReady()const{return frames>=warmRequired;}
 void filters(bool clear){for(unsigned c=0;c<2;++c){
  highPass[c].RefreshFilter(MultiBiquad::FilterType::HIGH_PASS,0.f,hpFrequency,rate,hpQ,false,clear);
  lowPass[c].RefreshFilter(MultiBiquad::FilterType::LOW_PASS,0.f,lpFrequency,rate,lpQ,false,clear);
  peak[c].RefreshFilter(MultiBiquad::FilterType::PEAK,peakGain,peakFrequency,rate,peakBw,true,clear);
 }}
 void reset(){frames=0;rawRing.clear();fir.configure(factor);filters(true);for(auto& h:harmonic){h.SetOversampling(factor);h.SetHarmonics(coefficients.data());}}
 void configure(const Settings& s,uint32_t fs){
  settings=s;rate=fs;factor=unsigned(effectiveFactor(s));delay=factor==1?0:latency;
  const bool core=s[1]&&s[3],tone=s[1]&&s[10];
  drive=core?std::pow(10.,s[4]/200.):1.;amount=core?s[5]/1000.:1.;gain=baseGains[s[2]];
  coefficients={.01f,.02f,.0001f,.001f,0,0,0,0,0,0};
  if(core)for(unsigned i=0;i<4;++i)coefficients[i+1]+=float(s[6+i]/1000000.);
  clampDomain=core&&(s[4]!=0||s[6]!=0||s[7]!=0||s[8]!=0||s[9]!=0);
  hpFrequency=safeFrequency(tone?s[11]:240,rate);lpFrequency=safeFrequency(baseCuts[s[2]]+(tone?s[13]:0),rate);
  peakFrequency=safePeak(tone?s[15]:633,rate);hpQ=tone?s[12]/1000.f:.717f;lpQ=tone?s[14]/1000.f:.717f;
  peakGain=tone?s[16]/100.f:.58f;peakBw=tone?s[17]/1000.f:6.28f;
  mix=s[1]&&s[18]?s[19]/1000.:1.;out=s[1]&&s[20]?std::pow(10.,s[21]/200.):1.;reset();
  float largest=0;for(float v:coefficients)largest=std::max(largest,std::abs(v));harmonicWarm=unsigned(largest*10000.f);
  rampBranch=effectiveSettings(s)[1]!=0;warmRequired=harmonicWarm+delay+(rampBranch?std::max(1u,rate/50):0u);
 }
 void inherit(const Kernel& old){
  rawRing=old.rawRing;
  // Retain histories for continuous coefficient changes; all allocation and
  // coefficient calculation happens here on the control side, before publication.
  highPass=old.highPass;lowPass=old.lowPass;peak=old.peak;filters(false);
  if(factor==old.factor){harmonic=old.harmonic;fir=old.fir;
   for(auto& h:harmonic){h.SetOversampling(factor);h.UpdateCoeffs(coefficients.data());}
   if(coefficients==old.coefficients){frames=old.frames;warmRequired=0;}}
 }
 void retune(const Settings& s,bool=false){settings=s;}
 Frame process(double l,double r){
  const std::array<double,2> input{cleanSample(l),cleanSample(r)};Frame f;std::array<double,2> branch{};
  rawRing.push(input[0],input[1]);
  for(unsigned c=0;c<2;++c)branch[c]=highPass[c].ProcessSample(input[c]);
  auto colour=[&](unsigned c,double x){const double feed=x*drive;return harmonic[c].Process(clampDomain?std::clamp(feed,-1.,1.):feed);};
  if(factor==1){for(unsigned c=0;c<2;++c)branch[c]=colour(c,branch[c]);}
  else {fir.inputFrame(branch[0],branch[1]);
   for(unsigned phase=0;phase<factor;++phase){fir.put(colour(0,fir.up(0,phase)),colour(1,fir.up(1,phase)));
    if(phase==0){branch[0]=fir.down(0);branch[1]=fir.down(1);}fir.advance();}fir.nextInput();}
  for(unsigned c=0;c<2;++c){const double original=rawRing.read(c,delay);
   // Literal legacy arithmetic at neutral settings: same float gain, 0.8 and
   // biquad/Harmonic ordering. The initial 200-frame suppression is branch-only.
   const double branchBlend=rampBranch?std::clamp((double(frames)-harmonicWarm)/std::max(1u,rate/50),0.,1.):1.;
   const double low=amount==1&&branchBlend==1?
    lowPass[c].ProcessSample(original+branch[c]*gain):
    lowPass[c].ProcessSample(original+branch[c]*gain*amount*branchBlend);
   const double wet=peak[c].ProcessSample(low*.8);
   f.original[c]=original;f.effect[c]=cleanSample(wet*out);
   f.normal[c]=cleanSample((mix==0?original:mix==1?wet:original+mix*(wet-original))*out);
  }
  if(frames<UINT64_MAX)++frames;rawRing.advance();return f;
 }
};
std::unique_ptr<Kernel> prepare(const Settings& s,uint32_t rate){
#ifdef DSP_FUSION_TEST_ALLOCATIONS
 if(analogtuning::allocationFailure>=0){if(analogtuning::allocationFailure==0){analogtuning::allocationFailure=-1;return {};}--analogtuning::allocationFailure;}
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
struct AnalogX::Engine {
 Settings settings=analogtuning::defaults;
 uint32_t rate=44100;bool enabled=false,ready=false,processed=false,pendingReady=false;
 std::unique_ptr<Kernel> normal,old,pending,a,b;
 Alignment normalAlign,oldAlign,pendingAlign,aAlign,bAlign;
 StereoRing rawHistory;
 unsigned fade=0,fadeLength=960,routeFade=0,routeLength=240,lastCommon=0;
 std::array<double,2> last{},routeFrom{};
 int mode=0,epoch=0,stage=0,revision=2;bool match=false;
 uint64_t warmFrames=0,warmRequired=22050,measureFrames=0;
 double energyIn=0,energyA=0,energyB=0,gainA=1,gainB=1,gainOriginal=1;
 std::array<double,4> peaks{},squares{};int32_t clips=0;double peakDecay=std::exp(-1./(.6*44100)),meterAlpha=1-std::exp(-1./(.1*44100));
 void bump(){revision=revision>=INT32_MAX-2?2:revision+2;}
 void clearMeters(){peaks={};squares={};clips=0;}
 void clearInternalTools(){a.reset();b.reset();aAlign={};bAlign={};mode=0;epoch=0;stage=0;match=false;warmFrames=measureFrames=0;energyIn=energyA=energyB=0;gainA=gainB=gainOriginal=1;bump();}
 void routeChange(){if(processed){routeFrom=last;routeFade=routeLength;}}
 void settle(){if(pendingReady){normal=std::move(pending);normalAlign=pendingAlign;pendingAlign={};pendingReady=false;}old.reset();fade=0;processed=false;routeFade=0;lastCommon=normal?normal->delay:0;}
 void clearControl(){const bool audible=mode!=0;clearInternalTools();clearMeters();if(audible)routeChange();}
 void reset(){settle();if(normal)normal->reset();if(a)a->reset();if(b)b->reset();normalAlign={};oldAlign={};aAlign={};bAlign={};rawHistory.clear();last={};clearInternalTools();clearMeters();}
 bool set(const Settings& s){
  const Kernel* candidate=pendingReady?pending.get():normal.get();
  if(ready&&candidate&&settings==s&&candidate->rate==rate&&candidate->factor==unsigned(effectiveFactor(s)))return true;
  const bool changed=!audioEqual(settings,s);
  const bool rebuild=changed||!ready||!candidate||candidate->rate!=rate||candidate->factor!=unsigned(effectiveFactor(s));
  if(rebuild){
   auto next=prepare(s,rate);if(!next)return false;
   if(normal&&normal->rate==rate&&enabled&&processed)next->inherit(*normal);
   if(enabled&&processed&&ready&&normal&&normal->rate==rate){
    // The latest target is continuously warmed with live samples before any
    // publication. Harmonic's weight-dependent startup count can reach 700
    // host frames; a fixed20ms crossfade alone cannot cover it at low Fs.
    pending=std::move(next);pendingAlign={};pendingReady=true;
   }else {normal=std::move(next);old.reset();pending.reset();pendingReady=false;normalAlign={};fade=0;}
  }else if(pendingReady)pending->retune(s,true);else normal->retune(s,!processed||!enabled);
  settings=s;ready=true;
  if(changed){const bool audibleTool=mode!=0;clearInternalTools();if(audibleTool)routeChange();}
  if(!settings[1]||!settings[24])clearMeters();bump();return true;
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
  if(v[0]>=4){Settings sa,sb;std::copy_n(v+4,28,sa.begin());std::copy_n(v+32,28,sb.begin());
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
   if(!fade&&pendingReady&&pending->warmReady()){old.swap(normal);normal.swap(pending);oldAlign=normalAlign;normalAlign=pendingAlign;pendingAlign={};pendingReady=false;fade=fadeLength;routeChange();}
   const unsigned common=commonDelay();if(common!=lastCommon){routeChange();lastCommon=common;}
   rawHistory.push(input[0],input[1]);std::array<double,2> raw{rawHistory.read(0,common),rawHistory.read(1,common)};rawHistory.advance();
   const auto current=normalAlign.process(normal->process(input[0],input[1]),common-normal->delay);std::array<double,2> result=current.normal;
   if(fade&&old){const auto previous=oldAlign.process(old->process(input[0],input[1]),common-old->delay);const double blend=1-double(fade)/fadeLength;
    for(unsigned c=0;c<2;++c)result[c]=previous.normal[c]+blend*(result[c]-previous.normal[c]);--fade;}
   if(pendingReady)pendingAlign.process(pending->process(input[0],input[1]),common-pending->delay);
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
   if(settings[1]&&settings[24]){for(unsigned c=0;c<2;++c){const double values[2]={input[c],last[c]};for(unsigned p=0;p<2;++p){const unsigned n=p*2+c;peaks[n]=std::max(std::abs(values[p]),peaks[n]*peakDecay);squares[n]+=(values[p]*values[p]-squares[n])*meterAlpha;}}
    if((std::abs(last[0])>1||std::abs(last[1])>1)&&clips<INT32_MAX)++clips;}
  }
  bump();
 }
 void runtime(int32_t* v)const{
  std::fill_n(v,40,0);v[0]=1;v[1]=revision;v[2]=rate;v[3]=enabled;v[4]=settings[1];v[5]=mode;v[6]=match;v[7]=stage;
  v[8]=int(measureFrames*1000/rate);v[9]=gainA>0?std::max(-18000,db(gainA)):0;v[10]=gainB>0?std::max(-18000,db(gainB)):0;v[11]=epoch;v[12]=settings[1]&&settings[24];
  const Kernel* selected=ready?(mode==4&&a?a.get():mode==5&&b?b.get():normal.get()):nullptr;
  const auto& s=selected?selected->settings:settings;v[13]=selected?selected->factor:1;v[14]=ready?commonDelay():0;
  v[15]=selected?int(std::lround(selected->hpFrequency)):0;
  for(unsigned i=16;i<24;++i)v[i]=INT32_MIN;
  if(enabled&&ready&&settings[1]&&settings[24])for(unsigned i=0;i<4;++i){v[16+i]=db(peaks[i]);v[20+i]=db(std::sqrt(std::max(0.,squares[i])));}
  v[24]=clips;v[25]=ready;v[26]=selected?int(std::lround(selected->lpFrequency)):0;
  v[27]=selected?int(std::lround(selected->peakFrequency)):0;
  v[28]=int(warmFrames*1000/rate);v[29]=int(warmRequired*1000/rate);v[30]=2000;v[31]=s[2];
  if(selected)for(unsigned i=0;i<4;++i)v[32+i]=int(std::lround(selected->coefficients[i+1]*1000000.));
 }
};
AnalogX::AnalogX():engine(new(std::nothrow) Engine){if(engine){engine->normal=prepare(analogtuning::defaults,44100);engine->ready=engine->normal!=nullptr;}}
AnalogX::~AnalogX()=default;
void AnalogX::Reset(){if(engine)engine->reset();}
void AnalogX::SetEnable(bool enabled){if(!engine)return;if(engine->enabled!=enabled){if(enabled)engine->reset();else {engine->settle();engine->clearControl();}engine->enabled=enabled;engine->bump();}}
void AnalogX::SetSamplingRate(uint32_t rate){if(engine)engine->setRate(rate);}
bool AnalogX::SetProfessional(const int32_t* v,size_t n){if(!engine||!analogtuning::valid(v,n))return false;Settings s;std::copy_n(v,28,s.begin());return engine->set(s);}
bool AnalogX::SetTools(const int32_t* v,size_t n){return engine&&analogtuning::validTools(v,n)&&engine->tools(v);}
void AnalogX::ClearTools(){if(engine)engine->clearControl();}
void AnalogX::Process(float* buffer,uint32_t size){if(engine)engine->process(buffer,size);}
void AnalogX::GetRuntime(int32_t* v)const{if(engine)engine->runtime(v);else{std::fill_n(v,40,0);v[0]=1;v[2]=44100;v[13]=1;v[15]=240;v[26]=19650;v[27]=633;v[29]=500;v[30]=2000;for(unsigned i=16;i<24;++i)v[i]=INT32_MIN;}}

void AnalogX::SetProcessingModel(int base){if(engine&&base>=0&&base<=2&&engine->settings[2]!=base){auto s=engine->settings;s[2]=base;engine->set(s);}}
