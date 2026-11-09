// libcofre.so in the :game process. ART loads it for GameActivity; HotSpot loads the same file
// again for the GLFW shim (org.lwjgl.glfw.GLFW). The dynamic linker returns the copy already in
// memory, so both VMs share the window and the input queue below.
//
// The JRE start-up (load order, LD_LIBRARY_PATH update, JLI_Launch on our own thread) follows
// PojavLauncher's jre_launcher.c and utils.c (LGPL-3.0); see NOTICE.
#define _GNU_SOURCE
#include <android/log.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <link.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>

#define TAG "Cofre"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ---- Input queue ------------------------------------------------------------------------------
// Written by GameActivity (ART), drained by glfwPollEvents (HotSpot). Record layout and type
// numbers are shared with GLFW.java and GameBridge.kt.

typedef struct {
    int32_t type, i0, i1, i2, i3, pad;
    double d0, d1;
} event;
_Static_assert(sizeof(event) == 40, "GLFW.EVENT_SIZE");

enum { EV_CURSOR_MOVE = 5, QUEUE_CAP = 1024 };

static event queue[QUEUE_CAP];
static int queue_head, queue_count;
static pthread_mutex_t queue_lock = PTHREAD_MUTEX_INITIALIZER;

JNIEXPORT void JNICALL Java_com_ignacio_cofre_runtime_GameBridge_pushEvent(
    JNIEnv *env, jclass clazz, jint type, jint i0, jint i1, jint i2, jint i3, jdouble d0, jdouble d1) {
    (void) env, (void) clazz;
    pthread_mutex_lock(&queue_lock);
    event *last = queue_count ? &queue[(queue_head + queue_count - 1) % QUEUE_CAP] : NULL;
    if (type == EV_CURSOR_MOVE && last && last->type == EV_CURSOR_MOVE) {
        last->d0 += d0; // merge mouse motion so a stalled game can't overflow the queue
        last->d1 += d1;
    } else if (queue_count < QUEUE_CAP) {
        queue[(queue_head + queue_count) % QUEUE_CAP] = (event) {type, i0, i1, i2, i3, 0, d0, d1};
        queue_count++;
    }
    pthread_mutex_unlock(&queue_lock);
}

JNIEXPORT jint JNICALL Java_org_lwjgl_glfw_GLFW_nPollEvents(JNIEnv *env, jclass clazz, jobject buffer, jint capacity) {
    (void) clazz;
    event *out = (*env)->GetDirectBufferAddress(env, buffer);
    pthread_mutex_lock(&queue_lock);
    int n = queue_count < capacity ? queue_count : capacity;
    for (int i = 0; i < n; i++) out[i] = queue[(queue_head + i) % QUEUE_CAP];
    queue_head = (queue_head + n) % QUEUE_CAP;
    queue_count -= n;
    pthread_mutex_unlock(&queue_lock);
    return n;
}

// ---- Window -------------------------------------------------------------------------------------
// Never touched with EGL. glfwCreateWindowSurface waits here while there's no surface (asleep).

static ANativeWindow *window;
static int window_width, window_height;
static pthread_mutex_t window_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t window_ready = PTHREAD_COND_INITIALIZER;

JNIEXPORT void JNICALL Java_com_ignacio_cofre_runtime_GameBridge_setSurface(
    JNIEnv *env, jclass clazz, jobject surface, jint width, jint height) {
    (void) clazz;
    ANativeWindow *next = surface ? ANativeWindow_fromSurface(env, surface) : NULL;
    pthread_mutex_lock(&window_lock);
    ANativeWindow *previous = window;
    window = next;
    if (next) {
        window_width = width;
        window_height = height;
        pthread_cond_broadcast(&window_ready);
    }
    pthread_mutex_unlock(&window_lock);
    if (previous) ANativeWindow_release(previous);
}

