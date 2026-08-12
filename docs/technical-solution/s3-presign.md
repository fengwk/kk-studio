# S3 预签名直传与直下载

## 职责

本通用端点只为 ComfyUI 临时输入对象提供 S3 直传 / 直下载。全局 Blob Storage 复用底层
`S3PresignService`，但通过 `/api/storage/*` 暴露不含 bucket/key 的专用 DTO；Canvas 与 Chat 不接受
客户端对象 key。

## 端点

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `POST` | `/api/s3/presigned-uploads` | 为 `comfyui-inputs/` 下对象的 `PUT` 上传生成签名响应 |
| `POST` | `/api/s3/presigned-downloads` | 为 `comfyui-inputs/` 下对象的 `GET` 下载生成签名响应 |

请求体字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `key` | string | 是 | 对象键；经 `S3ObjectKeyNormalizer.normalize` 校验后必须位于 `comfyui-inputs/` 下且具有非空对象后缀；空白、前导 `/`、`.` / `..` 段、控制字符、UTF-8 超过 1024 字节或其它命名空间一律拒绝 |
| `contentType` | string | 否 | 仅 `presigned-uploads` 有效；空白视为未提供；非空时校验长度（≤255）、控制字符和 media type 语法，并按 Spring `MimeTypeUtils` 规范化 |
| `expiresInSeconds` | long | 否 | 缺省使用服务端默认（600s）；必须为正数且不超过服务端上限（3600s） |

响应体字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `bucket` | string | 始终来自服务端配置 |
| `key` | string | 服务端校验后的对象键 |
| `method` | string | `PUT` 或 `GET` |
| `url` | string | 已签名 URL，path-style |
| `headers` | map<string,string> | 调用方发起请求时必须显式设置的已签名头（典型如 `Content-Type`）；浏览器自动发送且脚本禁止设置的 `Host` 不会返回 |
| `expiresAt` | string | UTC ISO-8601 过期时刻 |

## 配置

```yaml
kk-studio:
  storage:
    s3:
      enabled: true
      endpoint: http://minio:9000           # 服务端 SDK 读写使用的 endpoint
      public-endpoint: https://objects.example.com  # 浏览器直传/直下发的可外部访问 endpoint
      region: us-east-1
      bucket: kk-studio                       # 固定 bucket，调用方不能选择
      access-key: ${S3_ACCESS_KEY}
      secret-key: ${S3_SECRET_KEY}
      presign-default-expires-seconds: 600    # 可选
      presign-max-expires-seconds: 3600       # 可选
      public-base-url: ...                    # 服务端合成永久直链时使用的公开基础 URL（与浏览器预签名 URL 无关）
```

启用开关：`kk-studio.storage.s3.enabled=true` 未设置时，`S3Client`、`S3Presigner`、`S3StorageService`、`S3PresignService`、`StudioS3PresignController` 都不会被注册，部署可以安全地省略对象存储配置。

## 关键约束

- bucket 与 region 全部来自服务端配置；调用方既不能选择也不能覆盖。
- 服务端 `endpoint` 与浏览器 `public-endpoint` 必须显式分离；预签名 URL 只能落在 `public-endpoint` 上（未配置时回退到 `endpoint`，仅适合内网场景）。
- 所有面向固定 bucket 的服务端读取与浏览器预签名链路都必须使用 `S3ObjectKeyNormalizer.normalize` 校验 key，避免签名键与实际读取键出现语义偏差。
- 通用预签名 HTTP 端点只接受 `comfyui-inputs/` 下具有非空后缀的 key；`uploads/`、`blobs/`
  及其它命名空间返回 400，不能借此读取或覆盖全局 Blob 对象。
- 响应 `headers` 不会返回 `Host`：浏览器根据 URL 自动发送且脚本禁止设置的该头不应该出现在响应中。
- 全局 `/api/storage/uploads` 未命中去重时调用
  `presignChecksummedCreateOnlyUpload`，对象键固定为 `uploads/{uploadId}/original`。签名与返回
  headers 同时包含 `If-None-Match: *` 和 `x-amz-checksum-sha256`；浏览器必须原样发送，生产
  bucket CORS 必须允许这两个头。
- complete 使用 checksum-mode HEAD 复核 SHA-256/大小，再物化
  `blobs/{blobId}/original`；渲染期只通过
  `/api/storage/blobs/{blobId}/presigned-original|presigned-preview` 获取短期 URL。原件响应同时返回
  `storage_blob` 的权威 `mediaType/sizeBytes`；preview 与 upload 签名中的这两个字段为 null。
- `S3StorageService` 同时提供必须关闭的流式 read、HEAD 与 delete；既有 ComfyUI
  bounded byte[] 下载继续保留，并在 HEAD 与实际读取两阶段执行大小上限校验。

## 实现位置

| 关注点 | 文件 |
| --- | --- |
| 配置属性 | `core/src/main/java/fun/fengwk/kkstudio/core/storage/configuration/S3StorageProperties.java` |
| Spring 自动配置 | `core/src/main/java/fun/fengwk/kkstudio/core/storage/configuration/S3StorageConfiguration.java` |
| 预签名服务 | `core/src/main/java/fun/fengwk/kkstudio/core/storage/S3PresignService.java` |
| 预签名实现 | `core/src/main/java/fun/fengwk/kkstudio/core/storage/S3PresignServiceImpl.java` |
| 对象键校验 | `core/src/main/java/fun/fengwk/kkstudio/core/storage/S3ObjectKeyNormalizer.java` |
| 服务端读写 | `core/src/main/java/fun/fengwk/kkstudio/core/storage/S3StorageService(Impl).java` |
| HTTP 入口 | `web/src/main/java/fun/fengwk/kkstudio/web/controller/StudioS3PresignController.java` |
| 请求 / 响应 DTO | `share/src/main/java/fun/fengwk/kkstudio/share/storage/S3Presigned{Request,Response}DTO.java` |
