# 直播回看 + 预约 · 施工计划

2026-10-08。先看 `docs/plans/2026-10-08-catchup-and-reminders-spec.md`。

## 一、回看（catch-up）

1. `app/src/main/java/com/nukacast/app/live/CatchupUrl.java`（新增，纯逻辑）
   - `boolean canCatchUp(Channel)`：`catchup` 非空，或 `catchupSource` 非空。
   - `String reason(Channel, EpgProgram, long now)`：不能回看时给出人话原因。
   - `String url(Channel, EpgProgram, long now)`：按 `catchup-source` 模板或 `catchup` 写法生成地址；
     超出 `catchupDays` 返回空。
   - 变量：`{utc}`/`{utcend}`/`{lutc}`/`{start}`/`{end}`/`{duration}`（`{start}`/`{end}` 用频道所在时区，
     默认 `Asia/Shanghai`，`{utc}` 用 UTC 秒）。
2. 单测 `CatchupUrlTest`：三种写法、模板变量、超天数、无能力原因。
3. 电视端：`MainActivity` 的节目单弹窗里，已播完的节目可点 → `playCatchup(channel, program)`：
   生成地址 → 走 `startLiveUrl` 同一条播放路径（但标记为回看，HUD 显示「回看」）；生成不出来就提示原因。
4. 调试接口：`/api/debug/catchup?source=&channel=&index=` 返回生成结果（给冒烟与排查用）。
5. 设备验证：fixture 清单声明 `catchup="append"`，节目单点已播完节目 → 玩家地址带 `?start=...&end=...`
   且真的在播；冒烟新增两项（无回看能力时给出原因、可回看时地址正确）。

## 二、预约（reminders）

6. `app/src/main/java/com/nukacast/app/live/LiveReminders.java`（新增）
   - `SharedPreferences "live_reminders"`，条目：频道名/频道 id/节目名/开始时间/提前秒数。
   - `add(...)`（去重、上限 20）、`remove(...)`、`active(now)`、`dueAt(now)`（到点且未触发）、
     `markFired(id)`、`prune(now)`（过期一天以上清理）。
   - 单测：去重、上限、到点判定（含跨天）、过期清理。
7. 电视端：节目单里未开播节目可「预约」/「取消预约」；`MainActivity` 每 20 秒检查 `dueAt`，
   到点自动切台 + Toast；HUD 提示。
8. 调试接口：`/api/debug/reminders`（列出/新增/删除），`/api/debug/reminders/fire` 用于立刻触发检查。
9. 设备验证：新增一个 20 秒后的预约，等它自动切台（截图/状态为证）；重启后预约仍在。

## 三、收尾

10. 冒烟 `tools/tv-smoke.mjs` 增加：回看地址、预约增删与触发。
11. `docs/plans/2026-10-07-tvbox-parity-record.md` 记录；`README.md` 若有功能清单则同步。
12. 全量回归：单测、lint、冒烟、界面巡检、CI。

## 顺序与时间盒

先 1–5（回看，最大缺口），再 6–9（预约），每步跑单测；设备验证放最后一起做（夹具服务器 + 模拟器）。
