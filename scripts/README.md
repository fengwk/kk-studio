# 脚本入口索引

本目录只放仓库宿主侧（开发者与 CI）的工作流入口。Compose、Dockerfile、容器内入口与运行时
资产留在 [deploy](../deploy/local/README.md)；本页回答的是「要做什么 → 跑哪条命令」。

所有命令都从仓库根目录执行；脚本自行解析仓库根（优先 `KK_STUDIO_REPO_ROOT`，否则向上寻找
worktree 根），因此不受调用者 cwd 影响。任务级细节见
[开发与测试](../docs/operations/development-and-testing.md) 与
[部署与运行](../docs/operations/deployment.md)。

## 日常开发

| 任务 | 入口 | 前置条件 | 副作用 / 费用 | CI |
| --- | --- | --- | --- | --- |
| 启动、查看或停止本机开发栈 | [dev/app.sh](dev/app.sh) | JDK 21、Maven、Node/npm、curl、jq、lsof，可用的 PostgreSQL/S3 | 构建 web 产物、按需 `npm install`/`npm ci`、默认回收 18080/5173 端口占用、日志写 `runtime/dev` | 否 |
| 连外部数据面的本机 preview | [dev/shared-preview.sh](dev/shared-preview.sh) | 同上，外加 owner-only 数据面配置文件（默认 `$HOME/.config/kk-studio/shared-preview.env`） | 同上；固定 `prod` profile，不做 migration 与分布式 Work | 否 |

模板与例子：[dev/shared-preview.env.example](dev/shared-preview.env.example)。

## 验证

`dev/verify/` 下每个能力自包含入口、实现、测试与测试资源。费用栏只描述本机资源与外部调用，
不改变任何默认开关：真模型、真实提交与联网扫描都必须显式打开。

| 任务 | 入口 | 前置条件 | 副作用 / 费用 | CI |
| --- | --- | --- | --- | --- |
| 免费 API/链路矩阵（默认 L1） | [dev/verify/e2e/run.sh](dev/verify/e2e/run.sh) | JDK 21、Maven、Node、python3、curl，可复用的 backend 与 Vite | 复用或重启本机服务，报告写 `reports/e2e/` | 否 |
| 真模型矩阵 | [dev/verify/e2e/run.sh](dev/verify/e2e/run.sh) `--real` | 8 个 `TEST_*_BASE_URL` / `TEST_*_API_KEY` | 真实付费请求 | 否 |
| Tool / UI / 分布式扩展 | 同上 `--with-tools`、`--ui`、`--distributed` | Docker（分布式）、已安装的 Playwright 依赖（`npm --prefix frontend ci`） | 本地容器、浏览器与截图 | 否 |
| 双节点分布式栈生命周期 | [dev/verify/e2e/distributed.sh](dev/verify/e2e/distributed.sh) | Docker Engine 与 Compose v2 | 创建/删除容器、网络与数据卷 | 否 |
| 离线性能基线 | [dev/verify/performance/run.sh](dev/verify/performance/run.sh) | Docker、Node | 构建镜像、短时压测，报告写 `reports/performance/` | 否 |
| 可靠性确定性回归 | [dev/verify/reliability/regression.sh](dev/verify/reliability/regression.sh) | JDK 21、Maven、realpath、setsid | 反复执行冻结的 JUnit 集合，报告写 `reports/reliability/` | 否 |
| 可靠性隔离栈 | [dev/verify/reliability/stack.sh](dev/verify/reliability/stack.sh) | Docker、curl、python3、`PI_ANCHOR` 与 `PI_BASE_ANCHOR` Git worktree | 创建/删除隔离栈与数据卷 | 否 |
| Agent 可靠性矩阵 | [dev/verify/reliability/run-agent-matrix.mjs](dev/verify/reliability/run-agent-matrix.mjs) | Docker、`TEST_MINIMAX_*` | 真实付费模型调用 | 否 |
| 供应链 SBOM 与漏洞门禁 | [dev/verify/supply-chain/run.sh](dev/verify/supply-chain/run.sh) | Docker、Node、网络、git | 构建镜像、访问 NVD 与 npm registry，报告只写本地目录 | 否 |
| 隔离栈端到端 smoke | [dev/verify/smoke/offline-chat.sh](dev/verify/smoke/offline-chat.sh) | Docker，`--with-app` 还需 python3 | 创建/删除栈与数据卷 | 否 |
| 真实 Seedance prepare-only smoke | [dev/verify/smoke/seedance-prepare.sh](dev/verify/smoke/seedance-prepare.sh) | `RUN_REAL_SEEDANCE_PREPARE_SMOKE=1`、`SEEDANCE_WORKSPACE_ID`、`OPENCLI_HUB_BASE_URL` | 只做页面准备，不提交生成、不下载视频 | 否 |
| 文档与仓库结构检查 | [dev/verify/repository/check.mjs](dev/verify/repository/check.mjs) | Node | 只读 | 是 |
| 敏感数据门禁 | [dev/verify/repository/check-sensitive-data.py](dev/verify/repository/check-sensitive-data.py) | python3、git | 只读 | 是 |

