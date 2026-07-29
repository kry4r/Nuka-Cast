# v0.3.4 发布接续事项

## 今晚已完成

- AirPlay 重复 SPS/PPS 不再重置解码器及软件回退进度。
- 原生 AirPlay 连接仍活跃时，静止画面不会再被 2 秒媒体包超时主动断开。
- Android 4 使用应用内 Conscrypt，并在平台证书校验失败后补充 DigiCert Global Root G2。
- API 19 x86 模拟器已成功解析 `https://6800.kstore.vip/fish.json`：86 个站点、67 个可搜索站点。
- API 19 x86 模拟器已通过 TLS 1.2 下载 Spider JAR，大小 3928542 字节；日志未再出现 `Trust anchor`、`SSLHandshakeException` 或 TLS 协议版本错误。
- 中文配置名和站点名通过 HTTP API 返回正常，没有乱码。

## 明天优先验证

1. 在真实 ARM 电视安装候选包，重新添加 `fish.json`，确认 Spider 日志中 ARMv7 `FishGuard` SO 正常加载。
2. 验证主页能返回内容，并搜索“庆余年”；确认不再出现证书或 TLS 错误。
3. x86 模拟器搜索会因配置 JAR 只携带 ARMv7/ARM64 SO 而记录 `unexpected e_machine: 40`，随后部分 Cloud Spider 出现 `VerifyError`。这不能代表 ARM 电视结果；若 ARM 电视也出现 `VerifyError`，保存完整 Dalvik verifier 日志后再处理 JAR 的 Android 4 字节码兼容问题。
4. 用 iPhone 镜像到海思设备，确认首帧出现；保持桌面静止至少 30 秒，确认电视不会主动退出镜像。
5. 检查 AirPlay 日志：相同 SPS/PPS 不应每秒重复启动 `OMX.hisi.video.decoder.avc`；硬解无首帧时应能累计到软件回退阈值。

## 发布步骤

1. 运行全量验证：

   ```powershell
   Set-Location web
   npm test -- --run
   npm run build
   Set-Location ..
   .\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
   git diff --check
   ```

2. 验证通过后升级版本：Android `versionCode` 10、`versionName` 0.3.4，Web 版本 0.3.4，并把 README 标签示例更新为 `v0.3.4`。
3. 重建 Web assets，提交 `chore: prepare v0.3.4 release`。
4. 合并 `fix/v034-airplay-trust` 到 `main`，在合并结果上复跑验证并推送 `main`。
5. 等待 Android CI 的 `build` 和 `instrumentation` 成功后创建 annotated tag `v0.3.4`。
6. 等待 Release workflow 成功，确认 Release 非 draft/prerelease，包含 APK 和 SHA256 两个资产；下载 APK 后独立核对 SHA-256，并运行 `apksigner verify --verbose --print-certs`。

## 当前分支

- 分支：`fix/v034-airplay-trust`
- 最后功能提交：`0097ae9 fix: support modern TLS on Android 4`
- 版本仍为 `0.3.3-debug`，尚未创建 `v0.3.4` 标签。
