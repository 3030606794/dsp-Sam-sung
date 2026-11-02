// SPDX-License-Identifier: GPL-3.0-or-later
#include <array>
#include <algorithm>
#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <limits>
#include <memory>
#include <string>
#include <vector>
extern "C" {
#include "../../app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/jdsp_header.h"
}
#undef min
#undef max
namespace {
using Stereo = std::array<std::vector<float>, 2>;
std::string read(const std::filesystem::path& path) {
    std::ifstream input(path, std::ios::binary);
    assert(input);
    return {std::istreambuf_iterator<char>(input), {}};
}
Stereo signal(unsigned n, bool impulse = false) {
    Stereo out{{std::vector<float>(n), std::vector<float>(n)}};
    for (unsigned i = 0; i < n; ++i) {
        out[0][i] = impulse ? (i == 0 ? .25f : 0.f) : float(.02 * std::sin(i * .071) + .01 * std::sin(i * .29));
        out[1][i] = impulse ? (i == 0 ? .2f : 0.f) : float(.015 * std::sin(i * .113) + .01 * std::sin(i * .017));
    }
    return out;
}
double energy(const Stereo& input) {
    double result = 0;
    for (const auto& channel : input) for (float value : channel) result += double(value) * value;
    return result;
}
struct Core {
    std::unique_ptr<JamesDSPLib> owner = std::make_unique<JamesDSPLib>();
    JamesDSPLib* dsp = owner.get();
    explicit Core(unsigned rate = 48000) {
        dsp->fs = dsp->trueSampleRate = float(rate);
        assert(!pthread_mutex_init(&dsp->m_in_processing, nullptr));
        dsp->isMutexSuccess = 1;
        LiveProgConstructor(dsp);
        LiveProgEnable(dsp);
    }
    ~Core() { LiveProgDestructor(dsp); pthread_mutex_destroy(&dsp->m_in_processing); }
    int load(const std::string& text) { return LiveProgStringParser(dsp, const_cast<char*>(text.c_str())); }
    float value(const char* name) { const auto* result = NSEEL_VM_getvar(dsp->eel.vm, name); assert(result); return *result; }
    void set(const char* name, double value) { assert(LiveProgSetParameter(dsp, name, value) == 1); }
    Stereo render(Stereo input) {
        dsp->tmpBuffer[0] = input[0].data(); dsp->tmpBuffer[1] = input[1].data();
        LiveProgProcess(dsp, input[0].size());
        for (const auto& channel : input) for (float value : channel) assert(std::isfinite(value));
        return input;
    }
};
void transactionAndParsing() {
    Core c, reference;
    const std::string source = "@init\r\ngain=0.25; phase=0;\r\n@sample\r\nphase+=1; spl0=spl0*gain+phase*.000001; spl1*=gain;";
    assert(c.load(source) == 1 && reference.load(source) == 1);
    assert(c.render(signal(31)) == reference.render(signal(31)));
    const auto vm = c.dsp->eel.vm;
    for (const auto& malformed : std::vector<std::pair<std::string, int>>{
        {"@init\ngain=999;\n@sample\nspl0 = (;", -3},
        {"@init\ngain=(;\n@sample\nspl0=0;", -1},
        {"@init\ngain=999;\n@slider\ngain=(;\n@sample\nspl0=0;", -6},
        {"@sample\nspl0=0;", 0}, {"@init\ngain=999;", -2},
        {"@init\ngain=999;\n@init\ngain=3;\n@sample\nspl0=0;", -7}}) {
        assert(c.load(malformed.first) == malformed.second);
        assert(c.dsp->eel.vm == vm && c.dsp->eel.compileSucessfully && c.dsp->liveprogEnabled);
        assert(LiveProgGetStatus(c.dsp) == malformed.second && std::strlen(LiveProgGetLastError(c.dsp)) > 0);
        assert(c.render(signal(19)) == reference.render(signal(19)));
    }
    assert(c.load("// @sample must not split code\n/*\n@init\n*/\n@sample\nspl0*=gain;spl1*=gain;\n@init\ngain=.4;") == 1);
    assert(c.value("gain") == .4f && !*LiveProgGetLastError(c.dsp));
    std::puts("LiveProg init/slider/sample compilation failures preserve VM, enabled state and running audio history; CRLF, reversed sections, marker comments, duplicate/missing sections and detailed errors passed.");
}
void liveParametersAndRate() {
    Core c;
    assert(c.load("@init\ngain=.5;delayMs=10;pos=0;\n@slider\nmult=gain*2;delaySamples=srate*delayMs*.001;\n@sample\nspl0=pos[0]*mult;pos[0]=spl1;pos=(pos+1)%4;spl1*=mult;") == 1);
    c.render(signal(3));
    const auto vm = c.dsp->eel.vm;
    const auto position = c.value("pos");
    const auto ram = std::array<float, 4>{((compileContext*)vm)->ram_state[0], ((compileContext*)vm)->ram_state[1], ((compileContext*)vm)->ram_state[2], ((compileContext*)vm)->ram_state[3]};
    c.set("gain", .501);
    assert(c.dsp->eel.vm == vm && c.value("pos") == position && std::abs(c.value("mult") - 1.002f) < 1e-7);
    assert(!std::memcmp(ram.data(), ((compileContext*)vm)->ram_state, sizeof(ram)));
    for (const char* name : {"missing", "srate", "SPL0", "spl1", "1invalid", "bad-name", ""})
        assert(LiveProgSetParameter(c.dsp, name, 1) < 0);
    assert(!NSEEL_VM_getvar(c.dsp->eel.vm, "missing"));
    for (double value : {std::numeric_limits<double>::infinity(), std::numeric_limits<double>::quiet_NaN(), 1e200})
        assert(LiveProgSetParameter(c.dsp, "gain", value) < 0);
    assert(c.value("gain") == .501f);
    c.set("delayMs", 25.001);
    LiveProgFailNextPreparationForTest();
    jdsp_lock(c.dsp); c.dsp->fs = 44100; assert(LiveProgRefreshLocked(c.dsp) == -5); jdsp_unlock(c.dsp);
    assert(c.dsp->eel.vm == vm && c.dsp->eel.compiledSampleRate == 48000 && c.dsp->eel.parameters);
    const auto bypass = signal(20);
    assert(c.render(bypass) == bypass);
    jdsp_lock(c.dsp); c.dsp->fs = 44100; assert(LiveProgRefreshLocked(c.dsp) == 1); jdsp_unlock(c.dsp);
    assert(c.dsp->eel.vm != vm && c.value("srate") == 44100 && c.value("pos") == 0);
    assert(std::abs(c.value("gain") - .501f) < 1e-7 && std::abs(c.value("delaySamples") - 1102.5441f) < .001);
    assert(c.load("@init\ngain=.75;\n@sample\nspl0*=gain;spl1*=gain;") == 1);
    assert(!c.dsp->eel.parameters && c.value("gain") == .75f);
    std::puts("LiveProg precision .001, derived @slider values, unchanged delay RAM/cursor, strict existing-variable validation, nonfinite rejection, new-source override cleanup and 48k->44.1k complete reinit with parameter overrides passed.");
}
void builtins() {
    const std::filesystem::path base(FUSION_LIVEPROG_ASSET_DIR);
    unsigned count = 0, exact = 0;
    for (const auto& entry : std::filesystem::directory_iterator(base / "LiveprogRuntime")) {
        if (entry.path().extension() != ".eel") continue;
        const auto filename = entry.path().filename();
        Core original, runtime;
        const auto source = read(base / "Liveprog" / filename), adapted = read(entry.path());
        const int first = original.load(source), second = runtime.load(adapted);
        if (first != 1 || second != 1) std::printf("Asset compile failure %s original=%d runtime=%d %s\n", filename.string().c_str(), first, second, LiveProgGetLastError(runtime.dsp));
        assert(first == 1 && second == 1 && runtime.dsp->eel.codehandleSlider);
        const auto input = signal(2048);
        const auto a = original.render(input), b = runtime.render(input);
        if (filename != "audioGlitchGenerator.eel") {
            double maximum = 0;
            for (unsigned ch = 0; ch < 2; ++ch) for (unsigned i = 0; i < input[ch].size(); ++i)
                maximum = std::max(maximum, std::abs(double(a[ch][i]) - b[ch][i]));
            if (maximum > 1e-6) std::printf("Asset mismatch %s maximum %.9g\n", filename.string().c_str(), maximum);
            assert(maximum <= 1e-6); ++exact;
        }
        ++count;
    }
    assert(count == 22 && exact == 21);
    std::printf("All %u parameterized builtin runtime adapters compile and produce finite audio; %u deterministic adapters retain original initial output (random glitch generator excluded from equality).\n", count, exact);
}
void builtinHistory() {
    const std::filesystem::path base(FUSION_LIVEPROG_ASSET_DIR);
    for (const auto& item : std::vector<std::pair<std::string, std::string>>{{"hadamVerb.eel", "slider20"}, {"delayChorus.eel", "slider5"}, {"timeAdjustment.eel", "slider2"}, {"fractionalDelayline.eel", "rightDelay"}}) {
        Core runtime;
        assert(runtime.load(read(base / "LiveprogRuntime" / item.first)) == 1);
        if (item.first == "timeAdjustment.eel") runtime.set("slider1", 20);
        if (item.first == "fractionalDelayline.eel") { runtime.set("leftDelay", 80); runtime.set("rightDelay", 80); }
        runtime.render(signal(item.first == "hadamVerb.eel" ? 12000 : 32, true));
        auto* context = (compileContext*)runtime.dsp->eel.vm;
        const auto vm = runtime.dsp->eel.vm;
        std::vector<float> before(context->ram_state, context->ram_state + 600000);
        const double previous = runtime.value(item.second.c_str());
        runtime.set(item.second.c_str(), previous + (item.first == "fractionalDelayline.eel" ? .1 : 1));
        assert(runtime.dsp->eel.vm == vm);
        if (item.first != "fractionalDelayline.eel") assert(!std::memcmp(before.data(), context->ram_state, before.size() * sizeof(float)));
        const auto out = runtime.render(Stereo{{std::vector<float>(20000), std::vector<float>(20000)}});
        if (energy(out) <= 1e-10) std::printf("No retained tail: %s\n", item.first.c_str());
        assert(energy(out) > 1e-10);
    }
    for (const std::string name : {"3bandSplitting.eel", "8bandSplitting.eel"}) {
        Core runtime;
        assert(runtime.load(read(base / "LiveprogRuntime" / name)) == 1);
        runtime.render(signal(100));
        const auto vm = runtime.dsp->eel.vm;
        const size_t offset = size_t(runtime.value("iirBPS1")) + 1;
        auto* filter = (LinkwitzRileyCrossover*)(((compileContext*)vm)->ram_state + offset);
        const auto before = *filter;
        runtime.set("freqSplit1", 1234);
        assert(runtime.dsp->eel.vm == vm && filter->b1 != before.b1);
        assert(filter->lp_xm0 == before.lp_xm0 && filter->lp_xm1 == before.lp_xm1 && filter->hp_xm0 == before.hp_xm0 && filter->hp_xm1 == before.hp_xm1);
        runtime.render(signal(100));
    }
    std::puts("Builtin Hadamard reverb, chorus delay, time adjustment and fractional delay keep live impulse tails across parameter changes; 3/8 band splitters change coefficients while retaining sample history.");
}
void controllerRate() {
    auto dsp = std::make_unique<JamesDSPLib>();
    JamesDSPInit(dsp.get(), 128, 48000);
    assert(LiveProgStringParser(dsp.get(), const_cast<char*>("@init\ncutoff=100;rateCopy=srate;\n@slider\ncoefficient=cutoff/srate;\n@sample\nspl0*=coefficient;spl1*=coefficient;")) == 1);
    assert(LiveProgSetParameter(dsp.get(), "cutoff", 123.001) == 1);
    LiveProgFailNextPreparationForTest();
    JamesDSPSetSampleRate(dsp.get(), 44100, 0);
    assert(dsp->eel.compiledSampleRate == 48000 && LiveProgGetStatus(dsp.get()) == -5);
    JamesDSPSetSampleRate(dsp.get(), 44100, 0);
    assert(*NSEEL_VM_getvar(dsp->eel.vm, "rateCopy") == 44100);
    assert(std::abs(*NSEEL_VM_getvar(dsp->eel.vm, "coefficient") - 123.001f / 44100) < 1e-9);
    JamesDSPSetSampleRate(dsp.get(), 96000, 0);
    assert(dsp->fs == 96000 && *NSEEL_VM_getvar(dsp->eel.vm, "rateCopy") == 96000);
    JamesDSPFree(dsp.get());
    std::puts("JamesDSP lightweight SET_CONFIG refreshes loaded scripts at actual internal Fs including native 96k processing, and retains live parameter overrides.");
}
}
int main() {
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    transactionAndParsing(); liveParametersAndRate(); builtins(); builtinHistory(); controllerRate();
    std::puts("LiveProg native regression checks passed.");
}
