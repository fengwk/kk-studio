# Canvas 隔离容器测试栈

`deploy/test` 提供不依赖远端服务的本地容器测试底座：

- PostgreSQL；
- MinIO 与 bucket 初始化服务；
- 一个可配置的 HTTP mock，同时提供容器网络别名 `opencli-hub` 和 `comfyui`；
- 可选的当前 `deploy/local/Dockerfile` 应用服务。

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
2. build 当前应用 Dockerfile；
3. 启动依赖并等待 healthcheck；
4. 在应用 runtime image 中确认非 root `kkstudio` 用户以及 Canvas Resource 使用的
   `ffmpeg` / `ffprobe`；
5. 执行 PostgreSQL `SELECT 1`、检查 MinIO bucket 和 HTTP mock 健康；
6. 无论成功或失败，都执行 `down --volumes --remove-orphans`。

需要同时启动并等待应用健康检查时：

```bash
./deploy/test/run.sh --with-app
```

`--with-app` 还会使用仓库内极小 PNG fixture 执行完整的 Canvas Resource
`create canvas -> reserve -> browser-style direct PUT -> complete -> preview-url -> direct GET`
smoke，并验证 Canvas DTO 不暴露 bucket/key。

应用通过环境变量连接 `postgres:5432`、`minio:9000`、`comfyui:8080` 和
`opencli-hub:8080`。当前尚未启用 ComfyUI/OpenCLI adapter，相关 base URL 只作为后续
联调注入点。Canvas Resource 媒体进程显式配置为容器内的 `ffprobe` / `ffmpeg`，
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
| MinIO S3 API | `http://127.0.0.1:19000` |
| HTTP mock | `http://127.0.0.1:18089` |
| 可选 app | `http://127.0.0.1:18088` |

宿主端口可分别通过 `CANVAS_TEST_PG_PORT`、`CANVAS_TEST_MINIO_PORT`、
`CANVAS_TEST_MOCK_PORT`、`CANVAS_TEST_APP_PORT` 覆盖。

## 注入 mock routes

HTTP mock 固定提供 `GET /health`。其余 route 从 JSON 文件读取，只按 HTTP method 与
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
配置可通过 `http://opencli-hub:8080` 与 `http://comfyui:8080` 两个容器内地址访问；
具体 adapter route 应由对应 contract test 提供，而不是固化在此测试底座。
