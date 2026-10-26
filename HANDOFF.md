# Runtime Convergence 交接文档

> 这是操作交接文档，不是产品文档；收敛完成后可以删除。它描述当前真实状态、已验证证据、未完成 Review、阻塞和下一步，不能把“代码已集成”理解为“任务已验收”。

## 1. 一句话状态

- 全部有效代码改动已收敛到唯一交接分支 `handoff`，代码基线为 `7ba0f167eff6eb07b78b24880970880fb1e964f5`，本文件及其索引修正形成仅含文档的提交。
- **2026-10-26 受限环境接手（第 16 节）**：生产代码差异已逐文件审完（含前端 CSS 与 i18n），会交付或驱动验收的 scripts 已审；修复 12 项（缺陷、性能、交接 HEAD 上确定失败的测试、恒失败的 schema 检查脚本与一处测试隔离）并补 1 处文档；记录 3 个需要用户决策的高优先级设计问题与剩余中低优先级问题；本地完成了除 Docker 用例外的全量 reactor verify。测试差异仍未逐行审。
- 实现与定向验证覆盖了主要路径；**最终候选全量 CI 未运行，`main`/`dev` 未合入，`v4.0.0` 未发布**。
- 本地仅保留 `main`、`dev`、`handoff` 三个分支；所有开发 workspace 已释放，记录归档在 `.workspace/.archives/`。
- `handoff` 分支已推送到 `origin/handoff`；没有合入/推送 `main`/`dev`，没有发布、部署、生产数据库写入或新的真实模型请求。24 次有界模型探测已封账，hosted 探测 SKIPPED。

## 2. 接手前必读

1. 先加载 `~/.agents/skills/dev/SKILL.md`。
2. 再加载 `~/.agents/skills/git-workspace/SKILL.md`；需要拆分、并行或验收多个交付时加载 `coder-coordination` 及其 `references/review-checklist.md`。
3. 读本文件后，按第 12 节找到归档中的 `REVIEW.md`、`NOTES.md`、`TODOLIST.md` 和证据目录。
4. 不要直接采信历史记录里的“完整读取/完整 Review/无截断”字样。证据完整性规则见第 3 节。

## 3. 硬约束与证据完整性

### 3.1 证据完整性

- 工具返回可能带 `[... N more characters truncated]`。请求范围、偏移量、末行出现、文件哈希、Git cmp、测试通过、对源码的熟悉程度，都**不能**证明中间内容完整可见。
- 历史记录中曾多次出现依据截断输出写下的“完整审读”声明；这些声明已被撤回，但归档记录里可能仍保留旧措辞。以 `REVIEW.md` 的保守表和本文件为准，并在实际读到内容后再签收。
- 可以信任的机械证据：Git 祖先关系、提交/树/blob 哈希、`cmp` 结果、生成的 diff SHA256、CI run JSON 的 `head_sha`、测试日志中实际可见的 Surefire 计数与 `BUILD SUCCESS`。
- 不能替代内容 Review 的证据：请求覆盖全文件、最后一行可见、测试绿色、Git 无漂移、历史“已检查”勾选、助手口头总结。
- 复查代码时使用小窗口（建议 60–120 行），一次只登记实际可见范围；不要用连续窗口请求推断中间内容。

### 3.2 Git 与工作区

- 只允许普通提交；禁止 `--amend`、`rebase`、直接向 `main`/`dev` 提交。
- 一个 worktree 同一时刻只有一个写入者；接手前确认没有残留构建或测试进程。
- 交接分支是 `handoff`。根仓库当前检出 `dev`，不要在根工作区提交。
- 释放/归档使用 `git-workspace-cli`；不要手工删除仍被引用的 worktree 或分支。

### 3.3 生产、发布与探测

- 生产数据库只读，命令必须带：
  `PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=5000 -c lock_timeout=1000'`
- 禁止生产修复、重启、登录、取消任务、修改设置、部署或执行未授权迁移。
- 24 次模型探测已封账；不得发起新的 live 模型请求，除非用户单独明确授权。
- 线上 IDE 首次关闭方与根因未确认；禁止用重试、关闭 Brotli、放大超时等表面修复冒充根因修复。
- 发布、`main`/`dev` 推送、tag、生产操作都需要用户单独授权。本次交接授权了分支收敛、workspace 释放和 `handoff` 分支推送；未授权全量 CI、`main`/`dev` 合入或发布。

### 3.4 门禁与覆盖率

- 保持 Spotless、Checkstyle、JaCoCo、敏感数据、文档、版本对齐等现有门禁；不得跳过、降低阈值或删除测试来提速。
- d8 切片的严格新增行覆盖率是 `5/6 = 83.33%`，低于仓库核心路径 90% 目标；核心方法 `17/18 = 94.44%` 不能替代严格新增指标，未覆盖的是非 `STOPPED` 守卫的 fail-closed 返回。没有豁免，也不要用不符合真实 Stop 路径的状态去凑覆盖率。
- 明确标注“定向测试/变更行覆盖率/模块门禁/全量 CI”四种不同证据，不能互相替代。

### 3.5 退出语义

- 逻辑结果、传输关闭、进程退出、远端持久确认是不同事实；不得把任意 terminal 帧、callback 到达或 `close()` 返回当作物理退出/持久提交。
- JVM/远端死亡或保留期淘汰导致的未知，按设计保持 `UNCONFIRMED`；禁止 force-clear、伪造 ACK 或合成退出事实。

## 4. Git 快照与分支

| 项目 | 值 |
| --- | --- |
| 仓库 | `git rev-parse --show-toplevel` 返回的根目录 |
| 交接分支 | `handoff` |
| 代码基线 | `7ba0f167eff6eb07b78b24880970880fb1e964f5` |
| 基线 | `a300679d9f068c4c29ce287cdc62bec0752cf84a` |
| 提交数 | 基线到代码基线 161 个提交 |
| 代码净差异 | 362 路径，+18948/-3249，35009 行 diff |
| 本地保留分支 | 交接时为 `main`、`dev`、`handoff`；2026-10-26 本机为 `handoff`、`main`、`refactor/kiss-architecture`（`dev` 只在 origin） |
| `main` / `dev` | `a300679d9f068c4c29ce287cdc62bec0752cf84a` |
| 版本 | 根 POM `4.0.0`；尚未发布，尚未打 tag |
| 全量 CI | `7ba0`/本文件提交均未运行；最近成功 CI 只覆盖 `925c334098182eda7abdb9da55df8765977ed8ee` |
| 2026-10-26 接手提交 | 在 `7e067f915` 之上新增 13 个代码/测试/文档修复提交与本文件更新，见 16.2；均未推送 |

本文件提交后：

- `git rev-parse handoff` 是新的交接 HEAD。
- `git diff --stat 7ba0..HEAD` 包含 `HANDOFF.md` 与 16.2 列出的修复文件。
- `git diff --check 7ba0..HEAD` 应为空。
- 37 个非交接 workspace 已释放：其中 36 个已合并分支随释放删除；唯一未合入的红色测试提交 `26c5b77aaf832e94ade6fb2fed2026df9cb4b1a6` 已保存为 bundle（见第 12 节），其分支也已删除。
- `origin/handoff` 已创建并与本地 `handoff` 一致；旧远程引用 `origin/worktree/runtime-convergence` 仍保留，未删除。

### 4.1 重新创建工作区

释放后 `.workspace/runtime-convergence` 会被归档删除。重新创建时**复用现有分支**，不要新建第四个分支：

```sh
mkdir -p .workspace/runtime-convergence
git worktree add .workspace/runtime-convergence/worktree handoff
```

然后确认：

```sh
git -C .workspace/runtime-convergence/worktree status --short
git -C .workspace/runtime-convergence/worktree rev-parse HEAD
```

### 4.2 关键提交线索

