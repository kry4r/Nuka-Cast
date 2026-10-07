# 实施记录 2：源聚合、短剧直连播放与网页改版（2026-10-07）

承接 [实施记录 1](2026-10-06-implementation-record.md)。本轮解决三件事：**自己找源并集成**、**短剧真正能播**、**网页不再“一般”**。所有结论都标出证据来源，未验证的一律写 pending。

## 1. 源集成

### 1.1 内置推荐源清单

新增 `app/src/main/assets/sources/recommended.json`（16 条，`verifiedAt: 2026-10-07`），三种类型：

| 类型 | 条数 | 说明 |
| --- | --- | --- |
| `live` | 7 | IPTV m3u/txt 清单：bestK（540 频道，jsdelivr + gh-proxy 双镜像）、Guovin 精选（473）、YanG 精选（123）、iptv-org 中国频道（146）、best-fan 中国电视（203）、suxuang 典藏版（1273） |
| `vod` | 5 | TVBox 仓库：PyramidStore 单仓（23 站点）、noimank 多仓（14 子仓）、小盒子多仓、拾光仓库（storeHouse 7）、无邪多仓（storeHouse 3） |
| `drama` | 4 | 短剧：红果短剧榜（资料目录）、非凡资源·短剧（19874 部，分类 36，直连 m3u8）、暴风资源·短剧大全（12183 部，分类 58）、无金资源·短剧（1081 部，分类 41） |

设计要点：

- **内置清单是权威，但检测结果来自用户网络。** “检测”按钮用 APP 自己的 OkHttp（同一 UA、同一 TLS 栈）重新请求一次，并按类型校验响应形状：直播必须是能解析出频道的 m3u/txt；CMS 必须返回 `list`；配置必须有 `sites`/`urls`/`storeHouse`/`lives`；资料目录必须用 `/api/search?q=…` 真搜一次（首页是 HTML，探测首页等于没测）。
- **添加必须经过真正拥有它的存储**：直播 → `LiveSourceStore`，短剧 → `DramaCatalogRegistry`，点播 → `SourceStore`，因此“已添加”状态与各页真实状态永远一致，失败会带原因上报而不是静默成功。
- **不静默恢复**：删除后不会自动回来；`recommended.json` 里没有的源不会被凭空加入。

### 1.2 维护工具

```bash
node tools/check-recommended-sources.mjs            # 全部重测
node tools/check-recommended-sources.mjs drama-ffzy # 只测指定 id
```

2026-10-07 本机实测：`16/16 reachable`。清单里那条 GitHub raw 直连镜像（`vod-pyramid-raw`）在本机无法访问，已从清单删除——**清单里的每一条都必须是测过的**，不能靠“用户那边应该能通”。

### 1.3 直播源管理

- 新增 `LiveSourceStore`（最多 32 条，可启用/停用/删除，记录错误）。用户可以直接添加任意公开 IPTV 清单，不必先拼一个 TVBox 配置。
- `/api/live/sources`（列表/新增/删除/启用）与网页“直播 → 直播源管理”面板；TVBox 配置里的直播源仍照常出现，`user` 字段区分来源。

## 2. 短剧从“能找到”到“能播”

之前短剧只有资料目录，播放必须先去 TVBox 片源里匹配同名条目。现在多了一条**直连通路**：

- `DramaProviderConfig.kind` 区分三类目录：`vote.catalog`（资料）、`cms.drama`（CMS 直连）、`web.drama`（预留）。
- `CmsDramaCatalog`：走 MACCMS `api.php/provide/vod`，固定只取短剧分类（避免误取其它分类），带 `page`/`pagecount` 分页浏览；`vod_play_url` 解析规则：
  - 多条播放线用 `$$$` 分隔，**取集数最多的那条**；
  - 集内 `名称$地址` 用 `#` 分隔；
  - 只有本身指向媒体文件的地址才算可播（`DramaPlaybackUrls.isDirectMediaUrl`），`?url=` 包装的解析页一律丢弃，避免把黑屏当成功。
- `DramaService.play(providerId, dramaId, index)` 返回 `DramaPlayResult`（URL + 标题 + 参考头），格式不支持时抛 `play_not_found` 并提示改用播放线路。
- 播放器复用：直连 URL 交给既有 `PlayerController`，收藏/续播用 `drama:<providerId>` 作为 sourceId；CMS 的缩略图/简介/标签照旧展示。
- 网页：短剧详情页有“剧集”网格，点一下就在电视上播放；同时保留“用片源线路播放”作为兜底。
- 电视端：`MainActivity` 直连目录直接弹出集数选择（可切换“用片源线路播放”），不再强制先匹配同名条目。

