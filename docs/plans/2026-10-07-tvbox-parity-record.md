# 对标 TVBox 的能力补齐：实施记录（2026-10-07 晚）

承接 [sources-and-ui](2026-10-07-sources-and-ui.md) 与 [device-fixes-plan](2026-10-07-device-fixes-plan.md)。
目标：把「对标 TVBox 与主流直播/投屏软件」欠的三块能力补上——投屏接收端、直播节目单、分类筛选与
片库页——并让每一项都有设备端实测证据。

## 一、DLNA 投屏接收端（v0.4.5 版之后新增）

此前只有 AirPlay 发射端（iOS 投到电视），安卓手机、Windows「投放到设备」和 BubbleUPnP 都投不进来。

| 组件 | 文件 | 职责 |
| --- | --- | --- |
| SSDP 发现 | `app/src/main/java/com/nukacast/app/dlna/DlnaSsdp.java` | 监听 239.255.255.250:1900，应答 M-SEARCH，并统计应答数供诊断 |
| 设备描述 | `dlna/DlnaDescription.java` | `/dlna/description.xml` 与 SCPD，control point 据此得知能做什么 |
| SOAP | `dlna/SoapMessage.java` | 解析 SOAPACTION 头与信封（含命名空间标签），构造响应与 UPnP 错误 |
| 播放器 | `dlna/DlnaRenderer.java` | AVTransport/RenderingControl 的状态机，接到 `PlayerController` |
| 服务 | `dlna/DlnaService.java` | 动作分发：SetAVTransportURI、Play、Pause、Stop、Seek、SetVolume、Get* |

设备端接口：`/dlna/description.xml`、`/dlna/control/{AVTransport,RenderingControl}`、
`/dlna/event`（SUBSCRIBE 返回 200 即可，无需推送事件）。诊断：`/api/debug/dlna`。

控制点脚本 `tools/dlna-cast.mjs`：

```bash
node tools/dlna-cast.mjs list                       # SSDP 发现（跨网段时用地址直连）
node tools/dlna-cast.mjs info  http://<tv>:9978
node tools/dlna-cast.mjs play  <m3u8> http://<tv>:9978
node tools/dlna-cast.mjs stop  http://<tv>:9978
```

实测（模拟器，`adb forward tcp:9978`）：`play` 一个 m3u8 后 `state=PLAYING pos=0:00:01`，位置持续推进；
`info` 读到 `NO_MEDIA_PRESENT / 0:00:00 / 无 uri`；Stop 后不再残留上一段的时长。

## 二、直播节目单（EPG）

播放列表几乎从不自带 `x-tvg-url`，所以电视上此前完全没有节目单。

- 名字归一化 `live/EpgChannelId.java`：`CCTV-13 (1080p)` → `CCTV-13` → `CCTV13`；`CCTV-1 综合` → `CCTV-1`/`CCTV1`。
  逐个候选名去查，命中即用（EPG 站点只认朴素名字）。
- 选节目 `live/EpgNow.java`：从节目单里挑「正在播出/接下来」，兼容 `20:00`、`2026-10-07 20:00`、
  带 `T` 的 ISO、`20261007200000`；识别占位节目单（服务对未知频道返回 24 条「精彩节目-暂未提供节目预告信息」）
  并当作「没有节目单」，不显示假节目名；标题里的 `--免费使用` 水印去掉。
- 默认模板 `http://epg.51zmt.top:8000/api/diyp/?ch={name}&date={date}`，播放列表自带 `epg` 时优先；
  也可为单个源设置自己的模板（`LiveSourceStore.setEpg`）。
- 电视直播页有独立的节目单行：聚焦频道即显示
  `第一剧场　正在播出 20:15 熟年(14)　|　接下来 21:01 熟年(15)`；节目单在专有线程拉取，
  迟到的结果按「源 + 频道」校验后丢弃。

实测（模拟器，设备侧网络）：`IPTV 综合 · CCTV-13 (1080p)` → 42 条，`正在播出 20:00 东方时空 | 接下来 21:00 新闻联播`；
`CCTV-17` → 28 条；`CCTV-4K 高清` → 0 条并显示「这个源没有提供节目单」（服务确实不认识它）。

## 三、分类筛选与片库页