- Thread/Join 生命周期：`dd2e6ed84`、`b935592a3`、`a99a0e045`、`72aa5b9fa`、`0dc9f98b5`、`eddefde1f`、`9882b68de`、`22ccb6031`；测试契约修正 `9ff92f9df`、`4fa662da0`。
- Join 负例/额度：`81cc3fa96`、`45222bf39`、`6f11e5a42`、`7ebc632c8`。
- Stop 退出账本与结算：`99bc07de0`、`d965eaee4`、`5889110e77`、`05aef9596`、`cde3c22c1`、`dd90fbe70`、`b6b55be87`、`51a20f32d`、`3f9ad60cf`、`d2e2fdc01`、`fa1bee114`。
- Stop 后续修正：`4f88c5a45`、`5121934e8`、`79e36cd0a`、`5575686bc`、`a88ae7e8e`、`d30a49d9f`、`5e591f5b9`、`e20546f81`、`2c2aabb23`、`a559b2a3a`、`87db8f8b0`、`dcbea4a21`、`e0b453bcc`、`49e58f56e`。
- Stop 清理收尾：`0445da5ed`、`161d2cd4b`、`ec11efd86`、`c0553e782`、`b0ef292ea`、`9d02efc6a`、`242474189`。
- 远端/重连/LSP/skill：`7ce88688a`、`049c680f1`、`dff09dc22`、`1fba35bff`、`3feff2736`、`0d94c6b04`、`443d5f2fe`、`09cdb4da4`、`8eedece96`、`f1159ea31`。
- usage/计价/四协议：`ed177fb2b`、`7185ff999`、`634390995`、`7d8ba36af`、`8a7a4855a`、`bac49e4a6`、`4abe37ab3`；Chat 错误分类 `54d33d5f5`、`fe7a5f419`；压缩夹具 `06e701b6c`。
- 前端：`302ca14ea`、`f62ce155d`、`ee1b6070d`、`149644a50`、`31e748f36`、`acaa58c005`、`cce393ccd`。
- IDE/安装器/断流：`e8998c127`、`3852b6351`、`0a72d6e0b`、`c709e08b8`、`7ffc502c6`、`dd5e13914`、`a8b11fd52`。
- 构建/schema/版本：`a5cbec115`、`198a9576`、`39d1b616`、`000c57c4a`。
- E2E：`f6d6c4019`、`959e9c433`、`865c6d205`、`733f72e70`。
- 最终 Review 修正：`2752041df`、`858639a8d`、`6ecb231f7`、`0dad93b65`、`1dcb9d711`、`7ba0f167e`。

## 5. 已实现范围

| 工作流 | 已落地内容 |
| --- | --- |
| Thread/Join | 三态活动 `RUNNING/WAITING/IDLE` 与执行控制 `RUNNABLE/STOPPED` 正交；原子 `NOTIFY_PARENT`；`SETTLE_OWNER_TURN`；递归/祖先结算；精确 replay 先于 admission/CAS；当前 Thread/receipt 返回；无 quota 字段 |
| Stop 退出 | 历史 STOPPED `TURN_END` → `closeRequestId` → 精确回执；结果完成不等于物理退出；本地 body close/实际线程 join 后 `STUDIO_EXITED`；远端独立 ACK；未确认保持 `UNCONFIRMED` |
| 远端/重连 | 新 generation 使用新 remote UUID；发送前登记；确定未发送才 `NOT_STARTED`；原实例 tombstone；重连 CANCEL-only、INVOKE 一次、未知 CANCEL 静默 |
| LSP/Daemon | 目标 RPC ACK 与共享 server 生命周期分离；native child 退出先于 stdio close；独立 5 秒 page/browser/临时目录清理；journal CANCEL/recovery；spool 独立退出 |
| usage/计价 | 四协议 mandatory/optional 字段、unknown≠0、cache 关系、reasoning≤output、溢出/缺失拒绝；四份脱敏观测 fixture；未知费用不伪造 |
| 前端 | Thread 详情/三态、只读 CUSTOM 补充、队列 `idempotencyKey` + `type:text`、tool/task 链接、未知 usage/metadata、STOPPED 静态提示、真实活动与关闭事实分离 |
| IDE defaults | 原生 `configurationDefaults`，用户/ Git 覆盖优先；关闭 task/npm 自动发现、在线包信息、自动类型下载；Linux code-server/VS Code 验证 |
| 安装器 | 真实阶段/PTY/日志/回滚；`${RELEASE_TAG}` 边界；Linux Bash 与 Bash 3.2 各 38 项；不放松启动限流 |
| 构建/schema/版本 | 同提交 daemon/dist 构建；唯一 V1 schema 与新增约束；4.0.0 版本契约；未发布 |
| E2E | 三态契约、稳定 IDLE 空义务双游标、Stop 后 replay 基线、审批对象取用、有界清理 |

## 6. 不可破坏的设计不变量

### 6.1 活动、控制与退出

- Activity `RUNNING/WAITING/IDLE` 只描述本地流程/等待；durable `RUNNABLE/STOPPED` 只描述执行控制。两者不可互相覆盖。
- `RUNNING` 包含本地人工/环境阻塞；`WAITING` 仅表示本地已让出执行、等待异步 system-reminder 结果；`IDLE` 不等于结果已结算或物理退出。
- Stop 封账历史并请求取消，不同步等待物理退出；Stop 响应 `STOPPED`/`TURN_END STOPPED`、命令 `QUEUED`、invocation 状态都是不同事实。
- 稳定 quiescence 需要：`IDLE`、空义务、两次一致的 cursor 观察；`STOPPED` 本身不足以判定稳定。

### 6.2 Stop 边界与回执

- Stop 边界由历史选择：当前 owned `STOPPED` `TURN_END` 的 `closeRequestId` → 精确回执；绝不用时间戳或 UUID 猜测。
- 回执缺失/不匹配必须 fail-closed；删除注入只允许在隔离 Testcontainers 中进行。
- `SETTLE_OWNER_TURN` 是 owner 义务，不消耗 `NOTIFY_PARENT` 配额；它保持 owner `RUNNING`，阻止 Resume 与结算。
- 新 owner 证据推进版本；`STOPPED` 不产生执行唤醒；`RUNNABLE` 才请求 Work。
- `STOPPED` 通知写入历史并推进水位，不创建模型 Work；父通知命令先插入，再原子更新 matched+delivered。

### 6.3 Join 与版本

- Join 保留 delivery 类型、身份、源输入、冻结结果、软预算、接管关系、`deliveryCommandSequence`。
- `NOTIFY_PARENT` 冻结 + 通知 + Work 原子提交；不持久化 matched-undelivered 半状态；数据库 CHECK 保证原子性。
- 精确 replay 先于 admission/CAS；原回执/当前 Thread 返回；确认独立推进版本。
- `changed=false` 不触碰版本；只有 changed 才推进并触发后续事实。
- 不新增 phase/Agent 状态机/通用异步表；细节取 Model/Tool/Command 事实。

### 6.4 身份、ACK 与重连

- execution/thread/invocation/attempt/lease/kind/parent 身份不可替换；远端 parent 必须是同代 `TOOL` execution。
- 新 remote generation 发送前生成新 UUID；确定未发送的 Busy/Unavailable 才能 `NOT_STARTED`。
- 远端执行需要独立正向 ACK；callback 到达不等于 durable commit。
- 原实例 tombstone/有界保留；同实例 `READY` 只允许 CANCEL，`INVOKE` 一次；未知 CANCEL 静默。
- `ConnectionState.offer` 与 close 共享 monitor；不要发明不可能发生的旧 offer/READY 竞态。

### 6.5 锁与事务

- `ThreadTreeLocks`：不可变 root advisory lock 先于业务行；ownThread 锁不额外加 Session KEYSHARE。
- `ThreadContextLock` 顺序：Thread → Commands → Model → Tools → Work。
- Coordinator 使用 own/direct-parent 锁；Ledger 使用祖先链锁；不得推断隐藏锁获取。
- 确认依赖已存储 Thread 锁；完整 identity 冻结；changed-only 版本推进。
- 不在已闭合 turn 的 context 上新增 Model/Tool 锁。

### 6.6 清理与失败保留

