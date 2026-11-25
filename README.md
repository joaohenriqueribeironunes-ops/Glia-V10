# Glia

Assistente pessoal **100% offline** para Android, feito para o Redmi Note 11S
(Helio G96, Mali-G57 MC2, 6 GB, HyperOS). Kotlin + Jetpack Compose, IA local
(Ternary Bonsai 2 2B em GGUF executado pelo **llama.cpp** via JNI, com o modelo
embutido no APK), voz offline e automação do sistema por `AccessibilityService`.

Nenhum dado sai do aparelho: não há permissão de internet declarada no app.

## Download

APK assinado (arm64, Android 11+), publicado nas releases:

**https://github.com/joaohenriqueribeironunes-ops/Glia-V10/releases/latest**

### Assets do release `v1.1`

| Asset | Tamanho | Licença | Para quê |
|---|---|---|---|
| `app-release.apk` | 551 MB | — | app **com o modelo dentro** (arm64, Android 11+) |
| `Ternary-Bonsai-2-2B-Q2_0.gguf` | 547 MB | Apache 2.0 (PrismML) | mesmo GGUF do APK, avulso para inspeção |
| `vosk-model-small-pt-0.3.zip` | 31 MB | Apache 2.0 (Alpha Cephei) | wake word + ASR offline em pt-BR |

O GGUF vai **dentro do APK** em `assets/model/glia.gguf`, gravado sem compressão
(`noCompress += "gguf"`). O llama.cpp recebe o caminho `/proc/self/fd/<fd>` de um
`AssetFileDescriptor` aberto, então o próprio APK é mapeado com `mmap` — nada de
copiar 550 MB para o armazenamento na primeira execução.

Integridade do modelo (bate com o `SHA256SUMS` oficial do Hugging Face):

```
c2893fa6f951daee8b7263d5f49b455e57989ef56417a22125b03566dc560650  Ternary-Bonsai-2-2B-Q2_0.gguf
6e1ce909032e1afa7a88e68a3d628ecafff302bdf195befab308826c395e93b7  vosk-model-small-pt-0.3.zip
```

> **Por que Q2_0 e não PTQ1_0?** O PTQ1_0 (409 MB) usa o tipo GGML **143**, que só
existe no fork PrismML (PR #29672 ainda aberto). O Q2_0 (547 MB,
`general.file_type = 41`, 151 tensores `Q2_0` + 169 `F32`) carrega no llama.cpp
upstream: `arch = qwen35` está implementado em `src/models/qwen35.cpp` (atenção
híbrida — 18 camadas gated-delta-net + 6 completas a cada 4) e o carregador trata
`prism.hadamard.version = 2` (rotação de Hadamard com sinais explícitos). São os
mesmos pesos, com um bit a mais por valor.

Se você colocar outro `.gguf` na pasta de modelos (`adb push`), ele tem
prioridade sobre o embutido.

---

## 1. Arquitetura

```
app/                 orquestração, serviços do sistema, tile, onboarding, MainActivity
 ├─ GliaApplication   grafo de dependências, pressão de memória
 ├─ GliaViewModel     estado da UI, histórico, confirmação por voz
 ├─ ConversationEngine  roteador → modelo → tool calling → execução
 ├─ AssistantRouter   consultas/ações determinísticas (antes do LLM)
 ├─ GliaTileService   tile da central de controle
 └─ BootReceiver      retomada da escuta após reboot

ai/                  motor de linguagem
 ├─ cpp/glia_bridge.cpp ponte JNI sobre llama.cpp (modelo, contexto, batch_ext,
 │                      sampler, tokenize/piece, memória recorrente)
 ├─ cpp/llama/          cabeçalhos do llama.cpp (MIT) usados no build
 ├─ jniLibs/arm64-v8a/  libllama.so + libggml*.so compiladas para o Redmi
 ├─ cpp/glia_core.cpp   motor antigo (só LLaMA/Mistral), fora do build — histórico
 ├─ GliaEngine          carregamento, contexto, streaming de tokens
 ├─ MemoryGovernor      contexto adaptativo (2048 → 1024 → 512 → 256) e liberação
 ├─ ModelStore          GGUF avulso ou embutido via /proc/self/fd, modelo ativo
 ├─ prompt/GliaPrompt   persona + ChatML + lista de ferramentas
 ├─ tools/              ToolSpec, parser de JSON, validador de schema
 └─ OfflinePersonaEngine  fallback quando não há modelo instalado

voice/               voz offline
 ├─ VoskEngine          AudioRecord 16 kHz + Vosk (gramática p/ wake word)
 ├─ WakeWordService     FGS microfone, ciclo escuta → comando → TTS → escuta
 ├─ TtsSpeaker          TTS pt-BR do aparelho, com estado e cancelamento
 └─ VoiceBus            estado (idle/ouvindo/pensando/falando) e comandos

calendar/            CalendarProvider
 ├─ CalendarRepository  hoje, amanhã, semana, próximo, criação de eventos
 └─ TemporalResolver    hora/data/dia da semana, "daqui a 10 dias", "25/12"

device-control/      estado e ações
 ├─ DeviceStateReader   bateria, RAM, armazenamento, rede, volume, brilho
 ├─ DeviceActions       volume, brilho, lanterna, DND, alarme, timer, música,
 │                      ligação, mensagem, abertura de apps
 ├─ AccessibilityBridge + GliaAccessibilityService  tocar, digitar, rolar,
 │                      voltar, home, ler a tela
 ├─ GliaNotificationListener  notificações ativas
 ├─ ToolCatalog        22 ferramentas com schema JSON + sensibilidade
 └─ ToolExecutor       valida schema e executa

ui/                  design system + telas
 ├─ theme/             paleta solarpunk (claro/escuro), tipografia
 ├─ components/        GlassSurface, OrganicBackdrop, OrganicOrb, GliaIcons
 └─ screens/           Chat, Voz, Ajustes, Onboarding
```

