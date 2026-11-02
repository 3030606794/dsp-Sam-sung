// SPDX-License-Identifier: GPL-3.0-or-later
#include "../peq/PeqProcessor.h"
#include <array>
#include <algorithm>
#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstring>
namespace dspfusion {struct PeqTestAccess {static size_t count(const PeqProcessor& e){return e.pending_.count;}};}
std::array<int32_t,9> packet(int type,int freq,int gain,double q){uint64_t bits;std::memcpy(&bits,&q,8);return {3,1,1,type,1,freq,gain,int32_t(bits),int32_t(bits>>32)};}
int main(){unsigned cases=0;double maximum=0;
for(unsigned fs:{48000u,96000u,192000u,384000u,768000u})for(int type=0;type<7;++type)for(int gain:{-100000,100000})for(double q:{.0001,30000.}){
 dspfusion::PeqProcessor exact,inside;exact.configure(fs);inside.configure(fs);auto p=packet(type,int(fs*500),gain,q);assert(exact.setParameters(p.data(),p.size()));assert(dspfusion::PeqTestAccess::count(exact)==1);auto above=p;++above[5];dspfusion::PeqProcessor future;future.configure(fs);assert(future.setParameters(above.data(),above.size()));assert(dspfusion::PeqTestAccess::count(future)==0);--p[5];assert(inside.setParameters(p.data(),p.size()));
 std::array<float,1024> a{},b{};const unsigned frames=fs*2;
 for(unsigned offset=0;offset<frames;offset+=512){unsigned n=std::min(512u,frames-offset);for(unsigned j=0;j<n;++j){const auto i=offset+j;a[2*j]=float(1e-7*(i%2?-1.:1.));a[2*j+1]=float(1e-7*std::sin(i*.03));}b=a;exact.process(a.data(),n);inside.process(b.data(),n);assert(a==b);
  for(unsigned j=0;j<n*2;++j){assert(std::isfinite(a[j])&&std::abs(a[j])<1000);maximum=std::max(maximum,std::abs(double(a[j])));}
 }
 ++cases;
}
std::printf("PEQ exact Nyquist boundary: %u two-second streams, all7 types48..768k +/-100dB Q.0001/30000; bit-exact to prior accepted Nyquist-.001Hz, finite and no additional growth. Max output %.9g.\n",cases,maximum);
}
