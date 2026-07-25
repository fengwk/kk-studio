# E2E 回归

本文描述 `kk-studio` 当前生效的端到端自动化回归：矩阵分层、入口、报告目录、用例清单与维护方式。

> 运行时依赖 **空 PostgreSQL 数据库**（`schema-postgresql.sql` + `data-e2e-postgresql.sql`）。不再使用 H2/MySQL 作为 durable 存储；启动 e2e 前需提供可写空库（可用环境变量 `KK_STUDIO_DB_URL` / `KK_STUDIO_DB_USER` / `KK_STUDIO_DB_PASSWORD`）。Schema 初始化会对已存在对象 fail-fast，不会掩盖结构漂移。

## 边界

- **已覆盖**：API 契约、资源 CRUD、Model/Agent 配置校验矩阵、Chat/Session/Thread 编排、usage 语义；可选真模型/分支/tool。
- **未覆盖（默认）**：浏览器点击 UI、Canvas/ComfyUI 全流程、配置笛卡尔全组合穷举、SSE 多 Pane 视觉。
- 真模型与 tool/branch 默认关闭，需显式参数。

## 入口

```bash
./scripts/e2e.sh                         # 默认完整 L1 矩阵（免费）
./scripts/e2e.sh --rebuild               # 重打包并重启服务
./scripts/e2e.sh --real                  # + 真 MiniMax 文本
./scripts/e2e.sh --real --with-branch
./scripts/e2e.sh --real --with-tools
./scripts/e2e.sh --ui                 # + Playwright UI smoke（截图进报告）
./scripts/e2e.sh --list
./scripts/e2e.sh --docs

npm --prefix frontend run e2e
npm --prefix frontend run e2e:ui
npm --prefix frontend run e2e:matrix
npm --prefix frontend run e2e:list
```

## 实现结构

| 路径 | 职责 |
| --- | --- |
| `scripts/e2e.sh` | 环境准备 + 调用矩阵 |
| `scripts/e2e/lib.sh` | backend/frontend/daemon 启停、credential 注入 |
| `scripts/e2e/run-matrix.mjs` | Node API 矩阵 runner、报告输出 |
| `scripts/e2e/ui-smoke.mjs` | Playwright UI smoke（L5） |
| `scripts/e2e/lib/http.mjs` | fetch/断言 |
| `scripts/e2e/lib/fixtures.mjs` | 合法体与配置矩阵行 |
| `scripts/e2e/lib/registry.mjs` | case 注册表 |
| `scripts/e2e/cases/*.mjs` | API 用例 |
| `reports/e2e/` | 报告与产物（gitignore） |

## 报告

```text
reports/e2e/<runId>/
  report.md
  summary.json
  cases/<id>.json
  artifacts/<id>/
  logs/
reports/e2e/latest/report.md
reports/e2e/LATEST_RUN.txt
```

判读顺序：`latest/report.md` → `summary.json` → `cases/` / `artifacts/` → `logs/`。

## 矩阵分层

| 层级 | 开关 | 成本 | 覆盖 |
| --- | --- | --- | --- |
| L1 | 默认 | 免费 | seed 契约、CRUD、配置校验、编排、usage 404、proxy |
| L2 | `--real` | 真模型 | 文本轮次 + usage 入账 |
| L3 | `--real --with-branch` | 真模型 | 分支路径 usage |
| L4 | `--with-tools` / `--real --with-tools` | daemon/真模型 | Environment READY、tool invocation |
| L5 | `--ui` | 本地浏览器 | 页面可达、列表渲染、打开新建模态、无致命 pageerror；截图入报告 |

API 矩阵注册 **46** 条（以 `./scripts/e2e.sh --list` 为准）。默认执行全部免费 L1（**42** 条）。`--ui` 额外 **13** 条 UI smoke（`--real` 时再 +1 真实首发）。

## L1 用例清单

### Seed / 契约 / 编排

| Case | 断言 |
| --- | --- |
| `seed.structured_model_config` | 公开 `config`，禁止 `configJson/capabilitiesJson` |
| `seed.agent_and_provider` | seed agent；provider.configured |
| `chat.session.main_thread` | chat/session attach + main thread |
| `thread.blank_first_send_order` | SET_AGENT 先于 USER_MESSAGE 且 APPLIED |
| `usage.unknown_thread_404` | 未知 thread usage 404 |
| `frontend.proxy_model_contract` | 5173 代理契约 |
| `thread.commands_model_yolo` | SET_MODEL + SET_YOLO 应用 |
| `thread.commands_model_invalid_variant_rejected` | 非法 Variant 在 SET_MODEL 入队前拒绝 |

### CRUD

