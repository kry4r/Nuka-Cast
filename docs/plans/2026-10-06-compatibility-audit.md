# SHARP Android 4.4、AirPlay 黑屏与短剧接入：扫描记录

日期：2026-10-06。代码基线：`main`，`78244dca44380b15cd9265f05904c040e4508664`；源码版本为 0.3.4 / versionCode 10。

本轮只做扫描、基线检查与规划，没有修改功能代码。用户安装的 APK 版本、电视精确型号、ABI、可用内存及 iOS 版本尚未核实。

## 1. 用户报告与范围

- SHARP 电视、Android 4.4，解析源时闪退。“解析源”尚不能区分配置导入、自动首页、搜索、选集解析或 WebView 嗅探。
- iOS 能连接，连接后黑屏。关于退出后的表现，用户原话为“退出后仍未黑屏”，语义尚不明确；本计划覆盖退出恢复 UI，不能把“退出后仍黑屏”写成已确认现象。
- 故障源：`http://肥猫.net/tv`、`https://d.kstore.dev/download/9280/wex.json`、`http://xhztv.top/4k.json`。
- 后续参照 `https://vote.252035.xyz/` 接入短剧解析源。

扫描覆盖构建与依赖、Manifest、启动/服务、配置下载/解码/缓存、多仓、JAR/JS Spider、首页/搜索/详情/播放解析、WebView、播放器 Surface、AirPlay mDNS/JNI/镜像包/解码/会话退出、日志和 CI。未逐行审计全部第三方密码学与 AAC 源码，未连接目标电视或 iPhone。

## 2. 当前源的只读网络检查

2026-10-06 16:38 UTC，在本机用 Node HTTP GET、20 秒超时、2 MiB 响应上限检查；没有下载或执行 Spider JAR/JS。结果只代表本机网络，不能替代 API 19 的 DNS/TLS/原生加载验证。

| 源 | 本机结果 | 解释与后续核查 |
| --- | --- | --- |
| 肥猫 `/tv` | HTTP 200，16274 字节，octet-stream，内容为配置；39 个站点均为 type=3 | 全局 Spider 为 HTTPS URL，文件后缀 `.png`，声明 MD5；不能用扩展名判断其内容或兼容性。必须拆开检查导入成功与 JAR 初始化/首页调用。配置中有 2 个站点的字段含 `.js`，这不等于已确认能用当前 QuickJS 运行。 |
| kstore `wex.json` | 请求报 `ECONNRESET` | 未取得配置，类型、内容、JAR 均未知；不能推断源永久失效、证书错误或电视一定有同样结果。 |
| xhztv `4k.json` | HTTP 200，30612 字节，application/json | 清理注释后的 Node 严格 JSON 解析发现字符串内控制字符；项目使用 Gson lenient，是否接受必须用实际 ConfigDecoder 验证。全局 Spider 提示为 HTTPS `.txt;md5;...`，不可因后缀拒绝。 |

检查响应 SHA-256：

- 肥猫：`df88abef0151122fb190ce870f064496de2b2b0c5e0ea4ccb621967f0c273474`。
- xhztv：`ff00f27c05a041f2283871c10c1261e7da1291363cdfadc124ba466de40b6814`。

源会变化。后续 agent 应保存自己复现时的时间、最终 URL、响应大小和摘要，不能把这次返回当作固定 fixture。

## 3. 代码发现：按证据区分

