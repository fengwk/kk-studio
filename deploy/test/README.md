# Canvas/Storage 隔离测试栈

[compose.yaml](compose.yaml) 与 [scripts/dev/verify/smoke/offline-chat.sh](../../scripts/dev/verify/smoke/offline-chat.sh) 提供不依赖远端服务的容器测试底座：PostgreSQL、
MinIO 与 bucket 初始化、一个可配置 HTTP mock，以及可选的当前 App 镜像。它用于 Canvas
Resource/Blob、fake Canvas Function、OpenCLI fake Hub adapter 和离线 Chat 的确定性 smoke。

这个文件同时也是可靠性栈唯一的 Compose 定义，`canvas-test` / `app` profile 服务于本页的离线
smoke，`reliability` profile 由
[scripts/dev/verify/reliability/stack.sh](../../scripts/dev/verify/reliability/stack.sh) 启动。
需要本地可用界面时用 [deploy/local](../local/README.md)；可靠性栈、分布式双节点和生产部署见
[部署与运行](../../docs/operations/deployment.md)。

## 前置条件

- Docker Engine 与 Docker Compose v2。
- `--with-app` 还需要 Node（宿主侧 smoke 脚本）。
- 所有宿主端口只绑定 `127.0.0.1`，服务位于本 Compose project 的独立 bridge 网络，不会加入
  生产或其它本地栈的网络。
- Compose 中的账号都是固定、可丢弃的测试值，不要替换成生产凭据。默认流程不配置也不调用
  远端环境、真实模型或付费接口。

MinIO server 的平台限制、mc 首次联网构建与源码许可见
[开发栈 MinIO 依赖](../dependencies/minio-client/README.md)；ARM 宿主需要 amd64 模拟能力。

## Profiles 与 project

`compose.yaml` 用三个 profile 划分服务；文件顶部的 `name: kk-studio-canvas-test` 只是默认
project 名：

| profile | 服务 | 用途 |
| --- | --- | --- |
| `canvas-test` | `postgres`、`minio`、`minio-init`、`http-mock` | 基础 smoke 基础设施 |
| `app` | 上述基础设施 + `app` | 基础栈加当前 App 镜像 |
| `reliability` | `reliability-postgres`、`reliability-minio`、`reliability-minio-init`、`reliability-app`、`workspace-init`、`daemon` | 与离线 smoke 完全隔离的可靠性栈 |

基础设施和 app 服务都带 profile，默认配置不会启用它们：手动命令必须显式写
`--profile canvas-test`（只启动基础设施）或 `--profile app`（基础设施加 app）。

本页所有手动命令都显式指定 `kk-studio-canvas-test` project。可靠性栈使用
`reliability-*` 服务名和独立的 `reliability` 网络，只通过 `stack.sh` 在
`kk-studio-reliability` project 中启动；因为这个 Compose 文件是共用的、默认 project 是
`kk-studio-canvas-test`，手工执行可靠性 Compose 命令时必须始终同时给出
`-p kk-studio-reliability` 与 `COMPOSE_PROFILES=reliability`，覆盖可能继承的其他 profile；
否则会落进离线 smoke 的 project 或混入不属于可靠性栈的服务，
并可能被 `down --volumes` 连同 smoke 数据一起删除。可靠性栈的卷名前缀是
`kk-studio-reliability_*`，与本页的 `kk-studio-canvas-test_*` 互不影响，两套清理命令不要混用。
如果该 project 已有旧的 `postgres`、`minio`、`minio-init` 或 `app` 容器，
启动前先检查并停止这些旧服务，同时保留所需卷，避免新旧服务同时访问同一份数据。

```bash
./scripts/dev/verify/reliability/stack.sh up
./scripts/dev/verify/reliability/stack.sh status
./scripts/dev/verify/reliability/stack.sh down --volumes
```

## 一键验证

```bash
./scripts/dev/verify/smoke/offline-chat.sh
./scripts/dev/verify/smoke/offline-chat.sh --with-app
```

[scripts/dev/verify/smoke/offline-chat.sh](../../scripts/dev/verify/smoke/offline-chat.sh) 依次执行：

1. 以 `canvas-test` 和 `app` profile 分别校验 Compose 配置；
2. 无确认提示地销毁同名隔离栈及其 PostgreSQL/MinIO volumes，从空数据启动；
3. 用显式 `docker build` 构建当前应用 Dockerfile（不委托 Compose 构建），更新本机同名镜像 tag；
4. `docker compose build minio-init` 构建共享 MinIO client 镜像；
5. 启动依赖并等待 healthcheck（基础设施按服务名显式启动，app 走 `--profile app`）；
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
- stub Chat：用经 Catalog API 创建的 `default-assistant` 创建 Chat，再以 `owner: {type: "CHAT", chatId: ...}` 提交
  Harness command batch 创建 Thread、发送 `USER_MESSAGE`，poll 至 quiescent，断言
  durable assistant MESSAGE、`TURN_END` 为 `COMPLETED` 且无 `ASSISTANT_ERROR` 条目。

这条 smoke 使用容器内 mock，不需要浏览器登录或真实 Provider 凭据，也不提交付费请求。
它定义要检查的范围，不是某次执行的通过记录；检查失败即非零退出并输出诊断。

## 手动使用

以下命令均在仓库根目录执行。

