# 实施 Plan 与测试任务

基线和事实见 [audit](2026-10-06-compatibility-audit.md)，行为契约见 [spec](2026-10-06-compatibility-spec.md)。下面任务均为待实施；本轮通过的基线检查不勾选真机任务。

## 1. 执行顺序与任务包

| 任务 | 交付物 | 依赖 / 完成条件 |
| --- | --- | --- |
| T0 设备/安装包与复现采集 | 设备与APK指纹、逐步复现、logcat/崩溃/内存记录、源摘要、根因分级 | 先确认 installed release/debug/versionCode。无设备时仍做fixture/代码与CI，真机项记 pending，不能宣称修好。 |
| T1 源阶段诊断与最小修复 | 可区分配置、TLS、插件Init、站点方法、播放、WebView的诊断；首个可复现问题修复与回归 | 以T0或合成复现证明；不把下载成功当站点成功。 |
| T2 插件隔离与资源预算 | 必要时实现 :spider worker、错误/超时/代理流契约、资源释放与LRU | 先冻结IPC契约；native/不可中断故障必须有worker死亡与UI存活验证。若暂不实施，写明仍残留风险，不称彻底解决插件闪退。 |
| T3 AirPlay 身份/首帧/退出 | 动态一致/info、阶段诊断、低帧数fallback、受限重试、Surface和旧回调处理 | 可先独立做纯状态/数据测试，再与T0真机结果定位；保留已修SPS/PPS和静止会话行为。 |
| T4 API19/ARM测试与CI | API19 x86 job、真实Activity/源/嗅探测试、ARM真机脚本、证据报告 | 保留API35回归；x86不用ARM-only第三方SO，ARM设备另作发布门槛。 |
| T5 短剧契约调查 | 已知搜索/详情样本、真实剧集/播放provider契约、来源及ID映射说明 | 不猜/api/play，不把榜单当播放源；可与T1/T3调查并行。无播放契约时明确阻塞的部分。 |
| T6 短剧适配与产品链路 | provider adapter、源管理、短剧过滤/内容行、选集/解析/播放/收藏/续播 | T5与统一资源/来源身份契约稳定后开始；至少一条完整真实播放链路才算完成。 |
| T7 集成与发布候选验收 | 全量回归、相同APK真机记录、已知限制、候选SHA-256 | 本轮不发版。所有关键门槛通过后才建议发布；无真机只交候选和待验收清单。 |

推荐迭代：第一批 T0/T1/T3/T4，恢复稳定性和可观测性；第二批 T2/T5/T6；第三批 T7 与实测性能优化。短剧调查可提前，功能集成不要掩盖现有闪退/黑屏。

多人实施时各任务独立分支/worktree。T1/T3/T6共享 AppState/ControlServer/Web diagnostics 等文件，先确定字段契约，由集成者统一修改；不可在同一工作树相互覆盖。

## 2. 最少复现信息与采集

首个证据包包括：电视manufacturer/model/API/ABI、app heap、安装包versionName/code/package/SHA-256、iPhone型号/iOS、连接方式、源URL及响应摘要、复现时间、进程ID、崩溃前最后stage与第一个错误、是否能恢复UI。

不先清数据/删源来“修复”。首次保留故障状态；干净安装与旧缓存升级是独立测试组。debug后缀 `.debug` 与 release可共存，需确认实际运行的是哪一个。

示例只读采集（adb已配置后；有多设备时加 `-s <serial>`）：

```powershell
adb devices -l
adb shell getprop ro.product.manufacturer
adb shell getprop ro.product.model
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abi
adb shell dumpsys package com.nukacast.app
adb shell dumpsys meminfo com.nukacast.app
adb logcat -d -v threadtime > logcat-before.txt
```

在独立终端持续 `adb logcat -v threadtime > logcat-repro.txt`，依次执行“加一个源→等首页→搜一次→详情→解析→播放”，记录每个操作时间；闪退后继续记录并重新打开。不要先清logcat以丢失前置错误。

debug包可读取 `adb shell run-as com.nukacast.app.debug cat files/last-java-crash.txt`；release通常不能run-as，通过配对网页 `/api/diagnostics`、`/api/logs`、系统bugreport/logcat获取。native tombstone若固件/权限不允许读取，应记录这个限制，不能把无Java报告解释为没有崩溃。

