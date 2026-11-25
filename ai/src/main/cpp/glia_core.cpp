#include "glia_core.h"

#include <android/log.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <random>
#include <thread>

#if defined(__ARM_NEON)
#include <arm_neon.h>
#endif

#define LOG_TAG "GliaCore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace glia {
namespace {

struct MetaValue {
    int type = 0;
    uint64_t u = 0;
    int64_t i = 0;
    double f = 0.0;
    std::string s;
    std::vector<std::string> strs;
    std::vector<int64_t> ints;
    std::vector<double> floats;
};

bool rd(void* dst, size_t n, FILE* f) { return fread(dst, 1, n, f) == n; }
bool rdU32(FILE* f, uint32_t& v) { return rd(&v, sizeof(v), f); }
bool rdU64(FILE* f, uint64_t& v) { return rd(&v, sizeof(v), f); }

bool rdString(FILE* f, std::string& out) {
    uint64_t len = 0;
    if (!rdU64(f, len)) return false;
    if (len > (1ull << 28)) return false;
    out.assign(len, '\0');
    if (len && !rd(&out[0], len, f)) return false;
    return true;
}

int scalarSize(int type) {
    switch (type) {
        case 0: case 1: case 7: return 1;          // u8, i8, bool
        case 2: case 3: return 2;                  // u16, i16
        case 4: case 5: case 6: return 4;          // u32, i32, f32
        case 10: case 11: case 12: return 8;       // u64, i64, f64
        default: return 0;
    }
}

bool skipScalar(FILE* f, int type) {
    int sz = scalarSize(type);
    if (sz == 0) return false;
    char buf[8];
    return rd(buf, sz, f);
}

bool readMetaValue(FILE* f, int type, MetaValue& out) {
    out.type = type;
    switch (type) {
        case 0: case 2: case 4: {
            uint64_t v = 0;
            int sz = scalarSize(type);
            if (!rd(&v, sz, f)) return false;
            out.u = v;
            out.i = (int64_t)v;
            return true;
        }
        case 1: case 3: case 5: {
            uint64_t v = 0;
            int sz = scalarSize(type);
            if (!rd(&v, sz, f)) return false;
            out.u = v;
            out.i = (int64_t)v;
            return true;
        }
        case 7: {
            uint8_t v = 0;
            if (!rd(&v, 1, f)) return false;
            out.u = v;
            out.i = v;
            return true;
        }
        case 6: {
            float v = 0;
            if (!rd(&v, 4, f)) return false;
            out.f = v;
            return true;
        }
        case 12: {
            double v = 0;
            if (!rd(&v, 8, f)) return false;
            out.f = v;
            return true;
        }
        case 8:
            return rdString(f, out.s);
        case 9: {
            uint32_t etype = 0;
            uint64_t count = 0;
            if (!rdU32(f, etype) || !rdU64(f, count)) return false;
            if (count > (1ull << 26)) return false;
            if (etype == 8) {
                out.strs.reserve(count);
                for (uint64_t k = 0; k < count; k++) {
                    std::string item;
                    if (!rdString(f, item)) return false;
                    out.strs.push_back(std::move(item));
                }
            } else if (etype == 0 || etype == 2 || etype == 4 || etype == 10 ||
                       etype == 1 || etype == 3 || etype == 5 || etype == 11) {
                out.ints.reserve(count);
                const int intSize = scalarSize(etype);
                for (uint64_t k = 0; k < count; k++) {
                    uint64_t v = 0;
                    if (!rd(&v, intSize, f)) return false;
                    out.ints.push_back((int64_t)v);
                }
            } else if (etype == 6 || etype == 12) {
                out.floats.reserve(count);
                int sz = scalarSize(etype);
                for (uint64_t k = 0; k < count; k++) {
                    double v = 0;
                    if (etype == 6) {
                        float fv = 0;
                        if (!rd(&fv, 4, f)) return false;
                        v = fv;
                    } else {
                        if (!rd(&v, 8, f)) return false;
                    }
                    out.floats.push_back(v);
                }
            } else if (etype == 7) {
                for (uint64_t k = 0; k < count; k++) if (!skipScalar(f, 1)) return false;
            } else {
                return false;
            }
            out.u = count;
            return true;
        }
        default:
            return false;
    }
}

inline float fp16ToF32(uint16_t h) {
    uint32_t sign = (uint32_t)(h & 0x8000u) << 16;
    uint32_t exp = (h >> 10) & 0x1fu;
    uint32_t mant = h & 0x3ffu;
    uint32_t bits;
    if (exp == 0) {
        if (mant == 0) {
            bits = sign;
        } else {
            uint32_t e = 127 - 15 + 1;
            while ((mant & 0x400u) == 0) {
                mant <<= 1;
                e--;
            }
            mant &= 0x3ffu;
            bits = sign | (e << 23) | (mant << 13);
        }
    } else if (exp == 31) {
        bits = sign | 0x7f800000u | (mant << 13);
    } else {
        bits = sign | ((exp + 112) << 23) | (mant << 13);
    }
    float out;
    memcpy(&out, &bits, 4);
    return out;
}

inline uint16_t readU16(const uint8_t* p) {
    uint16_t v;
    memcpy(&v, p, 2);
    return v;
}

inline float dotF32(const float* a, const float* b, int n) {
    float sum = 0.f;
    int i = 0;
#if defined(__ARM_NEON)
    float32x4_t acc = vdupq_n_f32(0.f);
    for (; i + 4 <= n; i += 4) {
        acc = vmlaq_f32(acc, vld1q_f32(a + i), vld1q_f32(b + i));
    }
    sum = vaddvq_f32(acc);
#endif
    for (; i < n; i++) sum += a[i] * b[i];
    return sum;
}

int blockSize(uint32_t type) {
    switch (type) {
        case TYPE_F32:
        case TYPE_F16: return 1;
        case TYPE_Q4_0:
        case TYPE_Q8_0: return 32;
        case TYPE_TQ2_0: return 256;
        default: return 0;
    }
}

int typeBytes(uint32_t type) {
    switch (type) {
        case TYPE_F32: return 4;
        case TYPE_F16: return 2;
        case TYPE_Q4_0: return 18;
        case TYPE_Q8_0: return 34;
        case TYPE_TQ2_0: return 66;
        default: return 0;
    }
}

std::string canonicalName(std::string name) {
    // GGUF do llama.cpp nomeia os tensores com sufixo ".weight".
    const std::string weight = ".weight";
    if (name.size() > weight.size() &&
        name.compare(name.size() - weight.size(), weight.size(), weight) == 0) {
        name.erase(name.size() - weight.size());
    }
    // Aceita também o esquema antigo (v2).
    const std::string legacy = "layers.";
    if (name.rfind(legacy, 0) == 0) {
        name = "blk." + name.substr(legacy.size());
        const std::string suffix = ".attention.";
        auto p = name.find(suffix);
        if (p != std::string::npos) name.replace(p, suffix.size(), ".attn.");
    }
    return name;
}

}  // namespace

