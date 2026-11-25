import type { LocaleCatalog } from '@/shared/i18n/types'

/** 执行环境与 IDE。 */
export const environmentCatalog = {
  'ai.ide.title': {
    'en-US': 'Workspace IDE',
    'zh-CN': '工作区 IDE',
  },
  'ai.ide.resize': {
    'en-US': 'Resize IDE panel',
    'zh-CN': '调整 IDE 面板宽度',
  },
  'ai.ide.toggle': {
    'en-US': 'Show or collapse IDE',
    'zh-CN': '显示或收起 IDE',
  },
  'ai.ide.collapse': {
    'en-US': 'Collapse',
    'zh-CN': '收起',
  },
  'ai.ide.close': {
    'en-US': 'Close',
    'zh-CN': '关闭',
  },
  'ai.ide.dismiss': {
    'en-US': 'Dismiss',
    'zh-CN': '忽略',
  },
  'ai.ide.environmentSelector': {
    'en-US': 'Environment',
    'zh-CN': '环境',
  },
  'ai.ide.connecting': {
    'en-US': 'Connecting to the live event stream...',
    'zh-CN': '正在建立实时事件连接…',
  },
  'ai.ide.backoffReconnecting': {
    'en-US': 'Connection lost; reconnecting automatically.',
    'zh-CN': '连接已断开，正在自动重连。',
  },
  'ai.ide.backoffHandshake': {
    'en-US': 'The event connection did not finish its handshake; retrying.',
    'zh-CN': '事件连接握手未完成，正在重试。',
  },
  'ai.ide.backoffLiveness': {
    'en-US': 'The event connection stopped responding; reconnecting.',
    'zh-CN': '事件连接失去响应，正在重连。',
  },
  'ai.ide.backoffServerRestart': {
    'en-US': 'The server is restarting; reconnecting.',
    'zh-CN': '服务正在重启，正在重连。',
  },
  'ai.ide.stoppedProtocol': {
    'en-US': 'The event connection was closed for a protocol error. Reload the page to reconnect.',
    'zh-CN': '事件连接因协议错误被关闭，请刷新页面后重连。',
  },
  'ai.ide.stoppedPolicy': {
    'en-US': 'The event connection was closed for a policy violation. Check your session or permissions and reload the page.',
    'zh-CN': '事件连接因策略限制被关闭，请检查会话或权限后刷新页面。',
  },
  'ai.ide.stoppedManual': {
    'en-US': 'The event connection is stopped.',
    'zh-CN': '事件连接已停止。',
  },
  'ai.ide.storageUnavailable': {
    'en-US': 'Browser storage is unavailable; IDE recovery and last-used state will not persist.',
    'zh-CN': '浏览器存储不可用，IDE 恢复信息与最近使用状态将不会保留。',
  },
  'ai.ide.openWorkspace': {
    'en-US': 'Open workspace',
    'zh-CN': '打开工作区',
  },
  'ai.ide.workspaces': {
    'en-US': 'Workspaces',
    'zh-CN': '工作区',
  },
  'ai.ide.workspace': {
    'en-US': 'Empty window',
    'zh-CN': '空窗口',
  },
  'ai.ide.recoveryMigrationFailed': {
    'en-US': 'The previous IDE recovery state could not be verified. Its saved history was preserved; do not open a replacement view until you review it.',
    'zh-CN': '无法确认此前的 IDE 恢复状态，已保留原有记录。请先检查恢复记录，再决定是否打开替代视图。',
  },
  'ai.ide.sessionUnsupported': {
    'en-US': 'This browser does not support Web Locks, so an isolated IDE session cannot be established.',
    'zh-CN': '当前浏览器不支持 Web Locks，无法建立隔离的 IDE 会话。',
  },
  'ai.ide.sessionUnavailable': {
    'en-US': 'An independent IDE session is unavailable in this tab. Reload the page to try again.',
    'zh-CN': '当前标签页无法取得独立 IDE 会话，请刷新页面后重试。',
  },
  'ai.ide.sessionConflict': {
    'en-US': 'This IDE session is currently owned by another live page. The old binding was left untouched.',
    'zh-CN': '该 IDE 会话当前由另一个活动页面持有，旧绑定未被改动。',
  },
  'ai.ide.waitingReady': {
    'en-US': 'Waiting for the environment and event connection to become ready.',
    'zh-CN': '正在等待环境与事件连接就绪。',
  },
  'ai.ide.reconciling': {
    'en-US': 'Reconciling the authoritative IDE view state.',
    'zh-CN': '正在对账 IDE 权威视图状态。',
  },
  'ai.ide.viewOwnedByOther': {
    'en-US': 'This IDE view belongs to another browser session. Open a new view for this tab.',
    'zh-CN': '该 IDE 视图属于其他浏览器会话，请为当前标签页打开新视图。',
  },
  'ai.ide.cleanupPending': {
    'en-US': 'The IDE view is still being cleaned up. Wait for cleanup or open a new view.',
    'zh-CN': 'IDE 视图仍在清理中，请等待清理完成或打开新视图。',
  },
  'ai.ide.retryClaim': {
    'en-US': 'Reconcile',
    'zh-CN': '对账',
  },
  'ai.ide.viewGone': {
    'en-US': 'The IDE view is gone and was not recreated automatically.',
    'zh-CN': 'IDE 视图已不存在，未自动创建替代视图。',
  },
  'ai.ide.openNewView': {
    'en-US': 'Open New View',
    'zh-CN': '打开新视图',
  },
  'ai.ide.viewNotReady': {
    'en-US': 'The IDE view is not ready',
    'zh-CN': 'IDE 视图尚未就绪',
  },
  'ai.ide.commandResultUnknown': {
    'en-US': 'The command result is unknown; check the IDE before continuing.',
    'zh-CN': '命令结果未知，请先在 IDE 中确认再继续。',
  },
  'ai.ide.bindingChanged': {
    'en-US': 'The IDE binding changed, so the result was not applied.',
    'zh-CN': 'IDE 绑定已变化，结果未应用。',
  },
  'ai.ide.forceCloseTitle': {
    'en-US': 'Force close IDE view?',
    'zh-CN': '强制关闭 IDE 视图？',
  },
  'ai.ide.forceCloseUnknownMessage': {
    'en-US': 'The close result is unknown. Force closing may discard unsaved changes. Continue?',
    'zh-CN': '关闭结果未知。强制关闭可能丢失未保存的修改，是否继续？',
  },
  'ai.ide.forceCloseControlUnavailableMessage': {
    'en-US': 'The IDE control channel was unavailable, so the close was not sent. Force closing may discard unsaved changes. Continue?',
    'zh-CN': 'IDE 控制通道不可用，关闭请求未发送。强制关闭可能丢失未保存的修改，是否继续？',
  },
  'ai.ide.closeControlUnavailable': {
    'en-US': 'The IDE control channel is unavailable; the close request was not sent.',
    'zh-CN': 'IDE 控制通道不可用，关闭请求未发送。',
  },
  'ai.ide.discardAndClose': {
    'en-US': 'Force Close',
    'zh-CN': '强制关闭',
  },
  'ai.ide.loading': {
    'en-US': 'Connecting to workspace IDE...',
    'zh-CN': '正在连接工作区 IDE...',
  },
  'ai.ide.error': {
    'en-US': 'Failed to open workspace IDE',
    'zh-CN': '打开工作区 IDE 失败',
  },
  'ai.ide.offline': {
    'en-US': 'Environment is currently offline',
    'zh-CN': '环境当前处于离线状态',
  },
  'ai.ide.offlineShort': {
    'en-US': 'Offline',
    'zh-CN': '离线',
  },
  'ai.ide.changeDirectoryFailed': {
    'en-US': 'Failed to change directory',
    'zh-CN': '切换目录失败',
  },
  'ai.ide.terminalFailed': {
    'en-US': 'Failed to create terminal',
    'zh-CN': '创建终端失败',
  },
  'ai.environment.ide.listLabel': {
    'en-US': 'Environments',
    'zh-CN': '环境列表',
  },
  'ai.environment.ide.open': {
    'en-US': 'Open VS Code in {{name}}',
    'zh-CN': '在 {{name}} 打开 VS Code',
  },
  'ai.environment.status.ready': {
    'en-US': 'Ready',
    'zh-CN': '就绪',
  },
  'ai.environment.status.connecting': {
    'en-US': 'Connecting',
    'zh-CN': '连接中',
  },
  'ai.environment.status.offline': {
    'en-US': 'Offline',
    'zh-CN': '离线',
  },
  'ai.environment.status.unavailable': {
    'en-US': 'Unavailable',
    'zh-CN': '不可用',
  },
  'ai.environment.searchLabel': {
    'en-US': 'Search environments',
    'zh-CN': '搜索环境',
  },
  'ai.environment.searchPlaceholder': {
    'en-US': 'Search environment name or ID',
    'zh-CN': '搜索环境名称或 ID',
  },
  'ai.environment.empty.noMatch': {
    'en-US': 'No matching environments',
    'zh-CN': '暂无匹配环境',
  },
  'ai.environment.loading': {
    'en-US': 'Loading Environments',
    'zh-CN': '正在加载环境',
  },
  'ai.environment.loadFailed': {
    'en-US': 'Failed to load environments',
    'zh-CN': '加载环境失败',
  },
  'ai.environment.refreshFailed': {
    'en-US': 'Refresh failed',
    'zh-CN': '刷新失败',
  },
  'ai.environment.refreshStale': {
    'en-US': 'Refresh failed · Showing last data',
    'zh-CN': '刷新失败 · 显示上次数据',
  },
  'ai.environment.diagnostics': {
    'en-US': 'Diagnostics',
    'zh-CN': '诊断详情',
  },
  'ai.environment.lastDaemonVersion': {
    'en-US': 'Last Daemon version',
    'zh-CN': '上次 Daemon 版本',
  },
  'ai.environment.create': {
    'en-US': 'New Environment',
    'zh-CN': '新建环境',
  },
  'ai.environment.createDescription': {
    'en-US': 'Register execution environment and capabilities',
    'zh-CN': '注册执行环境与运行时能力',
  },
  'ai.environment.name': {
    'en-US': 'Environment Name',
    'zh-CN': '环境名称',
  },
  'ai.environment.namePlaceholder': {
    'en-US': 'e.g. local-dev',
    'zh-CN': '如 local-dev',
  },
  'ai.environment.rotateToken': {
    'en-US': 'Rotate Token',
    'zh-CN': '重新生成 Token',
  },
  'ai.environment.rotateTokenConfirm': {
    'en-US': 'Rotate the registration token for "{{name}}"? Existing connections stay online; the next connection must use the new token.',
    'zh-CN': '确认为环境「{{name}}」重新生成 Token？已有连接保持在线，下一次连接必须使用新 Token。',
  },
  'ai.environment.delete': {
    'en-US': 'Delete Environment',
    'zh-CN': '删除环境',
  },
  'ai.environment.deleteConfirm': {
    'en-US': 'Are you sure you want to delete environment "{{name}}"?',
    'zh-CN': '确认删除环境「{{name}}」？',
  },
  'ai.environment.install': {
    'en-US': "Install / overwrite",
    'zh-CN': "安装 / 覆盖",
  },
  'ai.environment.uninstall': {
    'en-US': "Uninstall",
    'zh-CN': "卸载",
  },
  'ai.environment.install.os': {
    'en-US': "Operating system",
    'zh-CN': "操作系统",
  },
  'ai.environment.install.title': {
    'en-US': 'Install/overwrite environment',
    'zh-CN': '安装/覆盖环境',
  },
  'ai.environment.install.uninstallTitle': {
    'en-US': 'Uninstall environment',
    'zh-CN': '卸载环境',
  },
  'ai.environment.install.copyInstall': {
    'en-US': "Copy installation command",
    'zh-CN': "复制安装命令",
  },
  'ai.environment.install.copyUninstall': {
    'en-US': "Copy uninstall command",
    'zh-CN': "复制卸载命令",
  },
  'ai.environment.install.notice': {
    'en-US': 'Choose the target operating system, copy the command and run it in a terminal on the target host.',
    'zh-CN': '选择目标操作系统，复制命令后，在目标主机的终端执行。',
  },
  'ai.environment.install.noticeDetail': {
    'en-US': 'The command downloads the matching official installer and starts this environment service. No JRE or Node installation is required; make sure Bash is available. Overwriting interrupts tool calls in progress; local configuration and data are kept.',
    'zh-CN': '命令会下载配套的官方安装器并启动该环境的服务。无需预装 JRE 或 Node；请确保 Bash 可用。覆盖安装会中断当前工具调用；本地配置和数据保留。',
  },
  'ai.environment.install.credentialNote': {
    'en-US': 'The command contains a one-time credential. Do not share it.',
    'zh-CN': '命令含一次性凭据，请勿分享。',
  },
  'ai.environment.install.uninstallNotice': {
    'en-US': 'Run the command on the host where this environment is installed. The service and program for this environment are removed, while local configuration, credentials and data are kept. The environment card in Studio is not deleted.',
    'zh-CN': '在安装该环境的主机上执行命令。该环境的服务与程序会被移除，本地配置、凭据和数据保留；Studio 中的环境卡片不会删除。',
  },
  'ai.environment.install.installCopied': {
    'en-US': 'Installation command copied. Valid for 5 minutes.',
    'zh-CN': '安装命令已复制，5 分钟内有效。',
  },
  'ai.environment.install.uninstallCopied': {
    'en-US': 'Uninstall command copied.',
    'zh-CN': '卸载命令已复制。',
  },
  'ai.environment.install.clipboardFailed': {
    'en-US': "Copy failed. Check clipboard permissions and retry.",
    'zh-CN': "复制失败，请检查剪贴板权限后重试。",
  },
  'ai.environment.config.title': {
    'en-US': 'Configuration',
    'zh-CN': '配置',
  },
  'ai.environment.config.status': {
    'en-US': 'Configuration status',
    'zh-CN': '配置状态',
  },
  'ai.environment.config.cardVersion': {
    'en-US': 'Card version',
    'zh-CN': '卡片版本',
  },
  'ai.environment.config.appliedVersion': {
    'en-US': 'Applied version',
    'zh-CN': '已应用版本',
  },
  'ai.environment.config.proxy': {
    'en-US': 'Daemon proxy',
    'zh-CN': 'Daemon 代理',
  },
  'ai.environment.config.proxyDirect': {
    'en-US': 'Direct',
    'zh-CN': '直连',
  },
  'ai.environment.config.error': {
    'en-US': 'Application error',
    'zh-CN': '应用错误',
  },
  'ai.environment.config.hotUpdateHelp': {
    'en-US': 'Saving a card configuration is applied by the current Daemon without reinstalling or restarting it. The card shows the version the running process has actually applied.',
    'zh-CN': '保存卡片配置后由当前 Daemon 热应用，无需重新安装或重启。卡片展示当前进程实际已应用的版本。',
  },
  'ai.environment.config.state.applied': {
    'en-US': 'Applied',
    'zh-CN': '已应用',
  },
  'ai.environment.config.state.pendingApply': {
    'en-US': 'Pending apply',
    'zh-CN': '待应用',
  },
  'ai.environment.config.state.pendingConnect': {
    'en-US': 'Pending connection',
    'zh-CN': '待连接',
  },
  'ai.environment.config.state.failed': {
    'en-US': 'Apply failed',
    'zh-CN': '应用失败',
  },
  'ai.environment.config.state.unknown': {
    'en-US': 'Unknown',
    'zh-CN': '未知',
  },
  'ai.environment.form.edit': {
    'en-US': 'Edit configuration',
    'zh-CN': '编辑配置',
  },
  'ai.environment.form.editTitle': {
    'en-US': 'Edit environment: {{name}}',
    'zh-CN': '编辑环境：{{name}}',
  },
  'ai.environment.form.nameImmutable': {
    'en-US': 'The name is the stable identity and cannot be changed after creation.',
    'zh-CN': '名称是稳定身份，创建后不可修改。',
  },
  'ai.environment.form.note': {
    'en-US': 'Note',
    'zh-CN': '备注',
  },
  'ai.environment.form.notePlaceholder': {
    'en-US': 'e.g. Development workstation',
    'zh-CN': '例如：开发工作站',
  },
  'ai.environment.form.studioUrl': {
    'en-US': 'Studio address',
    'zh-CN': 'Studio 地址',
  },
  'ai.environment.form.studioUrlPlaceholder': {
    'en-US': 'https://studio.example.com',
    'zh-CN': 'https://studio.example.com',
  },
  'ai.environment.form.studioUrlHelp': {
    'en-US': 'HTTP(S) origin used by the Daemon to connect to this Studio; no path, query or credentials.',
    'zh-CN': 'Daemon 连接此 Studio 使用的 HTTP(S) 源地址；不含路径、查询参数或凭据。',
  },
  'ai.environment.form.bashExecutable': {
    'en-US': 'Bash executable',
    'zh-CN': 'Bash 可执行文件',
  },
  'ai.environment.form.bashHelp': {
    'en-US': 'Leave empty to use bash from the host PATH.',
    'zh-CN': '留空则使用宿主 PATH 中的 bash。',
  },
  'ai.environment.form.lsp': {
    'en-US': 'Enable LSP servers',
    'zh-CN': '启用 LSP 服务器',
  },
  'ai.environment.form.lspHelp': {
    'en-US': 'Declare installed language servers. Command and at least one dotted file extension are required; root markers and first-match markers are optional.',
    'zh-CN': '声明已安装的语言服务器。命令和至少一个带点的文件后缀必填；根标记与首个匹配标记可选。',
  },
  'ai.environment.form.proxyUrl': {
    'en-US': 'Daemon proxy',
    'zh-CN': 'Daemon 代理',
  },
  'ai.environment.form.proxyPlaceholder': {
    'en-US': 'http://127.0.0.1:7890',
    'zh-CN': 'http://127.0.0.1:7890',
  },
  'ai.environment.form.proxyHelp': {
    'en-US': 'Leave empty for a direct connection. Only an unauthenticated http://host:port proxy without path, query or fragment is accepted.',
    'zh-CN': '留空表示直连。仅支持无认证的 http://host:port 代理，不接受路径、查询参数或片段。',
  },
  'ai.environment.form.applyNotice': {
    'en-US': 'Saved configuration is applied without reinstalling the Daemon.',
    'zh-CN': '保存后的配置无需重装 Daemon 即可生效。',
  },
  'ai.environment.form.hotUpdateHelp': {
    'en-US': 'The running Daemon receives the complete configuration immediately. Bash, LSP and network components switch to the new values for subsequent calls; the installation directory, token and identity do not change.',
    'zh-CN': '运行中的 Daemon 会立即收到完整配置。Bash、LSP 与网络组件对后续调用使用新值；安装目录、凭据与身份不变。',
  },
  'ai.environment.form.save': {
    'en-US': 'Save configuration',
    'zh-CN': '保存配置',
  },
  'ai.environment.form.create': {
    'en-US': 'Create',
    'zh-CN': '创建',
  },
  'ai.environment.form.invalidName': {
    'en-US': 'The name must be nonblank, at most 64 characters, without a slash or surrounding whitespace.',
    'zh-CN': '名称必须非空、最多 64 字符，且不含斜杠或首尾空白。',
  },
  'ai.environment.form.invalidNote': {
    'en-US': 'The note must be a single nonblank line of at most 512 characters, or empty.',
    'zh-CN': '备注必须为单行、最多 512 字符，或留空。',
  },
  'ai.environment.form.invalidStudioUrl': {
    'en-US': 'Studio address is invalid: use an HTTP(S) origin without path, query or credentials.',
    'zh-CN': 'Studio 地址无效：请填写 HTTP(S) 源地址，不含路径、查询参数或凭据。',
  },
  'ai.environment.form.invalidBash': {
    'en-US': 'Bash executable is invalid: it must be nonblank without control characters.',
    'zh-CN': 'Bash 可执行文件无效：不能为空或包含控制字符。',
  },
  'ai.environment.form.invalidProxy': {
    'en-US': 'Daemon proxy is invalid: use http://host:port without credentials, path, query or fragment.',
    'zh-CN': 'Daemon 代理无效：请使用不含凭据、路径、查询参数或片段的 http://host:port。',
  },
  'ai.environment.form.invalidLsp': {
    'en-US': 'LSP servers are invalid: each server needs a valid ID, command and at least one dotted file extension; markers are optional.',
    'zh-CN': 'LSP 服务器配置无效：每个服务器需有合法 ID、命令和至少一个带点的文件后缀；标记可选。',
  },
  'ai.environment.form.invalidConfig': {
    'en-US': 'The Daemon configuration is invalid.',
    'zh-CN': 'Daemon 配置无效。',
  },
  'ai.environment.form.invalidField': {
    'en-US': 'Configuration is invalid: {{field}}',
    'zh-CN': '配置无效：{{field}}',
  },
  'ai.environment.close': {
    'en-US': 'Close',
    'zh-CN': '关闭',
  },
  'ai.environment.lastSeen': {
    'en-US': 'Last seen',
    'zh-CN': '最近活动',
  },
  'ai.environment.capabilities': {
    'en-US': 'Capabilities',
    'zh-CN': '能力',
  },
  'ai.environment.userName': {
    'en-US': 'Process User',
    'zh-CN': '进程用户',
  },
  'ai.environment.daemonVersion': {
    'en-US': 'Daemon Version',
    'zh-CN': 'Daemon 版本',
  },
  'ai.environment.update': {
    'en-US': 'Update Daemon',
    'zh-CN': '更新 Daemon',
  },
  'ai.environment.updateConfirm': {
    'en-US': 'Update the Daemon for environment "{{name}}" to the latest release? The service will restart and reconnect.',
    'zh-CN': '确认将环境「{{name}}」的 Daemon 更新至最新发布版本？服务将重启并重新连接。',
  },
  'ai.environment.updateStatus': {
    'en-US': 'Update Status',
    'zh-CN': '更新状态',
  },
  'ai.environment.update.none': {
    'en-US': 'No update in progress',
    'zh-CN': '无进行中的更新',
  },
  'ai.environment.update.current': {
    'en-US': 'Daemon is already up to date',
    'zh-CN': 'Daemon 已是最新版本',
  },
  'ai.environment.update.sentUnconfirmed': {
    'en-US': 'Update request sent; awaiting confirmation',
    'zh-CN': '更新请求已发送，待确认',
  },
  'ai.environment.update.progressKnownTotal': {
    'en-US': 'Updating to {{targetVersion}} · {{downloaded}} / {{total}} ({{percent}}%)',
    'zh-CN': '正在更新至 {{targetVersion}} · {{downloaded}} / {{total}}（{{percent}}%）',
  },
  'ai.environment.update.progressUnknownTotal': {
    'en-US': 'Updating to {{targetVersion}} · {{downloaded}} downloaded · total size unknown',
    'zh-CN': '正在更新至 {{targetVersion}} · 已下载 {{downloaded}} · 总大小未知',
  },
  'ai.environment.manage': {
    'en-US': 'Manage',
    'zh-CN': '管理',
  },
  'ai.environment.managementTitle': {
    'en-US': 'Manage Environment: {{name}}',
    'zh-CN': '管理环境：{{name}}',
  },
  'ai.environment.runtime.title': {
    'en-US': 'Host Information',
    'zh-CN': '宿主信息',
  },
  'ai.environment.runtime.unavailable': {
    'en-US': 'Status unavailable until the Daemon reconnects',
    'zh-CN': '状态不可用，等待 Daemon 重连',
  },
  'ai.environment.runtime.loading': {
    'en-US': 'Loading runtime status',
    'zh-CN': '正在读取运行状态',
  },
  'ai.environment.runtime.loadFailed': {
    'en-US': 'Failed to read runtime status',
    'zh-CN': '运行状态读取失败',
  },
  'ai.environment.runtime.unreported': {
    'en-US': 'Not reported',
    'zh-CN': '未上报',
  },
  'ai.environment.runtime.daemonVersion': {
    'en-US': 'Daemon Version',
    'zh-CN': 'Daemon 版本',
  },
  'ai.environment.runtime.operatingSystem': {
    'en-US': 'Operating System',
    'zh-CN': '操作系统',
  },
  'ai.environment.runtime.timeZone': {
    'en-US': 'Timezone',
    'zh-CN': '时区',
  },
  'ai.environment.runtime.userName': {
    'en-US': 'Process User',
    'zh-CN': '进程用户',
  },
  'ai.environment.runtime.homeDirectory': {
    'en-US': 'Home Directory',
    'zh-CN': '用户主目录',
  },
  'ai.environment.runtime.note': {
    'en-US': 'Note',
    'zh-CN': '备注',
  },
  'ai.environment.events.title': {
    'en-US': 'Events',
    'zh-CN': '事件记录',
  },
  'ai.environment.events.empty': {
    'en-US': 'No events recorded',
    'zh-CN': '暂无事件记录',
  },
  'ai.environment.events.loading': {
    'en-US': 'Loading events',
    'zh-CN': '正在读取事件记录',
  },
  'ai.environment.events.loadFailed': {
    'en-US': 'Failed to load events',
    'zh-CN': '事件记录读取失败',
  },
  'ai.environment.events.level.info': {
    'en-US': 'Info',
    'zh-CN': '信息',
  },
  'ai.environment.events.level.warn': {
    'en-US': 'Warning',
    'zh-CN': '警告',
  },
  'ai.environment.events.message.CONNECTING': { 'en-US': 'Daemon connection accepted.', 'zh-CN': '已接受 Daemon 连接。' },
  'ai.environment.events.message.READY': { 'en-US': 'Environment is ready.', 'zh-CN': '环境已就绪。' },
  'ai.environment.events.message.DISCONNECTED': { 'en-US': 'Daemon connection lost.', 'zh-CN': 'Daemon 连接已断开。' },
  'ai.environment.events.message.released': { 'en-US': 'Daemon connection released.', 'zh-CN': '已释放 Daemon 连接。' },
  'ai.environment.events.message.UPDATE_FAILED': { 'en-US': 'Daemon update failed.', 'zh-CN': 'Daemon 更新失败。' },
  'ai.environment.installOverwrite': { 'en-US': 'Install / Overwrite', 'zh-CN': '安装 / 覆盖' },
  'ai.environment.events.type.connecting': {
    'en-US': 'Connecting',
    'zh-CN': '连接中',
  },
  'ai.environment.events.type.ready': {
    'en-US': 'Ready',
    'zh-CN': '已就绪',
  },
  'ai.environment.events.type.disconnected': {
    'en-US': 'Disconnected',
    'zh-CN': '已断开',
  },
  'ai.environment.events.type.updateFailed': {
    'en-US': 'Update failed',
    'zh-CN': '更新失败',
  },
} satisfies LocaleCatalog
