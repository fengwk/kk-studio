# Project 模块

Project 是业务状态的事实源；Canvas 组织资源，不决定 Issue 的执行顺序。项目把工作阶段定义在一份
`workflow` JSON 中，Issue 保存当前阶段，Run 记录一次已接受执行的历史坐标；Agent 身份由
`Issue + Agent` 唯一绑定到一条持久 Thread，同一阶段的预算按 `Issue + state` 计数，
不会因为切换 Agent 重置。先从[系统设计](../system-design.md)理解模块边界；
完整的事务边界和表关系见[Canvas / Project](../canvas-project.md)。

[`project/pom.xml`](../../project/pom.xml) 声明独立 Maven 模块；当前模块只放可单独验证的
领域规则，不连接数据库、不装配 Spring、不发起 Harness 命令。应用事务负责锁序、
权限、Session/Thread 归属、工作队列和外部调用。

## 工作流配置

[`ProjectWorkflowJsonCodec`](../../project/src/main/java/fun/fengwk/kkstudio/project/domain/ProjectWorkflowJsonCodec.java)
严格解析 `{"states":[...]}`：拒绝重复字段、未知字段、尾随内容和非法类型；编码为确定性 JSON，
可用于请求指纹。每个 state 使用项目内唯一的大写自然编码，不另建状态 ID。
`INIT`、`BLOCKED`、`DONE` 必须存在；正常边只指向已声明阶段，不通往 `BLOCKED`。
启用工作阶段须从 `INIT` 可达，且存在到 `DONE` 的正常路径。

[`IssueStateTransitions`](../../project/src/main/java/fun/fengwk/kkstudio/project/domain/IssueStateTransitions.java)
区分正常转移、业务阻塞与恢复、以及从 `DONE` 显式重开。问卷等待与人工暂停不是工作流状态。

## 预算与运行记录

[`IssueStageBudget`](../../project/src/main/java/fun/fengwk/kkstudio/project/domain/IssueStageBudget.java)
只计本 Issue、本阶段、序号超过授权高水位的全部 Run；失败、取消和 UNKNOWN 也消耗一次。
授权重置只能将高水位推进到 `nextRunOrdinal - 1`。它限制**新 Run**，不限制已接受 Run 的恢复。

[`IssueAgentThread`](../../project/src/main/java/fun/fengwk/kkstudio/project/domain/IssueAgentThread.java)
冻结 `(issueId, agentName) -> threadId` 身份；
[`IssueRun`](../../project/src/main/java/fun/fengwk/kkstudio/project/domain/IssueRun.java)
冻结 `(startEntryId, endEntryId]`、Session/Thread、阶段及终态事实。每 Issue 唯一活动 Run
由数据库部分唯一索引最终保证；领域层不能代替 Entry 父链、跨表外键或锁序校验。

执行该模块的领域与覆盖率检查：

```bash
env JAVA_HOME=$JAVA_HOME_21 mvn -pl project -am verify
```
