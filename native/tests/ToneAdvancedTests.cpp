// SPDX-License-Identifier: GPL-3.0-or-later
#include "../tone/ToneProcessor.h"
#include "Tone36Reference.h"
#include <algorithm>
#include <array>
#include <atomic>
#include <cassert>
#include <cmath>
#include <complex>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <new>
#include <thread>
#include <vector>
#ifndef TONE_CORE_ONLY
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
using dspfusion::ToneProcessor;using S=std::array<int32_t,80>;constexpr double pi=3.14159265358979323846;
S professional(){auto s=ToneProcessor::defaults();s[1]=s[2]=1;return s;}
std::vector<float> signal(unsigned fs,unsigned n,double hz=131,double amp=.08,bool burst=false){std::vector<float> out(n*2);uint32_t rng=17;
 for(unsigned i=0;i<n;++i){rng=rng*1664525u+1013904223u;const double envelope=!burst||i%(fs/5)>fs/20?1.:.015;
  out[2*i]=float(envelope*amp*(.6*std::sin(2*pi*hz*i/fs)+.22*std::sin(2*pi*733*i/fs)+.1*std::sin(2*pi*7103*i/fs)+.08*(int32_t(rng)/2147483648.)));
  out[2*i+1]=float(envelope*amp*(.7*std::sin(2*pi*hz*i/fs+.27)+.3*std::sin(2*pi*1831*i/fs)));}return out;}
std::vector<float> sine(unsigned fs,unsigned n,double hz,double amp=.02){std::vector<float> out(n*2);for(unsigned i=0;i<n;++i)out[2*i]=out[2*i+1]=float(amp*std::sin(2*pi*hz*i/fs));return out;}
double rms(const std::vector<float>& in,unsigned start=0){double e=0;for(unsigned i=2*start;i<in.size();++i)e+=double(in[i])*in[i];return std::sqrt(e/(in.size()-2*start));}
double error(const std::vector<float>& a,const std::vector<float>& b,unsigned start=0){assert(a.size()==b.size());double e=0;for(unsigned i=2*start;i<a.size();++i)e+=std::abs(a[i]-b[i]);return e/(a.size()-2*start);}
std::complex<double> amplitude(const std::vector<float>& v,unsigned fs,double hz,unsigned start,unsigned n){std::complex<double>a=0;for(unsigned i=0;i<n;++i)a+=double(v[2*(start+i)])*std::polar(1.,-2*pi*hz*(start+i)/fs);return a*2./double(n);}
struct Core{ToneProcessor p;unsigned fs;explicit Core(unsigned rate=48000):fs(rate){p.configure(rate);}void set(const S& s,bool reset=true){assert(p.setAdvanced(s.data(),s.size()));if(reset)p.reset();}
 auto status()const{std::array<int32_t,64> v{};p.status(v.data());return v;}auto response()const{std::array<int32_t,200> v{};p.response(v.data());return v;}
 bool tools(int mode,const S& a,const S& b,bool match=false,int epoch=1){std::array<int32_t,164> v{mode,int(match),epoch,0};std::copy(a.begin(),a.end(),v.begin()+4);std::copy(b.begin(),b.end(),v.begin()+84);return p.setTools(v.data(),v.size());}
 auto render(const std::vector<float>& input,unsigned chunk=137){auto out=input;for(unsigned i=0;i<out.size()/2;i+=chunk){const auto n=std::min(chunk,unsigned(out.size()/2-i));allocations=releases=0;watch=true;p.process(out.data()+2*i,n);watch=false;assert(!allocations&&!releases);}for(float x:out)assert(std::isfinite(x)&&std::abs(x)<=64);return out;}
};
void validation(){const auto defaults=ToneProcessor::defaults();assert(ToneProcessor::validateAdvanced(defaults.data(),80));assert(!ToneProcessor::validateAdvanced(nullptr,80)&&!ToneProcessor::validateAdvanced(defaults.data(),79)&&!ToneProcessor::validateAdvanced(defaults.data(),81));
 auto s=professional();for(unsigned i=72;i<80;++i){s[i]=1;assert(!ToneProcessor::validateAdvanced(s.data(),80));s[i]=0;}for(int range:{20,40,100}){s[3]=range;s[4]=range;assert(ToneProcessor::validateAdvanced(s.data(),80));s[4]=range+1;assert(!ToneProcessor::validateAdvanced(s.data(),80));s[4]=0;}s[3]=21;assert(!ToneProcessor::validateAdvanced(s.data(),80));
 s=professional();s[54]=500;s[55]=50;assert(!ToneProcessor::validateAdvanced(s.data(),80));s[55]=51;assert(ToneProcessor::validateAdvanced(s.data(),80));s[56]=500;s[57]=50;assert(!ToneProcessor::validateAdvanced(s.data(),80));
 Core c;const auto before=c.status();auto bad=professional();bad[43]=1001;assert(!c.p.setAdvanced(bad.data(),80)&&before==c.status());
 std::array<int32_t,164> tools{6,1,17,0};std::copy(defaults.begin(),defaults.end(),tools.begin()+4);std::copy(defaults.begin(),defaults.end(),tools.begin()+84);assert(ToneProcessor::validateTools(tools.data(),164));tools[2]=0;assert(!ToneProcessor::validateTools(tools.data(),164));tools[2]=1;tools[3]=1;assert(!ToneProcessor::validateTools(tools.data(),164));tools[3]=0;tools[159]=1;assert(!ToneProcessor::validateTools(tools.data(),164));
 assert(before[0]==1&&!(before[1]&1)&&before[2]==48000&&before[13]&&before[14]==100&&before[31]==2000);for(unsigned i=16;i<24;++i)assert(before[i]==INT32_MIN);
 std::puts("Tone advanced80/tools164 strict range, dormant fields, reserved slots, ordering and failure rollback; runtime64/response200 coherent defaults passed.");}
