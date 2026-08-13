# Canvas 隔离容器测试栈

`deploy/test` 提供不依赖远端服务的本地容器测试底座：

- PostgreSQL；
- Redis realtime overlay；
- MinIO 与 bucket 初始化服务；
- 一个可配置的 HTTP mock，同时提供容器网络别名 `opencli-hub` 和 `comfyui`；
- 可选的当前 `deploy/local/Dockerfile` 应用服务（`dev` profile + dev seed）。

所有宿主端口只绑定 `127.0.0.1`，服务位于当前 Compose project 的独立 bridge 网络，
不会加入生产或其他本地栈的网络。Compose 中的账号均为固定、可丢弃的测试值，不得替换
或复制生产凭据。默认流程不配置或调用远端环境、真实模型或付费接口。

## 一键验证

在仓库根目录执行：

```bash
./deploy/test/run.sh
```

脚本依次执行：

1. `docker compose config --quiet`；
2. 销毁同名隔离栈及其 PostgreSQL/MinIO volumes，确保直接重写的 V1 从空数据启动；
3. 通过显式 `docker build` 构建当前应用 Dockerfile；
4. 启动依赖并等待 healthcheck；
5. 在应用 runtime image 中确认非 root `kkstudio` 用户以及 Canvas Resource 使用的
   `ffmpeg` / `ffprobe`；
6. 执行 PostgreSQL `SELECT 1`、Redis `PING`，并检查 MinIO bucket 和 HTTP mock 健康；
7. 无论成功或失败，都执行 `down --volumes --remove-orphans`。

需要同时启动并等待应用健康检查时：

```bash
./deploy/test/run.sh --with-app
```

`--with-app` 还会使用仓库内极小 PNG/MP4 fixture 执行完整的全局 Blob + Canvas Resource
`create canvas -> reserve -> checksummed create-only PUT -> complete -> CREATE_RESOURCE_NODE ->
preview signed GET` smoke，并验证首次写入成功、不同内容的重复写入被 MinIO 拒绝、
original 字节不变、URL DTO 不暴露 bucket/key，以及 Redis Patch Cache 能从
`afterVersion=0` 返回连续 `0 -> 1` Patch。随后显式开启
`kk-studio.canvas.function.fake-enabled`，执行
`create fake-image Function node -> start -> poll -> snapshot Resource 替换 -> preview signed GET`，
并验证 `document.version` 按 resource command、Function start 与 terminal success 前进到 4
（checkpoint 不前进；HTTP wire 为非负十进制字符串，不存在 `graphRevision`）且公开 DTO
不包含 `stateJson`。最后启用仅指向
容器内 HTTP mock 的 OpenCLI Hub/GPT Image/Seedance 开关，执行
`gpt-image-2 -> fake Hub PNG -> materialize` 与
`seedance2.0fast -> fake submit/status -> fake Hub MP4 -> materialize` 两条完整 adapter
闭环。这里没有浏览器登录、真实 provider 或付费请求。

应用以 `dev` profile + dev seed 启动：`default-assistant` 指向 `stub/acceptance-stub`，
stub provider 的 `base_url` 为 `http://stub.local:8080/v1`；本栈将 `stub.local` 配置为
HTTP mock 的网络别名，由其内置的 `POST /v1/chat/completions` 确定性 OpenAI SSE 流应答。Chat smoke
断言 catalog 中 stub provider 的 endpoint 与 `configured`，然后走完整 harness 协议
`create Chat/Thread -> USER_MESSAGE -> poll snapshot 至 quiescent`，验证 durable
assistant MESSAGE 文本包含确定性 stub 回复、`TURN_END` outcome 为 `COMPLETED` 且无
`ASSISTANT_ERROR` 条目。其他本地栈若要使用该 stub 聊天，可提供同名本地 DNS/hosts
映射，或经 catalog API 把 provider 指向自己的 OpenAI 兼容端点。

应用通过环境变量连接 `postgres:5432`、`redis:6379`、`minio:9000`、`comfyui:8080`、
`opencli-hub:8080` 与 `http-mock:8080`。ComfyUI 保持禁用；OpenCLI adapters 只在该隔离栈中指向内置 fake Hub。
Canvas Resource 媒体进程显式配置为容器内的 `ffprobe` / `ffmpeg`，
临时目录为 `/tmp`，每次 finalize/materialize 都会清理自己的工作目录。

