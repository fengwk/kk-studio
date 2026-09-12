# Cloud File System

## 1. 定位

Cloud File System（CFS）是 kk-studio 平台拥有的、与 Environment 和 Project 解耦的虚拟文件系统。

它解决三类问题：

1. 没有 Environment 的 Agent 仍可读取和维护持久文本。
2. Project、Issue、Chat 和 Agent 可以通过稳定路径共享资料，但不建立领域外键。
3. 大型 Tool Result 可以保存为不可变 Blob，并通过同一组 Cloud 工具分片读取。

CFS 不是 Knowledge Base 的底层实现；它直接替代独立 Knowledge Base 产品域。用户可以按普通目录约定组织知识：

```text
/knowledge/projects/kk-studio/requirements.md
/knowledge/projects/kk-studio/architecture/environment.md
/knowledge/research/models/anthropic.md
/knowledge/shared/templates/review.md
```

路径只是文本引用。Project rename、archive 或 delete 不移动、不删除这些文件。

## 2. 文件空间

```text
/
|-- knowledge/                    # 用户知识目录与 Knowledge 视图入口
|-- uploads/                      # 用户上传的 BLOB nodes
`-- .artifacts/                   # system-managed，不在普通 tree 展示
    `-- tool-results/
        `-- {threadId}/
            `-- {invocationId}.txt
```

### 2.1 用户树

用户树由 PostgreSQL `cloud_node` 维护：

- `DIRECTORY`：目录。
- `TEXT`：PostgreSQL 中可编辑、带 revisions 的 UTF-8 文本。
- `BLOB`：指向 `storage_blob` 的不可变二进制或大型文件。

Migration 预建 `/knowledge`、`/uploads`、`/.artifacts` 和
`/.artifacts/tool-results`。`/knowledge` 是 Knowledge 视图的默认入口，但仍是普通 CFS
DIRECTORY；Project 不自动创建或拥有其下目录。用户可以创建其它顶层目录，唯独
`/.artifacts` 整棵子树由系统保留。

### 2.2 Tool Artifact 子树

`/.artifacts/tool-results/{threadId}/{invocationId}.txt` 是大型文本 Tool Result
的持久路径：

- DIRECTORY 和 BLOB 都是实际 `cloud_node`；
- BLOB node 指向完整输出的 ACTIVE `storage_blob`；
- `threadId`、`invocationId` 使用 canonical UUID；
- 单个 Json-only projection 可以使用 `.json`，其它文本使用 `.txt`；
- 相同 invocation 的重放只有在 blob size/hash 相等时才幂等成功；
- 普通根目录 list/find 隐藏 `.artifacts`；
- 只允许持有完整路径时执行 `cloud_read` 或对单文件执行 `cloud_grep`；
- Agent Tool 和公开 Files REST 不允许 write/edit/move/delete 该子树；
- 首版不自动删除 Artifact。

Artifact node 和 `session_blob_ref` 分别持有 Blob 引用：前者保证文件在 Session 删除后仍然存在，后者保持现有 Harness
Resource ownership 完整。Project、Issue、Chat 或 Session 删除都不隐式删除 Artifact。

完整 Artifact 路径进入 durable `ResourceMessageContent`；不复制 Blob bytes，也不持久化 URL。

## 3. 路径协议

CFS 路径是平台虚拟路径，不采用宿主 OS 规则：

- 必须是以 `/` 开头的绝对路径。
- 唯一分隔符是 `/`。
- 根路径固定为 `/`。
- 大小写敏感。
- 输入必须已经是 Unicode NFC；不满足时拒绝，不静默改写。
- 禁止空 segment、`.`、`..`、尾随 `/`（根路径除外）、NUL 和其他控制字符。
- segment UTF-8 最长 255 bytes，完整路径最长 2048 bytes。
- 不解释 `~`、`$HOME`、`${VAR}`、`%VAR%`、反斜杠或 URI scheme。
- 没有 symlink、hard link、权限位、owner、cwd、mount、设备文件或 `.gitignore`。

所有 API、Tool Result 和错误都返回 canonical 虚拟绝对路径。CFS 内部不得把虚拟路径传给 `java.nio.file.Path` 解释。

## 4. PostgreSQL 模型