| Case | 操作 |
| --- | --- |
| `crud.provider.invalid_missing_type` | 缺 providerType => 400 |
| `crud.model.invalid_update_config` | 非法 PUT 拒绝且原配置保留 |
| `crud.agent.invalid_blank_name` | 空白 name => 400 |
| `crud.agent.invalid_variant` | Variant 不属于所选 Model => 400 |
| `crud.provider.lifecycle` | create/list/update/delete + 空白 name 400 |
| `crud.model.lifecycle` | create/update/delete model（临时 provider） |
| `crud.agent.lifecycle` | create/update/delete agent |
| `crud.chat.lifecycle` | create/update/delete chat；空白 title 更新拒绝；attach/detach session |

### Model config 矩阵

由 `fixtures.modelConfigMatrix()` 展开为独立 case：

| Case 后缀 | 期望 |
| --- | --- |
| `valid.minimal` | 成功 |
| `valid.reasoning_variants` | 成功 |
| `valid.sampling_fields` | 成功 |
| `invalid.defaultVariant_mismatch` | 400 |
| `invalid.empty_variants` | 400 |
| `invalid.context_non_positive` | 400 |
| `invalid.output_gt_context` | 400 |
| `invalid.blank_currency` | 400 |
| `invalid.empty_modalities` | 400 |
| `invalid.duplicate_variant_id` | 400 |
| `invalid.missing_config` | 400 |
| `invalid.variant_blank_id` | 400 |
| `invalid.negative_temperature` | 400 |

### Agent config 矩阵

| Case 后缀 | 期望 |
| --- | --- |
| `valid.empty_lists_and_policy` | 成功 |
| `valid.positive_limits` | 成功 |
| `valid.missing_executionPolicy_defaults` | 成功并默认 `{}` |
| `invalid.unknown_tool` | 400 unknown agent tool |
| `invalid.duplicate_skill` | 400 |
| `invalid.non_positive_maxTurns` | 400 |

另有 setup/teardown case 管理临时 provider/model。

## 可选真实链路

| Case | 开关 |
| --- | --- |
| `real.text_turn` | `--real` |
| `branch.path_usage` | `--real --with-branch` |
| `daemon.ready` | `--with-tools` |
| `tool.read_turn` | `--real --with-tools` |

## UI smoke（L5）

| Case | 断言 |
| --- | --- |
| `ui.chats.page_loads` | `/chats` + 新建 Chat |
| `ui.models.page_loads` | `/models` + MiniMax + 新建 Model |
| `ui.models.open_create_modal` | 点击新建 Model |
| `ui.agents.page_loads` | `/agents` + default-assistant |
| `ui.providers.page_loads` | `/providers` |
| `ui.environments.page_loads` | `/environments` |
| `ui.nav.roundtrip` | 导航往返无 pageerror |
| `ui.chat.create_flow` | UI 创建 Chat 并出现在列表 |
| `ui.model.create_edit_delete_flow` | UI 创建/编辑/删除 Model |
| `ui.agent.create_edit_delete_flow` | UI 创建/编辑/删除 Agent |
| `ui.model.validation_empty_name` | 空名称前端校验错误展示 |
| `ui.provider.create_edit_delete_flow` | UI 创建/编辑/删除 Provider |
| `ui.chat.blank_workspace_shell` | 进入空白工作区，校验 blank pane + composer |
| `ui.chat.blank_first_send_real` | （`--real`）blank 首发真实消息 |

截图：`reports/e2e/latest/artifacts/ui_*/`；汇总：`ui-report.md` / `ui-summary.json`。

## 关键契约锚点

```text
POST /api/providers|models|agents|chats
PUT  /api/providers|models|agents|chats/{id}
DELETE /api/providers|models|agents|chats/{id}
POST /api/sessions -> {sessionId, mainThreadId}
PUT  /api/threads/{id}/agent|model|yolo
POST /api/threads/{id}/messages
GET  /api/usage/threads/{id}   # unknown => 404
GET  /api/models               # structured config only
```

## 维护

1. 改 API/配置校验：先更新 `fixtures.mjs` 矩阵行与本文表格，再跑 `./scripts/e2e.sh`。
2. 改首发/编排：更新 `cases/seed-and-harness.mjs`。
3. 前端 UI 变更：默认 E2E 不绑 selector；组件测用 Vitest。
4. 报告失败时从 `reports/e2e/latest/report.md` 开始排查。
5. 运行 jar 必须与源码一致；H2 重启后需注入 credential。

## 非目标（避免误解“全面”）

当前自动化 **不代表**：

- 所有 UI 点击路径
- 所有配置字段笛卡尔积
- Canvas / ComfyUI / 权限弹窗 / Subagent 全量
- 视觉回归与 Footer 像素级换行

这些可后续作为独立层扩展（Playwright smoke、更多矩阵行），但仍应写入本文件与报告。
