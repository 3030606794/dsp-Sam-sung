// Frozen 8939d79; only class names and include paths changed.
#include "DiffFirstRoundReference.h"
#include "../viper/src/viper/utils/ProfessionalTuning.h"
#include <complex>
#include <cmath>

namespace {
constexpr unsigned maxFft=1024,maxParts=32,ringSize=16384,maxCoefficients=12288;
constexpr double pi=3.14159265358979323846;
using Complex=std::complex<float>;
int32_t fold(double mono,double stereo) {
    return mono>1.e-30 && stereo>1.e-30?static_cast<int32_t>(std::lround(1000*std::log10(mono/stereo))):ImagingMeter::invalid;
}
void transform(std::array<Complex,maxFft>& x,bool inverse,unsigned fftSize,const std::array<Complex,maxFft/2>& twiddle) {
    for(unsigned i=1,j=0;i<fftSize;++i){unsigned bit=fftSize>>1;for(;j&bit;bit>>=1)j^=bit;j^=bit;if(i<j)std::swap(x[i],x[j]);}
    for(unsigned length=2;length<=fftSize;length<<=1) {
        const unsigned half=length/2,stride=fftSize/length;
        for(unsigned start=0;start<fftSize;start+=length)for(unsigned j=0;j<half;++j) {
            const Complex w=inverse?std::conj(twiddle[j*stride]):twiddle[j*stride];
            const Complex a=x[start+j],b=x[start+j+half]*w;x[start+j]=a+b;x[start+j+half]=a-b;
        }
    }
    if(inverse)for(unsigned i=0;i<fftSize;++i)x[i]/=float(fftSize);
}
}
struct DiffFirstRoundReference::Prepared {
        uint32_t rate=48000;
        std::array<int32_t,7> settings{};
        bool band=false;
        unsigned channel=1,common=0,parts=0;
        float delay=0,mix=1;
        std::array<std::array<Complex,maxFft>,maxParts> coefficients{};
        std::array<float,maxFft/2> output{};
};
struct DiffFirstRoundReference::Storage {
    std::array<std::shared_ptr<Prepared>,3> banks{};
    std::array<std::array<float,ringSize>,2> raw{};
    std::array<std::array<std::array<Complex,maxFft>,maxParts>,2> history{};
    std::array<Complex,maxFft/2> twiddle{};
    std::array<Complex,maxFft> scratch{};
    unsigned block=128,fftSize=256,write=0,fill=0,historyWrite=0,historyCount=0;
    unsigned active=0,target=1,pending=2,fadePosition=0,fadeFrames=720;
    bool switching=false,hasPending=false;
    Storage(){for(auto& bank:banks)bank=std::make_shared<Prepared>();}
    void Transform(std::array<Complex,maxFft>& x,bool inverse) const {
        transform(x,inverse,fftSize,twiddle);
    }
    float Read(unsigned channel,unsigned delay) const {return raw[channel][(write+ringSize-delay)%ringSize];}
    float Fractional(unsigned channel,float delay) const {
        const unsigned integer=static_cast<unsigned>(delay);const float fraction=delay-integer;
        const float recent=Read(channel,integer);
        return fraction==0?recent:recent+fraction*(Read(channel,integer+1)-recent);
    }
    void Refresh(unsigned slot) {
        auto& bank=*banks[slot];if(!bank.band)return;
        std::fill_n(scratch.begin(),fftSize,Complex{});
        const unsigned parts=std::min(bank.parts,historyCount);
        for(unsigned part=0;part<parts;++part) {
            const auto& input=history[bank.channel][(historyWrite+maxParts-1-part)%maxParts];
            for(unsigned bin=0;bin<fftSize;++bin)scratch[bin]+=input[bin]*bank.coefficients[part][bin];
        }
        Transform(scratch,true);
        for(unsigned i=0;i<block;++i)bank.output[i]=scratch[block+i].real();
    }
    void FinishBlock() {
        for(unsigned channel=0;channel<2;++channel) {
            auto& input=history[channel][historyWrite];
            for(unsigned i=0;i<fftSize;++i)input[i]=Read(channel,fftSize-1-i);
            Transform(input,false);
        }
        historyWrite=(historyWrite+1)%maxParts;historyCount=std::min(maxParts,historyCount+1);
        Refresh(active);if(switching)Refresh(target);
    }
    void Output(unsigned slot,float& left,float& right) const {
        const auto& bank=*banks[slot];
        left=Read(0,bank.common);right=Read(1,bank.common);
        float& selected=bank.channel==0?left:right;
        if(bank.band)selected=bank.output[fill];
        else if(bank.mix!=0) {
            const float delayed=Fractional(bank.channel,bank.delay);
            selected=bank.mix==1?delayed:selected+bank.mix*(delayed-selected);
        }
    }
};

