#include <algorithm>
#include <array>
#include <atomic>
#include <cassert>
#include <cmath>
#include <complex>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <new>
#include <thread>
#include <vector>
#include "Analog32Reference.h"
#include "../viper/src/viper/effects/AnalogX.h"
#include "../viper/src/viper/effects/TubeSimulator.h"
#include "essential.h"
extern "C" {extern audio_effect_library_t AELI;}
thread_local bool watch=false;thread_local unsigned allocations=0,releases=0;
void* operator new(size_t n){if(watch)++allocations;if(auto* p=std::malloc(n))return p;throw std::bad_alloc();}
void* operator new[](size_t n){return ::operator new(n);}
void operator delete(void* p)noexcept{if(watch&&p)++releases;std::free(p);}
void operator delete[](void* p)noexcept{::operator delete(p);}
void operator delete(void* p,size_t)noexcept{::operator delete(p);}
void operator delete[](void* p,size_t)noexcept{::operator delete(p);}
namespace {
using S=analogtuning::Settings;
constexpr double pi=3.14159265358979323846;
S core(){auto s=analogtuning::defaults;s[1]=s[3]=1;return s;}
std::vector<float> signal(unsigned rate,unsigned frames,double hz=1000,double amplitude=.4){std::vector<float> v(frames*2);
 for(unsigned i=0;i<frames;++i){v[2*i]=float(amplitude*std::sin(2*pi*hz*i/rate));v[2*i+1]=float(amplitude*.8*std::sin(2*pi*hz*i/rate+.31));}return v;}
struct Core {
 AnalogX analog;unsigned rate;
 explicit Core(unsigned fs=48000):rate(fs){analog.SetSamplingRate(fs);analog.SetEnable(true);}
 void set(const S& s){assert(analog.SetProfessional(s.data(),s.size()));}
 auto status()const{std::array<int32_t,40> v;analog.GetRuntime(v.data());return v;}
 bool tools(int mode,const S& a,const S& b,bool match=false,int epoch=1){std::array<int32_t,60> v{mode,int(match),epoch,0};std::copy(a.begin(),a.end(),v.begin()+4);std::copy(b.begin(),b.end(),v.begin()+32);return analog.SetTools(v.data(),v.size());}
 std::vector<float> render(const std::vector<float>& in,unsigned chunk=173){auto out=in;
  for(size_t pos=0;pos<out.size()/2;pos+=chunk){const auto n=std::min(size_t(chunk),out.size()/2-pos);allocations=releases=0;watch=true;analog.Process(out.data()+pos*2,uint32_t(n));watch=false;assert(!allocations&&!releases);}
  for(float v:out)assert(std::isfinite(v));return out;
 }
 void warm(unsigned n=48000){render(std::vector<float>(n*2));}
};
std::complex<double> amplitude(const std::vector<float>& v,unsigned rate,double hz,unsigned start,unsigned count,unsigned channel=0){std::complex<double> result{};
 for(unsigned i=0;i<count;++i)result+=double(v[2*(start+i)+channel])*std::polar(1.,-2*pi*hz*(start+i)/rate);return result*(2./count);}
void validation(){assert(analogtuning::valid(analogtuning::defaults.data(),28));assert(!analogtuning::valid(nullptr,28));assert(!analogtuning::valid(analogtuning::defaults.data(),27));
 for(unsigned i=0;i<28;++i){auto s=analogtuning::defaults;s[i]=analogtuning::minimum[i]-1;assert(!analogtuning::valid(s.data(),28));s=analogtuning::defaults;s[i]=analogtuning::maximum[i]+1;assert(!analogtuning::valid(s.data(),28));}
 auto s=analogtuning::defaults;s[23]=3;assert(!analogtuning::valid(s.data(),28));
 AnalogX fresh;std::array<int32_t,40> v;fresh.GetRuntime(v.data());assert(v[0]==1&&v[2]==44100&&v[13]==1&&v[14]==0&&v[30]==2000&&v[31]==0&&v[32]==20000&&v[33]==100&&v[34]==1000&&v[35]==0);
 for(unsigned i=36;i<40;++i)assert(v[i]==0);
}
void compatibility(){unsigned cases=0;
 for(unsigned rate:{44100u,48000u,96000u,192000u})for(int base:{0,1,2})for(bool pro:{false,true}){
  Core actual(rate);Analog32Reference ref;ref.SetSamplingRate(rate);ref.SetProcessingModel(base);ref.SetEnable(true);auto s=analogtuning::defaults;s[1]=pro;s[2]=base;actual.set(s);
  auto input=signal(rate,16000,713,.4),gold=input;const auto out=actual.render(input,17);ref.Process(gold.data(),16000);assert(out==gold);
  // Inactive saved values, meters and no-op tool epochs cannot touch audio state.
  s[4]=180;s[5]=0;s[6]=-50000;s[7]=50000;s[8]=-50000;s[9]=50000;s[11]=4000;s[12]=2000;s[13]=4000;s[14]=300;s[15]=20;s[16]=-600;s[17]=8000;s[19]=0;s[21]=-240;s[23]=4;s[24]=1;actual.set(s);
  assert(actual.tools(0,s,s,false,70)&&actual.tools(0,s,s,false,71));input=signal(rate,8000,997,.2);gold=input;ref.Process(gold.data(),8000);const auto continued=actual.render(input,509);if(continued!=gold){std::printf("Compatibility mismatch Fs%u base%d pro%d\n",rate,base,int(pro));for(unsigned i=0;i<gold.size();++i)if(continued[i]!=gold[i]){std::printf("first sample%u actual%.9g expected%.9g\n",i,continued[i],gold[i]);break;}}assert(continued==gold);
  s[1]=!s[1];actual.set(s);input=signal(rate,8000,1997,.15);gold=input;ref.Process(gold.data(),8000);assert(actual.render(input,1)==gold);
  // Every group enabled with its current-base original values is also neutral.
  s=analogtuning::defaults;s[2]=base;s[1]=s[3]=s[10]=s[18]=s[20]=s[24]=1;actual.set(s);input=signal(rate,8000,411,.6);gold=input;ref.Process(gold.data(),8000);assert(actual.render(input)==gold);
  actual.analog.SetEnable(false);ref.SetEnable(false);assert(actual.render(input)==input);actual.analog.SetEnable(true);ref.SetEnable(true);gold=input;ref.Process(gold.data(),8000);assert(actual.render(input)==gold);++cases;
 }
 std::printf("Analog frozen ui32: %u base/Fs/pro cases bit-identical, continuous inactive controls, meters, neutral groups, block sizes, disable/re-enable.\n",cases);
}
void lowRates(){unsigned cases=0;double peak=0;
 for(unsigned fs:{8000u,16000u,22050u,32000u})for(int base:{0,1,2}){
  Core c(fs);auto s=analogtuning::defaults;s[2]=base;c.set(s);const auto out=c.render(signal(fs,fs*2,fs*.21,.8));auto v=c.status();assert(v[26]<int(fs/2)&&v[15]<int(fs/2)&&v[27]<int(fs/2));
  for(float x:out)peak=std::max(peak,std::abs(double(x)));assert(peak<3);++cases;
 }
 std::printf("Analog low-Fs guard: %u original-base cases at8/16/22.05/32kHz remain finite and stable with all effective frequencies below Nyquist; peak%.7g.\n",cases,peak);
}
void harmonicsAndIntermod(){
 auto reference=core();Core original;original.set(reference);const auto in=signal(48000,96000,1000,.6),base=original.render(in);std::array<double,4> change{};
 for(unsigned order=2;order<=5;++order){auto s=reference;s[4+order]=20000;Core c;c.set(s);const auto out=c.render(in);const auto delta=amplitude(out,48000,order*1000,48000,48000)-amplitude(base,48000,order*1000,48000,48000);change[order-2]=std::abs(delta);assert(change[order-2]>1e-4);
  assert(c.status()[30+order]==(order==2?40000:order==3?20100:order==4?21000:20000));
  s[4+order]=-20000;Core opposite;opposite.set(s);const auto negative=opposite.render(in);const auto oppositeDelta=amplitude(negative,48000,order*1000,48000,48000)-amplitude(base,48000,order*1000,48000,48000);assert(std::abs(delta+oppositeDelta)<1e-6);
 }
 auto two=signal(48000,96000,1000,.25),second=signal(48000,96000,1300,.25);for(unsigned i=0;i<two.size();++i)two[i]+=second[i];auto s=reference;s[6]=50000;Core c,plain;c.set(s);plain.set(reference);const auto coloured=c.render(two),normal=plain.render(two);
 const double im=std::abs(amplitude(coloured,48000,300,48000,48000)-amplitude(normal,48000,300,48000,48000));assert(im>1e-4);
 std::printf("Analog measured H2/H3/H4/H5 coefficient-delta components: %.7g %.7g %.7g %.7g; signed deltas reverse phase;1k+1.3kHz intermodulation delta300Hz=%.7g.\n",change[0],change[1],change[2],change[3],im);
}
void aliasReduction(){std::array<double,3> alias{};unsigned n=0;
 for(unsigned factor:{1u,2u,4u}){auto s=core();s[9]=50000;s[22]=factor>1;s[23]=factor==1?2:factor;Core c;c.set(s);const auto out=c.render(signal(48000,96000,9000,.8));alias[n++]=std::abs(amplitude(out,48000,3000,48000,48000));}
 const double reduction2=20*std::log10(alias[0]/alias[1]),reduction4=20*std::log10(alias[0]/alias[2]);assert(reduction2>20&&reduction4>20);
 std::printf("Analog9kHz H5 folded3kHz component:1x=%.7g2x=%.7g4x=%.7g; reductions%.2f/%.2fdB.\n",alias[0],alias[1],alias[2],reduction2,reduction4);
 // Quality is available on the original nonlinear path, without core takeover.
 auto s=analogtuning::defaults;s[1]=s[22]=1;s[23]=4;Core q;q.set(s);q.render(signal(48000,5000));assert(q.status()[13]==4&&q.status()[14]==31);
}
void bandToneAndGain(){
 auto input=signal(48000,30000,50,.08);for(double hz:{630.,2000.,9000.,18000.}){const auto part=signal(48000,30000,hz,.08);for(unsigned i=0;i<input.size();++i)input[i]+=part[i];}
 auto s=core();s[3]=0;s[10]=1;Core reference;reference.set(s);const auto base=reference.render(input);
 const std::array<int,7> values{4000,1500,-10000,1500,1500,400,500};
 for(unsigned n=0;n<7;++n){auto changed=s;changed[11+n]=values[n];Core c;c.set(changed);const auto out=c.render(input);double error=0;for(unsigned i=10000;i<30000;++i){const double d=out[2*i]-base[2*i];error+=d*d;}assert(std::sqrt(error/20000)>1e-7);}
 s[20]=1;s[21]=-60;Core attenuated;attenuated.set(s);const auto low=attenuated.render(input);const double factor=std::pow(10.,-6./20.);for(unsigned i=10000;i<30000;++i)assert(std::abs(low[2*i]-base[2*i]*factor)<1e-7);
 std::puts("Analog all7 independent tone controls alter response with core disabled; -6dB additive output gain is measured and retains original0.8 stage.");
}
void delayMixAndRoutes(){
 for(unsigned factor:{1u,2u,4u}){auto s=core();s[18]=1;s[19]=0;s[22]=factor>1;s[23]=factor==1?2:factor;Core c;c.set(s);const auto in=signal(48000,4000,733,.2),out=c.render(in);const unsigned delay=factor==1?0:31;assert(c.status()[13]==int(factor)&&c.status()[14]==int(delay));
  for(unsigned i=0;i<4000;++i)for(unsigned ch=0;ch<2;++ch)assert(out[2*i+ch]==(i<delay?0:in[2*(i-delay)+ch]));}
 auto s=core();s[22]=1;s[23]=4;s[18]=1;s[19]=300;Core raw,wet,diff;for(auto* c:{&raw,&wet,&diff})c->set(s);
 assert(raw.tools(1,s,s)&&wet.tools(2,s,s)&&diff.tools(3,s,s));const auto in=signal(48000,10000,3000,.4),r=raw.render(in),w=wet.render(in),d=diff.render(in);
 for(unsigned i=1000;i<10000;++i)for(unsigned ch=0;ch<2;++ch)assert(std::abs(w[2*i+ch]-r[2*i+ch]-d[2*i+ch])<1e-6);
 std::array<std::complex<double>,3> h2{};unsigned n=0;
 for(unsigned factor:{1u,2u,4u}){auto v=core();v[6]=20000;v[22]=factor>1;v[23]=factor==1?2:factor;Core c;c.set(v);const auto out=c.render(signal(48000,96000,1000,.4));h2[n++]=amplitude(out,48000,2000,48000,48000);}
 for(unsigned i=1;i<3;++i){const auto compensated=h2[i]*std::polar(1.,2*pi*2000*31/48000)/h2[0];assert(std::abs(std::arg(compensated))<.01);assert(std::abs(std::abs(compensated)-1)<.02);}
 std::puts("Analog mix0 is exact aligned raw;1x delay0,2/4x31samples; original/effect/difference and independent harmonic phase verify FIR alignment.");
}
void endpoints(){unsigned cases=0;double peak=0;
 for(unsigned fs:{8000u,8001u,16000u,22050u,32000u,44100u,48000u,96000u,192000u})for(int side:{0,1})for(unsigned factor:{1u,2u,4u}){
  Core c(fs);auto s=core();s[2]=side?2:0;s[4]=side?180:-120;s[5]=side?2000:0;for(unsigned i=6;i<10;++i)s[i]=side?50000:-50000;
  s[10]=s[18]=s[20]=s[24]=1;s[11]=side?4000:20;s[12]=side?2000:300;s[13]=side?4000:-15000;s[14]=side?2000:300;s[15]=side?4000:20;s[16]=side?600:-600;s[17]=side?8000:100;s[19]=side?1000:0;s[21]=side?0:-240;s[22]=factor>1;s[23]=factor==1?2:factor;c.set(s);
  auto out=c.render(signal(fs,10000,fs*.13,1.2));for(float v:out)peak=std::max(peak,std::abs(double(v)));const auto v=c.status();assert(v[25]&&v[26]<int(fs/2)&&v[27]<=int(fs*.45));for(unsigned i=36;i<40;++i)assert(v[i]==0);
  c.analog.Reset();c.render(signal(fs,1000));auto neutral=analogtuning::defaults;neutral[2]=s[2];neutral[1]=neutral[24]=1;c.set(neutral);c.warm(fs/10);c.analog.Reset();
  if(fs>=44100){Analog32Reference ref;ref.SetSamplingRate(fs);ref.SetProcessingModel(s[2]);ref.SetEnable(true);auto in=signal(fs,4000,fs*.07,.4),gold=in;ref.Process(gold.data(),4000);assert(c.render(in)==gold);}++cases;
 }
 assert(peak<20);std::printf("Analog%u full-endpoint/Fs/quality cases finite, including8.001kHz near-Nyquist peak/BW; reset and pro exit restore original paths; endpoint peak%.7g.\n",cases,peak);
 Core nonfinite;auto s=core();s[6]=50000;s[22]=1;nonfinite.set(s);std::vector<float> input(2000);input[0]=NAN;input[1]=INFINITY;input[12]=-INFINITY;nonfinite.render(input);nonfinite.render(signal(48000,2000));
}
const std::array<S,3> factories{{
 {1,1,0,1,20,900,2000,0,-200,0,1,180,717,-1500,717,600,70,5000,1,850,1,-5,1,2,0,0,0,0},
 {1,1,1,1,30,750,4000,100,200,0,1,120,717,-2000,717,550,100,5500,1,750,1,-10,1,4,0,0,0,0},
 {1,1,0,1,0,700,-3000,50,-500,100,1,400,717,0,717,900,20,4000,1,650,1,0,1,2,0,0,0,0}}};
void factoriesStressAndFailure(){
 std::array<std::vector<float>,3> variants;unsigned index=0,cases=0;double peak=0;
 for(const auto& s:factories){assert(analogtuning::valid(s.data(),28));Core c;c.set(s);variants[index++]=c.render(signal(48000,20000,7000,.5));}
 for(unsigned i=0;i<3;++i)for(unsigned j=i+1;j<3;++j){double err=0;for(unsigned k=10000;k<20000;++k)err+=std::abs(variants[i][2*k]-variants[j][2*k]);assert(err/10000>1e-3);}
 for(unsigned rate:{8000u,16000u,44100u,48000u,96000u,192000u}){Core c(rate);for(const auto& s:factories){c.set(s);const auto out=c.render(signal(rate,4096,rate*.21,.8));for(float v:out)peak=std::max(peak,std::abs(double(v)));assert(c.status()[2]==int(rate)&&c.status()[25]);++cases;}
  auto s=core();s[22]=1;for(unsigned cycle=0;cycle<6;++cycle){s[2]=cycle%3;s[23]=2;c.set(s);c.render(signal(rate,19));s[2]=(cycle+1)%3;s[23]=4;c.set(s);s[2]=(cycle+2)%3;s[23]=2;c.set(s);c.render(signal(rate,rate/10));assert(c.status()[31]==s[2]&&c.status()[13]==2&&c.status()[14]==31);}
  s[23]=4;c.set(s);c.analog.ClearTools();c.analog.SetEnable(false);c.analog.SetEnable(true);c.render(signal(rate,1000));assert(c.status()[13]==4&&c.status()[25]);c.analog.SetSamplingRate(rate==48000?44100:48000);assert(c.status()[5]==0&&c.status()[25]);
 }
 Core left,right;auto s=core();s[22]=1;s[6]=4000;left.set(s);right.set(s);const auto first=signal(48000,10000);assert(left.render(first)==right.render(first));auto fail=s;fail[23]=4;
 analogtuning::failAllocationForTest(0);assert(!left.analog.SetProfessional(fail.data(),28));assert(left.status()[13]==2);const auto next=signal(48000,4000,1997);assert(left.render(next)==right.render(next));
 analogtuning::failAllocationForTest(0);left.analog.SetSamplingRate(96000);assert(left.status()[25]==0);left.set(s);assert(left.status()[25]&&left.status()[2]==96000);
 analogtuning::failAllocationForTest(0);left.analog.SetSamplingRate(8000);auto unavailable=left.status();assert(unavailable[25]==0&&unavailable[13]==1&&unavailable[14]==0&&unavailable[15]==0&&unavailable[26]==0&&unavailable[27]==0);left.set(s);assert(left.status()[25]&&left.status()[26]<4000);
 assert(peak<3);std::printf("Analog3 factory responses distinct; %u factory/Fs cases, rapid base/2-to-4-to-2, paused latest target, lifecycle and rate changes; allocation rollback and same-packet rate retry passed; factory peak%.7g.\n",cases,peak);
}
void lowRateContinuousWarmup(){
 constexpr unsigned fs=8000;const auto before=signal(fs,8000,500,.6),after=signal(fs,4000,500,.6);
 for(bool qualityChange:{false,true}){
  auto initial=core();initial[2]=2;initial[10]=1;initial[11]=20;Core changing(fs);changing.set(initial);changing.render(before);
  auto target=initial;target[6]=50000;if(qualityChange){target[22]=1;target[23]=4;}changing.set(target);
  auto actual=changing.render(std::vector<float>(after.begin(),after.begin()+256),7);changing.analog.ClearTools();const auto rest=changing.render(std::vector<float>(after.begin()+256,after.end()),7);actual.insert(actual.end(),rest.begin(),rest.end());
  Core stable(fs);stable.set(target);stable.render(before);const auto expected=stable.render(after,31);Core unchanged(fs);unchanged.set(initial);unchanged.render(before);const auto held=unchanged.render(after);
  // The target gets live input while hidden, and is published only after the
  //700-frame coefficient startup. No second delayed branch onset is allowed.
  // Preserve the running original branch while the new coefficient counter is
  // warming; quality changes also shift the whole running path by31 samples.
  const unsigned delay=qualityChange?31:0;for(unsigned i=100;i<500;++i)assert(actual[2*i]==held[2*(i-delay)]);
  const double measured=std::abs(amplitude(actual,fs,1000,2400,1600)),wanted=std::abs(amplitude(expected,fs,1000,2400,1600));assert(std::abs(measured/wanted-1)<.001);
  double step=0;for(unsigned i=1;i<4000;++i)step=std::max(step,std::abs(double(actual[2*i])-actual[2*(i-1)]));assert(step<.35);
  // Queue a latest quality/base target during an existing tone crossfade. Its
  // complete live prewarm history must survive until promotion.
  auto middle=target;middle[13]=-1200;changing.set(middle);changing.render(signal(fs,19,500,.6));auto latest=target;latest[22]=1;latest[23]=2;latest[2]=1;changing.set(latest);const auto raced=changing.render(after,11);Core finalReference(fs);finalReference.set(latest);finalReference.render(before);const auto finalExpected=finalReference.render(after);const double racedH2=std::abs(amplitude(raced,fs,1000,2400,1600)),finalH2=std::abs(amplitude(finalExpected,fs,1000,2400,1600));assert(std::abs(racedH2/finalH2-1)<.001);assert(changing.status()[13]==2&&changing.status()[31]==1&&changing.status()[32]==70000);
  std::printf("Analog8kHz live candidate warmup coeff/factor-change%d: H2%.7g reference%.7g, max adjacent step%.7g; latest pending history remains current.\n",int(qualityChange),measured,wanted,step);
 }
}
void abMeters(){Core c;auto s=core();s[24]=1;c.set(s);auto a=analogtuning::defaults,b=factories[1];assert(c.tools(6,a,b,true,17));c.render(signal(48000,10000));auto before=c.status();assert(c.tools(6,a,b,true,17));assert(c.status()[1]==before[1]&&c.status()[28]==before[28]);
 c.render(signal(48000,120000));auto v=c.status();assert(v[7]==3&&v[8]==2000&&v[9]<=0&&v[10]<=0&&v[14]==31);const int ga=v[9],gb=v[10];assert(c.tools(6,a,b,false,17));assert(c.status()[7]==3&&c.status()[9]==ga&&c.status()[10]==gb);
 s[24]=0;c.set(s);assert(c.status()[7]==3&&c.status()[9]==ga&&c.status()[10]==gb&&c.status()[16]==INT32_MIN);s[24]=1;c.set(s);assert(c.status()[7]==3&&c.status()[11]==17);
 assert(c.tools(4,a,b,true,17));c.render(signal(48000,48000));assert(c.status()[13]==1&&c.status()[14]==31&&c.status()[9]==ga);assert(c.tools(5,a,b,true,17));c.render(signal(48000,48000));assert(c.status()[13]==4&&c.status()[10]==gb);
 auto aa=factories[0],bb=factories[2];std::array<int32_t,60> packet{4,1,18,0};std::copy(aa.begin(),aa.end(),packet.begin()+4);std::copy(bb.begin(),bb.end(),packet.begin()+32);analogtuning::failAllocationForTest(1);assert(!c.analog.SetTools(packet.data(),60));assert(c.status()[11]==17&&c.status()[5]==5);
 // Same steady signal as the measurement: verify audible outputs, not just gain words.
 assert(c.tools(4,a,b,true,17));const auto matchedA=c.render(signal(48000,48000));assert(c.tools(5,a,b,true,17));const auto matchedB=c.render(signal(48000,48000));
 auto rms=[](const std::vector<float>& v){double sum=0;for(unsigned i=24000;i<48000;++i)for(unsigned ch=0;ch<2;++ch)sum+=double(v[2*i+ch])*v[2*i+ch];return std::sqrt(sum/48000);};
 const double rmsA=rms(matchedA),rmsB=rms(matchedB);assert(std::abs(20*std::log10(rmsA/rmsB))<.01);std::printf("Analog independent matched A/B output RMS: %.7g / %.7g, difference%.6gdB.\n",rmsA,rmsB,20*std::log10(rmsA/rmsB));
 c.analog.ClearTools();v=c.status();assert(v[5]==0&&v[7]==0&&v[14]==0&&v[16]==INT32_MIN);Core silence;silence.set(s);assert(silence.tools(6,a,b,true));silence.warm(130000);assert(silence.status()[7]==4);
 std::puts("Analog A/B legacy snapshots, aligned31samples, same-epoch idempotence, fixed attenuation, insufficient-signal rejection, second-model allocation rollback, meter toggle preservation and cleanup passed.");}
struct Effect {
 effect_handle_t h{};effect_uuid_t uuid{0x30306067,0x9400,0x4d53,0x9000,{0,0,0,0,0,1}};
 Effect(){assert(AELI.create_effect(&uuid,0,0,&h)==0);rate(48000);assert(command(EFFECT_CMD_ENABLE)==0);}
 ~Effect(){AELI.release_effect(h);}
 int command(uint32_t id,uint32_t n=0,void* data=nullptr){int32_t result=0;uint32_t size=4;const auto rc=(*h)->command(h,id,n,data,&size,&result);return rc?rc:result;}
 int raw(uint32_t id,const void* data,unsigned n,unsigned extra=0){std::array<uint32_t,72> packet{};packet[1]=4;packet[2]=n;packet[3]=id;std::memcpy(packet.data()+4,data,n);return command(EFFECT_CMD_SET_PARAM,16+n+extra,packet.data());}
 void rate(unsigned fs){effect_config_t c{};c.inputCfg.samplingRate=c.outputCfg.samplingRate=fs;c.inputCfg.channels=c.outputCfg.channels=AUDIO_CHANNEL_OUT_STEREO;c.inputCfg.format=c.outputCfg.format=AUDIO_FORMAT_PCM_FLOAT;c.inputCfg.accessMode=EFFECT_BUFFER_ACCESS_READ;c.outputCfg.accessMode=EFFECT_BUFFER_ACCESS_WRITE;c.inputCfg.mask=c.outputCfg.mask=EFFECT_CONFIG_ALL;assert(command(EFFECT_CMD_SET_CONFIG,sizeof(c),&c)==0);}
 auto status(unsigned id=131226){std::array<uint32_t,4> q{0,4,160,id};std::array<uint32_t,44> r{};uint32_t n=176;const int rc=(*h)->command(h,EFFECT_CMD_GET_PARAM,16,q.data(),&n,r.data());assert(rc==0||rc==-EAGAIN);std::array<int32_t,40> v{};if(!rc){assert(!r[0]&&n==176);std::memcpy(v.data(),r.data()+4,160);}return v;}
 void render(){std::array<float,1024> in{},out{};for(unsigned i=0;i<512;++i){in[2*i]=float(.4*std::sin(.07*i));in[2*i+1]=float(.3*std::sin(.09*i));}audio_buffer_t a{},b{};a.frameCount=b.frameCount=512;a.f32=in.data();b.f32=out.data();assert((*h)->process(h,&a,&b)==0);for(float v:out)assert(std::isfinite(v)&&std::abs(v)<=1);}
};
void protocol(){Effect e;auto s=factories[1];s[24]=1;assert(e.raw(65652,s.data(),112)==0);const int32_t enabled=1;assert(e.raw(65584,&enabled,4)==0);e.render();auto v=e.status();assert(v[0]==1&&!(v[1]&1)&&v[3]&&v[4]&&v[13]==4&&v[14]==31&&v[25]&&v[31]==1);
 assert(e.raw(65652,s.data(),108)!=0&&e.raw(65652,s.data(),112,4)!=0);auto bad=s;bad[25]=1;assert(e.raw(65652,bad.data(),112)!=0);
 std::array<int32_t,60> tools{3,0,1,0};std::copy(s.begin(),s.end(),tools.begin()+4);std::copy(s.begin(),s.end(),tools.begin()+32);assert(e.raw(65653,tools.data(),240)==0);assert(e.raw(65653,tools.data(),236)!=0&&e.raw(65653,tools.data(),240,4)!=0);
 int32_t clear=1;assert(e.raw(65654,&clear,4)!=0);clear=0;assert(e.raw(65654,&clear,4)==0);
 for(unsigned fs:{8000u,16000u,22050u,32000u,44100u,48000u,96000u,192000u}){e.rate(fs);assert(e.raw(65652,s.data(),112)==0);e.render();v=e.status();assert(v[2]==int(fs)&&v[5]==0&&v[25]&&v[26]<int(fs/2));}
 int32_t base=2;assert(e.raw(65585,&base,4)==0);e.render();v=e.status();assert(v[31]==2);base=3;assert(e.raw(65585,&base,4)!=0);
 // Both nonlinear modules may run simultaneously, with separate tools and settings.
 auto tube=tubetuning::defaults;tube[1]=tube[2]=tube[15]=tube[17]=1;tube[16]=2;tube[3]=60;
 assert(e.raw(65649,tube.data(),80)==0&&e.raw(65583,&enabled,4)==0);e.render();assert(e.status()[13]==4&&e.status()[31]==2);
 std::array<int32_t,44> tt{3,0,2,0};std::copy(tube.begin(),tube.end(),tt.begin()+4);std::copy(tube.begin(),tube.end(),tt.begin()+24);assert(e.raw(65650,tt.data(),176)==0);
 assert(e.raw(65653,tools.data(),240)==0);assert(e.raw(65651,&clear,4)==0);assert(e.status()[5]==3);assert(e.raw(65654,&clear,4)==0);
 std::atomic<bool> done=false;std::thread audio([&]{while(!done)e.render();});
 for(unsigned i=0;i<40;++i){auto next=factories[i%3];next[24]=1;assert(e.raw(65652,next.data(),112)==0);v=e.status();if(v[0])assert(!(v[1]&1)&&v[14]<=64&&v[13]>=1&&v[13]<=4&&v[31]>=0&&v[31]<=2);}
 done=true;audio.join();assert(e.command(EFFECT_CMD_DISABLE)==0);v=e.status();assert(v[5]==0&&v[16]==INT32_MIN);
 std::puts("Analog Fusion strict lengths/reserved/base validation, coherent concurrent snapshot, actualViPER8..192kHz, Tube independent settings/tools and disable cleanup passed.");}
}
int main(){std::setvbuf(stdout,nullptr,_IONBF,0);validation();compatibility();lowRates();harmonicsAndIntermod();aliasReduction();bandToneAndGain();delayMixAndRoutes();endpoints();factoriesStressAndFailure();lowRateContinuousWarmup();abMeters();protocol();std::puts("Analog native checks passed.");}
