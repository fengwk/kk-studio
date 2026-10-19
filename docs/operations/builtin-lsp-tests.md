# 内置 LSP 的行为验证

LSP 链路从共享 `DaemonConfiguration` 的内联 `lsp.servers` 进入服务器与项目根发现，经真实 stdio
JSON-RPC，最终服务 `lsp.goto-definition`、`lsp.workspace-symbols` 与 `lsp.java-decompile`。
验证时分别观察发现、协议、复用和进程清理，不能把假服务器返回成功当成真实 jdtls 已安装或可反编译。
行为定义见[Environment Daemon](../modules/daemon.md)，配置入口见
[Environment Daemon 安装与运行](environment-daemon.md)。

## 运行入口

```bash
npm --prefix daemon ci
npm --prefix daemon run check
npm --prefix daemon run build
npm --prefix daemon run test -- test/lsp/lsp-capabilities.test.ts test/lsp/lsp-discovery.test.ts
npm --prefix daemon run coverage
```

测试使用自带的假语言服务器，但子进程、分帧与 JSON-RPC 是真实的；会创建临时项目、启动并终止进程，
不依赖 Docker、真实 jdtls、网络或模型。代码覆盖率由 Vitest 与 `@vitest/coverage-v8` 在测试执行时统计。

## 发现正确的服务器和项目根

[`test/lsp/lsp-discovery.test.ts`](../../daemon/test/lsp/lsp-discovery.test.ts)
验证扩展名大小写、结构化配置解析、命令的绝对/家目录路径与安装状态：

- 验证同一 Git 边界内最浅的 root marker 优先于更深的 root marker。
- 验证 `.git` 文件/目录是扫描上界、缺 root marker 时的回退；非 Git 项目回退到文件父目录。
- 验证可执行命令解析支持绝对路径、`~` 与环境变量展开，并在 Windows 上支持 PATHEXT 扩展名推导。
- 验证配置中的重复 server ID 被明确拒绝，未知字段由配置校验层拒绝。

Daemon 不自动选择 JDK、安装服务器或注入 jdtls `-data`；服务器命令与标记由 operator 显式配置。
配置装配后服务器 cwd 为自动发现的项目根，不是随意的调用目录。

## 参数与请求位置

[`test/lsp/lsp-capabilities.test.ts`](../../daemon/test/lsp/lsp-capabilities.test.ts)
固定能力入口：

| 输入边界 | 证据 |
| --- | --- |
| 本地 `path` 必须绝对，相对 path 直接拒绝、不接受 workdir | 参数校验先于启动服务器进程，拒绝时不启动服务器 |
| line 必填且正整数，character 缺省为 0、不可为负 | 启动前校验参数数值与范围 |
| symbol limit 不可为负或超过 500，target 不可空白 | 参数边界在进入服务前拒绝 |
| 相对 class target 直接拒绝，绝不回退 Daemon cwd | 目标路径必须是绝对路径或受支持的 URI |

`lsp.*` schema 不含 `workdir`；`path` 与相对 class target 由能力层校验为绝对路径。
`path` 是文件系统路径；不把 goto-definition 的 `file://` 输入当作普通路径归一化。
反编译可接受提取出的 `jdt://`、`file://` 或本地 class 目标。

初始化时声明协议事实、位置编码与 workspaceFolders；位置偏移计算保证 UTF-8、CRLF、末行与 UTF-16 编码边界正确。

## 文档同步、能力与结果

[`test/lsp/lsp-capabilities.test.ts`](../../daemon/test/lsp/lsp-capabilities.test.ts) 与
[`test/lsp/lsp-protocol.test.ts`](../../daemon/test/lsp/lsp-protocol.test.ts)
承担 wire 证据：

- languageId 按扩展名映射，BOM 文本先解码；二进制文件在 didOpen 前拒绝。
- 已打开文档变化时全量 didChange + didSave，不重复 didOpen。文件消失拒绝同步，不发 didClose；文档状态在客户端关闭时整体清空。
- 未声明能力的请求不发往服务器并给出明确的不支持错误。
- 格式化 Location/LocationLink、两种 symbol 形态与空结果，`jdt://` 结果不改写。
- 仅 jdtls 路线处理 Java class 反编译请求，不用外部工具伪造源码。
- 正常应答服务器 workspace 请求和未知方法，容忍协议消息前的杂项标准输出。

read header 的可用状态由 `support()` 探测返回；服务器支持状态的发现不启动长驻进程，诊断通知不被当作模型可见文档状态。

## 复用、取消与清理

[`test/lsp/lsp-pool.test.ts`](../../daemon/test/lsp/lsp-pool.test.ts) 与
[`test/lsp/lsp-capabilities.test.ts`](../../daemon/test/lsp/lsp-capabilities.test.ts)
验证同项目复用、并发启动去重、失败共享、空闲回收、启动中关闭与崩溃后重建。
池的实现键包含 root、server id 与配置指纹；不同项目根使用独立客户端连接。

调用方超时/取消只结束请求，发送 `$/cancelRequest`，不关闭共享客户端。
LSP 三项工具（`lsp_goto_definition`、`lsp_workspace_symbols`、`lsp_java_decompile`）默认超时为 180 秒，
覆盖等待启动、initialize 与 query 全程，全程消费同一 `context.signal`。
共享池初始化生命周期默认 180 秒（独立资源生命周期保护，不重起查询 timer）；并发启动时一个 waiter 停止不影响其他等待者，
最后退出的 waiter 等待 cleanup。闲置 5 分钟回收客户端与 1 秒 shutdown 机制保持不变。

初始化失败不重试；并发等待者共享同一失败，进程范围随后收敛。
崩溃的服务器报告退出码与有界诊断，客户端标记失效并在后续请求中重建。
正常关闭走 shutdown/exit，顽固服务器及后代由 supervisor 强制清理。进程范围与平台限制见[Bash 验证](builtin-bash-tests.md#进程范围与清理)。

协议测试不覆盖所有偶发 I/O 失败，也不代替真实语言服务器的集成验收；
跨平台收敛须查看 [.github/workflows/daemon-ci.yml](../../.github/workflows/daemon-ci.yml) 的原生矩阵结果。
