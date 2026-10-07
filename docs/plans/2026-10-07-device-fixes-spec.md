# 真机问题修复：规格（2026-10-07）

面向 **SHARP LCD-xxBEL6A_B · Android 4.4.2 / API 19 · 1.5GB RAM**，所有条目都来自真机证据
（`/api/debug/*`、诊断包、用户截图），不是推测。

## 1. 证据

| 编号 | 证据 | 来源 |
| --- | --- | --- |
| E1 | `java.lang.NoSuchMethodError: HttpDataSource$InvalidResponseCodeException.<init>` at `ext.okhttp.OkHttpDataSource.open(324)`，线程 `ExoPlayer:Loader:DefaultHlsPlaylistTracker:MultivariantPlaylist` | 真机 `javaCrash` + 用户截图 |
| E2 | 源 `FISH` → `HTTP 401：该配置需要授权`（86 站点全废）；源 `XHZ` → 139 站点中 130 个是插件（type=3） | 真机 `/api/diagnostics`、`/api/debug/sites` |
| E3 | 插件站点阶段记录：`failed to connect to rihou.vip … (port 55) after 8000ms`、`Spider JAR HTTP 403`、`getaddrinfo failed EAI_NODATA` | 真机 `stages` |
| E4 | 首页警告：光速/量子/爱坤/熊掌/小胡 = `首页请求超时`；红牛/爱坤 = `ECONNRESET` | 用户贴出的启动诊断 |
| E5 | `SocketTimeoutException: failed to connect to /119.91.123.253 (port 2345) after 8000ms` at `TvBoxContentService.resolveWithConfiguredParsers` → 播放直接失败 | 用户贴出的堆栈 |
| E6 | 非凡/电影天堂短剧剧集地址是 `https://vip.ffzy-play9.com/share/<hash>`（HTML），页面内 `const url = "/20260918/…/index.m3u8?sign=…"`；实测该 m3u8 返回 200 `application/vnd.apple.mpegurl` | 电脑侧 curl + 节点脚本 |
| E7 | `?ac=detail&wd=LLDQ` → 0 条；`wd=流浪地球` → 4~5 条 | 电脑侧 curl（电影天堂、光速） |
| E8 | 空闲时：RSS 53MB / Java 堆 10MB（上限 256MB）/ 线程 39 / 插件会话 0 | 真机 `/api/debug/snapshot` |
| E9 | 两次运行在 39s / 50s 后消失，Java 堆仅 1%，启动 0.4s 即报"内存临界" | 上一轮诊断包 |

## 2. 目标行为（可验收）

### G1 播放器绝不能被 HTTP 错误打死
- 播放数据源只允许 core 自带实现；`extension-okhttp` 一类与 core 版本不一致的扩展禁止引入。
- 403/404/超时 → 抛**可捕获**的播放错误 → 界面提示（含状态码），进程存活。
- 验收：插桩测试在设备上对 403 断言 `InvalidResponseCodeException` 且不得是 `NoSuchMethodError`。

### G2 播放地址解析必须容错
- 配置里的"解析器/嗅探接口"不可达或超时 → **不得**让整次解析失败；保留原始地址继续尝试播放。
- `share/play/*.html` 形态地址 → 抓页面取 m3u8；失败 → 错误码 `play_not_found` + 文案"该线路返回的是播放页，未解析出可直接播放的地址"。
- 验收：单测（解析器失败的降级、share 页三种脚本写法）+ 模拟器实放一次点播/短剧。

### G3 源必须"要么可用要么走开"
- 源配置拉取失败（401/403/404/超时）→ 该源自动停用，UI 标注原因，不参与首页/搜索；保留一键删除与"修复后重试"。
- 启动诊断只列真正影响使用的问题，并给出可执行建议（换源/删除/重试）。
- 验收：真机 FISH 源显示为"已停用（HTTP 401）"，首页与搜索不再出现它的站点。

### G4 首页必须出画面
- 首页只用体检通过（`SiteHealthStore`）的站点；没有体检数据时优先 CMS 站点，插件最多 2 个。
- 首屏 5 秒内必须渲染：先显示已到的结果，未到的站点显示进度，不再"转圈到超时"。
- 全部失败时空态写明原因（无可用站点 / 全部超时），并给出"去源管理体检"的入口。
- 验收：在慢站点的真实网络上，首页 5 秒内至少渲染出一屏或明确文案。

### G5 搜索可用
- 首字母可搜：本地索引（首页/搜索结果/短剧目录见过的标题）按拼音首字母匹配，命中后自动用中文标题重搜。
- 线程池被占满（插件不可中断）时跳过插件站点，并写入日志与结果说明。
- 结果与每站点失败原因并列；空结果必须能解释（附前 5 条失败原因）。
- 验收：模拟器上输入 `LLDQ`（先浏览过首页）能搜到《流浪地球》。

### G6 体检可用
- 一键体检（CMS 优先、可停止、显示进度），结果持久化：可用 6 小时、不可用 30 分钟。
- 体检结果立即影响首页与搜索。
- 验收：真机一键体检后，首页只使用可用站点。

### G7 崩溃可取证、可本地复现
- 两条取证路径：应用内（`lastRun` 采样含 RSS/线程/oom_adj/插件会话 + `postMortem` + `javaCrash`）与外部（`tools/nukacast-watch.mjs` 轮询，进程死时仍有记录）。
- 本地有 **同版本系统**（API 19 x86）模拟器，可直接装 debug APK，用 `adb forward` 把设备端口映射出来，用同一套 `/api/debug/*` 与 MCP 调试。
- 验收：模拟器可复现一次搜索/播放流程；真机崩溃后诊断包含"被系统结束"或 Java 堆栈。

## 3. 不做（明确划界）

- 不引入新的播放器内核（换 IJK/VLC 是另一个项目级决定）。
- 不内置任何需要授权或已失效的源；不放插件 JAR（`starter.json` 全是纯 JSON 接口站点）。
- 不做跨进程插件隔离（既有的 `LinkageError` 兜底 + 站点黑名单 + 内存预算已覆盖当前症状）。
- 不改动 AirPlay 接收逻辑（本轮证据里没有它的故障）。
