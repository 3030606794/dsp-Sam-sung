#include <algorithm>
#include <array>
#include <cassert>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <filesystem>
#include <string>
#include <vector>
#include "../viper/src/viper/effects/ViPERClarity.h"
#include "../viper/src/viper/effects/SpectrumExtend.h"
#include "../viper/src/viper/utils/TopEndControls.h"
#include "../viper/src/viper/utils/OversampledHarmonics.h"

namespace {
constexpr double pi=3.14159265358979323846;
const std::array<int32_t,11> clarityDefaults{0,8250,100,120,1200,0,0,0,0,5000,2500};
const std::array<int32_t,7> spectrumDefaults{560,7600,717,0,717,0,0};
const std::array<int32_t,4> selectiveDefaults{0,3000,12000,707},nonlinearDefaults{0,0,100,1};
const std::array<int32_t,8> dynamicDefaults{0,6000,707,-180,40,60,50,120};
const std::array<int32_t,9> deessDefaults{0,4500,10000,707,-240,60,60,10,80};
std::vector<float> tone(unsigned rate,unsigned frames,double frequency,float amplitude=.2f,size_t position=0) {
    std::vector<float> result(frames*2);
    for(unsigned i=0;i<frames;++i)result[i*2]=result[i*2+1]=amplitude*std::sin(2*pi*frequency*(i+position)/rate);
    return result;
}
template<class Effect> void render(Effect& effect,std::vector<float>& data,unsigned block=127) {
    for(size_t pos=0;pos<data.size()/2;pos+=block)effect.Process(data.data()+2*pos,std::min<size_t>(block,data.size()/2-pos));
}
double energy(const std::vector<float>& data,size_t start=0,unsigned channel=0) {
    double sum=0;size_t count=0;
    for(size_t i=start*2+channel;i<data.size();i+=2) {assert(std::isfinite(data[i]));sum+=double(data[i])*data[i];++count;}
    return sum/std::max<size_t>(count,1);
}
double component(const std::vector<float>& data,unsigned rate,double frequency,size_t start=0) {
    double re=0,im=0;const size_t frames=data.size()/2-start;
    for(size_t i=0;i<frames;++i) {const double phase=2*pi*frequency*i/rate;re+=data[2*(start+i)]*std::cos(phase);im+=data[2*(start+i)]*std::sin(phase);}
    return (re*re+im*im)/(double(frames)*frames);
}
void configure(ViPERClarity& clarity,unsigned rate,int mode=1,int amount=0) {
    auto values=clarityDefaults;values[0]=amount;
    clarity.SetSamplingRate(rate);clarity.SetProcessMode(static_cast<ViPERClarity::ClarityMode>(mode));
    assert(clarity.SetAdvanced(values.data(),values.size()));clarity.SetEnable(true);
}
void configure(SpectrumExtend& spectrum,unsigned rate,const std::array<int32_t,4>& nonlinear,bool wet=false,int amount=560) {
    auto values=spectrumDefaults;values[0]=amount;values[1]=1000;
    spectrum.SetSamplingRate(rate);assert(spectrum.SetAdvanced(values.data(),values.size()));
    assert(spectrum.SetNonlinear(nonlinear.data(),nonlinear.size()));spectrum.SetEnable(true);
    if(wet){values[6]=1;assert(spectrum.SetAdvanced(values.data(),values.size()));}
}
void defaultAndContinuity() {
    for(unsigned rate:{8000u,44100u,48000u,96000u,192000u}) {
        auto input=tone(rate,8000,rate*.17,.4f);
        for(int mode=0;mode<3;++mode) {
            ViPERClarity untouched,explicitDefaults;configure(untouched,rate,mode,200);configure(explicitDefaults,rate,mode,200);
            assert(explicitDefaults.SetSelective(selectiveDefaults.data(),4));assert(explicitDefaults.SetDynamic(dynamicDefaults.data(),8));assert(explicitDefaults.SetDeess(deessDefaults.data(),9));
            auto a=input,b=input;render(untouched,a,97);render(explicitDefaults,b,511);assert(a==b);
        }
        SpectrumExtend untouched,explicitDefaults;configure(untouched,rate,nonlinearDefaults);configure(explicitDefaults,rate,nonlinearDefaults);
        auto a=input,b=input;render(untouched,a,97);
        for(size_t pos=0;pos<b.size()/2;pos+=97) {assert(explicitDefaults.SetNonlinear(nonlinearDefaults.data(),4));explicitDefaults.Process(b.data()+2*pos,std::min<size_t>(97,b.size()/2-pos));}
        assert(a==b);
    }
    // Every new family changes at the same exact sample; splitting calls does not alter DSP time.
    for(unsigned rate:{8000u,44100u,48000u,192000u}) for(unsigned block:{1u,509u}) {
        static std::vector<float> expected;
        auto input=tone(rate,20000,rate*.21,.7f);ViPERClarity c;SpectrumExtend s;configure(c,rate,0,200);configure(s,rate,nonlinearDefaults);
        for(size_t pos=0;pos<input.size()/2;) {
            if(pos==3000) {
                const int32_t selected[4]={1,2000,14000,1500},nonlinear[4]={120,70,50,4};
                const int32_t dynamic[8]={1,4000,1500,-400,80,90,10,80},deess[9]={1,3000,9000,1500,-400,90,100,10,50};
                assert(c.SetSelective(selected,4)&&c.SetDynamic(dynamic,8)&&c.SetDeess(deess,9)&&s.SetNonlinear(nonlinear,4));
            }
            if(pos==11000)assert(s.SetNonlinear(nonlinearDefaults.data(),4));
            const size_t end=pos<3000?3000:pos<11000?11000:input.size()/2;
            const unsigned count=std::min<size_t>(block,end-pos);
            c.Process(input.data()+2*pos,count);s.Process(input.data()+2*pos,count);pos+=count;
        }
        for(float value:input)assert(std::isfinite(value));
        if(block==1)expected=input;else assert(input==expected);
    }
    std::puts("TopEnd2 defaults and continuity: explicit default packets preserve prior samples; repeated packets and live edits are independent of block splitting.");
}
void selectiveResponse() {
    const int32_t selected[4]={1,3000,12000,707};
    for(double frequency:{300.,7000.,18000.}) {
        ViPERClarity base,full,limited;configure(base,48000,0,0);configure(full,48000,0,200);configure(limited,48000,0,200);
        assert(limited.SetSelective(selected,4));
        auto a=tone(48000,24000,frequency),b=a,c=a;render(base,a);render(full,b);render(limited,c);
        double all=0,band=0;
        for(size_t i=24000;i<a.size();++i){all+=std::pow(b[i]-a[i],2);band+=std::pow(c[i]-a[i],2);}
        const double ratio=band/all;
        if(frequency==300)assert(ratio<.002);
        if(frequency==7000)assert(ratio>.2);
        if(frequency==18000)assert(ratio<.2);
    }
    ViPERClarity invalidBand;configure(invalidBand,8000,0,200);const int32_t outside[4]={1,12000,20000,707};
    assert(invalidBand.SetSelective(outside,4)&&invalidBand.GetTopEnd(0)==0&&invalidBand.GetTopEnd(1)==0);
    std::puts("TopEnd2 selective sharpening: the original difference increment is frequency-selective; empty Nyquist-clamped bands report zero.");
}
void collapsingBands() {
    constexpr unsigned rate=22050;
    const int32_t validSelection[4]={1,10000,20000,707},collapsedSelection[4]={1,12000,20000,707};
    const int32_t validDeess[9]={1,10000,20000,707,-600,200,180,10,80},collapsedDeess[9]={1,12000,20000,707,-600,200,180,10,80};
    SelectiveSharpening selected;selected.Set(validSelection);selected.Configure(rate);
    DynamicTreble deess(true);deess.Set(validDeess);deess.Configure(rate);
    for(unsigned i=0;i<rate;++i){float input[2]={float(.8*std::cos(2*pi*10500*i/rate)),0},out[2];selected.Process(input,out);deess.Process(input);}
    // Copy the warmed state to distinguish a parameter boundary from the audio waveform.
    auto referenceSelection=selected;auto referenceDeess=deess;
    selected.Set(collapsedSelection);deess.Set(collapsedDeess);
    float input[2]={.8f,.8f},selectedOut[2],referenceOut[2],actual[2]={.8f,.8f},expected[2]={.8f,.8f};
    selected.Process(input,selectedOut);referenceSelection.Process(input,referenceOut);
    deess.Process(actual);referenceDeess.Process(expected);
    assert(std::abs(selectedOut[0]-referenceOut[0])<.01f);
    assert(std::abs(actual[0]-expected[0])<.01f);
    assert(deess.Frequency()==0&&deess.Reduction()>0); // The meter reports the still-audible fade.
    for(unsigned i=0;i<rate;++i){float frame[2]={float(.8*std::cos(2*pi*10500*i/rate)),0},out[2];selected.Process(frame,out);deess.Process(frame);assert(std::isfinite(frame[0])&&std::isfinite(out[0]));}
    assert(selected.Frequency(0)==0&&deess.Frequency()==0&&deess.Reduction()==0);
    referenceSelection=selected;referenceDeess=deess;
    selected.Set(validSelection);deess.Set(validDeess);
    actual[0]=actual[1]=expected[0]=expected[1]=.8f;
    selected.Process(input,selectedOut);referenceSelection.Process(input,referenceOut);
    deess.Process(actual);referenceDeess.Process(expected);
    assert(std::abs(selectedOut[0]-referenceOut[0])<.01f&&std::abs(actual[0]-expected[0])<.01f);
    std::puts("TopEnd2 Nyquist boundary: valid/collapsed band changes fade in both directions without a one-sample branch or gain jump.");
}
double dynamicGain(bool deess,int q,double frequency,float maximum=6) {
    DynamicTreble effect(deess);
    int32_t d[8]={1,6000,q,-600,200,static_cast<int32_t>(maximum*10),10,120};
    int32_t e[9]={1,4500,10000,q,-600,200,static_cast<int32_t>(maximum*10),10,80};
    effect.Set(deess?e:d);effect.Configure(48000);
    auto data=tone(48000,19200,frequency,.8f);const double input=energy(data,14400);
    for(size_t i=0;i<data.size();i+=2)effect.Process(data.data()+i);
    return 10*std::log10(energy(data,14400)/input);
}
void dynamicsResponse() {
    for(bool deess:{false,true}) for(int q:{300,707,1500})for(float max:{6.f,18.f}) {
        for(double frequency:{100.,1000.,3000.,4500.,6000.,6708.,10000.,16000.,20000.}) {
            const double gain=dynamicGain(deess,q,frequency,max);
            if(gain>.15 || gain< -max-.3)std::fprintf(stderr,"dynamic sweep invalid deess=%d q=%d f=%.0f max=%.0f gain=%.4f\n",deess,q,frequency,max,gain);
            assert(gain<=.15&&gain>=-max-.3);
        }
    }
    assert(dynamicGain(false,707,100)>-.2&&dynamicGain(false,707,16000)<-4);
    assert(dynamicGain(true,707,1000)>-.8&&dynamicGain(true,707,6708)<-5.5);
    DynamicTreble linked;int32_t d[8]={1,6000,707,-180,100,60,10,80};linked.Set(d);linked.Configure(48000);
    auto data=tone(48000,24000,10000,.8f);
    for(size_t i=0;i<data.size();i+=2)data[i]/=16;
    const double before=energy(data,20000,0);
    for(size_t i=0;i<data.size();i+=2)linked.Process(data.data()+i);
    assert(energy(data,20000,0)<before*.4);
    for(size_t i=40000;i<data.size();i+=2)assert(std::abs(data[i]*16-data[i+1])<1.e-5);
    DynamicTreble fast,slow;d[3]=-300;d[5]=120;d[6]=10;d[7]=5;fast.Set(d);fast.Configure(48000);
    d[6]=1000;d[7]=1000;slow.Set(d);slow.Configure(48000);
    auto burst=tone(48000,480,10000,.8f);
    for(size_t i=0;i<burst.size();i+=2){float a[2]={burst[i],burst[i+1]},b[2]={a[0],a[1]};fast.Process(a);slow.Process(b);}
    assert(fast.Reduction()>slow.Reduction()+200);
    auto longBurst=tone(48000,48000,10000,.8f);
    for(size_t i=0;i<longBurst.size();i+=2){float a[2]={longBurst[i],longBurst[i+1]},b[2]={a[0],a[1]};fast.Process(a);slow.Process(b);}
    for(unsigned i=0;i<4800;++i){float a[2]={},b[2]={};fast.Process(a);slow.Process(b);}
    assert(fast.Reduction()<10&&slow.Reduction()>600);
    // After a completed bypass fade, re-enabling on silence cannot revive old filter history.
    d[0]=0;slow.Set(d);for(unsigned i=0;i<48000;++i){float zero[2]={};slow.Process(zero);}
    d[0]=1;slow.Set(d);for(unsigned i=0;i<4800;++i){float zero[2]={};slow.Process(zero);assert(std::abs(zero[0])<1.e-9&&std::abs(zero[1])<1.e-9);}
    assert(slow.Reduction()==0);
    std::puts("TopEnd2 dynamics: sweep has no edge boost or excess cut, high shelf/bell are selective, stereo peak detector is linked, attack/release change timing, bypass clears stale state.");
}
void parameterResponse() {
    const auto selectedEnergy=[](std::array<int32_t,4> values) {
        SelectiveSharpening effect;effect.Set(values.data());effect.Configure(48000);
        auto data=tone(48000,12000,7000,.3f);
        for(size_t i=0;i<data.size();i+=2){float selected[2];effect.Process(data.data()+i,selected);data[i]=selected[0];data[i+1]=selected[1];}
        return energy(data,6000);
    };
    const std::array<int32_t,4> selection{1,3000,12000,707};const double selectedBase=selectedEnergy(selection);
    const int32_t changes[4]={0,6000,9000,300};
    for(unsigned i=0;i<4;++i){auto values=selection;values[i]=changes[i];assert(std::abs(selectedEnergy(values)-selectedBase)>1.e-4);}
    const auto gain=[](bool isDeess,const std::vector<int32_t>& parameters) {
        DynamicTreble effect(isDeess);effect.Set(parameters.data());effect.Configure(48000);
        auto data=tone(48000,24000,isDeess?7000:6000,.7f);const double before=energy(data,18000);
        for(size_t i=0;i<data.size();i+=2)effect.Process(data.data()+i);
        return 10*std::log10(energy(data,18000)/before);
    };
    for(bool deess:{false,true}) {
        const std::vector<int32_t> base=deess?std::vector<int32_t>{1,4500,10000,707,-120,40,180,10,80}:std::vector<int32_t>{1,6000,707,-120,40,180,10,80};
        const double baseline=gain(deess,base);assert(baseline<-.2);
        // All static controls must affect actual audio; timing controls are tested above.
        const std::vector<std::pair<unsigned,int32_t>> changes=deess?
            std::vector<std::pair<unsigned,int32_t>>{{0,0},{1,9000},{2,6000},{3,300},{4,0},{5,10},{6,0}}:
            std::vector<std::pair<unsigned,int32_t>>{{0,0},{1,12000},{2,300},{3,0},{4,10},{5,0}};
        for(const auto& change:changes){auto values=base;values[change.first]=change.second;assert(std::abs(gain(deess,values)-baseline)>.05);}
    }
    std::puts("TopEnd2 parameter response: selected-band enable/edges/Q and both dynamics enable/edges/detector-Q/threshold/ratio/maximum each produce measurable audio changes.");
}
void nonlinearResponse() {
    std::array<std::vector<float>,3> response;
    for(unsigned index=0;index<3;++index) {
        const std::array<int32_t,4> parameters{index==0?0:60,index==2?100:0,100,1};
        SpectrumExtend effect;configure(effect,48000,parameters,true);
        response[index]=tone(48000,48000,3000,.2f);render(effect,response[index]);
    }
    const double fundamental0=component(response[0],48000,3000,24000),fundamental1=component(response[1],48000,3000,24000);
    const double third0=component(response[0],48000,9000,24000)/fundamental0,third1=component(response[1],48000,9000,24000)/fundamental1;
    assert(std::abs(third1-third0)>.001); // Pre-shaper drive changes harmonic ratios, not just level.
    assert(component(response[2],48000,6000,24000)>component(response[1],48000,6000,24000)*100+1.e-10);
    SpectrumExtend linear;configure(linear,48000,{0,0,0,1},true);auto pure=tone(48000,48000,3000,.2f);render(linear,pure);
    assert(component(pure,48000,9000,24000)<component(response[0],48000,9000,24000)*.001);
    for(unsigned factor:{1u,2u,4u}) {
        SpectrumExtend effect;configure(effect,48000,{0,0,100,static_cast<int32_t>(factor)},false,0);
        std::vector<float> impulse(256);impulse[0]=impulse[1]=1;render(effect,impulse,1);
        const unsigned delay=factor==1?0:32;
        for(unsigned frame=0;frame<128;++frame)assert(impulse[2*frame]==(frame==delay?1.f:0.f));
        assert(effect.GetNonlinear(0)==static_cast<int>(factor)&&effect.GetNonlinear(1)==static_cast<int>(delay));
        OversampledHarmonics path(factor);path.Reset(0,0);
        double input[2]={},output[2];for(unsigned i=0;i<1000;++i)path.Process(input,output,1,0,0);
        unsigned peakAt=0;double peak=0;
        for(unsigned i=0;i<128;++i) {input[0]=input[1]=i==0?.01:0;path.Process(input,output,1,0,0);if(std::abs(output[0])>peak){peak=std::abs(output[0]);peakAt=i;}}
        assert(peakAt==delay); // Measured wet group delay agrees with dry compensation.
    }
    std::array<double,3> alias{};
    for(unsigned index=0;index<3;++index) {
        SpectrumExtend effect;configure(effect,48000,{120,50,100,1<<index},true);
        auto data=tone(48000,48000,13000,.75f);render(effect,data);
        // Legal baseband harmonics are only the fundamental for this 13 kHz input.
        for(double frequency:{4000.,5000.,9000.,17000.,21000.,22000.})alias[index]+=component(data,48000,frequency,24000);
        alias[index]/=component(data,48000,13000,24000);
    }
    std::printf("TopEnd2 alias probe normalized power: 1x %.8g, 2x %.8g, 4x %.8g\n",alias[0],alias[1],alias[2]);
    assert(alias[1]<alias[0]*.65&&alias[2]<alias[0]*.15&&alias[2]<alias[1]*.6);
    std::puts("TopEnd2 nonlinear: input drive changes shape, even/high-order controls affect harmonics, measured dry/wet FIR delay matches, 2x/4x suppress alias energy.");
}
void extremesAndPackets() {
    for(unsigned rate:{8000u,11025u,44100u,48000u,96000u,192000u}) {
        ViPERClarity clarity;configure(clarity,rate,0,450);SpectrumExtend spectrum;configure(spectrum,rate,nonlinearDefaults);
        uint32_t random=217;for(unsigned edit=0;edit<250;++edit) {
            const int32_t selected[4]={int32_t(edit%2),edit%2?1000:12000,20000,edit%2?300:1500};
            const int32_t shape[4]={edit%2?180:-120,int32_t(edit%3?100:0),int32_t(edit%3?100:0),edit%3==0?1:edit%3==1?2:4};
            const int32_t dynamic[8]={1,edit%2?1000:16000,1500,-600,200,180,1,5};
            const int32_t deess[9]={1,edit%2?2000:12000,20000,1500,-600,200,180,1,5};
            assert(clarity.SetSelective(selected,4)&&clarity.SetDynamic(dynamic,8)&&clarity.SetDeess(deess,9)&&spectrum.SetNonlinear(shape,4));
            const unsigned count=17+edit%239;std::vector<float> data(count*2);
            for(auto& value:data){random=1664525u*random+1013904223u;value=edit%3==0?1.f:edit%3==1?(random&1?1.f:-1.f):(int32_t(random>>8)-8388608)/8388608.f;}
            clarity.Process(data.data(),count);spectrum.Process(data.data(),count);
            for(float value:data)assert(std::isfinite(value));
        }
        assert(!clarity.SetSelective(nullptr,4)&&!clarity.SetDynamic(nullptr,8)&&!clarity.SetDeess(nullptr,9)&&!spectrum.SetNonlinear(nullptr,4));
        assert(!clarity.SetSelective(selectiveDefaults.data(),3)&&!clarity.SetDynamic(dynamicDefaults.data(),7)&&!clarity.SetDeess(deessDefaults.data(),8)&&!spectrum.SetNonlinear(nonlinearDefaults.data(),3));
        auto shape=nonlinearDefaults;shape[3]=3;assert(!spectrum.SetNonlinear(shape.data(),4));
        auto selection=selectiveDefaults;selection[1]=selection[2];assert(!clarity.SetSelective(selection.data(),4));
        auto deess=deessDefaults;deess[1]=deess[2];assert(!clarity.SetDeess(deess.data(),9));
    }
    std::puts("TopEnd2 limits: rapid mode/parameter edits with full-scale DC/alternation/noise remain finite from 8 to 192 kHz; malformed and invalid packets are rejected.");
}
void exportAudio(const std::filesystem::path& directory) {
    std::filesystem::create_directories(directory);
    FILE* metadata=std::fopen((directory/"metadata.json").string().c_str(),"wb");assert(metadata);
    std::fprintf(metadata,"{\"format\":\"mono float32 little-endian\",\"frames\":65536,\"driveDb\":12,\"evenPercent\":50,\"highOrderPercent\":100,\"hpHz\":1000,\"lp\":\"auto\",\"amount\":0.56,\"audition\":true,\"files\":[");
    bool first=true;
    for(unsigned rate:{44100u,48000u,96000u})for(double requested:{9000.,13000.,17000.})for(unsigned factor:{1u,2u,4u}) {
        const unsigned bin=static_cast<unsigned>(std::round(requested*65536/rate))|1u;const double frequency=double(bin)*rate/65536;
        SpectrumExtend effect;configure(effect,rate,{120,50,100,static_cast<int32_t>(factor)},true);
        auto warm=tone(rate,rate,frequency,.75f);render(effect,warm,512);
        auto data=tone(rate,65536,frequency,.75f,rate);render(effect,data,512);
        const std::string name="spectrum_"+std::to_string(rate)+"_"+std::to_string(static_cast<int>(requested))+"_"+std::to_string(factor)+"x.f32";
        FILE* output=std::fopen((directory/name).string().c_str(),"wb");assert(output);
        for(size_t frame=0;frame<data.size()/2;++frame)assert(std::fwrite(data.data()+2*frame,sizeof(float),1,output)==1);std::fclose(output);
        std::fprintf(metadata,"%s{\"file\":\"%s\",\"rate\":%u,\"frequency\":%.12f,\"factor\":%u,\"latencySamples\":%d}",first?"":",",name.c_str(),rate,frequency,factor,effect.GetNonlinear(1));first=false;
    }
    std::fprintf(metadata,"]}\n");std::fclose(metadata);
}
void benchmark() {
    constexpr unsigned rate=192000,seconds=20,block=512;const auto input=tone(rate,block,13000,.75f);
    for(int factor:{1,2,4}) {
        std::array<double,3> times;
        for(auto& elapsed:times) {
            SpectrumExtend effect;configure(effect,rate,{120,50,100,factor},true);
            std::vector<float> data=input;for(unsigned i=0;i<400;++i){data=input;effect.Process(data.data(),block);}
            const auto start=std::chrono::steady_clock::now();
            for(unsigned pos=0;pos<rate*seconds;pos+=block){std::copy(input.begin(),input.end(),data.begin());effect.Process(data.data(),std::min(block,rate*seconds-pos));}
            elapsed=std::chrono::duration<double>(std::chrono::steady_clock::now()-start).count();
        }
        std::sort(times.begin(),times.end());std::printf("Spectrum full-module O2 benchmark %dx: 192k stereo, 20s audio, median %.6fs wall / %.4f realtime\n",factor,times[1],times[1]/seconds);
    }
}
}
int main(int argc,char** argv) {
    if(argc==3&&std::string(argv[1])=="--export-dir"){exportAudio(argv[2]);return 0;}
    if(argc==2&&std::string(argv[1])=="--benchmark"){benchmark();return 0;}
    defaultAndContinuity();selectiveResponse();collapsingBands();dynamicsResponse();parameterResponse();nonlinearResponse();extremesAndPackets();return 0;
}
