# 实施 DAG

## 1. 基线与门禁

本设计建立在 Environment Workspace 已删除、Daemon Protocol V2 已要求具体工具显式
`workdir` 的集成基线 `c0ccb67e` 上。两条开发线并行：

```text
c0ccb67e
  |
  +-- other Agent: W2-B Skill Sources & Operations -> W2-C MCP JSON-only
  |
  `-- this branch: Project / Cloud FS / Tool Output
```

本分支不实现、修改或吸收 W2-B/W2-C 的 Skill Source、Inventory、Operation 或 MCP
业务。两条线分别形成 clean、green 的 integration branch；用户指定的另一 Agent
最终把本分支合入它的最新基线并处理少量共享 wiring/version 冲突。

门禁：

| Gate | 条件 |
| --- | --- |
| G0 Design | 本文档组完成用户 Review，未决产品选择关闭。已完成。 |
| G1 Slice Base | 所有本线 Workspace 从同一 RFC commit 创建，ownership 不含 W2-B/W2-C。 |
| G2 Contracts | Tool schema、path alias、RE2 semantics、migration 编号和 ownership 冻结。 |
| G3 Backend | CFS、Project Controller、Tool output 各自单元与 PostgreSQL 集成测试通过。 |
| G4 Product | Files、Projects 两个 UI 与 REST contract 测试通过。 |
| G5 Handoff | 本分支全 reactor、frontend、docs、security、E2E 通过且 clean。 |
| G6 Final Integration | 另一 Agent 合并 W2-B/W2-C 与本分支后重跑全部验证。 |

G0 已由用户批准开始开发。首批只并行 G1 中完全不重叠的 core；共享 contract
由主 Agent Review 后才放行依赖切片。

## 2. Migration 编号

独占顺序：

| Migration | Owner |
| --- | --- |
| `V4__skill_sources_operations.sql` | W2-B |
| `V5__mcp_json_only.sql` | W2-C |
| `V6__cloud_file_system.sql` | C1 |
| `V7__project_issue.sql` | P1 |

- 每个 migration 只由一个 Workspace 修改。
- 并行切片不得修改已有 migration。
- 本分支即使暂时没有 V4/V5 文件也固定使用 V6/V7，且在最终两线集成前不部署数据库。
- V6 和 V7 可以并行开发，但集成固定先 C1、后 P1。
- Migration 必须在真实 PostgreSQL 上验证 fresh install、约束、rollback-on-failure 和并发行为。

## 3. 总 DAG

```text
RFC-BASE
  |
  +--> A0 --> T1 -----------------------+
  |                                     |
  +--> C1 --> C2 -----------------------+--> O1
  |           |                         |
  |           +--> C3                   |
  |                                     |
  `--> P1 --> P2 --> P3 ----------------+--> R1
                      |                       |
                      +--> P4                 |
                                              v
                                      I1 --> I2 --> OUR-BRANCH

OTHER-BRANCH(W2-B/W2-C) + OUR-BRANCH
  --> final integration by the other Agent
```

| ID | 名称 | 可并行条件 |
| --- | --- | --- |
| A0 | Tool Argument Foundation | RFC-BASE 后；与 C1、P1 并行。 |
| T1 | Environment Five Tools & Spool | A0 后；不修改 W2-B Skill/MCP 代码。 |
| C1 | Cloud FS Core | RFC-BASE 后；与 A0、P1 并行。 |
| C2 | Cloud Tools & Blob Mount | C1 后；与 P2 并行。 |
| C3 | Files REST & Feature UI | C2 后；与 P3/P4/O1 并行，但不改共享路由。 |
| P1 | Project/Issue Core | RFC-BASE 后；与 A0、C1 并行。 |
| P2 | Issue Controller | P1 后；与 C2/T1 并行。 |
| P3 | Project Role Tools & Harness Ownership | P2 后；与 C3/O1 并行。 |
| P4 | Projects REST & Feature UI | P3 后；与 C3/O1 并行，但不改共享路由。 |
| O1 | Unified Output Finalizer | T1+C2 后；与 P3/P4/C3 并行。 |
| R1 | Turn Resolution Integration | C2+P3 后；统一注册 Cloud 与 Project role tools。 |
| I1 | Shared UI/Docs/E2E Integration | C3+P4+O1+R1 后单 owner 串行。 |
| I2 | Full Verification & NAS dev | I1 后。 |