Model::~Model() { unload(); }

void Model::unload() {
    ready_ = false;
    tensors_.clear();
    vocabIndex_.clear();
    tokens_.clear();
    tokenTypes_.clear();
    kvK_.clear();
    kvV_.clear();
    x_.clear();
    xb_.clear();
    q_.clear();
    k_.clear();
    v_.clear();
    attn_.clear();
    gate_.clear();
    up_.clear();
    ffn_.clear();
    logits_.clear();
    if (map_) {
        munmap(map_, mapSize_);
        map_ = nullptr;
        mapSize_ = 0;
    }
    if (fd_ >= 0) {
        close(fd_);
        fd_ = -1;
    }
}

const TensorInfo* Model::find(const std::string& name) const {
    auto it = tensors_.find(name);
    return it == tensors_.end() ? nullptr : &it->second;
}

bool Model::parseGguf(const std::string& path) {
    fd_ = open(path.c_str(), O_RDONLY);
    if (fd_ < 0) {
        error_ = "nao foi possivel abrir o modelo: " + path;
        return false;
    }
    struct stat st{};
    if (fstat(fd_, &st) != 0 || st.st_size <= 0) {
        error_ = "tamanho de arquivo invalido";
        return false;
    }
    mapSize_ = (size_t)st.st_size;
    map_ = (uint8_t*)mmap(nullptr, mapSize_, PROT_READ, MAP_PRIVATE, fd_, 0);
    if (map_ == MAP_FAILED) {
        map_ = nullptr;
        error_ = "mmap falhou";
        return false;
    }

    FILE* f = fdopen(dup(fd_), "rb");
    if (!f) {
        error_ = "fdopen falhou";
        return false;
    }

    char magic[4];
    uint32_t version = 0;
    uint64_t nTensors = 0, nKv = 0;
    bool ok = rd(magic, 4, f) && memcmp(magic, "GGUF", 4) == 0 && rdU32(f, version) &&
              rdU64(f, nTensors) && rdU64(f, nKv);
    if (!ok) {
        fclose(f);
        error_ = "cabecalho GGUF invalido";
        return false;
    }
    if (version < 2) {
        fclose(f);
        error_ = "GGUF v1 nao suportado; converta para GGUF v3";
        return false;
    }

    std::unordered_map<std::string, MetaValue> meta;
    for (uint64_t k = 0; k < nKv; k++) {
        std::string key;
        uint32_t type = 0;
        if (!rdString(f, key) || !rdU32(f, type)) {
            fclose(f);
            error_ = "metadata GGUF truncada";
            return false;
        }
        MetaValue value;
        if (!readMetaValue(f, type, value)) {
            fclose(f);
            error_ = "valor de metadata nao suportado em " + key;
            return false;
        }
        meta[key] = std::move(value);
    }

    tensors_.reserve((size_t)nTensors);
    for (uint64_t k = 0; k < nTensors; k++) {
        std::string name;
        uint32_t nDims = 0, type = 0;
        uint64_t offset = 0;
        if (!rdString(f, name) || !rdU32(f, nDims) || nDims == 0 || nDims > 4) {
            fclose(f);
            error_ = "infos de tensor invalidas";
            return false;
        }
        TensorInfo info;
        info.name = canonicalName(name);
        info.dims.resize(nDims);
        for (uint32_t d = 0; d < nDims; d++) {
            if (!rdU64(f, info.dims[d])) {
                fclose(f);
                error_ = "dimensao de tensor truncada";
                return false;
            }
        }
        if (!rdU32(f, type) || !rdU64(f, offset)) {
            fclose(f);
            error_ = "offset de tensor truncado";
            return false;
        }
        info.type = type;
        info.offset = offset;
        info.elements = 1;
        for (uint64_t d : info.dims) info.elements *= d;
        if (blockSize(type) == 0 || typeBytes(type) == 0) {
            fclose(f);
            error_ = "tipo de quantizacao nao suportado (" + std::to_string(type) +
                     ") no tensor " + info.name;
            return false;
        }
        tensors_[info.name] = std::move(info);
    }

    long pos = ftell(f);
    fclose(f);
    if (pos < 0) {
        error_ = "posicao de dados desconhecida";
        return false;
    }
    uint64_t alignment = 32;
    auto alignIt = meta.find("general.alignment");
    if (alignIt != meta.end() && alignIt->second.u >= 8) alignment = alignIt->second.u;
    dataOffset_ = ((uint64_t)pos + alignment - 1) / alignment * alignment;
    if (dataOffset_ >= mapSize_) {
        error_ = "area de tensores fora do arquivo";
        return false;
    }

    auto getU = [&](const char* key, uint64_t def) -> uint64_t {
        auto it = meta.find(key);
        return it == meta.end() ? def : it->second.u;
    };
    auto getF = [&](const char* key, double def) -> double {
        auto it = meta.find(key);
        return it == meta.end() ? def : it->second.f;
    };

    cfg_.nLayer = (int)getU("llama.block_count", 0);
    cfg_.nEmbd = (int)getU("llama.embedding_length", 0);
    cfg_.nHead = (int)getU("llama.attention.head_count", 0);
    cfg_.nHeadKv = (int)getU("llama.attention.head_count_kv", (uint64_t)cfg_.nHead);
    cfg_.nFfn = (int)getU("llama.feed_forward_length", 0);
    cfg_.nVocab = (int)getU("llama.vocab_size", 0);
    cfg_.nCtxTrain = (int)getU("llama.context_length", 2048);
    cfg_.rmsEps = (float)getF("llama.attention.layer_norm_rms_epsilon", 1e-5);
    cfg_.ropeBase = (float)getF("llama.rope.freq_base", 10000.0);
    cfg_.bosId = (int)getU("tokenizer.ggml.bos_token_id", 1);
    cfg_.eosId = (int)getU("tokenizer.ggml.eos_token_id", 2);

    auto tokIt = meta.find("tokenizer.ggml.tokens");
    if (tokIt == meta.end() || tokIt->second.strs.empty()) {
        error_ = "vocabulario ausente no GGUF (tokenizer.ggml.tokens)";
        return false;
    }
    tokens_ = tokIt->second.strs;
    if (cfg_.nVocab <= 0) cfg_.nVocab = (int)tokens_.size();

    auto typeIt = meta.find("tokenizer.ggml.token_type");
    if (typeIt != meta.end() && typeIt->second.ints.size() == tokens_.size()) {
        tokenTypes_.resize(tokens_.size());
        for (size_t i = 0; i < tokens_.size(); i++)
            tokenTypes_[i] = (int32_t)typeIt->second.ints[i];
    } else {
        tokenTypes_.assign(tokens_.size(), 1);
    }

    vocabIndex_.reserve(tokens_.size() * 2);
    for (int i = 0; i < (int)tokens_.size(); i++) {
        vocabIndex_.emplace(tokens_[(size_t)i], i);
        maxTokenLen_ = std::max(maxTokenLen_, (int)tokens_[(size_t)i].size());
    }
    maxTokenLen_ = std::min(maxTokenLen_, 48);

    if (cfg_.nLayer <= 0 || cfg_.nEmbd <= 0 || cfg_.nHead <= 0 || cfg_.nFfn <= 0) {
        error_ = "arquitetura LLaMA incompleta no GGUF";
        return false;
    }
    if (cfg_.nEmbd % cfg_.nHead != 0) {
        error_ = "dimensao de cabeca invalida";
        return false;
    }

    const char* required[] = {"token_embd", "output_norm"};
    for (const char* name : required) {
        if (!find(name)) {
            error_ = std::string("tensor obrigatorio ausente: ") + name;
            return false;
        }
    }
    const int headDim = cfg_.nEmbd / cfg_.nHead;
    const int kvDim = cfg_.nHeadKv * headDim;
    for (int l = 0; l < cfg_.nLayer; l++) {
        const std::string base = "blk." + std::to_string(l) + ".attn_";
        for (const char* suffix : {"q", "k", "v", "output", "norm"}) {
            const TensorInfo* t = find(base + suffix);
            if (!t && suffix[0] != 'n') {
                error_ = "tensor ausente: " + base + suffix;
                return false;
            }
        }
        const TensorInfo* wq = find(base + "q");
        const TensorInfo* wk = find(base + "k");
        if (!wq || !wk) {
            error_ = "camada de atencao incompleta em blk." + std::to_string(l);
            return false;
        }
        int inQ = (int)wq->dims[0];
        const uint64_t items = wq->elements / (uint64_t)inQ;
        if (items != (uint64_t)cfg_.nEmbd) {
            error_ = "dimensao de atencao inesperada";
            return false;
        }
        const uint64_t kvItems = wk->elements / (uint64_t)inQ;
        if (kvItems != (uint64_t)kvDim) {
            error_ = "GQA inconsistente (kv dim " + std::to_string(kvItems) + ")";
            return false;
        }
        auto validateType = [&](const TensorInfo* t) {
            if (blockSize(t->type) == 0) return false;
            const uint64_t total = t->elements;
            const uint64_t b = (uint64_t)blockSize(t->type);
            return total % b == 0 && (total / b) * (uint64_t)typeBytes(t->type) <= mapSize_ - dataOffset_ - t->offset;
        };
        if (!validateType(wq) || !validateType(wk)) {
            error_ = "layout ternario incompativel em blk." + std::to_string(l) +
                     " (converta o modelo para Q8_0/Q4_0 se persistir)";
            return false;
        }
    }

    nCtx_ = std::max(64, nCtx_);
    if (cfg_.nCtxTrain > 0) nCtx_ = std::min(nCtx_, cfg_.nCtxTrain);

    kvSize_ = nCtx_ * kvDim;
    kvK_.assign((size_t)cfg_.nLayer * (size_t)kvSize_, 0.f);
    kvV_.assign((size_t)cfg_.nLayer * (size_t)kvSize_, 0.f);
    x_.assign((size_t)cfg_.nEmbd, 0.f);
    xb_.assign((size_t)cfg_.nEmbd, 0.f);
    q_.assign((size_t)cfg_.nEmbd, 0.f);
    k_.assign((size_t)kvDim, 0.f);
    v_.assign((size_t)kvDim, 0.f);
    attn_.assign((size_t)cfg_.nEmbd, 0.f);
    gate_.assign((size_t)cfg_.nFfn, 0.f);
    up_.assign((size_t)cfg_.nFfn, 0.f);
    ffn_.assign((size_t)cfg_.nFfn, 0.f);
    logits_.assign((size_t)cfg_.nVocab, 0.f);

    ready_ = true;
    LOGI("modelo carregado: %d camadas, embd %d, ctx %d, vocab %d",
         cfg_.nLayer, cfg_.nEmbd, nCtx_, cfg_.nVocab);
    return true;
}

