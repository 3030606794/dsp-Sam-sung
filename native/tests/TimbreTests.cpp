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
#include "essential.h"
#include "../timbre/Timbre.h"
extern "C" {
#include "Timbre33Reference.h"
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
using S=std::array<int32_t,32>;
constexpr S original{1,0,0,0,1000,1000,1000,1000,1000,0,600,1900,4400,8000,12000,0,0,0,50,0,1000,0,0,0,1000,0,2,0,0,0,0,0};
constexpr std::array<S,3> factories{{
 {1,1,1,10,800,650,1100,850,400,1,600,1900,4400,8000,12000,100,14000,1,50,1,700,1,-5,1,1000,1,2,0,0,0,0,0},
 {1,1,1,20,900,1300,1200,650,300,1,500,1700,4000,7500,11500,70,12000,1,50,1,750,1,-10,1,1000,1,4,0,0,0,0,0},
 {1,1,1,0,600,250,500,900,1100,1,700,2200,4800,8500,12500,250,16000,1,50,1,600,1,0,1,1000,1,2,0,0,0,0,0}}};
constexpr double pi=3.14159265358979323846;
S core(){auto s=original;s[1]=s[2]=1;return s;}
std::vector<float> signal(unsigned rate,unsigned frames,double hz=1000,double amplitude=.4){std::vector<float> v(frames*2);
 for(unsigned i=0;i<frames;++i){v[2*i]=float(amplitude*std::sin(2*pi*hz*i/rate));v[2*i+1]=float(amplitude*.8*std::sin(2*pi*hz*i/rate+.31));}return v;}
struct Core {
 std::unique_ptr<JamesDSPLib> owner=std::make_unique<JamesDSPLib>();void* e=TimbreCreate();unsigned rate;
 std::array<float,512> left{},right{};
 explicit Core(unsigned fs=48000,unsigned host=0):rate(fs){assert(e);owner->fs=float(fs);owner->trueSampleRate=float(host?host:fs);owner->tmpBuffer[0]=left.data();owner->tmpBuffer[1]=right.data();TimbreRefresh(e,owner.get());TimbreEnable(e,owner.get(),1);}
 ~Core(){TimbreDestroy(e);}
 void set(const S& s){assert(TimbreSet(e,owner.get(),s.data(),s.size()));}
 auto status()const{std::array<int32_t,48> v;TimbreStatus(e,v.data());return v;}
 bool tools(int mode,const S& a,const S& b,bool match=false,int epoch=1,int soloGain=0){std::array<int32_t,68> v{mode,int(match),epoch,soloGain};std::copy(a.begin(),a.end(),v.begin()+4);std::copy(b.begin(),b.end(),v.begin()+36);return TimbreTools(e,owner.get(),v.data(),v.size());}
 std::vector<float> render(const std::vector<float>& in,unsigned chunk=173){std::vector<float> out(in.size());
  for(size_t pos=0;pos<in.size()/2;pos+=chunk){const auto n=std::min(size_t(chunk),in.size()/2-pos);for(size_t i=0;i<n;++i){left[i]=in[2*(pos+i)];right[i]=in[2*(pos+i)+1];}
   allocations=releases=0;watch=true;TimbreProcess(e,owner.get(),n);watch=false;assert(!allocations&&!releases);
   for(size_t i=0;i<n;++i){out[2*(pos+i)]=left[i];out[2*(pos+i)+1]=right[i];assert(std::isfinite(left[i])&&std::isfinite(right[i]));}}
  return out;}
 void warm(unsigned frames=48000){render(std::vector<float>(frames*2));}
 void refresh(unsigned fs,unsigned host=0){rate=fs;owner->fs=float(fs);owner->trueSampleRate=float(host?host:fs);TimbreRefresh(e,owner.get());}
};
std::complex<double> amplitude(const std::vector<float>& v,unsigned rate,double hz,unsigned start,unsigned count,unsigned channel=0){std::complex<double> result{};
 for(unsigned i=0;i<count;++i)result+=double(v[2*(start+i)+channel])*std::polar(1.,-2*pi*hz*(start+i)/rate);return result*(2./count);}
double rms(const std::vector<float>& v,unsigned start=0){double e=0;for(size_t i=start*2;i<v.size();++i)e+=double(v[i])*v[i];return std::sqrt(e/(v.size()-start*2));}
std::complex<double> crossoverH2(unsigned factor){SixBandsCrossover filter{};const unsigned fs=48000*factor;init6BandsCrossover(&filter,fs,600,1900,4400,8000,12000);std::vector<float> v(96000*2);for(unsigned i=0;i<96000*factor;++i){double bands[6];process6BandsCrossover(&filter,float(.4*std::sin(2*pi*1000*i/fs)),bands,bands+1,bands+2,bands+3,bands+4,bands+5);double residual=0;for(unsigned b=1;b<5;++b)residual+=bands[b]*bands[b]*.2*1.1;if(i%factor==0)v[2*(i/factor)]=float(residual);}return amplitude(v,48000,2000,48000,48000);}
void validation(){assert(TimbreValidate(original.data(),32));for(const auto& f:factories)assert(TimbreValidate(f.data(),32));assert(!TimbreValidate(original.data(),31));
 for(unsigned i=0;i<32;++i){auto bad=original;bad[i]=INT32_MAX;assert(!TimbreValidate(bad.data(),32));}
 auto bad=original;bad[11]=bad[10]+49;assert(!TimbreValidate(bad.data(),32));bad=original;bad[15]=19;assert(!TimbreValidate(bad.data(),32));bad=original;bad[16]=999;assert(!TimbreValidate(bad.data(),32));bad=original;bad[26]=3;assert(!TimbreValidate(bad.data(),32));
 std::array<int32_t,68> t{};std::copy(original.begin(),original.end(),t.begin()+4);std::copy(original.begin(),original.end(),t.begin()+36);assert(TimbreToolsValidate(t.data(),68));t[0]=6;assert(!TimbreToolsValidate(t.data(),68));t[2]=1;assert(TimbreToolsValidate(t.data(),68));t[0]=11;t[3]=180;assert(TimbreToolsValidate(t.data(),68));t[3]=181;assert(!TimbreToolsValidate(t.data(),68));t[3]=-1;assert(!TimbreToolsValidate(t.data(),68));t[3]=0;t[0]=12;assert(!TimbreToolsValidate(t.data(),68));
 for(int amount:{0,1000,2000,2001,6000,10000}){auto extended=core();extended[4]=amount;assert(TimbreValidate(extended.data(),32));}auto invalid=core();invalid[4]=10001;assert(!TimbreValidate(invalid.data(),32));invalid=core();invalid[5]=2001;assert(!TimbreValidate(invalid.data(),32));
 std::puts("Timbre strict32/68 schema, all bounds, ordered five cuts, filter zero gaps and factor2/4 validation passed.");}
void compatibility(){unsigned cases=0;
 for(unsigned host:{8000u,22050u,44100u,48000u,96000u,192000u})for(bool pro:{false,true}){
  const unsigned fs=(host==22050||host==44100)?44100:48000;Core c(fs,host);auto s=original;s[1]=pro;c.set(s);VacuumTube reference{};VTInitUi33Reference(&reference,fs);
  const auto input=signal(fs,18000,733,.35);std::vector<float> golden(input.size());for(unsigned i=0;i<input.size()/2;++i){float l=input[2*i],r=input[2*i+1];VTProcessUi33Reference(&reference,&l,&r,&golden[2*i],&golden[2*i+1],1);}
  assert(c.render(input)==golden);++cases;
  s[1]=s[27]=1;c.set(s);TimbreSetLegacyDrive(c.e,c.owner.get(),12);TimbreEnable(c.e,c.owner.get(),1);s[2]=s[9]=s[19]=s[21]=s[23]=1;c.set(s);
  const auto more=signal(fs,12000,311,.2);std::vector<float> expected(more.size());for(unsigned i=0;i<more.size()/2;++i){float l=more[2*i],r=more[2*i+1];VTProcessUi33Reference(&reference,&l,&r,&expected[2*i],&expected[2*i+1],1);}assert(c.render(more)==expected);
  const auto v=c.status();assert(v[2]==int(fs)&&v[26]==int(host)&&v[15]==0&&v[13]==2&&v[14]==0);}
 std::printf("Timbre frozen ui33: %u internal44.1/48k and host8/22.05/44.1/48/96/192k cases bit-identical; neutral groups, saved legacy drive, meters and repeated enable preserve continuous histories.\n",cases);}
void driveBandsAndDc(){
 auto a=core();a[4]=1100;auto b=a;b[3]=60;Core ca,cb;ca.set(a);cb.set(b);auto in=signal(48000,96000,1000,.3);auto oa=ca.render(in),ob=cb.render(in);
 const double h2a=std::abs(amplitude(oa,48000,2000,48000,48000)),h2b=std::abs(amplitude(ob,48000,2000,48000,48000));std::printf("Measured drive H2 %.9g / %.9g ratio%.9g\n",h2a,h2b,h2b/h2a);assert(std::abs(h2b/h2a-std::pow(10.,.3))<.001);
 const auto before=cb.status();TimbreEnable(cb.e,cb.owner.get(),1);assert(cb.status()[15]==before[15]&&cb.status()[15]==60);cb.render(in);assert(cb.status()[15]==60);
 std::printf("Timbre actual6dB drive changes squared H2 by%.6fx with inverse compensation, repeated enable retains drive.\n",h2b/h2a);
 auto s=core();s[4]=1200;s[27]=1;const std::array<double,4> probes{1000,3000,6000,10000};
 for(unsigned band=0;band<4;++band){Core on,off;on.set(s);auto zero=s;zero[5+band]=0;off.set(zero);on.tools(7+band,s,s);off.tools(7+band,zero,zero);const auto src=signal(48000,48000,probes[band],.4),o=on.render(src),z=off.render(src);assert(rms(o,24000)>1e-4);assert(rms(z,24000)==0);}
 auto full=s;full[25]=1;full[26]=4;auto dry=full;dry[19]=1;dry[20]=0;Core effect,linear;effect.set(full);linear.set(dry);const auto src=signal(48000,96000,1000,.4),wet=effect.render(src),base=linear.render(src);std::array<std::vector<float>,4> solos;
 for(unsigned band=0;band<4;++band){Core c;c.set(full);assert(c.tools(7+band,full,full));solos[band]=c.render(src);}
 for(size_t i=48000*2;i<src.size();++i){double sum=base[i];for(const auto& v:solos)sum+=v[i];assert(std::abs(sum-wet[i])<2e-7);}
 Core dcOff,dcOn;auto noDc=s;noDc[9]=1;noDc[17]=0;auto withDc=noDc;withDc[17]=1;dcOff.set(noDc);dcOn.set(withDc);auto old=dcOff.render(src),cut=dcOn.render(src);double meanOld=0,meanCut=0;for(unsigned i=48000;i<96000;++i){meanOld+=old[2*i];meanCut+=cut[2*i];}meanOld/=48000;meanCut/=48000;assert(std::abs(meanOld)>1e-3&&std::abs(meanCut)<1e-6);
 std::printf("Timbre four band squares/Solo independently verified; wet=matched linear+4residuals; DC mean%.8g becomes%.8g with5Hz residual-only cut.\n",meanOld,meanCut);}
void toneMixQuality(){
 for(int factor:{2,4}){std::array<float,100> impulse{};TimbreFirImpulseForTest(factor,impulse.data(),impulse.size());assert(std::distance(impulse.begin(),std::max_element(impulse.begin(),impulse.end()))==31);for(unsigned i=0;i<31;++i)assert(std::abs(impulse[i]-impulse[62-i])<1e-7);}
 for(int factor:{0,2,4}){auto zeroMix=factories[0];zeroMix[3]=60;zeroMix[24]=500;zeroMix[20]=0;zeroMix[25]=factor!=0;zeroMix[26]=factor?factor:2;auto zeroAmount=zeroMix;zeroAmount[20]=1000;zeroAmount[4]=0;Core mixed,linear;mixed.set(zeroMix);linear.set(zeroAmount);auto broad=signal(48000,12000,733,.2);uint32_t random=13;for(size_t i=0;i<broad.size();++i){random=random*1664525+1013904223;broad[i]+=float(int32_t(random)/2147483648.*.03+.07*std::sin(2*pi*10331*(i/2)/48000));}assert(mixed.render(broad,31)==linear.render(broad,509));}
 std::puts("Timbre isolated2/4x FIR impulse is symmetric around actual31samples; auto/2/4 matched mix0 equals zero-color reference sample-for-sample on wideband input.");
 for(int mix:{0,450}){auto s=factories[0];s[20]=mix;Core normal,effect,raw,diff;for(auto* c:{&normal,&effect,&raw,&diff})c->set(s);assert(effect.tools(2,s,s)&&raw.tools(1,s,s)&&diff.tools(3,s,s));const auto in=signal(48000,8000,733,.3),n=normal.render(in),e=effect.render(in),r=raw.render(in),d=diff.render(in);for(unsigned i=1000*2;i<in.size();++i){assert(e[i]==n[i]);assert(std::abs(d[i]+r[i]-n[i])<1e-7);}}
 auto s=core();s[4]=1100;s[9]=1;s[17]=1;s[27]=1;Core reference;reference.set(s);const auto src=signal(48000,96000,1000,.4),base=reference.render(src);const double before=std::abs(amplitude(base,48000,2000,48000,48000));
 unsigned count=0;for(unsigned index:{10u,11u,12u,13u,14u,15u,16u,18u}){auto changed=s;if(index>=10&&index<=14)changed[index]+=300;else if(index==15)changed[index]=1000;else if(index==16)changed[index]=1000;else changed[index]=300;Core c;c.set(changed);const auto out=c.render(src);double diff=0;for(unsigned i=48000*2;i<out.size();++i)diff+=std::abs(out[i]-base[i]);assert(diff>1e-6);++count;}
 auto out=s;out[21]=1;out[22]=-60;Core gain;gain.set(out);const auto reduced=gain.render(src);assert(std::abs(rms(reduced,48000)/rms(base,48000)-std::pow(10.,-.3))<1e-6);
 auto autoDry=s;autoDry[19]=1;autoDry[20]=0;Core mixed0; mixed0.set(autoDry);const auto dry=mixed0.render(src);assert(std::abs(std::abs(amplitude(dry,48000,1000,48000,48000))-.4)<1e-5);
 std::array<std::complex<double>,2> h2{};std::array<double,2> alias{};unsigned k=0;
 for(unsigned factor:{2u,4u}){auto q=s;q[25]=1;q[26]=factor;Core c;c.set(q);const auto v=c.render(src);assert(c.status()[13]==int(factor)&&c.status()[14]==31&&c.status()[45]==1);h2[k]=amplitude(v,48000,2000,48000,48000);
  auto dryQ=q;dryQ[19]=1;dryQ[20]=0;Core z;z.set(dryQ);const auto zOut=z.render(src);std::printf("FIR%d matched reference magnitude %.9g\n",factor,std::abs(amplitude(zOut,48000,1000,48000,48000)));assert(std::abs(std::abs(amplitude(zOut,48000,1000,48000,48000))-.4)<1e-4);
  const auto ratio=amplitude(v,48000,1000,48000,48000)/amplitude(zOut,48000,1000,48000,48000);assert(std::abs(std::arg(ratio))<.0001);
  auto aq=q;aq[10]=50;aq[11]=100;aq[12]=200;aq[13]=18000;aq[14]=20000;aq[17]=0;Core aa;aa.set(aq);const auto ao=aa.render(signal(48000,96000,19000,.8));alias[k++]=std::abs(amplitude(ao,48000,10000,48000,48000));}
 double phaseError=0;for(unsigned i=0;i<2;++i){const unsigned f=i?4:2;const double w=2*pi*2000/(48000*f),k=std::tan(pi*5/(48000*f)),p=(1-k)/(1+k);const auto z=std::polar(1.,-w),hp=(1+p)*.5*(1.-z)/(1.-p*z);const auto compensated=h2[i]/crossoverH2(f)/hp*std::polar(1.,2*pi*2000*31/48000);phaseError=std::max(phaseError,std::abs(std::arg(compensated)));std::printf("FIR%u H2 vs independent same-rate crossover/DC reference delay31 phase error%.9g amplitude%.9g\n",f,std::abs(std::arg(compensated)),std::abs(compensated));assert(std::abs(std::arg(compensated))<.0001);assert(std::abs(std::abs(compensated)-1)<.001);}
 auto aq=s;aq[10]=50;aq[11]=100;aq[12]=200;aq[13]=18000;aq[14]=20000;aq[17]=0;Core originalAuto;originalAuto.set(aq);const auto ao=originalAuto.render(signal(48000,96000,19000,.8));const double autoAlias=std::abs(amplitude(ao,48000,10000,48000,48000));assert(alias[0]<autoAlias*.5&&alias[1]<autoAlias*.5);
 std::printf("Timbre all%u tone controls measured; output-6dB, matched mix0 retains magnitude/phase; FIR2/4 additional31samples independent H2 phase error%.7grad;19kHz square folded10k: IIRauto%.8g,FIR2%.8g,FIR4%.8g.\n",count,phaseError,autoAlias,alias[0],alias[1]);}
void endpointsAndTransitions(){unsigned cases=0;double peak=0;
 for(unsigned fs:{8000u,16000u,22050u,44100u,48000u,96000u,192000u})for(unsigned factor:{2u,4u})for(bool high:{false,true}){auto s=core();s[3]=high?120:-30;s[4]=high?2000:0;for(unsigned i=5;i<9;++i)s[i]=high?2000:0;s[9]=s[17]=s[19]=s[21]=s[23]=s[25]=s[27]=1;for(unsigned i=0;i<5;++i)s[10+i]=high?19800+i*50:50+i*50;s[15]=high?1000:0;s[16]=high?20000:1000;s[18]=high?300:10;s[20]=high?1000:0;s[22]=high?0:-240;s[24]=high?0:1000;s[26]=factor;Core c(fs);c.set(s);const auto out=c.render(signal(fs,12000,fs*.13,.95));for(float v:out)peak=std::max(peak,std::abs(double(v)));auto status=c.status();assert(status[25]&&status[36]<=int(fs*.45)+1&&status[32]<status[33]);++cases;}
 Core c;auto first=factories[0];c.set(first);const auto input=signal(48000,48000,700,.3);c.render(input);
 for(unsigned i=0;i<12;++i){c.set(factories[i%3]);c.render(signal(48000,19,700,.3));c.set(factories[(i+1)%3]);c.render(signal(48000,11,700,.3));c.set(factories[(i+2)%3]);TimbreClear(c.e);assert(TimbreQueuedForTest(c.e));c.render(input,31);assert(!TimbreQueuedForTest(c.e)&&c.status()[13]==factories[(i+2)%3][26]);}
 c.set(factories[1]);TimbreEnable(c.e,c.owner.get(),0);TimbreEnable(c.e,c.owner.get(),1);assert(c.status()[13]==4);c.refresh(44100,22050);assert(c.status()[13]==4&&c.status()[2]==44100&&c.status()[26]==22050&&c.status()[5]==0);
 Core left,right;left.set(first);right.set(first);assert(left.render(input)==right.render(input));TimbreAllocationFailureForTest(0);assert(!TimbreSet(left.e,left.owner.get(),factories[1].data(),32));assert(left.render(input)==right.render(input));TimbreAllocationFailureForTest(0);left.refresh(44100);assert(!left.status()[25]);left.set(first);assert(left.status()[25]);
 Core nonfinite;nonfinite.set(factories[1]);auto bad=signal(48000,2000);bad[0]=NAN;bad[1]=INFINITY;bad[20]=-INFINITY;nonfinite.render(bad);nonfinite.render(input);
 std::printf("Timbre%u full endpoint/Fs/factor cases finite, peak%.7g;12 rapid latest/pending prewarm/clear cycles, paused latest, actual-rate refresh, failure rollback and same-packet retry; zero callback new/delete.\n",cases,peak);}
void factoriesAndAb(){unsigned cases=0;double peak=0;std::array<std::vector<float>,3> variants;unsigned k=0;
 for(const auto& f:factories){Core c;c.set(f);variants[k++]=c.render(signal(48000,24000,3000,.5));}
 for(unsigned i=0;i<3;++i)for(unsigned j=i+1;j<3;++j){double diff=0;for(size_t n=10000;n<variants[i].size();++n)diff+=std::abs(variants[i][n]-variants[j][n]);assert(diff>.01);}
 for(unsigned fs:{8000u,44100u,48000u,96000u,192000u})for(const auto& f:factories){Core c(fs);c.set(f);const auto out=c.render(signal(fs,8192,fs*.13,.8));for(float v:out)peak=std::max(peak,std::abs(double(v)));++cases;}
 Core c;auto s=core();s[27]=1;c.set(s);const auto a=original,b=factories[1];assert(c.tools(6,a,b,true,17));c.render(signal(48000,10000));const auto before=c.status();assert(c.tools(6,a,b,true,17));assert(c.status()[29]==before[29]);assert(c.tools(6,a,b,false,17));assert(c.status()[29]==before[29]);c.render(signal(48000,130000));auto v=c.status();assert(v[7]==3&&v[8]==2000&&v[9]<=0&&v[10]<=0);const auto ga=v[9],gb=v[10];s[27]=0;c.set(s);assert(c.status()[7]==3&&c.status()[9]==ga&&c.status()[10]==gb&&c.status()[16]==INT32_MIN);s[27]=1;c.set(s);
 assert(c.tools(4,a,b,true,17));auto oa=c.render(signal(48000,48000));assert(c.tools(5,a,b,true,17));auto ob=c.render(signal(48000,48000));const double ra=rms(oa,24000),rb=rms(ob,24000);assert(std::abs(20*std::log10(ra/rb))<.01);
 std::array<int32_t,68> packet{4,1,18,0};std::copy(factories[0].begin(),factories[0].end(),packet.begin()+4);std::copy(factories[2].begin(),factories[2].end(),packet.begin()+36);TimbreAllocationFailureForTest(1);assert(!TimbreTools(c.e,c.owner.get(),packet.data(),68));assert(c.status()[11]==17&&c.status()[5]==5);
 TimbreClear(c.e);assert(c.status()[5]==0&&c.status()[7]==0&&c.status()[16]==INT32_MIN);Core silence;silence.set(s);assert(silence.tools(6,a,b,true));silence.warm(130000);assert(silence.status()[7]==4);
 std::printf("Timbre%u factory/Fs cases distinct/finite,peak%.7g; matched actual A/B RMS%.8g/%.8g (%.6gdB), legacy snapshot, same-epoch, meter-only preservation, insufficient-signal and allocation rollback/cleanup passed.\n",cases,peak,ra,rb,20*std::log10(ra/rb));}
void expandedAmountAndSolo(){
 double minRatio=100,maxRatio=0;unsigned cases=0;
 for(unsigned quality:{0u,2u,4u}){auto s=core();s[4]=2000;s[25]=quality!=0;s[26]=quality?quality:2;auto strong=s;strong[4]=10000;Core a,b;a.set(s);b.set(strong);const auto in=signal(48000,96000,1000,.3),old=a.render(in),expanded=b.render(in);
  const double ratio=std::abs(amplitude(expanded,48000,2000,48000,48000)/amplitude(old,48000,2000,48000,48000));assert(std::abs(ratio-5)<.001);minRatio=std::min(minRatio,ratio);maxRatio=std::max(maxRatio,ratio);
  // The new pre-mix sum equals the four original Solo lanes sample-for-sample,
  // including onset, both channels, non-unity output and a zero wet mix.
  auto audition=strong;audition[19]=audition[21]=1;audition[20]=0;audition[22]=-30;
  auto multi=signal(48000,12000,1000,.1);for(double hz:{3000.,6000.,10000.}){const auto part=signal(48000,12000,hz,.06);for(size_t i=0;i<multi.size();++i)multi[i]+=part[i];}
  std::array<std::vector<float>,4> lanes;for(unsigned band=0;band<4;++band){Core c;c.set(audition);assert(c.tools(7+band,audition,audition));lanes[band]=c.render(multi);}
  Core total;total.set(audition);assert(total.tools(11,audition,audition));const auto sum=total.render(multi);for(size_t i=0;i<sum.size();++i){double expected=0;for(const auto& lane:lanes)expected+=lane[i];assert(sum[i]==float(expected));}
  for(unsigned mode=7;mode<=11;++mode)for(int boost:{0,60,120,180}){Core plain,boosted;plain.set(audition);boosted.set(audition);assert(plain.tools(mode,audition,audition)&&boosted.tools(mode,audition,audition,false,1,boost));const auto reference=plain.render(multi),actual=boosted.render(multi);const double gain=std::pow(10.,boost/200.);
   for(size_t i=0;i<actual.size();++i)assert(std::abs(actual[i]-double(reference[i])*gain)<2e-7*std::max(1.,std::abs(double(reference[i])*gain)));assert(boosted.status()[46]==boost&&boosted.status()[47]==0);assert(std::abs(rms(actual,6000)/rms(reference,6000)-gain)<2e-6);++cases;}
 }
 // Stored audition gain is never applied to normal, raw, effect, difference,
 // A/B or measurement. Meter-only changes retain the comparison state.
 auto s=core();s[4]=6000;s[27]=1;const auto in=signal(48000,16000,1000,.2);
 for(int mode=0;mode<=6;++mode){Core a,b;a.set(s);b.set(s);assert(a.tools(mode,s,factories[1],true,15,0)&&b.tools(mode,s,factories[1],true,15,180));assert(a.render(in)==b.render(in));assert(b.status()[46]==0);}
 Core liveGain;liveGain.set(s);assert(liveGain.tools(11,s,s,false,21,60));liveGain.render(in);auto same=liveGain.status();assert(liveGain.tools(11,s,s,false,21,60));assert(liveGain.status()[1]==same[1]);assert(liveGain.tools(11,s,s,false,21,120));assert(liveGain.status()[46]==120&&liveGain.status()[11]==21);auto metersOff=s;metersOff[27]=0;liveGain.set(metersOff);assert(liveGain.status()[46]==120&&liveGain.status()[5]==11);assert(liveGain.tools(2,s,s,false,21,180));assert(liveGain.status()[46]==0);
 Core measured;measured.set(s);assert(measured.tools(6,s,factories[1],true,17,180));measured.render(signal(48000,130000,1000,.2));auto before=measured.status();assert(before[7]==3&&before[46]==0);s[27]=0;measured.set(s);assert(measured.status()[7]==3&&measured.status()[9]==before[9]&&measured.status()[10]==before[10]);s[27]=1;measured.set(s);assert(measured.tools(6,s,factories[1],true,17,180));assert(measured.status()[7]==3&&measured.status()[46]==0);
 // Explicit cleanup cannot promote the pending1000% candidate before warming.
 auto initial=core();initial[4]=2000;auto final=initial;final[4]=10000;Core changed,held;changed.set(initial);held.set(initial);auto prior=signal(48000,48000,1000,.2);changed.render(prior);held.render(prior);assert(changed.tools(11,initial,initial,false,1,180));auto running=signal(48000,1000,1000,.2);changed.render(running);held.render(running);changed.set(final);assert(TimbreQueuedForTest(changed.e));TimbreClear(changed.e);assert(TimbreQueuedForTest(changed.e)&&changed.status()[46]==0);
 auto first=signal(48000,12000,1000,.2),live=changed.render(first),reference=held.render(first);for(size_t i=600*2;i<live.size();++i)assert(live[i]==reference[i]);changed.render(prior);assert(!TimbreQueuedForTest(changed.e));
 for(int step=0;step<4;++step){assert(changed.tools(11,final,final,false,1,180));assert(changed.status()[46]==180);if(step==0)TimbreClear(changed.e);else if(step==1){TimbreEnable(changed.e,changed.owner.get(),0);TimbreEnable(changed.e,changed.owner.get(),1);}else if(step==2)changed.refresh(44100);else changed.refresh(44100,96000);assert(changed.status()[46]==0&&changed.status()[5]==0);}
 std::printf("Timbre amount200%%->1000%% measured H2 ratios%.9g..%.9g (expected5); total Solo equals4 original lanes every sample; %u mode/quality/0/6/12/18dB gains measured, modes0..6 neutral, lifecycle and pending-candidate cleanup preserved.\n",minRatio,maxRatio,cases);
}
void expandedEndpointsAndFactory(){unsigned cases=0,clippingCases=0;double maxPeak=0;
 for(unsigned fs:{8000u,16000u,22050u,32000u,44100u,48000u,96000u,192000u})for(unsigned quality:{0u,2u,4u})for(bool narrow:{false,true}){
  auto s=core();s[3]=120;s[4]=10000;for(unsigned i=5;i<9;++i)s[i]=2000;s[9]=s[17]=s[19]=s[21]=s[23]=s[27]=1;s[24]=0;s[20]=1000;s[22]=0;s[18]=300;s[25]=quality!=0;s[26]=quality?quality:2;
  if(narrow){for(unsigned i=0;i<5;++i)s[10+i]=19800+i*50;s[15]=1000;s[16]=20000;}
  Core c(fs);c.set(s);const auto out=c.render(signal(fs,10000,std::min(1000.,fs*.13),.95));double peak=0;for(float x:out)peak=std::max(peak,std::abs(double(x)));maxPeak=std::max(maxPeak,peak);const auto v=c.status();assert(v[25]&&v[46]==0&&v[47]==0);if(v[24]>0)++clippingCases;if(peak>1)assert(v[24]>0);++cases;
 }
 assert(clippingCases>0&&maxPeak<10000);
 constexpr S factory{1,1,1,60,6000,1000,1000,1000,1000,1,600,1900,4400,8000,12000,0,12000,1,50,1,850,1,-60,1,1000,1,4,0,0,0,0,0};assert(TimbreValidate(factory.data(),32));double factoryPeak=0;
 for(unsigned fs:{8000u,16000u,22050u,32000u,44100u,48000u,96000u,192000u}){Core c(fs);c.set(factory);auto out=c.render(signal(fs,12000,std::min(1000.,fs*.13),.95));for(float x:out)factoryPeak=std::max(factoryPeak,std::abs(double(x)));}
 assert(factoryPeak<1);Core newPreset,oldLimit,oldWarm;newPreset.set(factory);auto capped=factory;capped[4]=2000;oldLimit.set(capped);oldWarm.set(factories[1]);const auto in=signal(48000,96000,1000,.3),current=newPreset.render(in),old=oldLimit.render(in),warm=oldWarm.render(in);const double h2=std::abs(amplitude(current,48000,2000,48000,48000)),limited=std::abs(amplitude(old,48000,2000,48000,48000)),oldH2=std::abs(amplitude(warm,48000,2000,48000,48000));assert(std::abs(h2/limited-3)<.001&&h2>oldH2*2);
 std::printf("Timbre1000%% maximum parameter/Fs/auto/2/4 matrix: %u finite cases, %u report real clipped frames, peak%.8g; new600%% factory8..192kHz finite peak%.8g, H2%.8g vs old200%% limit%.8g (%.6fx), old warm preset%.8g (%.6fx).\n",cases,clippingCases,maxPeak,factoryPeak,h2,limited,h2/limited,oldH2,h2/oldH2);
}
struct Effect {
 effect_handle_t h{};effect_uuid_t uuid{0x30306067,0x9400,0x4d53,0x9000,{0,0,0,0,0,1}};
 Effect(){assert(AELI.create_effect(&uuid,0,0,&h)==0);rate(48000);assert(command(EFFECT_CMD_ENABLE)==0);}
 ~Effect(){AELI.release_effect(h);}
 int command(uint32_t id,uint32_t n=0,void* data=nullptr){int32_t result=0;uint32_t size=4;const auto rc=(*h)->command(h,id,n,data,&size,&result);return rc?rc:result;}
 int raw(uint32_t id,const void* data,unsigned n,unsigned extra=0){std::array<uint32_t,96> packet{};packet[1]=4;packet[2]=n;packet[3]=id;std::memcpy(packet.data()+4,data,n);return command(EFFECT_CMD_SET_PARAM,16+n+extra,packet.data());}
 void rate(unsigned fs){effect_config_t c{};c.inputCfg.samplingRate=c.outputCfg.samplingRate=fs;c.inputCfg.channels=c.outputCfg.channels=AUDIO_CHANNEL_OUT_STEREO;c.inputCfg.format=c.outputCfg.format=AUDIO_FORMAT_PCM_FLOAT;c.inputCfg.accessMode=EFFECT_BUFFER_ACCESS_READ;c.outputCfg.accessMode=EFFECT_BUFFER_ACCESS_WRITE;c.inputCfg.mask=c.outputCfg.mask=EFFECT_CONFIG_ALL;assert(command(EFFECT_CMD_SET_CONFIG,sizeof(c),&c)==0);}
 auto status(){std::array<uint32_t,4> q{0,4,192,131227};std::array<uint32_t,52> r{};uint32_t n=208;const int rc=(*h)->command(h,EFFECT_CMD_GET_PARAM,16,q.data(),&n,r.data());assert(rc==0||rc==-EAGAIN);std::array<int32_t,48> v{};if(!rc){assert(!r[0]&&n==208);std::memcpy(v.data(),r.data()+4,192);}return v;}
 void render(){std::array<float,1024> in{},out{};for(unsigned i=0;i<512;++i){in[2*i]=float(.4*std::sin(.07*i));in[2*i+1]=float(.3*std::sin(.09*i));}audio_buffer_t a{},b{};a.frameCount=b.frameCount=512;a.f32=in.data();b.f32=out.data();allocations=releases=0;watch=true;assert((*h)->process(h,&a,&b)==0);watch=false;assert(!allocations&&!releases);for(float v:out)assert(std::isfinite(v)&&std::abs(v)<=1);}
};
void protocol(){Effect e;auto s=factories[1];s[27]=1;assert(e.raw(1427,s.data(),128)==0);const int16_t enabled=1;assert(e.raw(1206,&enabled,2)==0);e.render();auto v=e.status();assert(v[0]==1&&!(v[1]&1)&&v[3]&&v[4]&&v[13]==4&&v[14]==31&&v[25]);
 assert(e.raw(1427,s.data(),124)!=0&&e.raw(1427,s.data(),128,4)!=0);auto bad=s;bad[28]=1;assert(e.raw(1427,bad.data(),128)!=0);bad=s;bad[11]=bad[10];assert(e.raw(1427,bad.data(),128)!=0);
 std::array<int32_t,68> tools{3,0,1,0};std::copy(s.begin(),s.end(),tools.begin()+4);std::copy(s.begin(),s.end(),tools.begin()+36);assert(e.raw(1428,tools.data(),272)==0);assert(e.raw(1428,tools.data(),268)!=0&&e.raw(1428,tools.data(),272,4)!=0);int32_t clear=1;assert(e.raw(1429,&clear,4)!=0);clear=0;assert(e.raw(1429,&clear,4)==0);
 auto expanded=s;expanded[4]=10000;assert(e.raw(1427,expanded.data(),128)==0);tools[0]=11;tools[3]=180;std::copy(expanded.begin(),expanded.end(),tools.begin()+4);std::copy(expanded.begin(),expanded.end(),tools.begin()+36);assert(e.raw(1428,tools.data(),272)==0);e.render();v=e.status();assert(v[5]==11&&v[46]==180&&v[47]==0);tools[3]=181;assert(e.raw(1428,tools.data(),272)!=0);tools[3]=180;tools[0]=12;assert(e.raw(1428,tools.data(),272)!=0);assert(e.raw(1429,&clear,4)==0);assert(e.status()[46]==0);
 tools[0]=11;assert(e.raw(1428,tools.data(),272)==0);assert(e.command(EFFECT_CMD_RESET)==0);assert(e.status()[5]==0&&e.status()[46]==0);
 for(unsigned fs:{8000u,22050u,44100u,48000u,96000u,192000u}){e.rate(fs);assert(e.raw(1427,s.data(),128)==0);e.render();v=e.status();const unsigned internal=fs>=44100?fs:(fs==22050?44100:48000);assert(v[2]==int(internal)&&v[26]==int(fs)&&v[5]==0&&v[25]&&v[36]<int(internal/2));}
 std::atomic<bool> done=false;std::thread audio([&]{while(!done)e.render();});for(unsigned i=0;i<40;++i){auto next=factories[i%3];next[27]=1;assert(e.raw(1427,next.data(),128)==0);v=e.status();if(v[0])assert(!(v[1]&1)&&v[14]<=128&&v[13]>=1&&v[13]<=5&&v[46]==0&&v[47]==0);}done=true;audio.join();assert(e.command(EFFECT_CMD_DISABLE)==0);v=e.status();assert(v[5]==0&&v[16]==INT32_MIN);
 std::puts("Timbre Fusion strict packet lengths/reserved/cut ordering, amount1000% and totalSolo/18dB accepted with actual runtime46, invalid modes/gain rejected, coherent concurrent runtime, host8..192k to actualJames44.1/48k and disable tools cleanup passed.");}
}
int main(){std::setvbuf(stdout,nullptr,_IONBF,0);validation();compatibility();driveBandsAndDc();toneMixQuality();endpointsAndTransitions();factoriesAndAb();expandedAmountAndSolo();expandedEndpointsAndFactory();protocol();std::puts("Timbre native checks passed.");}
