#include <array>
#include <cassert>
#include <cmath>
#include <cstring>
#include <cstdio>
#include <complex>
#include "Width23Reference.h"
#include "../../app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/Effects/stereoWidthControl.h"
static const std::array<int32_t,26> defaults{1,0,1000,1000,1000,1000,180,3000,1,0,120,1000,1,0,1000,0,0,400,30,0,0,5,20,400,1000,300};
static void sample(StereoWidthControl& s,int n,double m=.04,double side=.04,double hz=440) {
    const double t=n/s.fs,L=m*sin(6.283185307179586*hz*t)+side*cos(6.283185307179586*hz*t),R=m*sin(6.283185307179586*hz*t)-side*cos(6.283185307179586*hz*t);
    float l=L,r=R;StereoWidthFrame(&s,L,R,&l,&r);assert(std::isfinite(l) && std::isfinite(r));
}
static void compatibility() {
    int cases=0;
    for(double fs:{8000.,44100.,48000.,96000.,192000.})for(int mode=0;mode<3;mode++)for(int variant=0;variant<3;variant++) {
        StereoWidthControl s{};Width23Control reference{};StereoWidthInit(&s,fs);Width23Init(&reference,fs);auto p=defaults;p[1]=mode;
        if(variant){p[2]=1300;p[3]=800;p[4]=1400;p[5]=1800;p[9]=1;p[11]=350;p[13]=-13;p[14]=713;p[15]=-12;p[16]=1;p[19]=mode?1:0;}
        assert(StereoWidthSet(&s,p.data(),26));assert(Width23Set(&reference,p.data(),26));
        if(variant==2){auto advanced=width2Defaults; (void)advanced;int32_t a[60];std::memcpy(a,width2Defaults,sizeof(a));a[59]=1;assert(StereoWidthAdvancedSet(&s,a,60));}
        for(int n=0;n<6000;n++){double L=.04*sin(n*.07)+.02*cos(n*.03),R=.03*sin(n*.071)-.02*cos(n*.03);float l=L*.9,r=R*.9,rl=l,rr=r;
            StereoWidthFrame(&s,L,R,&l,&r);Width23Frame(&reference,L,R,&rl,&rr);assert(!std::memcmp(&l,&rl,4));assert(!std::memcmp(&r,&rr,4));}
        cases++;
    }
    printf("Frozen ui23 default/monitor-only comparison: %d cases bit-identical\n",cases);
}
static void dynamicsAndTransient() {
    StereoWidthControl s{};StereoWidthInit(&s,48000);auto p=defaults;p[1]=1;p[2]=2000;StereoWidthSet(&s,p.data(),26);
    int32_t a[60];std::memcpy(a,width2Defaults,sizeof(a));a[22]=1;a[23]=-300;a[28]=500;StereoWidthAdvancedSet(&s,a,60);
    for(int n=0;n<48000;n++)sample(s,n);assert(s.advanced.dynamic[3]>.49 && s.advanced.dynamic[3]<=.500001);
    a[22]=0;a[29]=1;a[31]=30;a[33]=1;a[34]=100;StereoWidthAdvancedSet(&s,a,60);
    for(int n=0;n<24000;n++)sample(s,n,.000001,.000001);
    for(int n=24000;n<24480;n++)sample(s,n,.1,.1);assert(s.advanced.transient[3]>.5);
    p[2]=800;StereoWidthSet(&s,p.data(),26);for(int n=0;n<24000;n++)sample(s,n);assert(s.master<.801);
    for(int i=0;i<4;i++)assert(s.advanced.combined[i]>=0 && s.advanced.combined[i]<=1);
}
static void guardEqAutoMeters() {
    StereoWidthControl s{};StereoWidthInit(&s,48000);s.enabled=1;auto p=defaults;p[1]=2;p[2]=2000;StereoWidthSet(&s,p.data(),26);
    int32_t a[60];std::memcpy(a,width2Defaults,sizeof(a));a[36]=1;a[37]=1;a[38]=20;a[59]=1;StereoWidthAdvancedSet(&s,a,60);
    for(int n=0;n<48000;n++)sample(s,n,.02,.08,6000);
    assert(s.advanced.guard[2]>.99);assert(s.advanced.guard[0]<.01); // quiet bands are gated
    int32_t v[176];StereoWidthAdvancedSnapshot(&s,v);assert(v[5] && v[45] && v[10+2]<0);
    a[36]=0;a[44]=1;a[45]=6000;a[46]=-60;a[47]=100;StereoWidthAdvancedSet(&s,a,60);
    for(int n=0;n<48000;n++)sample(s,n,.02,.04,6000);assert(s.advanced.eqFade==1);
    assert(StereoWidthAdvancedAudition(&s,7));for(int n=0;n<12000;n++)sample(s,n);assert(s.audition==7);
    assert(StereoWidthAdvancedAudition(&s,0));assert(!StereoWidthAdvancedAudition(&s,3));
    p[16]=1;p[18]=30;StereoWidthSet(&s,p.data(),26);a[52]=1;a[57]=1;StereoWidthAdvancedSet(&s,a,60);
    for(int n=0;n<48000;n++)sample(s,n);assert(s.autoGain<=1.000001 && s.autoGain>=pow(10.,-3./20.)-.00001);
    const double held=s.advanced.autoHeldDb;for(int n=0;n<100000;n++)sample(s,n,0,0);assert(std::abs(s.advanced.autoHeldDb-held)<3);assert(std::isfinite(s.autoGain));
    StereoWidthClear(&s);StereoWidthAdvancedSnapshot(&s,v);assert(!v[5] && !v[44]);
}
static void validationAndRates() {
    for(int i=0;i<60;i++){int32_t a[60];std::memcpy(a,width2Defaults,sizeof(a));a[i]=width2Low[i]-1;assert(!StereoWidthAdvancedValidate(a,60));a[i]=width2High[i]+1;assert(!StereoWidthAdvancedValidate(a,60));}
    assert(!StereoWidthAdvancedValidate(width2Defaults,59));assert(StereoWidthAdvancedValidate(width2Defaults,60));
    for(double fs:{8000.,44100.,48000.,96000.,192000.}) {
        StereoWidthControl s{};StereoWidthInit(&s,fs);auto p=defaults;p[1]=2;p[2]=2000;StereoWidthSet(&s,p.data(),26);int32_t a[60];std::memcpy(a,width2Defaults,sizeof(a));
        a[1]=a[8]=a[15]=a[29]=a[36]=a[44]=a[48]=a[52]=a[59]=1;a[45]=16000;a[46]=60;a[47]=400;
        StereoWidthAdvancedSet(&s,a,60);for(int n=0;n<12000;n++){if(n%47==0){a[45]=n%2?80:16000;StereoWidthAdvancedSet(&s,a,60);}sample(s,n);}
        StereoWidthConfigure(&s,44100);assert(s.fs==44100);sample(s,0);
    }
}
static std::complex<double> referencePeak(double fs,double hz,double center,double gain,double q) {
    double A=std::pow(10.,gain/40.),w=2*acos(-1.)*std::min(center,fs*.45)/fs,alpha=sin(w)/(2*q);
    std::complex<double> z=std::exp(std::complex<double>(0,-2*acos(-1.)*hz/fs));
    return ((1+alpha*A)-2*cos(w)*z+(1-alpha*A)*z*z)/((1+alpha/A)-2*cos(w)*z+(1-alpha/A)*z*z);
}
static void independentEqResponse() {
    double worst=0;int cases=0;
    for(double fs:{8000.,44100.,48000.,96000.,192000.})for(int variant=0;variant<3;variant++)for(double frequency:{200.,1000.,6000.}) {
        const int window=static_cast<int>(fs*.5);double hz=round(std::min(frequency,fs*.4)*window/fs)*fs/window;
        StereoWidthControl s{};StereoWidthInit(&s,fs);auto p=defaults;p[1]=1;p[2]=variant==2?1000:2000;StereoWidthSet(&s,p.data(),26);
        int32_t a[60];std::memcpy(a,width2Defaults,sizeof(a));a[44]=1;a[45]=800;a[46]=variant==1?60:-60;a[47]=variant==1?350:70;a[48]=1;a[49]=7000;a[50]=30;a[51]=100;StereoWidthAdvancedSet(&s,a,60);
        std::complex<double> sum=0;
        for(int n=0;n<static_cast<int>(fs)+window;n++) {
            double phase=2*acos(-1.)*hz*n/fs,side=.03*cos(phase),mid=.02*sin(phase*.5);float l=mid+side,r=mid-side;StereoWidthFrame(&s,mid+side,mid-side,&l,&r);
            assert(std::abs((double(l)+r)*.5-mid)<1e-7);
            if(n>=static_cast<int>(fs))sum+=(double(l)-r)*.5*std::exp(std::complex<double>(0,-phase));
        }
        auto expected=variant==2?std::complex<double>(1,0):1.+referencePeak(fs,hz,a[45],a[46]/10.,a[47]/100.)*referencePeak(fs,hz,a[49],a[50]/10.,a[51]/100.);
        double error=std::abs(sum*(2./(.03*window))-expected);worst=std::max(worst,error);assert(error<2e-6);cases++;
    }
    printf("Width2 independent complex EQ response: %d cases, maximum error %.9g; Mid unchanged and 100%% width unaffected\n",cases,worst);
}
static void dynamicEqInteraction() {
    for(int gain:{-60,60}) {
        StereoWidthControl s{};StereoWidthInit(&s,48000);auto p=defaults;p[1]=1;p[2]=2000;StereoWidthSet(&s,p.data(),26);
        int32_t a[60];std::memcpy(a,width2Defaults,sizeof(a));a[22]=1;a[23]=0;a[24]=20;a[25]=0;a[28]=1000;a[44]=1;a[45]=1000;a[46]=gain;StereoWidthAdvancedSet(&s,a,60);
        double midPower=0,sidePower=0;
        for(int n=0;n<144000;n++){double phase=2*acos(-1.)*1000*n/48000.,m=.02*sin(phase),side=.02*cos(phase);float l=m+side,r=m-side;StereoWidthFrame(&s,m+side,m-side,&l,&r);if(n>=96000){midPower+=pow((double(l)+r)*.5,2);sidePower+=pow((double(l)-r)*.5,2);}}
        double expected=10*log10(1+pow(10.,gain/200.)),measured=10*log10(sidePower/midPower);fprintf(stderr,"Combined EQ %+.1f dB: expected %.9f dB measured %.9f dB, withdrawal %.9f\n",gain/10.,expected,measured,s.advanced.dynamic[3]);assert(std::abs(measured-expected)<.03);
    }
    puts("Width2 combined dynamics/EQ: measured 2:1 Side/Mid compression agrees with power/covariance solution at +/-6dB EQ");
}
static void appliedWithdrawalMeters() {
    StereoWidthControl s{};StereoWidthInit(&s,48000);s.enabled=1;auto p=defaults;p[1]=1;p[2]=2000;StereoWidthSet(&s,p.data(),26);
    int32_t a[60],v[176];std::memcpy(a,width2Defaults,sizeof(a));a[22]=1;a[23]=-300;a[28]=500;a[59]=1;StereoWidthAdvancedSet(&s,a,60);
    for(int n=0;n<48000;n++)sample(s,n);
    StereoWidthAdvancedSnapshot(&s,v);assert(v[5] && v[22]>490 && v[34]>490);
    for(int i=0;i<3;i++)for(int at:{19,23,27,31})assert(v[at+i]==0);
    // Turning off processing keeps monitoring alive; the bypassed envelopes can freeze.
    a[22]=0;StereoWidthAdvancedSet(&s,a,60);for(int n=0;n<48000;n++)sample(s,n);
    assert(s.advanced.processingMix==0 && s.advanced.dynamic[3]>.01);
    StereoWidthAdvancedSnapshot(&s,v);assert(v[5] && v[45]);for(int i=19;i<35;i++)assert(v[i]==0);
    // A global guard acts on every active band, not only the full-frequency detector slot.
    p[1]=2;StereoWidthSet(&s,p.data(),26);a[36]=1;StereoWidthAdvancedSet(&s,a,60);
    for(int n=0;n<48000;n++)sample(s,n,.02,.08,6000);
    StereoWidthAdvancedSnapshot(&s,v);for(int i=0;i<3;i++)assert(v[27+i]>990 && v[31+i]>990);assert(v[30]==0 && v[34]==0);
    p[1]=0;StereoWidthSet(&s,p.data(),26);for(int n=0;n<48000;n++)sample(s,n);
    StereoWidthAdvancedSnapshot(&s,v);for(int i=19;i<35;i++)assert(v[i]==0);
    p[1]=1;StereoWidthSet(&s,p.data(),26);for(int n=0;n<48000;n++)sample(s,n,.02,.08);s.enabled=0;
    StereoWidthAdvancedSnapshot(&s,v);for(int i=19;i<35;i++)assert(v[i]==0);
    puts("Width2 meters: bypass, inactive modes and disabled module report zero; global guard follows all active bands");
}
int main(){compatibility();dynamicsAndTransient();guardEqAutoMeters();validationAndRates();independentEqResponse();dynamicEqInteraction();appliedWithdrawalMeters();puts("Width2 audio, compatibility, bounds and lifecycle checks passed");}
