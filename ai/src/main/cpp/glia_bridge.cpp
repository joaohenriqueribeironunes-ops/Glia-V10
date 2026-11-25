// Ponte JNI do Glia sobre llama.cpp (upstream).
//
// O motor anterior (glia_core.cpp) só entendia LLaMA/Mistral. O Ternary Bonsai 2
// usa arquitetura `qwen35` (atenção híbrida + Hadamard + pesos ternários), que o
// llama.cpp implementa em src/models/qwen35.cpp. Este arquivo mantém exatamente a
// mesma superfície JNI de GliaNative.kt, então o resto do app não muda.

#include <jni.h>
#include <llama.h>

#include <android/log.h>
#include <unistd.h>

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#define LOG_TAG "GliaBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Handle {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    llama_sampler * smpl = nullptr;
    llama_batch_ext * batch = nullptr;

    int n_ctx = 0;
    int n_batch = 256;
    int n_past = 0;
    int n_vocab = 0;
    llama_token eos = 0;
    llama_token bos = 0;

    bool ready = false;
    std::string info;
    std::string error;
    std::mutex mu;
};

void backend_init_once() {
    static std::once_flag flag;
    std::call_once(flag, [] { llama_backend_init(); });
}

Handle * as_handle(jlong ptr) {
    return reinterpret_cast<Handle *>(static_cast<intptr_t>(ptr));
}