## 4. 切片定义

### A0 — Tool Argument Foundation

目标：

- 建立 built-in path alias normalization；
- 冻结 path alias tests 和 model tool name allowlist。

独占写入：

```text
harness/tool/**
```

不修改通用 `InputNormalizer`、Daemon、Environment、Builtin、Cloud、Project、MCP
或 W2-B/W2-C 文件。

验收：

- 所有调用方编译；
- alias 只对冻结的 built-in model names 生效；
- canonical+alias、多 alias 和非 allowlist tool 均由 strict schema 拒绝；
- durable assistant history 保留 Provider raw ToolCall；transient execution、审批和
  renderer 只使用 canonical arguments。

### T1 — Environment Five Tools & Spool

目标：

- 重写 `read/write/edit/find/grep` 为目标协议；
- visitor 搜索、RE2/J、编码/换行保持、超长行 marker；
- `bash` 与其它大 producer 使用有界 `OutputSpool`；
- 删除 Daemon 终态 preview 策略；
- 一次性完成本五工具所需的 capability ID/schema/prompt/wire version 更新。

独占写入：

```text
harness/daemon/**/coding/**
harness/daemon/**/resource transport tests
harness/environment/** file/process/LSP capability schema/codec only
harness/builtin/** file/process/LSP mapping and prompts only
harness/daemon/pom.xml
harness/environment-server/** only when resource wire handling requires
```

不修改 Skill Source/Inventory/Operation、MCP、Platform Tool finalizer、CFS、Project、
schema migration 或 frontend。与另一分支发生的 Catalog/protocol wiring 冲突留给最终
集成者，不复制其业务。

验收见[文件工具](filesystem-tools.md)和[工具输出](tool-output.md)的 Environment 部分；核心路径 JaCoCo line coverage ≥ 90%。

### C1 — Cloud FS Core

目标：

- V6 schema；
- virtual path value object；
- node/text revision repository；
- directory/text/blob application service；
- CAS、move cycle、Storage retain/release。

独占写入：

```text
schema/.../V6__cloud_file_system.sql
platform/.../cloudfs/domain/**
platform/.../cloudfs/repository/**
platform/.../cloudfs/service/**
platform PostgreSQL integration tests under the same packages
```

不修改 Tool Gateway、REST、frontend、Project 或 shared router。

### C2 — Cloud Tools & Blob Mount

目标：

- `cloud_read/write/edit/find/grep` Contributor；
- RE2/J Cloud walker；
- `/.artifacts/tool-results/{threadId}/{invocationId}.txt` exact read/grep；
- 将 Cloud Tool 注册到 registry，但不修改共享 turn resolver。

独占写入：

```text
platform/.../cloudfs/tool/**
platform/.../cloudfs/blob/**
platform matching tests/resources
```

Cloud Tool descriptors 标记为 internal，由 R1 决定何时进入 ModelRequestSpec。

### C3 — Files REST & Feature UI

目标：

- Cloud Files DTO、REST、browser notification；
- `/files` feature、tree、text editor、Blob upload/preview、CAS conflict。

独占写入：

```text
share/.../cloudfs/**
web/.../cloudfs/**
frontend/src/features/files/**
对应 tests
```

共享 `frontend` router/nav、API root wiring、正式 docs 和 E2E matrix 由 I1 修改。切片可提供独立 route component 与导出。

### P1 — Project/Issue Core

目标：

- V7 schema；
- Project、Issue、dependency、input、IssueRun、Controller Work domain/repository；
- 六态 transition、CAS、same-project DAG 和 session ownership relation。

