// SPDX-License-Identifier: GPL-3.0-or-later
#include "Ddc37Reference.h"
void Ddc37Reference::load(const double* c,size_t n){count=n;for(size_t i=0;i<n;++i)sections[i]={c[i*5],c[i*5+1],c[i*5+2],c[i*5+3],c[i*5+4]};}
void Ddc37Reference::process(float* left,float* right,size_t n){for(size_t i=0;i<n;++i){double sampleOutL=(double)left[i],sampleOutR=(double)right[i];for(size_t j=0;j<count;++j){auto* df2=&sections[j];double w1=sampleOutL-df2->a1*df2->v1L-df2->a2*df2->v2L;double y1=df2->b0*w1+df2->b1*df2->v1L+df2->b2*df2->v2L;df2->v2L=df2->v1L;df2->v1L=w1;sampleOutL=y1;double w2=sampleOutR-df2->a1*df2->v1R-df2->a2*df2->v2R;double y2=df2->b0*w2+df2->b1*df2->v1R+df2->b2*df2->v2R;df2->v2R=df2->v1R;df2->v1R=w2;sampleOutR=y2;}left[i]=(float)sampleOutL;right[i]=(float)sampleOutR;}}
