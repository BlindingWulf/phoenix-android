/* Phoenix original code. Public JNI invocation API, no linker hooks or ART VM. */
#define _GNU_SOURCE
#include <jni.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <limits.h>
#include <unistd.h>
#include <sys/stat.h>
#include "parent_lifecycle.h"
#ifndef JNI_VERSION_1_8
#define JNI_VERSION_1_8 0x00010008
#endif

typedef jint (*create_vm)(JavaVM **, void **, void *);
static char discovery_jvm_path[PATH_MAX];
/* Android's public linker canonicalizes symlinks. The stock port computes its
   data home before parsing -Djava.home. This executable exports a narrow public
   dladdr compatibility adapter: only libjvm's discovery name is its verified
   JAVA_HOME symlink path. Memory still maps the immutable package-installed ELF.
   Other address fields and all other libraries use the real public libdl API. */
__attribute__((visibility("default")))
int dladdr(const void *address, Dl_info *info) {
    typedef int (*real_dladdr)(const void *, Dl_info *);
    static real_dladdr delegate;
    if (!delegate) delegate = (real_dladdr)dlsym(RTLD_NEXT, "dladdr");
    if (!delegate) return 0;
    int result = delegate(address, info);
    if (result && discovery_jvm_path[0] && info->dli_fname) {
        const char *name = strrchr(info->dli_fname, '/');
        if (strcmp(name ? name + 1 : info->dli_fname, "libjvm.so") == 0)
            info->dli_fname = discovery_jvm_path;
    }
    return result;
}
static int failure(const char *message) {
    fprintf(stderr, "PHOENIX_RUNTIME_ERROR %s\n", message);
    return 70;
}

static int installed_native_dir(const char *argument, char *directory) {
    char executable[PATH_MAX];
    if (!realpath(argument, directory) || !realpath("/proc/self/exe", executable))
        return failure("cannot resolve installed native directory");
    char *separator = strrchr(executable, '/');
    if (!separator) return failure("invalid native executable path");
    *separator = '\0';
    if (strcmp(directory, executable) != 0)
        return failure("native-dir must be the launcher's own installed directory");
    return 0;
}

/* executable <installed-native-dir> <java-home> [JVM options] -- <main-class> [args] */
int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
    if (phoenix_require_parent() != 0) return 70;
    if (argc < 5) return failure("usage: native-dir java-home [VM options] -- main-class [args]");
    char native_dir[PATH_MAX], home[PATH_MAX], path[PATH_MAX];
    int directory_result = installed_native_dir(argv[1], native_dir);
    if (directory_result) return directory_result;
    if (!realpath(argv[2], home)) return failure("missing java-home");
    int split = 3;
    while (split < argc && strcmp(argv[split], "--") != 0) ++split;
    if (split + 1 >= argc) return failure("missing main class after --");
    /* Keep dladdr's path inside JAVA_HOME/lib/server so the port discovers its data.
       This is a symlink to immutable installed APK code, never an extracted ELF. */
    if (snprintf(path, sizeof(path), "%s/lib/server/libjvm.so", home) >= (int)sizeof(path)) return failure("native path too long");
    char resolved[PATH_MAX];
    if (!realpath(path, resolved) || strncmp(resolved, native_dir, strlen(native_dir)) != 0 ||
            resolved[strlen(native_dir)] != '/') return failure("libjvm must resolve into installed native-dir");
    strcpy(discovery_jvm_path, path);
    void *library = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    if (!library) return failure(dlerror());
    create_vm create = (create_vm)dlsym(library, "JNI_CreateJavaVM");
    if (!create) return failure("JNI_CreateJavaVM unavailable");
    int user_count = split - 3;
    JavaVMOption *options = calloc((size_t)user_count + 4, sizeof(*options));
    char *home_opt = NULL, *boot_opt = NULL, *library_opt = NULL;
    if (!options || asprintf(&home_opt, "-Djava.home=%s", home) < 0 ||
            asprintf(&boot_opt, "-Dsun.boot.library.path=%s/lib", home) < 0 ||
            asprintf(&library_opt, "-Djava.library.path=%s", native_dir) < 0) return failure("allocation failed");
    options[0].optionString = home_opt;
    options[1].optionString = boot_opt;
    options[2].optionString = library_opt;
    options[3].optionString = "-Djava.awt.headless=true";
    for (int i = 0; i < user_count; ++i) options[i + 4].optionString = argv[i + 3];
    JavaVMInitArgs init = {.version = JNI_VERSION_1_8, .nOptions = user_count + 4,
                          .options = options, .ignoreUnrecognized = JNI_FALSE};
    JavaVM *vm = NULL;
    JNIEnv *env = NULL;
    fprintf(stderr, "PHOENIX_RUNTIME_START public-JNI public-dladdr-data-discovery pid=%ld home=%s\n", (long)getpid(), home);
    jint result = create(&vm, (void **)&env, &init);
    free(options); free(home_opt); free(boot_opt); free(library_opt);
    if (result != JNI_OK) {
        char message[96]; snprintf(message, sizeof(message), "JNI_CreateJavaVM returned %d", result);
        return failure(message);
    }
    char *class_name = strdup(argv[split + 1]);
    if (!class_name) return failure("allocation failed");
    for (char *p = class_name; *p; ++p) if (*p == '.') *p = '/';
    jclass main_class = (*env)->FindClass(env, class_name);
    free(class_name);
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    if (!main_class || !string_class) goto java_failure;
    jmethodID main_method = (*env)->GetStaticMethodID(env, main_class, "main", "([Ljava/lang/String;)V");
    if (!main_method) goto java_failure;
    jobjectArray main_args = (*env)->NewObjectArray(env, argc - split - 2, string_class, NULL);
    if (!main_args) goto java_failure;
    for (int i = split + 2; i < argc; ++i) {
        jstring value = (*env)->NewStringUTF(env, argv[i]);
        if (!value) goto java_failure;
        (*env)->SetObjectArrayElement(env, main_args, i - split - 2, value);
        (*env)->DeleteLocalRef(env, value);
        if ((*env)->ExceptionCheck(env)) goto java_failure;
    }
    (*env)->CallStaticVoidMethod(env, main_class, main_method, main_args);
    if ((*env)->ExceptionCheck(env)) goto java_failure;
    /* Destroy waits for Java non-daemon threads and runs registered shutdown hooks. */
    result = (*vm)->DestroyJavaVM(vm);
    return result == JNI_OK ? 0 : failure("DestroyJavaVM failed");
java_failure:
    (*env)->ExceptionDescribe(env);
    (*env)->ExceptionClear(env);
    (*vm)->DestroyJavaVM(vm);
    return failure("Java main failed; see exception above");
}
