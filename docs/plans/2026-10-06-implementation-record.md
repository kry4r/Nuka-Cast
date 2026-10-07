# 实施记录：短剧接入、AirPlay 首帧与源稳定性（2026-10-06）

依据 [audit](2026-10-06-compatibility-audit.md)、[spec](2026-10-06-compatibility-spec.md)、[plan](2026-10-06-compatibility-plan.md) 实施。本文件只记录已经落地并有本地证据的改动；没有目标电视/iPhone 的项目一律标为 pending，不视为已验证修复。

## 1. 已实现内容

### 1.1 短剧目录与线路匹配（T5/T6 的目录部分 + 复用播放链路）

新增 `app/src/main/java/com/nukacast/app/drama/`：

| 文件 | 作用 |
| --- | --- |
| `DramaCatalogParser` | 纯 JSON 映射：`items[]`、`item`、`total`、`warning`、`ok=false`。id 全部经 `getAsString()` 读取，19 位数字不会被 double 截断；`/api/drama` 的 `episodeCount` 只当元信息，从不生成选集。 |
| `VoteDramaCatalog` | 只读目录适配器：`/api/search?q=`、`/api/drama?id=`、`/api/related?id=&title=`。相关推荐失败只降级为空，不影响详情。 |
| `DramaCatalogRegistry` | 目录 provider 持久化（启用/停用/删除），URL 校验、拒绝账号密码、删除后不自动恢复。 |
| `DramaTitleMatcher` | 跨源片名匹配与排序：规范化标点/全角/空白，`第N季`/`第N部` 统一成阿拉伯数字，季数冲突拒绝，短于 4 字的包含关系拒绝。完全同名 100 分、前缀 80、包含 70；同名不同站点全部保留。 |
| `DramaLineFinder`（在 `DramaService` 内） | 对已启用可搜索片源（type 0/1/3）并发按片名搜索，2 线程 + 12 秒 deadline，结果去重排序；从不按标题自动播放，完全同名才预选。 |
| `DramaService` | 编排：有界 LRU 缓存（16 条 / 5 分钟）、结构化错误（`errorCode`、`rootCauseClass`）、provider 错误回写。 |

播放仍走原有 `sourceId/siteKey/vodId` 契约：web 与电视确认线路后调用既有 `/api/detail`、`/api/play`，因此收藏、续播、选集、解析器/嗅探流程全部复用。

接口（`ControlServer`）：

- `GET/POST /api/drama/providers`、`DELETE /api/drama/providers/{id}`、`POST /api/drama/providers/{id}/enabled`
- `POST /api/drama/search`、`POST /api/drama/detail`、`POST /api/drama/lines`
- `GET /api/diagnostics` 增加 `drama` 与 `httpStack` 字段（旧客户端忽略未知字段）。

界面：

- 网页新增“短剧”页：目录管理（内置候选需手动添加）、搜索、详情、标签、相关推荐、线路匹配与确认、选集播放；“暂无可用播放线路”会显示原因（没有片源 / 已查询站点无匹配 / N 个站点失败）。
- 电视端：影视页新增“短剧”筛选；电视搜索在片源结果后用独立请求追加“短剧目录”区块；短剧卡片进入目录详情，完全同名线路直接进入既有详情/选集页，非完全同名弹出线路选择；无线路时只展示资料并提示原因。收藏/续播遇到 `drama:` 前缀会回到目录流程。

### 1.2 AirPlay 首帧与身份（T3 的 F06/F07 部分）

