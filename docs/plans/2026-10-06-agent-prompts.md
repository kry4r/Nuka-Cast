# 后续 Agent 启动 Prompt

可以只用“总任务”交给一个新agent，也可以按下面分工分别启动。当前会话只交付文档，没有启动子agent或改功能代码。

## 1. 总任务：复制整段给新 Agent

```text
你正在 C:\Softwares\code\Nuka-Cast 工作。请按以下文档完成证据驱动的排查与实施，持续推进可独立完成的任务；不要只给另一份泛泛计划：
1. docs/plans/2026-10-06-compatibility-audit.md
2. docs/plans/2026-10-06-compatibility-spec.md
3. docs/plans/2026-10-06-compatibility-plan.md

用户问题：SHARP电视Android4.4解析源时闪退；iOS可以连接但黑屏。
故障源：http://肥猫.net/tv；https://d.kstore.dev/download/9280/wex.json；http://xhztv.top/4k.json。
新增需求：参照 https://vote.252035.xyz/ 加入短剧解析源，最终要有真实剧集与播放链路。
退出后的具体异常、已安装APK、电视型号/ABI/内存和iOS版本尚未确认，请通过可用设备/日志采集，不复制历史版本记录作为当前证据。

先读本地AGENTS.md（若存在）、检查git status、版本/分支与文档基线；保护用户改动。基线文档对应commit 78244dca44380b15cd9265f05904c040e4508664，实际代码变化后重新核实发现。
本轮基线：87 Android JVM tests、7 Web tests、TypeScript和Lint通过（8 warnings）；没有目标设备，未assemble/设备验证。不能据此称Android4.4或投屏正常。

按T0/T1/T3/T4先恢复稳定性与首帧，再根据证据做T2插件隔离/资源控制；T5短剧接口调查可提前，T6功能接入在真实契约确定后实施，最后T7集成验收。
重点：HttpStack静态Conscrypt初始化Error边界；JAR/QuickJS同进程native故障和不可中断超时；API19 Dalvik/ARM SO；配置与自动首页分阶段；同source/site身份；AirPlay固定/info与动态mDNS身份、公钥不一致；低于24帧时无输出fallback；Surface/IDR恢复与退出竞态。
保留已经修复的TLS1.2、SPS/PPS去重、静止会话存活、API19 legacy buffers，写回归而非重复重写。

短剧参考站已观察到/api/search?q=和/api/drama?id=，返回items/item目录信息；本轮样本没有episodes或播放URL，首页榜单使用Nostr。它不是TVBox配置。调查真实播放provider，不猜/api/play，不把投票页面/封面当直链，不自动投票。剧ID为19位字符串，不能转换成JS Number。可交付目录部分，但没有真实播放链路不能把短剧解析任务标成完成。

保持minSdk17、API19 ARMv7为主要验收目标，保留API35回归。不要直接升级所有依赖、trust-all TLS、catch(Throwable)吞异常、仅增大heap/timeout或用空结果掩盖错误。
需要破坏fixture时用合成数据与受控worker；保留故障数据，不默认清除用户源/缓存。无设备则继续fixture、代码/CI和报告，并明确真机pending；不伪造已复现或已修复。
为每个有风险的修复给出复现/证据、最小改动、行为测试和实际结果。实施完跑相关测试、全量必要检查与git diff --check。
最终交付：改动/根因证据、测试pass/fail/pending表、日志与APK/commit指纹、剩余限制、复现与设备验收命令；不要未经要求发布Release或推送标签。
```

## 2. 源与插件 Agent（T0/T1/T2）

```text
在Nuka-Cast中负责SHARP Android4.4源闪退，先阅读2026-10-06的audit/spec/plan。
源：http://肥猫.net/tv、https://d.kstore.dev/download/9280/wex.json、http://xhztv.top/4k.json。
先证明失败处于配置下载/解码、Conscrypt加载、Dex/JAR Init、SO/QuickJS、首页/搜索、详情/播放还是WebView。为每阶段补operationId/sourceId/siteKey/rootCause与摘要，收集最早Dalvik/linker/native/ANR原因。
肥猫本轮配置39个type=3站，不能把导入成功当源可用；xhztv严格JSON检查失败但Gson lenient尚待实际验证；kstore本机ECONNRESET不是电视根因证明。
检查静态TLS Error边界、第三方自建线程/原生崩溃、初始化失败资源释放、Future取消不终止执行、锁内网络初始化、跨来源session/proxy身份和删除后的旧结果。
按spec在需要时实现:spider私有进程与有界IPC/代理流；防止worker启动完整NukaRuntime。限制总并发、模块/结果/内存与会话LRU，证明worker崩溃/超时后主UI仍活且健康请求成功。
与AirPlay/Web agent先协调共享diagnostics字段，主要修改spider/net/tvbox及必要Manifest/worker启动。保留TLS校验和minSdk17，不以吞异常或扩大heap解决。
运行S01～S10相关测试；无真机明确pending。交付最早根因证据、补丁、测试结果与残留风险；不发版。
```

