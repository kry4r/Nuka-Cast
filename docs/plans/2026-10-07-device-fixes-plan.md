# 真机问题修复：实施计划（2026-10-07）

规格见 [device-fixes-spec](2026-10-07-device-fixes-spec.md)。按顺序做，每步都有可验证的产出；
P0 未完成不进入 P1。

## 阶段 0：本地可复现（进行中）

| # | 任务 | 产出 | 验证 |
| --- | --- | --- | --- |
| T0.1 | API 19 x86 模拟器（与电视同为 Android 4.4.2） | 启动并可 `adb install` | `adb shell getprop ro.build.version.release` = 4.4.2 |
| T0.2 | x86 调试 APK | `:app:assembleDebug -PincludeLegacyTestAbi` | APK 内含 x86 原生库，装机启动成功 |
| T0.3 | 端口映射调试 | `adb forward tcp:9978 tcp:9978` | 本机 `/api/debug/snapshot` 返回模拟器状态 |
| T0.4 | 外部崩溃取证 | `tools/nukacast-watch.mjs` 轮询并落盘 JSONL | 杀进程后仍能看到最后一刻的 RSS/线程/阶段 |
| T0.5 | MCP 接入 | `tools/nukacast-mcp.mjs` + `NUKACAST_HOST` | `pi mcp list` 显示 connected，14 个工具 |

ARM 模拟器已被 QEMU2 移除（`CPU Architecture 'arm' is not supported`），因此本地调试用 x86 +
调试 APK；真机上仍是 armeabi-v7a，两者差异（HiSilicon 硬解、真实网络）只在真机验收时覆盖。

## 阶段 1：P0 崩溃与播放

| # | 任务 | 改动点 | 验证 |
| --- | --- | --- | --- |
| T1.1 | 播放数据源只用 core | 移除 `extension-okhttp`，`PlayerController` 用 `DefaultHttpDataSource`（已完成） | 插桩测试对本地 403 服务断言类型化异常 |
| T1.2 | 解析器失败降级 | `TvBoxContentService.resolveWithConfiguredParsers`：解析器异常/超时不再终止，保留原地址 | 单测 + 模拟器实测（E5 场景） |
| T1.3 | share 播放页解析 | `MaccmsShareResolver` + 点播/短剧两条路径（已完成） | `MaccmsShareResolverTest` + 模拟器实放短剧一集 |
| T1.4 | 播放失败可读 | `/api/drama/play`、`/api/vod/play` 返回错误码与文案 | 网页/电视端显示原因而不是"请求处理失败" |

## 阶段 2：源卫生与首页

| # | 任务 | 改动点 | 验证 |
| --- | --- | --- | --- |
| T2.1 | 源失败即停用 | 配置拉取失败（401/403/404/超时）→ `ConfigSource.enabled=false` + 原因（已完成） | 单测 + 真机 FISH 源显示已停用 |
| T2.2 | 首页出画面 | 首页只用体检通过的站点；未体检时 CMS 优先、插件 ≤2；超时 8s→6s；把"超时"计入体检 | 模拟器首页 5 秒内渲染或给出原因 |
| T2.3 | 首屏先渲染 | 首页结果到达即渲染，不再等全部站点 | 模拟器上有慢站点时仍先出画面 |
| T2.4 | 空态可解释 | 首页/搜索空结果展示前 5 条失败原因 | 模拟器断网场景 |

## 阶段 3：搜索

| # | 任务 | 改动点 | 验证 |
| --- | --- | --- | --- |
| T3.1 | 首字母搜索 | `PinyinInitials`（GB2312 表，已完成）+ 本地标题索引（首页/搜索/短剧所见标题）→ 命中后按中文标题重搜 | `PinyinInitialsTest` + 模拟器输入 `LLDQ` |
| T3.2 | 线程占满时跳过插件 | `inFlight` 计数 + 饱和判定（已完成） | 单测 + 模拟器连续搜索 |
| T3.3 | 结果与原因并列 | 电视端与网页都显示"命中 N 条 · 失败 M 个站点（原因）" | 界面检查 |

## 阶段 4：体检与真机验收

