#include <algorithm>
#include <array>
#include <atomic>
#include <cassert>
#include <cmath>
#include <complex>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <new>
#include <thread>
#include <vector>
#include "Dynamic34Reference.h"
#include "../viper/src/viper/effects/DynamicSystem.h"
#ifndef DYNAMIC_CORE_ONLY
#include "essential.h"
extern "C" {extern audio_effect_library_t AELI;}
#endif
thread_local bool watch=false;thread_local unsigned allocations=0,releases=0;
void* operator new(size_t n){if(watch)++allocations;if(auto* p=std::malloc(n))return p;throw std::bad_alloc();}
void* operator new[](size_t n){return ::operator new(n);}
void operator delete(void* p)noexcept{if(watch&&p)++releases;std::free(p);}
void operator delete[](void* p)noexcept{::operator delete(p);}
void operator delete(void* p,size_t)noexcept{::operator delete(p);}
void operator delete[](void* p,size_t)noexcept{::operator delete(p);}
namespace {
using S=dynamictuning::Settings;constexpr double pi=3.14159265358979323846;
constexpr std::array<std::array<int,6>,6> bases{{{140,6200,40,60,10,80},{180,5800,55,80,10,70},{300,5600,60,105,10,50},{600,5400,60,105,10,20},{100,5600,40,80,50,50},{1200,6200,40,80,0,20}}};
S professional(int base=0,int strength=20){auto s=dynamictuning::defaults;s[1]=1;s[2]=base;s[3]=strength;return s;}
std::vector<float> signal(unsigned fs,unsigned n,double hz=100,double a=.15,bool mono=false){std::vector<float> v(n*2);for(unsigned i=0;i<n;++i){v[2*i]=float(a*std::sin(2*pi*hz*i/fs));v[2*i+1]=mono?v[2*i]:float(a*.8*std::sin(2*pi*hz*i/fs+.31));}return v;}
struct Core {
 DynamicSystem dsp;unsigned fs;
 explicit Core(unsigned rate=48000):fs(rate){dsp.SetSamplingRate(rate);dsp.SetEnable(true);}
 void set(const S& s){assert(dsp.SetProfessional(s.data(),36));}
 auto status()const{std::array<int32_t,48> v;dsp.GetRuntime(v.data());return v;}
 auto response()const{std::array<int32_t,200> v;dsp.GetResponse(v.data());return v;}
 bool tools(int mode,const S& a,const S& b,bool match=false,int epoch=1){std::array<int32_t,76> p{mode,int(match),epoch,0};std::copy(a.begin(),a.end(),p.begin()+4);std::copy(b.begin(),b.end(),p.begin()+40);return dsp.SetTools(p.data(),p.size());}
 std::vector<float> render(const std::vector<float>& in,unsigned chunk=173){auto out=in;for(size_t pos=0;pos<out.size()/2;pos+=chunk){const auto n=std::min(size_t(chunk),out.size()/2-pos);allocations=releases=0;watch=true;dsp.Process(out.data()+pos*2,unsigned(n));watch=false;assert(!allocations&&!releases);}for(float x:out)assert(std::isfinite(x));return out;}
};
void init(Dynamic34Reference& r,const S& s,unsigned fs){const auto b=bases[s[2]];r.SetSamplingRate(fs);r.SetFilterXPassFrequency(b[0],b[1]);r.SetFilterYPassFrequency(b[2],b[3]);r.SetSideGain(b[4]/100.f,b[5]/100.f);r.SetBassGain(float((100+20*s[3])/100.));r.Reset();}
double rms(const std::vector<float>& v,unsigned start=0){double p=0;for(unsigned i=start*2;i<v.size();++i)p+=double(v[i])*v[i];return std::sqrt(p/(v.size()-start*2));}
std::complex<double> amplitude(const std::vector<float>& v,unsigned fs,double hz,unsigned start,unsigned n){std::complex<double> a{};for(unsigned i=0;i<n;++i)a+=double(v[2*(start+i)])*std::polar(1.,-2*pi*hz*(start+i)/fs);return a*(2./n);}
void validation(){assert(dynamictuning::valid(dynamictuning::defaults.data(),36));assert(!dynamictuning::valid(nullptr,36));assert(!dynamictuning::valid(dynamictuning::defaults.data(),35));
 for(unsigned i=0;i<36;++i){auto s=dynamictuning::defaults;s[i]=dynamictuning::minimum[i]-1;assert(!dynamictuning::valid(s.data(),36));s=dynamictuning::defaults;s[i]=dynamictuning::maximum[i]+1;assert(!dynamictuning::valid(s.data(),36));}
 auto s=professional();s[15]=9;assert(!dynamictuning::valid(s.data(),36));s[15]=300;s[16]=200;assert(!dynamictuning::valid(s.data(),36));s=dynamictuning::defaults;s[19]=250;s[20]=250;assert(!dynamictuning::valid(s.data(),36));
 Core c;auto v=c.status();assert(v[0]==1&&v[2]==48000&&v[31]==2000&&v[47]==3);for(unsigned i=16;i<24;++i)assert(v[i]==INT32_MIN);assert(v[28]==INT32_MIN&&v[45]==INT32_MIN);
 std::puts("Dynamic settings36/tools76/runtime48/response200 validation, strict reserved fields, frequency ordering and unavailable meter sentinels passed.");}
void compatibility(){unsigned cases=0;
 for(unsigned fs:{44100u,48000u,96000u,192000u})for(int base=0;base<6;++base)for(int strength:{0,17,100})for(int pro:{0,1}){
  auto s=professional(base,strength);s[1]=pro;Core c(fs);c.set(s);Dynamic34Reference ref;init(ref,s,fs);auto input=signal(fs,5000,431,.04),gold=input;ref.FilterSamples(gold.data(),5000);auto actual=c.render(input,17);
  if(actual!=gold){for(unsigned i=0;i<actual.size();++i)if(actual[i]!=gold[i]){std::printf("Frozen mismatch Fs%u base%d strength%d pro%d sample%u %.9g/%.9g\n",fs,base,strength,pro,i,actual[i],gold[i]);break;}}assert(actual==gold);
  // All disabled saved controls, diagnostics, no-op power/settings and neutral
  // enabled groups must retain continuously running ui34 filter histories.
  s[5]=120;s[6]=1000;s[8]=1000;s[11]=1900;s[14]=145;s[15]=20;s[16]=1000;s[27]=0;s[29]=-240;s[30]=s[31]=1;c.set(s);c.dsp.SetEnable(true);
  input=signal(fs,5000,811,.03);gold=input;ref.FilterSamples(gold.data(),5000);assert(c.render(input,509)==gold);
  s=professional(base,strength);s[4]=s[10]=s[26]=s[28]=s[30]=s[31]=1;s[17]=1;s[23]=0;c.set(s);input=signal(fs,5000,91,.04);gold=input;ref.FilterSamples(gold.data(),5000);assert(c.render(input,1)==gold);
  c.dsp.SetEnable(false);assert(c.render(input)==input);c.dsp.SetEnable(true);ref.Reset();gold=input;ref.FilterSamples(gold.data(),5000);assert(c.render(input)==gold);++cases;
 }
 std::printf("Dynamic frozen ui34: %u Fs/base/strength/pro cases bit-identical, inactive saved values, neutral enabled groups, diagnostics, chunk1/17/509 and power lifecycle preserve original sound.\n",cases);}
void effectiveControls(){auto in=signal(48000,48000,55,.03);for(double hz:{23.,85.,150.,400.,1500.}){auto part=signal(48000,48000,hz,.015);for(unsigned i=0;i<in.size();++i)in[i]+=part[i];}
 const std::array<unsigned,10> indices{5,6,7,11,12,13,15,16,27,29};const std::array<int,10> values{60,300,400,160,15,90,35,100,400,-60};unsigned cases=0;
 auto s=professional();s[4]=s[10]=s[26]=s[28]=1;Core initial;initial.set(s);const auto reference=initial.render(in);
 for(unsigned i=0;i<indices.size();++i){auto changed=s;changed[indices[i]]=values[i];Core c;c.set(changed);const auto out=c.render(in);double err=0;for(unsigned n=24000;n<48000;++n)err+=std::abs(out[2*n]-reference[2*n]);assert(err/24000>1e-6);++cases;}
 auto deep=professional(4);deep[4]=deep[10]=1;Core original;original.set(deep);const auto base=original.render(in);
 for(unsigned i:{8u,9u,14u}){auto changed=deep;changed[i]=i==8?500:i==9?60:30;Core c;c.set(changed);const auto out=c.render(in);double err=0;for(unsigned n=24000;n<48000;++n)err+=std::abs(out[2*n]-base[2*n]);assert(err/24000>1e-6);++cases;}
 for(int baseMode:{0,4}){auto normal=professional(baseMode);normal[4]=normal[10]=1;auto ignored=normal;if(baseMode==4){ignored[5]=120;ignored[6]=1000;ignored[7]=-1000;ignored[11]=1900;ignored[12]=500;ignored[13]=1000;}else{ignored[8]=1000;ignored[9]=240;ignored[14]=145;}Core a,b;a.set(normal);b.set(ignored);assert(a.render(in)==b.render(in));}
 auto crossing=professional(0);crossing[10]=1;crossing[11]=-130;crossing[12]=500;crossing[13]=-1000;Core fixed;fixed.set(crossing);fixed.render(in);auto v=fixed.status();assert(v[15]==0&&v[32]==10&&v[33]<v[34]);crossing[2]=4;fixed.set(crossing);fixed.render(in);assert(fixed.status()[15]==1);
 std::printf("Dynamic %u effective numeric controls measured; inactive mode controls are neutral, crossing Y offsets retain saved values with ordered actual limits, X crossing120Hz never changes normal/Deep algorithm.\n",cases);}
void lowFsCompatibility(){unsigned cases=0;double peak=0;
 for(unsigned fs:{8000u,16000u,22050u,32000u})for(int base=0;base<6;++base)for(int strength:{0,100}){
  auto s=dynamictuning::defaults;s[2]=base;s[3]=strength;Core c(fs);c.set(s);const auto output=c.render(signal(fs,fs*2,fs*.31,.15));for(float x:output)peak=std::max(peak,std::abs(double(x)));const auto v=c.status();assert(v[25]&&v[32]<int(fs/2)&&v[34]<int(fs/2));
  // Valid original frequency domains remain exactly the frozen algorithm;
  //8kHz's invalid internal X-upper is intentionally guarded instead.
  if(fs>=16000){Dynamic34Reference old;init(old,s,fs);auto input=signal(fs,7000,173,.02),gold=input;old.FilterSamples(gold.data(),7000);c.dsp.Reset();assert(c.render(input)==gold);}++cases;
 }assert(peak<10);std::printf("Dynamic low-Fs original path: %u six-base/strength cases8/16/22.05/32kHz finite (peak%.7g); valid legacy domains exact, invalid8kHz internal X-upper safely limited.\n",cases,peak);}
void adaptive(){auto s=professional(4,30);s[17]=s[30]=1;s[18]=0;s[21]=-300;s[22]=0;s[23]=120;s[24]=10;s[25]=100;Core peak;peak.set(s);
 peak.render(signal(48000,48000,70,.003,true));assert(peak.status()[27]<10);const auto loud=peak.render(signal(48000,48000,70,.3,true));assert(peak.status()[27]>900&&peak.status()[27]<=1200);
 const int reached=peak.status()[27];peak.render(signal(48000,960,70,.003,true));assert(peak.status()[27]>reached/2);peak.render(signal(48000,48000,70,.003,true));assert(peak.status()[27]<20);
 auto no=s;no[17]=0;Core baseline;baseline.set(no);const auto unchanged=baseline.render(signal(48000,48000,70,.3,true));assert(rms(loud,24000)<rms(unchanged,24000));
 s[18]=1;Core averaged;averaged.set(s);averaged.render(signal(48000,48000,70,.3,true));assert(averaged.status()[27]>900);
 auto impulses=std::vector<float>(48000*2);for(unsigned i=0;i<48000;i+=480)impulses[2*i]=impulses[2*i+1]=.9f;s[21]=-450;s[23]=180;s[24]=10;s[25]=50;Core pk,rm;s[18]=0;pk.set(s);s[18]=1;rm.set(s);pk.render(impulses);rm.render(impulses);assert(std::abs(pk.status()[27]-rm.status()[27])>30);
 auto a=professional(1,40);a[17]=1;a[23]=0;Core zero,disabled;zero.set(a);a[17]=0;disabled.set(a);const auto in=signal(48000,48000,80,.1);assert(zero.render(in)==disabled.render(in));
 // Linked sidechain applies identical attenuation to identical channels and
 // reacts to either side without independently moving the stereo image.
 Core linked;linked.set(s);auto stereo=signal(48000,48000,70,.3,true);auto out=linked.render(stereo);for(unsigned i=0;i<48000;++i)assert(out[2*i]==out[2*i+1]);
 std::printf("Dynamic real linked low-band detector: attack/release recovery verified; peak/RMS transient reductions %d/%d centidB; max0 is bit-neutral; primary dry path remains independent of low-branch attenuation.\n",pk.status()[27],rm.status()[27]);}
void routesAndResponse(){unsigned graphCases=0;double maxDb=0,maxPhase=0;
 for(int base:{0,4,5}){auto s=professional(base,10);s[4]=s[10]=s[26]=s[28]=s[30]=s[31]=1;s[5]=20;s[6]=100;s[8]=-200;s[9]=20;s[15]=20;s[16]=500;s[27]=700;s[29]=-20;Core diagram;diagram.set(s);const auto points=diagram.response();assert(points[5]==1&&points[6]==32);
  for(unsigned index:{0u,5u,10u,15u,22u,31u}){const int hz=points[8+6*index];Core c;c.set(s);auto input=signal(48000,96000,hz,.01,true);const auto output=c.render(input);const auto h=amplitude(output,48000,hz,48000,48000)/amplitude(input,48000,hz,48000,48000);const double err=std::abs(2000*std::log10(std::abs(h))-points[9+6*index]);maxDb=std::max(maxDb,err);
   const double phaseError=std::abs(std::remainder(std::arg(h)*180000/pi-points[10+6*index],360000.));maxPhase=std::max(maxPhase,phaseError);if(err>=3||phaseError>=300)std::printf("Response mismatch base%d hz%d err%.6g phase%.6g\n",base,hz,err,phaseError);assert(err<3&&phaseError<300);++graphCases;}
  auto raw=s;raw[27]=0;Core dry;dry.set(raw);const auto input=signal(48000,5000,733,.1),out=dry.render(input);const unsigned delay=base==4?0:3;for(unsigned i=1000;i<5000;++i)for(unsigned ch=0;ch<2;++ch)assert(std::abs(out[2*i+ch]-input[2*(i-delay)+ch]*std::pow(10.,-2./20.))<1e-8);
  Core original,effect,diff;for(auto* c:{&original,&effect,&diff})c->set(s);assert(original.tools(1,s,s)&&effect.tools(2,s,s)&&diff.tools(3,s,s));const auto r=original.render(input),w=effect.render(input),d=diff.render(input);for(unsigned i=1000;i<5000;++i)assert(std::abs(w[2*i]-r[2*i]-d[2*i])<1e-7);
  Core low,detector;low.set(s);detector.set(s);assert(low.tools(7,s,s)&&detector.tools(8,s,s));assert(rms(low.render(input),1000)>0&&rms(detector.render(input),1000)>0);
 }
 std::printf("Dynamic analytic mono-response vs %u actual sine renders: max%.6g centidB / %.6g millidegrees; normal/Deep dry0 exact fixed3/0samples, postmix effect/difference and both Solo routes verified.\n",graphCases,maxDb,maxPhase);}
void adaptiveDimensions(){
 auto base=professional(0,20);base[17]=base[30]=1;base[18]=1;base[19]=20;base[20]=250;base[21]=-300;base[22]=0;base[23]=180;base[24]=10;base[25]=50;
 const auto steady=signal(48000,96000,100,.15,true);Core reference;reference.set(base);reference.render(steady);const int original=reference.status()[27];assert(original>500);
 unsigned checked=0;for(const auto [index,value]:std::array<std::pair<unsigned,int>,5>{{{19,200},{20,60},{21,-180},{22,120},{23,30}}}){
  auto s=base;s[index]=value;Core changed;changed.set(s);changed.render(steady);const int reduction=changed.status()[27];
  if(index==22){ // Put the detector inside the soft knee instead of saturation.
   auto hard=base;hard[21]=reference.status()[28]/10;auto soft=hard;soft[22]=120;Core h,k;h.set(hard);k.set(soft);h.render(steady);k.render(steady);assert(k.status()[27]>h.status()[27]+100);
  }else assert(std::abs(reduction-original)>100);++checked;
 }
 auto fast=base,slow=base;fast[24]=10;slow[24]=2000;Core f,s;f.set(fast);s.set(slow);const auto quiet=signal(48000,48000,100,.001,true);f.render(quiet);s.render(quiet);const auto onset=signal(48000,4800,100,.15,true);f.render(onset);s.render(onset);assert(f.status()[27]>s.status()[27]+300);++checked;
 fast=base;slow=base;fast[25]=50;slow[25]=2000;Core r1,r2;r1.set(fast);r2.set(slow);r1.render(steady);r2.render(steady);const auto tail=signal(48000,24000,100,.001,true);r1.render(tail);r2.render(tail);assert(r2.status()[27]>r1.status()[27]+300);++checked;
 // Main-band independence: a strong low note recovers enhancement while the
 //5kHz fundamental stays at its original high-band amplitude.
 auto both=signal(48000,96000,80,.15,true),high=signal(48000,96000,5000,.03,true);for(unsigned i=0;i<both.size();++i)both[i]+=high[i];Core controlled,untouched;controlled.set(base);auto disabled=base;disabled[17]=0;untouched.set(disabled);const auto a=controlled.render(both),b=untouched.render(both);
 const double highRatio=std::abs(amplitude(a,48000,5000,48000,48000)/amplitude(b,48000,5000,48000,48000));const double lowRatio=std::abs(amplitude(a,48000,80,48000,48000)/amplitude(b,48000,80,48000,48000));
 Core lowControlled,lowOriginal;lowControlled.set(base);lowOriginal.set(disabled);assert(lowControlled.tools(7,base,base)&&lowOriginal.tools(7,disabled,disabled));const auto la=lowControlled.render(both),lb=lowOriginal.render(both);
 const double branchRatio=std::abs(amplitude(la,48000,80,48000,48000)/amplitude(lb,48000,80,48000,48000));assert(std::abs(20*std::log10(highRatio))<.01&&branchRatio<.5);
 std::printf("Dynamic %u adaptive numeric dimensions measured: detector HP/LP, threshold, knee, max, attack, release; 5kHz main-band change%.7gdB,80Hz low-branch ratio%.7g (phase-dependent full-output ratio%.7g).\n",checked,20*std::log10(highRatio),branchRatio,lowRatio);
}
constexpr std::array<S,4> factories{{
 {1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,20,250,-180,60,60,100,250,0,1000,0,0,0,0,0,0,0,0},
 {1,1,0,12,1,-10,100,-20,0,0,1,40,0,20,0,20,500,1,1,25,220,-240,60,60,100,300,1,700,1,-20,0,0,0,0,0,0},
 {1,1,4,18,1,0,0,0,-100,-10,1,0,0,0,15,25,200,1,0,25,180,-180,30,80,30,180,1,650,1,-30,0,0,0,0,0,0},
 {1,1,2,15,1,0,150,0,0,0,1,0,0,15,0,20,650,1,1,20,250,-300,80,90,150,500,1,800,1,-30,0,0,0,0,0,0}}};
void ratesStressFailure(){unsigned cases=0;double peak=0;
 for(unsigned fs:{8000u,8001u,16000u,22050u,32000u,44100u,48000u,96000u,192000u})for(int base=0;base<6;++base)for(int side:{0,1}){
  auto s=professional(base,side?100:0);s[4]=s[10]=s[17]=s[26]=s[28]=s[30]=s[31]=1;s[5]=side?120:-120;s[6]=s[7]=s[8]=side?1000:-1000;s[9]=side?240:-240;s[11]=side?1900:-1900;s[12]=side?500:-500;s[13]=side?1000:-1000;s[14]=side?145:-35;s[15]=side?300:0;s[16]=side?20000:100;s[19]=side?500:20;s[20]=side?2000:60;s[21]=side?-60:-600;s[22]=side?120:0;s[23]=side?180:0;s[24]=side?2000:10;s[25]=side?2000:50;s[27]=side?1000:0;s[29]=side?0:-240;
  Core c(fs);c.set(s);auto out=c.render(signal(fs,6000,fs*.37,.8));for(float x:out)peak=std::max(peak,std::abs(double(x)));const auto v=c.status();assert(v[2]==int(fs)&&v[25]);for(unsigned i:{32u,33u,34u,35u,36u,37u,43u,44u})assert(v[i]>=0&&v[i]<int(fs/2));assert(v[15]==(base==4)&&v[47]==(base==4?0:3));const auto graph=c.response();assert(graph[5]==1);for(unsigned i=0;i<32;++i)assert(graph[8+6*i]<=int(fs*.45));++cases;
 }
 for(unsigned fs:{8000u,16000u,44100u,48000u,96000u,192000u}){Core c(fs);for(const auto& s:factories){assert(dynamictuning::valid(s.data(),36));c.set(s);c.render(signal(fs,fs*2,83,.05));assert(c.status()[2]==int(fs)&&c.status()[25]);}
  auto initial=professional(0);c.set(initial);c.render(signal(fs,fs));auto latest=professional(5);latest[10]=1;latest[11]=300;
  for(unsigned i=0;i<12;++i){auto temporary=professional(i%6);c.set(temporary);c.render(signal(fs,31));}c.set(latest);c.render(signal(fs,100));c.dsp.ClearTools();c.render(signal(fs,fs));assert(c.status()[13]==5&&c.status()[32]==1500);
  c.dsp.SetEnable(false);c.dsp.SetEnable(true);c.render(signal(fs,3000));assert(c.status()[13]==5);c.dsp.SetSamplingRate(fs==48000?44100:48000);assert(c.status()[25]&&c.status()[5]==0);
 }
 Core left,right;auto s=professional(0);s[4]=1;s[5]=30;left.set(s);right.set(s);auto in=signal(48000,12000);assert(left.render(in)==right.render(in));auto changed=s;changed[2]=4;dynamictuning::failAllocationForTest(0);assert(!left.dsp.SetProfessional(changed.data(),36));assert(left.status()[13]==0);in=signal(48000,4000,411);assert(left.render(in)==right.render(in));
 dynamictuning::failAllocationForTest(0);left.dsp.SetSamplingRate(8000);assert(!left.status()[25]);left.set(s);assert(left.status()[25]&&left.status()[2]==8000);
 std::vector<float> nonfinite(4000);nonfinite[0]=NAN;nonfinite[1]=INFINITY;left.render(nonfinite);left.render(signal(8000,8000));assert(peak<2000);
 std::printf("Dynamic %u endpoint/Fs/base cases8..192kHz finite, original invalid X-upper guarded, all4 factory packets and latest-wins prewarm/crossfade/lifecycle/rate retry passed; peak%.7g.\n",cases,peak);}
void pendingContinuity(){constexpr unsigned fs=8000;auto first=professional(0,15),target=professional(4,25);target[10]=1;target[14]=40;Core changing(fs),reference(fs);changing.set(first);reference.set(first);auto before=signal(fs,fs,50,.05);changing.render(before);reference.render(before);changing.set(target);
 auto early=signal(fs,fs/4,50,.05);const auto held=changing.render(early,7),gold=reference.render(early,7);assert(held==gold);changing.dsp.ClearTools();auto next=signal(fs,fs/8,50,.05);assert(changing.render(next,11)==reference.render(next,11));
 changing.render(signal(fs,fs*2,50,.05));assert(changing.status()[13]==4&&changing.status()[47]==0);std::puts("Dynamic8kHz pending target receives live input for full500ms; ClearTools retains old audible path and cannot publish an unwarmed preset; latest target completes after crossfade.");}
void abMeters(){Core c;auto s=professional(0,15);s[30]=s[31]=1;c.set(s);auto a=professional(0,5),b=factories[2];assert(c.tools(6,a,b,true,17));c.render(signal(48000,12000,80,.1,true));auto before=c.status();assert(c.tools(6,a,b,true,17));assert(c.status()[1]==before[1]&&c.status()[29]==before[29]);c.render(signal(48000,180000,80,.1,true));auto v=c.status();assert(v[7]==3&&v[8]==2000&&v[9]<=0&&v[10]<=0);const int ga=v[9],gb=v[10];
 s[30]=0;c.set(s);assert(c.status()[7]==3&&c.status()[9]==ga&&c.status()[10]==gb&&c.status()[16]==INT32_MIN);s[30]=1;c.set(s);assert(c.status()[7]==3);
 assert(c.tools(4,a,b,true,17));auto outA=c.render(signal(48000,48000,80,.1,true));assert(c.tools(5,a,b,true,17));auto outB=c.render(signal(48000,48000,80,.1,true));const double rA=rms(outA,24000),rB=rms(outB,24000);assert(std::abs(20*std::log10(rA/rB))<.05);
 std::array<int32_t,76> packet{4,1,18,0};std::copy(b.begin(),b.end(),packet.begin()+4);std::copy(a.begin(),a.end(),packet.begin()+40);dynamictuning::failAllocationForTest(1);assert(!c.dsp.SetTools(packet.data(),76));assert(c.status()[11]==17&&c.status()[5]==5);
 c.dsp.ClearTools();assert(c.status()[5]==0&&c.status()[7]==0&&c.status()[16]==INT32_MIN);Core silent;silent.set(s);assert(silent.tools(6,a,b,true));silent.render(std::vector<float>(48000*8));assert(silent.status()[7]==4);
 std::printf("Dynamic same-input warm+2s A/B matched audible RMS %.7g/%.7g (%.6gdB); meter toggle preserves epoch/fixed attenuations, second-model allocation rolls back, insufficient signal and cleanup verified.\n",rA,rB,20*std::log10(rA/rB));}
#ifndef DYNAMIC_CORE_ONLY
struct Effect {
 effect_handle_t h{};effect_uuid_t uuid{0x30306067,0x9400,0x4d53,0x9000,{0,0,0,0,0,1}};
 Effect(){assert(AELI.create_effect(&uuid,0,0,&h)==0);rate(48000);assert(command(EFFECT_CMD_ENABLE)==0);}
 ~Effect(){AELI.release_effect(h);}
 int command(unsigned id,unsigned n=0,void* p=nullptr){int32_t r=0;unsigned count=4;const auto rc=(*h)->command(h,id,n,p,&count,&r);return rc?rc:r;}
 int raw(unsigned id,const void* data,unsigned n,unsigned extra=0){std::array<uint32_t,100> p{};p[1]=4;p[2]=n;p[3]=id;std::memcpy(p.data()+4,data,n);return command(EFFECT_CMD_SET_PARAM,16+n+extra,p.data());}
 void rate(unsigned fs){effect_config_t c{};c.inputCfg.samplingRate=c.outputCfg.samplingRate=fs;c.inputCfg.channels=c.outputCfg.channels=AUDIO_CHANNEL_OUT_STEREO;c.inputCfg.format=c.outputCfg.format=AUDIO_FORMAT_PCM_FLOAT;c.inputCfg.accessMode=EFFECT_BUFFER_ACCESS_READ;c.outputCfg.accessMode=EFFECT_BUFFER_ACCESS_WRITE;c.inputCfg.mask=c.outputCfg.mask=EFFECT_CONFIG_ALL;assert(command(EFFECT_CMD_SET_CONFIG,sizeof(c),&c)==0);}
 template<unsigned N>auto get(unsigned id){std::array<uint32_t,4> q{0,4,N*4,id};std::array<uint32_t,N+4> r{};unsigned n=unsigned(r.size()*4);const int rc=(*h)->command(h,EFFECT_CMD_GET_PARAM,16,q.data(),&n,r.data());assert(rc==0||rc==-EAGAIN);std::array<int32_t,N> v{};if(!rc){assert(!r[0]&&n==(N+4)*4);std::memcpy(v.data(),r.data()+4,N*4);}return v;}
 int invalidCombined(unsigned words,unsigned capacity){std::array<uint32_t,4> q{0,4,words*4,131230};std::array<uint32_t,252> r{};unsigned n=capacity;const int rc=(*h)->command(h,EFFECT_CMD_GET_PARAM,16,q.data(),&n,r.data());return rc?rc:int32_t(r[0]);}
 void render(){auto in=signal(48000,512,83,.1,true);std::array<float,1024> out{};audio_buffer_t a{},b{};a.frameCount=b.frameCount=512;a.f32=in.data();b.f32=out.data();allocations=releases=0;watch=true;assert((*h)->process(h,&a,&b)==0);watch=false;assert(!allocations&&!releases);for(float x:out)assert(std::isfinite(x)&&std::abs(x)<=1);}
};
void protocol(){Effect e;auto s=factories[1];s[30]=s[31]=1;assert(e.raw(65655,s.data(),144)==0);int32_t enabled=1;assert(e.raw(65569,&enabled,4)==0);e.render();auto v=e.get<48>(131228);assert(v[0]==1&&!(v[1]&1)&&v[3]&&v[4]&&v[25]&&v[13]==0&&v[14]==12);auto response=e.get<200>(131229);assert(response[0]==1&&response[5]==1&&response[6]==32);
 auto combined=e.get<248>(131230);assert(combined[0]==1&&combined[48]==1&&combined[1]==combined[49]&&!(combined[1]&1)&&combined[2]==combined[50]&&combined[15]==combined[51]);assert(e.invalidCombined(247,1008)!=0&&e.invalidCombined(249,1008)!=0&&e.invalidCombined(248,1004)!=0);
 assert(e.raw(65655,s.data(),140)!=0&&e.raw(65655,s.data(),144,4)!=0);auto bad=s;bad[35]=1;assert(e.raw(65655,bad.data(),144)!=0);
 std::array<int32_t,76> tools{3,0,1,0};std::copy(s.begin(),s.end(),tools.begin()+4);std::copy(s.begin(),s.end(),tools.begin()+40);assert(e.raw(65656,tools.data(),304)==0);assert(e.raw(65656,tools.data(),300)!=0&&e.raw(65656,tools.data(),304,4)!=0);int32_t clear=1;assert(e.raw(65657,&clear,4)!=0);clear=0;assert(e.raw(65657,&clear,4)==0);
 for(unsigned fs:{8000u,16000u,22050u,32000u,44100u,48000u,96000u,192000u}){e.rate(fs);assert(e.raw(65655,s.data(),144)==0);e.render();v=e.get<48>(131228);assert(v[2]==int(fs)&&v[5]==0&&v[25]&&v[32]<int(fs/2));}
 // Other modules' tools may not clear Dynamic's temporary state.
 e.rate(48000);assert(e.raw(65656,tools.data(),304)==0);assert(e.raw(65654,&clear,4)==0);assert(e.get<48>(131228)[5]==3);
 std::atomic<bool> done=false;std::thread audio([&]{while(!done)e.render();});for(unsigned i=0;i<30;++i){auto next=factories[i%4];next[30]=next[31]=1;assert(e.raw(65655,next.data(),144)==0);v=e.get<48>(131228);if(v[0])assert(!(v[1]&1)&&v[13]>=0&&v[13]<6&&v[47]<=3);response=e.get<200>(131229);if(response[0])assert(!(response[1]&1)&&response[6]==32);combined=e.get<248>(131230);if(combined[0])assert(combined[48]==1&&combined[1]==combined[49]&&!(combined[1]&1)&&combined[2]==combined[50]&&combined[15]==combined[51]&&combined[54]==32);}done=true;audio.join();assert(e.command(EFFECT_CMD_DISABLE)==0);v=e.get<48>(131228);assert(v[5]==0&&v[16]==INT32_MIN);
 std::puts("Dynamic Fusion strict sizes/flags/reserved/order, runtime48/response200 and compound248 coherent concurrent reads, compound size/epoch/Fs/path consistency, actualViPER8..192kHz, other module independence, callback allocation guard and disable cleanup passed.");}
#endif
}
int main(int argc,char**){std::setvbuf(stdout,nullptr,_IONBF,0);validation();compatibility();lowFsCompatibility();effectiveControls();adaptive();adaptiveDimensions();routesAndResponse();ratesStressFailure();pendingContinuity();abMeters();
#ifndef DYNAMIC_CORE_ONLY
 if(argc==1)protocol();
#endif
 std::puts("Dynamic native checks passed.");}
