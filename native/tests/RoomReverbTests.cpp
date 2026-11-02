#include <algorithm>
#include <array>
#include <atomic>
#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <new>
#include <thread>
#include <vector>
#include "essential.h"
#include "../room/RoomReverb.h"
extern "C" {
#include "jdsp/jdsp_header.h"
void sf_presetreverbUi30Reference(sf_reverb_state_st*,int,sf_reverb_preset);
void sf_reverb_processUi30Reference(sf_reverb_state_st*,float,float,float*,float*);
extern audio_effect_library_t AELI;
}
#undef min
#undef max
thread_local bool watchNew=false;thread_local unsigned newCount=0,deleteCount=0;
void* operator new(std::size_t n){if(watchNew)++newCount;if(auto* p=std::malloc(n))return p;throw std::bad_alloc();}
void* operator new[](std::size_t n){return ::operator new(n);}
void operator delete(void* p)noexcept{if(watchNew&&p)++deleteCount;std::free(p);}
void operator delete[](void* p)noexcept{::operator delete(p);}
void operator delete(void* p,std::size_t)noexcept{::operator delete(p);}
void operator delete[](void* p,std::size_t)noexcept{::operator delete(p);}
namespace {
using S=std::array<int32_t,24>;
constexpr std::array<int,13> visible{0,1,2,4,5,7,8,9,11,15,16,17,18};
constexpr double pi=3.14159265358979323846;
const std::array<std::array<int,5>,19> baseValues{{
 {320,160,700,1000,7000},{210,100,700,1000,9000},{230,100,700,1000,7000},
 {280,120,700,1000,8000},{290,120,700,1000,6000},{380,140,700,1000,9000},
 {420,150,700,1000,5000},{50,70,-400,800,18000},{50,80,600,900,10000},
 {80,120,-400,800,18000},{120,120,600,900,10000},{180,220,-400,900,16000},
 {190,220,600,900,9000},{80,120,-400,800,18000},{120,120,600,900,10000},
 {180,100,1000,1000,16000},{190,100,1000,1000,9000},{1200,100,100,1000,10000},{3000,100,100,1000,9000}}};
S base(int b=15,bool pro=true){const auto& v=baseValues[b];return {1,int(pro),b,0,v[0],v[1],0,0,0,0,0,0,0,0,0,v[2],v[3],0,0,0,v[4],0,0,0};}
std::vector<float> signal(unsigned fs,unsigned frames){std::vector<float> v(frames*2);uint32_t random=13;
 for(unsigned i=0;i<frames;++i){random=random*1664525+1013904223;const double noise=int32_t(random)/2147483648.*.001;
  v[2*i]=float(.03*std::sin(2*pi*311*i/fs)+noise);v[2*i+1]=float(.025*std::cos(2*pi*733*i/fs)-noise);}return v;}
struct Core {
 std::unique_ptr<JamesDSPLib> dsp=std::make_unique<JamesDSPLib>();void* engine=RoomReverbCreate();
 Core(int fs=48000){assert(engine);dsp->fs=float(fs);assert(RoomReverbBase(engine,dsp.get(),15));RoomReverbEnabled(engine,1);}
 ~Core(){RoomReverbDestroy(engine);}
 void set(const S& s){assert(RoomReverbSet(engine,dsp.get(),s.data(),24));}
 std::array<int32_t,32> status(){std::array<int32_t,32> r;RoomReverbStatus(engine,r.data());return r;}
 void tools(int mode,const S& a,const S& b,bool match=false,int epoch=1){std::array<int32_t,52> v{mode,int(match),epoch,0};std::copy(a.begin(),a.end(),v.begin()+4);std::copy(b.begin(),b.end(),v.begin()+28);assert(RoomReverbTools(engine,dsp.get(),v.data(),52));}
 std::vector<float> render(const std::vector<float>& input,unsigned chunk=173){
  std::vector<float> out(input.size());std::array<float,512> l{},r{},ol{},orr{};
  for(size_t pos=0;pos<input.size()/2;pos+=chunk){const size_t n=std::min(size_t(chunk),input.size()/2-pos);
   for(size_t i=0;i<n;++i){l[i]=input[2*(pos+i)];r[i]=input[2*(pos+i)+1];}
   newCount=deleteCount=0;watchNew=true;RoomReverbProcess(engine,l.data(),r.data(),ol.data(),orr.data(),n);watchNew=false;assert(newCount==0&&deleteCount==0);
   for(size_t i=0;i<n;++i){out[2*(pos+i)]=ol[i];out[2*(pos+i)+1]=orr[i];assert(std::isfinite(ol[i])&&std::isfinite(orr[i]));}
  }return out;
 }
 void warm(unsigned frames=24000){render(std::vector<float>(frames*2));}
};
void compatibility(){
 unsigned cases=0;
 for(int fs:{44100,48000})for(int room:visible)for(bool pro:{false,true}){
  Core actual(fs);auto s=base(room,pro);actual.set(s);
  auto source=std::make_unique<sf_reverb_state_st>(),reference=std::make_unique<sf_reverb_state_st>();RoomReverbCopyStateForTest(actual.engine,source.get());
  sf_presetreverbUi30Reference(reference.get(),fs,sf_reverb_preset(room));
  // Legacy RNG is process-global; normalize only its independently generated noise cache.
  reference->noise=source->noise;assert(std::memcmp(reference.get(),source.get(),sizeof(*source))==0);
  const auto in=signal(fs,12000);const int noiseFrames=SF_REVERB_NS/source->oversampleL.factor;
  for(unsigned i=0;i<12000;++i){float l,r,ol,orr;
   watchNew=true;RoomReverbProcess(actual.engine,&in[2*i],&in[2*i+1],&ol,&orr,1);watchNew=false;
   // The original noise source is global across instances. Normalize only each random cache,
   // leaving every independent delay/filter/feedback state and all reference arithmetic intact.
   if(i%noiseFrames==0){RoomReverbCopyNoiseForTest(actual.engine,&reference->noise);reference->noise.pos=0;}
   sf_reverb_processUi30Reference(reference.get(),in[2*i],in[2*i+1],&l,&r);
   if(std::memcmp(&l,&ol,4)!=0||std::memcmp(&r,&orr,4)!=0){std::printf("compat failure fs%d room%d pro%d frame%u L%.9g/%.9g R%.9g/%.9g\n",fs,room,int(pro),i,l,ol,r,orr);std::fflush(stdout);assert(false);}
  }++cases;
 }
 std::printf("Room ui30 reference: %u preset/rate/professional-off-or-neutral cases are bit-identical.\n",cases);
}
void splitAndDry(){
 auto s=base(7);s[8]=1;s[23]=1;Core main,early,late,wet;
 for(auto* c:{&main,&early,&late,&wet})c->set(s);
 // Synchronize only random modulation cache so different paths can be compared deterministically.
 // At the initial all-zero history first 2000 samples are dominated by dry/early; wandernoise does not yet enter tail.
 early.tools(2,s,s);late.tools(3,s,s);wet.tools(4,s,s);
 const auto input=signal(48000,1000),all=main.render(input);
 sf_rv_noise_st cache{};RoomReverbCopyNoiseForTest(main.engine,&cache);cache.pos=0;
 for(auto* c:{&early,&late,&wet})RoomReverbSetNoiseForTest(c->engine,&cache);
 const auto e=early.render(input),t=late.render(input),w=wet.render(input);
 double wetEnergy=0;for(size_t i=0;i<w.size();++i){assert(std::abs(w[i]-e[i]-t[i])<2e-5);wetEnergy+=w[i]*w[i];}assert(wetEnergy>1e-8);
 auto dry=base(18);dry[8]=1;dry[9]=-240;dry[10]=120;dry[11]=-240;dry[12]=1;dry[13]=0;dry[6]=1;dry[7]=2000;dry[17]=1;dry[18]=1000;dry[19]=2000;dry[21]=1;dry[22]=-60;
 Core c;c.set(dry);const auto out=c.render(input);for(size_t i=0;i<out.size();++i)assert(std::abs(out[i]-input[i]*std::pow(10.,-6./20))<1e-7);
 dry[9]=120;dry[10]=-240;dry[11]=120;c.set(dry);c.warm();const auto again=c.render(input);for(size_t i=0;i<again.size();++i)assert(std::abs(again[i]-input[i]*std::pow(10.,-6./20))<1e-7);
 std::puts("Room split wet=early+late; linked 0% is raw original unaffected by saved trim, predelay, wet EQ or feedback.");
}
void preDelayAndScale(){
 auto s=base(7);s[12]=1;s[13]=1000;s[6]=1;s[7]=1000;Core c;c.set(s);c.tools(2,s,s);
 std::vector<float> impulse(18000*2);impulse[0]=.1f;const auto delayed=c.render(impulse);
 for(unsigned i=0;i<4800;++i)assert(delayed[2*i]==0&&delayed[2*i+1]==0);
 double energy=0;for(float v:delayed)energy+=v*v;assert(energy>1e-9);
 for(int fs:{44100,48000}){
  auto p=base(7);p[3]=1;p[5]=140;Core scaled(fs);scaled.set(p);scaled.tools(2,p,p);
  auto q=base(7);q[3]=1;q[5]=70;Core ordinary(fs);ordinary.set(q);ordinary.tools(2,q,q);
  std::vector<float> x(fs/5*2);x[0]=.1f;const auto a=ordinary.render(x),b=scaled.render(x);
  auto first=[&](const auto& v){for(size_t i=fs/1000;i<v.size()/2;++i)if(std::abs(v[2*i])>1e-6)return i;return v.size()/2;};
  const size_t fa=first(a),fb=first(b);assert(fb>fa*1.8&&fb<fa*2.2);
 }
 std::puts("Room complete-wet predelay is silent before its added100ms; doubled early scale doubles the first reflected arrival at44.1/48k.");
}
double fittedRt(const std::vector<float>& output,int rate){
 const size_t frames=output.size()/2;std::vector<double> integral(frames);double sum=0;
 for(size_t i=frames;i-->0;){sum+=double(output[2*i])*output[2*i]+double(output[2*i+1])*output[2*i+1];integral[i]=sum;}
 if(!(integral[0]>0))return 0;
 double sx=0,sy=0,sxx=0,sxy=0,count=0;
 for(size_t i=0;i<frames;i+=std::max(1,rate/1000)){const double db=10*std::log10(std::max(integral[i],1e-300)/integral[0]);
  if(db<=-5&&db>=-25){const double t=double(i)/rate;sx+=t;sy+=db;sxx+=t*t;sxy+=t*db;++count;}}
 assert(count>20);return -60./((count*sxy-sx*sy)/(count*sxx-sx*sx));
}
void decayFits(){
 std::array<std::array<double,3>,2> fitted{};unsigned ri=0;
 for(int fs:{44100,48000}){unsigned ti=0;for(int rt:{50,150,300}){
  Core c(fs);auto s=base(15);s[3]=1;s[4]=rt;c.set(s);c.tools(3,s,s);
  std::vector<float> impulse(size_t(fs)*12*2);impulse[0]=.1f;
  fitted[ri][ti]=fittedRt(c.render(impulse),fs);
  std::printf("Room independent Schroeder T20 fit: Fs=%d requestedReference=%.2fs measuredWideband=%.3fs\n",fs,rt/100.,fitted[ri][ti]);++ti;
 }++ri;}
 for(unsigned r=0;r<2;++r){assert(fitted[r][1]>fitted[r][0]*1.4);assert(fitted[r][2]>fitted[r][1]*1.5);}
 for(unsigned i=0;i<3;++i)assert(std::abs(fitted[0][i]/fitted[1][i]-1)<.12);
}
void retainedTailAndRollback(){
 Core c;auto s=base(15);s[23]=1;c.set(s);std::vector<float> x(12000*2);x[0]=.1f;c.render(x);
 s[3]=1;s[4]=400;c.set(s);const auto tail=c.render(std::vector<float>(4000));double energy=0;for(float v:tail)energy+=v*v;assert(energy>1e-12);
 auto newer=base(16);RoomReverbFailNextAllocationForTest();assert(!RoomReverbSet(c.engine,c.dsp.get(),newer.data(),24));assert(c.status()[22]==15&&c.status()[21]==1);
 c.dsp->fs=44100;RoomReverbFailNextAllocationForTest();RoomReverbRefresh(c.engine,c.dsp.get());assert(c.status()[21]==0);
 c.set(s);assert(c.status()[2]==44100&&c.status()[21]==1);
 // Same control packets do not reset a live response.
 const auto before=c.status();c.set(s);assert(c.status()[1]==before[1]);
 auto a=base(7),b=base(16);c.tools(5,a,b,false,77);
 std::array<int32_t,52> tools{5,0,78,0};auto nextA=base(8),nextB=base(9);std::copy(nextA.begin(),nextA.end(),tools.begin()+4);std::copy(nextB.begin(),nextB.end(),tools.begin()+28);
 RoomReverbAllocationFailureForTest(1);assert(!RoomReverbTools(c.engine,c.dsp.get(),tools.data(),52));assert(c.status()[11]==77&&c.status()[5]==5);
 std::puts("Room live RT retune retains tail; allocation failure rolls back a preset and second A/B allocation; failed format refresh retries an identical packet.");
}
void pausedRoomSelectionSettles(){
 Core c;auto s=base(7);c.set(s);c.render(signal(48000,2000));
 c.set(base(1));c.set(base(2));c.set(base(11));assert(RoomReverbQueuedForTest(c.engine));
 RoomReverbClear(c.engine);assert(!RoomReverbQueuedForTest(c.engine)&&c.status()[22]==11);
 auto live=std::make_unique<sf_reverb_state_st>(),ref=std::make_unique<sf_reverb_state_st>();RoomReverbCopyStateForTest(c.engine,live.get());sf_presetreverbUi30Reference(ref.get(),48000,SF_REVERB_PRESET_LARGEROOM1);ref->noise=live->noise;
 assert(!std::memcmp(live.get(),ref.get(),sizeof(*live)));
 c.render(signal(48000,1000));c.set(base(1));c.set(base(2));RoomReverbEnabled(c.engine,0);assert(!RoomReverbQueuedForTest(c.engine)&&c.status()[22]==2);
 std::puts("Room paused/global-clear and module-disable settle the latest pending room on the control thread; no old fade survives resume.");
}
void optionalHistoryDoesNotReturn(){
 Core c;auto s=base(7);s[6]=s[8]=s[17]=1;s[7]=2000;s[10]=120;s[11]=120;s[18]=700;s[19]=1700;c.set(s);
 std::vector<float> input(18000*2,.05f);c.render(input);
 s[6]=s[8]=s[17]=0;c.set(s);c.warm(48000*5);
 s[21]=1;s[22]=0;c.set(s);const auto output=c.render(std::vector<float>(4800*2));
 double peak=0;for(float v:output)peak=std::max(peak,std::abs(double(v)));assert(peak<1e-6);
 std::puts("Room neutral fast path retires optional wet delay/EQ/trim history; an unrelated group cannot revive old buffered sound.");
}
void feedbackToggle(){
 Core c;auto s=base(7);s[3]=1;s[4]=3000;c.set(s);assert(c.status()[26]==1);
 std::vector<float> sample{.01f,.01f};auto snapshot=std::make_unique<sf_reverb_state_st>();
 s[3]=0;c.set(s);
 for(unsigned i=0;i<6000;++i){c.render(sample);RoomReverbCopyStateForTest(c.engine,snapshot.get());
  if(c.status()[26])assert(snapshot->loopdecay*(1+snapshot->bassb)<=.99501f);}
 c.warm(18000);RoomReverbCopyStateForTest(c.engine,snapshot.get());
 assert(c.status()[26]==0&&c.status()[27]==300000);
 auto reference=std::make_unique<sf_reverb_state_st>();sf_presetreverbUi30Reference(reference.get(),48000,SF_REVERB_PRESET_SMALLROOM1);
 assert(snapshot->loopdecay==reference->loopdecay&&snapshot->bassb==reference->bassb&&snapshot->dampap1L.decay==reference->dampap1L.decay);
 // Turning TIME_ON back on with the original room reference is also stable at every intermediate frame.
 s[3]=1;s[4]=3000;c.set(s);
 for(unsigned i=0;i<6000;++i){c.render(sample);RoomReverbCopyStateForTest(c.engine,snapshot.get());assert(snapshot->loopdecay*(1+snapshot->bassb)<=.99501f);}
 std::puts("Room feedback protection covers both switch directions and every smoothed intermediate sample; TIME_OFF restores original coefficients/bass exactly.");
}
void switchesAndExtremes(){
 unsigned switches=0;
 for(bool pro:{false,true}){Core c;
  for(int from:visible){c.set(base(from,pro));c.warm(2500);
   for(int to:visible){auto s=base(to,pro);if(pro){s[3]=s[6]=s[8]=s[14]=s[17]=s[21]=s[23]=1;s[4]=(switches%2)?10:3000;s[5]=(switches%2)?50:250;s[7]=2000;s[9]=120;s[10]=-240;s[11]=120;s[15]=-1000;s[16]=0;s[18]=1000;s[19]=2000;s[20]=100;s[22]=-120;}
    c.set(s);c.render(signal(48000,3000));assert(c.status()[21]==1&&c.status()[22]==to);++switches;
   }
  }
 }
 for(int fs:{44100,48000}){Core c(fs);auto s=base(18);s[3]=s[6]=s[8]=s[14]=s[17]=s[23]=1;s[4]=3000;s[5]=250;s[7]=2000;s[9]=-240;s[10]=s[11]=120;s[15]=-1000;s[16]=1000;s[18]=1000;s[19]=20000;s[20]=20000;c.set(s);c.render(signal(fs,24000));assert(c.status()[15]<=int(fs*.45));}
 std::printf("Room switch stress: %u directed legacy/professional13x13 switches plus2actual-internal-rate endpoint settings; no callback allocations/deallocations.\n",switches);
}
double internalPeak(const sf_reverb_state_st& s){
 double peak=0;auto inspect=[&](const auto& values){for(float v:values){assert(std::isfinite(v));peak=std::max(peak,std::abs(double(v)));}};
 for(unsigned i=0;i<10;++i){inspect(s.diffL[i].buf);inspect(s.diffR[i].buf);}
 for(unsigned i=0;i<4;++i){inspect(s.crossL[i].buf);inspect(s.crossR[i].buf);}
 inspect(s.cdelayL.buf);inspect(s.cdelayR.buf);inspect(s.dampdL.buf);inspect(s.dampdR.buf);
 inspect(s.dampap1L.buf);inspect(s.dampap1R.buf);inspect(s.dampap2L.buf);inspect(s.dampap2R.buf);
 inspect(s.cbassd1L.buf);inspect(s.cbassd1R.buf);inspect(s.cbassd2L.buf);inspect(s.cbassd2R.buf);
 inspect(s.cbassap1L.buf1);inspect(s.cbassap1L.buf2);inspect(s.cbassap1R.buf1);inspect(s.cbassap1R.buf2);
 inspect(s.cbassap2L.buf1);inspect(s.cbassap2L.buf2);inspect(s.cbassap2L.buf3);
 inspect(s.cbassap2R.buf1);inspect(s.cbassap2R.buf2);inspect(s.cbassap2R.buf3);
 inspect(s.combL.buf);inspect(s.combR.buf);inspect(s.lastdelayL.buf);inspect(s.lastdelayR.buf);inspect(s.inpdelayL.buf);inspect(s.inpdelayR.buf);
 return peak;
}
void longFeedbackStability(){
 double largest=0,worstRatio=0;unsigned cases=0;
 for(int room:visible)for(int damp:{100,20000}){
  const int fs=damp==100?44100:48000;Core c(fs);auto s=base(room);s[3]=s[17]=1;s[4]=3000;s[20]=damp;c.set(s);c.tools(3,s,s);
  auto snapshot=std::make_unique<sf_reverb_state_st>();std::vector<float> block(size_t(fs)*2);double first=0,last=0;
  for(unsigned second=0;second<32;++second){std::fill(block.begin(),block.end(),0);
   if(second==0)for(unsigned i=0;i<unsigned(fs)/2;++i){const float x=float(.05*std::sin(2*pi*20*i/fs)+.05*std::sin(2*pi*63*i/fs));block[2*i]=x;block[2*i+1]=x;}
   const auto output=c.render(block);double energy=0;for(float v:output){assert(std::abs(v)<32);energy+=double(v)*v;}
   if(second>=2&&second<=5)first+=energy;if(second>=28)last+=energy;
   RoomReverbCopyStateForTest(c.engine,snapshot.get());const double peak=internalPeak(*snapshot);largest=std::max(largest,peak);assert(peak<32);
   // The bound applies to actual smoothed feedback at every sampled stage, not a final output limiter.
   assert(snapshot->loopdecay*(1+snapshot->bassb)<=.99501f);
   const auto status=c.status();assert(status[26]==1&&status[27]>=0&&status[27]<=300000);
  }
  assert(first>0&&last<first*.8);worstRatio=std::max(worstRatio,last/first);++cases;
 }
 std::printf("Room long feedback stability: %u cases(all13 rooms,RT30s,damp100/20000Hz,actual44.1/48k),0.5s20+63Hz burst then31.5s silence; max internal |state|=%.6g, worst final4s/early4s energy=%.6g.\n",cases,largest,worstRatio);
}
void abAndMeters(){
 Core c;auto a=base(7),b=base(7);a[23]=b[23]=1;b[21]=1;b[22]=-60;c.set(a);c.tools(7,a,b,true,17);
 c.render(signal(48000,20000));const auto epochBefore=c.status()[1];c.tools(7,a,b,true,17);assert(c.status()[1]==epochBefore);
 const auto input=signal(48000,180000);c.render(input);auto st=c.status();assert(st[7]==3&&st[8]==2000&&st[9]<=0&&st[10]<=0&&st[11]==17&&st[16]!=INT32_MIN&&st[18]!=INT32_MIN);
 assert(std::abs((st[9]-st[10])+600)<35); // output-only6dB difference, distinct A/B modulation cache contributes a small residual.
 const int ga=st[9],gb=st[10];
 auto legacyA=base(7,false);Core legacyCompare;legacyCompare.set(a);legacyCompare.tools(5,legacyA,b);legacyCompare.render(signal(48000,10000));assert(legacyCompare.status()[5]==5);
c.tools(5,a,b,true,17);c.render(signal(48000,10000));st=c.status();assert(st[7]==3&&st[9]==ga&&st[10]==gb);
 c.tools(6,a,b,true,17);c.render(signal(48000,10000));assert(c.status()[9]==ga&&c.status()[10]==gb);
 RoomReverbEnabled(c.engine,0);st=c.status();assert(st[5]==0&&st[7]==0&&st[16]==INT32_MIN);
 Core silence;silence.set(a);silence.tools(7,a,b,true);silence.warm(200000);assert(silence.status()[7]==4);
 std::puts("Room independent full A/B snapshots: warmup+2s RMS measurement, fixed attenuation only, low-signal rejection, meter availability and off clearing.");
}
struct Effect {
 effect_handle_t h{};effect_uuid_t uuid{0x30306067,0x9400,0x4d53,0x9000,{0,0,0,0,0,1}};
 Effect(){assert(AELI.create_effect(&uuid,0,0,&h)==0);rate(48000);assert(command(EFFECT_CMD_ENABLE)==0);}
 ~Effect(){AELI.release_effect(h);}
 int command(uint32_t cmd,unsigned n=0,void* data=nullptr){int32_t reply=-1;uint32_t size=4;const int ret=(*h)->command(h,cmd,n,data,&size,&reply);return ret?ret:reply;}
 void rate(unsigned fs){effect_config_t c{};c.inputCfg.samplingRate=c.outputCfg.samplingRate=fs;c.inputCfg.channels=c.outputCfg.channels=AUDIO_CHANNEL_OUT_STEREO;c.inputCfg.format=c.outputCfg.format=AUDIO_FORMAT_PCM_FLOAT;c.inputCfg.accessMode=EFFECT_BUFFER_ACCESS_READ;c.outputCfg.accessMode=EFFECT_BUFFER_ACCESS_WRITE;c.inputCfg.mask=c.outputCfg.mask=EFFECT_CONFIG_ALL;assert(command(EFFECT_CMD_SET_CONFIG,sizeof(c),&c)==0);}
 int raw(uint32_t id,const void* value,unsigned n,unsigned extra=0){std::array<uint32_t,64> packet{};packet[1]=4;packet[2]=n;packet[3]=id;std::memcpy(packet.data()+4,value,n);return command(EFFECT_CMD_SET_PARAM,16+n+extra,packet.data());}
 std::array<int32_t,32> status(){std::array<uint32_t,4> q{0,4,128,131224};std::array<uint32_t,36> r{};uint32_t n=144;const int rc=(*h)->command(h,EFFECT_CMD_GET_PARAM,16,q.data(),&n,r.data());assert(rc==0||rc==-EAGAIN);std::array<int32_t,32> result{};if(rc==0&&r[0]==0)std::memcpy(result.data(),r.data()+4,128);return result;}
 void render(unsigned blocks=1){std::array<float,1024> input{},output{};for(unsigned k=0;k<blocks;++k){for(unsigned i=0;i<512;++i){input[2*i]=float(.02*std::sin((k*512+i)*.1));input[2*i+1]=float(.01*std::cos((k*512+i)*.13));}audio_buffer_t x{},y{};x.frameCount=y.frameCount=512;x.f32=input.data();y.f32=output.data();assert((*h)->process(h,&x,&y)==0);for(float v:output)assert(std::isfinite(v));}}
};
void fusionProtocolAndFormat(){
 Effect e;auto s=base(7);s[23]=1;assert(e.raw(1424,s.data(),96)==0);const int16_t enabled=1;assert(e.raw(1203,&enabled,2)==0);e.render(10);auto st=e.status();assert(st[0]==1&&st[1]%2==0&&st[2]==48000&&st[3]==1&&st[4]==1&&st[22]==7);
 assert(e.raw(1424,s.data(),92)!=0&&e.raw(1424,s.data(),96,4)!=0);int32_t clear=1;assert(e.raw(1426,&clear,4)!=0);clear=0;assert(e.raw(1426,&clear,4)==0);
 std::array<int32_t,52> tools{2,0,1,0};std::copy(s.begin(),s.end(),tools.begin()+4);std::copy(s.begin(),s.end(),tools.begin()+28);assert(e.raw(1425,tools.data(),208)==0);
 assert(e.raw(1425,tools.data(),204)!=0&&e.raw(1425,tools.data(),208,4)!=0);tools[3]=1;assert(e.raw(1425,tools.data(),208)!=0);
 for(unsigned host:{8000u,44100u,48000u,96000u,192000u,48000u}){e.rate(host);assert(e.raw(1424,s.data(),96)==0);e.render(5);st=e.status();assert(st[21]==1&&st[2]==int(host>=44100?host:48000)&&st[5]==0);}
 std::atomic<bool> done=false;
 std::thread audio([&]{while(!done.load())e.render();});
 for(unsigned i=0;i<40;++i){s=base(visible[i%13]);s[23]=1;assert(e.raw(1424,s.data(),96)==0);st=e.status();if(st[0])assert(st[1]%2==0&&st[22]>=0&&st[22]<=18);}
 done=true;audio.join();assert(e.command(EFFECT_CMD_DISABLE)==0);assert(e.status()[5]==0);
 std::puts("Room Fusion exact-length validation, coherent runtime polling,13room live changes, host8/44.1/48/96/192k configuration and disabled-tool clear passed.");
}
void validation(){auto s=base();assert(RoomReverbValidate(s.data(),24));assert(!RoomReverbValidate(s.data(),23));
 const int lo[]={1,0,0,0,10,50,0,0,0,-240,-240,-240,0,0,0,-1000,0,0,0,0,100,0,-120,0};
 const int hi[]={1,1,18,1,3000,250,1,2000,1,120,120,120,1,1000,1,1000,1000,1,1000,20000,20000,1,0,1};
 for(unsigned i=0;i<24;++i){auto q=s;q[i]=lo[i]-1;assert(!RoomReverbValidate(q.data(),24));q=s;q[i]=hi[i]+1;assert(!RoomReverbValidate(q.data(),24));}
 auto q=s;q[18]=1000;q[19]=500;assert(!RoomReverbValidate(q.data(),24));q=s;q[2]=3;assert(!RoomReverbValidate(q.data(),24));
}
}
int main(int argc,char** argv){std::setvbuf(stdout,nullptr,_IONBF,0);if(argc>1 && std::strcmp(argv[1],"decay")==0){decayFits();return 0;}if(argc>1 && std::strcmp(argv[1],"long")==0){longFeedbackStability();return 0;}validation();compatibility();splitAndDry();preDelayAndScale();decayFits();retainedTailAndRollback();pausedRoomSelectionSettles();optionalHistoryDoesNotReturn();feedbackToggle();switchesAndExtremes();longFeedbackStability();abAndMeters();fusionProtocolAndFormat();std::puts("Room reverb native checks passed.");}
