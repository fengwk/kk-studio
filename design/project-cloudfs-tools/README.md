# Project、Issue、Cloud File System 与工具体系目标方案

状态：已完成目标方案审查，实施中。本文档组描述一次性切换后的最终形态，不表示全部代码已经实现。实现完成后将有效事实同步到正式 `docs/`，并删除本设计稿。

## 1. 阅读顺序

1. 本文：全局边界、术语、模块关系和不变量。
2. [Project 与 Issue](project-issue.md)：Project Coordinator、Issue Controller、IssueRun、状态机和人类介入。
3. [Cloud File System](cloud-file-system.md)：平台虚拟文件树、PostgreSQL/S3 分工、路径、CAS、API 与 UI。
4. [文件工具](filesystem-tools.md)：Environment 与 Cloud 两套五工具协议、路径兼容、搜索和文本边界。
5. [工具输出](tool-output.md)：统一内联、外部化、Artifact 恢复、历史和失败语义。
6. [实施 DAG](implementation-dag.md)：依赖、切片所有权、迁移编号、Review 门禁和验收。

## 2. 目标

本方案新增两个相互解耦的产品能力，并重构现有工具基础：

- Project/Issue 负责目标拆解、依赖、Agent 分工、执行、Review 和人类介入。
- Cloud File System 是所有 Agent 都能访问的平台虚拟文件系统，承载持久文本、附件路径和只读 Tool Artifact。
- Environment 文件系统继续操作真实宿主文件；Cloud File System 不替代、不挂载、不模拟 Environment。
- 所有文本型 Tool Result 使用同一个终态输出策略：小结果内联，大结果保存为可分片读取的 Cloud Artifact，只展示很小的前缀和明确说明。
- 文件工具收敛为语义一致的 `read`、`write`、`edit`、`find`、`grep` 五项能力；进程和 LSP 保持独立。

## 3. 名称

| 名称 | 含义 |
| --- | --- |
| Cloud File System | 平台拥有的虚拟绝对路径、目录、文本 revision 和 Blob 文件体系，简称 CFS。 |
| Files | Cloud File System 在前端导航中的短名称。 |
| Environment file system | Daemon 所在目标操作系统的真实文件系统。 |
| Artifact | Tool Result 外部化后由 `storage_blob` 保存、通过 CFS 只读虚拟路径访问的不可变文件。 |
| Project Coordinator | Project 指定的一个现有 `AgentDefinition` 所扮演的协调角色，不是特殊 Agent 类型或固定名称。 |
| Issue Controller | 确定性应用代码和持久 Work reconciler，负责 Issue 状态、Run 创建、唤醒和围栏。 |
| IssueRun | 一次冻结 Agent、Issue spec 和输入游标的执行或 Review 运行。 |

架构和代码统一使用 **Cloud File System**；不再建设独立的 Knowledge Base
聚合。“知识”只是用户在 CFS 的 `/knowledge` 目录中组织的一类文件，例如
`/knowledge/projects/kk-studio/architecture.md`。

## 4. 全局结构

```text
Browser
  |
  v
Web composition root
  |
  +--> Platform
  |      |-- Project / Issue services + Issue Controller
  |      |-- Cloud File System services
  |      |-- Tool Gateway + output finalizer
  |      |-- Catalog / Storage / Environment integration
  |
  +--> Harness Runtime
  |      |-- Session / Thread / Invocation / Work
  |      `-- Model / Tool execution
  |
  +--> PostgreSQL
  |      |-- product and execution facts
  |      `-- CFS tree and editable text revisions
  |
  `--> S3
         `-- immutable Blob bytes

Environment Daemon
  |-- real filesystem tools
  |-- process / LSP
  |-- Skill / local MCP
  `-- one capability channel to Main