## 发布与运维

| 任务 | 入口 | 前置条件 | 副作用 / 费用 | CI |
| --- | --- | --- | --- | --- |
| 安装、升级、查询、卸载 Environment Daemon（Linux/macOS） | [daemon/install.sh](daemon/install.sh) `install` / `upgrade` / `status` / `uninstall` | 源码 checkout、JDK 21、Maven、合规的 token 文件；Linux 需可用的 `systemctl --user`，macOS 需可用的 `gui/$(id -u)` 域 | 构建 daemon、写 `$HOME/.local/lib/kk-studio`、写平台服务定义（systemd user unit 或 LaunchAgent plist）并重启用户服务；`uninstall` 只删除受管定义与 JAR，保留 token 文件与数据目录 | 否 |
| 安装、升级、查询、卸载 Environment Daemon（Windows 10/11） | [daemon/install.ps1](daemon/install.ps1) `install` / `upgrade` / `status` / `uninstall` | 源码 checkout、JDK 21、Maven（`mvn.cmd`）、DACL 合规的 token 文件、ScheduledTasks 模块、`bash.exe`（Git for Windows 或兼容 Bash） | 构建 daemon、写 `%LOCALAPPDATA%\kk-studio\daemon`、注册当前用户 AtLogOn 计划任务；`uninstall` 停止并注销任务、删除 JAR，保留 token 文件与数据目录 | 否 |
| 暂存 Daemon 发布资产 | [daemon/prepare-release.sh](daemon/prepare-release.sh) | JDK 21、已构建的 shaded JAR、sha256sum | 整体重建 `harness/daemon/target/release` | 是 |
| 导出 Agent catalog（Provider / Model / Agent 定义） | [ops/export-agent-catalog.sh](ops/export-agent-catalog.sh) | 原生 libpq 客户端（`psql`）、python3、继承的 libpq 连接设置（无 Docker / 无主应用容器） | 只读目标库；在仓库外 owner-only 目录写入版本化包（`catalog.sql`、`manifest.json`、`sha256sums.txt`，权限 0600） | 否 |
| 备份、冻结旧库并以原元数据重建空库 | [ops/reset-database.sh](ops/reset-database.sh) | 原生 libpq 客户端（`psql`、`pg_dump`、`pg_restore`、`createdb`）、python3、目标库 owner 角色（或 superuser）且具备 CREATEDB、继承的 libpq 连接设置（无其他活跃会话；无 Docker / 无主应用容器） | 在仓库外写入完整 custom-format 备份与 sha256 校验文件，目标库重命名为带时间戳快照并禁止连接，先以临时名建好空库再改名成目标库名；非 `--yes` 需交互输入库名确认 | 否 |
| 回灌 Agent catalog 包 | [ops/import-agent-catalog.sh](ops/import-agent-catalog.sh) `--package PATH` | 原生 libpq 客户端（`psql`）、python3、继承的 libpq 连接设置，目标库已由应用正常启动执行过 Flyway V1 且三张表为空、V1 checksum 一致（无 Docker / 无主应用容器） | 单事务恢复 agent_provider、agent_model、agent_definition 三张表数据（事务内加锁、复查空表并比对指纹后才提交），失败整体回滚；失败时在仓库外保留只含 SQLSTATE 与安全类别的 mode 0600 日志 | 否 |

Environment Daemon 的平台差异（Linux `systemd --user`、macOS LaunchAgent、Windows 10/11 计划任务）、
参数与故障处理见 [Environment Daemon 安装与运行](../docs/operations/environment-daemon.md)。

## 结构约定

- 顶层只有 `dev/`、`daemon/`、`ops/` 三个领域，加上本索引；没有兼容 wrapper 或符号链接。
- `dev/verify/<capability>/` 放该能力的入口与实现，`<capability>/tests/` 只放该能力自己的测试与
  测试资源；测试不跨能力堆在同一个目录里。CI 直接用 `scripts/*/tests` 与
  `scripts/dev/verify/*/tests` 发现测试，因此新增能力不需要改 workflow。
- 表格里的入口是公开入口；`dev/lib/` 是 dev 能力之间共享的私有实现（跨能力共享的开发自动化代码），`ops/lib/` 是数据库维护入口共享的私有实现（底层 helper 为 `ops/agent_catalog.py`），同目录下的 `lib/`、`cases/`、`ui/`、`fixtures/` 与 `tests/` 是实现与
  测试细节，均不是公开入口、不单独作为命令承诺。
- 宿主人类/CI 工作流属于本目录；Compose、Dockerfile、容器内 entrypoint 与 mock 运行时资产属于
  [deploy](../deploy/local/README.md)，容器内入口不从这里启动。
- 目录内相对引用可以按相对路径书写；跨目录定位仓库根一律用上述根解析规则，不写死目录深度。
