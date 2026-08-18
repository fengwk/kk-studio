# Environment Workspace 绑定

Environment 的选择在持久化与 wire 上都是**原子**的完整绑定 `{name, workspacePath}` 或 `null`（未绑定）：name 是 canonical 路由身份，workspacePath 是 Environment Root 下的 canonical 相对 wire 路径。不存在只传 name 或只传 path 的中间形态，也不存在独立展示名。

事实源：`harness/tool` 的 `EnvironmentBinding` / `EnvironmentWorkspacePath` / `EnvironmentName`，`share` 的 `EnvironmentBindingDTO` / `HarnessBranchSettingsDTO` / `ToolInvocationDTO` / `HarnessThreadCommandCreateDTO`，`core` 的 `CoreToolGateway`（preflight）与 `EnvironmentDaemonGateway`，`harness/daemon` 的 `DaemonRuntime` / `EnvironmentDirectoryBrowser`，前端 `EnvironmentWorkspacePanel`。

## 1. 原子语义与 canonical 规则

- `EnvironmentBinding(environmentName, workspacePath)` record 两字段均 non-null；null binding 由引用方以 Java null 表示，**绝不构造半空 record**。DTO 侧 `environment` 字段 `@JsonInclude(ALWAYS)`：`null` 显式序列化（解绑），非 null 时两字段都必须提供且经 domain 严格校验。
- **name**（`EnvironmentName`）：canonical bounded 小写路由名称，`^[a-z0-9]+(-[a-z0-9]+)*$`，≤64 字符，无空白、无 `'/'`、不以 `'-'` 开头或结尾；同一名称同一时刻至多由一个 live daemon 持有。
- **workspacePath**（`EnvironmentWorkspacePath`）：跨平台纯字符串契约，`'.'` 单独出现表示 Environment Root；段一律以 `'/'` 分隔（拒绝反斜杠、Windows drive 前缀 `C:/x`/`C:x` 与以 `'/'` 开头的 absolute）、无空段、无 `'.'`/`'..'` 段、无 ISO 控制字符、≤2048 字符。越界判定（symlink 等）由 daemon 在 canonicalize 时完成。`EnvironmentBinding` 与 `DaemonDirectoryCodec` 共用同一 validator，避免两套路径语义。

## 2. Chat default 与 branch snapshot

- `chat.environment` 是可空的**默认**完整 binding（两列同存同空）：空面板/线程草稿以此为起点，发送前可更改或清空。Chat 设置不复制到 Thread。
- Thread 的 `BranchSettings.environment` 是 durable 快照中的唯一路由身份：ROOT/TURN_START 完整冻结，Resolver 按 **latest-snapshot-wins** 读取（只使用最近一个快照，null/缺失/不可用绝不向更旧快照回退）。
- 前端 Blank pane 的 draft 从 Chat 默认 binding 物化（可 null）；Bound pane 从 snapshot `branchSettings` 初始化；整个 binding 原子比较/复制，绝不单独透传 name。

## 3. SET_ENVIRONMENT：缺省 vs 显式 null

`SET_ENVIRONMENT` 命令的 `environment` 字段在 JSON 中**必须出现**，可空对象：显式 `null` 表示解绑，非 null 必须是完整 `{name, workspacePath}`。字段缺失（缺省）按 400 拒绝；其余命令类型即使提供显式 null 的 `environment` 也按 forbidden 拒绝。前端 diff 只在 binding 变化时发出该命令，固定顺序 `SET_ENVIRONMENT -> SET_AGENT -> SET_MODEL -> SET_ACTIVE_TOOLS`；YOLO 是直接控制面（`PUT /yolo`），绝不作为命令发送。

## 4. ToolInvocation 冻结

`ToolInvocationDTO.environment` 是 `@JsonInclude(ALWAYS)` 的 nullable 完整 binding：**PLATFORM 工具恒为 null，ENVIRONMENT 工具为冻结的 `{name, workspacePath}`**。执行与 retry 都只消费冻结 binding，绝不依据后续 Agent/Environment 配置重新选择；ENVIRONMENT 工具按 `binding.environmentName` 路由到对应 live daemon 连接。