- 站点**忽略** `&year=`/`&area=`/`&lang=`（实测某站三种组合都返回同样的 5330 条），所以筛选在设备侧做：
  `tvbox/BrowseFilter.java` 负责取值与匹配，`TvBoxContentService.browseFiltered` 翻页扫描并缓存扫描进度。
  地区匹配放宽（大陆/中国大陆/内地/China 视为同类），并排除 `中国香港`/`中国台湾`。
- 电视影视页的分类条给出 年份 / 地区 / 语言 三排 chip（全部 + 常用值），扫描中会提示进度；
  窄分类（站点声明「电影」却只有 1 条）会跳过换下一个同类目，选筛选时也一样处理。
- 片库页（电视端「收藏」）与 `/api/library` GET/POST、`/api/debug/library`、`/api/debug/favorite`
  让网页也能看/改同一份收藏与观看记录。

实测：`光速资源 · 动作片 · 2024` → 4 条 2024 年影片（截图）；`area=大陆` 修复后不再混入港台片。

## 四、播放设置

`player/PlaybackSettings.java`（SharedPreferences）三项：自动连播（默认开）、画质（自动/最高/最低）、
解码（自动/软件，复用 `DecoderPreference`）。电视设置页有两栏，网页「设备」页有同一张卡片，
接口 `GET/POST /api/settings`，未知设置名返回 400 与原因。

实测：逐项切换后回读确认；未知设置返回 `未知设置：nope`。
过程中修掉一个真实崩溃：`PlaybackSettings` 作为 Activity 字段初始化会早于 base context 挂载，启动即 NPE
（模拟器复现，改为惰性创建）。

## 五、调试面

新增 MCP 工具：`nukacast_epg`、`nukacast_live_catalog`、`nukacast_live_search`、`nukacast_settings`、
`nukacast_library`、`nukacast_favorite`、`nukacast_dlna`。对应设备端端点：`/api/debug/epg`、
`/api/debug/type`、`/api/debug/browseFilter`、`/api/debug/library`、`/api/debug/favorite`、`/api/debug/dlna`。

## 验证汇总

| 项 | 结果 |
| --- | --- |
| Android 单测 | 66 suites / 278 tests / 0 failures |
| lint | 0 error（12 warning，均为既有） |
| 网页 | 5 files / 16 tests；`check-web-tokens` 266 tokens 缺失 0 |
| 设备端 | 模拟器 API 19 x86：DLNA 投放、EPG、筛选、收藏、设置逐项实测通过 |
| 稳定性 | `tools/tv-soak.mjs` 连续轮次播放/搜索/浏览/直播，堆 ~38MB、RSS ~96MB、线程 61 保持平稳 |
| 设备冒烟 | `node tools/tv-smoke.mjs` → **22/22 通过**（含 DLNA 投放后真的在播、低码率流位置推进、首字母搜索、筛选、收藏、屏幕键盘、直播频道搜索） |
| 发布 | v0.5.0：`NukaCast-v0.5.0.apk` 10.97 MB，`versionCode 23`、`versionName 0.5.0`，sha256 `2289dc73…7a04cf` 与发布文件一致，签名证书与 v0.3.4 起完全相同（可直接覆盖安装）；`Android CI` 与 `Android Release` 全绿 |

## 六、直播页补齐（v0.5.0 之后）

### 节目单

播放列表几乎从不自带 `x-tvg-url`，所以此前电视上完全没有节目单。现在：

- 默认模板 `http://epg.51zmt.top:8000/api/diyp/?ch={name}&date={date}`（可用 `LiveSourceStore.setEpg` 按源覆盖）。
- 频道名先归一化（`EpgChannelId`）：`CCTV-13 (1080p)` → `CCTV-13` → `CCTV13`，逐个候选名去查，命中即用。
- `EpgNow` 选「正在播出 / 接下来」，兼容 `20:00`、`2026-10-07 20:00`、带 `T` 的 ISO 与 `20261007200000` 五种写法；
  识别占位节目单（实测：服务对未知频道一律返回 24 条「精彩节目-暂未提供节目预告信息」）并当作「没有节目单」，
  标题里的 `--免费使用` 水印去掉。
