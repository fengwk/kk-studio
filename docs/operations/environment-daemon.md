# Environment Daemon 安装与运行

Environment Daemon 把 Studio 的文件、命令、检索与 LSP 工具调用执行在你的主机上。每台需要提供宿主能力的主机单独安装一个 Daemon，以**当前用户**身份运行。默认安装官方 GitHub Release，不需要克隆仓库，也不需要 Git 或 Maven。

## 安装

先在 Studio 的 Environment 页面创建目标 Environment，复制 registration token，并准备 gateway 地址，例如：

```text
wss://studio.example.com/api/harness/environment-daemon/v1
```

主机需要 **JDK 21 与 Bash**。安装器会检查 Java 版本为 21，并要求 Java home 中同时有 `java` 与 `javac`；不是只装一个 JRE。Java home 按 `JAVA_HOME_21` → `JAVA_HOME` → PATH 查找，也可以用安装参数指定。gateway 仅支持 `ws://` / `wss://`；公网使用 `wss://`，每个终止 WebSocket 的代理层都须支持 `permessage-deflate` 压缩。

| 平台 | 额外条件 | 常驻方式 |
| --- | --- | --- |
| Linux | 可用的 `systemctl --user`，curl 与 `sha256sum` 或 `shasum` | 用户服务 `kk-studio-daemon.service` |
| macOS | 已登录桌面，可用的 `gui/$(id -u)` 域；curl 与 `shasum` | LaunchAgent `fun.fengwk.kkstudio.environment-daemon` |
| Windows 10/11 | PowerShell 5.1 或 7、ScheduledTasks 模块、PATH 上的 `bash.exe` | 当前用户的 AtLogOn 计划任务，仅在该用户交互登录期间运行 |

Windows 的 Bash 可来自 Git for Windows 或兼容实现；这是命令工具的解释器要求，并不要求使用 Git 克隆源码。macOS 纯 SSH 会话如果没有桌面登录域，不能安装 LaunchAgent。Linux 若需注销后继续运行，由管理员开启 `loginctl enable-linger <用户名>`，安装器不会代为开启。

### Linux / macOS

在目标用户的终端复制执行：

```bash
curl -fsSL https://raw.githubusercontent.com/fengwk/kk-studio/main/scripts/daemon/install.sh | bash
```

默认命令是 `install`。脚本从终端询问 gateway 和 token，**token 输入不回显**；即使脚本来自管道，交互也从 `/dev/tty` 读取。没有交互终端时，须显式给出 gateway 与 token 文件路径。

### Windows

在目标用户的 PowerShell 中下载为临时文件，再用 `powershell -File` 执行；不使用 `iex`：

```powershell
$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
$installer = Join-Path ([IO.Path]::GetTempPath()) ("kk-studio-install-" + [Guid]::NewGuid().ToString("N") + ".ps1")
try {
    Invoke-WebRequest -UseBasicParsing -Uri "https://raw.githubusercontent.com/fengwk/kk-studio/main/scripts/daemon/install.ps1" -OutFile $installer
    powershell -NoProfile -ExecutionPolicy Bypass -File $installer
    if ($LASTEXITCODE -ne 0) { throw "Daemon install failed: $LASTEXITCODE" }
} finally {
    Remove-Item -LiteralPath $installer -Force -ErrorAction SilentlyContinue
}
```

这段命令为 PowerShell 5.1 的下载启用 TLS 1.2，并避免依赖 IE 的页面解析。安装器同样询问 gateway，通过 `Read-Host -AsSecureString` 隐藏 token 输入。服务任务直接执行 Java，应用参数按 UTF-8 Base64 传输，支持含中文或 emoji 的路径、备注，不必修改系统 code page。Base64 是编码，不是加密。

### 安装器会保存什么