void compatibility(){unsigned cases=0;for(unsigned fs:{8000u,44100u,48000u,96000u,192000u,384000u})for(unsigned pattern=0;pattern<5;++pattern)for(unsigned variant=0;variant<4;++variant){
 auto s=professional();s[1]=variant?1:0;std::array<int32_t,11> original{};original[0]=s[2]=pattern!=4;
 for(unsigned i=0;i<10;++i)original[i+1]=s[i+4]=pattern==0?0:pattern==1?int((i*19+13)%201)-100:pattern==2?100:pattern==3?-100:int((i*37+11)%201)-100;
 if(variant==2){s[14]=s[26]=s[49]=s[64]=s[66]=s[68]=1;s[70]=s[71]=1;}
 if(variant==3){s[15]=0;for(unsigned i=16;i<26;++i)s[i]=1777;s[27]=19999;s[37]=400;s[43]=250;s[53]=999;s[54]=1;s[55]=499;s[58]=4000;s[60]=120;s[65]=0;s[67]=s[69]=-240;}
 Core c(fs);c.set(s);dspfusion::Tone36Reference ref;ref.configure(fs);assert(ref.setParameters(original.data(),11));ref.reset();
 auto input=signal(fs,7000,173,.06,true),gold=input;ref.process(gold.data(),7000);const auto actual=c.render(input,17);
 if(actual!=gold){for(unsigned i=0;i<actual.size();++i)if(actual[i]!=gold[i]){std::printf("Frozen mismatch Fs%u pattern%u variant%u sample%u %.9g/%.9g\n",fs,pattern,variant,i,actual[i],gold[i]);break;}}assert(actual==gold);
 // Existing nonzero kernel history survives saved inactive values, diagnostics and
 // enabled neutral groups; old updates keep original 20ms sample smoothing.
 s[70]=s[71]=!s[70];assert(c.p.setAdvanced(s.data(),80));input=signal(fs,4000,611,.03);gold=input;ref.process(gold.data(),4000);assert(c.render(input,1)==gold);
 if(pattern!=4){for(unsigned i=0;i<10;++i)original[i+1]=s[i+4]=int((i*23+17)%201)-100;assert(ref.setParameters(original.data(),11));assert(c.p.setAdvanced(s.data(),80));input=signal(fs,7000,83,.04);gold=input;ref.process(gold.data(),7000);const auto next=c.render(input,509);if(next!=gold){for(unsigned ix=0;ix<next.size();++ix)if(next[ix]!=gold[ix]){std::printf("History mismatch Fs%u pattern%u variant%u sample%u %.9g/%.9g\n",fs,pattern,variant,ix,next[ix],gold[ix]);break;}}assert(next==gold);}
 ++cases;}
 std::printf("Tone frozen ui36: %u Fs8..384k/nonzero/bypass/pro cases bit-identical, dormant saved controls, enabled neutral groups, diagnostics, chunk1/17/509 and original history/smoothing passed.\n",cases);}
