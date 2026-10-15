# 海音 (HaiYin) 🎵

一款轻量、极简、无广告的 Android 音乐播放器。

全量基于 **Jetpack Compose (Material3)** 构建，沉浸式播放体验，支持 DLNA 投屏。

---

## ✨ 核心特性

- **沉浸流体光晕**：全屏播放页根据封面主色动态生成自适应弥散光晕，大圆角悬浮卡片。
- **灵动歌词**：上下边缘渐隐遮罩，当前唱句高亮显示，轻触任意一句直接跳转进度。
- **悬浮胶囊 Mini 播放条**：顶边内嵌流光进度条，实时 3 柱频谱小动画。
- **DLNA / UPnP 投屏**：原生 Kotlin 实现局域网设备发现与 AVTransport 控制，内置本地代理提升电视 / 音响兼容性，支持断点续播。
- **搜索与歌单导入**：在线搜索歌曲，公开歌单链接一键导入。
- **可扩展**：支持导入用户自备的扩展脚本，按需增强播放能力。
- **前台保活与锁屏控制**：前台服务 + MediaSession 系统媒体通知。
- **睡眠定时器**：15 / 30 / 60 分钟定时停止播放。

---

## 🛠️ 技术栈

- **平台与语言**：Android SDK 34 / Kotlin 1.9.24 / JVM 17
- **UI 框架**：Jetpack Compose (Material3, Compose BOM 2024.06.00)
- **网络与并发**：OkHttp 4.12.0 / Kotlin Coroutines & Flow
- **系统媒体服务**：AndroidX Media (`androidx.media:media:1.7.0`)

---

## 🚀 编译与构建

使用 **Android Studio** 打开项目根目录，等待 Gradle 同步完成，点击 **Run** 即可安装到手机。

---

## 📄 License

[MIT License](LICENSE)
