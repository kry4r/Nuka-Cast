# SHARP API 19 稳定性、AirPlay 首帧与短剧接入 Spec

状态：拟实施，尚未证明真机根因。依据：[扫描记录](2026-10-06-compatibility-audit.md)。执行与验收见 [plan](2026-10-06-compatibility-plan.md)。

## 1. 目标与边界

1. SHARP Android 4.4 / API 19 上，故障源导入、首页、搜索、详情、解析失败均可诊断；普通坏配置/网络/不兼容插件不得令电视主界面消失。
2. iOS 镜像有可测首帧；静止画面、Surface 重建、主动退出与重连有确定行为。
3. 参照 vote.252035.xyz 接入短剧目录/搜索，并通过经过验证的 provider 完成剧集与播放解析。
4. 兼容下限维持 minSdk 17，API 19 ARMv7 为主验收平台，API 35 为回归平台。提高 minSdk、更新所有依赖或整体迁移播放器不属于本次默认方案。

短剧建议入口为现有影视页中的“短剧”过滤/内容行及源管理中的可选 provider，复用详情、选集、收藏和继续观看。用户已要求增加短剧能力，但未要求把网址默认写入所有用户的 TVBox 源或自动恢复删除项。

## 2. 统一诊断契约

新增字段保持现有 HTTP API 向后兼容，旧客户端忽略未知字段；网页显示简短阶段/错误码，详细上下文供导出。

| 对象 | 最小字段 |
| --- | --- |
| `build` / `device` | versionName、versionCode、commit（构建时有则提供）、进程名、SDK、ABI、设备型号、app heap limit；APK SHA-256由外部采集记录 |
| `sourceOperation` | operationId、generation、sourceId、siteKey、stage、startedAt、elapsedMs、result、errorCode、rootCauseClass、配置/JAR 摘要、响应大小 |
| `airPlaySession` | sessionId、generation、connectionStage、lastProgressAt、surfaceAttached/valid、SPS/PPS/IDR、decoderInputs/outputs、lastError、结束原因 |
| `componentStage` | process/component、阶段、start/success/failure、时间；关键 native load/init 前写入持久化标记，完成后更新 |

source stages：`fetch_config -> decode -> validate -> persist -> load_plugin -> plugin_init -> home/search/detail -> resolve -> sniff -> player_start`。配置刷新与首页的 operationId 必须关联，但不能把“配置导入成功”显示为“源可播放”。

AirPlay stages：`native_load -> native_listen -> mdns_publish -> info/pair/fairplay/setup -> mirror_connected -> codec_config -> idr -> decoder_input -> first_output -> ended/error`。本轮连接黑屏应优先跟踪媒体路径，发现层仅作状态核实。

保存完整异常因果链，区分 Java 异常、linker/verifier、native signal、ANR、LMK；重启后“最后执行阶段”是线索，不是自动判定根因。日志与导出遮蔽 URL 凭据、token、cookie、Authorization，保留 host、阶段、状态码与摘要。原生协议日志要进入同一会话时间线，不能只保留视频数量。

## 3. 源解析与插件稳定性

### 3.1 先分离失败域

- 网络 provider 在可控制的初始化边界记录加载与能力失败；对于 LinkageError/ExceptionInInitializerError 提供明确组件错误，保留原始原因。不要通过静态初始化失败反复污染所有网络请求。
- 保留 TLS/hostname 校验。平台 TLS 回退只有在可用能力确实支持目标站时使用；否则返回 `TLS_PROVIDER_UNAVAILABLE` / `TLS_HANDSHAKE_FAILED`，不能 trust-all 或默默降级 HTTPS。
- ConfigDecoder 使用真实源快照及最小 fixture 验证 BOM、注释、宽松 JSON、编码、空/null站点、字段类型、数量与深度边界；保留现有 2 MiB配置、20 MiB JAR 等上限，新增总量预算。
- 不对所有失败 catch(Throwable) 然后返回空结果。可恢复异常在任务边界转换；内存耗尽与 native fault 通过进程/资源控制处理。
- 坏站点只影响本站，返回部分结果。故障站点退避/熔断并允许手动重试；禁用项不得自动重新启用。