- 使用 Java TWR/`t.after` 保护 setup/start/handshake/gates/assertions；release/join/shutdown 独立尝试。
- 保留主异常与清理异常；native TWR 语义和绿色测试不等于注入过双失败。
- 有界等待：实际 worker/scheduler 5 秒 join；不要用无界 `ExecutorService.close`。
- 私有资源工厂只在本测试类复用；不引入生产测试缝或清理框架。
- `FakeRun.exited` 只表示 body 返回；close/确认结束需要实际 `Thread.join`。

### 6.7 usage、计价与前端

- `ModelUsage.inputTokens` 是未缓存输入；`outputTokens` 包含 reasoning；cache 读写与 input 互斥；reasoning 是 output 子集。
- `unknown/null` 不等于 0；只有协议定义的 proto3 普通 int32 可省略为零；可选 total/thoughts 保持未知。
- 不合成 provider total；不把未知费用记 0；不重复计算 reasoning。
- 前端保留原始 metadata；null/undefined 人工消息不计；显式空对象/数组/primitive 传播未知。
- 补充消息只认实际 `idempotencyKey` 与 `type:'text'`；task 链接只认 canonical `details.thread_id`，不解析 prose。

## 7. 验证证据（精确 SHA）

> 以下均为历史精确提交的定向/CI 证据，不是本文件提交后的新验证。不要把“绿色”扩展为未运行范围。

| 范围 | 提交 | 结果 | 说明 |
| --- | --- | --- | --- |
| 第 13 轮全量 CI | `925c334098182eda7abdb9da55df8765977ed8ee` | 成功 | 733 脚本（732 通过/1 跳过）、25 Maven 模块、Vitest 3440 通过/2 跳过、298 文件/1 跳过、258 browser/9 跳过；覆盖率 statements 90.78 / branches 85.17 / functions 90.55 / lines 91.29；镜像 validate-only 跳过 |
| 第 12 轮全量 CI | `72f3c0dff57773cec94d3ce32100bee15ae776db` | 成功 | 25 Maven、Vitest 3440/2 跳过、258 browser、9 native；历史基线 |
| Stop 集成 | `d2e2fdc01`（另 `fa1bee114` 仅测试清理） | 763/763 Java、246/246 Node | 变更行 535/581=92.08%；不是全仓覆盖率；`fa1bee114` 不在 763 的精确 SHA 中 |
| Stop 回执 d8 | `d8bae4b6c` | 8 项定向通过 | PG 2 + runtime 5 + owner 1；核心方法 17/18=94.44%，严格新增 5/6=83.33%，10/14 分支；未覆盖第 220 行非 STOPPED 返回 |
| Chat reasoning | `fe7a5f419` | 39 项通过 | 新增 2/2 行、4/4 分支；`54d33d5f5` 红 1 项（expected ProviderException/actual IllegalArgumentException） |
| owner compaction | `5831c4da0` | 11 项定向通过 | 生产新增 17/17 行、13/16 分支；InMemory 8/8 行/分支 |
| 远端 fixture | `77b7997ab` | 2 项定向通过 | 主失败保留；13 行 Javadoc delta |
| Gateway 文档 | `858639a8d` | 提交前同源 validate 通过 | 仅 Javadoc；不涉及行为复测 |
| Environment 清理 | `2752041df` | 2 项定向通过 | 同源提交前；未做双失败矩阵 |
| LSP 失败保留 | `6ecb231f7` | 4 项定向通过 | 30 项名称过滤未运行；单一故障窗口保留 body AssertionError + cached LspError |
| 并发删除清理 | `0dad93b65` | 1 项定向通过 | 同源 blob；未做故障注入 |
| Settlement 清理 | `1dcb9d711` | 5 项定向通过 | 包装脚本未保存原始 Maven 退出码；以 Surefire 与 BUILD SUCCESS 为证 |
| Stop 契约清理 | `7ba0f167e` | 子快照 4 项通过；父最终首项 1 项通过 | 其他 3 项源码未变；未做最终全类复跑或故障矩阵 |
| 四协议负例 | `7ebc632c8` | 207 项通过 | 13 个既有测试方法补合法 usage，保留具体 tool/terminal/cache 错误 |
| 首轮集成基线 | `198a9576` | 463 Java、145 Node、667 Vitest、5 Chromium | 历史集成证据，不代表当前候选 |
| Provider 净差异 | `7ba0` | 347 行生产 + 2856 行测试逐窗读取 | Git cmp 与候选一致；diff 级检查，不等于全部周边源码/调用链已审 |

证据位置：

- 全量 diff：`.workspace/.archives/<runtime-convergence-archive>/records/evidence/final-net-7ba0f167e/`
  - `net-a300679d9-to-7ba0f167e.diff`：35009 行，362 路径，SHA256 `ac3f3375d25ec88a4f209edb2aae50bb23be0c20f4ef3fc608f29e44fa5ab73a`
  - `name-status.txt` SHA256 `fc6133d74fd4dcd6e284a35aef13a8039ae5701752ebe53d2a128fc0dfb8886e`
  - `numstat.txt` SHA256 `b44cc01b28e42cce57206907e3511e46fda6368bb714c7917db3f41424abe1e2`
- 第 13 轮 CI：`.workspace/.archives/<runtime-convergence-archive>/records/evidence/ci-40149290282/`
  - `run.json` SHA256 `321b1f962d5289a54db3cd1379ebe8f583dff2a97e9d943629efd823b4d83f4b`
  - `repository.log` SHA256 `9b256793e0beac8e6a3a3a709b72bc5664008116a24d4074f96bc0fa0b1a004f`
- Stop/d8/owner/Chat/LSP/清理切片的原始日志：对应 workspace 归档的 `records/`；释放后文件可能位于 `<archive>/records/records/`。

## 8. Review 现状与未完成项

### 8.1 当前结论

- 代码已集成，但**没有任何一组可以仅凭历史“已读”声明完成验收**。
- Provider 的净差异（生产 347、测试 2856）在最后一次续接中逐窗实际读取并与 Git cmp 对齐；这仍不是“全部当前源码 + 所有调用链”的 Review。
- 其余大部分组只有局部事实、精确测试或机械 diff，不足以签收。
- 归档中的 `REVIEW.md` 末尾已撤回多批“完整审阅”声明；以该表为索引，但逐项重新确认。

### 8.2 组级状态

| 组 | 当前状态 | 下一步 |
| --- | --- | --- |
| Provider 生产/测试净差异 | 生产 diff 2026-10-26 再次读完（发现见 16.5 H3、M1）；测试与周边上下文未逐行审 | 复核四协议当前源码、错误出口、共享 `ProviderSseCall`、调用方 |
| Runtime 生产净差异 2293 行 | 2026-10-26 已逐文件读完 29 个文件（待处理见 16.5 H1、M4；已修复见 16.4 第 6–8 项） | 按小窗口读 Ledger/Coordinator/StopControl/Processor/ContextLock/InputDemand，联查调用链 |
| Infra 生产净差异 274 行 | 2026-10-26 已读完（索引缺口已修复，见 16.4 第 9 项） | PG Transaction 回执/Join/锁序；联查 schema |
| Schema 295 行 | 2026-10-26 已读完 V1 diff；补索引后 V1 + 探针在本机 PG16 `PASS 205`（16.6） | 独立 execution 表、FK/CHECK/索引；核对现有 V1 checksum 与升级限制 |
| Runtime 测试 8253 行 | 未完成（2026-10-26 只读了 16.3 列出的文件） | 重点 ExecutionExitSettlement/Fact/FailureStore/Projector/Join/Stop；验证断言等价性 |
| Infra 测试 1472 行 | 未完成 | 新 PG 回执 316 行、fixture 88 行、Join/Deletion/Compaction |
| Platform/Web/Apps/Share/OpenCLI 测试 | 未完成 | 按 REVIEW 表逐组；Web 1660 行优先 |
| Frontend 生产 1436 / unit 3746 / browser 906 / other 53 | 生产 29 个文件已于 2026-10-26 全部读完（含 CSS 与 i18n）；测试未审 | 先生产调用链，再测试；不要用 120 行窗口声明全文件 |
| Scripts 3590 / docs 649 / build 355 | 2026-10-26 已读 daemon 安装器、reliability 报告/runner/policy、E2E harness 库与仓库检查脚本，修复 schema 检查脚本恒失败（16.4 第 12 项）；E2E case、脚本测试、docs/build 未逐行审 | 版本、CI、安装器、文档契约；按路径分组 |
| Daemon 生产 1018 / 测试 2441 / other 57 | 生产 13 个文件已读完（`config.ts` 一行版本除外），修复见 16.4；测试未逐行审 | LSP/进程/journal/spool/取消边界 |
| Environment 生产 702 / 测试 790 | 生产 6 个文件已读完（发现见 16.5 H2）；测试未逐行审 | ACK、READY、recovery、listener 隔离 |
| 0dad/1dcb/7ba0 清理切片 | 2026-10-26 断言等价性已核对（16.3）；未做故障注入 | 四方法资源作用域、断言等价性、5 秒真实 join；不要编造故障矩阵 |
| 新文件与跨模块关系 | 未完成 | 27 组之外的新增类、DTO、生成文件、调用方 |

