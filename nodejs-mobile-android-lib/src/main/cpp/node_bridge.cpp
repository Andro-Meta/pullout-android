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

    // Calculate total bytes for contiguous argv buffer
    int totalSize = 0;
    for (jsize i = 0; i < argc; i++) {
        jstring s = (jstring)env->GetObjectArrayElement(arguments, i);
        totalSize += (int)env->GetStringUTFLength(s) + 1;
        env->DeleteLocalRef(s);
    }

    char* argsBuffer = (char*)calloc(totalSize, sizeof(char));
    char** argv = (char**)calloc(argc, sizeof(char*));
    char* pos = argsBuffer;

    for (jsize i = 0; i < argc; i++) {
        jstring s = (jstring)env->GetObjectArrayElement(arguments, i);
        const char* cs = env->GetStringUTFChars(s, nullptr);
        size_t len = strlen(cs);
        strncpy(pos, cs, len);
        argv[i] = pos;
        pos += len + 1;
        env->ReleaseStringUTFChars(s, cs);
        env->DeleteLocalRef(s);
    }

    int exitCode = node::Start((int)argc, argv);
    free(argsBuffer);
    free(argv);
    return (jint)exitCode;
}
