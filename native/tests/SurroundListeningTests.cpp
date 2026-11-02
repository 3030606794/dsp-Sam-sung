#include "../surround/SurroundProcessor.h"
#include "essential.h"
#include <algorithm>
#include <array>
#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>
#include <thread>
#include <cstdlib>
#include <new>
extern "C" audio_effect_library_t AELI;
using dspfusion::SurroundListeningTools;
static std::atomic<bool> watch=false;
static std::atomic<unsigned> allocations=0;
void* operator new(std::size_t n){if(watch.load())++allocations;if(void* p=std::malloc(n?n:1))return p;throw std::bad_alloc();}
void* operator new[](std::size_t n){return ::operator new(n);}
void operator delete(void* p)noexcept{std::free(p);}
void operator delete[](void* p)noexcept{std::free(p);}
void operator delete(void* p,std::size_t)noexcept{std::free(p);}
void operator delete[](void* p,std::size_t)noexcept{std::free(p);}
using Packet=std::array<int32_t,13>;
static Packet packet(){return {1,0,60,500,1,0,-300,1000,200,8000,1000,20,0};}
static std::array<int32_t,24> read(SurroundListeningTools& t){t.publish();std::array<int32_t,24> v{};assert(t.snapshot(v.data()));assert(v[0]==1 && !(v[1]&1));return v;}
static void validation(){SurroundListeningTools t;auto p=packet();assert(t.setParameters(p.data(),13));assert(!t.setParameters(nullptr,13));assert(!t.setParameters(p.data(),12));
    constexpr Packet lo{1,0,0,500,1,0,-600,20,20,21,1000,20,0},hi{1,1,120,10000,INT32_MAX,4,-120,16000,16000,20000,30000,500,INT32_MAX};
    for(int i=0;i<13;++i){auto b=p;if(lo[i]>INT32_MIN){b[i]=lo[i]-1;assert(!t.setParameters(b.data(),13));}if(hi[i]<INT32_MAX){b=p;b[i]=hi[i]+1;assert(!t.setParameters(b.data(),13));}}
    p[8]=p[9];assert(!t.setParameters(p.data(),13));p=packet();p[5]=1;assert(!t.setParameters(p.data(),13));puts("Listening contract rejects malformed lengths, ranges, unordered bands and untriggered generators");}
static void matching(){SurroundListeningTools t;auto p=packet();p[1]=1;assert(t.setParameters(p.data(),13));
    auto measure=[&](int audition,double ratio){t.begin(8000,true,audition);for(int n=0;n<12000;++n){const double x=.05*std::sin(n*.3);t.match(x,x,x*ratio,x*ratio,true);}return read(t);};
    auto a=measure(3,1.5);assert(a[6]==1 && a[7]==0 && a[12]==0);auto b=measure(4,1);assert(b[6] && b[7]);assert(std::abs(b[10]+3522)<=1 && b[11]==0 && b[20]==0);
    a=measure(3,4);assert(std::abs(a[12]+3522)<=1 && std::abs(a[8]-3522)<=1); // fixed, no chasing new material
    p[2]=10;assert(t.setParameters(p.data(),13));a=measure(3,2);b=measure(4,1);a=measure(3,3);assert(a[10]==-1000 && a[20]==1);
    p[4]++;assert(t.setParameters(p.data(),13));t.begin(8000,true,3);for(int n=0;n<15000;++n)t.match(0,0,0,0,true);a=read(t);assert(!a[6] && !a[7] && a[21]);
    t.cancel(true);assert(!read(t)[6] && !read(t)[15]);puts("A/B energy ratios, fixed attenuation-only gains, maximum limit, quiet input and invalidation passed");}
static std::vector<double> signal(int fs,int mode,int chunk){SurroundListeningTools t;auto p=packet();p[5]=mode;p[12]=7;assert(t.setParameters(p.data(),13));std::vector<double> result(fs*2);
    for(int start=0;start<fs;start+=chunk){t.begin(fs,true,0);for(int n=start;n<std::min(fs,start+chunk);++n){double l=0,r=0;t.inject(l,r);result[2*n]=l;result[2*n+1]=r;assert(std::abs(l)<=std::pow(10.,p[6]/200.)+1e-12 && std::abs(r)<=std::pow(10.,p[6]/200.)+1e-12);}}
    assert(read(t)[15]==0);assert(t.setParameters(p.data(),13));t.begin(fs,true,0);double l=0,r=0;t.inject(l,r);assert(l==0 && r==0);return result;}
static void generators(){for(int fs:{8000,44100,48000,96000,192000})for(int mode=1;mode<=4;++mode){auto a=signal(fs,mode,1),b=signal(fs,mode,257);assert(a==b);double energy=0;for(int i=0;i<fs;++i){const auto l=a[2*i],r=a[2*i+1];energy+=l*l+r*r;if(mode==1)assert(r==0);if(mode==2)assert(l==0);if(mode>=3)assert(l==r);}assert(energy>.001);}
    SurroundListeningTools t;auto p=packet();p[5]=3;p[12]=8;assert(t.setParameters(p.data(),13));t.begin(48000,true,0);double l=0,r=0;t.inject(l,r);t.cancel(true);assert(t.setParameters(p.data(),13));t.begin(48000,true,0);assert(!read(t)[15]);p[12]++;assert(t.setParameters(p.data(),13));t.begin(48000,true,0);assert(read(t)[15]==3);t.cancel();t.begin(48000,true,0);for(int i=0;i<2000;++i){l=r=0;t.inject(l,r);}assert(!read(t)[15]);
    puts("20 generator/rate combinations: exact channel routing, bounded source level, 1/257 chunk identity, timeout, fade cancellation and stale-trigger rejection passed");}