bool Model::load(const std::string& path, int nCtx, int nThreads) {
    unload();
    error_.clear();
    nThreads_ = std::max(1, std::min(nThreads, 6));
    nCtx_ = std::max(64, nCtx);
    if (!parseGguf(path)) {
        LOGE("falha ao carregar: %s", error_.c_str());
        unload();
        return false;
    }
    return true;
}

void Model::dequantRow(const TensorInfo& t, uint64_t row, float* out, int n) const {
    const int block = blockSize(t.type);
    const uint64_t perRow = (uint64_t)((n + block - 1) / block);
    const uint8_t* base = map_ + dataOffset_ + t.offset +
                          row * perRow * (uint64_t)typeBytes(t.type);
    switch (t.type) {
        case TYPE_F32:
            memcpy(out, base, (size_t)n * 4);
            break;
        case TYPE_F16:
            for (int i = 0; i < n; i++) out[i] = fp16ToF32(readU16(base + i * 2));
            break;
        case TYPE_Q8_0: {
            for (int b = 0; b < n / 32; b++) {
                const uint8_t* p = base + (size_t)b * 34;
                const float d = fp16ToF32(readU16(p));
                const int8_t* qs = (const int8_t*)(p + 2);
                for (int j = 0; j < 32; j++) out[b * 32 + j] = d * (float)qs[j];
            }
            break;
        }
        case TYPE_Q4_0: {
            for (int b = 0; b < n / 32; b++) {
                const uint8_t* p = base + (size_t)b * 18;
                const float d = fp16ToF32(readU16(p));
                const uint8_t* qs = p + 2;
                for (int j = 0; j < 16; j++) {
                    const int lo = (qs[j] & 0x0f) - 8;
                    const int hi = ((qs[j] >> 4) & 0x0f) - 8;
                    out[b * 32 + j] = d * (float)lo;
                    out[b * 32 + j + 16] = d * (float)hi;
                }
            }
            break;
        }
        case TYPE_TQ2_0: {
            // Bloco de 256 valores: fp16 scale + 64 bytes com 4 valores de 2 bits.
            for (int b = 0; b < n / 256; b++) {
                const uint8_t* p = base + (size_t)b * 66;
                const float d = fp16ToF32(readU16(p));
                const uint8_t* qs = p + 2;
                for (int j = 0; j < 64; j++) {
                    const uint8_t v = qs[j];
                    const int base3 = b * 256 + j * 4;
                    for (int k = 0; k < 4; k++) {
                        const int code = (v >> (2 * k)) & 0x03;
                        // 0 -> -1, 1 -> 0, 2 -> +1, 3 -> 0 (reservado)
                        const int tri = code == 0 ? -1 : (code == 1 ? 0 : (code == 2 ? 1 : 0));
                        out[base3 + k] = d * (float)tri;
                    }
                }
            }
            break;
        }
        default:
            break;
    }
}

