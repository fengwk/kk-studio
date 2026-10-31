# D1core Runtime & HTTP Incremental Read Implementation Notes

## 1. 提交清单 (Git Commits)
- `fd2ae06bb1f15a0c876b9e07476eff712c23ace0`
  - `feat(runtime): replace full entry path with narrow current state in thread snapshot`
  - 重构 `ThreadSnapshot`（`EntryCurrentState` + `EntrySlice`）、`ThreadTreeNode` 记录、Driver/Probe/Lock/Classifier 改用 `EntryCurrentState`，支持 `getThreadSnapshot(id, afterEntryId)`。
- `6d108470ef87ae22da28de2cb4b5b7b0665b6952`
  - `merge(infra): integrate narrow postgresql entry reads`
  - 合并 `worktree/t-d1-pg-current`（ready SHA `8cc3fb0a8d5ca43321741d0cb8b88c4be4e367e4`）。
- `e4fb5f4b726fa3e5d0c20365168b0b69df23ab40`
  - `feat(web,platform): adapt callers, endpoints, and tests to narrow entry state and incremental snapshot`
  - 适配 Share DTO、Web 增量端点与切片成本投影、Platform/Project 调用方与测试、E2E L1 用例及文档。
- `f241ac1ac2e281c2231e6496dbf67e061319779d`
  - `test(runtime): cover edge branches of ThreadSnapshot constructor validation`
  - 补齐 `ThreadSnapshot` 构造校验边界测试（空切片 base 校验、非法切片尾、不匹配 head、非法 join 计数）。

## 2. 定向测试与验证
- **harness/runtime**:
  - 命令: `flock /tmp/tmpdir-7022833470427643707/java-a.lock env JAVA_HOME=$JAVA_HOME_21 mvn -o -pl harness/runtime -am test jacoco:report`
  - 结果: 1174 passed, 0 failures, 0 errors, 0 skipped
  - 覆盖率: `ThreadSnapshot` LINE 覆盖率 98.73% (78/79 lines), 模块 JaCoCo check 通过
- **harness/agent-builtin**:
  - 命令: `flock /tmp/tmpdir-7022833470427643707/java-a.lock env JAVA_HOME=$JAVA_HOME_21 mvn -o -pl harness/agent-builtin -am test -Dtest=BuiltinAgentDriverContractTest,HarnessRuntimeSnapshotTest,HarnessRuntimeThreadTreeContract,ThreadContextClassifierTest`
  - 结果: 53 passed
- **harness/infra**:
  - 命令: `flock /tmp/tmpdir-7022833470427643707/java-a.lock env JAVA_HOME=$JAVA_HOME_21 mvn -o -pl harness/infra -am test -Dtest=PostgresqlEntryCurrentQueryTest,PostgresqlEntrySliceQueryTest`
  - 结果: 7 passed
- **platform/runtime**:
  - 命令: `flock /tmp/tmpdir-7022833470427643707/java-a.lock env JAVA_HOME=$JAVA_HOME_21 mvn -o -pl platform/runtime -am test -Dtest=HarnessStreamCatchupServiceTest,InteractionQueryServiceTest,ModelRequestDebugServiceTest,PluginPromptTaskServiceTest,ProviderRequestPreviewServiceTest,SubagentTaskRunnerTest,HarnessOwnerQueryServiceTest`
  - 结果: 106 passed
- **apps/project**:
  - 命令: `flock /tmp/tmpdir-7022833470427643707/java-a.lock env JAVA_HOME=$JAVA_HOME_21 mvn -o -pl apps/project -am test -Dtest=IssueThreadExecutionTest,IssueLifecycleIntegrationTest,IssueReconcilerIntegrationTest`
  - 结果: 20 passed
- **web**:
  - 命令: `flock /tmp/tmpdir-7022833470427643707/java-a.lock env JAVA_HOME=$JAVA_HOME_21 mvn -o -pl web -am test -Dtest=StudioHarnessCommandBatchControllerTest,StudioHarnessThreadControllerTest,HarnessRuntimeResponseMapperTest,ProviderRequestPreviewIntegrationTest,ThreadInteractionRootPostgresIntegrationTest,TsContractArtifactTest`
  - 结果: 68 passed（含 `StudioHarnessThreadControllerTest` 37 passed、`HarnessRuntimeResponseMapperTest` 17 passed、`TsContractArtifactTest` 3 passed）
  - 覆盖率: `StudioHarnessThreadController.getSnapshot` LINE 覆盖率 100%
- **静态格式与代码规范**:
  - 命令: `flock /tmp/tmpdir-7022833470427643707/java-a.lock env JAVA_HOME=$JAVA_HOME_21 mvn -o checkstyle:check spotless:check`
  - 结果: 0 violations
- **E2E Case 注册与契约用例**:
  - 命令: `node --test scripts/dev/verify/e2e/api-contract-cases.test.mjs`
  - 结果: 6 tests passed（新增 `thread_snapshot.incremental_read` L1 用例）

## 3. 未验证项 (Unverified Items)
- **真实 HTTP E2E 运行**: 未启动本地 PostgreSQL 容器及 Web 应用进程执行端到端 HTTP 测试（`./scripts/dev/verify/e2e/run.sh`）；由集成推送后的 CI 统一调度与全量验收。