| # | 任务 | 产出 | 验证 |
| --- | --- | --- | --- |
| T4.1 | 真机一键体检 | `/api/debug/health/run` + 网页体检卡片（已完成） | 139 站点跑完，可用/不可用落盘 |
| T4.2 | 按体检结果收敛源 | 只保留 饭太硬 / 王二小 / 非凡短剧；FISH 停用、XHZ 视体检结果处置 | 真机 `sources` 列表 |
| T4.3 | 真机验收 | 装 v0.4.1 使用一天 | 无闪退；短剧可播；搜索可用 |

## 验证命令

```bash
# 本地构建与测试
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestJavaWithJavac
cd web && npm test -- --run && npm run build

# 模拟器（API 19 x86，与电视同系统版本）
emulator -avd NukaCast_API19_x86 -no-snapshot -no-audio -gpu swiftshader_indirect
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb forward tcp:9978 tcp:9978
node tools/nukacast-mcp.mjs --call nukacast_snapshot        # 经 MCP
curl -s localhost:9978/api/debug/sites                      # 或直接 HTTP

# 真机（电视在局域网内）
NUKACAST_HOST=192.168.5.3:9978 node tools/nukacast-mcp.mjs --call nukacast_site_sweep '{"action":"start","waitForCompletion":true}'
node tools/nukacast-watch.mjs --host 192.168.5.3:9978      # 崩溃取证（外部轮询）
```

## 7. v0.4.1 实施记录（真机证据驱动）

### 7.1 闪退根因（第二次，真机 `/api/diagnostics`）

设备 v0.4.0 的一次运行：`08:17:52` 启动，**`08:18:43` 消失**（约 51 秒），`endedCleanly=false`，
无 Java 异常、无尸检日志，5 秒采样显示最后一刻正在**逐个创建插件蜘蛛会话**：

```
08:18:18  spider/<uuid>|玩偶哥哥/jar_session
08:18:23  spider/<uuid>|玩偶运输车/jar_session
08:18:28  spider/<uuid>|玩偶运输车/jar_session
08:18:33  spider/<uuid>|csp_Nmys/js_session
08:18:43  spider/<uuid>|三秋影视/jar_session
```

即：**崩溃发生在首页拉取阶段（走插件站点），不是播放阶段**。每次采样都在不同站点的
`jar_session` 上，进程在连续创建 DexClassLoader 插件后被系统终结。日志里同时可见
`Spider JAR MD5 不匹配`、`VerifyError` —— 这些 JAR 在 Dalvik 上根本无法加载。

### 7.2 修复：API < 21 拒绝 JAR 蜘蛛

`SpiderManager.jarSpidersSupportedFor(sdkInt)`（阈值 21）：低于该值时不下载、不建会话，
直接抛出带原因的错误并把站点记入 `SiteCompatibilityStore`（永久），首页与搜索随后自动跳过。
理由既明确又真实：**Dalvik 无法校验这些 JAR 的字节码**，尝试只会拖垮进程。
`JS 蜘蛛`（配置里 api 指向 `.js`）仍然允许，本机实测可用。

### 7.3 随之而来的结论（必须告知用户）

饭太硬（53 站点）与王二小（96 站点）的站点**全部是 type 3 插件站点**，且它们的 JAR 由配置的
`spider` 字段提供（`./fty.jar`、伪装成 `.jpg` 的 403 大 JAR）。在 Android 4.4 电视上：

- 这两个源的所有站点都不可用；
- 能在这台电视上跑的是**纯 JSON 接口站点** —— 随包内置的 `asset://sources/starter.json`
  已验证：搜索、详情、直连 m3u8 播放全部正常（`getEnabledSites` → 光速/豪华/量子/非凡/电影天堂/好剧/暴风）。

因此推荐源清单保留用户要求的两个仓（在 Android 5.0+ 设备上可用），同时把内置清单作为
"本机可用"选项留在推荐源里，并在源刷新时明确提示插件站点数量与被自动限制的原因。

### 7.4 其它本轮修复

