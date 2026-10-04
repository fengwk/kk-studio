# 脚本入口索引

这里收录宿主上的安装、开发、验证与数据库维护入口。Daemon 默认安装官方 release，脚本下载到主机就能用，**无需源码 checkout**；开发、验证、发布资产暂存和数据库维护依赖仓库中的代码或配置，以下路径按仓库根目录书写。需要定位仓库的入口通常优先使用 `KK_STUDIO_REPO_ROOT`，否则从脚本位置寻找 worktree 根，具体参数以各入口帮助为准。

Compose、Dockerfile 与容器内 entrypoint 留在 [deploy](../deploy/local/README.md)。不要把宿主脚本与容器启动入口混用。

## 安装与管理 Daemon

| 平台 | 入口 | 所需环境与行为 |
| --- | --- | --- |
| Linux / macOS | [daemon/install.sh](daemon/install.sh) | JDK 21、Bash、curl、SHA-256 工具；Linux 需 `systemctl --user`，macOS 需当前用户 GUI 登录域 |
| Windows 10/11 | [daemon/install.ps1](daemon/install.ps1) | JDK 21、PowerShell 5.1/7、ScheduledTasks、`bash.exe`；当前用户交互登录期间运行 AtLogOn 计划任务，不是 Windows Service |

默认 `install` 从 GitHub latest 下载 JAR 与 SHA 文件，隐藏交互询问 gateway/token，写用户服务定义并启动。`upgrade` 下载新 JAR、复用已有配置并重启；`status` 只读；`uninstall` 删除受管服务定义与 JAR，保留 token、数据目录。支持 `--version` / `-Version` 固定发布版本；仅开发者使用 `--from-source` / `-FromSource`，该模式才需要 checkout 与 Maven。

Unix 安装与更新可直接执行：

```bash
curl -fsSL https://raw.githubusercontent.com/fengwk/kk-studio/main/scripts/daemon/install.sh | bash
curl -fsSL https://raw.githubusercontent.com/fengwk/kk-studio/main/scripts/daemon/install.sh | bash -s -- upgrade
```

Windows 下载脚本为临时或明确的用户文件，再用 `powershell -File` 执行。PowerShell 5.1 的 TLS 1.2、Unicode、token 权限、可复制安装/更新命令与三平台排错统一见 [Environment Daemon 安装与运行](../docs/operations/environment-daemon.md)。可保留脚本日常管理，但要重新下载才能获取新版安装器。下载/预检失败保留现有安装，替换与重启阶段失败不自动回滚；同名服务的所有权标记不匹配时拒绝管理。

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
| 默认免费 L1 API 矩阵 | [dev/verify/e2e/run.sh](dev/verify/e2e/run.sh) | JDK 21、Maven、Node、python3、curl，Backend/Vite 可复用或由入口启动；报告在 `reports/e2e` |
| 真模型、Tool、Branch、UI、Canvas 扩展 | 同一 E2E 入口：`--real`、`--with-tools`、`--with-branch`、`--ui`、`--with-canvas-storage`、`--with-canvas-function` | real 需 4 个 Provider 的 8 个 `TEST_*` URL/key，branch 隐含 real；tools 启动/复用 Daemon；UI 需 Playwright；Canvas storage 需 S3，function 使用免费 fake 并隐含 storage + rebuild |
| 免费双节点矩阵 | 同一 E2E 入口：`--distributed` | 需 Docker；不能与 real/tools/branch/UI/storage/function 组合，结束清理隔离栈与数据卷 |
| 双节点栈生命周期 | [dev/verify/e2e/distributed.sh](dev/verify/e2e/distributed.sh) | Docker Engine 与 Compose v2；创建/删除容器、网络和数据卷 |
| 本机免费性能基线 | [dev/verify/performance/run.sh](dev/verify/performance/run.sh) | Docker、Node；构建隔离测试镜像、短时压测，报告在 `reports/performance`，不是无 Docker 的纯离线构建 |
| 确定性可靠性回归 | [dev/verify/reliability/regression.sh](dev/verify/reliability/regression.sh) | JDK 21、Maven、realpath、setsid；反复执行指定 JUnit 集合，报告在 `reports/reliability` |
| 可靠性隔离栈 | [dev/verify/reliability/stack.sh](dev/verify/reliability/stack.sh) | Docker、curl、python3；snapshot 需 `PI_ANCHOR` 与 `PI_BASE_ANCHOR` Git worktree，创建/删除隔离栈与数据卷 |
| Agent 可靠性矩阵 | [dev/verify/reliability/run-agent-matrix.mjs](dev/verify/reliability/run-agent-matrix.mjs) | Node、Docker、可靠性栈与 `TEST_MINIMAX_*`；真实付费模型调用 |
| SBOM、依赖与镜像漏洞检查 | [dev/verify/supply-chain/run.sh](dev/verify/supply-chain/run.sh) `sbom` / `audit` / `image` / `all` | 按子命令需 JDK 21/Maven、Node/npm、Docker、git 与网络；访问 registry、NVD/漏洞库，报告在 `reports/supply-chain`；`test` 只运行脚本测试 |
| 隔离栈应用 smoke | [dev/verify/smoke/offline-chat.sh](dev/verify/smoke/offline-chat.sh) | Docker，`--with-app` 另需 python3，覆盖 Blob、Canvas revision/typed commands、fake Function 与 Chat；创建/删除栈与数据卷 |
| Seedance prepare-only smoke | [dev/verify/smoke/seedance-prepare.sh](dev/verify/smoke/seedance-prepare.sh) | 显式 `RUN_REAL_SEEDANCE_PREPARE_SMOKE=1`、`SEEDANCE_WORKSPACE_ID`、`OPENCLI_HUB_BASE_URL`；只做页面准备，不提交生成或下载视频 |
| 文档与仓库结构 | [dev/verify/repository/check.mjs](dev/verify/repository/check.mjs) | Node，只读 |
| Canvas/Project V1 库表验证 | [dev/verify/repository/check-canvas-project-schema.py](dev/verify/repository/check-canvas-project-schema.py) | python3、本机 Docker socket、已有 `postgres:17.10`；无网络/无宿主端口的临时容器，加载 V1、检查 16 张目标表与 SQL 探针，不访问部署库 |
| 当前代码树敏感数据门禁 | [dev/verify/repository/check-sensitive-data.py](dev/verify/repository/check-sensitive-data.py) | python3、git，只读；命中只报告规则与位置，不回显敏感值 |
| Git 历史敏感数据审计 | [dev/verify/repository/check-sensitive-history.py](dev/verify/repository/check-sensitive-history.py) | python3、git；扫描本地可见 refs 与当前树，可选抓取公开 PR refs 到隔离临时仓库；与当前树门禁分开使用 |

