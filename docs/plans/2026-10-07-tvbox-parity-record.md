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