### Fluxo de uma frase

1. **Roteador local** (`AssistantRouter`): hora, data, agenda, bateria, RAM,
   armazenamento, rede, volume, brilho, notificações, apps e comandos óbvios
   ("abre o WhatsApp", "lanterna", "alarme 7:30", "liga para Ana"). Resposta
   instantânea, sem inferência.
2. **Modelo local** (`ConversationEngine`): prompt ChatML com persona, resumo do
   aparelho e catálogo de ferramentas. O modelo pode responder texto ou emitir
   `{"tool":"…","args":{…}}`.
3. **Validação**: `ToolCallParser` extrai o JSON; `ToolSchemaValidator` confere
   tipos, `required`, `enum`, mínimos/máximos e chaves desconhecidas.
4. **Confirmação**: ferramentas `SENSITIVE` (ligar, enviar mensagem, criar evento,
   tocar/digitar na tela) pedem confirmação falada. A Glia pergunta em voz alta,
   abre o microfone e aceita "sim"/"não"; também há botões na tela. Timeout de 20 s
   significa "não".
5. **Execução** sempre pelo `ToolExecutor`; o modelo nunca toca no sistema.
6. **Resposta** é falada (TTS pt-BR) e exibida; o orbe muda de estado
   (ouvindo → pensando → falando).

### Memória e desempenho

- Pesos **mmapeados** com `MAP_PRIVATE`; nunca são copiados para o heap.
- KV cache fp32 dimensionado pelo contexto em uso (512–1024 tokens).
- `onTrimMemory` reduz o contexto e, em pressão crítica, desanexa o modelo;
  o próximo uso recarrega por page faults.
- Desativação automática da animação/update de UI em segundo plano.
- Design: sem blur de tela cheia (o G57 sofre). O "vidro líquido" é feito de
  gradientes + borda especular + brilho radial, com opção de desligar em Ajustes.
- Inferência em 4 threads (`Thread` por faixa de linhas na matmul), kernels NEON.

---

## 2. Modelos offline (assets do release, não versionados)

Os pesos têm licença própria e ficam anexados ao release do GitHub — nunca no
histórico do repositório. Baixe pelo link da seção **Download**.

### LLM — Ternary Bonsai 2 2B (GGUF)

O APK de release **já traz** `assets/model/glia.gguf` (Q2_0, 547 MB). Não é
preciso instalar nada à mão.

Para usar outro modelo, sem regerar o APK:

```bash
adb push outro-modelo.gguf \
  /sdcard/Android/data/com.glia.assistant/files/models/
```

O app também aceita `filesDir/models` (via `run-as`/root). Um GGUF na pasta de
modelos tem prioridade sobre o embutido; `ModelStore.activeModel` escolhe o
maior arquivo quando não há um marcado como ativo.

Quem carrega o arquivo é o **llama.cpp** (upstream), então valem os formatos e
arquiteturas que ele suporta: `qwen35` (Bonsai), LLaMA/Mistral/Qwen, `Q2_0`,
`TQ2_0`, `Q4_0`, `Q5_K`, `Q8_0`, `F16`, `F32`. Se o arquivo não carregar, o erro
do llama.cpp aparece em Ajustes → Modelo (`lastEngineError`).

