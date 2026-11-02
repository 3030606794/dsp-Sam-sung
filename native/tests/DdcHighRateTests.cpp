// SPDX-License-Identifier: GPL-3.0-or-later
#include "../ddc/DdcProcessor.h"
#include "Ddc37Reference.h"
#include <algorithm>
#include <array>
#include <cassert>
#include <cmath>
#include <complex>
#include <cstdio>
#include <iomanip>
#include <sstream>
#include <string>
#include <vector>

namespace {
constexpr double pi=3.14159265358979323846;
using Coeff=std::array<double,5>;
Coeff lowPass(unsigned fs){
 const double k=std::tan(pi*1300./fs),d=1+k;
 return{{k/d,k/d,0,(k-1)/d,0}};
}
std::string text(const Coeff& a,const Coeff& b){
 std::ostringstream s;s<<std::setprecision(17);
 auto bank=[&](const Coeff& c){s<<c[0]<<','<<c[1]<<','<<c[2]<<','<<-c[3]<<','<<-c[4];};
 s<<"SR_44100:";bank(a);s<<"\nSR_48000:";bank(b);return s.str();
}
std::complex<double> response(const Coeff& c,double omega){
 const auto z=std::polar(1.,-omega);
 return(c[0]+c[1]*z+c[2]*z*z)/(1.+c[3]*z+c[4]*z*z);
}
struct Core {
 void* p=DdcCreate();
 Core(unsigned fs,const Coeff& a,const Coeff& b,const std::string& source){
  assert(p);DdcConfigure(p,fs);DdcHostRate(p,fs);
  assert(DdcFile(p,a.data(),b.data(),1,source.data(),source.size()));DdcEnabled(p,1);DdcReset(p);
 }
 ~Core(){DdcDestroy(p);}
 std::array<int32_t,80> status()const {std::array<int32_t,80> s{};DdcStatus(p,s.data());return s;}
 void process(std::vector<float>& l,std::vector<float>& r){
  const auto designs=DdcPreparationCountForTest();
  for(size_t i=0;i<l.size();i+=257)DdcProcess(p,l.data()+i,r.data()+i,std::min(size_t(257),l.size()-i));
  assert(DdcPreparationCountForTest()==designs);
  for(float x:l)assert(std::isfinite(x));for(float x:r)assert(std::isfinite(x));
 }
};
}

int main(){
 const auto a=lowPass(44100),b=lowPass(48000);const auto source=text(a,b);
 std::array<int32_t,8> sha{};DdcSha256(source.data(),source.size(),sha.data());
 double maximumError=0;
 for(unsigned fs:{8000u,16000u,32000u,44100u,46000u,48000u,96000u,192000u,384000u,768000u}) {
  Core c(fs,a,b,source);const auto s=c.status();
  assert(s[5]&&!s[50]&&s[2]==int(fs)&&s[17]==int(fs));
  assert(std::equal(sha.begin(),sha.end(),s.begin()+36)&&std::equal(sha.begin(),sha.end(),s.begin()+62));
  for(double hz:{128.,1024.,8192.}) {
   if(hz>=fs*.45)continue;
   DdcReset(c.p);
   const size_t count=fs/2,burn=fs/4;
   std::vector<float> l(count),r(count);
   for(size_t i=0;i<count;++i)l[i]=r[i]=float(.1*std::sin(2*pi*hz*i/fs));
   auto legacyL=l,legacyR=r;
   c.process(l,r);
   if(fs==44100||fs==48000) {
    Ddc37Reference legacy;legacy.load((fs==44100?a:b).data(),1);
    legacy.process(legacyL.data(),legacyR.data(),count);
    assert(l==legacyL&&r==legacyR);
   }
   double sine=0,cosine=0;
   for(size_t i=burn;i<count;++i){const double w=2*pi*hz*i/fs;sine+=l[i]*std::sin(w);cosine+=l[i]*std::cos(w);}
   const auto actual=std::complex<double>(sine,cosine)*(2./(.1*(count-burn)));
   // This independent frequency substitution is equivalent to preserving the analogue pole,
   // rather than reusing unchanged 48 kHz coefficients at an arbitrary host rate.
   const auto& baseline=fs==44100?a:b;
   const double oldOmega=fs==44100||fs==48000?2*pi*hz/fs:
       2*std::atan((double(fs)/48000)*std::tan(pi*hz/fs));
   const auto expected=response(baseline,oldOmega);
   maximumError=std::max(maximumError,std::abs(actual-expected));
   assert(std::abs(actual-expected)<3e-5);
  }
 }
 Core changed(768000,a,b,source);DdcConfigure(changed.p,768001);assert(changed.status()[50]&&!changed.status()[5]);
 DdcConfigure(changed.p,48000);assert(!changed.status()[50]&&changed.status()[5]);
 const auto restored=changed.status();assert(std::equal(sha.begin(),sha.end(),restored.begin()+36));
 std::printf("DDC 8k..768k inverse-BLT response/finite/SHA identity/control-only design, 44.1/48k exact legacy and invalid-rate restoration passed; maximum complex error %.9g\n",maximumError);
}
