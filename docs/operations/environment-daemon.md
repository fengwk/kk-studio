# Environment Daemon 安装与运行

Environment Daemon 把 Studio 的文件、命令、检索与 LSP 工具调用执行在你的主机上。**一张
Environment 卡片就是一套独立安装**：安装目录、注册凭据、托管进程、操作锁与 OS 服务都由卡片的
canonical UUID 限定，同一用户的多个卡片互不共享、互不影响。Daemon 只做本机安装与运行，
**不执行任何远程主机操作**。

卡片配置由 Studio 保存并热下发：Web 中保存的可编辑配置不需要重装或重启 Daemon 就会生效。安装命令只
负责在该主机上建立或覆盖这套受管安装；命令执行前配置只是数据库记录，不代表已经部署。默认安装官方
GitHub Release 发行归档，归档内自带冻结的 Node 运行时，**不需要宿主安装 Node 或 JDK**，不需要克隆仓库，
也不需要 Git 或编译工具。

## 前置条件与平台

主机需要 **Bash** 与对应平台的服务管理工具。安装器与 Daemon 都不自动安装语言服务器或包管理器；
LSP client 已内嵌在 Daemon 发行中。Studio 没有内置登录鉴权，入口须配置外部认证，只有可信的
用户与 Agent 才应取得 Environment 的宿主权限。

| 平台 | 额外条件 | 每张卡片的常驻方式 |
| --- | --- | --- |
| Linux | 可用的 `systemctl --user`，curl 与 `sha256sum` 或 `shasum` | 用户服务 `kk-studio-daemon-<UUID>.service` |
| macOS | 已登录桌面，可用的 `gui/$(id -u)` 域；curl 与 `sha256sum` 或 `shasum` | LaunchAgent `fun.fengwk.kkstudio.environment-daemon.<UUID>` |
| Windows 10/11 | PowerShell 5.1 或 7、ScheduledTasks 模块、可用的 Bash（配置绝对可执行路径或服务 PATH 上可解析） | 当前用户的 AtLogOn 计划任务 `kk-studio-daemon-<SID>-<UUID>`，仅在该用户交互登录期间运行 |

Linux 注销后要持续运行，由管理员开启 `loginctl enable-linger <用户名>`，安装器不代为开启。
macOS 纯 SSH 会话如果没有图形登录域，不能安装 LaunchAgent。Windows 的 Bash 可来自 Git for
Windows 或兼容实现，这是命令工具的解释器要求，不需要用 Git 克隆源码；计划任务是普通
AtLogOn 任务，不是 Windows Service，重启设置只是尽力而为，Task Scheduler 也不捕获
stdout/stderr。

## 在 Web 中配置卡片并生成安装命令

