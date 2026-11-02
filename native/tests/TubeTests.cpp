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
#include "Tube31Reference.h"
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
using S=tubetuning::Settings;
constexpr double pi=3.14159265358979323846;
S core(){auto s=tubetuning::defaults;s[1]=s[2]=s[6]=1;s[3]=120;s[4]=800;s[7]=0;return s;}
std::vector<float> signal(unsigned rate,unsigned frames,double hz=1000,double amplitude=.3){std::vector<float> v(frames*2);
 for(unsigned i=0;i<frames;++i){v[2*i]=float(amplitude*std::sin(2*pi*hz*i/rate));v[2*i+1]=float(amplitude*.8*std::sin(2*pi*hz*i/rate+.31));}return v;}
struct Core {
 TubeSimulator tube;unsigned rate;
 explicit Core(unsigned fs=48000):rate(fs){tube.SetSamplingRate(fs);tube.SetEnable(true);}
 void set(const S& s){assert(tube.SetProfessional(s.data(),s.size()));}
 auto status()const{std::array<int32_t,32> v;tube.GetRuntime(v.data());return v;}
 bool tools(int mode,const S& a,const S& b,bool match=false,int epoch=1){std::array<int32_t,44> v{mode,int(match),epoch,0};std::copy(a.begin(),a.end(),v.begin()+4);std::copy(b.begin(),b.end(),v.begin()+24);return tube.SetTools(v.data(),v.size());}
 std::vector<float> render(const std::vector<float>& in,unsigned chunk=173){auto out=in;
  for(size_t pos=0;pos<out.size()/2;pos+=chunk){const auto n=std::min(size_t(chunk),out.size()/2-pos);allocations=releases=0;watch=true;tube.TubeProcess(out.data()+pos*2,uint32_t(n));watch=false;assert(!allocations&&!releases);}
  for(float v:out)assert(std::isfinite(v));return out;
 }
 void warm(unsigned n=48000){render(std::vector<float>(n*2));}
};
std::complex<double> amplitude(const std::vector<float>& v,unsigned rate,double hz,unsigned start,unsigned count,unsigned channel=0){std::complex<double> result{};
 for(unsigned i=0;i<count;++i)result+=double(v[2*(start+i)+channel])*std::polar(1.,-2*pi*hz*(start+i)/rate);return result*(2./count);}