### Voz — Vosk small pt-BR (~50 MB, Apache 2.0)

```bash
adb push vosk-model-small-pt-0.3 /sdcard/Android/data/com.glia.assistant/files/  # renomeie para vosk-model
```

O `VoskModelLocator` procura `filesDir/vosk-model` e também extrai
`assets/vosk-model.zip`, se existir. A mesma instância Vosk faz a palavra-chave
(gramática restrita a `["glia","[unk]"]`, custo baixo) e o reconhecimento do
comando (vocabulário aberto, VAD por energia).

---

## 3. Permissões

| Permissão | Para quê | Obrigatória |
|---|---|---|
| `RECORD_AUDIO` | wake word e comandos | sim, para voz |
| `POST_NOTIFICATIONS` | notificação discreta da escuta | Android 13+ |
| `READ_CALENDAR` / `WRITE_CALENDAR` | consultar/criar compromissos | opcional |
| `READ_CONTACTS` | ligar/mensagem por nome | opcional |
| `CALL_PHONE` | efetivar a ligação | opcional |
| `ACCESS_NOTIFICATION_POLICY` | Não Perturbe | opcional |
| `WRITE_SETTINGS` (tela especial) | brilho | opcional |
| `QUERY_ALL_PACKAGES` | listar/abrir apps | opcional |
| `FOREGROUND_SERVICE` + `_MICROPHONE` | escuta contínua | sim, para wake word |
| `RECEIVE_BOOT_COMPLETED` | retomar escuta após reboot | opcional |
| Serviço de acessibilidade | tocar, digitar, rolar, ler a tela | opcional |
| Acesso a notificações | ler notificações ativas | opcional |

O onboarding explica cada uma e permite pular. Nada de internet é declarado.

---

## 4. Build

Requisitos: JDK 17, Android SDK 35, build-tools 35, NDK 27.0.12077973,
CMake 3.22.1+ (o build do llama.cpp usa o 3.31.6 do SDK).

```bash
# 1. llama.cpp para arm64 (clona third_party/, gera ai/src/main/jniLibs)
./scripts/build-llama-android.sh

# 2. modelo embutido (não versionado; baixe do release ou do Hugging Face)
curl -L -o app/src/main/assets/model/glia.gguf \
  https://huggingface.co/prism-ml/Ternary-Bonsai-2-2B-gguf/resolve/main/Ternary-Bonsai-2-2B-Q2_0.gguf

# 3. APK
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/
./gradlew :app:assembleRelease      # ~577 MB com o modelo
./gradlew :app:installRelease       # com o Redmi conectado por USB
```

ABIs: apenas `arm64-v8a` (o Redmi). O llama.cpp é compilado à parte porque o
módulo `ai` linka contra as `.so` prontas (`ai/src/main/jniLibs/arm64-v8a/`);
`ai/src/main/cpp/CMakeLists.txt` compila só a ponte JNI.

Sem o GGUF em `app/src/main/assets/model/`, o APK compila no modo essencial; sem
as `.so`, o link falha — rode o script antes.

Instalação manual:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Assinatura de release: crie seu keystore e configure `signingConfigs` (não
incluído por segurança).

---

## 5. HyperOS / MIUI

Sem root, três ajustes são necessários para a escuta contínua sobreviver:

1. **Bateria** → sem restrições (`Ajustes → Apps → Glia → Economia de bateria`).
2. **Autostart** → permitir (o app oferece um atalho no onboarding).
3. **Serviço em primeiro plano** → notificação de baixa prioridade, sem som.

No Android 14+, o sistema bloqueia iniciar microfone em segundo plano a partir do
boot e do tile; por isso o tile abre a Activity (que já está em primeiro plano) e
o `BootReceiver` tenta reativar apenas se o usuário habilitou a escuta.

---

## 6. O que não é possível sem root / privilégios

| Pedido | Limite | Alternativa implementada |
|---|---|---|
| Ligar/desligar Wi‑Fi por código | `setWifiEnabled` bloqueado desde Android 10 | abre `Settings.Panel.ACTION_WIFI`, usuário confirma com um toque |
| Ligar/desligar Bluetooth por código | `BluetoothAdapter.enable` restrito a apps de sistema | abre as configurações de Bluetooth |
| Enviar SMS silenciosamente | exige `SEND_SMS` (política restritiva) | `ACTION_SENDTO` com texto pronto + confirmação; um toque envia |
| Ler notificações de outros apps | exige listener autorizado | `NotificationListenerService` + tela de autorização |
| Tocar em outros apps | exige acessibilidade | `AccessibilityService` (usuário ativa) |
| Brilho | exige `WRITE_SETTINGS` | pedido de permissão no fluxo |
| Acelerar por GPU (Mali-G57) | Vulkan/OpenCL exigiria backend dedicado | NEON multi-thread; Vulkan é o próximo passo (fora do escopo entregue) |
| "Escutar sempre" com bateria intacta | microfone contínuo custa energia | gramática restrita + microfone desligado fora da escuta |

