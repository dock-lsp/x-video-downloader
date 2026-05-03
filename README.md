# X Video Downloader

一个功能强大的 Android Twitter/X 视频下载器应用，支持多画质下载、内置视频播放和 AI 编程助手。

## 功能特点

### 🤖 AI 编程助手 (v1.1.0 新增)
- ✅ AI 对话式代码生成
- ✅ 自动生成文件并按 AI 指定的目录结构创建
- ✅ 对话历史记录（支持多轮上下文）
- ✅ 项目文件浏览器
- ✅ 代码自动保存
- ✅ 支持 OpenAI / DeepSeek / Moonshot / Qwen 等兼容 API
- ✅ 可配置 API 地址、Key 和模型

### 视频下载
- ✅ 粘贴 Twitter/X 推文 URL 自动解析
- ✅ 支持多画质选择 (SD/HD/2K/4K)
- ✅ 下载进度显示（支持暂停/继续/取消）
- ✅ 下载历史记录
- ✅ 支持分享菜单直接粘贴 URL
- ✅ 支持 GIF 下载

### 视频播放器
- ✅ 使用 ExoPlayer 实现流畅播放
- ✅ 支持在线播放和本地播放
- ✅ 手势控制：快进/快退（左右滑动）
- ✅ 手势控制：音量/亮度调节（上下滑动）
- ✅ 锁屏观看模式
- ✅ 倍速播放 (0.5x - 2x)
- ✅ 全屏/小窗切换
- ✅ 进度条拖拽

### 本地视频管理
- ✅ 扫描本地视频文件
- ✅ 视频缩略图显示
- ✅ 多种排序方式（时间/大小/名称）

### UI 设计
- ✅ Material Design 3 风格
- ✅ 深色/浅色主题切换
- ✅ 底部导航栏：首页 | AI | 在线 | 下载 | 本地 | 设置

## 技术栈

- Android Kotlin
- ViewBinding
- ExoPlayer (Media3)
- Coroutines + Flow
- Room Database
- Material Design 3
- OkHttp + Gson
- AndroidX
- OpenAI-compatible API (AI 功能)

## 项目结构

```
app/src/main/java/com/xvideo/downloader/
├── App.kt                          # Application 类
├── data/
│   ├── local/
│   │   ├── database/               # Room 数据库
│   │   │   ├── AppDatabase.kt
│   │   │   ├── dao/               # 数据访问对象
│   │   │   └── entity/            # 数据实体
│   │   └── DownloadManager.kt      # 下载管理
│   ├── remote/
│   │   ├── api/
│   │   │   ├── AiApiService.kt    # AI API 服务
│   │   │   └── TwitterApiService.kt
│   │   └── repository/
│   │       ├── CodeGenerationRepository.kt  # 代码生成
│   │       └── VideoRepository.kt
│   └── model/                     # 数据模型
├── ui/
│   ├── MainActivity.kt            # 主界面
│   ├── ai/                        # AI 助手
│   │   ├── AiFragment.kt
│   │   ├── AiViewModel.kt
│   │   ├── AiMessageAdapter.kt
│   │   ├── ProjectAdapter.kt
│   │   └── ConversationHistoryAdapter.kt
│   ├── home/                      # 首页（下载）
│   ├── player/                    # 播放器
│   ├── downloads/                 # 下载管理
│   ├── local/                     # 本地视频
│   ├── online/                    # 在线播放
│   └── settings/                  # 设置
├── service/
│   └── DownloadService.kt        # 下载服务
└── util/                          # 工具类
```

## 构建

### 本地构建

1. 克隆项目
```bash
git clone https://github.com/dock-lsp/x-video-downloader.git
cd x-video-downloader
```

2. 使用 Gradle 构建
```bash
./gradlew assembleDebug
```

3. APK 输出位置：`app/build/outputs/apk/debug/app-debug.apk`

### GitHub Actions 构建

项目配置了自动构建，推送到 main 分支将自动触发构建并创建 Release。

APK 下载：前往 [Releases](https://github.com/dock-lsp/x-video-downloader/releases) 页面下载最新版本。

## AI 功能使用说明

1. 打开应用，点击底部导航栏的 **AI** 标签
2. 点击右上角齿轮图标配置 AI API：
   - **API Base URL**: 如 `https://api.openai.com/v1` 或 `https://api.deepseek.com/v1`
   - **API Key**: 你的 API 密钥
   - **模型**: 如 `gpt-4o-mini`、`deepseek-chat` 等
3. 在输入框描述你需要的代码功能，AI 会自动生成代码
4. 生成的代码会按照 AI 指定的目录结构自动创建文件
5. 对话历史会自动保存，方便后续查看上下文
6. 点击「项目」标签可以浏览所有已生成的项目文件

### 代码文件创建规则

AI 生成代码时，需要在代码块中包含文件路径注释：
```kotlin
// 文件路径: app/src/main/java/com/example/MyClass.kt
package com.example

class MyClass {
    // 代码内容...
}
```

支持的注释格式：
- `// 文件路径: path/to/file.ext`
- `// filepath: path/to/file.ext`
- `# 文件路径: path/to/file.ext`

## 权限说明

- `INTERNET`: 网络访问
- `READ_EXTERNAL_STORAGE`: 读取存储（Android 10 以下）
- `WRITE_EXTERNAL_STORAGE`: 写入存储（Android 10 以下）
- `READ_MEDIA_VIDEO`: 读取视频文件（Android 13+）
- `FOREGROUND_SERVICE`: 前台服务
- `POST_NOTIFICATIONS`: 通知权限（Android 13+）

## 下载目录

视频默认保存在：`Android/data/com.xvideo.downloader/files/Movies/XVideoDownloader/`

AI 生成的项目保存在：`/data/data/com.xvideo.downloader/files/ai_projects/`

## License

MIT License