double rms(const std::vector<float>& v,unsigned start){double sum=0;for(size_t i=start*2;i<v.size();++i)sum+=double(v[i])*v[i];return std::sqrt(sum/(v.size()-start*2));}
void compatibility(){unsigned cases=0;
 for(unsigned rate:{8000u,44100u,48000u,96000u,192000u})for(bool pro:{false,true}){
  Core actual(rate);Tube31Reference reference;reference.SetEnable(true);auto s=tubetuning::defaults;s[1]=pro;
  actual.set(s);auto input=signal(rate,16000,713,.4),expected=input;
  const auto actualOutput=actual.render(input,17);reference.TubeProcess(expected.data(),8000);reference.TubeProcess(expected.data()+16000,8000);assert(actualOutput==expected);
  // Inactive saved controls and a neutral professional toggle must not trigger a fade or clear histories.
  s[3]=180;s[4]=0;s[5]=-1000;s[7]=0;s[8]=20000;s[9]=1000;s[10]=20000;s[12]=0;s[14]=-240;s[15]=1;s[16]=4;s[17]=1;actual.set(s);assert(actual.tools(0,s,s,false,70));assert(actual.tools(0,s,s,false,71));
  auto next=signal(rate,8000,997,.2),gold=next;const auto output=actual.render(next,509);reference.TubeProcess(gold.data(),8000);assert(output==gold);
  s[1]=!s[1];actual.set(s);next=signal(rate,8000,1997,.15);gold=next;const auto switched=actual.render(next,1);reference.TubeProcess(gold.data(),8000);assert(switched==gold);
  actual.tube.SetEnable(false);reference.SetEnable(false);assert(actual.render(input)==input);
  actual.tube.SetEnable(true);reference.SetEnable(true);gold=input;reference.TubeProcess(gold.data(),16000);assert(actual.render(input)==gold);++cases;
 }
 std::printf("Tube frozen ui31: %u Fs/pro cases bit-identical, including continuous inactive-control changes, neutral pro switch, meters, block sizes and disable/re-enable.\n",cases);
}
void harmonicsAndDc(){auto s=core();Core symmetric;symmetric.set(s);const auto input=signal(48000,144000,1000,.35),odd=symmetric.render(input);
 const double third=std::abs(amplitude(odd,48000,3000,96000,48000)),second=std::abs(amplitude(odd,48000,2000,96000,48000));assert(third>1e-3&&second<1e-6);
 s[5]=700;Core asymmetric;asymmetric.set(s);const auto even=asymmetric.render(input);const auto h2=amplitude(even,48000,2000,96000,48000);assert(std::abs(h2)>1e-3);
 s[5]=-700;Core opposite;opposite.set(s);const auto negative=opposite.render(input);const auto h2n=amplitude(negative,48000,2000,96000,48000);assert(std::abs(h2+h2n)<std::abs(h2)*.01);
 double mean=0;for(unsigned i=96000;i<144000;++i)mean+=even[2*i];mean/=48000;assert(std::abs(mean)<1e-6);
 std::printf("Tube measured H3(sym)=%.7g, H2(sym)=%.7g, H2(asym)=%.7g; opposite bias reverses H2; asym output DC=%.7g.\n",third,second,std::abs(h2),mean);
 // Strong finite inputs remain bounded by the actual nonlinear curve, not an output hard clamp.
 s=core();s[3]=180;s[4]=1000;s[5]=1000;Core driven;driven.set(s);const auto high=driven.render(signal(48000,96000,1000,3));double peak=0;for(unsigned i=48000;i<96000;++i)peak=std::max(peak,std::abs(double(high[2*i])));assert(peak<1);
}
void aliasReduction(){std::array<double,3> alias{};unsigned n=0;
 for(unsigned factor:{1u,2u,4u}){auto s=core();s[3]=180;s[4]=0;s[15]=factor>1;s[16]=factor==1?2:factor;Core c;c.set(s);const auto out=c.render(signal(48000,96000,9000,.8));alias[n++]=std::abs(amplitude(out,48000,21000,48000,48000));}
 const double reduction2=20*std::log10(alias[0]/alias[1]),reduction4=20*std::log10(alias[0]/alias[2]);assert(reduction2>20&&reduction4>20);
 std::printf("Tube 9kHz hard-drive folded21kHz component: 1x=%.7g 2x=%.7g 4x=%.7g, reductions%.2f/%.2fdB.\n",alias[0],alias[1],alias[2],reduction2,reduction4);
}
void delayMixAndRoutes(){
 for(unsigned factor:{1u,2u,4u}){auto s=core();s[11]=1;s[12]=0;s[15]=factor>1;s[16]=factor==1?2:factor;Core c;c.set(s);const auto in=signal(48000,4000,733,.2),out=c.render(in);const unsigned delay=factor==1?0:31;assert(c.status()[13]==int(factor)&&c.status()[14]==int(delay));
  for(unsigned i=0;i<4000;++i)for(unsigned ch=0;ch<2;++ch)assert(out[2*i+ch]==(i<delay?0:in[2*(i-delay)+ch]));}
 auto s=core();s[15]=1;s[16]=4;s[10]=7000;s[11]=1;s[12]=300;Core raw,wet,diff;for(auto* c:{&raw,&wet,&diff})c->set(s);
 assert(raw.tools(1,s,s)&&wet.tools(2,s,s)&&diff.tools(3,s,s));const auto in=signal(48000,10000,3000,.4),r=raw.render(in),w=wet.render(in),d=diff.render(in);
 for(unsigned i=1000;i<10000;++i)for(unsigned ch=0;ch<2;++ch)assert(std::abs(w[2*i+ch]-r[2*i+ch]-d[2*i+ch])<1e-6);
 std::puts("Tube0% mix is exact delay-aligned raw;1x latency0,2/4x31samples; original/effect/difference share the same timeline.");
}
void harmonicPhaseAlignment(){
 std::array<std::complex<double>,3> h2{};unsigned index=0;
 for(unsigned factor:{1u,2u,4u}){auto s=core();s[5]=700;s[15]=factor>1;s[16]=factor==1?2:factor;Core c;c.set(s);const auto out=c.render(signal(48000,96000,1000,.3));h2[index++]=amplitude(out,48000,2000,48000,48000);}
 for(unsigned i=1;i<3;++i){const auto compensated=h2[i]*std::polar(1.,2*pi*2000*31/48000)/h2[0];assert(std::abs(std::arg(compensated))<.003);assert(std::abs(std::abs(compensated)-1)<.01);}
 std::puts("Tube independent H2 phase comparison confirms FIR residual31sample delay relative to1x, within.003rad after delay compensation.");
}
void endpointsAndRecovery(){
 unsigned cases=0;double peak=0;
 for(unsigned fs:{8000u,44100u,48000u,96000u,192000u})for(int side:{0,1})for(unsigned factor:{1u,2u,4u}){
  Core c(fs);auto s=core();s[3]=side?180:0;s[4]=side?1000:0;s[5]=side?1000:-1000;s[7]=side?1000:0;s[8]=side?20000:20;s[9]=side?1000:20;s[10]=side?20000:1000;s[11]=s[13]=s[17]=1;s[12]=side?1000:0;s[14]=side?0:-240;s[15]=factor>1;s[16]=factor==1?2:factor;c.set(s);
  const auto out=c.render(signal(fs,6000,fs*.13,1.2));for(float v:out)peak=std::max(peak,std::abs(double(v)));
  auto v=c.status();assert(v[25]&&v[31]==0&&v[26]<=int(fs*.45)&&v[27]<=int(fs*.45));
  // Turning every processing group off restores the literal old path after its short transition.
  auto neutral=tubetuning::defaults;neutral[1]=1;neutral[17]=1;c.set(neutral);c.warm(fs/10);assert(c.status()[13]==1&&c.status()[14]==0);
  Tube31Reference reference;reference.SetEnable(true);auto silence=std::vector<float>(fs/10*2);reference.TubeProcess(silence.data(),fs/10);
  // Compare a fresh reset using identical current settings and finite input history.
  c.tube.Reset();const auto input=signal(fs,4000,fs*.07,.4),actual=c.render(input);auto expected=input;reference.TubeProcess(expected.data(),4000);assert(actual==expected);++cases;
 }
 std::printf("Tube%u full parameter endpoint/Fs/quality cases are finite; reset/professional-exit restores old path; max output%.6g.\n",cases,peak);
}
void preHpPreservesBase(){auto s=core();s[3]=180;s[4]=1000;s[9]=1000;Core filtered;filtered.set(s);const auto in=signal(48000,96000,50,.3),out=filtered.render(in);const double fundamental=std::abs(amplitude(out,48000,50,48000,48000));assert(std::abs(fundamental-.3)<.003);
 s[9]=0;Core whole;whole.set(s);const auto coloured=whole.render(in);const double saturated=std::abs(amplitude(coloured,48000,50,48000,48000));assert(fundamental>saturated*1.5);
 std::printf("Tube preHP1000Hz retains50Hz base: amplitude%.6g; full-band saturation%.6g.\n",fundamental,saturated);
}
void abAndFailure(){
 Core c;auto s=tubetuning::defaults;s[1]=s[17]=1;c.set(s);auto a=tubetuning::defaults,b=core();b[15]=1;b[16]=4;b[13]=1;b[14]=-40;
 assert(c.tools(6,a,b,true,17));c.render(signal(48000,10000));const auto before=c.status();assert(c.tools(6,a,b,true,17));const auto repeated=c.status();assert(repeated[1]==before[1]&&repeated[28]==before[28]);
 c.render(signal(48000,120000));auto status=c.status();assert(status[7]==3&&status[8]==2000&&status[9]<=0&&status[10]<=0&&status[14]==31);
 const int gainA=status[9],gainB=status[10];
 assert(c.tools(6,a,b,false,17));assert(c.status()[7]==3&&c.status()[8]==2000&&c.status()[9]==gainA&&c.status()[10]==gainB);
 assert(c.tools(6,a,b,true,17));assert(c.status()[7]==3&&c.status()[8]==2000);
 assert(c.tools(4,a,b,true,17));c.render(signal(48000,48000));assert(c.status()[13]==1&&c.status()[14]==31&&c.status()[9]==gainA&&c.status()[10]==gainB);
 assert(c.tools(5,a,b,true,17));c.render(signal(48000,48000));assert(c.status()[13]==4&&c.status()[14]==31&&c.status()[9]==gainA&&c.status()[10]==gainB);
 auto aa=core(),bb=core();aa[3]=30;bb[3]=60;std::array<int32_t,44> packet{4,1,18,0};std::copy(aa.begin(),aa.end(),packet.begin()+4);std::copy(bb.begin(),bb.end(),packet.begin()+24);
 tubetuning::failAllocationForTest(1);assert(!c.tube.SetTools(packet.data(),packet.size()));assert(c.status()[11]==17&&c.status()[5]==5);
 c.tube.ClearTools();assert(c.status()[5]==0&&c.status()[7]==0&&c.status()[14]==0);
 Core silence;silence.set(s);assert(silence.tools(6,a,b,true));silence.warm(130000);assert(silence.status()[7]==4);
 Core left,right;auto current=core();current[15]=1;current[16]=2;left.set(current);right.set(current);const auto first=signal(48000,10000);assert(left.render(first)==right.render(first));
 auto fail=current;fail[16]=4;tubetuning::failAllocationForTest(0);assert(!left.tube.SetProfessional(fail.data(),20));assert(left.status()[13]==2);const auto next=signal(48000,4000,1997);assert(left.render(next)==right.render(next));
 tubetuning::failAllocationForTest(0);left.tube.SetSamplingRate(96000);assert(left.status()[25]==0);left.set(current);assert(left.status()[25]==1&&left.status()[2]==96000&&left.status()[13]==2);
 std::puts("Tube A/B accepts legacy snapshots, shared31sample delay, same-epoch measurement idempotence, fixed attenuation, low-signal rejection; model/secondAB allocation rollback and same-packet rate retry passed.");
}
const std::array<S,3> factories{{
 {1,1,1,60,750,200,1,700,0,150,16000,1,300,1,-10,1,2,0,0,0},
 {1,1,1,120,650,350,1,850,0,80,14000,1,600,1,-20,1,4,0,0,0},
 {1,1,1,45,850,100,1,250,12000,180,0,1,250,1,0,1,2,0,0,0}}};
