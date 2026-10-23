# 脚本入口索引

这里收录宿主上的安装、开发、验证与数据库维护入口。Daemon 默认安装官方 release，脚本下载到主机就能用，**无需源码 checkout**；开发、验证、发布资产暂存和数据库维护依赖仓库中的代码或配置，以下路径按仓库根目录书写。需要定位仓库的入口通常优先使用 `KK_STUDIO_REPO_ROOT`，否则从脚本位置寻找 worktree 根，具体参数以各入口帮助为准。

Compose、Dockerfile 与容器内 entrypoint 留在 [deploy](../deploy/local/README.md)。不要把宿主脚本与容器启动入口混用。

## 安装与管理 Daemon

| 平台 | 入口 | 所需环境与行为 |
| --- | --- | --- |
| Linux / macOS | [daemon/install.sh](daemon/install.sh) | Bash、curl、SHA-256 工具（官方发行归档自带冻结运行时，无需宿主安装 Node 或 JDK）；Linux 需 `systemctl --user`，macOS 需当前用户 GUI 登录域 |
| Windows 10/11 | [daemon/install.ps1](daemon/install.ps1) | PowerShell 5.1/7、ScheduledTasks、可用的 Bash（官方发行归档自带冻结运行时，无需宿主安装 Node 或 JDK）；当前用户交互登录期间运行 AtLogOn 计划任务，不是 Windows Service |

正常入口是 Studio 的 Web 安装弹窗：保存配置后复制命令，脚本会自行暂存 `daemon.json` 与
`daemon.token`、下载安装器并执行。安装器只有 `install` / `status` / `uninstall` / `help`
四个动作，稳定契约是
`bash install.sh install --config-file <绝对 daemon.json> --token-file <绝对 daemon.token>`
（Windows 为 `-ConfigFile` / `-TokenFile`）。它默认下载官方 GitHub latest release
的发行归档与 SHA 文件、用归档内置的运行时 `--check-config` 预检暂存输入，再整体替换程序/配置/token 并重启，
保留数据；没有交互提示，也没有 from-source 或单独 upgrade 动作。`status` 只读；`uninstall`
删除受管服务定义与程序发行目录，保留配置、token、数据与日志。详情与三平台排错见
[Environment Daemon 安装与运行](../docs/operations/environment-daemon.md)。

