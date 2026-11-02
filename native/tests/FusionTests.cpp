#include <array>
#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <limits>
#include <vector>
#include <thread>
#include <atomic>
#include "../viper/src/viper/utils/DiffAdvanced.h"
#include "essential.h"
extern "C" audio_effect_library_t AELI;
constexpr effect_uuid_t uuid{0x30306067,0x9400,0x4d53,0x9000,{0,0,0,0,0,1}};
void runStreamingTests();

int cmd(effect_handle_t h,uint32_t code,uint32_t size=0,void* data=nullptr) {
    int reply=-999; uint32_t replySize=4;
    int rc=(*h)->command(h,code,size,data,&replySize,&reply);
    return rc?rc:reply;
}
int setRaw(effect_handle_t h,uint32_t id,const void* data,size_t size) {
    std::vector<uint32_t> packet((sizeof(effect_param_t)+4+size+3)/4);
    auto* p=reinterpret_cast<effect_param_t*>(packet.data());
    p->status=0;p->psize=4;p->vsize=size;
    std::memcpy(p->data,&id,4); std::memcpy(p->data+4,data,size);
    return cmd(h,EFFECT_CMD_SET_PARAM,sizeof(effect_param_t)+4+size,p);
}
int set(effect_handle_t h,uint32_t id,int value) { return setRaw(h,id,&value,4); }
int get(effect_handle_t h,uint32_t id) {
    std::array<uint32_t,5> packet{0,4,4,id,0},reply{}; uint32_t size=20;
    assert((*h)->command(h,EFFECT_CMD_GET_PARAM,16,packet.data(),&size,reply.data())==0);
    assert(size==20 && reply[0]==0 && reply[1]==4 && reply[2]==4 && reply[3]==id);
    return reply[4];
}
effect_config_t config(uint32_t rate,uint8_t format=AUDIO_FORMAT_PCM_FLOAT,bool accumulate=false) {
    effect_config_t c{};
    c.inputCfg.samplingRate=c.outputCfg.samplingRate=rate;
    c.inputCfg.channels=c.outputCfg.channels=AUDIO_CHANNEL_OUT_STEREO;
    c.inputCfg.format=c.outputCfg.format=format;
    c.inputCfg.accessMode=EFFECT_BUFFER_ACCESS_READ;
    c.outputCfg.accessMode=accumulate?EFFECT_BUFFER_ACCESS_ACCUMULATE:EFFECT_BUFFER_ACCESS_WRITE;
    c.inputCfg.mask=c.outputCfg.mask=EFFECT_CONFIG_ALL;
    return c;
}
int process(effect_handle_t h,void* in,void* out,size_t frames) {
    audio_buffer_t a{},b{};a.frameCount=b.frameCount=frames;a.raw=in;b.raw=out;
    return (*h)->process(h,&a,&b);
}
void bassBridgeTests() {
    effect_handle_t h=nullptr;assert(AELI.create_effect(&uuid,0,0,&h)==0);
    assert(get(h,131081)==2147483647 && get(h,131080)==5);
    assert(get(h,131084)==0 && get(h,131085)==0);
    auto setup=config(48000);assert(cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(setup),&setup)==0);
    const std::array<int32_t,7> defaults{50,0,250,200,48,192,192};
    assert(setRaw(h,1300,defaults.data(),sizeof(defaults))==0);
    assert(setRaw(h,1300,defaults.data(),sizeof(defaults)-4)==-EINVAL);
    const int32_t low[7]={0,0,1,50,5,5,5},high[7]={150,249,250,400,1000,2000,5000};
    for(size_t i=0;i<7;++i) for(int32_t invalid:{low[i]-1,high[i]+1}) {
        auto values=defaults;values[i]=invalid;
        assert(setRaw(h,1300,values.data(),sizeof(values))==-EINVAL);
    }
    auto invalid=defaults;invalid[1]=invalid[2];assert(setRaw(h,1300,invalid.data(),sizeof(invalid))==-EINVAL);
    const int32_t vl[7]={30,20,60,-120,-120,10,10},vh[7]={200,80,160,120,120,200,200};
    const int32_t vd[7]={53,44,80,0,0,75,20};
    for(uint32_t i=0;i<7;++i) {
        assert(set(h,65590+i,vl[i])==0 && set(h,65590+i,vh[i])==0);
        assert(set(h,65590+i,vl[i]-1)==-EINVAL && set(h,65590+i,vh[i]+1)==-EINVAL);
        int16_t shortValue=vd[i];assert(setRaw(h,65590+i,&shortValue,sizeof(shortValue))==-EINVAL);
        assert(set(h,65590+i,vd[i])==0);
    }
    assert(set(h,131073,0)==0);
    int16_t on=1,off=0;
    assert(setRaw(h,1201,&on,2)==0 && cmd(h,EFFECT_CMD_ENABLE)==0);
    assert(get(h,131084)==0 && get(h,131085)==0);
    std::array<float,512> input{},output{};size_t position=0;
    const auto render=[&](int blocks) {
        for(int block=0;block<blocks;++block) {
            for(size_t i=0;i<256;++i) input[2*i]=input[2*i+1]=.005f*std::sin(2*3.141592653589793*62.5*(position+i)/48000);
            assert(process(h,input.data(),output.data(),256)==0);position+=256;
            for(float x:output) assert(std::isfinite(x));
        }
    };
    render(600);
    assert(get(h,131084)>6200 && get(h,131084)<6300);
    assert(get(h,131085)>=499 && get(h,131085)<=500);
    auto precise=defaults;precise[0]=51;assert(setRaw(h,1300,precise.data(),sizeof(precise))==0);
    render(400);assert(get(h,131085)>=509 && get(h,131085)<=510);
    assert(setRaw(h,1201,&off,2)==0 && get(h,131084)==0 && get(h,131085)==0);
    assert(setRaw(h,1201,&on,2)==0);render(40);assert(get(h,131085)>0);
    assert(cmd(h,EFFECT_CMD_DISABLE)==0 && get(h,131084)==0 && get(h,131085)==0);
    assert(cmd(h,EFFECT_CMD_ENABLE)==0 && get(h,131084)==0 && get(h,131085)==0);
    render(20);assert(get(h,131085)>0);
    assert(set(h,131072,0)==0 && get(h,131084)==0 && get(h,131085)==0);
    assert(set(h,131072,1)==0);render(20);
    assert(cmd(h,EFFECT_CMD_RESET)==0 && get(h,131084)==0 && get(h,131085)==0);
    assert(set(h,131084,0)==-EINVAL && set(h,131085,0)==-EINVAL);
    AELI.release_effect(h);
    std::puts("Bass bridge passed: strict packet/range validation, capability 32, real centi-Hz/centi-dB readings, disabled/reset invalidation.");
}
void professionalBridgeTests() {
    effect_handle_t h=nullptr;assert(AELI.create_effect(&uuid,0,0,&h)==0);
    assert(get(h,131081)==2147483647&&get(h,131080)==5);
    for(unsigned id=131086;id<=131093;++id)assert(get(h,id)==0&&set(h,id,1)==-EINVAL);
    assert(set(h,65602,0)==-EINVAL); // Internal lifecycle message is not a public control.
    const std::array<int32_t,11> clarity{0,8250,100,120,1200,0,0,0,0,5000,2500};
    const std::array<int32_t,7> spectrum{560,7600,717,0,717,0,0};
    const int32_t cl[11]={0,2000,25,60,500,-120,-120,-120,-120,0,0};
    const int32_t ch[11]={450,16000,100,400,5000,60,60,60,0,10000,5000};
    const int32_t sl[7]={0,1000,300,0,300,-120,0},sh[7]={5600,16000,1500,24000,1500,0,1};
    auto setup=config(48000);assert(cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(setup),&setup)==0);
    for(size_t field=0;field<clarity.size();++field) {
        for(int value:{cl[field],ch[field]}) {auto v=clarity;v[field]=value;assert(setRaw(h,65600,v.data(),sizeof(v))==0);}
        for(int value:{cl[field]-1,ch[field]+1}) {auto v=clarity;v[field]=value;assert(setRaw(h,65600,v.data(),sizeof(v))==-EINVAL);}
    }
    for(size_t field=0;field<spectrum.size();++field) {
        for(int value:{sl[field],sh[field]}) {auto v=spectrum;v[field]=value;assert(setRaw(h,65601,v.data(),sizeof(v))==0);}
        for(int value:{sl[field]-1,sh[field]+1}) {auto v=spectrum;v[field]=value;assert(setRaw(h,65601,v.data(),sizeof(v))==-EINVAL);}
    }
    auto gap=spectrum;gap[3]=7999;assert(setRaw(h,65601,gap.data(),sizeof(gap))==-EINVAL);
    assert(setRaw(h,65600,clarity.data(),sizeof(clarity)-4)==-EINVAL);
    assert(setRaw(h,65601,spectrum.data(),sizeof(spectrum)-4)==-EINVAL);
    std::array<int32_t,12> oversized{};assert(setRaw(h,65600,oversized.data(),sizeof(oversized))==-EINVAL);
    assert(setRaw(h,65601,oversized.data(),32)==-EINVAL);
    std::array<int32_t,16> trailing{};trailing[1]=4;trailing[2]=44;trailing[3]=65600;
    std::memcpy(trailing.data()+4,clarity.data(),sizeof(clarity));
    assert(cmd(h,EFFECT_CMD_SET_PARAM,sizeof(trailing),trailing.data())==-EINVAL);
    assert(setRaw(h,65600,clarity.data(),sizeof(clarity))==0);
    assert(setRaw(h,65601,spectrum.data(),sizeof(spectrum))==0);
    for(unsigned rate:{8000u,44100u,48000u,96000u,192000u}) {
        setup=config(rate);assert(cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(setup),&setup)==0);
        assert(get(h,131086)==static_cast<int>(std::min(8250u,rate/2-100)));
        assert(get(h,131087)==120&&get(h,131088)==1200);
        assert(get(h,131089)==static_cast<int>(rate/200)&&get(h,131090)==static_cast<int>(rate/400));
        assert(get(h,131091)==static_cast<int>(std::min(7600u,rate/2-100)));
        assert(get(h,131092)==static_cast<int>(rate/2-2000)&&get(h,131093)==0);
    }
    setup=config(44100);assert(cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(setup),&setup)==0);
    auto c=clarity;c[1]=16000;c[3]=400;c[4]=5000;c[9]=10000;c[10]=5000;
    auto s=spectrum;s[1]=16000;s[3]=24000;
    assert(setRaw(h,65600,c.data(),sizeof(c))==0&&setRaw(h,65601,s.data(),sizeof(s))==0);
    assert(get(h,131086)==16000&&get(h,131087)==400&&get(h,131088)==5000);
    assert(get(h,131089)==441&&get(h,131090)==220&&get(h,131091)==16000&&get(h,131092)==21950);
    auto bad=c;bad[1]=2000;bad[10]=-1;assert(setRaw(h,65600,bad.data(),sizeof(bad))==-EINVAL);
    assert(get(h,131086)==16000&&get(h,131090)==220);
    // Old IDs still work without disturbing new XHiFi routing/delay controls.
    assert(set(h,65580,100)==0&&set(h,65550,56)==0&&set(h,65549,7600)==0);
    assert(get(h,131091)==7600&&get(h,131089)==441);
    assert(set(h,65548,1)==0&&cmd(h,EFFECT_CMD_ENABLE)==0);
    const auto arm=[&] {
        s[6]=0;assert(setRaw(h,65601,s.data(),sizeof(s))==0);
        s[6]=1;assert(setRaw(h,65601,s.data(),sizeof(s))==0);assert(get(h,131093)==1);
        // Ordinary identical packets must neither cancel nor restart audition.
        assert(setRaw(h,65601,s.data(),sizeof(s))==0&&get(h,131093)==1);
    };
    const auto stale=[&] {
        assert(get(h,131093)==0);s[0]=561;
        assert(setRaw(h,65601,s.data(),sizeof(s))==0&&get(h,131093)==0);
    };
    arm();assert(cmd(h,EFFECT_CMD_RESET)==0);stale();
    // Android may resend the same host configuration between tracks. Preserve
    // the existing stream and audition; only an actual rate change invalidates it.
    arm();assert(cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(setup),&setup)==0);
    assert(get(h,131093)==1);
    auto changedRate=setup;changedRate.inputCfg.samplingRate=changedRate.outputCfg.samplingRate=48000;
    assert(cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(changedRate),&changedRate)==0);stale();
    assert(cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(setup),&setup)==0);
    arm();assert(cmd(h,EFFECT_CMD_DISABLE)==0);assert(cmd(h,EFFECT_CMD_ENABLE)==0);stale();
    arm();assert(set(h,131073,0)==0);assert(set(h,131073,1)==0);stale();
    arm();assert(set(h,65548,0)==0);assert(set(h,65548,1)==0);stale();
    arm();auto invalid=s;invalid[2]=1501;assert(setRaw(h,65601,invalid.data(),sizeof(invalid))==-EINVAL);
    assert(get(h,131093)==1); // Invalid packets have no partial side effects.
    s[6]=0;assert(setRaw(h,65601,s.data(),sizeof(s))==0&&get(h,131093)==0);
    // Even a fresh request sent while a power switch is off must not arm later playback.
    for(unsigned power:{0u,131073u,65548u}) {
        if(power)assert(set(h,power,0)==0);else assert(cmd(h,EFFECT_CMD_DISABLE)==0);
        s[6]=0;assert(setRaw(h,65601,s.data(),sizeof(s))==0);
        s[6]=1;assert(setRaw(h,65601,s.data(),sizeof(s))==0&&get(h,131093)==0);
        if(power)assert(set(h,power,1)==0);else assert(cmd(h,EFFECT_CMD_ENABLE)==0);
        stale();
    }
    AELI.release_effect(h);
    std::puts("Professional bridge: capability 64, strict atomic packets, actual clamped frequencies/sample delays, legacy IDs, and five lifecycle cancellations rejecting stale audition requests passed.");
}
void topEndBridgeTests() {
    effect_handle_t h=nullptr;assert(AELI.create_effect(&uuid,0,0,&h)==0);
    assert(get(h,131081)==2147483647&&get(h,131080)==5);
    for(unsigned id=131094;id<=131102;++id)assert(get(h,id)==0&&set(h,id,1)==-EINVAL);
    const std::vector<std::vector<int32_t>> defaults={
        {0,3000,12000,707},{0,0,100,1},
        {0,6000,707,-180,40,60,50,120},
        {0,4500,10000,707,-240,60,60,10,80}};
    const std::vector<std::vector<int32_t>> lower={
        {0,1000,2000,300},{-120,0,0,1},
        {0,1000,300,-600,10,0,1,5},
        {0,2000,3000,300,-600,10,0,1,5}};
    const std::vector<std::vector<int32_t>> upper={
        {1,12000,20000,1500},{180,100,100,4},
        {1,16000,1500,0,200,180,1000,2000},
        {1,12000,20000,1500,0,200,180,100,500}};
    for(unsigned group=0;group<4;++group) {
        const uint32_t id=65604+group;
        auto baseline=defaults[group];const size_t bytes=baseline.size()*sizeof(int32_t);
        assert(setRaw(h,id,baseline.data(),bytes)==0);
        for(size_t field=0;field<baseline.size();++field) {
            for(int32_t value:{lower[group][field],upper[group][field]}) {
                auto v=baseline;v[field]=value;
                if(group==0||group==3) {
                    if(field==1)v[2]=20000;
                    if(field==2)v[1]=lower[group][1];
                }
                assert(setRaw(h,id,v.data(),bytes)==0);
            }
            for(int32_t value:{lower[group][field]-1,upper[group][field]+1}) {
                auto v=baseline;v[field]=value;assert(setRaw(h,id,v.data(),bytes)==-EINVAL);
            }
        }
        assert(setRaw(h,id,baseline.data(),bytes-4)==-EINVAL);
        auto extra=baseline;extra.push_back(0);assert(setRaw(h,id,extra.data(),bytes+4)==-EINVAL);
        std::vector<uint32_t> trailing(5+baseline.size());
        trailing[1]=4;trailing[2]=bytes;trailing[3]=id;
        std::memcpy(trailing.data()+4,baseline.data(),bytes);
        assert(cmd(h,EFFECT_CMD_SET_PARAM,trailing.size()*4,trailing.data())==-EINVAL);
        assert(setRaw(h,id,baseline.data(),bytes)==0);
    }
    auto invalid=defaults[1];invalid[3]=3;assert(setRaw(h,65605,invalid.data(),invalid.size()*4)==-EINVAL);
    for(unsigned group:{0u,3u}) {
        auto v=defaults[group];v[1]=v[2];assert(setRaw(h,65604+group,v.data(),v.size()*4)==-EINVAL);
        v[1]=v[2]+1;assert(setRaw(h,65604+group,v.data(),v.size()*4)==-EINVAL);
    }
    for(unsigned rate:{8000u,44100u,48000u,96000u,192000u}) {
        auto setup=config(rate);assert(cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(setup),&setup)==0);
        assert(get(h,131094)==3000&&get(h,131095)==static_cast<int>(std::min(12000u,rate/2-100)));
        assert(get(h,131096)==static_cast<int>(std::min(6000u,rate/2-100)));
        assert(get(h,131097)==(rate==8000?0:4500));
        assert(get(h,131098)==(rate==8000?0:10000));
        for(unsigned id=131099;id<=131102;++id)assert(get(h,id)==0);
    }
    auto setup=config(48000);assert(cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(setup),&setup)==0);
    assert(set(h,131072,0)==0&&set(h,65578,1)==0&&set(h,65548,1)==0&&cmd(h,EFFECT_CMD_ENABLE)==0);
    auto nonlinear=defaults[1];nonlinear[3]=4;
    auto dynamic=defaults[2];dynamic[0]=1;dynamic[3]=-400;
    auto deess=defaults[3];deess[0]=1;deess[4]=-400;
    assert(setRaw(h,65605,nonlinear.data(),nonlinear.size()*4)==0);
    assert(setRaw(h,65606,dynamic.data(),dynamic.size()*4)==0);
    assert(setRaw(h,65607,deess.data(),deess.size()*4)==0);
    std::array<float,1024> input{},output{};unsigned position=0;
    const auto render=[&](unsigned blocks) {
        for(unsigned block=0;block<blocks;++block) {
            for(unsigned frame=0;frame<512;++frame)input[2*frame]=input[2*frame+1]=.4f*std::sin(2*3.141592653589793*7000*(position+frame)/48000);
            assert(process(h,input.data(),output.data(),512)==0);position+=512;
            for(float x:output)assert(std::isfinite(x));
        }
    };
    render(30);
    assert(get(h,131099)==4&&get(h,131100)==32);
    assert(get(h,131101)>0&&get(h,131101)<=600);
    assert(get(h,131102)>0&&get(h,131102)<=600);
    auto bad=dynamic;bad[1]=1000;bad[7]=2001;
    assert(setRaw(h,65606,bad.data(),bad.size()*4)==-EINVAL&&get(h,131096)==6000);
    assert(set(h,65578,0)==0&&get(h,131101)==0&&get(h,131102)==0);
    assert(set(h,65578,1)==0);render(10);assert(get(h,131101)>0);
    assert(set(h,65548,0)==0&&get(h,131099)==0&&get(h,131100)==0);
    assert(set(h,65548,1)==0);render(10);
    assert(cmd(h,EFFECT_CMD_DISABLE)==0);
    for(unsigned id=131099;id<=131102;++id)assert(get(h,id)==0);
    assert(cmd(h,EFFECT_CMD_ENABLE)==0);render(10);assert(get(h,131101)>0);
    assert(set(h,131073,0)==0);
    for(unsigned id=131099;id<=131102;++id)assert(get(h,id)==0);
    assert(set(h,131073,1)==0);render(10);
    assert(cmd(h,EFFECT_CMD_RESET)==0&&get(h,131101)==0&&get(h,131102)==0);
    AELI.release_effect(h);
    std::puts("Top-end round 2 bridge: strict packets/ranges/order/factors, read-only state, real processing GR/oversampling, rate limits, and lifecycle invalidation passed.");
}
void imagingBridgeTests() {
    effect_handle_t h=nullptr;assert(AELI.create_effect(&uuid,0,0,&h)==0);
    constexpr int32_t invalidReading=std::numeric_limits<int32_t>::min();
    assert(get(h,131080)==5&&get(h,131081)==2147483647);
    for(unsigned id=131103;id<=131111;++id) {
        assert(get(h,id)==(id>=131106&&id<=131109?invalidReading:0));
        assert(set(h,id,1)==-EINVAL);
    }
    assert(set(h,65609,0)==-EINVAL);
    const std::array<int32_t,13> defaults{0,1000,0,0,150,16000,0,120,1000,1,1000,0,0};
    const int32_t low[13]={0,0,-120,0,100,2000,0,40,0,0,0,-120,0};
    const int32_t high[13]={1,2000,60,1,2000,20000,1,500,1000,1,1000,0,3};
    for(size_t field=0;field<defaults.size();++field) {
        for(int value:{low[field],high[field]}) {auto v=defaults;v[field]=value;assert(setRaw(h,65608,v.data(),sizeof(v))==0);}
        for(int value:{low[field]-1,high[field]+1}) {auto v=defaults;v[field]=value;assert(setRaw(h,65608,v.data(),sizeof(v))==-EINVAL);}
    }
    auto equal=defaults;equal[4]=equal[5]=2000;assert(setRaw(h,65608,equal.data(),sizeof(equal))==-EINVAL);
    assert(setRaw(h,65608,defaults.data(),sizeof(defaults)-4)==-EINVAL);
    std::array<int32_t,14> oversized{};assert(setRaw(h,65608,oversized.data(),sizeof(oversized))==-EINVAL);
    std::array<uint32_t,18> trailing{};trailing[1]=4;trailing[2]=sizeof(defaults);trailing[3]=65608;
    std::memcpy(trailing.data()+4,defaults.data(),sizeof(defaults));
    assert(cmd(h,EFFECT_CMD_SET_PARAM,sizeof(trailing),trailing.data())==-EINVAL);
    assert(setRaw(h,65608,defaults.data(),sizeof(defaults))==0);
    for(unsigned rate:{8000u,44100u,48000u,96000u,192000u}) {
        auto setup=config(rate);assert(cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(setup),&setup)==0);
        assert(get(h,131103)==150&&get(h,131104)==static_cast<int>(std::min(16000u,rate/2-100))&&get(h,131105)==120);
        for(unsigned id=131106;id<=131109;++id)assert(get(h,id)==invalidReading);
        assert(get(h,131110)==0&&get(h,131111)==0);
    }
    auto setup=config(48000);assert(cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(setup),&setup)==0);
    assert(set(h,131072,0)==0&&set(h,65553,1)==0&&cmd(h,EFFECT_CMD_ENABLE)==0);
    auto imaging=defaults;imaging[0]=1;imaging[9]=0;assert(setRaw(h,65608,imaging.data(),sizeof(imaging))==0);
    std::array<float,1024> input{},output{};unsigned position=0;
    const auto render=[&](unsigned blocks,int kind) {
        double power=0;
        for(unsigned block=0;block<blocks;++block) {
            for(unsigned frame=0;frame<512;++frame) {
                const float value=.25f*std::sin(2*3.141592653589793*1000*(position+frame)/48000);
                input[2*frame]=value;input[2*frame+1]=kind==1?-value:kind==2?0.f:value;
            }
            assert(process(h,input.data(),output.data(),512)==0);position+=512;
            for(float x:output){assert(std::isfinite(x));if(block+2>=blocks)power+=double(x)*x;}
        }
        return power;
    };
    render(30,0);
    assert(get(h,131111)==1&&get(h,131106)>=9999);
    assert(std::abs(get(h,131107)+1505)<=2&&get(h,131108)==invalidReading);
    assert(std::abs(get(h,131109)+1204)<=2);
    render(30,1);assert(get(h,131106)<=-9999&&get(h,131107)==invalidReading);
    assert(std::abs(get(h,131108)+1505)<=2);
    imaging[12]=1;assert(setRaw(h,65608,imaging.data(),sizeof(imaging))==0&&get(h,131110)==1);
    assert(render(30,1)<1.e-9); // Mono audition cancels antisymmetric Side, telemetry remains before audition.
    assert(get(h,131106)<=-9999&&get(h,131108)!=invalidReading);
    imaging[12]=3;assert(setRaw(h,65608,imaging.data(),sizeof(imaging))==0&&get(h,131110)==3);
    assert(render(30,0)<1.e-9);assert(get(h,131106)>=9999&&get(h,131107)!=invalidReading);
    imaging[12]=0;assert(setRaw(h,65608,imaging.data(),sizeof(imaging))==0);
    render(30,2);assert(get(h,131106)==invalidReading&&get(h,131107)!=invalidReading&&get(h,131108)!=invalidReading);
    auto bad=imaging;bad[5]=2000;bad[8]=1001;
    assert(setRaw(h,65608,bad.data(),sizeof(bad))==-EINVAL&&get(h,131104)==16000);
    const auto arm=[&] {
        imaging[12]=0;assert(setRaw(h,65608,imaging.data(),sizeof(imaging))==0);
        imaging[12]=1;assert(setRaw(h,65608,imaging.data(),sizeof(imaging))==0&&get(h,131110)==1);
    };
    const auto stale=[&] {
        assert(get(h,131110)==0&&get(h,131111)==0);
        for(unsigned id=131106;id<=131109;++id)assert(get(h,id)==invalidReading);
        assert(setRaw(h,65608,imaging.data(),sizeof(imaging))==0&&get(h,131110)==0);
    };
    arm();assert(cmd(h,EFFECT_CMD_RESET)==0);stale();
    arm();assert(cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(setup),&setup)==0);
    assert(get(h,131110)==1);
    auto changedRate=setup;changedRate.inputCfg.samplingRate=changedRate.outputCfg.samplingRate=44100;
    assert(cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(changedRate),&changedRate)==0);stale();
    assert(cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(setup),&setup)==0);
    arm();assert(cmd(h,EFFECT_CMD_DISABLE)==0);assert(cmd(h,EFFECT_CMD_ENABLE)==0);stale();
    arm();assert(set(h,131073,0)==0);assert(set(h,131073,1)==0);stale();
    arm();assert(set(h,65553,0)==0);assert(set(h,65553,1)==0);stale();
    AELI.release_effect(h);
    std::puts("Imaging bridge: strict 13-word packet, actual meter calibration, invalid readings, pre-audition telemetry, modes and lifecycle cancellation passed.");
}
void imagingAdvancedBridgeTests() {
    effect_handle_t h=nullptr;assert(AELI.create_effect(&uuid,0,0,&h)==0);
    constexpr int32_t invalid=std::numeric_limits<int32_t>::min();
    const auto unavailable=[](unsigned id){return id==131112 || (id>=131121 && id<=131124);};
    for(unsigned id=131112;id<=131125;++id) {
        assert(get(h,id)==(unavailable(id)?invalid:0));assert(set(h,id,1)==-EINVAL);
    }
    assert(get(h,131127)==0&&set(h,131127,1)==-EINVAL);
    assert(set(h,65609,0)==-EINVAL);
    for(unsigned id=65610;id<=65626;++id)assert(set(h,id,0)==-ENOSYS);
    const std::vector<std::vector<int32_t>> defaults{
        {0,-180,20,30,10,250},{0,0,5,30,600,1000},{0,500,60,80},{0,30,150,0,2,1200,10,150}};
    const std::vector<std::vector<int32_t>> low{
        {0,-480,10,0,1,50},{0,-30,2,10,200,0},{0,0,30,20},{0,0,0,0,1,200,5,0}};
    const std::vector<std::vector<int32_t>> high{
        {1,-60,80,120,100,1500},{1,50,20,100,2000,1000},{1,1000,180,300},{1,500,1000,1,4,8000,30,1000}};
    for(unsigned kind=0;kind<defaults.size();++kind) {
        const uint32_t id=65627+kind;const auto& original=defaults[kind];const size_t bytes=original.size()*sizeof(int32_t);
        for(unsigned field=0;field<original.size();++field) {
            for(int32_t value:{low[kind][field],high[kind][field]}) {
                auto v=original;v[field]=value;assert(setRaw(h,id,v.data(),bytes)==0);
            }
            for(int32_t value:{low[kind][field]-1,high[kind][field]+1}) {
                auto v=original;v[field]=value;assert(setRaw(h,id,v.data(),bytes)==-EINVAL);
            }
        }
        assert(setRaw(h,id,original.data(),bytes-4)==-EINVAL);
        assert(setRaw(h,id,original.data(),bytes-1)==-EINVAL);
        auto oversized=original;oversized.push_back(0);
        assert(setRaw(h,id,oversized.data(),bytes+4)==-EINVAL);
        std::vector<uint32_t> trailing(5+original.size());trailing[1]=4;trailing[2]=bytes;trailing[3]=id;
        std::memcpy(trailing.data()+4,original.data(),bytes);
        assert(cmd(h,EFFECT_CMD_SET_PARAM,trailing.size()*4,trailing.data())==-EINVAL);
        assert(setRaw(h,id,original.data(),bytes)==0);
    }
    auto wrongStages=defaults[3];wrongStages[4]=3;
    assert(setRaw(h,65630,wrongStages.data(),wrongStages.size()*4)==-EINVAL);
    assert(set(h,65631,-1)==-EINVAL&&set(h,65631,2)==-EINVAL);
    const int16_t shortValue=1;assert(setRaw(h,65631,&shortValue,2)==-EINVAL);
    std::array<uint32_t,6> trailingAudition{0,4,4,65631,1,0};
    assert(cmd(h,EFFECT_CMD_SET_PARAM,sizeof(trailingAudition),trailingAudition.data())==-EINVAL);
    auto setup=config(48000);assert(cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(setup),&setup)==0);
    assert(set(h,131072,0)==0&&set(h,65553,1)==0&&cmd(h,EFFECT_CMD_ENABLE)==0);
    std::array<int32_t,13> imaging{1,1000,0,0,150,16000,0,120,1000,0,1000,0,0};
    assert(setRaw(h,65608,imaging.data(),sizeof(imaging))==0);
    std::array<float,1024> in{},out{};unsigned position=0;
    const auto render=[&](unsigned blocks,int kind=0) {
        double power=0;
        for(unsigned block=0;block<blocks;++block) {
            for(unsigned frame=0;frame<512;++frame) {
                const double t=double(position+frame)/48000;
                const float mid=kind==1?0.f:.08f*std::sin(2*3.141592653589793*200*t);
                const float side=.3f*std::sin(2*3.141592653589793*1000*t);
                in[2*frame]=mid+side;in[2*frame+1]=mid-side;
            }
            assert(process(h,in.data(),out.data(),512)==0);position+=512;
            for(float value:out){assert(std::isfinite(value));if(block+2>=blocks)power+=double(value)*value;}
        }
        return power;
    };
    render(30);assert(get(h,131125)==1);
    assert(std::abs(get(h,131112)-get(h,131106))<=1);
    assert(get(h,131113)==0&&get(h,131114)==0&&get(h,131115)==0);
    const double folded=10*std::log10(.08*.08/(.08*.08+.3*.3));
    assert(std::abs(get(h,131123)-static_cast<int>(std::lround(folded*100)))<=2);
    assert((get(h,131127)&1)==0);
    const auto epoch=get(h,131127);
    auto space=defaults[3];space[1]=123;
    assert(setRaw(h,65630,space.data(),space.size()*4)==0);
    assert(get(h,131116)==1230&&get(h,131117)==59040);
    assert(get(h,131127)!=epoch&&(get(h,131127)&1)==0);
    auto dynamic=defaults[0];dynamic[0]=1;dynamic[1]=-300;dynamic[2]=40;dynamic[3]=60;
    assert(setRaw(h,65627,dynamic.data(),dynamic.size()*4)==0);render(100);
    assert(get(h,131113)>=590&&get(h,131113)<=601);
    dynamic[0]=0;assert(setRaw(h,65627,dynamic.data(),dynamic.size()*4)==0);render(100);
    assert(get(h,131113)==0);
    assert(set(h,65631,0)==0&&set(h,65631,1)==0&&get(h,131110)==4);
    assert(render(30)<1.e-12); // Difference is zero when the second round is bypassed.
    assert(setRaw(h,65608,imaging.data(),sizeof(imaging))==0&&get(h,131110)==4);
    space[0]=1;space[2]=500;assert(setRaw(h,65630,space.data(),space.size()*4)==0);
    assert(render(60)>1.e-4); // Actual new processing, not another Side solo.
    assert(get(h,131125)==1&&get(h,131122)!=invalid);
    imaging[12]=1;assert(setRaw(h,65608,imaging.data(),sizeof(imaging))==0&&get(h,131110)==1);
    assert(set(h,65631,1)==0&&get(h,131110)==1); // Same stale request cannot reactivate the difference.
    imaging[12]=0;assert(setRaw(h,65608,imaging.data(),sizeof(imaging))==0);
    const auto arm=[&]{assert(set(h,65631,0)==0&&set(h,65631,1)==0&&get(h,131110)==4);};
    const auto stale=[&] {
        assert(get(h,131110)==0&&get(h,131125)==0);
        for(unsigned id:{131112u,131121u,131122u,131123u,131124u})assert(get(h,id)==invalid);
        assert(get(h,131113)==0&&get(h,131114)==0&&get(h,131115)==0);
        assert(set(h,65631,1)==0&&get(h,131110)==0);
    };
    arm();assert(cmd(h,EFFECT_CMD_RESET)==0);stale();
    arm();assert(cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(setup),&setup)==0);
    assert(get(h,131110)==4);
    auto changedRate=setup;changedRate.inputCfg.samplingRate=changedRate.outputCfg.samplingRate=44100;
    assert(cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(changedRate),&changedRate)==0);stale();
    assert(cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(setup),&setup)==0);
    arm();assert(cmd(h,EFFECT_CMD_DISABLE)==0&&cmd(h,EFFECT_CMD_ENABLE)==0);stale();
    arm();assert(set(h,131073,0)==0&&set(h,131073,1)==0);stale();
    arm();assert(set(h,65553,0)==0&&set(h,65553,1)==0);stale();
    render(30,1);assert(get(h,131112)<=-9999&&get(h,131123)==invalid&&get(h,131125)==1);
    assert(AELI.release_effect(h)==0);
    std::puts("Imaging round 2 bridge: strict packets, fractional targets, real dynamics/difference, coherent epoch, mono-fold scale and lifecycle edge guards passed.");
}
void lowRateStreamingTests() {
    effect_handle_t h=nullptr;assert(AELI.create_effect(&uuid,0,0,&h)==0);
    const int32_t clarity[11]={200,16000,25,400,5000,0,0,0,-60,10000,5000};
    const int32_t spectrum[7]={560,16000,1500,24000,1500,-60,0};
    assert(setRaw(h,65600,clarity,sizeof(clarity))==0&&setRaw(h,65601,spectrum,sizeof(spectrum))==0);
    assert(set(h,65578,1)==0&&set(h,65579,1)==0&&set(h,65548,1)==0&&cmd(h,EFFECT_CMD_ENABLE)==0);
    for(unsigned low:{8000u,11025u,16000u,22050u,32000u}) for(unsigned rate:{low,44100u,low,48000u}) {
        auto actual=config(rate);assert(cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(actual),&actual)==0);
        assert(get(h,131082)==static_cast<int>(rate));
        assert(get(h,131086)==static_cast<int>(std::min(16000u,rate/2-100)));
        assert(get(h,131089)==static_cast<int>(rate/100));
        for(unsigned count:{1u,17u,127u,511u,512u,513u,1025u}) {
            std::vector<float> source(2*count),out(2*count+2,-1234.5f);
            for(size_t i=0;i<source.size();++i)source[i]=.05f*std::sin(i*.37f);
            assert(process(h,source.data(),out.data()+1,count)==0);
            assert(out.front()==-1234.5f&&out.back()==-1234.5f);
            for(size_t i=1;i<out.size()-1;++i)assert(std::isfinite(out[i]));
        }
    }
    AELI.release_effect(h);
    std::puts("Fusion low-rate streaming: James + both professional effects, 8/11.025/16/22.05/32 kHz round trips to 44.1/48 kHz, varied blocks and output guards passed.");
}
void diffBridgeTests() {
    effect_handle_t h=nullptr;assert(AELI.create_effect(&uuid,0,0,&h)==0);
    constexpr int32_t invalid=std::numeric_limits<int32_t>::min();
    assert(get(h,131080)==5 && get(h,131081)==2147483647);
    for(unsigned id=131128;id<=131151;++id) {
        assert(set(h,id,1)==-EINVAL);
        assert(get(h,id)==((id>=131135 && id<=131144)?invalid:0));
    }
    assert(set(h,65634,0)==-EINVAL);
    auto setup=config(48000);assert(cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(setup),&setup)==0);
    const std::array<int32_t,7> defaults{0,500,0,1000,0,150,12000};
    const int32_t low[7]={0,0,0,0,0,40,2000},high[7]={1,2000,1,1000,1,2000,20000};
    for(unsigned index=0;index<defaults.size();++index) {
        for(int32_t value:{low[index],high[index]}) {
            auto packet=defaults;packet[index]=value;
            if(packet[5]>=packet[6])continue;
            assert(setRaw(h,65632,packet.data(),sizeof(packet))==0);
        }
        for(int32_t value:{low[index]-1,high[index]+1}) {
            auto packet=defaults;packet[index]=value;
            assert(setRaw(h,65632,packet.data(),sizeof(packet))==-EINVAL);
        }
    }
    auto crossed=defaults;crossed[5]=crossed[6]=2000;
    assert(setRaw(h,65632,crossed.data(),sizeof(crossed))==-EINVAL);
    assert(setRaw(h,65632,defaults.data(),sizeof(defaults)-4)==-EINVAL);
    std::array<int32_t,8> oversized{};assert(setRaw(h,65632,oversized.data(),sizeof(oversized))==-EINVAL);
    std::array<uint32_t,12> trailing{};trailing[1]=4;trailing[2]=28;trailing[3]=65632;
    std::memcpy(trailing.data()+4,defaults.data(),sizeof(defaults));
    assert(cmd(h,EFFECT_CMD_SET_PARAM,sizeof(trailing),trailing.data())==-EINVAL);
    assert(set(h,65633,-1)==-EINVAL && set(h,65633,3)==-EINVAL);
    const int16_t shortChoice=1;assert(setRaw(h,65633,&shortChoice,2)==-EINVAL);
    assert(set(h,131072,0)==0 && set(h,131073,1)==0 && cmd(h,EFFECT_CMD_ENABLE)==0);
    // The input is mono at the imaging checkpoint, then becomes anti-correlated at this later node.
    std::array<int32_t,13> field{1,1000,0,0,150,16000,0,120,1000,0,1000,0,0};
    assert(setRaw(h,65608,field.data(),sizeof(field))==0 && set(h,65553,1)==0);
    auto professional=defaults;professional[0]=1;
    assert(setRaw(h,65632,professional.data(),sizeof(professional))==0 && set(h,65557,1)==0);
    std::array<float,512> input{},output{};size_t position=0;
    const auto render=[&](unsigned blocks) {
        for(unsigned block=0;block<blocks;++block) {
            for(unsigned index=0;index<256;++index)
                input[2*index]=input[2*index+1]=.05f*std::sin(2*3.141592653589793*100*(position+index)/48000);
            assert(process(h,input.data(),output.data(),256)==0);position+=256;
            for(float value:output)assert(std::isfinite(value));
        }
    };
    render(50);
    assert(get(h,131145)==1 && get(h,131149)==1);
    assert(get(h,131150)==1000 && get(h,131151)==0);
    assert(get(h,131128)==5000 && get(h,131129)==240000 && get(h,131130)==0);
    assert(get(h,131131)==0 && get(h,131132)==0);
    assert(get(h,131135)>=9999 && get(h,131136)<=-9999);
    assert(get(h,131137)==0 && (get(h,131138)==invalid || get(h,131138)<-6000));
    assert(get(h,131106)>=9999); // Upstream imaging meter cannot represent the delayed output.
    assert((get(h,131148)&1)==0);
    auto epoch=get(h,131148);
    professional[1]=501;professional[2]=1;
    assert(setRaw(h,65632,professional.data(),sizeof(professional))==0);
    assert(get(h,131128)==5010 && get(h,131129)==240480 && get(h,131130)==1);
    assert(get(h,131148)!=epoch && (get(h,131148)&1)==0);
    render(20);
    const auto arm=[&](int choice) {
        assert(set(h,65633,0)==0 && set(h,65633,choice)==0 && get(h,131146)==choice);
    };
    arm(2);render(20);
    for(unsigned index=0;index<256;++index)assert(std::abs(output[2*index]-output[2*index+1])<1e-7f);
    arm(1);render(20);
    for(unsigned index=0;index<input.size();++index)assert(std::abs(output[index]-input[index])<1e-7f);
    for(unsigned action=0;action<4;++action) {
        arm(2);render(3);
        if(action==0)assert(cmd(h,EFFECT_CMD_RESET)==0);
        if(action==1)assert(set(h,65557,0)==0);
        if(action==2)assert(set(h,131073,0)==0);
        if(action==3)assert(cmd(h,EFFECT_CMD_DISABLE)==0);
        assert(get(h,131146)==0 && get(h,131145)==0);
        for(unsigned id=131135;id<=131144;++id)assert(get(h,id)==invalid);
        assert(set(h,65557,1)==0 && set(h,131073,1)==0 && cmd(h,EFFECT_CMD_ENABLE)==0);
        assert(set(h,65633,2)==0 && get(h,131146)==0); // Stale repeated request cannot revive a temporary audition.
        render(25);
    }
    professional[4]=1;
    assert(setRaw(h,65632,professional.data(),sizeof(professional))==0);
    render(80);
    assert(get(h,131131)>0 && get(h,131132)>0 && get(h,131133)==150 && get(h,131134)==12000);
    assert(get(h,131151)==1);
    assert(get(h,131145)==1);
    professional[5]=2000;professional[6]=2000;
    assert(setRaw(h,65632,professional.data(),sizeof(professional))==-EINVAL && get(h,131133)==150);
    AELI.release_effect(h);
    std::puts("Interchannel-delay bridge passed: strict seven-word protocol, read-only epoch telemetry, post-imaging meters, fractional targets, post-delay mono/bypass and stale-audition lifecycle gates.");
}
void diffAdvancedBridgeTests() {
    effect_handle_t h=nullptr;assert(AELI.create_effect(&uuid,0,0,&h)==0);
    auto setup=config(48000);assert(cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(setup),&setup)==0);
    auto advanced=diffadvanced::defaults;
    const int32_t low[21]={0,0,0,20,-240,-120,0,0,-30,0,2,0,5,100,0,0,30,5,20,80,1000};
    const int32_t high[21]={1,1,20000,80,0,0,1,2,50,120,20,1000,100,2000,1,1000,180,200,300,500,6000};
    for(unsigned i=0;i<advanced.size();++i)for(int value:{low[i],high[i],low[i]-1,high[i]+1}) {
        auto packet=advanced;packet[i]=value;
        assert(setRaw(h,65635,packet.data(),sizeof(packet))==(value>=low[i] && value<=high[i]?0:-EINVAL));
    }
    auto invalid=advanced;invalid[3]=60;assert(setRaw(h,65635,invalid.data(),sizeof(invalid))==-EINVAL);
    assert(setRaw(h,65635,advanced.data(),sizeof(advanced)-4)==-EINVAL);
    std::array<uint32_t,26> trailing{};trailing[1]=4;trailing[2]=84;trailing[3]=65635;std::memcpy(trailing.data()+4,advanced.data(),84);
    assert(cmd(h,EFFECT_CMD_SET_PARAM,sizeof(trailing),trailing.data())==-EINVAL);
    assert(set(h,65636,-1)==-EINVAL && set(h,65636,5)==-EINVAL && set(h,65633,3)==-EINVAL);
    assert(cmd(h,0x44494650u)==-EINVAL); // No externally callable prepared-pointer handoff.
    std::array<int32_t,7> basic{1,500,0,1000,0,150,12000};
    advanced=diffadvanced::defaults;advanced[0]=1;advanced[1]=1;advanced[2]=5001;
    assert(setRaw(h,65632,basic.data(),sizeof(basic))==0 && setRaw(h,65635,advanced.data(),sizeof(advanced))==0);
    assert(set(h,131072,0)==0 && set(h,65557,1)==0 && cmd(h,EFFECT_CMD_ENABLE)==0);
    const auto bulk=[&] {
        std::array<uint32_t,4> request{0,4,336,131212};std::array<uint32_t,88> response{};uint32_t bytes=sizeof(response);
        assert((*h)->command(h,EFFECT_CMD_GET_PARAM,sizeof(request),request.data(),&bytes,response.data())==0);
        assert(bytes==sizeof(response) && response[0]==0 && response[1]==4 && response[2]==336 && response[3]==131212);
        std::array<int32_t,84> values{};std::memcpy(values.data(),response.data()+4,336);assert((values[20]&1)==0);return values;
    };
    auto values=bulk();assert(values[0]==5001 && values[1]==240048 && values[3]==1 && values[24]==1);
    std::array<uint32_t,4> shortRequest{0,4,4,131212};std::array<uint32_t,88> response{};uint32_t bytes=sizeof(response);
    assert((*h)->command(h,EFFECT_CMD_GET_PARAM,sizeof(shortRequest),shortRequest.data(),&bytes,response.data())==-EINVAL);
    for(unsigned id=131152;id<=131212;++id)assert(set(h,id,1)==-EINVAL);
    std::array<float,1024> input{},output{};unsigned position=0;
    const auto render=[&](unsigned blocks){for(unsigned block=0;block<blocks;++block){for(unsigned i=0;i<512;++i)input[2*i]=input[2*i+1]=.1f*std::sin(2*3.141592653589793*1000*(position+i)/48000);assert(process(h,input.data(),output.data(),512)==0);position+=512;}};
    render(30);values=bulk();assert(values[45]==1 && values[35]>=9999);
    for(unsigned selection:{3u,4u}) {
        assert(set(h,65636,0)==0 && set(h,65636,selection)==0 && get(h,131146)==int(selection));render(5);
        if(selection==3)for(unsigned i=1;i<512;++i)assert(output[2*i]==input[2*(i-1)] && output[2*i+1]==input[2*(i-1)+1]);
        assert(cmd(h,EFFECT_CMD_RESET)==0 && get(h,131146)==0);
        assert(set(h,65636,selection)==0 && get(h,131146)==0);render(20);
    }
    // Orthogonal in-flight requests must merge; either prepare may finish last.
    std::atomic<bool> start{false};
    std::thread basicWriter([&]{while(!start.load()){};for(int i=0;i<30;++i){auto b=basic;b[1]=900+i;b[4]=1;assert(setRaw(h,65632,b.data(),sizeof(b))==0);}});
    std::thread advancedWriter([&]{while(!start.load()){};for(int i=0;i<30;++i){auto a=diffadvanced::defaults;a[0]=i%2;a[3]=i%2?80:20;assert(setRaw(h,65635,a.data(),sizeof(a))==0);}});
    start.store(true);basicWriter.join();advancedWriter.join();
    values=bulk();assert(values[0]==9290 && values[24]==1 && values[26]==80);
    assert(cmd(h,EFFECT_CMD_DISABLE)==0);values=bulk();
    for(unsigned i=0;i<84;++i)if((i>=7 && i<=16) || (i>=35 && i<=44) || (i>=46 && i<=81))assert(values[i]==std::numeric_limits<int32_t>::min());
    assert(values[45]==0 && values[18]==0 && values[24]==1 && values[26]==80);
    AELI.release_effect(h);
    std::puts("Diff round-2 bridge: strict extension packet, atomic bulk telemetry, extended audition cancellation, read-only diagnostics and concurrent orthogonal request merging passed.");
}
void liveprogBridgeTests() {
    effect_handle_t h=nullptr;assert(AELI.create_effect(&uuid,0,0,&h)==0);
    assert(get(h,131234)==15); // Legacy bits plus precise PEQ schema 2.
    auto c=config(48000);assert(cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(c),&c)==0);
    auto upload=[&](const char* source) {
        const size_t bytes=std::strlen(source)+1;
        const int32_t start[]{static_cast<int32_t>((bytes+255)/256),256};
        assert(setRaw(h,8888,start,sizeof(start))==0);
        for(int32_t i=0;i<start[0];++i) {
            std::array<char,256> chunk{};
            const size_t offset=i*256;
            std::memcpy(chunk.data(),source+offset,std::min<size_t>(256,bytes-offset));
            assert(setRaw(h,12001,chunk.data(),chunk.size())==0);
        }
        const int16_t commit=0;return setRaw(h,10010,&commit,2);
    };
    assert(upload("@init\ngain=0.1;state=0;\n@slider\nscaled=gain*2;\n@sample\nstate+=1;spl0=state*scaled*0.001;spl1=spl0;\n")==0);
    assert(get(h,131241)==1 && set(h,25002,12345)==0 && get(h,30002)==12345);
    assert(set(h,131073,0)==0);
    const int16_t on=1;assert(setRaw(h,1213,&on,2)==0 && cmd(h,EFFECT_CMD_ENABLE)==0);
    std::array<float,16> input{},output{};
    assert(process(h,input.data(),output.data(),8)==0);
    const float before=output[14];assert(before>0);
    auto parameter=[](const char* name,double value) {
        std::array<uint8_t,72> p{};std::memcpy(p.data(),name,std::strlen(name));std::memcpy(p.data()+64,&value,8);return p;
    };
    auto p=parameter("gain",0.2);
    assert(setRaw(h,196656,p.data(),p.size())==0);
    assert(process(h,input.data(),output.data(),8)==0);
    // A fresh VM would restart at sample 1. State must continue at sample 9,
    // and @slider must update the derived multiplier as well as the variable.
    assert(output[0]>before*2 && output[14]>output[0]);
    const int16_t commit=0;assert(setRaw(h,10010,&commit,2)==-EINVAL);
    assert(upload("@init\ngain=0.9;\n@sample\nspl0=(;\n")!=0);
    assert(get(h,131241)==-3 && get(h,30002)==12345 && set(h,25002,999)==-EINVAL);
    assert(process(h,input.data(),output.data(),8)==0 && output[0]>0.006f);
    assert(setRaw(h,196656,p.data(),p.size()-1)==-EINVAL);
    for(const char* name:{"srate","SRATE","spl0","spl1","bad-name","9gain","missing"}) {
        auto bad=parameter(name,0.5);assert(setRaw(h,196656,bad.data(),bad.size())!=0);
    }
    auto bad=parameter("gain",std::numeric_limits<double>::quiet_NaN());assert(setRaw(h,196656,bad.data(),bad.size())==-EINVAL);
    bad=parameter("gain",0.3);bad[20]='x';assert(setRaw(h,196656,bad.data(),bad.size())==-EINVAL);
    bad.fill('a');assert(setRaw(h,196656,bad.data(),bad.size())==-EINVAL);
    assert(set(h,131241,1)==-EINVAL);
    assert(AELI.release_effect(h)==0);
    std::puts("Liveprog Fusion bridge passed: real compilation, derived live parameter updates without state reset, syntax rollback, truthful status/hash and strict wire validation.");
}
int main() {
    liveprogBridgeTests();
    runStreamingTests();
    bassBridgeTests();
    professionalBridgeTests();
    topEndBridgeTests();
    imagingBridgeTests();
    imagingAdvancedBridgeTests();
    diffBridgeTests();
    diffAdvancedBridgeTests();
    lowRateStreamingTests();
    {
        effect_handle_t statusHandle=nullptr;
        assert(AELI.create_effect(&uuid,0,0,&statusHandle)==0);
        // Constructor defaults are not evidence of an Android host configuration.
        assert(get(statusHandle,131082)==0 && get(statusHandle,131083)==0);
        std::array<float,64> source{},destination{};
        int frames=0;
        for(uint32_t rate:{44100u,48000u,96000u,192000u}) {
            auto actual=config(rate);
            assert(cmd(statusHandle,EFFECT_CMD_SET_CONFIG,sizeof(actual),&actual)==0);
            assert(get(statusHandle,131082)==static_cast<int>(rate));
            assert(get(statusHandle,131083)==frames); // James preallocation is not host processing.
            assert(cmd(statusHandle,EFFECT_CMD_ENABLE)==0);
            assert(process(statusHandle,source.data(),destination.data(),32)==0);
            frames+=32;
            assert(get(statusHandle,131083)==frames);
            assert(cmd(statusHandle,EFFECT_CMD_DISABLE)==0);
            assert(process(statusHandle,source.data(),destination.data(),32)==-ENODATA);
            assert(get(statusHandle,131083)==frames); // Dry bypass never reports processed audio.
        }
        auto invalid=config(768001);
        assert(cmd(statusHandle,EFFECT_CMD_SET_CONFIG,sizeof(invalid),&invalid)==-EINVAL);
        assert(get(statusHandle,131082)==192000 && get(statusHandle,131083)==frames);
        assert(set(statusHandle,131082,48000)!=0 && set(statusHandle,131083,0)!=0);
        assert(get(statusHandle,131082)==192000 && get(statusHandle,131083)==frames);
        AELI.release_effect(statusHandle);
        std::puts("Host runtime diagnostics passed: real 44.1/48/96/192 kHz, no default/internal-rate substitution, processed-only frame count.");
    }
    effect_descriptor_t descriptor{};
    assert(AELI.get_descriptor(&uuid,&descriptor)==0);
    // Android AudioPolicyManager rejects even a correctly created/configured
    // engine when its declared memory exceeds the legacy 512 KB global budget.
    assert(descriptor.memoryUsage>0 && descriptor.memoryUsage<512);
    effect_handle_t h=nullptr;
    effect_uuid_t bad=uuid;bad.timeLow=0;
    assert(AELI.create_effect(&bad,0,0,&h)==-ENOENT);
    assert(AELI.create_effect(&uuid,0,0,&h)==0 && h);
    effect_descriptor_t instanceDescriptor{};
    assert((*h)->get_descriptor(h,&instanceDescriptor)==0);
    assert(instanceDescriptor.memoryUsage==descriptor.memoryUsage);
    assert(get(h,131080)==5 && get(h,131081)==2147483647);
    assert(get(h,20002)>0 && get(h,20001)==48000);
    assert(set(h,131072,0)==0 && set(h,131073,0)==0);
    assert(cmd(h,EFFECT_CMD_ENABLE)==0);
    std::array<float,2054> input{},output{};
    for(size_t i=0;i<input.size();++i) input[i]=std::sin(i*.023f)*.2f;
    assert(process(h,input.data(),output.data(),input.size()/2)==0);
    assert(input==output); // Multiple chunks, odd final frame count, exact dry bypass.
    assert(process(h,input.data(),input.data(),input.size()/2)==0);
    assert(input==output); // In-place buffers.

    // Verify the complete Android parameter bridge reaches the real RACE
    // processor; an accepted UI packet alone must not count as an audio test.
    std::array<int32_t,6> surround{1,1,200,9000,5500,35};
    assert(setRaw(h,196609,surround.data(),sizeof(surround))==0);
    std::array<float,4096> surroundInput{},surroundOutput{};
    assert(process(h,surroundInput.data(),surroundOutput.data(),2048)==0);
    surroundInput[0]=.05f;
    assert(process(h,surroundInput.data(),surroundOutput.data(),2048)==0);
    double oppositeEnergy=0;
    for(size_t i=1;i<surroundOutput.size();i+=2) oppositeEnergy+=double(surroundOutput[i])*surroundOutput[i];
    assert(oppositeEnergy>1e-10);
    assert(setRaw(h,196609,surround.data(),sizeof(surround)-4)==-EINVAL);
    auto invalidSurround=surround;invalidSurround[4]=0;
    assert(setRaw(h,196609,invalidSurround.data(),sizeof(invalidSurround))==-EINVAL);
    surround[1]=0;assert(setRaw(h,196609,surround.data(),sizeof(surround))==0);
    surroundInput.fill(0);assert(process(h,surroundInput.data(),surroundOutput.data(),2048)==0);
    assert(process(h,input.data(),output.data(),input.size()/2)==0 && input==output);
    std::puts("RACE parameter bridge passed: real cross-channel response, invalid packet rejection, exact disabled bypass.");

    auto c=config(44100,AUDIO_FORMAT_PCM_FLOAT,true);
    assert(cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(c),&c)==0);
    output.fill(.125f);
    assert(process(h,input.data(),output.data(),input.size()/2)==0);
    for(size_t i=0;i<input.size();++i) assert(std::abs(output[i]-(input[i]+.125f))<1e-6f);
    // Full-scale PCM conversion must be exact on the explicit disabled path.
    // Active output intentionally reserves headroom for the final limiter;
    // its protected float/integer conversion is checked separately.
    assert(cmd(h,EFFECT_CMD_DISABLE)==0);
    c=config(48000,AUDIO_FORMAT_PCM_16_BIT);
    assert(cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(c),&c)==0);
    std::array<int16_t,8> pcm{-32768,32767,-12345,12345,0,1,-1,0},pcmOut{};
    assert(process(h,pcm.data(),pcmOut.data(),4)==-ENODATA && pcm==pcmOut);
    c=config(48000,AUDIO_FORMAT_PCM_24_BIT_PACKED);
    assert(cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(c),&c)==0);
    std::array<uint8_t,12> packed{0,0,128,255,255,127,0,0,0,1,0,0},packedOut{};
    assert(process(h,packed.data(),packedOut.data(),2)==-ENODATA && packed==packedOut);
    for(uint8_t format:{uint8_t(AUDIO_FORMAT_PCM_32_BIT),uint8_t(AUDIO_FORMAT_PCM_8_24_BIT)}) {
        c=config(48000,format); assert(cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(c),&c)==0);
        std::array<int32_t,4> wide{0,65536,-65536,1048576},wideOut{};
        assert(process(h,wide.data(),wideOut.data(),2)==-ENODATA && wide==wideOut);
    }
    assert(cmd(h,EFFECT_CMD_RESET)==0 && cmd(h,EFFECT_CMD_ENABLE)==0);

    c=config(48000); assert(cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(c),&c)==0);
    c.outputCfg.samplingRate=44100; assert(cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(c),&c)==-EINVAL);
    assert(get(h,20001)==48000); // Rejected config does not alter current rate.
    c=config(48000); c.inputCfg.channels=1; assert(cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(c),&c)==-EINVAL);
    assert((*h)->command(h,EFFECT_CMD_SET_PARAM,0,nullptr,nullptr,nullptr)==-EINVAL);
    std::array<uint32_t,4> truncated{0,4,0xffffffffu,65560}; int reply=0;uint32_t size=4;
    assert((*h)->command(h,EFFECT_CMD_SET_PARAM,16,truncated.data(),&size,&reply)==-EINVAL);
    assert(set(h,65560,101)==-EINVAL);
    assert(set(h,65544,1)==-ENOSYS); // Incomplete upstream VHE must not report success.
    assert(set(h,65565,1)==-ENOSYS);
    std::array<int32_t,2> badEq{10,0};assert(setRaw(h,65552,badEq.data(),8)==-ENOSYS);
    std::array<float,3> nanLimiter{0,std::numeric_limits<float>::quiet_NaN(),0};
    assert(setRaw(h,1500,nanLimiter.data(),12)==-EINVAL);
    std::array<int32_t,4> badIr{1,0,0,1};assert(setRaw(h,9999,badIr.data(),16)==-EINVAL);
    int16_t one=1;assert(setRaw(h,10004,&one,2)==-EINVAL);
    std::array<float,4096> ir{};assert(setRaw(h,12000,ir.data(),sizeof(ir))==-EINVAL);

    // Exercise real upstream reverb and its parameter bridge, not a mock.
    assert(set(h,131073,1)==0);
    assert(set(h,65560,70)==0 && set(h,65561,80)==0 && set(h,65562,40)==0);
    assert(set(h,65563,50)==0 && set(h,65564,0)==0 && set(h,65559,1)==0);
    std::array<float,1024> impulse{},tail{};impulse[0]=impulse[1]=.1f;
    double lateEnergy=0;
    for(int block=0;block<32;++block) {
        assert(process(h,impulse.data(),tail.data(),512)==0);
        for(float sample:tail) { assert(std::isfinite(sample)); if(block>4) lateEnergy+=sample*sample; }
        impulse.fill(0);
    }
    assert(lateEnergy>1e-9);
    assert(set(h,65559,0)==0);
    assert(set(h,65576,70)==0 && set(h,65577,200)==0 && set(h,65575,1)==0);
    assert(set(h,65550,56)==0 && set(h,65558,500)==0 && set(h,65580,100)==0);
    const std::array<uint32_t,10> effects{65548,65553,65557,65569,65574,65578,65581,65583,65584,65559};
    for(auto effect:effects) {
        assert(set(h,effect,1)==0);
        for(int block=0;block<6;++block) {
            for(size_t i=0;i<impulse.size();++i) impulse[i]=std::sin((block*1024+i)*.021f)*.05f;
            assert(process(h,impulse.data(),tail.data(),512)==0);
            for(float sample:tail) assert(std::isfinite(sample) && std::abs(sample)<=1);
        }
        assert(set(h,effect,0)==0);
    }
    assert(set(h,131073,0)==0 && set(h,131072,1)==0);
    assert(setRaw(h,1203,&one,2)==0); // Original James reverb enable protocol remains compatible.
    for(uint32_t rate:{44100u,48000u,96000u}) {
        c=config(rate); assert(cmd(h,EFFECT_CMD_SET_CONFIG,sizeof(c),&c)==0);
        // The James query must report the real native rate, including HD PCM.
        assert(get(h,20001)==static_cast<int>(rate));
        effect_config_t actual{}; uint32_t configSize=sizeof(actual);
        assert((*h)->command(h,EFFECT_CMD_GET_CONFIG,0,nullptr,&configSize,&actual)==0);
        assert(actual.inputCfg.samplingRate==rate && actual.outputCfg.samplingRate==rate);
        assert(process(h,impulse.data(),tail.data(),512)==0);
        for(float sample:tail) assert(std::isfinite(sample));
    }
    assert(set(h,131072,0)==0);
    std::array<int32_t,11> tone{1,70,50,50,20,40,30,-20,-30,40,50};
    assert(setRaw(h,196608,tone.data(),sizeof(tone))==0);
    for(size_t i=0;i<impulse.size();++i) impulse[i]=std::sin(i*.12f)*.1f;
    assert(process(h,impulse.data(),tail.data(),512)==0);
    assert(impulse!=tail);
    for(float sample:tail) assert(std::isfinite(sample));
    assert(cmd(h,EFFECT_CMD_DISABLE)==0);
    assert(process(h,impulse.data(),tail.data(),512)==-ENODATA && impulse==tail);
    assert(AELI.release_effect(h)==0);
    std::puts("Fusion integration passed: lifecycle, protocols, five PCM formats supported, bypass, accumulation, invalid packets, real reverb, James/Tone stages.");
}
