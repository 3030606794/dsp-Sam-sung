#include "essential.h"
#include <algorithm>
#include <array>
#include <cassert>
#include <chrono>
#include <cmath>
#include <complex>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <fstream>
#include <string>
#include <thread>
#include <vector>

extern "C" audio_effect_library_t AELI;
unsigned fs=192000;
bool paced=false;
constexpr double pi=3.14159265358979323846,frequency=1000,amplitude=.0002;
constexpr effect_uuid_t uuid{0x30306067,0x9400,0x4d53,0x9000,{0,0,0,0,0,1}};
struct Engine {
 effect_handle_t h{};
 Engine(){assert(!AELI.create_effect(&uuid,fs==48000?0:4041,21,&h));effect_config_t c{};c.inputCfg.samplingRate=c.outputCfg.samplingRate=fs;c.inputCfg.channels=c.outputCfg.channels=AUDIO_CHANNEL_OUT_STEREO;c.inputCfg.format=c.outputCfg.format=AUDIO_FORMAT_PCM_FLOAT;c.inputCfg.accessMode=EFFECT_BUFFER_ACCESS_READ;c.outputCfg.accessMode=EFFECT_BUFFER_ACCESS_WRITE;c.inputCfg.mask=c.outputCfg.mask=EFFECT_CONFIG_ALL;assert(!command(EFFECT_CMD_SET_CONFIG,sizeof(c),&c));assert(!command(EFFECT_CMD_ENABLE));int32_t off=0;assert(!raw(131073,&off,4));}
 ~Engine(){AELI.release_effect(h);}
 int command(uint32_t id,uint32_t size=0,void* data=nullptr){int32_t status=-999;uint32_t n=4;int r=(*h)->command(h,id,size,data,&n,&status);return r?r:status;}
 int raw(uint32_t id,const void* values,unsigned n){std::vector<uint32_t> p((16+n+3)/4);p[1]=4;p[2]=n;p[3]=id;std::memcpy(p.data()+4,values,n);return command(EFFECT_CMD_SET_PARAM,16+n,p.data());}
 void load(const std::vector<float>& kernel){auto k=kernel;const unsigned chunks=(k.size()+4095)/4096;std::array<int32_t,4> header{int(k.size()),2,0,int(chunks)};assert(!raw(9999,header.data(),16));k.resize(chunks*4096);for(unsigned i=0;i<chunks;++i)assert(!raw(12000,k.data()+i*4096,16384));int16_t on=1;assert(!raw(10004,&on,2));assert(!raw(1205,&on,2));}
 std::vector<float> render(unsigned block,double& maxMs,double& meanMs,double& maxCpuMs){const size_t frames=fs*2,guard=32;const float marker=123456.25f;std::vector<float> in(frames*2+guard*2,marker),out(in.size(),marker);for(size_t i=0;i<frames;++i){float x=amplitude*std::sin(2*pi*frequency*i/fs);in[guard+2*i]=x;in[guard+2*i+1]=x;}
  unsigned callbacks=0;double total=0;
  // Preparation runs on this control thread; processing owns a separate audio thread.
  std::thread audio([&]{const auto epoch=std::chrono::steady_clock::now();for(size_t i=0;i<frames;i+=block){audio_buffer_t a{},b{};a.frameCount=b.frameCount=std::min(size_t(block),frames-i);a.f32=in.data()+guard+2*i;b.f32=out.data()+guard+2*i;auto begin=std::chrono::steady_clock::now();const auto cpu=std::clock();assert(!(*h)->process(h,&a,&b));maxCpuMs=std::max(maxCpuMs,1000.*(std::clock()-cpu)/CLOCKS_PER_SEC);double ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-begin).count();maxMs=std::max(maxMs,ms);total+=ms;++callbacks;if(paced)std::this_thread::sleep_until(epoch+std::chrono::duration<double>(double(i+a.frameCount)/fs));}});audio.join();meanMs=total/callbacks;
  for(size_t i=0;i<guard;++i){assert(in[i]==marker&&out[i]==marker);assert(in[in.size()-1-i]==marker&&out[out.size()-1-i]==marker);}return {out.begin()+guard,out.end()-guard};
 }
};

int main(int argc,char** argv){assert(argc>=2 && argc<=4);if(argc>=3)fs=unsigned(std::stoul(argv[2]));if(argc==4){assert(std::string(argv[3])=="--paced");paced=true;}assert(fs>=44100&&fs<=768000);std::ifstream f(argv[1],std::ios::binary|std::ios::ate);assert(f);size_t bytes=f.tellg();assert(bytes%8==0);f.seekg(0);std::vector<float> kernel(bytes/4);f.read(reinterpret_cast<char*>(kernel.data()),bytes);assert(f);const size_t irFrames=kernel.size()/2;std::complex<double> response[2]{};for(size_t n=0;n<irFrames;++n)for(unsigned c=0;c<2;++c)response[c]+=double(kernel[2*n+c])*std::polar(1.,-2*pi*frequency*n/fs);
 std::vector<float> baseline;double largestReferenceError=0,largestChunkError=0;
 for(unsigned block:{fs/50,512u,256u,509u}){Engine e;e.load(kernel);double maxMs=0,meanMs=0,maxCpuMs=0;auto y=e.render(block,maxMs,meanMs,maxCpuMs);double error=0,peak=0,square=0;size_t finite=0;
  for(size_t n=0;n<y.size()/2;++n)for(unsigned c=0;c<2;++c){double sample=y[2*n+c];assert(std::isfinite(sample));++finite;peak=std::max(peak,std::abs(sample));if(n>irFrames+8192){double phase=2*pi*frequency*n/fs;double expected=amplitude*(response[c].real()*std::sin(phase)+response[c].imag()*std::cos(phase));error=std::max(error,std::abs(sample-expected));square+=sample*sample;}}
  if(baseline.empty())baseline=y;else for(size_t i=0;i<y.size();++i)largestChunkError=std::max(largestChunkError,std::abs(double(y[i]-baseline[i])));
  largestReferenceError=std::max(largestReferenceError,error);std::printf("%u Hz IR%zu stereo hostBlock%u: finite%zu peak%.9g steadyDTFTMaxError%.9g hostMeanMs%.6f hostMaxMs%.6f processCpuMaxMs%.6f paced%d guardOK\n",fs,irFrames,block,finite,peak,error,meanMs,maxMs,maxCpuMs,paced);std::fflush(stdout);assert(error<.00005);
 }
 std::printf("PASS real2ch high-rate long IR: maxDTFTerror %.9g, maxAcrossChunkError %.9g; isolated-process timings are not live playback guarantees.\n",largestReferenceError,largestChunkError);assert(largestChunkError<.00005);
}