### 4.1 `cloud_node`

| 字段 | 语义 |
| --- | --- |
| `id` | UUID 主键。 |
| `parent_id` | 可空；null 表示根的直接子节点。 |
| `name` | 单个 canonical segment。 |
| `kind` | `DIRECTORY`、`TEXT` 或 `BLOB`。 |
| `version` | node metadata CAS；rename/move 等操作递增。 |
| `blob_id` | BLOB 必填，其余为空，FK `storage_blob` RESTRICT。 |
| `created_at` / `updated_at` | 时间事实。 |

约束：

- 同一 parent 下 name 唯一，包括 `parent_id IS NULL` 的顶层节点。
- DIRECTORY/TEXT 的 `blob_id` 为空，BLOB 的 `blob_id` 必填。
- `/.artifacts` 只能由内部 Artifact service 修改。
- parent 必须是 DIRECTORY。

路径不冗余存入每行。服务通过 parent chain 解析；首版规模不引入 `ltree`、closure table、materialized path 或搜索索引同步器。

### 4.2 `cloud_text_revision`

| 字段 | 语义 |
| --- | --- |
| `node_id` / `revision` | 组合主键；revision 从 1 单调递增。 |
| `content` | 权威 UTF-8 文本。 |
| `size_bytes` | 严格 UTF-8 字节数。 |
| `sha256` | 文本 UTF-8 字节摘要。 |
| `is_current` | 每个 TEXT node 恰好一个 true；旧 revision 为 false。 |
| `created_at` | revision 创建时间。 |

`cloud_text_revision.node_id` 使用 RESTRICT FK 指向 TEXT node，且部分唯一索引保证同一
node 最多一条 `is_current=true`。TEXT node 的创建与首个 current revision 只通过同一
application transaction 完成；不为“每个 TEXT 至少一条 current row”增加 constraint
trigger。一次写入在同一事务内：

1. 锁定或创建 node；
2. 校验 `expected_revision`；
3. 将旧 current 标为 false；
4. 插入 N+1 current revision；
5. 提交后发布 CFS changed 通知。

旧 revision 保留，首版不做历史压缩或清理。单个 editable TEXT 最大 1 MiB UTF-8；更大内容使用 BLOB。

### 4.3 Blob ownership

创建 BLOB node 时：

1. 事务外通过现有 Storage upload/ingest 得到 ACTIVE blob；
2. 事务内插入 node；
3. 同事务 `retain(blobId)`；
4. 插入失败时不遗留 node 引用，未消费 upload 由现有清理器回收。

删除 BLOB node 时，在删除 node 的同一事务中 `release(blobId)`。S3 删除仍由已有 token-fenced Storage Maintenance 异步完成。

CFS 不复制 `storage_blob` 的 mediaType、size、hash、preview 或 S3 key。

Tool Result 外部化在 terminal history 事务中创建 Artifact BLOB node，并分别为 Artifact 与 Session
retain。S3 已写但数据库事务失败时，由现有未引用 Blob 清理机制收敛；同一路径已存在但 hash/size 不同属于 invariant
violation，不能覆盖。

## 5. 文本一致性

### 5.1 CAS

`cloud_write` 和 `cloud_edit` 必须携带 `expected_revision`：

- `0`：只允许创建不存在的 TEXT。
- `N > 0`：只允许当前 revision 恰好为 N。
- 路径存在但 kind 不是 TEXT：类型冲突。
- revision 冲突：返回当前 revision 和稳定错误，不回显当前完整内容。

不存在“无条件覆盖”、last-write-wins、自动 merge 或服务端读取最新 revision 后替调用者重试。

### 5.2 自动目录

`cloud_write` 创建新文件时可以在同一事务中创建缺失的 DIRECTORY ancestors，以保持五工具集合足够使用。规则：

- 已存在同名 TEXT/BLOB segment 时失败。
- 并发创建相同目录由唯一约束收敛，再重新解析。
- `cloud_edit` 不创建文件或目录。
- UI 的显式 mkdir 使用同一 directory service。

### 5.3 Revision 与 node version

- `expected_revision` 只保护 TEXT 内容。
- rename、move、delete 使用 `expectedVersion` 保护 `cloud_node.version`。
- 文本写入不依赖调用者提供 node version。
- 路径在内容写入事务中重新解析并锁定，不能先 resolve 后无条件更新。