E2E 的精确 case 列表以 `run.sh --list` / `--docs` 为准，分类与执行路径见[开发与测试](../docs/operations/development-and-testing.md)。覆盖率/进程矩阵的 CI 辅助脚本位于 [process-scope](dev/verify/process-scope/)，不用于启动应用。

## 发布与数据库维护

| 任务 | 入口 | 前置条件与影响 |
| --- | --- | --- |
| 暂存 Daemon 发布资产 | [daemon/prepare-release.sh](daemon/prepare-release.sh) `<release-tag>` | checkout、JDK 21、git、sha256sum、已构建 shaded JAR；校验后替换 `harness/daemon/target/release`，生成 JAR、SHA、JSON、LICENSE、THIRD_PARTY_NOTICES，**不上传发布** |
| 备份、冻结旧库、建空库 | [ops/reset-database.sh](ops/reset-database.sh) | psql/pg_dump/pg_restore/createdb、python3，owner 或 superuser 且有建库权限、无其它会话；完整备份与校验写仓库外，旧库改名禁连接，按原元数据建空库；非 `--yes` 需交互确认 |

配置导出与导入在产品的“设置 → 同步”完成，YAML 始终包含所需凭据，不属于数据库脚本。
数据库入口不管理服务生命周期。连接参数优先级为 CLI → `VPS_POSTGRES_*` → 标准 libpq；不从命令行接收密码，不交互询问口令，缺凭据直接失败。先用 `--dry-run` 看重建计划；reset 不执行 Flyway，空库 Schema 初始化由外部流程完成。完整步骤见[共享数据库重建](../docs/operations/development-and-testing.md#共享数据库重建)。

## 目录与维护边界

- `dev/`、`daemon/`、`ops/` 分别持有开发验证、Daemon 安装发布与数据库维护入口。
- `dev/verify/<capability>/tests` 放对应脚本测试和资源；CI 发现 `scripts/*/tests` 与 `scripts/dev/verify/*/tests`。测试目录不是公开操作入口。
- [dev/lib](dev/lib/) 共享开发自动化实现；[ops/lib](ops/lib/) 共享数据库维护实现。同目录的 `cases`、`ui`、`fixtures`、`lib` 属于实现细节，不把它们单独承诺为用户命令。
- 新增入口时维护这里的用途、前置条件与副作用；参数细节放入口帮助和对应操作文档，不在索引重复源码流程。
