// node_bridge.cpp - JNI bridge between Java NodeJsMobile and libnode
#include <jni.h>
#include <string>
#include <cstdlib>
#include <pthread.h>
#include <unistd.h>
#include <android/log.h>
#include "node.h"

#define ADBTAG "NODEJS-MOBILE"

// Log redirect pipes
static int pipe_stdout[2];
static int pipe_stderr[2];
static pthread_t thread_stdout;
static pthread_t thread_stderr;

static void* thread_stderr_func(void*) {
    ssize_t n;
    char buf[2048];
    while ((n = read(pipe_stderr[0], buf, sizeof(buf) - 1)) > 0) {
        if (buf[n - 1] == '\n') --n;
        buf[n] = 0;
        __android_log_write(ANDROID_LOG_ERROR, ADBTAG, buf);
    }
    return nullptr;
}

static void* thread_stdout_func(void*) {
    ssize_t n;
    char buf[2048];
    while ((n = read(pipe_stdout[0], buf, sizeof(buf) - 1)) > 0) {
        if (buf[n - 1] == '\n') --n;
        buf[n] = 0;
        __android_log_write(ANDROID_LOG_INFO, ADBTAG, buf);
    }
    return nullptr;
}

static int start_redirecting_stdout_stderr() {
    setvbuf(stdout, nullptr, _IONBF, 0);
    pipe(pipe_stdout);
    dup2(pipe_stdout[1], STDOUT_FILENO);

    setvbuf(stderr, nullptr, _IONBF, 0);
    pipe(pipe_stderr);
    dup2(pipe_stderr[1], STDERR_FILENO);

    if (pthread_create(&thread_stdout, nullptr, thread_stdout_func, nullptr) == -1) return -1;
    pthread_detach(thread_stdout);
    if (pthread_create(&thread_stderr, nullptr, thread_stderr_func, nullptr) == -1) return -1;
    pthread_detach(thread_stderr);
    return 0;
}

#if defined(__arm__)
  #define CURRENT_ABI "armeabi-v7a"
#elif defined(__aarch64__)
  #define CURRENT_ABI "arm64-v8a"
#elif defined(__i386__)
  #define CURRENT_ABI "x86"
#elif defined(__x86_64__)
  #define CURRENT_ABI "x86_64"
#else
  #define CURRENT_ABI "unknown"
#endif

extern "C"
JNIEXPORT jstring JNICALL
Java_com_janeasystems_nodejsmobile_NodeJsMobile_getCurrentABIName(JNIEnv* env, jobject) {
    return env->NewStringUTF(CURRENT_ABI);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_janeasystems_nodejsmobile_NodeJsMobile_startNodeWithArguments(
        JNIEnv* env,
        jobject,
        jobjectArray arguments,
        jboolean redirectToLogcat) {

    if (redirectToLogcat) {
        if (start_redirecting_stdout_stderr() == -1) {
            __android_log_write(ANDROID_LOG_ERROR, ADBTAG,
                "Couldn't redirect stdout/stderr to logcat.");
        }
    }

    jsize argc = env->GetArrayLength(arguments);
    if (argc < 2) {
        __android_log_write(ANDROID_LOG_ERROR, ADBTAG, "Need at least 2 arguments: node <script>");
        return -1;
    }

    // Extract all argument strings
    char** argv = (char**)calloc(argc, sizeof(char*));
    char** toFree = (char**)calloc(argc, sizeof(char*));

    for (jsize i = 0; i < argc; i++) {
        jstring s = (jstring)env->GetObjectArrayElement(arguments, i);
        const char* cs = env->GetStringUTFChars(s, nullptr);
        argv[i] = strdup(cs);
        toFree[i] = argv[i];
        env->ReleaseStringUTFChars(s, cs);
        env->DeleteLocalRef(s);
    }

    // argv[1] is the script path, e.g. /data/user/0/.../files/nodejs-project/main.js
    // Derive the project directory from the script path
    const char* scriptPath = argv[1];
    char projectDir[PATH_MAX] = {0};

    // Find the last '/' to get the directory containing main.js
    const char* lastSlash = strrchr(scriptPath, '/');
    if (lastSlash != nullptr) {
        size_t dirLen = (size_t)(lastSlash - scriptPath);
        if (dirLen < PATH_MAX - 1) {
            strncpy(projectDir, scriptPath, dirLen);
            projectDir[dirLen] = '\0';
        }
    }

    if (projectDir[0] != '\0') {
        // Critical: set working directory to the nodejs project.
        // Node.js uses CWD for dotenv file loading and some module resolution.
        if (chdir(projectDir) != 0) {
            __android_log_write(ANDROID_LOG_WARN, ADBTAG, "chdir to project dir failed");
        } else {
            __android_log_print(ANDROID_LOG_INFO, ADBTAG, "CWD set to: %s", projectDir);
        }

        // HOME must be set; Node.js / libuv crash on Android without it
        if (getenv("HOME") == nullptr || getenv("HOME")[0] == '\0') {
            setenv("HOME", projectDir, 1);
            __android_log_print(ANDROID_LOG_INFO, ADBTAG, "HOME set to: %s", projectDir);
        }

        // TMPDIR must point to a writable directory; libuv uses it for temp files
        // Android's tmpdir is typically /data/user/0/<pkg>/cache
        // We derive it from projectDir: go two levels up (files/nodejs-project -> files -> app-root)
        // then use cache. Simplest safe choice: use projectDir itself as tmpdir fallback.
        if (getenv("TMPDIR") == nullptr || getenv("TMPDIR")[0] == '\0') {
            // Build cache path: replace /files/nodejs-project with /cache
            char tmpDir[PATH_MAX] = {0};
            const char* filesMarker = strstr(projectDir, "/files/");
            if (filesMarker != nullptr) {
                size_t prefixLen = (size_t)(filesMarker - projectDir);
                snprintf(tmpDir, sizeof(tmpDir), "%.*s/cache", (int)prefixLen, projectDir);
            } else {
                strncpy(tmpDir, projectDir, sizeof(tmpDir) - 1);
            }
            setenv("TMPDIR", tmpDir, 1);
            __android_log_print(ANDROID_LOG_INFO, ADBTAG, "TMPDIR set to: %s", tmpDir);
        }
    }

    __android_log_print(ANDROID_LOG_INFO, ADBTAG,
        "Starting node::Start with %d args, script=%s", (int)argc, scriptPath);

    int exitCode = node::Start((int)argc, argv);

    __android_log_print(ANDROID_LOG_INFO, ADBTAG, "node::Start exited with code %d", exitCode);

    for (jsize i = 0; i < argc; i++) free(toFree[i]);
    free(argv);
    free(toFree);

    return (jint)exitCode;
}
