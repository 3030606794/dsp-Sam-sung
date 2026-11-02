#include "HighShelf.h"
#include <cmath>

double HighShelf::Process(double sample) {
    double out = (((this->x_1 * this->b1 + sample * this->b0 + this->b2 * this->x_2) - this->y_1 * this->a1) - this->a2 * this->y_2) * this->a0;
    this->y_2 = this->y_1;
    this->y_1 = out;
    this->x_2 = this->x_1;
    this->x_1 = sample;
    return out;
}

void HighShelf::SetFrequency(float freq) {
    this->frequency = freq;
}

void HighShelf::SetGain(float gain) {
    this->gain = 20.0 * log10((double) gain);
}

void HighShelf::SetSamplingRate(uint32_t samplingRate) {
    Configure(frequency,static_cast<float>(std::pow(10.0,gain/20.0)),1.f,samplingRate,true);
}

void HighShelf::Configure(float freq,float gainAmplitude,float slope,uint32_t samplingRate,bool resetState) {
    SetFrequency(freq);
    SetGain(gainAmplitude);
    double x = (2 * M_PI * this->frequency) / (double) samplingRate;
    double sinX = sin(x);
    double cosX = cos(x);
    double y = exp((this->gain * log(10.0)) / 40.0);

    if(resetState) this->x_1=this->x_2=this->y_1=this->y_2=0.0;

    double z = slope==1.f ? sqrt(y*2.0)*sinX : sqrt(y)*sinX*sqrt((y+1.0/y)*(1.0/slope-1.0)+2.0);
    double a = (y - 1.0) * cosX;
    double b = (y + 1.0) - a;
    double c = z + b;
    double d = (y + 1.0) * cosX;
    double e = (y + 1.0) + a;
    double f = (y - 1.0) - d;

    this->a0 = 1.0 / c;
    this->a1 = f * 2.0;
    this->a2 = b - z;
    this->b0 = (e + z) * y;
    this->b1 = -y * 2.0 * ((y - 1.0) + d);
    this->b2 = (e - z) * y;
}
