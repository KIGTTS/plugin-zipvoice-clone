# Third-party notices

The new adapter and shared voice/model management code are MIT licensed.

KIGTTS plugin SDK is MIT licensed, pinned independently. Its classes and Kotlin/coroutines/serialization runtimes are compile-time dependencies provided by NEXT and are excluded from the plugin DEX payload.

The current CPU package includes the pinned ZipVoice Distill INT8 and Vocos 24 kHz assets, sourced from the sherpa-onnx ZipVoice Distill distribution. Model cards and a hash manifest are shipped alongside the files in `assets/default-model/`. Reference recordings, user configuration, host source and publisher private keys are not included. The development publisher key is reused from this project's ignored `.signing` directory for in-place updates; it is distinct from the standard KIGTTS plugin publisher.

Model rights are separate from code rights. ZipVoice's official pretrained model is CC-BY-NC because of Emilia training-data restrictions; this plugin does not grant commercial model rights. The separate Vocos model card states MIT. Serveurperso's Qwen GGUF model card states Apache-2.0 and attributes Qwen. Local model preparation records source revisions and SHA-256 values and keeps upstream model cards beside the weights.

Public example audio is used only for local smoke testing and is not included in the plugin packages. Runtime output is not a guarantee of subjective voice similarity or low latency on every phone.