## 6. 读取

### 6.1 DIRECTORY

`cloud_read` 一个 DIRECTORY 返回：

```text
path: /knowledge/projects/kk-studio
kind: directory

architecture/
requirements.md
```

- 只列直接 children。
- 按 canonical name 升序。
- DIRECTORY 以 `/` 结尾。
- 不递归，不返回 Blob URL。

### 6.2 TEXT

返回 canonical path、kind、revision、是否以换行结束，以及带 1-based 行号的窗口：

```text
path: /knowledge/projects/kk-studio/requirements.md
kind: text
revision: 7
ends_with_newline: yes

12|...
13|...
```

`offset` 默认 1，`limit` 默认 200、最大 2000。输出达到通用 read page 字节预算时可以提前停止，并给出下一行 offset。

单个物理行最多展示 2000 Unicode code points；超过后追加明确的 line-truncated 标记。本轮不提供横向分页，因此不能承诺通过 `cloud_read` 完整重建超长单行。

### 6.3 BLOB 和 Tool Artifact

- `text/plain`、`application/json` 和明确的 UTF-8 文本媒体类型：有界流式解码并按 TEXT 窗口展示。
- 支持的 image：返回稳定文本 metadata + `ResourceResultContent`。
- 其他媒体：返回 name/mediaType/size metadata + Resource，不把任意二进制当 UTF-8。
- 不可识别、非法 UTF-8 或超出读取硬上限的文本明确失败。
- 模型永远看不到 S3 URL；Provider/UI 在 attempt/render 阶段按 blobId 物化。
- `/.artifacts/tool-results/...` 与普通 BLOB 使用相同读取路径，但 mutation 权限不同。

## 7. Find 与 Grep

### 7.1 `cloud_find`

- 只匹配路径和 glob，不读取内容。
- `path` 指定用户树起点；从 `/` 或任何非 `.artifacts` path 搜索时跳过 `/.artifacts`。
- 不允许用 `cloud_find` 枚举 `/.artifacts`；Artifact 的 path 由 Tool Result 明确给出。
- 结果是 canonical 虚拟绝对路径。
- DIRECTORY 结果以 `/` 结尾。
- 使用 visitor + `limit + 1`，检测到超限立即停止。
- 不应用 `.gitignore` 或宿主隐藏文件规则。

### 7.2 `cloud_grep`

- 对用户树只扫描当前 TEXT revision。
- 对精确 `/.artifacts/tool-results/{threadId}/{invocationId}.txt` 路径可以流式扫描一个 UTF-8 文本 Blob。
- 不递归枚举 `.artifacts`。
- literal 是精确 substring；regex 使用 RE2/J 线性子集。
- `ignore_case`、`include`、`multiline` 与 Environment `grep` 保持相同含义。
- 输出 `canonical-path:line:content`。
- 使用 visitor + `limit + 1`；不把 PostgreSQL FTS、`LIKE` 或 ARE regex 冒充 grep。
- 不预建 `tsvector`、倒排索引或全文搜索服务。

## 8. Agent Tool

CFS 五工具对所有 Agent 都是平台核心工具，不要求 Environment，也不进入 Agent 的 Environment tool allowlist：

| AgentToolId | 模型名称 |
| --- | --- |
| `cloud.read` | `cloud_read` |
| `cloud.write` | `cloud_write` |
| `cloud.edit` | `cloud_edit` |
| `cloud.find` | `cloud_find` |
| `cloud.grep` | `cloud_grep` |

它们由 Platform Contributor 注册，并由 turn resolver 在每次规划中追加。Cloud write/edit 仍遵循 Harness approval policy 和 Tool side-effect 分类。

角色 Tool 与 Cloud Tool 可以同时出现；Coordinator/Executor/Reviewer 都不需要为读取 Project 文档而绑定 Environment。

完整参数和输出格式见[文件工具](filesystem-tools.md)。

## 9. REST

### 9.1 Tree 与文本

