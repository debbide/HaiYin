# 海音 (HaiYin) 🎵

一款轻量、极简、无广告的现代沉浸式 Android 在线音乐播放器。

全量基于 **Jetpack Compose (Material3)** 构建，内置落雪音乐（LX Music）协议自定义音源脚本运行时与自主实现的轻量级 DLNA 投屏代理引擎。

---

## ✨ 核心特性

- **现代沉浸流体光晕**：全屏播放页采用封面主导色动态提取与自适应弥散流体光晕（Apple Music / Spotify 风格），大圆角悬浮浮雕卡片。
- **灵动歌词系统**：上下边缘平滑遮罩渐隐（Fading Edges），当前唱句高亮发光，**支持轻触任意歌词直接 Seek 跳转进度**。
- **悬浮胶囊 Mini 播放条**：顶边内嵌 2dp 流光进度条，内置实时跳动的 3 柱微型频谱均衡器（Playing Equalizer）动画。
- **落雪音乐 (LX) 协议音源扩展**：内置 QuickJS 引擎，可直接导入/粘贴 LX 协议音源脚本（`.js`），无缝扩展全网音源解析能力。
- **DLNA / UPnP 原生投屏**：
  - 纯 Kotlin 原生实现 SSDP 局域网搜索与 AVTransport 控制；
  - 内置本地微型 HTTP 流媒体代理服务（`StreamProxy`），解决电视/音响设备对防盗链与 Range 断点续传兼容性差的问题；
  - 支持智能播放状态防抖与断点自动续播。
- **多源聚合与歌单导入**：内置公开源聚合搜索，支持网易云歌单链接一键导入解析。
- **前台保活与锁屏控制**：集成 Android 前台服务与 `MediaSessionCompat` 系统媒体通知栏控制器。
- **睡眠定时器**：支持 15 / 30 / 60 分钟定时自动停止播放。

---

## 🛠️ 技术栈

- **平台与语言**：Android SDK 34 / Kotlin 1.9.24 / JVM 17
- **UI 框架**：Jetpack Compose (Material3, Compose BOM 2024.06.00)
- **JS 脚本引擎**：QuickJS Android (`app.cash.quickjs:quickjs-android`)
- **网络与并发**：OkHttp 4.12.0 / Kotlin Coroutines & Flow
- **系统媒体服务**：AndroidX Media (`androidx.media:media:1.7.0`)

---

## 🚀 编译与构建

直接使用 **Android Studio** 打开本项目根目录，等待 Gradle 依赖同步完成，点击 **Run** 即可编译并安装到手机。

---

## 📄 License

[MIT License](LICENSE)