void effective(){auto base=professional();base[14]=base[26]=base[49]=base[64]=base[66]=base[68]=1;for(unsigned i=0;i<10;++i)base[4+i]=i%3?55:-45;
 const auto in=signal(48000,48000,113,.07,true);Core c;c.set(base);const auto reference=c.render(in);unsigned n=0;
 auto compare=[&](unsigned index,int value){auto s=base;s[index]=value;Core p;p.set(s);const auto out=p.render(in);const double difference=error(out,reference,12000);if(difference<=1e-7)std::printf("Ineffective slot%u value%d error%.9g\n",index,value,difference);assert(difference>1e-7);++n;};
 compare(15,400);for(unsigned i=16;i<26;++i)compare(i,i%2?400:-500);
 for(unsigned i=27;i<=36;++i)compare(i,i%2?900:2200);for(unsigned i=37;i<=42;++i)compare(i,300);for(unsigned i=43;i<=46;++i)compare(i,250);
 compare(47,1900);compare(48,200);compare(50,0);compare(51,0);compare(52,0);compare(53,800);compare(54,300);compare(55,400);compare(56,300);compare(57,400);compare(58,250);compare(59,250);compare(60,0);compare(61,0);compare(65,400);compare(67,-120);compare(69,-120);
 // Floor has its own effective context (weak protection must first be on).
 base[63]=-300;Core weakOff;weakOff.set(base);const auto unprotected=weakOff.render(in);base[62]=1;Core protectedBase;protectedBase.set(base);const auto protectedOut=protectedBase.render(in);assert(error(protectedOut,unprotected,10000)>1e-7);++n;auto changed=base;changed[63]=-1000;Core floor;floor.set(changed);assert(error(protectedOut,floor.render(in),10000)>1e-7);++n;
 auto off=professional();for(unsigned i=0;i<10;++i)off[i+4]=base[i+4];auto inactive=off;inactive[15]=0;inactive[16]=2000;inactive[27]=20000;inactive[37]=400;inactive[43]=250;inactive[50]=0;inactive[53]=1000;inactive[54]=300;inactive[55]=500;inactive[58]=4000;inactive[62]=1;inactive[65]=0;inactive[67]=inactive[69]=-240;Core plain,dormant;plain.set(off);dormant.set(inactive);assert(plain.render(in)==dormant.render(in));
 std::printf("Tone %u numeric core/frequency/Q/shelf-S/temperature/bass-static/dynamic/time/sensitivity/cap/weak-floor/mix/output/headroom dimensions measured from real audio; disabled groups bit-neutral.\n",n);}
void shapeResponse(){double maxError=0;unsigned cases=0;for(unsigned fs:{8000u,48000u,96000u}){auto s=professional();s[14]=s[26]=s[64]=s[66]=s[68]=s[71]=1;s[4]=35;s[5]=20;s[7]=50;s[8]=-40;s[10]=45;s[13]=30;s[31]=425;s[38]=370;s[43]=300;s[47]=1300;s[48]=700;s[65]=700;s[67]=-15;s[69]=-10;
 Core graph(fs);graph.set(s);const auto points=graph.response();assert(points[3]&&points[4]==32);for(unsigned index:{0u,7u,14u,21u,27u,31u}){const unsigned at=8+6*index;const double hz=points[at];Core actual(fs);actual.set(s);const auto input=sine(fs,fs*2,hz),out=actual.render(input);const auto transfer=amplitude(out,fs,hz,fs,fs)/amplitude(input,fs,hz,fs,fs);const double e=std::abs(2000*std::log10(std::abs(transfer))-points[at+2]);if(e>=3)std::printf("Graph error Fs%u hz%.4g expected%d measured%.6g\n",fs,hz,points[at+2],2000*std::log10(std::abs(transfer)));assert(e<3);maxError=std::max(maxError,e);++cases;}}
 // A Q4 peak halfway between old sparse grid frequencies remains covered by
 // analytic per-filter maxima; closely overlapping boosts also remain covered.
 auto s=professional();s[26]=s[14]=s[71]=1;for(unsigned i=4;i<14;++i)s[i]=100;s[15]=2000;for(unsigned i=27;i<=36;++i)s[i]=677;for(unsigned i=37;i<=42;++i)s[i]=400;s[47]=2000;s[48]=0;
 Core worst;worst.set(s);const auto v=worst.status();assert(v[27]<-5000);const auto out=worst.render(sine(48000,96000,677,.9));assert(rms(out,48000)<.9);
 std::printf("Tone static complex dry/wet response matches %u real sine renders across Fs8/48/96kHz within%.6g centidB; high-Q/overlapping peaks use analytic gain bounds.\n",cases,maxError);}