void factoriesAndStress(){
 std::array<std::vector<float>,3> variants;unsigned f=0;
 for(const auto& s:factories){assert(tubetuning::valid(s.data(),20));Core c;c.set(s);auto input=signal(48000,96000,7000,.5);variants[f++]=c.render(input);}
 for(unsigned i=0;i<3;++i)for(unsigned j=i+1;j<3;++j){double error=0;for(unsigned k=48000*2;k<96000*2;++k)error+=std::abs(variants[i][k]-variants[j][k]);assert(error/96000>1e-3);}
 unsigned cases=0;double peak=0;
 for(unsigned rate:{8000u,44100u,48000u,96000u,192000u}){
  Core c(rate);for(const auto& preset:factories){c.set(preset);const auto out=c.render(signal(rate,4096,rate*.21,.8));for(float v:out)peak=std::max(peak,std::abs(double(v)));assert(c.status()[2]==int(rate)&&c.status()[25]);++cases;}
  auto s=core();s[15]=1;for(unsigned cycle=0;cycle<6;++cycle){s[16]=2;c.set(s);c.render(signal(rate,19));s[16]=4;c.set(s);s[16]=2;c.set(s);c.render(signal(rate,rate/10));assert(c.status()[13]==2&&c.status()[14]==31);}
  c.tube.ClearTools();s[16]=4;c.set(s);c.tube.ClearTools();c.tube.SetEnable(false);c.tube.SetEnable(true);c.render(signal(rate,1000));assert(c.status()[13]==4&&c.status()[25]);
  c.tube.SetSamplingRate(rate==48000?44100:48000);c.render(signal(rate==48000?44100:48000,1000));assert(c.status()[5]==0&&c.status()[25]);
 }
 assert(peak<1.5);std::printf("Tube3 factory responses are distinct; %u factory/Fs cases plus rapid2-to-4-to-2, pending clear, lifecycle and rate changes passed; factory peak%.6g.\n",cases,peak);
}
struct Effect {
 effect_handle_t h{};effect_uuid_t uuid{0x30306067,0x9400,0x4d53,0x9000,{0,0,0,0,0,1}};
 Effect(){assert(AELI.create_effect(&uuid,0,0,&h)==0);rate(48000);assert(command(EFFECT_CMD_ENABLE)==0);}
 ~Effect(){AELI.release_effect(h);}
 int command(uint32_t id,uint32_t n=0,void* data=nullptr){int32_t result=0;uint32_t size=4;const auto rc=(*h)->command(h,id,n,data,&size,&result);return rc?rc:result;}
 int raw(uint32_t id,const void* data,unsigned n,unsigned extra=0){std::array<uint32_t,64> packet{};packet[1]=4;packet[2]=n;packet[3]=id;std::memcpy(packet.data()+4,data,n);return command(EFFECT_CMD_SET_PARAM,16+n+extra,packet.data());}
 void rate(unsigned rate){effect_config_t c{};c.inputCfg.samplingRate=c.outputCfg.samplingRate=rate;c.inputCfg.channels=c.outputCfg.channels=AUDIO_CHANNEL_OUT_STEREO;c.inputCfg.format=c.outputCfg.format=AUDIO_FORMAT_PCM_FLOAT;c.inputCfg.accessMode=EFFECT_BUFFER_ACCESS_READ;c.outputCfg.accessMode=EFFECT_BUFFER_ACCESS_WRITE;c.inputCfg.mask=c.outputCfg.mask=EFFECT_CONFIG_ALL;assert(command(EFFECT_CMD_SET_CONFIG,sizeof(c),&c)==0);}
 auto status(){std::array<uint32_t,4> q{0,4,128,131225};std::array<uint32_t,36> r{};uint32_t n=144;const int rc=(*h)->command(h,EFFECT_CMD_GET_PARAM,16,q.data(),&n,r.data());assert(rc==0||rc==-EAGAIN);std::array<int32_t,32> v{};if(!rc){assert(!r[0]&&n==144);std::memcpy(v.data(),r.data()+4,128);}return v;}
 void render(){std::array<float,1024> in{},out{};for(unsigned i=0;i<512;++i){in[2*i]=float(.4*std::sin(.07*i));in[2*i+1]=float(.3*std::sin(.09*i));}audio_buffer_t a{},b{};a.frameCount=b.frameCount=512;a.f32=in.data();b.f32=out.data();assert((*h)->process(h,&a,&b)==0);for(float v:out)assert(std::isfinite(v)&&std::abs(v)<=1);}
};
void protocol(){Effect e;auto s=core();s[17]=1;s[15]=1;s[16]=4;assert(e.raw(65649,s.data(),80)==0);const int32_t enabled=1;assert(e.raw(65583,&enabled,4)==0);e.render();auto v=e.status();assert(v[0]==1&&!(v[1]&1)&&v[3]&&v[4]&&v[13]==4&&v[14]==31&&v[25]);
 assert(e.raw(65649,s.data(),76)!=0&&e.raw(65649,s.data(),80,4)!=0);std::array<int32_t,44> tools{3,0,1,0};std::copy(s.begin(),s.end(),tools.begin()+4);std::copy(s.begin(),s.end(),tools.begin()+24);assert(e.raw(65650,tools.data(),176)==0);
 assert(e.raw(65650,tools.data(),172)!=0&&e.raw(65650,tools.data(),176,4)!=0);int32_t clear=1;assert(e.raw(65651,&clear,4)!=0);clear=0;assert(e.raw(65651,&clear,4)==0);
 for(unsigned rate:{8000u,44100u,48000u,96000u,192000u}){e.rate(rate);assert(e.raw(65649,s.data(),80)==0);e.render();v=e.status();assert(v[2]==int(rate)&&v[5]==0&&v[25]);}
 std::atomic<bool> done=false;std::thread audio([&]{while(!done)e.render();});
 for(unsigned i=0;i<40;++i){auto next=factories[i%3];next[17]=1;assert(e.raw(65649,next.data(),80)==0);v=e.status();if(v[0])assert(!(v[1]&1)&&v[14]<=64&&v[13]>=1&&v[13]<=4);}
 done=true;audio.join();assert(e.command(EFFECT_CMD_DISABLE)==0);v=e.status();assert(v[5]==0&&v[16]==INT32_MIN);
 std::puts("Tube Fusion strict packet lengths/reserved words, coherent concurrent snapshot, actualViPER8..192k rate, limiter boundary and disable cleanup passed.");}