void Model::matmul(const TensorInfo& w, const float* x, float* y, int nIn, int nOut) {
    if (nOut <= 0) return;
    const int threads = std::max(1, std::min(nThreads_, nOut / 8));
    auto work = [&](int r0, int r1, std::vector<float>& row) {
        for (int r = r0; r < r1; r++) {
            dequantRow(w, (uint64_t)r, row.data(), nIn);
            y[r] = dotF32(row.data(), x, nIn);
        }
    };
    if (threads <= 1) {
        std::vector<float> row((size_t)nIn);
        work(0, nOut, row);
        return;
    }
    std::vector<std::vector<float>> rows((size_t)threads);
    std::vector<std::thread> pool;
    const int chunk = (nOut + threads - 1) / threads;
    for (int t = 0; t < threads; t++) {
        const int a = t * chunk;
        const int b = std::min(nOut, a + chunk);
        if (a >= b) break;
        rows[(size_t)t].assign((size_t)nIn, 0.f);
        pool.emplace_back(work, a, b, std::ref(rows[(size_t)t]));
    }
    for (auto& th : pool) th.join();
}

void Model::rmsnorm(float* out, const float* x, const TensorInfo& w, int n) {
    float sum = 0.f;
    for (int i = 0; i < n; i++) sum += x[i] * x[i];
    const float scale = 1.f / std::sqrt(sum / (float)n + cfg_.rmsEps);
    std::vector<float> wrow((size_t)n);
    dequantRow(w, 0, wrow.data(), n);
    for (int i = 0; i < n; i++) out[i] = x[i] * scale * wrow[(size_t)i];
}