- 直播页有独立的节目单行（原先借用状态行，会被搜索标题/页码覆盖）：聚焦频道即显示
  `第一剧场　正在播出 20:15 熟年(14)　|　接下来 21:01 熟年(15)`；节目单在专有线程拉取，迟到的结果按源+频道校验后丢弃。
- MENU/INFO 键打开该频道全天节目表：`CCTV-13 (1080p) 节目单` + 时间段列表，当前正在播出的那一条标 ▶ 并自动滚动到它；
  没有节目单时只弹提示（对话框会抢焦点，把遥控器按键都吃掉，实测确认）。

### 遥控器

- 数字键跳台：列表态或在看直播时累积数字（1.5 秒内可继续输入，最多 4 位），停止输入后跳到当前列表第 N 个频道；
  越界显示「没有第 N 个频道（当前 M 个）」，观看时提示显示在画面上。
- 快进/快退（左右键、媒体键）后在画面中部显示 `快进 30 秒 0:34 / 10:34` 约 1.2 秒——此前按了没有任何反馈。
- 直播页分组行最前面是「常看 (N)」（`live/RecentChannels`，每源最多 12 个，按最近观看排序）。

### 播放与稳定性

- 直播失败不再显示点播的措辞（`片源地址无法访问…按返回键退出`）：直播有自己的恢复路径（自动换线并提示上/下键换台）。
- 「检测到上次崩溃」弹窗此前每次启动都弹、抢焦点并吞掉遥控器按键：新增 `diagnostics/CrashPrompt` 按崩溃签名比较，
  同一次崩溃只提示一次，文本仍保留在诊断包里。
- 频道聚焦回调原先用 `isFocused()`/`getWindowToken()` 过滤重建产生的旧事件，实测把正常聚焦事件也过滤掉了
  （走查频道时节目单行永远停在上一个频道）；改用列表重建计数器 `liveListGeneration` 精确识别。

### 调试面

`/api/debug/live` 增加 `state`（源、分组、是否搜索、可见频道数、当前聚焦频道、正在看的序号）；
`/api/debug/live?query=` 空值退出搜索态；节目单读取写入日志（`节目单结果：CCTV-13 (1080p) → 41 条`）；
MCP 工具 `nukacast_epg` / `nukacast_live_catalog` / `nukacast_live_search` / `nukacast_library` / `nukacast_settings`
等 21 个工具全部实测可用。

| 项 | 结果 |
| --- | --- |
| Android 单测 | 68 suites / 287 tests / 0 failures |
| lint | 0 error |
| 设备冒烟 | `node tools/tv-smoke.mjs` → **28/28 通过**（新增：按键快进/快退/暂停 3 项、数字键跳台、常看分组、全天节目单） |
| 稳定性 | `tools/tv-soak.mjs` 连续 25+ 轮：堆 11.8–38.7MB、RSS 58–94MB、线程 36–56，无增长趋势 |
| 连续两轮冒烟 | 28/28、28/28（无抖动） |

### 这一轮的设备实测问题与修复（tv-smoke 34 项）

| 现象（来自用户） | 实测到的原因 | 处理 | 证据 |
| --- | --- | --- | --- |
| 「更多地方不可见 / 有遮挡」 | 布局自检只比屏幕边界，看不到被父容器裁掉的内容 | `LayoutInspector` 增加裁切检测（滚动容器内部豁免） | 首页/影视/直播/投屏/设置/搜索 六页 + 详情弹窗 + 播放中 HUD 全部 0 问题 |
| 「有滚动问题」 | 未验证过遥控器能否走到折叠以下 | tv-smoke 用真实 adb 按键走 26 次下键 | 首页滚到 1920/1920、设置滚到 400/400 |
| 「有遮挡」的另一个来源：直播搜索 | 键盘 + 源/分组行 + 结果行挤爆 1080p，结果行只剩 1px | 开键盘时收起源/分组/节目单行 | 搜「湖南」→ 5 个结果完整可见（此前被挤到屏幕外） |
| 「主页源推荐放不了」 | 首页大推荐面板既不可聚焦也没有点击监听：看得见、选不中 | 面板改为可聚焦卡片 + 点击打开详情 | 聚焦 `hero:流浪地球3(上)（预告片）` → 按确定 → 详情弹窗 2 条线路 |
| 节目单「这个源没有提供节目单」误导 | 实测 2026-10-07 夜 `epg.51zmt.top:8000` 对任何频道返回 200 + 空响应，与「该频道没有节目单」无法区分 | 默认模板扩为 3 个镜像、30 分钟缓存（失败 2 分钟）、`EpgSchedule.error` 区分两者 | 直播页显示「直播中国：节目单服务暂时不可用」；服务正常时 CCTV-13 → 41 条 |
| 弹窗吞掉遥控器按键 | 弹窗持有输入焦点，注入按键到不了页面；详情弹窗还没登记 | 弹窗统一登记（含详情弹窗）、调试 BACK 先关弹窗、布局自检改读当前弹窗 | 详情页 36 集：线路 2 条 + 集数按钮 33 个、0 问题 |
| 调试包版本号说谎 | `app/build.gradle` 默认 versionName 仍是 0.3.8 / versionCode 18 | 默认值更新为 0.5.0 / 23（发布流程仍显式传入） | `dumpsys package` → `versionName=0.5.0-debug, versionCode=23` |
| CI 偶发「零用例」 | API 35 runner 无 KVM，模拟器启动慢导致 adb 连不上 | 等设备最长 420 秒并在 boot_completed 后确认 shell 可用；重试前 reconnect；最多 3 次（真实失败不重试） | 之后 3 个 job 连续全绿（含此前失败的 API 35） |