void dryAndRoutes(){auto s=professional();s[5]=75;s[26]=s[64]=s[66]=s[68]=1;s[65]=0;s[67]=-60;s[69]=-30;Core dry;dry.set(s);const auto in=signal(48000,24000),out=dry.render(in);const double g=std::pow(10.,-9./20);for(unsigned i=0;i<out.size();++i)assert(std::abs(out[i]-in[i]*g)<2e-8);
 s[65]=700;Core raw,effect,difference;for(auto* p:{&raw,&effect,&difference})p->set(s);assert(raw.tools(1,s,s)&&effect.tools(2,s,s)&&difference.tools(3,s,s));const auto a=raw.render(in),b=effect.render(in),d=difference.render(in);for(unsigned i=40000;i<out.size();++i)assert(std::abs(b[i]-a[i]-d[i])<2e-7);
 s[70]=1;Core meters;meters.set(s);meters.render(in);auto v=meters.status();for(unsigned i=16;i<24;++i)assert(v[i]!=INT32_MIN);assert(v[27]<0&&v[28]==-30&&v[32]==700&&v[33]==-60);meters.render(std::vector<float>(2000,0));v=meters.status();assert(v[16]==INT32_MIN&&v[17]==INT32_MIN);s[70]=0;meters.set(s,false);for(unsigned i=16;i<24;++i)assert(meters.status()[i]==INT32_MIN);
 std::puts("Tone dry0 applies output/manual attenuation correctly; raw/effect/difference same-input relation, actual automatic headroom, RMS/peak and quiet/disabled sentinels passed.");}
