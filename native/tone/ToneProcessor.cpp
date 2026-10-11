// SPDX-License-Identifier: GPL-3.0-or-later
#include "ToneProcessor.h"
#include <algorithm>
#include <cmath>
#include <complex>

namespace dspfusion {
namespace {
constexpr double pi = 3.14159265358979323846;
double coefficient(double seconds, double rate) { return 1.0-std::exp(-1.0/(seconds*rate)); }
double gain(double db) { return std::pow(10.0, db/20.0); }
double clean(double value) { return std::isfinite(value) ? std::clamp(value,-64.0,64.0) : 0.0; }
}
ToneProcessor::ToneProcessor() { configure(48000); }
void ToneProcessor::configure(uint32_t rate) {
    sampleRate_=std::clamp(rate,8000u,384000u);
    alpha_=coefficient(0.020,sampleRate_);
    lowAlpha_=1.0-std::exp(-2.0*pi*150.0/sampleRate_);
    fastAlpha_=coefficient(0.002,sampleRate_);
    slowAlpha_=coefficient(0.045,sampleRate_);
    bassFastAlpha_=coefficient(0.005,sampleRate_);
    bassSlowAlpha_=coefficient(0.070,sampleRate_);
    design();
    reset();
}
bool ToneProcessor::setParameters(const int32_t* values,size_t count) {
    if(!values || count!=11) return false;
    enabled_=values[0]!=0;
    for(size_t i=0;i<knobs_.size();++i) knobs_[i]=std::clamp(values[i+1],-100,100);
    design();
    return true;
}
void ToneProcessor::Filter::clear() { z1[0]=z1[1]=z2[0]=z2[1]=0; }
void ToneProcessor::Filter::advance(double a) {
    now.b0+=a*(target.b0-now.b0); now.b1+=a*(target.b1-now.b1);
    now.b2+=a*(target.b2-now.b2); now.a1+=a*(target.a1-now.a1);
    now.a2+=a*(target.a2-now.a2);
}
double ToneProcessor::Filter::tick(double input,unsigned c) {
    const double output=now.b0*input+z1[c];
    z1[c]=now.b1*input-now.a1*output+z2[c];
    z2[c]=now.b2*input-now.a2*output;
    if(std::abs(z1[c])<1e-24) z1[c]=0;
    if(std::abs(z2[c])<1e-24) z2[c]=0;
    if(!std::isfinite(output) || !std::isfinite(z1[c]) || !std::isfinite(z2[c])) {
        z1[c]=z2[c]=0; return 0;
    }
    return output;
}
ToneProcessor::Coeff ToneProcessor::peak(double rate,double hz,double q,double db) {
    if(db==0) return {};
    const double w=2*pi*std::min(hz,rate*0.43)/rate;
    const double a=std::pow(10.0,db/40.0), cs=std::cos(w), al=std::sin(w)/(2*q), d=1+al/a;
    return {(1+al*a)/d,-2*cs/d,(1-al*a)/d,-2*cs/d,(1-al/a)/d};
}
ToneProcessor::Coeff ToneProcessor::shelf(double rate,double hz,double db,bool high) {
    if(db==0) return {};
    const double w=2*pi*std::min(hz,rate*0.43)/rate;
    const double a=std::pow(10.0,db/40.0), cs=std::cos(w), t=std::sqrt(2*a)*std::sin(w);
    if(high) {
        const double d=(a+1)-(a-1)*cs+t;
        return {a*((a+1)+(a-1)*cs+t)/d,-2*a*((a-1)+(a+1)*cs)/d,
            a*((a+1)+(a-1)*cs-t)/d,2*((a-1)-(a+1)*cs)/d,((a+1)-(a-1)*cs-t)/d};
    }
    const double d=(a+1)+(a-1)*cs+t;
    return {a*((a+1)-(a-1)*cs+t)/d,2*a*((a-1)-(a+1)*cs)/d,
        a*((a+1)-(a-1)*cs-t)/d,-2*((a-1)+(a+1)*cs)/d,((a+1)+(a-1)*cs-t)/d};
}
double ToneProcessor::magnitude(const Coeff& c,double w) {
    const auto z=std::polar(1.0,-w);
    return std::abs((c.b0+c.b1*z+c.b2*z*z)/(1.0+c.a1*z+c.a2*z*z));
}
void ToneProcessor::design() {
    const auto value=[this](size_t i) { return knobs_[i]/100.0; };
    filters_[0].target=shelf(sampleRate_,180,4*value(0),false);
    filters_[1].target=shelf(sampleRate_,3500,-4*value(0),true);
    filters_[2].target=shelf(sampleRate_,60,6*value(1),false);
    filters_[3].target=peak(sampleRate_,110,0.8,3*value(2));
    filters_[4].target=peak(sampleRate_,350,0.75,4*value(3));
    filters_[5].target=peak(sampleRate_,1800,0.8,4*value(4));
    filters_[6].target=peak(sampleRate_,3500,1.0,3*value(5));
    filters_[7].target=peak(sampleRate_,5500,1.1,4*value(6));
    filters_[8].target=peak(sampleRate_,8500,1.1,4*value(7));
    filters_[9].target=shelf(sampleRate_,12000,4*value(9),true);
    punchTarget_=value(2);
    transientTarget_=value(8);
    wetTarget_=enabled_ && std::any_of(knobs_.begin(),knobs_.end(),[](int32_t x){return x!=0;}) ? 1.0 : 0.0;
    // Reserve gain for the composed response and both bounded dynamic stages.
    // This changes loudness deliberately; it is not a claim of loudness matching.
    double maximum=1.0;
    for(unsigned i=0;i<=192;++i) {
        const double f=20*std::pow((sampleRate_*0.499)/20,i/192.0);
        double response=1;
        for(const auto& filter:filters_) response*=magnitude(filter.target,2*pi*f/sampleRate_);
        maximum=std::max(maximum,response);
    }
    preampTarget_=1.0/(maximum*gain(3*std::abs(punchTarget_)+3*std::abs(transientTarget_)));
}
void ToneProcessor::reset() {
    for(auto& filter:filters_) { filter.clear(); filter.now=filter.target; }
    low_[0]=low_[1]=fast_=slow_=bassFast_=bassSlow_=0;
    preamp_=preampTarget_; punch_=punchTarget_; transient_=transientTarget_; wet_=wetTarget_;
}
void ToneProcessor::process(float* data,uint32_t frames) {
    if(!data) return;
    if(wetTarget_==0 && wet_==0) return; // Neutral and bypass preserve original float samples exactly.
    for(uint32_t i=0;i<frames;++i) {
        wet_+=alpha_*(wetTarget_-wet_);
        preamp_+=alpha_*(preampTarget_-preamp_);
        punch_+=alpha_*(punchTarget_-punch_);
        transient_+=alpha_*(transientTarget_-transient_);
        for(auto& filter:filters_) filter.advance(alpha_);
        const double dry[2]={clean(data[2*i]),clean(data[2*i+1])};
        double x[2]={dry[0]*preamp_,dry[1]*preamp_};
        for(unsigned c=0;c<2;++c) {
            for(auto& filter:filters_) x[c]=filter.tick(x[c],c);
            low_[c]+=lowAlpha_*(x[c]-low_[c]);
        }
        const double level=std::max(std::abs(x[0]),std::abs(x[1]));
        const double bass=std::max(std::abs(low_[0]),std::abs(low_[1]));
        fast_+=fastAlpha_*(level-fast_); slow_+=slowAlpha_*(level-slow_);
        bassFast_+=bassFastAlpha_*(bass-bassFast_); bassSlow_+=bassSlowAlpha_*(bass-bassSlow_);
        const double onset=std::clamp((fast_-slow_)/(slow_+1e-5),0.0,1.0);
        const double bassOnset=std::clamp((bassFast_-bassSlow_)/(bassSlow_+1e-5),0.0,1.0);
        const double punchGain=gain(3*punch_*bassOnset)-1;
        const double transientGain=gain(3*transient_*onset);
        for(unsigned c=0;c<2;++c) {
            const double wet=(x[c]+punchGain*low_[c])*transientGain;
            data[2*i+c]=static_cast<float>(clean(dry[c]+wet_*(wet-dry[c])));
        }
        if(wetTarget_==0 && wet_<1e-7) {
            wet_=0;
            for(auto& filter:filters_) filter.clear();
            low_[0]=low_[1]=fast_=slow_=bassFast_=bassSlow_=0;
            return; // Remainder is already the untouched input.
        }
    }
}
}