调试接口新增：`/api/debug/focus?target=hero|search|nav`（焦点移动在输入系统内完成，脚本注入的按键到不了，
需要一条把焦点放到待测控件上的路）、`/api/debug/live` 的 `state.firstChannels`/`channelCount`、
`/api/debug/live?query=` 空值退出搜索态、`/api/debug/epg` 的 `reason`、
`/api/debug/player/action?name=stop`（注入 BACK 到不了 `onBackPressed`，脚本需要一条退出播放的路）。

## 七、又一轮设备实测与修复（tv-smoke 34 项，CI 恢复全绿）

| 问题 | 实测原因 | 处理 |
| --- | --- | --- |
| CI 连续三天红（EpgNowTest 3 个用例） | 节目单里的 `20:00` 被当作「今天 20:00」解析，而节目单属于某一天；CI 在 UTC、本机在 UTC+8，日期差一天就错开（本机之前只是命中了 Gradle 缓存） | `EpgNow.parse` 增加日期上下文：`20:00` 锚定到节目单自身的日期，节目单没写日期才退回今天；新增两个用例（跨日期、无日期）并在 4 个时区下验证通过 |
| MCP 探测把能用的站点报成 TLS 失败 | `ProbeTool` 用 `HttpURLConnection`，API 19 的平台 TLS 根本协商不了 TLS 1.2（`SSL23_GET_SERVER_HELLO:unsupported protocol`），而应用自己的 OkHttp 客户端带 Conscrypt 走得好好的 | 探测改走 `HttpStack.client()`，结果里写明 `stack=okhttp+conscrypt`：同样的 8 个地址从「3 个失败」变成全部 `HTTP 200` |
| 诊断包说「本机不支持的站点：（无）」，首页日志却说跳过 53 个 | 首页跳过 JAR 插件站点时只计数、不记录原因（只有搜索路径记录） | 首页跳过时也记录（含系统版本）；导出包先按原因汇总数量再列站点：`共 53 个：需要 JAR 插件，Android 4.4.2（API 19）无法加载` |
| 「被外部结束」把装新版也当成崩溃 | 正常更新/重装会替换进程，与系统杀进程长得一样 | 记录安装包 `lastUpdateTime`，早于上次启动的更新标记为「被应用更新结束」，网页设备页区分显示 |
| 调试包版本号是 0.3.8 | `app/build.gradle` 默认 versionName 一直没跟着发布走（发布流程显式传参，调试包就用默认值） | 默认值更新为 0.5.0 / 23 |
| 看护工具 6 小时什么都没看 | `tv-watch` 对着旧地址轮询，890 次采样全是 timeout | 新增 `tools/tv-discover.mjs`（先试 adb 转发的模拟器，再 SSDP，最后扫 /24），`tv-watch` 连续 3 次失败即重新发现；判定只用设备独有的 `/api/debug/layout`（预览服务器也会答 `/api/status`，上一版就被它骗了） |
| 看护每次都报 crash | 应用会一直保留上一次崩溃记录，直到用户清除 | 只在崩溃记录*变化*时报告，并打印新记录首行 |