- `DecoderFallbackPolicy`：24 帧 + 1500ms 只是加速路径；**任一已提交输入超过 3000ms 且无输出**同样触发回退。0 输入不会触发（等待 IDR 时软件解码也无济于事）。
- `H264VideoRenderer`：硬解运行期异常最多重建 3 次，超过后停止重建并记录“解码器持续异常”；软件解码 4 秒无输出时明确报“镜像格式可能不受支持”，不再无限黑屏。计数在配置变化/`flush()` 时复位。
- `AirPlayIdentity`：设备 MAC 稳定持久化，`name=NukaCast`、`model=AppleTV3,2`、`pi=UUID(name|mac)` 一次生成，mDNS TXT 与原生 `/info` 共用。
- 原生 `/info`：`raop_set_identity()` 保存身份，`raop_build_identity_info()` 用捆绑 libplist 解析 836 字节模板 plist，替换 `deviceID/macAddress/name/model/pi/pk` 后重新序列化；任何一步失败都回退到原始模板，保证配对不受影响。`raop_get_identity_summary()` 暴露实际身份，`/api/diagnostics → airPlay.identity` 与网页设备页可见（A01 需要的证据面）。
- 已确认 Python 侧等价变换结果：19 位/身份字段替换后 plist 合法，`features/displays/audioFormats/sourceVersion` 等能力字段保持不变（836 → 837 字节，序列化差异）。

### 1.3 源稳定性（T1 的 F01 部分）

- `HttpStack`：静态初始化整体包在 `try/catch(Throwable)` 中。Conscrypt 加载失败或 legacy TLS 构造失败时不再抛 `ExceptionInInitializerError`，而是退回平台 TLS 客户端，并通过 `degraded()`/`initError()` 暴露；`/api/diagnostics.httpStack` 与网页设备页显示“已回退平台 TLS：原因”。证书校验没有被放宽。
- `StageTrace` + `ErrorCodes`：有界（48 条）阶段记录，覆盖 `fetch_config -> decode -> persist`（片源）、`native_load -> native_listen -> mdns_publish -> codec_config -> first_output`（AirPlay）与 `js_session/jar_session -> plugin_init`（Spider）；失败记录 `errorCode`、最深层 `rootCauseClass`、原始消息与耗时。`ErrorCodes` 能区分 DNS/超时/TLS/网络、Dalvik verifier/linker、native SO、OOM 与取消。记录是线索不是结论，需与 logcat/native/ANR 一起判读。
- 刷新执行边界：`TvBoxRepository.refreshSafely()` 现在捕获 `Throwable`（原来只捕 `Exception`），插件的 `LinkageError`/`VerifyError` 不再从刷新线程逃出导致进程退出，而是记录阶段并保留其余 UI。
- Spider 会话身份与上限（F04 部分）：session key 加入 `sourceId|siteKey`，`siteSpiders` 改用同一身份键；代理请求优先使用最近 pin 的同 siteKey spider，避免跨仓串用。会话达到上限时按“最久未用”LRU 释放并重建，不再直接报“会话数已达上限”。

### 1.4 测试与 CI（T4 部分）

- 新增 JVM 测试：`DramaCatalogParserTest`、`DramaTitleMatcherTest`、`DramaCatalogRegistryTest`、`DramaServiceTest`、`AirPlayIdentityTest`，并扩充 `DecoderFallbackPolicyTest`、`HttpStackTest`。
- 新增 `Api19UiLoadTest`（androidTest）：真实布局/导航 ID 膨胀、关键类加载、内嵌 web 资源、Conscrypt 未被降级。
- `.github/workflows/android.yml` 仪器测试改为矩阵：API 35 x86_64（`includeTestAbi`）与 API 19 x86（`includeLegacyTestAbi`），`run-instrumentation.sh` 接受 ABI 属性参数，诊断产物按 API 区分。

## 2. 本地证据

```text
JDK 18.0.2.1（CI 仍为 17），ANDROID_HOME 指向 SDK 35，NDK 20.1.5948944 / CMake 3.22.1
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
  -> 39 suites / 142 tests / 0 failures / 0 errors / 0 skipped
  -> lint: 0 error / 8 warning（与基线一致）
.\gradlew.bat :app:assembleDebug
  -> BUILD SUCCESSFUL，arm64-v8a + armeabi-v7a 原生库含新的 raop.c / raop_handlers.h
.\gradlew.bat :app:compileDebugAndroidTestJavaWithJavac
  -> BUILD SUCCESSFUL
web: npm test -- --run   -> 4 files / 11 tests
web: npm run build       -> tsc -b + vite build 成功，assets 已更新
python yaml parse        -> workflow jobs/matrix 结构合法
python plistlib 等价变换 -> 836B 模板身份替换后仍为合法 binary plist
```

