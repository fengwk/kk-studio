# Environment Daemon 安装与运行

Environment Daemon 是宿主上的独立 JVM 进程，连接 Studio 的 Environment gateway，为 Agent 提供
文件读写、命令执行、检索和 LSP 能力。规范安装路径是在目标宿主 clone 源码，再用平台对应的脚本
安装为**当前用户**的常驻服务：Unix（Linux/macOS）用
[scripts/daemon/install.sh](../../scripts/daemon/install.sh)，Windows 用
[scripts/daemon/install.ps1](../../scripts/daemon/install.ps1)。服务启动并成功注册后，Studio 的
Environment 页面会显示 `READY`，Agent 即可绑定它。

进程内部的协议、能力协商与恢复语义见 [Harness Daemon 模块](../modules/harness-daemon.md)，跨模块
边界见[系统设计](../system-design.md)。隔离测试栈里的容器内运行方式见
[部署与运行](deployment.md#隔离栈与可靠性栈)。NAS 上的 App 容器只运行 App 本身，需要主机能力
的每台主机各自运行本 Daemon。

## 平台矩阵

| 平台 | 安装脚本 | 服务机制 | JAR | 服务定义 | 日志 |
| --- | --- | --- | --- | --- | --- |
| Linux | `scripts/daemon/install.sh` | `systemd --user` 用户服务 `kk-studio-daemon.service` | `~/.local/lib/kk-studio/kk-studio-daemon.jar`（0644） | `~/.config/systemd/user/kk-studio-daemon.service`（0644，首行为受管标记） | 用户 journal：`journalctl --user -u kk-studio-daemon.service` |
| macOS | 同一个 Unix 脚本 | 当前用户 GUI 域（`gui/$(id -u)`）的 LaunchAgent `fun.fengwk.kkstudio.environment-daemon` | 与 Linux 相同路径 | `~/Library/LaunchAgents/fun.fengwk.kkstudio.environment-daemon.plist`（0644，受管标记是紧跟 XML 声明的第 2 行注释） | `~/Library/Logs/kk-studio/environment-daemon.stdout.log` 与 `environment-daemon.stderr.log` |
| Windows 10/11 | `scripts/daemon/install.ps1` | 当前用户的 AtLogOn 计划任务 `kk-studio-environment-daemon-<当前用户 SID>` | `%LOCALAPPDATA%\kk-studio\daemon\kk-studio-daemon.jar` | Task Scheduler 中的任务定义（没有 plist/unit 文件） | Task Scheduler **不捕获** stdout/stderr |

两个脚本都以当前用户身份安装、只操作该用户的受管路径，并且只管理自己写出的服务定义（见下文所有权
标记）。Windows 任务只在该用户处于交互登录期间运行：它是计划任务，**不是 Windows Service**，没有
systemd 等价的守护、停止超时与日志语义。macOS 的 LaunchAgent 属于当前用户的图形登录域，没有该域时
安装直接失败。

## 前置条件

所有平台共同要求：

- 目标主机的源码 checkout（`git clone` 后在该目录执行脚本），脚本自行解析仓库根：优先
  `KK_STUDIO_REPO_ROOT`，否则从脚本位置向上寻找 worktree 根。
- JDK 21：home 内 `bin/java -version` 报告 21，且必须提供可执行的 `bin/javac`（构建需要）与
  `bin/javap`（未显式给出 javap 选项时）。默认按 `JAVA_HOME_21` → `JAVA_HOME` → `PATH` 顺序解析
  绝对路径，也可通过 `--java-home` / `-JavaHome` 显式指定；环境变量提供的路径同样必须是绝对路径且
  不含控制字符。
- 构建工具：Unix 上 PATH 要有 `mvn`，Windows 上要有 `mvn.cmd`，用于从当前源码 checkout 构建
  shaded JAR。
- Studio 中已存在目标 Environment，可打开 Environment 页面并复制 registration token。
- 能访问 gateway origin，路径固定为 `/api/harness/environment-daemon/v1`，仅支持 `ws://` 或
  `wss://` scheme，公共 TLS 地址形如
  `wss://studio.example.com/api/harness/environment-daemon/v1`。
- 反向代理必须协商 WebSocket `permessage-deflate` 扩展：Daemon 拒绝未压缩会话，也不会退化为普通
  WebSocket。每个终止 WebSocket 的代理层都要启用压缩。

平台差异：

- Linux：可用的 `systemctl --user` 用户实例。不可用或未登录的用户环境会在触碰任何路径前报错退出。
- macOS：可用的 `gui/$(id -u)` launchd 域，即真正的图形登录会话；没有同时登录桌面的纯 SSH 会话
  因为不存在 GUI 域而失败。
- Unix 通用：两个平台共用同一个 Bash 脚本，需要 Bash 可执行文件。
- Windows：Windows 10/11，Windows PowerShell 5.1 或 PowerShell 7（脚本声明
  `#Requires -Version 5.1`），当前会话具备 ScheduledTasks 模块命令（`Get-ScheduledTask`、
  `New-ScheduledTask*`、`Register-ScheduledTask`、`Start-ScheduledTask`、`Stop-ScheduledTask`、
  `Unregister-ScheduledTask`、`Get-ScheduledTaskInfo`），并且能解析到 `bash.exe`：Git for Windows
  或兼容 Bash 提供的 `bash.exe` 是 `process.exec` 的默认解释器，缺失时可用 `-BashExecutable`
  显式指定。

## 运行边界

Daemon 是普通宿主进程，继承启动它的用户权限：Linux/macOS 上继承该 Unix 用户的权限，Windows 上以
`Interactive` 登录类型与 `Limited` 运行级别在该用户会话中运行。业务授权由 Studio 侧判定，宿主不提供
文件系统沙箱。READY 自动报告进程用户与 canonical HOME，但两者都不是默认工作目录；相对文件路径与
命令执行必须由调用显式给出 `workdir`。

## 写入 registration token

在 Studio 的 Environment 页面为目标 Environment 点击「复制 Token」（或先「重新生成 Token」再
复制），并只以本机 owner-only 普通文件交给 Daemon。

Linux/macOS：

```bash
install -d -m 700 ~/.config/kk-studio
(umask 077; cat > ~/.config/kk-studio/daemon.token)   # 粘贴 token，按 Ctrl-D 结束
chmod 600 ~/.config/kk-studio/daemon.token           # 确认没有 group/other 权限位
```

`umask 077` 让文件从创建起就是 0600，不会出现短暂可读窗口；用编辑器创建也可以，只要最终权限是 0600。

Windows（PowerShell）：先让父目录 owner-only，再用不计入命令历史的交互输入写入 token，最后收敛文件
DACL。三步都不可省略，顺序也不能颠倒。

```powershell
# 1) 父目录先收敛：禁用继承、移除继承来的 ACE，只保留当前用户 SID。
$root = Join-Path $env:USERPROFILE ".config\kk-studio"
New-Item -ItemType Directory -Path $root -Force | Out-Null
$sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value

$dirAcl = Get-Acl -LiteralPath $root
$dirAcl.SetAccessRuleProtection($true, $false)
foreach ($rule in @($dirAcl.Access)) { [void] $dirAcl.RemoveAccessRuleAll($rule) }
$dirAcl.SetOwner([System.Security.Principal.SecurityIdentifier]::new($sid))
$dirAcl.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new(
  $sid, "FullControl", "ContainerInherit,ObjectInherit", "None", "Allow"))
Set-Acl -LiteralPath $root -AclObject $dirAcl

# 2) 用 SecureString 交互输入读取 token：不回显，也不会进入命令历史。
$tokenPath = Join-Path $root "daemon.token"
New-Item -ItemType File -Path $tokenPath -Force | Out-Null
$secure = Read-Host -Prompt "Registration token" -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
  [IO.File]::WriteAllText(
    $tokenPath,
    [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr),
    (New-Object System.Text.UTF8Encoding($false)))
} finally {
  [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
}

# 3) 文件 DACL 收敛：禁用继承，只留当前用户 SID 的 Read/Write，便于后续安全轮换。
$fileAcl = Get-Acl -LiteralPath $tokenPath
$fileAcl.SetAccessRuleProtection($true, $false)
foreach ($rule in @($fileAcl.Access)) { [void] $fileAcl.RemoveAccessRuleAll($rule) }
$fileAcl.SetOwner([System.Security.Principal.SecurityIdentifier]::new($sid))
$ownerRights = [System.Security.AccessControl.FileSystemRights]::Read -bor `
  [System.Security.AccessControl.FileSystemRights]::Write
$fileAcl.AddAccessRule(
  [System.Security.AccessControl.FileSystemAccessRule]::new(
    $sid, $ownerRights, "Allow"))
Set-Acl -LiteralPath $tokenPath -AclObject $fileAcl
```

不要把 token 文本作为命令参数或在提示符下明文输入（例如 `echo`、`Set-Content -Value`、
`-RegistrationTokenFile '<token>'` 之类的写法），那会留在命令历史里；也不要把 token 写进命令行参数、
环境变量文件、文档、日志或 shell 历史。

Token 校验（fail closed）分两处；安装脚本只读元数据、**不读取内容**，Daemon 只在运行时读取内容：

- Linux/macOS 安装脚本按 POSIX 元数据校验 `--registration-token-file`：必须是绝对路径、现存普通文件
  （拒绝符号链接与目录）、非空、属主为当前用户、对属主可读，且不含任何 group/other 权限位
  （`077` 掩码位全为 0）。
- Windows 安装脚本按 ACL 校验 `-RegistrationTokenFile`：必须是绝对路径、现存普通文件、不是
  reparse point、非空、Owner 为当前用户 SID、ACL 继承已禁用（`AreAccessRulesProtected`）、除当前
  用户 SID 外没有任何 Allow ACE、不含 deny-read ACE，且当前用户 SID 具备读取权限。Windows 没有
  POSIX 权限位，因此
  owner-only 由 DACL 表达。
- Daemon 进程启动时按平台对应的同一组路径规则再校验一次，然后**读取文件内容**（去除外围空白）用于
  注册。Unix 上会重新检查属主与 group/other 位；Windows 上没有 POSIX 属性视图，Daemon 只读取内容。
  凭证文本只存在于进程内存与注册报文，不会写入 argv、环境变量、数据目录或日志。

服务定义里只会出现 token 的**文件路径**，token 文本绝不进入命令行参数、构建参数或日志；`mvn` 构建
阶段完全看不到数据面配置。在 Studio 轮换 token 后，只需重写该文件并重启服务：Linux
`systemctl --user restart kk-studio-daemon.service`，macOS
`launchctl kickstart -k gui/$(id -u)/fun.fengwk.kkstudio.environment-daemon`，Windows 用
`Stop-ScheduledTask` 与 `Start-ScheduledTask` 重启该任务。

## 安装与启动（install）

Unix（Linux/macOS）单行命令：

```bash
git clone https://github.com/fengwk/kk-studio.git
cd kk-studio
./scripts/daemon/install.sh install \
  --gateway-uri wss://studio.example.com/api/harness/environment-daemon/v1 \
  --registration-token-file "$HOME/.config/kk-studio/daemon.token"
```

Windows 单行命令：

```powershell
git clone https://github.com/fengwk/kk-studio.git
Set-Location kk-studio
.\scripts\daemon\install.ps1 install `
  -GatewayUri wss://studio.example.com/api/harness/environment-daemon/v1 `
  -RegistrationTokenFile "$env:USERPROFILE\.config\kk-studio\daemon.token"
```

两个脚本的 `--registration-token-file` / `-RegistrationTokenFile` 都必须使用绝对路径。

### 通用安全顺序

两个 install 子命令都先完成输入、所有权和新产物验证，再进入服务切换：

1. 校验全部输入：未知/重复选项、控制字符、非 `ws`/`wss` scheme 或缺少 host 的 gateway 地址、
   非绝对路径（含 HOME 与仓库根）、非十进制非负整数的验证窗口、数据目录与 JDK 路径，以及 token
   文件的属主与权限（Windows 为 ACL）。不受支持的操作系统在同一阶段失败。
2. 校验现有服务定义的所有权：目标位置已有服务定义但不是本脚本生成的，直接拒绝，见下文所有权标记。
3. 构建并自证产物：`mvn -B -ntp -pl harness/daemon -am clean package`（Windows 用 `mvn.cmd`），
   然后执行 `java -jar <jar> --version`，要求退出码为 0 且输出以 `kk-studio-daemon ` 开头。脚本传给
   Maven 的唯一安装配置是 `JAVA_HOME`；gateway、token 路径与 token 文本都不进入 Maven argv 或环境。
   这条检查只证明入口类与内嵌依赖可加载，**不校验**版本号与当前 checkout revision 是否一致。
4. 暂存后替换：新产物先暂存到目标同一目录（因此不受跨文件系统改名限制），再替换目标路径。Unix 的
   JAR 与 unit/plist 都是临时文件 + `mv -f` 原子改名；Windows 的 JAR 用同目录 `Move-Item -Force`
   覆盖，任务定义则通过 `Register-ScheduledTask -Force` 重新注册。进入替换阶段后的宿主 API、磁盘或
   启动失败会明确返回错误，但不承诺自动回滚已完成的服务切换。
5. 直接 Java 动作：服务定义直接执行绝对路径的 `java`/`java.exe`，没有 cmd/shell wrapper、环境变量
   文件、注册表项或 `~/.local/bin` 入口；配置只存在于服务定义本身，`mvn` 与令牌文本都不进入其中。
6. 启动并验证：在最多 30 秒窗口内等待平台报告运行状态，再要求它在随后 3 秒内**持续**保持——一次瞬时
   的成功状态不足以判定成功。两个窗口分别由 `DAEMON_VERIFY_TIMEOUT_SECONDS`（默认 30）与
   `DAEMON_VERIFY_STABLE_SECONDS`（默认 3）覆盖，取值必须是十进制非负整数；`0` 表示只做一次立即
   检查、不要求稳定窗口。

编译、产物校验与（macOS）plist lint 都发生在停掉现有服务之前，因此构建失败不会卸下或改写正在运行的
安装。第 6 步只证明进程仍在运行，不代表 gateway 注册成功：注册与可用性以 Studio 的 `READY` 为准。

### 所有权标记

三个平台都以脚本写出的精确标记判断服务定义是否属于自己；缺失时一律拒绝覆盖或删除，避免接管人工维护
的配置：

- Linux：unit 文件首行必须是 `# Managed by scripts/daemon/install.sh`；
- macOS：plist 第 2 行必须是 `<!-- Managed by scripts/daemon/install.sh -->`；
- Windows：任务 `Description` 必须精确等于
  `Managed by scripts/daemon/install.ps1; schema=1; ownerSid=<当前用户 SID>`。

### Linux 执行流程

1. 预检 `systemctl --user` 与已存在的 unit 所有权，然后按通用顺序构建与校验产物。
2. 安装 JAR 到 `$HOME/.local/lib/kk-studio/kk-studio-daemon.jar`（0644，同文件系统临时文件原子覆盖）。
3. 写 unit 到 `$HOME/.config/systemd/user/kk-studio-daemon.service`（0644，临时文件 + `mv -f` 原子
   替换）。`ExecStart` 中的每个参数都会被引号包裹，并转义反斜杠、双引号、`%` 与 `$`，避免 systemd
   的变量展开与说明符二次解释。unit 固定了 `Restart=on-failure`、`RestartSec=10`、
   `TimeoutStopSec=30`、`KillMode=mixed`、`UMask=0077`、`NoNewPrivileges=yes`、
   `Wants=network-online.target`/`After=network-online.target`、
   `StartLimitIntervalSec=300`/`StartLimitBurst=5` 与 `WantedBy=default.target`。
4. 执行 `systemctl --user daemon-reload`、`systemctl --user enable kk-studio-daemon.service`、
   `systemctl --user restart kk-studio-daemon.service`，然后等到服务 `active` 并在稳定窗口内持续
   `active`。
5. 若需要用户注销登录后守护进程仍保持常驻运行，需由系统管理员开启 linger（脚本不代为开启）：

   ```bash
   sudo loginctl enable-linger "$USER"
   ```

### macOS 执行流程

1. 预检 `gui/$(id -u)` launchd 域与已存在的 plist 所有权。
2. 构建并校验产物，同时生成 plist 临时文件并先过 `plutil -lint`；非法 XML 不会替换已有受管 plist。
3. 若已有受管 plist，先执行 `launchctl bootout gui/<uid>/fun.fengwk.kkstudio.environment-daemon`，
   并在验证窗口内轮询 `launchctl print` 直到它变为 unloaded——bootout 返回后任务仍可能短暂 loaded，
   在真正卸载前不会替换 plist 或再次 bootstrap。该步骤在 JAR 发布之前完成。
4. 发布 JAR，原子改名替换 plist，然后 `launchctl bootstrap gui/<uid> <plist>`；启动成功只由进程存活性
   证明：用 `launchctl kickstart -p` 取得 PID，并在稳定窗口内用 `kill -0` 持续确认该 PID 存活。
5. plist 键固定：`Label`、`ProgramArguments`（绝对路径的 java、同一个 JAR 与全部参数，逐项 XML 转义）、
   `RunAtLoad=true`（登录即启动）、`KeepAlive={SuccessfulExit=false}`（非成功退出才重启）、
   `ThrottleInterval=10`、`Umask=63`（八进制 077）、`WorkingDirectory=$HOME`、
   `StandardOutPath`/`StandardErrorPath` 指向 `~/Library/Logs/kk-studio/`。plist 直接执行绝对路径
   的 java，不经过 shell，也不读取环境变量。

### Windows 执行流程

1. 预检宿主与工具链：`$env:OS` 必须是 `Windows_NT`，ScheduledTasks 命令必须齐全，并解析当前用户
   SID、用户 profile 与 `%LOCALAPPDATA%`；缺少任一命令或路径立即失败。
2. 校验 gateway URI、token 文件 ACL、数据目录、JDK 21、`bash.exe` 与 `javap.exe`，并检查已存在任务的
   所有权标记。
3. 构建并校验产物，然后把 JAR 暂存到 `%LOCALAPPDATA%\kk-studio\daemon` 下的临时文件。
4. 任务定义：
   - 动作直接执行 `java.exe`（绝对路径，无 wrapper、无重定向、不经过 cmd.exe 或 PowerShell runner），
     工作目录是当前用户 profile；参数用 Windows 命令行序列化，空串或含空白/引号的参数按
     JDK 使用的 Windows C runtime 规则加引号并转义反斜杠，因此含空格的路径不会被拆分；
   - 触发器是当前用户 SID 的 `AtLogOn`；principal 为 `Interactive` 登录类型与 `Limited` 运行级别，
     即只在该用户交互登录期间运行，且不提权；
   - 设置为 `ExecutionTimeLimit=0`（无运行时限）、`AllowStartIfOnBatteries`、
     `DontStopIfGoingOnBatteries`、`MultipleInstances=IgnoreNew`，以及 `RestartCount=3` 与
     `RestartInterval=1 分钟`。这些重启设置是**尽力而为**的，不等价于 systemd 的守护与重启语义。
5. 若已有任务处于 `Running`/`Queued`，先停止并等待它离开这两个状态，之后才替换 JAR；再用
   `Register-ScheduledTask -Force` 重新注册任务，随后启动任务并等到 `Running` 且在稳定窗口内持续
   `Running`。

Task Scheduler 不捕获 Daemon 的 stdout/stderr：Windows 上没有 journal，也没有日志文件，诊断要靠前台
运行复现或 Studio 侧状态。

### 可选安装参数

两个脚本的参数一一对应，语义相同；所有取值都不能包含控制字符或换行：

| Unix 选项 | Windows 参数 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `--gateway-uri` | `-GatewayUri` | 无（必填） | Studio Environment WebSocket gateway 地址，仅支持 `ws://` 或 `wss://` |
| `--registration-token-file` | `-RegistrationTokenFile` | 无（必填） | registration token 的绝对文件路径，属主必须是当前用户且仅该用户可读（Unix 0600 / Windows 仅当前 SID 的 Allow ACE） |
| `--java-home` | `-JavaHome` | 自动解析 | 指定绝对 JDK 21 home（须含可执行的 `bin/java`、`bin/javac`）；默认顺序为 `JAVA_HOME_21` → `JAVA_HOME` → PATH |
| `--data-dir` | `-DataDir` | `$HOME/.kk-studio`（Windows 为 `%USERPROFILE%\.kk-studio`） | 指定本地数据目录绝对路径 |
| `--note` | `-Note` | 无 | 单行可信备注文本（不超过 512 字符，两端无空格），进入受信任模型 SYSTEM Prompt。只能由可信操作者设置，禁止包含凭证或秘密 |
| `--bash-executable` | `-BashExecutable` | 自动解析 | `process.exec` 使用的 bash 可执行文件路径；Windows 默认解析 PATH 上的 `bash.exe` |
| `--lsp-bridge-command` | `-LspBridgeCommand` | 缺省禁用 | LSP bridge 命令；未提供时禁用 LSP 查询能力 |
| `--javap-executable` | `-JavapExecutable` | `<选中 JDK>/bin/javap`（Windows 为 `bin\javap.exe`） | 用于 class 反编译回退的 javap 路径 |

`upgrade`、`status`、`uninstall` 不接受任何安装参数；Windows 只把显式给出的 `-Note`、
`-LspBridgeCommand` 等写进任务定义。Unix 的 `install --help` 只在它是 `install` 的完整参数列表时打印
用法；Windows 的 `-Help`（`install -Help` 也接受）不与任何安装参数混用。其它组合按未知选项失败，
避免帮助掩盖无效命令。

## 查看状态与日志（status）

Linux：

```bash
./scripts/daemon/install.sh status
```

以非交互模式输出 `systemctl --user status kk-studio-daemon.service` 与 journal 末尾 20 行内容，退出码：
`0` 服务 `active`；`1` 没有受管安装（无 unit，或存在不带受管标记的人工 unit，此时只报错不查询）；
`3` 受管服务已安装但未 `active`。也可以直接用 systemd 原生命令：

```bash
systemctl --user is-active kk-studio-daemon.service
journalctl --user -u kk-studio-daemon.service -n 50 --no-pager
journalctl --user -u kk-studio-daemon.service -f
```

macOS：

```bash
./scripts/daemon/install.sh status
```

输出 `launchctl print gui/$(id -u)/fun.fengwk.kkstudio.environment-daemon` 与
`~/Library/Logs/kk-studio/` 下两个日志文件的末尾 20 行；只读，不会 kickstart 拉起服务。退出码：
`0` launchd 已加载；`1` 没有受管安装；`3` 已安装但未加载。这里的 `0` 只表示服务已被 launchd 加载，
不代表注册成功或健康。

Windows：

```powershell
.\scripts\daemon\install.ps1 status
```

只读地输出任务的 `TaskName`、`State`、`Description` 与 `LastRunTime`、`LastTaskResult`、`NextRunTime`，
不会启动任务。退出码：`0` 任务 `Running`；`1` 未安装，或任务不属于本脚本（只报错，不报告为受管安装）；
`3` 已安装但未 `Running`。Task Scheduler 不捕获 stdout/stderr，`Running` 只表示任务进程存在，不等于
gateway 健康。

## 升级服务（upgrade）

当本仓库代码更新或切换 revision 后，直接在 checkout 目录执行 `upgrade`：

```bash
git pull
./scripts/daemon/install.sh upgrade
```

```powershell
git pull
.\scripts\daemon\install.ps1 upgrade
```

`upgrade` 契约：

- 要求已存在带受管标记的服务定义，且不接受任何配置参数；
- 直接复用服务定义中已记录的完整配置（gateway、token 文件路径、note 等），无需重复输入；
- 基于当前 checkout 重新构建 shaded JAR 并校验 `--version`；
- 只替换 `~/.local/lib/kk-studio/kk-studio-daemon.jar` /
  `%LOCALAPPDATA%\kk-studio\daemon\kk-studio-daemon.jar`，不改写服务定义（Windows 任务定义也不变）；
- 平台重启方式：Linux `systemctl --user daemon-reload` + `restart`；macOS 用
  `launchctl kickstart -k` 先停再起，让已加载的服务重新执行新 JAR；Windows 先停止任务、替换 JAR，再
  启动任务；重启后都按同一窗口规则验证运行状态；
- 重启会中断正在执行的工具调用（内存 Invocation journal 不跨进程保留），已落盘的数据和命令日志保持
  完好。

## 卸载服务（uninstall）

```bash
./scripts/daemon/install.sh uninstall
```

```powershell
.\scripts\daemon\install.ps1 uninstall
```

`uninstall` 契约：

- 要求平台服务管理器可用，且已存在的服务定义带受管标记（人工维护的配置一律拒绝删除）；
- Linux：`systemctl --user disable --now kk-studio-daemon.service` 停止并禁用服务，失败时立即中止；
  随后只删除受管 unit 与已安装 JAR，执行 `daemon-reload` 与 `reset-failed`；
- macOS：若服务已加载先 `launchctl bootout`（失败则什么都不删，仍在运行的进程必须继续能找到自己的
  JAR 与 plist）；随后只删除 plist 与已安装 JAR；
- Windows：若任务处于 `Running`/`Queued` 先停止并等待其离开该状态，然后取消注册任务并删除 JAR；
- 保留数据：registration token 文件与数据目录（默认 `$HOME/.kk-studio`，Windows 为
  `%USERPROFILE%\.kk-studio`）被显式保留；macOS 还会保留
  `~/Library/Logs/kk-studio/environment-daemon.{stdout,stderr}.log`，Linux 的 journal 历史留在
  journal 中；Windows 安装器没有创建 stdout/stderr 日志文件；
- 如需彻底清理历史数据，可手动删除数据目录与 token 文件：

  ```bash
  rm -f ~/.config/kk-studio/daemon.token
  rm -rf ~/.kk-studio
  ```

## 数据目录布局

默认数据目录为 `$HOME/.kk-studio`（Windows 为 `%USERPROFILE%\.kk-studio`），可用 `--data-dir` /
`-DataDir` 指定为其它绝对路径。目录、锁文件、暂存文件与原文日志在支持 POSIX 的文件系统上收敛为
owner-only（目录 0700、文件 0600；Linux unit 同时设置 `UMask=0077`，macOS plist 设置 `Umask=63`）；
Windows 没有 POSIX 权限位，Daemon 退回使用 owner-only 的 ACL 视图。同一目录同一时间只允许一个
Daemon 进程持有独占锁，第二个进程以明确错误退出。

```text
~/.kk-studio/
  daemon.lock                          # 进程独占文件锁
  resources/
    text/<invocation>-<unique>.log     # 命令输出 durable 全文
    staging/<name>.part                # 上传中转暂存，启动时清理遗留文件
  skills/<package>/                    # 已安装的 Skill Package
  skill-work/
    cache/<package>.git                # 每个 Package 的 bare Git 缓存
    staging/<package>.<uuid>/          # 安装暂存，启动时清空
    backup/<package>.<uuid>/           # 原子替换前的备份，启动时回滚或清理
```

`resources/text` 下的全文日志是 durable 事实（模型历史可能仍引用其绝对路径），Daemon 不会自动
删除，由运维按本地保留策略清理。启动清理只涉及未发布的中间产物：`resources/staging` 的遗留
`*.part`、`skill-work/staging` 的全部内容，以及 `skill-work/backup` 中可回滚到 `skills/` 的备份；
`skill-work/cache` 会保留以复用 Git 对象，仅当 Package 的 origin URL 不再匹配时整份丢弃重建。
卸载服务时整个数据目录完整保留。

## 验证 READY 与能力使用

安装并启动后，按平台检查进程存活，再以 Studio 为准判定就绪：

- Linux：`systemctl --user is-active kk-studio-daemon.service` 输出 `active`，且
  `journalctl --user -u kk-studio-daemon.service -n 50 --no-pager` 中没有注册失败报错。
- macOS：`./scripts/daemon/install.sh status` 退出码为 `0`，且 `~/Library/Logs/kk-studio/` 下两个日志
  尾部没有注册失败报错。
- Windows：`.\scripts\daemon\install.ps1 status` 退出码为 `0`（任务 `Running`）；Task Scheduler 不提供
  stdout/stderr，注册失败只能从 Studio 侧状态或前台运行观察到。

无论哪个平台，最终都必须确认 Studio 的 Environment 页面把该 Environment 显示为 `READY`：主机侧
服务管理器状态（`active`、launchd `loaded`、任务 `Running`）只说明进程存在，单独不足以判定就绪。

状态转为 `READY` 后：

- 可在 Chat 对话分支中选择该 Environment，并为 Agent 配置需要的 Environment Tools；
- Platform 会在 READY 后异步把当前已发布的 Skill Package 同步到 Daemon；若同步遇到异常，不影响
  其它 Environment 命令与文件能力，Agent 会退回使用 Platform Skill URI。

## 前台调试（非常驻）

排查临时问题时，可以用已编译或已安装的 JAR 直接在前台运行：

```bash
/path/to/jdk-21/bin/java -jar "$HOME/.local/lib/kk-studio/kk-studio-daemon.jar" \
  --gateway-uri wss://studio.example.com/api/harness/environment-daemon/v1 \
  --registration-token-file "$HOME/.config/kk-studio/daemon.token"
```

```powershell
& "$env:JAVA_HOME_21\bin\java.exe" -jar `
  "$env:LOCALAPPDATA\kk-studio\daemon\kk-studio-daemon.jar" `
  --gateway-uri wss://studio.example.com/api/harness/environment-daemon/v1 `
  --registration-token-file "$env:USERPROFILE\.config\kk-studio\daemon.token"
```

前台运行时 `Ctrl-C` 直接终止进程，输出直接写在当前控制台。前台启动不具备任何平台的自动重启、资源
约束与登录守护，仅用于临时开发或调试，不得作为常驻方案。

## 发布物获取（可移植替代方式）

针对无法在宿主安装 Maven 或克隆源码的特殊环境，官方在 [GitHub Releases](https://github.com/fengwk/kk-studio/releases)
提供预构建资产：

| 文件 | 用途 |
| --- | --- |
| `kk-studio-daemon-<tag>.jar` | 可执行 Daemon shaded JAR |
| `kk-studio-daemon-<tag>.jar.sha256` | JAR 的 SHA-256 校验文件 |
| `kk-studio-daemon-<tag>.json` | 确定性发布元数据（tag、commit、`minimumJava=21`、artifact 摘要） |
| `LICENSE` | Apache License 2.0 |
| `THIRD_PARTY_NOTICES` | 第三方组件来源与许可 |

校验并放置发布物示例：

```bash
RELEASE_TAG=vX.Y.Z                   # 替换为实际 release tag
BASE="https://github.com/fengwk/kk-studio/releases/download/${RELEASE_TAG}"
cd /tmp
curl -fLO "$BASE/kk-studio-daemon-${RELEASE_TAG}.jar"
curl -fLO "$BASE/kk-studio-daemon-${RELEASE_TAG}.jar.sha256"
sha256sum -c "kk-studio-daemon-${RELEASE_TAG}.jar.sha256"
mkdir -p ~/.local/lib/kk-studio
install -m 644 "kk-studio-daemon-${RELEASE_TAG}.jar" ~/.local/lib/kk-studio/kk-studio-daemon.jar
```

发布物只提供预构建 JAR 与元数据的便携获取手段，不作为安装途径：常驻安装一律由平台对应的源码
checkout 脚本管理（Unix 用 `scripts/daemon/install.sh`，Windows 用 `scripts/daemon/install.ps1`）。
本仓库不提供手工编写 unit/plist、注册计划任务或外部 wrapper 的配置。

## 常见问题

| 现象 | 处理 |
| --- | --- |
| 启动/安装即失败，提示 token 文件必须是绝对路径、普通文件或 owner-only | Unix：`--registration-token-file` 必须是绝对路径下的现存普通文件（非符号链接），属主为当前用户且没有 group/other 权限位（用 `chmod 600` 修正）。Windows：`-RegistrationTokenFile` 必须是绝对路径、非 reparse point、Owner 为当前用户 SID、继承已禁用、除当前用户 SID 外无 Allow ACE；按上文 PowerShell 步骤重新收敛 DACL |
| Windows 提示 `ACL inheritance must be disabled`、`must not grant access to another SID` 或 `must not be a reparse point` | 文件仍带有继承或其它 SID 的 Allow ACE（或本身是链接）；重做「写入 registration token」中父目录与文件两级 DACL 设置，不要用创建后不收敛权限的写文件方式 |
| 非零退出，stderr 或日志提示 `environment registration is rejected` | token 已轮换、已撤销或与目标 Environment 不匹配；在 Studio 重新复制 token 并更新 token 文件内容，然后重启服务 |
| 报 `UnsupportedClassVersionError` 或提示需要 JDK 21 | 当前使用的 java 版本低于 21；安装 JDK 21 并通过 `--java-home`/`-JavaHome` 或 `JAVA_HOME_21`/`JAVA_HOME` 指定 |
| 连接反复断开，日志出现 WebSocket close code 1010 | Daemon 与 Gateway 之间的反向代理未开启 `permessage-deflate` 扩展；在每个终止 WebSocket 的代理层启用压缩 |
| 启动报数据目录已被占用 | 同一数据目录下已有其它 Daemon 进程持有 `daemon.lock`；停止冲突进程或通过 `--data-dir`/`-DataDir` 指定独立路径 |
| 提示 `systemctl --user is unavailable or unusable` | 当前宿主缺少可用的用户级 systemd 实例；检查是否处于非登录终端或无 systemd 的容器中，必要时改用前台调试 |
| 提示 `launchctl GUI domain gui/<uid> is unavailable` | 当前会话没有图形登录域（例如纯 SSH）；在 macOS 桌面登录会话中重新执行安装 |
| 提示 `cannot bootout` 或 `is still loaded after bootout` | 旧 LaunchAgent 未能在验证窗口内卸载，已有配置未被替换；先用 `launchctl print`/`launchctl bootout` 处理该 label 后重试 |
| 提示 `generated LaunchAgent plist failed validation` | 生成的 plist 未通过 `plutil -lint`；已有受管 plist 未被替换，检查键取值后重试 |
| `upgrade`/`status`/`uninstall` 提示 `refusing to touch unmanaged unit` 或 `refusing to touch unmanaged plist` | 服务定义缺少脚本写入的受管标记，说明它是人工编写或非脚本生成；先手工迁移或删除，再通过 install 脚本管理 |
| Windows 提示 `refusing to touch unmanaged Scheduled Task` | 同名任务的 Description 不是脚本的精确所有权标记；删除或改名该任务后重跑 install |
| Windows 提示 `required ScheduledTasks command is unavailable` | 当前 PowerShell 会话缺少 ScheduledTasks 模块；改用完整 Windows PowerShell 5.1/PowerShell 7 会话执行 |
| Windows 提示 `cannot resolve bash.exe` | 未安装 Git for Windows 或兼容 Bash；安装后重试，或用 `-BashExecutable` 指定绝对路径 |
| Windows 报禁止运行脚本（`running scripts is disabled on this system`） | 执行策略阻止脚本运行；用 `powershell -ExecutionPolicy Bypass -File .\scripts\daemon\install.ps1 ...` 运行，或在策略允许的会话中执行 |
| Windows 提示 `scripts/daemon/install.ps1 supports Windows only` 或 `cannot locate the kk-studio repository root` | 脚本只在 Windows 上运行；仓库根解析失败时设置 `KK_STUDIO_REPO_ROOT` 指向 checkout |
| Windows 任务 `Running` 但 Studio 未显示 `READY`，且看不到日志 | Task Scheduler 不捕获 stdout/stderr；用同一组参数前台运行复现，或从 Studio 侧查看注册与连接状态 |
| Studio 页面始终显示离线，未转入 `READY` | 先确认 gateway 地址与 token；Linux 用 `journalctl --user -u kk-studio-daemon.service -n 50 --no-pager`，macOS 看 `~/Library/Logs/kk-studio/` 下两个日志，Windows 用前台运行查看控制台输出 |

## CLI 选项

以下为 Daemon 进程自身接受的完整命令行选项（与安装平台无关）：

| 选项 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `--gateway-uri` | 是 | — | gateway 地址，仅接受 `ws`/`wss`，路径为 `/api/harness/environment-daemon/v1` |
| `--registration-token-file` | 是 | — | registration token 的绝对文件路径，只能出现一次 |
| `--heartbeat` | 否 | `PT15S` | 心跳间隔 |
| `--reconnect-initial` | 否 | `PT1S` | 首次重连退避 |
| `--reconnect-max` | 否 | `PT30S` | 最大重连退避 |
| `--note` | 否 | 无 | 进入 READY 的可信备注，单行且不超过 512 字符，用于模型 SYSTEM Prompt |
| `--data-dir` | 否 | `~/.kk-studio` | 本地数据目录，显式给出时必须绝对 |
| `--bash-executable` | 否 | `bash` | `process.exec` 使用的 shell 路径 |
| `--lsp-bridge-command` | 否 | 缺省禁用 | LSP bridge 命令，未配置时相关能力返回不可用 |
| `--javap-executable` | 否 | `javap` | class 反编译程序路径 |
| `--help`、`-h` | 否 | — | 作为唯一参数时打印用法并退出 |
| `--version` | 否 | — | 作为唯一参数时打印版本并退出 |

时间参数使用 ISO-8601 duration 文本，重连参数要求非负且首次退避不超过最大退避。未知参数一律
启动失败，不做兼容回退；能力执行超时不属于 Daemon 配置，它由 Tool definition 的默认值与每次调用的
显式 arguments 共同决定。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Daemon 模块](../modules/harness-daemon.md)、[部署与运行](deployment.md)、[开发与测试](development-and-testing.md)。