当前挂机验证：`tools/tv-soak.mjs 180` + `tools/tv-watch.mjs 127.0.0.1 19978 20` 同时运行（看护只读采样），
15 轮后堆 40–42MB、RSS 99–105MB、线程 64–67，无事件、无掉线。

## 八、播放器音轨与字幕（v0.5.0 之后一轮）

对标主流播放器时，播放器菜单此前只有「上一集／下一集／倍速／画面比例」：媒体里带的多音轨与字幕
根本用不到，而这正是 TVBox 与主流播放器的标配。

| 项 | 实现 |
| --- | --- |
| 音轨 | `PlayerController.audioTrackLabels()` / `selectAudioTrack(i)`；标签形如 `中文 · AAC 2声道`，选中项带勾 |
| 字幕 | `textTrackLabels()` / `selectTextTrack(i)`，`-1` 关闭字幕（`setTrackTypeDisabled`） |
| 字幕上屏 | 新增 `ui/SubtitleOverlay`：白字＋阴影、底部居中、最多 3 行、左右各内缩 60dp |
| 菜单 | 媒体有 2 条以上音轨才出现「音轨 xx」，有字幕轨才出现「字幕 xx」；循环切换后关闭（关闭排最后） |

### 踩过的三个坑（都是实测出来的，不是推断）

1. **字幕不能放在 HUD 里**：HUD 几秒后自动隐藏（`setVisibility(GONE)`），字幕会跟着一起消失。
   改为独立覆盖层 `SubtitleOverlay`，与 HUD 的显隐无关；截图证据 `.preview/tv-subtitle.png`
   （HUD 已隐藏，字幕仍在画面上）。
2. **轨道信息不能从 HTTP 线程读**：`player.getCurrentTracks()` 在非主线程抛
   `IllegalStateException: Player is accessed on the wrong thread`（`/api/debug/player/track` 直接 500）。
   改为在 `mirrorPlayerState()`（主线程）里把标签构造成列表，接口只读镜像值。
3. **HLS 字幕必须是播放列表**：把 `.vtt` 直接写进 `#EXT-X-MEDIA` 的 `URI` 会得到
   `ERROR_CODE_PARSING_MANIFEST_MALFORMED`（ExoPlayer 会去解析那份 `.vtt` 文本）。正确写法是
   `#EXT-X-MEDIA:TYPE=SUBTITLES,URI="subs-zh.m3u8"`，播放列表里再指向 `.vtt` 片段。

### 可复现验证（不再靠「看起来没问题」）

```
python tools/build-subtitle-fixture.py                        # 4 段 mux.dev 测试片段 + 两条 WebVTT 字幕组
python -m http.server 8899 --bind 0.0.0.0 --directory .fixture/hls
python tools/verify-subtitle-tracks.py                        # 9/9 通过，失败即非零退出
```

* 为何要自建测试流：公开测试流要么没有字幕、要么几十 MB；API 19 模拟器 `/sdcard` 只读，
  推不进设备，因此经 `10.0.2.2` 用 HTTP 提供（这也正是应用真实使用时的形态）。
* `tv-smoke.mjs` 扩到 **41 项**：新增「字幕上屏」「字幕可关闭」「轨道 API 契约」「非法类型返回 400」；
  测试流不可达时前两项记为跳过而不是假装通过。

| 检查 | 结果 |
| --- | --- |
| `tools/verify-subtitle-tracks.py` | 9/9（两条字幕轨识别、切换生效、关闭清空、字幕真的画在画面上） |
| `tools/tv-smoke.mjs` | 41/41 |
| Android 单测 | 71 suites / 301 用例全绿（新增 `PlayerControllerTracksTest`、`PlayerTrackMenuTest`） |
| lint | 0 错误 / 14 警告 |
| web | 5 文件 / 16 用例 |

顺带修掉两个设备实测暴露的问题：首页推荐卡偶发「选不中」（首页重建会替换面板，对已脱离的实例
`requestFocus` 静默失败 → 现在会重新定位当前面板，`tv-smoke` 也会在页面未就绪时重试），
以及直播源尚未加载完就进直播页导致的空指针（`/api/debug/live` 曾直接 500）。