void immediatePowerAndProRestore(){unsigned cases=0;double maximumError=0,peak=0;
 for(unsigned fs:{8000u,48000u,192000u}){auto s=professional();s[14]=s[26]=s[49]=s[64]=s[66]=s[68]=1;s[15]=2000;for(unsigned i=4;i<14;++i)s[i]=i%2?70:-65;for(unsigned i=27;i<=36;++i)s[i]=20;for(unsigned i=37;i<=42;++i)s[i]=400;s[54]=s[56]=500;s[55]=s[57]=500;s[51]=s[52]=2000;s[60]=s[61]=30;s[65]=400;s[67]=-180;s[69]=-60;
 Core off(fs),untouched(fs),restore(fs),base(fs);off.set(s);untouched.set(s);restore.set(s);auto plain=s;plain[1]=0;base.set(plain);const auto whole=sine(fs,fs*3,311,.07);const std::vector<float> before(whole.begin(),whole.begin()+fs*2),after(whole.begin()+fs*2,whole.end());for(auto* p:{&off,&untouched,&restore,&base})p->render(before);
 auto disabled=s;disabled[2]=0;off.set(disabled,false);assert(off.status()[3]==0&&off.status()[62]==0);const auto actual=off.render(after,17),effect=untouched.render(after,17);const double alpha=1-std::exp(-1./(.020*fs));for(unsigned frame:{0u,fs/100,fs/50,fs/10}){const double wet=std::pow(1-alpha,frame+1);const double expected=after[2*frame]+wet*(effect[2*frame]-after[2*frame]);maximumError=std::max(maximumError,std::abs(actual[2*frame]-expected));assert(std::abs(actual[2*frame]-expected)<2e-8);}for(unsigned frame=fs/2;frame<fs*2;++frame)assert(actual[2*frame]==after[2*frame]);
 restore.set(plain,false);assert(restore.status()[4]==0&&restore.status()[62]==0&&restore.status()[5]==0);const auto restored=restore.render(after,19),baseline=base.render(after,19);for(float x:restored)peak=std::max(peak,std::abs(double(x)));assert(error(restored,baseline,fs)<1e-8&&peak<.5);++cases;}
 std::printf("Tone %u Fs8/48/192k strong low20Hz/Q4/500ms-envelope power-OFF proofs: no candidate wait, immediate original20ms exponential bypass within%.7g float; professional OFF restores basis with smooth mix/output/headroom and bounded caps (peak%.7g), no warm/late revival.\n",cases,maximumError,peak);
}
void crossfadeProof(){unsigned cases=0;double errorMaximum=0;
 for(unsigned fs:{8000u,48000u,192000u}){auto old=professional(),target=professional();old[5]=70;target=old;target[26]=1;target[29]=900;target[64]=1;target[65]=600;
 Core changing(fs),referenceOld(fs),referenceNew(fs);changing.set(old);referenceOld.set(old);referenceNew.set(target);
 const auto whole=sine(fs,fs*3,311,.07);const std::vector<float> before(whole.begin(),whole.begin()+fs*2),after(whole.begin()+fs*2,whole.end());changing.render(before);referenceOld.render(before);referenceNew.render(before);
 changing.set(target,false);const unsigned warm=unsigned(changing.status()[62])*fs/1000,fade=fs/50;assert(warm==fs*35/100);const auto actual=changing.render(after,17),a=referenceOld.render(after,17),b=referenceNew.render(after,17);
 for(unsigned frame=0;frame<warm-1;++frame)for(unsigned ch=0;ch<2;++ch)assert(actual[2*frame+ch]==a[2*frame+ch]);
 for(unsigned step:{1u,fade/4,fade/2,fade*3/4,fade}){const unsigned frame=warm+step-2;const double blend=double(step)/fade;for(unsigned ch=0;ch<2;++ch){const double expected=a[2*frame+ch]+blend*(b[2*frame+ch]-a[2*frame+ch]);const double e=std::abs(actual[2*frame+ch]-expected);errorMaximum=std::max(errorMaximum,e);assert(e<1e-7);}}
 assert(error(actual,b,fs)<1e-9);++cases;}
 std::printf("Tone %u Fs8/48/192k transition proofs: old audio exact throughout350ms warm, 20ms quarter/half/full fade follows independent warmed-reference interpolation within%.7g float, settled target exact within1e-9 mean.\n",cases,errorMaximum);
}
void unmatchedAndRefresh(){auto a=professional(),b=professional();a[5]=80;b[5]=-60;b[66]=1;b[67]=-90;
 Core listening;listening.set(a);assert(listening.tools(5,a,b,false,61));const auto in=signal(48000,48000,311,.03);const auto out=listening.render(in);auto v=listening.status();assert(v[5]==5&&v[7]==0&&v[8]==0&&v[61]>=v[62]);Core golden;golden.set(b);const auto gold=golden.render(in);assert(error(out,gold,30000)<1e-8);
 // Quiet matching failure does not disable ordinary A/B listening.
 assert(listening.tools(6,a,b,true,62));listening.render(std::vector<float>(260000));assert(listening.status()[7]==4);assert(listening.tools(5,a,b,false,62));const auto recovered=listening.render(in);assert(error(recovered,gold,30000)<1e-8);
 // Repeated SETs while warming and diagnostic-only edits do not restart the
 // candidate or the AB warm-up, and unmatched A/B never measures automatically.
 Core pending(8000);auto s=professional();s[5]=70;pending.set(s);s[26]=1;s[29]=800;pending.set(s,false);pending.render(signal(8000,1000));const int before=pending.status()[61];s[70]=1;pending.set(s,false);assert(pending.status()[61]==before);pending.render(signal(8000,4000));assert(pending.status()[53]==800);
 assert(listening.tools(5,a,b,false,62));const auto epoch=listening.status()[1];assert(listening.tools(5,a,b,false,62));assert(listening.status()[7]==4&&listening.status()[8]==2000&&listening.status()[1]!=epoch);
 // New snapshots are warmed in separate storage; their cold filters cannot
 // overwrite an audible old AB kernel, even with rapid repeated new epochs.
 for(unsigned i=0;i<8;++i){auto fresh=b;fresh[5]=i%2?90:-90;fresh[26]=1;fresh[29]=100+int(i)*70;assert(listening.tools(5,a,fresh,false,100+int(i)));listening.render(signal(48000,97,311,.03));}
 listening.render(signal(48000,48000,311,.03));assert(listening.status()[5]==5&&listening.status()[7]==0&&listening.status()[8]==0);
 auto proOff=a;proOff[1]=0;listening.set(proOff,false);assert(listening.status()[5]==0&&listening.status()[11]==0&&listening.status()[7]==0);
 listening.set(a);assert(listening.tools(6,a,b,true,177));listening.p.disable();assert(!listening.status()[3]&&!listening.status()[5]&&!listening.status()[11]);listening.render(signal(48000,48000,311,.03));assert(listening.render(in)==in);
 std::puts("Tone unmatched A/B listens immediately after warm-up without2s measurement; quiet-failed matching remains listenable, retired snapshots cannot expose cold replacements, diagnostic refresh preserves pending progress, professional OFF clears tools, and explicit OFF preserves foundation without late revival.");}
