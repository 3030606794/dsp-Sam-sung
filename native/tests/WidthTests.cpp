#include "essential.h"
#include "../../app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/Effects/stereoWidthControl.h"
#include <array>
#include <algorithm>
#include <cassert>
#include <cmath>
#include <complex>
#include <cstdio>
#include <cstring>
#include <random>
#include <vector>
#include <atomic>
#include <thread>
#ifdef _WIN32
#include <windows.h>
#endif
extern "C" audio_effect_library_t AELI;
namespace {
using Packet=std::array<int32_t,26>;
constexpr Packet defaults{1,0,1000,1000,1000,1000,180,3000,1,0,120,1000,1,0,1000,0,0,400,30,0,0,5,20,400,1000,300};
constexpr double pi=3.14159265358979323846;
constexpr effect_uuid_t uuid{0x30306067,0x9400,0x4d53,0x9000,{0,0,0,0,0,1}};
StereoWidthControl state(int fs,Packet p){StereoWidthControl s;StereoWidthInit(&s,fs);assert(StereoWidthSet(&s,p.data(),p.size()));s.enabled=1;StereoWidthConfigure(&s,fs);return s;}
void core() {
    const int low[]={1,0,0,0,0,0,80,1000,0,0,30,0,0,-120,0,-120,0,100,0,0,-20,0,5,50,0,100};
    const int high[]={1,2,2000,2000,2000,2000,500,8000,1,1,400,1000,2,60,1000,60,1,2000,60,1,50,20,200,2000,1000,1000};
    for(unsigned i=0;i<26;i++)for(int v:{low[i]-1,high[i]+1}){auto p=defaults;p[i]=v;assert(!StereoWidthValidate(p.data(),p.size()));}
    assert(!StereoWidthValidate(defaults.data(),25));auto bad=defaults;bad[17]=401;assert(!StereoWidthValidate(bad.data(),26));
    std::mt19937 rng(190);std::uniform_real_distribution<float> random(-.1f,.1f);
    for(int fs:{8000,44100,48000,96000,192000}) {
        auto s=state(fs,defaults);
        for(int i=0;i<20000;i++){float l=random(rng),r=random(rng),y=l,z=r;StereoWidthFrame(&s,l,r,&y,&z);assert(std::memcmp(&y,&l,4)==0 && std::memcmp(&z,&r,4)==0);}
        for(int w:{0,1000,2000}) {
            auto p=defaults;p[1]=1;p[2]=w;s=state(fs,p);
            for(int i=0;i<5000;i++){float l=random(rng),r=random(rng),y=l,z=r;StereoWidthFrame(&s,l,r,&y,&z);double m=(double(l)+r)*.5,side=(double(l)-r)*.5*w/1000.;assert(std::abs(y-(m+side))<2e-8 && std::abs(z-(m-side))<2e-8);}
            for(int i=0;i<5000;i++){float l=random(rng),y=l,z=l;StereoWidthFrame(&s,l,l,&y,&z);assert(y==l && z==l);}
        }
    }
    std::puts("Width core: strict packet, exact legacy neutrality, fullband matrix and mono input at five rates passed");
}
using C=std::complex<double>;
std::pair<C,C> theoretical(double f,double fc,int fs,int slope) {
    C u(0,std::tan(pi*f/fs)/std::tan(pi*fc/fs));
    if(!slope){C d=(1.+u)*(1.+u);return {1./d,-u*u/d};}
    C d=1.+std::sqrt(2.)*u+u*u;d*=d;return {1./d,u*u*u*u/d};
}
void responses() {
    unsigned scenarios=0;
    for(int fs:{8000,44100,48000,96000,192000})for(int slope:{0,1})for(double f:{50.,180.,800.,3000.}) {
        auto p=defaults;p[1]=2;p[8]=slope;p[3]=300;p[4]=1100;p[5]=1800;
        auto s=state(fs,p);auto a=theoretical(f,180,fs,slope),b=theoretical(f,std::min(3000.,fs*.45),fs,slope);
        C expected=.3*a.first*(b.first+b.second)+1.1*a.second*b.first+1.8*a.second*b.second;
        C expectedMid=(a.first+a.second)*(b.first+b.second),measured=0,measuredMid=0;
        int n=fs*2,start=fs;
        for(int i=0;i<n;i++){double phase=2*pi*f*i/fs;float m=.05*std::cos(phase),side=.03*std::cos(phase),y=m+side,z=m-side;StereoWidthFrame(&s,y,z,&y,&z);
            if(i>=start){C demod=std::exp(C(0,-phase));measured+=double((y-z)*.5f)*demod;measuredMid+=double((y+z)*.5f)*demod;}}
        measured*=2./((n-start)*.03);measuredMid*=2./((n-start)*.05);
        assert(std::abs(measured-expected)<3e-5 && std::abs(measuredMid-expectedMid)<3e-5);scenarios++;
    }
    for(int fs:{8000,44100,48000,96000,192000})for(int slope:{0,1,2})for(double f:{30.,120.,500.,2000.}) {
        auto p=defaults;p[1]=1;p[9]=1;p[12]=slope;auto s=state(fs,p);double energy=0;
        for(int i=0;i<fs*2;i++){float side=.05*std::sin(2*pi*f*i/fs),y=side,z=-side;StereoWidthFrame(&s,side,-side,&y,&z);if(i>=fs)energy+=y*y;assert(std::abs(y+z)<1e-7);}
        double ratio=std::tan(pi*120/fs)/std::tan(pi*f/fs),expected=1/std::sqrt(1+std::pow(ratio,2*(2<<slope)));
        assert(std::abs(std::sqrt(energy*2/fs)/.05-expected)<3e-5);scenarios++;
    }
    std::printf("Width responses: %u independent LR complex-response and Butterworth magnitude scenarios passed\n",scenarios);
}
void dynamics() {
    for(int midGain:{-120,0,60})for(int mix:{0,500,1000}) {
        auto q=defaults;q[1]=1;q[2]=1500;q[13]=midGain;q[14]=mix;q[15]=-30;auto trim=state(48000,q);
        for(int i=0;i<100;i++){float y=.08f,z=.02f;StereoWidthFrame(&trim,.08,.02,&y,&z);double m=.05*(1+mix/1000.*(std::pow(10.,midGain/200.)-1)),side=.03*(1+mix/1000.*.5),gain=std::pow(10.,-.15);assert(std::abs(y-(m+side)*gain)<2e-8 && std::abs(z-(m-side)*gain)<2e-8);}
    }
    auto p=defaults;p[1]=1;p[2]=2000;p[19]=1;p[20]=10;auto s=state(48000,p);
    for(int i=0;i<48000*3;i++){double t=i/48000.;float m=.05*std::sin(2*pi*500*t),side=.05*std::cos(2*pi*2000*t),y=m+side,z=m-side;StereoWidthFrame(&s,y,z,&y,&z);}
    int32_t v[32];StereoWidthSnapshot(&s,v);assert(v[19]>=999 && v[6]>-200 && v[6]<200 && v[15]==1000);
    p[19]=0;p[16]=1;p[18]=60;s=state(48000,p);
    for(int i=0;i<48000*5;i++){double t=i/48000.;float m=.05*std::sin(2*pi*500*t),side=.05*std::cos(2*pi*2000*t),y=m+side,z=m-side;StereoWidthFrame(&s,y,z,&y,&z);}
    StereoWidthSnapshot(&s,v);assert(std::abs(v[14]-v[31])<=1 && std::abs(v[20]+40)<=1);
    p[18]=10;s=state(48000,p);
    for(int i=0;i<48000*3;i++){float x=.05*std::sin(2*pi*1000*i/48000.),y=x,z=-x;StereoWidthFrame(&s,x,-x,&y,&z);}
    StereoWidthSnapshot(&s,v);assert(v[20]==-10);
    StereoWidthClear(&s);for(int i=0;i<48000;i++){float y=0,z=0;StereoWidthFrame(&s,0,0,&y,&z);}StereoWidthSnapshot(&s,v);assert(v[5]==INT32_MIN && v[6]==INT32_MIN);
    p=defaults;p[1]=2;s=state(48000,p);double last=0,maxJump=0;
    for(int i=0;i<48000;i++){if(i%200==0){p[6]=80+(i/200)%420;p[8]=(i/200)%2;assert(StereoWidthSet(&s,p.data(),26));}float y=.05f,z=-.05f;StereoWidthFrame(&s,.05,-.05,&y,&z);assert(std::isfinite(y)&&std::isfinite(z));if(i>2000)maxJump=std::max(maxJump,std::abs(y-last));last=y;}
    assert(maxJump<.005);
    std::puts("Width dynamics: independent mid/mix/output formula, guard withdrawal, bounded RMS compensation, silence and rapid filter edits passed");
}
int command(effect_handle_t h,uint32_t code,uint32_t n=0,void* data=nullptr){int32_t reply=-999;uint32_t bytes=4;int r=(*h)->command(h,code,n,data,&bytes,&reply);return r?r:reply;}
int set(effect_handle_t h,uint32_t id,const void* data,size_t n){std::vector<uint32_t> packet((16+n+3)/4);packet[1]=4;packet[2]=n;packet[3]=id;std::memcpy(packet.data()+4,data,n);return command(h,EFFECT_CMD_SET_PARAM,16+n,packet.data());}
void configure(effect_handle_t h,int fs) {effect_config_t c{};c.inputCfg.samplingRate=c.outputCfg.samplingRate=fs;c.inputCfg.channels=c.outputCfg.channels=AUDIO_CHANNEL_OUT_STEREO;c.inputCfg.format=c.outputCfg.format=AUDIO_FORMAT_PCM_FLOAT;c.inputCfg.accessMode=EFFECT_BUFFER_ACCESS_READ;c.outputCfg.accessMode=EFFECT_BUFFER_ACCESS_WRITE;c.inputCfg.mask=c.outputCfg.mask=EFFECT_CONFIG_ALL;assert(command(h,EFFECT_CMD_SET_CONFIG,sizeof(c),&c)==0);int32_t off=0;assert(set(h,131073,&off,4)==0);int16_t on=1,w=60;assert(set(h,137,&w,2)==0 && set(h,1204,&on,2)==0 && command(h,EFFECT_CMD_ENABLE)==0);}
std::array<int32_t,32> snapshot(effect_handle_t h) {std::array<uint32_t,4> req{0,4,128,131215};std::array<uint32_t,36> reply{};
    for(unsigned attempt=0;attempt<1000;++attempt){uint32_t n=sizeof(reply);int rc=(*h)->command(h,EFFECT_CMD_GET_PARAM,16,req.data(),&n,reply.data());if(rc==-EAGAIN){std::this_thread::yield();continue;}assert(rc==0 && n==144);std::array<int32_t,32> v{};std::memcpy(v.data(),reply.data()+4,128);assert(v[0]==1 && !(v[1]&1));return v;}assert(false);return {};}
void process(effect_handle_t h,float* in,float* out,size_t n){audio_buffer_t a{},b{};a.frameCount=b.frameCount=n;a.f32=in;b.f32=out;assert((*h)->process(h,&a,&b)==0);}
void integration() {
    effect_handle_t h=nullptr;assert(!AELI.create_effect(&uuid,0,0,&h));configure(h,44100);auto p=defaults;p[1]=1;p[2]=2000;assert(set(h,1410,p.data(),104)==0);assert(set(h,1410,p.data(),100)==-EINVAL);
    std::array<float,1024> input{},output{};for(unsigned i=0;i<512;i++)input[2*i]=input[2*i+1]=.05f*std::sin(2*pi*500*i/44100);
    for(int i=0;i<100;i++)process(h,input.data(),output.data(),512);
    auto v=snapshot(h);std::array<uint32_t,4> rateRequest{0,4,4,20001};std::array<uint32_t,5> rateReply{};uint32_t rateBytes=20;
    assert(!(*h)->command(h,EFFECT_CMD_GET_PARAM,16,rateRequest.data(),&rateBytes,rateReply.data()) && rateBytes==20);
    assert(v[2]==static_cast<int32_t>(rateReply[4]) && v[3]==1 && v[25]==1 && v[5]>9990 && v[6]>9990 && v[15]==2000);
    std::array<int32_t,60> advanced{};std::copy(width2Defaults,width2Defaults+60,advanced.begin());advanced[59]=1;
    assert(set(h,1412,advanced.data(),240)==0);assert(set(h,1412,advanced.data(),236)==-EINVAL);
    advanced[38]=501;assert(set(h,1412,advanced.data(),240)==-EINVAL);advanced[38]=100;
    auto advancedSnapshot=[&] {
        std::array<uint32_t,4> request{0,4,704,131216};std::array<uint32_t,180> reply{};
        for(int attempt=0;attempt<1000;attempt++){uint32_t bytes=sizeof(reply);int rc=(*h)->command(h,EFFECT_CMD_GET_PARAM,16,request.data(),&bytes,reply.data());if(rc==-EAGAIN)continue;assert(rc==0 && bytes==720);assert(reply[4]==1 && !(reply[5]&1));return reply;}
        assert(false);return reply;
    };
    for(int i=0;i<40;i++)process(h,input.data(),output.data(),512);
    auto av=advancedSnapshot();assert(av[4+5]==1 && av[4+45]==1);
    int32_t advancedListen=7;assert(set(h,1413,&advancedListen,4)==0);advancedListen=3;assert(set(h,1413,&advancedListen,4)==-EINVAL);
    for(int i=0;i<100;i++)process(h,input.data(),output.data(),512);av=advancedSnapshot();assert(av[4+44]==7);
    for(float x:output)assert(std::abs(x)<1e-6f);advancedListen=0;assert(!set(h,1413,&advancedListen,4));
    std::atomic<bool> done=false;std::thread poll([&]{while(!done.load()){snapshot(h);advancedSnapshot();}});
    for(int i=0;i<100;i++)process(h,input.data(),output.data(),512);done=true;poll.join();
    int32_t audition=3;assert(set(h,1411,&audition,4)==0);for(int i=0;i<100;i++)process(h,input.data(),output.data(),512);v=snapshot(h);assert(v[24]==3 && v[6]>9990);double peak=0;for(float x:output)peak=std::max(peak,std::abs(double(x)));assert(peak<1e-6);
    assert(!command(h,EFFECT_CMD_DISABLE));v=snapshot(h);assert(v[24]==0 && v[25]==0 && v[6]==INT32_MIN);
    for(int fs:{8000,44100,48000,96000,192000}) {
        configure(h,fs);rateBytes=20;assert(!(*h)->command(h,EFFECT_CMD_GET_PARAM,16,rateRequest.data(),&rateBytes,rateReply.data()));
        v=snapshot(h);assert(v[2]==static_cast<int32_t>(rateReply[4]) && v[24]==0 && v[25]==0);
        for(int i=0;i<10;i++)process(h,input.data(),output.data(),512);v=snapshot(h);assert(v[2]==static_cast<int32_t>(rateReply[4]));
    }
    assert(!AELI.release_effect(h));
    std::puts("Width Fusion: actual James-rate snapshots, concurrent coherent polling, audition measurement isolation and disable cleanup passed");
}
#ifdef _WIN32
void frozen(const char* path) {
    auto module=LoadLibraryA(path);assert(module);auto* old=reinterpret_cast<audio_effect_library_t*>(GetProcAddress(module,"AELI"));assert(old);unsigned cases=0;
    for(int fs:{8000,44100,48000,96000,192000})for(int width:{30,50,60,75}) {
        effect_handle_t a=nullptr,b=nullptr;assert(!old->create_effect(&uuid,0,0,&a) && !AELI.create_effect(&uuid,0,0,&b));configure(a,fs);configure(b,fs);int16_t w=width;assert(!set(a,137,&w,2) && !set(b,137,&w,2));
        std::mt19937 rng(530+fs+width);std::uniform_real_distribution<float> random(-.05f,.05f);std::array<float,1024> in{},x{},y{};
        for(int block=0;block<50;block++){for(float& v:in)v=random(rng);process(a,in.data(),x.data(),512);process(b,in.data(),y.data(),512);assert(std::memcmp(x.data(),y.data(),sizeof(x))==0);}
        assert(!old->release_effect(a) && !AELI.release_effect(b));cases++;
    }
    FreeLibrary(module);std::printf("Width frozen ui22: %u actual old-algorithm cases bit-identical passed\n",cases);
}
#endif
}
int main(int argc,char** argv){core();responses();dynamics();integration();
#ifdef _WIN32
    if(argc>1)frozen(argv[1]);
#endif
    std::puts("Stereo width: all checks passed");return 0;}