## 2026-10-08 · 界面复查（用户反馈后的三轮）

用户反馈三件事：直播按钮一直像被按着、片单的「下一页」摆在中间很突兀、设置页太丑太啰嗦。
按遥控器实际走了一遍每一页，三件都改了，另外查出两个只有用遥控器走才会暴露的问题。

### 1. 直播按钮一直处于按下状态（真 bug）

侧栏各项共用同一个 XML `StateListDrawable`：第一个改变状态的项决定全部项的绘制，其余项不会被
重绘，所以从直播页返回后那一格仍留着填充底。现在每个项在代码里各拿一份自己的 drawable
（`giveOwnBackground`），实测切页后只有当前页那一项有底（`tv-pages.mjs` 的「选中」列）。

分清两种状态：**选中**（当前在哪一页，填充底）与**焦点**（遥控器在哪，白色描边）。

### 2. 片单分页

「下一页（第 N 页）」按钮从列表中间拿掉，也不再抢焦点：现在走到最后一张卡片就自动加载下一页
（主流播放器的做法），底部只留一行小字说明。短剧页的「更多」芯片同样去掉。

### 3. 设置页重排

改成一列一行的常规配置页：左边标题（必要时带当前值），右边动作按钮。原先两列小卡片里塞满了
说明整句，现在文字只剩必要信息（`开机自启已开：电视开机后…` → `已开启`）。

**顺带查出并修掉的两件事**（都是「只看截图看不出来」的）：

1. **设置页有一半按钮遥控器够不到**。两列布局下 `focusSearch(DOWN)` 只找正下方最近的一个：从
   「自动连播」往下会跳过片头/片尾、跳到下一行的刷新按钮，走到底（查看日志）就 `no-candidate`
   —— 15 个控件里有 7 个无法用下键到达。改成一列后逐个可达，实测顺序：
   自动连播 → 片头 → 片尾 → 画质 → 解码 → 刷新配置源 → 扫描片库 → 开机自启 → 切换为浅色 → 查看日志 → 下载地址。
2. **页面重建后光标会彻底丢失**，此时任何按键都没有反应（看着像死机）。现在按键前若发现没有焦点
   就先放回页面第一个控件（播放中不做，播放器自己处理按键）。

### 验证工具（这轮把「驱动设备」这件事做扎实）

* `tools/tv-pages.mjs`：按遥控器走 15 个界面（含弹层），每页截图 + 布局问题 + 焦点 + 选中/按下状态。
  实测 15 页 **0 布局问题**、焦点均落在合理控件、无按不起来的按钮。
* `/api/debug/close`：只关弹窗的接口——自动化里按 BACK「保险一下」会把应用关掉，之后每页都拍成黑屏
  （实测过一次 14 页全空）。
* `/api/debug/focus?target=page|down|up|right`：直接问 `focusSearch` 的结果，用来区分「下面没有候选」
  与「候选拒绝获得焦点」——上面第 1 条就是这么查出来的。
* 布局报告新增 `focusable`（哪些控件遥控器根本够不到）与「一行放不下」检查（三个按钮并排时第三个被
  容器裁掉，而旧的溢出检查看不见），配套单测 `LayoutInspectorRowTest`。
* `tools/verify-player-features.py`（原 `verify-subtitle-tracks.py`）扩到 **16 项**：字幕/音轨之外增加
  跳过片头片尾与本地存储播放（挂载 → 扫描 → 打开 → 真的播 `file://`），仍以 fixture 素材为准。

| 检查 | 结果 |
| --- | --- |
| `tools/tv-smoke.mjs` | 50/50（含「远端键：快进/快退/暂停」） |
| `tools/verify-player-features.py` | 16/16 |
| `tools/tv-pages.mjs` | 15 页 0 布局问题、0 按下态残留 |
| Android 单测 | 75 suites / 319 用例全绿 |
| lint | 0 错误 |

一处自己踩的坑记在这里：给「焦点丢失就放回页面」加的兜底一开始在播放中也会触发，把光标放到播放器
背后的控件上，于是遥控器快退与暂停全部失效（`tv-smoke` 两项变红）。现在播放中不做这件事——冒烟测试
就是为了抓这种回归而存在的。