void toolsAndTransition(){auto a=professional(),b=professional();a[5]=75;b[7]=-80;b[8]=70;b[64]=1;b[65]=400;b[66]=1;b[67]=-90;a[70]=a[71]=1;Core c;c.set(a);assert(c.tools(6,a,b,true,29));c.render(signal(48000,130000,733,.03));auto v=c.status();assert(v[7]==3&&v[8]==2000&&v[9]<=0&&v[10]<=0&&v[11]==29);const auto ga=v[9],gb=v[10];assert(c.tools(4,a,b,true,29));const auto outA=c.render(signal(48000,48000,733,.03));assert(c.tools(5,a,b,true,29));const auto outB=c.render(signal(48000,48000,733,.03));assert(std::abs(20*std::log10(rms(outA,24000)/rms(outB,24000)))<.05);
 a[70]=0;c.set(a,false);assert(c.status()[7]==3&&c.status()[9]==ga&&c.status()[10]==gb);c.p.clearTools();assert(c.status()[5]==0&&c.status()[7]==0&&c.status()[11]==0);assert(c.tools(6,a,b,true,30));c.render(std::vector<float>(260000));assert(c.status()[7]==4);
 c.p.configure(44100);assert(c.status()[2]==44100&&c.status()[11]==0&&c.status()[5]==0);c.fs=44100;assert(c.tools(3,a,b));c.p.reset();assert(c.status()[5]==0);auto disabled=a;disabled[2]=0;c.set(disabled,false);assert(!c.tools(4,a,b)&&c.status()[11]==0);
 // Until warmed, candidate never interrupts the audible legacy kernel. Clear
 // transient tools cannot jump a pending candidate to the output prematurely.
 Core changing(8000),reference(8000);auto first=professional();first[5]=45;changing.set(first);reference.set(first);auto before=signal(8000,8000,113,.05);changing.render(before);reference.render(before);auto target=first;target[26]=1;target[29]=300;changing.set(target,false);const auto early=signal(8000,800,113,.05);assert(changing.render(early,7)==reference.render(early,7));changing.p.clearTools();assert(changing.render(early,11)==reference.render(early,11));for(unsigned i=0;i<20;++i){auto intermediate=target;intermediate[29]=400+i*10;changing.set(intermediate,false);changing.render(signal(8000,17,113,.05));}target[29]=777;changing.set(target,false);changing.render(signal(8000,16000,113,.05));assert(changing.status()[53]==777);const auto graph=changing.response();assert(graph[1]==changing.status()[1]&&graph[2]==changing.status()[2]);
 std::printf("Tone same-input AB prewarm+2s RMS fixed matching measured%.7gdB residual; quiet invalid, mode/power/reset/rate cleanup, idempotent snapshots, latest-wins warmed20ms handoff and pending ClearTools continuity passed.\n",20*std::log10(rms(outA,24000)/rms(outB,24000)));}
constexpr std::array<S,6> factories{{
{1,0,0,100,0,0,0,0,0,0,0,0,0,0,0,1000,0,0,0,0,0,0,0,0,0,0,0,180,3500,60,110,350,1800,3500,5500,8500,12000,80,75,80,100,110,110,1000,1000,1000,1000,1000,1000,0,1000,1000,1000,150,50,70,20,45,1000,1000,30,30,0,-800,0,1000,0,0,0,0,0,0,0,0,0,0,0,0,0,0},
{1,1,0,100,0,0,0,0,0,0,0,0,0,0,1,1000,200,100,0,150,-30,-50,-100,-100,-20,-80,1,160,3200,60,110,320,1800,3500,5500,8500,12000,80,75,80,100,110,110,1000,1000,1000,1000,1100,900,0,1000,1000,1000,150,50,70,20,45,1000,1000,30,30,0,-800,1,850,1,0,0,0,0,0,0,0,0,0,0,0,0,0},
{1,1,0,100,0,0,0,0,0,0,0,0,0,0,1,1000,0,-40,-20,-100,250,80,-20,-30,0,30,1,180,3500,60,110,350,2000,3300,5500,8500,12000,80,75,90,100,110,110,1000,1000,1000,1000,1000,1000,0,1000,1000,1000,150,50,70,20,45,1000,1000,30,30,0,-800,1,850,1,0,0,0,0,0,0,0,0,0,0,0,0,0},
{1,1,0,100,0,0,0,0,0,0,0,0,0,0,1,1000,50,180,250,20,0,0,0,0,120,0,0,180,3500,60,110,350,1800,3500,5500,8500,12000,80,75,80,100,110,110,1000,1000,1000,1000,1000,1000,1,700,1400,1000,130,30,90,20,55,1250,1000,40,30,1,-750,1,800,1,0,1,-10,0,0,0,0,0,0,0,0,0,0},
{1,1,0,100,0,0,0,0,0,0,0,0,0,0,1,1000,-80,-40,0,-50,50,60,-30,-50,50,250,1,180,4000,60,110,350,1800,3500,5500,8500,13000,80,75,80,100,110,110,1000,800,1000,800,1000,1000,0,1000,1000,1000,150,50,70,20,45,1000,1000,30,30,0,-800,1,800,1,0,0,0,0,0,0,0,0,0,0,0,0,0},
{1,1,0,100,0,0,0,0,0,0,0,0,0,0,1,1000,150,40,-60,70,-30,-60,-250,-200,-150,-80,0,180,3500,60,110,350,1800,3500,5500,8500,12000,80,75,80,100,110,110,1000,1000,1000,1000,1000,1000,1,800,700,800,150,50,70,40,65,1000,1000,20,20,1,-800,1,850,1,0,0,0,0,0,0,0,0,0,0,0,0,0}
}};