## 3. 验收状态

| 项 | 状态 | 说明 |
| --- | --- | --- |
| 短剧目录搜索/详情/相关（真实接口形状） | pass（本地 HTTP + 单元 fixture） | 2026-10-06 观测契约；分页未验证，不伪造翻页 |
| 短剧线路匹配排序/季数防混淆 | pass（JVM 测试） | 不自动播放近似标题 |
| 短剧真实播放链路（D03：两部剧首/中/末集） | **pending** | 本机无法访问可播放短剧 provider；需要用户片源或真实短剧 CMS |
| 电视端短剧筛选/搜索区块/详情路由 | 代码完成，真机 pending | 编译通过，未在电视操作 |
| AirPlay 低帧数回退阈值 | pass（JVM 策略测试） | 真机首帧仍 pending |
| AirPlay /info 与 mDNS 身份一致 | 代码完成，真机 pending（A01） | 需在重启 native 后比对 TXT 与 `/info` |
| 软件解码无输出明确报错 | 代码完成，真机 pending（A05） | 需海思类设备验证 |
| HttpStack 初始化失败不再带走 UI | pass（JVM 测试覆盖边界与回退客户端） | API19 真机 TLS provider 加载由 CI + 真机确认 |
| 阶段诊断（source/spider/airplay/http） | pass（JVM 测试 + `/api/diagnostics.stages` + 网页展示） | 真机采集仍需结合 logcat/native |
| Spider 会话身份与 LRU | pass（代码 + 编译） | 需在真实多仓配置下观察会话命中/释放 |
| API19 x86 类加载/布局/TLS | 代码 + CI job 完成，流水线 pending | API19 job 严格断言 Conscrypt 未降级；若 CI 失败，修代码而不是放宽断言 |
| 插件独立进程 / 不可中断执行（T2/F02/F03） | **未实施** | 需要单独设计 IPC 契约，见 spec 3.2 |
| 24 帧门槛之外的真机首帧耗时 | **pending** | 需要 SHARP/iPhone |

## 4. 限制与风险

- 参考站 `vote.252035.xyz` 不提供剧集与播放地址，也不提供空关键词浏览（`q=` 空返回 400）；短剧页因此只有搜索入口，没有伪造的热门列表。
- 播放线路依赖用户已启用的 TVBox 片源包含短剧内容；本机网络无法验证第三方短剧 CMS 的直链，D03 仍未完成。
- 原生 plist 重写使用捆绑 libplist，编译通过但只在本地用等价 Python 变换验证了数据形状；真机需要按 A01 比对 `/info` 字段与 mDNS TXT。
- 真实设备的根因（Dalvik verifier、SO、海思解码器）仍未知；本轮没有声称修复了用户报告的闪退，只是补上诊断与错误边界。
- 未改动 minSdk、依赖版本、播放器与已有 TLS 校验策略。

## 5. 版本与发布

- 本轮默认版本号提升为 `0.3.5`（`versionCode` 11），正式包由 `v0.3.5` 标签触发 `release.yml` 构建（标签版本号会覆盖默认值，`versionCode` 取 run number）。
- 发布门禁：`testDebugUnitTest + lintRelease + assembleRelease`，签名校验、双 ABI 原生库存在性、无 x86_64、内置网页资源未过期。

## 6. 建议下一步

1. 在 SHARP 电视安装本次 debug APK，按 plan 的 T0 采集流程抓取首次崩溃的 earliest cause，并导出 `/api/diagnostics`（现在包含 `httpStack`/`drama`/`airPlay.identity`/`stages`）。网页“设备”页的阶段诊断可直接看到最后一个 running/failed 阶段。
2. 提供至少一个可用短剧片源或 CMS 地址，完成 D03 的完整真实播放链路。
3. 按 F02/F03 评估 `:spider` 独立进程与 QuickJS 中断/内存限制；这是防止插件带走 UI 的架构项，不能在当前证据下假定已解决。
