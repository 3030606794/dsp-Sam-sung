/* Diagnostic only: load a legacy Android AudioEffect library in this process.
 * No audio buffers or effect commands are sent.  --create additionally creates
 * and releases one instance; it may trigger vendor initialization side effects.
 * A successful shell probe does not prove audioserver namespace/SELinux access.
 */
#include <dlfcn.h>
#include <inttypes.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#include "../../native/viper/src/include/essential.h"

static const effect_uuid_t dts_uuid = {
    0x146edfc0, 0x7ed2, 0x11e4, 0x80eb,
    {0x00, 0x02, 0xa5, 0xd5, 0xc5, 0x1b}
};

static void print_mapped_libraries(void) {
    FILE *maps = fopen("/proc/self/maps", "r");
    if (!maps) {
        perror("/proc/self/maps");
        return;
    }
    char line[4096];
    while (fgets(line, sizeof(line), maps)) {
        char *path = strchr(line, '/');
        if (path && strstr(line, " r-xp ") && strstr(path, ".so")) {
            printf("mapped=%s", path);
        }
    }
    fclose(maps);
}

static void print_uuid(const char *label, const effect_uuid_t *uuid) {
    printf("%s=%08" PRIx32 "-%04x-%04x-%04x-%02x%02x%02x%02x%02x%02x\n",
           label, uuid->timeLow, uuid->timeMid, uuid->timeHiAndVersion,
           uuid->clockSeq, uuid->node[0], uuid->node[1], uuid->node[2],
           uuid->node[3], uuid->node[4], uuid->node[5]);
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    int create = 0, maps = 0;
    if (argc < 2) {
        fprintf(stderr, "Usage: %s /absolute/path/libdtsaudio.so [--create] [--maps]\n", argv[0]);
        return 2;
    }
    for (int i = 2; i < argc; ++i) {
        if (strcmp(argv[i], "--create") == 0) create = 1;
        else if (strcmp(argv[i], "--maps") == 0) maps = 1;
        else {
            fprintf(stderr, "Unknown argument: %s\n", argv[i]);
            return 2;
        }
    }
    char executable[4096];
    ssize_t length = readlink("/proc/self/exe", executable, sizeof(executable) - 1);
    if (length >= 0) {
        executable[length] = '\0';
        printf("executable=%s\n", executable);
    }
    printf("library=%s\n", argv[1]);
    void *module = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!module) {
        fprintf(stderr, "dlopen failed: %s\n", dlerror());
        return 3;
    }
    dlerror();
    const audio_effect_library_t *library = dlsym(module, "AELI");
    const char *error = dlerror();
    if (error || !library) {
        fprintf(stderr, "AELI missing: %s\n", error ? error : "null symbol");
        dlclose(module);
        return 4;
    }
    printf("tag=0x%08" PRIx32 "\nversion=0x%08" PRIx32 "\n",
           library->tag, library->version);
    if (library->tag != AUDIO_EFFECT_LIBRARY_TAG || (library->version >> 16) != 3) {
        fprintf(stderr, "Unsupported AudioEffect library ABI\n");
        dlclose(module);
        return 5;
    }
    printf("name=%s\nimplementor=%s\n", library->name ? library->name : "(null)",
           library->implementor ? library->implementor : "(null)");
    if (!library->get_descriptor) {
        fprintf(stderr, "Missing get_descriptor\n");
        dlclose(module);
        return 6;
    }
    effect_descriptor_t descriptor = {0};
    int32_t result = library->get_descriptor(&dts_uuid, &descriptor);
    printf("get_descriptor=%" PRId32 "\n", result);
    if (result != 0) {
        dlclose(module);
        return 7;
    }
    printf("descriptor.name=%.*s\ndescriptor.implementor=%.*s\n",
           EFFECT_STRING_LEN_MAX, descriptor.name,
           EFFECT_STRING_LEN_MAX, descriptor.implementor);
    printf("descriptor.apiVersion=0x%08" PRIx32 "\ndescriptor.flags=0x%08" PRIx32
           "\ndescriptor.cpuLoad=%u\ndescriptor.memoryUsage=%u\n",
           descriptor.apiVersion, descriptor.flags, descriptor.cpuLoad, descriptor.memoryUsage);
    print_uuid("descriptor.uuid", &descriptor.uuid);
    print_uuid("descriptor.type", &descriptor.type);
    if (maps) print_mapped_libraries();
    if (create) {
        if (!library->create_effect || !library->release_effect) {
            fprintf(stderr, "Missing create_effect/release_effect\n");
            dlclose(module);
            return 8;
        }
        effect_handle_t effect = NULL;
        printf("creating standalone instance: session=0 io=0\n");
        result = library->create_effect(&dts_uuid, 0, 0, &effect);
        printf("create_effect=%" PRId32 " handle=%s\n", result, effect ? "present" : "null");
        if (result != 0 || !effect) {
            dlclose(module);
            return 9;
        }
        result = library->release_effect(effect);
        printf("release_effect=%" PRId32 "\n", result);
        if (result != 0) {
            /* Keep code mapped if the library reports a failed release. */
            return 10;
        }
    }
    if (dlclose(module) != 0) {
        fprintf(stderr, "dlclose failed: %s\n", dlerror());
        return 11;
    }
    return 0;
}