判别规则：HTTP异常普通失败；VerifyError/NoClassDefFoundError看最早Dalvik verifier；UnsatisfiedLinkError看ABI/符号；SIGSEGV/SIGABRT看native backtrace与模块；ANR看主线程/锁；进程消失无Java异常看系统ActivityManager/low-memory记录。每一类都要给证据而非只报最后一条Toast。

## 3. 设备矩阵

| 环境 | 必测 | 证明边界 |
| --- | --- | --- |
| SHARP API19 ARMv7，用户故障设备 | 三个源完整阶段；海思或实际AVC解码器；iPhone镜像首帧/退出/重连；短剧播放 | 主验收门槛；型号/解码器以采集结果为准，不默认所有SHARP均海思。 |
| API19 x86 emulator | TLS provider、真实配置decoder、JmDNS解析、Activity导航/WebView、纯Java合成Spider | 验证Dalvik/API边界；不能证明ARM-only SO或电视硬解。 |
| API17可用测试设备/模拟器 | 启动/optional capability降级/主要API加载 | 维持minSdk17承诺；依赖SO若不支持应明确能力失败，不造假为全功能。 |
| API35 x86_64 emulator | 现有功能/新字段/Activity/播放器回归 | 保留当前CI覆盖。 |
| 真实iPhone＋目标电视 | 用户当前iOS；另选一个可获得的iOS版本作对照 | 记录确切版本，不复制历史文件的iOS版本；Mac仅可作为辅助。 |

## 4. 源与稳定性测试

| ID | 场景 | 通过标准 |
| --- | --- | --- |
| S01 | 三个用户源分别单独导入，每源重复10次 | 不闪退；成功计数准确，失败有source/stage/rootCause；保存实时摘要。上游不可用可以失败，但应用必须可操作。 |
| S02 | 每个可加载源依次首页、搜索、详情、解析、播放；冷/热JAR缓存各一组 | 各阶段结果独立；插件不兼容可明确失败，不能配置成功后自动调用带走UI。 |
| S03 | IDN肥猫域名、HTTP→HTTPS重定向、HTTPS→HTTPS、TLS1.2、错误证书、断网、连接重置 | API19走真实HttpStack；合法证书可用、非法证书拒绝；退出/重试有界，绝不trust-all。 |
| S04 | BOM/注释/Base64、宽松JSON、字符串控制字符、错误类型、null站点、过深结构、响应超过2MiB、压缩后超限 | 实际ConfigDecoder结果可预期，畸形数据显示错误；保留良好缓存且不重复无限刷新。 |
| S05 | JAR摘要正确/错误、内容变化、.png/.txt扩展名、损坏DEX、VerifyError、缺SO或错误ABI | 错误分类准确；不通过改后缀/跳过摘要掩盖问题；有控制的合成fixture先行。 |
| S06 | QuickJS语法错、缺模块、模块循环、无限循环、过量模块/堆、初始化中途失败 | deadline后调用结束、资源有界；受控worker测试不可中断执行，不能在主进程直接运行破坏fixture。 |
| S07 | worker主动终止/SIGABRT，随后查健康站点 | UI与局域网控制仍活；故障请求返回明确错误；下一健康请求成功，重启次数受限。 |
| S08 | 多仓相同site.key/JS相对路径、正在请求时删除/禁用/更新源 | 不串源、不复活删除项、旧结果不覆盖新内容；proxy指向正确provider。 |
| S09 | 快速刷新/切仓/搜索、连续20轮操作，AirPlay同时接收 | 任务队列/插件并发受限，无ANR；操作结束后线程/FD回归基线，无持续增长。 |
| S10 | SniffingActivity在API19真实打开与退出；解析接口HTML/TLS失败；播放器创建失败 | 类可加载；25s内或取消后返回明确结果，主UI可恢复；不要认为Conscrypt修复覆盖WebView。 |

## 5. AirPlay 测试