1. 在 [Environment](http://localhost:8080/environments) 页面创建卡片并填写配置：
   - **名称**：创建后不可变。
   - **备注**：卡片展示与提示词使用，保存后立即生效，不由 Daemon 消费。
   - **Studio 地址（仅 origin）**：例如 `https://studio.example.com`，不要带路径、查询或片段。
   - **Bash 可执行文件**：可留空，留空表示使用宿主 PATH 上的 `bash`。
   - **LSP servers**：可留空；一旦启用须声明至少一个 server（JSON `servers`），不需要选择本地
     文件路径，也没有 LSP 上传。
   - **代理地址**：可留空；留空表示该 Daemon 的自身请求明确直连。
2. 保存卡片。保存成功即写入 Studio 的完整配置快照；已安装且在线连接的 Daemon 会**全量热更新**，
   无需重装或重启。安装或覆盖安装使用当前快照，不需要另存“安装设置”。
3. 在卡片上点击**“安装 / 覆盖”**，打开安装弹窗。弹窗只选择**目标操作系统**（默认按当前浏览器平台
   预选，可改），不再维护第二套安装配置草稿。
4. 点击生成命令。Studio 先签发 **5 分钟有效**的一次性安装 code，再把一行下载执行命令写入剪贴板；
   请在有效期内到目标主机执行。

命令从当前 Studio 地址下载脚本，不在终端粘贴配置或长期注册令牌。Linux/macOS 形如：

```bash
(set -o pipefail; curl -fsSL 'https://studio.example.com/api/platform/environments/<id>/install-script?code=<code>&operatingSystem=linux' | bash)
```

Windows 先完整下载脚本，再在 PowerShell 的局部作用域执行，下载或安装失败都会返回失败；下载执行前
会关闭调试回显。两条命令都只携带卡片 UUID、一次性 code 与目标 OS，完整配置和本次新令牌由服务端
在兑换脚本时写入。

Studio 只保存注册令牌的摘要；兑换 code 会为本次安装签发新令牌并写进脚本，因此 code 只能使用一次：
再次安装请重新复制命令。新令牌生效后，用旧令牌连接的 Daemon 下次握手会被拒绝；已经下载的脚本不会因
code 到期而中断。安装脚本把 `daemon.json` 与 `daemon.token` 写在私有系统暂存目录，下载该版本 tag 的
官方安装器并执行安装；暂存目录在命令退出时删除。

配置弹窗与安装弹窗相互独立：保存配置不签发 code，也不复制命令。保存发生版本冲突时提示刷新卡片
版本后重试，保留当前草稿。安装弹窗签发 code 失败时刷新卡片版本后重新生成；复制失败时可重新生成命令，
已保存的配置不受影响。

> 安装命令中的 code 可下载含凭据的脚本，请勿分享。**保存不等于已应用**；安装/覆盖只影响该 UUID 的
> 受管安装，同用户的其它卡片继续运行。重装会以本次脚本中的配置与令牌为引导缓存，随后仍由 Studio
> 对账最新配置。

Environment 卡片的“卸载”按钮生成一行按卡片定位的卸载命令，不读取或保存配置，也不需要 code 或
token。

如果外部入口要求浏览器登录，需允许目标主机访问这两个 GET 脚本下载路径：

- `/api/platform/environments/{environmentId}/install-script`
- `/api/platform/environments/{environmentId}/uninstall-script`

安装下载由 code 校验授权；签发 code 的 POST 与其它管理 API 仍须受入口访问控制保护。

## 安装器契约

稳定命令契约（Advanced/排错时可直接调用）：

```text
# Unix
bash install.sh install --environment-id <UUID> --version <version> \
                        --config-file <absolute daemon.json> --token-file <absolute daemon.token>
bash install.sh update --environment-id <UUID> --version <version>
bash install.sh status --environment-id <UUID>
bash install.sh uninstall --environment-id <UUID>
bash install.sh help

# Windows（PS 5.1 与 PS7 参数一致）
powershell -NoProfile -ExecutionPolicy Bypass -File install.ps1 install -EnvironmentId <UUID> -Version <version> -ConfigFile <absolute daemon.json> -TokenFile <absolute daemon.token>
powershell -NoProfile -ExecutionPolicy Bypass -File install.ps1 update -EnvironmentId <UUID> -Version <version>
powershell -NoProfile -ExecutionPolicy Bypass -File install.ps1 status -EnvironmentId <UUID>
powershell -NoProfile -ExecutionPolicy Bypass -File install.ps1 uninstall -EnvironmentId <UUID>
powershell -NoProfile -ExecutionPolicy Bypass -File install.ps1 help
```

`--version` / `-Version` 必填，值为不带 `v` 前缀的发行版本；安装器**不解析 `latest`**，也不会
下载可变的 main 分支安装器。`install` 是唯一的引导/人工覆盖命令；`update` 只替换已有安装的程序与
服务定义，读取当前 `daemon.json`，**不接受也不写入配置或令牌**。只有
`install` / `update` / `status` / `uninstall` / `help` 五个动作，**没有交互提示**，未知或重复选项
直接失败。

Unix 的 `--config-file` / `--token-file` 必须指向现存、可读、属主私有、不是符号链接且有界的普通
文件，且必须是同目录的兄弟文件 `daemon.json` / `daemon.token`。Windows 的 `-ConfigFile` /
`-TokenFile` 必须由当前 SID 拥有、禁用 ACL 继承、不含其它 SID 的 Allow ACE，且不是 reparse point。
安装器只校验文件元数据并复制 token 内容，自身不解析 token 文本；发行归档的 `--check-config` 预检会读取同目录凭据以确认其存在且可读。

`DAEMON_VERIFY_TIMEOUT_SECONDS`
（默认 30）与 `DAEMON_VERIFY_STABLE_SECONDS`（默认 3）可覆盖启动与稳定性验证窗口，均须十进制
非负整数；0 表示立即检查，不等待对应窗口。

### 执行流程与预检

安装器按固定顺序工作，全部语义校验都发生在目标写入之前：

1. 检查系统服务管理器是否可用、目标服务是否属于当前卡片（见[管理身份](#管理身份与冲突处理)），
   并核验输入文件是私有、同目录、非符号链接且有界的 `daemon.json` / `daemon.token`。同名但非
   受管定义会在下载或写入前被拒绝。
2. 解析本机平台到官方归档命名，在**本操作独占的系统临时目录**下载目标 tag 的发行归档
   `kk-studio-daemon-<tag>-<os>-<arch>.tar.gz` 与 `.tar.gz.sha256`，核对 SHA256 摘要，并验证归档
   结构、平台清单与 `--version` 输出。下载/解包不进入卡片目录，取得操作锁之前不存在可被另一操作
   清理的暂存。
3. 把安装输入复制为暂存目录内的同名 `daemon.json` / `daemon.token`，先用归档内置的冻结运行时
   （`runtime/code-server/lib/node`）检查配置信封：根对象只允许
   `environmentId`、`version`、`daemonConfig`，UUID 必须等于本次 `--environment-id`，`version`
   必须是规范非负十进制字符串。随后执行 `main.js --check-config` 预检，校验配置结构、取值与同目录
   凭据。它**不执行** Bash 或任何其它程序，不建数据目录、不加锁、不连接、不启动 LSP；Bash 与语言
   服务器的真实解析由 Daemon 在应用配置时完成，失败会保持连接并上报字段与规则。
4. 准备独立管理副本（冻结 Node、安装操作锁 helper 及其 native 依赖），由 helper 以内核锁取得
   `install.lock`；该卡片已有安装操作时立即报告 busy，不排队、不猜 PID。
5. 取得锁后清理本卡片 `.work/` 残留，把新程序解包到目标文件系统的 `.work/lib`，保证发布是同
   文件系统 rename。
6. 停止目标服务，并用同一内核锁确认运行进程已经退出；超时或无法确认时失败，不继续替换文件。
7. 停服后用新程序对最终输入复检：`install` 校验本次引导快照，`update` 只校验当前
   `daemon.json`。通过后删除旧 `lib/`、rename 新 `lib/`、更新服务定义；`install` 再原子写入配置与
   令牌，`update` 不写两者。启动服务并验证本机进程存活。
8. 成功结果只在受管暂存清理成功、且固定诊断记录写入成功后报告；清理失败按失败处理。

**安装失败时不备份、不留历史程序目录、不自动回滚。** 发布之前的失败保持现有安装不变；发布之后
的失败可能使服务不可用，由重新执行 `install` 恢复。安装器输出失败阶段、是否已开始替换以及本机诊断
文件位置，不创建 `backups/`、`.bak` 或按操作累积的日志目录。重启会中断在途工具调用，进程内
invocation journal 不跨重启保留，已经发生的命令副作用不回滚。

### Unix 安装进度

终端按实际执行顺序显示七个阶段：**检查环境与输入 → 解析版本 → 下载归档 → 校验解包配置 →
取得操作锁 → 停止发布启动 → 稳定性验证**。失败时显示停在哪一阶段，不会提前报告安装成功。
发行归档下载时，标准错误连接终端则显示 curl 进度条；标准错误被重定向或接入管道时只显示阶段文本，
不输出动态进度条。阶段完成不等于注册成功，最后仍须回 Studio 确认 `READY`。

Linux 启动前清除本卡片受管服务的旧失败计数，避免上一次启动限流阻挡新版本；仍保留 **300 秒内最多
5 次启动**的限制，不放宽限流，也不自动重试安装。

## 固定布局与文件

每张卡片在安装根 `~/.kk-studio/environments/<environmentId>/` 下拥有全部受管资源（Windows 为
`%USERPROFILE%\.kk-studio\environments\<environmentId>\`）：

| 内容 | Linux / macOS | Windows |
| --- | --- | --- |
| 配置 | `daemon.json`（0600） | `daemon.json`（owner-only ACL） |
| token | `daemon.token`（0600，与配置同目录） | `daemon.token`（owner-only ACL） |
| 运行锁 | `daemon.lock`（内核独占建议锁） | `daemon.lock` |
| 安装操作锁 | `install.lock`（内核锁，由独立 helper 持有） | `install.lock` |
| 程序发行目录 | `lib/`（含 `main.js` 与冻结运行时） | `lib\` |
| IDE 数据与恢复 | `ide/` | `ide\` |
| 技能 | `skills/` | `skills\` |
| 操作暂存 | `.work/`（当前锁持有者） | `.work\` |
| 最近一次操作诊断 | `last-operation.json`（≤4 KiB） | `last-operation.json` |
| 日志 | Linux 走 journal；macOS `daemon.stdout.log` / `daemon.stderr.log` | Task Scheduler 不捕获 stdout/stderr |

安装根是代码推导的受管布局，不提供用户自定义目录或服务名。每个卡片目录独立；更新、卸载或修复
卡片 A 不影响卡片 B 的服务、程序与锁。安装器不读取、也不修改宿主的共享目录（`HOME`、
`~/.config`、`~/Library` 及其上级），这些目录属于用户自己的信任边界。

`daemon.json` 是本地配置缓存，结构固定：

```json
{
  "environmentId": "00000000-0000-4000-8000-000000000001",
  "version": "12",
  "daemonConfig": {
    "studioUrl": "https://studio.example.com",
    "bashExecutable": null,
    "lsp": null,
    "proxyUrl": null
  }
}
```

`environmentId` 是卡片 UUID，`version` 是最近一次被接受的卡片配置版本，`daemonConfig` 是完整可编辑
配置。Studio 数据库是该卡片的唯一配置权威；`daemon.json` 只是最近一次被接受的本地缓存，供进程启动与
断线重连使用，不提供本地编辑或独立同步入口。运行中的配置变更始终由控制通道的 `ENVIRONMENT_CONFIG`
完整快照下发，成功后原子覆盖该缓存。token 是独立兄弟文件 `daemon.token`，不进 JSON、命令行值、日志或
错误详情；没有第二个 LSP 文件，也没有环境变量配置入口。运行数据根就是配置文件的父目录；Unix 全新
安装时目录为 0700、配置与 token 为 0600。token 是磁盘明文凭证，隐藏程度取决于文件权限，不等于加密
存储。

## 卡片配置字段与校验

Studio 保存的完整卡片配置：

```json
{
  "name": "archlinux",
  "note": "日常开发环境",
  "daemonConfig": {
    "studioUrl": "https://studio.example.com",
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
    },
    "proxyUrl": "http://127.0.0.1:7890"
  },
  "version": "12"
}
```

服务端、安装脚本、本地 JSON 与控制协议使用同一份严格配置契约，未知字段、错误类型与越界值都会被
拒绝，错误只报告字段路径与规则、不回显输入值：

- `studioUrl` 必填，只能是 HTTP(S) origin：拒绝 userinfo、path、query、fragment 与非法端口。
  Daemon 由它派生统一连接入口 `ws(s)://<authority>/ws/daemon`。单条物理连接通过 HTTP/2 复用
  Protobuf 控制流，IDE HTTP 请求与 WebSocket 隧道（RFC 8441 Extended CONNECT）在同一连接上直通。
- `bashExecutable` 可选；空值保存为 `null`，表示使用宿主 PATH 中的 `bash`。非空值不能是空白或含
  控制字符；Daemon 应用配置时把它解析为实际可执行路径，解析失败上报 `VALIDATION_FAILED`。
- `lsp` 可选；`null` 表示禁用 LSP。一旦启用就必须声明至少一个 server，空表非法。server id 匹配
  `[A-Za-z0-9_.-]+`，`command` 是非空字符串数组，`extensions` 必须带前导点且不含路径分隔符，
  `rootMarkers` / `firstMatchMarkers` 是项目相对路径、不得为绝对路径或含 `..`。**未声明的字段
  一律拒绝**。
- `proxyUrl` 可选；`null` 表示该 Daemon 的自身请求明确直连。非空只接受无认证的
  `http://host:port`，拒绝 userinfo、query、fragment 和非根 path。
- `note` 是卡片备注，最多 512 字符，trim 后单行、不含控制字符；空值保存为 `null`。
- 目标 OS 是生成安装命令的参数，不是卡片运行配置；实际 OS 与架构来自 READY。

安装 UI 不读取长期 token，只请求一次性 code：

| 入口 | 契约 |
| --- | --- |
| `PUT /api/platform/environments/{id}` | `{expectedVersion, note, daemonConfig}` 整体替换可编辑属性；无 `name` 字段。每次显式保存都会推进版本，即使内容相同；版本不匹配返回冲突 |
| `POST /api/platform/environments/{id}/install-codes` | `{expectedVersion}`；返回 `{code, expiresAt}`，不改变环境版本或配置 |
| `GET /api/platform/environments/{id}/install-script?code=...&operatingSystem=...` | 校验并消费 code，轮换 token、推进版本，返回包含卡片 UUID、兑换后版本、完整配置与新 token 的 UTF-8 纯文本脚本 |
| `GET /api/platform/environments/{id}/uninstall-script?operatingSystem=...` | 返回只定位本卡片的卸载脚本，不含配置或凭据 |
| `POST /api/platform/environments/{id}/rotate-token` | `{expectedVersion}`；返回一次新 token，不改变配置 |
| `GET /api/platform/environments/{id}/runtime` | 返回当前 owner 的运行快照；CONNECTING 期间也可读取已上报的配置错误 |
| `GET /api/platform/environments/{id}/events` | 最近 200 条以内的连接与技能同步事件 |

脚本响应均禁止缓存，并阻止浏览器按附件内容嗅探。code 是 256 bit 安全随机值，服务端只保存
SHA-256 摘要与数据库时间下的 5 分钟有效期。每个环境最多保留一张待兑换 code，新签发立即替换旧
code；到期、篡改、错环境、已被兑换，或配置/凭据变化后，下载被拒绝。兑换在同一事务内消费 code、
轮换 token、推进版本并生成脚本，失败全部回滚；临时 code 记录不参与配置导出。

### 配置同步

“设置 → 同步”导出的 `environments` 条目把完整卡片配置与 `name`、可空 `note`、注册令牌摘要并列：

```yaml
environments:
  - name: env
    note: 日常开发环境
    registrationTokenHash: f21d92ece713505af79fce6804dafd72b239be0d023924c022006c892bdf4d42
    daemonConfig:
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

导入按 name 匹配新增或覆盖，保留 UUID，并走与 HTTP 相同的配置写入与版本推进；文件省略 `note` 即
清空备注。每个条目只接受 `name`、可空 `note`、`daemonConfig` 与 `registrationTokenHash`；结构、必填
字段、长度、URL、命令数组与根标记均按同一 codec 严格校验，非法条目在预检阶段整份拒绝，不因条目级
跳过而忽略。文件只含令牌摘要，不能用来冒充 Daemon；导入后已安装的 Daemon 用原令牌继续连接。

## 可选 LSP

**LSP 客户端已内置在 Daemon 中**，基于 `vscode-jsonrpc` 实现，不需要额外安装客户端，Daemon 也不负责下载或升级语言服务器。
服务器由你在卡片的 `daemonConfig.lsp` 中以内联 JSON 声明；不启用时保存为 `null`，LSP 查询返回明确的
不可用错误，但文件、命令、检索仍可使用。没有独立的 `lsp.json` 或文件路径配置项。修改 LSP 声明后
新调用使用新 generation，在途调用按既有生命周期收敛。

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
不越过最近的 `.git` 目录或 gitfile。服务器在该项目根启动；文件 `path` 必须为绝对路径，不用
`workdir` 解析。

程序基名识别为 jdtls 后才提供 `java/classFileContents`，`jdt://` 反编译不能在其它服务器上使用。
客户端连接按项目复用，调用超时或取消只取消请求并发送 `$/cancelRequest`，不杀掉其它调用共享的
服务器；初始化失败不重试，进程范围与清理行为见[内置工具与异步委派](../modules/builtin-tools.md#lsp-生命周期)。

## 网络代理

代理是**卡片级**配置：`daemonConfig.proxyUrl` 只作用于该 Daemon 自身的请求，不共享给 Backend，
也不共享给同用户的其它卡片。

- Daemon 的 S3 上传、技能下载、在线更新下载与控制 WS/WSS 都使用当前已应用的卡片代理；
  `null` 表示明确直连，即使宿主存在 `HTTP_PROXY`、`NODE_USE_ENV_PROXY` 或全局 dispatcher 也不
  改变。
- 代理地址仅接受无认证的 `http://host:port`；不支持 SOCKS、代理认证、TLS 到代理、绕过列表或自动
  系统代理探测。控制连接与普通请求使用同一条策略，没有隐式直连回退。
- Bash、LSP、code-server 与 IDE 终端等用户进程保留真实继承或调用方显式配置的环境，**不注入卡片
  代理**。`withLoopbackResponse` 是 code-server/IDE bridge 的专用直连通道。
- 人工安装/覆盖使用的下载命令继承执行时的宿主代理环境；在线更新的独立作业只构造本次下载所需的
  大小写代理变量，值为当前卡片代理，`null` 时显式直连，不改 `process.env` 或通用进程监督器。
- 修改 `studioUrl` 或 `proxyUrl` 后，Daemon 完成快照切换即按新参数主动重连；旧连接上已受理的
  执行继续收敛。代理或地址错误可能使执行端失联，此时只能恢复原网络可达性后接收修正，或者修正卡片
  后重新执行安装引导。没有隐式直连回退、自动恢复旧配置或额外救援连接。

Backend 自身的模型、MCP、插件与 S3 SDK 请求由 Studio 的**系统设置 → 网络**（`backendNetwork`）
单独治理，和卡片代理互不覆盖。透明 VPN/TUN 的底层路由由宿主网络栈决定。

## 验证与 READY

安装器默认最多等待 30 秒，再检查运行状态持续保持 3 秒。它验证的是服务或任务存活，**不是 gateway
注册成功**，也不代表最新配置已经应用。Linux/macOS 用 `status` 或系统命令检查主机侧状态：

| 平台 | 主机侧检查 | 日志 |
| --- | --- | --- |
| Linux | `systemctl --user is-active kk-studio-daemon-<UUID>.service` | `journalctl --user -u kk-studio-daemon-<UUID>.service -n 50 --no-pager` |
| macOS | `launchctl print gui/$(id -u)/fun.fengwk.kkstudio.environment-daemon.<UUID>` | `~/.kk-studio/environments/<UUID>/daemon.stdout.log` 与 `.stderr.log` |
| Windows | `install.ps1 status -EnvironmentId <UUID>` 或 Task Scheduler | Task Scheduler **不捕获 stdout/stderr** |

`status` 只读，不启动服务。退出码 `0`：Linux active、macOS loaded、Windows Running；`1`：未安装
或不是受管安装；`3`：已有受管安装但未达到上述状态（macOS loaded 甚至不保证进程仍存活）。

**注册是否成功必须在 Studio 的 Environment 页面确认 `READY`**。Daemon 在收到并应用当前完整卡片
配置后才发送 READY；SETTINGS 只绑定 scope 与平台资源预算。若当前进程尚无可应用配置、或候选校验
失败，连接保持 CONNECTING，Daemon 仍可通过同一连接上报配置错误并等待修正；未 READY 时不接受能力
调用、SkillSync 或 IDE 命令。

连接状态与配置状态是两件事，页面分开显示：

| 条件 | 展示 |
| --- | --- |
| 当前实例 `appliedVersion` = 卡片 `version` | 已应用 |
| 已发送新版本、尚未完成 | 待应用 |
| 当前配置错误的 `version` = 卡片 `version` | 应用失败，显示字段与规则 |
| 离线且无当前快照 | 待连接 |
| 当前 `appliedVersion` 小于卡片 `version` | 待应用 |

READY 后 Platform 会异步同步已发布的 Skill Package；同步失败不撤销 READY，其它宿主能力仍可用。已选择
环境时，未安装当前版本的技能不会进入 system prompt。

## 管理身份与冲突处理

安装器只管理**当前用户、当前卡片**且带所有权标记的服务定义：

| 平台 | 所有权检查 |
| --- | --- |
| Linux | unit 首行必须是 `# Managed by scripts/daemon/install.sh`，且 ExecStart 指向本卡片的 `lib/` 与 `daemon.json` |
| macOS | plist 第二行必须是 `<!-- Managed by scripts/daemon/install.sh -->`，且参数指向本卡片路径 |
| Windows | 任务 Description 必须精确等于 `Managed by scripts/daemon/install.ps1; ownerSid=<当前 SID>; environmentId=<UUID>` |

检查顺序在下载与写入之前。同名服务存在但标记不匹配、定义是符号链接/非普通文件、属主不是当前
用户、定义引用了其它卡片的程序或配置时，安装或卸载会拒绝并报告原因、服务定义路径或任务名，以及
一条检查命令。安装器**不会删除未知程序发行目录或数据，也不会伪造所有权标记**；标记只用于识别管理
归属，不能通过手工补标记授权接管。

每张卡片独立受管，同一 OS 用户可安装多张卡片；它们各自继承启动用户的文件与命令权限，没有文件系统
沙箱。只把 Environment 开放给可信的 Studio 用户和 Agent；`process.exec` 的 `workdir` 是调用目录，
不是权限边界，HOME 也不是默认工作目录。

## 修改配置、轮换 token 与更新

**改配置不需要重装。** 在卡片配置中修改 Studio 地址、Bash、LSP 或代理后保存，Studio 推进版本并
通知当前 owner；Daemon 读取完整快照、准备新网络/LSP generation、原子写入本地缓存并同步切换运行
快照。仅版本推进而内容相同的保存只更新确认版本，不重建客户端或 LSP。配置错误时已有可用快照继续
运行，新版本报错并等待下一次保存或重连。

**轮换 token** 会立即使旧令牌的下次握手失效。已安装 Daemon 需要新的 `daemon.token`：可在 Web 重新
签发安装 code 并执行安装命令，或安全替换 `daemon.token` 文件后重启受管服务。Daemon 每次连接准入前
读取 token 文件。

### 受管在线更新

已受管的安装可以从 Web 直接触发一次**二进制在线更新**，无需重新复制安装命令：

| 入口 | 契约 |
| --- | --- |
| `POST /api/platform/environments/{id}/update-daemon` | 发起一次受管更新；目标版本由运行中 Platform 的打包版本决定。响应 `{runtimeStatus, commandStatus}`，`commandStatus` 为 `CURRENT`（已是目标版本）、`IN_PROGRESS`（同一目标已在更新）或 `SENT_UNCONFIRMED`（命令已进入发送队列，Daemon 尚未确认）。目标不可用、已有更新在途或存在在途调用/工作时以冲突拒绝 |
| `GET /api/platform/environments/{id}/runtime` | 只读当前 owner 的运行快照 `{daemonVersion, update, configuration}`；`update` 只在正在更新时存在（否则显式 null），包含 `targetVersion`、`downloadedBytes` 与可空 `totalBytes`；`configuration` 包含 `appliedVersion`、`proxyUrl` 与可空 `error` |

更新目标是**运行中 Platform 版本对应的官方 GitHub Release**：必须是非草稿、tag 匹配、五件套资产
齐全（`kk-studio-daemon-<tag>-<os>-<arch>.tar.gz`、其 `.tar.gz.sha256`、确定性元数据、`LICENSE`、
`THIRD_PARTY_NOTICES`）的完整发布，且制品地址就是官方地址。Daemon 收到目标版本后下载该 tag 的官方
安装脚本，由安装脚本校验官方归档及校验文件，并以 `update` 动作替换程序。

- 一个 Environment 同一时刻至多一次更新：Server 只在命令下发的短暂窗口内登记内存准入，发送返回后
  立即释放；最终互斥由本机 `install.lock` 保证。更新期间普通 capability 调用在起点被 busy 拒绝，
  连接仍保持认证。
- 只有官方受管安装接受更新：Daemon 的 config/token/distribution 路径必须等于本卡片受管目录。
- Daemon 启动 **OS 级独立、短生命周期的任务**（Linux `systemd-run` 瞬时单元、
  macOS `launchctl submit` 瞬时 job、Windows 一次性计划任务），任务在目标服务作用域之外运行，
  服务停止后仍能继续。它先写 `ready`，收到 `permit` 后调用安装器 `update`。
- 更新下载使用当前卡片代理（`null` 时显式直连），不读写新的配置与 token；卡片热更新在下载期间照常
  处理，最终发布时安装器重新读取最新缓存并用新程序复检，不会被准备阶段的旧快照覆盖。
- 阶段回执只有 `ACCEPTED`、`PREPARED`（下载、校验与磁盘配置预检通过，可交给 OS 更新器）与
  `FAILED`，回执本身不表达成功。**最终成功只由更新后 Daemon 以目标版本重新 READY 确认**；
  重连后 Daemon 重发仍有效的回执。
- 持久诊断只有卡片目录的 `last-operation.json`（固定字段、≤4 KiB，匹配本次 `operationId`）；作业
  目录与安装器、归档、runner、ready/permit 标记在完成或失败后由独立作业清理，不保留
  `updates/<operationId>-*`、`install.log` 或历史目录。服务仍在运行时安装失败会发送安全的失败回执；
  旧 Daemon 已退出时不承诺通过控制通道回报，卡片会离线，本地保留最近结果。
- 协议版本不一致时，准入端点返回 HTTP 426 + protobuf `UpgradeRequired`（含目标 Daemon 版本），
  Daemon 据此执行本机受管更新；无法受管更新时需重新覆盖安装。

### 覆盖安装

需要重新引导时，在卡片上重新“安装 / 覆盖”并复制命令，在目标主机执行。安装器先校验新发行归档、
配置信封与安装输入，再停止受管服务，发布新程序、配置与 token 并重启服务。配置与凭据以本次脚本为
新引导缓存，随后仍从 Studio 对账最新配置；数据目录、技能与用户数据保留，重新 `READY` 后才继续正常
使用。重启会中断在途工具调用，已经发生的命令副作用不回滚。

## 卸载与数据保留

`uninstall` 停止服务、删除受管服务定义与本卡片 `lib/` 下的程序发行目录，**保留 `daemon.json`、
`daemon.token`、`ide/`、`skills/`、日志、`last-operation.json` 与其它用户数据**。它是幂等的：服务
定义已经缺失但程序仍残留时继续完成清理；两者都不存在时直接报告未安装并保留数据。卸载不会删除任何
Studio Environment 记录，也不清理 token、数据与日志；彻底删除前先评估历史引用，并按需手动清理。

数据目录持有 `daemon.lock`，同一卡片目录只允许一个 Daemon。`tmp/workspaces/<uuid>` 保存命令与检索的
大文本全文（`*.part` 中转后原子发布 `*.log`），模型历史可能仍引用其绝对路径。定时清扫（默认 30 分钟
一次）只回收超过当前保留期（默认 3 天）且未被使用的 workspace，历史引用的全文因此在保留期后可能
被自动回收。`skills` 保存已安装包及 `.staging-*` / `.old-*` 安装暂存与替换备份；安装器负责清理暂存，
并保留已发布的技能包。安装器自身不创建 `backups/`，也不保留历史程序或配置副本。

## 参数与排错

完整用法以 [Unix 脚本](../../scripts/daemon/install.sh) 的 `help` 和
[Windows 脚本](../../scripts/daemon/install.ps1) 的 `help` 为准：

| 动作 / 参数 | Unix | Windows | 说明 |
| --- | --- | --- | --- |
| install | `install` | `install` | 必填 `--environment-id` / `-EnvironmentId`、`--version` / `-Version`、`--config-file` / `-ConfigFile` 与 `--token-file` / `-TokenFile` |
| update | `update` | `update` | 必填卡片 UUID 与目标版本；不接收配置或 token |
| status | `status` | `status` | 只读；退出码 0/1/3 见上 |
| uninstall | `uninstall` | `uninstall` | 保留配置、token、技能与用户数据 |
| help | `help` | `help` | 输出用法 |

`status` 与 `uninstall` 只接收 `--environment-id` / `-EnvironmentId`。配置与 token 必须是同目录、
名为 `daemon.json` / `daemon.token` 的兄弟文件。

| 现象 | 处理 |
| --- | --- |
| 下载脚本返回 HTTP 400 | 安装 code 已过期、被修改，或因配置/凭据变化失效；回 Studio 重新复制安装命令 |
| `配置属于另一张环境卡片` | 安装输入不是本 UUID 的配置；重新从该卡片生成命令，不要复用其它卡片的暂存文件 |
| `配置格式不是当前版本` | 输入不是 `{environmentId, version, daemonConfig}` 结构；重新生成安装命令 |
| GitHub 下载或 SHA 校验失败 | 检查网络、宿主代理与 release 是否含成对资产；不要跳过校验，现有受管安装保持不动 |
| `unmanaged unit` / `unmanaged plist` / `unmanaged Scheduled Task` | 按[管理身份与冲突处理](#管理身份与冲突处理)确认归属，检查、导出、停止并移开非受管定义；不要伪造所有权标记 |
| `该环境的另一个安装操作正在执行` | 同一卡片已有 install/update/uninstall 持锁；等待其结束，不要删除锁文件 |
| `服务停止后仍持有运行锁` | 目标进程未在窗口内退出；检查进程与日志后重试，不放宽检查、不强行删锁 |
| 配置或 token 文件权限失败 | Unix 确认属主与无 group/other 权限；Windows 检查当前 SID、继承与其它 Allow ACE。安装器拒绝不安全现存文件，不自动放宽规则 |
| `服务启动失败` / `start-limit-hit` | 查看服务状态与本次启动日志，先处理退出原因；覆盖安装会清除旧失败计数，无需放宽启动限流 |
| 服务存活但未 READY | 核对 studioUrl、token、网络与代理；Linux 看 journal，macOS 看卡片日志，Windows 用前台复现 |
| 配置状态显示“应用失败” | 按错误中的字段与规则修正卡片配置后重新保存；恢复网络可达性不能替代配置修正 |
| LSP 显示未安装或查询失败 | 检查卡片 `lsp` JSON、服务进程可见的程序路径、服务器依赖与项目根；read header 的 supported 只证明配置/程序发现，不证明服务器能初始化 |
| WebSocket close code 1010 | 终止浏览器侧 WebSocket（`/ws/events` 与 IDE 视图）的代理层启用 `permessage-deflate`（Daemon 与 Studio 之间的 WSS 不协商压缩扩展） |

Unix 安装失败先按输出的阶段和下一步处理。服务状态和日志可能含敏感信息，分享前请去除 token、安装
code 与带凭据的 URL；不要开启 shell 调试回显。

### 前台诊断

先停止受管服务，避免数据锁冲突，再用该卡片归档内置的冻结运行时与同一份 `daemon.json` 启动程序，
输出直接写控制台；`Ctrl-C` 结束，不提供常驻守护。`daemon.token` 必须与配置同目录：

Unix:
```bash
"$HOME/.kk-studio/environments/<UUID>/lib/runtime/code-server/lib/node" \
  "$HOME/.kk-studio/environments/<UUID>/lib/main.js" \
  --config "$HOME/.kk-studio/environments/<UUID>/daemon.json"
```

Windows:
```powershell
& "$env:USERPROFILE\.kk-studio\environments\<UUID>\lib\runtime\code-server\lib\node.exe" "$env:USERPROFILE\.kk-studio\environments\<UUID>\lib\main.js" --config "$env:USERPROFILE\.kk-studio\environments\<UUID>\daemon.json"
```

Daemon 自身 CLI 只有 `--config <绝对路径>`（及 `--check-config <绝对路径>` 预检、`--help`/`-h`、
`--version`）。心跳、重连与最大退避固定为 `PT15S` / `PT1S` / `PT30S`，
没有公开开关；工具执行超时由调用方提供，不是 Daemon 安装配置。

上级：[系统设计](../system-design.md)。协议与执行实现：[Environment Daemon](../modules/daemon.md)、
[Harness Environment](../modules/harness-environment.md)。容器内运行见
[部署与运行](deployment.md)，开发验证见[开发与测试](development-and-testing.md)。
