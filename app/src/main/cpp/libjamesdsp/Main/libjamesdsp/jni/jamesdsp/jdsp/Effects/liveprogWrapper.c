#include <math.h>
#include <ctype.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <strings.h>
void NSEEL_HOSTSTUB_EnterMutex() { }
void NSEEL_HOSTSTUB_LeaveMutex() { }
#include "../jdsp_header.h"
#ifdef DSP_FUSION_TEST_ALLOCATIONS
static int liveprogFailNextPreparation;
void LiveProgFailNextPreparationForTest(void) { liveprogFailNextPreparation = 1; }
#endif

static void LiveProgRelease(LiveProg *pg)
{
    NSEEL_code_free(pg->codehandleProcess);
    NSEEL_code_free(pg->codehandleSlider);
    NSEEL_code_free(pg->codehandleInit);
    NSEEL_VM_free(pg->vm);
    free(pg->sourceInit);
    free(pg->sourceSlider);
    free(pg->sourceProcess);
    while (pg->parameters) {
        LiveProgParameter *next = pg->parameters->next;
        free(pg->parameters);
        pg->parameters = next;
    }
    memset(pg, 0, sizeof(*pg));
}

static int LiveProgError(LiveProg *pg, int result, const char *detail)
{
    pg->lastCompileResult = result;
    snprintf(pg->lastError, sizeof(pg->lastError), "%s", detail ? detail : checkErrorCode(result));
    return result;
}

static char *LiveProgCopy(const char *begin, size_t length)
{
    char *copy = (char *)malloc(length + 1);
    if (copy) {
        memcpy(copy, begin, length);
        copy[length] = 0;
    }
    return copy;
}

void LiveProgConstructor(JamesDSPLib *jdsp)
{
    LiveProg *pg = &jdsp->eel;
    memset(pg, 0, sizeof(*pg));
    pg->active = 1;
    pg->vm = NSEEL_VM_alloc();
    if (pg->vm) {
        pg->vmFs = NSEEL_VM_regvar(pg->vm, "srate");
        pg->input1 = NSEEL_VM_regvar(pg->vm, "spl0");
        pg->input2 = NSEEL_VM_regvar(pg->vm, "spl1");
        if (pg->vmFs) *pg->vmFs = jdsp->fs;
    }
}
void LiveProgDestructor(JamesDSPLib *jdsp)
{
    LiveProgRelease(&jdsp->eel);
}
void LiveProgEnable(JamesDSPLib *jdsp)
{
    if (jdsp->eel.sourceInit && jdsp->eel.compiledSampleRate != jdsp->fs) {
        jdsp_lock(jdsp);
        LiveProgRefreshLocked(jdsp);
        jdsp_unlock(jdsp);
    } else if (!jdsp->eel.compileSucessfully && jdsp->eel.vmFs) *jdsp->eel.vmFs = jdsp->fs;
    jdsp->liveprogEnabled = 1;
}
void LiveProgDisable(JamesDSPLib *jdsp)
{
    jdsp->liveprogEnabled = 0;
}
int LiveProgGetStatus(JamesDSPLib *jdsp)
{
    return jdsp->eel.lastCompileResult;
}
const char *LiveProgGetLastError(JamesDSPLib *jdsp)
{
    return jdsp->eel.lastError;
}

