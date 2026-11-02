#include "DynamicTuning.h"
#include "../utils/PolesFilter.h"
#include "../utils/Biquad.h"
#include <algorithm>
#include <cmath>
#include <complex>
#include <limits>
#include <new>

namespace dynamictuning {
bool valid(const int32_t* v,size_t n){
 if(!v||n!=kWords)return false;
 for(unsigned i=0;i<kWords;++i)if(v[i]<minimum[i]||v[i]>maximum[i])return false;
 return (v[15]==0||v[15]>=10)&&(v[16]==0||v[16]>=100)&&
  (!v[15]||!v[16]||v[15]<v[16])&&v[19]<v[20];
}
bool validTools(const int32_t* v,size_t n){
 return v&&n==kToolsWords&&v[0]>=0&&v[0]<=8&&v[1]>=0&&v[1]<=1&&v[2]>=0&&v[3]==0&&
  valid(v+4,kWords)&&valid(v+40,kWords)&&((v[0]<4||v[0]>6)||v[2]>0);
}
#ifdef DSP_FUSION_TEST_ALLOCATIONS
int allocationFailure=-1;
void failAllocationForTest(int after){allocationFailure=after;}
#endif
}
namespace {
using S=dynamictuning::Settings;
using C=std::complex<double>;
constexpr double pi=3.14159265358979323846;
constexpr std::array<std::array<int,6>,6> bases{{
 {140,6200,40,60,10,80},{180,5800,55,80,10,70},{300,5600,60,105,10,50},
 {600,5400,60,105,10,20},{100,5600,40,80,50,50},{1200,6200,40,80,0,20}}};
double clean(double x){return std::isfinite(x)?x:0.;}
float sampleFloat(double x){return std::isfinite(x)&&std::abs(x)<=std::numeric_limits<float>::max()?float(x):0.f;}
int32_t db(double x){return x>0?int32_t(std::clamp(std::lround(2000.*std::log10(x)),-20000l,20000l)):INT32_MIN;}
int graphDb(C x){return std::abs(x)>1e-10?std::clamp(db(std::abs(x)),-18000,6000):-18000;}
int phase(C x){return int(std::lround(std::arg(x)*180000./pi));}
int safeHz(double value,unsigned fs){return int(std::clamp(std::lround(value),10l,long(std::floor(fs*.45))));}
S effective(const S& s){
 S v=s;v[30]=v[31]=0;
 if(!v[1]){const int base=v[2],strength=v[3];v=dynamictuning::defaults;v[2]=base;v[3]=strength;return v;}
 if(!v[4])for(unsigned i=5;i<=9;++i)v[i]=0;
 if(v[2]==4){for(unsigned i=5;i<=7;++i)v[i]=0;}else v[8]=v[9]=0;
 if(!v[10])for(unsigned i=11;i<=16;++i)v[i]=0;
 if(v[2]==4){v[11]=v[12]=v[13]=0;}else v[14]=0;
 if(!v[17]||v[23]==0){v[17]=0;for(unsigned i=18;i<=25;++i)v[i]=dynamictuning::defaults[i];}
 if(!v[26])v[27]=1000;
 if(!v[28])v[29]=0;
 v[4]=false;for(unsigned i=5;i<=9;++i)if(v[i])v[4]=true;
 v[10]=false;for(unsigned i=11;i<=16;++i)if(v[i])v[10]=true;
 v[26]=v[27]!=1000;v[28]=v[29]!=0;v[1]=v[4]||v[10]||v[17]||v[26]||v[28];
 return v;
}
bool audioEqual(const S& a,const S& b){return effective(a)==effective(b);}
struct StereoRing {
 std::array<std::array<double,8>,2> data{};unsigned position=0;
 void push(double l,double r){data[0][position]=l;data[1][position]=r;}
 double read(unsigned c,unsigned delay)const{return data[c][(position+8-delay)%8];}
 void advance(){position=(position+1)%8;}
};
// Cookbook two-pole filters operate only on enhancement and detector branches.
struct Filter {
 double b0=1,b1=0,b2=0,a1=0,a2=0,x1=0,x2=0,y1=0,y2=0;
 void configure(int hz,unsigned fs,bool high){
  const double w=2*pi*hz/fs,co=std::cos(w),alpha=std::sin(w)/std::sqrt(2.),a0=1+alpha;
  b0=(high?1+co:1-co)/(2*a0);b1=(high?-(1+co):1-co)/a0;b2=b0;
  a1=-2*co/a0;a2=(1-alpha)/a0;x1=x2=y1=y2=0;
 }
 double process(double x){const double y=b0*x+b1*x1+b2*x2-a1*y1-a2*y2;x2=x1;x1=x;y2=y1;y1=y;return y;}
 C response(C z)const{return (b0+b1*z+b2*z*z)/(1.+a1*z+a2*z*z);}
};
struct Frame {std::array<double,2> normal{},original{},low{},detector{};};
struct Kernel {
 S settings=dynamictuning::defaults;
 unsigned rate=44100,delay=3,warmRequired=22050;uint64_t frames=0;
 bool deep=false,neutral=true,adaptive=false,rms=true;
 int xHz=140,xUpper=6200,yLo=40,yHi=60,deepHz=55,hpHz=0,lpHz=0,detectorHP=20,detectorLP=250;
 float bass=1,lowGain=.8f,highGain=.1f,inject=1,q=.5f;
 double mix=1,out=1,threshold=-18,knee=6,maxReduction=6,attack=0,release=0,rmsAlpha=0,rmsSquare=0,reduction=0,detectorLevel=0;
 std::array<Filter,2> hp,lp,dhp,dlp;
 PolesFilter fx,fy;Biquad deepFilter;StereoRing dry;
 void configure(const S& s,unsigned fs){
  // PolesFilter reads in[2] before shifting its three input-history samples.
  // Its direct reconstruction is z^-3; Deep's direct path is undelayed.
  settings=s;rate=fs;deep=s[2]==4;delay=deep?0:3;const auto b=bases[s[2]];const S p=effective(s);neutral=!p[1];
  bass=float((100+20*s[3])/100.);lowGain=b[5]/100.f;highGain=b[4]/100.f;
  const float qPeak=float(std::min(1600.,((double(bass)-1.)/20.)*1600.));q=qPeak/666.f+.5f;
  const bool core=s[1]&&s[4],tone=s[1]&&s[10];
  if(core){if(deep){inject=std::max(0.f,1+s[8]/1000.f);q=std::clamp(q+s[9]/100.f,.1f,4.f);}
   else {bass*=float(std::pow(10.,s[5]/200.));lowGain=std::max(0.f,lowGain+s[6]/1000.f);highGain=std::max(0.f,highGain+s[7]/1000.f);}}
  xHz=safeHz(b[0]+(tone&&!deep?s[11]:0),fs);xUpper=safeHz(b[1],fs);
  yLo=safeHz(b[2]+(tone&&!deep?s[12]:0),fs);yHi=safeHz(b[3]+(tone&&!deep?s[13]:0),fs);
  // Saved offsets remain intact across presets, while actual cutoffs stay ordered.
  yLo=std::min(yLo,int(fs*.45)-1);yHi=std::max(yLo+1,yHi);
  deepHz=safeHz(55+(tone&&deep?s[14]:0),fs);
  hpHz=tone&&s[15]?safeHz(s[15],fs):0;lpHz=tone&&s[16]?safeHz(s[16],fs):0;
  if(hpHz&&lpHz&&lpHz<=hpHz)lpHz=std::min(int(fs*.45),hpHz+1);
  detectorHP=safeHz(s[19],fs);detectorLP=std::max(detectorHP+1,safeHz(s[20],fs));
  adaptive=s[1]&&s[17]&&s[23]!=0;rms=s[18]!=0;threshold=s[21]/10.;knee=s[22]/10.;maxReduction=s[23]/10.;
  attack=std::exp(-1./(fs*s[24]/10000.));release=std::exp(-1./(fs*s[25]/1000.));rmsAlpha=1-std::exp(-1./(.020*fs));
  mix=s[1]&&s[26]?s[27]/1000.:1.;out=s[1]&&s[28]?std::pow(10.,s[29]/200.):1.;
  fx.SetSamplingRate(fs);fx.SetPassFilter(xHz,xUpper);fy.SetSamplingRate(fs);fy.SetPassFilter(yLo,yHi);
  deepFilter.SetLowPassParameter(float(deepHz),fs,q);
  for(unsigned c=0;c<2;++c){if(hpHz)hp[c].configure(hpHz,fs,true);if(lpHz)lp[c].configure(lpHz,fs,false);dhp[c].configure(detectorHP,fs,true);dlp[c].configure(detectorLP,fs,false);}
  frames=0;rmsSquare=reduction=detectorLevel=0;dry={};warmRequired=std::max(fs/2,adaptive?unsigned(uint64_t(fs)*s[25]*3/1000):0u);
 }
 void reset(){const auto s=settings;configure(s,rate);}
 void retune(const S& s){settings=s;}
 bool warmReady()const{return frames>=warmRequired;}
 Frame process(double l,double r){
  const float input[2]={sampleFloat(l),sampleFloat(r)};Frame f;dry.push(l,r);for(unsigned c=0;c<2;++c)f.original[c]=dry.read(c,delay);dry.advance();
  double detectorPower=0,detectorPeak=0;
  for(unsigned c=0;c<2;++c){f.detector[c]=dlp[c].process(dhp[c].process(input[c]));detectorPower+=f.detector[c]*f.detector[c];detectorPeak=std::max(detectorPeak,std::abs(f.detector[c]));}
  rmsSquare+=(detectorPower*.5-rmsSquare)*rmsAlpha;detectorLevel=rms?std::sqrt(std::max(0.,rmsSquare)):detectorPeak;
  if(adaptive){const double level=detectorLevel>0?20*std::log10(detectorLevel):-300.,over=level-threshold;double wanted;
   if(knee>0&&over>-knee*.5&&over<knee*.5)wanted=(over+knee*.5)*(over+knee*.5)/(2*knee);
   else wanted=std::max(0.,over);wanted=std::min(wanted,maxReduction);
   const double alpha=wanted>reduction?attack:release;reduction=wanted+(reduction-wanted)*alpha;
  }else reduction=0;
  const double recovery=reduction==0?1:std::pow(10.,-reduction/20.);
  if(deep){const float avg=float(deepFilter.ProcessSample(input[0]+input[1]));
   for(unsigned c=0;c<2;++c){double branch=avg;if(!neutral){branch*=inject;if(hpHz)branch=hp[c].process(branch);if(lpHz)branch=lp[c].process(branch);branch*=recovery;}
    f.low[c]=branch;f.normal[c]=neutral?double(input[c]+avg):(input[c]+branch);}
  }else {float x[2][3],y[2][3];fx.DoFilterLeft(input[0],&x[0][0],&x[0][1],&x[0][2]);fx.DoFilterRight(input[1],&x[1][0],&x[1][1],&x[1][2]);
   fy.DoFilterLeft(bass*x[0][0],&y[0][0],&y[0][1],&y[0][2]);fy.DoFilterRight(bass*x[1][0],&y[1][0],&y[1][1],&y[1][2]);
   for(unsigned c=0;c<2;++c){double branch=y[c][2]+highGain*y[c][1]+lowGain*y[c][0];
    if(neutral){f.normal[c]=x[c][1]+y[c][2]+highGain*y[c][1]+lowGain*y[c][0]+x[c][2];}
    else {if(hpHz)branch=hp[c].process(branch);if(lpHz)branch=lp[c].process(branch);branch*=recovery;f.normal[c]=double(x[c][1])+x[c][2]+branch;}
    f.low[c]=branch;}
  }
  if(!neutral)for(unsigned c=0;c<2;++c)f.normal[c]=(f.original[c]+mix*(f.normal[c]-f.original[c]))*out;
  ++frames;return f;
 }
 C poles(int hz,C z)const{const double a=float(float(hz)*float(pi)/float(rate));return std::pow(a/(1.-(1.-a)*z),4);}
 C deepResponse(C z)const{const double w=2*pi*float(deepHz)/rate,co=std::cos(w),a=std::sin(w)/(double(q)+double(q));return ((1-co)/2.+(1-co)*z+(1-co)/2.*z*z)/(1+a-2*co*z+(1-a)*z*z);}
 std::array<C,3> response(double hz)const{
  const C z=std::polar(1.,-2*pi*hz/rate),raw=std::pow(z,int(delay));C low,high;
  if(deep){low=2.*deepResponse(z)*double(inject);high=1.;}
  else {const C xx=poles(xHz,z),yl=poles(yLo,z),yh=poles(yHi,z);low=double(bass)*xx*(double(highGain)*z*z*z+(1.-double(highGain))*yh+(double(lowGain)-1.)*yl);high=z*z*z-xx;}
  if(hpHz)low*=hp[0].response(z);if(lpHz)low*=lp[0].response(z);low*=std::pow(10.,-reduction/20.);
  const C normal=(raw+mix*(high+low-raw))*out;return {normal,raw,low};
 }
};
std::unique_ptr<Kernel> prepare(const S& s,unsigned fs){
#ifdef DSP_FUSION_TEST_ALLOCATIONS
 if(dynamictuning::allocationFailure>=0){if(dynamictuning::allocationFailure--==0)return nullptr;}
#endif
 auto k=std::unique_ptr<Kernel>(new(std::nothrow) Kernel);if(k)k->configure(s,fs);return k;
}
struct Alignment {
 std::array<StereoRing,4> rings;
 Frame process(const Frame& f,unsigned extra){Frame out;const std::array<double,2>* src[4]={&f.normal,&f.original,&f.low,&f.detector};std::array<double,2>* dst[4]={&out.normal,&out.original,&out.low,&out.detector};
  for(unsigned i=0;i<4;++i){rings[i].push((*src[i])[0],(*src[i])[1]);for(unsigned c=0;c<2;++c)(*dst[i])[c]=rings[i].read(c,extra);rings[i].advance();}return out;}
};
}