| 问题 | 处理 |
| --- | --- |
| 解析接口逐个超时，播放入口等待 30 秒以上 | 并发竞速 + 4 秒总时限；结果缓存 10 分钟 |
| `share/play/*.html` 播放页黑屏 | `MaccmsShareResolver`（三种脚本写法 + 相对路径补全）|
| Let's Encrypt Generation Y 根证书不在信任库 | 随包内置 `ISRG Root YR/YE` |
| 播放错误路径 `NoSuchMethodError` 杀进程 | 自研 `OkHttpDataSource`（与 core 同版本，走应用 OkHttp/Conscrypt）|
| 暴露到局域网的 HTTP API 在老化 TLS 上不可用 | `HttpStack.installPlatformTls()` 进程级 Conscrypt 注入（仅当平台缺 TLS 1.2）|
| 首字母搜索（LLDQ）搜不到 | `PinyinInitials`（GB2312 首字母表）+ `TitleIndex`（本地标题索引）|
| 首页/详情/播放页整体偏大、内容被裁 | 全面缩小（侧栏 164dp、导航 14sp、区块标题 15sp、卡片 132×210、首屏 132dp、集数 12sp/8 列）|
| 投屏时右下角常驻"退出投屏" | 改为自动隐藏的 `PlayerHudView`（非焦点、按键唤出、4 秒淡出）|
| 集数页字号过大、排版拥挤 | `DetailScreen`：紧凑网格（76×34dp、8 列、6dp 间距）|
| 离开影视页后类型筛选仍高亮 | `showPage` 中清空筛选 |
| 站点体检会走完全部站点 | 内存预算检查 + 每站点释放会话 + 跳过不可用站点 |

## 9. v0.4.2：播放质量与可用性（全部由真机/模拟器实测驱动）

### 9.1 实测发现（API 19 x86 模拟器 + 小体积测试流）

| 现象 | 测量 | 结论 |
| --- | --- | --- |
| 「太糊了」 | `/api/player` 显示选中 `320x184@246k`，而清单里有 `1280x720@2.1M` 与 `1920x1080@6.2M` | ExoPlayer 的自适应按带宽估算挑最低档；`forceHighestSupportedBitrate` 不起作用 |
| 强制最高档后「一直缓冲」 | 覆盖到 1080p 变体后 `state=buffering` 且永不前进 | 该解码器不支持 1080p（`format_supported=NO_EXCEEDS_CAPABILITIES`），既不报错也不出画 |
| 部分线路「未解析出可播放地址」 | `https://v.gsuus.com/play/<id>` 页面里是 `const vid = '.../index.m3u8'` | 解析正则只认 `url=` 变量；且旧实现用 `HttpURLConnection`，在 API 19 上对该站点 TLS 失败 |
| `/api/debug/play` 永远 400「不支持的播放地址」 | 参数顺序写反：`play(ctx, title, url, …)` | 调试接口自己坏掉了 |

### 9.2 修复

- **画质**：按“解码器真正支持”筛选变体（`MediaCodecInfo.isFormatSupported`），在其中选像素最大（同尺寸取最高码率）的一档并显式 override；同尺寸不同码率的自适应保留。
- **保底**：① 强制最高档后 8 秒无画面 → 自动回到自适应；② 解码失败 → 先换软件解码（持久化，设备属性）；③ 仍失败 → 放弃强制、回到自适应重播。
- **换线**：`resolvePlayable()` —— 当前线路拿不到可播地址时，自动尝试同一剧目的其他线路（最多 3 条，按集号对齐），电视端点播与 `/api/play` 都走这条路径。
- **播放页解析**：改用应用 OkHttp（内置根证书 + Conscrypt），并识别 `url/vid/video/src` 变量名。
- **推荐源**：加回随包的 `asset://sources/starter.json`（7 个纯 JSON 接口站点，Android 4.4 可用），排在饭太硬/王二小之前；后两者在 API < 21 上无法工作（插件仓）。
- **调试接口**：修好 `/api/debug/play` 的参数顺序；`/api/player` 现在返回实际选中的视频轨道（尺寸/码率/编码）、可见的全部变体、当前解码偏好，因此「糊/不清晰」可以用数字回答。

验证：55 suites / 223 unit tests 全通过 · lint 0 error / 4 warning · assembleDebug + androidTest 编译通过 ·
模拟器实测：`state=playing`，位置持续推进（4s→35s），选中轨道与全部变体均可在 `/api/player` 查到。