DiffFirstRoundReference::DiffFirstRoundReference():storage(std::make_unique<Storage>()){Reset();}
DiffFirstRoundReference::~DiffFirstRoundReference()=default;
bool DiffFirstRoundReference::ValidAdvanced(const int32_t* v,size_t n) {
    if(!v || n!=7)return false;
    const int32_t low[7]={0,0,0,0,0,40,2000},high[7]={1,2000,1,1000,1,2000,20000};
    for(unsigned i=0;i<7;++i)if(v[i]<low[i] || v[i]>high[i])return false;
    return v[5]<v[6];
}
void DiffFirstRoundReference::Prepare(unsigned slot) {
    auto prepared=PrepareAdvanced(settings.data(),7,samplingRate);
    if(!settings[0])prepared->delay=float(static_cast<uint32_t>(double(delayTime)/1000.*samplingRate));
    storage->banks[slot].swap(prepared);
}
std::shared_ptr<DiffFirstRoundReference::Prepared> DiffFirstRoundReference::PrepareAdvanced(const int32_t* values,size_t count,uint32_t samplingRate) {
    if(!ValidAdvanced(values,count) || samplingRate<8000 || samplingRate>192000)return {};
    auto prepared=std::make_shared<Prepared>();auto& bank=*prepared;
    std::copy_n(values,7,bank.settings.begin());bank.rate=samplingRate;const auto& settings=bank.settings;
    unsigned block=1;while(block<samplingRate/375.f)block*=2;const unsigned fftSize=block*2;
    bank.band=settings[0] && settings[4];bank.channel=settings[0] && settings[2]?0:1;
    bank.delay=settings[0]?samplingRate*settings[1]/100000.f:0;
    bank.mix=settings[0]?settings[3]/1000.f:1.f;bank.parts=0;bank.common=0;
    if(!bank.band)return prepared;
    const unsigned middle=static_cast<unsigned>(std::lround(samplingRate*.020));
    bank.common=middle+block;
    const unsigned delay=static_cast<unsigned>(bank.delay);const float fraction=bank.delay-delay;
    const unsigned taps=2*middle+1,total=taps+delay+(fraction>0?1:0);
    std::array<float,maxCoefficients> coefficients{};
    coefficients[middle]=1;
    const double low=professional::frequency(settings[5],samplingRate),high=professional::frequency(settings[6],samplingRate);
    // Fejer's non-negative spectral kernel smooths a 0/1 ideal mask, hence the
    // zero-phase band response stays in [0,1]. Do not peak-normalize this FIR.
    // H = z^-M + mix * (fractionalDelay - 1) * B, with common FFT latency P.
    if(bank.delay!=0 && bank.mix!=0)for(unsigned i=0;i<taps;++i) {
        const int offset=int(i)-int(middle);
        const double ideal=offset==0?2*(high-low)/samplingRate:
            (std::sin(2*pi*high*offset/samplingRate)-std::sin(2*pi*low*offset/samplingRate))/(pi*offset);
        const float band=bank.mix*ideal*(1-std::abs(offset)/double(middle+1));
        coefficients[i]-=band;coefficients[i+delay]+=(1-fraction)*band;
        if(fraction>0)coefficients[i+delay+1]+=fraction*band;
    }
    bank.parts=(total+block-1)/block;
    std::array<Complex,maxFft/2> twiddle{};
    for(unsigned i=0;i<fftSize/2;++i)twiddle[i]=Complex(std::cos(2*pi*i/fftSize),-std::sin(2*pi*i/fftSize));
    for(unsigned part=0;part<bank.parts;++part) {
        auto& filter=bank.coefficients[part];std::fill_n(filter.begin(),fftSize,Complex{});
        for(unsigned i=0;i<block && part*block+i<total;++i)filter[i]=coefficients[part*block+i];
        transform(filter,false,fftSize,twiddle);
    }
    return prepared;
}
bool DiffFirstRoundReference::ApplyPrepared(std::shared_ptr<Prepared>& prepared) {
    if(!prepared || prepared->rate!=samplingRate)return false;
    if(settings==prepared->settings)return true;
    settings=prepared->settings;
    if(!settings[0])prepared->delay=float(static_cast<uint32_t>(double(delayTime)/1000.*samplingRate));
    storage->banks[storage->pending].swap(prepared);storage->hasPending=true;return true;
}
bool DiffFirstRoundReference::SetAdvanced(const int32_t* values,size_t count) {
    if(!ValidAdvanced(values,count))return false;
    if(std::equal(settings.begin(),settings.end(),values))return true;
    auto prepared=PrepareAdvanced(values,count,samplingRate);return ApplyPrepared(prepared);
}
void DiffFirstRoundReference::ClearRuntime() {
    audition=0;bypassMix=monoMix=0;
    before.Reset(samplingRate);after.Reset(samplingRate);count=0;inMid=inTotal=outMid=outTotal=0;
    beforeFold=afterFold=ImagingMeter::invalid;
}
void DiffFirstRoundReference::Reset() {
    auto& s=*storage;s.block=1;while(s.block<samplingRate/375.f)s.block*=2;s.fftSize=s.block*2;
    for(unsigned i=0;i<s.fftSize/2;++i)s.twiddle[i]=Complex(std::cos(2*pi*i/s.fftSize),-std::sin(2*pi*i/s.fftSize));
    for(auto& channel:s.raw)channel.fill(0);
    for(auto& channel:s.history)for(auto& part:channel)part.fill({});
    for(auto& bank:s.banks)bank->output.fill(0);
    s.active=0;s.target=1;s.pending=2;s.write=s.fill=s.historyWrite=s.historyCount=s.fadePosition=0;
    s.switching=s.hasPending=false;s.fadeFrames=std::max(1u,static_cast<unsigned>(std::lround(samplingRate*.015)));
    Prepare(s.active);alpha=1.f/s.fadeFrames;window=std::max(1u,(samplingRate+9)/10);ClearRuntime();
}
void DiffFirstRoundReference::SetDelayTime(float value) {
    if(!std::isfinite(value) || value<0 || value>50 || value==delayTime)return;
    delayTime=value;if(!settings[0])Reset(); // Preserve the old command's exact integer and reset semantics.
}
void DiffFirstRoundReference::SetEnable(bool value) {if(value!=enable){if(value)Reset();else ClearRuntime();enable=value;}}
void DiffFirstRoundReference::SetSamplingRate(uint32_t value){if(value>=8000 && value<=192000 && value!=samplingRate){samplingRate=value;Reset();}}
void DiffFirstRoundReference::SetAudition(int32_t request) {
    if(request<0 || request>2)return;
    if(request==0)audition=0;else if(request!=lastAudition && enable)audition=request;
    lastAudition=request;
}
void DiffFirstRoundReference::Process(float* samples,uint32_t frames) {
    if(!enable)return;auto& s=*storage;
    for(unsigned frame=0;frame<frames;++frame) {
        const float inputL=samples[2*frame],inputR=samples[2*frame+1];
        s.raw[0][s.write]=inputL;s.raw[1][s.write]=inputR;
        if(!s.switching && s.hasPending){std::swap(s.target,s.pending);s.hasPending=false;s.switching=true;s.fadePosition=0;s.Refresh(s.target);}
        float left,right;s.Output(s.active,left,right);
        if(s.switching) {
            float nextL,nextR;s.Output(s.target,nextL,nextR);const float mix=float(s.fadePosition)/s.fadeFrames;
            left+=mix*(nextL-left);right+=mix*(nextR-right);
            if(++s.fadePosition>s.fadeFrames){std::swap(s.active,s.target);s.switching=false;}
        }
        before.Process(inputL,inputR);after.Process(left,right);
        const double mi=(double(inputL)+inputR)*.5,mo=(double(left)+right)*.5;
        inMid+=mi*mi;inTotal+=(double(inputL)*inputL+double(inputR)*inputR)*.5;
        outMid+=mo*mo;outTotal+=(double(left)*left+double(right)*right)*.5;
        if(++count==window){beforeFold=fold(inMid,inTotal);afterFold=fold(outMid,outTotal);count=0;inMid=inTotal=outMid=outTotal=0;}
        const auto auditionStep=[&](float value,bool selected){return selected?std::min(1.f,value+alpha):std::max(0.f,value-alpha);};
        bypassMix=auditionStep(bypassMix,audition==1);
        monoMix=auditionStep(monoMix,audition==2);
        if(bypassMix+monoMix>1.f) { // Keep the convex weights bounded despite float rounding.
            if(audition==1)monoMix=1.f-bypassMix;else bypassMix=1.f-monoMix;
        }
        if(bypassMix!=0 || monoMix!=0) {
            const float normal=std::max(0.f,1.f-bypassMix-monoMix),mid=(left+right)*.5f;
            left=normal*left+bypassMix*inputL+monoMix*mid;right=normal*right+bypassMix*inputR+monoMix*mid;
        }
        samples[2*frame]=left;samples[2*frame+1]=right;
        if(++s.fill==s.block){s.FinishBlock();s.fill=0;}
        s.write=(s.write+1)%ringSize;
    }
}
int32_t DiffFirstRoundReference::GetRuntime(unsigned index) const {
    const bool pro=settings[0]!=0,band=pro && settings[4];
    if(index==0)return pro?settings[1]*10:static_cast<int32_t>(std::lround(delayTime*1000));
    if(index==1)return pro?static_cast<int32_t>(std::lround(samplingRate*settings[1]/100.f)):
        static_cast<int32_t>(static_cast<uint32_t>(double(delayTime)/1000.*samplingRate)*1000);
    if(index==2)return pro?settings[2]:0;
    const unsigned common=band?static_cast<unsigned>(std::lround(samplingRate*.020))+storage->block:0;
    if(index==3)return common;
    if(index==4)return static_cast<int32_t>(std::lround(common*1000000./samplingRate));
    if(index==5 || index==6)return band?static_cast<int32_t>(professional::frequency(settings[index],samplingRate)):0;
    if(index>=7 && index<=16) {
        if(!enable)return ImagingMeter::invalid;
        if(index==7)return before.Get(0);if(index==8)return after.Get(0);
        if(index==9)return beforeFold;if(index==10)return afterFold;
        if(index==11)return before.Get(3);if(index==12)return after.Get(3);
        if(index==13 || index==14)return before.Get(index-12);
        return after.Get(index-14);
    }
    if(index==17)return enable?after.Get(4):0;
    if(index==18)return enable?audition:0;
    if(index==19)return enable && (storage->switching || storage->hasPending)?1:0;
    if(index==22)return pro?settings[3]:1000;
    if(index==23)return band?1:0;
    return index==21?settings[0]:0;
}
