# Environment Daemon 安装与运行

Environment Daemon 把 Studio 的文件、命令、检索与 LSP 工具调用执行在你的主机上。每个
OS 用户只运行**一个受管 Daemon**，固定根目录 `~/.kk-studio`（Windows 为
`%USERPROFILE%\.kk-studio`）；单个 Daemon 实例同一时刻只绑定一个 Environment，由
`daemon.token` 里的注册凭据决定。Daemon 只做本机安装与运行，**不执行任何远程主机操作**。

安装设置由 Studio 的 Environment 保存：在 Web 中填写并保存配置，复制生成的安装命令，再到
目标主机执行；执行前配置只是数据库记录，不代表已经部署。默认安装官方 GitHub Release，
不需要克隆仓库，也不需要 Git 或 Maven。

## 前置条件与平台

主机需要 **JDK 21 与 Bash**。安装器与 Daemon 都不自动安装语言服务器、JDK 或包管理器；
LSP client 已内嵌在 Daemon JAR 中。Studio 没有内置登录鉴权，入口须配置外部认证，只有可信的
用户与 Agent 才应取得 Environment 的宿主权限。

| 平台 | 额外条件 | 常驻方式 |
| --- | --- | --- |
| Linux | 可用的 `systemctl --user`，curl 与 `sha256sum` 或 `shasum` | 用户服务 `kk-studio-daemon.service` |
| macOS | 已登录桌面，可用的 `gui/$(id -u)` 域；curl 与 `sha256sum` 或 `shasum` | LaunchAgent `fun.fengwk.kkstudio.environment-daemon` |
| Windows 10/11 | PowerShell 5.1 或 7、ScheduledTasks 模块、可用的 Bash（配置绝对可执行路径或服务 PATH 上可解析） | 当前用户的 AtLogOn 计划任务，仅在该用户交互登录期间运行 |

Linux 注销后要持续运行，由管理员开启 `loginctl enable-linger <用户名>`，安装器不代为开启。
macOS 纯 SSH 会话如果没有图形登录域，不能安装 LaunchAgent。Windows 的 Bash 可来自 Git for
Windows 或兼容实现，这是命令工具的解释器要求，不需要用 Git 克隆源码；计划任务是普通
AtLogOn 任务，不是 Windows Service，重启设置只是尽力而为，Task Scheduler 也不捕获
stdout/stderr。

## 从 Web 保存并复制安装命令

