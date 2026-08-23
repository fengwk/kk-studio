# System Settings

## 1. 职责与事实源

系统级、非敏感的产品行为与运行软策略统一存放在 PostgreSQL `system_setting`。该表恒有一行
`id=1`：

- `config jsonb`：六个完整强类型 section；
- `version bigint`：从 0 开始的 CAS 版本；
- `created_at` / `updated_at`：创建与最后更新时间。

`SystemSettings` 是领域事实，`SystemSettingsCodec` 是唯一 JSON/DTO 边界。Codec 对持久化 JSON
执行严格解码：未知字段、缺失必填对象、错误类型、字符串与整数互相强转、尾随 token 都会失败。
领域 record 的 canonical constructor 负责范围、跨字段和启用前提校验。数据库默认行与
`SystemSettings.DEFAULT` 由自动化测试保持一致。

## 2. HTTP 与并发写入

设置 API：

```text
GET /api/settings
GET /api/settings/schema
PUT /api/settings
```

`GET /api/settings` 返回六个 section、`version` 与时间戳；`GET /api/settings/schema`
返回 ordered sections/groups/fields，是服务端设置 UI 元数据的唯一事实源。PUT 必须发送六个完整 section 和
`expectedVersion`；服务端执行：

```text
严格 DTO 映射与领域校验
-> 读取当前版本
-> update ... where id=1 and version=expectedVersion
-> version + 1
-> 回读权威聚合
```

校验失败返回 400，固定行缺失返回 404，CAS 冲突返回 409 并携带 expected/actual version。
HTTP wire 上的 Java `Long` 使用非负十进制字符串，`Integer` 使用 JSON number，避免浏览器
整数精度损失。

当前应用没有 Spring Security、用户或管理员角色。`PUT /api/settings` 只适用于受信任的
单用户部署；开放多用户访问前必须先建立管理员鉴权，不能直接暴露全局权限与 YOLO 写入口。

## 3. Section 与生效时机

| Section | 内容 | 生效时机 |
| --- | --- | --- |
| `tool` | permission、默认 YOLO、Model/Tool Gateway 重试延迟、skill 加载超时 | permission：下一次 preflight；defaultYolo：下一次未显式指定模式的 Chat 创建；gateway/skill 超时：下一次 invocation |
| `aiRuntime` | invocation retry；`compactionKeepRecentTokens=20000` 与可空 `compactionFallbackModel`；subagent depth/idle/turn，每父 `subagentMaxConcurrency=10`、每 root tree `subagentMaxTotalConcurrency=0`（primitive `int` 必填，0 表示不额外限制） | live：下一次 invocation（retry 判定 / 压缩规划 / task spawn/reserve） |
| `environment` | daemon heartbeat、目录查询、资源预算 | 下一次判定或请求 |
| `integrations` | ComfyUI、OpenCLI Hub、Seedance、GPT Image 2、MiniMax H3 非敏感参数 | 重启 |
| `storageMedia` | S3 启用、上传/预签名预算、Canvas 媒体处理预算 | Canvas 媒体超时/缩略图：下一次 probe/preview；S3 启用与预签名预算：重启 |
| `advanced` | processor、dispatcher、executor、realtime 与事件通道预算 | 重启 |

`SystemSettingsSnapshot` 是进程内 live 快照：启动时读取一次，PUT 在事务 `afterCommit` 成功后通过
`SystemSettingsChangeHandler` 回读权威记录并原子替换（回滚绝不更新内存）。提交后的回读失败只记日志，
不改变已完成写结果，快照保持原值等待后续通知恢复。跨节点刷新由统一 PostgreSQL listener 调用
`SystemSettingsChangeHandler.onNotification(payload)`；listener 建连或重连后调用 `onResync()` 补齐断连窗口。
两条入口都忽略 payload 语义并回读 `system_setting.id=1`，回读失败只记日志，不中断 listener。Snapshot
同时保存 repository record version，并以 CAS 门控阻止较旧的并发回读覆盖较新配置。

aiRuntime 的 compaction / retry / subagent 决策点经
`CompactionConfigProvider` / `InvocationRetryPolicyProvider` / `SubagentConfigProvider` 每次现读快照，
无需重启；同一 ModelInvocation 的 retry 仍重放冻结 spec，live 只影响新的 retry 判定、新的压缩规划与新的 task spawn。
`tool.gateway`、`environment.runtime` 与 `storageMedia.canvasMedia` 同样在决策点现读快照：新的 Busy/Overloaded 延迟、新的 heartbeat/目录超时/资源上限、新的 ffmpeg/ffprobe 超时与缩略图预算。进行中的请求仍使用开始时的值。 Daemon WebSocket 单帧上限是部署配置 `kk-studio.harness.environment-gateway.max-message-bytes`（`${KK_STUDIO_ENVIRONMENT_GATEWAY_MAX_MESSAGE_BYTES:16777216}`），不进 SystemSettings。
其余 restart-required 配置 bean 仍共享装配期读取的同一快照值，DB 变更需重启生效。
`SystemSettingsToolSettingsProvider` 是运行期按调用现读通道：permission 在下一次权限预检时生效，
defaultYolo 在下一次 Chat 创建读取默认值时生效。

## 4. Settings UI

General 只管理当前浏览器的 `BrowserPreferences`，保存在
`kkstudio.browser-preferences.v1`，不写数据库。服务端 section/group/field 全部由
`GET /api/settings/schema` 动态渲染，前端不维护六套 server field 列表；通用类型为
`BOOLEAN/INTEGER/LONG/TEXT/ENUM/PERMISSION/MODEL_SELECTION`，只有 permission 与 nullable
model selection 使用 custom renderer。permission 的 tool 名与 compaction fallback model
都从 catalog 下拉选择，交互与 Agent 表单的 Default Model 一致。schema 缺失、重复 path
或未知类型时 fail closed。

服务端设置共享同一个聚合 draft，保存时发送完整 CAS PUT；409 通过全站共享 conflict presenter
展示 `errors.reason -> ApiError.code -> CONFLICT` 的稳定原因，不自动覆盖本地 draft。用户确认后刷新并
重新读取最新聚合，当前未保存修改随刷新丢弃。

## 5. 不进入 SystemSettings 的配置

以下内容继续由部署配置或业务实体持有：

- DB 连接、数据库/应用端口、worker 进程开关；
- Environment root/workdir、ffmpeg/ffprobe 路径、临时目录；
- S3 endpoint/region/bucket、OpenCLI instance identity；
- daemon token、API key、access key/secret key、bearer token；
- Provider/Model/Agent、Chat、Canvas function 等资源自身属性；
- 协议帧、标识长度、持久化 payload、Tool result 与 Daemon WebSocket 单帧上限等安全不变量；
- Locale、Pane、Composer、Canvas viewport 等浏览器本地偏好。

这条边界避免把秘密写入普通 JSONB，也避免让运行中的进程通过数据库改变文件系统、连接身份或协议安全边界。
