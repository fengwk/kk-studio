# 稳定传输契约生成

把显式选定的后端传输契约（DTO 字段、enum wire 值）用真实 HTTP Jackson 3 mapper 生成到
[stable-contracts.ts](../../../../frontend/src/shared/api/generated/stable-contracts.ts)，并让漂移在 CI 中失败。
生成器与真实 mapper 都在
[web/src/test/java/fun/fengwk/kkstudio/web/generated/](../../../../web/src/test/java/fun/fengwk/kkstudio/web/generated/)，
由前端各 `shared/api/contracts/*` 入口重新导出。

范围是有限的显式清单：`PluginConnectionCheckDTO` 及其三个维度枚举、`PluginConnectionCheckRequestDTO`、
`CanvasFunctionVersionDTO`、`CanvasFunctionRunRequestDTO`、`CanvasFunctionCancelRequestDTO`、`ConfigSyncKind`。
清单之外的类型/枚举固定失败，不推断业务状态机、不解析 Java 源码、不用 JSON 样本猜类型。

## 校验（默认）

```bash
scripts/dev/verify/generated-contracts/check.sh
```

只读：重新生成并与提交的产物逐字符比对，缺文件、字段/enum 变化或产物被手改都会失败，绝不修改源码。
`mvn verify` 会执行同一个 `StableTsContractArtifactTest`，因此正常 CI 已覆盖这条门禁。

## 更新

```bash
scripts/dev/verify/generated-contracts/write.sh
```

会用真实 mapper 重写生成产物。提交前必须人工审阅 diff，并同步更新依赖该产物的前端类型/解码器与测试。

两个入口都需要 JDK 21（`JAVA_HOME_21`，回退 `JAVA_HOME`），并且只运行 `-pl web -am` 的定向 reactor，
不替代全量验证。