void validation(){assert(tubetuning::valid(tubetuning::defaults.data(),20));assert(!tubetuning::valid(nullptr,20));assert(!tubetuning::valid(tubetuning::defaults.data(),19));
 for(unsigned i=0;i<20;++i){auto s=tubetuning::defaults;s[i]=tubetuning::minimum[i]-1;assert(!tubetuning::valid(s.data(),20));s=tubetuning::defaults;s[i]=tubetuning::maximum[i]+1;assert(!tubetuning::valid(s.data(),20));}
 auto s=tubetuning::defaults;s[16]=3;assert(!tubetuning::valid(s.data(),20));for(unsigned i:{8u,9u,10u}){s=tubetuning::defaults;s[i]=1;assert(!tubetuning::valid(s.data(),20));}
 TubeSimulator fresh;std::array<int32_t,32> v;fresh.GetRuntime(v.data());assert(v[0]==1&&v[2]==48000&&v[13]==1&&v[14]==0&&v[30]==2000&&v[31]==0);
}
}
int main(){std::setvbuf(stdout,nullptr,_IONBF,0);validation();compatibility();harmonicsAndDc();aliasReduction();delayMixAndRoutes();harmonicPhaseAlignment();endpointsAndRecovery();preHpPreservesBase();abAndFailure();factoriesAndStress();protocol();std::puts("Tube native checks passed.");}