默认下载 latest 对应的 `kk-studio-daemon-<tag>.jar` 与 `.jar.sha256`，核对摘要，再执行 JAR 的 `--version` 入口预检。发布元数据、LICENSE 与 THIRD_PARTY_NOTICES 可在 [GitHub Releases](https://github.com/fengwk/kk-studio/releases) 获取。安装器只写当前用户的服务定义与 JAR，不安装系统服务、管理命令 wrapper 或环境变量配置文件。

| 内容 | Linux / macOS | Windows |
| --- | --- | --- |
| JAR | `~/.local/lib/kk-studio/kk-studio-daemon.jar` | `%LOCALAPPDATA%\kk-studio\daemon\kk-studio-daemon.jar` |
| 交互输入的 token | `~/.config/kk-studio/daemon.token` | `%LOCALAPPDATA%\kk-studio\config\daemon.token` |
| 默认数据目录 | `~/.kk-studio` | `%USERPROFILE%\.kk-studio` |

Unix token 文件为 0600，新建凭证目录为 0700；现存父目录须由当前用户拥有且不可被 group/other 写入，脚本不会自动修复它们。Windows 新建凭证目录与文件由当前 SID 拥有，禁用 ACL 继承，只允许当前 SID FullControl；已有不安全目录、链接或 reparse point 会被拒绝。token 是磁盘上的明文凭证，隐藏输入不等于加密存储。

服务定义与 Daemon argv 只保存 token 的**文件路径**，不保存 token 文本。避免把 token 放入命令历史、日志或文档；直接 token 参数虽然可用，但会暴露在安装器 argv 和可能的 shell 历史中，优先交互输入或文件模式。

Daemon 有当前用户的文件与命令权限，**没有文件系统沙箱**。只把 Environment 开放给可信的 Studio 用户和 Agent；`workdir` 是调用目录，不是权限边界，HOME 也不是默认工作目录。

## 验证

安装器默认最多等待 30 秒，再检查运行状态持续保持 3 秒。它验证的是服务或任务存活，**不是 gateway 注册成功**。

| 平台 | 主机侧检查 | 日志 |
| --- | --- | --- |
| Linux | `systemctl --user is-active kk-studio-daemon.service` | `journalctl --user -u kk-studio-daemon.service -n 50 --no-pager` |
| macOS | `launchctl print gui/$(id -u)/fun.fengwk.kkstudio.environment-daemon` | `~/Library/Logs/kk-studio/environment-daemon.stdout.log` 与 `environment-daemon.stderr.log` |
| Windows | 下文安装器的 `status` 命令，或 Task Scheduler | Task Scheduler **不捕获 stdout/stderr** |

最后必须在 Studio 的 Environment 页面确认 **`READY`**。之后可在 Chat 分支中选择该 Environment，并给 Agent 配置所需的 Environment Tools。READY 后 Platform 会异步同步已发布的 Skill Package；同步失败不撤销 READY，其它宿主能力仍可用，技能引用可退回 Platform Skill URI。

## 一键更新

更新时不必重复填写 gateway、token 或其它配置，也不需要 `git pull`。

Linux / macOS：

```bash
curl -fsSL https://raw.githubusercontent.com/fengwk/kk-studio/main/scripts/daemon/install.sh | bash -s -- upgrade
```

Windows：

```powershell
$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
$installer = Join-Path ([IO.Path]::GetTempPath()) ("kk-studio-install-" + [Guid]::NewGuid().ToString("N") + ".ps1")
try {
    Invoke-WebRequest -UseBasicParsing -Uri "https://raw.githubusercontent.com/fengwk/kk-studio/main/scripts/daemon/install.ps1" -OutFile $installer
    powershell -NoProfile -ExecutionPolicy Bypass -File $installer upgrade
    if ($LASTEXITCODE -ne 0) { throw "Daemon upgrade failed: $LASTEXITCODE" }
} finally {
    Remove-Item -LiteralPath $installer -Force -ErrorAction SilentlyContinue
}
```

`upgrade` 要求已有受管安装，默认下载 latest，只替换 JAR 并重启，保留服务定义、token 和数据目录。下载、SHA 校验或 JAR 入口预检失败时，旧 JAR 与旧服务不动；进入替换、重启阶段后的失败返回非零，**不承诺自动回滚**。Windows 的 `install` 会先保存交互 token，因此不要把重新安装与只更新 JAR 的 `upgrade` 混为一谈。

重启会中断在途工具调用；进程内 invocation journal 不跨重启保留，已经发生的命令副作用不回滚。更新后重新确认 Studio `READY`。

需要固定版本时，把实际 release tag 传给 `upgrade --version vX.Y.Z`（Unix）或 `upgrade -Version vX.Y.Z`（Windows）；`install` 也接受该参数。不带版本的下一次更新仍取 latest，pin 不是永久更新策略。

旧版或手工维护的 **legacy unmanaged** unit、plist、同名任务不能自动接管。安装器按服务定义的所有权标记拒绝覆盖或删除；先确认配置、token 与数据位置，手动停止并迁移旧服务，再执行 `install`。不要仅补标记来绕过检查。

## 日常管理

可把脚本保存在用户目录，便于执行 `status`、`uninstall` 或带参数的 `install`。**本地脚本不会自动更新；升级前重新下载，才能拿到新版安装器。**

Linux / macOS：

```bash
curl -fsSL https://raw.githubusercontent.com/fengwk/kk-studio/main/scripts/daemon/install.sh -o "$HOME/kk-studio-install.sh"
bash "$HOME/kk-studio-install.sh" status
# 需要卸载时执行：
# bash "$HOME/kk-studio-install.sh" uninstall
```

Windows：

```powershell
$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
$installer = Join-Path $env:USERPROFILE "kk-studio-install.ps1"
Invoke-WebRequest -UseBasicParsing -Uri "https://raw.githubusercontent.com/fengwk/kk-studio/main/scripts/daemon/install.ps1" -OutFile $installer
powershell -NoProfile -ExecutionPolicy Bypass -File $installer status
# 需要卸载时执行：
# powershell -NoProfile -ExecutionPolicy Bypass -File $installer uninstall
```

`status` 只读，不启动服务。退出码 `0`：Linux active、macOS loaded、Windows Running；`1`：未安装或不是受管安装；`3`：已有受管安装但未达到上述状态。macOS loaded 甚至不保证进程仍存活，三者都不能替代 Studio READY。

### 改配置与轮换 token

`upgrade` 不接收 gateway、LSP 等运行配置。改配置时重新执行 `install`，提供完整配置；使用已有 token 文件可避免再次输入：

```bash
bash "$HOME/kk-studio-install.sh" install \
  --gateway-uri wss://studio.example.com/api/harness/environment-daemon/v1 \
  --registration-token-file "$HOME/.config/kk-studio/daemon.token"
```

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "$env:USERPROFILE\kk-studio-install.ps1" install `
  -GatewayUri wss://studio.example.com/api/harness/environment-daemon/v1 `
  -RegistrationTokenFile "$env:LOCALAPPDATA\kk-studio\config\daemon.token"
```

Studio 重新生成 token 后，用交互安装重写默认 token，或安全更新原有文件再重启服务。Daemon 每次 HELLO 前读取文件；已被拒绝而退出的进程需要重启。

```bash
# Linux
systemctl --user restart kk-studio-daemon.service
# macOS
launchctl kickstart -k "gui/$(id -u)/fun.fengwk.kkstudio.environment-daemon"
```

```powershell
$task = "kk-studio-environment-daemon-" + [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
Stop-ScheduledTask -TaskName $task
# 确认任务离开 Running/Queued 后再启动
Get-ScheduledTask -TaskName $task
Start-ScheduledTask -TaskName $task
```

### 卸载与数据保留

`uninstall` 停止服务、删除受管服务定义和 JAR，**保留 token 文件与整个数据目录**；macOS 日志和 Linux journal 历史也不主动清理。Windows 计划任务不是 Windows Service，重启设置仅为尽力而为，不能提供 systemd 等价的守护与日志语义。

数据目录持有 `daemon.lock`，同一目录只允许一个 Daemon。`resources/text` 保存命令与检索的大文本全文，模型历史可能仍引用其绝对路径，不会自动删除；`skills` 保存已安装包，`skill-work` 保存 Git 缓存及安装中间产物。启动清理遗留资源 `.part`，技能安装器恢复或清理 staging/backup，但保留发布后的全文与技能。彻底删除前先评估历史引用，并手动清理 token、数据和日志；卸载本身不会抹掉这些数据。

## 可选 LSP

**LSP client 已在 Daemon JAR 中**，不需要额外安装客户端。外部语言服务器及其运行依赖由你安装；Daemon 不负责下载或升级它们。没有 `--lsp-config` / `-LspConfig` 时，LSP 查询不可用，但文件、命令、检索仍可使用。

例如已安装 Node/npm 的开发主机可先安装 TypeScript 服务器：

```bash
npm install -g typescript typescript-language-server
```

将下面的有效 JSON 保存为 UTF-8 文件，例如 Unix 的 `$HOME/.config/kk-studio/lsp.json`，或 Windows 的 `%LOCALAPPDATA%\kk-studio\config\lsp.json`：

```json
{
  "servers": {
    "typescript": {
      "command": ["typescript-language-server", "--stdio"],
      "extensions": [".ts", ".tsx", ".js", ".jsx"],
      "rootMarkers": ["tsconfig.json"],
      "firstMatchMarkers": ["package.json"]
    }
  }
}
```

`command` 是程序与参数数组，不是 shell 命令字符串。它必须能在**服务进程**中解析；服务 PATH 可能不同于交互终端，推荐将首元素换为本机可执行程序的绝对路径。Windows 若使用 npm 的 `.cmd` 入口，最稳妥的是直接配置 Node 的绝对路径和已安装服务器的 JS 入口。可先安装到明确目录（需要该目录的写权限）：

```powershell
npm install --prefix C:\tools\lsp typescript typescript-language-server
```

再使用以下 JSON；Node 不在默认位置时修改首元素：

```json
{
  "servers": {
    "typescript": {
      "command": ["C:/Program Files/nodejs/node.exe", "C:/tools/lsp/node_modules/typescript-language-server/lib/cli.mjs", "--stdio"],
      "extensions": [".ts", ".tsx", ".js", ".jsx"],
      "rootMarkers": ["tsconfig.json"],
      "firstMatchMarkers": ["package.json"]
    }
  }
}
```

用完整 `install` 命令把配置路径写入服务定义：

```bash
bash "$HOME/kk-studio-install.sh" install \
  --gateway-uri wss://studio.example.com/api/harness/environment-daemon/v1 \
  --registration-token-file "$HOME/.config/kk-studio/daemon.token" \
  --lsp-config "$HOME/.config/kk-studio/lsp.json"
```

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "$env:USERPROFILE\kk-studio-install.ps1" install `
  -GatewayUri wss://studio.example.com/api/harness/environment-daemon/v1 `
  -RegistrationTokenFile "$env:LOCALAPPDATA\kk-studio\config\daemon.token" `
  -LspConfig "$env:LOCALAPPDATA\kk-studio\config\lsp.json"
```

配置文件路径必须绝对，不接受字面的 `~` 或环境变量占位符。安装器只检查文件存在、可读并透传路径；Daemon 在启动时解析 JSON，未知字段、空服务器表或非法条目会导致启动失败。更改同一文件内容后重启即可；更改路径则重新 install。

`extensions` 必须带前导点，匹配不区分大小写，同一后缀由先声明的服务器负责。项目根从目标文件向上查找：优先最靠上的 `rootMarkers` 命中，其次最近的 `firstMatchMarkers`，否则用文件父目录；不越过最近的 `.git` 目录或 gitfile。服务器在该项目根启动，调用 `workdir` 只解析相对文件路径。

Java 可声明 `command: ["/opt/jdtls/bin/jdtls"]`、`extensions: [".java"]`、`rootMarkers: ["pom.xml", "build.gradle"]`。程序基名识别为 jdtls 后才提供 `java/classFileContents`，`jdt://` 反编译不能在其它服务器上使用。客户端连接按项目复用，调用超时或取消只取消请求，不杀掉其它调用共享的服务器。

## 参数与排错

安装器的完整用法由 [Unix 脚本](../../scripts/daemon/install.sh) 的 `--help` 和 [Windows 脚本](../../scripts/daemon/install.ps1) 的 `-Help` 提供：

| Unix | Windows | 用途 |
| --- | --- | --- |
| `--version <vTAG>` | `-Version <vTAG>` | install/upgrade 固定发布版本，tag 匹配 `^v[0-9A-Za-z._-]+$` |
| `--gateway-uri` | `-GatewayUri` | install 的 gateway；省略则交互询问 |
| `--registration-token-file` | `-RegistrationTokenFile` | 现存非空、owner-only 普通文件的绝对路径，与直接 token 互斥 |
| `--registration-token` | `-RegistrationToken` | 写入默认 token 文件；优先隐藏交互输入，避免 argv/历史暴露 |
| `--java-home` | `-JavaHome` | 指定 JDK 21 home 的绝对路径 |
| `--data-dir` | `-DataDir` | 数据目录绝对路径，默认用户 HOME 下 `.kk-studio` |
| `--bash-executable` | `-BashExecutable` | 命令执行的 Bash；Windows 推荐明确指向 Git Bash，而不是 WSL 的 `System32\bash.exe` |
| `--note` | `-Note` | 最多 512 字符、单行、无周边空白的可信备注；进入模型 SYSTEM Prompt，不放凭证或不可信文本 |
| `--lsp-config` | `-LspConfig` | 外部语言服务器配置文件的绝对路径 |
| `--from-source` | `-FromSource` | **仅供开发者**：从本地 checkout 用 Maven 构建，不可与版本 pin 混用 |

只有源码模式需要仓库与 Maven（Windows 为 `mvn.cmd`）；从 checkout 执行脚本，或通过 `KK_STUDIO_REPO_ROOT` 指向 checkout。`status` / `uninstall` 不接收安装或发布物参数。

手工 token 文件在 Unix 上须属于当前用户、无 group/other 权限位、属主可读且不是符号链接。Windows 须由当前 SID 拥有、禁用 ACL 继承、无其它 SID 的 Allow ACE、无 deny-read ACE 且当前 SID 可读，不能是 reparse point。安装器文件模式只校验元数据，不读内容；Daemon 启动时检查普通文件与 POSIX 权限，在每次 HELLO 前读 UTF-8 内容并去外围空白；Daemon 自身不复核 Windows DACL，因此不要绕过安装器的 ACL 校验。

`DAEMON_VERIFY_TIMEOUT_SECONDS`（默认 30）与 `DAEMON_VERIFY_STABLE_SECONDS`（默认 3）可覆盖验证窗口，均须十进制非负整数；0 表示立即检查，不等待对应窗口。

| 现象 | 处理 |
| --- | --- |
| Java 版本或 home 预检失败 | 指定真正的 JDK 21 home，检查 `bin/java` 与 `bin/javac`；PATH 中的 java shim 未必指向正确 home |
| GitHub 下载或 SHA 校验失败 | 检查网络、代理与 release 是否含成对资产；不要跳过校验。upgrade 仍保留旧服务 |
| `unmanaged unit` / `unmanaged plist` / `unmanaged Scheduled Task` | 手动迁移旧服务，不伪造所有权标记；Linux 标记是 unit 首行，macOS 是 plist 第二行，Windows 是精确任务 Description |
| token 父目录权限失败 | Unix 确认共享需求后去掉 group/other 写权限；Windows 检查当前 SID、继承与其它 Allow ACE。安装器拒绝不安全现存目录，不自动放宽规则 |
| `environment registration is rejected` | 从目标 Environment 重新复制 token，安全更新文件并重启 |
| WebSocket close code 1010 | 所有终止 WebSocket 的代理层启用 `permessage-deflate` |
| 数据目录已占用 | 停止冲突进程，或给第二个 Daemon 独立 `--data-dir`；不要删锁文件来绕过独占锁 |
| `systemctl --user` 不可用 | 确认用户登录实例，不在无用户 systemd 的容器中使用受管安装 |
| macOS GUI 域不可用或 bootout 失败 | 登录桌面，用 `launchctl print` 检查旧 label；卸载完成之前不要覆盖旧 plist |
| Windows 缺 ScheduledTasks 或 `bash.exe` | 使用完整 Windows PowerShell 会话，安装兼容 Bash，必要时传 `-BashExecutable` 绝对路径 |
| Windows 执行策略阻止脚本 | 按上文 `powershell -ExecutionPolicy Bypass -File` 执行；组织策略仍可能限制运行 |
| 服务存活但未 READY | 核对 gateway、token、网络和压缩；Linux 看 journal，macOS 看日志，Windows 用前台复现 |
| LSP 显示未安装或查询失败 | 检查服务可见的程序路径、服务器依赖与项目根；read header 的 supported 只证明配置/程序发现，不证明服务器能初始化 |

### 前台诊断

先停止受管服务，避免数据锁冲突，再用相同 gateway、token 文件与数据目录启动 JAR，输出直接写控制台；`Ctrl-C` 结束，不提供常驻守护。Unix 示例：

```bash
"$JAVA_HOME_21/bin/java" -jar "$HOME/.local/lib/kk-studio/kk-studio-daemon.jar" \
  --gateway-uri wss://studio.example.com/api/harness/environment-daemon/v1 \
  --registration-token-file "$HOME/.config/kk-studio/daemon.token"
```

Windows 使用机器启动入口保留 Unicode：

```powershell
$daemonArgs = @("--gateway-uri", "wss://studio.example.com/api/harness/environment-daemon/v1",
    "--registration-token-file", "$env:LOCALAPPDATA\kk-studio\config\daemon.token")
$encoded = @($daemonArgs | ForEach-Object { [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($_)) })
Push-Location "$env:LOCALAPPDATA\kk-studio\daemon"
try { & "$env:JAVA_HOME_21\bin\java.exe" -jar kk-studio-daemon.jar --base64-args @encoded }
finally { Pop-Location }
```

以上假定 `JAVA_HOME_21` 已设置；否则替换为实际 JDK 21 路径。Daemon 自身 CLI 与安装器不同：只接收 `--registration-token-file`，不接受 token 文本；`--version` 是单独的信息命令，不是 release pin。自身还接受 `--heartbeat`（默认 `PT15S`）、`--reconnect-initial`（`PT1S`）与 `--reconnect-max`（`PT30S`），使用 ISO-8601 duration；心跳与最大退避须正数，初始退避非负且不超过最大值。工具执行超时由调用方提供，不是 Daemon 安装配置。

上级：[系统设计](../system-design.md)。协议与执行实现：[Harness Daemon](../modules/harness-daemon.md)、[Harness Environment](../modules/harness-environment.md)、[Harness Environment Server](../modules/harness-environment-server.md)。容器内运行见[部署与运行](deployment.md)，开发验证见[开发与测试](development-and-testing.md)。
