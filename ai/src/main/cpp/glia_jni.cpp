#include <jni.h>

#include <unistd.h>

#include <cstdio>
#include <string>
#include <vector>

#include "glia_core.h"

namespace {
glia::Model* asModel(jlong handle) { return reinterpret_cast<glia::Model*>(handle); }

std::string jstr(JNIEnv* env, jstring value) {
    if (!value) return std::string();
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return out;
}

jstring toJString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_glia_ai_engine_GliaNative_nativeLoadModel(JNIEnv* env, jobject, jstring path, jint nCtx,
                                                   jint nThreads) {
    auto* model = new glia::Model();
    if (!model->load(jstr(env, path), nCtx, nThreads)) {
        return reinterpret_cast<jlong>(model);  // handle mantido para ler error()
    }
    return reinterpret_cast<jlong>(model);
}

JNIEXPORT jstring JNICALL
Java_com_glia_ai_engine_GliaNative_nativeLastError(JNIEnv* env, jobject, jlong handle) {
    auto* model = asModel(handle);
    if (!model) return toJString(env, "motor nativo indisponivel");
    return toJString(env, model->error());
}

JNIEXPORT jboolean JNICALL
Java_com_glia_ai_engine_GliaNative_nativeIsReady(JNIEnv*, jobject, jlong handle) {
    auto* model = asModel(handle);
    return (model && model->ready()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_glia_ai_engine_GliaNative_nativeFreeModel(JNIEnv*, jobject, jlong handle) {
    auto* model = asModel(handle);
    if (model) {
        model->unload();
        delete model;
    }
}

JNIEXPORT jintArray JNICALL
Java_com_glia_ai_engine_GliaNative_nativeTokenize(JNIEnv* env, jobject, jlong handle,
                                                  jstring text) {
    auto* model = asModel(handle);
    std::vector<int> ids;
    if (model && model->ready()) ids = model->tokenize(jstr(env, text));
    jintArray result = env->NewIntArray((jsize)ids.size());
    if (result && !ids.empty()) {
        env->SetIntArrayRegion(result, 0, (jsize)ids.size(), reinterpret_cast<const jint*>(ids.data()));
    }
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_glia_ai_engine_GliaNative_nativePiece(JNIEnv* env, jobject, jlong handle, jint id) {
    auto* model = asModel(handle);
    return toJString(env, model ? model->piece(id) : std::string());
}

JNIEXPORT jstring JNICALL
Java_com_glia_ai_engine_GliaNative_nativeDecode(JNIEnv* env, jobject, jlong handle,
                                                jintArray ids) {
    auto* model = asModel(handle);
    std::vector<int> values;
    if (ids) {
        const jsize n = env->GetArrayLength(ids);
        values.resize((size_t)n);
        env->GetIntArrayRegion(ids, 0, n, reinterpret_cast<jint*>(values.data()));
    }
    return toJString(env, model ? model->decode(values) : std::string());
}

JNIEXPORT jint JNICALL
Java_com_glia_ai_engine_GliaNative_nativeEval(JNIEnv* env, jobject, jlong handle, jintArray ids,
                                              jint nPast) {
    auto* model = asModel(handle);
    if (!model || !model->ready() || !ids) return nPast;
    const jsize n = env->GetArrayLength(ids);
    std::vector<int> values((size_t)n);
    env->GetIntArrayRegion(ids, 0, n, reinterpret_cast<jint*>(values.data()));
    return model->eval(values, nPast);
}

JNIEXPORT jint JNICALL
Java_com_glia_ai_engine_GliaNative_nativeSample(JNIEnv* env, jobject, jlong handle, jfloat temp,
                                                jint topK, jfloat topP, jfloat penalty,
                                                jintArray recent) {
    auto* model = asModel(handle);
    if (!model || !model->ready()) return -1;
    std::vector<int> values;
    if (recent) {
        const jsize n = env->GetArrayLength(recent);
        values.resize((size_t)n);
        env->GetIntArrayRegion(recent, 0, n, reinterpret_cast<jint*>(values.data()));
    }
    return model->sample(temp, topK, topP, penalty, values);
}

JNIEXPORT void JNICALL
Java_com_glia_ai_engine_GliaNative_nativeReset(JNIEnv*, jobject, jlong handle) {
    auto* model = asModel(handle);
    if (model) model->resetKv();
}

JNIEXPORT jint JNICALL
Java_com_glia_ai_engine_GliaNative_nativeContextSize(JNIEnv*, jobject, jlong handle) {
    auto* model = asModel(handle);
    return model ? model->nCtx() : 0;
}

JNIEXPORT jint JNICALL
Java_com_glia_ai_engine_GliaNative_nativeEosToken(JNIEnv*, jobject, jlong handle) {
    auto* model = asModel(handle);
    return model ? model->config().eosId : 2;
}

JNIEXPORT jint JNICALL
Java_com_glia_ai_engine_GliaNative_nativeBosToken(JNIEnv*, jobject, jlong handle) {
    auto* model = asModel(handle);
    return model ? model->config().bosId : 1;
}

JNIEXPORT jstring JNICALL
Java_com_glia_ai_engine_GliaNative_nativeConfig(JNIEnv* env, jobject, jlong handle) {
    auto* model = asModel(handle);
    if (!model) return toJString(env, "");
    const auto& c = model->config();
    char buf[256];
    snprintf(buf, sizeof(buf), "layers=%d;embd=%d;heads=%d;kv=%d;ffn=%d;vocab=%d;ctx=%d",
             c.nLayer, c.nEmbd, c.nHead, c.nHeadKv, c.nFfn, c.nVocab, model->nCtx());
    return toJString(env, buf);
}

JNIEXPORT jint JNICALL
Java_com_glia_ai_engine_GliaNative_nativeRssKb(JNIEnv*, jobject) {
    FILE* f = fopen("/proc/self/statm", "r");
    if (!f) return 0;
    long pages = 0, resident = 0;
    const int read = fscanf(f, "%ld %ld", &pages, &resident);
    fclose(f);
    if (read != 2) return 0;
    return (jint)(resident * (long)sysconf(_SC_PAGESIZE) / 1024L);
}

}  // extern "C"