```

Project/Issue 不实现第二套 Agent Runtime。它只创建、观察和继续普通 Harness Session/Thread；模型调用、Tool 调用、审批、重试、超时、UNKNOWN 和 Environment 路由仍由 Harness 负责。

CFS 不实现 POSIX。它提供路径树和文件操作，不提供进程、cwd、权限位、符号链接、设备文件、文件锁或宿主路径语义。

## 5. 全局不变量

1. **无 Workspace 产品状态。** Chat、Project、Issue、Session、Agent 和 CFS 都不保存 Environment 工作目录。Environment 文件工具每次显式携带目标 OS 的绝对 `workdir`。
2. **两个文件系统不隐式切换。** `read` 永远访问 Environment；`cloud_read` 永远访问 CFS。是否绑定 Environment 不改变同一工具的路径含义。
3. **CFS 与 Project 解耦。** Project 表不外键引用 CFS node，CFS node 也不引用 Project。Project description 使用普通文本记录需要的虚拟绝对路径。
4. **Agent 是角色承担者。** Coordinator、Executor 和 Reviewer 都引用现有 `AgentDefinition`；不复制模型、Environment、Skill 或 MCP 配置。
5. **Agent 不拥有状态机。** Agent 决定拆分、实现和 Review 内容；Issue Controller 决定何时可运行、怎样转换状态、怎样围栏并发以及何时需要继续。
6. **静默不等于完成。** Executor 只有成功调用 `issue_submit` 才算提交；Reviewer 只有成功调用 `issue_review` 才能批准或要求修改。
7. **Issue 状态只有六个。** `BACKLOG`、`TODO`、`IN_PROGRESS`、`IN_REVIEW`、`DONE`、`CANCELED`。blocked 是依赖计算结果，archive 是时间戳，等待人类是 IssueRun 状态。
8. **持久事实进入 PostgreSQL。** Project、Issue、依赖、输入、IssueRun、Controller Work、CFS tree 和文本 revisions 都可在进程退出后恢复。
9. **大字节进入 Blob Storage。** 二进制文件和 Tool Artifact 复用 `storage_blob`
   与 S3；Tool Artifact 使用
   `/.artifacts/tool-results/{threadId}/{invocationId}.txt` 持久路径，模型历史不持久化
   URL、bucket、object key 或 Daemon 临时路径。
10. **修改使用 CAS。** Project/Issue 普通编辑使用 `version`；CFS 文本使用 `expected_revision`；Issue terminal tools 额外核对 `observed_spec_revision` 和 `observed_input_sequence`。
11. **并发 Issue 不互相排队。** 依赖已满足的 Issue 可以并行启动；不增加 Project、Environment 或 Agent 级人工容量、队列槽位或配额。
12. **通知只负责唤醒。** PostgreSQL 行是事实源，`NOTIFY` 和浏览器事件可以丢失，周期扫描与 Snapshot 必须收敛。
13. **一次性切换。** 不保留 Knowledge/Cloud 双 API、旧/new capability ID、Workspace fallback 或两套输出截断路径。

## 6. 领域边界

| 领域 | 拥有 | 不拥有 |
| --- | --- | --- |
| Catalog | Agent、Provider、Model、Environment、Skill、MCP 配置 | Project 执行状态、CFS 内容 |
| Harness | Session、Thread、Entry、Invocation、Work、审批和 Agent Loop | Issue 生命周期、CFS tree |
| Project/Issue | Project、Issue DAG、输入、IssueRun、Controller Work | Model/Tool 执行器、文件内容 |
| Cloud File System | 虚拟路径、目录、文本 revisions、Blob node、Artifact 只读挂载 | Project 关系、宿主路径、进程执行 |
| Storage | Blob identity、媒体事实、引用计数、S3 生命周期 | 用户目录树、Issue 状态 |
| Environment | Daemon 连接、真实文件/进程/LSP、Skill/local MCP | Cloud 路径、Project 状态 |

## 7. 模块落点

本轮不新增 Maven module：

- `schema`：按独立 migration 保存 CFS 与 Project/Issue schema。
- `platform`：两个领域的 service、PostgreSQL adapter、Controller、Host Tool 和 Storage 集成。
- `share`：public DTO。
- `web`：REST、事件适配和组合根。
- `frontend`：`features/files` 与 `features/projects`。
- `harness/common|tool|runtime`：仅承载真正通用的 Tool Result、参数和历史协议调整。
- `harness/environment|daemon|builtin`：Environment capability schema、实现与 prompt。

不为“保持纯净”建立只有一个消费者的 Cloud Core、Project Core、Controller SDK 或通用工作流框架。Platform 包内通过明确接口分层并以纯 Java 单元测试覆盖状态规则。

## 8. 明确非目标

- 不把 Environment 文件系统挂载到 CFS。
- 不把 CFS 做成 POSIX、WebDAV、Git、对象存储浏览器或远程 shell。
- 不新增用户/RBAC/租户模型；沿用当前受信任单用户部署边界。
- 不新增 Project priority、label、里程碑、甘特图、自定义状态、评论富文本、父子 Issue、定时任务或 Squad。
- 不建设语义检索、向量库或 PostgreSQL FTS；`cloud_grep` 是精确文本/正则匹配。
- 不在首版提供 Agent 侧 `cloud_delete`、`cloud_move`、`cloud_copy` 或二进制上传工具；这些操作先由 UI/REST 提供。
- 不增加 `column_offset` 或超长单行横向分页。超过单行展示上限的内容保持显式截断；完整重建超长单行不是本轮承诺。
- 不承诺 Tool Artifact 超过系统硬资源上限后仍可完整保存。
- 不让 Coordinator 的自然语言“完成”自动改变 Project 或 Issue 终态。

## 9. 交付原则

- 当前 Environment/Skill/MCP 重构先形成绿色公共基线；新实现从该 checkpoint 分支。
- 公共协议、migration 编号和共享文件所有权先冻结，再并行编码。
- 每个切片包含生产代码、调用方、测试和定向验证，不把“修编译”交给另一切片。
- 主集成者 Review 完整 diff、新文件、SQL、错误路径和测试断言后才合入。
- 正式 `docs/` 只在实现与 E2E 一致后更新；本设计稿不提前声称目标已落地。
