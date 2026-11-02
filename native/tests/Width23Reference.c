#include "Width23Reference.h"
#include <math.h>
#include <string.h>
#include <limits.h>

static const int32_t defaults[WIDTH23_WORDS]={1,0,1000,1000,1000,1000,180,3000,1,0,120,1000,1,0,1000,0,0,400,30,0,0,5,20,400,1000,300};
static double bound(double x,double a,double b){return x<a?a:x>b?b:x;}
static double amp(double db){return pow(10.,db/20.);}
static double coef(double fs,double ms){return -expm1(-1000./(fs*ms));}
static double follow(double x,double target,double a){double y=x+(target-x)*a;return fabs(y-target)<1e-10?target:y;}
static double freq(const Width23Control* s,int index){return fmin(s->settings[index],s->fs*.45);}
int Width23Validate(const int32_t* p,size_t n) {
    static const int32_t lo[WIDTH23_WORDS]={1,0,0,0,0,0,80,1000,0,0,30,0,0,-120,0,-120,0,100,0,0,-20,0,5,50,0,100};
    static const int32_t hi[WIDTH23_WORDS]={1,2,2000,2000,2000,2000,500,8000,1,1,400,1000,2,60,1000,60,1,2000,60,1,50,20,200,2000,1000,1000};
    if(!p || n!=WIDTH23_WORDS)return 0;
    for(unsigned i=0;i<WIDTH23_WORDS;i++)if(p[i]<lo[i] || p[i]>hi[i])return 0;
    return p[6]<p[7] && p[17]%10==0 && p[25]%10==0;
}
static double biquad(Width23Biquad* b,double x){double y=b->b0*x+b->z1;b->z1=b->b1*x-b->a1*y+b->z2;b->z2=b->b2*x-b->a2*y;return y;}
static Width23Biquad filter(double f,double fs,double q,int high) {
    double w=6.2831853071795864769*f/fs,c=cos(w),a=sin(w)/(2*q),d=1+a;
    Width23Biquad b={0};b.b0=(high?1+c:1-c)/(2*d);b.b1=(high?-1:1)*2*b.b0;b.b2=b.b0;b.a1=-2*c/d;b.a2=(1-a)/d;return b;
}
static Width23Split split(double f,double fs,int slope) {
    Width23Split s={0};s.stages=slope?2:1;s.sign=slope?1:-1;
    if(slope) {
        s.lo[0]=s.lo[1]=filter(f,fs,.7071067811865475244,0);
        s.hi[0]=s.hi[1]=filter(f,fs,.7071067811865475244,1);
        s.ap=s.lo[0];s.ap.b0=s.ap.a2;s.ap.b1=s.ap.a1;s.ap.b2=1;
    } else {
        // LR12: two first-order sections; invert the high branch when summing.
        double k=tan(3.14159265358979323846*f/fs),a=(k-1)/(k+1);
        Width23Biquad l={k/(1+k),k/(1+k),0,a,0,0,0},h={1/(1+k),-1/(1+k),0,a,0,0,0};
        // Convolve the two identical sections into one biquad.
        s.lo[0]=(Width23Biquad){l.b0*l.b0,2*l.b0*l.b1,l.b1*l.b1,2*a,a*a,0,0};
        s.hi[0]=(Width23Biquad){h.b0*h.b0,2*h.b0*h.b1,h.b1*h.b1,2*a,a*a,0,0};
        s.ap=(Width23Biquad){a,1,0,a,0,0,0};
    }
    return s;
}
static void splitFrame(Width23Split* s,double x,double* lo,double* hi) {
    *lo=*hi=x;for(int i=0;i<s->stages;i++){*lo=biquad(&s->lo[i],*lo);*hi=biquad(&s->hi[i],*hi);}*hi*=s->sign;
}
static Width23Bank design(const Width23Control* s) {
    Width23Bank b={0};b.first=split(freq(s,6),s->fs,s->settings[8]);b.second=split(freq(s,7),s->fs,s->settings[8]);
    b.lowAlign=b.second.ap;b.midAlign[0]=b.first.ap;b.midAlign[1]=b.second.ap;
    int order=2<<s->settings[12];b.monoStages=order/2;
    for(int i=0;i<b.monoStages;i++)for(int j=0;j<2;j++)
        b.mono[j][i]=filter(freq(s,10),s->fs,1/(2*cos(3.14159265358979323846*(2*i+1)/(2*order))),1);
    return b;
}
static void targets(Width23Control* s) {
    s->alpha=coef(s->fs,20);s->meterAlpha=coef(s->fs,s->settings[25]);s->guardAlpha=coef(s->fs,100);s->peakDecay=exp(-1/s->fs);
    s->autoAlpha=coef(s->fs,s->settings[17]);s->attack=coef(s->fs,s->settings[22]);s->release=coef(s->fs,s->settings[23]);
    s->masterTarget=s->settings[2]/1000.;for(int i=0;i<3;i++)s->bandTarget[i]=s->settings[3+i]/1000.;
    s->monoTarget=s->settings[9]?s->settings[11]/1000.:0;
    s->midTarget=amp(s->settings[13]/10.);s->mixTarget=s->settings[14]/1000.;s->outputTarget=amp(s->settings[15]/10.);
    s->guardStrength=s->settings[24]/1000.;s->autoLimit=s->settings[18]/10.;
}
void Width23Clear(Width23Control* s) {
    memset(&s->input,0,sizeof(s->input));memset(&s->output,0,sizeof(s->output));
    s->candidateL=s->candidateR=s->candidateLR=s->matchInput=s->matchOutput=0;
    s->samples=s->tick=0;s->guardAmount=0;s->guardTriggered=0;s->autoGain=s->autoTarget=1;
    s->audition=0;memset(s->auditionMix,0,sizeof(s->auditionMix));s->auditionMix[0]=1;
}
void Width23Configure(Width23Control* s,double fs) {
    s->fs=bound(fs,8000,192000);targets(s);s->bank=design(s);s->bankFade=1;s->pendingBank=0;
    for(int i=0;i<3;i++){s->modes[i]=s->settings[1]==i;s->bands[i]=s->bandTarget[i];}
    s->master=s->masterTarget;s->monoAmount=s->monoTarget;s->midGain=s->midTarget;s->mix=s->mixTarget;s->outputGain=s->outputTarget;
    Width23Clear(s);
}
void Width23Init(Width23Control* s,double fs){memset(s,0,sizeof(*s));memcpy(s->settings,defaults,sizeof(defaults));Width23Configure(s,fs);}
int Width23Set(Width23Control* s,const int32_t* p,size_t n) {
    if(!Width23Validate(p,n))return 0;
    int changed=p[6]!=s->settings[6] || p[7]!=s->settings[7] || p[8]!=s->settings[8] || p[10]!=s->settings[10] || p[12]!=s->settings[12];
    memcpy(s->settings,p,sizeof(s->settings));targets(s);
    if(changed) {
        Width23Bank next=design(s);
        if(s->bankFade<1){s->pending=next;s->pendingBank=1;}
        else {s->previous=s->bank;s->bank=next;s->bankFade=0;}
    }
    return 1;
}
int Width23Audition(Width23Control* s,int request) {
    if(request<0 || request>3)return 0;
    if(!request)s->audition=0;
    else if(request!=s->auditionRequest && s->enabled)s->audition=request;
    s->auditionRequest=request;return 1;
}
static void path(Width23Control* s,Width23Bank* b,double m,double side,double legacyMid,double legacySide,double* dryM,double* dryS,double* mid,double* selected,double* baseline) {
    double low,upper,medium,high;splitFrame(&b->first,side,&low,&upper);splitFrame(&b->second,upper,&medium,&high);low=biquad(&b->lowAlign,low);
    double alignedMid=biquad(&b->midAlign[1],biquad(&b->midAlign[0],m));
    double alignedSide=low+medium+high;
    *dryM=m*(s->modes[0]+s->modes[1])+alignedMid*s->modes[2];
    *dryS=side*(s->modes[0]+s->modes[1])+alignedSide*s->modes[2];
    *mid=s->modes[0]*legacyMid+s->modes[1]*m+s->modes[2]*alignedMid;
    double w[3];for(int i=0;i<3;i++)w[i]=s->master*s->bands[i];
    *selected=s->modes[0]*legacySide+s->modes[1]*side*s->master+s->modes[2]*(w[0]*low+w[1]*medium+w[2]*high);
    *baseline=s->modes[0]*legacySide+s->modes[1]*side*fmin(s->master,1)+s->modes[2]*(fmin(w[0],1)*low+fmin(w[1],1)*medium+fmin(w[2],1)*high);
    double hp[2]={*selected,*baseline};for(int j=0;j<2;j++)for(int i=0;i<b->monoStages;i++)hp[j]=biquad(&b->mono[j][i],hp[j]);
    *selected+=s->monoAmount*(hp[0]-*selected);*baseline+=s->monoAmount*(hp[1]-*baseline);
}
static double correlation(double l,double r,double lr){return l>1e-16 && r>1e-16?bound(lr/sqrt(l*r),-1,1):2;}
static void meter(Width23Meter* m,double l,double r,double a,double peakDecay) {
    double mid=(l+r)*.5,side=(l-r)*.5;
    m->l+=(l*l-m->l)*a;m->r+=(r*r-m->r)*a;m->lr+=(l*r-m->lr)*a;m->m+=(mid*mid-m->m)*a;m->s+=(side*side-m->s)*a;
    m->peak=fmax(fmax(fabs(l),fabs(r)),m->peak*peakDecay);
}
void Width23Frame(Width23Control* s,double l,double r,float* outL,float* outR) {
    s->master=follow(s->master,s->masterTarget,s->alpha);s->midGain=follow(s->midGain,s->midTarget,s->alpha);
    s->mix=follow(s->mix,s->mixTarget,s->alpha);s->outputGain=follow(s->outputGain,s->outputTarget,s->alpha);s->monoAmount=follow(s->monoAmount,s->monoTarget,s->alpha);
    for(int i=0;i<3;i++){s->modes[i]=follow(s->modes[i],s->settings[1]==i,s->alpha);s->bands[i]=follow(s->bands[i],s->bandTarget[i],s->alpha);}
    double m=(l+r)*.5,side=(l-r)*.5,legacyMid=(*outL+*outR)*.5,legacySide=(*outL-*outR)*.5;
    double dm,ds,mid,candidate,base;path(s,&s->bank,m,side,legacyMid,legacySide,&dm,&ds,&mid,&candidate,&base);
    if(s->bankFade<1) {
        double om,os,oldMid,oldCandidate,oldBase;path(s,&s->previous,m,side,legacyMid,legacySide,&om,&os,&oldMid,&oldCandidate,&oldBase);
        double t=s->bankFade;dm=om+t*(dm-om);ds=os+t*(ds-os);mid=oldMid+t*(mid-oldMid);candidate=oldCandidate+t*(candidate-oldCandidate);base=oldBase+t*(base-oldBase);
        s->bankFade=fmin(1,s->bankFade+1/(.02*s->fs));
        if(s->bankFade==1 && s->pendingBank){s->previous=s->bank;s->bank=s->pending;s->pendingBank=0;s->bankFade=0;}
    }
    mid*=s->midGain;
    double cl=mid+candidate,cr=mid-candidate;
    s->candidateL+=(cl*cl-s->candidateL)*s->guardAlpha;s->candidateR+=(cr*cr-s->candidateR)*s->guardAlpha;s->candidateLR+=(cl*cr-s->candidateLR)*s->guardAlpha;
    double rho=correlation(s->candidateL,s->candidateR,s->candidateLR);
    if(!s->settings[19] || s->settings[1]==0 || rho==2)s->guardTriggered=0;
    else if(rho<s->settings[20]/100.)s->guardTriggered=1;
    else if(rho>(s->settings[20]+s->settings[21])/100.)s->guardTriggered=0;
    double guardTarget=s->guardTriggered?s->guardStrength:0;
    s->guardAmount=follow(s->guardAmount,guardTarget,guardTarget>s->guardAmount?s->attack:s->release);
    double protectedSide=candidate+s->guardAmount*(base-candidate);
    double left=mid+protectedSide,right=mid-protectedSide,dryLeft=dm+ds,dryRight=dm-ds;
    left=dryLeft+s->mix*(left-dryLeft);right=dryRight+s->mix*(right-dryRight);
    s->matchInput+=((dryLeft*dryLeft+dryRight*dryRight)*.5-s->matchInput)*s->autoAlpha;
    s->matchOutput+=((left*left+right*right)*.5-s->matchOutput)*s->autoAlpha;
    if(s->tick++%16==0) {
        double db=s->settings[16] && s->matchInput>1e-12 && s->matchOutput>1e-12?10*log10(s->matchInput/s->matchOutput):0;
        s->autoTarget=amp(bound(db,-s->autoLimit,s->autoLimit));
    }
    s->autoGain=follow(s->autoGain,s->autoTarget,s->autoAlpha);left*=s->autoGain*s->outputGain;right*=s->autoGain*s->outputGain;
    // Preserve the old branch's exact float output at default settings.
    if(s->modes[0]==1 && s->midGain==1 && s->monoAmount==0 && s->mix==1 && s->autoGain==1 && s->outputGain==1 && s->guardAmount==0){left=*outL;right=*outR;}
    meter(&s->input,l,r,s->meterAlpha,s->peakDecay);meter(&s->output,left,right,s->meterAlpha,s->peakDecay);s->samples++;
    for(int i=0;i<4;i++)s->auditionMix[i]=follow(s->auditionMix[i],s->audition==i,s->alpha);
    double am=(left+right)*.5,as=(left-right)*.5;
    *outL=(float)(s->auditionMix[0]*left+s->auditionMix[1]*dryLeft+s->auditionMix[2]*am+s->auditionMix[3]*as);
    *outR=(float)(s->auditionMix[0]*right+s->auditionMix[1]*dryRight+s->auditionMix[2]*am-s->auditionMix[3]*as);
}
static int32_t dbPower(double v){return v>1e-24?(int32_t)llround(100*log10(v)):INT32_MIN;}
static int32_t rhoInt(const Width23Meter* m){double v=correlation(m->l,m->r,m->lr);return v==2?INT32_MIN:(int32_t)llround(v*10000);}
void Width23Snapshot(const Width23Control* s,int32_t* p) {
    memset(p,0,WIDTH23_RUNTIME_WORDS*4);p[0]=1;p[2]=(int32_t)s->fs;p[3]=s->enabled;p[4]=s->settings[1];
    int ready=s->enabled && s->samples>=(uint64_t)(s->fs*.02);p[25]=ready;
    for(int i=5;i<=14;i++)p[i]=INT32_MIN;p[31]=INT32_MIN;
    if(ready){p[5]=rhoInt(&s->input);p[6]=rhoInt(&s->output);p[7]=dbPower(s->input.m);p[8]=dbPower(s->input.s);p[9]=dbPower(s->output.m);p[10]=dbPower(s->output.s);
        if(s->output.m>1e-24 && s->output.s>1e-24)p[11]=dbPower(s->output.s/s->output.m);
        p[12]=dbPower(s->input.peak*s->input.peak);p[13]=dbPower(s->output.peak*s->output.peak);p[14]=dbPower((s->output.l+s->output.r)*.5);p[31]=dbPower((s->input.l+s->input.r)*.5);}
    p[15]=s->settings[1]?(int32_t)llround(1000*(s->master-s->guardAmount*fmax(s->master-1,0))):INT32_MIN;
    for(int i=0;i<3;i++){double w=s->master*s->bands[i];p[16+i]=s->settings[1]==2?(int32_t)llround(1000*(w-s->guardAmount*fmax(w-1,0))):INT32_MIN;}
    p[19]=(int32_t)llround(s->guardAmount*1000);p[20]=dbPower(s->autoGain*s->autoGain);
    p[21]=(int32_t)llround(freq(s,6));p[22]=(int32_t)llround(freq(s,7));p[23]=(int32_t)llround(freq(s,10));p[24]=s->enabled?s->audition:0;
    p[26]=(int32_t)(s->samples&0x7fffffff);p[27]=dbPower(s->midGain*s->midGain);p[28]=(int32_t)llround(s->mix*1000);p[29]=dbPower(s->outputGain*s->outputGain);p[30]=s->guardTriggered;
}
