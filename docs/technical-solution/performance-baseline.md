# 免费性能基线

`./scripts/performance.sh` 是 kk-studio 的机器内免费 HTTP 回归门禁。它用于比较当前
代码与同一台机器上此前运行的结果，不代表容量规划、生产 SLA 或可承载用户数。

## 入口与生命周期

```bash
./scripts/performance.sh
./scripts/performance.sh --duration-seconds 5
./scripts/performance.sh --skip-build
./scripts/performance.sh --report-root /tmp/kk-studio-performance
```

默认每个场景测量 10 秒；`--duration-seconds` 允许 `1..120`，`--skip-build` 复用
`kk-studio-app:performance-baseline` 镜像。每个场景先进行 1 秒 warmup，测量期间由固定
并发 worker 持续发请求，测量结束后等待所有 inflight 请求完成。每个 HTTP 请求的超时为
5 秒。

Shell 入口负责：

1. 构建当前 `deploy/local/Dockerfile` 镜像（除非使用 `--skip-build`）；
2. 通过 `deploy/test/compose.yaml --profile app` 清理并启动隔离的 PostgreSQL、MinIO、
   HTTP mock 与 app；
3. 等待 Compose 健康检查和 `/actuator/health` 就绪；
4. 调用 Node 原生 `fetch` runner；
5. 无论成功、失败、INT 还是 TERM，执行 `down --volumes --remove-orphans`。

该入口只使用 `deploy/test` 内置的离线 mock，不读取或同步真实 Provider 凭证，也不启动
daemon。应用固定使用 `http://127.0.0.1:18088`；PostgreSQL、MinIO 和 HTTP mock 分别
使用 `15432`、`19000` 和 `18089`。

## 固定场景

| 场景 | 并发 | 请求/事务 | 成功验证 |
| --- | ---: | --- | --- |
| `health` | 16 | `GET /actuator/health` | HTTP 200 且 JSON `status=UP` |
| `catalog` | 16 | `GET /api/ai/catalog/models?pageNumber=1&pageSize=20` | `data.results` 页面信封、model name/provider/config/variants/limit 严格存在，禁止内部 id/旧 JSON 字段 |
| `canvas` | 4 | 循环 `POST /api/canvases` 后 `DELETE /api/canvases/{id}` | 一个样本是完整 create+cleanup cycle；create DTO 必须有 canonical UUID、decimal `version`，且不得含 `threadId` 或 `graphVersion` |

Canvas worker 使用本次 run 的标题前缀跟踪资源。正常删除、请求失败、超时以及
INT/TERM 后，runner 都会尝试删除已知画布；中断或有错误时还会扫描同一标题前缀，避免
create 已提交但响应中断造成残留。

## 统计与门禁

每个场景报告：

- `requests`、`success`、`error`、`errorRate`；
- `requestsPerSecond`（全部尝试）和 `throughputRps`（成功请求/完整 cycle）；
- `p50Ms`、`p95Ms`、`p99Ms`、`maxMs`；
- warmup 请求数、测量样本数和门禁失败原因。

百分位使用 nearest-rank：将所有请求延迟升序排列，取一基下标
`ceil(p / 100 × n)` 对应值；错误请求仍计入延迟样本。每个场景少于 20 个测量样本时
fail closed。

| 场景 | error | p95 | throughput RPS | 最少样本 |
| --- | ---: | ---: | ---: | ---: |
| health | 0 | ≤ 250ms | ≥ 50 | 20 |
| catalog | 0 | ≤ 500ms | ≥ 25 | 20 |
| canvas | 0 | ≤ 1500ms | ≥ 5 | 20 |

这些阈值是 runner 常量，同时写入 `summary.json` 和 `report.md`，普通 CLI 没有放宽
阈值的参数。

## 报告

报告根目录默认为 `reports/performance/`（已被 Git 忽略），每次运行生成：

```text
reports/performance/<timestamp>-<id>/report.md
reports/performance/<timestamp>-<id>/summary.json
reports/performance/latest/report.md
reports/performance/latest/summary.json
reports/performance/LATEST_RUN.txt
```

报告包含 commit、JDK/Node/Docker、宿主 CPU/内存、应用镜像 ID、参数、固定并发与
warmup、阈值、每场景样本/结果、隔离服务和清理状态。`--report-root` 拒绝文件系统根、
仓库根、非目录和 symlink 路径。

Node runner 没有第三方压测包，永久测试位于
`scripts/performance/runner.test.mjs`，覆盖 percentile、统计、阈值失败、响应契约、
CLI 非法值、报告路径和一秒本地 HTTP fake 集成；fake 集成不需要 Docker。
