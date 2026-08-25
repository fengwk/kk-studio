# Current AGENTS.md Maintenance

- 本文件只记录最小必要的仓库级约束；禁止写背景说明、过程描述和长篇约定，新增内容必须足够稳定且影响整个仓库。
- **Design and coding principles: Less is More.**

## Repository Conventions

- **当前仓库处于起步阶段，因此如果有更好的方向或实现，你可以进行彻底、干净的重构，不必担心代码或数据的兼容性问题，一旦你决定重构就应该抛弃所有历史包袱，移除所有死代码，让重构之后像第一次编写一样。**
- Java 代码体内禁止使用全限定类名，优先使用 import；仓库级 Checkstyle 会在 Maven `validate` 阶段检查这条规则。
- 关键逻辑新增或重构时，使用 JaCoCo 等覆盖率报告做指标化度量；核心路径覆盖率目标 ≥ 90%，分支覆盖率作为参考。
- 测试代码保持清晰；长篇或结构化测试数据优先抽取到 `src/test/resources` 下按包结构组织的资源文件中。
- 复杂基座包及其测试基座应提供清晰注释；对外边界、分层职责与维护约定优先放在包级注释或公共基座类注释中。
- DTO/DO/配置对象等 JavaBean 优先使用 Lombok；需要日志时优先 `@Slf4j`。
- 新增依赖必须是直接使用的能力；不因 starter/JUnit 的传递依赖告警去平铺无关依赖。
- 任何有意义的测试请沉淀为单元测试和自动化集成测试。
- 编写测试的同时在注释中描述测试意图，便于后续接手者判断此测试是否仍然有效。

## Java Formatting

- 全仓权威格式工具是 Spotless：Google Java Format `1.18.0`（GOOGLE）+ `removeUnusedImports` + importOrder `#,,fun.fengwk.kkstudio,javax,java`。
- 根 POM 在 `validate` 阶段对所有模块执行 `spotless:check`；格式不通过则构建失败。
- Java 的所有控制流分支体（`if` / `else` / `for` / `while` / `do`）必须显式使用 `{}`；仓库级 Checkstyle 在 `validate` 阶段检查 main 和 test 源码。
- 提交前只格式化本切片实际改动的 Java 文件：`env JAVA_HOME=$JAVA_HOME_21 mvn -pl <module> spotless:apply`。
- 禁止顺手全仓格式化无关历史文件；全仓对齐仅在独立格式化切片中进行。

## Docs

- 项目文档按当前风格维护到 `./docs/`，与最新代码保持一致，不另设文档版本。
- 技术方案文档必须自洽可读：只描述当前生效的职责、结构、协议、约束与实现；不写否决项、会话过程或依赖历史上下文才能理解的内容。
- 文档质量入口：`node scripts/docs/check.mjs`。

## E2E

- 端到端回归入口：`./scripts/e2e.sh` 或 `npm --prefix frontend run e2e`；矩阵实现为 Node：`scripts/e2e/run-matrix.mjs`。
- 事实源文档：`docs/operations/development-and-testing.md`。精确 inventory 以 matrix 的 `--list` / `--docs` 输出为准；前端/API 契约变化时同步矩阵 case 与该文档的分类和入口说明。
- 默认只跑无成本 L1 API 矩阵；真模型/tool/branch/UI 需显式开关（`--real` / `--with-tools` / `--ui`）。报告与截图写入 `reports/e2e/`（已 gitignore），以 `reports/e2e/latest/report.md` 为最近一次可读结论。
