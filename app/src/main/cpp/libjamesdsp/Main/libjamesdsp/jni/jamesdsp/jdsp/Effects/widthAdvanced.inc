/* Included by stereoWidthControl.c: reuse the same verified LR/filter primitives. */
static WidthBiquad width2Peak(double hz,double fs,double gain,double q) {
    double w=6.2831853071795864769*fmin(hz,fs*.45)/fs,c=cos(w),a=sin(w)/(2*q),A=amp(gain*.5),d=1+a/A;
    return (WidthBiquad){(1+a*A)/d,-2*c/d,(1-a*A)/d,-2*c/d,(1-a/A)/d,0,0};
}
static void width2EqDesign(StereoWidthControl* s,WidthBiquad out[4][2]) {
    for(int band=0;band<4;band++)for(int i=0;i<2;i++) {
        int at=44+4*i;const int32_t* p=s->advanced.settings;
        out[band][i]=width2Peak(p[at+1],s->fs,p[at]?p[at+2]/10.:0,p[at+3]/100.);
    }
}
static void width2Targets(StereoWidthControl* s) {
    WidthAdvanced* a=&s->advanced;const int32_t* p=a->settings;
    for(int i=0;i<4;i++){a->dynAttack[i]=coef(s->fs,p[1+7*i+4]);a->dynRelease[i]=coef(s->fs,p[1+7*i+5]);}
    a->transAttack=coef(s->fs,p[33]);a->transRelease=coef(s->fs,p[35]);a->guardAlpha=coef(s->fs,p[38]);a->floorPower=amp(p[40]/5.);
    a->autoAttack=coef(s->fs,p[53]);a->autoRelease=coef(s->fs,p[54]);a->autoFloor=amp(p[56]/5.);a->autoDeadband=p[55]/10.;
    a->envelopeAlpha=coef(s->fs,50);a->fastAlpha=coef(s->fs,.5);
    a->processingTarget=s->settings[1] && (p[29] || p[36] || p[44] || p[48] || (s->settings[1]==2?(p[1] || p[8] || p[15]):p[22]))?1:0;
}
static void width2Clear(StereoWidthControl* s) {
    WidthAdvanced* a=&s->advanced;
    memset(a->bands,0,sizeof(a->bands));memset(a->candidate,0,sizeof(a->candidate));
    memset(a->dynamic,0,sizeof(a->dynamic));memset(a->transient,0,sizeof(a->transient));memset(a->guard,0,sizeof(a->guard));memset(a->combined,0,sizeof(a->combined));
    memset(a->envelopeM,0,sizeof(a->envelopeM));memset(a->envelopeS,0,sizeof(a->envelopeS));
    memset(a->envelopeBase,0,sizeof(a->envelopeBase));memset(a->envelopeExtra,0,sizeof(a->envelopeExtra));memset(a->envelopeCross,0,sizeof(a->envelopeCross));
    memset(a->fast,0,sizeof(a->fast));memset(a->slow,0,sizeof(a->slow));memset(a->hold,0,sizeof(a->hold));memset(a->triggered,0,sizeof(a->triggered));
    for(int i=0;i<4;i++){a->rho[i]=2;a->transientDb[i]=0;}
    memset(a->vectors,0,sizeof(a->vectors));a->vectorIndex=0;a->autoHeldDb=0;a->meterSamples=0;
    for(int i=0;i<2;i++)a->monitor[i]=design(s);
    width2EqDesign(s,a->eq);a->eqFade=1;a->eqWaiting=0;
}
int StereoWidthAdvancedValidate(const int32_t* p,size_t n) {
    if(!p || n!=WIDTH2_WORDS)return 0;
    for(int i=0;i<WIDTH2_WORDS;i++)if(p[i]<width2Low[i] || p[i]>width2High[i])return 0;
    return 1;
}
int StereoWidthAdvancedSet(StereoWidthControl* s,const int32_t* p,size_t n) {
    if(!StereoWidthAdvancedValidate(p,n))return 0;
    int changed=memcmp(s->advanced.settings+44,p+44,8*sizeof(int32_t))!=0;
    if(p[59]!=s->advanced.settings[59]){s->advanced.meterSamples=0;memset(s->advanced.bands,0,sizeof(s->advanced.bands));for(int i=0;i<2;i++)s->advanced.monitor[i]=design(s);}
    memcpy(s->advanced.settings,p,sizeof(s->advanced.settings));width2Targets(s);
    if(changed){WidthBiquad next[4][2];width2EqDesign(s,next);
        if(s->advanced.eqFade<1){memcpy(s->advanced.eqPending,next,sizeof(next));s->advanced.eqWaiting=1;}
        else {memcpy(s->advanced.eqOld,s->advanced.eq,sizeof(next));memcpy(s->advanced.eq,next,sizeof(next));s->advanced.eqFade=0;}}
    return 1;
}
int StereoWidthAdvancedAudition(StereoWidthControl* s,int request) {
    if(request!=0 && (request<4 || request>9))return 0;
    if(!request)s->audition=0;
    else if(request!=s->auditionRequest && s->enabled && s->settings[1]!=0 && (request>=7 || s->settings[1]==2))s->audition=request;
    s->auditionRequest=request;return 1;
}
static double width2SoftReduction(double over,double knee,double ratio) {
    double slope=1-1/ratio;
    if(knee>0 && over>-knee*.5 && over<knee*.5)return slope*(over+knee*.5)*(over+knee*.5)/(2*knee);
    return over>0?slope*over:0;
}
static double width2Guard(StereoWidthControl* s,int i,double m,double side,double threshold) {
    WidthAdvanced* a=&s->advanced;WidthMeter* d=&a->candidate[i];meter(d,m+side,m-side,a->guardAlpha,s->peakDecay);
    double rho=correlation(d->l,d->r,d->lr);a->rho[i]=rho;
    if((d->l+d->r)*.5<a->floorPower || rho==2)a->triggered[i]=0;
    else if(rho<threshold)a->triggered[i]=1;
    else if(rho>threshold+s->settings[21]/100.)a->triggered[i]=0;
    double target=a->triggered[i]?s->guardStrength:0;
    return a->guard[i]=follow(a->guard[i],target,target>a->guard[i]?s->attack:s->release);
}
/* parts: phase-coherent dry Mid/Side, low-mono baseline Side, low-mono extra Side. */
static void width2Process(StereoWidthControl* s,double parts[4][4],double dm,double ds,double mid,double base,double* candidate) {
    WidthAdvanced* a=&s->advanced;const int32_t* p=a->settings;
    double extras[4],all=0;int mode=s->settings[1];
    a->processingMix=follow(a->processingMix,mode?a->processingTarget:0,s->alpha);
    for(int i=0;i<4;i++) {
        double x=parts[i][3],old=x;
        for(int j=0;j<2;j++){x=biquad(&a->eq[i][j],x);if(a->eqFade<1)old=biquad(&a->eqOld[i][j],old);}
        if(a->eqFade<1)x=old+a->eqFade*(x-old);
        extras[i]=x;all+=x;
        int at=1+7*i;double M=parts[i][0]*s->midGain,S=parts[i][2]+x;
        a->envelopeM[i]+=(M*M-a->envelopeM[i])*a->envelopeAlpha;
        a->envelopeS[i]+=(S*S-a->envelopeS[i])*a->envelopeAlpha;
        a->envelopeBase[i]+=(parts[i][2]*parts[i][2]-a->envelopeBase[i])*a->envelopeAlpha;
        a->envelopeExtra[i]+=(x*x-a->envelopeExtra[i])*a->envelopeAlpha;
        a->envelopeCross[i]+=(parts[i][2]*x-a->envelopeCross[i])*a->envelopeAlpha;
        double target=0,w=i==3?s->master:s->master*s->bands[i];
        if(p[at] && w>1 && a->envelopeM[i]>1e-12 && a->envelopeS[i]>1e-12) {
            double over=10*log10(a->envelopeS[i]/a->envelopeM[i])-p[at+1]/10.;
            double attenuation=amp(-width2SoftReduction(over,p[at+3]/10.,p[at+2]/10.));
            /* Solve RMS power of baseline + g*EQ(extra); includes their covariance.
               Choosing the upper root withdraws only as far as useful, even when
               the EQ rotates phase. A fixed width formula is inaccurate after EQ. */
            double B=a->envelopeBase[i],E=a->envelopeExtra[i],C=a->envelopeCross[i];
            if(E>1e-20 && E+C>0) {
                double disc=C*C+E*(a->envelopeS[i]*attenuation*attenuation-B);
                double gain=disc>=0?(-C+sqrt(disc))/E:bound(-C/E,0,1);
                target=bound(1-gain,0,p[at+6]/1000.);
            }
        }
        a->dynamic[i]=follow(a->dynamic[i],target,target>a->dynamic[i]?a->dynAttack[i]:a->dynRelease[i]);
        double energy=parts[i][0]*parts[i][0]+parts[i][1]*parts[i][1];
        a->fast[i]+=(energy-a->fast[i])*a->fastAlpha;a->slow[i]+=(energy-a->slow[i])*a->envelopeAlpha;
        a->transientDb[i]=a->fast[i]>1e-12 && a->slow[i]>1e-12?fmax(0,10*log10(a->fast[i]/a->slow[i])):0;
        int trans=p[29] && (i==3 || (p[30]&(1<<i)));
        if(trans && energy>1e-12 && a->transientDb[i]>p[31]/10.)a->hold[i]=p[34]*s->fs/1000.;
        else if(a->hold[i]>0)a->hold[i]-=1;
        double tt=trans && (a->hold[i]>0 || a->transientDb[i]>p[31]/10.)?p[32]/1000.:0;
        a->transient[i]=follow(a->transient[i],tt,tt>a->transient[i]?a->transAttack:a->transRelease);
    }
    if(a->eqFade<1){a->eqFade=fmin(1,a->eqFade+1/(.02*s->fs));if(a->eqFade==1 && a->eqWaiting){memcpy(a->eqOld,a->eq,sizeof(a->eq));memcpy(a->eq,a->eqPending,sizeof(a->eq));a->eqWaiting=0;a->eqFade=0;}}
    double global=0;
    if(p[36] && mode) {
        double gm=mid,gs=base+all;
        if(p[39]){gm=dm+s->mix*(gm-dm);gs=ds+s->mix*(gs-ds);}
        global=width2Guard(s,3,gm,gs,s->settings[20]/100.);
        if(mode==2 && p[37])for(int i=0;i<3;i++) {
            double m=parts[i][0]*s->midGain,side=parts[i][2]+extras[i];
            if(p[39]){m=parts[i][0]+s->mix*(m-parts[i][0]);side=parts[i][1]+s->mix*(side-parts[i][1]);}
            width2Guard(s,i,m,side,p[41+i]/100.);
        }
    } else for(int i=0;i<4;i++){a->guard[i]=follow(a->guard[i],0,s->release);a->triggered[i]=0;}
    double processed=base;
    for(int i=0;i<4;i++) {
        double guard=mode==2 && p[37]?a->guard[i]:global;
        if(!p[36])guard=a->guard[i];
        a->combined[i]=fmax(a->dynamic[i],fmax(a->transient[i],guard));
        a->processedExtra[i]=extras[i]*(1-a->combined[i]);processed+=a->processedExtra[i];
    }
    *candidate+=a->processingMix*(processed-*candidate);
}
static void width2SplitMonitor(WidthBank* b,double m,double side,double out[3][2]) {
    double up,mu;splitFrame(&b->first,side,&out[0][1],&up);splitFrame(&b->second,up,&out[1][1],&out[2][1]);out[0][1]=biquad(&b->lowAlign,out[0][1]);
    splitFrame(&b->midFirst,m,&out[0][0],&mu);splitFrame(&b->midSecond,mu,&out[1][0],&out[2][0]);out[0][0]=biquad(&b->midLowAlign,out[0][0]);
}
static void width2Monitor(StereoWidthControl* s,double l,double r,double left,double right) {
    WidthAdvanced* a=&s->advanced;if(!a->settings[59])return;
    a->meterSamples++;
    double input[3][2],output[3][2];width2SplitMonitor(&a->monitor[0],(l+r)*.5,(l-r)*.5,input);width2SplitMonitor(&a->monitor[1],(left+right)*.5,(left-right)*.5,output);
    for(int i=0;i<3;i++){meter(&a->bands[0][i],input[i][0]+input[i][1],input[i][0]-input[i][1],s->meterAlpha,s->peakDecay);meter(&a->bands[1][i],output[i][0]+output[i][1],output[i][0]-output[i][1],s->meterAlpha,s->peakDecay);}
    if(s->samples%(uint64_t)fmax(1,round(s->fs/8000))==0){int i=a->vectorIndex;a->vectors[2*i]=bound(left,-1,1);a->vectors[2*i+1]=bound(right,-1,1);a->vectorIndex=(i+1)%64;}
}
void StereoWidthAdvancedSnapshot(const StereoWidthControl* s,int32_t* v) {
    const WidthAdvanced* a=&s->advanced;memset(v,0,WIDTH2_RUNTIME_WORDS*4);
    v[0]=1;v[2]=(int32_t)s->fs;v[3]=s->enabled;v[4]=s->settings[1];v[5]=s->enabled && s->samples>=(uint64_t)(s->fs*.1) && (!a->settings[59] || a->meterSamples>=(uint64_t)(s->fs*.1));v[6]=(int32_t)(s->samples&0x7fffffff);
    for(int i=7;i<19;i++)v[i]=INT32_MIN;
    if(v[5] && a->settings[59])for(int i=0;i<3;i++) {
        v[7+i]=rhoInt(&a->bands[0][i]);v[10+i]=rhoInt(&a->bands[1][i]);
        if(a->bands[0][i].m>1e-24 && a->bands[0][i].s>1e-24)v[13+i]=dbPower(a->bands[0][i].s/a->bands[0][i].m);
        if(a->bands[1][i].m>1e-24 && a->bands[1][i].s>1e-24)v[16+i]=dbPower(a->bands[1][i].s/a->bands[1][i].m);
    }
    for(int i=0;i<4;i++) {
        /* Envelopes may stop updating once the audio bypass fade reaches zero.
           Publish applied withdrawal, not a dormant detector's last value. */
        int active=s->enabled && ((s->settings[1]==1 && i==3) || (s->settings[1]==2 && i<3));
        double applied=active?a->processingMix:0;
        double guard=a->settings[36] && !(s->settings[1]==2 && a->settings[37])?a->guard[3]:a->guard[i];
        v[19+i]=(int32_t)llround(applied*a->dynamic[i]*1000);
        v[23+i]=(int32_t)llround(applied*a->transient[i]*1000);
        v[27+i]=(int32_t)llround(applied*guard*1000);
        v[31+i]=(int32_t)llround(applied*a->combined[i]*1000);
        v[35+i]=a->rho[i]==2?INT32_MIN:(int32_t)llround(a->rho[i]*10000);
        v[39+i]=(int32_t)llround(a->transientDb[i]*10);
    }
    v[43]=dbPower(s->autoGain*s->autoGain);v[44]=s->audition;v[45]=a->settings[59];v[46]=(int32_t)fmin(a->settings[45],s->fs*.45);v[47]=(int32_t)fmin(a->settings[49],s->fs*.45);
    if(v[5] && a->settings[59])for(int i=0;i<64;i++){int at=(a->vectorIndex+i)%64;v[48+2*i]=(int32_t)llround(a->vectors[2*at]*1000000);v[49+2*i]=(int32_t)llround(a->vectors[2*at+1]*1000000);}
}