### 3.2 插件执行隔离

若复现指向 JAR/SO/QuickJS native fault、不可中断执行或持续资源泄漏，实施 app 私有 `:spider` Service。此方向是防止插件故障带走 UI 的必要架构选项，不是对当前根因的预判。[Android 支持按组件指定进程](https://developer.android.com/guide/components/processes-and-threads)。独立进程不是独立 UID 的安全沙箱。

- 主进程持有源/片库/用户状态；worker 不启动主应用 NukaRuntime、AirPlay、控制端口或 UI。NukaCastApp 必须识别进程，防止 worker 重复初始化整套服务。
- 请求包括 operationId、sourceId、siteKey、配置/JAR/脚本指纹、generation、deadline、方法与参数。
- 返回正常结果或结构化错误；较大 JSON/媒体不要作为大 Binder transaction 传递，使用受控文件/FD/pipe，校验长度并明确释放责任。
- worker crash/timeout 经 binder death/超时处理只令当前调用失败；淘汰失效会话，限定重启次数并退避；只终止自己拥有且身份已验证的 worker，不能杀主进程。
- Spider proxy 也要纳入隔离与流传输设计，不能仅移动 search/home 而把 Init/proxy/JNI 留在主进程。

### 3.3 可控资源与会话

- API 19 初始目标：跨首页/搜索/详情的插件运行并发总预算 2；缓存活跃会话初始上限 8，采用 LRU/空闲释放。最终数值用实测调整，不以常量变化作为完成证据。
- session key 包含 sourceId/siteKey、解析后的绝对脚本/JAR URL、指纹与必要 ext；site proxy 索引同样包含来源身份。
- 移除、禁用或配置更新后，释放关联会话；迟到结果不覆盖新 generation。
- QuickJS 初始化失败须在其所属线程释放 module/runtime；脚本支持范围明确记录。脚本入口识别、HTTP(S)支持、模块解析不能只靠 `.js` 子串判断。
- 引擎中断/堆限制需核实当前 binding 是否暴露；[QuickJS 原生文档](https://bellard.org/quickjs/quickjs.html)提供 interrupt handler 和 memory limit，不能假设 Java binding 已可调用。缺失时采用受控 binding 扩展或 worker 超时终止。
- 模块数量、累计脚本/HTTP字节、结果 JSON、执行时间和排队长度都有边界；Future取消不等于执行已停止。

## 4. AirPlay 首帧与退出

### 4.1 接收器身份

同一 receiver 实例由一个 identity/capability snapshot 生成 mDNS 与 `/info`：名称、model、deviceID、public key、pi 等相关标识保持一致。公钥若随 native 实例重建变化，重新发布正确 TXT；设备标识可保持稳定。不能把私钥直接作为客户端常量。

将硬编码 plist 改为结构化生成并验证 bplist 类型和长度。对 features 与实际支持的配对/音视频能力做逐项审查，只宣告已实现且测试通过的能力。保留 Legacy AirPlay 范围，不承诺所有 iOS/AirPlay 2/DRM 内容都兼容。

### 4.2 解码状态

- 解码器与会话状态由同一渲染线程执行命令，native/UI线程仅提交事件；generation 隔离旧回调。
- 相同 SPS/PPS 不重建；真正变更重置解码器与对应 IDR，避免错误复用旧尺寸关键帧。
- Surface晚到或重建后，使用当前配置和最近有效 IDR 恢复，静止画面无需等待发送端再次送帧。
- 硬解已经收到有效配置/IDR并成功提交输入而长期无输出时，用时间门槛触发受限回退；24帧可作为加速证据，不能成为唯一必要条件。
- 候选 configure/start/运行期异常有受限重试与候选切换；不能无限重建同一个失败硬解。
- 软件解码失败或设备不支持 profile/尺寸时返回明确错误，展示恢复入口；只有支持的设备配置计入“首帧成功”验收。
- 记录真实第一帧输出、尝试过的解码器、输入/输出/丢帧、尺寸、配置变化和回退原因。CPU/帧率/音画同步作为后续性能测量，不能用“输出计数>0”宣称流畅。

### 4.3 生命周期

静止画面不因媒体静默退出。native明确结束、用户停止、连接失效与服务停止必须使状态一致；区分镜像会话与纯音频路径，验证每条 connect/disconnect 事件的来源。

退出后立即恢复可操作的电视 UI，迟到包不能重新打开旧会话；断开与释放应在工作线程有界完成，避免持锁调用 native join。接收器重新就绪可再次被发现并连接。网络变化时清理旧地址发布并恢复；不得无限“sessionActive=true”掩盖已经结束的控制连接。

## 5. 短剧目录与可播放 provider

### 5.1 已知接口与未知项

[参考站](https://vote.252035.xyz/)已观察到搜索与详情接口，其榜单来自 Nostr，已验证详情样本没有剧集或播放链接。此站不是可直接导入的 TVBox JSON 配置。

实施前必须记录：真实剧集接口、播放解析接口、请求/响应字段、鉴权方式、ID关系、分页、缓存期限、限流与失败结构。没有真实播放契约时，目录可以交付为部分成果，但“短剧解析接入完成”必须保持未完成。

### 5.2 接入边界

新增明确的 short-drama provider adapter，复用项目 HTTP栈和统一内容模型，避免把全站 HTML 塞入 WebView 或假装该站是 CMS。优先复用可观察 HTTP JSON API；若存在可用 TVBox source，则通过已有源模型接入并验证完整播放链路。

| 参考站字段 | 内部映射/约束 |
| --- | --- |
| `items[]` / `item` | adapter显式读取；原TVBox parser继续兼容原响应 |
| `id` | 全程 String，示例为19位数字；不得通过 JS Number/Java浮点数转换 |
| `title` / `cover` / `intro` / `remark` | name / poster / plot / remarks；允许缺封面 |
| `category` / `tags` / `status` | 保留题材和连载状态；另设 contentKind=`short_drama`，不强行用类别字符串猜短剧 |
| `episodeCount` | 仅元信息；选集由真实episodes响应生成 |
| `total` / `warning` | 展示已加载数及警告；API未验证分页时不得假装已支持 |

sourceId/providerId/dramaId/episodeId 标识必须稳定、带来源，剧目与播放 provider 可能使用不同 ID。跨源匹配必须有可验证映射或明确选择，不能仅按标题默默自动播放另一部剧。

provider接口至少支持 `search -> detail/episodes -> resolve -> PlaybackInfo`，可选 home/recommendations。解析结果提供 URL、headers、过期信息、错误码；播放链接过期可有限刷新并保留当前集。兼容嵌套JSON/缺扩展名地址须依据真实接口字段或Content-Type验证，不靠任意字符串猜直链。

初版不需要 Nostr 投票写入、账号私钥或默认多 relay 长连接。若实现榜单，作为独立可关闭的只读能力，限定事件数量/连接/回填；榜单不可用不影响搜索与播放。若未实现榜单，不以此阻塞已验证的目录/播放接入。

### 5.3 产品行为

用户能添加/启用/停用/删除短剧 provider，源删除不自动恢复。目录有数据但没有可用播放 provider时显示“暂无可用播放线路”，不伪造选集或进入黑屏播放器。

电视遥控器可进入短剧、搜索、详情、选集、返回；大量剧集用分组/分页或可控渲染，避免百集以上一次创建全部复杂视图。Web与电视共用来源身份和内容版本；收藏、继续观看精确保存 provider与集ID。

## 6. 验收与优化顺序

- P0：确定源闪退阶段/原因、增加关键观测、修首帧与退出；证据驱动最小修复。
- P1：插件进程/资源控制、API19 CI、短剧真实播放契约与适配。
- P2：异步日志、首页/海报/百集渲染、缓存和音画同步；依据 profiling 决定。

验收门槛、故障注入、设备矩阵与证据包见 plan。无需为了此次规划部署站点、发版或升级版本。
