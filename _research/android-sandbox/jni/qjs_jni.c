/*
 * QuickJS as a JNI library, for the sandbox probe.
 *
 * The engine is compiled into this same .so rather than linked as a separate
 * library: everything has to arrive through APK native-library loading, and one
 * library is one thing to load.
 *
 * This file deliberately exposes only create / eval / destroy. Files, network
 * and timers are not here yet, because the question this build answers is
 * narrower -- whether a bundled interpreter runs at all inside an
 * `isolatedProcess` -- and a smaller surface is a smaller thing to be wrong
 * about.
 */

#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "quickjs.h"

#define LOG_TAG "QJS-SANDBOX"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* Interrupt handler budget. A script that never returns must not hold the
 * process forever; QuickJS calls this from its own evaluation loop. */
#define INTERRUPT_GRACE_MS 5000

typedef struct {
	JSRuntime *rt;
	JSContext *ctx;
	int64_t deadline_ms;
} Engine;

static int64_t now_ms(void) {
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/*
 * Called periodically by the interpreter. Returning non-zero aborts the script
 * with an uncatchable error, which is the only way to stop a `while(true)`.
 */
static int interrupt_handler(JSRuntime *rt, void *opaque) {
	Engine *e = (Engine *)opaque;
	if (e == NULL) return 0;
	return now_ms() > e->deadline_ms;
}

/* Messages are captured rather than printed: stdout of a library goes to
 * logcat, and the Java side needs them as a return value. Not wired up yet --
 * this build only answers whether the interpreter runs at all. */

JNIEXPORT jstring JNICALL
Java_com_inhuman_probe_QjsSandbox_nativeVersion(JNIEnv *env, jclass clazz) {
	/* There is no JS_GetVersion() in this engine; the Makefile bakes the
	 * version in as a macro from the VERSION file, so it is reported from
	 * CONFIG_VERSION where the build defines it and a literal otherwise. */
#ifdef CONFIG_VERSION
	return (*env)->NewStringUTF(env, CONFIG_VERSION);
#else
	return (*env)->NewStringUTF(env, "quickjs");
#endif
}

JNIEXPORT jlong JNICALL
Java_com_inhuman_probe_QjsSandbox_nativeCreate(JNIEnv *env, jclass clazz) {
	Engine *e = (Engine *)calloc(1, sizeof(Engine));
	if (e == NULL) return 0;

	e->rt = JS_NewRuntime();
	if (e->rt == NULL) { free(e); return 0; }

	/* A heap cap, so an allocation loop fails inside the script instead of
	 * taking the process down with it. */
	JS_SetMemoryLimit(e->rt, 32 * 1024 * 1024);
	JS_SetMaxStackSize(e->rt, 512 * 1024);

	e->ctx = JS_NewContext(e->rt);
	if (e->ctx == NULL) { JS_FreeRuntime(e->rt); free(e); return 0; }

	JS_SetInterruptHandler(e->rt, interrupt_handler, e);

	return (jlong)(intptr_t)e;
}

JNIEXPORT void JNICALL
Java_com_inhuman_probe_QjsSandbox_nativeDestroy(JNIEnv *env, jclass clazz, jlong handle) {
	Engine *e = (Engine *)(intptr_t)handle;
	if (e == NULL) return;
	if (e->ctx) JS_FreeContext(e->ctx);
	if (e->rt) JS_FreeRuntime(e->rt);
	free(e);
}

/*
 * Evaluate a script and return what it produced.
 *
 * The return value is a small JSON envelope rather than a bare string, because
 * the Java side has to distinguish "the script produced this" from "the script
 * failed", and a string cannot carry both.
 */
JNIEXPORT jstring JNICALL
Java_com_inhuman_probe_QjsSandbox_nativeEval(
	JNIEnv *env, jclass clazz, jlong handle, jstring source, jlong timeout_ms) {

	Engine *e = (Engine *)(intptr_t)handle;
	if (e == NULL) return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"no engine\"}");

	const char *src = (*env)->GetStringUTFChars(env, source, NULL);
	if (src == NULL) return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"bad source\"}");

	e->deadline_ms = now_ms() + (timeout_ms > 0 ? timeout_ms : INTERRUPT_GRACE_MS);

	JSValue result = JS_Eval(e->ctx, src, strlen(src), "<agent>",
		JS_EVAL_TYPE_GLOBAL | JS_EVAL_FLAG_STRICT);

	(*env)->ReleaseStringUTFChars(env, source, src);

	char *out = NULL;
	if (JS_IsException(result)) {
		JSValue exc = JS_GetException(e->ctx);
		const char *msg = JS_ToCString(e->ctx, exc);
		/* The interrupt handler's abort arrives as an exception too, so it is
		 * reported as what it is rather than as a script bug. */
		int timed_out = now_ms() > e->deadline_ms;
		size_t n = strlen(msg ? msg : "unknown") + 64;
		out = (char *)malloc(n);
		if (out) snprintf(out, n, "{\"ok\":false,\"error\":\"%s\",\"timeout\":%s}",
			msg ? msg : "unknown", timed_out ? "true" : "false");
		JS_FreeCString(e->ctx, msg);
		JS_FreeValue(e->ctx, exc);
	} else {
		const char *text = JS_ToCString(e->ctx, result);
		size_t n = strlen(text ? text : "") + 64;
		out = (char *)malloc(n);
		if (out) snprintf(out, n, "{\"ok\":true,\"value\":\"%s\"}", text ? text : "");
		JS_FreeCString(e->ctx, text);
	}

	jstring ret = (*env)->NewStringUTF(env, out ? out : "{\"ok\":false,\"error\":\"oom\"}");
	free(out);
	JS_FreeValue(e->ctx, result);
	return ret;
}