void ratesAndFactories(){unsigned cases=0;double peak=0;for(unsigned fs:{8000u,8001u,16000u,22050u,32000u,44100u,48000u,96000u,192000u,384000u})for(unsigned extreme=0;extreme<2;++extreme){auto s=professional();s[14]=s[26]=s[49]=s[64]=s[66]=s[68]=s[70]=s[71]=1;s[15]=extreme?2000:0;
 for(unsigned i=4;i<14;++i)s[i]=i%2?100:-100;for(unsigned i=16;i<26;++i)s[i]=extreme?2000:-2000;for(unsigned i=27;i<=36;++i)s[i]=extreme?20000:20;for(unsigned i=37;i<=42;++i)s[i]=extreme?400:25;for(unsigned i=43;i<=46;++i)s[i]=extreme?1000:250;s[47]=s[48]=extreme?2000:0;s[50]=s[51]=s[52]=extreme?2000:0;s[53]=extreme?1000:20;s[54]=s[56]=extreme?500:1;s[55]=s[57]=500;s[58]=s[59]=extreme?4000:250;s[60]=s[61]=extreme?120:0;s[65]=extreme?1000:0;s[67]=s[69]=extreme?0:-240;Core c(fs);c.set(s);auto input=signal(fs,10000,fs*.39,.8,true);input[31]=NAN;input[72]=INFINITY;const auto out=c.render(input);for(float x:out)peak=std::max(peak,std::abs(double(x)));const auto v=c.status();assert(v[2]==int(fs)&&v[13]);for(unsigned i=51;i<=60;++i)assert(v[i]>=0&&v[i]<int(fs/2));const auto graph=c.response();assert(graph[3]&&graph[4]==32&&graph[1]==v[1]);++cases;}
 for(unsigned fs:{8000u,44100u,48000u,96000u,192000u,384000u})for(bool nonzero:{false,true})for(const auto& preset:factories){auto s=preset;s[2]=1;if(nonzero)for(unsigned i=0;i<10;++i)s[4+i]=-31+int(i)*7;assert(ToneProcessor::validateAdvanced(s.data(),80));Core c(fs);c.set(s);const auto out=c.render(signal(fs,12000,131,.05,true));for(float x:out)peak=std::max(peak,std::abs(double(x)));++cases;}
 assert(peak<64);std::printf("Tone %u extreme/Fs8..384k and six literal factory/zero+nonzero basis cases finite, low-Fs saved requests untouched/actual filters Nyquist-safe, strict packet rollback and callback no-allocation passed (peak%.7g).\n",cases,peak);}
