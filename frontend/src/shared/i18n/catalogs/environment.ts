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
  'ai.ide.selectChatEnvironment': {
    'en-US': 'Select an environment for this conversation first',
    'zh-CN': '请先为当前对话选择环境',
  },
  'ai.ide.chatEnvironmentUnavailable': {
    'en-US': 'The conversation environment is unavailable',
    'zh-CN': '当前对话的环境不可用',
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
  'ai.ide.reconnecting': {
    'en-US': 'Connection lost. Reconnecting; the IDE will resume automatically.',
    'zh-CN': '连接已断开，正在重连，IDE 将自动恢复。',
  },
  'ai.ide.storageUnavailable': {
    'en-US': 'Browser storage is unavailable; IDE recovery and last-used state will not persist.',
    'zh-CN': '浏览器存储不可用，IDE 恢复信息与最近使用状态将不会保留。',
  },
  'ai.ide.claimUnknown': {
    'en-US': 'The IDE view result is unknown. Reconcile the original claim before continuing.',
    'zh-CN': 'IDE 视图结果未知，请先对账原 claim 再继续。',
  },
  'ai.ide.retryClaim': {
    'en-US': 'Reconcile',
    'zh-CN': '对账',
  },
  'ai.ide.viewGone': {
    'en-US': 'The IDE view is gone and was not recreated automatically.',
    'zh-CN': 'IDE 视图已不存在，未自动创建替代视图。',
  },
  'ai.ide.viewInUse': {
    'en-US': 'This IDE view is still held by another tab or an unreleased connection. The old tab may still be active. Wait for it to release, then retry resume, or open a new view.',
    'zh-CN': '该 IDE 视图仍被其他标签页或尚未释放的连接占用，旧标签页可能仍在使用。请等待释放后重试恢复，或打开新视图。',
  },
  'ai.ide.retryResume': {
    'en-US': 'Retry Resume',
    'zh-CN': '重试恢复',
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
  'ai.ide.dirtyDiscardTitle': {
    'en-US': 'Force close IDE view?',
    'zh-CN': '强制关闭 IDE 视图？',
  },
  'ai.ide.dirtyDiscardMessage': {
    'en-US': 'The IDE state is unknown or unreachable. Force closing may discard unsaved changes. Continue?',
    'zh-CN': 'IDE 状态未知或无法连接。强制关闭可能丢失未保存的修改，是否继续？',
  },
  'ai.ide.discardAndClose': {
    'en-US': 'Force Close',
    'zh-CN': '强制关闭',
  },
  'ai.ide.openTerminal': {
    'en-US': 'Open Terminal',
    'zh-CN': '打开终端',
  },
  'ai.ide.openDirectory': {
    'en-US': 'Open Directory',
    'zh-CN': '打开目录',
  },
  'ai.ide.copyDirectory': {
    'en-US': 'Copy directory path',
    'zh-CN': '复制目录路径',
  },
  'ai.ide.directoryCopied': {
    'en-US': 'Directory path copied',
    'zh-CN': '目录路径已复制',
  },
  'ai.ide.directoryCopyFailed': {
    'en-US': 'Could not copy the directory path',
    'zh-CN': '无法复制目录路径',
  },
  'ai.ide.openDirectoryTitle': {
    'en-US': 'Open Host Directory',
    'zh-CN': '打开宿主目录',
  },
  'ai.ide.parentDirectory': {
    'en-US': 'Parent directory',
    'zh-CN': '上级目录',
  },
  'ai.ide.recentDirectories': {
    'en-US': 'Recently opened',
    'zh-CN': '最近打开',
  },
  'ai.ide.directoryTree': {
    'en-US': 'Directory tree',
    'zh-CN': '目录树',
  },
  'ai.ide.expandDirectory': {
    'en-US': 'Expand {{name}}',
    'zh-CN': '展开 {{name}}',
  },
  'ai.ide.collapseDirectory': {
    'en-US': 'Collapse {{name}}',
    'zh-CN': '收起 {{name}}',
  },
  'ai.ide.directoryEmpty': {
    'en-US': 'No subdirectories',
    'zh-CN': '没有子目录',
  },
  'ai.ide.directoryTruncated': {
    'en-US': 'Showing the first {{count}} entries',
    'zh-CN': '仅显示前 {{count}} 项',
  },
  'ai.ide.directoryLoadFailed': {
    'en-US': 'Failed to load the directory',
    'zh-CN': '目录加载失败',
  },
  'ai.ide.directoryHelpLabel': {
    'en-US': 'Directory picker help',
    'zh-CN': '目录选择帮助',
  },
  'ai.ide.directoryHelp': {
    'en-US': 'Folders list their subdirectories only when you expand them. Use Parent directory to walk back up to the root.',
    'zh-CN': '只有展开目录时才读取它的直接子目录；用“上级目录”可以逐级返回到根目录。',
  },
  'ai.ide.selectedDirectory': {
    'en-US': 'Switch to',
    'zh-CN': '将切换到',
  },
  'ai.ide.confirm': {
    'en-US': 'Confirm',
    'zh-CN': '确认',
  },
  'ai.ide.cancel': {
    'en-US': 'Cancel',
    'zh-CN': '取消',
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
  'ai.ide.noEnvironments': {
    'en-US': 'No available environments',
    'zh-CN': '暂无可用环境',
  },
  'ai.ide.missingEnvironment': {
    'en-US': 'Please select an environment to open IDE',
    'zh-CN': '请选择一个环境以打开 IDE',
  },
  'ai.ide.changeDirectoryFailed': {
    'en-US': 'Failed to change directory',
    'zh-CN': '切换目录失败',
  },
  'ai.ide.terminalFailed': {
    'en-US': 'Failed to create terminal',
    'zh-CN': '创建终端失败',
  },
  'ai.environment.loading': {
    'en-US': 'Loading Environments',
    'zh-CN': '正在加载 Environment',
  },
  'ai.environment.loadFailed': {
    'en-US': 'Failed to load environments',
    'zh-CN': '加载 Environment 失败',
  },
  'ai.environment.create': {
    'en-US': 'Create Environment',
    'zh-CN': '创建环境',
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
    'en-US': 'Rotate the registration token for "{name}"? Existing connections stay online; the next connection must use the new token.',
    'zh-CN': '确认为环境「{name}」重新生成 Token？已有连接保持在线，下一次连接必须使用新 Token。',
  },
  'ai.environment.delete': {
    'en-US': 'Delete Environment',
    'zh-CN': '删除环境',
  },
  'ai.environment.deleteConfirm': {
    'en-US': 'Are you sure you want to delete environment "{name}"?',
    'zh-CN': '确认删除环境「{name}」？',
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
  'ai.environment.install.origin': {
    'en-US': "Studio address",
    'zh-CN': "Studio 地址",
  },
  'ai.environment.install.title': {
    'en-US': 'Install/overwrite environment',
    'zh-CN': '安装/覆盖环境',
  },
  'ai.environment.install.uninstallTitle': {
    'en-US': 'Uninstall environment',
    'zh-CN': '卸载环境',
  },
  'ai.environment.install.originHelp': {
    'en-US': 'No path or query.',
    'zh-CN': '不含路径或查询参数。',
  },
  'ai.environment.install.originPlaceholder': {
    'en-US': 'https://studio.example.com',
    'zh-CN': 'https://studio.example.com',
  },
  'ai.environment.install.bash': {
    'en-US': "Bash executable",
    'zh-CN': "Bash 可执行文件",
  },
  'ai.environment.install.note': {
    'en-US': "Note",
    'zh-CN': "备注",
  },
  'ai.environment.install.notePlaceholder': {
    'en-US': 'e.g. Development workstation',
    'zh-CN': '例如：开发工作站',
  },
  'ai.environment.install.lsp': {
    'en-US': "Enable LSP servers",
    'zh-CN': "启用 LSP servers",
  },
  'ai.environment.install.lspHelp': {
    'en-US': 'Install the language server on the target host first.',
    'zh-CN': '请先在目标主机安装对应的语言服务器。',
  },
  'ai.environment.install.saveCopy': {
    'en-US': "Save and copy installation command",
    'zh-CN': "保存并复制安装命令",
  },
  'ai.environment.install.copyUninstall': {
    'en-US': "Copy uninstall command",
    'zh-CN': "复制卸载命令",
  },
  'ai.environment.install.notice': {
    'en-US': 'Copy the command and run it in a terminal on the target host.',
    'zh-CN': '复制命令后，在目标主机的终端执行。',
  },
  'ai.environment.install.noticeDetail': {
    'en-US': 'No JRE or Node installation is required; make sure Bash is available. Overwriting restarts the service and connects to this environment, interrupting tool calls in progress. Existing data is kept.',
    'zh-CN': '无需预装 JRE 或 Node；请确保 Bash 可用。覆盖安装会重启服务并连接到此环境，中断当前工具调用；已有数据保留。',
  },
  'ai.environment.install.credentialNote': {
    'en-US': 'The command contains credentials. Do not share it.',
    'zh-CN': '命令含凭据，请勿分享。',
  },
  'ai.environment.install.uninstallNotice': {
    'en-US': 'Run the command on the host you want to uninstall. The environment service is removed, and local configuration and data are kept. The environment record in Studio is not deleted.',
    'zh-CN': '在需要卸载的主机上执行命令。环境服务会被移除，本地配置和数据保留；Studio 中的环境记录不会删除。',
  },
  'ai.environment.install.savedCopied': {
    'en-US': 'Installation command copied. Valid for 5 minutes.',
    'zh-CN': '安装命令已复制，5分钟内有效。',
  },
  'ai.environment.install.savedCopyFailed': {
    'en-US': "Settings saved, but command generation or copying failed. Check your connection and clipboard permissions, then retry. Nothing has been deployed.",
    'zh-CN': "配置已保存，但命令生成或复制失败。请检查网络和剪贴板权限后重试。尚未部署。",
  },
  'ai.environment.install.uninstallCopied': {
    'en-US': 'Uninstall command copied.',
    'zh-CN': '卸载命令已复制。',
  },
  'ai.environment.install.clipboardFailed': {
    'en-US': "Copy failed. Check clipboard permissions and retry.",
    'zh-CN': "复制失败，请检查剪贴板权限后重试。",
  },
  'ai.environment.install.reload': {
    'en-US': "Reload",
    'zh-CN': "重新加载",
  },
  'ai.environment.install.useDefaults': {
    'en-US': "Use default settings",
    'zh-CN': "使用默认设置",
  },
  'ai.environment.install.metadataFailed': {
    'en-US': "Could not load environment settings: {{message}}",
    'zh-CN': "读取环境信息失败：{{message}}",
  },
  'ai.environment.install.invalidSaved': {
    'en-US': "Saved installation settings are invalid: {{reason}}",
    'zh-CN': "已保存的安装设置无效：{{reason}}",
  },
  'ai.environment.install.invalidOrigin': {
    'en-US': "Studio address is invalid: use an http(s) base host without path, query or userinfo.",
    'zh-CN': "Studio 地址无效：请填写 http(s) base 主机，不含路径、查询或用户信息。",
  },
  'ai.environment.install.invalidNote': {
    'en-US': "Note is invalid: a single nonblank line of at most 512 characters.",
    'zh-CN': "备注无效：单行、非空、最多 512 字符。",
  },
  'ai.environment.install.invalidBash': {
    'en-US': "Bash executable is invalid: it must be nonblank without control characters.",
    'zh-CN': "Bash 可执行文件无效：不能为空或包含控制字符。",
  },
  'ai.environment.install.invalidOs': {
    'en-US': "The operating system is invalid.",
    'zh-CN': "操作系统无效。",
  },
  'ai.environment.install.invalidField': {
    'en-US': "Installation setting is invalid: {{field}}",
    'zh-CN': "安装设置无效：{{field}}",
  },
  'ai.environment.install.invalidLsp': {
    'en-US': 'LSP servers are invalid: check server IDs, command, extensions and markers; extensions are required and must start with a dot.',
    'zh-CN': 'LSP servers 配置无效：请检查 server ID、command、extensions 和 markers；extensions 必填并以 . 开头。',
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
    'zh-CN': 'Capabilities',
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
    'en-US': 'Manage Environment',
    'zh-CN': '管理环境',
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
} satisfies LocaleCatalog