void Model::rope(float* vec, int nHeads, int headDim, int pos) const {
    const int half = headDim / 2;
    for (int h = 0; h < nHeads; h++) {
        float* base = vec + (size_t)h * headDim;
        for (int i = 0; i < half; i++) {
            const float freq = std::pow(cfg_.ropeBase, -2.0f * (float)i / (float)headDim);
            const float angle = (float)pos * freq;
            const float c = std::cos(angle);
            const float s = std::sin(angle);
            const float x0 = base[2 * i];
            const float x1 = base[2 * i + 1];
            base[2 * i] = x0 * c - x1 * s;
            base[2 * i + 1] = x0 * s + x1 * c;
        }
    }
}

void Model::attention(int layer, int pos) {
    const int headDim = cfg_.nEmbd / cfg_.nHead;
    const int kvDim = cfg_.nHeadKv * headDim;
    const int group = std::max(1, cfg_.nHead / cfg_.nHeadKv);
    float* kb = kvK_.data() + (size_t)layer * (size_t)kvSize_;
    float* vb = kvV_.data() + (size_t)layer * (size_t)kvSize_;
    const float invScale = 1.0f / std::sqrt((float)headDim);
    std::vector<float> scores((size_t)pos + 1);
    std::fill(attn_.begin(), attn_.end(), 0.f);
    for (int h = 0; h < cfg_.nHead; h++) {
        const float* qh = q_.data() + (size_t)h * headDim;
        const float* kbHeadBase = kb + (size_t)(h / group) * headDim;
        float maxs = -1e30f;
        for (int t = 0; t <= pos; t++) {
            const float* kt = kbHeadBase + (size_t)t * kvDim;
            const float s = dotF32(qh, kt, headDim) * invScale;
            scores[(size_t)t] = s;
            maxs = std::max(maxs, s);
        }
        float sum = 0.f;
        for (int t = 0; t <= pos; t++) {
            const float e = std::exp(scores[(size_t)t] - maxs);
            scores[(size_t)t] = e;
            sum += e;
        }
        const float inv = sum > 0 ? 1.f / sum : 0.f;
        float* out = attn_.data() + (size_t)h * headDim;
        const float* vbHeadBase = vb + (size_t)(h / group) * headDim;
        for (int t = 0; t <= pos; t++) {
            const float p = scores[(size_t)t] * inv;
            if (p == 0.f) continue;
            const float* vt = vbHeadBase + (size_t)t * kvDim;
            for (int d = 0; d < headDim; d++) out[d] += p * vt[d];
        }
    }
}

