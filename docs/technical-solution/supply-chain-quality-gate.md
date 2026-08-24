# 供应链质量门禁

本文档描述 `kk-studio` 当前生效的后端、前端 SBOM 与高危漏洞扫描入口。普通构建与显式在线门禁分离，报告是每次执行的可复核事实。

## 入口与边界

普通构建保持离线安全边界：

```bash
env JAVA_HOME=$JAVA_HOME_21 mvn verify
```

根 `pom.xml` 中的 `supply-chain` profile 没有自动激活，CycloneDX 与 Dependency-Check 只在显式选择该 profile 时解析和执行。因此普通 `mvn verify` 不依赖 NVD、OSS Index 或其他在线漏洞源。

质量门禁统一通过以下入口调用：

```bash
./scripts/supply-chain.sh sbom
./scripts/supply-chain.sh audit
./scripts/supply-chain.sh all
./scripts/supply-chain.sh test
./scripts/supply-chain.sh help
```

`all` 总是先生成 SBOM，再执行审计；即使前一步失败，也会继续执行审计以保留更多诊断产物。`test` 只运行仓库内永久的脚本契约测试，不访问 NVD、npm audit 或 Maven 在线源。

## Maven profile

`supply-chain` profile 的插件和策略固定如下：

| 工具 | 版本 | 聚合目标 | 策略 |
| --- | --- | --- | --- |
| CycloneDX Maven Plugin | `2.9.3` | `makeAggregateBom` | JSON、CycloneDX schema `1.6`、不包含 test scope、只输出聚合 BOM |
| OWASP Dependency-Check Maven Plugin | `13.0.0` | `aggregate` | HTML/JSON/SARIF、`failBuildOnCVSS=7`、关闭 OSS Index、启用 NVD 自动更新 |

两个插件都在根 POM 中标记 `inherited=false`，聚合目标只由根项目执行。插件跳过属性仍可用于显式 profile 调试或拆分执行：

```bash
mvn -P supply-chain -Dcyclonedx.skip=true -Ddependency-check.skip=false verify
mvn -P supply-chain -Dcyclonedx.skip=false -Ddependency-check.skip=true verify
```

脚本通过 `supply-chain.report.directory` 将 Maven 产物写入本次报告目录，不把 API key 放入 Maven 参数。

## NVD key、缓存与在线失败

Dependency-Check 以 NVD 为主要漏洞数据源，首次更新可能需要较长时间。无 key 时脚本显式使用 NVD 官方 JSON 2.0 data feed（`https://nvd.nist.gov/feeds/json/cve/2.0/nvdcve-2.0-{0}.json.gz`）而不是被服务端拒绝的无 key REST API；有 key 时使用 NVD REST API。工具和 Maven 会复用本机已有的本地缓存；缓存不完整或过期时，显式 `audit`/`all` 会按官方路径更新，不把漏洞源绑定到普通构建。

当 `NVD_API_KEY` 非空时，脚本会在系统临时目录创建仅当前用户可读写的 `settings.xml`（权限 `600`），将 key 放到固定 server id `kk-studio-supply-chain-nvd` 的 `<password>`，并以 `nvdApiServerId` 指向该 server。Maven 进程不会继承 `NVD_API_KEY`，临时文件在退出时由 `trap` 删除。key 不写入 POM、命令行参数、summary 或日志。

未设置 `NVD_API_KEY` 时，脚本会明确提示使用官方 NVD data feed 的慢速路径。NVD、Maven Central 或 npm registry 等在线源不可用、超时、返回错误，或者工具未能生成完整报告时，门禁保持 `FAIL` 并保留本次报告；不得依据缺失数据生成 `PASS`。

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
│   ├── logs/
│   ├── summary.md
│   └── summary.json
├── latest/
└── LATEST_RUN.txt
```

`summary.json` 记录模式、整体状态、各检查状态、产物和失败原因；`summary.md` 供人工阅读。`reports/supply-chain/` 已加入 `.gitignore`，SBOM、漏洞报告、本地缓存和临时凭证不会进入提交。

## 结果语义

- `PASS`：所有请求的工具都成功运行，JSON 产物可解析且非空，Dependency-Check 的三种报告均存在。
- `FAIL`：工具返回非零（包括高危漏洞命中）、在线数据源不可用、报告缺失/不可解析、参数错误或报告发布失败。
- `all` 以所有步骤的合取结果作为最终状态；`latest` 只复制真实执行结果，不把失败改写为成功。