### 8.3 建议 Review 顺序

1. Stop/Ledger/PG/Coordinator：最高风险，涉及历史边界、锁序、ACK、UNCONFIRMED。
2. Runtime/Infra/Schema 生产差异与数据库约束；核对回执与 Join 原子性。
3. Runtime 测试与清理切片；确认断言没有因测试重构被弱化。
4. Frontend 生产与测试；确认三态、details、queue、usage 的消费者一致。
5. Daemon/Environment/Platform/Web/Apps/Share/OpenCLI。
6. Scripts/docs/build/version/CI。
7. 最后再决定是否需要修复或补充测试；修复走隔离 workspace + 普通提交。

## 9. 已知限制与阻塞

- **最终 CI 未运行**：`7ba0` 和本文件提交都没有全量 CI；第 13 轮只覆盖 `925c334098`。验收前必须冻结最终 SHA 并跑 validate-only 全量 CI。
- **d8 严格覆盖率 83.33%**：低于 90% 目标；未覆盖非 STOPPED 返回。没有豁免。
- **丢失的原始日志**：`1dcb` 的包装脚本因 zsh 只读变量 `status` 未保存原始 Maven 退出码；Stop 契约 coder 首次编译失败日志被成功日志覆盖。只能保留 Surefire/BUILD SUCCESS 与“失败事实来自 coder 报告”的限制。
- **未知退出**：JVM/远端死亡或 retention 淘汰按设计保持 `UNCONFIRMED`；不得 force-clear 或伪造 ACK。
- **LSP ACK 范围**：目标 RPC 的 ACK 不等于共享 server 生命周期；start 抛错/缺 handle/空 registry 不能证明 bootstrap 清理。
- **线上 IDE 断流**：首次 close 方和跨主机因果未确认；本地 ACK 竞态修复不等于线上根因。
- **Schema 升级**：唯一 V1 checksum 不兼容；4.0.0 升级需要明确的维护窗口/审批，不得对现有生产库直接迁移或 repair。
- **探测封账**：24 次已封账，hosted SKIPPED；不存在“再跑一次确认”的授权。
- **归档不是 Git**：workspace 记录已归档到 `.workspace/.archives/`；代码只在 Git 分支。归档目录不随分支推送。
- **待用户决策的设计问题**：未确认退出没有收敛出口、同实例重连取消全部在途调用、`openAiChatIncludeUsage=false` 与强制 usage 冲突，
  见 16.5 H1–H3。它们决定发布前要不要改设计，不是能靠测试补齐的缺口。

## 10. 环境与命令

### 10.1 工具链

```sh
# JDK 21（按环境变量；常见路径 /usr/local/opt/jdk21）
env JAVA_HOME="$JAVA_HOME_21" java -version

# Node 24
$HOME/.local/node-v24/bin/node --version   # v24.22.1
```

Maven 必须显式指定 JDK 21；不要用默认 JDK。

### 10.2 定向 Java 测试

