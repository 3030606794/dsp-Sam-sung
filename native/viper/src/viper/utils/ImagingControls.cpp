#include "ImagingControls.h"
#include <algorithm>
#include <cmath>

void ImagingLowpass::Configure(float hz,uint32_t rate) {
    const double k=std::tan(3.14159265358979323846*hz/rate);
    b=k/(1+k);a=(k-1)/(k+1);
}
double ImagingLowpass::Process(double sample) {
    const double output=b*sample+state;
    state=b*sample-a*output;
    return output;
}
void ImagingMeter::ClearWindow() {
    count=0;meanL=meanR=varL=varR=cov=midEnergy=sideEnergy=peak=0;
}
void ImagingMeter::Reset(uint32_t rate) {
    window=std::max(1u,(rate+9)/10);ClearWindow();result.fill(invalid);ready=false;
}
void ImagingMeter::Process(float left,float right) {
    ++count;
    const double dl=left-meanL,dr=right-meanR;
    meanL+=dl/count;meanR+=dr/count;
    varL+=dl*(left-meanL);varR+=dr*(right-meanR);cov+=dl*(right-meanR);
    const double mid=(double(left)+right)*.5,side=(double(left)-right)*.5;
    midEnergy+=mid*mid;sideEnergy+=side*side;
    peak=std::max({peak,std::abs(double(left)),std::abs(double(right))});
    if(count<window)return;
    result[0]=varL>1.e-20 && varR>1.e-20?static_cast<int32_t>(std::lround(std::clamp(cov/std::sqrt(varL*varR),-1.,1.)*10000)):invalid;
    const auto level=[](double power){return power>1.e-30?static_cast<int32_t>(std::lround(1000*std::log10(power))):invalid;};
    result[1]=level(midEnergy/count);result[2]=level(sideEnergy/count);result[3]=level(peak*peak);
    ready=true;ClearWindow();
}
int32_t ImagingMeter::Get(unsigned index) const {return index<4?result[index]:ready?1:0;}
