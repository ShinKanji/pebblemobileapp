# NPU 加速与自定义模型导入

Android 端本地语音识别有两个可插拔引擎，由所选模型决定：

1. **Cactus**（默认）：`libcactus_engine.so`（JNI），模型为 Cactus bundle 格式。
2. **LiteRT-LM**：Google 官方端侧推理运行时，模型为 `.litertlm` 单文件 bundle（如 Qwen3-ASR-0.6B），
   支持 **NPU / GPU / CPU** 三级后端。

## 导入自定义模型

设置 → 语音识别 → Manage Offline Models → **Import model from file**：

| 文件类型 | 引擎 | 处理方式 |
|---|---|---|
| `.zip`（Cactus bundle） | Cactus | 解包到 `filesDir/models/<名称>/`，校验 `config.txt`，写 `.imported` 标记 |
| `.litertlm`（LiteRT-LM bundle） | LiteRT-LM | 整文件拷贝到 `filesDir/litert/<名称>.litertlm` |

- 两种导入都会出现在列表中，选择哪个列表里的模型即切换到对应引擎（互斥）。
- **Clean up temporary files** 清理：失败的导入残留（`.import_tmp_*`）、旧下载缓存 zip、转录临时 WAV、残缺模型目录。

## LiteRT-LM 引擎（Qwen3-ASR / NPU 优先路径）

运行时为 `com.google.ai.edge.litertlm:litertlm-android`（Google Maven），集成方式对齐官方示例
[litert-community/Qwen3-ASR](https://huggingface.co/litert-community/Qwen3-ASR-0.6B)：

- 推荐模型：`qwen3_asr_0.6b_5s_i8.litertlm`（960 MB，30 语言 + 22 种中文方言，含粤语；自动语种识别、
  标点、大小写；5 秒滑窗 + 重叠对齐支持任意长度音频）。从上方 HuggingFace 仓库下载后经导入功能装入。
- 后端选择链（加载时自动回退）：**NPU → GPU → CPU**。
  - **NPU**：通过 LiteRT 的 Qualcomm AI Engine Direct（QNN）委托（`Backend.NPU(nativeLibraryDir)`）。
    需要设备为高通 SoC（Android 12+，`SOC_MANUFACTURER=Qualcomm` 或 `SOC_MODEL` 以 `SM` 开头），
    **且用户在 `filesDir/qnn/` 放入 QNN 运行库**（`libQnnHtp.so`、`libQnnHtpV*.stub.so` 等，来自
    Qualcomm AI Engine Direct SDK / qairt，遵循其分发条款）。缺库或加载失败自动回退。
  - **GPU / CPU**：无需额外文件，开箱可用。
- NPU 变体的 AOT 编译：官方为 SM8450/8550/8650/8750/8850、SA8255/8295 与多款联发科 SoC 预编译了
  独立 `.tflite`（f32）。其他 SoC（如 SM8975）可用 LiteRT 的 `convert_to_tflite.py` +
  `compile_for_npu.py` 工具链（见 LiteRT 仓库 `litert/samples/asr/`）自行编译。

## Cactus 引擎的 NPU 通道

Cactus 引擎内置模型包驱动的 NPU 通道：当模型包根目录存在 `npu_audio_encoder` 文件时，引擎自动检测
（`has_npu_audio_encoder`）并优先在 NPU 上执行音频编码，其余部分（解码器/tokenizer）仍跑在 CPU 上；
加载或运行失败会**自动回退 CPU**（日志可见 "falling back to CPU"）。

- `npu_audio_encoder` 是面向 Qualcomm Hexagon NPU 的 QNN context binary，**必须用 Qualcomm QNN/SNPE
  SDK 离线编译**，且编译目标要匹配设备的 HTP 架构版本。
- 列表中含 NPU 编码器的模型会显示 **NPU accelerated** 徽章。
- 验证方法：Debug 构建下查看 logcat，引擎日志会输出 NPU 编码器加载成功或回退 CPU 的信息。

