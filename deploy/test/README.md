# Canvas/Storage 隔离测试栈

[compose.yaml](compose.yaml) 与 [scripts/dev/verify/smoke/offline-chat.sh](../../scripts/dev/verify/smoke/offline-chat.sh) 提供不依赖远端服务的容器测试底座：PostgreSQL、
MinIO 与 bucket 初始化、一个可配置 HTTP mock，以及可选的当前 App 镜像。它用于 Canvas
Resource/Blob、fake Canvas Function、OpenCLI fake Hub adapter 和离线 Chat 的确定性 smoke。

需要本地可用界面时用 [deploy/local](../local/README.md)；可靠性栈、分布式双节点和生产部署见
[部署与运行](../../docs/operations/deployment.md)。

## 前置条件

- Docker Engine 与 Docker Compose v2。
- `--with-app` 还需要 Python 3（宿主侧 smoke 脚本）。
- 所有宿主端口只绑定 `127.0.0.1`，服务位于本 Compose project 的独立 bridge 网络，不会加入
  生产或其它本地栈的网络。
- Compose 中的账号都是固定、可丢弃的测试值，不要替换成生产凭据。默认流程不配置也不调用
  远端环境、真实模型或付费接口。

## 一键验证

```bash
./scripts/dev/verify/smoke/offline-chat.sh
./scripts/dev/verify/smoke/offline-chat.sh --with-app
```

[scripts/dev/verify/smoke/offline-chat.sh](../../scripts/dev/verify/smoke/offline-chat.sh) 依次执行：

1. `docker compose config --quiet` 校验默认与 `--profile app` 配置；
2. 销毁同名隔离栈及其 PostgreSQL/MinIO volumes，保证直接重写的 V1 从空数据启动；
3. 用显式 `docker build` 构建当前应用 Dockerfile（不委托 Compose 构建）；
4. 启动依赖并等待 healthcheck；
5. 在应用 runtime image 中确认非 root `kkstudio` 用户以及 Canvas Resource 使用的 `ffmpeg` / `ffprobe`；
6. 执行 PostgreSQL `SELECT 1`，检查 MinIO bucket 与 HTTP mock（health、确定性 OpenAI SSE）；
7. 无论成功或失败，都执行 `down --volumes --remove-orphans`，失败时先输出 compose 诊断。

脚本只接受 `--with-app` 与 `-h` / `--help`，其它参数报错退出。

`--with-app` 在步骤 6 之后再用仓库内极小 PNG/MP4 fixture 跑完整应用 smoke：

- 全局 Blob `reserve -> checksummed create-only PUT -> complete`，验证首次写入成功、不同内容的
  重复写入被 MinIO 拒绝、original 字节不变、URL DTO 不暴露 bucket/key；
- Canvas Resource node、preview signed GET 与 `version=1` snapshot；
- 打开 `kk-studio.canvas.function.fake-enabled` 后的 fake image Function node：start、poll、
  terminal snapshot 替换与 preview signed GET，`document.version` 按 resource command、
  Function node command、start、checkpoint 与 terminal success 前进到 5；
- OpenCLI fake Hub 上的 `gpt-image-2` 与 `seedance2.0fast` 两条 adapter 闭环，验证 GPT Image
  四个、Seedance 六个 durable checkpoint 均独立前进 Canvas version；
- `dev` seed 的 stub Chat `create Chat/Thread -> USER_MESSAGE -> poll 至 quiescent`，断言
  durable assistant MESSAGE、`TURN_END` 为 `COMPLETED` 且无 `ASSISTANT_ERROR` 条目。

这里没有浏览器登录、真实 provider 或付费请求。

## 手动使用

```bash
# 启动依赖
docker compose -f deploy/test/compose.yaml up -d --wait

# 启动依赖和应用
docker compose -f deploy/test/compose.yaml --profile app up -d --build --wait

# 清理容器、网络与测试数据
docker compose -f deploy/test/compose.yaml --profile app down -v --remove-orphans
```

手动启动应用镜像时需要先自行构建，否则 Compose 会按 [deploy/local/Dockerfile](../local/Dockerfile)
现场构建。

默认宿主地址：

| 服务 | 地址 |
| --- | --- |
| PostgreSQL | `postgresql://canvas_test:canvas_test_only@127.0.0.1:15432/canvas_test` |
| MinIO S3 API | `http://127.0.0.1:19000` |
| HTTP mock | `http://127.0.0.1:18089` |
| 可选 app | `http://127.0.0.1:18088` |

