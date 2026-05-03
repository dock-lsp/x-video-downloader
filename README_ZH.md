# X Video Downloader

Android Twitter/X 视频下载器 + AI 编程助手

## 构建状态

[![Build APK](https://github.com/dock-lsp/x-video-downloader/actions/workflows/build.yml/badge.svg)](https://github.com/dock-lsp/x-video-downloader/actions/workflows/build.yml)

## 功能

- 🤖 **AI 编程助手** - 对话式代码生成，自动创建目录结构
- 📥 Twitter/X 视频下载
- 🎬 多画质选择 (SD/HD/2K/4K)
- 🎥 内置 ExoPlayer 播放器
- 📂 本地视频管理
- 🎨 Material Design 3 UI
- 📜 AI 对话历史记录
- 📁 项目文件浏览器

## 技术栈

- Kotlin
- ExoPlayer (Media3)
- Room Database
- Coroutines + Flow
- Material Design 3
- OpenAI-compatible API

## 构建

```bash
./gradlew assembleDebug
```

APK 输出位置: `app/build/outputs/apk/debug/app-debug.apk`

## 下载

前往 [Releases](https://github.com/dock-lsp/x-video-downloader/releases) 页面下载最新 APK。

## AI 功能配置

1. 打开应用 → AI 标签 → 设置
2. 配置 API Base URL、API Key 和模型名称
3. 支持 OpenAI、DeepSeek、Moonshot、Qwen 等兼容 API

## 许可证

MIT License
