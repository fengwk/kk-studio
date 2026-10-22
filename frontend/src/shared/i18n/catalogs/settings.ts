import type { LocaleCatalog } from '@/shared/i18n/types'

export const settingsCatalog = {
  'settings.loading': {
    'en-US': 'Loading settings…',
    'zh-CN': '正在加载设置…',
  },
  'settings.title': {
    'en-US': 'Settings',
    'zh-CN': '设置',
  },
  'settings.retry': {
    'en-US': 'Retry',
    'zh-CN': '重试',
  },
  'settings.save': {
    'en-US': 'Save',
    'zh-CN': '保存',
  },
  'settings.saving': {
    'en-US': 'Saving…',
    'zh-CN': '保存中…',
  },
  'settings.reset': {
    'en-US': 'Reset',
    'zh-CN': '重置',
  },
  'settings.unsavedChanges': {
    'en-US': 'Unsaved changes',
    'zh-CN': '有未保存的更改',
  },
  'settings.allSaved': {
    'en-US': 'All changes saved',
    'zh-CN': '已是最新',
  },
  'settings.tabs.ariaLabel': {
    'en-US': 'Settings sections',
    'zh-CN': '设置分区',
  },
  'settings.tabs.general': {
    'en-US': 'General',
    'zh-CN': '常规',
  },
  'settings.tabs.plugins': {
    'en-US': 'Plugins',
    'zh-CN': '插件',
  },
  'plugins.empty': {
    'en-US': 'No plugins installed',
    'zh-CN': '未安装任何插件',
  },
  'plugins.connect': {
    'en-US': 'Connect',
    'zh-CN': '连接',
  },
  'plugins.reconnect': {
    'en-US': 'Reconnect',
    'zh-CN': '重新连接',
  },
  'plugins.disconnect': {
    'en-US': 'Disconnect',
    'zh-CN': '断开连接',
  },
  'plugins.disconnectConfirm': {
    'en-US': 'Are you sure you want to disconnect plugin "{name}"? Active credentials will be deleted.',
    'zh-CN': '确认断开插件 “{name}” 的连接？当前凭据将被删除。',
  },
  'plugins.status.NOT_CONNECTED': {
    'en-US': 'Not Connected',
    'zh-CN': '未连接',
  },
  'plugins.status.CONNECTED': {
    'en-US': 'Connected',
    'zh-CN': '已连接',
  },
  'plugins.status.REFRESH_FAILED': {
    'en-US': 'Refresh Failed',
    'zh-CN': '刷新失败',
  },
  'plugins.status.REFRESH_UNCERTAIN': {
    'en-US': 'Refresh Uncertain (Reauth Required)',
    'zh-CN': '刷新状态不确定（需重新认证）',
  },
  'plugins.status.REAUTH_REQUIRED': {
    'en-US': 'Reauth Required',
    'zh-CN': '需重新认证',
  },
  'plugins.region': {
    'en-US': 'Region',
    'zh-CN': '区域',
  },
  'plugins.tokenExpiresAt': {
    'en-US': 'Token Expires At',
    'zh-CN': 'Token 到期时间',
  },
  'plugins.nextRefreshAt': {
    'en-US': 'Next Refresh At',
    'zh-CN': '下次刷新时间',
  },
  'plugins.lastRefreshedAt': {
    'en-US': 'Last Refreshed At',
    'zh-CN': '最近刷新时间',
  },
  'plugins.lastRefreshError': {
    'en-US': 'Last Error',
    'zh-CN': '最近错误',
  },
  'plugins.connectDialog.title': {
    'en-US': 'Connect Plugin: {{name}}',
    'zh-CN': '连接插件：{{name}}',
  },
  'plugins.connectDialog.step1': {
    'en-US': '1. Select Region and Open Login Page',
    'zh-CN': '1. 选择区域并打开登录页面',
  },
  'plugins.connectDialog.openLogin': {
    'en-US': 'Open Login Page',
    'zh-CN': '打开官方登录页面',
  },
  'plugins.connectDialog.step2': {
    'en-US': '2. Paste Callback Deep Link',
    'zh-CN': '2. 粘贴回调 Deep Link',
  },
  'plugins.connectDialog.callbackPlaceholder': {
    'en-US': 'Paste callback URL (e.g. minimax-cn://auth-callback?...)',
    'zh-CN': '粘贴回调地址（例如 minimax-cn://auth-callback?...）',
  },
  'plugins.connectDialog.complete': {
    'en-US': 'Complete Connection',
    'zh-CN': '完成连接',
  },
  'plugins.connectDialog.cancel': {
    'en-US': 'Cancel',
    'zh-CN': '取消',
  },
  'plugins.connectDialog.preparing': {
    'en-US': 'Opening login…',
    'zh-CN': '正在打开登录页面…',
  },
  'plugins.connectDialog.completing': {
    'en-US': 'Connecting…',
    'zh-CN': '正在连接…',
  },
  'plugins.connectDialog.beforeLoginHint': {
    'en-US': 'Open the login page first, then paste the callback link here.',
    'zh-CN': '先打开登录页面，再将回调链接粘贴到这里。',
  },
  'plugins.connectDialog.afterLoginHint': {
    'en-US': 'After login, copy the callback deep link from the browser and paste it here.',
    'zh-CN': '登录后，从浏览器复制回调 Deep Link 并粘贴到这里。',
  },
  'plugins.noRegionsAvailable': {
    'en-US': 'No regions available for this plugin',
    'zh-CN': '该插件无可用区域',
  },
  'plugins.configure': {
    'en-US': 'Configure',
    'zh-CN': '配置',
  },
  'plugins.configDialog.title': {
    'en-US': 'Configure Plugin: {{name}}',
    'zh-CN': '配置插件：{{name}}',
  },
  'plugins.configDialog.generalSettings': {
    'en-US': 'Configuration',
    'zh-CN': '普通配置',
  },
  'plugins.configDialog.secrets': {
    'en-US': 'Secrets',
    'zh-CN': '秘密配置',
  },
  'plugins.configDialog.jsonEditor': {
    'en-US': 'JSON Editor',
    'zh-CN': 'JSON 编辑',
  },
  'plugins.configDialog.formEditor': {
    'en-US': 'Form Editor',
    'zh-CN': '表单编辑',
  },
  'plugins.configDialog.save': {
    'en-US': 'Save Configuration',
    'zh-CN': '保存配置',
  },
  'plugins.configDialog.saving': {
    'en-US': 'Saving…',
    'zh-CN': '正在保存…',
  },
  'plugins.configDialog.cancel': {
    'en-US': 'Cancel',
    'zh-CN': '取消',
  },
  'plugins.configDialog.conflict': {
    'en-US': 'Configuration was modified by another operation (version conflict). Please refresh and review latest changes.',
    'zh-CN': '配置已被其他操作修改（版本冲突），请刷新并查看最新改动。',
  },
  'plugins.configDialog.unsupportedSchema': {
    'en-US': 'Schema contains unsupported keywords: {{error}}. Switched to JSON editor mode.',
    'zh-CN': 'Schema 包含不支持的关键字：{{error}}。已自动切换至 JSON 编辑模式。',
  },
  'plugins.configDialog.jsonSyntaxError': {
    'en-US': 'Invalid JSON format: {{error}}',
    'zh-CN': 'JSON 格式无效：{{error}}',
  },
  'plugins.secrets.configured': {
    'en-US': 'Configured',
    'zh-CN': '已配置',
  },
  'plugins.secrets.notConfigured': {
    'en-US': 'Not Configured',
    'zh-CN': '未配置',
  },
  'plugins.secrets.keep': {
    'en-US': 'Keep',
    'zh-CN': '保持不变',
  },
  'plugins.secrets.set': {
    'en-US': 'Set',
    'zh-CN': '设置新值',
  },
  'plugins.secrets.clear': {
    'en-US': 'Clear',
    'zh-CN': '清除',
  },
  'plugins.secrets.clearConfirmPrompt': {
    'en-US': 'Confirm clearing this secret upon save?',
    'zh-CN': '确认保存时清除此秘密凭据？',
  },
  'plugins.secrets.inputPlaceholder': {
    'en-US': 'Enter secret value',
    'zh-CN': '输入秘密值',
  },
  'plugins.configDialog.deepLinkSecretHint': {
    'en-US': 'Credentials for this plugin are managed via official login authorization.',
    'zh-CN': '此插件通过官方登录授权管理凭据。',
  },
  'plugins.configDialog.mustConfirmClear': {
    'en-US': 'You must explicitly confirm clearing secret "{{key}}".',
    'zh-CN': '必须确认清除秘密凭据 “{{key}}”。',
  },
  'plugins.configDialog.secretRequired': {
    'en-US': 'Secret "{{key}}" requires a non-empty value.',
    'zh-CN': '秘密凭据 “{{key}}” 需要非空值。',
  },
  'plugins.configDialog.missingRevision': {
    'en-US': 'Missing configuration revision. Configuration cannot be saved.',
    'zh-CN': '缺少配置修订版本，无法保存配置。',
  },
  'plugins.configDialog.invalidInteger': {
    'en-US': 'Invalid integer format.',
    'zh-CN': '无效的整数格式。',
  },
  'plugins.configDialog.invalidNumber': {
    'en-US': 'Invalid number format.',
    'zh-CN': '无效的数字格式。',
  },
  'plugins.configDialog.invalidContainerJson': {
    'en-US': 'Invalid JSON container: {{error}}',
    'zh-CN': '无效的 JSON 容器格式：{{error}}',
  },
  'plugins.configDialog.fileReadPending': {
    'en-US': 'Reading the imported file; saving is unavailable until it finishes',
    'zh-CN': '正在读取导入的文件，读取完成前无法保存',
  },
  'plugins.configDialog.resolveFormDraftErrors': {
    'en-US': 'Resolve or explicitly discard the draft errors in the form before switching editors',
    'zh-CN': '请先修复或显式丢弃表单中的草稿错误，再切换编辑器',
  },
  'plugins.configDialog.resolveArrayDraftErrors': {
    'en-US': 'Resolve or explicitly discard the draft errors under {{name}} before switching editors',
    'zh-CN': '请先修复或显式丢弃 {{name}} 下的草稿错误，再切换编辑器',
  },
  'plugins.configDialog.valuesTooLarge': {
    'en-US': 'Configuration must not exceed {{max}} bytes (UTF-8)',
    'zh-CN': '普通配置不得超过 {{max}} 字节（UTF-8）',
  },
  'plugins.configDialog.toggleTextEditor': {
    'en-US': 'Multiline/JSON text editor for {{name}}',
    'zh-CN': '{{name}} 的多行/JSON 文本编辑器',
  },
  'plugins.configDialog.expandTextEditor': {
    'en-US': 'Multiline/JSON',
    'zh-CN': '多行/JSON',
  },
  'plugins.configDialog.collapseTextEditor': {
    'en-US': 'Collapse',
    'zh-CN': '收起',
  },
  'plugins.configDialog.validateJsonText': {
    'en-US': 'Validate as JSON',
    'zh-CN': '按 JSON 校验',
  },
  'plugins.configDialog.invalidJsonText': {
    'en-US': 'Invalid JSON content: {{error}}',
    'zh-CN': 'JSON 内容无效：{{error}}',
  },
  'plugins.configDialog.importJsonFile': {
    'en-US': 'Import JSON file',
    'zh-CN': '从文件导入 JSON',
  },
  'plugins.configDialog.importFileTooLarge': {
    'en-US': 'JSON file must not exceed {{max}} bytes',
    'zh-CN': 'JSON 文件不得超过 {{max}} 字节',
  },
  'plugins.configDialog.importFileNotUtf8': {
    'en-US': 'JSON file must be strict UTF-8',
    'zh-CN': 'JSON 文件必须是严格 UTF-8',
  },
  'plugins.configDialog.importFileInvalidJson': {
    'en-US': 'Invalid JSON file syntax: {{error}}',
    'zh-CN': 'JSON 文件语法无效：{{error}}',
  },
  'plugins.configDialog.importFileReadFailed': {
    'en-US': 'Failed to read the JSON file',
    'zh-CN': '读取 JSON 文件失败',
  },
  'plugins.configDialog.discardDraft': {
    'en-US': 'Discard draft for {{name}}',
    'zh-CN': '丢弃 {{name}} 的草稿',
  },
  'plugins.configDialog.discardDraftLabel': {
    'en-US': 'Discard draft',
    'zh-CN': '丢弃草稿',
  },
  'plugins.configDialog.unsetOptionalField': {
    'en-US': 'Unset {{name}}',
    'zh-CN': '未设置 {{name}}',
  },
  'plugins.configDialog.unsetOptionalFieldLabel': {
    'en-US': 'Unset',
    'zh-CN': '未设置',
  },
  'plugins.configDialog.arrayAddItem': {
    'en-US': 'Add {{name}} item',
    'zh-CN': '添加 {{name}} 条目',
  },
  'plugins.configDialog.arrayRemoveItem': {
    'en-US': 'Remove {{name}}[{{index}}]',
    'zh-CN': '删除 {{name}}[{{index}}]',
  },
  'plugins.configDialog.arrayRemoveItemLabel': {
    'en-US': 'Remove',
    'zh-CN': '删除',
  },
  'plugins.configDialog.toggleArrayEditor': {
    'en-US': 'Toggle structured/Raw JSON for {{name}}',
    'zh-CN': '切换 {{name}} 的结构化/Raw JSON 编辑',
  },
  'plugins.configDialog.arrayStructuredMode': {
    'en-US': 'Structured',
    'zh-CN': '结构化编辑',
  },
  'plugins.configDialog.arrayRawMode': {
    'en-US': 'Raw JSON',
    'zh-CN': 'Raw JSON',
  },
  'plugins.configDialog.structureEditsBlocked': {
    'en-US': 'Resolve draft errors before removing {{name}} items',
    'zh-CN': '请先解决草稿错误，再删除 {{name}} 条目',
  },
  'plugins.desiredRevision': {
    'en-US': 'Desired Revision',
    'zh-CN': '期望修订',
  },
  'plugins.appliedRevision': {
    'en-US': 'Applied Revision',
    'zh-CN': '已应用修订',
  },
  'plugins.applicationStatus': {
    'en-US': 'Application Status',
    'zh-CN': '应用状态',
  },
  'plugins.applicationStatus.applied': {
    'en-US': 'Applied',
    'zh-CN': '已生效',
  },
  'plugins.applicationStatus.pending': {
    'en-US': 'Pending Application',
    'zh-CN': '待应用',
  },
  'plugins.capabilities': {
    'en-US': 'Active Capabilities',
    'zh-CN': '生效能力',
  },
  'plugins.capabilities.none': {
    'en-US': 'None',
    'zh-CN': '无',
  },
  'plugins.connectionCheck': {
    'en-US': 'Check Connection',
    'zh-CN': '检查连接',
  },
  'plugins.connectionCheck.blockedHint': {
    'en-US': 'This node has not applied the current configuration yet; check after it is applied.',
    'zh-CN': '本节点尚未应用当前配置，应用完成后再检查。',
  },
  'plugins.connectionCheck.pending': {
    'en-US': 'Checking…',
    'zh-CN': '检查中…',
  },
  'plugins.connectionCheck.title': {
    'en-US': 'Connection Check: {{name}}',
    'zh-CN': '连接检查：{{name}}',
  },
  'plugins.connectionCheck.description': {
    'en-US':
      'Runs one read-only probe against the remote service. It does not change configuration or credentials, and it never repeats on its own.',
    'zh-CN': '仅对远端服务执行一次只读探测，不修改配置与凭据，也不会自行重复检查。',
  },
  'plugins.connectionCheck.coordinates': {
    'en-US': 'Applied Revision',
    'zh-CN': '已应用修订',
  },
  'plugins.connectionCheck.checkedAt': {
    'en-US': 'Checked At',
    'zh-CN': '检查时刻',
  },
  'plugins.connectionCheck.reachability': {
    'en-US': 'Reachability',
    'zh-CN': '网络可达性',
  },
  'plugins.connectionCheck.authentication': {
    'en-US': 'Authentication',
    'zh-CN': '认证结论',
  },
  'plugins.connectionCheck.browserSession': {
    'en-US': 'Browser Session',
    'zh-CN': '浏览器会话',
  },
  'plugins.connectionCheck.reachability.REACHABLE': {
    'en-US': 'Reachable',
    'zh-CN': '可达',
  },
  'plugins.connectionCheck.reachability.UNREACHABLE': {
    'en-US': 'Unreachable',
    'zh-CN': '不可达',
  },
  'plugins.connectionCheck.reachability.NOT_CHECKED': {
    'en-US': 'Not checked',
    'zh-CN': '未检查',
  },
  'plugins.connectionCheck.reachability.INDETERMINATE': {
    'en-US': 'Indeterminate',
    'zh-CN': '无法判定',
  },
  'plugins.connectionCheck.authentication.ACCEPTED': {
    'en-US': 'Credentials accepted',
    'zh-CN': '凭据已接受',
  },
  'plugins.connectionCheck.authentication.REJECTED': {
    'en-US': 'Credentials rejected',
    'zh-CN': '凭据被拒绝',
  },
  'plugins.connectionCheck.authentication.UNAVAILABLE': {
    'en-US': 'No local credentials',
    'zh-CN': '本地无可用凭据',
  },
  'plugins.connectionCheck.authentication.NOT_CHECKED': {
    'en-US': 'Not checked',
    'zh-CN': '未检查',
  },
  'plugins.connectionCheck.browserSession.UNKNOWN': {
    'en-US': 'Unknown (this check does not verify browser sign-in)',
    'zh-CN': '未知（本检查不核验浏览器登录态）',
  },
  'plugins.connectionCheck.mismatch': {
    'en-US': 'The result does not match this request and was discarded.',
    'zh-CN': '结果与本次请求不一致，已丢弃。',
  },
  'plugins.connectionCheck.conflict': {
    'en-US': 'The configuration changed. Refresh the list and check again.',
    'zh-CN': '配置已改变，请刷新列表后重新检查。',
  },
  'plugins.connectionCheck.failed': {
    'en-US': 'Connection check failed.',
    'zh-CN': '连接检查失败。',
  },
  'plugins.connectionCheck.stale': {
    'en-US': 'The configuration changed after this check; the result no longer matches the current one.',
    'zh-CN': '此后配置已改变，本次结果已不再对应当前配置。',
  },
  'settings.tabs.aiRuntime': {
    'en-US': 'AI Runtime',
    'zh-CN': 'AI 运行时',
  },
  'settings.tabs.toolsPermissions': {
    'en-US': 'Tools & Permissions',
    'zh-CN': '工具与权限',
  },
  'settings.tabs.environment': {
    'en-US': 'Environment',
    'zh-CN': '环境',
  },
  'settings.tabs.network': {
    'en-US': 'Network',
    'zh-CN': '网络',
  },
  'settings.section.network.description': {
    'en-US': 'Configure the single global default HTTP proxy for Backend.',
    'zh-CN': '配置 Backend 单一全局默认 HTTP 代理。',
  },
  'settings.section.network.proxy.title': {
    'en-US': 'Global HTTP proxy',
    'zh-CN': '全局 HTTP 代理',
  },
  'settings.section.network.proxy.description': {
    'en-US': 'New HTTP requests use the saved proxy; in-flight requests are unaffected. This does not control daemon, browser, or internal requests of services such as Hub.',
    'zh-CN': '保存后新发起的 HTTP 请求使用该代理，正在执行的请求不受影响；不控制 daemon、浏览器或 Hub 等服务内部请求。',
  },
  'settings.field.network.proxyUrl': {
    'en-US': 'Proxy URL',
    'zh-CN': '代理地址',
  },
  'settings.field.network.proxyUrl.hint': {
    'en-US': 'Backend global default HTTP proxy. Supports http://host:port without authentication; HTTPS targets use CONNECT. Leave blank to force direct connections. New HTTP requests use the saved proxy while in-flight requests continue unaffected; this does not control daemon, browser, or internal requests of services such as Hub.',
    'zh-CN': 'Backend 全局默认 HTTP 代理，支持无认证的 http://host:port，HTTPS 目标通过 CONNECT；留空强制直连。保存后新发起的 HTTP 请求使用该代理，正在执行的请求不受影响；不控制 daemon、浏览器或 Hub 等服务内部请求。',
  },
  'settings.field.network.noProxyHosts': {
    'en-US': 'Proxy bypass rules',
    'zh-CN': '代理绕过规则',
  },
  'settings.field.network.noProxyHosts.hint': {
    'en-US': 'Comma-separated rules, e.g. localhost,127.*,::1,192.168.0.0/16,minio,*.internal. IPv4/IPv6 CIDR matches only the target URL numeric IP, not resolved hostnames.',
    'zh-CN': '绕过规则以逗号分隔，例如 localhost,127.*,::1,192.168.0.0/16,minio,*.internal；IPv4/IPv6 CIDR 只匹配目标 URL 中的数值 IP，不解析主机名。',
  },
  'settings.tabs.storageMedia': {
    'en-US': 'Storage & Media',
    'zh-CN': '存储与媒体',
  },
  'settings.tabs.advanced': {
    'en-US': 'Advanced',
    'zh-CN': '高级',
  },
  'settings.toolbar.ariaLabel': {
    'en-US': 'Settings save toolbar',
    'zh-CN': '设置保存工具条',
  },
  'settings.applyTiming.nextInvocation': {
    'en-US': 'Takes effect on next invocation',
    'zh-CN': '下次调用生效',
  },
  'settings.applyTiming.nextHttpRequest': {
    'en-US': 'Takes effect on new HTTP requests',
    'zh-CN': '新发起请求生效',
  },
  'settings.applyTiming.restart': {
    'en-US': 'Restart required',
    'zh-CN': '重启后生效',
  },
  'settings.error.load': {
    'en-US': 'Failed to load settings.',
    'zh-CN': '设置加载失败。',
  },
  'settings.error.saveFailed': {
    'en-US': 'Failed to save settings.',
    'zh-CN': '设置保存失败。',
  },
  'settings.error.schema': {
    'en-US': 'Unable to load the settings form.',
    'zh-CN': '无法加载设置表单。',
  },
  'settings.error.permissionToolNameRequired': {
    'en-US': 'Tool name is required.',
    'zh-CN': '工具名不能为空。',
  },
  'settings.error.permissionDuplicateToolName': {
    'en-US': 'Two permission groups share the same tool name after trimming; choose unique names.',
    'zh-CN': '两个权限分组在去空格后使用了相同的工具名，请改为唯一名称。',
  },
  'settings.error.permissionPatternRequired': {
    'en-US': 'Pattern is required.',
    'zh-CN': '规则模式不能为空。',
  },
  'settings.error.numericFieldRequired': {
    'en-US': 'A required numeric field is empty; fill it in before saving.',
    'zh-CN': '存在为空的必需数值字段，请填写后再保存。',
  },
  'settings.error.httpStatusNotInteger': {
    'en-US': 'HTTP status codes must be integers without decimals or empty entries.',
    'zh-CN': 'HTTP 状态码必须为整数，不能包含小数、非法字符或空条目。',
  },
  'settings.error.httpStatusOutOfRange': {
    'en-US': 'HTTP status codes must be between 400 and 599.',
    'zh-CN': 'HTTP 状态码必须在 400 至 599 之间。',
  },
  'settings.error.httpStatusDuplicate': {
    'en-US': 'HTTP status code list contains duplicate entries.',
    'zh-CN': 'HTTP 状态码列表中包含重复的状态码。',
  },

  // --- General / notifications / shortcuts ---
  'settings.notifications.title': {
    'en-US': 'Notifications',
    'zh-CN': '通知',
  },
  'settings.notifications.description': {
    'en-US': 'Receive browser notifications when a task completes or needs your approval.',
    'zh-CN': '任务完成或需要你审批时接收浏览器通知。',
  },
  'settings.notifications.enabled': {
    'en-US': 'Browser notifications',
    'zh-CN': '浏览器通知',
  },
  'settings.notifications.statusLabel': {
    'en-US': 'Status',
    'zh-CN': '状态',
  },
  'settings.notifications.status.enabled': {
    'en-US': 'On',
    'zh-CN': '已开启',
  },
  'settings.notifications.status.disabled': {
    'en-US': 'Off',
    'zh-CN': '已关闭',
  },
  'settings.notifications.browserPermissionLabel': {
    'en-US': 'Browser permission',
    'zh-CN': '浏览器权限',
  },
  'settings.notifications.permission.default': {
    'en-US': 'Not decided',
    'zh-CN': '未决定',
  },
  'settings.notifications.permission.granted': {
    'en-US': 'Granted',
    'zh-CN': '已允许',
  },
  'settings.notifications.permission.denied': {
    'en-US': 'Denied',
    'zh-CN': '已拒绝',
  },
  'settings.notifications.permission.unsupported': {
    'en-US': 'Unsupported',
    'zh-CN': '不支持',
  },
  'settings.notifications.defaultHint': {
    'en-US': 'Turning this on asks the browser for notification permission.',
    'zh-CN': '开启时会向浏览器申请通知权限。',
  },
  'settings.notifications.deniedHint': {
    'en-US':
      'Notifications are blocked. Allow this site to send notifications in your browser settings, then try again.',
    'zh-CN': '通知已被浏览器阻止。请在浏览器的站点设置中允许本站发送通知后重试。',
  },
  'settings.notifications.unsupportedHint': {
    'en-US': 'This browser does not support notifications, so this option cannot be enabled.',
    'zh-CN': '当前浏览器不支持通知，无法启用该选项。',
  },
  'settings.shortcuts.title': {
    'en-US': 'Keyboard shortcuts',
    'zh-CN': '键盘快捷键',
  },
  'settings.shortcuts.description': {
    'en-US': 'Read-only catalog of the keyboard shortcuts supported by this version.',
    'zh-CN': '本版本支持的键盘快捷键只读目录。',
  },

  // --- AI Runtime ---
  'settings.section.aiRuntime.description': {
    'en-US': 'Shared invocation retry, automatic compaction and subagent budgets.',
    'zh-CN': '共享调用重试、自动压缩与子代理预算。',
  },
  'settings.section.aiRuntime.retry.title': {
    'en-US': 'Invocation retry',
    'zh-CN': '调用重试',
  },
  'settings.section.aiRuntime.retry.description': {
    'en-US': 'Shared retry policy for model and tool invocations.',
    'zh-CN': '模型/工具调用的共享重试策略。',
  },
  'settings.section.aiRuntime.compaction.title': {
    'en-US': 'Automatic compaction',
    'zh-CN': '自动压缩',
  },
  'settings.section.aiRuntime.compaction.description': {
    'en-US': 'Token budget for automatic conversation compaction.',
    'zh-CN': '对话自动压缩的 token 预算。',
  },
  'settings.section.aiRuntime.subagent.title': {
    'en-US': 'Subagent budget',
    'zh-CN': '子代理预算',
  },
  'settings.section.aiRuntime.subagent.description': {
    'en-US': 'Limits for delegated subagent tasks.',
    'zh-CN': '委派子代理任务的预算限制。',
  },
  'settings.field.aiRuntime.retryMaxRetries': {
    'en-US': 'Max retries',
    'zh-CN': '最大重试次数',
  },
  'settings.field.aiRuntime.retryMaxRetries.hint': {
    'en-US': 'Maximum number of automatic retries when a model or tool invocation fails. Set to 0 to disable automatic retries.',
    'zh-CN': '模型或工具调用失败后的最大自动重试次数。设为 0 表示失败后不自动重试。',
  },
  'settings.field.aiRuntime.retryBackoffStrategy': {
    'en-US': 'Backoff strategy',
    'zh-CN': '退避策略',
  },
  'settings.field.aiRuntime.retryBackoffStrategy.hint': {
    'en-US': 'Strategy used to calculate delay between retry attempts. Fixed uses constant delay, while exponential doubles delay with each attempt.',
    'zh-CN': '重试等待时间的计算策略。固定策略在各轮次使用相同延迟，指数策略随重试轮次按指数增长。',
  },
  'settings.field.aiRuntime.retryBaseDelayMillis': {
    'en-US': 'Base delay (ms)',
    'zh-CN': '基础延迟（毫秒）',
  },
  'settings.field.aiRuntime.retryBaseDelayMillis.hint': {
    'en-US': 'Base delay in milliseconds before retrying. Serves as the constant delay for fixed backoff or the initial delay for exponential backoff.',
    'zh-CN': '重试等待的基础延迟毫秒数。固定策略下为每次等待时长，指数策略下为首次重试的等待时长。',
  },
  'settings.field.aiRuntime.retryMaxDelayMillis': {
    'en-US': 'Max delay (ms)',
    'zh-CN': '最大延迟（毫秒）',
  },
  'settings.field.aiRuntime.retryMaxDelayMillis.hint': {
    'en-US': 'Upper limit in milliseconds for exponential backoff delay. Calculated delays exceeding this value will be clamped.',
    'zh-CN': '指数退避策略下的最大重试等待毫秒数。计算出的重试延迟超过此值时会被截断。',
  },
  'settings.field.aiRuntime.compactionKeepRecentTokens': {
    'en-US': 'Recent tokens to keep',
    'zh-CN': '保留最近 token',
  },
  'settings.field.aiRuntime.compactionKeepRecentTokens.hint': {
    'en-US': 'Token budget for recent context to preserve during automatic conversation compaction. Messages within this budget remain uncompressed.',
    'zh-CN': '对话自动压缩时保留的最近上下文 Token 预算。低于此预算的最新消息将被完整保留而不被压缩。',
  },
  'settings.field.aiRuntime.subagentMaxDepth': {
    'en-US': 'Max depth',
    'zh-CN': '最大嵌套深度',
  },
  'settings.field.aiRuntime.subagentMaxDepth.hint': {
    'en-US': 'Maximum nesting depth for recursive subagent delegation. Further delegations are blocked once this limit is reached.',
    'zh-CN': '允许子代理继续递归委派子代理的最大嵌套层级。达到上限后将禁止进一步向下委派。',
  },
  'settings.field.aiRuntime.subagentMaxConcurrency': {
    'en-US': 'Max concurrency',
    'zh-CN': '最大并发',
  },
  'settings.field.aiRuntime.subagentMaxConcurrency.hint': {
    'en-US': 'Maximum number of subagent tasks that can execute concurrently within a single conversation session.',
    'zh-CN': '单个会话内允许同时并行运行的子代理任务最大数量。',
  },
  'settings.field.aiRuntime.subagentMaxTotalConcurrency': {
    'en-US': 'Max total concurrency',
    'zh-CN': '全局最大并发',
  },
  'settings.field.aiRuntime.subagentMaxTotalConcurrency.hint': {
    'en-US': 'Global limit on concurrent subagents across all conversation sessions. Set to 0 for no global cap.',
    'zh-CN': '全系统所有会话中同时运行的子代理任务总数上限。设为 0 表示不设全局上限。',
  },
  'settings.field.aiRuntime.subagentMaxTurns': {
    'en-US': 'Max turns',
    'zh-CN': '最大轮数',
  },
  'settings.field.aiRuntime.subagentMaxTurns.hint': {
    'en-US': 'Soft interaction-turn budget for a subagent task. At the limit, the subagent is reminded to finish the current phase and report.',
    'zh-CN': '单个子代理任务的交互轮数软预算。达到上限后提醒子代理结束当前阶段并汇报。',
  },
  'settings.option.retryBackoff.fixed': {
    'en-US': 'Fixed',
    'zh-CN': '固定',
  },
  'settings.option.retryBackoff.exponential': {
    'en-US': 'Exponential',
    'zh-CN': '指数',
  },
  'settings.section.aiRuntime.httpErrors.title': {
    'en-US': 'HTTP error policy',
    'zh-CN': 'HTTP 错误策略',
  },
  'settings.section.aiRuntime.modelCatalog.title': {
    'en-US': 'Model catalog',
    'zh-CN': '模型目录',
  },
  'settings.section.aiRuntime.modelCatalog.description': {
    'en-US': 'Choose the source used to load the model catalog.',
    'zh-CN': '选择加载模型目录的数据来源。',
  },
  'settings.field.aiRuntime.modelCatalogSource': {
    'en-US': 'Model catalog source',
    'zh-CN': '模型目录来源',
  },
  'settings.field.aiRuntime.modelCatalogSource.hint': {
    'en-US': 'New catalog HTTP requests use the selected source after saving. No restart is required.',
    'zh-CN': '保存后新发起的目录 HTTP 请求使用所选来源，无需重启。',
  },
  'settings.option.modelCatalogSource.modelsDevV2': {
    'en-US': 'models.dev v2',
    'zh-CN': 'models.dev v2',
  },
  'settings.option.modelCatalogSource.modelsDevV1': {
    'en-US': 'models.dev v1 (legacy)',
    'zh-CN': 'models.dev v1（旧版）',
  },
  'settings.section.aiRuntime.httpErrors.description': {
    'en-US': 'Configure retry behavior for model HTTP errors. Takes effect on next invocation.',
    'zh-CN': '配置模型调用遇到 HTTP 错误时的重试行为。下次调用生效。',
  },
  'settings.field.aiRuntime.modelHttpRetryStatusCodes': {
    'en-US': 'Retry HTTP status codes',
    'zh-CN': '重试 HTTP 状态码',
  },
  'settings.field.aiRuntime.modelHttpRetryStatusCodes.hint': {
    'en-US': 'HTTP errors matching this list retry within existing attempt and backoff budgets. Unlisted HTTP errors fail directly. Takes effect on next invocation.',
    'zh-CN': '匹配此列表的 HTTP 错误在现有重试次数与退避预算内自动重试；未列出的 HTTP 错误直接失败。下次调用生效。',
  },

  // --- Tools & Permissions ---
  'settings.section.tool.description': {
    'en-US': 'Tool permission rules, default YOLO and gateway/skill loading budgets.',
    'zh-CN': '工具权限规则、默认 YOLO 与网关/技能加载预算。',
  },
  'settings.section.tool.permission.title': {
    'en-US': 'Permission rules',
    'zh-CN': '权限规则',
  },
  'settings.section.tool.permission.description': {
    'en-US': 'Per-tool ordered rules; the first matching rule wins. Changes apply to the next invocation.',
    'zh-CN': '按工具的保序规则，首个匹配项生效；修改对下一次调用立即生效。',
  },
  'settings.field.tool.permission.hint': {
    'en-US': 'Access control rules for tool execution. Rules are evaluated top-to-bottom in order; the first matching rule determines whether to allow, ask, or deny.',
    'zh-CN': '工具执行的访问控制规则列表。按顺序自上而下匹配，首条匹配的规则决定该工具是直接允许、弹出审批还是直接拒绝。',
  },
  'settings.section.tool.gateway.title': {
    'en-US': 'Tool gateway & skill loading',
    'zh-CN': '工具网关与技能加载',
  },
  'settings.section.tool.gateway.description': {
    'en-US': 'Retry and skill-load budgets. Applies to the next invocation.',
    'zh-CN': '重试与技能加载预算；对下一次调用立即生效。',
  },
  'settings.field.tool.modelGatewayBusyRetryMillis': {
    'en-US': 'Model gateway busy retry (ms)',
    'zh-CN': '模型网关繁忙重试（毫秒）',
  },
  'settings.field.tool.modelGatewayBusyRetryMillis.hint': {
    'en-US': 'Delay in milliseconds before retrying when the model gateway encounters provider busy or rate limit errors.',
    'zh-CN': '模型网关遇到提供商繁忙或限流时的重试等待毫秒数。',
  },
  'settings.field.tool.toolGatewayBusyRetryMillis': {
    'en-US': 'Tool gateway busy retry (ms)',
    'zh-CN': '工具网关繁忙重试（毫秒）',
  },
  'settings.field.tool.toolGatewayBusyRetryMillis.hint': {
    'en-US': 'Delay in milliseconds before retrying when the tool gateway encounters a busy host or tool service.',
    'zh-CN': '工具网关在外部工具或宿主繁忙时的重试等待毫秒数。',
  },
  'settings.field.tool.toolGatewayOverloadRetryMillis': {
    'en-US': 'Tool gateway overload retry (ms)',
    'zh-CN': '工具网关过载重试（毫秒）',
  },
  'settings.field.tool.toolGatewayOverloadRetryMillis.hint': {
    'en-US': 'Delay in milliseconds before retrying when the tool gateway encounters system overload.',
    'zh-CN': '工具网关在系统过载时的重试等待毫秒数。',
  },
  'settings.permission.empty': {
    'en-US': 'No permission rules configured.',
    'zh-CN': '尚未配置权限规则。',
  },
  'settings.permission.groupAriaLabel': {
    'en-US': 'Permission group {{tool}}',
    'zh-CN': '权限分组 {{tool}}',
  },
  'settings.permission.toolName': {
    'en-US': 'Tool',
    'zh-CN': '工具',
  },
  'settings.permission.removeTool': {
    'en-US': 'Remove tool {{tool}}',
    'zh-CN': '移除工具 {{tool}}',
  },
  'settings.permission.removeToolShort': {
    'en-US': 'Remove',
    'zh-CN': '移除',
  },
  'settings.permission.noRules': {
    'en-US': 'No rules for this tool yet.',
    'zh-CN': '该工具暂无规则。',
  },
  'settings.permission.patternPlaceholder': {
    'en-US': 'e.g. node_modules/**',
    'zh-CN': '例如 node_modules/**',
  },
  'settings.permission.patternAriaLabel': {
    'en-US': 'Pattern for rule {{index}}',
    'zh-CN': '第 {{index}} 条规则的模式',
  },
  'settings.permission.actionAriaLabel': {
    'en-US': 'Action for rule {{index}}',
    'zh-CN': '第 {{index}} 条规则的动作',
  },
  'settings.permission.moveUp': {
    'en-US': 'Move up',
    'zh-CN': '上移',
  },
  'settings.permission.moveDown': {
    'en-US': 'Move down',
    'zh-CN': '下移',
  },
  'settings.permission.removeRule': {
    'en-US': 'Remove rule {{index}}',
    'zh-CN': '移除第 {{index}} 条规则',
  },
  'settings.permission.addRule': {
    'en-US': 'Add rule',
    'zh-CN': '添加规则',
  },
  'settings.permission.addTool': {
    'en-US': 'Add tool',
    'zh-CN': '添加工具',
  },
  'settings.permission.action.allow': {
    'en-US': 'Allow',
    'zh-CN': '允许',
  },
  'settings.permission.action.ask': {
    'en-US': 'Ask',
    'zh-CN': '询问',
  },
  'settings.permission.action.deny': {
    'en-US': 'Deny',
    'zh-CN': '拒绝',
  },

  // --- Environment ---
  'settings.section.environment.description': {
    'en-US': 'Resource limits and timeouts for the daemon gateway.',
    'zh-CN': '守护网关的资源上限与超时。',
  },
  'settings.section.environment.runtime.title': {
    'en-US': 'Runtime budgets',
    'zh-CN': '运行预算',
  },
  'settings.section.environment.runtime.description': {
    'en-US': 'Heartbeat and resource limits. Applies to the next check or request.',
    'zh-CN': '心跳与资源上限；对下一次判定或请求立即生效。',
  },
  'settings.field.environment.maxResourceBytes': {
    'en-US': 'Max resource bytes',
    'zh-CN': '最大资源字节数',
  },
  'settings.field.environment.maxResourceBytes.hint': {
    'en-US': 'Maximum bytes allowed for a single Environment resource. Larger resource writes are rejected.',
    'zh-CN': '单个 Environment 资源允许的最大字节数。超出限制时将拒绝写入。',
  },
  'settings.field.environment.heartbeatTimeoutMillis': {
    'en-US': 'Heartbeat timeout (ms)',
    'zh-CN': '心跳超时（毫秒）',
  },
  'settings.field.environment.heartbeatTimeoutMillis.hint': {
    'en-US': 'Heartbeat timeout in milliseconds for runtime environments. Environments without heartbeats past this duration are marked disconnected.',
    'zh-CN': 'Environment 运行时的心跳超时毫秒数。超过该时间未收到心跳将被标记为失联。',
  },
  // --- Storage & Media ---
  'settings.section.storageMedia.description': {
    'en-US': 'Upload, S3 presigning and canvas media processing budgets.',
    'zh-CN': '上传、S3 预签名与 Canvas 媒体处理预算。',
  },
  'settings.section.storageMedia.upload.title': {
    'en-US': 'Uploads & S3 presigning',
    'zh-CN': '上传与 S3 预签名',
  },
  'settings.section.storageMedia.upload.description': {
    'en-US':
      'Upload lifetime and S3 presigned URL budgets. Upload lifetime applies on the next decision to existing unclaimed uploads, not only to new uploads.',
    'zh-CN':
      '上传有效期与 S3 预签名 URL 预算。上传有效期在下次判断时作用于存量未 claim 上传，而不只是新上传。',
  },
  'settings.section.storageMedia.canvasMedia.title': {
    'en-US': 'Canvas media processing',
    'zh-CN': '画布媒体处理',
  },
  'settings.section.storageMedia.canvasMedia.description': {
    'en-US': 'Timeout and thumbnail budgets for canvas media processing.',
    'zh-CN': '画布媒体处理的超时与缩略图预算。',
  },
  'settings.section.storageMedia.temporaryResource.title': {
    'en-US': 'Temporary resources',
    'zh-CN': '临时资源',
  },
  'settings.section.storageMedia.temporaryResource.description': {
    'en-US':
      'Retention and scan interval for controlled temporary tool workspaces. Changed values apply to not-yet-collected workspaces on the next scan.',
    'zh-CN':
      '受控工具临时工作区的保留期与扫描间隔。修改后的值在下次扫描时对尚未回收的工作区生效。',
  },
  'settings.field.storageMedia.uploadExpiresSeconds': {
    'en-US': 'Upload expiry (s)',
    'zh-CN': '上传有效期（秒）',
  },
  'settings.field.storageMedia.uploadExpiresSeconds.hint': {
    'en-US':
      'Lifetime in seconds of an upload from its creation. Shortening it immediately expires older unclaimed uploads on their next check; extending it keeps unclaimed uploads alive. Once cleanup has started it never revives.',
    'zh-CN':
      '上传自创建起的有效秒数。缩短后存量未 claim 上传会在下次判断时立即过期；延长则让未 claim 上传继续有效；清理一旦开始便不再复活。',
  },
  'settings.field.storageMedia.s3PresignDefaultExpiresSeconds': {
    'en-US': 'S3 presign default expiry (s)',
    'zh-CN': 'S3 预签名默认有效期（秒）',
  },
  'settings.field.storageMedia.s3PresignDefaultExpiresSeconds.hint': {
    'en-US': 'Default expiration duration in seconds for S3 presigned access and download URLs.',
    'zh-CN': '生成 S3 预签名下载与访问链接时的默认过期秒数。',
  },
  'settings.field.storageMedia.s3PresignMaxExpiresSeconds': {
    'en-US': 'S3 presign max expiry (s)',
    'zh-CN': 'S3 预签名最大有效期（秒）',
  },
  'settings.field.storageMedia.s3PresignMaxExpiresSeconds.hint': {
    'en-US': 'Maximum allowable expiration duration in seconds for S3 presigned URLs.',
    'zh-CN': '允许请求 S3 预签名链接的最大过期秒数上限。',
  },
  'settings.field.storageMedia.canvasMediaProcessTimeoutMillis': {
    'en-US': 'Media process timeout (ms)',
    'zh-CN': '媒体处理超时（毫秒）',
  },
  'settings.field.storageMedia.canvasMediaProcessTimeoutMillis.hint': {
    'en-US': 'Timeout in milliseconds for processing and transcoding canvas audio, video, and image media.',
    'zh-CN': '画布中对音视频、图片等多媒体素材进行转码与处理的超时毫秒数。',
  },
  'settings.field.storageMedia.thumbnailMaxDimension': {
    'en-US': 'Thumbnail max dimension',
    'zh-CN': '缩略图最大边长',
  },
  'settings.field.storageMedia.thumbnailMaxDimension.hint': {
    'en-US': 'Maximum pixel dimension for the longer edge when generating image and video thumbnails.',
    'zh-CN': '系统生成图片与视频缩略图时的长边最大像素尺寸。',
  },
  'settings.field.storageMedia.thumbnailQuality': {
    'en-US': 'Thumbnail quality',
    'zh-CN': '缩略图质量',
  },
  'settings.field.storageMedia.thumbnailQuality.hint': {
    'en-US': 'JPEG compression quality (1-100) for generated thumbnails. Higher values improve quality with larger file sizes.',
    'zh-CN': '缩略图生成的 JPEG 压缩质量（1-100）。数值越高画质越好但体积越大。',
  },
  'settings.field.storageMedia.temporaryResourceTtlSeconds': {
    'en-US': 'Temporary resource retention (s)',
    'zh-CN': '临时资源保留期（秒）',
  },
  'settings.field.storageMedia.temporaryResourceTtlSeconds.hint': {
    'en-US':
      'Lifetime in seconds of a controlled temporary tool workspace from its recorded creation. Shortening it collects older workspaces on their next scan; workspaces still in use are never collected.',
    'zh-CN':
      '受控工具临时工作区自登记创建起的保留秒数。缩短后较旧工作区会在下次扫描时被回收；仍在使用的不会回收。',
  },
  'settings.field.storageMedia.temporaryResourceCleanupIntervalSeconds': {
    'en-US': 'Temporary resource scan interval (s)',
    'zh-CN': '临时资源扫描间隔（秒）',
  },
  'settings.field.storageMedia.temporaryResourceCleanupIntervalSeconds.hint': {
    'en-US': 'Interval in seconds between controlled temporary workspace cleanup scans.',
    'zh-CN': '受控临时工作区清理扫描之间的间隔秒数。',
  },
  // --- Advanced ---
  'settings.section.advanced.description': {
    'en-US': 'Process-level runtime budgets for processors, events and work notification.',
    'zh-CN': '处理器、事件与工作通知的进程级运行预算。',
  },
  'settings.section.advanced.resource.title': {
    'en-US': 'Resource budget',
    'zh-CN': '资源预算',
  },
  'settings.section.advanced.resource.description': {
    'en-US': 'Per-request resource budgets.',
    'zh-CN': '单次请求的资源预算。',
  },
  'settings.section.advanced.processor.title': {
    'en-US': 'Processor',
    'zh-CN': '处理器',
  },
  'settings.section.advanced.processor.description': {
    'en-US': 'Lease, heartbeat, resolution failure and dispatch fallback budgets for processors.',
    'zh-CN': '处理器的租约、心跳、解析失败与分发回退预算。',
  },
  'settings.section.advanced.applicationEvent.title': {
    'en-US': 'Application events',
    'zh-CN': '应用事件',
  },
  'settings.section.advanced.applicationEvent.description': {
    'en-US': 'Application event channel budgets.',
    'zh-CN': '应用事件通道预算。',
  },
  'settings.section.advanced.workNotification.title': {
    'en-US': 'Work notification',
    'zh-CN': '工作通知',
  },
  'settings.section.advanced.workNotification.description': {
    'en-US': 'Work notification polling and reconnection budgets.',
    'zh-CN': '工作通知轮询与重连预算。',
  },
  'settings.field.advanced.resourceMaxBytes': {
    'en-US': 'Max resource bytes',
    'zh-CN': '最大资源字节数',
  },
  'settings.field.advanced.resourceMaxBytes.hint': {
    'en-US': 'Maximum byte size limit for individual resource objects loaded and cached in memory.',
    'zh-CN': '系统允许加载与缓存的单个资源对象的最大字节数上限。',
  },
  'settings.field.advanced.processorLeaseDurationMillis': {
    'en-US': 'Processor lease duration (ms)',
    'zh-CN': '处理器租约时长（毫秒）',
  },
  'settings.field.advanced.processorLeaseDurationMillis.hint': {
    'en-US': 'Distributed lease duration in milliseconds for background task processor nodes.',
    'zh-CN': '异步任务处理节点的分布式租约有效期毫秒数。持有租约的节点需在此周期内定期续约。',
  },
  'settings.field.advanced.processorHeartbeatIntervalMillis': {
    'en-US': 'Processor heartbeat (ms)',
    'zh-CN': '处理器心跳间隔（毫秒）',
  },
  'settings.field.advanced.processorHeartbeatIntervalMillis.hint': {
    'en-US': 'Heartbeat interval in milliseconds for task processor nodes to renew their distributed leases.',
    'zh-CN': '任务处理节点向分布式协调器发送心跳续约的时间间隔毫秒数。',
  },
  'settings.field.advanced.threadResolveFailureDelayMillis': {
    'en-US': 'Resolve failure delay (ms)',
    'zh-CN': '解析失败延迟（毫秒）',
  },
  'settings.field.advanced.threadResolveFailureDelayMillis.hint': {
    'en-US': 'Backoff delay in milliseconds before retrying after a conversation thread resolution failure.',
    'zh-CN': '解析会话上下文或分支失败时的重试退避延迟毫秒数。',
  },
  'settings.field.advanced.modelDispatchBusyFallbackDelayMillis': {
    'en-US': 'Model busy fallback (ms)',
    'zh-CN': '模型繁忙回退（毫秒）',
  },
  'settings.field.advanced.modelDispatchBusyFallbackDelayMillis.hint': {
    'en-US': 'Delay in milliseconds before falling back when the model dispatch queue is busy.',
    'zh-CN': '模型分发调度遇到通道繁忙触发降级回退前的等待毫秒数。',
  },
  'settings.field.advanced.toolPreflightFailureDelayMillis': {
    'en-US': 'Tool preflight failure delay (ms)',
    'zh-CN': '工具预检失败延迟（毫秒）',
  },
  'settings.field.advanced.toolPreflightFailureDelayMillis.hint': {
    'en-US': 'Backoff delay in milliseconds after a tool execution preflight check failure.',
    'zh-CN': '工具执行前置预检失败后的重试退避等待毫秒数。',
  },
  'settings.field.advanced.toolDispatchBusyFallbackDelayMillis': {
    'en-US': 'Tool busy fallback (ms)',
    'zh-CN': '工具繁忙回退（毫秒）',
  },
  'settings.field.advanced.toolDispatchBusyFallbackDelayMillis.hint': {
    'en-US': 'Delay in milliseconds before fallback when the tool dispatch queue is busy.',
    'zh-CN': '工具调度队列繁忙时触发降级处理前的等待毫秒数。',
  },
  'settings.field.advanced.applicationEventQueueCapacity': {
    'en-US': 'Event queue capacity',
    'zh-CN': '事件队列容量',
  },
  'settings.field.advanced.applicationEventQueueCapacity.hint': {
    'en-US': 'Capacity limit for the internal application domain event queue.',
    'zh-CN': '应用内部领域事件发布与消费队列的容量上限。',
  },
  'settings.field.advanced.applicationEventMaxBytes': {
    'en-US': 'Max event bytes',
    'zh-CN': '单条事件最大字节数',
  },
  'settings.field.advanced.applicationEventMaxBytes.hint': {
    'en-US': 'Maximum allowed byte size for a single internal application event payload.',
    'zh-CN': '应用内部单条事件 Payload 允许的最大字节数。',
  },
  'settings.field.advanced.applicationEventSendTimeoutMillis': {
    'en-US': 'Event send timeout (ms)',
    'zh-CN': '事件发送超时（毫秒）',
  },
  'settings.field.advanced.applicationEventSendTimeoutMillis.hint': {
    'en-US': 'Timeout in milliseconds when dispatching an event to the application event bus.',
    'zh-CN': '向应用事件总线投递事件时的超时毫秒数。',
  },
  'settings.field.advanced.applicationEventHeartbeatIntervalMillis': {
    'en-US': 'Event heartbeat (ms)',
    'zh-CN': '事件心跳间隔（毫秒）',
  },
  'settings.field.advanced.applicationEventHeartbeatIntervalMillis.hint': {
    'en-US': 'Heartbeat interval in milliseconds for application event bus health checks.',
    'zh-CN': '应用事件总线健康巡检心跳的时间间隔毫秒数。',
  },
  'settings.field.advanced.postgresqlWorkNotificationPollMillis': {
    'en-US': 'PostgreSQL poll (ms)',
    'zh-CN': 'PostgreSQL 轮询（毫秒）',
  },
  'settings.field.advanced.postgresqlWorkNotificationPollMillis.hint': {
    'en-US': 'Polling interval in milliseconds for PostgreSQL work notification events.',
    'zh-CN': 'PostgreSQL 任务状态通知长轮询的时间间隔毫秒数。',
  },
  'settings.field.advanced.postgresqlWorkReconnectBackoffMillis': {
    'en-US': 'PostgreSQL reconnect backoff (ms)',
    'zh-CN': 'PostgreSQL 重连退避（毫秒）',
  },
  'settings.field.advanced.postgresqlWorkReconnectBackoffMillis.hint': {
    'en-US': 'Backoff delay in milliseconds before attempting to reconnect to PostgreSQL notifications.',
    'zh-CN': 'PostgreSQL 通知通道断开重连时的退避延迟毫秒数。',
  },
} satisfies LocaleCatalog