| ID / 优先级 | 已看到的代码事实 | 风险或假设，尚非本次真机根因 | 入口 |
| --- | --- | --- | --- |
| F01 / P0 | HttpStack 的静态 CLIENT 初始化在 SDK<22 调用 Conscrypt；初始化与配置刷新只 catch Exception | 原生加载的 LinkageError 或静态初始化 Error 可逃出刷新 executor.execute，造成进程退出。应先查 logcat 中第一个原因，不能默认加全局 catch(Throwable)。 | `net/HttpStack.java`、`tvbox/TvBoxRepository.java` |
| F02 / P0 | JAR Init、DexClassLoader、第三方 Spider 与 QuickJS 都在主应用进程；Manifest 没有 Spider 独立进程 | SIGSEGV/SIGABRT 无法由 Java try/catch 隔离；插件自身线程未捕获异常也可能结束应用。Dalvik VerifyError、SO ABI/指令集或缺失符号是重点候选。 | `spider/SpiderManager.java`、`QuickJsSpiderSession.java`、Manifest |
| F03 / P0 | Future.cancel(true)/shutdownNow 用于超时；QuickJS 未配置引擎中断、内存或模块总量限制；构造失败无完整 finally 清理 | 不响应中断的原生 JS/JAR 可继续执行，初始化失败可能留下线程/运行时；反复超时会耗尽线程和内存。[Future 文档](https://developer.android.com/reference/java/util/concurrent/Future.html)只定义尝试中断。 | `spider/QuickJsSpiderSession.java:176`、`SpiderManager.java` |
| F04 / P1 | 三层固定线程池各为 4；Spider session() 同步锁内下载/加载/初始化；最多保留 64 会话，无 LRU | API 19 低内存设备会排队、阻塞或累积资源；单个慢 Init 可阻塞其他站点。session key 缺 sourceId，siteSpiders 只以 site.key 索引，存在跨仓复用/代理串源风险。 | `SpiderManager.java:165`、`SearchEngine.java`、`TvBoxContentService.java` |
| F05 / P1 | ConfigDecoder 设置 lenient；2 MiB 下载上限、64 子仓上限已有，但结构深度/站点数/空元素边界不完整 | 必须保留常见配置兼容，同时避免畸形配置消耗内存。JS 入口目前只识别 HTTPS 且包含 .js 的 URL，HTTP JS 与其他格式不一定受支持。 | `ConfigDecoder.java`、`SourceStore.java`、`QuickJsSpiderSession.java:217` |
| F06 / P0 | AirPlay `/info` 是 836 字节固定 binary plist；mDNS 使用保存的随机设备 ID 和当前原生公钥 | 身份信息不一致是确定事实，是否触发当前 iOS 黑屏需验证。不要因为能选择接收器就认为媒体握手全部通过。 | `cpp/legacy-airplay/lib/raop_handlers.h:33`、`AirPlayPublisher.java` |
| F07 / P0 | 硬解无输出回退要求 inputs>=24 且 elapsed>=1500ms；RuntimeException 会释放解码器并重试，未限制同一候选重建次数 | 若静止画面只送少量帧，永远达不到 24 帧；若硬解持续抛错，重建可反复重置计时，继续黑屏。 | `airplay/DecoderFallbackPolicy.java:14`、`H264VideoRenderer.java:136` |
| F08 / P0 | Surface 同时交给播放器和 AirPlay；IDR 被保留；pendingKeyFrame 有写入但未见独立消费路径；flush 跨线程更新状态 | 检查 Surface 晚到、配置与 IDR 顺序、配置变化、退出与旧 JNI 回调竞态。首次静止图像需在无需后续新帧的条件下恢复。 | `MainActivity.java:269`、`H264VideoRenderer.java`、`AirPlayReceiver.java` |
| F09 / P1 | native bridge 吞掉原生加载 Throwable，只返回“当前 ABI 没有库”；原生日志只到 logcat；CrashReporter 只保存 Java 崩溃 | 加载失败、缺失符号、原生信号或系统低内存杀进程不能靠网页最后 Java 错误判定。现有 diagnostics 未包含持久化 native stage。 | `NativeAirPlayBridge.java`、`CrashReporter.java`、`server/ControlServer.java` |
| F10 / P1 | 服务 onCreate 在主线程启动 server、native receiver 和 JmDNS；日志每条同步重写整个 JSONL | mDNS/释放 native 若阻塞，会影响 UI 或形成 ANR；高频日志可能加剧旧电视 I/O 压力。需要测耗时与线程栈后优化。 | `service/NukaCastService.java`、`diagnostics/AppLog.java:121` |
| F11 / P1 | SniffingActivity 同一个匿名 WebViewClient 引用 API 21 WebResourceRequest；播放器主线程初始化没有完整错误转换 | API 19 应单独验证嗅探类加载、WebView TLS/页面脚本能力及播放器初始化。HttpStack 的 TLS 修复不会自动作用于系统 WebView。API 21 类型信息见 [Android 文档](https://developer.android.com/reference/android/webkit/WebResourceRequest)。 | `tvbox/SniffingActivity.java`、`player/PlayerController.java` |
| F12 / P0 验证 | CI instrumentation 仅 API 35 x86_64；SmokeTest 仅断言 packageName | 现有测试名包含 Api19 不代表实际在 API 19 运行，SmokeTest 也未验证 Activity、源或首帧。 | `.github/workflows/android.yml:159`、`androidTest/.../SmokeTest.java` |

`/info` 固定 plist 本轮解码结果：model=`AppleTV2,1`、name=`AppleTV`、deviceID/macAddress=`aa:54:01:af:c3:c1`、固定 32 字节 pk；mDNS model=`AppleTV3,2`、name=`NukaCast`。两处 features 都对应 `0x1e5a7ffff7`，**feature 数值本身不是这次发现的不一致项**；是否宣告了未实现能力需另行逐位审查。

## 4. 已有修复与历史记录

仓库已经包含 TLS 1.2、Conscrypt/补充根证书、相同 SPS/PPS 不重建解码器、native 活跃会话不因静止两秒断开、API<21 legacy MediaCodec buffers 等修复。不要把这些当作缺失功能重新实现。

`2026-07-29-v034-release-handoff.md` 记载模拟器解析与 JAR 下载成功，但明确留下 ARM TV 的 FishGuard SO、Dalvik verifier、海思首帧与 iPhone 镜像验收。该文件的型号、IP、iOS 版本均属于历史记录，不能自动当作当前设备信息。

## 5. 短剧参考站调查

2026-10-06 只读 GET 获取了 [首页](https://vote.252035.xyz/)、[前端 app.js](https://vote.252035.xyz/app.js)、[配置接口](https://vote.252035.xyz/api/config)、[搜索接口](https://vote.252035.xyz/api/search?q=%E9%87%8D%E7%94%9F)及一条搜索结果的详情。没有生成身份、投票或发布 Nostr 事件。

| 入口 | 观察到的契约 | 对接影响 |
| --- | --- | --- |
| 首页 / app.js | “红果短剧 · 投票推荐榜”；首页推荐通过 Nostr relay 事件聚合 | 不是标准 TVBox 配置，不应作为 JSON 单仓直接导入；榜单与播放解析分开设计。 |
| `/api/config` | HTTP 200；title、version、relays、eventKind=30078、voteDTag | 不是站点/剧集配置，没有提供播放器契约。 |
| `/api/search?q=` | HTTP 200；`{ok,items,total,warning}`；一次“重生”搜索返回 23 条、total=213 | item 包含 id/title/cover/intro/remark/episodeCount/category/tags/heat/status。未发现公开分页参数，不能拿 total 伪造翻页。 |
| `/api/drama?id=` | HTTP 200；`{ok,item}`；本轮样本只有剧目信息 | 没有返回剧集列表、播放 URL 或播放凭据；episodeCount 不能当作可播放选集。 |
| `/api/related?id=&title=`、`/api/poster?u=` | 前端代码可确认使用；本轮未验证其返回 | 按需再验证，避免假定其他端点存在。 |

app.js SHA-256：`adabb30619e92d4be08cb19ff362307571dcfa5b755dc2ddb22f272efdf13cfb`。当前搜索/详情结构与项目 TVBox 模型不同：HomeCatalogParser 不识别 items，MediaDetailParser 不识别 item，播放器解析也没有剧目 ID 到剧集的实现。

**接入结论：目录/搜索适配有可观察接口；短剧“可播放解析源”还缺经验证的剧集与播放接口。** 后续 agent 必须找到真实支持的播放 provider 或可播放 TVBox 源，再验证 ID 对应关系；不能编造 `/api/play`，也不能把投票页面或封面 URL 送给播放器。

## 6. 本轮验证

| 检查 | 结果 |
| --- | --- |
| Android JVM 单测 `:app:testDebugUnitTest` | 32 suites、87 tests，0 failure/error/skipped |
| Android `:app:lintDebug` | 通过；0 error、8 warning。旧依赖提示不能作为直接升级理由，应保留 API 19 兼容边界。 |
| Web `npm test -- --run` | 3 files、7 tests，通过 |
| Web TypeScript `tsc -b` | 通过；本轮未重建打包 assets |
| APK assemble / 原生重编译 / 设备 instrumentation | 本轮未运行 |
| `adb devices -l` | 无设备；未做 SHARP/iPhone 真机复现 |

本机基线使用现有 JDK 18.0.2.1 与 Android SDK 35；项目/CI推荐 JDK 17。没有更改工具链配置或安装系统工具。

下一步见 [spec](2026-10-06-compatibility-spec.md)、[plan 与测试任务](2026-10-06-compatibility-plan.md)、[agent prompts](2026-10-06-agent-prompts.md)。
