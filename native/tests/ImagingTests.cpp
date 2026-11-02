#include <algorithm>
#include <array>
#include <cassert>
#include <cmath>
#include <cstdio>
#include <vector>
#include "ImagingReference.h"
#include "../viper/src/viper/effects/ColorfulMusic.h"

namespace {
constexpr double pi=3.14159265358979323846;
using Settings=std::array<int32_t,13>;
const Settings legacy{0,1000,0,0,150,16000,0,120,1000,1,1000,0,0};
const Settings neutral{1,1000,0,0,150,16000,0,120,1000,0,1000,0,0};
constexpr int32_t invalid=ImagingMeter::invalid;
std::vector<float> signal(unsigned rate,unsigned frames,double frequency=1000,float amplitude=.5f,int kind=0) {
    std::vector<float> data(frames*2);
    for(unsigned i=0;i<frames;++i) {
        const float value=amplitude*std::sin(2*pi*frequency*i/rate);
        data[2*i]=value;
        data[2*i+1]=kind==0?value:kind==1?-value:kind==2?0.f:amplitude*std::sin(2*pi*(frequency*2)*i/rate);
    }
    return data;
}
std::vector<float> noise(unsigned frames) {
    std::vector<float> data(frames*2);uint32_t seed=19;
    for(float& value:data){seed=1664525u*seed+1013904223u;value=(int32_t(seed>>8)-8388608)/10485760.f;}
    return data;
}
void render(ColorfulMusic& effect,std::vector<float>& data,unsigned block=127) {
    for(size_t pos=0;pos<data.size()/2;pos+=block)effect.Process(data.data()+pos*2,std::min<size_t>(block,data.size()/2-pos));
}
void configure(ColorfulMusic& effect,unsigned rate,const Settings& settings=neutral,int depth=200,float widen=0,float middle=1.5f) {
    effect.SetSamplingRate(rate);effect.SetDepthValue(depth);effect.SetWidenValue(widen);effect.SetMidImageValue(middle);
    assert(effect.SetAdvanced(settings.data(),settings.size()));effect.SetEnable(true);
}
double power(const std::vector<float>& data,unsigned start=0,bool mid=false) {
    double sum=0;
    for(size_t i=start*2;i<data.size();i+=2){const double value=mid?(double(data[i])+data[i+1])*.5:data[i];assert(std::isfinite(value));sum+=value*value;}
    return sum/(data.size()/2-start);
}
void legacyDefaults() {
    for(unsigned rate:{8000u,11025u,44100u,48000u,96000u,192000u})for(int depth:{0,200,499,500,800})for(float widen:{0.f,.73f,1.f})for(float middle:{1.f,1.5f,2.f}) {
        ColorfulMusic effect;configure(effect,rate,legacy,depth,widen,middle);
        ImagingReference reference(rate,widen,middle,depth);
        auto expected=noise(4097),actual=expected;reference.Process(expected.data(),4097);render(effect,actual,73);
        assert(expected==actual);
    }
    for(unsigned rate:{8000u,44100u,48000u,192000u}) {
        ColorfulMusic effect;configure(effect,rate);auto input=noise(10000),actual=input;render(effect,actual);assert(input==actual);
        effect.SetEnable(false);render(effect,actual);assert(input==actual);
    }
    std::puts("Imaging legacy oracle: all 270 rate/depth/matrix combinations match frozen 06f2560 sample-for-sample; neutral professional and bypass preserve exact input.");
}
void matrixAndBands() {
    for(int width:{0,250,1000,1500,2000})for(int trim:{-120,0,60}) {
        auto values=neutral;values[1]=width;values[2]=trim;ColorfulMusic effect;configure(effect,48000,values);
        auto data=noise(10000),original=data;render(effect,data);
        const float gain=std::pow(10.f,trim/200.f);
        for(size_t i=0;i<data.size();i+=2) {
            const float m=(original[i]+original[i+1])*.5f*gain,s=(original[i]-original[i+1])*.5f*(width/1000.f);
            assert(std::abs(data[i]-(m+s))<8.e-7f&&std::abs(data[i+1]-(m-s))<8.e-7f);
        }
    }
    for(unsigned rate:{8000u,44100u,48000u,96000u,192000u})for(int width:{0,500,1000,1500,2000})for(bool focus:{false,true}) {
        auto values=neutral;values[1]=width;values[3]=1;values[4]=500;values[5]=6000;values[6]=focus;values[7]=120;values[8]=0;
        for(double frequency:{32.,128.,500.,1000.,3000.,6000.,17000.}) {
            if(frequency>rate*.45)continue;
            ColorfulMusic effect;configure(effect,rate,values);
            auto data=signal(rate,rate/2,frequency,.4f,1);const double before=power(data,rate/4);render(effect,data);
            const double ratio=std::sqrt(power(data,rate/4)/before);
            assert(ratio<=std::max(1.,width/1000.)+.002);
            if(!focus)assert(ratio>=std::min(1.,width/1000.)-.002);
            for(size_t i=0;i<data.size();i+=2)assert(data[i]==-data[i+1]);
        }
        ColorfulMusic effect;configure(effect,rate,values);auto mono=signal(rate,10000,500);const auto original=mono;render(effect,mono);assert(mono==original);
    }
    auto values=neutral;values[1]=2000;values[3]=1;values[4]=500;values[5]=6000;values[6]=1;values[8]=0;
    ColorfulMusic effect;configure(effect,48000,values);auto data=noise(24000),input=data;render(effect,data);
    for(size_t i=0;i<data.size();i+=2)assert(std::abs((data[i]+data[i+1])-(input[i]+input[i+1]))<4.e-7f);
    // Frequency edges are soft: they change the selected effect without filtering Mid.
    const auto gainFor=[](Settings setting,double frequency) {
        ColorfulMusic effect;configure(effect,48000,setting);auto data=signal(48000,24000,frequency,.4f,1);double before=power(data,12000);render(effect,data);return power(data,12000)/before;
    };
    auto a=neutral;a[1]=2000;a[3]=1;auto b=a;b[4]=2000;assert(std::abs(gainFor(a,500)-gainFor(b,500))>.1);
    b=a;b[5]=2000;assert(std::abs(gainFor(a,8000)-gainFor(b,8000))>.1);
    a=neutral;a[6]=1;a[8]=0;b=a;b[7]=500;assert(std::abs(gainFor(a,200)-gainFor(b,200))>.1);
    b=a;b[8]=500;assert(gainFor(b,40)>gainFor(a,40)+.1);
    std::puts("Imaging transfer: width/Mid are independent, complementary band/focus stay inside endpoint gains without edge boost, mono sum is preserved, all frequency controls affect audio.");
}
void depthAndTrim() {
    const auto result=[](Settings settings) {ColorfulMusic effect;configure(effect,48000,settings);auto data=noise(10000);render(effect,data);return data;};
    auto dry=neutral,wet=neutral,half=neutral;wet[9]=half[9]=1;half[10]=500;
    auto a=result(dry),b=result(wet),c=result(half);double difference=0;
    for(size_t i=0;i<a.size();++i){assert(std::abs(c[i]-(a[i]+.5f*(b[i]-a[i])))<4.e-7f);difference+=std::abs(b[i]-a[i]);}
    assert(difference>100);
    auto trim=neutral;trim[11]=-60;const auto reduced=result(trim);const float amplitude=std::pow(10.f,-6.f/20.f);
    for(size_t i=0;i<a.size();++i)assert(reduced[i]==a[i]*amplitude);
    ColorfulMusic effect;configure(effect,48000,legacy,499);auto warm=noise(5000);render(effect,warm);
    auto reference=effect;effect.SetDepthValue(500);float actual[2]={.4f,-.3f},expected[2]={.4f,-.3f};effect.Process(actual,1);reference.Process(expected,1);
    assert(std::abs(actual[0]-expected[0])<.002f&&std::abs(actual[1]-expected[1])<.002f);
    std::puts("Imaging depth: separate bypass/mix and output trim change audio as specified; 499-to-500 feedback polarity fades without an abrupt branch reversal.");
}
void metersAndAudition() {
    for(int kind:{0,1,2,3}) {
        ColorfulMusic effect;configure(effect,48000);auto data=signal(48000,4800,1000,.5f,kind);
        effect.Process(data.data(),4799);assert(effect.GetImaging(8)==0&&effect.GetImaging(3)==invalid);
        effect.Process(data.data()+9598,1);assert(effect.GetImaging(8)==1);
        assert(effect.GetImaging(3)==(kind==0?10000:kind==1?-10000:kind==2?invalid:0));
        assert(std::abs(effect.GetImaging(6)+602)<=1);
        if(kind==0)assert(std::abs(effect.GetImaging(4)+903)<=1&&effect.GetImaging(5)==invalid);
        if(kind==1)assert(effect.GetImaging(4)==invalid&&std::abs(effect.GetImaging(5)+903)<=1);
        if(kind==2)assert(std::abs(effect.GetImaging(4)+1505)<=1&&std::abs(effect.GetImaging(5)+1505)<=1);
    }
    ColorfulMusic effect;configure(effect,48000);std::vector<float> zero(9600);render(effect,zero);
    assert(effect.GetImaging(8)==1);for(unsigned i=3;i<7;++i)assert(effect.GetImaging(i)==invalid);
    auto dc=zero;std::fill(dc.begin(),dc.end(),.5f);render(effect,dc);assert(effect.GetImaging(3)==invalid&&effect.GetImaging(4)==-602);
    auto values=neutral;
    for(int request:{1,2,3}) {
        ColorfulMusic inspected,reference;configure(inspected,48000);configure(reference,48000);
        values[12]=request;assert(inspected.SetAdvanced(values.data(),13));
        auto data=signal(48000,48000,1000,.5f,2),original=data,untouched=data;render(inspected,data);render(reference,untouched);
        for(unsigned i=3;i<7;++i)assert(inspected.GetImaging(i)==reference.GetImaging(i));
        for(size_t i=72000;i<data.size();i+=2) {const float mid=original[i]*.5f;assert(std::abs(data[i]-mid)<1.e-6f);assert(std::abs(data[i+1]-(request==3?-mid:mid))<1.e-6f);}
        inspected.ClearImagingRuntime();assert(inspected.GetImaging(7)==0&&inspected.GetImaging(8)==0);
        assert(inspected.SetAdvanced(values.data(),13)&&inspected.GetImaging(7)==0);
        values[12]=0;assert(inspected.SetAdvanced(values.data(),13));values[12]=request;assert(inspected.SetAdvanced(values.data(),13)&&inspected.GetImaging(7)==request);
        values[12]=request==3?1:3;assert(inspected.SetAdvanced(values.data(),13)&&inspected.GetImaging(7)==values[12]);
        inspected.Reset();assert(inspected.SetAdvanced(values.data(),13)&&inspected.GetImaging(7)==0);
        inspected.SetEnable(false);for(unsigned i=3;i<7;++i)assert(inspected.GetImaging(i)==invalid);
        values[12]=0;assert(inspected.SetAdvanced(values.data(),13));values[12]=1;assert(inspected.SetAdvanced(values.data(),13)&&inspected.GetImaging(7)==0);
        inspected.SetEnable(true);assert(inspected.SetAdvanced(values.data(),13)&&inspected.GetImaging(7)==0);
    }
    std::puts("Imaging meters: 100ms window, Pearson correlation, mono/Side/RMS/sample-peak scaling and silent/one-sided invalid states; pre-listen metering and lifecycle edge latch verified.");
}
std::vector<float> changing(unsigned rate,unsigned block) {
    ColorfulMusic effect;configure(effect,rate,legacy);auto data=noise(18000);Settings settings=legacy;
    for(size_t pos=0;pos<data.size()/2;) {
        if(pos%1000==0) {
            const int step=pos/1000;settings[0]=step%2;settings[1]=(step%3)*1000;settings[2]=step%2?60:-120;
            settings[3]=step%2;settings[4]=step%2?100:1999;settings[5]=step%2?20000:2000;
            settings[6]=(step/2)%2;settings[7]=step%2?40:500;settings[8]=step%2?0:1000;
            settings[9]=step%2;settings[10]=step%3?500:1000;settings[11]=step%2?0:-120;
            assert(effect.SetAdvanced(settings.data(),13));effect.SetDepthValue(step%2?499:500);effect.SetWidenValue(step%2?0.f:1.f);effect.SetMidImageValue(step%2?1.f:2.f);
        }
        const unsigned count=std::min<size_t>({block,1000-pos%1000,data.size()/2-pos});
        effect.Process(data.data()+2*pos,count);pos+=count;
    }
    for(float value:data)assert(std::isfinite(value));return data;
}
void continuityAndValidation() {
    for(unsigned rate:{8000u,11025u,44100u,48000u,96000u,192000u})assert(changing(rate,1)==changing(rate,509));
    ColorfulMusic effect,reference;configure(effect,48000);configure(reference,48000);auto a=noise(10000),b=a;
    render(reference,b);for(unsigned pos=0;pos<10000;pos+=100){assert(effect.SetAdvanced(neutral.data(),13));effect.Process(a.data()+2*pos,100);}assert(a==b);
    const int32_t lo[13]={0,0,-120,0,100,2000,0,40,0,0,0,-120,0},hi[13]={1,2000,60,1,2000,20000,1,500,1000,1,1000,0,3};
    for(unsigned i=0;i<13;++i)for(int32_t invalidValue:{lo[i]-1,hi[i]+1}) {auto values=neutral;values[i]=invalidValue;assert(!effect.SetAdvanced(values.data(),13));}
    auto values=neutral;values[4]=values[5]=2000;assert(!effect.SetAdvanced(values.data(),13));
    assert(!effect.SetAdvanced(nullptr,13)&&!effect.SetAdvanced(neutral.data(),12)&&!effect.SetAdvanced(neutral.data(),14));
    for(unsigned rate:{8000u,11025u,44100u,48000u,96000u,192000u}) {
        ColorfulMusic stressed;configure(stressed,rate,legacy,800,1.f,2.f);
        for(unsigned edit=0;edit<200;++edit) {
            auto settings=neutral;settings[0]=edit%2;settings[1]=edit%2?0:2000;settings[2]=edit%2?-120:60;
            settings[3]=edit%2;settings[4]=edit%2?100:1999;settings[5]=edit%2?20000:2000;
            settings[6]=edit%2;settings[7]=edit%2?40:500;settings[8]=edit%2?0:1000;settings[9]=edit%2;
            assert(stressed.SetAdvanced(settings.data(),13));stressed.SetDepthValue(edit%3==0?0:edit%3==1?499:800);
            auto data=noise(31+edit%211);
            for(size_t i=0;i<data.size();++i)if(edit%3!=2)data[i]=edit%3==0?1.f:(i%2?1.f:-1.f);
            render(stressed,data,17);for(float sample:data)assert(std::isfinite(sample)&&std::abs(sample)<32.f);
        }
    }
    std::puts("Imaging live state: all controls and feedback boundary edits are block-independent at six sample rates; repeated packets preserve state; malformed/range/order packets reject atomically.");
}
}
int main(){legacyDefaults();matrixAndBands();depthAndTrim();metersAndAudition();continuityAndValidation();}