JNIEXPORT jlong JNICALL Java_org_lwjgl_glfw_GLFW_nAcquireWindow(JNIEnv *env, jclass clazz) {
    (void) env, (void) clazz;
    pthread_mutex_lock(&window_lock);
    if (!window) LOGI("[Cofre] glfwCreateWindowSurface waiting for a surface");
    while (!window) pthread_cond_wait(&window_ready, &window_lock);
    ANativeWindow *acquired = window;
    ANativeWindow_acquire(acquired);
    pthread_mutex_unlock(&window_lock);
    return (jlong) (intptr_t) acquired;
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_GLFW_nReleaseWindow(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env, (void) clazz;
    ANativeWindow_release((ANativeWindow *) (intptr_t) handle);
}

JNIEXPORT jlong JNICALL Java_org_lwjgl_glfw_GLFW_nWindowSize(JNIEnv *env, jclass clazz) {
    (void) env, (void) clazz;
    pthread_mutex_lock(&window_lock);
    jlong size = ((jlong) window_width << 32) | (uint32_t) window_height;
    pthread_mutex_unlock(&window_lock);
    return size;
}

// ---- Requests from the game to the activity ------------------------------------------------------
// HotSpot threads never call into ART: they post a request that GameActivity's request thread takes.

enum { REQ_CAPTURE = 1, REQ_RELEASE = 2, REQ_EXIT = 3 };
static int requests[16], request_head, request_count, exit_acked;
static pthread_mutex_t request_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t request_changed = PTHREAD_COND_INITIALIZER;

static void post_request(int request) {
    pthread_mutex_lock(&request_lock);
    if (request_count < 16) requests[(request_head + request_count++) % 16] = request;
    pthread_cond_broadcast(&request_changed);
    pthread_mutex_unlock(&request_lock);
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_GLFW_nSetCursorCaptured(JNIEnv *env, jclass clazz, jboolean captured) {
    (void) env, (void) clazz;
    post_request(captured ? REQ_CAPTURE : REQ_RELEASE);
}

JNIEXPORT jint JNICALL Java_com_ignacio_cofre_runtime_GameBridge_nextRequest(JNIEnv *env, jclass clazz) {
    (void) env, (void) clazz;
    pthread_mutex_lock(&request_lock);
    while (!request_count) pthread_cond_wait(&request_changed, &request_lock);
    int request = requests[request_head];
    request_head = (request_head + 1) % 16;
    request_count--;
    pthread_mutex_unlock(&request_lock);
    return request;
}

JNIEXPORT void JNICALL Java_com_ignacio_cofre_runtime_GameBridge_exitAck(JNIEnv *env, jclass clazz) {
    (void) env, (void) clazz;
    pthread_mutex_lock(&request_lock);
    exit_acked = 1;
    pthread_cond_broadcast(&request_changed);
    pthread_mutex_unlock(&request_lock);
}

static void deadline(struct timespec *ts, long millis) {
    clock_gettime(CLOCK_REALTIME, ts);
    ts->tv_sec += millis / 1000;
    ts->tv_nsec += (millis % 1000) * 1000000L;
    if (ts->tv_nsec >= 1000000000L) {
        ts->tv_sec++;
        ts->tv_nsec -= 1000000000L;
    }
}

// ---- stdout/stderr → log file (+ logcat in debuggable builds) ------------------------------------

static int stdio_pipe[2] = {-1, -1}, log_fd = -1, stdio_done, mirror_logcat;
static pthread_mutex_t stdio_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t stdio_closed = PTHREAD_COND_INITIALIZER;

static void *stdio_reader(void *arg) {
    (void) arg;
    FILE *in = fdopen(stdio_pipe[0], "r");
    char line[4096];
    while (in && fgets(line, sizeof line, in)) {
        // Bionic ignores OpenJDK's DT_RPATH and warns once per JRE library; LD_LIBRARY_PATH covers it.
        if (strstr(line, "unused DT entry: DT_RPATH")) continue;
        size_t len = strlen(line);
        if (log_fd >= 0) write(log_fd, line, len);
        if (len && line[len - 1] == '\n') line[len - 1] = 0;
        if (mirror_logcat) __android_log_write(ANDROID_LOG_INFO, TAG, line);
    }
    if (log_fd >= 0) fsync(log_fd);
    pthread_mutex_lock(&stdio_lock);
    stdio_done = 1;
    pthread_cond_broadcast(&stdio_closed);
    pthread_mutex_unlock(&stdio_lock);
    return NULL;
}

static void redirect_stdio(const char *log_path) {
    log_fd = open(log_path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (log_fd < 0) LOGE("can't open log %s: %s", log_path, strerror(errno));
    if (pipe(stdio_pipe) != 0) return;
    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
    dup2(stdio_pipe[1], STDOUT_FILENO);
    dup2(stdio_pipe[1], STDERR_FILENO);
    close(stdio_pipe[1]);
    pthread_t reader;
    pthread_create(&reader, NULL, stdio_reader, NULL);
    pthread_detach(reader);
}

// Closing our only write ends lets the reader drain what's left and stop.
static void drain_stdio(void) {
    fflush(stdout);
    fflush(stderr);
    close(STDOUT_FILENO);
    close(STDERR_FILENO);
    struct timespec until;
    deadline(&until, 1000);
    pthread_mutex_lock(&stdio_lock);
    while (!stdio_done && stdio_pipe[0] >= 0) {
        if (pthread_cond_timedwait(&stdio_closed, &stdio_lock, &until) == ETIMEDOUT) break;
    }
    pthread_mutex_unlock(&stdio_lock);
}

// ---- Exit ---------------------------------------------------------------------------------------
// The :game process ends when the JVM does: main returns, or the game calls System.exit, which
// reaches libc exit() from libjvm. We catch that call (see hook_exit), write the exit record the
// launcher reads, let the activity finish, and leave with _exit: ART's own teardown isn't safe here.

static char *exit_path;
static atomic_int finishing;

static void write_exit_record(int code) {
    if (!exit_path) return;
    struct timespec now;
    clock_gettime(CLOCK_REALTIME, &now);
    char tmp[4096], json[128];
    snprintf(tmp, sizeof tmp, "%s.tmp", exit_path);
    int n = snprintf(json, sizeof json, "{\"code\":%d,\"timeMillis\":%lld}\n", code,
                     (long long) now.tv_sec * 1000 + now.tv_nsec / 1000000);
    int fd = open(tmp, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (fd < 0) return;
    write(fd, json, n);
    fsync(fd);
    close(fd);
    rename(tmp, exit_path);
}

__attribute__((noreturn)) static void finish(int code) {
    if (atomic_exchange(&finishing, 1)) {
        for (;;) pause(); // another thread is already exiting
    }
    write_exit_record(code);
    drain_stdio();
    LOGI("[Cofre] jvm exited code=%d", code);
    post_request(REQ_EXIT);
    struct timespec until;
    deadline(&until, 3000);
    pthread_mutex_lock(&request_lock);
    while (!exit_acked) {
        if (pthread_cond_timedwait(&request_changed, &request_lock, &until) == ETIMEDOUT) break;
    }
    pthread_mutex_unlock(&request_lock);
    _exit(code);
}

static void exit_hook(int code) {
    finish(code);
}

typedef struct {
    const char *library;
    int patched;
} hook_target;

static void patch_relocations(ElfW(Addr) base, const ElfW(Rela) *rel, size_t size, const ElfW(Sym) *symbols,
                              const char *strings, hook_target *target) {
    long page = sysconf(_SC_PAGESIZE);
    for (size_t i = 0; rel && i < size / sizeof *rel; i++) {
        uint32_t type = ELF64_R_TYPE(rel[i].r_info);
        if (type != R_AARCH64_JUMP_SLOT && type != R_AARCH64_GLOB_DAT) continue;
        if (strcmp(strings + symbols[ELF64_R_SYM(rel[i].r_info)].st_name, "exit") != 0) continue;
        void **slot = (void **) (base + rel[i].r_offset);
        void *start = (void *) ((uintptr_t) slot & ~(uintptr_t) (page - 1));
        if (mprotect(start, page, PROT_READ | PROT_WRITE) != 0) continue; // the GOT is read-only after RELRO
        *slot = (void *) exit_hook;
        mprotect(start, page, PROT_READ);
        target->patched++;
    }
}

static int patch_library(struct dl_phdr_info *info, size_t size, void *data) {
    (void) size;
    hook_target *target = data;
    const char *name = info->dlpi_name;
    size_t len = name ? strlen(name) : 0, want = strlen(target->library);
    if (len < want || strcmp(name + len - want, target->library) != 0) return 0;
    const ElfW(Dyn) *dynamic = NULL;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        if (info->dlpi_phdr[i].p_type == PT_DYNAMIC) dynamic = (const ElfW(Dyn) *) (info->dlpi_addr + info->dlpi_phdr[i].p_vaddr);
    }
    const ElfW(Sym) *symbols = NULL;
    const char *strings = NULL;
    const ElfW(Rela) *plt = NULL, *rela = NULL;
    size_t plt_size = 0, rela_size = 0;
    for (const ElfW(Dyn) *d = dynamic; d && d->d_tag != DT_NULL; d++) {
        switch (d->d_tag) { // bionic leaves d_ptr unrelocated
            case DT_SYMTAB: symbols = (const ElfW(Sym) *) (info->dlpi_addr + d->d_un.d_ptr); break;
            case DT_STRTAB: strings = (const char *) (info->dlpi_addr + d->d_un.d_ptr); break;
            case DT_JMPREL: plt = (const ElfW(Rela) *) (info->dlpi_addr + d->d_un.d_ptr); break;
            case DT_PLTRELSZ: plt_size = d->d_un.d_val; break;
            case DT_RELA: rela = (const ElfW(Rela) *) (info->dlpi_addr + d->d_un.d_ptr); break;
            case DT_RELASZ: rela_size = d->d_un.d_val; break;
            default: break;
        }
    }
    if (!symbols || !strings) return 0;
    patch_relocations(info->dlpi_addr, plt, plt_size, symbols, strings, target);
    patch_relocations(info->dlpi_addr, rela, rela_size, symbols, strings, target);
    return 1;
}

// Points the library's imported exit() at exit_hook, so we learn System.exit's code.
static void hook_exit(const char *library) {
    hook_target target = {library, 0};
    dl_iterate_phdr(patch_library, &target);
    LOGI("exit hook %s: %d slot(s)", library, target.patched);
}

// ---- JVM launch -----------------------------------------------------------------------------

typedef jint JLI_Launch_t(int argc, char **argv, int jargc, const char **jargv, int appclassc,
                          const char **appclassv, const char *fullversion, const char *dotversion,
                          const char *pname, const char *lname, jboolean javaargs, jboolean cpwildcard,
                          jboolean javaw, jint ergo);
typedef void update_ld_path_t(const char *);

static char *java_home, *game_dir;
static char **jvm_argv;
static int jvm_argc;

static void update_ld_library_path(const char *path) {
    void *libdl = dlopen("libdl.so", RTLD_NOW);
    update_ld_path_t *update = libdl ? (update_ld_path_t *) dlsym(libdl, "android_update_LD_LIBRARY_PATH") : NULL;
    if (!update && libdl) update = (update_ld_path_t *) dlsym(libdl, "__loader_android_update_LD_LIBRARY_PATH");
    if (update) update(path);
}

static void *load(const char *relative) {
    char path[4096];
    snprintf(path, sizeof path, "%s/%s", java_home, relative);
    void *handle = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    if (!handle) LOGE("dlopen %s: %s", path, dlerror());
    return handle;
}

static void *jvm_main(void *arg) {
    (void) arg;
    if (chdir(game_dir) != 0) LOGE("chdir %s: %s", game_dir, strerror(errno));
    char path[8192];
    snprintf(path, sizeof path, "%s/lib/server:%s/lib", java_home, java_home);
    update_ld_library_path(path);

    // Dependencies first (libjava needs libjvm, libnio needs libnet), each by absolute path so
    // later DT_NEEDED lookups match the copy already loaded.
    static const char *const order[] = {"lib/server/libjvm.so", "lib/libjava.so", "lib/libjimage.so", "lib/libverify.so",
                                        "lib/libzip.so", "lib/libnet.so", "lib/libnio.so", "lib/libjli.so"};
    void *jli = NULL;
    for (size_t i = 0; i < sizeof order / sizeof *order; i++) {
        void *handle = load(order[i]);
        if (!handle && i < 2) finish(-1001); // without libjvm/libjava there's nothing to run
        if (i == sizeof order / sizeof *order - 1) jli = handle;
    }
    JLI_Launch_t *launch = jli ? (JLI_Launch_t *) dlsym(jli, "JLI_Launch") : NULL;
    if (!launch) {
        LOGE("JLI_Launch not found");
        finish(-1002);
    }
    hook_exit("/libjvm.so");
    hook_exit("/libjli.so");

    LOGI("[Cofre] starting JVM: %d args, java.home=%s", jvm_argc, java_home);
    int code = launch(jvm_argc, jvm_argv, 0, NULL, 0, NULL, "25", "25", "java", "openjdk", JNI_FALSE, JNI_TRUE, JNI_FALSE, 0);
    finish(code);
}

static char *dup_string(JNIEnv *env, jstring s) {
    const char *utf = (*env)->GetStringUTFChars(env, s, NULL);
    char *copy = strdup(utf);
    (*env)->ReleaseStringUTFChars(env, s, utf);
    return copy;
}

JNIEXPORT jint JNICALL Java_com_ignacio_cofre_runtime_GameBridge_launch(
    JNIEnv *env, jclass clazz, jobjectArray args, jstring home, jstring dir, jstring log_file, jstring exit_file, jboolean mirror) {
    (void) clazz;
    mirror_logcat = mirror; // game output holds player names, chat and server addresses
    char *log_path = dup_string(env, log_file);
    redirect_stdio(log_path);
    free(log_path);
    exit_path = dup_string(env, exit_file);
    unlink(exit_path);
    java_home = dup_string(env, home);
    game_dir = dup_string(env, dir);
    jvm_argc = (*env)->GetArrayLength(env, args);
    jvm_argv = calloc(jvm_argc + 1, sizeof(char *));
    for (int i = 0; i < jvm_argc; i++) {
        jstring arg = (*env)->GetObjectArrayElement(env, args, i);
        jvm_argv[i] = dup_string(env, arg);
        (*env)->DeleteLocalRef(env, arg);
    }

    // HotSpot sizes its own threads; this one only runs the launcher code, but JLI recurses a lot.
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, 16 * 1024 * 1024);
    pthread_t thread;
    int result = pthread_create(&thread, &attr, jvm_main, NULL);
    pthread_attr_destroy(&attr);
    if (result != 0) return -result;
    pthread_detach(thread);
    return 0;
}
