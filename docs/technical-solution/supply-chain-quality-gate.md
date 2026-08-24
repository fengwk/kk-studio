# 供应链质量门禁

本文档描述 `kk-studio` 当前生效的后端、前端 SBOM、依赖审计和运行时镜像漏洞扫描入口。普通构建与显式在线门禁分离，报告是每次执行的可复核事实。

## 入口与边界

普通构建保持离线供应链门禁边界：

```bash
env JAVA_HOME=$JAVA_HOME_21 mvn verify
```

根 `pom.xml` 中的 `supply-chain` profile 没有自动激活，CycloneDX 与 Dependency-Check 只在显式选择该 profile 时解析和执行。因此普通 `mvn verify` 不调用 NVD、OSS Index、npm audit、Trivy 或 ECR；需要完整的本地构建依赖缓存才能完全不访问依赖仓库。

质量门禁统一通过以下入口调用：

```bash
./scripts/supply-chain.sh sbom
./scripts/supply-chain.sh audit
./scripts/supply-chain.sh image
./scripts/supply-chain.sh all
./scripts/supply-chain.sh test
./scripts/supply-chain.sh help
```

`all` 总是先生成 SBOM，再执行依赖审计，最后构建 app / daemon 镜像；每个镜像构建成功后立即运行功能 smoke，两个 smoke 都完成后才启动 Trivy 版本检查和镜像扫描。即使前一步失败，也会继续执行后续步骤以保留更多诊断产物。`test` 只运行仓库内永久的脚本契约测试，不访问 NVD、npm audit、Maven 在线源、Docker registry 或 ECR。

## Maven profile

`supply-chain` profile 的插件和策略固定如下：

| 工具 | 版本 | 聚合目标 | 策略 |
| --- | --- | --- | --- |
| CycloneDX Maven Plugin | `2.9.3` | `makeAggregateBom` | JSON、CycloneDX schema `1.6`、不包含 test scope、只输出聚合 BOM |
| OWASP Dependency-Check Maven Plugin | `13.0.0` | `aggregate` | HTML/JSON/SARIF、`failBuildOnCVSS=0`、关闭 OSS Index、启用 NVD 自动更新；报告中的非 suppressed `vulnerabilities` 数组必须为空 |

两个插件都在根 POM 中标记 `inherited=false`，聚合目标只由根项目执行。插件跳过属性仍可用于显式 profile 调试或拆分执行：

```bash
mvn -P supply-chain -Dcyclonedx.skip=true -Ddependency-check.skip=false verify
mvn -P supply-chain -Dcyclonedx.skip=false -Ddependency-check.skip=true verify
```

脚本通过 `supply-chain.report.directory` 将 Maven 产物写入本次报告目录，不把 API key 放入 Maven 参数。

## 后端依赖版本策略

根 POM 在本仓 `dependencyManagement` 中显式导入并按该顺序解析：

| 依赖族 | 最终版本 |
| --- | --- |
| Spring Boot BOM / `spring-boot-maven-plugin` | `3.5.16` |
| Spring Framework（由 Boot BOM 管理） | `6.2.19` |
| Jackson BOM | `2.22.1` |
| Netty BOM | `4.1.137.Final` |
| Log4j BOM | `2.26.1` |
| Kotlin runtime（由 Spring Boot BOM 管理） | `1.9.25` |
| Tomcat embed core/el/jasper/websocket | `10.1.59` |
| PostgreSQL JDBC | `42.7.13` |
| OpenNLP | `2.5.11` |

Jackson、Netty、Log4j 的显式 BOM 位于 Boot BOM 之前，避免被 Boot BOM 的旧版本管理覆盖；Tomcat、PostgreSQL、OpenNLP 使用本仓显式 dependencyManagement 条目。Kotlin 不额外导入 BOM、不新增直接依赖，使用 Spring Boot BOM 的自然运行时图（stdlib、reflect、jdk7、jdk8、common 均为 `1.9.25`）。`web/pom.xml` 的 Spring Boot Maven Plugin 使用 `${spring-boot.version}`，不再硬编码旧版本。版本变更应通过 `help:effective-pom` 与 `dependency:tree` 同时核对最终解析结果。

## 误报抑制

抑制文件为 `config/supply-chain/dependency-check-suppressions.xml`。每条规则都同时限定一个精确 GAV 和一个 CVE，并在 `<notes>` 中保留官方事实与 URL；禁止使用通配 GAV、CPE、CVSS 阈值或整包抑制：