// Compile into a new VM. Failed compilation and @init side effects cannot
// corrupt the running program. All sections compile before @init executes.
static int LiveProgPrepare(JamesDSPLib *jdsp, LiveProg *next,
                           const char *init, const char *slider, const char *sample,
                           const LiveProgParameter *parameters)
{
    memset(next, 0, sizeof(*next));
#ifdef DSP_FUSION_TEST_ALLOCATIONS
    if (liveprogFailNextPreparation) {
        liveprogFailNextPreparation = 0;
        return LiveProgError(next, -5, NULL);
    }
#endif
    next->active = jdsp->eel.active;
    next->sourceInit = LiveProgCopy(init, strlen(init));
    next->sourceSlider = LiveProgCopy(slider, strlen(slider));
    next->sourceProcess = LiveProgCopy(sample, strlen(sample));
    next->vm = NSEEL_VM_alloc();
    if (!next->sourceInit || !next->sourceSlider || !next->sourceProcess || !next->vm)
        return LiveProgError(next, -5, NULL);
    next->vmFs = NSEEL_VM_regvar(next->vm, "srate");
    next->input1 = NSEEL_VM_regvar(next->vm, "spl0");
    next->input2 = NSEEL_VM_regvar(next->vm, "spl1");
    if (!next->vmFs || !next->input1 || !next->input2)
        return LiveProgError(next, -5, NULL);
    *next->vmFs = jdsp->fs;
    next->codehandleInit = NSEEL_code_compile_ex(next->vm, init, 0, NSEEL_CODE_COMPILE_FLAG_COMMONFUNCS);
    if (!next->codehandleInit)
        return LiveProgError(next, -1, NSEEL_code_getcodeerror(next->vm));
    if (*slider) {
        next->codehandleSlider = NSEEL_code_compile(next->vm, slider, 0);
        if (!next->codehandleSlider)
            return LiveProgError(next, -6, NSEEL_code_getcodeerror(next->vm));
    }
    next->codehandleProcess = NSEEL_code_compile(next->vm, sample, 0);
    if (!next->codehandleProcess)
        return LiveProgError(next, -3, NSEEL_code_getcodeerror(next->vm));
    NSEEL_code_execute(next->codehandleInit);
    for (const LiveProgParameter *param = parameters; param; param = param->next) {
        float *variable = NSEEL_VM_getvar(next->vm, param->name);
        if (variable) *variable = param->value;
    }
    if (next->codehandleSlider) NSEEL_code_execute(next->codehandleSlider);
    next->compileSucessfully = 1;
    next->compiledSampleRate = jdsp->fs;
    next->lastCompileResult = 1;
    return 1;
}

static int LiveProgCommit(JamesDSPLib *jdsp, const char *init, const char *slider,
                          const char *sample, int preserveParameters)
{
    LiveProg next;
    const int result = LiveProgPrepare(jdsp, &next, init, slider, sample,
                                      preserveParameters ? jdsp->eel.parameters : NULL);
    if (result <= 0) {
        LiveProgError(&jdsp->eel, result, next.lastError);
        LiveProgRelease(&next);
        return result;
    }
    LiveProg previous = jdsp->eel;
    if (preserveParameters) {
        next.parameters = previous.parameters;
        previous.parameters = NULL;
    }
    jdsp->eel = next;
    LiveProgRelease(&previous);
    return 1;
}

int LiveProgRefreshLocked(JamesDSPLib *jdsp)
{
    LiveProg *pg = &jdsp->eel;
    if (!pg->sourceInit || !pg->sourceProcess) {
        if (pg->vmFs) *pg->vmFs = jdsp->fs;
        return pg->lastCompileResult;
    }
    return LiveProgCommit(jdsp, pg->sourceInit, pg->sourceSlider, pg->sourceProcess, 1);
}

const char* checkErrorCode(int errCode)
{
    switch (errCode) {
    case 0: return "@init section not found";
    case -1: return "Syntax error at @init section";
    case -2: return "@sample section not found";
    case -3: return "Syntax error at @sample section";
    case -4: return "Invalid live parameter";
    case -5: return "Insufficient memory for script";
    case -6: return "Syntax error at @slider section";
    case -7: return "Duplicate or unsupported script section";
    default: return "No syntax errors detected";
    }
}

