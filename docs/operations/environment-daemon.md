# Environment Daemon 安装与运行

Environment Daemon 是宿主上的独立 JVM 进程，连接 Studio 的 Environment gateway，为 Agent
提供文件读写、命令执行、检索和 LSP 能力。按本文完成下载校验、注册、常驻和验证后，Studio 的
Environment 页面会显示 `READY`，Agent 即可绑定它。

进程内部的协议、能力协商与恢复语义见 [Harness Daemon 模块](../modules/harness-daemon.md)，跨模块
边界见[系统设计](../system-design.md)。隔离测试栈里的容器内运行方式见
[部署与运行](deployment.md#隔离栈与可靠性栈)；NAS 上的 App 容器只运行 App 本身，需要主机能力
的每台主机各自运行本 Daemon。

## 前置条件

- JDK 21：`java -version` 报告 21。Daemon 只通过 `java -jar` 启动，不需要 Maven、自定义
  classpath 或 `lib/` 目录。
- Studio 中已存在目标 Environment，可打开 Environment 页面并复制 registration token。
- 能访问 gateway origin，路径固定为 `/api/harness/environment-daemon/v1`，公共 TLS 地址形如
  `wss://studio.example.com/api/harness/environment-daemon/v1`。
- 反向代理必须协商 WebSocket `permessage-deflate` 扩展：Daemon 拒绝未压缩会话，也不会退化为
  普通 WebSocket。每个终止 WebSocket 的代理层都要启用压缩。

运行边界：Daemon 是普通宿主进程，继承启动它的 Unix 用户权限。业务授权由 Studio 侧判定，
宿主不提供文件系统沙箱。READY 自动报告进程用户与 canonical HOME，但两者都不是默认
工作目录；相对文件路径与命令执行必须由调用显式给出 `workdir`。

## 下载并校验发布物

从 [GitHub Releases](https://github.com/fengwk/kk-studio/releases) 下载目标 tag 的发布物：

| 文件 | 用途 |
| --- | --- |
| `kk-studio-daemon-<tag>.jar` | 可执行 Daemon，`java -jar` 直接运行 |
| `kk-studio-daemon-<tag>.jar.sha256` | 上者 JAR 的 SHA-256 校验文件 |
| `kk-studio-daemon-<tag>.json` | 确定性发布元数据（tag、commit、`minimumJava=21`、artifact 摘要） |
| `LICENSE` | Apache License 2.0 |
| `THIRD_PARTY_NOTICES` | 第三方组件来源与许可 |

```bash
RELEASE_TAG=vX.Y.Z                   # 替换为 Releases 页面上的实际 tag
BASE="https://github.com/fengwk/kk-studio/releases/download/${RELEASE_TAG}"
cd /tmp
curl -fLO "$BASE/kk-studio-daemon-${RELEASE_TAG}.jar"
curl -fLO "$BASE/kk-studio-daemon-${RELEASE_TAG}.jar.sha256"
sha256sum -c "kk-studio-daemon-${RELEASE_TAG}.jar.sha256"
mkdir -p ~/.local/lib/kk-studio
install -m 644 "kk-studio-daemon-${RELEASE_TAG}.jar" \
  ~/.local/lib/kk-studio/kk-studio-daemon.jar
```

macOS 用 `shasum -a 256 -c "kk-studio-daemon-${RELEASE_TAG}.jar.sha256"` 校验。校验失败就不要
继续安装。

## 写入 registration token

在 Studio 的 Environment 页面为目标 Environment 点击「复制 Token」（或先「重新生成 Token」再
复制），并只以本机 owner-only 普通文件交给 Daemon。

```bash
install -d -m 700 ~/.config/kk-studio
(umask 077; cat > ~/.config/kk-studio/daemon.token)   # 粘贴 token，按 Ctrl-D 结束
chmod 600 ~/.config/kk-studio/daemon.token           # 确认没有 group/other 权限位
```

`umask 077` 让文件从创建起就是 0600，不会出现短暂可读窗口；用编辑器创建也可以，只要最终
权限是 0600。不要把 token 写进命令行参数、环境变量文件、文档、日志或 shell 历史。

`--registration-token-file` 必须是绝对路径的现存普通文件，不接受符号链接或目录，在支持 POSIX
的文件系统上不得有 group/other 权限位，且对 owner 可读；token 两端空白会被忽略。在 Studio
轮换 token 后重写该文件并重启 Daemon。

## 前台试运行

```bash
java -jar ~/.local/lib/kk-studio/kk-studio-daemon.jar \
  --gateway-uri wss://studio.example.com/api/harness/environment-daemon/v1 \
  --registration-token-file ~/.config/kk-studio/daemon.token
```

注册成功后进程保持前台运行，连接断开时按退避自动重连；`Ctrl-C` 结束进程。启动或注册失败时
进程向 stderr 输出原因并以非零状态码退出。Studio 页面转为 `READY` 后，可在对话分支选择该
Environment，并为 Agent 选择需要的 Environment Tools；Skill 由 Platform 全局目录独立管理。
Platform 会在 READY 后异步把当前已发布的 Skill Package commit 同步到 Daemon；同步失败
不影响其它 Environment 能力，Agent 会退回 Platform Skill URI。

`--help`、`-h` 和 `--version` 只在作为唯一参数时生效，打印后直接退出，不打开数据目录也不建立
连接。

## systemd --user 常驻

写入 `~/.config/systemd/user/kk-studio-daemon.service`，只需替换 gateway URI：

```ini
[Unit]
Description=kk-studio Environment Daemon
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
ExecStart=/usr/bin/java -jar %h/.local/lib/kk-studio/kk-studio-daemon.jar \
  --gateway-uri wss://studio.example.com/api/harness/environment-daemon/v1 \
  --registration-token-file %h/.config/kk-studio/daemon.token
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
```

```bash
systemctl --user daemon-reload
systemctl --user enable --now kk-studio-daemon.service
systemctl --user status kk-studio-daemon.service
```

- 凭证只通过文件路径读取，不要放进 `Environment=`。
- 服务以启动它的系统用户身份运行，Daemon 上报的进程用户与 canonical HOME 都来自这个用户：
  换运行用户就换了 Agent 能触达的文件与命令边界。
- `/usr/bin/java` 必须指向 JDK 21；否则改成本机 JDK 21 的绝对路径。
- 仅在需要「未登录也随机器启动」时执行一次 `sudo loginctl enable-linger "$USER"`；否则 systemd
  会在该用户最后一个会话结束时停止服务。

## 验证 READY

- Studio Environment 页面显示该 Environment 状态为 `READY`；
- `systemctl --user is-active kk-studio-daemon.service` 输出 `active`；
- 进程只在失败时输出诊断，journal 或前台 stderr 是唯一诊断来源：

```bash
journalctl --user -u kk-studio-daemon.service -n 50 --no-pager
```

## 数据目录

默认数据目录是 `~/.kk-studio`，以 owner-only（目录 0700、文件 0600）创建；可用 `--data-dir`
改为其它绝对路径，同一目录只允许一个 Daemon 进程。

```text
~/.kk-studio/
  daemon.lock                 # 进程独占锁
  resources/text/*.log        # 命令输出 durable 全文
  resources/staging/*.part    # 写入中的中转文件，启动时清理
```

`resources/text/*.log` 是命令输出的 durable 全文，Daemon 不会自动删除，必须由运维按保留策略
显式清理；删除整个数据目录会移除进程锁与本机命令输出。

## 升级

按[下载并校验发布物](#下载并校验发布物)重新下载目标 tag 的 JAR 与其校验文件，校验通过后用同一
安装路径替换 `~/.local/lib/kk-studio/kk-studio-daemon.jar`，再重启服务：

```bash
systemctl --user restart kk-studio-daemon.service
```

进程内 Invocation journal 不跨进程保留，重启会中断正在执行的工具调用；已落盘的命令输出
保持不变。

## 卸载

```bash
systemctl --user disable --now kk-studio-daemon.service
rm ~/.config/systemd/user/kk-studio-daemon.service
systemctl --user daemon-reload
rm -rf ~/.local/lib/kk-studio
rm -f ~/.config/kk-studio/daemon.token
rm -rf ~/.kk-studio        # 数据目录：进程锁、resources/ 与 skills/
```

最后一条会删除进程锁、durable 命令文本日志（`resources/text/*.log`）与上传中转暂存
（`resources/staging/*.part`），以及当前安装的 Skill Packages；确认不再需要这些记录后再执行。

## 常见问题

| 现象 | 处理 |
| --- | --- |
| 启动即失败，提示 token 文件必须是绝对路径、普通文件或 owner-only | `--registration-token-file` 指向绝对路径的现存普通文件（非符号链接），权限 `0600` |
| 非零退出，stderr 提示 `environment registration is rejected` | token 已轮换、撤销或与目标 Environment 不匹配；在 Studio 重新复制 token 并重写 token 文件 |
| 报 `UnsupportedClassVersionError` | 使用了低于 21 的 `java`；改用 JDK 21 |
| 连接反复断开，日志出现 WebSocket close code 1010 | Daemon-facing WebSocket 未协商 `permessage-deflate`；在每个终止 WebSocket 的代理层分别启用压缩 |
| 启动报数据目录已被占用 | 同一 `--data-dir` 上已有 Daemon 持有 `daemon.lock`；停止重复进程或改用其它目录 |
| Studio 页面始终不是 `READY` | 核对 gateway 路径与 token，再用 `journalctl --user -u kk-studio-daemon.service -n 50` 查看原因 |

## CLI 选项

| 选项 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `--gateway-uri` | 是 | — | gateway 地址，仅接受 `ws`/`wss`，路径为 `/api/harness/environment-daemon/v1` |
| `--registration-token-file` | 是 | — | registration token 的绝对文件路径，只能出现一次 |
| `--heartbeat` | 否 | `PT15S` | 心跳间隔 |
| `--reconnect-initial` | 否 | `PT1S` | 首次重连退避 |
| `--reconnect-max` | 否 | `PT30S` | 最大重连退避 |
| `--note` | 否 | 无 | 进入 READY 的可信备注，单行且不超过 512 字符 |
| `--data-dir` | 否 | `~/.kk-studio` | 本地数据目录，显式给出时必须绝对 |
| `--bash-executable` | 否 | `bash` | `process.exec` 使用的 shell |
| `--lsp-bridge-command` | 否 | 缺省禁用 | LSP bridge 命令，未配置时相关能力返回不可用 |
| `--javap-executable` | 否 | `javap` | class 反编译回退程序 |
| `--help`、`-h` | 否 | — | 作为唯一参数时打印用法并退出 |
| `--version` | 否 | — | 作为唯一参数时打印版本并退出 |

时间参数使用 ISO-8601 duration 文本，重连参数要求非负且首次退避不超过最大退避。未知参数一律
启动失败，不做兼容回退；能力执行超时不属于 Daemon 配置，它由 Tool definition 的默认值与每次调用的
显式 arguments 共同决定。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Daemon 模块](../modules/harness-daemon.md)、
[部署与运行](deployment.md)。
