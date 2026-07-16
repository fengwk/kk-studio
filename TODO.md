# T15 Harness Session command/query API — TODO

## 第二小阶段

- [ ] SSE 事件流：`GET /api/sessions/{id}/events/stream`
- [ ] Activity / Task / Artifact 三块控制器
- [ ] Legacy `agent_session` / `agent_session_event` / `agent_run` 删除与数据迁移
- [ ] WebSocket / Environment 切换
- [ ] Worker / runtime resolver 重写

## 第一小阶段补强

- [ ] 单元测试覆盖 `HarnessSessionCommandService`（root snapshot / unknown agent / submit success / leaf 冲突 / active-run 冲突）
- [ ] 集成测试覆盖 `HarnessRunQueryService`（runs 排序）
- [ ] 集成测试覆盖 `StudioHarnessSessionController`（6 端点 + 字符串 ID 解析 + 非法 ID）
- [ ] `mvn verify` 通过 Checkstyle + Spotless GJF
