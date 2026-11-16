# 语音克隆引擎

当前交付：**语音克隆引擎 0.3.6**。这是供需要语音克隆功能的插件声明的前置包；ZipVoice INT8 与 Vocos 已随包安装，不需要另装模型或「语音克隆」入口。包 ID 保留为 `engine.zipvoice-clone`（同签名就地升级，保留已有语音包、选中项和配置）。

- 仅交付 ZipVoice INT8 CPU：编码、解码、声码器都走 CPU。没有计算模式开关，旧 GPU/NPU 配置不会生效，安装包也没有 QNN 库及 HTP 固件。
- 使用现有 NEXT SDK API35、Android 8/API26 起、arm64-v8a。不修改 NEXT 主程序。
- 仍可由现有 NEXT 朗读服务选择声音或请求预热/常驻。默认不用时约 30 秒释放模型；首段沿用短切，后续在长度上限内合并逗号小段；不改变缓存或常驻规则。
- 新合成的语音（实时和重新缓存）自动裁去首尾明显的空白，并保留一点边缘余量以保护轻音和尾音。中间的停顿按原 PCM 输出；开始播放不必等整条语音生成完。不能可靠判断边缘时保留原音频。

## 设置与语音包

设置页是一张宿主原生设置卡（CPU 线程、实时合成质量、缓存合成质量），下方是带头像或默认图标的语音包列表。列表最后一行「添加语音包」用于选择 WAV 制作；右上角仅保留「导入语音包」，用于导入 ZIP。点语音包行打开二级页面，可编辑名称、备注、置顶、头像、语言、参考文本和参考录音，也可以设为当前声音、分享或删除。参考音频需为 PCM16 单声道 WAV，1–30 秒，文字必须与录音匹配。

制作、编辑和保存设置不需要等待正在生成的语音完成。已有请求沿用启动时的参考声音和参数；后续合成读新设置。修改 CPU 线程后，在下一次需要模型时更新本地线程数。

Android 10 或更新使用系统分享面板，并在「下载/KIGTTS」留下供分享的 ZIP；如果系统不支持直接分享，则使用 NEXT 现有保存文件界面。不会自动向任何人发送。仅分享有权使用的录音、文字和头像。

ZIP 根目录：
- `voice.json`：格式版本 1、语音包名称、参考文字 (`referenceText`) 与可选语言/备注；
- `reference.wav`：参考录音；
- `avatar.png`：可选。选图支持 JPEG、PNG、WebP，存为最长边不超过 256 px 的 PNG。

导出不含模型、诊断、内部语音 ID 或缓存。将 ZIP 导入另一设备仍可使用其中声音与头像。旧无头像语音包可继续导入。新导入分配新 ID；经宿主更新已有包保留原 ID、头像、名称及排序设置。

## 构建

PowerShell 7、JDK17、Android SDK36、NDK 26.1.10909125、CMake 3.22.1、Gradle 8.13。先确保固定 SDK 和上游子模块可用；准备本地固定 sherpa/ORT 构建依赖，详见 `SOURCE_LOCK.json` 和 `NOTICE.md`。

```powershell
.\gradlew.bat -g .gradle-home :clone-core:test :zipvoice:assembleDebug
cmake --build qualcomm/build/sherpa-native --target sherpa-onnx-jni
python tools/prepare_models.py zipvoice  # 构建机首次准备固定模型；手机用户无需执行
python tools/package_plugins.py --module zipvoice
```

输出 `dist/engine.zipvoice-clone-0.3.6.kigplugin` 和签名检查 `dist/package-verification.json`。需要支持 API35 的 KIGTTS 主程序。CPU 线程和两项合成质量都是 1–8 的整数滑条，每档对应相同数量的推理步数。缓存合成质量用于后台预合成；普通实时朗读用实时合成质量。默认实时 2 档／缓存 4 档，升级保留已有设置；只改一项质量不会改另一项。没有模型导入入口，已导入的不同模型仍保留并继续使用；如果旧模型与内置版本相同，引擎直接读取包内资产，不会删除旧文件。

自有代码 MIT；sherpa-onnx 和 ORT 的许可证随包提供。ZipVoice 预训练模型适用 CC-BY-NC，插件许可证不授予模型商业使用权。此仓库仅发布已打包的 CPU 实现。
