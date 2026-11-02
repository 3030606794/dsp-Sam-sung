#include "Compander.h"
#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstring>
#include <limits>
#include <memory>
#include <new>
#include <vector>
extern "C" {
#include "../../app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/jdsp_header.h"
}
#undef min
#undef max
namespace {
using Settings=std::array<int32_t,COMPANDER_WORDS>;
constexpr unsigned capacity=1024,ringSize=8192;
constexpr double pi=3.14159265358979323846;
float unpack(int32_t v){return std::bit_cast<float>(v);}
int32_t pack(float v){return std::bit_cast<int32_t>(v);}
Settings defaults(){Settings s{};s[0]=1;s[3]=pack(.22f);const float f[]{95,200,400,800,1600,3400,7500};for(unsigned i=0;i<7;++i)s[6+i]=pack(f[i]);s[21]=1000;s[37]=120;s[38]=240;s[41]=100;s[43]=250;s[47]=-800;s[52]=1000;s[56]=-180;s[57]=200;s[58]=60;s[59]=1;s[60]=20;s[66]=30;return s;}
double clean(double x){return std::isfinite(x)?x:0.;}
float asFloat(double x){return std::isfinite(x)&&std::abs(x)<=std::numeric_limits<float>::max()?float(x):0.f;}
int32_t db(double v){return v>0?int32_t(std::clamp(std::lround(2000*std::log10(v)),-20000l,20000l)):INT32_MIN;}
double dbValue(double x){return x>1e-10?20*std::log10(x):-200.;}
unsigned validFs(float fs){return std::clamp(unsigned(std::isfinite(fs)&&fs>0?fs:48000),8000u,768000u);}
bool standard(const Settings& s){return s[1]&&s[2];}
Settings effective(Settings s){
 s[67]=s[68]=0;
 if(!s[1]){auto d=defaults();std::copy(s.begin()+3,s.begin()+20,d.begin()+3);return d;}
 const auto d=defaults();
 if(!s[20]){s[21]=1000;for(unsigned i=22;i<=35;++i)s[i]=0;s[55]=d[55];s[56]=d[56];s[57]=d[57];s[58]=d[58];s[69]=0;}
 if(!s[36]){s[37]=d[37];s[38]=d[38];}
 if(!s[39]){s[40]=0;s[41]=d[41];s[42]=0;s[43]=d[43];}
 if(!s[44])for(unsigned i=45;i<=50;++i)s[i]=d[i];
 if(!s[51])s[52]=1000;if(!s[53])s[54]=0;
 if(!standard(s)){for(unsigned i=55;i<=66;++i)s[i]=d[i];}
 else {s[21]=1000;for(unsigned i=22;i<=35;++i)s[i]=0;s[40]=0;s[45]=s[46]=s[69]=0;
  if(!s[61])s[62]=s[63]=0;if(!s[64])s[65]=0;if(!s[59])s[60]=d[60];if(s[55]!=3)s[66]=d[66];}
 // Group flags at their neutral values have no audible effect on legacy audio.
 if(!standard(s)){
  bool core=s[21]!=1000||s[69];for(unsigned i=22;i<=35;++i)core=core||s[i]!=0;s[20]=core;
  s[51]=s[52]!=1000;s[53]=s[54]!=0;
  s[1]=s[20]||s[36]||s[39]||s[44]||s[51]||s[53];
 }
 return s;
}
bool audioEqual(const Settings& a,const Settings& b){return effective(a)==effective(b);}
#ifdef DSP_FUSION_TEST_ALLOCATIONS
int failAllocation=-1;
#endif
struct Ring {
 std::array<std::array<double,ringSize>,2> data{};unsigned position=0;
 void push(double l,double r){data[0][position]=l;data[1][position]=r;}
 double read(unsigned c,unsigned delay)const{return data[c][(position+ringSize-std::min(delay,ringSize-1))%ringSize];}
 void advance(){position=(position+1)%ringSize;}
};
struct Pole {
 double previous=0,state=0;
 double low(double x,double pole){const double y=(1-pole)*.5*(x+previous)+pole*state;previous=x;state=y;return y;}
 double high(double x,double pole){return x-low(x,pole);}
};
struct Frame {std::array<double,2> normal{},reference{};};
struct Kernel {
 Settings settings=defaults();unsigned rate=48000,delay=0,nominalDelay=0,warmRequired=24000;uint64_t frames=0;
 bool isStandard=false,neutral=true,gateOpen=false;
 std::unique_ptr<FFTCompander> legacy;
 std::array<std::array<float,capacity>,2> normal{},reference{};
 std::array<double,7> nodes{},coefficients{};
 double backgroundMs=220,attackMs=10,holdMs=0,releaseMs=250,mix=1,output=1;
 double rangeLow=0,rangeHigh=0,sideHP=0,sideLP=0,rangeHpPole=0,rangeLpPole=0,sideHpPole=0,sideLpPole=0;
 double gain=0,detectorDb=-200,maxBoost=0,maxCut=0,inputEnergy=0;
 double attackAlpha=0,releaseAlpha=0;unsigned held=0,holdFrames=0,rmsPosition=0,rmsFrames=1;bool exact=false;
 std::array<Pole,2> rangeHp{},rangeLp{},sideHp{},sideLp{};Ring raw,band;
 std::array<std::vector<double>,2> rms;
 std::array<double,2> rmsSum{};
 ~Kernel(){if(legacy)freeIerper(&legacy->pch);}
 bool warmReady()const{return frames>=warmRequired;}
 double pole(double hz)const{const double k=std::tan(pi*std::clamp(hz,.01,rate*.45)/rate);return (1-k)/(1+k);}
 void configure(const Settings& s,unsigned fs){
  settings=s;rate=fs;isStandard=standard(s);neutral=!effective(s)[1];
  const bool pro=s[1],core=pro&&s[20],time=pro&&s[39],detect=pro&&s[44];
  backgroundMs=std::clamp(double(unpack(s[3]))*1000+(time?s[40]/10.:0),1.,4000.);
  attackMs=time?s[41]/10.:10.;holdMs=time?s[42]:0;releaseMs=time?s[43]:250;
  attackAlpha=1-std::exp(-1./(attackMs*fs/1000.));releaseAlpha=1-std::exp(-1./(releaseMs*fs/1000.));holdFrames=unsigned(std::lround(holdMs*fs/1000.));
  mix=pro&&s[51]?s[52]/1000.:1;output=pro&&s[53]?std::pow(10.,s[54]/200.):1;
  rangeLow=detect&&s[49]?std::min(double(s[49]),fs*.40):0;rangeHigh=detect&&s[50]?std::min(double(s[50]),fs*.45):0;
  if(rangeLow&&rangeHigh&&rangeLow>=rangeHigh)rangeLow=rangeHigh*.8;
  rangeHpPole=pole(rangeLow);rangeLpPole=pole(rangeHigh);
  for(unsigned i=0;i<7;++i){nodes[i]=unpack(s[6+i])+(core&&!isStandard?s[22+i]/1000.:0);coefficients[i]=std::clamp(double(unpack(s[13+i]))+(core&&!isStandard?s[29+i]/1000.:0),-1.2,1.2);}
  // Saved requests are immutable. Clamp the derived ordered set at actual Fs.
  const double top=std::min(23999.,fs*.45);for(unsigned i=0;i<7;++i)nodes[i]=std::clamp(nodes[i],1.+i,top-(6-i));
  for(unsigned i=1;i<7;++i)nodes[i]=std::max(nodes[i],nodes[i-1]+.001);
  exact=core&&!isStandard&&s[69];
  if(isStandard){
   sideHP=s[61]&&s[62]?std::min(double(s[62]),fs*.40):0;sideLP=s[61]&&s[63]?std::min(double(s[63]),fs*.45):0;
   if(sideHP&&sideLP&&sideHP>=sideLP)sideHP=sideLP*.8;
   sideHpPole=pole(sideHP);sideLpPole=pole(sideLP);
   delay=s[64]?unsigned(std::lround(s[65]*fs/10000.)):0;
   rmsFrames=std::max(1u,unsigned(std::lround(s[60]*fs/1000.)));for(auto& c:rms)c.assign(rmsFrames,0.);
  }else{
   legacy=std::make_unique<FFTCompander>();initIerper(&legacy->pch,NUMPTS_DRS+2);
   legacy->proExact=exact;FFTCompanderConfigure(legacy.get(),float(fs),float(backgroundMs/1000.),s[4],s[5]);
   // The original non-neutral parameter requests are preserved exactly off.
   bool frequencyCorrection=false;for(unsigned i=22;i<=28;++i)frequencyCorrection=frequencyCorrection||(core&&s[i]!=0);
   if(!exact&&!frequencyCorrection)for(unsigned i=0;i<7;++i)nodes[i]=unpack(s[6+i]);
   if(!core)for(unsigned i=0;i<7;++i)coefficients[i]=unpack(s[13+i]);
   FFTCompanderCurve(legacy.get(),float(fs),nodes.data(),coefficients.data());
   auto& c=*legacy;c.proCore=core;c.proLimits=pro&&s[36];c.proTime=time;c.proDetect=detect;c.proLinkMax=detect&&s[48];c.proTrackRaw=1;
   c.proAmount=s[21]/1000.f;c.proBoost=s[37]/10.f;c.proCut=s[38]/10.f;c.proRelative=s[45]/10.f;c.proKnee=s[46]/10.f;c.proFloor=s[47]/10.f;
   c.proLow=float(rangeLow);c.proHigh=rangeHigh?float(rangeHigh):float(fs*.5);c.proInputDb=-200;
   const double tickRate=s[5]<3?c.spectralRate:fs;c.proAttack=float(1-std::exp(-1./(attackMs*tickRate/1000.)));c.proRelease=float(1-std::exp(-1./(releaseMs*tickRate/1000.)));c.proHold=unsigned(std::lround(holdMs*tickRate/1000.));
   if(s[5]==3){for(unsigned i=0;i<DYN_BANDS_GAMMATONE;++i)c.proFrequency[i]=c.gmtFreq[i];delay=0;}
   else {delay=s[5]==2?c.fftLen/2:c.fftLen-c.ovpLen;
    if(c.noGridDownsampling)for(unsigned i=0;i<c.procUpTo;++i)c.proFrequency[i]=float(double(i)*fs/c.fftLen);
    else {const unsigned len=*reinterpret_cast<unsigned*>(c.octaveSmooth+sizeof(unsigned)+sizeof(float));const float* levels=reinterpret_cast<float*>(c.octaveSmooth+sizeof(unsigned)+sizeof(float)+sizeof(unsigned)+(len<<1)*sizeof(unsigned)+len*sizeof(float));for(unsigned i=0;i<c.smallGridSize;++i)c.proFrequency[i]=float(levels[i]*c.procUpTo*fs/c.fftLen);}}
  }
  nominalDelay=delay;warmRequired=unsigned(std::lround(fs*std::clamp(std::max(.5,3*(isStandard?releaseMs:backgroundMs)/1000.),.5,6.)))+delay;
 }
 double softPositive(double x,double knee)const{if(knee<=0)return std::max(0.,x);if(x<=-knee*.5)return 0;if(x>=knee*.5)return x;const double y=x+knee*.5;return y*y/(2*knee);}
 double standardTarget(double level){
  const auto& s=settings;if(!s[20])return 0.;const double threshold=s[56]/10.,ratio=s[57]/100.,knee=s[58]/10.;double target=0;
  if(s[55]==0)target=-(1-1/ratio)*softPositive(level-threshold,knee);
  else if(s[55]==1)target=level<=-100?0:(1-1/ratio)*softPositive(threshold-level,knee);
  else if(s[55]==2)target=-(ratio-1)*softPositive(threshold-level,knee);
  else {if(gateOpen){if(level<threshold-s[66]/10.)gateOpen=false;}else if(level>=threshold)gateOpen=true;
   const double depth=s[36]?s[38]/10.:60.;target=gateOpen?0:-depth;
   if(!gateOpen&&knee>0){const double close=threshold-s[66]/10.;const double t=std::clamp((close+knee*.5-level)/knee,0.,1.);target=-depth*t*t*(3-2*t);}}
  if(s[55]==1&&s[44]&&level<s[47]/10.)target=0;
  if(s[36])target=std::clamp(target,-s[38]/10.,s[37]/10.);
  return std::clamp(target,-120.,60.);
 }
 void render(const float* l,const float* r,unsigned n){
  maxBoost=maxCut=0;
  if(!isStandard){
   double energy=0;for(unsigned i=0;i<n;++i){normal[0][i]=asFloat(l[i]);normal[1][i]=asFloat(r[i]);energy+=(double(l[i])*l[i]+double(r[i])*r[i])*.5;}
   const double rmsIn=std::sqrt(std::max(0.,energy/n));legacy->proInputDb=float(dbValue(rmsIn));detectorDb=legacy->proInputDb;
   FFTCompanderRender(legacy.get(),normal[0].data(),normal[1].data(),n,reference[0].data(),reference[1].data());
   if(settings[5]<3&&legacy->proInputSamples)delay=legacy->proRawDelay;
   const unsigned count=settings[5]==3?DYN_BANDS_GAMMATONE:legacy->noGridDownsampling?legacy->procUpTo:legacy->smallGridSize;
   for(unsigned i=0;i<count;++i){maxBoost=std::max(maxBoost,double(legacy->proGain[i]));maxCut=std::max(maxCut,-double(legacy->proGain[i]));}
   if(mix!=1||output!=1)for(unsigned i=0;i<n;++i)for(unsigned c=0;c<2;++c)normal[c][i]=asFloat((reference[c][i]+mix*(normal[c][i]-reference[c][i]))*output);
   frames+=n;return;
  }
  for(unsigned i=0;i<n;++i){const std::array<double,2> input{clean(l[i]),clean(r[i])};std::array<double,2> part{},det{};
   for(unsigned c=0;c<2;++c){double b=input[c];const double h=rangeHp[c].high(b,rangeHpPole);if(rangeLow)b=h;const double low=rangeLp[c].low(b,rangeLpPole);if(rangeHigh)b=low;part[c]=b;
    double x=b;const double h1=sideHp[c].high(x,sideHpPole);if(sideHP)x=h1;const double l1=sideLp[c].low(x,sideLpPole);if(sideLP)x=l1;
    const double energy=x*x;rmsSum[c]+=energy-rms[c][rmsPosition];rms[c][rmsPosition]=energy;
    det[c]=settings[59]?std::sqrt(std::max(0.,rmsSum[c]/rmsFrames)):std::abs(x);}
   rmsPosition=(rmsPosition+1)%rmsFrames;
   const double linked=settings[44]&&settings[48]?std::max(det[0],det[1]):(det[0]+det[1])*.5;detectorDb=dbValue(linked);
   const double target=standardTarget(detectorDb);
   // A gate attacks when opening and releases when closing. Other processors
   // attack as their dynamic action grows and release towards neutral gain.
   const bool increasing=settings[55]==3?target>gain:std::abs(target)>std::abs(gain)||target*gain<0;
   if(increasing)held=holdFrames;else if(held)--held;
   if(increasing||held==0)gain+=(target-gain)*(increasing?attackAlpha:releaseAlpha);
   maxBoost=std::max(maxBoost,gain);maxCut=std::max(maxCut,-gain);
   const double linear=std::pow(10.,gain/20.);raw.push(input[0],input[1]);band.push(part[0],part[1]);
   for(unsigned c=0;c<2;++c){const double dry=raw.read(c,delay),wet=dry+(linear-1)*band.read(c,delay);reference[c][i]=asFloat(dry);normal[c][i]=asFloat((dry+mix*(wet-dry))*output);}
   raw.advance();band.advance();++frames;
  }
 }
 Frame frame(unsigned i)const{return {{normal[0][i],normal[1][i]},{reference[0][i],reference[1][i]}};}
 std::array<double,4> control(double hz)const{
  const bool active=!settings[44]||(hz>=rangeLow&&(!rangeHigh||hz<=rangeHigh));
  if(isStandard)return {active?gain:0.,0.,detectorDb,-200.};
  const auto& c=*legacy;const unsigned n=settings[5]==3?DYN_BANDS_GAMMATONE:c.noGridDownsampling?c.procUpTo:c.smallGridSize;if(!n)return {0,0,-200,-200};
  unsigned j=0;while(j+1<n&&c.proFrequency[j+1]<hz)++j;const unsigned k=std::min(j+1,n-1);const double denominator=c.proFrequency[k]-c.proFrequency[j];const double t=denominator>0?std::clamp((hz-c.proFrequency[j])/denominator,0.,1.):0.;
  auto interp=[&](const float* a){return a[j]+t*(a[k]-a[j]);};
  const double mask=(1-t)*std::pow(10.,c.proGain[j]/20.)+t*std::pow(10.,c.proGain[k]/20.);
  return {dbValue(mask),interp(c.DREmult),interp(c.proDetector),interp(c.oldBuf)};
 }
};
std::unique_ptr<Kernel> prepare(const Settings& s,unsigned rate){
#ifdef DSP_FUSION_TEST_ALLOCATIONS
 if(failAllocation>=0){if(!failAllocation){failAllocation=-1;return {};}--failAllocation;}
#endif
 try{auto k=std::unique_ptr<Kernel>(new(std::nothrow) Kernel);if(k)k->configure(s,rate);return k;}catch(const std::bad_alloc&){return {};}
}
struct Align {
 Ring normal,reference;
 Frame process(const Frame& f,unsigned delay){Frame result;normal.push(f.normal[0],f.normal[1]);reference.push(f.reference[0],f.reference[1]);for(unsigned c=0;c<2;++c){result.normal[c]=normal.read(c,delay);result.reference[c]=reference.read(c,delay);}normal.advance();reference.advance();return result;}
};
struct Engine {
 Settings settings=defaults();unsigned rate=48000,hostRate=48000;bool enabled=false,ready=false,processed=false,pendingReady=false;
 std::unique_ptr<Kernel> main,old,pending,a,b;Align mainAlign,oldAlign,pendingAlign,aAlign,bAlign;
 unsigned fade=0,fadeLength=960,routeFade=0,routeLength=240,lastCommon=0,lastNominal=0;
 std::array<double,2> last{},routeFrom{};int mode=0,epoch=0,stage=0,revision=2;bool match=false;
 uint64_t warmFrames=0,warmRequired=24000,measureFrames=0;double energyIn=0,energyA=0,energyB=0,gainA=1,gainB=1,gainRaw=1;
 std::array<double,4> peaks{},squares{};int32_t clips=0;double peakDecay=std::exp(-1./(.6*48000)),meterAlpha=1-std::exp(-1./(.1*48000));
 void bump(){revision=revision>=INT32_MAX-2?2:revision+2;}
 void clearMeters(){peaks={};squares={};clips=0;}
 void clearTools(){a.reset();b.reset();aAlign={};bAlign={};mode=epoch=stage=0;match=false;warmFrames=measureFrames=0;energyIn=energyA=energyB=0;gainA=gainB=gainRaw=1;bump();}
 void routeChange(){if(processed){routeFrom=last;routeFade=routeLength;}}
 void clearControl(){const bool audible=mode!=0;clearTools();clearMeters();if(audible)routeChange();}
 bool set(const Settings& s){
  const Kernel* candidate=pendingReady?pending.get():main.get();if(ready&&candidate&&settings==s&&candidate->rate==rate)return true;
  const bool changed=!audioEqual(settings,s),rebuild=changed||!ready||!candidate||candidate->rate!=rate;
  if(rebuild){auto next=prepare(s,rate);if(!next)return false;
   if(enabled&&processed&&ready&&main&&main->rate==rate){pending=std::move(next);pendingAlign={};pendingReady=true;}
   else {main=std::move(next);old.reset();pending.reset();pendingReady=false;mainAlign={};fade=0;}}
  else if(pendingReady)pending->settings=s;else if(main)main->settings=s;
  settings=s;ready=true;if(changed){const bool audible=mode!=0;clearTools();if(audible)routeChange();}
  if(!s[1]||!s[67])clearMeters();bump();return true;
 }
 void refresh(unsigned fs,unsigned host){
  const bool hostChanged=hostRate!=host;hostRate=host;if(rate==fs&&ready&&main&&main->rate==fs){if(hostChanged)clearControl();return;}
  rate=fs;fadeLength=std::max(1u,fs/50);routeLength=std::max(1u,fs/200);peakDecay=std::exp(-1./(.6*fs));meterAlpha=1-std::exp(-1./(.1*fs));
  auto next=prepare(settings,fs);if(next){main=std::move(next);ready=true;}else ready=false;
  old.reset();pending.reset();pendingReady=false;fade=routeFade=0;processed=false;mainAlign={};oldAlign={};pendingAlign={};last={};lastCommon=lastNominal=0;clearTools();clearMeters();
 }
 void enable(bool on){
  if(enabled==on)return;
  if(!on){if(pendingReady){main=std::move(pending);mainAlign=pendingAlign;pendingAlign={};pendingReady=false;}old.reset();fade=routeFade=0;processed=false;clearTools();clearMeters();}
  else {auto next=prepare(settings,rate);if(next){main=std::move(next);ready=true;}else ready=false;mainAlign={};processed=false;last={};}
  enabled=on;bump();
 }
 bool tools(const int32_t* v){
  if(!enabled||!settings[1]||!ready){clearTools();return v[0]==0;}
  if(v[0]==0){if(mode)routeChange();clearTools();return true;}
  bool snapshotsChanged=false;
  if(v[0]>=4&&v[0]<=6){Settings sa,sb;std::copy_n(v+4,72,sa.begin());std::copy_n(v+76,72,sb.begin());
   if(!a||!b||!audioEqual(a->settings,sa)||!audioEqual(b->settings,sb)||a->rate!=rate||b->rate!=rate||epoch!=v[2]){auto na=prepare(sa,rate),nb=prepare(sb,rate);if(!na||!nb)return false;a=std::move(na);b=std::move(nb);aAlign={};bAlign={};snapshotsChanged=true;stage=0;gainA=gainB=gainRaw=1;warmFrames=measureFrames=0;}}
  if(!snapshotsChanged&&mode==v[0]&&match==(v[1]!=0)&&epoch==v[2])return true;
  const bool measurement=v[0]==6&&(snapshotsChanged||mode!=6||epoch!=v[2]);const bool audible=mode!=v[0]||epoch!=v[2]||((mode==1||mode==4||mode==5)&&stage==3&&match!=(v[1]!=0));
  mode=v[0];match=v[1]!=0;epoch=v[2];if(audible)routeChange();
  if(measurement){stage=1;warmFrames=measureFrames=0;energyIn=energyA=energyB=0;gainA=gainB=gainRaw=1;warmRequired=std::max({rate/2,a?a->warmRequired:0,b?b->warmRequired:0});}bump();return true;
 }
 unsigned commonNominal()const{unsigned d=main?main->nominalDelay:0;if(fade&&old)d=std::max(d,old->nominalDelay);if(pendingReady&&pending)d=std::max(d,pending->nominalDelay);if(mode&&epoch&&a&&b)d=std::max({d,a->nominalDelay,b->nominalDelay});return d;}
 unsigned commonDelay()const{unsigned d=main?main->delay:0;if(fade&&old)d=std::max(d,old->delay);if(pendingReady&&pending)d=std::max(d,pending->delay);if(mode&&epoch&&a&&b)d=std::max({d,a->delay,b->delay});return std::min(d,ringSize-1);}
 void meter(const std::array<double,2>& input,const std::array<double,2>& result){
  if(!settings[1]||!settings[67])return;
  for(unsigned c=0;c<2;++c){peaks[c]=std::max(std::abs(input[c]),peaks[c]*peakDecay);peaks[c+2]=std::max(std::abs(result[c]),peaks[c+2]*peakDecay);squares[c]+=(input[c]*input[c]-squares[c])*meterAlpha;squares[c+2]+=(result[c]*result[c]-squares[c+2])*meterAlpha;}
  if((std::abs(result[0])>=1||std::abs(result[1])>=1)&&clips<INT32_MAX)++clips;
 }
 void process(float* l,float* r,size_t n){
  if(!enabled||!ready||!main)return;
  for(size_t offset=0;offset<n;offset+=capacity){const unsigned count=unsigned(std::min(size_t(capacity),n-offset));
   // Every legacy snapshot receives exactly the same original block subdivision.
   // No per-sample rescheduling is used to manufacture an A/B reference.
   if(!fade&&pendingReady&&pending->warmReady()){old.swap(main);main.swap(pending);oldAlign=mainAlign;mainAlign=pendingAlign;pendingAlign={};pendingReady=false;fade=fadeLength;}
   main->render(l+offset,r+offset,count);if(fade&&old)old->render(l+offset,r+offset,count);if(pendingReady)pending->render(l+offset,r+offset,count);if(mode&&epoch&&a&&b){a->render(l+offset,r+offset,count);b->render(l+offset,r+offset,count);}
   const unsigned common=commonDelay(),nominal=commonNominal();if(nominal!=lastNominal)routeChange();lastCommon=common;lastNominal=nominal;
   const bool legacyFast=main->neutral&&!fade&&!pendingReady&&mode==0&&!routeFade;
   for(unsigned i=0;i<count;++i){const std::array<double,2> input{clean(l[offset+i]),clean(r[offset+i])};const auto current=mainAlign.process(main->frame(i),common-main->delay);auto result=legacyFast?main->frame(i).normal:current.normal;
    if(fade&&old){const auto previous=oldAlign.process(old->frame(i),common-old->delay);const double blend=1-double(fade)/fadeLength;for(unsigned c=0;c<2;++c)result[c]=previous.normal[c]+blend*(result[c]-previous.normal[c]);--fade;}
    if(pendingReady)pendingAlign.process(pending->frame(i),common-pending->delay);
    Frame fa{},fb{};if(mode&&epoch&&a&&b){fa=aAlign.process(a->frame(i),common-a->delay);fb=bAlign.process(b->frame(i),common-b->delay);}
    if(mode==6&&a&&b){if(stage==1){if(++warmFrames>=warmRequired){stage=2;measureFrames=0;}}
     else if(stage==2){for(unsigned c=0;c<2;++c){energyIn+=current.reference[c]*current.reference[c];energyA+=fa.normal[c]*fa.normal[c];energyB+=fb.normal[c]*fb.normal[c];}++measureFrames;
      if(measureFrames>=uint64_t(rate)*2){if(energyIn/(measureFrames*2)<1e-8||energyA/(measureFrames*2)<1e-12||energyB/(measureFrames*2)<1e-12){stage=4;gainA=gainB=gainRaw=1;}
       else {const double target=std::min({energyIn,energyA,energyB});gainA=std::sqrt(target/energyA);gainB=std::sqrt(target/energyB);gainRaw=std::sqrt(target/energyIn);stage=3;}}}}
    if(mode==1){result=current.reference;if(match&&stage==3)for(auto& x:result)x*=gainRaw;}
    else if(mode==2)result=current.normal;
    else if(mode==3)for(unsigned c=0;c<2;++c)result[c]=current.normal[c]-current.reference[c];
    else if(mode==4&&a){result=fa.normal;if(match&&stage==3)for(auto& x:result)x*=gainA;}
    else if(mode==5&&b){result=fb.normal;if(match&&stage==3)for(auto& x:result)x*=gainB;}
    if(routeFade&&!legacyFast){const double t=1-double(routeFade)/routeLength;for(unsigned c=0;c<2;++c)result[c]=routeFrom[c]+t*(result[c]-routeFrom[c]);--routeFade;}
    if(legacyFast)routeFade=0;
    for(auto& x:result)x=clean(x);meter(input,result);l[offset+i]=asFloat(result[0]);r[offset+i]=asFloat(result[1]);last=result;processed=true;
   }
  }
  bump();
 }
 const Kernel* selected()const{return (mode==4&&a)?a.get():(mode==5&&b)?b.get():main.get();}
 void status(int32_t* v)const{
  std::fill_n(v,80,0);const Kernel* k=selected();v[0]=1;v[1]=revision;v[2]=rate;v[3]=enabled;v[4]=settings[1];v[5]=mode;v[6]=match;v[7]=stage;v[8]=int(std::min(uint64_t(2000),measureFrames*1000/rate));v[9]=db(gainA);v[10]=db(gainB);v[11]=epoch;v[12]=settings[1]&&settings[67];
  v[13]=k&&k->isStandard;v[14]=k?k->settings[5]:settings[5];v[15]=k?k->settings[4]:settings[4];for(unsigned i=16;i<24;++i)v[i]=INT32_MIN;v[41]=INT32_MIN;
  if(enabled&&ready&&settings[1]&&settings[67]){for(unsigned i=0;i<4;++i){v[16+i]=db(peaks[i]);v[20+i]=db(std::sqrt(std::max(0.,squares[i])));}if(k){v[27]=int(std::lround(k->maxBoost*100));v[28]=int(std::lround(k->maxCut*100));v[41]=int(std::clamp(std::lround(k->detectorDb*100),-20000l,20000l));}}
  v[24]=clips;v[25]=ready;v[26]=hostRate;v[29]=int(warmFrames*1000/rate);v[30]=int(warmRequired*1000/rate);v[31]=2000;v[32]=lastCommon;v[45]=200;v[48]=20;
  if(k){const auto& s=k->settings;v[33]=int(std::lround(k->backgroundMs*10));v[34]=k->isStandard||(s[1]&&s[39])?int(std::lround(k->attackMs*10)):0;v[35]=k->isStandard||(s[1]&&s[39])?int(k->holdMs):0;v[36]=k->isStandard||(s[1]&&s[39])?int(k->releaseMs):0;v[37]=s[1]&&s[44]&&!k->isStandard?s[45]:0;v[38]=s[1]&&s[44]&&!k->isStandard?s[46]:0;v[39]=s[1]&&s[44]?s[47]:-1000;v[40]=s[1]&&s[44]?s[48]:0;
   v[42]=k->isStandard?int(std::lround(1e6/rate)):s[5]==3?int(std::lround(2e6/rate)):int(std::lround(1e6/k->legacy->spectralRate));v[43]=k->isStandard?k->delay:0;v[44]=s[56];v[45]=s[57];v[46]=s[55];v[47]=s[59];v[48]=s[60];v[49]=int(std::lround(k->sideHP));v[50]=int(std::lround(k->sideLP));v[51]=int(std::lround(k->rangeLow));v[52]=int(std::lround(k->rangeHigh));v[53]=s[1]&&s[36]?s[37]*10:0;v[54]=s[1]&&s[36]?s[38]*10:0;v[55]=k->gateOpen;v[56]=s[66];v[57]=s[1]&&s[39];v[58]=s[1]&&s[20];v[59]=s[1]&&s[44];v[60]=int(std::lround(k->mix*1000));v[61]=s[1]&&s[53]?s[54]:0;v[62]=k->exact;for(unsigned i=0;i<7;++i){v[64+i]=int(std::lround(k->nodes[i]));v[71+i]=int(std::lround(k->coefficients[i]*1000));}v[78]=!k->isStandard;}
 }
 void response(int32_t* v)const{
  std::fill_n(v,200,0);const auto* k=selected();v[0]=1;v[1]=revision;v[2]=rate;v[3]=k&&k->isStandard;v[4]=enabled&&ready&&settings[1]&&settings[68];v[5]=32;v[6]=k?k->settings[5]:settings[5];
  const double high=std::min(20000.,rate*.45);for(unsigned i=0;i<32;++i){const double hz=20*std::pow(high/20.,double(i)/31);const unsigned at=8+6*i;v[at]=int(std::lround(hz));v[at+3]=v[at+4]=INT32_MIN;if(v[4]&&k){const auto c=k->control(hz);v[at+1]=int(std::clamp(std::lround(c[0]*100),-18000l,18000l));v[at+2]=int(std::clamp(std::lround(c[1]*1000),-2400l,2400l));v[at+3]=int(std::clamp(std::lround(c[2]*100),-20000l,20000l));v[at+4]=k->isStandard?INT32_MIN:int(std::clamp(std::lround(c[3]*100),-20000l,20000l));v[at+5]=(!k->isStandard||k->settings[20])&&(!k->settings[44]||(hz>=k->rangeLow&&(!k->rangeHigh||hz<=k->rangeHigh)));}}
 }
};
}
extern "C" {
int CompanderValidate(const int32_t* v,size_t n){
 if(!v||n!=72||v[0]!=1)return 0;
 const float t=unpack(v[3]);if(!std::isfinite(t)||t<.06f||t>.3f)return 0;
 for(unsigned i=0;i<7;++i){const float f=unpack(v[6+i]),g=unpack(v[13+i]);if(!std::isfinite(f)||f<=0||f>=24000||!std::isfinite(g)||g< -1.2f||g>1.2f||(i&&f<=unpack(v[5+i])))return 0;}
 for(unsigned i:{1u,2u,20u,36u,39u,44u,48u,51u,53u,59u,61u,64u,67u,68u,69u})if(v[i]<0||v[i]>1)return 0;
 if(v[4]<0||v[4]>4||v[5]<0||v[5]>3||v[21]<0||v[21]>2000)return 0;
 for(unsigned i=22;i<=28;++i)if(v[i]<-20000000||v[i]>20000000)return 0;for(unsigned i=29;i<=35;++i)if(v[i]<-2400||v[i]>2400)return 0;
 if(v[37]<0||v[37]>240||v[38]<0||v[38]>480||v[40]<-20000||v[40]>20000||v[41]<1||v[41]>2000||v[42]<0||v[42]>500||v[43]<10||v[43]>2000||v[45]<0||v[45]>240||v[46]<0||v[46]>240||v[47]<-1000||v[47]>-300)return 0;
 for(unsigned i:{49u,50u})if(v[i]<0||v[i]>20000||(v[i]>0&&v[i]<20))return 0;if(v[49]&&v[50]&&v[49]>=v[50])return 0;
 if(v[52]<0||v[52]>1000||v[54]<-240||v[54]>0||v[55]<0||v[55]>3||v[56]<-800||v[56]>0||v[57]<100||v[57]>2000||v[58]<0||v[58]>240||v[60]<1||v[60]>100||v[62]<0||v[62]>1000||(v[62]>0&&v[62]<20)||v[63]<0||v[63]>20000||(v[63]>0&&v[63]<500)||(v[62]&&v[63]&&v[62]>=v[63])||v[65]<0||v[65]>200||v[66]<0||v[66]>120||v[70]||v[71])return 0;
 return 1;
}
int CompanderToolsValidate(const int32_t* v,size_t n){return v&&n==148&&v[0]>=0&&v[0]<=6&&v[1]>=0&&v[1]<=1&&v[2]>=0&&v[3]==0&&CompanderValidate(v+4,72)&&CompanderValidate(v+76,72)&&(!(v[0]>=4&&v[0]<=6)||v[2]>0);}
void* CompanderCreate(){return new(std::nothrow) Engine;}
void CompanderDestroy(void* p){delete static_cast<Engine*>(p);}
int CompanderSet(void* p,void* owner,const int32_t* v,size_t n){if(!p||!owner||!CompanderValidate(v,n))return 0;auto& e=*static_cast<Engine*>(p);const auto& d=*static_cast<JamesDSPLib*>(owner);if(e.rate!=validFs(d.fs)||e.hostRate!=validFs(d.trueSampleRate))e.refresh(validFs(d.fs),validFs(d.trueSampleRate));Settings s;std::copy_n(v,72,s.begin());return e.set(s);}
int CompanderTools(void* p,void* owner,const int32_t* v,size_t n){return p&&owner&&CompanderToolsValidate(v,n)&&static_cast<Engine*>(p)->tools(v);}
void CompanderClear(void* p){if(p)static_cast<Engine*>(p)->clearControl();}
void CompanderStatus(const void* p,int32_t* v){if(!v)return;if(p)static_cast<const Engine*>(p)->status(v);else{std::fill_n(v,80,0);v[0]=1;v[31]=2000;v[45]=200;v[48]=20;for(unsigned i=16;i<24;++i)v[i]=INT32_MIN;v[41]=INT32_MIN;}}
void CompanderResponse(const void* p,int32_t* v){if(!v)return;if(p)static_cast<const Engine*>(p)->response(v);else{std::fill_n(v,200,0);v[0]=1;v[5]=32;for(unsigned i=0;i<32;++i)v[8+i*6+3]=v[8+i*6+4]=INT32_MIN;}}
void CompanderProcess(void* p,void* owner,size_t n){if(p&&owner){auto& d=*static_cast<JamesDSPLib*>(owner);static_cast<Engine*>(p)->process(d.tmpBuffer[0],d.tmpBuffer[1],n);}}
void CompanderRefresh(void* p,void* owner){if(p&&owner){const auto& d=*static_cast<JamesDSPLib*>(owner);static_cast<Engine*>(p)->refresh(validFs(d.fs),validFs(d.trueSampleRate));}}
void CompanderEnable(void* p,void* owner,int on){if(p&&owner){CompanderRefresh(p,owner);static_cast<Engine*>(p)->enable(on!=0);}}
void CompanderSetLegacyParam(void* p,void* owner,float time,int granularity,int transform){if(!p||!owner)return;auto& e=*static_cast<Engine*>(p);auto s=e.settings;s[3]=pack(time);s[4]=granularity;s[5]=transform;if(CompanderValidate(s.data(),s.size()))e.set(s);}
void CompanderSetLegacyCurve(void* p,void* owner,const double* freq,const double* coefficient){if(!p||!owner||!freq||!coefficient)return;auto& e=*static_cast<Engine*>(p);auto s=e.settings;for(unsigned i=0;i<7;++i){s[6+i]=pack(float(freq[i]));s[13+i]=pack(float(coefficient[i]));}if(CompanderValidate(s.data(),s.size()))e.set(s);}
#ifdef DSP_FUSION_TEST_ALLOCATIONS
void CompanderAllocationFailureForTest(int after){failAllocation=after;}
int CompanderQueuedForTest(void* p){return p&&static_cast<Engine*>(p)->pendingReady;}
#endif
}
