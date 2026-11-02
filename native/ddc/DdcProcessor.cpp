// SPDX-License-Identifier: GPL-3.0-or-later
#include "DdcProcessor.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <complex>
#include <climits>
#include <cfloat>
#include <cstring>
#include <new>
namespace {
using Settings=std::array<int32_t,64>;using Hash=std::array<int32_t,8>;
constexpr double pi=3.14159265358979323846;
#ifdef DSP_FUSION_TEST_ALLOCATIONS
thread_local uint64_t preparationCount=0;
#endif
const Settings initial=[] {Settings s{};s[0]=1;s[4]=1000;s[7]=120;s[9]=1000;s[11]=8000;s[13]=1000;s[15]=1000;s[17]=100;s[19]=6000;s[21]=100;s[23]=1;s[24]=s[25]=1000;s[33]=10;return s;}();
double gain(double d){return std::pow(10.,d/20.);}
int32_t db100(double a){return a>1e-18&&std::isfinite(a)?int32_t(std::clamp(std::lround(2000*std::log10(a)),-36000l,36000l)):INT_MIN;}
int32_t graphDb(double a){return std::clamp(db100(a),-36000,36000);}
int32_t attenuation(double a){return std::clamp(db100(a),-36000,0);}
struct Coeff {double b0=1,b1=0,b2=0,a1=0,a2=0;};
// Inverse bilinear mapping preserves the analogue response represented by the 48 kHz bank.
// Native 44.1/48 kHz operation never calls this path, retaining the exact original coefficients.
bool mapRate(const double* source,double* target,size_t count,uint32_t rate){
 if(rate<DDC_MIN_SAMPLE_RATE||rate>DDC_MAX_SAMPLE_RATE)return false;
 const long double r=(48000.L-rate)/(48000.L+rate),rr=r*r;
 for(size_t i=0;i<count;++i){const double* c=source+5*i;double* out=target+5*i;
  auto transform=[&](long double p0,long double p1,long double p2){return std::array<long double,3>{{p0+p1*r+p2*rr,2*p0*r+p1*(1+rr)+2*p2*r,p0*rr+p1*r+p2}};};
  const auto b=transform(c[0],c[1],c[2]),a=transform(1,c[3],c[4]);
  if(!std::isfinite(a[0])||a[0]==0)return false;
  out[0]=double(b[0]/a[0]);out[1]=double(b[1]/a[0]);out[2]=double(b[2]/a[0]);out[3]=double(a[1]/a[0]);out[4]=double(a[2]/a[0]);
  for(unsigned j=0;j<5;++j)if(!std::isfinite(out[j])||std::abs(out[j])>1e12)return false;
  // Strict real-SOS Jury stability after the final double rounding.
  if(std::abs(out[4])>=1||1+out[3]+out[4]<=0||1-out[3]+out[4]<=0)return false;
 }
 return true;
}
struct Filter {
 Coeff c{};double v1[2]{},v2[2]{};
 double tick(double x,unsigned ch){const double w=x-c.a1*v1[ch]-c.a2*v2[ch];const double y=c.b0*w+c.b1*v1[ch]+c.b2*v2[ch];v2[ch]=v1[ch];v1[ch]=w;return y;}
 void reset(){v1[0]=v1[1]=v2[0]=v2[1]=0;}
};
std::complex<double> transfer(const Coeff& c,double omega){const auto z=std::polar(1.,-omega);return(c.b0+c.b1*z+c.b2*z*z)/(1.+c.a1*z+c.a2*z*z);}
Coeff peak(double fs,double hz,double db,double q){if(db==0)return{};const double w=2*pi*std::min(hz,fs*.43)/fs,a=std::pow(10.,db/40),cs=std::cos(w),al=std::sin(w)/(2*q),d=1+al/a;return{(1+al*a)/d,-2*cs/d,(1-al*a)/d,-2*cs/d,(1-al/a)/d};}
Coeff shelf(double fs,double hz,double db,double slope,bool high){if(db==0)return{};const double w=2*pi*std::min(hz,fs*.43)/fs,a=std::pow(10.,db/40.),cs=std::cos(w),t=std::sin(w)*std::sqrt(a)*std::sqrt((a+1/a)*(1/slope-1)+2);
 if(high){const double d=(a+1)-(a-1)*cs+t;return{a*((a+1)+(a-1)*cs+t)/d,-2*a*((a-1)+(a+1)*cs)/d,a*((a+1)+(a-1)*cs-t)/d,2*((a-1)-(a+1)*cs)/d,((a+1)-(a-1)*cs-t)/d};}
 const double d=(a+1)+(a-1)*cs+t;return{a*((a+1)-(a-1)*cs+t)/d,2*a*((a-1)-(a+1)*cs)/d,a*((a+1)-(a-1)*cs-t)/d,-2*((a-1)+(a+1)*cs)/d,((a+1)+(a-1)*cs-t)/d};}
bool valid(const int32_t* s,size_t n){if(!s||n!=64||s[0]!=1)return false;
 for(unsigned i:{1u,2u,3u,5u,6u,10u,14u,18u,22u,23u,28u,30u,32u,34u,35u})if(s[i]<0||s[i]>1)return false;
 auto range=[s](unsigned i,int a,int b){return s[i]>=a&&s[i]<=b;};
 if(!range(4,0,1000)||!range(7,20,1000)||!range(8,-120,120)||!range(9,250,1000)||!range(11,1000,20000)||!range(12,-120,120)||!range(13,250,1000)||!range(15,20,20000)||!range(16,-120,120)||!range(17,25,1000)||!range(19,20,20000)||!range(20,-120,120)||!range(21,25,1000)||!range(24,0,1000)||!range(25,0,1000)||!range(26,-120,120)||!range(27,-120,120)||!range(29,-480,0)||!range(31,-240,120)||!range(33,0,120))return false;
 for(unsigned i=44;i<64;++i)if(s[i])return false;return true;
}
bool neutral(const Settings& s){if(!s[1])return true;if(s[3]&&s[4]!=1000)return false;
 if(s[5]&&((s[6]&&s[8])||(s[10]&&s[12])||(s[14]&&s[16])||(s[18]&&s[20])))return false;
 if(s[22]&&(s[24]!=1000||s[26]||(!s[23]&&(s[25]!=1000||s[27]))))return false;
 return !(s[28]&&s[29])&&!(s[30]&&s[31])&&!s[32];
}
Settings normalized(Settings s){s[2]=s[34]=s[35]=0;for(unsigned i=36;i<64;++i)s[i]=0;if(neutral(s)){s=initial;s[2]=0;return s;}
 if(!s[3])s[4]=1000;if(!s[5])for(unsigned i=6;i<22;++i)s[i]=initial[i];else for(auto v:std::array<std::array<int,3>,4>{{{{6,7,9}},{{10,11,13}},{{14,15,17}},{{18,19,21}}}})if(!s[v[0]])for(int i=v[1];i<=v[2];++i)s[i]=initial[i];
 if(!s[22])for(unsigned i=23;i<28;++i)s[i]=initial[i];else if(s[23]){s[25]=s[24];s[27]=s[26];}
 if(!s[28])s[29]=0;if(!s[30])s[31]=0;if(!s[32])s[33]=10;return s;
}
struct Kernel {
 Settings settings=initial;Hash hash{};std::array<Filter,DDC_MAX_SOS> base{};std::array<Filter,4> eq{};size_t sections=0;uint32_t revision=0,fs=48000;
 double ratio[2]{1,1},ear[2]{1,1},pre=1,output=1,automatic=1,prediction=1,sourcePeak=1;bool plain=true;std::array<int32_t,4> frequencies{};
 std::complex<double> source(double hz)const{std::complex<double> result=1.;for(size_t i=0;i<sections;++i)result*=transfer(base[i].c,2*pi*hz/fs);return result;}
 std::complex<double> final(double hz,unsigned ch,bool includeAuto=true)const{const auto h=source(hz);std::complex<double> result=ratio[ch]==1?h:1.+ratio[ch]*(h-1.);for(const auto& f:eq)result*=transfer(f.c,2*pi*hz/fs);return result*pre*ear[ch]*output*(includeAuto?automatic:1.);}
 void design(){
#ifdef DSP_FUSION_TEST_ALLOCATIONS
  ++preparationCount;
#endif
  const auto& s=settings;const bool pro=s[1];plain=neutral(s);const double master=pro&&s[3]?s[4]/1000.:1.;
  for(unsigned ch=0;ch<2;++ch){const unsigned c=ch&&(!s[23])?1:0;ratio[ch]=master*(pro&&s[22]?s[24+c]/1000.:1.);ear[ch]=pro&&s[22]?gain(s[26+c]/10.):1.;}
  pre=pro&&s[28]?gain(s[29]/10.):1.;output=pro&&s[30]?gain(s[31]/10.):1.;automatic=1;
  const unsigned hz[4]={7,11,15,19},on[4]={6,10,14,18};for(unsigned i=0;i<4;++i){frequencies[i]=int32_t(std::lround(std::min(double(s[hz[i]]),fs*.43)));eq[i].c={};}
  if(pro&&s[5]){if(s[6])eq[0].c=shelf(fs,frequencies[0],s[8]/10.,s[9]/1000.,false);if(s[10])eq[1].c=shelf(fs,frequencies[1],s[12]/10.,s[13]/1000.,true);if(s[14])eq[2].c=peak(fs,frequencies[2],s[16]/10.,s[17]/100.);if(s[18])eq[3].c=peak(fs,frequencies[3],s[20]/10.,s[21]/100.);}
  // File preparation already sampled the source bank and its pole angles.
  // A neutral layer is that exact source response, so no second dense scan is
  // needed for original/professional-off operation or immediate restoration.
  if(plain){prediction=std::max(sourcePeak,1e-18);return;}
  prediction=0;auto inspect=[&](double f){for(unsigned ch=0;ch<2;++ch){const double a=std::abs(final(f,ch,false));if(std::isfinite(a))prediction=std::max(prediction,a);}};
  // Prediction samples the complex combined response, not the sum of SOS gains.
  // Dense points and each pole angle supplement the displayed 32-point graph.
  inspect(0);inspect(fs*.5);for(unsigned i=0;i<=4096;++i)inspect(1.*std::pow((fs*.499),i/4096.));
  auto poles=[&](const Coeff& c){const auto roots=std::sqrt(std::complex<double>(c.a1*c.a1-4*c.a2,0));for(auto z:{(-c.a1+roots)/2.,(-c.a1-roots)/2.}){const double f=std::abs(std::arg(z))*fs/(2*pi);for(double delta:{-.002,-.0002,0.,.0002,.002})inspect(std::clamp(f*(1+delta),0.,fs*.5));}};
  for(size_t i=0;i<sections;++i)poles(base[i].c);for(const auto& e:eq)poles(e.c);
  prediction=std::max(prediction,1e-18);if(pro&&s[32])automatic=std::min(1.,gain(-s[33]/10.)/prediction);
 }
 void assign(const Settings& s,bool reset){settings=s;design();for(auto& f:eq)f.reset();if(reset)for(size_t i=0;i<sections;++i)base[i].reset();}
 void load(const double* c,size_t count,const Hash& sha,uint32_t rev,uint32_t rate,const Settings& s,double peak){sections=count;hash=sha;revision=rev;fs=rate;sourcePeak=peak;for(size_t i=0;i<count;++i)base[i].c={c[5*i],c[5*i+1],c[5*i+2],c[5*i+3],c[5*i+4]};assign(s,true);}
 void tick(const float* input,float* outputSamples){
  if(!sections){outputSamples[0]=input[0];outputSamples[1]=input[1];return;}
  double x[2]={double(input[0]),double(input[1])};if(!plain){for(auto& value:x)if(!std::isfinite(value))value=0;x[0]*=pre*automatic;x[1]*=pre*automatic;}double y[2]={x[0],x[1]};
  // Preserve ui37's DirectForm2 operation order and its sole final float cast.
  for(size_t i=0;i<sections;++i){y[0]=base[i].tick(y[0],0);y[1]=base[i].tick(y[1],1);}
  if(!plain)for(unsigned ch=0;ch<2;++ch){y[ch]=ratio[ch]==1?y[ch]:x[ch]+ratio[ch]*(y[ch]-x[ch]);for(auto& f:eq)y[ch]=f.tick(y[ch],ch);y[ch]*=ear[ch]*output;}
  outputSamples[0]=static_cast<float>(y[0]);outputSamples[1]=static_cast<float>(y[1]);
  if(!plain&&(!std::isfinite(outputSamples[0])||!std::isfinite(outputSamples[1]))){for(size_t i=0;i<sections;++i)base[i].reset();for(auto& f:eq)f.reset();outputSamples[0]=outputSamples[1]=0;}
 }
 uint32_t warm()const{double seconds=.1;auto inspect=[&](const Coeff& c){const auto d=std::sqrt(std::complex<double>(c.a1*c.a1-4*c.a2,0));const double r=std::max(std::abs((-c.a1+d)/2.),std::abs((-c.a1-d)/2.));if(r>0&&r<1)seconds=std::max(seconds,std::min(30.,std::log(1e-5)/std::log(r)/fs));};for(size_t i=0;i<sections;++i)inspect(base[i].c);for(const auto& f:eq)inspect(f.c);return uint32_t(std::ceil(seconds*fs-1e-9));}
};
struct Processor {
 Settings saved=initial;Hash acceptedHash{};std::array<double,DDC_MAX_SOS*5> bank441{},bank480{};size_t acceptedSections=0;uint32_t acceptedRevision=0;
 std::array<double,DDC_MAX_SOS*5> bound441{},bound480{};Hash boundHash{};size_t boundSections=0;uint32_t boundRevision=0;std::array<double,2> acceptedPeaks{{1,1}},boundPeaks{{1,1}};
 std::array<double,DDC_MAX_SOS*5> mappedBank{};bool mappedValid=true;
 std::array<Kernel,7> kernels{};unsigned active=0,candidate=1,queuedKernel=6;uint32_t fs=48000,hostFs=48000,epoch=0,elapsed=0,required=0,fade=0,queuedElapsed=0,queuedRequired=0;bool pending=false,queuedFile=false,everProcessed=false,restoringOriginal=false;
 double power=0,powerTarget=0;std::array<int32_t,132> tools{};bool prepared=false,warmed=false,retired=false;unsigned stage=0;uint32_t warmFrames=0,warmRequired=0,measureFrames=0;double energyA=0,energyB=0,attenuationA=1,attenuationB=1;std::array<double,4> route{{0,1,0,0}};
 std::array<double,2> inputPeak{},outputPeak{},inputEnergy{},outputEnergy{};size_t meterFrames=0;uint32_t clips=0,error=0;std::array<int32_t,80> runtime{};std::array<int32_t,232> response{};
 Processor(){publish();}
 bool supported()const{return fs>=DDC_MIN_SAMPLE_RATE&&fs<=DDC_MAX_SAMPLE_RATE&&mappedValid;}
 const double* bank()const{return fs==44100?bound441.data():fs==48000?bound480.data():mappedBank.data();}
 double bankPeak()const{return boundPeaks[fs==44100?0:1];}
 void mapBank(){mappedValid=fs==44100||fs==48000||mapRate(bound480.data(),mappedBank.data(),boundSections,fs);if(!mappedValid)kernels[active].sections=0;}
 void bind(){if(boundRevision==acceptedRevision)return;bound441=bank441;bound480=bank480;boundHash=acceptedHash;boundSections=acceptedSections;boundRevision=acceptedRevision;boundPeaks=acceptedPeaks;mapBank();}
 void clear(){tools.fill(0);prepared=warmed=false;stage=0;warmFrames=warmRequired=measureFrames=0;energyA=energyB=0;attenuationA=attenuationB=1;}
 void changed(){tools[0]=tools[1]=0;stage=0;measureFrames=0;energyA=energyB=0;attenuationA=attenuationB=1;}
 void start(const Settings& s,bool immediate=false){
  if(!boundSections||!supported()){pending=queuedFile=restoringOriginal=false;return;}
  auto& current=kernels[active];const bool sameFile=current.hash==boundHash&&current.sections==boundSections;
  if(sameFile)current.revision=boundRevision;
  if(sameFile&&normalized(current.settings)==normalized(s)){current.settings=s;if(pending&&kernels[candidate].revision==boundRevision&&normalized(kernels[candidate].settings)==normalized(s))kernels[candidate].settings=s;else{pending=false;restoringOriginal=false;}return;}
  auto& next=kernels[candidate];
  if(immediate){next=current;next.assign(s,false);queuedFile=!sameFile;required=0;
   if(queuedFile){kernels[queuedKernel].load(bank(),boundSections,boundHash,boundRevision,fs,s,bankPeak());queuedElapsed=0;queuedRequired=kernels[queuedKernel].warm();}}
  else {next.load(bank(),boundSections,boundHash,boundRevision,fs,s,bankPeak());required=next.warm();queuedFile=false;}
  pending=true;restoringOriginal=immediate;elapsed=fade=0;
 }
 bool set(const int32_t* values,size_t n){if(!valid(values,n))return false;Settings s;std::copy_n(values,64,s.begin());
  if(!std::equal(s.begin()+36,s.begin()+44,acceptedHash.begin())||(s[2]&&!acceptedSections))return false;
  const bool audioChanged=normalized(saved)!=normalized(s),powerChanged=saved[2]!=s[2],proOff=(saved[1]&&!s[1])||(restoringOriginal&&!s[1]);
  const bool sameBound=std::equal(s.begin()+36,s.begin()+44,boundHash.begin());const bool keepPending=pending&&!audioChanged&&!powerChanged&&sameBound;
  if(!s[1]||powerChanged)clear();else if(audioChanged)changed();if(saved[34]!=s[34]||saved[1]!=s[1]){meterFrames=0;clips=0;}
  bind();saved=s;powerTarget=s[2]?1.:0.;if(!everProcessed)power=powerTarget;
  if(!s[2]){pending=queuedFile=restoringOriginal=false;elapsed=fade=required=0;}
  else if(!everProcessed){if(supported())kernels[active].load(bank(),boundSections,boundHash,boundRevision,fs,s,bankPeak());else kernels[active].sections=0;pending=queuedFile=restoringOriginal=false;}
  else if(keepPending){kernels[candidate].settings=s;}else start(s,proOff);
  publish();return true;
 }
 bool file(const double* a,const double* b,size_t count,const char* text,size_t length){
  if(!a||!b||!text||!count||count>DDC_MAX_SOS||!length||length>DDC_MAX_FILE_BYTES)return false;
  Hash sha{};DdcSha256(text,length,sha.data());if(sha==acceptedHash&&count==acceptedSections){error=0;publish();return true;}
  // Preparation additionally rejects numerical overflow before touching the
  // accepted banks. Stable individual poles alone cannot bound a 128-SOS cascade.
  std::array<double,2> peaks{};unsigned bankIndex=0;
  for(const double* coefficients:{a,b}){
   std::array<Filter,DDC_MAX_SOS> check{};
   for(size_t i=0;i<count;++i){const double* c=coefficients+5*i;for(unsigned j=0;j<5;++j)if(!std::isfinite(c[j])||std::abs(c[j])>1e12)return false;
    if(std::abs(c[4])>1-1e-12||1+c[3]+c[4]<1e-12||1-c[3]+c[4]<1e-12)return false;check[i].c={c[0],c[1],c[2],c[3],c[4]};}
   for(unsigned i=0;i<4096;++i){double x=i==0?1:0;for(size_t j=0;j<count;++j){x=check[j].tick(x,0);if(!std::isfinite(x)||std::abs(x)>1e250)return false;}if(std::abs(x)>FLT_MAX/65536.)return false;}
   auto inspect=[&](double w){std::complex<double> h=1.;for(size_t i=0;i<count;++i){h*=transfer(check[i].c,w);if(!std::isfinite(std::abs(h))||std::abs(h)>1e250)return false;}peaks[bankIndex]=std::max(peaks[bankIndex],std::abs(h));return std::abs(h)<=FLT_MAX/65536.;};
   for(unsigned i=0;i<=4096;++i)if(!inspect(pi*i/4096.))return false;
   for(size_t i=0;i<count;++i){const auto& c=check[i].c;const auto d=std::sqrt(std::complex<double>(c.a1*c.a1-4*c.a2,0));for(auto z:{(-c.a1+d)/2.,(-c.a1-d)/2.})if(!inspect(std::abs(std::arg(z))))return false;}
   ++bankIndex;
  }
  // No allocations or partial ownership transfer: all accepted storage is fixed.
  std::copy_n(a,count*5,bank441.begin());std::copy_n(b,count*5,bank480.begin());acceptedPeaks=peaks;acceptedHash=sha;acceptedSections=count;acceptedRevision=(acceptedRevision+1)&INT_MAX;if(!acceptedRevision)acceptedRevision=1;
  // Acceptance alone never changes audible configuration. A matching atomic
  // settings packet (or a legacy enable request) starts the prepared handoff.
  clear();if(!restoringOriginal){pending=queuedFile=false;elapsed=fade=required=0;}error=0;publish();return true;
 }
 void configure(uint32_t rate){if(rate==fs)return;fs=rate;mapBank();clear();route={0,1,0,0};retired=false;pending=queuedFile=restoringOriginal=false;elapsed=fade=required=0;meterFrames=0;clips=0;
  if(boundSections&&supported())kernels[active].load(bank(),boundSections,boundHash,boundRevision,fs,saved,bankPeak());else kernels[active].sections=0;power=saved[2]?1.:0.;publish();
 }
 void enabled(bool value){const bool samePower=saved[2]==int(value),sameFile=kernels[active].revision==acceptedRevision;
  if(samePower&&(!value||sameFile)){return;}saved[2]=value;powerTarget=value?1:0;if(!everProcessed)power=powerTarget;clear();pending=queuedFile=restoringOriginal=false;elapsed=fade=required=0;
  if(value){bind();std::copy(acceptedHash.begin(),acceptedHash.end(),saved.begin()+36);if(!everProcessed&&boundSections&&supported())kernels[active].load(bank(),boundSections,boundHash,boundRevision,fs,saved,bankPeak());else start(saved);}publish();}
 void original(){saved[1]=0;clear();pending=queuedFile=restoringOriginal=false;elapsed=fade=required=0;
  // No SHA-bearing accepted source is installed here. The current bound file
  // remains the foundation even if a different upload has just been accepted.
  if(saved[2]&&supported())start(saved,true);publish();}
 bool setTools(const int32_t* s,size_t n){if(!DdcToolsValidate(s,n))return false;if(s[0]==0){clear();publish();return true;}
  if(!saved[1]||!saved[2]||!boundSections||!supported()||boundHash!=acceptedHash)return false;
  if(!std::equal(s+40,s+48,acceptedHash.begin())||!std::equal(s+104,s+112,acceptedHash.begin()))return false;
  const bool same=tools[2]==s[2]&&s[2]>0&&std::equal(s+4,s+132,tools.begin()+4),need=s[0]>=4;
  if(need&&(!same||!prepared)){
   if(!retired&&(std::abs(route[2])+std::abs(route[3])>1e-6)){kernels[4]=kernels[2];kernels[5]=kernels[3];retired=true;}
   Settings a,b;std::copy_n(s+4,64,a.begin());std::copy_n(s+68,64,b.begin());kernels[2].load(bank(),boundSections,boundHash,boundRevision,fs,a,bankPeak());kernels[3].load(bank(),boundSections,boundHash,boundRevision,fs,b,bankPeak());
   warmFrames=measureFrames=0;warmRequired=std::max(kernels[2].warm(),kernels[3].warm());energyA=energyB=0;attenuationA=attenuationB=1;stage=1;prepared=true;warmed=false;
  }
  if(!need&&!same){prepared=warmed=false;stage=0;warmFrames=measureFrames=0;energyA=energyB=0;attenuationA=attenuationB=1;}
  if(s[0]==6&&same&&prepared&&warmed&&stage!=3&&tools[0]!=6){stage=2;measureFrames=0;energyA=energyB=0;attenuationA=attenuationB=1;}
  std::copy_n(s,132,tools.begin());publish();return true;
 }
 void process(float* l,float* r,size_t frames){if(!l||!r||!frames)return;everProcessed=true;meterFrames=frames;inputPeak.fill(0);outputPeak.fill(0);inputEnergy.fill(0);outputEnergy.fill(0);const double alpha=1-std::exp(-1./(.020*std::max(8000u,fs)));
  for(size_t i=0;i<frames;++i){float raw[2]={l[i],r[i]};if(saved[1])for(auto& value:raw)if(!std::isfinite(value))value=0;float effect[2];kernels[active].tick(raw,effect);
   if(queuedFile){float discarded[2];kernels[queuedKernel].tick(raw,discarded);++queuedElapsed;}
   if(pending){float next[2];kernels[candidate].tick(raw,next);++elapsed;if(elapsed>=required){const double blend=std::min(1.,double(++fade)/std::max(1u,fs/50));for(unsigned ch=0;ch<2;++ch)effect[ch]=float(effect[ch]+blend*(next[ch]-effect[ch]));if(fade>=std::max(1u,fs/50)){std::swap(active,candidate);pending=false;restoringOriginal=false;if(queuedFile){std::swap(candidate,queuedKernel);pending=true;queuedFile=false;elapsed=queuedElapsed;required=queuedRequired;fade=0;}}}}
   float a[2]{},b[2]{};const bool compare=prepared&&tools[2]>0,tail=std::abs(route[2])+std::abs(route[3])>1e-12;
   if(compare||(tail&&!retired)){kernels[2].tick(raw,a);kernels[3].tick(raw,b);}float routeA[2]={a[0],a[1]},routeB[2]={b[0],b[1]};
   if(retired){kernels[4].tick(raw,routeA);kernels[5].tick(raw,routeB);if(std::abs(route[2])+std::abs(route[3])<1e-6){route[2]=route[3]=0;retired=false;}}
   if(compare){if(stage==1){if(++warmFrames>=warmRequired){warmed=true;stage=tools[0]==6?2:0;}}
    else if(stage==2&&tools[0]==6){energyA+=double(a[0])*a[0]+double(a[1])*a[1];energyB+=double(b[0])*b[0]+double(b[1])*b[1];if(++measureFrames>=fs*2){const double ra=std::sqrt(energyA/(measureFrames*2.)),rb=std::sqrt(energyB/(measureFrames*2.));if(ra<1e-6||rb<1e-6||!std::isfinite(ra)||!std::isfinite(rb)){stage=4;attenuationA=attenuationB=1;}else{stage=3;const double minimum=std::min(ra,rb);attenuationA=minimum/ra;attenuationB=minimum/rb;}}}}
   std::array<double,4> target{{0,1,0,0}};if(saved[1]&&saved[2])switch(tools[0]){case 1:target={1,0,0,0};break;case 3:target={-1,1,0,0};break;case 4:if(warmed&&(!tools[1]||stage==3)&&!retired)target={0,0,tools[1]?attenuationA:1.,0};break;case 5:if(warmed&&(!tools[1]||stage==3)&&!retired)target={0,0,0,tools[1]?attenuationB:1.};break;default:break;}
   for(unsigned j=0;j<4;++j){route[j]+=alpha*(target[j]-route[j]);if(std::abs(route[j]-target[j])<1e-12)route[j]=target[j];}power+=alpha*(powerTarget-power);if(std::abs(power-powerTarget)<1e-7)power=powerTarget;
   for(unsigned ch=0;ch<2;++ch){float out=effect[ch];if(route[0]!=0||route[1]!=1||route[2]!=0||route[3]!=0)out=float(route[0]*raw[ch]+route[1]*effect[ch]+route[2]*routeA[ch]+route[3]*routeB[ch]);if(power!=1)out=power==0?raw[ch]:float(raw[ch]+power*(out-raw[ch]));if(saved[1]&&!std::isfinite(out))out=0;if(ch)r[i]=out;else l[i]=out;
    inputPeak[ch]=std::max(inputPeak[ch],std::abs(double(raw[ch])));outputPeak[ch]=std::max(outputPeak[ch],std::abs(double(out)));inputEnergy[ch]+=double(raw[ch])*raw[ch];outputEnergy[ch]+=double(out)*out;}
   if(std::max(std::abs(l[i]),std::abs(r[i]))>=1&&clips<INT_MAX)++clips;
  }publish();
 }
 void publish(){epoch=(epoch+2)&0x7ffffffeu;runtime.fill(0);response.fill(0);const auto& k=kernels[active];const auto& s=k.settings;const bool ready=supported()&&k.sections>0;
  runtime[0]=1;runtime[1]=epoch;runtime[2]=fs;runtime[3]=saved[2];runtime[4]=saved[1];runtime[5]=ready;runtime[6]=tools[0];runtime[7]=tools[1];runtime[8]=stage;runtime[9]=fs?int32_t(uint64_t(measureFrames)*1000/fs):0;runtime[10]=attenuation(attenuationA);runtime[11]=attenuation(attenuationB);runtime[12]=tools[2];runtime[13]=saved[2]&&saved[1]&&saved[34];runtime[14]=saved[1]&&saved[35];runtime[15]=1;runtime[16]=hostFs;runtime[17]=ready?fs:0;runtime[18]=k.sections;runtime[19]=k.revision;
  for(unsigned i=20;i<28;++i)runtime[i]=INT_MIN;if(runtime[13]&&meterFrames)for(unsigned ch=0;ch<2;++ch){runtime[20+ch]=db100(inputPeak[ch]);runtime[22+ch]=db100(outputPeak[ch]);runtime[24+ch]=db100(std::sqrt(inputEnergy[ch]/meterFrames));runtime[26+ch]=db100(std::sqrt(outputEnergy[ch]/meterFrames));}
  runtime[28]=runtime[13]?clips:0;runtime[29]=attenuation(k.automatic);runtime[30]=s[1]&&s[28]?s[29]:0;runtime[31]=s[1]&&s[30]?s[31]:0;runtime[32]=std::lround(k.ratio[0]*1000);runtime[33]=std::lround(k.ratio[1]*1000);runtime[34]=s[1]&&s[22]?s[26]:0;runtime[35]=s[1]&&s[22]?(s[23]?s[26]:s[27]):0;
  std::copy(k.hash.begin(),k.hash.end(),runtime.begin()+36);for(unsigned i=0;i<4;++i)runtime[44+i]=ready?k.frequencies[i]:0;runtime[48]=fs?int32_t(uint64_t(tools[2]?warmFrames:elapsed)*1000/fs):0;runtime[49]=fs?int32_t(uint64_t(tools[2]?warmRequired:required)*1000/fs):0;runtime[50]=!supported();runtime[51]=error;runtime[52]=s[1]&&s[32];runtime[53]=s[1]&&s[22]&&s[23];runtime[54]=s[1]&&s[5];const unsigned on[4]={6,10,14,18};for(unsigned i=0;i<4;++i)runtime[55+i]=runtime[54]&&s[on[i]];runtime[59]=graphDb(k.prediction);runtime[60]=pending||queuedFile;runtime[61]=acceptedRevision;std::copy(acceptedHash.begin(),acceptedHash.end(),runtime.begin()+62);
  runtime[60]=runtime[60]||(acceptedRevision!=k.revision);
  response[0]=1;response[1]=epoch;response[2]=fs;response[3]=runtime[14]&&ready;response[4]=32;if(response[3])for(unsigned i=0;i<32;++i){const unsigned j=8+i*7;const double hz=std::round(20*std::pow((fs*.499)/20.,i/31.));response[j]=int32_t(hz);response[j+1]=graphDb(std::abs(k.source(hz)));response[j+2]=graphDb(std::abs(k.final(hz,0)));response[j+3]=graphDb(std::abs(k.final(hz,1)));response[j+6]=1;}
 }
};
#ifdef DSP_FUSION_TEST_ALLOCATIONS
bool failNext=false;
#endif
}
extern "C" {
void* DdcCreate(void){
#ifdef DSP_FUSION_TEST_ALLOCATIONS
 if(failNext){failNext=false;return nullptr;}
#endif
 return new(std::nothrow)Processor;
}
void DdcDestroy(void* p){delete static_cast<Processor*>(p);}
int DdcValidate(const int32_t* s,size_t n){return valid(s,n);}
int DdcToolsValidate(const int32_t* s,size_t n){return s&&n==132&&s[0]>=0&&s[0]<=6&&s[1]>=0&&s[1]<=1&&s[2]>=0&&(s[0]<4||s[2]>0)&&s[3]==0&&valid(s+4,64)&&valid(s+68,64);}
void DdcDefaults(int32_t* s){if(s)std::copy(initial.begin(),initial.end(),s);}
int DdcSet(void* p,const int32_t* s,size_t n){return p&&static_cast<Processor*>(p)->set(s,n);}
int DdcTools(void* p,const int32_t* s,size_t n){return p&&static_cast<Processor*>(p)->setTools(s,n);}
void DdcClear(void* p){if(p){auto& v=*static_cast<Processor*>(p);v.clear();v.publish();}}
void DdcEnabled(void* p,int e){if(p)static_cast<Processor*>(p)->enabled(e!=0);}
void DdcOriginal(void* p){if(p)static_cast<Processor*>(p)->original();}
int DdcNeedsProcessing(const void* p){if(!p)return 0;const auto& v=*static_cast<const Processor*>(p);return v.saved[2]||v.power>0;}
void DdcConfigure(void* p,uint32_t fs){if(p)static_cast<Processor*>(p)->configure(fs);}
void DdcHostRate(void* p,uint32_t fs){if(p){auto& v=*static_cast<Processor*>(p);v.hostFs=fs;v.publish();}}
void DdcFileError(void* p,int e){if(p){auto& v=*static_cast<Processor*>(p);v.error=std::clamp(e,0,16);v.publish();}}
int DdcFile(void* p,const double* a,const double* b,size_t count,const char* text,size_t length){return p&&static_cast<Processor*>(p)->file(a,b,count,text,length);}
void DdcReset(void* p){if(p){auto& v=*static_cast<Processor*>(p);v.clear();v.pending=v.queuedFile=v.restoringOriginal=false;v.route={0,1,0,0};v.retired=false;if(v.boundSections&&v.supported())v.kernels[v.active].load(v.bank(),v.boundSections,v.boundHash,v.boundRevision,v.fs,v.saved,v.bankPeak());v.power=v.powerTarget;v.meterFrames=v.clips=0;v.publish();}}
void DdcProcess(void* p,float* l,float* r,size_t n){if(p)static_cast<Processor*>(p)->process(l,r,n);}
void DdcStatus(const void* p,int32_t* s){if(!s)return;if(p)std::copy(static_cast<const Processor*>(p)->runtime.begin(),static_cast<const Processor*>(p)->runtime.end(),s);else{std::fill_n(s,80,0);s[0]=1;s[15]=1;for(unsigned i=20;i<28;++i)s[i]=INT_MIN;}}
void DdcResponse(const void* p,int32_t* s){if(!s)return;if(p)std::copy(static_cast<const Processor*>(p)->response.begin(),static_cast<const Processor*>(p)->response.end(),s);else{std::fill_n(s,232,0);s[0]=1;s[4]=32;}}
#ifdef DSP_FUSION_TEST_ALLOCATIONS
void DdcFailNextAllocationForTest(void){failNext=true;}
uint64_t DdcPreparationCountForTest(void){return preparationCount;}
#endif
}