void Model::forwardToken(int token, int pos) {
    if (token < 0) token = 0;
    if (token >= cfg_.nVocab) token = cfg_.nVocab - 1;
    const int headDim = cfg_.nEmbd / cfg_.nHead;
    const int kvDim = cfg_.nHeadKv * headDim;

    dequantRow(*find("token_embd"), (uint64_t)token, x_.data(), cfg_.nEmbd);

    for (int l = 0; l < cfg_.nLayer; l++) {
        const std::string base = "blk." + std::to_string(l) + ".";
        const TensorInfo* attnNorm = find(base + "attn_norm");
        const TensorInfo* wq = find(base + "attn_q");
        const TensorInfo* wk = find(base + "attn_k");
        const TensorInfo* wv = find(base + "attn_v");
        const TensorInfo* wo = find(base + "attn_output");
        const TensorInfo* ffnNorm = find(base + "ffn_norm");
        const TensorInfo* wGate = find(base + "ffn_gate");
        const TensorInfo* wUp = find(base + "ffn_up");
        const TensorInfo* wDown = find(base + "ffn_down");
        if (!attnNorm || !wq || !wk || !wv || !wo || !ffnNorm || !wGate || !wUp || !wDown) {
            return;
        }

        rmsnorm(xb_.data(), x_.data(), *attnNorm, cfg_.nEmbd);
        matmul(*wq, xb_.data(), q_.data(), cfg_.nEmbd, cfg_.nEmbd);
        matmul(*wk, xb_.data(), k_.data(), cfg_.nEmbd, kvDim);
        matmul(*wv, xb_.data(), v_.data(), cfg_.nEmbd, kvDim);
        rope(q_.data(), cfg_.nHead, headDim, pos);
        rope(k_.data(), cfg_.nHeadKv, headDim, pos);

        float* kb = kvK_.data() + (size_t)l * (size_t)kvSize_ + (size_t)pos * kvDim;
        float* vb = kvV_.data() + (size_t)l * (size_t)kvSize_ + (size_t)pos * kvDim;
        memcpy(kb, k_.data(), sizeof(float) * (size_t)kvDim);
        memcpy(vb, v_.data(), sizeof(float) * (size_t)kvDim);

        attention(l, pos);
        matmul(*wo, attn_.data(), xb_.data(), cfg_.nEmbd, cfg_.nEmbd);
        for (int i = 0; i < cfg_.nEmbd; i++) x_[(size_t)i] += xb_[(size_t)i];

        rmsnorm(xb_.data(), x_.data(), *ffnNorm, cfg_.nEmbd);
        matmul(*wGate, xb_.data(), gate_.data(), cfg_.nEmbd, cfg_.nFfn);
        matmul(*wUp, xb_.data(), up_.data(), cfg_.nEmbd, cfg_.nFfn);
        for (int i = 0; i < cfg_.nFfn; i++) {
            const float g = gate_[(size_t)i];
            const float silu = g / (1.f + std::exp(-g));
            ffn_[(size_t)i] = silu * up_[(size_t)i];
        }
        matmul(*wDown, ffn_.data(), xb_.data(), cfg_.nFfn, cfg_.nEmbd);
        for (int i = 0; i < cfg_.nEmbd; i++) x_[(size_t)i] += xb_[(size_t)i];
    }

    const TensorInfo* outNorm = find("output_norm");
    if (outNorm) rmsnorm(xb_.data(), x_.data(), *outNorm, cfg_.nEmbd);
    else memcpy(xb_.data(), x_.data(), sizeof(float) * (size_t)cfg_.nEmbd);

    const TensorInfo* wOut = find("output");
    if (!wOut) wOut = find("token_embd");
    if (wOut) matmul(*wOut, xb_.data(), logits_.data(), cfg_.nEmbd, cfg_.nVocab);
}

