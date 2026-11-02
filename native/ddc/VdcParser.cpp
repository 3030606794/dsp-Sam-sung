// SPDX-License-Identifier: GPL-3.0-or-later
#include "DdcProcessor.h"
#include <array>
#include <cerrno>
#include <cmath>
#include <cstdlib>
#include <cstring>
namespace {
bool space(char c){return c==' '||c=='\t'||c=='\r'||c=='\n';}
bool digit(char c){return c>='0'&&c<='9';}
struct Parser {
 const char *p,*end;
 void ws(){while(p<end&&space(*p))++p;}
 bool literal(const char* text){const auto n=std::strlen(text);if(size_t(end-p)<n||std::memcmp(p,text,n))return false;p+=n;return true;}
 bool number(double& value){
  ws();const char* start=p;if(p<end&&(*p=='+'||*p=='-'))++p;
  bool digits=false;while(p<end&&digit(*p)){digits=true;++p;}
  if(p<end&&*p=='.'){++p;while(p<end&&digit(*p)){digits=true;++p;}}
  if(!digits)return false;
  if(p<end&&(*p=='e'||*p=='E')){++p;if(p<end&&(*p=='+'||*p=='-'))++p;const char* exponent=p;while(p<end&&digit(*p))++p;if(p==exponent)return false;}
  const auto n=size_t(p-start);if(n>=128)return false;char token[128];std::memcpy(token,start,n);token[n]=0;char* tail=nullptr;errno=0;value=std::strtod(token,&tail);
  return tail==token+n&&std::isfinite(value)&&std::abs(value)<=1e12;
 }
};
bool stable(const double* c){constexpr double e=1e-12;return std::abs(c[4])<=1-e&&1+c[3]+c[4]>=e&&1-c[3]+c[4]>=e;}
}
extern "C" int DdcParse(const char* text,size_t length,double* output441,double* output480,size_t* count){
 if(!text||!output441||!output480||!count||!length||length>DDC_MAX_FILE_BYTES)return 0;
 size_t offset=length>=3&&static_cast<unsigned char>(text[0])==239&&static_cast<unsigned char>(text[1])==187&&static_cast<unsigned char>(text[2])==191?3:0;
 for(size_t i=offset;i<length;++i)if(static_cast<unsigned char>(text[i])>127||text[i]==0)return 0;
 std::array<double,DDC_MAX_SOS*5> c441{},c480{};size_t sizes[2]{};bool seen[2]{};Parser parser{text+offset,text+length};
 for(unsigned section=0;section<2;++section){
  parser.ws();int index=-1;if(parser.literal("SR_44100"))index=0;else if(parser.literal("SR_48000"))index=1;else return 0;
  if(seen[index])return 0;seen[index]=true;parser.ws();if(!parser.literal(":"))return 0;
  auto& coefficients=index?c480:c441;size_t numbers=0;
  for(;;){if(numbers>=coefficients.size()||!parser.number(coefficients[numbers]))return 0;++numbers;parser.ws();if(parser.p==parser.end||*parser.p!=',')break;++parser.p;}
  if(!numbers||numbers%5)return 0;sizes[index]=numbers/5;
  for(size_t i=0;i<numbers;i+=5){coefficients[i+3]=-coefficients[i+3];coefficients[i+4]=-coefficients[i+4];if(!stable(coefficients.data()+i))return 0;}
 }
 parser.ws();if(parser.p!=parser.end||sizes[0]!=sizes[1])return 0;
 std::memcpy(output441,c441.data(),sizes[0]*5*sizeof(double));std::memcpy(output480,c480.data(),sizes[0]*5*sizeof(double));*count=sizes[0];return 1;
}
namespace {
constexpr uint32_t shaK[64]={0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2};
uint32_t ror(uint32_t n,unsigned bits){return(n>>bits)|(n<<(32-bits));}
void shaBlock(uint32_t* h,const unsigned char* b){
 uint32_t w[64];for(unsigned i=0;i<16;++i)w[i]=uint32_t(b[i*4])<<24|uint32_t(b[i*4+1])<<16|uint32_t(b[i*4+2])<<8|b[i*4+3];
 for(unsigned i=16;i<64;++i){const uint32_t x=w[i-15],y=w[i-2];w[i]=w[i-16]+(ror(x,7)^ror(x,18)^(x>>3))+w[i-7]+(ror(y,17)^ror(y,19)^(y>>10));}
 uint32_t a=h[0],c=h[2],b0=h[1],d=h[3],e=h[4],f=h[5],g=h[6],j=h[7];
 for(unsigned i=0;i<64;++i){const uint32_t t1=j+(ror(e,6)^ror(e,11)^ror(e,25))+((e&f)^(~e&g))+shaK[i]+w[i],t2=(ror(a,2)^ror(a,13)^ror(a,22))+((a&b0)^(a&c)^(b0&c));j=g;g=f;f=e;e=d+t1;d=c;c=b0;b0=a;a=t1+t2;}
 h[0]+=a;h[1]+=b0;h[2]+=c;h[3]+=d;h[4]+=e;h[5]+=f;h[6]+=g;h[7]+=j;
}
}
extern "C" void DdcSha256(const char* input,size_t length,int32_t* output){
 if(!output)return;uint32_t h[8]={0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
 const auto* p=reinterpret_cast<const unsigned char*>(input);size_t left=length;
 while(left>=64){shaBlock(h,p);p+=64;left-=64;}
 unsigned char tail[128]{};if(left)std::memcpy(tail,p,left);tail[left]=128;const size_t padded=left<56?64:128;const uint64_t bits=uint64_t(length)*8;
 for(unsigned i=0;i<8;++i)tail[padded-1-i]=static_cast<unsigned char>(bits>>(8*i));shaBlock(h,tail);if(padded==128)shaBlock(h,tail+64);
 for(unsigned i=0;i<8;++i){const auto x=h[i];const uint32_t little=(x>>24)|((x>>8)&0xff00)|((x<<8)&0xff0000)|(x<<24);std::memcpy(output+i,&little,4);}
}
