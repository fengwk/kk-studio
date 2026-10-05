# Agent Rules

- 本文件只记录稳定、必要的仓库级约束，不写背景和执行过程。

## Design and Refactoring

- **KISS / Less is More：选择满足当前需求的最简单方案，不引入无实际用途的抽象、配置和扩展点。**
- 仓库处于起步阶段，优先正确、干净的最终设计，不为历史代码、接口或数据保留兼容层、别名和双实现。
- 重构应完整更新范围内的调用方、测试、Schema 和文档，删除被替代的代码、依赖与说明，不顺带重构无关功能。
- 允许通过配置导出、数据库重建和当前 Schema 初始化完成结构调整；这不授权操作已有部署或共享数据库，执行前仍须确认范围并保留备份。
- 保留外部输入校验、并发一致性、资源释放和凭据保护；不添加假设性防御、静默兜底、异常吞噬或无依据的重试。

## Development and CI

- 日常开发本地只做与改动直接相关的静态检查、必要编译和定向测试，不默认执行全仓 Maven `verify`、前端全量覆盖率或完整浏览器矩阵。
- 本地快速检查通过后，按用户授权提交并推送；全量测试、覆盖率和跨平台验收由推送后的 CI 统一负责，不把本地全量验证作为日常推送的前置条件。
- 推送后确认全量 CI 已触发；当前 `dev` 默认仅构建镜像，不能视为全量验证。CI 失败时优先定位失败项、定向复现和修复，不重复执行无关的本地全量构建。
- 本地全量验证仅在用户明确要求或有明确诊断需要时执行；保留现有 CI 门禁，不以跳过测试、降低覆盖率或忽略失败提速，CI 通过前不得宣称全量验证或发布完成。

## Code and Tests

- Java 代码体内禁止使用全限定类名，优先使用 import；仓库级 Checkstyle 会在 Maven `validate` 阶段检查这条规则。
- 关键逻辑新增或重构时，以 JaCoCo 等报告度量覆盖率，核心路径行覆盖率目标 ≥ 90%，分支覆盖率作为参考；构建门禁以各模块实际配置为准。
- 测试代码保持清晰；长篇或结构化测试数据优先抽取到 `src/test/resources` 下按包结构组织的资源文件中。
- 复杂基座包及其测试基座应提供清晰注释；对外边界、分层职责与维护约定优先放在包级注释或公共基座类注释中。
- DTO/DO/配置对象等 JavaBean 优先使用 Lombok；需要日志时优先 `@Slf4j`。
- 新增依赖必须是直接使用的能力；不因 starter/JUnit 的传递依赖告警去平铺无关依赖。
- 有意义的验证应沉淀为自动化测试：纯逻辑用单元测试，数据库、协议或跨组件行为用集成测试，不为凑层次重复同一断言。
- 测试注释简洁说明意图，便于判断测试是否仍然有效。
- 当前代码树的敏感数据门禁是 `python3 scripts/dev/verify/repository/check-sensitive-data.py`；命中时只报告规则和位置，不回显敏感值。

## Java Formatting

- 全仓权威格式工具是 Spotless：Google Java Format `1.18.0`（GOOGLE）+ `removeUnusedImports` + importOrder `#,,fun.fengwk.kkstudio,javax,java`。
- 根 POM 在 `validate` 阶段对所有模块执行 `spotless:check`；格式不通过则构建失败。
- Java 的所有控制流分支体（`if` / `else` / `for` / `while` / `do`）必须显式使用 `{}`；仓库级 Checkstyle 在 `validate` 阶段检查 main 和 test 源码。
- 格式化限于本切片实际改动的 Java 文件；`mvn -pl <module> spotless:apply` 会格式化整个模块，必须用文件范围限制并检查 diff。Maven 显式指定 JDK；禁止顺手格式化无关文件。

## Daemon Release

- 修改 Daemon（包括影响其行为的共享模块、依赖或打包配置）时，必须升级项目版本；验证通过并合入 `main` 后，推送与 JAR 版本一致的 `v<version>` tag，触发 Daemon Release 自动发布。
- 必须确认新版 GitHub Release 及其 JAR、SHA256 校验文件发布完成；仅推送 `main/dev` 或完成 Docker 构建不算完成 Daemon 发布，不覆盖已有版本资产。

## Docs

- 项目文档按当前风格维护到 `./docs/`，与最新代码保持一致，不另设文档版本。
- 每篇文档围绕目标读者的任务和核心价值组织，先建立必要心智模型，再给出简洁而完整的理解或操作路径；禁止用固定模板和清单堆砌代替信息设计。
- 技术方案文档必须自洽可读：只描述当前生效的职责、结构、协议、约束与实现；不写否决项、会话过程或依赖历史上下文才能理解的内容。
- 用户文案描述操作与结果，不解释组件绑定、内部配置路径或无关副作用；中英文保持语义、单位和占位符一致。
- 文档质量入口：`node scripts/dev/verify/repository/check.mjs`。

## E2E

- 端到端回归入口：`./scripts/dev/verify/e2e/run.sh` 或 `npm --prefix frontend run e2e`；矩阵实现为 Node：`scripts/dev/verify/e2e/run-matrix.mjs`。
- 事实源文档：`docs/operations/development-and-testing.md`。精确 inventory 以 matrix 的 `--list` / `--docs` 输出为准；前端/API 契约变化时同步矩阵 case 与该文档的分类和入口说明。
- 默认只跑无成本 L1 API 矩阵；真模型/tool/branch/UI 需显式开关（`--real` / `--with-tools` / `--ui`）。报告与截图写入 `reports/e2e/`（已 gitignore），以 `reports/e2e/latest/report.md` 为最近一次可读结论。