从 checkout 运行安装脚本只属于开发/CI fixture，用来验证脚本契约，**不是用户安装路径**；安装器
从不构建，发行归档由发布流程独立生成。Fixture 会替代网络与服务管理命令，不注册真实
systemd/launchd 服务，测试入口见
[开发与测试](../docs/operations/development-and-testing.md#daemon-安装与受管更新回归)。

## 日常开发

| 任务 | 入口 | 前置条件与影响 |
| --- | --- | --- |
| 本机 Backend + Vite 生命周期 | [dev/app.sh](dev/app.sh) `start` / `stop` / `restart` / `status` / `logs` / `tail` | JDK 21、Maven、Node/npm、curl、jq、lsof，PostgreSQL/S3 配置；按 revision 构建 Backend、按需安装前端依赖，默认回收 18080/5173 端口监听者，日志在 `runtime/dev` |
| 连接外部数据面的本机 preview | [dev/shared-preview.sh](dev/shared-preview.sh)，同上子命令 | 复用 app 入口，另需 owner-only 配置文件，默认 `$HOME/.config/kk-studio/shared-preview.env`；固定 prod、禁用 Flyway 与本机 Harness worker |

数据面配置模板：[dev/shared-preview.env.example](dev/shared-preview.env.example)。`app.sh` 的 e2e profile 可同步完整的真实 Provider 凭据对，不要把带真实凭据的启动误当成完全离线操作。操作说明见[开发与测试](../docs/operations/development-and-testing.md)。

## 验证入口

先选择要验证的能力，而不是一次运行所有入口。默认 E2E 不调用付费模型；真实模型、工具、UI 与分布式栈通过显式开关启用，开关并不都能组合。

| 任务 | 入口 | 前置条件与影响 |
| --- | --- | --- |
| 默认免费 L1 API 矩阵 | [dev/verify/e2e/run.sh](dev/verify/e2e/run.sh) | JDK 21、Maven、Node、curl，Backend/Vite 可复用或由入口启动；报告在 `reports/e2e` |
| 真模型、Tool、Branch、UI、Canvas 扩展 | 同一 E2E 入口：`--real`、`--with-tools`、`--with-branch`、`--ui`、`--with-canvas-storage`、`--with-canvas-function` | real 需 4 个 Provider 的 8 个 `TEST_*` URL/key，branch 隐含 real；tools 启动/复用 Daemon；UI 需 Playwright；Canvas storage 需 S3，function 使用免费 fake 并隐含 storage + rebuild |
| 免费双节点矩阵 | 同一 E2E 入口：`--distributed` | 需 Docker；不能与 real/tools/branch/UI/storage/function 组合，结束清理隔离栈与数据卷 |
| 双节点栈生命周期 | [dev/verify/e2e/distributed.sh](dev/verify/e2e/distributed.sh) | Docker Engine 与 Compose v2；创建/删除容器、网络和数据卷 |
| 本机免费性能基线 | [dev/verify/performance/run.sh](dev/verify/performance/run.sh) | Docker、Node；构建隔离测试镜像、短时压测，报告在 `reports/performance`，不是无 Docker 的纯离线构建 |
| 确定性可靠性回归 | [dev/verify/reliability/regression.sh](dev/verify/reliability/regression.sh) | JDK 21、Maven、realpath、setsid；反复执行指定 JUnit 集合，报告在 `reports/reliability` |
| 可靠性隔离栈 | [dev/verify/reliability/stack.sh](dev/verify/reliability/stack.sh) | Docker、curl、Node；snapshot 需 `PI_ANCHOR` 与 `PI_BASE_ANCHOR` Git worktree，创建/删除隔离栈与数据卷 |
| Agent 可靠性矩阵 | [dev/verify/reliability/run-agent-matrix.mjs](dev/verify/reliability/run-agent-matrix.mjs) | Node、Docker、可靠性栈与 `TEST_MINIMAX_*`；真实付费模型调用 |
| SBOM、依赖与镜像漏洞检查 | [dev/verify/supply-chain/run.sh](dev/verify/supply-chain/run.sh) `sbom` / `audit` / `image` / `all` | 按子命令需 JDK 21/Maven、Node/npm、Docker、git 与网络；访问 registry、NVD/漏洞库，报告在 `reports/supply-chain`；`test` 只运行脚本测试 |
| 隔离栈应用 smoke | [dev/verify/smoke/offline-chat.sh](dev/verify/smoke/offline-chat.sh) | Docker，`--with-app` 另需 Node，覆盖 Blob、Canvas revision/typed commands、fake Function 与 Chat；创建/删除栈与数据卷 |
| Seedance prepare-only smoke | [dev/verify/smoke/seedance-prepare.mjs](dev/verify/smoke/seedance-prepare.mjs) | 显式 `RUN_REAL_SEEDANCE_PREPARE_SMOKE=1`、`SEEDANCE_WORKSPACE_ID`、`OPENCLI_HUB_BASE_URL`；只做页面准备，不提交生成或下载视频 |
| 文档与仓库结构 | [dev/verify/repository/check.mjs](dev/verify/repository/check.mjs) | Node，只读 |
| 生成契约（前端传输与 Daemon 协议常量）生成/漂移 | [dev/verify/generated-contracts/check.sh](dev/verify/generated-contracts/check.sh)（更新用 [write.sh](dev/verify/generated-contracts/write.sh)） | JDK 21、Maven；按 Web 控制器签名用真实 HTTP Jackson mapper 生成/比对 [frontend/src/shared/api/generated/contracts.ts](../frontend/src/shared/api/generated/contracts.ts)，并按 Java Daemon 协议常量生成/比对 [daemon/src/generated/protocol-contract.ts](../daemon/src/generated/protocol-contract.ts)，只跑 `-pl web -am` 定向 reactor；`check.sh` 只读，`write.sh` 会改源码需人工审阅 diff；`mvn verify` 执行同一 check |
| Canvas/Project V1 库表验证 | [dev/verify/repository/check-canvas-project-schema.mjs](dev/verify/repository/check-canvas-project-schema.mjs) | Node、本机 Docker socket、已有 `postgres:17.10`；无网络/无宿主端口的临时容器，加载 V1、检查 45 张业务表、17 张目标表与 SQL 探针，不访问部署库 |
| 当前代码树敏感数据门禁 | [dev/verify/repository/check-sensitive-data.mjs](dev/verify/repository/check-sensitive-data.mjs) | Node、git，只读；命中只报告规则与位置，不回显敏感值 |
| Git 历史敏感数据审计 | [dev/verify/repository/check-sensitive-history.mjs](dev/verify/repository/check-sensitive-history.mjs) | Node、git；扫描本地可见 refs 与当前树，可选抓取公开 PR refs 到隔离临时仓库；与当前树门禁分开使用 |

E2E 的精确 case 列表以 `run.sh --list` / `--docs` 为准，分类与执行路径见[开发与测试](../docs/operations/development-and-testing.md)。

## 发布与数据库维护

| 任务 | 入口 | 前置条件与影响 |
| --- | --- | --- |
| 暂存 Daemon 发布资产 | [daemon/prepare-release.sh](daemon/prepare-release.sh) `<release-tag> <os> <arch>` | checkout、Node 24、npm、git、已编译 `daemon/dist`；按目标平台安装生产依赖并打包冻结 code-server 运行时，替换 `daemon/release/<os>-<arch>`，生成 tar.gz、SHA256、JSON 元数据、LICENSE、THIRD_PARTY_NOTICES 5 个资产，**不上传发布** |
| 备份、冻结旧库、建空库 | [ops/reset-database.sh](ops/reset-database.sh) | psql/pg_dump/pg_restore/createdb、Node，owner 或 superuser 且有建库权限、无其它会话；完整备份与校验写仓库外，旧库改名禁连接，按原元数据建空库；非 `--yes` 需交互确认 |

配置导出与导入在产品的“设置 → 同步”完成，YAML 始终包含所需凭据，不属于数据库脚本。
数据库入口不管理服务生命周期。连接参数优先级为 CLI → `VPS_POSTGRES_*` → 标准 libpq；不从命令行接收密码，不交互询问口令，缺凭据直接失败。先用 `--dry-run` 看重建计划；reset 不执行 Flyway，空库 Schema 初始化由外部流程完成。完整步骤见[共享数据库重建](../docs/operations/development-and-testing.md#共享数据库重建)。

## 目录与维护边界

- `dev/`、`daemon/`、`ops/` 分别持有开发验证、Daemon 安装发布与数据库维护入口。
- `dev/verify/<capability>/tests` 放对应脚本测试和资源；CI 发现 `scripts/*/tests` 与 `scripts/dev/verify/*/tests`。测试目录不是公开操作入口。
- [dev/lib](dev/lib/) 共享开发自动化实现；[ops/lib](ops/lib/) 共享数据库维护实现。同目录的 `cases`、`ui`、`fixtures`、`lib` 属于实现细节，不把它们单独承诺为用户命令。
- 新增入口时维护这里的用途、前置条件与副作用；参数细节放入口帮助和对应操作文档，不在索引重复源码流程。