int Model::eval(const std::vector<int>& tokens, int nPast) {
    if (!ready_) return nPast;
    int p = nPast;
    for (int token : tokens) {
        if (p >= nCtx_) break;
        forwardToken(token, p);
        p++;
    }
    return p;
}

int Model::sample(float temperature, int topK, float topP, float repeatPenalty,
                  const std::vector<int>& recent) {
    if (!ready_ || logits_.empty()) return cfg_.eosId;
    const int n = std::min(cfg_.nVocab, (int)logits_.size());
    std::vector<float> logits(logits_.begin(), logits_.begin() + n);

    if (repeatPenalty > 1.f && !recent.empty()) {
        const size_t window = std::min<size_t>(recent.size(), 256);
        for (size_t i = recent.size() - window; i < recent.size(); i++) {
            const int id = recent[i];
            if (id < 0 || id >= n) continue;
            float& v = logits[(size_t)id];
            v = v > 0 ? v / repeatPenalty : v * repeatPenalty;
        }
    }

    if (temperature <= 0.01f) {
        int best = 0;
        for (int i = 1; i < n; i++) if (logits[(size_t)i] > logits[(size_t)best]) best = i;
        return best;
    }

    for (int i = 0; i < n; i++) logits[(size_t)i] /= temperature;
    float maxLogit = -1e30f;
    for (int i = 0; i < n; i++) maxLogit = std::max(maxLogit, logits[(size_t)i]);
    double sum = 0;
    for (int i = 0; i < n; i++) {
        const float e = std::exp(logits[(size_t)i] - maxLogit);
        logits[(size_t)i] = e;
        sum += e;
    }
    if (sum <= 0) return cfg_.eosId;
    for (int i = 0; i < n; i++) logits[(size_t)i] = (float)(logits[(size_t)i] / sum);

    // Ordena candidatos para top-k/top-p.
    std::vector<int> idx((size_t)n);
    for (int i = 0; i < n; i++) idx[(size_t)i] = i;
    const int k = topK > 0 ? std::min(topK, n) : n;
    std::partial_sort(idx.begin(), idx.begin() + k, idx.end(),
                      [&](int a, int b) { return logits[(size_t)a] > logits[(size_t)b]; });

    double cum = 0;
    int keep = 0;
    for (; keep < k; keep++) {
        cum += logits[(size_t)idx[(size_t)keep]];
        if (topP > 0.f && cum >= topP) { keep++; break; }
    }
    if (keep <= 0) keep = 1;
    double mass = 0;
    for (int i = 0; i < keep; i++) mass += logits[(size_t)idx[(size_t)i]];
    if (mass <= 0) return idx[0];

    static thread_local std::mt19937 rng{std::random_device{}()};
    std::uniform_real_distribution<double> dist(0.0, mass);
    double r = dist(rng);
    for (int i = 0; i < keep; i++) {
        r -= logits[(size_t)idx[(size_t)i]];
        if (r <= 0) return idx[(size_t)i];
    }
    return idx[0];
}

