import type { LocaleCatalog } from '@/shared/i18n/types'

/** 资源目录：Agent、模型、Provider、Skill Package 与 MCP Server。 */
export const resourcesCatalog = {
  'ai.catalog.resourceType.agent': {
    'en-US': 'Agent',
    'zh-CN': 'Agent',
  },
  'ai.catalog.resourceType.model': {
    'en-US': 'Model',
    'zh-CN': 'Model',
  },
  'ai.catalog.resourceType.modelGroup': {
    'en-US': 'Model Group',
    'zh-CN': '模型组',
  },
  'ai.catalog.resourceType.provider': {
    'en-US': 'Provider',
    'zh-CN': 'Provider',
  },
  'ai.catalog.createAgent': {
    'en-US': 'Create Agent',
    'zh-CN': '新建 Agent',
  },
  'ai.catalog.createPrefix': {
    'en-US': 'Create',
    'zh-CN': '新建',
  },
  'ai.catalog.editPrefix': {
    'en-US': 'Edit',
    'zh-CN': '编辑',
  },
  'ai.catalog.createAgentDescription': {
    'en-US': 'Combine Model, tools, skills, and policy',
    'zh-CN': '组合模型、工具、技能与策略',
  },
  'ai.catalog.createModel': {
    'en-US': 'Create Model',
    'zh-CN': '新建 Model',
  },
  'ai.catalog.createModelDescription': {
    'en-US': 'Define model information and variants',
    'zh-CN': '定义模型信息与模型预设',
  },
  'ai.catalog.createProvider': {
    'en-US': 'Create Provider',
    'zh-CN': '新建 Provider',
  },
  'ai.catalog.createProviderDescription': {
    'en-US': 'Configure provider and credentials',
    'zh-CN': '配置提供商与凭据',
  },
  'ai.catalog.action.createSession': {
    'en-US': 'Start session',
    'zh-CN': '创建会话',
  },
  'ai.catalog.action.enterConversation': {
    'en-US': 'Open chat',
    'zh-CN': '进入对话',
  },
  'ai.catalog.action.edit': {
    'en-US': 'Edit',
    'zh-CN': '编辑',
  },
  'ai.catalog.action.delete': {
    'en-US': 'Delete',
    'zh-CN': '删除',
  },
  'ai.catalog.action.confirmCreate': {
    'en-US': 'Create',
    'zh-CN': '确认创建',
  },
  'ai.catalog.action.saveChanges': {
    'en-US': 'Save changes',
    'zh-CN': '保存修改',
  },
  'ai.catalog.action.confirmDelete': {
    'en-US': 'Delete',
    'zh-CN': '确认删除',
  },
  'ai.catalog.card.effectiveModel': {
    'en-US': 'Effective Model',
    'zh-CN': '生效模型',
  },
  'ai.catalog.card.modelDefaultSuffix': {
    'en-US': ' · model default',
    'zh-CN': ' · 模型默认预设',
  },
  'ai.catalog.card.unknownVariant': {
    'en-US': 'unresolved',
    'zh-CN': '未解析',
  },
  'ai.catalog.card.modelUnconfigured': {
    'en-US': 'Not configured',
    'zh-CN': '未配置',
  },
  'ai.catalog.card.pricing': { 'en-US': 'Pricing', 'zh-CN': '价格' },
  'ai.catalog.card.pricingSummary': {
    'en-US': '{{input}} / 1M input · {{output}} / 1M output · cache write {{cacheWrite}}',
    'zh-CN': '输入 {{input}} / 百万 tokens · 输出 {{output}} / 百万 tokens · 缓存写入 {{cacheWrite}}',
  },
  'ai.catalog.card.type': {
    'en-US': 'Type',
    'zh-CN': 'Type',
  },
  'ai.catalog.card.url': {
    'en-US': 'URL',
    'zh-CN': 'URL',
  },
  'ai.catalog.card.apiKey': {
    'en-US': 'API Key',
    'zh-CN': 'API Key',
  },
  'ai.catalog.card.timeout': {
    'en-US': 'Timeout',
    'zh-CN': 'Timeout',
  },
  'ai.catalog.card.idle': {
    'en-US': 'Idle',
    'zh-CN': 'Idle',
  },
  'ai.catalog.card.httpRetry': {
    'en-US': 'HTTP Retry',
    'zh-CN': 'HTTP 重试',
  },
  'ai.catalog.card.httpRetryInherit': {
    'en-US': 'Inherit system',
    'zh-CN': '继承系统',
  },
  'ai.catalog.card.httpRetryDisabled': {
    'en-US': 'Disabled',
    'zh-CN': '禁用',
  },
  'ai.catalog.card.configured': {
    'en-US': 'Configured',
    'zh-CN': '已配置',
  },
  'ai.catalog.card.unconfiguredApiKey': {
    'en-US': 'No API key; requests are unauthenticated',
    'zh-CN': '无 API Key，请求不认证',
  },
  'ai.catalog.card.emptyValue': {
    'en-US': '—',
    'zh-CN': '—',
  },
  'ai.catalog.card.contextLimit': {
    'en-US': 'ctx {{value}}',
    'zh-CN': '上下文 {{value}}',
  },
  'ai.catalog.card.outputLimit': {
    'en-US': 'out {{value}}',
    'zh-CN': '输出 {{value}}',
  },
  'ai.catalog.card.seconds': {
    'en-US': '{{value}}s',
    'zh-CN': '{{value}}s',
  },
  'ai.catalog.card.milliseconds': {
    'en-US': '{{value}}ms',
    'zh-CN': '{{value}}ms',
  },
  'ai.catalog.card.toolsAbility': {
    'en-US': 'tools',
    'zh-CN': '工具',
  },
  'ai.catalog.card.reasoningAbility': {
    'en-US': 'reasoning',
    'zh-CN': '推理',
  },
  'ai.catalog.form.name': {
    'en-US': 'Name',
    'zh-CN': '名称',
  },
  'ai.catalog.form.nameHint': {
    'en-US': 'Globally unique logical name shared by Models and Model Groups.',
    'zh-CN': '模型和模型组共用此命名空间，名称不可重复。',
  },
  'ai.catalog.form.groupNameHint': {
    'en-US': 'Globally unique logical name shared with Models.',
    'zh-CN': '与单 Model 共享的全局唯一逻辑名。',
  },
  'ai.catalog.form.modelKind': {
    'en-US': 'Resource type',
    'zh-CN': '资源类型',
  },
  'ai.catalog.form.groupDescriptionPlaceholder': {
    'en-US': 'Ordered fallback for coding tasks',
    'zh-CN': '按序降级的编码模型组合',
  },
  'ai.catalog.form.groupMembers': {
    'en-US': 'Model list',
    'zh-CN': '模型列表',
  },
  'ai.catalog.form.addGroupMember': {
    'en-US': 'Add model',
    'zh-CN': '添加模型',
  },
  'ai.catalog.form.groupMemberModel': {
    'en-US': 'Member model {{index}}',
    'zh-CN': '成员模型 {{index}}',
  },
  'ai.catalog.form.groupMemberModelPlaceholder': {
    'en-US': 'Select a Model',
    'zh-CN': '选择 Model',
  },
  'ai.catalog.form.groupMemberVariant': {
    'en-US': 'Member variant {{index}}',
    'zh-CN': '成员预设 {{index}}',
  },
  'ai.catalog.form.groupMemberVariantPlaceholder': {
    'en-US': 'Select a Variant',
    'zh-CN': '选择模型预设',
  },
  'ai.catalog.form.moveGroupMemberUp': {
    'en-US': 'Move member up',
    'zh-CN': '成员上移',
  },
  'ai.catalog.form.moveGroupMemberDown': {
    'en-US': 'Move member down',
    'zh-CN': '成员下移',
  },
  'ai.catalog.form.removeGroupMember': {
    'en-US': 'Remove member {{index}}',
    'zh-CN': '删除成员 {{index}}',
  },
  'ai.catalog.form.noGroupModels': {
    'en-US': 'No Models yet. Create one on the Models page first.',
    'zh-CN': '暂无模型，请先在模型页创建',
  },
  'ai.catalog.form.groupMembersRequired': {
    'en-US': 'Add at least one model',
    'zh-CN': '请至少添加一个模型',
  },
  'ai.catalog.form.groupSummaryAbilities': {
    'en-US': 'Shared abilities',
    'zh-CN': '共同能力',
  },
  'ai.catalog.form.fallbackStatusCodes': {
    'en-US': 'Immediate fallback status codes',
    'zh-CN': '立即降级状态码',
  },
  'ai.catalog.form.fallbackStatusCodesPlaceholder': {
    'en-US': 'Type a status code...',
    'zh-CN': '输入状态码...',
  },
  'ai.catalog.form.fallbackStatusCodesHint': {
    'en-US': 'HTTP 400–599. Matching responses immediately move to the next member.',
    'zh-CN': 'HTTP 400–599；命中后立即切换到下一成员。',
  },
  'ai.catalog.form.cooldownSeconds': {
    'en-US': 'Cooldown time',
    'zh-CN': '冷却时间',
  },
  'ai.catalog.form.cooldownHint': {
    'en-US': '0: skip only for this call; no cooldown is stored.',
    'zh-CN': '0：仅本次降级，不保存冷却状态。',
  },
  'ai.catalog.card.groupMembers': {
    'en-US': 'Model list',
    'zh-CN': '模型列表',
  },
  'ai.catalog.card.moreMembers': {
    'en-US': '+{{count}} more',
    'zh-CN': '另 {{count}} 个',
  },
  'ai.catalog.card.fallbackStatusCodes': {
    'en-US': 'Immediate fallback codes',
    'zh-CN': '立即降级码',
  },
  'ai.catalog.card.cooldownSeconds': {
    'en-US': 'Cooldown',
    'zh-CN': '冷却时间',
  },
  'ai.catalog.catalog.title': {
    'en-US': 'Catalog defaults',
    'zh-CN': '目录默认参数',
  },
  'ai.catalog.catalog.open': {
    'en-US': 'Get default model parameters',
    'zh-CN': '获取默认模型参数',
  },
  'ai.catalog.catalog.hide': {
    'en-US': 'Hide catalog defaults',
    'zh-CN': '收起目录默认参数',
  },
  'ai.catalog.catalog.searchPlaceholder': {
    'en-US': 'Search catalog models...',
    'zh-CN': '搜索目录模型...',
  },
  'ai.catalog.catalog.refresh': {
    'en-US': 'Refresh',
    'zh-CN': '刷新',
  },
  'ai.catalog.catalog.retry': {
    'en-US': 'Retry',
    'zh-CN': '重试',
  },
  'ai.catalog.catalog.fill': {
    'en-US': 'Fill',
    'zh-CN': '一键填入',
  },
  'ai.catalog.catalog.empty': {
    'en-US': 'No matching catalog models.',
    'zh-CN': '没有匹配的目录模型。',
  },
  'ai.catalog.catalog.loadMore': {
    'en-US': 'Load more',
    'zh-CN': '加载更多',
  },
  'ai.catalog.catalog.noVariants': {
    'en-US': 'No importable Variants',
    'zh-CN': '无可导入的模型预设',
  },
  'ai.catalog.catalog.unsupportedPricing': {
    'en-US': 'Pricing is not fully supported; existing prices are kept.',
    'zh-CN': '价格未完整支持；保留现有价格。',
  },
  'ai.catalog.catalog.priceSummary': {
    'en-US': 'Input {{input}} / output {{output}} / cache read {{cacheRead}} / cache write {{cacheWrite}} per 1M tokens · {{ranges}} input bands · {{tiers}} service tiers · {{timePeriods}} time periods',
    'zh-CN': '输入 {{input}} / 输出 {{output}} / 缓存读取 {{cacheRead}} / 缓存写入 {{cacheWrite}}（每百万 tokens）· {{ranges}} 个输入档位 · {{tiers}} 个服务档 · {{timePeriods}} 个时段',
  },
  'ai.catalog.catalog.issue.ignoredRegionMultiplier': {
    'en-US': 'Region multipliers ignored; estimated costs may be lower than your actual bill',
    'zh-CN': '已忽略地区倍率，统计费用可能低于实际账单',
  },
  'ai.catalog.catalog.issue.nonTokenCostWebSearch': {
    'en-US': 'Web search surcharges are not included in token prices',
    'zh-CN': '网页搜索附加费用未计入Token价格',
  },
  'ai.catalog.form.modelId': {
    'en-US': 'Model ID',
    'zh-CN': '模型 ID',
  },
  'ai.catalog.form.modelIdHint': {
    'en-US': 'Upstream model ID sent to the Provider; may differ from Name.',
    'zh-CN': '发送给供应商的上游模型 ID，可以与名称不同。',
  },
  'ai.catalog.form.remoteModelsFetch': {
    'en-US': 'Fetch models from Provider',
    'zh-CN': '从供应商拉取模型',
  },
  'ai.catalog.form.remoteModelsLoading': {
    'en-US': 'Fetching models…',
    'zh-CN': '正在拉取模型…',
  },
  'ai.catalog.form.remoteModelsLoaded': {
    'en-US': '{{count}} models found; pick one in Model ID.',
    'zh-CN': '找到 {{count}} 个模型，可在模型 ID 中选择。',
  },
  'ai.catalog.form.remoteModelsEmpty': {
    'en-US': 'The Provider listed no models.',
    'zh-CN': '供应商未列出任何模型。',
  },
  'ai.catalog.form.description': {
    'en-US': 'Description',
    'zh-CN': '说明',
  },
  'ai.catalog.form.descriptionPlaceholder': {
    'en-US': 'Purpose or description',
    'zh-CN': '用途说明',
  },
  'ai.catalog.form.modelDescriptionPlaceholder': {
    'en-US': 'Model description',
    'zh-CN': '模型说明',
  },
  'ai.catalog.form.defaultModel': {
    'en-US': 'Default Model',
    'zh-CN': '默认模型',
  },
  'ai.catalog.form.defaultVariantOverride': {
    'en-US': 'Default Variant Override',
    'zh-CN': '模型预设覆盖',
  },
  'ai.catalog.form.systemPrompt': {
    'en-US': 'System Prompt',
    'zh-CN': '系统提示词',
  },
  'ai.catalog.form.systemPromptPlaceholder': {
    'en-US': 'System prompt',
    'zh-CN': '系统提示词',
  },
  'ai.catalog.form.apiKeyOptional': {
    'en-US': 'API Key',
    'zh-CN': 'API Key',
  },
  'ai.catalog.form.provider': {
    'en-US': 'Provider',
    'zh-CN': '供应商',
  },
  'ai.catalog.form.providerType': {
    'en-US': 'Provider Type',
    'zh-CN': 'Provider Type',
  },
  'ai.catalog.form.baseUrl': {
    'en-US': 'Base URL',
    'zh-CN': 'Base URL',
  },
  'ai.catalog.form.modelCallTimeout': {
    'en-US': 'Model Call Timeout (ms)',
    'zh-CN': 'Model Call Timeout (ms)',
  },
  'ai.catalog.form.modelCallIdleTimeout': {
    'en-US': 'Model Call Idle Timeout (ms)',
    'zh-CN': 'Model Call Idle Timeout (ms)',
  },
  'ai.catalog.form.httpRetryPolicy': {
    'en-US': 'HTTP Retry Policy',
    'zh-CN': 'HTTP 错误重试策略',
  },
  'ai.catalog.form.httpRetryInherit': {
    'en-US': 'Inherit system settings',
    'zh-CN': '继承系统配置',
  },
  'ai.catalog.form.httpRetryInheritHint': {
    'en-US': 'Inherits the system-wide HTTP retry status code list.',
    'zh-CN': '当前 Provider 继承系统全局配置的 HTTP 重试状态码名单。',
  },
  'ai.catalog.form.httpRetryCustom': {
    'en-US': 'Custom status codes',
    'zh-CN': '自定义重试名单',
  },
  'ai.catalog.form.httpRetryPlaceholder': {
    'en-US': 'Enter status code (400-599), press Enter or comma',
    'zh-CN': '输入 400-599 状态码，按 Enter 或逗号添加',
  },
  'ai.catalog.form.contextWindow': {
    'en-US': 'Context Window',
    'zh-CN': '上下文窗口',
  },
  'ai.catalog.form.executionDriver': {
    'en-US': 'Execution driver',
    'zh-CN': '执行驱动',
  },
  'ai.catalog.form.maxOutputTokens': {
    'en-US': 'Max Output Tokens',
    'zh-CN': '最大输出 token 数',
  },
  'ai.catalog.form.inputLimit': {
    'en-US': 'Input Limit',
    'zh-CN': '输入上限',
  },
  'ai.catalog.form.inputLimitHint': {
    'en-US': 'Optional cap on prompt tokens per request; leave empty to use the context window.',
    'zh-CN': '可选的每次请求输入 token 上限；留空表示仅受上下文窗口约束。',
  },
  'ai.catalog.form.limit': {
    'en-US': 'Limit',
    'zh-CN': '请求限制',
  },
  'ai.catalog.form.abilities': {
    'en-US': 'Abilities',
    'zh-CN': '功能',
  },
  'ai.catalog.form.tools': {
    'en-US': 'Tools',
    'zh-CN': '工具',
  },
  'ai.catalog.form.reasoning': {
    'en-US': 'Reasoning',
    'zh-CN': '推理',
  },
  'ai.catalog.form.midConversationSystemMessages': {
    'en-US': 'Mid-conversation system messages',
    'zh-CN': '对话中的系统消息更新',
  },
  'ai.catalog.form.messageLevelEffort': {
    'en-US': 'Per-message reasoning effort',
    'zh-CN': '消息级推理强度',
  },
  'ai.catalog.form.inputTypes': {
    'en-US': 'Input types',
    'zh-CN': '输入类型',
  },
  'ai.catalog.form.pricing': {
    'en-US': 'Pricing',
    'zh-CN': '价格',
  },
  'ai.catalog.form.inputPrice': {
    'en-US': 'Input',
    'zh-CN': '输入',
  },
  'ai.catalog.form.outputPrice': {
    'en-US': 'Output',
    'zh-CN': '输出',
  },
  'ai.catalog.form.cacheReadPrice': {
    'en-US': 'Cache Read',
    'zh-CN': '缓存读取',
  },
  'ai.catalog.form.cacheWritePrice': {
    'en-US': 'Cache Write',
    'zh-CN': '缓存写入',
  },
  'ai.catalog.form.reasoningPrice': {
    'en-US': 'Reasoning',
    'zh-CN': '推理',
  },
  'ai.catalog.form.defaultVariant': {
    'en-US': 'Default Variant',
    'zh-CN': '默认预设',
  },
  'ai.catalog.form.presetList': {
    'en-US': 'Preset list',
    'zh-CN': '预设列表',
  },
  'ai.catalog.form.variantId': {
    'en-US': 'Variant ID',
    'zh-CN': '预设 ID',
  },
  'ai.catalog.form.promptCacheRetention': {
    'en-US': 'Prompt Cache Retention',
    'zh-CN': '提示缓存',
  },
  'ai.catalog.form.promptCacheRetentionAria': {
    'en-US': 'Prompt Cache Retention',
    'zh-CN': '提示缓存留存',
  },
  'ai.catalog.form.promptCacheRetentionNone': {
    'en-US': 'Off',
    'zh-CN': '关闭',
  },
  'ai.catalog.form.promptCacheRetentionShort': {
    'en-US': 'Standard',
    'zh-CN': '标准',
  },
  'ai.catalog.form.promptCacheRetentionLong': {
    'en-US': 'Long',
    'zh-CN': '长',
  },
  'ai.catalog.form.advancedOptions': {
    'en-US': 'Advanced options',
    'zh-CN': '高级选项',
  },
  'ai.catalog.form.reasoningEffort': {
    'en-US': 'Reasoning Effort',
    'zh-CN': '思考强度',
  },
  'ai.catalog.form.reasoningEffortPlaceholder': {
    'en-US': 'e.g. high, max, off',
    'zh-CN': '例如 high、max、off',
  },
  'ai.catalog.form.protocolOptions': {
    'en-US': 'Protocol Options',
    'zh-CN': '原生协议选项',
  },
  'ai.catalog.form.protocolOptionsPlaceholder': {
    'en-US': '{\n  "temperature": 0.7\n}',
    'zh-CN': '{\n  "temperature": 0.7\n}',
  },
  'ai.catalog.form.protocolOptionsHint': {
    'en-US': 'Optional JSON object (max 65536 UTF-8 bytes). Duplicate keys are rejected on save; numeric text is preserved.',
    'zh-CN': '可留空；JSON 对象最多 65536 UTF-8 字节。保存时拒绝重复键，数字文本原样保留。',
  },
  'ai.catalog.form.useModelDefault': {
    'en-US': 'Use model default',
    'zh-CN': '使用模型默认预设',
  },
  'ai.catalog.form.optionalCredential': {
    'en-US': 'Optional',
    'zh-CN': '可留空',
  },
  'ai.catalog.form.keepCredential': {
    'en-US': 'Leave blank to keep the current key',
    'zh-CN': '留空保留当前密钥',
  },
  'ai.catalog.form.credentialCleared': {
    'en-US': 'Removed when saved',
    'zh-CN': '保存后移除',
  },
  'ai.catalog.form.clearCredential': {
    'en-US': 'Clear saved credential',
    'zh-CN': '清除已保存的凭据',
  },
  'ai.catalog.form.credentialEditHint': {
    'en-US': 'Leave blank to keep the configured API Key; the secret is never echoed.',
    'zh-CN': '留空会保留已配置的 API Key；密钥不会回显。',
  },
  'ai.catalog.form.credentialCreateHint': {
    'en-US': 'Leave blank to call the OpenAI-compatible endpoint without Authorization.',
    'zh-CN': '留空会以无 Authorization 方式请求 OpenAI-compatible 端点。',
  },
  'ai.catalog.form.noCandidateTools': {
    'en-US': 'No tools in the catalog yet',
    'zh-CN': '目录还没有工具',
  },
  'ai.catalog.sectionNavigation': { 'en-US': 'Section navigation', 'zh-CN': '区块导航' },
  'ai.catalog.section.basics': { 'en-US': 'Basic information', 'zh-CN': '基本信息' },
  'ai.catalog.section.limits': { 'en-US': 'Request limits and capabilities', 'zh-CN': '请求限制与能力' },
  'ai.catalog.section.presets': { 'en-US': 'Model presets', 'zh-CN': '模型预设' },
  'ai.catalog.section.pricing': { 'en-US': 'Pricing and rates', 'zh-CN': '价格与费率' },
  'ai.catalog.section.execution': { 'en-US': 'Execution', 'zh-CN': '执行' },
  'ai.catalog.section.prompt': { 'en-US': 'System prompt', 'zh-CN': '系统提示词' },
  'ai.catalog.section.capabilities': { 'en-US': 'Tools, skills and subagents', 'zh-CN': '工具 / 技能 / 子代理' },
  'ai.catalog.form.agentTools': { 'en-US': 'Tools', 'zh-CN': '工具' },
  'ai.catalog.form.agentSkills': { 'en-US': 'Skills', 'zh-CN': '技能' },
  'ai.catalog.form.agentSubagents': { 'en-US': 'Subagents', 'zh-CN': '子代理' },
  'ai.catalog.form.manageTools': { 'en-US': 'Manage tools', 'zh-CN': '管理工具' },
  'ai.catalog.form.manageSkills': { 'en-US': 'Manage skills', 'zh-CN': '管理技能' },
  'ai.catalog.form.manageSubagents': { 'en-US': 'Manage subagents', 'zh-CN': '管理子代理' },
  'ai.catalog.form.searchTools': { 'en-US': 'Search tools', 'zh-CN': '搜索工具' },
  'ai.catalog.form.searchSkills': { 'en-US': 'Search skills', 'zh-CN': '搜索技能' },
  'ai.catalog.form.searchSubagents': { 'en-US': 'Search subagents', 'zh-CN': '搜索子代理' },
  'ai.catalog.form.selectedCount': { 'en-US': '{{count}} selected', 'zh-CN': '已选 {{count}}' },
  'ai.catalog.form.allCapabilities': { 'en-US': 'All', 'zh-CN': '全部' },
  'ai.catalog.form.selectedCapabilities': { 'en-US': 'Selected', 'zh-CN': '已选' },
  'ai.catalog.form.noMatchingCapabilities': { 'en-US': 'No matching items', 'zh-CN': '没有匹配项' },
  'ai.catalog.form.noCandidateSkills': {
    'en-US': 'No skills in the catalog yet',
    'zh-CN': '目录还没有技能',
  },
  'ai.catalog.form.noCandidateSubagents': {
    'en-US': 'No subagents in the catalog yet',
    'zh-CN': '目录还没有子代理',
  },
  'ai.catalog.form.needModel': {
    'en-US': 'Create a Model first to configure an Agent.',
    'zh-CN': '需要先创建 Model 才能配置 Agent。',
  },
  'ai.catalog.form.fillFirstModel': {
    'en-US': 'Use the first model',
    'zh-CN': '使用第一个模型',
  },
  'ai.catalog.form.unavailable': {
    'en-US': 'Unavailable',
    'zh-CN': '不可用',
  },
  'ai.catalog.form.unavailableIdentityHint': {
    'en-US': 'Unavailable; the original identity is retained when saving other fields.',
    'zh-CN': '不可用；保存其他字段时仍保留原始身份。',
  },
  'ai.catalog.form.unavailableModelVariants': {
    'en-US': 'Variant options are unavailable because the referenced Model is not loaded; the saved override is preserved.',
    'zh-CN': '引用的模型未加载，无法选择模型预设；已保存的覆盖值会保留。',
  },
  'ai.catalog.form.offline': {
    'en-US': 'Offline',
    'zh-CN': 'offline',
  },
  'ai.catalog.form.variantHint': {
    'en-US':
      'A Variant carries reasoning effort and protocol options; IDs must be unique, and Default Variant must point to one of them.',
    'zh-CN': '模型预设包含思考强度与原生协议选项。预设 ID 不可重复，默认预设必须是其中一项。',
  },
  'ai.catalog.form.reasoningHint': {
    'en-US':
      ' Leave empty to keep provider protocol default; off = explicitly disable reasoning; provider-defined values (e.g. low, medium, high, max) are passed through.',
    'zh-CN':
      ' 留空使用供应商协议的默认值；off=显式关闭推理；支持厂商自定义值（如 low、medium、high、max 等）。',
  },
  'ai.catalog.form.reasoningDisabledHint': {
    'en-US':
      ' Reasoning is disabled. To configure reasoning effort, check Reasoning above to enable it.',
    'zh-CN': '推理当前已关闭；若需配置思考强度，请勾选上方的「推理」。',
  },
  'ai.catalog.form.maxOutputLimitHint': {
    'en-US': 'Model-level output budget; each request also narrows it by the remaining context.',
    'zh-CN': '模型级输出预算；每次请求还会按剩余上下文收敛。',
  },
  'ai.catalog.form.inputModalityHint': {
    'en-US': 'Input modalities accepted by the model; keep at least one.',
    'zh-CN': '模型可接受的输入模态；至少保留一种。',
  },
  'ai.catalog.form.reasoningPricing': {
    'en-US': 'Independent reasoning pricing',
    'zh-CN': '思考独立定价',
  },
  'ai.catalog.form.cacheWriteTtl': {
    'en-US': 'Cache write TTL overrides',
    'zh-CN': '缓存写入 TTL 覆盖',
  },
  'ai.catalog.form.overrideTtl': {
    'en-US': 'Override cache write TTL',
    'zh-CN': '覆盖缓存写入 TTL',
  },
  'ai.catalog.form.overrideTtlHint': {
    'en-US': 'Unchecked inherits the base map; checked replaces it. An empty map explicitly clears inherited TTL prices.',
    'zh-CN': '未勾选继承基础映射；勾选后整体替换。显式空映射用于清空继承的 TTL 价格。',
  },
  'ai.catalog.form.ttlSeconds': {
    'en-US': 'TTL seconds',
    'zh-CN': 'TTL 秒数',
  },
  'ai.catalog.form.ttlRate': {
    'en-US': 'TTL cache write price',
    'zh-CN': 'TTL 缓存写入价',
  },
  'ai.catalog.form.addTtl': {
    'en-US': 'Add TTL override',
    'zh-CN': '添加 TTL 覆盖',
  },
  'ai.catalog.form.removeTtl': {
    'en-US': 'Remove TTL override',
    'zh-CN': '删除 TTL 覆盖',
  },
  'ai.catalog.form.priceOverrides': {
    'en-US': 'Price overrides',
    'zh-CN': '价格覆盖',
  },
  'ai.catalog.form.addPriceOverride': {
    'en-US': 'Add price override',
    'zh-CN': '添加价格覆盖',
  },
  'ai.catalog.form.priceMultipliers': {
    'en-US': 'Price multipliers',
    'zh-CN': '价格倍率',
  },
  'ai.catalog.form.addPriceMultiplier': {
    'en-US': 'Add price multiplier',
    'zh-CN': '添加价格倍率',
  },
  'ai.catalog.form.noPriceRules': {
    'en-US': 'No rules',
    'zh-CN': '暂无规则',
  },
  'ai.catalog.form.moveRuleUp': {
    'en-US': 'Move rule up',
    'zh-CN': '规则上移',
  },
  'ai.catalog.form.moveRuleDown': {
    'en-US': 'Move rule down',
    'zh-CN': '规则下移',
  },
  'ai.catalog.form.removeRule': {
    'en-US': 'Remove rule',
    'zh-CN': '删除规则',
  },
  'ai.catalog.form.overrideRates': {
    'en-US': 'Override rates',
    'zh-CN': '覆盖费率',
  },
  'ai.catalog.form.inheritPlaceholder': {
    'en-US': 'Inherit',
    'zh-CN': '继承',
  },
  'ai.catalog.form.overrideField': {
    'en-US': 'Override {{label}}',
    'zh-CN': '覆盖 {{label}}',
  },
  'ai.catalog.form.multiplierFactors': {
    'en-US': 'Multiplier factors',
    'zh-CN': '倍率因子',
  },
  'ai.catalog.form.multiplierField': {
    'en-US': 'Multiplier {{label}}',
    'zh-CN': '倍率 {{label}}',
  },
  'ai.catalog.form.condition': {
    'en-US': 'Condition',
    'zh-CN': '条件',
  },
  'ai.catalog.form.addCondition': {
    'en-US': 'Add condition',
    'zh-CN': '添加条件',
  },
  'ai.catalog.form.removeCondition': {
    'en-US': 'Remove {{label}}',
    'zh-CN': '删除 {{label}}',
  },
  'ai.catalog.form.removeNestedCondition': {
    'en-US': 'Remove nested condition',
    'zh-CN': '删除子条件',
  },
  'ai.catalog.form.conditionContext': {
    'en-US': 'Context tokens',
    'zh-CN': '上下文 token',
  },
  'ai.catalog.form.conditionServiceTier': {
    'en-US': 'Service tier',
    'zh-CN': '服务档位',
  },
  'ai.catalog.form.conditionDate': {
    'en-US': 'Date',
    'zh-CN': '日期',
  },
  'ai.catalog.form.conditionTime': {
    'en-US': 'Time',
    'zh-CN': '时间',
  },
  'ai.catalog.form.conditionRequestBody': {
    'en-US': 'Request body',
    'zh-CN': '请求体字段',
  },
  'ai.catalog.form.requestBodyPath': {
    'en-US': 'JSON Pointer path',
    'zh-CN': 'JSON Pointer 路径',
  },
  'ai.catalog.form.requestBodyType': {
    'en-US': 'Value type',
    'zh-CN': '值类型',
  },
  'ai.catalog.form.requestBodyType.string': {
    'en-US': 'String',
    'zh-CN': '字符串',
  },
  'ai.catalog.form.requestBodyType.number': {
    'en-US': 'Number',
    'zh-CN': '数字',
  },
  'ai.catalog.form.requestBodyType.boolean': {
    'en-US': 'Boolean',
    'zh-CN': '布尔值',
  },
  'ai.catalog.form.requestBodyType.null': {
    'en-US': 'Null',
    'zh-CN': '空值',
  },
  'ai.catalog.form.requestBodyValue': {
    'en-US': 'Expected value',
    'zh-CN': '期望值',
  },
  'ai.catalog.form.requestBodyHint': {
    'en-US': 'Use RFC 6901 JSON Pointer (for example /speed or /options/0/mode). Strings are case-sensitive; numbers compare exactly.',
    'zh-CN': '使用 RFC 6901 JSON Pointer（如 /speed 或 /options/0/mode）；字符串区分大小写，数字按精确十进制比较。',
  },
  'ai.catalog.form.requestBodySourceHint': {
    'en-US': 'Matches the final encoded Provider JSON. Client-controlled values can affect price by administrator choice; they do not prove the upstream tier.',
    'zh-CN': '匹配最终编码后的 Provider JSON；客户端可控值会按管理员配置影响价格，但不代表上游实际档位。',
  },
  'ai.catalog.form.condition.all': {
    'en-US': 'All of',
    'zh-CN': '全部满足',
  },
  'ai.catalog.form.condition.any': {
    'en-US': 'Any of',
    'zh-CN': '任一满足',
  },
  'ai.catalog.form.condition.not': {
    'en-US': 'Not',
    'zh-CN': '取反',
  },
  'ai.catalog.form.add.all': {
    'en-US': 'Add all-of condition',
    'zh-CN': '添加全部满足子条件',
  },
  'ai.catalog.form.add.any': {
    'en-US': 'Add any-of condition',
    'zh-CN': '添加任一满足子条件',
  },
  'ai.catalog.form.context.gt': {
    'en-US': 'Greater than',
    'zh-CN': '大于',
  },
  'ai.catalog.form.context.gte': {
    'en-US': 'At least',
    'zh-CN': '大于等于',
  },
  'ai.catalog.form.context.lt': {
    'en-US': 'Less than',
    'zh-CN': '小于',
  },
  'ai.catalog.form.context.lte': {
    'en-US': 'At most',
    'zh-CN': '小于等于',
  },
  'ai.catalog.form.dateFrom': {
    'en-US': 'Date from',
    'zh-CN': '起始日期',
  },
  'ai.catalog.form.dateBefore': {
    'en-US': 'Exclusive end date',
    'zh-CN': '截止日期，不含当天',
  },
  'ai.catalog.form.timeStart': {
    'en-US': 'Start time',
    'zh-CN': '开始时间',
  },
  'ai.catalog.form.timeEnd': {
    'en-US': 'End time',
    'zh-CN': '结束时间',
  },
  'ai.catalog.form.timeZone': {
    'en-US': 'IANA time zone',
    'zh-CN': 'IANA 时区',
  },
  'ai.catalog.form.timeHint': {
    'en-US': 'Use HH:mm. End may be 24:00; 00:00-24:00 means all day, and start must differ from end.',
    'zh-CN': '格式 HH:mm；结束时间可为 24:00，00:00-24:00 表示全天，开始与结束不能相同。',
  },
  'ai.catalog.form.weekday.1': {
    'en-US': 'Mon',
    'zh-CN': '周一',
  },
  'ai.catalog.form.weekday.2': {
    'en-US': 'Tue',
    'zh-CN': '周二',
  },
  'ai.catalog.form.weekday.3': {
    'en-US': 'Wed',
    'zh-CN': '周三',
  },
  'ai.catalog.form.weekday.4': {
    'en-US': 'Thu',
    'zh-CN': '周四',
  },
  'ai.catalog.form.weekday.5': {
    'en-US': 'Fri',
    'zh-CN': '周五',
  },
  'ai.catalog.form.weekday.6': {
    'en-US': 'Sat',
    'zh-CN': '周六',
  },
  'ai.catalog.form.weekday.7': {
    'en-US': 'Sun',
    'zh-CN': '周日',
  },
  'ai.catalog.form.pricingUnpriced': {
    'en-US': 'Unpriced',
    'zh-CN': '未定价',
  },
  'ai.catalog.form.pricingBase': { 'en-US': 'Base prices', 'zh-CN': '基础价格' },
  'ai.catalog.form.pricingRanges': { 'en-US': 'Input length prices', 'zh-CN': '输入档位' },
  'ai.catalog.form.pricingTiers': { 'en-US': 'Service tiers', 'zh-CN': '服务档' },
  'ai.catalog.form.pricingTimes': { 'en-US': 'Time periods', 'zh-CN': '时段规则' },
  'ai.catalog.form.pricingMillionTokens': { 'en-US': 'million tokens', 'zh-CN': '百万 token' },
  'ai.catalog.form.pricingBasePlan': { 'en-US': 'Base plan', 'zh-CN': '基础方案' },
  'ai.catalog.form.pricingActive': { 'en-US': 'Currently effective', 'zh-CN': '当前生效' },
  'ai.catalog.form.pricingSelectPlan': { 'en-US': 'Price plan', 'zh-CN': '编辑价格方案' },
  'ai.catalog.form.pricingCreateDraft': { 'en-US': 'Create price change', 'zh-CN': '创建调价草稿' },
  'ai.catalog.form.pricingRemoveChange': { 'en-US': 'Remove price change', 'zh-CN': '删除调价' },
  'ai.catalog.form.pricingDuplicateDate': { 'en-US': 'This effective date already has a plan.', 'zh-CN': '该生效日期已有调价方案。' },
  'ai.catalog.form.pricingHistoryWarning': {
    'en-US': 'Editing or removing a plan that has taken effect also recalculates displayed historical costs. Add a future price change to keep past prices unchanged.',
    'zh-CN': '修改或删除已生效的方案也会重新计算历史费用展示。若只调整未来价格，请添加调价。',
  },
  'ai.catalog.form.pricingNotConfigured': { 'en-US': 'No pricing configured', 'zh-CN': '未配置价格' },
  'ai.catalog.form.pricingNotSet': { 'en-US': 'Not set', 'zh-CN': '未设置' },
  'ai.catalog.form.pricingRateRequired': { 'en-US': 'Enter a rate, or 0 for a zero rate.', 'zh-CN': '请输入费率；零价请填 0。' },
  'ai.catalog.form.pricingReasoningRate': { 'en-US': 'Reasoning rate', 'zh-CN': '推理费率' },
  'ai.catalog.form.pricingFactorOverrides': { 'en-US': 'Per-rate factors', 'zh-CN': '按费用项覆盖倍率' },
  'ai.catalog.form.pricingOvernight': { 'en-US': 'Across midnight, this becomes: {{intervals}}', 'zh-CN': '跨午夜后实际生效区间：{{intervals}}' },
  'ai.catalog.form.pricingConfirmSplit': { 'en-US': 'Confirm split', 'zh-CN': '确认拆分' },
  'ai.catalog.form.pricingOverlapTokens': { 'en-US': 'Token range overlaps rule {{rows}}.', 'zh-CN': '输入区间与规则 {{rows}} 重叠。' },
  'ai.catalog.form.pricingDuplicateTier': { 'en-US': 'Same service tier as rule {{rows}}.', 'zh-CN': '服务档名称与规则 {{rows}} 重复。' },
  'ai.catalog.form.pricingOverlapTime': { 'en-US': 'Time interval overlaps rule {{rows}}.', 'zh-CN': '生效时段与规则 {{rows}} 重叠。' },
  'ai.catalog.form.pricingTtlInvalid': { 'en-US': 'Enter a positive whole number of seconds.', 'zh-CN': '请输入正整数秒数。' },
  'ai.catalog.form.pricingTtlDuplicate': { 'en-US': 'This TTL duration is repeated.', 'zh-CN': '该 TTL 时长重复。' },
  'ai.catalog.form.pricingPreviewScope': {
    'en-US': 'Preview rates for the entire draft. Request time is in UTC. This does not save prices or run the model.',
    'zh-CN': '按整份价格草稿试算费率。请求时间使用 UTC；试算不会保存价格或调用模型。',
  },
  'ai.catalog.form.pricingPreviewAddTime': { 'en-US': 'Enter request time', 'zh-CN': '补充请求时间' },
  'ai.catalog.form.pricingPreviewAddTier': { 'en-US': 'Enter service tier', 'zh-CN': '补充服务档' },
  'ai.catalog.form.addPricingRange': { 'en-US': 'Add range', 'zh-CN': '添加输入档位' },
  'ai.catalog.form.addPricingTier': { 'en-US': 'Add service tier', 'zh-CN': '添加服务档' },
  'ai.catalog.form.addPricingTime': { 'en-US': 'Add time period', 'zh-CN': '添加时段' },
  'ai.catalog.form.addPricingChange': { 'en-US': 'Add price change', 'zh-CN': '添加调价' },
  'ai.catalog.form.removePricingRule': { 'en-US': 'Remove rule', 'zh-CN': '删除规则' },
  'ai.catalog.form.pricingFactor': { 'en-US': 'All prices ×', 'zh-CN': '所有费用项倍率' },
  'ai.catalog.form.pricingTierName': { 'en-US': 'Service tier', 'zh-CN': '服务档名称' },
  'ai.catalog.form.pricingMinTokens': { 'en-US': 'Start (inclusive)', 'zh-CN': '起点（含）' },
  'ai.catalog.form.pricingMaxTokens': { 'en-US': 'End (exclusive)', 'zh-CN': '终点（不含）' },
  'ai.catalog.form.pricingTimezone': { 'en-US': 'Timezone', 'zh-CN': '时区' },
  'ai.catalog.form.pricingStart': { 'en-US': 'Start time', 'zh-CN': '开始时间' },
  'ai.catalog.form.pricingEnd': { 'en-US': 'End time', 'zh-CN': '结束时间' },
  'ai.catalog.form.pricingWeekdays': { 'en-US': 'Weekdays (ISO)', 'zh-CN': '星期（ISO）' },
  'ai.catalog.form.pricingFromDate': { 'en-US': 'Effective from (UTC)', 'zh-CN': '生效日期（UTC）' },
  'ai.catalog.form.pricingPreview': { 'en-US': 'Rate preview', 'zh-CN': '费率试算' },
  'ai.catalog.form.pricingInputTokens': { 'en-US': 'Input tokens', 'zh-CN': '输入 tokens' },
  'ai.catalog.form.pricingServiceTier': { 'en-US': 'Returned service tier', 'zh-CN': '实际返回服务档' },
  'ai.catalog.form.pricingPreviewUnknown': { 'en-US': 'Unable to determine rates.', 'zh-CN': '无法确定费率。' },
  'ai.catalog.form.addPricingTtl': { 'en-US': 'Add TTL price', 'zh-CN': '添加 TTL 写入价' },
  'ai.catalog.form.pricingTtlSeconds': { 'en-US': 'TTL seconds', 'zh-CN': 'TTL 秒数' },
  'ai.catalog.form.pricingTtlMode': { 'en-US': 'Cache write TTL pricing', 'zh-CN': '缓存写入 TTL 定价' },
  'ai.catalog.form.pricingTtlInherit': { 'en-US': 'Inherit', 'zh-CN': '继承' },
  'ai.catalog.form.pricingTtlCustom': { 'en-US': 'Custom TTL prices', 'zh-CN': '自定义 TTL 价格' },
  'ai.catalog.form.pricingTtlUnpartitioned': { 'en-US': 'Unpartitioned', 'zh-CN': '不分档' },
  'ai.catalog.form.reasoningFollowOutput': { 'en-US': 'Follow output price', 'zh-CN': '跟随输出价' },
  'ai.catalog.form.reasoningSeparate': { 'en-US': 'Independent price', 'zh-CN': '独立计价' },
  'ai.catalog.form.pricingImportUnsupported': {
    'en-US': 'Directory pricing was not imported. Your existing price is unchanged.',
    'zh-CN': '目录价格无法等价导入，原有价格已保留。',
  },
  'ai.catalog.form.pricingImportExcluded': {
    'en-US': 'Some directory charges are not included in token prices.',
    'zh-CN': '部分目录费用不属于 token 单价，未计入。',
  },
  'ai.catalog.form.pricingImportNoData': {
    'en-US': 'The directory has no complete price to import.',
    'zh-CN': '目录没有可导入的完整价格。',
  },
  'ai.catalog.form.viewCatalogPrice': { 'en-US': 'View directory price', 'zh-CN': '查看目录价格' },
  'ai.catalog.form.pricingPreviewRun': { 'en-US': 'Preview prices', 'zh-CN': '试算价格' },
  'ai.catalog.form.pricingPreviewInvalidTokens': { 'en-US': 'Enter a non-negative whole token count.', 'zh-CN': '请输入非负整数 token 数。' },
  'ai.catalog.form.pricingPreviewInvalidTime': { 'en-US': 'Enter a valid UTC date and time.', 'zh-CN': '请输入有效的 UTC 日期和时间。' },
  'ai.catalog.form.pricingPreviewResult': { 'en-US': 'Effective prices', 'zh-CN': '生效价格' },
  'ai.catalog.form.pricingPreviewActiveFrom': { 'en-US': 'Price effective from {{date}} (UTC)', 'zh-CN': '价格从 {{date}}（UTC）起生效' },
  'ai.catalog.form.pricingPreviewMatched': { 'en-US': 'Matched rules', 'zh-CN': '命中规则' },
  'ai.catalog.form.pricingPreviewNoRules': { 'en-US': 'No rule matched. Using the effective plan’s base rates.', 'zh-CN': '未命中规则，使用生效方案的基础费率。' },
  'ai.catalog.form.pricingPreviewTime': { 'en-US': 'Request start time (UTC)', 'zh-CN': '请求开始时间（UTC）' },
  'ai.catalog.form.pricingEveryDay': { 'en-US': 'Every day', 'zh-CN': '每天' },
  'ai.catalog.form.modelCapabilitiesLoading': { 'en-US': 'Loading model options…', 'zh-CN': '正在加载模型选项…' },
  'ai.catalog.form.modelCapabilitiesUnavailable': { 'en-US': 'Could not load prompt cache options', 'zh-CN': '无法加载提示缓存选项' },
  'ai.catalog.form.promptCacheRetentionSelect': { 'en-US': 'Select a supported option', 'zh-CN': '选择支持的选项' },
  'ai.catalog.form.promptCacheOptionTtl': { 'en-US': '{{seconds}} seconds', 'zh-CN': '{{seconds}} 秒' },
  'ai.catalog.form.promptCacheOptionWriteMultiplier': { 'en-US': 'write ×{{multiplier}}', 'zh-CN': '写入 ×{{multiplier}}' },
  'ai.catalog.form.promptCacheProviderManaged': { 'en-US': 'Automatic (managed by provider)', 'zh-CN': '自动（由服务商管理）' },
  'ai.catalog.form.promptCacheUnmanaged': { 'en-US': 'Determined by endpoint · Studio does not configure it', 'zh-CN': '由端点决定 · Studio 不配置' },
  'ai.catalog.form.promptCacheUnsupported': { 'en-US': 'Prompt caching is not supported', 'zh-CN': '不支持提示缓存' },
  'ai.catalog.form.promptCachePending': { 'en-US': 'Select a provider and enter a model ID to check prompt caching', 'zh-CN': '选择供应商并填写模型 ID 后查看提示缓存能力' },
  'ai.catalog.form.deleteVariant': {
    'en-US': 'Delete Variant',
    'zh-CN': '删除预设',
  },
  'ai.catalog.form.addVariant': {
    'en-US': 'Add Variant',
    'zh-CN': '添加预设',
  },
  'ai.catalog.deleteProviderTitle': {
    'en-US': 'Delete Provider',
    'zh-CN': '删除 Provider',
  },
  'ai.catalog.deleteProviderDescription': {
    'en-US': 'Provider {{name}} will be deleted.',
    'zh-CN': '将删除 Provider {{name}}。',
  },
  'ai.catalog.deleteModelTitle': {
    'en-US': 'Delete Model',
    'zh-CN': '删除 Model',
  },
  'ai.catalog.deleteModelDescription': {
    'en-US': 'Model {{name}} will be deleted.',
    'zh-CN': '将删除 Model {{name}}。',
  },
  'ai.catalog.deleteAgentTitle': {
    'en-US': 'Delete Agent',
    'zh-CN': '删除 Agent',
  },
  'ai.catalog.deleteAgentDescription': {
    'en-US': 'Agent {{name}} will be deleted. Existing sessions remain, but new runs cannot use this Agent.',
    'zh-CN': '将删除 Agent {{name}}。已有会话会保留，但不能再用该 Agent 新建运行。',
  },
  'ai.catalog.validation.saveForm': {
    'en-US': 'Save failed. Check the form and try again.',
    'zh-CN': '保存失败，请检查表单后重试',
  },
  'ai.catalog.validation.saveRequired': {
    'en-US': 'Save failed. Check required fields and try again.',
    'zh-CN': '保存失败，请检查必填项后重试',
  },
  'ai.catalog.validation.reasoningEnabled': {
    'en-US':
      'Reasoning effort must not exceed 64 characters; leave empty to keep provider default.',
    'zh-CN': '思考强度不能超过 64 个字符；留空使用供应商协议的默认值。',
  },
  'ai.catalog.validation.variantRequired': {
    'en-US': 'Select a valid Variant',
    'zh-CN': '请选择有效的模型预设',
  },
  'ai.catalog.validation.variantAddOne': {
    'en-US': 'Add at least one Variant and enter its ID',
    'zh-CN': '请至少添加一个模型预设，并填写预设 ID',
  },
  'ai.catalog.validation.variantDuplicate': {
    'en-US': 'Variant IDs must be unique',
    'zh-CN': '预设 ID 不能重复',
  },
  'ai.catalog.validation.protocolOptionsInvalidJson': {
    'en-US': 'Variant "{{ id }}" protocolOptions must be valid JSON',
    'zh-CN': '预设「{{ id }}」的原生协议选项必须是有效的 JSON',
  },
  'ai.catalog.validation.protocolOptionsNotObject': {
    'en-US': 'Variant "{{ id }}" protocolOptions must be a JSON object',
    'zh-CN': '预设「{{ id }}」的原生协议选项必须是 JSON 对象',
  },
  'ai.catalog.validation.protocolOptionsInvalidJsonSimple': {
    'en-US': 'protocolOptions must be valid JSON',
    'zh-CN': 'protocolOptions 必须是有效的 JSON',
  },
  'ai.catalog.validation.protocolOptionsNotObjectSimple': {
    'en-US': 'protocolOptions must be a JSON object',
    'zh-CN': 'protocolOptions 必须是 JSON 对象',
  },
  'ai.catalog.validation.protocolOptionsTooLarge': {
    'en-US': 'Protocol options must not exceed 65536 UTF-8 bytes',
    'zh-CN': '原生协议选项不能超过 65536 UTF-8 字节',
  },
  'ai.catalog.validation.protocolOptionsStrict': {
    'en-US': 'Protocol options must be a strict JSON object with no duplicate keys',
    'zh-CN': '原生协议选项必须为无重复键的严格 JSON 对象',
  },
  'ai.catalog.validation.defaultVariant': {
    'en-US': 'Select a valid default Variant',
    'zh-CN': '请选择一个有效的默认预设',
  },
  'ai.catalog.validation.maxOutputContext': {
    'en-US': 'Max output length cannot exceed the context window',
    'zh-CN': '最大输出长度不能超过上下文窗口',
  },
  'ai.catalog.validation.contextWindow': {
    'en-US': 'Enter a valid context window; it must be a positive integer',
    'zh-CN': '请填写有效的上下文窗口，必须为正整数',
  },
  'ai.catalog.validation.maxOutput': {
    'en-US': 'Enter a valid max output length; it must be a positive integer',
    'zh-CN': '请填写有效的最大输出长度，必须为正整数',
  },
  'ai.catalog.validation.inputModality': {
    'en-US': 'Select at least one input type; TEXT is recommended',
    'zh-CN': '请至少选择一种输入类型，建议保留 TEXT',
  },
  'ai.catalog.validation.modelId': {
    'en-US': 'Enter a Model ID',
    'zh-CN': '请填写模型 ID',
  },
  'ai.catalog.validation.pricing': {
    'en-US': 'Check the required rates, pricing rules, and effective dates.',
    'zh-CN': '请检查价格配置：必填费率、定价规则和生效日期。',
  },
  'ai.catalog.validation.duplicateModel': {
    'en-US': 'A Model with this name already exists under the current Provider. Choose another name.',
    'zh-CN': '当前 Provider 下已存在同名 Model，请换一个名称',
  },
  'ai.catalog.validation.duplicateProvider': {
    'en-US': 'A Provider with this name already exists. Choose another name.',
    'zh-CN': 'Provider 名称已存在，请换一个名称',
  },
  'ai.catalog.validation.duplicateAgent': {
    'en-US': 'An Agent with this name already exists. Choose another name.',
    'zh-CN': 'Agent 名称已存在，请换一个名称',
  },
  'ai.catalog.validation.provider': {
    'en-US': 'Select a Provider',
    'zh-CN': '请选择供应商',
  },
  'ai.catalog.validation.providerType': {
    'en-US': 'Select a valid Provider Type',
    'zh-CN': '请选择有效的 Provider Type',
  },
  'ai.catalog.validation.providerTimeout': {
    'en-US': 'Model call timeouts must be whole milliseconds',
    'zh-CN': '模型调用超时必须是整数毫秒',
  },
  'ai.catalog.validation.model': {
    'en-US': 'Select a Default Model',
    'zh-CN': '请选择默认模型',
  },
  'ai.catalog.validation.baseUrl': {
    'en-US': 'Enter a Base URL',
    'zh-CN': '请填写 Base URL',
  },
  'ai.catalog.validation.toolsConflict': {
    'en-US': 'Tool names conflict. Check the selected items.',
    'zh-CN': 'Tools 名称冲突，请检查勾选项',
  },
  'ai.catalog.validation.skillsConflict': {
    'en-US': 'Skill names conflict. Check the selected items.',
    'zh-CN': 'Skills 名称冲突，请检查勾选项',
  },
  'ai.catalog.validation.capabilityDuplicate': {
    'en-US': '{{kind}} names must be unique: {{names}}',
    'zh-CN': '{{kind}} 名称不能重复：{{names}}',
  },
  'ai.catalog.validation.network': {
    'en-US': 'Network error. Please try again later.',
    'zh-CN': '网络异常，请稍后重试',
  },
  'ai.catalog.validation.unauthorized': {
    'en-US': 'You do not have permission to perform this action.',
    'zh-CN': '没有权限执行此操作',
  },
  'ai.catalog.validation.notFound': {
    'en-US': 'The resource does not exist or has been deleted.',
    'zh-CN': '资源不存在或已被删除',
  },
  'ai.catalog.validation.conflict': {
    'en-US': 'Resource conflict. Refresh and try again.',
    'zh-CN': '资源冲突，请刷新后重试',
  },
  'ai.catalog.validation.server': {
    'en-US': 'The service is temporarily unavailable. Please try again later.',
    'zh-CN': '服务暂时异常，请稍后重试',
  },
  'ai.catalog.validation.providerName': {
    'en-US': 'Enter a Provider name',
    'zh-CN': '请填写 Provider 名称',
  },
  'ai.catalog.validation.name': {
    'en-US': 'Enter a name',
    'zh-CN': '请填写名称',
  },
  'ai.catalog.validation.addVariant': {
    'en-US': 'Add a Variant',
    'zh-CN': '请添加预设',
  },
  'ai.catalog.validation.defaultVariantField': {
    'en-US': 'Select a default Variant',
    'zh-CN': '请选择默认预设',
  },
  'ai.catalog.validation.modelName': {
    'en-US': 'Enter a Model name',
    'zh-CN': '请填写 Model 名称',
  },
  'ai.catalog.validation.agentName': {
    'en-US': 'Enter an Agent name',
    'zh-CN': '请填写 Agent 名称',
  },
  'ai.catalog.validation.agentModel': {
    'en-US': 'Select a Model',
    'zh-CN': '请选择 Model',
  },
  'ai.catalog.validation.groupMembers': {
    'en-US': 'Add at least one model',
    'zh-CN': '请至少添加一个模型',
  },
  'ai.skillPackages.description': {
    'en-US': 'Platform-global Skill Packages and definitions',
    'zh-CN': '平台全局技能包及其定义管理',
  },
  'ai.skillPackages.create': {
    'en-US': 'Create Package',
    'zh-CN': '创建技能包',
  },
  'ai.skillPackages.edit': {
    'en-US': 'Edit Package',
    'zh-CN': '编辑技能包',
  },
  'ai.skillPackages.editTitle': {
    'en-US': 'Edit Package: {{name}}',
    'zh-CN': '编辑技能包：{{name}}',
  },
  'ai.skillPackages.delete': {
    'en-US': 'Delete Package',
    'zh-CN': '删除技能包',
  },
  'ai.skillPackages.deleteConfirm': {
    'en-US': 'Are you sure you want to delete skill package "{{name}}"?',
    'zh-CN': '确认删除技能包“{{name}}”？',
  },
  'ai.skillPackages.name': {
    'en-US': 'Package Name',
    'zh-CN': '技能包名称',
  },
  'ai.skillPackages.descriptionLabel': {
    'en-US': 'Description',
    'zh-CN': '描述',
  },
  'ai.skillPackages.skillsCount': {
    'en-US': 'Skills Count',
    'zh-CN': '包含技能数量',
  },
  'ai.skillPackages.loading': {
    'en-US': 'Loading Skill Packages...',
    'zh-CN': '正在加载技能包...',
  },
  'ai.skillPackages.source': {
    'en-US': 'Source',
    'zh-CN': '来源',
  },
  'ai.skillPackages.source.GIT': {
    'en-US': 'Git repository',
    'zh-CN': 'Git 仓库',
  },
  'ai.skillPackages.source.UPLOAD': {
    'en-US': 'Uploaded archive',
    'zh-CN': '上传压缩包',
  },
  'ai.skillPackages.sourceLabel': {
    'en-US': 'Source',
    'zh-CN': '来源',
  },
  'ai.skillPackages.packageNameInvalid': {
    'en-US': 'Package name must be 1 to 64 lowercase letters, digits or single hyphens',
    'zh-CN': '技能包名称只能使用 1 到 64 个小写字母、数字或单个连字符',
  },
  'ai.skillPackages.packageVersion': {
    'en-US': 'Package Version',
    'zh-CN': '包版本',
  },
  'ai.skillPackages.versionInvalid': {
    'en-US': 'Enter a package version of 1 to 128 characters without control characters',
    'zh-CN': '请填写 1 到 128 个字符的包版本，不能包含控制字符',
  },
  'ai.skillPackages.descriptionTooLong': {
    'en-US': 'Description can be at most 1024 characters',
    'zh-CN': '描述最多 1024 个字符',
  },
  'ai.skillPackages.repositoryUrl': {
    'en-US': 'Repository URL',
    'zh-CN': 'Git 仓库地址',
  },
  'ai.skillPackages.branch': {
    'en-US': 'Branch',
    'zh-CN': '分支',
  },
  'ai.skillPackages.token': {
    'en-US': 'Access Token',
    'zh-CN': '访问令牌',
  },
  'ai.skillPackages.tokenPlaceholder': {
    'en-US': 'Personal Access Token',
    'zh-CN': 'Personal Access Token',
  },
  'ai.skillPackages.tokenCreateHint': {
    'en-US': 'Optional personal access token for private repositories. Stored encrypted and never echoed.',
    'zh-CN': '私有仓库的访问令牌，可选。加密存储且绝不回显。',
  },
  'ai.skillPackages.tokenEditPlaceholder': {
    'en-US': 'Leave blank to keep existing token',
    'zh-CN': '留空保留已有令牌',
  },
  'ai.skillPackages.tokenEditHint': {
    'en-US': 'Leave blank to keep the configured token; the secret is never echoed.',
    'zh-CN': '留空保留已配置的访问令牌；密钥绝不回显。',
  },
  'ai.skillPackages.clearToken': {
    'en-US': 'Clear token',
    'zh-CN': '清除令牌',
  },
  'ai.skillPackages.tokenConfigured': {
    'en-US': 'Configured',
    'zh-CN': '已配置',
  },
  'ai.skillPackages.tokenNotConfigured': {
    'en-US': 'Not configured',
    'zh-CN': '未配置',
  },
  'ai.skillPackages.checkStatus': {
    'en-US': 'Check Status',
    'zh-CN': '检查状态',
  },
  'ai.skillPackages.observedHeadCommit': {
    'en-US': 'Observed Branch HEAD',
    'zh-CN': '最近观察到的分支 HEAD',
  },
  'ai.skillPackages.check': {
    'en-US': 'Check',
    'zh-CN': '检查更新',
  },
  'ai.skillPackages.updateTo': {
    'en-US': 'Update to {{commit}}',
    'zh-CN': '更新到 {{commit}}',
  },
  'ai.skillPackages.detail': {
    'en-US': 'Details',
    'zh-CN': '详情',
  },
  'ai.skillPackages.detailTitle': {
    'en-US': 'Details: {{name}}',
    'zh-CN': '详情：{{name}}',
  },
  'ai.skillPackages.summary': {
    'en-US': 'Overview',
    'zh-CN': '概览',
  },
  'ai.skillPackages.loadingDetail': {
    'en-US': 'Loading skill package details...',
    'zh-CN': '正在读取技能包详情...',
  },
  'ai.skillPackages.loadingFiles': {
    'en-US': 'Loading archive files...',
    'zh-CN': '正在读取归档文件...',
  },
  'ai.skillPackages.fileTree': {
    'en-US': 'File Tree',
    'zh-CN': '文件树',
  },
  'ai.skillPackages.fileTreeHelp': {
    'en-US': 'Help: file tree',
    'zh-CN': '帮助：文件树',
  },
  'ai.skillPackages.fileTreeHelpText': {
    'en-US': 'The upload preview only has the server-side skill manifest. Every file of the published archive is listed in the details.',
    'zh-CN': '上传预览只有服务端技能清单，归档的全部文件在发布后的详情中列出。',
  },
  'ai.skillPackages.ignoredBadge': {
    'en-US': 'Not packaged',
    'zh-CN': '未打包',
  },
  'ai.skillPackages.ignoredDirectoriesValue': {
    'en-US': 'Not packaged directories: {{directories}}',
    'zh-CN': '未打包目录：{{directories}}',
  },
  'ai.skillPackages.fileCount': {
    'en-US': 'Files',
    'zh-CN': '文件数',
  },
  'ai.skillPackages.fileCountValue': {
    'en-US': '{{count}} files',
    'zh-CN': '共 {{count}} 个文件',
  },
  'ai.skillPackages.contentSize': {
    'en-US': 'Unpacked Size',
    'zh-CN': '解压后大小',
  },
  'ai.skillPackages.archiveSize': {
    'en-US': 'Archive Size',
    'zh-CN': '归档大小',
  },
  'ai.skillPackages.archiveSha': {
    'en-US': 'Archive Checksum',
    'zh-CN': '归档摘要',
  },
  'ai.skillPackages.publishedAt': {
    'en-US': 'Published',
    'zh-CN': '发布时间',
  },
  'ai.skillPackages.previewSummary': {
    'en-US': 'Archive Preview',
    'zh-CN': '归档预览',
  },
  'ai.skillPackages.previewTitle': {
    'en-US': 'Preview and Publish',
    'zh-CN': '预览并发布',
  },
  'ai.skillPackages.parsePreview': {
    'en-US': 'Preview Archive',
    'zh-CN': '解析预览',
  },
  'ai.skillPackages.publish': {
    'en-US': 'Publish',
    'zh-CN': '发布',
  },
  'ai.skillPackages.changeSummary': {
    'en-US': 'Change Summary',
    'zh-CN': '变化摘要',
  },
  'ai.skillPackages.newPackageHint': {
    'en-US': 'This is a new skill package and publishing creates it.',
    'zh-CN': '这是新技能包，发布会创建它。',
  },
  'ai.skillPackages.addedSkills': {
    'en-US': 'Added Skills',
    'zh-CN': '新增技能',
  },
  'ai.skillPackages.removedSkills': {
    'en-US': 'Removed Skills',
    'zh-CN': '移除的技能',
  },
  'ai.skillPackages.noAddedSkills': {
    'en-US': 'No added skills',
    'zh-CN': '没有新增技能',
  },
  'ai.skillPackages.noRemovedSkills': {
    'en-US': 'No removed skills',
    'zh-CN': '没有移除技能',
  },
  'ai.skillPackages.referencedSkills': {
    'en-US': 'Skills Still Referenced by Agents',
    'zh-CN': '仍被 Agent 引用的技能',
  },
  'ai.skillPackages.referencedSkillsHint': {
    'en-US': 'Publishing fails while agents still reference these skills. Update those agents first.',
    'zh-CN': '仍有 Agent 引用这些技能时发布会失败，请先调整对应 Agent。',
  },
  'ai.skillPackages.noReferencedSkills': {
    'en-US': 'No referenced skills',
    'zh-CN': '没有仍被引用的技能',
  },
  'ai.skillPackages.loadingReferences': {
    'en-US': 'Loading agent references...',
    'zh-CN': '正在读取 Agent 引用...',
  },
  'ai.skillPackages.downloadArchive': {
    'en-US': 'Download Archive',
    'zh-CN': '下载归档',
  },
  'ai.skillPackages.skillCountValue': {
    'en-US': '{{count}} skills',
    'zh-CN': '共 {{count}} 个技能',
  },
  'ai.skillPackages.uploadGitConflict': {
    'en-US': 'A Git-backed skill package already uses this name and cannot be replaced by an upload',
    'zh-CN': '同名技能包已由 Git 仓库发布，不能用上传覆盖',
  },
  'ai.skillPackages.fileRequired': {
    'en-US': 'Choose an archive to upload',
    'zh-CN': '请选择要上传的压缩包',
  },
  'ai.skillPackages.fileTypeInvalid': {
    'en-US': 'Only .zip and .tar.gz archives are supported',
    'zh-CN': '只支持 .zip 与 .tar.gz 压缩包',
  },
  'ai.skillPackages.archiveFile': {
    'en-US': 'Package Archive',
    'zh-CN': '技能包压缩包',
  },
  'ai.skillPackages.chooseFile': {
    'en-US': 'Choose File',
    'zh-CN': '选择文件',
  },
  'ai.skillPackages.noFile': {
    'en-US': 'No file selected',
    'zh-CN': '尚未选择文件',
  },
  'ai.skillPackages.archiveFileHint': {
    'en-US': 'Every directory that directly contains SKILL.md becomes one skill.',
    'zh-CN': '根目录下直接包含 SKILL.md 的目录会打包为一个技能。',
  },
  'ai.skillPackages.uploadProgress': {
    'en-US': 'Upload progress',
    'zh-CN': '上传进度',
  },
  'ai.skillPackages.stage.validating': {
    'en-US': 'Validating archive',
    'zh-CN': '正在校验归档',
  },
  'ai.skillPackages.stage.uploading': {
    'en-US': 'Uploading archive',
    'zh-CN': '正在上传归档',
  },
  'ai.skillPackages.stage.packaging': {
    'en-US': 'Building the archive',
    'zh-CN': '正在服务端打包',
  },
  'ai.skillPackages.stage.publishing': {
    'en-US': 'Publishing',
    'zh-CN': '正在发布',
  },
  'ai.skillPackages.repoRequired': {
    'en-US': 'Repository URL is required',
    'zh-CN': 'Git 仓库地址不能为空',
  },
  'ai.skillPackages.branchRequired': {
    'en-US': 'Branch is required',
    'zh-CN': '分支不能为空',
  },
  'ai.skillPackages.skills': {
    'en-US': 'Skills',
    'zh-CN': '技能',
  },
  'ai.skillPackages.status.UNCHECKED': {
    'en-US': 'Unchecked',
    'zh-CN': '未检查',
  },
  'ai.skillPackages.status.UP_TO_DATE': {
    'en-US': 'Up to date',
    'zh-CN': '已是最新',
  },
  'ai.skillPackages.status.UPDATE_AVAILABLE': {
    'en-US': 'Update available',
    'zh-CN': '有更新可用',
  },
  'ai.skillPackages.status.CHECK_FAILED': {
    'en-US': 'Check failed',
    'zh-CN': '检查失败',
  },
  'ai.mcp.create': {
    'en-US': 'Create MCP Server',
    'zh-CN': '创建 MCP 服务',
  },
  'ai.mcp.createDescription': {
    'en-US': 'Configure Streamable HTTP MCP Server',
    'zh-CN': '配置 Streamable HTTP MCP 服务',
  },
  'ai.mcp.edit': {
    'en-US': 'Edit MCP Server',
    'zh-CN': '编辑 MCP 服务',
  },
  'ai.mcp.editTitle': {
    'en-US': 'Edit MCP Server: {{name}}',
    'zh-CN': '编辑 MCP 服务：{{name}}',
  },
  'ai.mcp.delete': {
    'en-US': 'Delete MCP Server',
    'zh-CN': '删除 MCP 服务',
  },
  'ai.mcp.deleteConfirm': {
    'en-US': 'Are you sure you want to delete MCP server "{{name}}"?',
    'zh-CN': '确认删除 MCP 服务「{{name}}」？',
  },
  'ai.mcp.discover': {
    'en-US': 'Discover Tools',
    'zh-CN': '发现工具',
  },
  'ai.mcp.unsavedDiscoverBlocked': {
    'en-US': 'Unsaved configuration changes. Please save before discovering tools.',
    'zh-CN': '存在未保存的配置更改，请先保存再发现工具。',
  },
  'ai.mcp.name': {
    'en-US': 'Server Name',
    'zh-CN': '服务名称',
  },
  'ai.mcp.url': {
    'en-US': 'Endpoint URL',
    'zh-CN': '服务地址',
  },
  'ai.mcp.headers': {
    'en-US': 'JSON headers',
    'zh-CN': 'JSON 请求头',
  },
  'ai.mcp.headersHint': {
    'en-US': 'JSON object of request headers. Values may contain ${VAR} placeholders.',
    'zh-CN': '请求头 JSON 对象，属性值支持 ${VAR} 环境变量占位符。',
  },
  'ai.mcp.headersKeepHint': {
    'en-US': 'Leave empty to keep the stored headers.',
    'zh-CN': '留空保持已保存的请求头。',
  },
  'ai.mcp.clearHeaders': {
    'en-US': 'Clear stored headers',
    'zh-CN': '清除已保存的请求头',
  },
  'ai.mcp.toolCount': {
    'en-US': 'Tools',
    'zh-CN': '工具数量',
  },
  'ai.mcp.status': {
    'en-US': 'Status',
    'zh-CN': '状态',
  },
  'ai.mcp.statusAvailable': {
    'en-US': 'Available',
    'zh-CN': '可用',
  },
  'ai.mcp.statusUnverified': {
    'en-US': 'Unverified',
    'zh-CN': '未验证',
  },
  'ai.mcp.enabledState': {
    'en-US': 'Enabled State',
    'zh-CN': '启用状态',
  },
  'ai.mcp.enabled': {
    'en-US': 'Enabled',
    'zh-CN': '已启用',
  },
  'ai.mcp.disabled': {
    'en-US': 'Disabled',
    'zh-CN': '已禁用',
  },
  'ai.mcp.loading': {
    'en-US': 'Loading MCP servers',
    'zh-CN': '正在加载 MCP 服务',
  },
  'ai.mcp.loadFailed': {
    'en-US': 'Failed to load MCP servers',
    'zh-CN': '加载 MCP 服务失败',
  },
  'ai.mcp.loadingConfig': {
    'en-US': 'Loading configuration...',
    'zh-CN': '正在读取配置...',
  },
  'ai.mcp.loadConfigFailed': {
    'en-US': 'Failed to load configuration',
    'zh-CN': '读取配置失败',
  },
  'ai.mcp.version': {
    'en-US': 'Version',
    'zh-CN': '版本',
  },
  'ai.mcp.updated': {
    'en-US': 'Updated',
    'zh-CN': '更新时间',
  },
} satisfies LocaleCatalog