1. 在 [Environment](http://localhost:8080/environments) 页面创建目标 Environment（或使用已有
   Environment）。
2. 在 Environment 卡片点击**“安装 / 覆盖”**，打开安装弹窗。
3. 填写并保存配置：
   - **操作系统**：默认按当前浏览器平台预选，可改。
   - **Studio 地址（仅 origin）**：默认当前 Studio origin，例如 `https://studio.example.com`；
     不要带路径、查询或片段。
   - 可选的 **Java home (JDK 21)**、**Bash 可执行文件**、**备注**。空字段显示路径或备注示例，示例不是已填入的值。
   - 可选的 **启用 LSP servers** 与 JSON `servers`。不需要选择本地文件路径，也没有 LSP 上传。
     启用后空编辑器显示灰色 JSON 示例，示例不会被保存。
4. 点击**“保存并复制安装命令”**。Studio 先校验并保存配置，再签发 **5 分钟有效**的安装 code，
   将一行下载执行命令写入剪贴板。请在有效期内到目标主机执行。

命令从当前 Studio 地址下载脚本，不在终端粘贴配置或长期注册令牌。Linux/macOS 命令形如：

```bash
(set -o pipefail; curl -fsSL 'https://studio.example.com/api/harness/environments/<id>/install?code=<code>' | bash)
```

Windows 命令先完整下载脚本，再在 PowerShell 的局部作用域执行；下载或安装失败都会返回失败。
下载执行前会关闭调试回显，避免脚本中的长期凭据进入终端输出。
code 在有效期内可重复使用；过期后重新复制命令，已经下载的脚本不会因 code 到期而中断。
安装成功会提示服务已启动，再回 Studio 确认 `READY`。

保存失败时不会签发 code 或复制命令。保存成功但签发或复制失败时，提示会明确说明
**配置已保存但命令未复制，尚未部署**。CAS 版本冲突时只重基版本，保留你正在编辑的草稿。

> 安装命令中的 code 可下载含凭据的脚本，请勿分享。**保存不代表已应用**；覆盖只影响当前
> OS 用户唯一的受管 Daemon，重启后它连接这次选择的 Environment，并中断原绑定 Environment
> 的在途调用（结果不确定、已发生的命令副作用不回滚），但保留运行数据。

Studio 返回的脚本在私有临时目录里以 0600 写入 `daemon.json` 与 `daemon.token`
（两个文件必须是同目录的兄弟文件，且分别命名为 `daemon.json` 与 `daemon.token`），下载
`install.sh` / `install.ps1` 到同一目录，再执行下面的稳定安装契约。token 只作为文件内容传递给
安装器，不进入子进程 argv、环境变量或日志。

Environment 卡片上的“卸载”按钮复制一行卸载命令，从 Studio 下载对应 OS 的脚本；
不读取或保存环境配置，也不需要 code 或 token。

如果外部入口要求浏览器登录，需允许目标主机访问这两个 GET 脚本下载路径：

- `/api/harness/environments/{environmentId}/install`
- `/api/harness/environments/uninstall/{operatingSystem}`

安装下载由 code 校验授权；签发 code 的 POST 与其它管理 API 仍须受入口访问控制保护。

## 安装器契约

稳定命令契约（Advanced/排错时可直接调用）：

```text
# Unix
bash install.sh install --config-file <absolute daemon.json> --token-file <absolute daemon.token> [--java-home <absolute JDK home>]
bash install.sh status | uninstall | help

# Windows (PS 5.1 与 PS7 pwsh 参数一致)
powershell -NoProfile -ExecutionPolicy Bypass -File install.ps1 install -ConfigFile <absolute daemon.json> -TokenFile <absolute daemon.token> [-JavaHome <absolute JDK21>]
powershell -NoProfile -ExecutionPolicy Bypass -File install.ps1 status|uninstall|help
```

只有 `install` / `status` / `uninstall` / `help` 四个动作，**没有交互提示**，未知或重复选项
直接失败。Unix 的 `--config-file` / `--token-file` 必须指向现存、可读、属主私有的普通文件，
不是符号链接，内容非空且有界；`--java-home` 指定 JDK 21 home。Windows 的 `-ConfigFile` /
`-TokenFile` 必须是同目录的 `daemon.json` / `daemon.token`，由当前 SID 拥有、禁用 ACL 继承、
不含其它 SID 的 Allow ACE，且不是 reparse point。安装器只校验文件元数据，**不读取 token 内容**。

Java home 发现顺序为 `--java-home` / `-JavaHome` → `JAVA_HOME_21` → `JAVA_HOME` → PATH；
所选 home 必须是 JDK 21，Unix 只要求可执行的 `bin/java`（安装器与程序都不编译），Windows
还要求 `bin/javac`。`DAEMON_VERIFY_TIMEOUT_SECONDS`
（默认 30）与 `DAEMON_VERIFY_STABLE_SECONDS`（默认 3）可覆盖启动与稳定性验证窗口，均须十进制
非负整数；0 表示立即检查，不等待对应窗口。

### 执行流程与预检

安装器按固定顺序工作，全部语义校验都发生在目标写入之前：

1. 检查系统服务管理器是否可用、目标服务是否属于本安装器（见[管理身份](#管理身份与冲突处理)），
   并核验输入文件是私有、同目录、非符号链接且有界的 `daemon.json` / `daemon.token`。同名但非
   受管定义会在下载或写入前被拒绝。
2. 解析 latest 官方 release tag，下载对应的 `kk-studio-daemon-<tag>.jar` 与 `.jar.sha256`，
   核对摘要，并确认 JAR 版本与解析出的 tag 一致。
3. 把暂存配置与 token 复制为下载目录内的 0600 快照，后续只针对该快照做预检，避免校验与发布
   之间被替换。
4. 用下载的新 JAR 对快照执行 `--check-config` 预检：它读取配置与 token 文件，用共享 codec 校验
   配置结构与取值，在本机只读文件系统中把配置的（缺省为 `bash`）Bash 解析为实际可执行路径，并
   校验内联 LSP 的结构。它**不执行** Bash 或任何其它程序，不建数据目录、不加锁、不连接、不启动
   LSP、不做服务变更；Bash 是否真能运行、语言服务器是否完整可用都不在这里判断。失败只输出固定
   safe marker（字段路径与规则，不含原始取值），旧版 JAR 不认识该新参数时给出可操作错误。
5. 预检全部通过后，先把现有的受管 JAR、`daemon.json`、`daemon.token` 复制到 `backups/` 下的
   唯一个人备份目录，再发布新文件并重启服务。

预检失败时现有受管安装保持不变。**install 会整体替换程序、配置与 token，并重启服务**，保留数据
目录与日志。发布之后的失败返回非零，**不自动回滚**：安装器会输出备份路径和当前平台的诊断命令，
你可以检查后用备份手动恢复，或修正安装设置后重新复制命令重试。重启会中断在途工具调用，进程内 invocation
journal 不跨重启保留，已经发生的命令副作用不回滚。

## 固定布局与文件

| 内容 | Linux / macOS | Windows |
| --- | --- | --- |
| 根目录 | `~/.kk-studio`（0700） | `%USERPROFILE%\.kk-studio`（owner-only ACL） |
| 配置 | `~/.kk-studio/daemon.json`（0600） | `%USERPROFILE%\.kk-studio\daemon.json` |
| token | `~/.kk-studio/daemon.token`（0600，与配置同目录） | `%USERPROFILE%\.kk-studio\daemon.token` |
| 程序 | `~/.kk-studio/lib/kk-studio-daemon.jar` | `%USERPROFILE%\.kk-studio\lib\kk-studio-daemon.jar` |
| 日志 | `~/.kk-studio/logs/`（macOS） | Task Scheduler 不捕获 stdout/stderr |
| 备份 | `~/.kk-studio/backups/`（仅安装时创建） | `%USERPROFILE%\.kk-studio\backups\` |
| 数据 | 配置文件的父目录 | 配置文件的父目录 |

`daemon.json` 就是 Web 中 `installConfig.daemon` 对象本身，只含 `studioUrl`、`note`、
`bashExecutable` 与可选的 `lsp`，**不含** `operatingSystem`、`javaHome`，也不含 token、gateway
或数据目录字段。`operatingSystem` 与 `javaHome` 只用于安装与生成命令，不进入运行时配置文件。
token 是独立兄弟文件 `daemon.token`。Daemon 不做任何独立配置解析：`daemon.json` 是唯一配置
来源，没有第二个 LSP 文件，也没有环境变量配置入口。

运行数据根就是配置文件的父目录。Unix 全新安装时目录为 0700、配置与 token 为 0600。Unix 安装器只设置并
校验**自身**资源与凭据：`~/.kk-studio` 及其 `lib/`、`logs/`、`backups/`，以及私有暂存的输入
目录；不安全的受管路径会被拒绝且不自动放宽权限。宿主共享目录（`HOME`、`~/.config`、
`~/Library` 及其上级）是用户自己的信任边界，安装器不检查也不修改它们，不因 group 可写或为符号
链接而拒绝安装，也不承诺抵御能修改这些目录的本机用户。token 是磁盘明文凭证，隐藏程度取决于
文件权限，不等于加密存储。

## 安装配置的字段与校验

Web 保存的 `EnvironmentInstallConfigDTO`：

```json
{
  "operatingSystem": "linux",
  "javaHome": null,
  "daemon": {
    "studioUrl": "https://studio.example.com",
    "note": null,
    "bashExecutable": null,
    "lsp": {
      "servers": {
        "jdtls": {
          "command": ["~/.local/share/nvim/mason/bin/jdtls"],
          "extensions": [".java"],
          "rootMarkers": ["pom.xml"],
          "firstMatchMarkers": [".git"]
        }
      }
    }
  }
}
```

服务端与 Daemon 共用同一个严格 codec，未知字段、错误类型与越界值都会被拒绝，错误只报告字段路径
与规则、不回显输入值：

- `operatingSystem` 必填，取值为 `linux` / `macos` / `windows`。
- `javaHome` 可选，必须是 OS 对应的绝对路径（Windows 为盘符或 UNC），不接受 `~`、`${VAR}`
  或 `%VAR%` 占位符；服务端不做可执行探测。
- `studioUrl` 必填，只能是 HTTP(S) origin：允许一个结尾 `/`（保存时去掉），拒绝 userinfo、
  path、query、fragment 与非法端口。Daemon 由它派生 WebSocket 入口
  `ws(s)://<authority>/api/harness/environment-daemon/v1`。
- `note` 可选，trim 后单行、最长 512 字符、不含控制字符；空值保存为省略/null。
- `bashExecutable` 可选，非空白且不含控制字符；缺省为 `bash`。
- `lsp` 可选；一旦启用就必须声明至少一个 server，空表非法。server id 匹配
  `[A-Za-z0-9_.-]+`，`command` 是非空字符串数组，`extensions` 必须带前导点且不含路径分隔符，
  `rootMarkers` / `firstMatchMarkers` 是项目相对路径、不得为绝对路径或含 `..`。**未声明的字段
  一律拒绝**，包括 `requestTimeoutMs`。

`PUT /api/harness/environments/{id}/install-config` 以 `{expectedVersion, installConfig}` 保存，
`installConfig` 不可为 null；版本不匹配返回冲突，配置未变则幂等不写行。响应 Card 不包含 token。
安装 UI 不读取长期 token，只请求安装 code：

| 入口 | 契约 |
| --- | --- |
| `POST /api/harness/environments/{id}/install-code` | `{expectedVersion}` 校验已保存版本，返回 `{code, expiresAt}`；不改变环境版本或配置 |
| `GET /api/harness/environments/{id}/install?code=...` | 校验 code 后，按当前保存的 OS、配置和 token 返回 UTF-8 纯文本脚本 |
| `GET /api/harness/environments/uninstall/{operatingSystem}` | 返回对应 OS 的卸载脚本，不含环境配置或凭据 |

签发与脚本响应均禁止缓存。code 以当前 registration token 对安装用途、环境 ID 和到期时间做
HMAC-SHA256 签名，无独立存储；到期、篡改、错环境或 token 轮换后，下载被拒绝。

### 配置同步

“设置 → 同步”导出的 `environments` 条目把 `installConfig` 与 `name`、`registrationToken`
并列嵌套：

```yaml
environments:
  - name: env
    registrationToken: token-env
    installConfig:
      operatingSystem: linux
      javaHome: /opt/jdk21
      daemon:
        studioUrl: https://studio.example.com
        bashExecutable: /bin/bash
        lsp:
          servers:
            jdtls:
              command: ["~/.local/share/nvim/mason/bin/jdtls"]
              extensions: [".java"]
              rootMarkers: ["pom.xml"]
              firstMatchMarkers: [".git"]
```

条目没有 `installConfig` 表示该 Environment 未保存安装设置。导入支持新增、覆盖、清空（文件
省略该键即清空现有设置）与 token+配置的原子更新；预检查会用同一套 codec 深度校验，已知非法值
不会被条目级跳过。凭据导出策略不变，YAML 仍包含注册令牌，请保存在私密位置。

## 可选 LSP

**LSP client 已在 Daemon JAR 中**，不需要额外安装客户端，Daemon 也不负责下载或升级语言服务器。
服务器由你在 Web 的安装弹窗中以内联 JSON 声明；不启用时省略 `lsp`，LSP 查询返回明确的不可用
错误，但文件、命令、检索仍可使用。没有独立的 `lsp.json` 或文件路径配置项。

例如已安装 Node/npm 的开发主机可先自行安装 TypeScript 服务器：

```bash
npm install -g typescript typescript-language-server
```

对应 `servers`：

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

Neovim 用户常经 Mason 管理语言服务器，可直接引用其安装路径；`command` 首元素支持 `~` / HOME
前缀展开，Daemon 仍以服务进程的 PATH 解析其余可执行程序：

```json
{
  "servers": {
    "jdtls": {
      "command": ["~/.local/share/nvim/mason/bin/jdtls"],
      "extensions": [".java"],
      "rootMarkers": ["pom.xml"],
      "firstMatchMarkers": [".git"]
    }
  }
}
```

`command` 是程序与参数数组，不是 shell 字符串。它必须能在**服务进程**中解析，服务 PATH 可能
不同于交互终端，推荐把首元素写成绝对路径。Windows 若使用 npm 的 `.cmd` 入口，最稳妥的是直接
配置 Node 绝对路径和已安装服务器的 JS 入口：

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

`extensions` 必须带前导点，匹配不区分大小写，同一后缀由先声明的服务器负责。项目根从目标文件
向上查找：优先最靠上的 `rootMarkers` 命中，其次最近的 `firstMatchMarkers`，否则用文件父目录；
不越过最近的 `.git` 目录或 gitfile。服务器在该项目根启动；文件 `path` 必须为绝对路径，不用 `workdir` 解析。

程序基名识别为 jdtls 后才提供 `java/classFileContents`，`jdt://` 反编译不能在其它服务器上使用。
客户端连接按项目复用，调用超时或取消只取消请求并发送 `$/cancelRequest`，不杀掉其它调用共享的
服务器；初始化失败不重试，进程范围与清理行为见[内置 LSP 验证](builtin-lsp-tests.md)。

## 本机代理

Daemon 不接收 Backend 的代理设置。启动时分别读取 `http_proxy`、`https_proxy`，
兼容大写变量，小写存在时优先；空值明确直连，未设置的协议才委托 JDK 的操作系统代理。
`no_proxy`/`NO_PROXY` 的逗号分隔绕过规则同样作用于操作系统回退结果，支持主机、域名后缀、
IPv4/IPv6 字面量、`*` 与 IPv4/IPv6 CIDR（如 `192.168.0.0/16`、`::1/128`）；CIDR 只按目标 URL
中的数值 IP 逐位匹配，不会为目标域名解析 DNS，因此按域名访问的内网服务仍应写域名规则。
环境代理地址只接受无认证的 `http://host:port`；HTTPS/WSS 通过 CONNECT，
不支持 SOCKS、代理认证或 TLS 到代理，非法配置在启动时拒绝。

macOS 的回退来源是网络设置；Linux 的桌面代理取决于宿主支持，服务器/容器通常应显式
传入环境变量。Windows 读取当前运行用户的系统代理（含系统自动代理能力），不是
`netsh winhttp` 的独立服务配置，也不会继承另一个登录用户的代理。未显式指定时
Daemon 开启 `java.net.useSystemProxies`；显式 JVM 代理属性仍参与 JDK 回退。

环境变量必须进入 Daemon 进程：终端 `export` 不会改变已经运行的服务。安装器不保存
代理配置文件；Linux systemd user service、macOS LaunchAgent 或 Windows 计划任务
需要由对应的用户环境/服务管理器提供变量，修改后重新启动 Daemon。将 studio 和
对象存储的内网地址加入绕过规则，避免控制连接与预签名上传误走外部代理。

## 验证与 READY

安装器默认最多等待 30 秒，再检查运行状态持续保持 3 秒。它验证的是服务或任务存活，**不是
gateway 注册成功**。Linux/macOS 用 `status` 或系统命令检查主机侧状态：

| 平台 | 主机侧检查 | 日志 |
| --- | --- | --- |
| Linux | `systemctl --user is-active kk-studio-daemon.service` | `journalctl --user -u kk-studio-daemon.service -n 50 --no-pager` |
| macOS | `launchctl print gui/$(id -u)/fun.fengwk.kkstudio.environment-daemon` | `~/.kk-studio/logs/environment-daemon.stdout.log` 与 `.stderr.log` |
| Windows | `install.ps1 status` 或 Task Scheduler | Task Scheduler **不捕获 stdout/stderr** |

`status` 只读，不启动服务。退出码 `0`：Linux active、macOS loaded、Windows Running；`1`：未安装
或不是受管安装；`3`：已有受管安装但未达到上述状态（macOS loaded 甚至不保证进程仍存活）。

主机侧的存活与预检都只证明本地条件；**配置是否真正生效、注册是否成功，最后必须在 Studio 的
Environment 页面确认 `READY`**。只有 READY 才表示 Daemon 已接受当前配置并注册成功；之后可在
Chat 分支中选择该 Environment，并给 Agent 配置所需的 Environment Tools。READY 后 Platform 会
异步同步已发布的 Skill Package；同步失败不撤销 READY，其它宿主能力仍可用，技能引用可退回
Platform Skill URI。

## 管理身份与冲突处理

安装器只管理当前用户且带所有权标记的服务定义：

| 平台 | 所有权检查 |
| --- | --- |
| Linux | unit 首行必须是 `# Managed by scripts/daemon/install.sh` |
| macOS | plist 第二行必须是 `<!-- Managed by scripts/daemon/install.sh -->` |
| Windows | 任务 Description 必须精确等于 `Managed by scripts/daemon/install.ps1; schema=1; ownerSid=<当前 SID>` |

检查顺序在下载与写入之前。同名服务存在但标记不匹配、定义是符号链接/非普通文件、属主不是当前
用户时，安装或卸载会拒绝并报告原因、服务定义路径或任务名，以及一条检查命令。
确认归属后再由对应所有者处理冲突，先私密备份，保留未知程序与数据；不要直接删除系统文件。
安装器**不会删除未知 JAR 或数据，也不会
伪造所有权标记**；标记只用于识别管理归属，不能通过手工补标记授权接管。

同一 OS 用户只允许一个受管 Daemon；它继承启动用户的文件与命令权限，没有文件系统沙箱。
只把 Environment 开放给可信的 Studio 用户和 Agent；`process.exec` 的 `workdir` 是调用目录，不是权限边界，
HOME 也不是默认工作目录。

## 改配置、轮换 token 与更新

改配置与轮换 token 仍走 `install`：在 Web 弹窗修改后重新“保存并复制安装命令”，在目标主机执行
同一条 `install`，它会**整体替换程序、配置与 token 后重启**，保留数据目录与日志。

- 改配置：在 Web 弹窗修改后重新“保存并复制安装命令”，在目标主机执行同一条 `install`。
- 轮换 token：环境 ID 不变，但旧安装 code 立即失效；在 Web 重新复制并执行安装命令，
  或安全更新 `daemon.token` 文件后重启受管服务。Daemon 每次 HELLO 前读取 token 文件；已被拒绝
  而退出的进程需要重启。

### 受管在线更新

已受管的安装还可以从 Web 直接触发一次**二进制在线更新**，无需重新执行安装命令：

| 入口 | 契约 |
| --- | --- |
| `POST /api/harness/environments/{id}/update` | 发起一次受管更新；目标版本由运行中 Platform 的打包版本决定。目标不可用、已有更新在途或存在在途调用/工作时以冲突拒绝 |
| `GET /api/harness/environments/{id}/update` | 只读最近一次更新投影（`operationId`、`targetVersion`、`phase`、可选 `error`、时间戳）；从未更新时为 null |

更新目标不是“最新版”也不是浏览者提供的 URL，而是 **运行中 Platform 版本对应的官方 GitHub Release**：
必须是非草稿、tag 匹配、五件套资产齐全（jar、其 `.sha256`、确定性元数据、`LICENSE`、
`THIRD_PARTY_NOTICES`）的完整发布，且制品地址就是官方地址。Daemon 收到命令后再次校验 URL 形状、
SHA256、官方校验文件与 JAR manifest 版本，并用现有 `daemon.json` 预检，任何一步失败都不改动旧二进制。

- 一个 Environment 同一时刻至多一次更新：内存准入与持久 operation 行双重保证；更新期间普通
  capability 调用在起点被 busy 拒绝，连接仍保持认证（catalog 版本不一致时只禁止普通工具，不影响
  版本查询与更新）。
- 只有受管安装（存在 `lib/kk-studio-daemon.jar` 与同目录 `daemon.json`）接受更新；更新只替换
  `lib` 下的二进制，`daemon.json`、`daemon.token`、技能与数据都不改动，也不隐式重跑已保存的安装配置。
- 下载/预检/备份后，Daemon 把制品交给一个 **OS 级独立、短生命周期的更新器**（Linux `systemd-run`
  瞬时单元、macOS `launchctl submit` 瞬时 job、Windows 一次性计划任务）完成替换与重启。
  更新器不常驻、不提权、不自动回滚；受管服务定义缺失或归属标记不匹配时 fail closed，不改动二进制。
- **最终成功只由更新后 Daemon 以目标版本重新 READY 确认**；Daemon 的阶段回执只表达准备进度，
  连接断开派生为 `UNKNOWN`（等待重连），不等于失败。持久阶段为 `PENDING / RUNNING / PREPARED /
  SUCCEEDED / FAILED`。
- 尚未具备该协议的旧 Daemon 不能远程更新，需要先手工执行一次安装命令完成引导。

重启会中断在途工具调用，进程内 invocation journal 不跨重启保留，已经发生的命令副作用不回滚。
更新后重新确认 Studio `READY`。

## 卸载与数据保留

`uninstall` 停止服务、删除受管服务定义与 `lib/kk-studio-daemon.jar`，**保留 `daemon.json`、
`daemon.token`、整个数据目录、日志与 `backups/`**。它是幂等的，服务管理器错误不会被吞掉。
卸载不会删除任何 Studio Environment 记录，也不清理 token、数据与日志；彻底删除前先评估历史
引用，并按需手动清理。

数据目录持有 `daemon.lock`，同一目录只允许一个 Daemon。`tmp/workspaces/<uuid>` 保存命令与检索的大文本
全文（`*.part` 中转后原子发布 `*.log`），模型历史可能仍引用其绝对路径。定时清扫（默认 30 分钟一次）
只回收超过当前保留期（默认 3 天）且未被使用的 workspace，历史引用的全文因此在保留期后可能被自动回收。
`skills` 保存已安装包，`skill-work` 保存 Git 缓存及安装中间产物；安装器负责恢复或清理 staging/backup，
并保留已发布的全文与技能。

## 参数与排错

完整用法以 [Unix 脚本](../../scripts/daemon/install.sh) 的 `help` 和
[Windows 脚本](../../scripts/daemon/install.ps1) 的 `help` 为准：

| 动作 / 参数 | Unix | Windows | 说明 |
| --- | --- | --- | --- |
| install | `install` | `install` | 必填 `--config-file` / `-ConfigFile` 与 `--token-file` / `-TokenFile` |
| 可选 Java | `--java-home` | `-JavaHome` | JDK 21 home 的绝对路径；缺省按 `JAVA_HOME_21` → `JAVA_HOME` → PATH 发现 |
| status | `status` | `status` | 只读；退出码 0/1/3 见上 |
| uninstall | `uninstall` | `uninstall` | 保留配置、token、数据、日志与备份 |
| help | `help` | `help` | 输出用法 |

`status` 与 `uninstall` 不接收安装参数。配置与 token 必须是同目录、名为 `daemon.json` /
`daemon.token` 的兄弟文件。

| 现象 | 处理 |
| --- | --- |
| 下载脚本返回 HTTP 400 | 安装 code 已过期、被修改或因 token 轮换失效；回 Studio 重新复制安装命令 |
| Java 版本或 home 预检失败 | 指定真正的 JDK 21 home；Unix 检查可执行的 `bin/java`，Windows 还要有 `bin/javac`；PATH 中的 java shim 未必指向正确 home |
| GitHub 下载或 SHA 校验失败 | 检查网络、代理与 release 是否含成对资产；不要跳过校验，旧受管安装保持不动 |
| `unmanaged unit` / `unmanaged plist` / `unmanaged Scheduled Task` | 按[管理身份与冲突处理](#管理身份与冲突处理)确认归属，检查、导出、停止并移开非受管定义；不要伪造所有权标记 |
| 配置或 token 文件权限失败 | Unix 确认属主与无 group/other 写权限；Windows 检查当前 SID、继承与其它 Allow ACE。安装器拒绝不安全现存文件，不自动放宽规则 |
| 输入文件名或目录不符 | 必须使用 Web 生成的暂存文件，且是私有同目录的 `daemon.json` / `daemon.token` |
| `environment registration is rejected` | 从目标 Environment 重新轮换/读取 token，重新生成命令执行 `install` 或安全更新 token 文件后重启 |
| WebSocket close code 1010 | 所有终止 WebSocket 的代理层启用 `permessage-deflate` |
| 服务存活但未 READY | 核对 studioUrl、token、网络与压缩；Linux 看 journal，macOS 看日志，Windows 用前台复现 |
| LSP 显示未安装或查询失败 | 检查弹窗 JSON、服务进程可见的程序路径、服务器依赖与项目根；read header 的 supported 只证明配置/程序发现，不证明服务器能初始化 |

### 前台诊断

先停止受管服务，避免数据锁冲突，再用同一份 `daemon.json` 启动 JAR，输出直接写控制台；
`Ctrl-C` 结束，不提供常驻守护。`daemon.token` 必须与配置同目录：

```bash
"$JAVA_HOME_21/bin/java" -jar "$HOME/.kk-studio/lib/kk-studio-daemon.jar" \
  --config "$HOME/.kk-studio/daemon.json"
```

Windows 路径可能含 Unicode，使用机器启动入口保留编码；它把完整的 `--config <路径>` 参数对
逐个编码为 UTF-8 Base64：

```powershell
$encoded = @("--config", "$env:USERPROFILE\.kk-studio\daemon.json") |
    ForEach-Object { [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($_)) }
& "$env:JAVA_HOME_21\bin\java.exe" -jar "$env:USERPROFILE\.kk-studio\lib\kk-studio-daemon.jar" --base64-args @encoded
```

Daemon 自身 CLI 只有 `--config <绝对路径>`（及 `--check-config <绝对路径>` 预检、`--help`/`-h`、
`--version` 与机器入口 `--base64-args`）。心跳、重连与最大退避固定为 `PT15S` / `PT1S` / `PT30S`，
没有公开开关；工具执行超时由调用方提供，不是 Daemon 安装配置。

上级：[系统设计](../system-design.md)。协议与执行实现：[Harness Daemon](../modules/harness-daemon.md)、
[Harness Environment](../modules/harness-environment.md)、
[Harness Environment Server](../modules/harness-environment-server.md)。容器内运行见
[部署与运行](deployment.md)，开发验证见[开发与测试](development-and-testing.md)。
