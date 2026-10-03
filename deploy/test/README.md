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

MinIO server 的平台限制、mc 首次联网构建与源码许可见
[开发栈 MinIO 依赖](../dependencies/minio-client/README.md)；ARM 宿主需要 amd64 模拟能力。

## 一键验证

```bash
./scripts/dev/verify/smoke/offline-chat.sh
./scripts/dev/verify/smoke/offline-chat.sh --with-app
```

[scripts/dev/verify/smoke/offline-chat.sh](../../scripts/dev/verify/smoke/offline-chat.sh) 依次执行：

1. `docker compose config --quiet` 校验默认与 `--profile app` 配置；
2. 无确认提示地销毁同名隔离栈及其 PostgreSQL/MinIO volumes，从空数据启动；
3. 用显式 `docker build` 构建当前应用 Dockerfile（不委托 Compose 构建），更新本机同名镜像 tag；
4. `docker compose build minio-init` 构建共享 MinIO client 镜像（复用缓存层）；
5. 启动依赖并等待 healthcheck；
6. 在应用 runtime image 中确认非 root `kkstudio` 用户以及 Canvas Resource 使用的 `ffmpeg` / `ffprobe`；
7. 执行 PostgreSQL `SELECT 1`，检查 MinIO bucket 与 HTTP mock（health、确定性 OpenAI SSE）；
8. 无论成功或失败，都执行 `down --volumes --remove-orphans`，失败时先输出 compose 诊断。

两种执行模式都会在开始和退出时删除 `kk-studio-canvas-test` project 的数据卷，
不能用于保留中的开发数据，更不能把生产凭据或健康的共享 PG/S3 接入该栈。
默认应用镜像 tag 为 `kk-studio-app:canvas-test`；同名旧镜像 tag 会被本次构建更新。
脚本只接受 `--with-app` 与 `-h` / `--help`，其它参数报错退出。

`--with-app` 在步骤 7 之后用仓库内极小 PNG/MP4 fixture 检查应用契约：

- 全局 Blob `reserve -> checksummed create-only PUT -> complete`，验证首次写入成功、不同内容的
  重复写入被 MinIO 拒绝、original 字节不变、URL DTO 不暴露 bucket/key；
- Canvas document/snapshot 的 `revision`，`CREATE_NODE` 挂载 Blob Resource，patch 的变化集、
  幂等重放不推进 revision，以及 preview signed GET；具体 typed command 契约见
  [`canvas-api.mjs`](../../scripts/dev/verify/e2e/cases/canvas-api.mjs)；
- `/api/canvas-functions` 的函数目录与 `SET_NODE_FUNCTION` 的函数名、结构化 args 和
  `expectedFunction` 前置条件；打开 `kk-studio.canvas.function.fake-enabled` 后的 fake image：start、poll、
  terminal snapshot 替换与 preview signed GET；
- OpenCLI fake Hub 上的 `gpt-image-2` 与 `seedance2.0fast` 两条 adapter 闭环，验证 GPT Image
  四个、Seedance 六个 durable checkpoint；
- `dev` seed 的 stub Chat：创建 Chat，再以 `owner: {type: "CHAT", chatId: ...}` 提交
  Harness command batch 创建 Thread、发送 `USER_MESSAGE`，poll 至 quiescent，断言
  durable assistant MESSAGE、`TURN_END` 为 `COMPLETED` 且无 `ASSISTANT_ERROR` 条目。

这条 smoke 使用容器内 mock，不需要浏览器登录或真实 Provider 凭据，也不提交付费请求。
它定义要检查的范围，不是某次执行的通过记录；检查失败即非零退出并输出诊断。

## 手动使用

以下命令均在仓库根目录执行。

```bash
# 仅启动常驻依赖，再单独运行一次性 bucket 初始化
docker compose -f deploy/test/compose.yaml up -d --wait postgres minio http-mock
docker compose -f deploy/test/compose.yaml run --rm --no-deps minio-init

# 启动依赖和应用
docker compose -f deploy/test/compose.yaml --profile app up -d --build --wait

# 停止容器和网络，保留手动验证的数据
docker compose -f deploy/test/compose.yaml --profile app down --remove-orphans

# 确认测试数据可丢弃后才执行：额外删除 PostgreSQL 和 MinIO volumes，无确认提示
docker compose -f deploy/test/compose.yaml --profile app down -v --remove-orphans
```

