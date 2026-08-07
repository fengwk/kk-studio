运行构建、测试、git、包管理器、移动/复制以及外部 CLI 命令。

环境：
- Shell：daemon 配置的 bash 可执行文件，使用 `-lc` 调用。
- 边界：`workdir` 必须是 daemon environment root 内已有的目录。Shell 命令内容不受文件系统 sandbox 限制，因此必须已经获得 Platform 授权。

用法：
- 使用 `bash` 执行命令；不要把它作为读取、搜索或编辑仓库文件的默认方式。
- `workdir` 默认是 Agent 当前工作目录。提供 `workdir` 时，命令从该目录执行。需要切换目录时，优先使用 `workdir`，不要在 `command` 中嵌入 `cd ... &&`。
- 命令运行在尽量接近用户终端的 Shell 环境中。
- 长时间运行的命令（例如构建、测试、大规模迁移、`mvn`、`gradle`、`docker build`）必须显式传入更大的 `timeout_seconds`，否则可能超过默认值。
- 命令创建仓库文件或目录前，必须先用文件工具确认目标父目录。
- 包含空格的文件路径必须加引号。
- 独立命令应优先通过多个并行工具调用执行；有依赖关系时使用 `&&` 串联，只有前置失败不影响后续时才使用 `;`。
- 下载、临时 clone、生成中间产物等非目标副作用，应放在仓库外的临时目录中，除非用户明确要求写入项目。

参数：
- `command`（必填）
- `workdir`（可选，默认：daemon 当前工作目录；提供后从该目录解析；需要切换目录时优先使用它，而不是在 `command` 中嵌入 `cd ... &&`）
- `timeout_seconds`（可选，默认 120 秒；正整数，最大 3600）。长时间运行的命令必须显式传入更大的值。daemon 调用截止时间仍是硬上限。

示例：
- `bash({ command: "npm test", workdir: "packages/web" })`
- `bash({ command: "mvn -q test", workdir: "services/java", timeout_seconds: 120 })`
- `bash({ command: "git status --short" })`
- `bash({ command: "mkdir -p build && cp \"source file.txt\" build/", workdir: "packages/app" })`
- `bash({ command: "mv src/old.ts src/archive/old.ts", workdir: "services/api" })`
- `bash({ command: "cp \"source file.txt\" \"target file.txt\"", workdir: "/tmp/anydir" })`