#ifndef TONE_CORE_ONLY
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
void protocol(){Effect e;auto s=professional();s[5]=70;s[14]=s[26]=s[49]=s[70]=s[71]=1;s[16]=50;assert(e.raw(196640,s.data(),320)==0);for(unsigned i=0;i<100;++i)e.render();auto v=e.get<64>(131235);assert(v[0]==1&&!(v[1]&1)&&v[2]==48000&&v[3]&&v[4]&&v[13]&&v[15]);auto graph=e.get<200>(131236);assert(graph[0]==1&&graph[3]&&graph[4]==32);assert((e.get<1>(131234)[0]&7)==7);
 auto combined=e.get<264>(131237);assert(combined[0]==1&&combined[64]==1&&combined[1]==combined[65]&&!(combined[1]&1)&&combined[2]==combined[66]&&combined[68]==32);assert(e.invalidGet(131237,263,1072)!=0&&e.invalidGet(131237,265,1072)!=0&&e.invalidGet(131237,264,1068)!=0);assert(e.invalidGet(131235,63,272)!=0&&e.invalidGet(131236,199,816)!=0&&e.invalidGet(131234,2,24)!=0);
 assert(e.raw(196640,s.data(),316)!=0&&e.raw(196640,s.data(),320,4)!=0);for(unsigned i=72;i<80;++i){auto bad=s;bad[i]=1;assert(e.raw(196640,bad.data(),320)!=0);}auto bad=s;bad[3]=20;bad[5]=21;assert(e.raw(196640,bad.data(),320)!=0);bad=s;bad[54]=500;bad[55]=50;assert(e.raw(196640,bad.data(),320)!=0);
 std::array<int32_t,164> tools{3,0,1,0};std::copy(s.begin(),s.end(),tools.begin()+4);std::copy(s.begin(),s.end(),tools.begin()+84);assert(e.raw(196641,tools.data(),656)==0);assert(e.raw(196641,tools.data(),652)!=0&&e.raw(196641,tools.data(),656,4)!=0);int32_t clear=1;assert(e.raw(196642,&clear,4)!=0);clear=0;assert(e.raw(196642,&clear,4)==0);assert(e.raw(196643,&clear,0)!=0&&e.raw(196643,&clear,4,4)!=0);clear=1;assert(e.raw(196643,&clear,4)!=0);clear=0;
 // Independent control mutations do not clear Tone tools.
 assert(e.raw(196641,tools.data(),656)==0);assert(e.raw(65657,&clear,4)==0);assert(e.get<64>(131235)[5]==3);
 // Explicit off retains last valid foundation and all professional controls,
 // and pending targets cannot turn the module back on after the off command.
 auto pending=s;pending[29]=777;assert(e.raw(196640,pending.data(),320)==0);assert(e.raw(196643,&clear,4)==0);for(unsigned i=0;i<100;++i)e.render();v=e.get<64>(131235);assert(!v[3]&&v[41]==50&&v[42]==700&&!v[5]&&!v[11]);
 for(unsigned fs:{8000u,16000u,22050u,32000u,44100u,48000u,96000u,192000u}){e.rate(fs);assert(e.raw(196640,s.data(),320)==0);e.render();v=e.get<64>(131235);assert(v[2]==int(fs)&&v[13]&&v[5]==0);combined=e.get<264>(131237);assert(combined[1]==combined[65]&&combined[2]==combined[66]&&combined[68]==32);for(unsigned i=51;i<=60;++i)assert(v[i]>=0&&v[i]<int(fs/2));}
 e.rate(48000);assert(e.raw(196641,tools.data(),656)==0);assert(e.command(EFFECT_CMD_RESET)==0);assert(e.get<64>(131235)[5]==0);
 std::array<int32_t,11> old{};old[0]=1;old[1]=100000;assert(e.raw(196608,old.data(),44)==0);assert(e.raw(196608,old.data(),40)!=0);for(unsigned i=0;i<100;++i)e.render();v=e.get<64>(131235);assert(v[41]==1000); // old clamps100, saved professional delta5 remains active
 std::atomic<bool> done=false;std::thread audio([&]{while(!done)e.render();});for(unsigned i=0;i<48;++i){if(i%8==0)e.rate(i%16?44100:48000);auto next=s;next[27]=20+int(i)*100;next[29]=20+int(i)*37;next[16]=int(i)*10;assert(e.raw(196640,next.data(),320)==0);v=e.get<64>(131235);if(v[0])assert(!(v[1]&1)&&v[2]>=8000&&v[2]<=384000&&v[13]&&v[14]==100);combined=e.get<264>(131237);if(combined[0])assert(combined[64]==1&&combined[1]==combined[65]&&!(combined[1]&1)&&combined[2]==combined[66]&&combined[68]==32);}done=true;audio.join();assert(e.command(EFFECT_CMD_DISABLE)==0);v=e.get<64>(131235);assert(v[5]==0&&v[16]==INT32_MIN);
 std::puts("Tone Fusion strict80/164/clear/off and legacy11 controls, extended capabilities value7 (Tone bit0 preserved), runtime64/response200/compound264 exact sizes and coherent epochs/Fs, hostFs8..192k, audio/config/rate concurrency, independent Dynamic tools, invalid rollback, pending/off preserving basis, reset/disable cleanup and callback no-allocation passed.");
}
#endif
}
int main(){std::setvbuf(stdout,nullptr,_IONBF,0);validation();compatibility();effective();shapeResponse();dryAndRoutes();toolsAndTransition();immediatePowerAndProRestore();crossfadeProof();unmatchedAndRefresh();ratesAndFactories();
#ifndef TONE_CORE_ONLY
protocol();
#endif
std::puts("Tone advanced native verification passed.");}