独占写入：

```text
schema/.../V7__project_issue.sql
platform/.../project/domain/**
platform/.../project/repository/**
platform matching PostgreSQL integration tests
```

不创建 Controller Worker、不调用 Harness、不修改 turn resolver 或 UI。

### P2 — Issue Controller

目标：

- durable claim/lease/fencing；
- bounded reconcile；
- Executor/Reviewer Harness Session 创建；
- quiescence continuation、deadline、FAILED/UNKNOWN/WAITING_HUMAN；
- Project Coordinator attention notification。

独占写入：

```text
platform/.../project/controller/**
platform/.../project/session/**
platform matching worker/integration tests
```

优先复用 Harness service/port；需要修改共享 Harness 接口时先由集成者批准并补契约测试，不能复制 Harness Work。

### P3 — Project Role Tools & Harness Ownership

目标：

- Coordinator Issue management Tool；
- Executor `issue_submit`/`issue_request_input`；
- Reviewer `issue_review`；
- Thread owner 反查与 cursor fence；
- 角色 system context assembler。

独占写入：

```text
platform/.../project/tool/**
platform/.../project/prompt/**
platform matching tests
```

不直接修改 `DatabaseTurnResolver`。Tool 注册为 internal，R1 统一完成 owner-aware resolution。

### P4 — Projects REST & Feature UI

目标：

- Project/Issue DTO、REST、Snapshot 与 browser notification；
- `/projects` 列表、详情、Coordinator 对话、Issue board/list/detail；
- CAS conflict、人类 input/review/retry/cancel。

独占写入：

```text
share/.../project/**
web/.../project/**
frontend/src/features/projects/**
对应 tests
```

不修改共享 router/nav/docs/E2E matrix。

### O1 — Unified Output Finalizer

目标：

- 替换 Platform 双截断；
- 实现 result-level textual projection、精确计数、all-or-nothing artifact plan；
- durable `/.artifacts/tool-results/{threadId}/{invocationId}.txt` path
  projection；
- history、Provider、compaction 和 UI Resource contract 一致；
- 删除旧 OutputLimiter/Externalizer dead paths。

独占写入：

```text
platform/.../harness/tool/gateway/**
platform/.../harness/model/ProviderResourceMaterializer*
harness/runtime/.../history/**
harness/runtime/.../compaction/**
harness/runtime/.../thread resource models when required
frontend/src/features/chat/**/resource-output components only
对应 tests
```

如果 O1 与并行 Chat 工作有文件重叠，前端 Resource renderer 拆到 I1，O1 只交付后端协议。

### R1 — Turn Resolution Integration

目标：

- Cloud 五工具进入所有 Agent 的冻结 ModelRequestSpec；
- Project role tools 只按当前 Session owner/Run role 注入；
- Project/Issue role prompt 与普通 Agent prompt 组合；
- 禁止模型调用未在冻结请求中声明的 internal tool；
- 保持 Agent selectable Environment/MCP tools 逻辑不变。

独占写入：

```text
platform/.../harness/thread/command/DatabaseTurnResolver*
platform composition registry directly required by resolution
matching resolver/integration tests
```

R1 是该共享热点的唯一 writer。它从 C2/P3 的 registry/contributor 获取 descriptor，不复制 Tool 实现。

### I1 — Shared Integration

唯一集成 owner 修改：

```text
frontend shared router/nav/query keys
web common configuration/root controller advice
root pom or shared dependency declarations not already owned
docs/**
scripts/e2e/run-matrix.mjs
cross-feature API client exports
```

I1 解决所有跨切片冲突、运行全量测试并删除过渡代码，不在这里首次实现某个领域的核心逻辑。

### I2 — Full Verification & NAS dev

执行顺序：

```text
env JAVA_HOME=$JAVA_HOME_21 mvn verify
npm --prefix frontend test
npm --prefix frontend run lint
npm --prefix frontend run build
node scripts/docs/check.mjs
python3 scripts/security/check-sensitive-data.py
./scripts/e2e.sh
```