struct DynamicTuning::Engine {
 S settings=dynamictuning::defaults;unsigned rate=44100;bool enabled=false,ready=false,processed=false,pendingReady=false;
 std::unique_ptr<Kernel> normal,old,pending,a,b;Alignment normalAlign,oldAlign,pendingAlign,aAlign,bAlign;StereoRing rawHistory;
 unsigned fade=0,fadeLength=882,routeFade=0,routeLength=220,lastCommon=0;
 std::array<double,2> last{},routeFrom{};int mode=0,epoch=0,stage=0,revision=2;bool match=false;
 uint64_t warmFrames=0,warmRequired=22050,measureFrames=0;double energyIn=0,energyA=0,energyB=0,gainA=1,gainB=1,gainOriginal=1;
 std::array<double,4> peaks{},squares{};double lowSquare=0;int32_t clips=0;double peakDecay=std::exp(-1./(.6*44100)),meterAlpha=1-std::exp(-1./(.1*44100));
 void bump(){revision=revision>=INT32_MAX-2?2:revision+2;}
 void clearMeters(){peaks={};squares={};clips=0;lowSquare=0;}
 void clearInternalTools(){a.reset();b.reset();aAlign={};bAlign={};mode=epoch=stage=0;match=false;warmFrames=measureFrames=0;energyIn=energyA=energyB=0;gainA=gainB=gainOriginal=1;bump();}
 void routeChange(){if(processed){routeFrom=last;routeFade=routeLength;}}
 void settle(){if(pendingReady){normal=std::move(pending);normalAlign=pendingAlign;pendingAlign={};pendingReady=false;}old.reset();fade=0;processed=false;routeFade=0;lastCommon=normal?normal->delay:0;}
 void clearControl(){const bool audible=mode!=0;clearInternalTools();clearMeters();if(audible)routeChange();}
 void reset(){settle();if(normal)normal->reset();normalAlign={};oldAlign={};rawHistory={};last={};clearInternalTools();clearMeters();}
 bool set(const S& s){
  const Kernel* candidate=pendingReady?pending.get():normal.get();
  if(ready&&candidate&&settings==s&&candidate->rate==rate)return true;
  const bool changed=!audioEqual(settings,s),rebuild=changed||!ready||!candidate||candidate->rate!=rate;
  if(rebuild){auto next=prepare(s,rate);if(!next)return false;
   if(enabled&&processed&&ready&&normal&&normal->rate==rate){pending=std::move(next);pendingAlign={};pendingReady=true;}
   else {normal=std::move(next);old.reset();pending.reset();pendingReady=false;normalAlign={};fade=0;}
  }else if(pendingReady)pending->retune(s);else normal->retune(s);
  settings=s;ready=true;if(changed){const bool audible=mode!=0;clearInternalTools();if(audible)routeChange();}
  if(!settings[1]||!settings[30])clearMeters();bump();return true;
 }
 void setRate(unsigned fs){
  if(fs<8000||fs>768000)return;
  if(fs==rate&&ready&&normal&&normal->rate==rate)return;
  rate=fs;fadeLength=std::max(1u,fs/50);routeLength=std::max(1u,fs/200);peakDecay=std::exp(-1./(.6*fs));meterAlpha=1-std::exp(-1./(.1*fs));warmRequired=fs/2;
  auto next=prepare(settings,rate);if(next){normal=std::move(next);ready=true;}else ready=false;
  old.reset();pending.reset();pendingReady=false;fade=routeFade=0;processed=false;normalAlign={};oldAlign={};aAlign={};bAlign={};rawHistory={};last={};clearInternalTools();clearMeters();
 }
 bool tools(const int32_t* v){
  if(!enabled||!settings[1]||!ready){clearInternalTools();return v[0]==0;}
  if(v[0]==0){if(mode)routeChange();clearInternalTools();return true;}
  bool snapshotsChanged=false;
  if(v[0]>=4&&v[0]<=6){S sa,sb;std::copy_n(v+4,36,sa.begin());std::copy_n(v+40,36,sb.begin());
   if(!a||!b||a->settings!=sa||b->settings!=sb||a->rate!=rate||b->rate!=rate||epoch!=v[2]){
    auto na=prepare(sa,rate),nb=prepare(sb,rate);if(!na||!nb)return false;
    a=std::move(na);b=std::move(nb);aAlign={};bAlign={};snapshotsChanged=true;stage=0;gainA=gainB=gainOriginal=1;warmFrames=measureFrames=0;}
  }
  if(!snapshotsChanged&&mode==v[0]&&match==(v[1]!=0)&&epoch==v[2])return true;
  const bool measurement=v[0]==6&&(snapshotsChanged||mode!=6||epoch!=v[2]);
  const bool audible=mode!=v[0]||epoch!=v[2]||((mode==1||mode==4||mode==5)&&stage==3&&match!=(v[1]!=0));
  mode=v[0];match=v[1]!=0;epoch=v[2];if(audible)routeChange();
  if(measurement){stage=1;warmFrames=measureFrames=0;energyIn=energyA=energyB=0;gainA=gainB=gainOriginal=1;warmRequired=std::max({rate/2,a?a->warmRequired:0u,b?b->warmRequired:0u});}
  bump();return true;
 }
 unsigned commonDelay()const{
  unsigned delay=normal?normal->delay:0;if(fade&&old)delay=std::max(delay,old->delay);if(pendingReady&&pending)delay=std::max(delay,pending->delay);
  if(mode&&epoch>0&&a&&b)delay=std::max({delay,a->delay,b->delay});return delay;
 }
 void process(float* buffer,unsigned count){
  if(!enabled||!ready||!normal)return;
  for(unsigned i=0;i<count;++i){const std::array<double,2> input{clean(buffer[2*i]),clean(buffer[2*i+1])};
   if(!fade&&pendingReady&&pending->warmReady()){old.swap(normal);normal.swap(pending);oldAlign=normalAlign;normalAlign=pendingAlign;pendingAlign={};pendingReady=false;fade=fadeLength;routeChange();}
   const unsigned common=commonDelay();if(common!=lastCommon){routeChange();lastCommon=common;}
   rawHistory.push(input[0],input[1]);std::array<double,2> raw{rawHistory.read(0,common),rawHistory.read(1,common)};rawHistory.advance();
   const Frame current=normalAlign.process(normal->process(input[0],input[1]),common-normal->delay);std::array<double,2> result=current.normal;
   if(fade&&old){const Frame previous=oldAlign.process(old->process(input[0],input[1]),common-old->delay);const double t=1-double(fade)/fadeLength;for(unsigned c=0;c<2;++c)result[c]=previous.normal[c]+t*(result[c]-previous.normal[c]);--fade;}
   if(pendingReady)pendingAlign.process(pending->process(input[0],input[1]),common-pending->delay);
   Frame fa{},fb{};if(mode&&a&&b&&epoch>0){fa=aAlign.process(a->process(input[0],input[1]),common-a->delay);fb=bAlign.process(b->process(input[0],input[1]),common-b->delay);}
   if(mode==6&&a&&b){if(stage==1){if(++warmFrames>=warmRequired){stage=2;measureFrames=0;}}
    else if(stage==2){for(unsigned c=0;c<2;++c){energyIn+=raw[c]*raw[c];energyA+=fa.normal[c]*fa.normal[c];energyB+=fb.normal[c]*fb.normal[c];}++measureFrames;
     if(measureFrames>=uint64_t(rate)*2){if(energyIn/(measureFrames*2)<1e-8||energyA/(measureFrames*2)<1e-12||energyB/(measureFrames*2)<1e-12){stage=4;gainA=gainB=gainOriginal=1;}
      else {const double target=std::min({energyIn,energyA,energyB});gainA=std::sqrt(target/energyA);gainB=std::sqrt(target/energyB);gainOriginal=std::sqrt(target/energyIn);stage=3;}}}}
   if(mode==1){result=raw;if(match&&stage==3)for(auto& x:result)x*=gainOriginal;}
   else if(mode==2)result=current.normal;
   else if(mode==3)for(unsigned c=0;c<2;++c)result[c]=current.normal[c]-raw[c];
   else if(mode==4&&a){result=fa.normal;if(match&&stage==3)for(auto& x:result)x*=gainA;}
   else if(mode==5&&b){result=fb.normal;if(match&&stage==3)for(auto& x:result)x*=gainB;}
   else if(mode==7)result=current.low;
   else if(mode==8)result=current.detector;
   if(routeFade){const double t=1-double(routeFade)/routeLength;for(unsigned c=0;c<2;++c)result[c]=routeFrom[c]+t*(result[c]-routeFrom[c]);--routeFade;}
   for(unsigned c=0;c<2;++c)buffer[2*i+c]=sampleFloat(result[c]);last={buffer[2*i],buffer[2*i+1]};processed=true;
   if(settings[1]&&settings[30]){for(unsigned c=0;c<2;++c){const double values[2]={input[c],last[c]};for(unsigned p=0;p<2;++p){const unsigned n=p*2+c;peaks[n]=std::max(std::abs(values[p]),peaks[n]*peakDecay);squares[n]+=(values[p]*values[p]-squares[n])*meterAlpha;}}
    const Frame& diagnostic=mode==4&&a?fa:mode==5&&b?fb:current;
    lowSquare+=((diagnostic.low[0]*diagnostic.low[0]+diagnostic.low[1]*diagnostic.low[1])*.5-lowSquare)*meterAlpha;
    if((std::abs(last[0])>1||std::abs(last[1])>1)&&clips<INT32_MAX)++clips;}
  }bump();
 }
 const Kernel* selected()const{return ready?(mode==4&&a?a.get():mode==5&&b?b.get():normal.get()):nullptr;}
 void runtime(int32_t* v)const{
  std::fill_n(v,48,0);v[0]=1;v[1]=revision;v[2]=rate;v[3]=enabled;v[4]=settings[1];v[5]=mode;v[6]=match;v[7]=stage;
  v[8]=int(measureFrames*1000/rate);v[9]=gainA>0?std::max(-18000,db(gainA)):0;v[10]=gainB>0?std::max(-18000,db(gainB)):0;v[11]=epoch;v[12]=settings[1]&&settings[30];
  const Kernel* k=selected();const auto& s=k?k->settings:settings;v[13]=s[2];v[14]=s[3];v[15]=s[2]==4;
  for(unsigned i=16;i<24;++i)v[i]=INT32_MIN;v[28]=v[45]=INT32_MIN;
  if(enabled&&ready&&settings[1]&&settings[30]){for(unsigned i=0;i<4;++i){v[16+i]=db(peaks[i]);v[20+i]=db(std::sqrt(std::max(0.,squares[i])));}v[28]=k?db(k->detectorLevel):INT32_MIN;v[45]=db(std::sqrt(std::max(0.,lowSquare)));}
  v[24]=clips;v[25]=ready;v[26]=rate;v[27]=k?int(std::lround(k->reduction*100)):0;
  v[29]=int(warmFrames*1000/rate);v[30]=int(warmRequired*1000/rate);v[31]=2000;v[46]=settings[1]&&settings[31];v[47]=k?k->delay:0;
  if(k){v[32]=k->deep?0:k->xHz;v[33]=k->deep?0:k->yLo;v[34]=k->deep?0:k->yHi;v[35]=k->deep?k->deepHz:0;v[36]=k->hpHz;v[37]=k->lpHz;
   v[38]=k->deep?0:std::clamp(db(k->bass),-18000,6000);v[39]=k->deep?0:int(std::lround(k->lowGain*1000));v[40]=k->deep?0:int(std::lround(k->highGain*1000));
   v[41]=k->deep?int(std::lround(k->inject*1000)):0;v[42]=k->deep?int(std::lround(k->q*1000)):0;
   v[43]=s[1]&&s[17]?k->detectorHP:0;v[44]=s[1]&&s[17]?k->detectorLP:0;}
 }
 void response(int32_t* v)const{
  std::fill_n(v,200,0);v[0]=1;v[1]=revision;v[2]=rate;const Kernel* k=selected();v[3]=k?k->deep:settings[2]==4;v[4]=k?int(std::lround(k->reduction*100)):0;v[6]=32;
  if(!enabled||!ready||!settings[1]||!settings[31]||!k)return;v[5]=1;
  const double hi=std::min(20000.,std::floor(rate*.45));
  for(unsigned i=0;i<32;++i){const int hz=int(std::lround(20*std::pow(hi/20.,i/31.)));const auto h=k->response(hz);int32_t* p=v+8+i*6;p[0]=hz;p[1]=graphDb(h[0]);p[2]=phase(h[0]);p[3]=graphDb(h[1]);p[4]=phase(h[1]);p[5]=graphDb(h[2]);}
 }
};
DynamicTuning::DynamicTuning():engine(new(std::nothrow) Engine){if(engine){engine->normal=prepare(dynamictuning::defaults,44100);engine->ready=engine->normal!=nullptr;}}
DynamicTuning::~DynamicTuning()=default;
bool DynamicTuning::SetProfessional(const int32_t* v,size_t n){if(!engine||!dynamictuning::valid(v,n))return false;S s;std::copy_n(v,36,s.begin());return engine->set(s);}
bool DynamicTuning::SetTools(const int32_t* v,size_t n){return engine&&dynamictuning::validTools(v,n)&&engine->tools(v);}
void DynamicTuning::ClearTools(){if(engine)engine->clearControl();}
void DynamicTuning::SetEnable(bool enable){if(engine&&engine->enabled!=enable){if(enable)engine->reset();else{engine->settle();engine->clearControl();}engine->enabled=enable;engine->bump();}}
void DynamicTuning::SetSamplingRate(uint32_t rate){if(engine)engine->setRate(rate);}
void DynamicTuning::Reset(){if(engine)engine->reset();}
void DynamicTuning::Process(float* v,uint32_t n){if(engine)engine->process(v,n);}
void DynamicTuning::GetRuntime(int32_t* v)const{if(engine)engine->runtime(v);else{std::fill_n(v,48,0);v[0]=1;v[2]=44100;v[31]=2000;for(unsigned i=16;i<24;++i)v[i]=INT32_MIN;v[28]=v[45]=INT32_MIN;}}
void DynamicTuning::GetResponse(int32_t* v)const{if(engine)engine->response(v);else{std::fill_n(v,200,0);v[0]=1;v[6]=32;}}