## 3. AirPlay Agent（T3）

```text
在Nuka-Cast中负责iOS连接后黑屏，先读2026-10-06的audit/spec/plan，执行T3和A01～A08。
确认mDNS与/info使用同一identity/capability snapshot。当前原生/info固定AppleTV2,1/AppleTV/aa:54:01:af:c3:c1/固定pk，而mDNS是AppleTV3,2/NukaCast/动态ID与当前pk；features数值两处相同，不要误报features不一致。
以sessionId/generation追踪pair/fairplay/setup、mirror、配置、IDR、Surface、decoder输入/输出。连接UI成功不证明媒体握手完整。
重点修复/验证：1～23个有效输入后静止的硬解无输出不能永远等24帧；硬解异常有界候选切换；配置/SPS/PPS去重；Surface晚到/重建重送配置+IDR；native/UI跨线程flush和退出旧回调；停止/释放避免主线程或持锁join阻塞。
保留已有静止native会话修复及API<21 legacy buffers。验证用户/iPhone停止、电视返回、网页断开、断网重连后UI可恢复，不把用户含混的退出描述当已确认事实。
主要修改airplay、cpp接收器及必要MainActivity联动，与集成者协调AppState/ControlServer/Web字段。测试动态bplist字段与低帧数时间门槛，不只测helper。
无SHARP/iPhone不能宣称首帧修好；交付候选补丁、数据/状态测试、真机诊断步骤和pending记录，不升级整个协议栈或发版。
```

## 4. 测试与 CI Agent（T4/T7）

```text
在Nuka-Cast中负责API19兼容测试与集成证据，阅读2026-10-06的audit/spec/plan。
当前CI只有API35 x86_64；SmokeTest仅断言packageName。新增API19 x86运行环境（includeLegacyTestAbi），真实启动Activity、导航、配置解析、TLS/JmDNS/WebView加载的仪器测试，保留API35回归（includeTestAbi）。
ARM-only第三方SO不得拿x86失败当ARM结论；提供ARM电视采集与验收脚本，检查生产APK ABI/minSdk/签名/资源，不混入测试ABI。
用合成fixture覆盖LinkageError、VerifyError、timeout/不可中断worker、坏配置和坏mirror包；负责验证真正行为，不只复制实现的helper断言。
按S/A/D矩阵建立结果表，记录同一APK SHA-256、commit、设备/API/ABI/iOS、复现时间与日志，收集Java/native/ANR/LMK证据；没有设备明确pending。
主要负责androidTest/test/CI/脚本/验收报告，先与功能agent确认契约。相关检查通过后不反复跑无新增价值的全量测试；不得用CI绿灯替代SHARP首帧/ARM插件验收，不发版。
```

## 5. 短剧 Agent（T5/T6）

```text
在Nuka-Cast中负责参照 https://vote.252035.xyz/ 接入短剧解析源，先读2026-10-06的audit/spec/plan，执行T5/T6与D01～D06。
已知参考站是“红果短剧·投票推荐榜”，app.js使用/api/search?q=、/api/drama?id=、/api/related和/api/poster；首页榜单通过Nostr relay聚合。已读样本search返回{ok,items,total,warning}，detail返回{ok,item}，包含id/title/cover/intro/episodeCount/category等，未提供episodes或播放URL。
先调查实际剧集和播放provider契约、来源/ID对应、headers/token/过期/分页/限流并写可复核记录；不猜/api/play、不执行投票或发布事件、不把站点HTML当TVBox JSON。找不到真实播放契约时仍完成可独立的mapper/契约与目录工作，并明确解析部分未完成。
为目录/播放建立明确provider adapter，复用HttpStack与稳定source/provider/drama/episode标识；19位id全程字符串。目录item/items适配不可破坏原TVBox list/data行为；不得根据episodeCount造选集或按标题自动匹配错误剧。
复用影视页短剧过滤/内容行、详情/选集/播放、源管理、收藏/续播；初版不需要Nostr投票与默认多relay长连接。没有播放线路时显示暂无线路，不进入黑屏播放器；新增可选provider可禁用/删除且不自动恢复。
验证至少两部短剧各首/中/末集的完整真实解析播放，过期刷新与headers传递；API19资源预算与百集遥控器体验。无设备记pending，目录成功不算可播放解析完成。
主要修改adapter/模型及必要Web/电视接入，与源agent/集成者协调source身份、内容版本和共享API。不默认升级依赖、提高minSdk或重写整个UI，不发版。
```