```text
GET    /api/cloud/files?path={path}
PUT    /api/cloud/text
PATCH  /api/cloud/text
POST   /api/cloud/directories
POST   /api/cloud/nodes/move
DELETE /api/cloud/nodes?path={path}&expectedVersion={version}
POST   /api/cloud/find
POST   /api/cloud/grep
```

- `GET /files` 返回 node metadata；DIRECTORY 同时返回直接 children，TEXT 返回当前 revision metadata，不默认返回完整正文。
- TEXT 正文通过带 offset/limit 的同一读取 service 获取。
- write/edit 请求与 Agent Tool 共用 command model 和 CAS service，不复制业务规则。
- move 请求提供 source path、destination path 和 source `expectedVersion`。
- 非空 DIRECTORY 删除拒绝，不做递归 delete。

### 9.2 Binary

浏览器继续使用现有 `/api/storage/uploads` 上传：

```text
reserve -> direct PUT -> complete
  -> POST /api/cloud/blobs { path, uploadId, expectedAbsent: true }
```

Cloud service 在事务内消费 READY upload、创建 BLOB node 并配对 retain。下载/预览继续通过现有 blob presign API，不新增 CFS 长期 URL。

## 10. Frontend

`/files` 页面复用 kk-studio 现有布局和资源组件，交互借鉴 kk-circle 的目录树与按媒体类型选择面板，但不复用其本地磁盘、visitor path 或下载 token 实现。

页面结构：

```text
+---------------+----------------------------------+
| directory tree| selected file                    |
|               | text editor / media preview      |
|               | revision / size / copy path      |
+---------------+----------------------------------+
```

首版支持：

- create directory；
- create/edit Markdown 或 plain text；
- upload Blob；
- rename/move；
- delete empty directory or file；
- copy canonical path；
- text revision conflict 保留草稿；
- image/audio/video/download 使用现有 Resource URL resolver；
- `/.artifacts` 不出现在普通 tree，只能从 Tool message 的 Artifact action 打开。

不建立动态文件插件框架。按 `kind + mediaType/extension` 使用明确的小型 renderer mapping。

## 11. 通知、缓存与故障

- CFS mutation 提交后发送 `cloud_files_changed`，payload 只含受影响 node/path 的有界提示。
- 前端通知后失效相关 query 并回读；通知丢失、重连和 malformed payload 均通过页面 refetch 收敛。
- CFS node/text 以 PostgreSQL 为权威，不使用本地磁盘缓存作为事实。
- Blob 内容仍以 S3 为权威；数据库可用但 S3 不可用时，tree/metadata/text 仍可读，Blob read/upload 明确不可用。
- Tool Result 需要外部化但 Blob persistence 失败时，不返回“截断成功”；按[工具输出](tool-output.md)收敛 UNKNOWN/失败。

## 12. 删除与保留

- TEXT revisions 随 node 显式删除；首版不提供单 revision 删除。
- BLOB node 删除释放一份 ref；同 Blob 的 Session、Canvas、Upload 或其他 CFS node 引用不受影响。
- `/.artifacts` 首版没有公开 delete 或自动 retention；后续若增加清理器，必须先删除 node 并配对 release。
- Project/Chat/Issue 删除不扫描 CFS 用户路径。
- CFS root 不允许删除。

## 13. 验收

- Path parser 对 root、NFC、空 segment、dot segment、控制字符、长度和 reserved `.artifacts` 有完整单元测试。
- PostgreSQL 集成测试覆盖顶层唯一、parent kind、TEXT/BLOB check、CAS、并发 create、move cycle 和非空目录删除。
- 文本 create=expected 0、update=N、stale conflict 和 revision history 均有真实数据库测试。
- Blob node 的 upload consume、retain/release、删除和 Storage GC 配对成立。
- `cloud_find`/`cloud_grep` 在 `limit + 1` 后停止，regex 不进入 Java Pattern 或 PostgreSQL regex。
- `.artifacts` 不能 enumerate 或公开 mutate，但持有 Artifact 路径时可 read/grep。
- Session 删除后 Artifact node 与 Blob 保留；Artifact 创建与 history transaction 的失败不会留下可见半成品。
- 没有 Environment 的 Agent 可以使用全部五个 Cloud Tool。
- Project 删除不会修改任何 CFS node。
- 前端 CAS 冲突保留草稿，Blob URL 只在 render attempt 获取。