**仍未验证**：D03（两部剧首/中/末集的真机播放）需要你的电视上实际操作一次；“短剧直连”链路目前只有单元测试与真实接口探针的证据。

## 3. 网页改版

改版前后的判断依据不是“我觉得好看”，而是把页面截图出来逐页看。为此新增了可复现的预览手段（见第 4 节）。

- **总览**从“只有 AirPlay 卡片 + 三个数字”变成控制台：AirPlay 状态卡（含解码器、输入/输出、软硬解标记）、播放器卡、控制地址/片源/当前播放三格、四个快捷入口、最近动态（实时日志 6 条，带分级圆点）。
- **短剧页**：推荐源货架（带检测状态与延迟）、目录管理（增删启停、类型徽标）、来源 chip 切换、搜索 + 分类浏览（分页）、卡片网格（封面失败自动降级为图标，避免破图）、详情对话框（海报/标签/简介/剧集网格/相关推荐）。
- **直播页**：频道筛选、`auto-fill` 网格（不再被固定列数切碎）、多地址频道才显示地址数。
- **设备页**：`SectionCard` 结构 + 双列布局，阶段诊断单独一卡并高亮失败项，闪退记录独立成卡。
- **日志页**：级别 chip + 错误/警告计数、按时间倒序、调用栈折叠。
- **统一基础件**：`web/src/components/ui/primitives.tsx`（PageHeader / SectionCard / EmptyState / Skeleton / StatusDot）、`web/src/components/source-shelf.tsx`（推荐源货架）、`web/src/components/view-boundary.tsx`（渲染错误不再白屏，而是显示哪一页失败并给出重试）。
- 短剧/直播请求加了 latest-request gate：快速切换目录时旧响应不会覆盖新结果。

## 4. 验证

```text
JDK 18.0.2.1 / SDK 35 / NDK 20.1.5948944
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
  -> 43 suites / 168 tests / 0 failures / 0 errors
  -> lint 0 error / 5 warning（修改前 8）
.\gradlew.bat :app:compileDebugAndroidTestJavaWithJavac  -> BUILD SUCCESSFUL
web: npm test -- --run  -> 5 files / 16 tests
web: npm run build      -> tsc -b + vite build 成功
node tools/check-web-tokens.mjs -> 257 个 className token，缺失 0
node tools/check-recommended-sources.mjs -> 16/16 reachable
python tools/preview-server.mjs 9978 + Chrome headless 截图 -> 各页渲染确认（总览/短剧/直播/源管理/设备/日志）
```

新增单测：`CmsDramaCatalogTest`（7）、`DramaPlayResultTest`（6）、`RecommendedSourcesTest`（8）；覆盖多播放线取最长、解析页链接被拒、19 位 id 当字符串、直连目录/资料目录的区分、目录搜索 URL 生成。

## 5. 发布

| 版本 | 内容 | 产物复核 |
| --- | --- | --- |
| v0.3.6 | 推荐源一键添加、直播源管理、短剧直连播放、网页改版 | `NukaCast-v0.3.6.apk` 10.26 MB，`Android CI` 三个 job 全绿 |
| v0.3.7 | 短剧详情弹层改为按内容自适应高度；随包带上预览工具与 pid 文件 | `NukaCast-v0.3.7.apk` 10.26 MB，`versionCode 15`、`versionName 0.3.7`、签名与 v0.3.4/0.3.5 相同（可直接覆盖安装）、仅 `arm64-v8a`+`armeabi-v7a` |

v0.3.7 下载后复核：APK 内 `assets/sources/recommended.json` 与仓库文件 sha256 完全一致；内置网页 bundle 含“推荐短剧源 / 直播源管理 / 阶段诊断”等界面文案。

## 6. 仍未完成

1. **真机验收**：短剧直连播放（D03）、AirPlay 首帧（A01/A05）、API19 类加载以外的真实闪退根因。
2. **插件独立进程（T2）**：`RefreshSafely` 已能兜住 `LinkageError`，但插件仍与应用同进程，内存/中断隔离未做。
3. **`web.drama` 网页解析目录**：已接通接口与 UI，但尚无可用站点实测，因此没有内置任何 `web.drama` 条目。
4. 推荐源里的“网页解析”类站点（如基于 `player_aaaa` 的短剧站）在本机能访问，但同类站点经常改版且部分地区被墙，所以只保留 CMS 直连源作为默认，避免把易碎解析写进发布包。

## 7. 本地预览工具

```bash
node tools/preview-server.mjs 9978     # 用示例数据提供全部 /api，无需电视
cd web && npm run dev                  # 指向该服务，即可用浏览器查看真实界面
```

预览服务的数据明确是示例（`tools/preview-server.mjs`），只用于界面开发与截图，不参与发布产物。
