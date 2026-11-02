#include "Timbre.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <limits>
#include <memory>
#include <new>
extern "C" {
#include "../../app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/jdsp_header.h"
void VTInit(VacuumTube*,double);
void VTProcess(VacuumTube*,float*,float*,float*,float*,size_t);
}
#undef min
#undef max
namespace {
using Settings=std::array<int32_t,32>;
constexpr Settings defaults{1,0,0,0,1000,1000,1000,1000,1000,0,600,1900,4400,8000,12000,0,0,0,50,0,1000,0,0,0,1000,0,2,0,0,0,0,0};
constexpr double pi=3.14159265358979323846;
constexpr unsigned ringSize=256,maxFir=125,firLatency=31;
double clean(double x){return std::isfinite(x)?x:0.;}
float asFloat(double x){return std::isfinite(x)&&std::abs(x)<=std::numeric_limits<float>::max()?float(x):0.f;}
int32_t db(double x){return x>0?int32_t(std::clamp(std::lround(2000*std::log10(x)),-20000l,20000l)):INT32_MIN;}
int autoFactor(int fs){return fs>=65000?1:fs>=30000?2:fs>=20000?3:fs>=14000?4:5;}
Settings effective(Settings s){
 s[27]=0;
 if(!s[1])return defaults;
 if(!s[2])for(unsigned i=3;i<=8;++i)s[i]=defaults[i];
 if(!s[9])for(unsigned i=10;i<=18;++i)s[i]=defaults[i];
 if(!s[17])s[18]=defaults[18];
 if(!s[19])s[20]=1000;if(!s[21])s[22]=0;if(!s[23])s[24]=1000;if(!s[25])s[26]=2;
 s[2]=false;for(unsigned i=3;i<=8;++i)if(s[i]!=defaults[i])s[2]=true;
 s[9]=false;for(unsigned i=10;i<=18;++i)if(s[i]!=defaults[i])s[9]=true;
 s[19]=s[20]!=1000;s[21]=s[22]!=0;s[23]=s[24]!=1000;
 s[1]=s[2]||s[9]||s[19]||s[21]||s[23]||s[25];return s;
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
// Five matched downsampling lanes: one linear recombination and four squared
// residuals. Their phase and FIR delay are identical before dry/wet mixing.
struct Fir {
 unsigned factor=2,length=63,position=0,inputPosition=0;
 std::array<double,maxFir> taps{};
 std::array<std::array<double,32>,2> input{};
 std::array<std::array<std::array<double,maxFir>,2>,5> output{};
 void configure(unsigned f){
  factor=f;length=31*f+1;const double center=(length-1)*.5,cutoff=.45/f;double sum=0;
  for(unsigned i=0;i<length;++i){const double t=double(i)-center;
   const double sinc=t==0?2*cutoff:std::sin(2*pi*cutoff*t)/(pi*t);
   const double w=.42-.5*std::cos(2*pi*i/(length-1))+.08*std::cos(4*pi*i/(length-1));taps[i]=sinc*w;sum+=taps[i];}
  for(unsigned i=0;i<length;++i)taps[i]/=sum;
 }
 void inputFrame(double l,double r){input[0][inputPosition]=l;input[1][inputPosition]=r;}
 double up(unsigned c,unsigned phase)const{double sum=0;unsigned age=0;
  for(unsigned j=phase;j<length;j+=factor,++age)sum+=taps[j]*input[c][(inputPosition+32-age)%32];return sum*factor;}
 void put(unsigned lane,unsigned c,double x){output[lane][c][position]=x;}
 double down(unsigned lane,unsigned c)const{double sum=0;for(unsigned j=0;j<length;++j)sum+=taps[j]*output[lane][c][(position+length-j)%length];return sum;}
 void advance(){position=(position+1)%length;}
 void nextInput(){inputPosition=(inputPosition+1)%32;}
};
struct Pole {
 double previous=0,state=0;
 double low(double x,double pole){const double y=(1-pole)*.5*(x+previous)+pole*state;previous=x;state=y;return y;}
 double high(double x,double pole){return x-low(x,pole);}
};
struct Frame {std::array<double,2> normal{},effect{},reference{};std::array<std::array<double,2>,4> parts{};};
struct Kernel {
 Settings settings=defaults;
 unsigned rate=48000,factor=2,delay=0;bool legacy=true,firOn=false;
 VacuumTube frozen{};
 std::array<SixBandsCrossover,2> crossover{};
 std::array<samplerateTool,2> up{};
 std::array<std::array<samplerateTool,2>,5> down{};
 Fir fir;
 Ring raw;
 std::array<double,5> cuts{};
 std::array<double,4> amounts{1,1,1,1};
 double drive=1,amount=1,compensation=1,mix=1,out=1,hpHz=0,lpHz=0,dcHz=0;
 double hpPole=0,lpPole=0,dcPole=0;unsigned compPercent=1000;
 std::array<std::array<Pole,2>,4> hp{},lp{},dc{};
 uint64_t frames=0;unsigned warmRequired=0;
 bool warmReady()const{return frames>=warmRequired;}
 void configure(const Settings& s,unsigned fs){
  settings=s;rate=fs;legacy=!effective(s)[1];factor=autoFactor(fs);firOn=!legacy&&s[1]&&s[25];if(firOn)factor=s[26];delay=firOn?firLatency:0;
  VTInit(&frozen,fs);
  const bool core=s[1]&&s[2],tone=s[1]&&s[9];
  drive=core?std::pow(10.,s[3]/200.):1.;amount=core?s[4]/1000.:1.;for(unsigned i=0;i<4;++i)amounts[i]=core?s[5+i]/1000.:1.;
  compPercent=s[1]&&s[23]?s[24]:1000;compensation=std::pow(drive,-double(compPercent)/1000.);
  mix=s[1]&&s[19]?s[20]/1000.:1.;out=s[1]&&s[21]?std::pow(10.,s[22]/200.):1.;
  for(unsigned i=0;i<5;++i)cuts[i]=tone?s[10+i]:std::array<double,5>{300,950,2200,4000,6000}[i]*autoFactor(fs);
  // Scale the complete ordered set together when the host band is narrower.
  // This preserves order/ratios and keeps every cookbook section below Nyquist.
  const double scale=legacy?1.:std::min(1.,rate*.45/cuts[4]);for(auto& f:cuts)f*=scale;
  for(unsigned c=0;c<2;++c){init6BandsCrossover(&crossover[c],double(rate)*factor,cuts[0],cuts[1],cuts[2],cuts[3],cuts[4]);
   if(factor>1){oversample_makeSmp(&up[c],factor);for(auto& lane:down)oversample_makeSmp(&lane[c],factor);}}
  if(firOn)fir.configure(factor);
  hpHz=tone&&s[15]?std::min(double(s[15]),rate*.45):0;lpHz=tone&&s[16]?std::min(double(s[16]),rate*.45):0;dcHz=tone&&s[17]?s[18]/10.:0;
  auto pole=[&](double hz){const double k=std::tan(pi*std::clamp(hz,.1,double(rate)*factor*.45)/(rate*factor));return (1-k)/(1+k);};
  hpPole=pole(hpHz);lpPole=pole(lpHz);dcPole=pole(dcHz);warmRequired=rate/2+delay;
 }
 Frame process(double l,double r,bool partsNeeded=false){
  const std::array<double,2> input{clean(l),clean(r)};Frame f;
  raw.push(input[0],input[1]);
  std::array<double,2> legacyOutput{};
  if(legacy){float il=asFloat(input[0]),ir=asFloat(input[1]),ol,orr;VTProcess(&frozen,&il,&ir,&ol,&orr,1);legacyOutput={ol,orr};
   if(!partsNeeded){f.normal=legacyOutput;f.effect=f.normal;f.reference=input;raw.advance();++frames;return f;}}
  std::array<std::array<float,5>,2> interpolated{};
  if(firOn)fir.inputFrame(input[0]*drive,input[1]*drive);
  else if(factor>1)for(unsigned c=0;c<2;++c)oversample_stepupSmp(&up[c],asFloat(input[c]*drive),interpolated[c].data());
  std::array<std::array<std::array<float,5>,2>,5> lanes{};
  std::array<std::array<double,2>,5> result{};
  for(unsigned p=0;p<factor;++p){
   for(unsigned c=0;c<2;++c){const double x=firOn?fir.up(c,p):factor>1?interpolated[c][p]:input[c]*drive;double bands[6];
    process6BandsCrossover(&crossover[c],asFloat(std::clamp(x,-1e6,1e6)),bands,bands+1,bands+2,bands+3,bands+4,bands+5);
    bands[1]=-bands[1];bands[3]=-bands[3];bands[5]=-bands[5];double linear=0;for(double b:bands)linear+=b;
    const double base=linear*compensation;
    if(firOn)fir.put(0,c,base);else lanes[0][c][p]=asFloat(base);
    for(unsigned band=0;band<4;++band){double residual=bands[band+1]*bands[band+1]*(factor>1?.2:.25)*amount*amounts[band]*compensation;
     const double cutDc=dc[band][c].high(residual,dcPole);if(dcHz>0)residual=cutDc;
     const double cutHp=hp[band][c].high(residual,hpPole);if(hpHz>0)residual=cutHp;
     const double cutLp=lp[band][c].low(residual,lpPole);if(lpHz>0)residual=cutLp;
     if(firOn)fir.put(band+1,c,residual);else lanes[band+1][c][p]=asFloat(residual);
    }
   }
   if(firOn){if(p==0)for(unsigned lane=0;lane<5;++lane)for(unsigned c=0;c<2;++c)result[lane][c]=fir.down(lane,c);fir.advance();}
  }
  if(firOn)fir.nextInput();else for(unsigned lane=0;lane<5;++lane)for(unsigned c=0;c<2;++c)result[lane][c]=factor>1?oversample_stepdownSmpFloat(&down[lane][c],lanes[lane][c].data()):lanes[lane][c][0];
  for(unsigned c=0;c<2;++c){double residual=0;for(unsigned band=0;band<4;++band){f.parts[band][c]=clean(result[band+1][c]*out);residual+=result[band+1][c];}
   f.reference[c]=clean(result[0][c]*out);f.normal[c]=clean((result[0][c]+residual*mix)*out);f.effect[c]=f.normal[c];}
  if(legacy){f.normal=legacyOutput;f.effect=legacyOutput;}raw.advance();++frames;return f;
 }
};
std::unique_ptr<Kernel> prepare(const Settings& s,unsigned fs){
#ifdef DSP_FUSION_TEST_ALLOCATIONS
 if(failAllocation>=0){if(failAllocation==0){failAllocation=-1;return {};}--failAllocation;}
#endif
 auto k=std::unique_ptr<Kernel>(new(std::nothrow) Kernel);if(k)k->configure(s,fs);return k;
}
struct Align {
 Ring normal,effect,reference;std::array<Ring,4> parts;
 Frame process(const Frame& f,unsigned extra){Frame o;normal.push(f.normal[0],f.normal[1]);effect.push(f.effect[0],f.effect[1]);reference.push(f.reference[0],f.reference[1]);
  for(unsigned b=0;b<4;++b)parts[b].push(f.parts[b][0],f.parts[b][1]);
  for(unsigned c=0;c<2;++c){o.normal[c]=normal.read(c,extra);o.effect[c]=effect.read(c,extra);o.reference[c]=reference.read(c,extra);for(unsigned b=0;b<4;++b)o.parts[b][c]=parts[b].read(c,extra);}
  normal.advance();effect.advance();reference.advance();for(auto& p:parts)p.advance();return o;}
};
struct Engine {
 Settings settings=defaults;unsigned rate=48000,hostRate=48000;double savedLegacyDrive=0;
 bool enabled=false,ready=false,processed=false,pendingReady=false;
 std::unique_ptr<Kernel> main,old,pending,a,b;Align mainAlign,oldAlign,pendingAlign,aAlign,bAlign;Ring raw;
 unsigned fade=0,fadeLength=960,routeFade=0,routeLength=240,lastCommon=0;
 std::array<double,2> last{},routeFrom{};
 int mode=0,epoch=0,stage=0,revision=2,soloGainTenths=0;bool match=false;
 double soloGain=1;
 uint64_t warmFrames=0,warmRequired=24000,measureFrames=0;
 double energyIn=0,energyA=0,energyB=0,gainA=1,gainB=1,gainRaw=1;
 std::array<double,4> peaks{},squares{},partSquares{};std::array<double,2> dcMean{};int32_t clips=0;
 double peakDecay=std::exp(-1./(.6*48000)),meterAlpha=1-std::exp(-1./(.1*48000));
 void bump(){revision=revision>=INT32_MAX-2?2:revision+2;}
 void clearMeters(){peaks={};squares={};partSquares={};dcMean={};clips=0;}
 void clearTools(){a.reset();b.reset();aAlign={};bAlign={};mode=epoch=stage=soloGainTenths=0;soloGain=1;match=false;warmFrames=measureFrames=0;energyIn=energyA=energyB=0;gainA=gainB=gainRaw=1;bump();}
 void routeChange(){if(processed){routeFrom=last;routeFade=routeLength;}}
 void clearControl(){const bool audible=mode!=0;clearTools();clearMeters();if(audible)routeChange();}
 bool set(const Settings& s){
  const Kernel* candidate=pendingReady?pending.get():main.get();
  if(ready&&candidate&&settings==s&&candidate->rate==rate)return true;
  const bool changed=!audioEqual(settings,s),rebuild=changed||!ready||!candidate||candidate->rate!=rate;
  if(rebuild){auto next=prepare(s,rate);if(!next)return false;
   if(enabled&&processed&&ready&&main&&main->rate==rate){pending=std::move(next);pendingAlign={};pendingReady=true;}
   else {main=std::move(next);old.reset();pending.reset();pendingReady=false;mainAlign={};fade=0;}}
  else if(pendingReady)pending->settings=s;else main->settings=s;
  settings=s;ready=true;if(changed){const bool audible=mode!=0;clearTools();if(audible)routeChange();}
  if(!s[1]||!s[27])clearMeters();bump();return true;
 }
 void refresh(unsigned fs,unsigned host){
  const bool hostChanged=hostRate!=host;hostRate=host;if(fs==rate&&ready&&main&&main->rate==rate){if(hostChanged)clearControl();return;}
  rate=fs;fadeLength=std::max(1u,fs/50);routeLength=std::max(1u,fs/200);peakDecay=std::exp(-1./(.6*fs));meterAlpha=1-std::exp(-1./(.1*fs));warmRequired=fs/2;
  auto next=prepare(settings,rate);if(next){main=std::move(next);ready=true;}else ready=false;
  old.reset();pending.reset();pendingReady=false;fade=routeFade=0;processed=false;mainAlign={};oldAlign={};pendingAlign={};raw={};last={};clearTools();clearMeters();
 }
 void enable(bool on){
  if(enabled==on)return;
  // On disable, latest selected settings are adopted on the control thread.
  // ClearTools alone deliberately never promotes an un-warmed candidate.
  if(!on){if(pendingReady){main=std::move(pending);mainAlign=pendingAlign;pendingAlign={};pendingReady=false;}old.reset();fade=routeFade=0;processed=false;clearTools();clearMeters();}
  else {auto next=prepare(settings,rate);if(next){main=std::move(next);ready=true;}else ready=false;mainAlign={};raw={};processed=false;last={};}
  enabled=on;bump();
 }
 bool tools(const int32_t* v){
  if(!enabled||!settings[1]||!ready){clearTools();return v[0]==0;}
  if(v[0]==0){if(mode!=0)routeChange();clearTools();return true;}
  bool snapshotsChanged=false;
  if(v[0]>=4&&v[0]<=6){Settings sa,sb;std::copy_n(v+4,32,sa.begin());std::copy_n(v+36,32,sb.begin());
   if(!a||!b||!audioEqual(a->settings,sa)||!audioEqual(b->settings,sb)||a->rate!=rate||b->rate!=rate||epoch!=v[2]){auto na=prepare(sa,rate),nb=prepare(sb,rate);if(!na||!nb)return false;
    a=std::move(na);b=std::move(nb);aAlign={};bAlign={};snapshotsChanged=true;stage=0;gainA=gainB=gainRaw=1;warmFrames=measureFrames=0;}}
  const int nextSoloGain=v[0]>=7?v[3]:0;
  if(!snapshotsChanged&&mode==v[0]&&match==(v[1]!=0)&&epoch==v[2]&&soloGainTenths==nextSoloGain)return true;
  const bool measurement=v[0]==6&&(snapshotsChanged||mode!=6||epoch!=v[2]);
  const bool audible=mode!=v[0]||epoch!=v[2]||((mode==1||mode==4||mode==5)&&stage==3&&match!=(v[1]!=0))||(mode>=7&&soloGainTenths!=nextSoloGain);
  mode=v[0];match=v[1]!=0;epoch=v[2];soloGainTenths=nextSoloGain;soloGain=std::pow(10.,nextSoloGain/200.);if(audible)routeChange();
  if(measurement){stage=1;warmFrames=measureFrames=0;energyIn=energyA=energyB=0;gainA=gainB=gainRaw=1;warmRequired=rate/2+commonDelay();}bump();return true;
 }
 unsigned commonDelay()const{unsigned d=main?main->delay:0;if(fade&&old)d=std::max(d,old->delay);if(pendingReady&&pending)d=std::max(d,pending->delay);if(mode&&epoch>0&&a&&b)d=std::max({d,a->delay,b->delay});return d;}
 void process(float* l,float* r,size_t n){
  if(!enabled||!ready||!main)return;
  for(size_t i=0;i<n;++i){const std::array<double,2> input{clean(l[i]),clean(r[i])};
   if(!fade&&pendingReady&&pending->warmReady()){old.swap(main);main.swap(pending);oldAlign=mainAlign;mainAlign=pendingAlign;pendingAlign={};pendingReady=false;fade=fadeLength;}
   const unsigned common=commonDelay();if(common!=lastCommon){routeChange();lastCommon=common;}
   raw.push(input[0],input[1]);const std::array<double,2> original{raw.read(0,common),raw.read(1,common)};raw.advance();
   const auto current=mainAlign.process(main->process(input[0],input[1],settings[1]&&(settings[27]||mode>=7)),common-main->delay);auto result=current.normal;
   if(fade&&old){const auto previous=oldAlign.process(old->process(input[0],input[1]),common-old->delay);const double blend=1-double(fade)/fadeLength;for(unsigned c=0;c<2;++c)result[c]=previous.normal[c]+blend*(result[c]-previous.normal[c]);--fade;}
   if(pendingReady)pendingAlign.process(pending->process(input[0],input[1]),common-pending->delay);
   Frame fa{},fb{};if(mode&&a&&b&&epoch>0){fa=aAlign.process(a->process(input[0],input[1]),common-a->delay);fb=bAlign.process(b->process(input[0],input[1]),common-b->delay);}
   if(mode==6&&a&&b){if(stage==1){if(++warmFrames>=warmRequired){stage=2;measureFrames=0;}}
    else if(stage==2){for(unsigned c=0;c<2;++c){energyIn+=original[c]*original[c];energyA+=fa.normal[c]*fa.normal[c];energyB+=fb.normal[c]*fb.normal[c];}++measureFrames;
     if(measureFrames>=uint64_t(rate)*2){if(energyIn/(measureFrames*2)<1e-8||energyA/(measureFrames*2)<1e-12||energyB/(measureFrames*2)<1e-12){stage=4;gainA=gainB=gainRaw=1;}
      else {const double target=std::min({energyIn,energyA,energyB});gainA=std::sqrt(target/energyA);gainB=std::sqrt(target/energyB);gainRaw=std::sqrt(target/energyIn);stage=3;}}}}
   if(mode==1){result=original;if(match&&stage==3)for(auto& x:result)x*=gainRaw;}
   else if(mode==2)result=current.effect;
   else if(mode==3)for(unsigned c=0;c<2;++c)result[c]=current.effect[c]-original[c];
   else if(mode==4&&a){result=fa.normal;if(match&&stage==3)for(auto& x:result)x*=gainA;}
   else if(mode==5&&b){result=fb.normal;if(match&&stage==3)for(auto& x:result)x*=gainB;}
   else if(mode>=7&&mode<=10)result=current.parts[mode-7];
   // Sum the same float lanes exposed by the four existing Solo routes.
   else if(mode==11){result={};for(const auto& part:current.parts)for(unsigned c=0;c<2;++c)result[c]+=asFloat(part[c]);}
   if(mode>=7&&mode<=11)for(auto& x:result)x*=soloGain;
   if(routeFade){const double blend=1-double(routeFade)/routeLength;for(unsigned c=0;c<2;++c)result[c]=routeFrom[c]+blend*(result[c]-routeFrom[c]);--routeFade;}
   l[i]=asFloat(result[0]);r[i]=asFloat(result[1]);last={l[i],r[i]};processed=true;
   if(settings[1]&&settings[27]){for(unsigned c=0;c<2;++c){const double values[2]={input[c],last[c]};for(unsigned p=0;p<2;++p){const unsigned k=p*2+c;peaks[k]=std::max(std::abs(values[p]),peaks[k]*peakDecay);squares[k]+=(values[p]*values[p]-squares[k])*meterAlpha;}dcMean[c]+=(last[c]-dcMean[c])*meterAlpha;}
    for(unsigned b=0;b<4;++b){double energy=0;for(double x:current.parts[b])energy+=x*x*.5;partSquares[b]+=(energy-partSquares[b])*meterAlpha;}
    if((std::abs(last[0])>1||std::abs(last[1])>1)&&clips<INT32_MAX)++clips;}
  }bump();
 }
 void status(int32_t* v)const{
  std::fill_n(v,48,0);v[0]=1;v[1]=revision;v[2]=rate;v[3]=enabled;v[4]=settings[1];v[5]=mode;v[6]=match;v[7]=stage;
  v[8]=int(measureFrames*1000/rate);v[9]=std::max(-18000,db(gainA));v[10]=std::max(-18000,db(gainB));v[11]=epoch;v[12]=settings[1]&&settings[27];
  const Kernel* selected=ready?(mode==4&&a?a.get():mode==5&&b?b.get():main.get()):nullptr;
  v[13]=selected?selected->factor:1;v[14]=ready?commonDelay():0;v[15]=selected&&!selected->legacy&&selected->settings[1]&&selected->settings[2]?selected->settings[3]:0;
  for(unsigned i=16;i<24;++i)v[i]=INT32_MIN;v[27]=v[28]=INT32_MIN;for(unsigned i=40;i<44;++i)v[i]=INT32_MIN;
  if(enabled&&ready&&settings[1]&&settings[27]){for(unsigned i=0;i<4;++i){v[16+i]=db(peaks[i]);v[20+i]=db(std::sqrt(std::max(0.,squares[i])));v[40+i]=db(std::sqrt(std::max(0.,partSquares[i])));}for(unsigned c=0;c<2;++c)v[27+c]=int32_t(std::clamp(dcMean[c]*1e6,-1e9,1e9));}
  v[24]=clips;v[25]=ready;v[26]=hostRate;v[29]=int(warmFrames*1000/rate);v[30]=int(warmRequired*1000/rate);v[31]=2000;
  v[46]=enabled&&ready&&mode>=7&&mode<=11?soloGainTenths:0;
  if(selected){for(unsigned i=0;i<5;++i)v[32+i]=int(std::lround(selected->cuts[i]));v[37]=int(std::lround(selected->hpHz));v[38]=int(std::lround(selected->lpHz));v[39]=int(std::lround(selected->dcHz*10));v[44]=selected->compPercent;v[45]=!selected->legacy;}
 }
};
unsigned validFs(float fs){return std::clamp(unsigned(std::isfinite(fs)&&fs>0?fs:48000),8000u,768000u);}
}
extern "C" {
int TimbreValidate(const int32_t* v,size_t n){
 if(!v||n!=32)return 0;
 constexpr Settings lo{1,0,0,-30,0,0,0,0,0,0,50,50,50,50,50,0,0,0,10,0,0,0,-240,0,0,0,2,0,0,0,0,0};
 constexpr Settings hi{1,1,1,120,10000,2000,2000,2000,2000,1,20000,20000,20000,20000,20000,1000,20000,1,300,1,1000,1,0,1,1000,1,4,1,0,0,0,0};
 for(unsigned i=0;i<32;++i)if(v[i]<lo[i]||v[i]>hi[i])return 0;
 for(unsigned i=11;i<=14;++i)if(v[i]-v[i-1]<50)return 0;
 if(v[15]>0&&v[15]<20)return 0;if(v[16]>0&&v[16]<1000)return 0;if(v[15]&&v[16]&&v[15]>=v[16])return 0;
 return v[26]==2||v[26]==4;
}
int TimbreToolsValidate(const int32_t* v,size_t n){return v&&n==68&&v[0]>=0&&v[0]<=11&&v[1]>=0&&v[1]<=1&&v[2]>=0&&v[3]>=0&&v[3]<=180&&TimbreValidate(v+4,32)&&TimbreValidate(v+36,32)&&(!(v[0]>=4&&v[0]<=6)||v[2]>0);}
void* TimbreCreate(){return new(std::nothrow) Engine;}
void TimbreDestroy(void* p){delete static_cast<Engine*>(p);}
int TimbreSet(void* p,void* owner,const int32_t* v,size_t n){if(!p||!owner||!TimbreValidate(v,n))return 0;auto& e=*static_cast<Engine*>(p);const auto& d=*static_cast<JamesDSPLib*>(owner);if(e.rate!=validFs(d.fs)||e.hostRate!=validFs(d.trueSampleRate))e.refresh(validFs(d.fs),validFs(d.trueSampleRate));Settings s;std::copy_n(v,32,s.begin());return e.set(s);}
int TimbreTools(void* p,void* owner,const int32_t* v,size_t n){return p&&owner&&TimbreToolsValidate(v,n)&&static_cast<Engine*>(p)->tools(v);}
void TimbreClear(void* p){if(p)static_cast<Engine*>(p)->clearControl();}
void TimbreStatus(const void* p,int32_t* v){if(!v)return;if(p)static_cast<const Engine*>(p)->status(v);else{std::fill_n(v,48,0);v[0]=1;v[2]=v[26]=48000;v[13]=2;v[31]=2000;for(unsigned i=16;i<24;++i)v[i]=INT32_MIN;v[27]=v[28]=INT32_MIN;for(unsigned i=40;i<44;++i)v[i]=INT32_MIN;}}
void TimbreProcess(void* p,void* owner,size_t n){if(p&&owner){auto& d=*static_cast<JamesDSPLib*>(owner);static_cast<Engine*>(p)->process(d.tmpBuffer[0],d.tmpBuffer[1],n);}}
void TimbreRefresh(void* p,void* owner){if(p&&owner){const auto& d=*static_cast<JamesDSPLib*>(owner);static_cast<Engine*>(p)->refresh(validFs(d.fs),validFs(d.trueSampleRate));}}
void TimbreEnable(void* p,void* owner,int enabled){if(p&&owner){TimbreRefresh(p,owner);static_cast<Engine*>(p)->enable(enabled!=0);}}
void TimbreSetLegacyDrive(void* p,void*,double drive){if(p&&std::isfinite(drive))static_cast<Engine*>(p)->savedLegacyDrive=std::clamp(drive,-3.,12.);}
#ifdef DSP_FUSION_TEST_ALLOCATIONS
void TimbreAllocationFailureForTest(int after){failAllocation=after;}
int TimbreQueuedForTest(void* p){return p&&static_cast<Engine*>(p)->pendingReady;}
void TimbreFirImpulseForTest(int factor,float* output,size_t n){if(!output||(factor!=2&&factor!=4))return;Fir f;f.configure(factor);for(size_t i=0;i<n;++i){f.inputFrame(i==0?1:0,0);for(int p=0;p<factor;++p){f.put(0,0,f.up(0,p));if(p==0)output[i]=asFloat(f.down(0,0));f.advance();}f.nextInput();}}
#endif
}