没有 app 时，不要对全部服务直接 `up --wait`：成功退出的 `minio-init` 没有常驻 health 状态，
此时也没有 app 的 `service_completed_successfully` 依赖承接它。上面的两步与 smoke 脚本一致。
启动 app 时它会等待初始化成功退出。手动启动默认保留已有数据；需要构建时 Compose 使用
[deploy/local/Dockerfile](../local/Dockerfile)，也可先自行构建镜像。

默认宿主地址：

| 服务 | 地址 |
| --- | --- |
| PostgreSQL | `postgresql://canvas_test:canvas_test_only@127.0.0.1:15432/canvas_test` |
| MinIO S3 API | `http://127.0.0.1:19000` |
| HTTP mock | `http://127.0.0.1:18089` |
| 可选 app | `http://127.0.0.1:18088` |

宿主端口可分别用 `CANVAS_TEST_PG_PORT`、`CANVAS_TEST_MINIO_PORT`、`CANVAS_TEST_MOCK_PORT`、
`CANVAS_TEST_APP_PORT` 覆盖，应用镜像 tag 用 `CANVAS_TEST_APP_IMAGE` 覆盖；容器内端口固定。应用以 `dev,canvas-test` profile 启动，
`KK_STUDIO_STORAGE_S3_*` 指向容器内 `minio:9000`，public endpoint 指向
`http://127.0.0.1:19000`，媒体进程使用容器内 `ffprobe`/`ffmpeg`，临时目录 `/tmp`。
Compose 卷键为 `minio-data` 与 `postgres-data`；默认 Docker 卷名为
`kk-studio-canvas-test_minio-data` 与 `kk-studio-canvas-test_postgres-data`。

应用 seed 后 `default-assistant` 指向 `stub/acceptance-stub`，stub provider 的 `base_url` 是
`http://stub.local:8080/v1`；本栈把 `stub.local` 配成 HTTP mock 的网络别名，由其内置的
`POST /v1/chat/completions` 确定性 SSE 应答。ComfyUI 保持禁用，OpenCLI adapters 只在该隔离栈中
指向内置 fake Hub。

## 构建代理

应用镜像构建使用 BuildKit Maven/npm cache。脚本继承标准 `HTTP_PROXY` / `HTTPS_PROXY` /
`NO_PROXY`（含小写形式）；能解析为 Java proxy 参数的 HTTP(S) 代理会让脚本默认选择 host build network，
不只限于 loopback。Maven proxy 参数同时显式生成；代理值不会进入 runtime 镜像。

可用 `CANVAS_TEST_BUILD_HTTP_PROXY`、`CANVAS_TEST_BUILD_HTTPS_PROXY`、
`CANVAS_TEST_BUILD_NO_PROXY`、`CANVAS_TEST_BUILD_NETWORK` 和 `CANVAS_TEST_BUILD_MAVEN_OPTS`
覆盖；带认证或非标准 URL 的代理应显式提供后两项。脚本不委托 Compose 构建镜像，因为部分
Docker Compose/BuildKit 组合会静默忽略 `build.network=host`。

## 注入 mock routes

HTTP mock 固定提供 `/health`、内置 `POST /v1/chat/completions` SSE stub，以及 fake Hub 的
upload、execute、execution detail 与 Resource download。其余 route 从 JSON 文件读取，只按 HTTP
method 与 URL path 精确匹配，不解析请求体。默认 [mock/routes.json](mock/routes.json) 为空。

```bash
CANVAS_TEST_MOCK_ROUTES=/absolute/path/to/routes.json \
  docker compose -f deploy/test/compose.yaml up -d --wait postgres minio http-mock
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
FunctionRun、不下载或导入视频。`--confirm-prepare-only` 必须是唯一参数，同时要求
`RUN_REAL_SEEDANCE_PREPARE_SMOKE=1`、非空 `SEEDANCE_WORKSPACE_ID` 和非空
`OPENCLI_HUB_BASE_URL`；缺任一条件都在发起外部请求前退出。workspace 必须真实可访问，
Hub URL 必须是无 userinfo、path、query 和 fragment 的 HTTP(S) origin，先校验再访问。
`OPENCLI_HUB_INSTANCE_ID` 可选，`SEEDANCE_PREPARE_SMOKE_PROMPT` 可覆盖准备提示词。

任何正式 Seedance/GPT Image 提交都可能产生费用，只能通过应用的独立真实提交开关人工启用；本
smoke 不替代那条路径。失败时脚本以非零状态退出并保留诊断，不要通过降低校验重试。