Também: o modelo de 2B em ternário ocupa ~0,5 GB e, num Helio G96, gera na ordem
de **1–4 tokens/s** com contexto curto. A UI faz streaming desde o primeiro token
e o roteador local responde instantaneamente o que é determinístico, para que a
experiência não dependa só da velocidade do modelo.

---

## 7. Roteiro de testes para o Redmi Note 11S

**Instalação e abertura**
1. `adb install -r app-debug.apk`; abrir sem conceder nada — onboarding aparece.
2. Conceder microfone; verificar notificação "Glia está por perto".
3. Ajustes → Modelo deve mostrar "ausente" antes do push do GGUF.

**Modelo local**
4. `adb push` do GGUF; Ajustes → Recarregar; status vira "pronto" e o contexto
   aparece no diagnóstico.
5. Conversa livre ("explique o que é fotossíntese") — tokens chegam em streaming;
   medir tokens/s com `adb logcat | grep GliaEngine`.
6. Matar o app e reabrir: carregamento deve ocorrer por page faults em poucos
   segundos (checar `RSS` no diagnóstico).
7. Rodar 10 minutos e conferir se o processo permanece vivo (HyperOS).

**Voz**
8. Tocar no microfone e dizer "que horas são" — resposta falada em pt-BR.
9. Habilitar escuta contínua, bloquear a tela, dizer "Glia" — deve acordar e
   ouvir o comando; microfone desliga durante a fala.
10. Dizer "Glia" duas vezes em sequência e verificar que não há auto-escuta.
11. Testar com fones e sem fones; verificar que o TTS não é captado como comando.

**Consultas**
12. "Que dia cai daqui a 10 dias?", "que dia da semana será 25 de dezembro?",
    "que dia é hoje?" — respostas corretas e offline.
13. "Bateria", "armazenamento", "RAM", "quantos apps" comparados com Ajustes do
    sistema.
14. Agenda: hoje/amanhã/semana/próximo; criar evento e conferir no app Calendário.

**Ações**
15. "Abre o WhatsApp", "volume em 30", "brilho em 80", "liga a lanterna",
    "não perturbe", "alarme 7:30", "timer de 5 minutos".
16. "Liga para [contato]" e "manda mensagem para [contato] dizendo oi" — deve
    pedir confirmação por voz antes; "não" cancela; "sim" executa.
17. Sem permissão de telefone, a Glia explica e não quebra.

**Acessibilidade**
18. Ativar o serviço; abrir outro app e pedir "o que está na tela?" — texto
    retornado.
19. "Rola para baixo", "volta", "vai para a tela inicial" (confirmação nos
    comandos sensíveis).
20. Desativar o serviço: as ações de tela respondem com instrução clara.

**Robustez**
21. Negar todas as permissões; app deve continuar utilizável (conversa + modelo).
22. `adb shell am kill com.glia.assistant` com escuta ativa: serviço deve voltar
    ao abrir o app.
23. Tirar o GGUF do lugar e tocar em Recarregar: cai no modo essencial com
    mensagem, sem crash.
24. Girar a tela e alternar tema claro/escuro durante a fala.
25. Bateria: medir consumo com escuta ligada por 1 h em tela apagada.

---

## 8. Design

Solarpunk + linguagem iOS 26, sem copiar assets licenciados:

- **Paleta**: folha, musgo, água, sol, creme, céu; escuro em verdes profundos
  (nunca preto). Tokens em `ui/theme/Color.kt`.
- **Vidro líquido**: superfícies translúcidas com brilho radial, borda especular
  e profundidade em camadas (`GlassSurface`), sem `RenderEffect` de tela cheia.
- **Orbe**: semente/flor de luz com ramificações que crescem como raízes/neurônios
  (`OrganicOrb`), pulso de respiração, anel de escuta e arco de pensamento.
- **Movimento**: molas no crossfade de abas, transições contínuas; respeita
  "Animações" desligadas e o modo de economia.
- **Háptica**: feedback sutil no microfone, envio e ações principais.
- **Tipografia**: sans do sistema (SF não é redistribuível), tracking negativo nos
  títulos e entrelinha generosa no corpo.
