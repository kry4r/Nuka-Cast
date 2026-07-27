<p align="center">
  <img src="docs/assets/logo.png" width="148" alt="NukaCast logo" />
</p>

<h1 align="center">NukaCast</h1>

<p align="center">
  面向 Android 4.2.2 电视的 AirPlay 接收器、TVBox 聚合播放器与局域网控制台。
</p>

<p align="center">
  <a href="https://github.com/kry4r/Nuka-Cast/actions/workflows/android.yml"><img src="https://github.com/kry4r/Nuka-Cast/actions/workflows/android.yml/badge.svg" alt="Android CI" /></a>
  <img src="https://img.shields.io/badge/Android-4.2.2%2B-3DDC84?logo=android&logoColor=white" alt="Android 4.2.2+" />
  <img src="https://img.shields.io/badge/ABI-armeabi--v7a-555" alt="armeabi-v7a" />
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPLv3-2f855a" alt="GPLv3" /></a>
</p>

## 主要能力

- **AirPlay 镜像接收**：发布 `_airplay._tcp` / `_raop._tcp`，完成 Legacy AirPlay、FairPlay/RAOP、H.264 和 AAC 接收，并使用低缓冲 `MediaCodec` / `AudioTrack` 渲染。
- **TVBox 影视聚合**：兼容普通 JSON、图片体 Base64 JSON、CMS、CatVod JAR Spider 与 ES Module JS Spider。
- **电视端浏览**：首页、影视、投屏、设置四项导航；继续观看、收藏、首页推荐、全站搜索和类型/年份/地区过滤。
- **播放体验**：详情、多个线路、选集、解析接口、HLS/DASH/普通媒体播放、断点续播与遥控器媒体键。
- **网页控制**：同一局域网内使用六位码配对，管理片源、搜索选集、控制播放器并查看设备与 AirPlay 诊断。
- **电视遥控器**：方向键、确定键和媒体键使用原生焦点系统，不依赖手机遥控器。

电视端不展示直播入口；底层仍保留 TVBox 直播字段解析，以免读取含直播配置的接口时失败。

## 安装与使用

1. 从 [Releases](https://github.com/kry4r/Nuka-Cast/releases) 下载 APK，复制到 U 盘。
2. 在电视上允许“未知来源”，打开文件管理器安装 APK。
3. 确保电视、iPhone 或 Mac 连接到同一子网。
4. iPhone/Mac 打开屏幕镜像并选择 `NukaCast`。
5. 网页控制地址和六位配对码位于电视端“投屏”页，默认端口为 `9978`。

首次安装默认加入巧技 TVBox 配置。更多配置源请从网页控制端添加、删除或刷新。

## 电视界面

首屏针对 1080p 电视和红外遥控器设计：左侧是四项固定导航，顶部只保留全站搜索与网络状态，内容区按“快速浏览、继续观看、我的收藏、最近更新”排列。空的历史或收藏行会自动隐藏。

海报卡保持固定尺寸，焦点变化不会推挤周围内容；旧电视上不启用模糊背景、自动预告片和复杂过渡动画。

## 本地构建

需要 JDK 17、Android Platform/Build Tools 35、CMake 3.22.1、NDK `20.1.5948944` 和 Node.js 20。

```powershell
$env:JAVA_HOME=(Resolve-Path '.toolchains\jdk17').Path
$env:ANDROID_SDK_ROOT=(Resolve-Path '.toolchains\android-sdk').Path

Set-Location web
npm ci
npm run build
Set-Location ..

.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

输出文件：`app/build/outputs/apk/debug/app-debug.apk`。

## CI 与自动发版

`Android CI` 会在主分支和 Pull Request 上构建网页、运行关键单测与 Lint、编译原生 AirPlay 库，并检查 APK 的 `minSdk 17`、`armeabi-v7a` 和内嵌网页资源。

正式发版前，在仓库 `Settings > Secrets and variables > Actions` 配置：

| Secret | 内容 |
| --- | --- |
| `NUKACAST_KEYSTORE_BASE64` | Release keystore 的 Base64 内容 |
| `NUKACAST_KEYSTORE_PASSWORD` | keystore 密码 |
| `NUKACAST_KEY_ALIAS` | 签名别名 |
| `NUKACAST_KEY_PASSWORD` | 私钥密码 |

推送标签即可自动创建带 SHA-256 校验文件的 GitHub Release：

```bash
git tag v0.1.0
git push origin v0.1.0
```

## 兼容边界

- APK 最低版本为 Android 4.2.2 / API 17，当前原生库仅提供 `armeabi-v7a`。
- QuickJS 上游声明 `minSdk 18`；项目进行了 API 17 清单覆盖，仍需在目标电视验证原生库加载。
- AirPlay 协议核心基于 Legacy AirPlay。iOS 26/macOS 26 的兼容性必须使用真实设备验证。
- DRM 内容（例如 Apple TV+、Netflix 的受保护视频）不保证能够镜像或播放。
- `<15 ms` 是队列设计目标，不是未经实测的承诺；最终延迟取决于电视解码器、Wi-Fi 与发送设备。

## 开源与内容说明

NukaCast 使用 GPLv3 发布。第三方组件与引入源码的许可证见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

项目本身不提供、不托管影视内容。用户添加的 TVBox 配置、Spider、解析接口和媒体地址应由用户自行确认授权及合规性。