- `fun.fengwk.auto-mapper:auto-mapper-processor:0.0.48` 与 `fun.fengwk.auto-mapper:auto-mapper-annotation:0.0.48` 的 `CVE-2020-7644`：官方记录对应 npm `fun-map`，不是这两个 Maven 构件。依据：[NVD CVE-2020-7644](https://nvd.nist.gov/vuln/detail/CVE-2020-7644)。
- `org.jetbrains.kotlin:kotlin-stdlib:1.9.25`、`kotlin-reflect:1.9.25`、`kotlin-stdlib-jdk7:1.9.25`、`kotlin-stdlib-jdk8:1.9.25`、`kotlin-stdlib-common:1.9.25` 的 `CVE-2020-29582`：JetBrains 公告说明漏洞影响 Kotlin `before 1.4.21`，而 `1.9.25` 已高于修复版本；NVD CPE 使用 `versionEndExcluding=2.1.0` 将该 CVE 错配到这些运行时构件。依据：[JetBrains security bulletin](https://blog.jetbrains.com/blog/2021/02/03/jetbrains-security-bulletin-q4-2020/) 与 [NVD CVE-2020-29582](https://nvd.nist.gov/vuln/detail/CVE-2020-29582)。
- 同一组 Kotlin 运行时 GAV 的 `CVE-2026-53914`：本次扫描实际报告这些坐标，但官方受影响对象为 `kotlin-gradle-plugin`；本仓库没有 Gradle 插件依赖。依据：[NVD CVE-2026-53914](https://nvd.nist.gov/vuln/detail/CVE-2026-53914) 与 [JetBrains security fixes](https://www.jetbrains.com/privacy-security/issues-fixed/)。

这些 suppression 是当前扫描实际报告坐标的最小精确规则；不抑制其他 Kotlin GAV。Log4j 升级到 `2.26.1` 后不再命中 `CVE-2026-34479`，没有添加 Log4j 抑制。

## NVD key、缓存与在线失败

Dependency-Check 以 NVD 为主要漏洞数据源，首次更新可能需要较长时间。无 key 时脚本显式使用 NVD 官方 JSON 2.0 data feed（`https://nvd.nist.gov/feeds/json/cve/2.0/nvdcve-2.0-{0}.json.gz`）而不是被服务端拒绝的无 key REST API；有 key 时使用 NVD REST API。工具和 Maven 会复用本机已有的本地缓存；缓存不完整或过期时，显式 `audit`/`all` 会按官方路径更新，不把漏洞源绑定到普通构建。

当 `NVD_API_KEY` 非空时，脚本会在系统临时目录创建仅当前用户可读写的 `settings.xml`（权限 `600`），将 key 放到固定 server id `kk-studio-supply-chain-nvd` 的 `<password>`，并以 `nvdApiServerId` 指向该 server。Maven 进程不会继承 `NVD_API_KEY`，临时文件在退出时由 `trap` 删除。key 不写入 POM、命令行参数、summary 或日志。

未设置 `NVD_API_KEY` 时，脚本会明确提示使用官方 NVD data feed 的慢速路径。NVD、Maven Central 或 npm registry 等在线源不可用、超时、返回错误，或者工具未能生成完整报告时，门禁保持 `FAIL` 并保留本次报告；不得依据缺失数据生成 `PASS`。

## 运行时镜像扫描

`image` 命令从当前源码分别构建以下两个镜像，不执行 `docker push`：

| 镜像 | Dockerfile | 默认标签 | 覆盖变量 |
| --- | --- | --- | --- |
| App | `deploy/local/Dockerfile` | `kk-studio-app:supply-chain` | `SUPPLY_CHAIN_APP_IMAGE` |
| Daemon | `deploy/reliability/daemon.Dockerfile` | `kk-studio-daemon:supply-chain` | `SUPPLY_CHAIN_DAEMON_IMAGE` |

镜像构建使用源码根目录作为 context，`.dockerignore` 排除密钥和凭证文件。运行时阶段每次构建都会先执行非交互 `apt-get upgrade --yes`，再安装运行时包并清理 apt lists，以吸收 Ubuntu security updates；builder 阶段不执行这项升级。Daemon 的 Node runtime stage 还固定刷新 npm 及其已知受影响的 bundled packages，保持 daemon 的 Node/npm 工具契约。

每个镜像 build 成功后，脚本不覆盖镜像的默认 `USER`，而是通过临时 entrypoint 在该默认用户下执行功能 smoke。App smoke 验证用户不是 root、`java -version` 成功且 `ffmpeg`、`ffprobe`、`curl` 存在。Daemon smoke 额外验证 `java -version`、Node 版本匹配 `v22.19.x`、npm 精确为 `11.19.0`、`bash` 与 `git` 存在，并在镜像内的临时可写目录执行：

```bash
npm init --yes
npm install --package-lock-only --ignore-scripts --no-audit --no-fund lodash@4.17.21
```

随后脚本解析 `package-lock.json`，确认其中包含 `lodash@4.17.21`。该安装只生成 lockfile，且显式关闭 lifecycle scripts；因此 npm bundled package 的修补必须与这项功能 smoke 配套，不能只依赖 Trivy 的静态漏洞结果。任一命令失败、版本不符、默认用户为 root、临时目录不可写或 lockfile 缺少 lodash，均保持 `FAIL`，并在报告中保留经过代理值脱敏的 smoke 日志。

扫描器固定为 Trivy `0.74.0` 的 immutable image：

```text
aquasec/trivy@sha256:62b1e65e8869bc4b4c6aa4fa2b21595256c7c2f6018a9d9ad61caf87187c1969
```

Trivy 使用具名 volume `kk-studio-trivy-cache`（可通过
`SUPPLY_CHAIN_TRIVY_CACHE_VOLUME` 覆盖），并固定从以下仓库更新数据库：

```text
public.ecr.aws/aquasecurity/trivy-db:2
public.ecr.aws/aquasecurity/trivy-java-db:1
```

默认扫描会在线更新数据库。已有完整缓存时，设置
`TRIVY_SKIP_DB_UPDATE=true`，脚本会同时传递
`--skip-db-update --skip-java-db-update --offline-scan`；缓存不完整时仍然
`FAIL`。如果 HTTP(S) proxy 的主机是 `127.0.0.1`、`localhost` 或 `::1`，
扫描容器自动使用 `--network host`，并以只包含变量名的 `--env HTTP_PROXY`
等参数传入代理环境，不把代理值写入命令输出或报告。

扫描只启用 Trivy vulnerability scanner，过滤 `HIGH,CRITICAL` 且忽略无修复
版本的结果；Trivy 非零退出、JSON 缺失/不可解析，或二次解析仍发现任何
HIGH/CRITICAL，均保持 `FAIL`。即使 Trivy 工具退出码为零，报告中的漏洞也
不能生成 `PASS`；由于命令已使用 `--ignore-unfixed`，报告中的 HIGH/CRITICAL
即代表可修复项。App 与 Daemon 各自生成 JSON 报告，summary 同时记录扫描
状态、image id、image digest 和 Trivy 版本；两个 image smoke 的状态和日志也
单独记录。

## 报告

默认报告根目录为 `reports/supply-chain`，也可以指定绝对路径或相对仓库根的路径：

```bash
SUPPLY_CHAIN_REPORT_ROOT=/tmp/kk-studio-supply-chain ./scripts/supply-chain.sh all
```

每次执行创建一个 UTC 时间戳目录，并更新 `latest` 副本与 `LATEST_RUN.txt`：

```text
reports/supply-chain/
├── <YYYYMMDD>T<hhmmss>Z-<pid>/
│   ├── backend-sbom/bom.json
│   ├── frontend-sbom/bom.json
│   ├── frontend-audit/audit.json
│   ├── backend-audit/dependency-check-report.html
│   ├── backend-audit/dependency-check-report.json
│   ├── backend-audit/dependency-check-report.sarif
│   ├── image/app.json
│   ├── image/daemon.json
│   ├── image/trivy-version.json
│   ├── logs/
│   │   ├── app-image-smoke.log
│   │   └── daemon-image-smoke.log
│   ├── summary.md
│   └── summary.json
├── latest/
└── LATEST_RUN.txt
```

`summary.json` 记录模式、整体状态、各检查状态、产物和失败原因；镜像检查
额外记录 image id、image digest、扫描器 image 和版本，并分别记录 App/Daemon
image build、功能 smoke、Trivy scan 及其日志；`summary.md` 供人工阅读。
`reports/supply-chain/` 已加入 `.gitignore`，SBOM、漏洞报告、本地缓存和临时
凭证不会进入提交。

## 结果语义

- `PASS`：所有请求的工具都成功运行，JSON 产物可解析且非空，Dependency-Check 的三种报告均存在，且 JSON 中所有非 suppressed `vulnerabilities` 数组总数为零；两个镜像功能 smoke 均通过，镜像报告中没有 HIGH/CRITICAL。
- `FAIL`：工具或镜像功能 smoke 返回非零（包括任意已知漏洞命中）、在线数据源不可用、报告缺失/不可解析、报告仍包含漏洞、参数错误或报告发布失败。
- `all` 以所有步骤的合取结果作为最终状态；`latest` 只复制真实执行结果，不把失败改写为成功。
