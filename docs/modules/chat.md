# Chat

Chat 是直接持有 Harness Session 的产品之一：一次对话把多个 Session 归在一个 Chat 下，Session
本身仍由 [Harness Runtime](harness-runtime.md) 执行与持久化。Chat 只保存对话集合与归属边，
不复制 Thread、Entry 或命令事实。先从[系统设计](../system-design.md)理解模块边界；表结构与约束见
[Schema](schema.md)。

[`apps/chat`](../../apps/chat/pom.xml) 是建立在平台之上的应用，集中提供 Chat 领域行、PostgreSQL
持久化、用例、wire DTO 与 HTTP 控制器，经
[`ChatAutoConfiguration`](../../apps/chat/src/main/java/fun/fengwk/kkstudio/chat/ChatAutoConfiguration.java)
装配进宿主应用。它通过 [Platform](platform.md) 的公开服务使用 Agent 目录、Environment 目录、Session
摘要查询与深删除编排，并实现平台定义的 `SessionOwnership`；平台从不依赖 Chat。`chat` 与
`chat_session` 两张表只由本模块的 `JdbcClient` 仓库访问。模块依赖方向由仓库检查
[`check.mjs`](../../scripts/dev/verify/repository/check.mjs) 校验。

## 模块结构

| 包 | 内容 |
| --- | --- |
| [chat](../../apps/chat/src/main/java/fun/fengwk/kkstudio/chat) | `Chat`/`ChatSession` 值、两张表的 `JdbcClient` Repository、CRUD 与深删除服务、目录守卫、可变字段规范化、`ChatSessionOwnership` 与自动装配入口 |
| [dto](../../apps/chat/src/main/java/fun/fengwk/kkstudio/chat/dto) | `/api/chat/chats` 的公开 wire DTO；由契约生成器扫描，产物写入前端 `contracts.ts` |
| [http](../../apps/chat/src/main/java/fun/fengwk/kkstudio/chat/http) | [`ChatController`](../../apps/chat/src/main/java/fun/fengwk/kkstudio/chat/http/ChatController.java)：只做 HTTP 映射，路径参数用 UUID |

## HTTP 接口

| 方法与路径 | 语义 |
| --- | --- |
| `GET /api/chat/chats?cursor=&limit=` | 按最近更新倒序返回 Chat 一页；`nextCursor` 非空表示还有下一页 |
| `POST /api/chat/chats` | 创建 Chat，返回 201；省略 `yoloEnabled` 为 `false`，`environmentName` 可省略或为 null（都表示无默认环境），携带时必须为已存在的 Environment 名 |
| `GET /api/chat/chats/{chatId}` | 读取单个 Chat 的权威版本 |
| `PUT /api/chat/chats/{chatId}` | 整体替换 `title`、`agentName`、`environmentName` 与 `expectedVersion`；未知或非 canonical Environment 名返回 400 且当前值/version 不变 |
| `PUT /api/chat/chats/{chatId}/yolo` | 单独设置 `yoloEnabled`，与 Chat 其他修改共享同一版本 |
| `DELETE /api/chat/chats/{chatId}?expectedVersion=` | 深删除 Chat 及其全部归属 Session，返回 204 |
| `GET /api/chat/chats/{chatId}/sessions?cursor=&limit=` | 按归属时间从新到旧返回该 Chat 的 Session 摘要一页 |
| `GET /api/chat/chats/{chatId}/sessions/{sessionId}` | 按 id 读取单个归属 Session 摘要；不存在或不属于该 Chat 返回 404 |

两个列表接口使用 keyset 分页：`limit` 缺省 50、范围 `[1, 100]`，`cursor` 是上一页返回的
`nextCursor`，首屏省略；排序键包含唯一 id，因此相同时间戳与同名标题下也不会跳行或重复。Chat 的
稳定顺序是 `(updatedAt desc, createdAt desc, title asc, id asc)`，Session 是
`(createdAt desc, sessionId desc)`。

成功响应直接返回资源；响应的 `environmentName` 是始终存在的 required-nullable 字段（未选择时为
null）。`agentName` 与 `environmentName` 只按名称软引用目录行，不建立
外键：Agent 或 Environment 硬删除期间引用暂时无法解析，同名重建后重新生效。名称的 canonical
校验只由本模块写入路径按当前目录完成，且只校验本次整体替换中发生变化的值，因此目录行已删除时
仍可编辑 Chat 的其他字段；数据库不重复目录命名规则；Chat 的生命周期由创建、修改和深删除直接管理。
HTTP 中 64 位整数（包括版本）在请求与响应中都是规范十进制字符串；客户端每次更新必须回传版本。
客户端输入错误在产生处以 `DomainException` 表达，由 Web 统一映射为 problem 响应。

## 归属与深删除

`CHAT` 是平台编排认识的产品 owner 之一。
[`ChatSessionOwnership`](../../apps/chat/src/main/java/fun/fengwk/kkstudio/chat/ChatSessionOwnership.java)
实现平台的 `SessionOwnership`：`chat_session` 直接持有 Session 且以 `session_id` 为主键，因此一个
Session 至多属于一个 Chat；命令接受与人工交互对 `chat` 行取 KEY SHARE，允许分叉与通用命令直写，
深删除对 `chat` 行取排他锁。删除 Chat 时先排他锁定并校验版本，再经平台的
`SessionDeletionOrchestrator` 在同一事务内按 owner → tree → Session → Thread 的锁序删除整棵执行树、
释放 Blob 引用并删除归属行，最后 CAS 删除 `chat` 行；任一步失败整体回滚。

## 验证

纯逻辑（字段规范化、归属 SPI 契约、HTTP 映射）用单元测试；仓库、归属锁、深删除与 Session 摘要查询
用真实 PostgreSQL Testcontainers 集成测试，容器不可用时失败而非静默跳过。
