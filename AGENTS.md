# Current AGENTS.md Maintenance

- 本文件只记录最小必要的仓库级约束；禁止写背景说明、过程描述和长篇约定，新增内容必须足够稳定且影响整个仓库。

## Repository Conventions

- **当前仓库处于起步阶段，因此如果有更好的方向或实现，你可以进行彻底、干净的重构，不必担心代码或数据的兼容性问题，一旦你决定重构就应该抛弃所有历史包袱，移除所有死代码，让重构之后像第一次编写一样。**
- Java 代码体内禁止使用全限定类名，优先使用 import；仓库级 Checkstyle 会在 Maven `validate` 阶段检查这条规则。
- 关键逻辑新增或重构时，使用 JaCoCo 等覆盖率报告做指标化度量；核心路径覆盖率目标 ≥ 90%，分支覆盖率作为参考。
- 测试代码保持清晰；长篇或结构化测试数据优先抽取到 `src/test/resources` 下按包结构组织的资源文件中。
- 复杂基座包及其测试基座应提供清晰注释；对外边界、分层职责与维护约定优先放在包级注释或公共基座类注释中。
- DTO/DO/配置对象等 JavaBean 优先使用 Lombok；需要日志时优先 `@Slf4j`。
- 新增依赖必须是直接使用的能力；不因 starter/JUnit 的传递依赖告警去平铺无关依赖。

## Java Formatting

- `share` / `core` / `web` 的权威格式工具是 Spotless：Google Java Format `1.18.0`（GOOGLE）+ `removeUnusedImports` + importOrder `#,,fun.fengwk.kkstudio,javax,java`。
- `harness/*` 当前未挂载 Spotless，以 Google Java Format `1.18.0` 为准。
- pure GJF dry-run 与 Spotless importOrder 不完全一致时，以 Spotless 配置为准。
- 提交前只格式化本切片实际改动的 Java 文件；禁止顺手全仓格式化无关历史文件。
- 推荐命令：
  - `share` / `core` / `web`：`env JAVA_HOME=$JAVA_HOME_17 mvn -pl <module> spotless:apply`
  - `harness/*`：对改动文件执行 GJF `1.18.0 --replace`
  - 检查：`spotless:check`；harness 用 GJF `--dry-run --set-exit-if-changed`
- 根 POM 使用 `ratchetFrom=origin/master` 限制 Spotless 检查范围；全仓格式化债务清理需显式临时去掉 ratchet，并在隔离 worktree 完成。
- git worktree 中 Spotless ratchet 可能因 JGit 无法定位仓库失败；此时用无 ratchet 的定向 `spotless:check`/`apply`，或 `-Dspotless.check.skip=true` 后补做定向格式检查。

## Docs

- 项目文档按照当前文档风格维护到 `./docs/`。
- 技术方案文档不设置版本与最新代码保持一致。
- 技术方案文档必须上下文无关，只描述当前生效的职责、结构、协议、约束与实现方案。
- 技术方案文档只写与实现目标相关的方案和系统设计，不写不做什么，不写被否决方案，不写会话过程或依赖历史版本才能理解的内容。
- 技术方案文档必须保证一个没有任何历史上下文的 Agent 也可以直接阅读并接手工作。
