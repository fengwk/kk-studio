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
  'settings.field.aiRuntime.compactionFallbackModel': {
    'en-US': 'Compaction fallback model',
    'zh-CN': '压缩回退模型',
  },
  'settings.field.aiRuntime.compactionFallbackModel.hint': {
    'en-US': 'Fallback model and variant used when the active conversation model cannot complete a compaction turn. Leave empty to disable fallback.',
    'zh-CN': '当前会话模型无法完成压缩轮次时使用的备选模型与变体。留空表示不使用回退模型。',
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
  'settings.field.aiRuntime.subagentIdleTimeoutMillis': {
    'en-US': 'Idle timeout (ms)',
    'zh-CN': '空闲超时（毫秒）',
  },
  'settings.field.aiRuntime.subagentIdleTimeoutMillis.hint': {
    'en-US': 'Idle timeout in milliseconds for inactive subagents. Set to 0 to disable idle timeout detection.',
    'zh-CN': '子代理无输出或处于等待状态的超时毫秒数。设为 0 表示不启用空闲超时检测。',
  },
  'settings.field.aiRuntime.subagentMaxTurns': {
    'en-US': 'Max turns',
    'zh-CN': '最大轮数',
  },
  'settings.field.aiRuntime.subagentMaxTurns.hint': {
    'en-US': 'Maximum interaction turns allowed for a single subagent task. Upon reaching the limit, the subagent returns a phase report.',
    'zh-CN': '单个子代理任务允许执行的最大交互轮数。达到上限后子代理将强制返回阶段性结果。',
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
  'settings.field.tool.permission.hint': {
    'en-US': 'Access control rules for tool execution. Rules are evaluated top-to-bottom in order; the first matching rule determines whether to allow, ask, or deny.',
    'zh-CN': '工具执行的访问控制规则列表。按顺序自上而下匹配，首条匹配的规则决定该工具是直接允许、弹出审批还是直接拒绝。',
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
    'en-US': 'Retry and skill-load budgets. Applies to the next invocation.',
    'zh-CN': '重试与技能加载预算；对下一次调用立即生效。',
  },
  'settings.field.tool.defaultYolo': {
    'en-US': 'Default YOLO',
    'zh-CN': '默认 YOLO',
  },
  'settings.field.tool.defaultYolo.hint': {
    'en-US': 'Whether YOLO mode is enabled by default for new conversations. When enabled, tool executions bypass manual approval.',
    'zh-CN': '新建对话时是否默认开启 YOLO 模式。开启后执行工具将跳过人工审批直接运行。',
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
  'settings.field.tool.skillLoadTimeoutMillis': {
    'en-US': 'Skill load timeout (ms)',
    'zh-CN': '技能加载超时（毫秒）',
  },
  'settings.field.tool.skillLoadTimeoutMillis.hint': {
    'en-US': 'Timeout in milliseconds when loading external skill definitions and scripts. Times out if loading takes longer.',
    'zh-CN': '加载外部 Skill 技能定义与脚本时的超时毫秒数。超时后本次技能加载将判定为失败。',
  },
  'settings.modelSelection.none': {
    'en-US': 'None',
    'zh-CN': '不使用回退',
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
    'en-US': 'Resource limits and timeouts for the daemon gateway.',
    'zh-CN': '守护网关的资源上限与超时。',
  },
  'settings.section.environment.runtime.title': {
    'en-US': 'Runtime budgets',
    'zh-CN': '运行预算',
  },
  'settings.section.environment.runtime.description': {
    'en-US': 'Heartbeat, directory listing and resource limits. Applies to the next check or request.',
    'zh-CN': '心跳、目录列出与资源上限；对下一次判定或请求立即生效。',
  },
  'settings.field.environment.maxResourceBytes': {
    'en-US': 'Max resource bytes',
    'zh-CN': '最大资源字节数',
  },
  'settings.field.environment.maxResourceBytes.hint': {
    'en-US': 'Maximum storage in bytes allowed for a single environment workspace. New resource writes are rejected beyond this limit.',
    'zh-CN': '单个 Environment 环境工作区允许占用的最大资源字节数。超出限制时将拒绝写入新资源。',
  },
  'settings.field.environment.heartbeatTimeoutMillis': {
    'en-US': 'Heartbeat timeout (ms)',
    'zh-CN': '心跳超时（毫秒）',
  },
  'settings.field.environment.heartbeatTimeoutMillis.hint': {
    'en-US': 'Heartbeat timeout in milliseconds for runtime environments. Environments without heartbeats past this duration are marked disconnected.',
    'zh-CN': 'Environment 运行时的心跳超时毫秒数。超过该时间未收到心跳将被标记为失联。',
  },
  'settings.field.environment.directoryListTimeoutMillis': {
    'en-US': 'Directory list timeout (ms)',
    'zh-CN': '目录列出超时（毫秒）',
  },
  'settings.field.environment.directoryListTimeoutMillis.hint': {
    'en-US': 'Timeout in milliseconds for directory listing operations within an environment.',
    'zh-CN': '在 Environment 环境中遍历或列出目录文件时的超时毫秒数。',
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
  'settings.field.integrations.comfyui.enabled.hint': {
    'en-US': 'Whether to enable ComfyUI workflow image generation and rendering integration.',
    'zh-CN': '是否启用 ComfyUI 工作流绘图与图像生成集成。',
  },
  'settings.field.integrations.comfyui.baseUrl': {
    'en-US': 'Base URL',
    'zh-CN': '基础地址',
  },
  'settings.field.integrations.comfyui.baseUrl.hint': {
    'en-US': 'Base URL for the ComfyUI service API (e.g. http://localhost:8188).',
    'zh-CN': 'ComfyUI 服务的 API 基础地址（如 http://localhost:8188）。',
  },
  'settings.field.integrations.comfyui.connectTimeoutMillis': {
    'en-US': 'Connect timeout (ms)',
    'zh-CN': '连接超时（毫秒）',
  },
  'settings.field.integrations.comfyui.connectTimeoutMillis.hint': {
    'en-US': 'TCP connection timeout in milliseconds when connecting to the ComfyUI service.',
    'zh-CN': '连接 ComfyUI 服务时的 TCP 连接超时毫秒数。',
  },
  'settings.field.integrations.comfyui.readTimeoutMillis': {
    'en-US': 'Read timeout (ms)',
    'zh-CN': '读取超时（毫秒）',
  },
  'settings.field.integrations.comfyui.readTimeoutMillis.hint': {
    'en-US': 'HTTP read timeout in milliseconds when waiting for ComfyUI responses.',
    'zh-CN': '等待 ComfyUI HTTP 响应的读取超时毫秒数。',
  },
  'settings.field.integrations.comfyui.websocketTimeoutMillis': {
    'en-US': 'WebSocket timeout (ms)',
    'zh-CN': 'WebSocket 超时（毫秒）',
  },
  'settings.field.integrations.comfyui.websocketTimeoutMillis.hint': {
    'en-US': 'WebSocket connection timeout in milliseconds for ComfyUI real-time progress events.',
    'zh-CN': '与 ComfyUI 建立 WebSocket 实时事件连接时的超时毫秒数。',
  },
  'settings.field.integrations.comfyui.maxInputFileBytes': {
    'en-US': 'Max input file bytes',
    'zh-CN': '最大输入文件字节数',
  },
  'settings.field.integrations.comfyui.maxInputFileBytes.hint': {
    'en-US': 'Maximum allowed file size in bytes when uploading input images or assets to ComfyUI.',
    'zh-CN': '向 ComfyUI 上传输入图片或素材时的单文件最大字节数限制。',
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
  'settings.field.integrations.openCliHub.enabled.hint': {
    'en-US': 'Whether to enable the OpenCLI Hub browser automation and orchestration integration.',
    'zh-CN': '是否启用 OpenCLI Hub 浏览器与自动化集成服务。',
  },
  'settings.field.integrations.openCliHub.baseUrl': {
    'en-US': 'Base URL',
    'zh-CN': '基础地址',
  },
  'settings.field.integrations.openCliHub.baseUrl.hint': {
    'en-US': 'Base URL for the OpenCLI Hub service API.',
    'zh-CN': 'OpenCLI Hub 服务的 API 基础地址。',
  },
  'settings.field.integrations.openCliHub.connectTimeoutMillis': {
    'en-US': 'Connect timeout (ms)',
    'zh-CN': '连接超时（毫秒）',
  },
  'settings.field.integrations.openCliHub.connectTimeoutMillis.hint': {
    'en-US': 'TCP connection timeout in milliseconds when connecting to OpenCLI Hub.',
    'zh-CN': '连接 OpenCLI Hub 服务时的 TCP 连接超时毫秒数。',
  },
  'settings.field.integrations.openCliHub.requestTimeoutMillis': {
    'en-US': 'Request timeout (ms)',
    'zh-CN': '请求超时（毫秒）',
  },
  'settings.field.integrations.openCliHub.requestTimeoutMillis.hint': {
    'en-US': 'Request timeout in milliseconds for standard OpenCLI Hub API calls.',
    'zh-CN': '发送常规请求至 OpenCLI Hub 时的等待响应超时毫秒数。',
  },
  'settings.field.integrations.openCliHub.longPollTimeoutMillis': {
    'en-US': 'Long-poll timeout (ms)',
    'zh-CN': '长轮询超时（毫秒）',
  },
  'settings.field.integrations.openCliHub.longPollTimeoutMillis.hint': {
    'en-US': 'Maximum duration in milliseconds for long-polling connection with OpenCLI Hub.',
    'zh-CN': '与 OpenCLI Hub 保持长轮询连接等待事件的最大毫秒数。',
  },
  'settings.field.integrations.openCliHub.streamBufferBytes': {
    'en-US': 'Stream buffer bytes',
    'zh-CN': '流缓冲字节数',
  },
  'settings.field.integrations.openCliHub.streamBufferBytes.hint': {
    'en-US': 'Buffer size in bytes for streaming responses from OpenCLI Hub.',
    'zh-CN': '接收 OpenCLI Hub 流式输出时的缓冲区字节数大小。',
  },
  'settings.field.integrations.openCliHub.maxJsonResponseBytes': {
    'en-US': 'Max JSON response bytes',
    'zh-CN': '最大 JSON 响应字节数',
  },
  'settings.field.integrations.openCliHub.maxJsonResponseBytes.hint': {
    'en-US': 'Maximum allowed JSON response payload size in bytes from OpenCLI Hub.',
    'zh-CN': '解析 OpenCLI Hub JSON 响应允许的最大响应体积字节数。',
  },
  'settings.field.integrations.openCliHub.maxErrorResponseBytes': {
    'en-US': 'Max error response bytes',
    'zh-CN': '最大错误响应字节数',
  },
  'settings.field.integrations.openCliHub.maxErrorResponseBytes.hint': {
    'en-US': 'Maximum bytes to capture and retain from an OpenCLI Hub error response.',
    'zh-CN': '读取 OpenCLI Hub 错误响应内容时截取保存的最大字节数。',
  },
  'settings.field.integrations.openCliHub.maxOutputChars': {
    'en-US': 'Max output chars',
    'zh-CN': '最大输出字符数',
  },
  'settings.field.integrations.openCliHub.maxOutputChars.hint': {
    'en-US': 'Maximum character limit retained from OpenCLI Hub terminal and command output.',
    'zh-CN': '接收 OpenCLI Hub 终端或脚本输出时保留的最大字符数。',
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
  'settings.field.integrations.seedance.enabled.hint': {
    'en-US': 'Whether to enable the Seedance multimodal video production integration.',
    'zh-CN': '是否启用 Seedance 短剧与多模态视频生产集成服务。',
  },
  'settings.field.integrations.seedance.workspaceId': {
    'en-US': 'Workspace ID',
    'zh-CN': '工作区 ID',
  },
  'settings.field.integrations.seedance.workspaceId.hint': {
    'en-US': 'Default workspace identifier for Seedance. Leave empty to use the service default.',
    'zh-CN': 'Seedance 绑定的默认工作空间标识符。留空表示使用服务默认工作空间。',
  },
  'settings.field.integrations.seedance.retry': {
    'en-US': 'Retries',
    'zh-CN': '重试次数',
  },
  'settings.field.integrations.seedance.retry.hint': {
    'en-US': 'Number of automatic retry attempts when submitting Seedance generation tasks.',
    'zh-CN': '请求 Seedance 生成任务失败时的自动重试次数。',
  },
  'settings.field.integrations.seedance.hubExecutionTimeoutMillis': {
    'en-US': 'Hub execution timeout (ms)',
    'zh-CN': 'Hub 执行超时（毫秒）',
  },
  'settings.field.integrations.seedance.hubExecutionTimeoutMillis.hint': {
    'en-US': 'Execution timeout in milliseconds for single operations dispatched to Seedance Hub.',
    'zh-CN': 'Seedance 任务在 Hub 中执行单次操作的最大超时毫秒数。',
  },
  'settings.field.integrations.seedance.statusPollIntervalMillis': {
    'en-US': 'Status poll interval (ms)',
    'zh-CN': '状态轮询间隔（毫秒）',
  },
  'settings.field.integrations.seedance.statusPollIntervalMillis.hint': {
    'en-US': 'Interval in milliseconds between status polling requests for Seedance generation tasks.',
    'zh-CN': '轮询查询 Seedance 视频生成进度与状态的时间间隔毫秒数。',
  },
  'settings.field.integrations.seedance.maxWaitMillis': {
    'en-US': 'Max wait (ms)',
    'zh-CN': '最大等待（毫秒）',
  },
  'settings.field.integrations.seedance.maxWaitMillis.hint': {
    'en-US': 'Maximum total wait duration in milliseconds for a Seedance task before timing out.',
    'zh-CN': '等待 Seedance 任务完成的最大总等待毫秒数。超时后将放弃等待并判定为超时失败。',
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
  'settings.field.integrations.gptImage2.paidEnabled.hint': {
    'en-US': 'Whether to enable the GPT-Image-2 paid image generation and editing capability.',
    'zh-CN': '是否启用 GPT-Image-2 高级付费图像生成与编辑功能。',
  },
  'settings.field.integrations.gptImage2.askTimeoutSeconds': {
    'en-US': 'Ask timeout (s)',
    'zh-CN': '请求超时（秒）',
  },
  'settings.field.integrations.gptImage2.askTimeoutSeconds.hint': {
    'en-US': 'Timeout in seconds when waiting for user prompt confirmation or interaction in GPT-Image-2.',
    'zh-CN': '等待 GPT-Image-2 生成提示词确认或交互应答时的超时秒数。',
  },
  'settings.field.integrations.gptImage2.hubExecutionTimeoutMillis': {
    'en-US': 'Hub execution timeout (ms)',
    'zh-CN': 'Hub 执行超时（毫秒）',
  },
  'settings.field.integrations.gptImage2.hubExecutionTimeoutMillis.hint': {
    'en-US': 'Hub execution timeout in milliseconds for single GPT-Image-2 operations.',
    'zh-CN': 'GPT-Image-2 图像生成任务在 Hub 中的单次执行超时毫秒数。',
  },
  'settings.field.integrations.gptImage2.maxWaitMillis': {
    'en-US': 'Max wait (ms)',
    'zh-CN': '最大等待（毫秒）',
  },
  'settings.field.integrations.gptImage2.maxWaitMillis.hint': {
    'en-US': 'Maximum total duration in milliseconds to wait for GPT-Image-2 generation completion.',
    'zh-CN': '等待 GPT-Image-2 完整产出图像的最大总等待毫秒数。',
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
  'settings.field.integrations.minimaxH3.enabled.hint': {
    'en-US': 'Whether to enable the MiniMax-H3 multimodal storyboard and video integration.',
    'zh-CN': '是否启用 MiniMax-H3 多模态视频与分镜生成集成。',
  },
  'settings.field.integrations.minimaxH3.promptAgentName': {
    'en-US': 'Prompt agent name',
    'zh-CN': '提示词代理名',
  },
  'settings.field.integrations.minimaxH3.promptAgentName.hint': {
    'en-US': 'Agent name delegated for MiniMax-H3 prompt generation. Leave empty to use the default agent.',
    'zh-CN': 'MiniMax-H3 生成提示词所委派的 Agent 名称。留空表示使用当前默认 Agent。',
  },
  'settings.field.integrations.minimaxH3.promptEnvironmentName': {
    'en-US': 'Prompt environment name',
    'zh-CN': '提示词环境名',
  },
  'settings.field.integrations.minimaxH3.promptEnvironmentName.hint': {
    'en-US': 'Environment name for MiniMax-H3 prompt execution. Leave empty to use the default environment.',
    'zh-CN': 'MiniMax-H3 提示词生成所运行的环境名称。留空表示使用默认运行环境。',
  },
  'settings.field.integrations.minimaxH3.promptMaxWaitMillis': {
    'en-US': 'Prompt max wait (ms)',
    'zh-CN': '提示词最大等待（毫秒）',
  },
  'settings.field.integrations.minimaxH3.promptMaxWaitMillis.hint': {
    'en-US': 'Maximum wait duration in milliseconds for MiniMax-H3 prompt generation.',
    'zh-CN': '等待 MiniMax-H3 提示词生成完成的最大毫秒数。',
  },
  'settings.field.integrations.minimaxH3.comfyBaseUrl': {
    'en-US': 'Comfy base URL',
    'zh-CN': 'Comfy 基础地址',
  },
  'settings.field.integrations.minimaxH3.comfyBaseUrl.hint': {
    'en-US': 'ComfyUI backend service URL for MiniMax-H3. Leave empty to inherit from ComfyUI integration.',
    'zh-CN': 'MiniMax-H3 依赖的 ComfyUI 后端服务地址。留空表示沿用 ComfyUI 集成中的基础地址。',
  },
  'settings.field.integrations.minimaxH3.comfyConnectTimeoutMillis': {
    'en-US': 'Comfy connect timeout (ms)',
    'zh-CN': 'Comfy 连接超时（毫秒）',
  },
  'settings.field.integrations.minimaxH3.comfyConnectTimeoutMillis.hint': {
    'en-US': 'Connection timeout in milliseconds for MiniMax-H3 connecting to ComfyUI.',
    'zh-CN': 'MiniMax-H3 连接后端 ComfyUI 时的连接超时毫秒数。',
  },
  'settings.field.integrations.minimaxH3.comfyRequestTimeoutMillis': {
    'en-US': 'Comfy request timeout (ms)',
    'zh-CN': 'Comfy 请求超时（毫秒）',
  },
  'settings.field.integrations.minimaxH3.comfyRequestTimeoutMillis.hint': {
    'en-US': 'Request timeout in milliseconds for MiniMax-H3 calls to ComfyUI.',
    'zh-CN': 'MiniMax-H3 向 ComfyUI 发送请求时的响应读取超时毫秒数。',
  },
  'settings.field.integrations.minimaxH3.comfyPollIntervalMillis': {
    'en-US': 'Comfy poll interval (ms)',
    'zh-CN': 'Comfy 轮询间隔（毫秒）',
  },
  'settings.field.integrations.minimaxH3.comfyPollIntervalMillis.hint': {
    'en-US': 'Polling interval in milliseconds for MiniMax-H3 checking ComfyUI progress.',
    'zh-CN': 'MiniMax-H3 轮询 ComfyUI 渲染状态与产物的时间间隔毫秒数。',
  },
  'settings.field.integrations.minimaxH3.comfyMaxWaitMillis': {
    'en-US': 'Comfy max wait (ms)',
    'zh-CN': 'Comfy 最大等待（毫秒）',
  },
  'settings.field.integrations.minimaxH3.comfyMaxWaitMillis.hint': {
    'en-US': 'Maximum total wait duration in milliseconds for MiniMax-H3 ComfyUI rendering.',
    'zh-CN': 'MiniMax-H3 等待 ComfyUI 渲染完成的最大总毫秒数。',
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
  'settings.field.storageMedia.uploadExpiresSeconds.hint': {
    'en-US': 'Expiration period in seconds for temporary upload credentials and upload URLs.',
    'zh-CN': '本地或直传上传凭证及临时上传链接的有效秒数。',
  },
  'settings.field.storageMedia.s3Enabled': {
    'en-US': 'S3 enabled',
    'zh-CN': '启用 S3',
  },
  'settings.field.storageMedia.s3Enabled.hint': {
    'en-US': 'Whether to enable S3-compatible object storage as the persistent media backend.',
    'zh-CN': '是否启用 S3 兼容对象存储作为媒体与素材的持久化后端。',
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
  // --- Advanced ---
  'settings.section.advanced.description': {
    'en-US': 'Process-level runtime budgets for processing, dispatching, events and work notification.',
    'zh-CN': '任务处理、分发、事件与工作通知的进程级运行预算。',
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