void Model::resetKv() {
    std::fill(kvK_.begin(), kvK_.end(), 0.f);
    std::fill(kvV_.begin(), kvV_.end(), 0.f);
}

std::vector<int> Model::tokenize(const std::string& text) const {
    std::vector<int> out;
    if (tokens_.empty()) return out;
    // SentencePiece: espaço inicial e "▁" como marcador de espaço.
    std::string s;
    s.reserve(text.size() + 8);
    s += "\xE2\x96\x81";
    for (size_t i = 0; i < text.size(); i++) {
        const char c = text[i];
        if (c == ' ') {
            s += "\xE2\x96\x81";
        } else if (c == '\n' || c == '\t' || c == '\r') {
            // tokens de controle/whitespace caem no byte fallback
            s.push_back(c);
        } else {
            s.push_back(c);
        }
    }

    size_t i = 0;
    while (i < s.size()) {
        bool found = false;
        const size_t maxLen = std::min((size_t)maxTokenLen_, s.size() - i);
        for (size_t len = maxLen; len >= 1; len--) {
            auto it = vocabIndex_.find(s.substr(i, len));
            if (it != vocabIndex_.end()) {
                out.push_back(it->second);
                i += len;
                found = true;
                break;
            }
            if (len == 1) break;
        }
        if (!found) {
            char buf[8];
            snprintf(buf, sizeof(buf), "<0x%02X>", (unsigned char)s[i]);
            auto it = vocabIndex_.find(buf);
            if (it != vocabIndex_.end()) out.push_back(it->second);
            i++;
        }
    }
    return out;
}

std::string Model::piece(int id) const {
    if (id < 0 || id >= (int)tokens_.size()) return std::string();
    std::string s = tokens_[(size_t)id];
    std::string clean;
    clean.reserve(s.size());
    for (size_t i = 0; i < s.size();) {
        if (i + 2 < s.size() && (unsigned char)s[i] == 0xE2 && (unsigned char)s[i + 1] == 0x96 &&
            (unsigned char)s[i + 2] == 0x81) {
            clean.push_back(' ');
            i += 3;
        } else {
            clean.push_back(s[i]);
            i++;
        }
    }
    return clean;
}

std::string Model::decode(const std::vector<int>& ids) const {
    std::string out;
    for (int id : ids) {
        if (id < 0 || id >= (int)tokens_.size()) continue;
        const std::string& raw = tokens_[(size_t)id];
        if (raw.size() == 6 && raw[0] == '<' && raw[1] == '0' && raw[2] == 'x' && raw[5] == '>' &&
            raw[4] != '>') {
            int hi = 0, lo = 0;
            auto hex = [](char c) -> int {
                if (c >= '0' && c <= '9') return c - '0';
                if (c >= 'a' && c <= 'f') return c - 'a' + 10;
                if (c >= 'A' && c <= 'F') return c - 'A' + 10;
                return -1;
            };
            hi = hex(raw[3]);
            lo = hex(raw[4]);
            if (hi >= 0 && lo >= 0) {
                out.push_back((char)((hi << 4) | lo));
                continue;
            }
        }
        out += piece(id);
    }
    return out;
}

}  // namespace glia