static void callbackAndSnapshots(){
    dspfusion::SurroundProcessor s;s.configure(44100);std::array<int32_t,27> pro{1,1,200,9000,5500,35};std::copy(dspfusion::kSurroundProfessionalDefaults.begin(),dspfusion::kSurroundProfessionalDefaults.end(),pro.begin()+6);pro[7]=1;pro[18]=pro[23]=1;assert(s.setProfessionalParameters(pro.data(),27));auto p=packet();p[1]=1;p[5]=4;p[12]=17;assert(s.setListeningTools(p.data(),13));assert(s.setAudition(3));std::array<float,514> audio{};
    allocations=0;watch=true;for(int b=0;b<500;++b)s.process(audio.data(),257);watch=false;assert(allocations==0);
    SurroundListeningTools t;assert(t.setParameters(p.data(),13));t.begin(44100,true,3);t.publish();std::atomic<bool> done=false;std::thread writer([&]{for(int i=0;i<2000;++i){auto q=p;q[4]=1+i%2;assert(t.setParameters(q.data(),13));}done=true;});
    std::thread reader([&]{for(int i=0;i<2000 || !done;++i){std::array<int32_t,24> v{};if(t.snapshot(v.data()))assert(v[0]==1 && !(v[1]&1) && (v[19]==1 || v[19]==2) && v[17]<v[18]);}});
    for(int b=0;b<2000;++b){t.begin(44100,true,3);for(int n=0;n<16;++n){double l=0,r=0;t.inject(l,r);t.match(l,r,l*.5,r*.5,true);}t.publish();}writer.join();reader.join();puts("Listening/calibration callback allocations: 0; concurrent controls and coherent atomic snapshots passed");
}
static int cmd(effect_handle_t h,uint32_t code,uint32_t size=0,void* data=nullptr){int reply=0;uint32_t n=4;int rc=(*h)->command(h,code,size,data,&n,&reply);return rc?rc:reply;}
static int set(effect_handle_t h,uint32_t id,const void* value,size_t bytes){std::vector<uint32_t> p((16+bytes+3)/4);p[1]=4;p[2]=bytes;p[3]=id;std::memcpy(p.data()+4,value,bytes);return cmd(h,EFFECT_CMD_SET_PARAM,16+bytes,p.data());}
static std::array<int32_t,24> runtime(effect_handle_t h){std::array<uint32_t,4> req{0,4,96,131218};std::array<uint32_t,28> out{};uint32_t bytes=112;assert(!(*h)->command(h,EFFECT_CMD_GET_PARAM,16,req.data(),&bytes,out.data()) && bytes==112);std::array<int32_t,24> v{};std::memcpy(v.data(),out.data()+4,96);return v;}
static void bridge(){constexpr effect_uuid_t uuid{0x30306067,0x9400,0x4d53,0x9000,{0,0,0,0,0,1}};effect_handle_t h=nullptr;assert(!AELI.create_effect(&uuid,0,0,&h));effect_config_t c{};c.inputCfg.samplingRate=c.outputCfg.samplingRate=48000;c.inputCfg.channels=c.outputCfg.channels=AUDIO_CHANNEL_OUT_STEREO;c.inputCfg.format=c.outputCfg.format=AUDIO_FORMAT_PCM_FLOAT;c.inputCfg.accessMode=EFFECT_BUFFER_ACCESS_READ;c.outputCfg.accessMode=EFFECT_BUFFER_ACCESS_WRITE;c.inputCfg.mask=c.outputCfg.mask=EFFECT_CONFIG_ALL;assert(!cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(c),&c));int off=0;assert(!set(h,131072,&off,4) && !set(h,131073,&off,4));assert(!cmd(h,EFFECT_CMD_ENABLE));std::array<int32_t,6> legacy{1,1,200,9000,5500,35};assert(!set(h,196609,legacy.data(),24));auto p=packet();p[5]=1;p[12]=11;assert(set(h,196612,p.data(),48)==-EINVAL);assert(!set(h,196612,p.data(),52));std::array<float,1024> in{},out{};
    auto process=[&]{audio_buffer_t a{},b{};a.frameCount=b.frameCount=512;a.f32=in.data();b.f32=out.data();assert(!(*h)->process(h,&a,&b));};for(int i=0;i<5;++i)process();assert(runtime(h)[15]==1);assert(!cmd(h,EFFECT_CMD_DISABLE));assert(!runtime(h)[15]);assert(!set(h,196612,p.data(),52));assert(!cmd(h,EFFECT_CMD_ENABLE));process();assert(!runtime(h)[15]);p[12]++;assert(!set(h,196612,p.data(),52));process();assert(runtime(h)[15]==1);assert(!cmd(h,EFFECT_CMD_RESET));assert(!runtime(h)[15]);assert(!AELI.release_effect(h));puts("Fusion listening bridge: strict 52-byte packet and 96-byte snapshot; disable/reset stop calibration without replaying stale triggers");}
int main(){validation();matching();generators();callbackAndSnapshots();bridge();puts("Speaker crosstalk listening tools passed");}