## 5. ENVIRONMENT preflight 与 daemon 执行

- **preflight**（`CoreToolGateway`）：ENVIRONMENT 工具的权限路径上下文体现冻结 binding 的 workspace——以配置的 `environmentRoot` 作为逻辑 root，把 canonical `workspacePath` 纯路径解析为 effective workdir；**不查询 live registry、不做文件系统 IO**。权限 `path` pattern 的唯一基准是该 effective workdir，`environmentRoot` 只参与 workdir 计算与边界定义，不再额外生成 Environment root 相对匹配 alias。PLATFORM 工具与 null binding 保持 server 默认 workdir；null binding 的确定性拒绝发生在 `start`（发送前 `Rejected`，绝不进入 transport，避免 null binding 变成不确定结果）。
- **daemon 执行**（`DaemonRuntime.canonicalWorkspace`）：INVOKE 的 `workspacePath` 先过 `EnvironmentWorkspacePath` 形状校验，再相对 environment root 解析并 `toRealPath()` canonicalize；symlink 越界（real path 不在 root 内）、**路径已删除**、非目录都是确定性 FAILED，且必须在发送 `STARTED` 之前完成——绝不产生 STARTED 后再失败。root 内 symlink alias 的 real path 仍在 root 内时允许。

## 6. 目录 API 隐私

`GET /api/ai/environments/{name}/directories?path=.` 是 control-plane 只读查询（不走 Tool Invocation/Permission，不占 active tool slot）：

- `path` 缺省 `'.'`；响应 `path`/`parentPath`/entry `path` 都是 canonical wire 路径；`parentPath` 必须等于请求 `path` 的 lexical 父路径（root 与单段路径均为 `'.'`）；`displayPath` 固定为**请求 `path` 的最后一段**（root 为 `'.'`），只作展示，codec 层严格拒绝任何其它值（含旧/恶意 daemon 泄漏的本地绝对路径）；entry 只含 `{name,path}` 且 `name` 必须等于 `path` 最后一段，且 `entries` 不得超过 1000 条。
- daemon 侧 `EnvironmentDirectoryBrowser`：`resolve().normalize()` 后 `toRealPath()` canonicalize，越出 root 即 `INVALID_PATH`；列表默认不暴露 symlink 目录；显式请求 root 内 symlink alias 时回显 alias 本身，内部只用 real path 校验与读取；只列真实目录、按名称稳定排序、最多 1000 条（超出置 `truncated`）；无法编码为合法 wire 子路径的本地目录名被跳过；不存在 → `NOT_FOUND`，非目录 → `NOT_DIRECTORY`，本地 IO 失败 → `IO_ERROR`。目录文件系统 IO 提交到 daemon 级共享有界单 worker。
- HTTP 映射：`ENVIRONMENT_NOT_FOUND`/`NOT_FOUND` → 404，`ENVIRONMENT_UNAVAILABLE` → 409，`INVALID_PATH`/`NOT_DIRECTORY` → 400，`TIMEOUT` → 504，`IO_ERROR` → 502。

## 7. 内联 picker

`EnvironmentWorkspacePanel` 是唯一可复用的内联 picker（列表模式选择 READY Environment → 目录模式单层浏览）：

- 目录 UI 只依赖安全 wire path（`path`/`parentPath`/`entries[].path`）；`displayPath` 绝不当绝对路径使用；Escape/关闭只调用 `onClose`，绝不调用 `onSelect`。
- **confirm guard**：只有「目录查询成功、响应 `path` 与请求 `path` 精确一致、无错误、非 pending/fetching」时才允许确认（`canConfirm`）；响应 `path` 与请求不一致视为 invalid response，禁用确认并显示加载失败；missing/error 时同样禁用。确认只提交 `{name, workspacePath: data.path}`。
- 父路径由 canonical 相对路径的 lexical 规则计算（不依赖目录查询结果），因此 404/409/其他错误下仍可按安全 wire path 向上恢复。

相关文档：[environment-daemon-gateway.md](environment-daemon-gateway.md)、[architecture.md](architecture.md)、[frontend-implementation-design.md](frontend-implementation-design.md)、[e2e-regression.md](e2e-regression.md)。