```sh
env JAVA_HOME="$JAVA_HOME_21" mvn -o -B -ntp \
  -pl harness/runtime -am \
  '-Dtest=ExecutionExitSettlementTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

- 只跑与改动直接相关的测试；不要为了弥补未读代码而重复已充分的测试。
- 修改测试后保留正常 Spotless/Checkstyle；文件级 Spotless 用 `-DspotlessFiles=<regex>`，并检查 diff 不夹带无关格式化。
- 复杂/长测试数据放 `src/test/resources` 对应包结构。

### 10.3 仓库门禁

```sh
NODE="$HOME/.local/node-v24/bin/node"
$NODE scripts/dev/verify/repository/check.mjs
$NODE scripts/dev/verify/repository/check-sensitive-data.mjs
RELEASE_TAG=v4.0.0 $NODE scripts/dev/verify/repository/check-version-alignment.mjs
git diff --check
```

- 版本对齐脚本必须带 `RELEASE_TAG`，否则按空 tag 报失败。
- Daemon 的 vitest 通过 alias 把 `../src/*.js` 指向编译后的 `daemon/dist/*.js`：改 Daemon 源码后先 `npm --prefix daemon run build`
  （`npm run test` 已包含），否则测试跑的是旧产物。

### 10.4 E2E

```sh
./scripts/dev/verify/e2e/run.sh --list
./scripts/dev/verify/e2e/run.sh                 # 默认 L1 API，无成本
./scripts/dev/verify/e2e/run.sh --real          # 真实模型，需明确授权
./scripts/dev/verify/e2e/run.sh --with-tools
./scripts/dev/verify/e2e/run.sh --ui
```

事实源：`docs/operations/development-and-testing.md`；矩阵 inventory 以 `run-matrix.mjs --list/--docs` 为准。报告在 `reports/e2e/`（gitignored），最近结论看 `reports/e2e/latest/report.md`。

### 10.5 全量 CI（validate-only）

分支已推送；触发全量 CI 仍需单独授权，然后执行：

```sh
gh workflow run docker-publish.yml \
  --repo fengwk/kk-studio \
  --ref handoff \
  -f validate_only=true

gh run list --repo fengwk/kk-studio --workflow=docker-publish.yml --limit 3
```

必须核对 run 的 `head_sha` 等于最终候选；validate-only 不发布镜像，但会跑仓库脚本、Java/覆盖率、前端、browser、native 和平台矩阵。CI 失败时定向复现，不重复无关全量。

## 11. 收敛与发布流程

> 只在内容 Review 完成、必要修复已提交、定向验证通过后执行。

1. 重新创建工作区，核对分支/HEAD/clean，确认没有残留写入者或构建。
2. 完成第 8 节 Review；发现问题走隔离 workspace，普通提交，补定向测试；不要 amend/rebase。
3. 更新 `REVIEW.md`/`TODOLIST.md` 的实际可见范围和测试证据；不要把“请求范围”写成“已读”。
4. 运行必要的定向测试与仓库门禁；冻结最终 SHA。
5. 触发 `validate_only=true` 全量 CI（需授权）；分支推送已完成。
6. 核对 CI run `head_sha` 精确等于最终 SHA；所有 job 成功；失败则定向修复后重新冻结/触发。
7. 合入 `main`/`dev`（需授权）：目标已推进时，先把最新目标合入候选，复核并验证新提交，再用新的 expected target/head 调 `git-workspace-cli integrate`。
8. 推送 `main`/`dev`（需授权）；确认远端 refs 与本地一致。
9. 在 `main` 的发布提交上创建 tag `v4.0.0`（版本已在根 POM），推送 tag；Daemon Release 由 tag 触发。
10. 验证 GitHub Release、发行归档、SHA256 校验文件；不覆盖已有版本资产。
11. 归档/释放最后的工作区与记录；保留必要的 Git bundle/证据。

发布前必须重新核对：

- `v4.0.0` 远端 tag 不存在，或确认允许覆盖（默认禁止覆盖）。
- 版本对齐脚本通过。
- `main` 是 tag 的祖先。
- 发行归档和 SHA256 均存在且可下载。

## 12. Workspace 与归档索引

所有非交接 workspace 已在 2026-10-26 释放；记录在 `.workspace/.archives/<archive>/records/`。原 workspace 自身的 `records/` 下的文件，归档后位于 `<archive>/records/records/`（CLI 保留目录名）。交接 workspace 已在本文档首次提交后释放为 `20261026-033430-028039Z-runtime-convergence`。

| Workspace | HEAD | 用途 | 归档目录（`.workspace/.archives/` 下） |
| --- | --- | --- | --- |
| runtime-convergence | `7ba0f167e` + 本文档提交 | 唯一交接分支、集成、最终材料 | `20261026-033430-028039Z-runtime-convergence` |
| ci-web-reset | `b438fa286` | CI 测试库 reset / Flyway 46 | `20261026-032755-030191Z-ci-web-reset` |
| daemon-install-ux | `c709e08b8` | 安装器 UX/PTY/docs | `20261026-032755-308986Z-daemon-install-ux` |
| ide-lightweight | `e8998c127` | IDE 原生默认值 | `20261026-032755-553409Z-ide-lightweight` |
| ide-stream-close-fix | `a8b11fd52` | IDE ACK/断流修复 | `20261026-032755-969395Z-ide-stream-close-fix` |
| provider-negative-contract | `7ebc632c8` | 四协议负例补合法 usage | `20261026-032756-273570Z-provider-negative-contract` |
| review-chat-usage-error | `fe7a5f419` | Chat reasoning>completion 分类 | `20261026-032756-534092Z-review-chat-usage-error` |
| review-cleanup-faults | `e20546f81` | Node/Java 失败窗口注入证据 | `20261026-032756-799181Z-review-cleanup-faults` |
| review-daemon-bounded-cleanup | `94f897f29` | native page/browser 有界清理 | `20261026-032757-079449Z-review-daemon-bounded-cleanup` |
| review-deletion-test-cleanup | `0dad93b65` | 并发删除 TWR | `20261026-032757-490682Z-review-deletion-test-cleanup` |
| review-e2e-stop-convergence | `733f72e70` | E2E Stop 稳定基线/有界清理 | `20261026-032757-749814Z-review-e2e-stop-convergence` |
| review-e2e-thread-contract | `959e9c433` | E2E 三态/审批对象 | `20261026-032757-998321Z-review-e2e-thread-contract` |
| review-ide-ack-safe-log | `427eac4a1` | ACK 安全日志 | `20261026-032758-266495Z-review-ide-ack-safe-log` |
| review-lsp-failure-preservation | `6ecb231f7` | LSP exit barrier 主失败保留 | `20261026-032758-543988Z-review-lsp-failure-preservation` |
| review-native-close-wait | `da8e98a8e` | native close 等待 | `20261026-032758-834138Z-review-native-close-wait` |
| review-platform-fixture-errors | `77b7997ab` | platform fixture 主失败保留 | `20261026-032759-236782Z-review-platform-fixture-errors` |
| review-settlement-test-cleanup | `1dcb9d711` | Settlement TWR | `20261026-032759-508719Z-review-settlement-test-cleanup` |
| review-stop-contract-cleanup | `7ba0f167e` | Stop 契约测试 TWR | `20261026-032759-761930Z-review-stop-contract-cleanup` |
| review-stop-owner-compaction | `5831c4da0` | `SETTLE_OWNER_TURN` 缺口 | `20261026-032800-082330Z-review-stop-owner-compaction` |
| review-stop-owner-compaction-red | `26c5b77aa` | 故意红测（未合入） | `20261026-032828-867851Z-review-stop-owner-compaction-red` |
| review-stop-receipt-boundary | `d8bae4b6c` | 迟到退出按历史回执 | `20261026-032800-450605Z-review-stop-receipt-boundary` |
| review-stop-receipt-boundary-red | `d80647e89` | 回执边界红测 | `20261026-032800-725354Z-review-stop-receipt-boundary-red` |
| runtime-release-version | `000c57c4a` | 4.0.0 版本 | `20261026-032800-971866Z-runtime-release-version` |
| stop-exit-ack-failure | `3f9ad60cf` | ACK 持久化失败 | `20261026-032801-220422Z-stop-exit-ack-failure` |
| stop-exit-contract | `12a07e3b8` | Stop 红测原点 | `20261026-032801-477553Z-stop-exit-contract` |
| stop-exit-daemon | `47492c20b` | Daemon 退出/取消/journal | `20261026-032801-741194Z-stop-exit-daemon` |
| stop-exit-platform | `637e4b477` | Gateway 远端退出/ACK | `20261026-032802-033433Z-stop-exit-platform` |
| stop-exit-runtime | `cde3c22c1` | execution exit ledger | `20261026-032802-307092Z-stop-exit-runtime` |
| stop-exit-ui | `5b350eb2d` | UI STOPPED/未确认 | `20261026-032802-561121Z-stop-exit-ui` |
| stop-local-exit-diagnostics | `e0b453bcc` | 本地退出安全日志 | `20261026-032802-943641Z-stop-local-exit-diagnostics` |
| stop-lsp-scope-exit | `09cdb4da4` | LSP stop scope | `20261026-032803-213936Z-stop-lsp-scope-exit` |
| stop-progress-abort | `31d243386` | progress 编码取消 | `20261026-032803-494177Z-stop-progress-abort` |
| stop-reconnect-journal-proof | `3feff2736` | journal recovery | `20261026-032803-764677Z-stop-reconnect-journal-proof` |
| stop-reconnect-no-reexecution | `049c680f1` | 重连不重复执行 | `20261026-032804-033808Z-stop-reconnect-no-reexecution` |
| stop-skill-exit-proof | `f1159ea31` | skill 退出证明 | `20261026-032804-275691Z-stop-skill-exit-proof` |
| subagent-continuation-fix | `ed177fb2b` | usage/计价/归一化 | `20261026-032804-552905Z-subagent-continuation-fix` |
| subagent-observability | `302ca14ea` | 前端 details/queue/usage | `20261026-032804-845221Z-subagent-observability` |
| thread-lifecycle | `4fa662da0` | Thread/Join 生命周期 | `20261026-032805-247229Z-thread-lifecycle` |

特别保留：

- 交接 workspace 归档：`.workspace/.archives/20261026-033430-028039Z-runtime-convergence`；全量 diff 在 `records/evidence/final-net-7ba0f167e/`，其 `net-a300679d9-to-7ba0f167e.diff` SHA256 为 `ac3f3375d25ec88a4f209edb2aae50bb23be0c20f4ef3fc608f29e44fa5ab73a`。
- 红测分支 bundle：`.workspace/.archives/20261026-032828-867851Z-review-stop-owner-compaction-red/records/records/red-branch-26c5b77.bundle`
  - SHA256 `f9a69fb60d59555891dbd366aa79ccc106515e24934604a155753d31d650ca80`
  - 可恢复：`git worktree add -b recover-red /tmp/recover-red <bundle>`（先 `git bundle verify`）。
- `stop-exit-contract` 未跟踪红测源：`.workspace/.archives/20261026-032801-477553Z-stop-exit-contract/records/records/untracked-red-HarnessRuntimeStopExitContractTest.java`
  - SHA256 `a747379763d5fb0f3ff7b71e15282e8019cceb92a795382efa904306dc5994ed`；195 行旧版本，已被 7ba0 的 655 行版本取代。

## 13. 首次接手检查清单

```sh
cd "$(git rev-parse --show-toplevel)"
git branch --format='%(refname:short)'
git worktree list
git status --short
git log --oneline --decorate -5 handoff
git diff --stat 7ba0f167eff6eb07b78b24880970880fb1e964f5..handoff
ps -eo pid,args | grep -E '\.workspace/|mvn|surefire|playwright' | grep -v grep
```

预期：

- 本地分支只有 `main`、`dev`、`handoff`。
- 只有根工作区；交接 workspace 需要按 4.1 重新创建。
- `git diff --stat 7ba0..HEAD` 显示 `HANDOFF.md` 与 16.2 列出的修复文件。
- 2026-10-26 接手时根仓库直接检出 `handoff`（用户切换），本轮提交就在该检出上完成；没有本任务的残留构建或写入进程。
- 无本任务构建/写入进程。

## 14. 授权边界

已由用户明确授权并在本次执行：

- 将全部有效改动收敛到一个交接分支。
- 在分支中提交本交接文档。
- 释放所有开发 workspace，仅保留 `main`、`dev`、`handoff` 三个本地分支。
- 推送 `handoff` 分支到 `origin/handoff`。

尚未授权、必须再次确认：

- 触发全量 CI。
- 合入/推送 `main`/`dev`。
- 创建/推送 `v4.0.0` tag、发布 Daemon Release 或镜像。
- 部署、生产数据库写入/迁移/修复、登录/设置变更。
- 任何新的真实模型请求。

## 15. 交接结论

- **分支收敛：完成。**
- **workspace 释放：完成（交接 workspace 在本文件提交后释放）。**
- **代码实现：已集成。**
- **内容 Review：生产代码差异与交付相关 scripts 已审，测试差异与 docs/build 未逐行审；3 个高优先级设计问题待用户决策（16.5）。**
- **最终精确 SHA 全量 CI：未运行。**
- **任务验收：未通过。**
- **发布：未开始。**

接手者应先完成 Review 与最终 CI，再进入发布；不要把本文件或历史绿色测试当作验收结论。

## 16. 2026-10-26 受限环境接手记录

### 16.1 环境与边界

- 环境没有 Docker、没有 `gh`、没有生产访问；JDK 21 在 `/opt/toolchains/jdk21`；系统 Node 为 v22。Web 的真实 Node IDE 反向流用例要求
  Node 24：本轮按官方 `SHASUMS256.txt` 校验后，把 Node v24.21.0（与 CI 一致）解包到会话临时目录，只在验证命令的 `PATH` 中使用，
  不修改系统环境。
- 数据库测试连本机 PostgreSQL 16（`127.0.0.1:55432`）：运行时临时把 Testcontainers 字段换成本地连接，结束时从备份恢复，从未提交。
  CI 使用 PostgreSQL 17 Testcontainers；需要容器内命令或 MinIO 的用例本机无法运行（见 16.6）。本机 PG 工具目录里有 `psql`，
  因此 V1 与共享探针 `fresh-install-probes.sql` 可以直接在 PG16 上执行（不经 Flyway）。
- 用户在本会话把根检出切到 `handoff` 并让我直接在该检出上工作，因此本轮普通提交都在根检出完成（不同于 3.2 的 worktree 约定）。
- 未执行：推送、全量 CI、`main`/`dev` 合入、tag/发布、真实模型请求、任何生产操作。

### 16.2 本轮提交（`7e067f915` 之后）

| 提交 | 内容 |
| --- | --- |
| `f70661628` | fix(daemon)：`process.exec` 启动被拒但 supervisor 已证明范围收敛时确认退出；`ProcessSupervisor.start` 新增 `ProcessScopeNotConvergedError` 契约 |
| `491bb6779` | docs(daemon)：wire 协议版本变化后旧 Daemon 在 HELLO 被拒、收不到在线更新，每台主机需重新执行安装命令 |
| `39b865c56` | fix(frontend)：定价条件“添加条件”菜单使用已定义的 i18n key（原先 context/serviceTier/date/time 显示原始 key） |
| `b6a2bcc95` | fix(frontend)：补齐定价时间条件 weekday 1..7 译名（ISO，1=周一，与后端 `DayOfWeek` 一致） |
| `644d0d537` | test(runtime)：`ThreadProcessorCompactionTest` 按 `5831c4da0` 的 STOPPED owner 语义改写（交接 HEAD 上该用例确定失败） |
| `d838fc3bf` | fix(runtime)：晚到退出确认补结算时只为 COMPLETED 回合冻结最终回答 |
| `ea7c26535` | fix(runtime)：Thread 已深删后的迟到远端 ACK 与本地确认一致按缺失处理，不再抛错 |
| `729a942a9` | perf(runtime)：新增 store 原语 `hasAppliedNotificationAfter`，未接纳通知判据不再读取整个命令历史 |
| `5a78b4c99` | perf(schema)：`harness_execution_exit` 补 `(thread_id)` 与部分 `(parent_execution_id)` 索引 |
| `1c3dd3e55` | fix(platform)：subagent 票据复核自身失败时保留原提交失败（suppressed） |
| `47a4f559d` | fix(platform)：执行过的模型名被模型组复用时该 Entry 按未计价处理，不让整段历史读取失败 |
| `23ac7d01f` | fix(scripts)：schema 检查脚本的清单守卫同步为 46 张业务表（此前主流程恒失败） |
| `e5312e0cb` | test(web)：反向流 20 MiB 用例只核对本用例 invocation 的 `abandonIde` |
| 本文件更新 | 本节 |

### 16.3 实际读到的范围

- 用 `git diff a300679d9 7ba0f167e -- <path>` 逐文件读完的生产差异：harness/runtime 29 个文件、harness/infra 2、schema V1、
  harness/environment 6、harness/provider 4、platform/runtime 7、share 3、web 2、daemon/src 13（`config.ts` 一行版本号除外）。
  大 diff 分窗读取：`EnvironmentDaemonServer` 603 行分 3 窗、provider 347 行分 2 窗；输出没有截断标记。
- 前端生产 29 个文件全部读完（usage 解析/合并、三态展示、STOPPED 未确认提示、排队补充消息、task 回执只读
  `details.kind == task.accepted`、生成契约；2 个 CSS 文件与 i18n `catalogs/chat.ts`：状态文案 key 全部静态引用且有定义，旧细分状态 key 与
  `data-processing` / `is-processing` 钩子无残留）。
- scripts 中会交付或驱动验收的部分已读：`scripts/daemon/install.sh`（阶段化失败报告、日志游标、令牌被拒识别与 Daemon 实际日志文本一致）、
  reliability `policy.mjs` / `report.mjs` / `run-agent-matrix.mjs`（未知费用不按 0 累计，按 unknown 展示并阻断）、E2E `lib/harness.mjs`
  与 `seed-and-harness.mjs` 的三态/`executionControl` 断言、仓库检查脚本。E2E case 与脚本测试文件没有逐行审。
- `0dad93b65` / `1dcb9d711` / `7ba0f167e` 三个清理切片：把每个提交前后的断言语句规范化后按多重集比较，业务断言逐条一致；
  减少的只是清理断言（executor `awaitTermination(5s)`、执行线程 `join(5s)`），它们收进 `schedulerCleanup` / `executionCleanup`，
  调用次数不少于原内联次数。没有做故障注入矩阵。
- 测试差异（约 14k 行）只读了本轮修改或判断所需的部分：`ExecutionExitSettlementTest`、`ExecutionExitResumeTest`、
  `HarnessRuntimeStopExitContractTest`、`ThreadActivityEvaluatorTest` 全文、
  `HarnessStoreJoinContract` 中执行代次与 NOTIFY_PARENT 原子交付的改动（`overlappingLeaseGenerationsConfirmIndependently` 明确把
  “新代确认后旧代仍未确认”固定为预期，与 H1 一致）、`ModelProcessorTest` 的 held-close 用例、`ExecutionConcurrentDeletionTest`、
  命令 store 契约、`SubagentTaskRunnerTest`、`UsageCostProjectionServiceTest`。
- 已核对、不构成问题：晚到退出确认与压缩回合交错时，`settleClosedTurn` 不会把压缩 TURN_END 冻结为委派终态——手动压缩在存在未完成 Join
  时被拒绝（`ManualCompactionControl.manualDecision`），软阈值压缩只在有排队输入时运行，其余压缩都带 `continueModel`。

### 16.4 已修复缺陷

1. **`process.exec` 启动失败永久留下未确认的远端执行。** 两套 supervisor 在 `start` 失败时已经保证：除“失败范围未被证明收敛”
   （组合错误）外，其它拒绝都没有遗留进程；但执行器把任何 `start` 拒绝都当成“不能确认”，于是 Bash 不存在、spawn 前已取消、
   helper 建立失败等常见失败都让 Studio 侧 REMOTE 事实永远 `UNCONFIRMED`，进而阻塞 Join 结算与 Stop 后恢复（见 H1）。
   修复：typed error 只用于未证明收敛的分支，执行器对其它拒绝确认退出。测试：执行器正反两例、POSIX supervisor 与 Windows（mock）
   supervisor 的未收敛分支；均做过变异验证（还原实现后测试失败）。
2. **定价条件“添加条件”菜单显示原始 i18n key**（v2.0.0 上游缺陷，`AiModelPricingEditor.tsx`）。测试 + 变异验证。
3. **定价时间条件 weekday 复选框显示原始 key**（v2.0.0 上游缺陷，目录从未定义 `ai.catalog.form.weekday.*`）。测试 + 变异验证。
4. **文档**：协议 5 下旧 Daemon 无法在线更新、必须重装（`docs/operations/environment-daemon.md`）。
5. **交接 HEAD 上有确定失败的测试。** `5831c4da0`（第 13 轮 CI 之后）让 STOPPED owner 的 `SETTLE_OWNER_TURN` 结算只推进 version、
   不请求 Work，并同步改了 PG 版 `PostgresqlCompactionChildTest`，但遗漏了内存版
   `ThreadProcessorCompactionTest.stoppedParentSettlesCompactionResultWithoutWorkOrQueuedInputConsumption`（仍期望 STOPPED owner
   物化压缩结果）；harness/runtime 2270 个测试中它确定失败，全量 CI 会因此变红。该用例构造的“STOPPED 且压缩回合仍 open”只能靠直接改
   控制字段得到（真实 Stop 会关闭 owner 的 open 回合并停止压缩子执行），已按新语义改写：回执冻结、owner version 只推进一次、
   不请求 owner Work、历史与压缩义务保持、排队输入保留。旁证：此状态下若仍有 THREAD Work 到达，处理器会物化压缩结果并保持 STOPPED。
6. **晚到确认对 FAILED 回合冻结最终回答**（原 L1）。`ExecutionExitLedger.settleClosedTurn` 用 TURN_END 前紧邻的 ASSISTANT 消息作为
   `finalAnswerEntryId`，不区分结局；普通路径只在 COMPLETED 时给出。成功响应但截断（LENGTH）或过滤（FILTERED）的 FAILED 回合，
   冻结结果取决于物化与退出确认谁先提交。修复后只在 `end.outcome() == COMPLETED` 时计算。测试：held-close 用例参数化为
   COMPLETE/LENGTH/FILTERED，并断言 TURN_END 结局与 ASSISTANT 结尾；变异验证（还原后 LENGTH/FILTERED 两例失败）。
7. **深删后的迟到远端 ACK 抛错**（原 L3）。`ToolExecution.confirmRemote` 在 Thread 缺失时抛 `IllegalStateException`，回调隔离层
   记录一条“确认失败”日志；本地 `confirm` 则把合法深删视为无事实可确认。现与本地一致返回，不回写、不伪造确认。测试 + 变异验证。
8. **快照/树读取与收敛判据读取整个命令历史**（原 M2）。`ThreadInputDemand.hasInputDemand`、单 Thread 快照、树快照（持树锁、逐 Thread）
   与 INPUT 规划都用 `loadCommandsByThread`（`select *` 含 payload）只为判断水位之后是否有 APPLIED 通知。新增 store 原语
   `hasAppliedNotificationAfter(threadId, afterSequence)`（PG：`select exists(... thread_id = ? and sequence > ? and
   command_type = 'NOTIFICATION' and applied_entry_id is not null)`），四处调用改走它。契约测试在内存与 PG 两种 store 上覆盖
   APPLIED/QUEUED/CANCELLED/非通知/其它 Thread；变异验证（内存去掉 sequence 或状态条件、PG 去掉 applied 条件均失败）。
   `loadCommandsByThread` 在生产代码中已无调用，只剩测试检查用途；是否从 store 接口删除留给后续（需改约 40 处测试）。
9. **`harness_execution_exit` 缺少全量索引**（原 M3）。补 `idx_harness_execution_exit_thread (thread_id)` 与部分索引
   `idx_harness_execution_exit_parent (parent_execution_id) where parent_execution_id is not null`，服务深删的按 Thread 删除、
   删除 Thread 时的外键检查与自引用外键检查（NO ACTION 外键对每个被删父行各查一次）。本机 PG16 `EXPLAIN` 确认两种外键检查形状都走新索引。
   V1 在 4.0.0 中本来就已改写（第 9 节 Schema 升级），不新增升级约束。
10. **票据复核失败掩盖提交失败**（原 L5）。`SubagentTaskRunner` 在提交失败后的回放复核自身抛错时，原提交失败丢失；现作为 suppressed
    保留。测试 + 变异验证。
11. **模型名被模型组复用后整段历史读取失败**（原 L2，v2.0.0 上游）。模型可改名或删除，名称随后可被模型组复用；
    `UsageCostProjectionService.pricing` 遇到同名 GROUP 抛 `IllegalStateException`，Session/Thread entries 读取整体失败。现按 catalog
    中已没有该单模型处理为未计价（缺席，不伪造 0）；`docs/modules/platform.md` 同步。测试 + 变异验证。
12. **schema 检查脚本恒失败。** `fdc1267b0` 把 `harness_execution_exit` 加入 `BUSINESS_TABLES`（46 项）并把单元测试改为 46，但脚本自身的
    清单守卫仍要求 45，`check-canvas-project-schema.mjs` 主流程在接触 Docker 前就抛错；`scripts/README.md` 也仍写 45。已同步为 46。
    本机现在只因缺少 Docker socket 失败（预期），完整检查需在有 Docker 的环境运行。
13. **反向流用例失败放大**（原 L6，测试隔离）。前序 Node 用例失败时遗留的异步 `abandonIde` 会在 mock 重置后计入
    `streamsTwentyMiBBodyThroughRealReverseWebSocket`。该用例现在只核对自己 invocation 的 `abandonIde`。用系统 Node 22 复跑：
    只剩 3 个依赖 Node 24 的 Brotli 用例失败，20 MiB 用例不再连带失败；Node 24 下 5/5 通过。

### 16.5 待处理发现

**H1 未确认退出没有收敛出口（活性，需用户决策）。** `ExecutionExitLedger.hasUnconfirmed` 同时挡住普通 Join 结算
（`ThreadLifecycleCoordinator.isQuiescent`）、Stop 后恢复（`AcceptCommandsControl.resumeConverged`，`EXIT_UNCONFIRMED`）并让
活动状态恒为 `RUNNING`（`ThreadActivityEvaluator.evaluate`）。`loadUnconfirmedExecutions` 按 Thread 读取全部代次，不区分已被新
attempt 取代的旧代次。没有任何代码在接管、启动或超时后确认孤儿事实，因此以下情况会让 Thread 永久 RUNNING、父 Join 永久 WAITING、
停止后无法恢复（只能删除 Thread）：JVM 崩溃/kill/OOM 中的 Model 或 Tool；Daemon 实例更换或重启；重连时 INVOKE 实际未送达
（Daemon 对未知 CANCEL 静默）；tombstone 超过每 Environment 1024 条被淘汰；LSP 服务卡死且不退出；进程范围无法证明收敛；退出证据真实存在但确认事务本身失败
（数据库瞬时故障、主备切换、死锁回滚等，`ModelExecution` / `ToolExecution` 的 `confirm` 只记录日志，此后没有任何路径再次确认）。
这与“不得 force-clear/伪造 ACK”的约束一致，但约束本身没有给出出口。可选方向（都需要用户确认）：
用户显式“放弃确认”并记为独立事实（不冒充退出）；以宿主死亡作为本地代次的证据（例如 `cluster_node` 实例被替换）；
Daemon 同实例 READY 时上报“当前运行中 invocation 集合”（journal 的 RUNNING 条目不淘汰）作为不在运行的正向证据。

**H2 同实例重连取消全部在途远端调用（行为变化，需确认是否有意）。** `EnvironmentDaemonServer.recoverActiveInvocations` 对
INVOKE 发送于旧连接代际的所有未终结调用设 `cancelled = true` 并发送 CANCEL；旧实现是以同一 invocationId 重发 INVOKE、由 Daemon
journal 去重。于是一次控制连接抖动就会取消正在运行的命令（例如长构建），模型看到 `Cancelled` + “结果不确定、请核实副作用”
（`ToolExecutionGateway.classify`）。Javadoc 的理由是 journal 终态有上界、不能永久去重；但 RUNNING 条目不淘汰，可考虑
只查询/重放、不重新执行的恢复消息，而不是一律取消。`docs/modules/harness-environment.md` 把它写成有意设计，但“物理连接失效**不**终结
在途调用”的表述容易误读：断连本身不终结，同实例重连后的 CANCEL 仍会停止正在运行的执行范围。线上 IDE 断流的根因尚未定位（第 9 节）；若 Daemon 控制连接也有同类断流，影响会被放大。

**H3 `openAiChatIncludeUsage=false` 与强制 usage 冲突（配置缺陷，需用户决策）。** 四个累积器现在缺少完整 usage 即
`INVALID_RESPONSE: missing complete usage`；但 `OpenAiChatConfiguration` 仍接受 `openAiChatIncludeUsage=false`，
`docs/modules/harness-provider.md` 也仍把它列为可配置项。设为 false 时编码器仍发送 `stream_options`（`include_usage: false`，
`OpenAiChatRequestEncoder`），所以它也帮不了拒绝 `stream_options` 的上游；只有无条件返回 usage 的上游还能工作。需要决定：删除该选项
（运行时恒发 `include_usage=true`，同时决定已有配置里的该字段如何处理），或在配置校验期拒绝 false，并同步配置同步、文档与测试。

**M1 强制 usage 的兼容面。** 任一上游或代理省略 usage（或 Anthropic 缺 input/output、Responses 缺 input/output）都会让模型调用
整体失败，此前按 0 记录。发布前应确认生产 Provider 全部返回完整 usage，并在发布说明中写明。

**M4 每次退出确认都取根树锁与整条祖先链行锁。** `ModelExecution` / `ToolExecution` 的确认事务先 `lockAncestorChain`
（树 advisory 锁 + 全部祖先 Session/Thread），即使 Thread 为 RUNNABLE 且没有 Join 需要结算；这让每次 Model/Tool 调用多一次树锁，
与 K3 收窄锁范围的目标相反。可先只锁自身完成确认，确有结算需要时再进入取树锁的事务；涉及锁序与并发结算，建议与 H1 一起设计。

**M5 已淘汰 tombstone 的迟到退出确认会关闭连接。** `EnvironmentDaemonServer.handleExecutionExited` 对既不在途、也不在 tombstone 中的
invocation 抛 `DaemonProtocolException` 并关闭连接；`MAX_INVOCATION_TOMBSTONES` 的 Javadoc 只说淘汰后迟到确认“不再交付”，而
`EnvironmentDaemonServerTest.evictedTombstoneDropsLateExitConfirmation` 明确断言关闭连接，所以关闭是有意设计，不是实现与文档矛盾。
风险在于组合：Studio 每 Environment 的 1024 条 tombstone 与 Daemon journal 的 1024 条终态按不同事件计数（Studio 还计入实例接管、
从未送达的 expire），退出确认又可能比终态晚很久（卡死的 LSP 请求在服务回收时才确认），因此可能出现 Studio 已淘汰、Daemon 仍发送的
确认；连接被关后叠加 H2 会取消该 Environment 的全部在途调用。可考虑对此类迟到确认只记录不断连，或让未确认退出的 tombstone 单独保留到
确认为止（另设上界）；两者都改变协议严格性，需要与 H2 一起决定。

**L4** `scripts/daemon/tests/support/pty_run.py` 让脚本测试重新依赖 `python3`（`release-fixture.mjs` 调用），与此前“脚本测试统一 Node”
的方向不一致；可评估用 `script` 命令或接受该例外。

### 16.6 本轮验证证据

均为本地结果，不等于 CI。PG 用本机 16；排除需要 Docker/MinIO 的 `WebApplicationStartupSubprocessTest` 与需要容器内 `psql` 的
`FreshInstallSchemaContractTest`（其探针内容见下文单独执行）。

- 全量 `mvn verify`（含 JaCoCo 门禁，Node 24 在 `PATH`）：
  - 代码 HEAD `644d0d537`：BUILD SUCCESS，25 个模块，26:46；Surefire 计数 common 145、share 177、work 9、
    harness/environment 397、harness/runtime 2270、provider 1202（1 跳过）、harness/infra 436、platform-plugin-api 126、
    platform/runtime 2167（2 跳过）、canvas-core 44、canvas-infra 172、project 255、ide 93、minimax-mavis 201、opencli 134、
    media 25、comfyui 112、web 881（2 跳过），0 失败（schema 模块唯一的测试类被排除）；14 个模块执行了 JaCoCo check 且全部满足。
  - 代码 HEAD `e5312e0cb`（本轮全部代码提交之后）：BUILD SUCCESS，27:08；与上一行逐模块相同，只有 harness/runtime 2273、
    harness/infra 437、platform/runtime 2169（2 跳过）分别多出本轮新增的 3、1、2 个用例；0 失败，JaCoCo check 同样全部满足。
- 本轮新增修复的定向测试与变异验证：harness/runtime（`ModelProcessorTest` 108、`ExecutionConcurrentDeletionTest` 5、
  `ThreadInputDemandTest` 4、`InMemoryCommandTest` 28、`HarnessStoreContractTest` 4）、PG（`PostgresqlCommandTest` 28、
  `PostgresqlHarnessSchemaTest` 15）、platform（`SubagentTaskRunnerTest` 19、`UsageCostProjectionServiceTest` 11）、web
  （`IdeReverseStreamEndpointIntegrationTest` Node 24 5/5）全部通过；L1、L3、M2（内存两种、PG 一种）、L2、L5 的变异都让对应测试失败，
  还原后通过。
- V1 + `fresh-install-probes.sql` 在本机 PG16 新库上直接执行：`PASS 205 database contract assertions`（与
  `FreshInstallSchemaContractTest.PROBE_ASSERTION_COUNT` 一致）。这不是 Flyway + PG17 的同一路径。
- Maven reactor `validate`（Spotless + Checkstyle，0 违规）通过；生成契约漂移测试 `TsContractArtifactTest`、`DaemonContractArtifactTest` 通过。
- 前端（`e5312e0cb`，Node 24.21.0，与 CI 一致）：`typecheck`、`typecheck:test`、`lint`、`lint:css` 通过；vitest（`TZ=UTC`）3442 通过 /
  2 跳过；Playwright 全量（`test:browser`）258 通过。
- Daemon（`e5312e0cb`，Node 24.21.0；`f70661628` 之后未再改动）：`npm run check` 通过；vitest 1272 通过 / 24 跳过（平台相关；Node 22
  结果相同）。
- 仓库脚本测试（CI 同一组 87 个文件，`e5312e0cb`，Node 24.21.0）：711 个，708 通过、3 个因无 Docker 跳过、0 失败。
- 文档门禁（26 篇）、敏感数据门禁、`git diff --check 7ba0f167e..HEAD`、`RELEASE_TAG=v4.0.0` 版本对齐在 `e5312e0cb` 上通过。
- 未运行：全量 CI、Docker 相关用例（`check-canvas-project-schema.mjs` 完整流程、`FreshInstallSchemaContractTest`、
  `WebApplicationStartupSubprocessTest`）、Windows/macOS 原生安装与更新、真实模型 E2E。

### 16.7 剩余工作

1. 用户决策 H1–H3（会改变设计不变量或产品行为），然后按决定实现或记录为已知限制；M4、M5 依赖 H1/H2 的决定。
2. 在有 Docker 与 `gh` 的环境：运行 `node scripts/dev/verify/repository/check-canvas-project-schema.mjs` 与上面三个 Docker 用例；
   冻结最终 SHA，运行 `validate_only=true` 全量 CI 并核对 `head_sha`（第 11 节）。
3. 未审部分：全部测试差异（约 14k 行）、E2E case 与脚本测试文件、docs/build；M1 的生产 Provider 核对；L4。
4. 推送 `handoff`、合入 `main`/`dev`、`v4.0.0` tag 与 Daemon Release 仍需用户单独授权。
