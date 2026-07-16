# T15 Harness Session command/query API — 第一小阶段 NOTES

## 范围（第一小阶段已落地）

| 域 | 落地点 |
| --- | --- |
| share | `HarnessSessionDTO` / `HarnessSessionCreateDTO` / `HarnessSessionMessageCreateDTO` / `HarnessSessionEntryDTO` / `HarnessRunDTO` |
| core | `HarnessIds`（严格 long 解析）、`HarnessAgentSnapshotResolver`（canonical snapshot 解析，替代 `DatabaseTaskRuntime` 内联实现）、`HarnessRunMapper.listBySessionOrderByCreateTimeAsc`、`HarnessSessionCommandService` / `HarnessSessionQueryService` / `HarnessRunQueryService` |
| web | `StudioHarnessSessionController`（`/api/sessions`）、`StudioHarnessRunController`（`/api/runs/{id}` + `/api/sessions/{id}/runs`） |

## 关键约束

- 所有 ID 在 API 边界为 String，进入 core 后必须经 `HarnessIds.parsePositive` 严格 parseLong（正整数），非法 → `IllegalArgumentException`（HTTP 400）。
- 创建 Root Session 在同一事务内写入 `harness_session` + 唯一 `AGENT_SNAPSHOT` entry（满足"HarnessSessionDTO 返回时 leaf 已指向 snapshot"）。
- submit 直接走 `HarnessRunTransactionService.submitUserMessage`，保留 activeRun/leaf CAS 语义。
- entries 按 `id ASC` 排序（`HarnessSessionEntryMapper.listBySession`）。
- runs 按 `gmt_create ASC, id ASC` 排序（`HarnessRunMapper.listBySessionOrderByCreateTimeAsc`）。

## 显式不做（留给后续小阶段）

- SSE / 事件流
- Activity / Task / Artifact
- Legacy `agent_session` / `agent_session_event` / `agent_run` 删除与迁移
- WebSocket / Environment
- Worker / runtime resolver
