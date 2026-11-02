#include <algorithm>
#include <array>
#include <atomic>
#include <bit>
#include <cassert>
#include <cmath>
#include <complex>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <limits>
#include <new>
#include <thread>
#include <vector>
#include "essential.h"
#include "../compander/Compander.h"
extern "C" {
#include "../../app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/jdsp_header.h"
void CompressorConstructorUi35Reference(JamesDSPLib*);
void CompressorDestructorUi35Reference(JamesDSPLib*);
void CompressorSetParamUi35Reference(JamesDSPLib*,float,int,int,char);
void CompressorSetGainUi35Reference(JamesDSPLib*,double*,double*,char);
void CompressorProcessUi35Reference(JamesDSPLib*,size_t);
extern audio_effect_library_t AELI;
}
#undef min
#undef max
thread_local bool watch=false;thread_local unsigned allocations=0,releases=0;
void* operator new(size_t n){if(watch)++allocations;if(auto* p=std::malloc(n))return p;throw std::bad_alloc();}
void* operator new[](size_t n){return ::operator new(n);}
void operator delete(void* p)noexcept{if(watch&&p)++releases;std::free(p);}
void operator delete[](void* p)noexcept{::operator delete(p);}
void operator delete(void* p,size_t)noexcept{::operator delete(p);}
void operator delete[](void* p,size_t)noexcept{::operator delete(p);}
namespace {
using S=std::array<int32_t,72>;
constexpr double pi=3.14159265358979323846;
S base(){S s{};s[0]=1;s[3]=std::bit_cast<int32_t>(.22f);const float f[]{95,200,400,800,1600,3400,7500};for(unsigned i=0;i<7;++i)s[6+i]=std::bit_cast<int32_t>(f[i]);s[21]=1000;s[37]=120;s[38]=240;s[41]=100;s[43]=250;s[47]=-800;s[52]=1000;s[56]=-180;s[57]=200;s[58]=60;s[59]=1;s[60]=20;s[66]=30;return s;}
S standard(int type=0){auto s=base();s[1]=s[2]=s[20]=1;s[55]=type;s[56]=-240;s[58]=0;s[67]=s[68]=1;return s;}
struct Core {
 std::unique_ptr<JamesDSPLib> owner=std::make_unique<JamesDSPLib>();void* e=CompanderCreate();unsigned rate;
 std::array<float,512> left{},right{};
 explicit Core(unsigned fs=48000,unsigned host=0):rate(fs){assert(e);owner->fs=float(fs);owner->trueSampleRate=float(host?host:fs);owner->tmpBuffer[0]=left.data();owner->tmpBuffer[1]=right.data();CompanderRefresh(e,owner.get());CompanderEnable(e,owner.get(),1);}
 ~Core(){CompanderDestroy(e);}
 void set(const S& s){assert(CompanderSet(e,owner.get(),s.data(),s.size()));}
 auto status()const{std::array<int32_t,80> v;CompanderStatus(e,v.data());return v;}
 auto graph()const{std::array<int32_t,200> v;CompanderResponse(e,v.data());return v;}
 bool tools(int mode,const S& a,const S& b,bool match=false,int epoch=1){std::array<int32_t,148> v{mode,int(match),epoch,0};std::copy(a.begin(),a.end(),v.begin()+4);std::copy(b.begin(),b.end(),v.begin()+76);return CompanderTools(e,owner.get(),v.data(),v.size());}
 std::vector<float> render(const std::vector<float>& in,unsigned chunk=173){std::vector<float> out(in.size());
  for(size_t pos=0;pos<in.size()/2;pos+=chunk){const auto n=std::min(size_t(chunk),in.size()/2-pos);for(size_t i=0;i<n;++i){left[i]=in[2*(pos+i)];right[i]=in[2*(pos+i)+1];}
   allocations=releases=0;watch=true;CompanderProcess(e,owner.get(),n);watch=false;if(allocations||releases)std::printf("alloc %u free %u\n",allocations,releases);assert(!allocations&&!releases);
   for(size_t i=0;i<n;++i){out[2*(pos+i)]=left[i];out[2*(pos+i)+1]=right[i];assert(std::isfinite(left[i])&&std::isfinite(right[i]));}}
  return out;}
 void refresh(unsigned fs,unsigned host=0){rate=fs;owner->fs=float(fs);owner->trueSampleRate=float(host?host:fs);CompanderRefresh(e,owner.get());}
};
std::vector<float> sine(unsigned rate,unsigned n,double hz=733,double a=.2){std::vector<float> v(n*2);for(unsigned i=0;i<n;++i){v[2*i]=float(a*std::sin(2*pi*hz*i/rate));v[2*i+1]=float(a*.8*std::sin(2*pi*hz*i/rate+.31));}return v;}
std::vector<float> constant(unsigned n,double a){return std::vector<float>(n*2,float(a));}
double rms(const std::vector<float>& v,unsigned start=0){double sum=0;for(size_t i=start*2;i<v.size();++i)sum+=double(v[i])*v[i];return std::sqrt(sum/(v.size()-start*2));}
std::complex<double> amplitude(const std::vector<float>& v,unsigned fs,double hz,unsigned start,unsigned count){std::complex<double> a{};for(unsigned i=0;i<count;++i)a+=double(v[2*(start+i)])*std::polar(1.,-2*pi*hz*(start+i)/fs);return a*(2./count);}
constexpr std::array<S,7> factories{{
 {1,0,0,1046562734,0,0,1119748096,1128792064,1137180672,1145569280,1153957888,1163165696,1172987904,0,0,0,0,0,0,0,0,1000,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,120,240,0,0,100,0,250,0,0,0,-800,0,0,0,0,1000,0,0,0,-180,200,60,1,20,0,0,0,0,0,30,0,0,0,0,0},
 {1,1,0,1046562734,0,0,1119748096,1128792064,1137180672,1145569280,1153957888,1163165696,1172987904,0,0,0,0,0,0,0,1,1000,0,0,0,0,0,0,0,-250,-250,-250,-250,-250,-250,-250,1,0,60,1,0,100,0,300,1,10,30,-800,1,0,0,1,800,1,0,0,-180,200,60,1,20,0,0,0,0,0,30,0,0,0,0,0},
 {1,1,0,1046562734,0,0,1119748096,1128792064,1137180672,1145569280,1153957888,1163165696,1172987904,0,0,0,0,0,0,0,1,750,0,0,0,0,0,0,0,150,350,250,100,50,50,100,1,40,60,1,0,20,8,140,1,10,20,-700,1,40,12000,1,700,1,-20,0,-180,200,60,1,20,0,0,0,0,0,30,0,0,0,0,0},
 {1,1,1,1046562734,0,0,1119748096,1128792064,1137180672,1145569280,1153957888,1163165696,1172987904,0,0,0,0,0,0,0,1,1000,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,0,120,1,0,100,0,250,1,0,0,-800,1,0,0,1,1000,1,0,0,-180,200,60,1,20,1,80,0,1,50,30,0,0,0,0,0},
 {1,1,1,1046562734,0,0,1119748096,1128792064,1137180672,1145569280,1153957888,1163165696,1172987904,0,0,0,0,0,0,0,1,1000,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,60,0,1,0,200,30,350,1,0,0,-700,1,0,0,1,1000,1,-30,1,-300,200,60,1,20,0,0,0,0,0,30,0,0,0,0,0},
 {1,1,1,1046562734,0,0,1119748096,1128792064,1137180672,1145569280,1153957888,1163165696,1172987904,0,0,0,0,0,0,0,1,1000,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,0,180,1,0,50,50,150,0,0,0,-1000,1,0,0,1,1000,1,0,2,-400,200,60,1,20,1,80,0,0,0,30,0,0,0,0,0},
 {1,1,1,1046562734,0,0,1119748096,1128792064,1137180672,1145569280,1153957888,1163165696,1172987904,0,0,0,0,0,0,0,1,1000,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,0,480,1,0,10,80,200,0,0,0,-1000,1,0,0,1,1000,1,0,3,-450,200,0,0,20,1,80,0,1,30,40,0,0,0,0,0}}};
void pchipGolden(){ierper p{};initIerper(&p,9);double freq[]{0,95,200,400,800,1600,3400,7500,24000},coeff[]{-.8,-.8,-.3,.2,.6,.1,-.5,.4,.4};pchip(&p,freq,coeff,9,1,1);std::printf("Independent C PCHIP golden:");for(double q:{50.,100.,500.,1000.,3000.,6000.,20000.})std::printf(" %.17g",getValueAt(&p.cb,q));std::puts("");freeIerper(&p);}
void validation(){auto s=base();assert(CompanderValidate(s.data(),72));assert(!CompanderValidate(s.data(),71));for(unsigned i=0;i<72;++i){auto bad=s;bad[i]=INT32_MAX;assert(!CompanderValidate(bad.data(),72));}
 for(unsigned i=3;i<=19;++i){if(i==4||i==5)continue;for(float value:{NAN,INFINITY,-INFINITY}){auto bad=s;bad[i]=std::bit_cast<int32_t>(value);assert(!CompanderValidate(bad.data(),72));}}
 auto fractional=s;fractional[6]=std::bit_cast<int32_t>(.5f);assert(CompanderValidate(fractional.data(),72));fractional[6]=1;assert(CompanderValidate(fractional.data(),72));fractional[6]=0;assert(!CompanderValidate(fractional.data(),72));fractional[6]=std::bit_cast<int32_t>(float(1e-100));assert(!CompanderValidate(fractional.data(),72));
 auto bad=s;bad[7]=bad[6];assert(!CompanderValidate(bad.data(),72));bad=s;bad[13]=std::bit_cast<int32_t>(1.21f);assert(!CompanderValidate(bad.data(),72));bad=s;bad[62]=19;assert(!CompanderValidate(bad.data(),72));bad=s;bad[63]=499;assert(!CompanderValidate(bad.data(),72));bad=s;bad[49]=bad[50]=200;assert(!CompanderValidate(bad.data(),72));
 std::array<int32_t,148> t{};std::copy(s.begin(),s.end(),t.begin()+4);std::copy(s.begin(),s.end(),t.begin()+76);assert(CompanderToolsValidate(t.data(),148));t[0]=6;assert(!CompanderToolsValidate(t.data(),148));t[2]=1;assert(CompanderToolsValidate(t.data(),148));t[3]=1;assert(!CompanderToolsValidate(t.data(),148));
 for(auto f:factories)assert(CompanderValidate(f.data(),72));std::puts("Compander strict72/148 schema: all bounds, exact raw Float bits, finite/ordered seven nodes, filter gaps/order and seven factories passed.");}
void compatibility(){unsigned cases=0,repaired=0;
 for(unsigned rate:{44100u,48000u})for(int mode=0;mode<4;++mode)for(int gran=0;gran<5;++gran)for(bool pro:{false,true})for(bool curve:{false,true}){
  auto s=base();s[1]=pro;s[4]=gran;s[5]=mode;
  const double g[]{-.8,-.3,.25,.7,.4,-.5,.1};for(unsigned i=0;i<7;++i)if(curve)s[13+i]=std::bit_cast<int32_t>(float(g[i]));
  Core core(rate);core.set(s);auto reference=std::make_unique<JamesDSPLib>();reference->fs=float(rate);CompressorConstructorUi35Reference(reference.get());CompressorSetParamUi35Reference(reference.get(),.22f,gran,mode,1);double f[7],gg[7];for(unsigned i=0;i<7;++i){f[i]=std::bit_cast<float>(s[6+i]);gg[i]=std::bit_cast<float>(s[13+i]);}CompressorSetGainUi35Reference(reference.get(),f,gg,1);
  auto input=sine(rate,6144,733,.2);for(unsigned i=0;i<6144;++i){input[2*i]+=float(.06*std::sin(2*pi*3731*i/rate));input[2*i+1]+=float(.04*std::sin(2*pi*119*i/rate));}
  const unsigned chunk=curve?173:512;auto actual=core.render(input,chunk);auto expected=input;std::array<float,512> l{},r{};reference->tmpBuffer[0]=l.data();reference->tmpBuffer[1]=r.data();for(unsigned pos=0;pos<6144;pos+=chunk){const unsigned n=std::min(chunk,6144-pos);for(unsigned i=0;i<n;++i){l[i]=input[2*(pos+i)];r[i]=input[2*(pos+i)+1];}CompressorProcessUi35Reference(reference.get(),n);for(unsigned i=0;i<n;++i){expected[2*(pos+i)]=l[i];expected[2*(pos+i)+1]=r[i];}}
  if(mode==3&&curve){bool broken=false;for(size_t i=0;i<expected.size();++i)if(!std::isfinite(expected[i])){broken=true;break;}assert(broken);for(size_t i=0;i<expected.size()&&std::isfinite(expected[i]);++i)assert(std::abs(actual[i]-expected[i])<2e-6);assert(rms(actual)>1e-5);++repaired;}
  else{if(actual!=expected){for(size_t i=0;i<actual.size();++i)if(actual[i]!=expected[i]){std::printf("compat fs%u mode%d gran%d pro%d curve%d at%zu %.9g %.9g\n",rate,mode,gran,pro,curve,i,actual[i],expected[i]);break;}}assert(actual==expected);++cases;}
  CompressorDestructorUi35Reference(reference.get());
 }
 std::printf("Compander frozen ui35: %u exact cases internal44.1/48k x4 legacy transforms x5 granularities xpro off/on xzero/nonzero curves; original512/173 frame call scheduling retained; %u mixed-curve time-domain cases formerly NaN now finite via cancellation-safe shelf coefficients.\n",cases,repaired);
}
void customNeutral(){unsigned cases=0;for(unsigned fs:{44100u,48000u})for(int mode=0;mode<4;++mode)for(float low:{.5f,std::numeric_limits<float>::denorm_min()})for(float high:{23000.f,std::nextafter(24000.f,0.f)}){auto s=base();s[5]=mode;s[6]=std::bit_cast<int32_t>(low);s[12]=std::bit_cast<int32_t>(high);for(unsigned i=0;i<7;++i)s[13+i]=std::bit_cast<int32_t>(float(.6-.15*i));auto neutral=s;neutral[1]=neutral[20]=1;Core original(fs),professional(fs);original.set(s);professional.set(neutral);auto input=sine(fs,6144);assert(original.render(input,256)==professional.render(input,256));++cases;}std::printf("Custom legacy0.5Hz/subnormal and23k/just-below24k basis: %u core-neutral cases remain bit-identical; Float0 underflow is rejected.\n",cases);}
void publicCurveWireGuards(){
 Core c;c.owner->companderNative=c.e;CompressorSetParam(c.owner.get(),.22f,0,0,0);
 std::array<double,7> healthy{.5,200,400,800,1600,3400,23000},coeff{.15,.3,.2,-.1,.4,.1,-.2};
 CompressorSetGain(c.owner.get(),healthy.data(),coeff.data(),1);const auto before=c.status();assert(before[64]==1&&before[70]==23000&&before[71]==150);
 std::array<double,14> saved{};std::copy_n(c.owner->comp.freq2+1,7,saved.begin());std::copy_n(c.owner->comp.gains2+1,7,saved.begin()+7);
 for(unsigned test=0;test<3;++test){auto invalid=healthy;if(test==0)invalid[0]=1e-100;if(test==1)invalid[2]=std::nextafter(invalid[1],400.);if(test==2)invalid[6]=std::nextafter(24000.,0.);
  CompressorSetGain(c.owner.get(),invalid.data(),coeff.data(),1);assert(c.status()==before);for(unsigned i=0;i<7;++i){assert(c.owner->comp.freq2[i+1]==saved[i]);assert(c.owner->comp.gains2[i+1]==saved[7+i]);}}
 std::puts("Public Dispatch healthy Double curve applied; Float underflow0, Float-colliding adjacent Doubles and rounding to24k are rejected before either saved owner basis or native runtime/revision is changed.");
}
void standardCurves(){for(int type=0;type<4;++type){Core c;auto s=standard(type);c.set(s);const double input=type==0?.25:.01;const auto out=c.render(constant(144000,input),127);const double gainDb=20*std::log10(rms(out,120000)/input);const double level=20*std::log10(input);const double expected=type==0?-(level+24)*.5:type==1?(-24-level)*.5:type==2?-(-24-level):-60;
  std::printf("Standard type%d measured %.6fdB expected %.6fdB\n",type,gainDb,expected);assert(std::abs(gainDb-expected)<.02);const auto rt=c.status();assert(rt[13]==1&&rt[46]==type&&rt[45]==200&&rt[44]==-240);}
 for(int ratio:{100,200,400,2000}){Core c;auto s=standard();s[57]=ratio;c.set(s);const auto out=c.render(constant(48000,.25));const double expected=-(20*std::log10(.25)+24)*(1-100./ratio),actual=20*std::log10(rms(out,36000)/.25);assert(std::abs(actual-expected)<.01);}
 Core identity;auto off=standard();off[20]=0;identity.set(off);const auto input=sine(48000,6000);assert(identity.render(input)==input);
 std::puts("Standard genuine fixed-dBFS downward compression/upward compression/downward expansion/gate and1:1/2:1/4:1/20:1 numeric transfer checks passed; core-off is unity.");
}
void rmsTimeGate(){
 Core c;auto s=standard();s[56]=0;c.set(s);c.render(constant(48,.1),48);auto rt=c.status();const double expected=20*std::log10(.1*std::sqrt(48./960));assert(std::abs(rt[41]/100.-expected)<.02);
 Core peak;auto p=s;p[59]=0;peak.set(p);peak.render(constant(48,.1),48);assert(std::abs(peak.status()[41]+2000)<=1);
 auto time=standard();time[59]=0;time[39]=1;time[41]=100;time[42]=50;time[43]=250;Core timed;timed.set(time);timed.render(constant(480,.25),480);const double steady=-(20*std::log10(.25)+24)*.5,at10ms=timed.status()[28]/-100.;assert(std::abs(at10ms/steady-(1-std::exp(-1.)))<.002);
 timed.render(constant(480,.0001),480);assert(std::abs(timed.status()[28]/-100.-at10ms)<.02);timed.render(constant(12000,.0001),250);assert(timed.status()[28]/100.<std::abs(at10ms));
 auto gate=standard(3);gate[59]=0;gate[39]=1;gate[41]=10;gate[43]=10;Core g;g.set(gate);g.render(constant(4800,.25));assert(g.status()[55]);g.render(constant(4800,.056));assert(g.status()[55]);g.render(constant(4800,.04));assert(!g.status()[55]);g.render(constant(4800,.056));assert(!g.status()[55]);g.render(constant(4800,.07));assert(g.status()[55]);
 Core floor;auto u=standard(1);floor.set(u);const auto tiny=floor.render(constant(4800,1e-7));assert(rms(tiny,2400)>0&&std::abs(rms(tiny,2400)/1e-7-1)<1e-6);
 for(int type:{2,3}){Core protectedGate;auto p=standard(type);p[44]=1;p[47]=-300;protectedGate.set(p);auto quiet=protectedGate.render(constant(96000,.001));assert(rms(quiet,72000)<2e-5&&protectedGate.status()[28]>3000);}
 std::puts("True rectangular20ms RMS vs Peak,63.212%/10ms attack,50ms hold, independent release, gate hysteresis and upward−100dBFS protection passed.");
}
void standardFeatures(){
 auto full=standard();full[59]=0;Core dry;auto mix=full;mix[51]=1;mix[52]=0;mix[64]=1;mix[65]=200;dry.set(mix);auto impulse=constant(5000,0);impulse[0]=impulse[1]=.5;auto out=dry.render(impulse);assert(out[1920]==.5f&&out[1921]==.5f);for(unsigned i=0;i<1920;++i)assert(out[i]==0);assert(dry.status()[43]==960);
 Core gain;auto trim=mix;trim[53]=1;trim[54]=-60;gain.set(trim);auto reduced=gain.render(impulse);assert(std::abs(reduced[1920]/.5-std::pow(10.,-.3))<1e-6);
 Core side,normal;auto hp=full;hp[61]=1;hp[62]=1000;side.set(hp);normal.set(full);auto dc=constant(96000,.25),a=side.render(dc),b=normal.render(dc);assert(std::abs(rms(a,72000)/.25-1)<1e-5);assert(rms(b,72000)<.15);
 Core range;auto limited=full;limited[44]=1;limited[49]=500;range.set(limited);auto band=range.render(dc);assert(std::abs(rms(band,72000)/.25-1)<1e-5);
 Core cap;auto limitedGain=standard(1);limitedGain[36]=1;limitedGain[37]=30;cap.set(limitedGain);auto boosted=cap.render(constant(96000,.01));assert(std::abs(20*std::log10(rms(boosted,72000)/.01)-3)<.01);
 Core before,after;auto ahead=full;ahead[64]=1;ahead[65]=200;before.set(full);after.set(ahead);auto step=constant(96000,0);for(unsigned i=48000;i<96000;++i)step[2*i]=step[2*i+1]=.25;auto no=before.render(step),yes=after.render(step);assert(yes[96000+1920]<no[96000]*.8f);
 auto silent=full;silent[44]=1;silent[47]=-300;Core protect;protect.set(silent);auto weak=constant(48000,.01),preserved=protect.render(weak);assert(std::abs(rms(preserved,24000)/.01-1)<1e-6);
 std::puts("Standard aligned20ms dry/wet,−6dB output, detection-only HP, action-band residual, real+3dB cap, lookahead transient anticipation and nonmuting weak-signal protection passed.");
}
void legacyReferenceAlignment(){
 for(int mode=0;mode<4;++mode){auto s=base();s[1]=s[67]=1;s[5]=mode;Core effect,raw,diff;for(auto* c:{&effect,&raw,&diff})c->set(s);assert(effect.tools(2,s,s)&&raw.tools(1,s,s)&&diff.tools(3,s,s));auto impulse=constant(16000,0);impulse[12000]=impulse[12001]=.5;const auto e=effect.render(impulse,256),r=raw.render(impulse,256),d=diff.render(impulse,256);unsigned pe=0,pr=0;for(unsigned i=0;i<16000;++i){if(std::abs(e[2*i])>std::abs(e[2*pe]))pe=i;if(std::abs(r[2*i])>std::abs(r[2*pr]))pr=i;}std::printf("Legacy mode%d source-aligned RAW impulse peak%u EFFECT peak%u actualDelay%u\n",mode,pr,pe,raw.status()[32]);assert(pr==pe);for(unsigned i=1000*2;i<e.size();++i)assert(std::abs(r[i]+d[i]-e[i])<1e-7);}
 std::puts("All4 legacy transforms: original block-scheduled output source tags align dry impulse peaks; RAW+DIFF=EFFECT exact within float rounding.");
}
void legacyProfessional(){
 unsigned cases=0;
 for(int mode=0;mode<4;++mode){auto s=base();s[1]=s[20]=s[67]=s[68]=1;s[5]=mode;for(unsigned i=29;i<=35;++i)s[i]=800;Core on,off;on.set(s);auto zero=s;zero[20]=0;off.set(zero);auto input=sine(48000,16000,733,.2);for(unsigned i=8000;i<16000;++i){input[2*i]*=3;input[2*i+1]*=3;}auto a=on.render(input,256),b=off.render(input,256);double difference=0;for(size_t i=8000*2;i<a.size();++i)difference+=std::abs(a[i]-b[i]);assert(difference>.01);assert(on.status()[27]>0);
  auto lim=s;lim[21]=2000;lim[36]=1;lim[37]=20;Core capped;capped.set(lim);capped.render(input,256);assert(capped.status()[27]<=201);
  auto negative=s;for(unsigned i=29;i<=35;++i)negative[i]=-1200;negative[36]=1;negative[38]=10;Core cut;cut.set(negative);cut.render(input,256);assert(cut.status()[28]<=101&&cut.status()[28]>0);
  auto weak=s;weak[44]=1;weak[47]=-300;Core protect;protect.set(weak);auto tiny=sine(48000,10000,733,.001);const auto protectedOut=protect.render(tiny,256);assert(rms(protectedOut,6000)>1e-5&&protect.status()[27]==0);
  auto time=s;time[39]=1;time[41]=2000;time[43]=2000;Core slow;slow.set(time);auto slower=slow.render(input,256);double diff=0;for(size_t i=8000*2;i<a.size();++i)diff+=std::abs(a[i]-slower[i]);assert(diff>.01);++cases;}
 auto old=base();old[1]=old[20]=old[68]=1;const float coeff[]{-1,-.5,0,.7,.5,0,-.5};for(unsigned i=0;i<7;++i)old[13+i]=std::bit_cast<int32_t>(coeff[i]);Core legacy,exact;legacy.set(old);auto calibrated=old;calibrated[69]=1;exact.set(calibrated);auto input=sine(48000,16000,1000,.002);for(unsigned i=8000*2;i<input.size();++i)input[i]*=200;auto a=legacy.render(input,256),b=exact.render(input,256);double diff=0;for(size_t i=8000*2;i<a.size();++i)diff+=std::abs(a[i]-b[i]);std::printf("Exact map difference%.9g flags%d/%d\n",diff,legacy.status()[62],exact.status()[62]);assert(diff>.01&&legacy.status()[62]==0&&exact.status()[62]==1);
 std::printf("Legacy %u transforms: core amount/seven coefficients, boost/cut limits, true attack/release, weak protection active; exact-Hz remapping is explicit and changes nonzero curve only on request.\n",cases);
}
void relativeDimensions(){
 auto base=::base();base[1]=base[20]=base[44]=base[67]=base[68]=1;for(unsigned i=29;i<=35;++i)base[i]=250;base[47]=-1000;
 auto input=sine(48000,16000,1000,.0002);for(unsigned i=0;i<16000;++i){input[2*i+1]=0;if(i>=8000)input[2*i]*=500;}
 unsigned dimension=0;auto compare=[&](S a,S b,const std::vector<float>* stimulus=nullptr){++dimension;Core ca,cb;ca.set(a);cb.set(b);const auto& source=stimulus?*stimulus:input;const auto oa=ca.render(source,256),ob=cb.render(source,256);double difference=0;for(unsigned i=8000*2;i<oa.size();++i)difference+=std::abs(oa[i]-ob[i]);std::printf("Relative dimension%u difference%.9g\n",dimension,difference);assert(difference>.001);};
 auto changed=base;changed[45]=120;compare(base,changed);auto kneeBase=base;kneeBase[45]=240;changed=kneeBase;changed[46]=240;auto kneeInput=sine(48000,105600,1000,.005);for(unsigned i=96000;i<105600;++i){const double multiplier=std::pow(10.,(12.+24.*(i-96000)/9600)/20.);kneeInput[2*i]*=float(multiplier);kneeInput[2*i+1]*=float(multiplier);}compare(kneeBase,changed,&kneeInput);
 changed=base;changed[48]=1;auto stereoInput=input;for(unsigned i=0;i<8000;++i)stereoInput[2*i+1]=stereoInput[2*i];compare(base,changed,&stereoInput);changed=base;changed[49]=2000;compare(base,changed);changed=base;changed[50]=500;compare(base,changed);changed=base;changed[21]=2000;compare(base,changed);
 changed=base;changed[39]=1;changed[40]=-2000;compare(base,changed);auto sameTime=changed;sameTime[40]=0;compare(sameTime,changed);
 auto shape=base;shape[30]=700;shape[31]=-500;changed=shape;changed[23]=100000;compare(shape,changed);changed=shape;changed[30]=100;compare(shape,changed);
 std::puts("Legacy real audio independently confirms10 dimensions: relative threshold, knee, mean/max stereo link, lower/upper action limits, total amount, time group/background delta, node frequency and one coefficient.");
}
void auditionAndTransitions(){
 auto s=standard();s[53]=1;s[54]=-30;Core normal,raw,effect,diff;for(auto* c:{&normal,&raw,&effect,&diff})c->set(s);assert(raw.tools(1,s,s)&&effect.tools(2,s,s)&&diff.tools(3,s,s));auto input=sine(48000,10000,733,.25);auto n=normal.render(input),r=raw.render(input),e=effect.render(input),d=diff.render(input);for(unsigned i=1000*2;i<n.size();++i){assert(n[i]==e[i]);assert(std::abs(d[i]+r[i]-n[i])<1e-7);}
 Core comparison;auto a=standard(),b=a;b[53]=1;b[54]=-60;comparison.set(a);assert(comparison.tools(6,a,b,true,7));comparison.render(sine(48000,192000,1000,.25));assert(comparison.status()[7]==3);assert(comparison.tools(4,a,b,true,7));auto oa=comparison.render(sine(48000,48000,1000,.25));assert(comparison.tools(5,a,b,true,7));auto ob=comparison.render(sine(48000,48000,1000,.25));const double error=20*std::log10(rms(oa,24000)/rms(ob,24000));assert(std::abs(error)<.01);
 auto metersOff=b;metersOff[67]=0;comparison.set(metersOff);assert(comparison.status()[5]==0); // changed output ends the prior audition
 Core live;live.set(a);live.render(input);live.set(b);assert(CompanderQueuedForTest(live.e));CompanderClear(live.e);assert(CompanderQueuedForTest(live.e));auto newest=b;newest[54]=-120;live.set(newest);live.render(sine(48000,96000,733,.2));assert(!CompanderQueuedForTest(live.e)&&live.status()[61]==-120);
 const auto before=live.status();CompanderAllocationFailureForTest(0);auto failed=newest;failed[54]=-180;assert(!CompanderSet(live.e,live.owner.get(),failed.data(),72));assert(live.status()[61]==before[61]);live.set(failed);live.render(sine(48000,96000));assert(live.status()[61]==-180);
 live.refresh(44100,96000);assert(live.status()[2]==44100&&live.status()[26]==96000&&live.status()[5]==0);live.render(sine(44100,6000));CompanderEnable(live.e,live.owner.get(),0);assert(live.status()[3]==0);CompanderEnable(live.e,live.owner.get(),1);assert(live.status()[3]==1);
 std::printf("Audition RAW/EFFECT/DIFF identity, same-input AB2s fixed attenuation %.8fdB error, latest-wins warmed transitions, no-early-promote clear, allocation rollback/retry, actual/host Fs refresh and lifecycle passed; Process callback allocations/frees=0.\n",error);
}
void tinyCrossings(){unsigned cases=0;for(unsigned fs:{8000u,22050u,44100u,48000u,96000u,192000u})for(float coefficient:{-1e-8f,1e-8f,-.0001f,.0001f,-.8f,.8f}){Core c(fs);auto s=base();s[1]=s[20]=1;s[5]=3;for(unsigned i=0;i<7;++i)s[13+i]=std::bit_cast<int32_t>(i%2?coefficient:-coefficient);c.set(s);auto v=c.render(sine(fs,12000,733,.2),127);assert(rms(v)>1e-5);auto reversed=s;for(unsigned i=13;i<=19;++i)reversed[i]=std::bit_cast<int32_t>(-std::bit_cast<float>(s[i]));c.set(reversed);v=c.render(sine(fs,fs*2,733,.2),127);assert(rms(v,fs)>1e-5);++cases;}std::printf("Mode3 tiny +/-1e-8, +/-1e-4 and +/-0.8, continuous sign crossings, actual8/22.05/44.1/48/96/192k: %u finite nonzero cases passed.\n",cases);}
void factoriesAndEndpoints(){unsigned cases=0;for(unsigned fs:{8000u,22050u,44100u,48000u,96000u,192000u})for(unsigned i=0;i<factories.size();++i){Core c(fs);c.set(factories[i]);const auto out=c.render(sine(fs,fs/4,733,.1),256);assert(std::isfinite(rms(out)));++cases;}
 for(int mode=0;mode<4;++mode){auto s=base();s[1]=s[20]=s[36]=s[39]=s[44]=s[51]=s[53]=s[67]=s[68]=1;s[5]=mode;s[21]=2000;s[37]=240;s[38]=480;s[40]=-20000;s[41]=1;s[42]=500;s[43]=2000;s[45]=240;s[46]=240;s[47]=-1000;s[48]=1;s[49]=20;s[50]=20000;s[52]=450;s[54]=-240;s[69]=1;for(unsigned i=22;i<=28;++i)s[i]=i%2?20000000:-20000000;for(unsigned i=29;i<=35;++i)s[i]=i%2?2400:-2400;Core c(48000);c.set(s);c.render(sine(48000,16000,7999,.5),127);const auto g=c.graph();for(unsigned i=0;i<32;++i){assert(g[8+6*i]>=20&&g[8+6*i]<=20000);assert(g[9+6*i]>=-18000&&g[9+6*i]<=18000);}}
 std::printf("Seven factories x6 actual sample rates: %u finite renders; all4 legacy maximal settings, clamped crossed requests, actual control graph32points and low-Fs filter boundaries passed.\n",cases);
}
struct Effect {
 effect_handle_t h{};effect_uuid_t uuid{0x30306067,0x9400,0x4d53,0x9000,{0,0,0,0,0,1}};
 Effect(){assert(AELI.create_effect(&uuid,0,0,&h)==0);rate(48000);assert(command(EFFECT_CMD_ENABLE)==0);}
 ~Effect(){AELI.release_effect(h);}
 int command(unsigned id,unsigned n=0,void* p=nullptr){int32_t r=0;unsigned count=4;const auto rc=(*h)->command(h,id,n,p,&count,&r);return rc?rc:r;}
 int raw(unsigned id,const void* data,unsigned n,unsigned extra=0){std::array<uint32_t,180> p{};assert(n+16+extra<=p.size()*4);p[1]=4;p[2]=n;p[3]=id;std::memcpy(p.data()+4,data,n);return command(EFFECT_CMD_SET_PARAM,16+n+extra,p.data());}
 void rate(unsigned fs){effect_config_t c{};c.inputCfg.samplingRate=c.outputCfg.samplingRate=fs;c.inputCfg.channels=c.outputCfg.channels=AUDIO_CHANNEL_OUT_STEREO;c.inputCfg.format=c.outputCfg.format=AUDIO_FORMAT_PCM_FLOAT;c.inputCfg.accessMode=EFFECT_BUFFER_ACCESS_READ;c.outputCfg.accessMode=EFFECT_BUFFER_ACCESS_WRITE;c.inputCfg.mask=c.outputCfg.mask=EFFECT_CONFIG_ALL;assert(command(EFFECT_CMD_SET_CONFIG,sizeof(c),&c)==0);}
 template<unsigned N>auto get(unsigned id){std::array<uint32_t,4> q{0,4,N*4,id};std::array<uint32_t,N+4> r{};unsigned n=unsigned(r.size()*4);const int rc=(*h)->command(h,EFFECT_CMD_GET_PARAM,16,q.data(),&n,r.data());assert(rc==0||rc==-EAGAIN);std::array<int32_t,N> v{};if(!rc){assert(!r[0]&&n==(N+4)*4);std::memcpy(v.data(),r.data()+4,N*4);}return v;}
 int invalidGet(unsigned id,unsigned words,unsigned capacity){std::array<uint32_t,4> q{0,4,words*4,id};std::array<uint32_t,300> r{};unsigned n=capacity;const int rc=(*h)->command(h,EFFECT_CMD_GET_PARAM,16,q.data(),&n,r.data());return rc?rc:int32_t(r[0]);}
 void render(){std::array<float,1024> in{},out{};for(unsigned i=0;i<512;++i){in[2*i]=float(.1*std::sin(.07*i));in[2*i+1]=float(.08*std::sin(.09*i));}audio_buffer_t a{},b{};a.frameCount=b.frameCount=512;a.f32=in.data();b.f32=out.data();allocations=releases=0;watch=true;assert((*h)->process(h,&a,&b)==0);watch=false;assert(!allocations&&!releases);for(float x:out)assert(std::isfinite(x)&&std::abs(x)<=1);}
};
void protocol(){Effect e;auto s=standard();assert(e.raw(1430,s.data(),288)==0);const int16_t enabled=1;assert(e.raw(1200,&enabled,2)==0);e.render();auto v=e.get<80>(131231);assert(v[0]==1&&!(v[1]&1)&&v[3]&&v[4]&&v[25]&&v[13]==1&&v[45]==200);auto graph=e.get<200>(131232);assert(graph[0]==1&&graph[4]&&graph[5]==32);
 auto combined=e.get<280>(131233);assert(combined[0]==1&&combined[80]==1&&combined[1]==combined[81]&&!(combined[1]&1)&&combined[2]==combined[82]&&combined[13]==combined[83]);assert(e.invalidGet(131233,279,1136)!=0&&e.invalidGet(131233,281,1136)!=0&&e.invalidGet(131233,280,1132)!=0);assert(e.invalidGet(131231,79,336)!=0&&e.invalidGet(131232,199,816)!=0);
 assert(e.raw(1430,s.data(),284)!=0&&e.raw(1430,s.data(),288,4)!=0);auto bad=s;bad[71]=1;assert(e.raw(1430,bad.data(),288)!=0);bad=s;bad[3]=std::bit_cast<int32_t>(NAN);assert(e.raw(1430,bad.data(),288)!=0);
 std::array<int32_t,148> tools{3,0,1,0};std::copy(s.begin(),s.end(),tools.begin()+4);std::copy(s.begin(),s.end(),tools.begin()+76);assert(e.raw(1431,tools.data(),592)==0);assert(e.raw(1431,tools.data(),588)!=0&&e.raw(1431,tools.data(),592,4)!=0);int32_t clear=1;assert(e.raw(1432,&clear,4)!=0);clear=0;assert(e.raw(1432,&clear,4)==0);
 std::array<float,17> legacy{};legacy[0]=.22f;for(unsigned i=0;i<7;++i)legacy[3+i]=std::bit_cast<float>(s[6+i]);assert(e.raw(115,legacy.data(),68)==0);auto invalid=legacy;invalid[4]=invalid[3];assert(e.raw(115,invalid.data(),68)!=0);invalid=legacy;invalid[16]=NAN;assert(e.raw(115,invalid.data(),68)!=0);assert(e.raw(115,legacy.data(),64)!=0);
 for(unsigned fs:{8000u,16000u,22050u,32000u,44100u,48000u,96000u,192000u}){e.rate(fs);assert(e.raw(1430,s.data(),288)==0);e.render();v=e.get<80>(131231);const unsigned actual=fs>=44100?fs:(fs==22050?44100:48000);assert(v[2]==int(actual)&&v[26]==int(fs)&&v[5]==0&&v[25]);combined=e.get<280>(131233);assert(combined[1]==combined[81]&&combined[2]==combined[82]&&combined[13]==combined[83]);}
 e.rate(48000);assert(e.raw(1431,tools.data(),592)==0);assert(e.raw(65657,&clear,4)==0);assert(e.get<80>(131231)[5]==3);assert(e.command(EFFECT_CMD_RESET)==0);assert(e.get<80>(131231)[5]==0);
 std::atomic<bool> done=false;std::thread audio([&]{while(!done)e.render();});for(unsigned i=0;i<32;++i){if(i%8==0)e.rate(i%16?44100:48000);auto next=standard(int(i%4));next[39]=1;next[41]=1+int(i%10)*100;next[64]=1;next[65]=int(i%5)*40;assert(e.raw(1430,next.data(),288)==0);v=e.get<80>(131231);if(v[0])assert(!(v[1]&1)&&v[13]>=0&&v[13]<=1&&v[45]>=100&&v[45]<=2000&&v[32]<8192);combined=e.get<280>(131233);if(combined[0])assert(combined[80]==1&&combined[1]==combined[81]&&!(combined[1]&1)&&combined[2]==combined[82]&&combined[13]==combined[83]&&combined[85]==32);}done=true;audio.join();assert(e.command(EFFECT_CMD_DISABLE)==0);v=e.get<80>(131231);assert(v[5]==0&&v[16]==INT32_MIN);
 std::puts("Compander Fusion strict72/148/clear and old68byte controls+curve validation,80/200/compound280 exact sizes and coherent epochs/Fs/engine, nativeJames44.1..192k and low-rate compatibility, audio/config/rate concurrency, independent Dynamic tools, reset/disable cleanup and callback no-allocation passed.");
}

}
int main(){std::setvbuf(stdout,nullptr,_IONBF,0);pchipGolden();validation();compatibility();customNeutral();publicCurveWireGuards();standardCurves();rmsTimeGate();standardFeatures();legacyReferenceAlignment();legacyProfessional();relativeDimensions();auditionAndTransitions();tinyCrossings();factoriesAndEndpoints();protocol();std::puts("Compander native verification passed.");}