// Markers must be complete tokens at the start of a line. Accept LF and CRLF;
// comments elsewhere containing @sample must not change the section boundary.
int LiveProgStringParser(JamesDSPLib *jdsp, char *eelCode)
{
    jdsp_lock(jdsp);
    const char *begins[3] = {NULL, NULL, NULL}, *ends[3] = {NULL, NULL, NULL};
    int previous = -1, result = 1, blockComment = 0;
    char quote = 0;
    if (!eelCode) result = 0;
    for (const char *line = eelCode; line && *line && result > 0;) {
        const char *end = strchr(line, '\n');
        if (!end) end = line + strlen(line);
        const char *token = line;
        while (token < end && (*token == ' ' || *token == '\t' || *token == '\r')) ++token;
        if (!blockComment && !quote && *token == '@') {
            int section = -1;
            const char *names[3] = {"@init", "@slider", "@sample"};
            for (int i = 0; i < 3; ++i) {
                const size_t length = strlen(names[i]);
                if ((size_t)(end - token) >= length && !strncmp(token, names[i], length) &&
                    (token + length == end || isspace((unsigned char)token[length]))) {
                    section = i;
                    break;
                }
            }
            if (section < 0 || begins[section]) { result = -7; break; }
            if (previous >= 0) ends[previous] = line;
            begins[section] = token + strlen(names[section]);
            previous = section;
        }
        for (const char *cursor = line; cursor < end; ++cursor) {
            if (blockComment) {
                if (*cursor == '*' && cursor + 1 < end && cursor[1] == '/') { blockComment = 0; ++cursor; }
            } else if (quote) {
                if (*cursor == '\\' && cursor + 1 < end) ++cursor;
                else if (*cursor == quote) quote = 0;
            } else if (*cursor == '/' && cursor + 1 < end && cursor[1] == '/') break;
            else if (*cursor == '/' && cursor + 1 < end && cursor[1] == '*') { blockComment = 1; ++cursor; }
            else if (*cursor == '"' || *cursor == '\'') quote = *cursor;
        }
        line = *end ? end + 1 : end;
    }
    if (result > 0 && !begins[0]) result = 0;
    if (result > 0 && !begins[2]) result = -2;
    if (result > 0) {
        ends[previous] = eelCode + strlen(eelCode);
        char *sections[3] = {NULL, NULL, NULL};
        for (int i = 0; i < 3; ++i)
            sections[i] = begins[i] ? LiveProgCopy(begins[i], (size_t)(ends[i] - begins[i])) : LiveProgCopy("", 0);
        if (!sections[0] || !sections[1] || !sections[2]) result = -5;
        else result = LiveProgCommit(jdsp, sections[0], sections[1], sections[2], 0);
        for (int i = 0; i < 3; ++i) free(sections[i]);
    }
    if (result <= 0 && jdsp->eel.lastCompileResult != result)
        LiveProgError(&jdsp->eel, result, NULL);
    jdsp_unlock(jdsp);
    return result;
}

int LiveProgSetParameter(JamesDSPLib *jdsp, const char *name, double value)
{
    const size_t length = name ? strlen(name) : 0;
    if (!length || length >= 64 || !isfinite(value) || !isfinite((float)value)) return -4;
    for (size_t i = 0; i < length; ++i)
        if (!((name[i] >= 'A' && name[i] <= 'Z') || (name[i] >= 'a' && name[i] <= 'z') ||
              name[i] == '_' || (i && name[i] >= '0' && name[i] <= '9'))) return -4;
    if (!strcasecmp(name, "srate") || !strcasecmp(name, "spl0") || !strcasecmp(name, "spl1")) return -4;
    jdsp_lock(jdsp);
    LiveProg *pg = &jdsp->eel;
    float *variable = pg->compileSucessfully ? NSEEL_VM_getvar(pg->vm, name) : NULL;
    if (!variable) { jdsp_unlock(jdsp); return -4; }
    LiveProgParameter *param = pg->parameters;
    while (param && strcasecmp(param->name, name)) param = param->next;
    if (!param) {
        param = (LiveProgParameter *)calloc(1, sizeof(*param));
        if (!param) { jdsp_unlock(jdsp); return -5; }
        memcpy(param->name, name, length + 1);
        param->next = pg->parameters;
        pg->parameters = param;
    }
    param->value = (float)value;
    *variable = param->value;
    if (pg->codehandleSlider) NSEEL_code_execute(pg->codehandleSlider);
    jdsp_unlock(jdsp);
    return 1;
}
void LiveProgProcess(JamesDSPLib *jdsp, size_t n)
{
	LiveProg *eel = &jdsp->eel;
    if (eel->compileSucessfully && eel->active && eel->compiledSampleRate == jdsp->fs)
	{
		for (size_t i = 0; i < n; i++)
		{
			*eel->input1 = jdsp->tmpBuffer[0][i];
			*eel->input2 = jdsp->tmpBuffer[1][i];
				NSEEL_code_execute(eel->codehandleProcess);
			    // Prevent broken scripts (returning NaN or inf) causing permanent audio loss
			    if(isinf((float)*eel->input1) || isnan((float)*eel->input1))
				jdsp->tmpBuffer[0][i] = 0;
			    else
				jdsp->tmpBuffer[0][i] = (float)*eel->input1;
		
			    if(isinf((float)*eel->input2) || isnan((float)*eel->input2))
				jdsp->tmpBuffer[1][i] = 0;
			    else
				jdsp->tmpBuffer[1][i] = (float)*eel->input2;
		}
	}
}
