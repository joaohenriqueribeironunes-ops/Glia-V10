#pragma once

// Núcleo de inferência do Glia.
// Lê GGUF (llama.cpp) via mmap, executa forward pass LLaMA/Mistral com
// kernel ternário (TQ2_0) e Q8_0/F16/F32, mantendo KV cache em fp32.

#include <cstdint>
#include <string>
#include <vector>
#include <unordered_map>

namespace glia {

enum GgmlType : uint32_t {
    TYPE_F32 = 0,
    TYPE_F16 = 1,
    TYPE_Q4_0 = 2,
    TYPE_Q8_0 = 8,
    TYPE_TQ2_0 = 35,
};

struct TensorInfo {
    std::string name;
    uint32_t type = 0;
    uint64_t offset = 0;   // relativo ao início da área de dados
    std::vector<uint64_t> dims;
    uint64_t elements = 0;
};

struct ModelConfig {
    int nLayer = 0;
    int nEmbd = 0;
    int nHead = 0;
    int nHeadKv = 0;
    int nFfn = 0;
    int nVocab = 0;
    int nCtxTrain = 0;
    float rmsEps = 1e-5f;
    float ropeBase = 10000.0f;
    int bosId = 1;
    int eosId = 2;
    bool tieWordEmbeddings = true;
};

class Model {
public:
    Model() = default;
    ~Model();

    bool load(const std::string& path, int nCtx, int nThreads);
    void unload();

    const ModelConfig& config() const { return cfg_; }
    const std::string& error() const { return error_; }
    bool ready() const { return ready_; }
    int nCtx() const { return nCtx_; }

    // Tokenização (SentencePiece aproximada: match guloso + byte fallback).
    std::vector<int> tokenize(const std::string& text) const;
    std::string piece(int id) const;
    std::string decode(const std::vector<int>& ids) const;
    int maxTokenLength() const { return maxTokenLen_; }

    // Avalia tokens em sequência a partir de nPast; devolve novo nPast.
    int eval(const std::vector<int>& tokens, int nPast);
    // Amostra o próximo token a partir dos últimos logits.
    int sample(float temperature, int topK, float topP, float repeatPenalty,
               const std::vector<int>& recent);
    void resetKv();

private:
    bool parseGguf(const std::string& path);
    void mapTensors();
    const TensorInfo* find(const std::string& name) const;
    void dequantRow(const TensorInfo& t, uint64_t row, float* out, int nOut) const;
    void matmul(const TensorInfo& w, const float* x, float* y, int nIn, int nOut);
    void rmsnorm(float* out, const float* x, const TensorInfo& w, int n);
    void attention(int layer, int pos);
    void rope(float* vec, int nHeads, int headDim, int pos) const;
    void forwardToken(int token, int pos);
    float* logits() { return logits_.data(); }

    int fd_ = -1;
    uint8_t* map_ = nullptr;
    size_t mapSize_ = 0;
    uint64_t dataOffset_ = 0;
    bool ready_ = false;
    std::string error_;

    ModelConfig cfg_;
    int nCtx_ = 512;
    int nThreads_ = 4;

    std::unordered_map<std::string, TensorInfo> tensors_;
    std::unordered_map<std::string, int> vocabIndex_;
    std::vector<std::string> tokens_;
    std::vector<int32_t> tokenTypes_;
    int maxTokenLen_ = 1;

    // Buffers de trabalho (fp32).
    std::vector<float> x_, xb_, q_, k_, v_, attn_, gate_, up_, ffn_, logits_;
    std::vector<float> kvK_, kvV_;   // [nLayer][nCtx][nKv*headDim]
    int kvSize_ = 0;                 // nCtx * nKv * headDim
};

}  // namespace glia