宿主端口可分别用 `CANVAS_TEST_PG_PORT`、`CANVAS_TEST_MINIO_PORT`、`CANVAS_TEST_MOCK_PORT`、
`CANVAS_TEST_APP_PORT` 覆盖；容器内端口固定。应用以 `dev,canvas-test` profile 启动，
`KK_STUDIO_STORAGE_S3_*` 指向容器内 `minio:9000`，public endpoint 指向
`http://127.0.0.1:19000`，媒体进程使用容器内 `ffprobe`/`ffmpeg`，临时目录 `/tmp`。
MinIO 数据在 `minio-data`，PostgreSQL 数据在 `postgres-data`。

应用 seed 后 `default-assistant` 指向 `stub/acceptance-stub`，stub provider 的 `base_url` 是
`http://stub.local:8080/v1`；本栈把 `stub.local` 配成 HTTP mock 的网络别名，由其内置的
`POST /v1/chat/completions` 确定性 SSE 应答。ComfyUI 保持禁用，OpenCLI adapters 只在该隔离栈中
指向内置 fake Hub。

## 构建代理

应用镜像构建使用 BuildKit Maven/npm cache。脚本继承标准 `HTTP_PROXY` / `HTTPS_PROXY` /
`NO_PROXY`（含小写形式）；代理是宿主 loopback 地址时，build 阶段改用 host network 并为 Maven
显式生成 Java proxy 参数。代理值不会进入 runtime 镜像。

可用 `CANVAS_TEST_BUILD_HTTP_PROXY`、`CANVAS_TEST_BUILD_HTTPS_PROXY`、
`CANVAS_TEST_BUILD_NO_PROXY`、`CANVAS_TEST_BUILD_NETWORK` 和 `CANVAS_TEST_BUILD_MAVEN_OPTS`
覆盖；带认证或非标准 URL 的代理应显式提供后两项。脚本不委托 Compose 构建镜像，因为部分
Docker Compose/BuildKit 组合会静默忽略 `build.network=host`。

## 注入 mock routes

HTTP mock 固定提供 `GET /health`、内置 `POST /v1/chat/completions` SSE stub，以及 fake Hub 的
upload、execute、execution detail 与 Resource download。其余 route 从 JSON 文件读取，只按 HTTP
method 与 URL path 精确匹配，不解析请求体。默认 [mock/routes.json](mock/routes.json) 为空。

```bash
CANVAS_TEST_MOCK_ROUTES=/absolute/path/to/routes.json \
  docker compose -f deploy/test/compose.yaml up -d --wait
```

route 文件格式：

```json
{
  "routes": [
    {
      "method": "GET",
      "path": "/example",
      "status": 200,
      "headers": {
        "X-Test-Route": "example"
      },
      "json": {
        "result": "fixture"
      }
    }
  ]
}
```

响应可以使用 `json`，或使用字符串 `body` 并自行设置 `Content-Type`。同一份配置可通过
`http://opencli-hub:8080` 与 `http://comfyui:8080` 两个容器内地址访问。内置 fake Hub 只实现
Canvas adapter smoke 所需的最小协议；细粒度异常、origin、multipart 与状态边界仍由
[server.py](mock/server.py) 的 contract tests 覆盖。

## 真实 Seedance prepare-only 边界

真实 smoke 不走本容器栈，也不通过 Canvas FunctionRun，只有同时给出确认参数与环境开关才运行：

```bash
RUN_REAL_SEEDANCE_PREPARE_SMOKE=1 \
SEEDANCE_WORKSPACE_ID=... \
OPENCLI_HUB_BASE_URL=https://your-opencli-hub.example \
  ./scripts/dev/verify/smoke/seedance-prepare.sh --confirm-prepare-only
```

[scripts/dev/verify/smoke/seedance-prepare.sh](../../scripts/dev/verify/smoke/seedance-prepare.sh) 硬编码 `seedance2.0fast`、
`duration=4`、`submit=0`、`retry=0`，只验证页面准备与 checkpoint，不点击真实生成、不创建
FunctionRun、不下载或导入视频。`RUN_REAL_SEEDANCE_PREPARE_SMOKE=1` 与 `--confirm-prepare-only`
必须同时给出，否则脚本在发起任何外部请求前退出；`SEEDANCE_WORKSPACE_ID` 必须指向真实可访问的
workspace。`OPENCLI_HUB_BASE_URL` 没有默认值，必须是无 userinfo、path、query 和 fragment 的
HTTP(S) origin，脚本会先校验 origin 再去访问 Hub。

任何正式 Seedance/GPT Image 提交都可能产生费用，只能通过应用的独立真实提交开关人工启用；本
smoke 不替代那条路径。失败时脚本以非零状态退出并保留诊断，不要通过降低校验重试。
