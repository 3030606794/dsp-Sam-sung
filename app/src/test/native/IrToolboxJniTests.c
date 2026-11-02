#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#undef NDEBUG
#include <assert.h>
#undef JNIEXPORT
#define JNIEXPORT /* A host executable does not export the unused EQ entry points. */

/* The legacy EQ helper lacks a declaration in jdsp_header.h; unrelated to this test. */
void HSHOResponse(double a, double b, unsigned int c, double d, double e, unsigned int f, double *g, double *h, double *i) {
    (void)a; (void)b; (void)c; (void)d; (void)e; (void)f; (void)g; (void)h; (void)i;
    abort(); /* EQ is outside this harness; IR decoding/FFT/resampling use production implementations. */
}
#include "JdspImpResToolbox.c"

unsigned int upper_power_of_two(unsigned int value) {
    unsigned int result = 1; while (result < value) result <<= 1; return result;
}

typedef struct { jsize length; void *values; } TestArray;
static int acquired, released, failString, failArray;
static unsigned cases;
static const char *JNICALL getString(JNIEnv *env, jstring str, jboolean *copy) {
    (void)env; (void)copy;
    if (failString) return 0;
    ++acquired; return (const char*)str;
}
static void JNICALL releaseString(JNIEnv *env, jstring str, const char *text) {
    (void)env; assert((const char*)str == text); ++released;
}
static jsize JNICALL arrayLength(JNIEnv *env, jarray array) { (void)env; return ((TestArray*)array)->length; }
static void JNICALL getInts(JNIEnv *env, jintArray array, jsize from, jsize count, jint *out) {
    (void)env; TestArray *a = (TestArray*)array; assert(from >= 0 && from + count <= a->length);
    memcpy(out, (jint*)a->values + from, count * sizeof(jint));
}
static void JNICALL putInts(JNIEnv *env, jintArray array, jsize from, jsize count, const jint *in) {
    (void)env; TestArray *a = (TestArray*)array; assert(from >= 0 && from + count <= a->length);
    memcpy((jint*)a->values + from, in, count * sizeof(jint));
}
static jfloatArray JNICALL newFloats(JNIEnv *env, jsize count) {
    (void)env; if (failArray) return 0;
    TestArray *a = malloc(sizeof(*a)); assert(a); a->length = count; a->values = calloc(count, sizeof(jfloat)); assert(a->values);
    return (jfloatArray)a;
}
static void JNICALL putFloats(JNIEnv *env, jfloatArray array, jsize from, jsize count, const jfloat *in) {
    (void)env; TestArray *a = (TestArray*)array; assert(from >= 0 && from + count <= a->length);
    memcpy((jfloat*)a->values + from, in, count * sizeof(jfloat));
}
static jstring JNICALL newString(JNIEnv *env, const char *text) { (void)env; return (jstring)strdup(text); }
static const struct JNINativeInterface_ table = {
    .GetStringUTFChars = getString, .ReleaseStringUTFChars = releaseString,
    .GetArrayLength = arrayLength, .GetIntArrayRegion = getInts, .SetIntArrayRegion = putInts,
    .NewFloatArray = newFloats, .SetFloatArrayRegion = putFloats, .NewStringUTF = newString
};
static JNIEnv environment = &table;
static jint info[5], advanced[6] = {-80, -100, 0, 0, 0, 0};
static TestArray infoArray = {4, info}, advancedArray = {6, advanced};
static TestArray *readIr(const char *name, int mode) {
    memset(info, 0, sizeof(info));
    TestArray *out = (TestArray*)Java_me_timschneeberger_rootlessjamesdsp_interop_JdspImpResToolbox_ReadImpulseResponseToFloat(
        &environment, 0, (jstring)name, 48000, (jintArray)&infoArray, mode, (jintArray)&advancedArray);
    assert(acquired == released); ++cases; return out;
}
static void freeArray(TestArray *array) { if (array) { free(array->values); free(array); } }
static void writeImpulse(const char *name, int channels, int frames, float polarity) {
    float input[1024] = {0}; assert(channels * frames <= 1024);
    const int location = frames < 8 ? 0 : 2;
    for (int c = 0; c < channels; ++c) input[location * channels + c] = polarity / (c + 1);
    drwav wav; drwav_data_format f = {drwav_container_riff, DR_WAVE_FORMAT_IEEE_FLOAT, channels, 48000, 32};
    assert(drwav_init_file_write(&wav, name, &f, 0));
    assert(drwav_write_pcm_frames(&wav, frames, input) == (drwav_uint64)frames); assert(drwav_uninit(&wav) == DRWAV_SUCCESS);
}
int main(void) {
    const int channels[] = {1, 2, 4}, lengths[] = {1, 2, 8, 64};
    for (int c = 0; c < 3; ++c) for (int n = 0; n < 4; ++n) for (int sign = -1; sign <= 1; sign += 2) {
        writeImpulse("test.wav", channels[c], lengths[n], sign);
        for (int mode = 0; mode < 3; ++mode) {
            TestArray *out = readIr("test.wav", mode); assert(out && info[0] == channels[c] && info[1] > 0 && info[3] == 1);
            assert(out->length == info[0] * info[1]);
            float *values = out->values; for (int i = 0; i < out->length; ++i) assert(isfinite(values[i]));
            if (mode == 0) {
                assert(info[1] == lengths[n]); const int location = lengths[n] < 8 ? 0 : 2;
                for (int channel = 0; channel < channels[c]; ++channel) assert(values[location * channels[c] + channel] == (float)sign / (channel + 1));
            } else if (mode == 1) {
                assert(info[1] == 1); for (int channel = 0; channel < channels[c]; ++channel) assert(values[channel] == (float)sign / (channel + 1));
            } else {
                for (int channel = 0; channel < channels[c]; ++channel) assert(fabsf(values[channel] - 1.f / (channel + 1)) < .0002f);
            }
            freeArray(out);
        }
    }
    /* All these paths used to read uninitialized metadata or leak pinned JNI objects. */
    assert(!readIr("missing.wav", 0)); assert(!readIr("", 0)); assert(!readIr("test.wav", 3));
    FILE *broken = fopen("broken.wav", "wb"); assert(broken); fputs("bad RIFF", broken); fclose(broken);
    assert(!readIr("broken.wav", 2));
    advancedArray.length = 5; assert(!readIr("test.wav", 0)); advancedArray.length = 6;
    infoArray.length = 3; assert(!readIr("test.wav", 0)); infoArray.length = 4;
    failString = 1; assert(!readIr("test.wav", 0)); failString = 0;
    failArray = 1; assert(!readIr("test.wav", 0)); failArray = 0;
    writeImpulse("test.wav", 1, 64, -1); advanced[2] = 63;
    TestArray *out = readIr("test.wav", 1); assert(out && info[1] == 1 && info[3] == 0 && ((float*)out->values)[0] == -1); freeArray(out); advanced[2] = 0;
    char *copy = (char*)Java_me_timschneeberger_rootlessjamesdsp_interop_JdspImpResToolbox_OfflineAudioResample(&environment, 0, (jstring)"./", (jstring)"test.wav", 48000);
    assert(copy && strcmp(copy, "Invalid")); assert(acquired == released); free(copy); ++cases;
    copy = (char*)Java_me_timschneeberger_rootlessjamesdsp_interop_JdspImpResToolbox_OfflineAudioResample(&environment, 0, (jstring)"./", (jstring)"test.wav", 48000);
    assert(copy && !strcmp(copy, "Invalid")); assert(acquired == released); free(copy); ++cases;
    out = readIr("test.wav", 0); assert(out && info[1] == 64 && ((float*)out->values)[2] == -1); freeArray(out);
    writeImpulse("UPPER.IRS", 1, 8, -1);
    out = readIr("UPPER.IRS", 1); assert(out && info[1] == 1 && ((float*)out->values)[0] == -1); freeArray(out);
    for (int mode = 0; mode < 3; ++mode) {
        out = readIr("bundled-default.irs", mode); assert(out && info[0] == 2 && info[1] > 0 && info[3] == 1);
        if (mode == 0) assert(info[1] == (int)ceil(16383. * 48000. / 44100.));
        for (int i = 0; i < out->length; ++i) assert(isfinite(((float*)out->values)[i]));
        freeArray(out);
    }
    /* The optional fifth word reports file rate, independently of the 48 kHz working rate. */
    infoArray.length = 5;
    out = readIr("bundled-default.irs", 0); assert(out && info[4] == 44100); freeArray(out);
    out = readIr("test.wav", 0); assert(out && info[4] == 48000); freeArray(out);
    infoArray.length = 4;
    printf("IR toolbox actual JNI host regression: %u cases passed; UTF acquisitions/releases %d/%d.\n", cases, acquired, released);
    return 0;
}
