# AI Recorder

一款面向 Android 的智能录音应用，集成本地与云端语音转录、AI 对话及本地文件记忆功能。支持触摸与鼠标操作。

## 功能概览

### 录音主界面（首页）
应用启动后直接进入录音主界面，提供：
- **高质量录音**：基于 `AudioRecord` 获取原始 PCM 流（48 kHz，16-bit 单声道），经 `MediaCodec + MediaMuxer` 实时编码为 M4A（**320 kbps AAC-LC，48 kHz**），支持暂停/继续与实时时长显示
- **即时回放**：录音过程中随时点击"15s回放"按钮，将最近 15 秒音频单独截取并编码为独立片段（**192 kbps AAC-LC，48 kHz**），自动进入录音文件列表；录音界面最多同时显示最新 3 条回放的播放控制（播放/暂停、停止）
- **实时转录**：点击"实时转录"按钮，加载 Vosk 模型后将 PCM 流 3:1 抽取降采样至 16 kHz 后送入识别器（不影响录音与回放质量），转录结果以滚动字幕形式实时展示；激活后界面布局自动切换为字幕模式，支持中文/英文切换；录音结束、新录音开始或手动关闭时退出字幕模式

### 录音管理
- 录音文件（含即时回放片段）统一展示于录音列表，支持重命名、删除、分享、导入外部音频

### 语音转录（离线/事后）
- **本地转录（Vosk）**：离线运行，支持中文与英文，无需网络
  - 转录管线：M4A → MediaExtractor 解压 → MediaCodec 解码为 PCM → TarsosDSP 重采样至 16 kHz → Vosk 转录
  - 输出带时间戳的字幕文件
- **云端转录（讯飞标准版）**：音频直接上传讯飞 API，无本地解码开销，需用户提供 APPID 与 Secret Key

### 字幕查看器
转录完成后进入专用查看界面：
- 上方音频播放器（约占 1/5 屏幕）：播放/暂停、±0.2 秒步进、进度条拖拽、实时时长显示
- 下方字幕区：多行展示，当前句高亮，支持滑动浏览与快速拖拽跳转
- 自动滚动模式：当前句始终保持在第二行
- 支持删除转录结果并重新转录（二次确认）

### AI 对话
- **本地模型（Qwen 2.5 1.5B）**：完全离线，基于 LiteRT-LM 运行，无需任何 API Key
- **云端模型（Qwen3-Max）**：阿里云 DashScope API，支持深度思考（reasoning_content）与流式输出，需用户提供 API Key
  - 可选联网搜索（原生 enable_search，无需 Tavily）
  - 可选 **Tavily 联网检索**增强（需独立 Tavily API Key）
  - 可选 **本地文件记忆**：将录音库的摘要索引注入对话上下文

### 音频总结与本地文件记忆
- 使用 **Qwen Omni Plus** 对录音文件进行 AI 音频总结（限 30 分钟 / 20 MB 以内）
- 二次总结：对第一次总结结果再压缩至 125 字以内，仅用于索引，不在 UI 展示
- 本地内容同步索引（JSON）：记录每条录音的文件名、时长、日期及二次总结，用于 AI 对话时的上下文注入
- 索引在应用启动、每 10 分钟及手动点击"本地内容同步"时自动刷新

### 其他功能
- 音频导入：支持从外部导入音频文件至录音库
- 音频分享（导出）：将录音文件分享至其他应用

## 技术栈

| 层级 | 技术 |
|------|------|
| UI | Jetpack Compose + Material 3 |
| 架构 | MVVM（ViewModel + StateFlow） |
| 数据库 | Room |
| 录音引擎 | AudioRecord 48 kHz → MediaCodec + MediaMuxer（PCM 三路并行，主录 320 kbps / 回放 192 kbps） |
| 实时转录 | Vosk（48 kHz→16 kHz 3:1 抽取降采样后流式输入，中文 / 英文离线模型） |
| 事后转录 | Vosk（本地）/ 讯飞（云端） |
| 音频处理 | MediaExtractor / MediaCodec + TarsosDSP |
| 本地 LLM | LiteRT-LM（Qwen 2.5 1.5B Instruct，INT8 量化） |
| 云端 ASR | 讯飞语音转写标准版 API |
| 云端 LLM | 阿里云 DashScope — Qwen3-Max / Qwen Omni Plus |
| 联网搜索 | Tavily Search API（可选） |
| 网络 | OkHttp |

## 环境要求

- Android 14+（minSdk 35，targetSdk 36）
- 编译需 Android Studio Hedgehog 或更高版本

## 大文件说明

本地 LLM 模型文件（`app/src/main/assets/qwen/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm`，约 1.5 GB）通过 **Git LFS** 管理。克隆后需执行：

```bash
git lfs pull
```

## API Key 配置

以下服务需在应用设置界面手动填入对应密钥：

| 服务 | 所需凭据 |
|------|---------|
| 讯飞语音转写 | APPID + Secret Key |
| Qwen3-Max / Qwen Omni Plus | 阿里云 DashScope API Key |
| Tavily 联网搜索（可选） | Tavily API Key |

所有密钥均仅存储在本地设备，不上传至任何服务器。