| ID | 场景 | 通过标准 |
| --- | --- | --- |
| A01 | mDNS TXT与实际GET /info解码比较，native restart后重测 | ID/model/name/pk一致；plist类型正确；不继续发布旧公钥；features审查有记录。 |
| A02 | iPhone连接后的阶段快照 | 能定位停在握手/配置/IDR/Surface/输入/输出哪一层；不能只报“ready”。 |
| A03 | valid config+IDR、1～23输入帧、硬解0输出，之后完全静止 | 受限时间触发fallback或明确错误，不被24帧门槛永远阻塞。纯状态/假decoder与ARM真机分别验证。 |
| A04 | 连续重复SPS/PPS；真正尺寸变化；Surface晚到与重建 | 相同配置不重建；变更正确重建；静止画面可恢复，无需新的网络关键帧。 |
| A05 | configure/start错误、运行期持续异常、无软件decoder、软件也无输出 | 候选/重试有界；不会每包重建或持续刷屏；清楚显示不支持并能退出。 |
| A06 | 桌面静止60s、滚动/视频30min、旋转、纯音频 | 活跃镜像不因2s静默退出；视频/音频会话结束准确；记录帧率/延迟/CPU/音画偏差，不用目标值当实测。 |
| A07 | iPhone停止、电视返回/停止、网页停止、断网、Surface销毁；各重复10轮 | 停止后2s内恢复可操作UI；5s内重新就绪（网络可用时）；旧包不能重开会话；可重连。 |
| A08 | 损坏mirror头/NAL长度、分片读/中断、native回调异常 | 原生输入校验无越界/崩溃；JNI异常有明确处理；候选原生测试可使用可用的sanitizer/fuzz harness。 |

正常网络、明确支持的非DRM镜像格式：从mirror_connected到首次decoder输出，目标硬解≤3s、含软件回退≤5s；最终阈值应通过目标电视测量确认。不支持格式必须在5s级别给出明确失败，不能无限黑屏。至少记录10次的成功次数/耗时，不把一次成功当稳定性。

## 6. 短剧测试

| ID | 场景 | 通过标准 |
| --- | --- | --- |
| D01 | 参考站搜索/详情/相关接口，ok=false/HTTP失败/空结果/限流 | mapper符合真实契约；warning保留；API未确认分页时不伪造。 |
| D02 | 19位剧ID、跨provider同名剧、重复集/缺集/乱序/百集以上 | ID字符串不丢精度，来源不混用，episodeCount不生成假选集，正确分组/排序。 |
| D03 | 已验证provider：搜索→详情→真实episodes→resolve→API19播放 | 至少两部短剧、每部首/中/末集可完成链路；记录provider与请求摘要。只目录成功不算解析完成。 |
| D04 | 签名地址过期、嵌套响应、无扩展名直链、必须Referer/UA/Cookie、HLS跳转与分片 | 有效媒体证据与headers传递正确；失败有限刷新，保持当前集；日志不泄漏凭据。 |
| D05 | 添加/停用/删除provider，收藏、续播、切集、Web/电视内容同步 | provider状态一致；删除不自动恢复；记录精确集ID。 |
| D06 | 没有可用播放契约/provider，榜单relay断开 | 显示暂无线路，仍可搜索/查看详情；不把页面/封面送播放器，不发投票。 |

## 7. 基线命令与最终证据

```powershell
# 工具链按README配置为JDK17、SDK35、NDK20.1.5948944、CMake3.22.1。
Set-Location web
npm ci
npm test -- --run
npm run build
Set-Location ..
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
git diff --check
# API19 x86专用构建/连接测试，需先确认当前adb目标是API19 x86：
.\gradlew.bat :app:connectedDebugAndroidTest -PincludeLegacyTestAbi=true --console=plain
```

API35 x86_64用 `-PincludeTestAbi=true`；生产包不得混入测试ABI。CI新增API19运行环境与SDK验证，SmokeTest扩展为真的启动Activity/导航/资源加载；真机播放与首帧保持独立测试标签。

本轮已通过：87 Android JVM tests、7 Web tests、TypeScript、Lint（0error/8warning）。后续实现后再跑相关回归和必要全量检查。

验收报告必须逐项标注 `pass/fail/pending/not_supported`，附命令、日志路径、设备、APK摘要和commit。候选APK以同一SHA-256用于SHARP源测试与iPhone镜像测试。没有设备、播放provider或上游响应时，明确未验证项及恢复执行的方法。

## 8. 后续优化方向

稳定性通过后按数据推进：插件LRU和合并并发预算、异步限量日志、首页缓存与请求去重、可取消的旧请求、海报降采样、百集选集分组/惰性创建、AirPlay低分配队列与音画同步。分别采集Java heap/native PSS、FD/线程数、主线程耗时、网络延迟、首帧与CPU；不要仅凭largeHeap或扩大超时处理资源问题。