```bash
# 仅启动常驻依赖，再单独运行一次性 bucket 初始化
COMPOSE_PROFILES=canvas-test docker compose -p kk-studio-canvas-test -f deploy/test/compose.yaml up -d --wait postgres minio http-mock
COMPOSE_PROFILES=canvas-test docker compose -p kk-studio-canvas-test -f deploy/test/compose.yaml run --rm --no-deps minio-init

# 启动依赖和应用
COMPOSE_PROFILES=app docker compose -p kk-studio-canvas-test -f deploy/test/compose.yaml up -d --build --wait

# 停止容器和网络，保留手动验证的数据
COMPOSE_PROFILES=app docker compose -p kk-studio-canvas-test -f deploy/test/compose.yaml down --remove-orphans

# 确认测试数据可丢弃后才执行：额外删除 PostgreSQL 与 MinIO volumes，无确认提示
COMPOSE_PROFILES=app docker compose -p kk-studio-canvas-test -f deploy/test/compose.yaml down -v --remove-orphans
```

每条命令都要显式给出 profile：`COMPOSE_PROFILES=canvas-test` 只启用基础 smoke 基础设施，
`COMPOSE_PROFILES=app` 同时启用基础设施和 app。不要在这些命令里启用 `reliability`，可靠性栈
始终通过 `stack.sh` 在 `kk-studio-reliability` project 中运行（见上节）。

没有 app 时，不要对全部服务直接 `up --wait`：成功退出的 `minio-init` 没有常驻 health 状态，
此时也没有 app 的 `service_completed_successfully` 依赖承接它。上面的两步与 smoke 脚本一致。
启动 app 时它会等待 `minio-init` 成功退出。手动启动默认保留已有数据；需要构建时 Compose 使用
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

本栈不预置 Catalog。离线对话 smoke 通过 Catalog API 创建 `stub` Provider
（`base_url` 为 `http://stub.local:8080/v1`）、`acceptance-stub` Model 和指向它的
`default-assistant` Agent；本栈把 `stub.local` 配成 HTTP mock 的网络别名，由其内置的
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
upload、execute、execution detail 与 Resource download。自定义 route 从 JSON 文件读取（每个请求重新读取），
在内置 chat completions stub 与 fake Hub 之前匹配：按 HTTP method 与 URL path 精确匹配，可选的字符串
`bodyIncludes` 要求 UTF-8 请求体包含该子串；多条 route 同时满足时取文件中的第一条。没有 route 命中时
才交给内置 stub 与 fake Hub。默认 [mock/routes.json](mock/routes.json) 为空。

```bash
CANVAS_TEST_MOCK_ROUTES=/absolute/path/to/routes.json COMPOSE_PROFILES=canvas-test \
  docker compose -p kk-studio-canvas-test -f deploy/test/compose.yaml up -d --wait postgres minio http-mock
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
    },
    {
      "method": "POST",
      "path": "/v1/chat/completions",
      "bodyIncludes": "\"role\":\"tool\"",
      "status": 200,
      "headers": {
        "Content-Type": "text/event-stream"
      },
      "body": "data: {\"id\":\"mock-cmpl\",\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"
    }
  ]
}
```

响应可以使用 `json`，或使用字符串 `body` 并自行设置 `Content-Type`。同一份配置可通过
`http://opencli-hub:8080` 与 `http://comfyui:8080` 两个容器内地址访问。内置 fake Hub 只接受
合成单 Bearer（默认 `synthetic-opencli-key`），上传、执行、查询、取消与资源下载各受保护端点均严格验证；
offline smoke 经统一插件配置与 secret API 初始化，不将秘密明文写入 seed 或 schema。细粒度异常、
origin、multipart 与状态边界由 [server.mjs](mock/server.mjs) 的 contract tests 覆盖。

## 真实 Seedance prepare-only 边界

真实 smoke 不走本容器栈，也不通过 Canvas FunctionRun，只有同时给出确认参数与环境开关才运行：

```bash
RUN_REAL_SEEDANCE_PREPARE_SMOKE=1 \
SEEDANCE_WORKSPACE_ID=... \
OPENCLI_HUB_BASE_URL=https://your-opencli-hub.example \
OPENCLI_API_KEY=... \
  ./scripts/dev/verify/smoke/seedance-prepare.mjs --confirm-prepare-only
```

[scripts/dev/verify/smoke/seedance-prepare.mjs](../../scripts/dev/verify/smoke/seedance-prepare.mjs) 硬编码 `seedance2.0fast`、
`duration=4`、`submit=0`、`retry=0`，只验证页面准备与 checkpoint，不点击真实生成、不创建
FunctionRun、不下载或导入视频。`--confirm-prepare-only` 必须是唯一参数，同时要求
`RUN_REAL_SEEDANCE_PREPARE_SMOKE=1`、非空 `SEEDANCE_WORKSPACE_ID`、非空
`OPENCLI_HUB_BASE_URL` 和显式调用方 `OPENCLI_API_KEY`；真实脚本仅接受显式 Key，不取外部真实 Key 或开启收费测试，
缺任一条件都在发起外部请求前退出。workspace 必须真实可访问，
Hub URL 必须是无 userinfo、path、query 和 fragment 的 HTTP(S) origin，先校验再访问。
`OPENCLI_HUB_INSTANCE_ID` 可选，`SEEDANCE_PREPARE_SMOKE_PROMPT` 可覆盖准备提示词。

任何正式 Seedance/GPT Image 提交都可能产生费用，只能通过应用的独立真实提交开关人工启用；本
smoke 不替代那条路径。失败时脚本以非零状态退出并保留诊断，不要通过降低校验重试。
