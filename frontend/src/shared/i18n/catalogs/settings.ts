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
  'settings.tabs.integrations': {
    'en-US': 'Integrations',
    'zh-CN': '集成',
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
  'settings.applyTiming.nextChat': {
    'en-US': 'Applies to newly created chats',
    'zh-CN': '新建对话生效',
  },
  'settings.applyTiming.restart': {
    'en-US': 'Restart required',
    'zh-CN': '重启后生效',
  },
  'settings.applyTiming.restartDescription': {
    'en-US': 'These settings are captured by the process at startup and apply after a restart.',
    'zh-CN': '这些设置由进程在启动时持有，修改后需要重启应用才能生效。',
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
    'en-US': 'The settings form metadata is invalid; cannot render the editor.',
    'zh-CN': '设置表单元数据无效，无法渲染编辑器。',
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
    'zh-CN': '存在为空的其他必需数值字段，请填写后再保存。',
  },
  'settings.error.partialModelSelection': {
    'en-US': 'The fallback model selection is incomplete; fill in all three fields or clear all of them.',
    'zh-CN': '回退模型选择不完整，请填齐三个字段或全部清空。',
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
    'en-US': 'Shared invocation retry, compaction fallback and subagent budgets.',
    'zh-CN': '共享调用重试、压缩回退与子代理预算。',
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
  'settings.field.aiRuntime.retryBackoffStrategy': {
    'en-US': 'Backoff strategy',
    'zh-CN': '退避策略',
  },
  'settings.field.aiRuntime.retryBaseDelayMillis': {
    'en-US': 'Base delay (ms)',
    'zh-CN': '基础延迟（毫秒）',
  },
  'settings.field.aiRuntime.retryMaxDelayMillis': {
    'en-US': 'Max delay (ms)',
    'zh-CN': '最大延迟（毫秒）',
  },
  'settings.field.aiRuntime.compactionKeepRecentTokens': {
    'en-US': 'Recent tokens to keep',
    'zh-CN': '保留最近 token',
  },
  'settings.field.aiRuntime.compactionFallbackModel': {
    'en-US': 'Compaction fallback model',
    'zh-CN': '压缩回退模型',
  },
  'settings.field.aiRuntime.compactionFallbackModel.hint': {
    'en-US':
      'Optional model used when the configured model cannot satisfy a compaction turn. Leave all three fields empty to disable the fallback.',
    'zh-CN': '可选：配置的模型无法完成压缩轮次时使用的回退模型。三个字段全部留空表示禁用回退。',
  },
  'settings.field.aiRuntime.compactionFallbackModel.providerName': {
    'en-US': 'Provider',
    'zh-CN': 'Provider',
  },
  'settings.field.aiRuntime.compactionFallbackModel.modelName': {
    'en-US': 'Model',
    'zh-CN': '模型',
  },
  'settings.field.aiRuntime.compactionFallbackModel.variant': {
    'en-US': 'Variant',
    'zh-CN': '变体',
  },
  'settings.field.aiRuntime.subagentMaxDepth': {
    'en-US': 'Max depth',
    'zh-CN': '最大嵌套深度',
  },
  'settings.field.aiRuntime.subagentMaxConcurrency': {
    'en-US': 'Max concurrency',
    'zh-CN': '最大并发',
  },
  'settings.field.aiRuntime.subagentMaxTotalConcurrency': {
    'en-US': 'Max total concurrency',
    'zh-CN': '全局最大并发',
  },
  'settings.field.aiRuntime.subagentMaxTotalConcurrency.hint': {
    'en-US': 'Empty means no extra cap.',
    'zh-CN': '留空表示不额外限制。',
  },
  'settings.field.aiRuntime.subagentIdleTimeoutMillis': {
    'en-US': 'Idle timeout (ms)',
    'zh-CN': '空闲超时（毫秒）',
  },
  'settings.field.aiRuntime.subagentIdleTimeoutMillis.hint': {
    'en-US': '0 disables the timeout.',
    'zh-CN': '0 表示关闭超时。',
  },
  'settings.field.aiRuntime.subagentMaxTurns': {
    'en-US': 'Max turns',
    'zh-CN': '最大轮数',
  },
  'settings.option.retryBackoff.fixed': {
    'en-US': 'Fixed',
    'zh-CN': '固定',
  },
  'settings.option.retryBackoff.exponential': {
    'en-US': 'Exponential',
    'zh-CN': '指数',
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
  'settings.section.tool.yolo.title': {
    'en-US': 'Default YOLO',
    'zh-CN': '默认 YOLO',
  },
  'settings.section.tool.yolo.description': {
    'en-US': 'Whether tools run without approval by default. Applies to the next invocation.',
    'zh-CN': '是否默认在无审批下运行工具；对下一次调用立即生效。',
  },
  'settings.section.tool.gateway.title': {
    'en-US': 'Tool gateway & skill loading',
    'zh-CN': '工具网关与技能加载',
  },
  'settings.section.tool.gateway.description': {
    'en-US': 'Retry and load budgets captured by the process at startup; restart required.',
    'zh-CN': '进程启动时捕获的重试/加载预算；修改后需要重启生效。',
  },
  'settings.field.tool.defaultYolo': {
    'en-US': 'Default YOLO',
    'zh-CN': '默认 YOLO',
  },
  'settings.field.tool.modelGatewayBusyRetryMillis': {
    'en-US': 'Model gateway busy retry (ms)',
    'zh-CN': '模型网关繁忙重试（毫秒）',
  },
  'settings.field.tool.toolGatewayBusyRetryMillis': {
    'en-US': 'Tool gateway busy retry (ms)',
    'zh-CN': '工具网关繁忙重试（毫秒）',
  },
  'settings.field.tool.toolGatewayOverloadRetryMillis': {
    'en-US': 'Tool gateway overload retry (ms)',
    'zh-CN': '工具网关过载重试（毫秒）',
  },
  'settings.field.tool.skillLoadTimeoutMillis': {
    'en-US': 'Skill load timeout (ms)',
    'zh-CN': '技能加载超时（毫秒）',
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
  'settings.section.environment.title': {
    'en-US': 'Daemon gateway',
    'zh-CN': '守护网关',
  },
  'settings.section.environment.description': {
    'en-US': 'Resource/message boundaries and timeouts for the daemon gateway.',
    'zh-CN': '守护网关的资源/消息边界与超时。',
  },
  'settings.field.environment.maxResourceBytes': {
    'en-US': 'Max resource bytes',
    'zh-CN': '最大资源字节数',
  },
  'settings.field.environment.maxMessageBytes': {
    'en-US': 'Max message bytes',
    'zh-CN': '最大消息字节数',
  },
  'settings.field.environment.heartbeatTimeoutMillis': {
    'en-US': 'Heartbeat timeout (ms)',
    'zh-CN': '心跳超时（毫秒）',
  },
  'settings.field.environment.directoryListTimeoutMillis': {
    'en-US': 'Directory list timeout (ms)',
    'zh-CN': '目录列出超时（毫秒）',
  },

  // --- Integrations ---
  'settings.section.integrations.description': {
    'en-US': 'Non-sensitive runtime parameters for external media and generation integrations.',
    'zh-CN': '外部媒体/生成集成的非敏感运行参数。',
  },
  'settings.section.integrations.comfyui.title': {
    'en-US': 'ComfyUI',
    'zh-CN': 'ComfyUI',
  },
  'settings.section.integrations.comfyui.description': {
    'en-US': 'ComfyUI runtime endpoint and input budget.',
    'zh-CN': 'ComfyUI 运行端点与输入预算。',
  },
  'settings.field.integrations.comfyui.enabled': {
    'en-US': 'Enable ComfyUI',
    'zh-CN': '启用 ComfyUI',
  },
  'settings.field.integrations.comfyui.baseUrl': {
    'en-US': 'Base URL',
    'zh-CN': '基础地址',
  },
  'settings.field.integrations.comfyui.connectTimeoutMillis': {
    'en-US': 'Connect timeout (ms)',
    'zh-CN': '连接超时（毫秒）',
  },
  'settings.field.integrations.comfyui.readTimeoutMillis': {
    'en-US': 'Read timeout (ms)',
    'zh-CN': '读取超时（毫秒）',
  },
  'settings.field.integrations.comfyui.websocketTimeoutMillis': {
    'en-US': 'WebSocket timeout (ms)',
    'zh-CN': 'WebSocket 超时（毫秒）',
  },
  'settings.field.integrations.comfyui.maxInputFileBytes': {
    'en-US': 'Max input file bytes',
    'zh-CN': '最大输入文件字节数',
  },
  'settings.section.integrations.openCliHub.title': {
    'en-US': 'OpenCLI Hub',
    'zh-CN': 'OpenCLI Hub',
  },
  'settings.section.integrations.openCliHub.description': {
    'en-US': 'OpenCLI Hub shared configuration (no credentials).',
    'zh-CN': 'OpenCLI Hub 的共享配置（不含凭据）。',
  },
  'settings.field.integrations.openCliHub.enabled': {
    'en-US': 'Enable OpenCLI Hub',
    'zh-CN': '启用 OpenCLI Hub',
  },
  'settings.field.integrations.openCliHub.baseUrl': {
    'en-US': 'Base URL',
    'zh-CN': '基础地址',
  },
  'settings.field.integrations.openCliHub.connectTimeoutMillis': {
    'en-US': 'Connect timeout (ms)',
    'zh-CN': '连接超时（毫秒）',
  },
  'settings.field.integrations.openCliHub.requestTimeoutMillis': {
    'en-US': 'Request timeout (ms)',
    'zh-CN': '请求超时（毫秒）',
  },
  'settings.field.integrations.openCliHub.longPollTimeoutMillis': {
    'en-US': 'Long-poll timeout (ms)',
    'zh-CN': '长轮询超时（毫秒）',
  },
  'settings.field.integrations.openCliHub.streamBufferBytes': {
    'en-US': 'Stream buffer bytes',
    'zh-CN': '流缓冲字节数',
  },
  'settings.field.integrations.openCliHub.maxJsonResponseBytes': {
    'en-US': 'Max JSON response bytes',
    'zh-CN': '最大 JSON 响应字节数',
  },
  'settings.field.integrations.openCliHub.maxErrorResponseBytes': {
    'en-US': 'Max error response bytes',
    'zh-CN': '最大错误响应字节数',
  },
  'settings.field.integrations.openCliHub.maxOutputChars': {
    'en-US': 'Max output chars',
    'zh-CN': '最大输出字符数',
  },
  'settings.section.integrations.seedance.title': {
    'en-US': 'Seedance',
    'zh-CN': 'Seedance',
  },
  'settings.section.integrations.seedance.description': {
    'en-US': 'Seedance submission and polling budget.',
    'zh-CN': 'Seedance 提交与轮询预算。',
  },
  'settings.field.integrations.seedance.enabled': {
    'en-US': 'Enable Seedance',
    'zh-CN': '启用 Seedance',
  },
  'settings.field.integrations.seedance.workspaceId': {
    'en-US': 'Workspace ID',
    'zh-CN': '工作区 ID',
  },
  'settings.field.integrations.seedance.retry': {
    'en-US': 'Retries',
    'zh-CN': '重试次数',
  },
  'settings.field.integrations.seedance.hubExecutionTimeoutMillis': {
    'en-US': 'Hub execution timeout (ms)',
    'zh-CN': 'Hub 执行超时（毫秒）',
  },
  'settings.field.integrations.seedance.statusPollIntervalMillis': {
    'en-US': 'Status poll interval (ms)',
    'zh-CN': '状态轮询间隔（毫秒）',
  },
  'settings.field.integrations.seedance.maxWaitMillis': {
    'en-US': 'Max wait (ms)',
    'zh-CN': '最大等待（毫秒）',
  },
  'settings.section.integrations.gptImage2.title': {
    'en-US': 'GPT Image 2',
    'zh-CN': 'GPT Image 2',
  },
  'settings.section.integrations.gptImage2.description': {
    'en-US': 'GPT Image 2 paid submission budget.',
    'zh-CN': 'GPT Image 2 付费提交预算。',
  },
  'settings.field.integrations.gptImage2.paidEnabled': {
    'en-US': 'Paid submissions enabled',
    'zh-CN': '启用付费提交',
  },
  'settings.field.integrations.gptImage2.askTimeoutSeconds': {
    'en-US': 'Ask timeout (s)',
    'zh-CN': '请求超时（秒）',
  },
  'settings.field.integrations.gptImage2.hubExecutionTimeoutMillis': {
    'en-US': 'Hub execution timeout (ms)',
    'zh-CN': 'Hub 执行超时（毫秒）',
  },
  'settings.field.integrations.gptImage2.maxWaitMillis': {
    'en-US': 'Max wait (ms)',
    'zh-CN': '最大等待（毫秒）',
  },
  'settings.section.integrations.minimaxH3.title': {
    'en-US': 'MiniMax H3',
    'zh-CN': 'MiniMax H3',
  },
  'settings.section.integrations.minimaxH3.description': {
    'en-US': 'MiniMax-H3 Ref2VA adapter configuration (no credentials).',
    'zh-CN': 'MiniMax-H3 Ref2VA 适配器配置（不含凭据）。',
  },
  'settings.field.integrations.minimaxH3.enabled': {
    'en-US': 'Enable MiniMax H3',
    'zh-CN': '启用 MiniMax H3',
  },
  'settings.field.integrations.minimaxH3.promptAgentName': {
    'en-US': 'Prompt agent name',
    'zh-CN': '提示词代理名',
  },
  'settings.field.integrations.minimaxH3.promptEnvironmentName': {
    'en-US': 'Prompt environment name',
    'zh-CN': '提示词环境名',
  },
  'settings.field.integrations.minimaxH3.promptMaxWaitMillis': {
    'en-US': 'Prompt max wait (ms)',
    'zh-CN': '提示词最大等待（毫秒）',
  },
  'settings.field.integrations.minimaxH3.comfyBaseUrl': {
    'en-US': 'Comfy base URL',
    'zh-CN': 'Comfy 基础地址',
  },
  'settings.field.integrations.minimaxH3.comfyConnectTimeoutMillis': {
    'en-US': 'Comfy connect timeout (ms)',
    'zh-CN': 'Comfy 连接超时（毫秒）',
  },
  'settings.field.integrations.minimaxH3.comfyRequestTimeoutMillis': {
    'en-US': 'Comfy request timeout (ms)',
    'zh-CN': 'Comfy 请求超时（毫秒）',
  },
  'settings.field.integrations.minimaxH3.comfyPollIntervalMillis': {
    'en-US': 'Comfy poll interval (ms)',
    'zh-CN': 'Comfy 轮询间隔（毫秒）',
  },
  'settings.field.integrations.minimaxH3.comfyMaxWaitMillis': {
    'en-US': 'Comfy max wait (ms)',
    'zh-CN': 'Comfy 最大等待（毫秒）',
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
    'en-US': 'Upload expiry and S3 presigned URL budgets.',
    'zh-CN': '上传有效期与 S3 预签名 URL 预算。',
  },
  'settings.section.storageMedia.canvasMedia.title': {
    'en-US': 'Canvas media processing',
    'zh-CN': '画布媒体处理',
  },
  'settings.section.storageMedia.canvasMedia.description': {
    'en-US': 'Timeout and thumbnail budgets for canvas media processing.',
    'zh-CN': '画布媒体处理的超时与缩略图预算。',
  },
  'settings.field.storageMedia.uploadExpiresSeconds': {
    'en-US': 'Upload expiry (s)',
    'zh-CN': '上传有效期（秒）',
  },
  'settings.field.storageMedia.s3Enabled': {
    'en-US': 'S3 enabled',
    'zh-CN': '启用 S3',
  },
  'settings.field.storageMedia.s3PresignDefaultExpiresSeconds': {
    'en-US': 'S3 presign default expiry (s)',
    'zh-CN': 'S3 预签名默认有效期（秒）',
  },
  'settings.field.storageMedia.s3PresignMaxExpiresSeconds': {
    'en-US': 'S3 presign max expiry (s)',
    'zh-CN': 'S3 预签名最大有效期（秒）',
  },
  'settings.field.storageMedia.canvasMediaProcessTimeoutMillis': {
    'en-US': 'Media process timeout (ms)',
    'zh-CN': '媒体处理超时（毫秒）',
  },
  'settings.field.storageMedia.thumbnailMaxDimension': {
    'en-US': 'Thumbnail max dimension',
    'zh-CN': '缩略图最大边长',
  },
  'settings.field.storageMedia.thumbnailQuality': {
    'en-US': 'Thumbnail quality',
    'zh-CN': '缩略图质量',
  },
  // --- Advanced ---
  'settings.section.advanced.description': {
    'en-US': 'Process-level runtime budgets for processor, dispatcher, executors and work notification.',
    'zh-CN': '处理器/分发器/执行器/工作通知的进程级运行预算。',
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
    'en-US': 'Lease and heartbeat budgets for thread processors.',
    'zh-CN': '线程处理器的租约与心跳预算。',
  },
  'settings.section.advanced.dispatcher.title': {
    'en-US': 'Dispatcher',
    'zh-CN': '分发器',
  },
  'settings.section.advanced.dispatcher.description': {
    'en-US': 'Dispatch concurrency, queue and rejection budgets.',
    'zh-CN': '分发并发、队列与拒绝预算。',
  },
  'settings.section.advanced.canvas.title': {
    'en-US': 'Canvas runtime',
    'zh-CN': '画布运行时',
  },
  'settings.section.advanced.canvas.description': {
    'en-US': 'Canvas realtime and function executor budgets.',
    'zh-CN': '画布实时通道与函数执行器预算。',
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
  'settings.field.advanced.processorLeaseDurationMillis': {
    'en-US': 'Processor lease duration (ms)',
    'zh-CN': '处理器租约时长（毫秒）',
  },
  'settings.field.advanced.processorHeartbeatIntervalMillis': {
    'en-US': 'Processor heartbeat (ms)',
    'zh-CN': '处理器心跳间隔（毫秒）',
  },
  'settings.field.advanced.threadResolveFailureDelayMillis': {
    'en-US': 'Resolve failure delay (ms)',
    'zh-CN': '解析失败延迟（毫秒）',
  },
  'settings.field.advanced.modelDispatchBusyFallbackDelayMillis': {
    'en-US': 'Model busy fallback (ms)',
    'zh-CN': '模型繁忙回退（毫秒）',
  },
  'settings.field.advanced.toolPreflightFailureDelayMillis': {
    'en-US': 'Tool preflight failure delay (ms)',
    'zh-CN': '工具预检失败延迟（毫秒）',
  },
  'settings.field.advanced.toolDispatchBusyFallbackDelayMillis': {
    'en-US': 'Tool busy fallback (ms)',
    'zh-CN': '工具繁忙回退（毫秒）',
  },
  'settings.field.advanced.dispatcherLeaseDurationMillis': {
    'en-US': 'Dispatcher lease duration (ms)',
    'zh-CN': '分发器租约时长（毫秒）',
  },
  'settings.field.advanced.dispatcherPollIntervalMillis': {
    'en-US': 'Dispatcher poll interval (ms)',
    'zh-CN': '分发器轮询间隔（毫秒）',
  },
  'settings.field.advanced.dispatcherRejectionDelayMillis': {
    'en-US': 'Rejection delay (ms)',
    'zh-CN': '拒绝延迟（毫秒）',
  },
  'settings.field.advanced.dispatcherMaxDispatchTasks': {
    'en-US': 'Max dispatch tasks',
    'zh-CN': '最大分发任务数',
  },
  'settings.field.advanced.dispatcherWorkerConcurrency': {
    'en-US': 'Worker concurrency',
    'zh-CN': '工作线程并发',
  },
  'settings.field.advanced.dispatcherWorkerQueueCapacity': {
    'en-US': 'Worker queue capacity',
    'zh-CN': '工作队列容量',
  },
  'settings.field.advanced.canvasRealtimeMaxLength': {
    'en-US': 'Realtime max length',
    'zh-CN': 'Realtime 最大长度',
  },
  'settings.field.advanced.canvasFunctionExecutorCoreSize': {
    'en-US': 'Function executor core size',
    'zh-CN': '函数执行器核心线程数',
  },
  'settings.field.advanced.canvasFunctionExecutorMaxSize': {
    'en-US': 'Function executor max size',
    'zh-CN': '函数执行器最大线程数',
  },
  'settings.field.advanced.canvasFunctionExecutorQueueCapacity': {
    'en-US': 'Function executor queue capacity',
    'zh-CN': '函数执行器队列容量',
  },
  'settings.field.advanced.applicationEventQueueCapacity': {
    'en-US': 'Event queue capacity',
    'zh-CN': '事件队列容量',
  },
  'settings.field.advanced.applicationEventMaxBytes': {
    'en-US': 'Max event bytes',
    'zh-CN': '单条事件最大字节数',
  },
  'settings.field.advanced.applicationEventSendTimeoutMillis': {
    'en-US': 'Event send timeout (ms)',
    'zh-CN': '事件发送超时（毫秒）',
  },
  'settings.field.advanced.applicationEventHeartbeatIntervalMillis': {
    'en-US': 'Event heartbeat (ms)',
    'zh-CN': '事件心跳间隔（毫秒）',
  },
  'settings.field.advanced.postgresqlWorkNotificationPollMillis': {
    'en-US': 'PostgreSQL poll (ms)',
    'zh-CN': 'PostgreSQL 轮询（毫秒）',
  },
  'settings.field.advanced.postgresqlWorkReconnectBackoffMillis': {
    'en-US': 'PostgreSQL reconnect backoff (ms)',
    'zh-CN': 'PostgreSQL 重连退避（毫秒）',
  },
  'settings.field.advanced.redisRealtimeRetryDelayMillis': {
    'en-US': 'Redis realtime retry delay (ms)',
    'zh-CN': 'Redis Realtime 重试延迟（毫秒）',
  },
} satisfies LocaleCatalog
