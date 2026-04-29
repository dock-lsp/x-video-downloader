# X Video Downloader

一个功能强大的 Android Twitter/X 视频下载器应用，支持多画质下载和内置视频播放。

## 功能特点

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
- ✅ 底部导航栏：首页 | 下载 | 本地 | 设置

## 技术栈

- Android Kotlin
- ViewBinding
- ExoPlayer (Media3)
- Coroutines + Flow
- Room Database
- Material Design 3
- OkHttp + Retrofit
- AndroidX

## 项目结构

```
app/src/main/java/com/xvideo/downloader/
├── App.kt                          # Application 类
├── data/
│   ├── local/
│   │   ├── database/               # Room 数据库
│   │   └── DownloadManager.kt      # 下载管理
│   ├── remote/
│   │   ├── api/                   # API 服务
│   │   └── repository/             # 数据仓库
│   └── model/                     # 数据模型
├── ui/
│   ├── MainActivity.kt            # 主界面
│   ├── home/                     # 首页（下载）
│   ├── player/                   # 播放器
│   ├── downloads/                # 下载管理
│   ├── local/                    # 本地视频
│   └── settings/                 # 设置
├── service/
│   └── DownloadService.kt        # 下载服务
└── util/                         # 工具类
```

## 构建

### 本地构建

1. 克隆项目
```bash
git clone https://github.com/qq00150610-cpu/x-video-downloader.git
cd x-video-downloader
```

2. 使用 Gradle 构建
```bash
./gradlew assembleDebug
```

3. APK 输出位置：`app/build/outputs/apk/debug/app-debug.apk`

### GitHub Actions 构建

项目配置了自动构建，推送到 main 分支将自动触发构建。

需要配置的 Secrets：
- `KEYSTORE_BASE64`: 签名文件 Base64 编码
- `KEYSTORE_PASSWORD`: 签名密码
- `KEY_ALIAS`: 签名别名
- `KEY_PASSWORD`: 签名密钥密码

## 权限说明

- `INTERNET`: 网络访问
- `READ_EXTERNAL_STORAGE`: 读取存储（Android 10 以下）
- `WRITE_EXTERNAL_STORAGE`: 写入存储（Android 10 以下）
- `READ_MEDIA_VIDEO`: 读取视频文件（Android 13+）
- `FOREGROUND_SERVICE`: 前台服务
- `POST_NOTIFICATIONS`: 通知权限（Android 13+）

## 下载目录

视频默认保存在：`Android/data/com.xvideo.downloader/files/Movies/XVideoDownloader/`

## License

MIT License