## 手动使用

```bash
# 启动依赖
docker compose -f deploy/test/compose.yaml up -d --wait

# 启动依赖和应用
docker compose -f deploy/test/compose.yaml --profile app up -d --build --wait

# 清理容器、网络与测试数据
docker compose -f deploy/test/compose.yaml --profile app down -v --remove-orphans
```

默认宿主地址：

| 服务 | 地址 |
| --- | --- |
| PostgreSQL | `postgresql://canvas_test:canvas_test_only@127.0.0.1:15432/canvas_test` |
| Redis | `redis://127.0.0.1:16379` |
| MinIO S3 API | `http://127.0.0.1:19000` |
| HTTP mock | `http://127.0.0.1:18089` |
| 可选 app | `http://127.0.0.1:18088` |

宿主端口可分别通过 `CANVAS_TEST_PG_PORT`、`CANVAS_TEST_REDIS_PORT`、`CANVAS_TEST_MINIO_PORT`、
`CANVAS_TEST_MOCK_PORT`、`CANVAS_TEST_APP_PORT` 覆盖。

应用镜像构建使用 BuildKit Maven/npm cache。脚本会继承标准
`HTTP_PROXY` / `HTTPS_PROXY`（含小写形式）；当代理是宿主 loopback 地址时，build
阶段使用 host network，并为 Maven 显式生成 Java proxy 参数，代理值不会进入 runtime
镜像。可通过 `CANVAS_TEST_BUILD_HTTP_PROXY`、`CANVAS_TEST_BUILD_HTTPS_PROXY`、
`CANVAS_TEST_BUILD_NO_PROXY`、`CANVAS_TEST_BUILD_NETWORK` 和
`CANVAS_TEST_BUILD_MAVEN_OPTS` 覆盖；带认证或非标准 URL 的代理应显式提供后两项。
脚本不委托 Compose 构建镜像，因为部分 Docker Compose/BuildKit 组合会静默忽略
`build.network=host`；构建完成后以 `docker compose up --no-build` 启动同名镜像。

## 注入 mock routes

HTTP mock 固定提供 `GET /health`、内置的 `POST /v1/chat/completions` 确定性 OpenAI
Chat Completions SSE stub（响应固定文本，`data:` 分块以 `[DONE]` 结尾，供 dev seed 的
`stub/acceptance-stub` 离线对话），并在隔离栈中模拟本切片使用的 Hub upload、
execute、execution detail 与 Resource download。其余 route 从 JSON 文件读取，只按 HTTP method 与
URL path 精确匹配，不解析或假设请求体。默认
[`mock/routes.json`](mock/routes.json) 为空。

后续 Canvas tests 可准备自己的 route 文件，并在启动前传入绝对路径：

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

响应可以使用 `json`，或使用字符串 `body` 并自行设置 `Content-Type`。同一份 route
配置可通过 `http://opencli-hub:8080` 与 `http://comfyui:8080` 两个容器内地址访问。
内置 fake Hub 只实现 Canvas adapter smoke 所需的最小协议；细粒度异常、origin、multipart
与状态边界仍由 JDK HttpServer contract tests 覆盖。

## 真实 Seedance prepare-only 边界

真实 smoke 不走本容器栈，也不通过 Canvas FunctionRun。只有同时给出确认参数与环境开关
才会运行：

```bash
RUN_REAL_SEEDANCE_PREPARE_SMOKE=1 \
SEEDANCE_WORKSPACE_ID=... \
OPENCLI_HUB_BASE_URL=http://vps-opencli-hub:8080 \
  ./scripts/seedance-prepare-smoke.sh --confirm-prepare-only
```

脚本硬编码 `seedance2.0fast`、`duration=4`、`submit=0`、`retry=0`，只验证 Jimeng 页面
准备与 checkpoint，不点击真实生成，不创建 FunctionRun，不下载或导入视频。任何正式
Seedance/GPT Image 提交都可能产生费用，只能通过应用的独立真实提交开关人工启用，绝不
属于默认测试或此 prepare-only smoke。
