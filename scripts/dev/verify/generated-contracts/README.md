# 传输契约生成

前端的全部 HTTP 传输类型由
[contracts.ts](../../../../frontend/src/shared/api/generated/contracts.ts) 提供，它从 Web 控制器签名与生产
HTTP Jackson 3 mapper 生成，漂移在 CI 中失败。生成器与产物测试在
[web/src/test/java/fun/fengwk/kkstudio/web/generated/](../../../../web/src/test/java/fun/fengwk/kkstudio/web/generated/)。

生成器以 `@RestController` 处理方法的 `@RequestBody` 参数（请求方向）、返回值（响应方向）和错误响应上下文里的
结构化载荷为根，沿属性闭包收集 share 包下的 DTO 与枚举：

- 属性名、顺序与枚举 wire 值来自真实 mapper；未标 jspecify `@Nullable` 的属性必填且非空。
- 写出方向 `@JsonInclude(ALWAYS)` 的可空属性写作 `T | null`，其它可空属性写作 `?: T | null`。
- Java `long`/`Long` 在响应中是十进制字符串，只读入的请求属性写作 `number`；`Instant` 是 epoch 秒数字。
- `@JsonTypeInfo` 密封类型生成带判别字面量的子类型与联合。
- 会改变写出形态的定制（未登记的自定义序列化器、格式化、unwrapped、any getter、契约包外的对象类型）直接失败，
  不回退成 `unknown`。登记的序列化器只有两个：`DecimalStringSerializer` 把价格 decimal 写作 `string`，
  `AgentModelRatePatchDTOSerializer` 只省略未声明的字段，因此该 DTO 的全部属性可选。

Java 侧是带 `type` 字段的扁平类（如 Harness 命令、交互、上传）时，前端在 `shared/api/contracts/*` 以同名判别联合
细化；其余代码直接从生成物导入，不手改生成物。

Daemon 的 [protocol-contract.ts](../../../../daemon/src/generated/protocol-contract.ts) 由
[harness/environment](../../../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/daemon/)
测试源码的 `DaemonContractGenerator` 从 Java 协议常量生成：协议与 catalog 版本、payload 上限、错误码，以及消息类型、
操作系统、架构、上传票据状态与更新阶段的 wire 值。Daemon 的严格解码器只引用这些常量；字段形状仍由两端解码器各自实现，
并由共享 fixture 对拍。

## 校验（默认）

```bash
scripts/dev/verify/generated-contracts/check.sh
```

只读：重新生成并与提交的产物逐字符比对，缺文件、类型变化或产物被手改都会失败，绝不修改源码。
`mvn verify` 会执行同样的 `TsContractArtifactTest` 与 `DaemonContractArtifactTest`，因此正常 CI 已覆盖这条门禁。

## 更新

```bash
scripts/dev/verify/generated-contracts/write.sh
```

重写两个生成产物。提交前必须人工审阅 diff，并同步更新依赖它们的前端细化、解码器与测试。

两个入口都需要 JDK 21（`JAVA_HOME_21`，回退 `JAVA_HOME`），并且只运行 `-pl web -am` 的定向 reactor，
不替代全量验证。