void fail(Handle * h, const std::string & msg) {
    h->ready = false;
    h->error = msg;
    LOGE("%s", msg.c_str());
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_glia_ai_engine_GliaNative_nativeLoadModel(
        JNIEnv * env, jobject /*thiz*/, jstring jpath, jint n_ctx, jint n_threads) {
    backend_init_once();

    Handle * h = new Handle();
    h->n_ctx = std::max<int>(256, n_ctx);

    const char * path = env->GetStringUTFChars(jpath, nullptr);
    const std::string model_path = path ? path : "";
    if (path) env->ReleaseStringUTFChars(jpath, path);

    if (model_path.empty()) {
        fail(h, "caminho do modelo vazio");
        return reinterpret_cast<jlong>(h);
    }
    if (access(model_path.c_str(), R_OK) != 0) {
        fail(h, "modelo inacessível: " + model_path);
        return reinterpret_cast<jlong>(h);
    }

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;       // CPU pura: 2B Q2 roda com folga em 4 threads
    mp.use_extra_bufts = true; // repacking de pesos ternários no CPU

    h->model = llama_model_load_from_file(model_path.c_str(), mp);
    if (h->model == nullptr) {
        fail(h, "llama.cpp não conseguiu carregar o GGUF (arquitetura ou quantização não suportada)");
        return reinterpret_cast<jlong>(h);
    }

    h->vocab = llama_model_get_vocab(h->model);
    h->n_vocab = llama_vocab_n_tokens(h->vocab);
    h->eos = llama_vocab_eos(h->vocab);
    h->bos = llama_vocab_bos(h->vocab);

    char desc[256] = {0};
    llama_model_desc(h->model, desc, sizeof(desc));
    h->info = desc;

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(h->n_ctx);
    cp.n_batch = static_cast<uint32_t>(std::min(h->n_ctx, 512));
    cp.n_ubatch = cp.n_batch;
    // Só o último token do lote precisa de logits. Sem este limite o llama.cpp
    // reserva n_batch × vocab × 4 B (~500 MB) só para logits.
    cp.n_outputs_max = 32;
    cp.n_seq_max = 1;
    cp.n_threads = std::max(1, static_cast<int>(n_threads));
    cp.n_threads_batch = cp.n_threads;
    cp.no_perf = true;
    cp.offload_kqv = false;

    h->n_batch = static_cast<int>(cp.n_batch);

    h->ctx = llama_init_from_model(h->model, cp);
    if (h->ctx == nullptr) {
        fail(h, "falha ao criar o contexto do llama.cpp (memória insuficiente?)");
        return reinterpret_cast<jlong>(h);
    }

    h->batch = llama_batch_ext_init(h->ctx);
    if (h->batch == nullptr) {
        fail(h, "falha ao alocar o lote de tokens");
        return reinterpret_cast<jlong>(h);
    }

    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    h->smpl = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(h->smpl, llama_sampler_init_penalties(h->n_vocab, 64, 1.12f, 0.0f, 0.0f));
    llama_sampler_chain_add(h->smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(h->smpl, llama_sampler_init_top_p(0.90f, 1));
    llama_sampler_chain_add(h->smpl, llama_sampler_init_temp(0.55f));
    llama_sampler_chain_add(h->smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    h->ready = true;
    h->n_past = 0;
    LOGI("modelo pronto: %s (ctx=%d, threads=%d, vocab=%d)", desc, h->n_ctx, cp.n_threads, h->n_vocab);
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT jstring JNICALL
Java_com_glia_ai_engine_GliaNative_nativeLastError(JNIEnv * env, jobject /*thiz*/, jlong ptr) {
    Handle * h = as_handle(ptr);
    if (h == nullptr) return env->NewStringUTF("handle nulo");
    return env->NewStringUTF(h->error.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_glia_ai_engine_GliaNative_nativeIsReady(JNIEnv * /*env*/, jobject /*thiz*/, jlong ptr) {
    Handle * h = as_handle(ptr);
    return (h != nullptr && h->ready) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_glia_ai_engine_GliaNative_nativeFreeModel(JNIEnv * /*env*/, jobject /*thiz*/, jlong ptr) {
    Handle * h = as_handle(ptr);
    if (h == nullptr) return;
    {
        std::lock_guard<std::mutex> lock(h->mu);
        if (h->batch) llama_batch_ext_free(h->batch);
        if (h->smpl) llama_sampler_free(h->smpl);
        if (h->ctx) llama_free(h->ctx);
        if (h->model) llama_model_free(h->model);
        h->batch = nullptr;
        h->smpl = nullptr;
        h->ctx = nullptr;
        h->model = nullptr;
        h->ready = false;
    }
    delete h;
}

JNIEXPORT jintArray JNICALL
Java_com_glia_ai_engine_GliaNative_nativeTokenize(JNIEnv * env, jobject /*thiz*/, jlong ptr, jstring jtext) {
    Handle * h = as_handle(ptr);
    if (h == nullptr || !h->ready) return nullptr;

    const char * text = env->GetStringUTFChars(jtext, nullptr);
    const std::string input = text ? text : "";
    if (text) env->ReleaseStringUTFChars(jtext, text);

    const int n = -llama_tokenize(h->vocab, input.c_str(), static_cast<int32_t>(input.size()),
                                  nullptr, 0, false, true);
    if (n <= 0) return env->NewIntArray(0);

    std::vector<llama_token> tokens(static_cast<size_t>(n));
    const int written = llama_tokenize(h->vocab, input.c_str(), static_cast<int32_t>(input.size()),
                                       tokens.data(), n, false, true);
    if (written <= 0) return env->NewIntArray(0);
    tokens.resize(static_cast<size_t>(written));

    jintArray out = env->NewIntArray(written);
    if (out == nullptr) return nullptr;
    env->SetIntArrayRegion(out, 0, written, reinterpret_cast<const jint *>(tokens.data()));
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_glia_ai_engine_GliaNative_nativePiece(JNIEnv * env, jobject /*thiz*/, jlong ptr, jint id) {
    Handle * h = as_handle(ptr);
    if (h == nullptr || !h->ready) return env->NewStringUTF("");
    char buf[256];
    int n = llama_token_to_piece(h->vocab, static_cast<llama_token>(id), buf, sizeof(buf), 0, true);
    if (n < 0) return env->NewStringUTF("");
    return env->NewStringUTF(std::string(buf, static_cast<size_t>(n)).c_str());
}

JNIEXPORT jstring JNICALL
Java_com_glia_ai_engine_GliaNative_nativeDecode(JNIEnv * env, jobject /*thiz*/, jlong ptr, jintArray jids) {
    Handle * h = as_handle(ptr);
    if (h == nullptr || !h->ready || jids == nullptr) return env->NewStringUTF("");

    const jsize len = env->GetArrayLength(jids);
    std::vector<jint> ids(static_cast<size_t>(len));
    env->GetIntArrayRegion(jids, 0, len, ids.data());

    std::string out;
    out.reserve(static_cast<size_t>(len) * 4);
    for (jint raw : ids) {
        char buf[256];
        int n = llama_token_to_piece(h->vocab, static_cast<llama_token>(raw), buf, sizeof(buf), 0, true);
        if (n < 0) {
            std::string big(static_cast<size_t>(-n), '\0');
            n = llama_token_to_piece(h->vocab, static_cast<llama_token>(raw), big.data(),
                                     static_cast<int32_t>(big.size()), 0, true);
            if (n > 0) out.append(big.data(), static_cast<size_t>(n));
        } else {
            out.append(buf, static_cast<size_t>(n));
        }
    }
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jint JNICALL
Java_com_glia_ai_engine_GliaNative_nativeEval(
        JNIEnv * env, jobject /*thiz*/, jlong ptr, jintArray jids, jint n_past) {
    Handle * h = as_handle(ptr);
    if (h == nullptr || !h->ready || jids == nullptr) return -1;

    const jsize len = env->GetArrayLength(jids);
    if (len <= 0) return n_past;

    std::vector<jint> ids(static_cast<size_t>(len));
    env->GetIntArrayRegion(jids, 0, len, ids.data());

    std::lock_guard<std::mutex> lock(h->mu);

    int pos = n_past;
    jsize offset = 0;
    while (offset < len) {
        const int chunk = std::min<int>(h->n_batch, static_cast<int>(len - offset));
        if (pos + chunk > h->n_ctx) return -1; // contexto cheio

        llama_batch_ext_clear(h->batch);
        for (int i = 0; i < chunk; ++i) {
            const int idx = llama_batch_ext_add_token(h->batch, 0,
                                                      static_cast<llama_token>(ids[offset + i]));
            if (idx < 0) return -1;
            llama_pos p = pos + i;
            llama_batch_ext_set_pos(h->batch, idx, &p);
        }
        llama_batch_ext_set_output_logits(h->batch, chunk - 1, true);

        const int rc = llama_process(h->ctx, LLAMA_PROCESS_TYPE_DECODE, h->batch);
        if (rc != 0) {
            LOGE("llama_process falhou (rc=%d) em pos=%d", rc, pos);
            return -1;
        }
        pos += chunk;
        offset += chunk;
    }

    h->n_past = pos;
    return pos;
}

JNIEXPORT jint JNICALL
Java_com_glia_ai_engine_GliaNative_nativeSample(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong ptr,
        jfloat temperature, jint top_k, jfloat top_p, jfloat repeat_penalty, jintArray /*recent*/) {
    Handle * h = as_handle(ptr);
    if (h == nullptr || !h->ready) return -1;

    std::lock_guard<std::mutex> lock(h->mu);

    // Os parâmetros fixos do chain valem para o Bonsai; ajustes de temperatura por
    // chamada são aplicados reconstruindo apenas o sampler quando divergem.
    static float last_temp = 0.55f;
    static int last_top_k = 40;
    static float last_top_p = 0.90f;
    static float last_penalty = 1.12f;

    if (temperature > 0.0f &&
        (temperature != last_temp || top_k != last_top_k || top_p != last_top_p ||
         repeat_penalty != last_penalty)) {
        llama_sampler_free(h->smpl);
        llama_sampler_chain_params sp = llama_sampler_chain_default_params();
        sp.no_perf = true;
        h->smpl = llama_sampler_chain_init(sp);
        llama_sampler_chain_add(h->smpl,
            llama_sampler_init_penalties(h->n_vocab, 64,
                repeat_penalty > 0.0f ? repeat_penalty : 1.0f, 0.0f, 0.0f));
        llama_sampler_chain_add(h->smpl, llama_sampler_init_top_k(top_k > 0 ? top_k : 40));
        llama_sampler_chain_add(h->smpl, llama_sampler_init_top_p(top_p > 0.0f ? top_p : 0.9f, 1));
        if (temperature > 0.0f) {
            llama_sampler_chain_add(h->smpl, llama_sampler_init_temp(temperature));
        }
        llama_sampler_chain_add(h->smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
        last_temp = temperature;
        last_top_k = top_k;
        last_top_p = top_p;
        last_penalty = repeat_penalty;
    }

    const llama_token id = llama_sampler_sample(h->smpl, h->ctx, -1);
    if (id == LLAMA_TOKEN_NULL) return -1;
    llama_sampler_accept(h->smpl, id);
    return static_cast<jint>(id);
}

JNIEXPORT void JNICALL
Java_com_glia_ai_engine_GliaNative_nativeReset(JNIEnv * /*env*/, jobject /*thiz*/, jlong ptr) {
    Handle * h = as_handle(ptr);
    if (h == nullptr || !h->ready) return;
    std::lock_guard<std::mutex> lock(h->mu);
    llama_memory_t mem = llama_get_memory(h->ctx);
    if (mem) llama_memory_clear(mem, true);
    if (h->smpl) llama_sampler_reset(h->smpl);
    h->n_past = 0;
}

JNIEXPORT jint JNICALL
Java_com_glia_ai_engine_GliaNative_nativeContextSize(JNIEnv * /*env*/, jobject /*thiz*/, jlong ptr) {
 Handle * h = as_handle(ptr);
    return h ? h->n_ctx : 0;
}

JNIEXPORT jint JNICALL
Java_com_glia_ai_engine_GliaNative_nativeEosToken(JNIEnv * /*env*/, jobject /*thiz*/, jlong ptr) {
    Handle * h = as_handle(ptr);
    return h ? static_cast<jint>(h->eos) : -1;
}

JNIEXPORT jint JNICALL
Java_com_glia_ai_engine_GliaNative_nativeBosToken(JNIEnv * /*env*/, jobject /*thiz*/, jlong ptr) {
    Handle * h = as_handle(ptr);
    return h ? static_cast<jint>(h->bos) : -1;
}

JNIEXPORT jstring JNICALL
Java_com_glia_ai_engine_GliaNative_nativeConfig(JNIEnv * env, jobject /*thiz*/, jlong ptr) {
    Handle * h = as_handle(ptr);
    if (h == nullptr) return env->NewStringUTF("");
    return env->NewStringUTF(h->info.c_str());
}

JNIEXPORT jint JNICALL
Java_com_glia_ai_engine_GliaNative_nativeRssKb(JNIEnv * /*env*/, jobject /*thiz*/) {
    FILE * f = fopen("/proc/self/status", "r");
    if (f == nullptr) return 0;
    char line[256];
    int kb = 0;
    while (fgets(line, sizeof(line), f) != nullptr) {
        if (strncmp(line, "VmRSS:", 6) == 0) {
            std::sscanf(line + 6, "%d", &kb);
            break;
        }
    }
    fclose(f);
    return kb;
}

} // extern "C"