有 Tool/Environment/UI 契约变化，继续显式运行对应 `--with-tools`、`--ui` 矩阵。NAS `dev` 部署前先阅读并遵守 `docs/operations/development-and-testing.md#44-nas-maindev-自迭代运行规范`；Agent 不更新 main 稳定节点。

## 5. Workspace 规则

每个切片使用独立 `.workspace/<slice>/worktree` 和 `worktree/<slice>` branch：

- 都从同一个 RFC-BASE 或注明的本分支已集成依赖 commit 建立。
- `NOTES.md` 写 baseline、边界、验收和实际验证。
- `TODOLIST.md` 只记录可核验任务。
- 禁止修改非 ownership 文件来“顺手清理”。
- 发现共享接口缺失时暂停该小部分，报告集成者；不要在两个 Workspace 分别发明 adapter。
- 完成后提交单个可 Review commit，工作树必须 clean。
- 主 Agent逐文件 Review 后按 DAG 顺序 cherry-pick/merge。
- 合并后在集成树重新跑定向测试，不采信只在子 Workspace 的结论。
- Workspace 仅在 commit 已可从集成分支到达且无未提交文件后归档释放。

## 6. Review 清单

每个切片必须回答：

1. 是否只修改 ownership 范围？
2. 是否新增了 dual-track、fallback、兼容 alias 或无 owner abstraction？
3. SQL 是否有 DB-level check/FK/unique，而非只靠 Java？
4. Side effect 发生与否未知时是否错误地重试或报告失败？
5. 错误、log、`toString`、event 和 test fixture 是否可能泄漏 token/path credential？
6. 所有新行为是否有自动化测试，而非只跑编译？
7. 核心逻辑 coverage 是否量化且达到目标？
8. Browser notification 丢失后是否能由 Snapshot/refetch 收敛？
9. Resource retain/release 是否在同一事务正确配对？
10. Tool schema、prompt、Java contract、Provider projection 和 UI renderer 是否一致？

## 7. 跨切片验收场景

### 场景 A：无 Environment 的 Project

```text
create Project
-> Coordinator cloud_read requirements
-> create two independent Issues
-> Controller starts two Runs concurrently
-> Executors cloud_edit different docs
-> submit
-> Reviewers approve
-> both DONE
```

### 场景 B：Environment 实现 + Cloud 资料

```text
Issue Executor has Environment
-> cloud_read /knowledge/projects/.../spec.md
-> read/edit/bash in explicit workdir
-> bash output > 50 KiB
-> history shows tiny preview + /.artifacts/tool-results/... path
-> cloud_grep then cloud_read artifact
-> issue_submit
```

### 场景 C：依赖与返工

```text
Issue B depends on A
-> B blocked and never starts
-> A submit/reviewer request changes
-> A returns TODO and new Executor Run
-> A DONE
-> B automatically starts once
```

### 场景 D：人类与不确定副作用

```text
Executor issue_request_input
-> Run WAITING_HUMAN
-> process restart
-> human input resumes same Run
-> later side-effect Tool becomes UNKNOWN
-> Run UNKNOWN, no auto retry
-> human explicitly retries as a new ordinal
```

### 场景 E：Storage failure

```text
side-effect Tool produces large terminal text
-> S3 ingest fails
-> no fake truncated-success history
-> Tool/Run becomes UNKNOWN
-> original Tool invocation is not auto replayed
```

## 8. Release

G5 通过后：

1. 把最终事实同步到正式系统设计、模块文档、开发测试说明和 E2E inventory。
2. 删除本 `design/project-cloudfs-tools/` 临时设计目录，避免“目标文档”和“当前事实文档”双源。
3. 提供本 integration branch 的 HEAD、验证记录和共享冲突清单。
4. 不直接 fast-forward `dev` 或部署；由用户指定的另一 Agent 完成 G6。
