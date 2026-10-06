import type { LocaleCatalog } from '@/shared/i18n/types'

export const aiCatalog = {
  'ai.nav.chats': {
    'en-US': 'Chat',
    'zh-CN': '对话',
  },
  'ai.nav.agents': {
    'en-US': 'Agent',
    'zh-CN': '代理',
  },
  'ai.nav.models': {
    'en-US': 'Model',
    'zh-CN': '模型',
  },
  'ai.nav.providers': {
    'en-US': 'Provider',
    'zh-CN': '提供商',
  },
  'ai.nav.environments': {
    'en-US': 'Environment',
    'zh-CN': '环境',
  },
  'ai.nav.mcpServers': {
    'en-US': 'MCP Server',
    'zh-CN': 'MCP 服务',
  },
  'ai.nav.skillPackages': {
    'en-US': 'Skill Packages',
    'zh-CN': '技能包',
  },
  'ai.catalog.resourceType.agent': {
    'en-US': 'Agent',
    'zh-CN': 'Agent',
  },
  'ai.catalog.resourceType.model': {
    'en-US': 'Model',
    'zh-CN': 'Model',
  },
  'ai.catalog.resourceType.provider': {
    'en-US': 'Provider',
    'zh-CN': 'Provider',
  },
  'ai.common.loadingResources': {
    'en-US': 'Loading resources',
    'zh-CN': '正在加载资源',
  },
  'ai.common.resourceLoadFailed': {
    'en-US': 'Failed to load resources',
    'zh-CN': '资源加载失败',
  },
  'ai.common.operationFailed': {
    'en-US': 'Operation failed. Please try again.',
    'zh-CN': '操作失败，请稍后重试',
  },
  'ai.common.loadingAgent': {
    'en-US': 'Loading Agent',
    'zh-CN': '正在加载 Agent',
  },
  'ai.common.loadingModel': {
    'en-US': 'Loading Model',
    'zh-CN': '正在加载 Model',
  },
  'ai.common.loadingProvider': {
    'en-US': 'Loading Provider',
    'zh-CN': '正在加载 Provider',
  },
  'ai.common.loadingEnvironment': {
    'en-US': 'Loading Environment',
    'zh-CN': '正在加载 Environment',
  },
  'ai.chat.running': {
    'en-US': 'RUNNING',
    'zh-CN': '运行中',
  },
  'ai.runtime.approval.title': {
    'en-US': 'Tool approval',
    'zh-CN': '工具审批',
  },
  'ai.runtime.approval.requested': {
    'en-US': 'This tool call requires approval:',
    'zh-CN': '此工具调用需要审批：',
  },
  'ai.runtime.approval.allow': {
    'en-US': 'Allow',
    'zh-CN': '允许',
  },
  'ai.runtime.approval.deny': {
    'en-US': 'Deny',
    'zh-CN': '拒绝',
  },
  'ai.runtime.approval.deciding': {
    'en-US': 'Applying decision…',
    'zh-CN': '正在提交审批…',
  },
  'ai.runtime.approval.allowed': {
    'en-US': 'Allowed',
    'zh-CN': '已允许',
  },
  'ai.runtime.approval.denied': {
    'en-US': 'Denied',
    'zh-CN': '已拒绝',
  },
  'ai.runtime.action.approvalFailed': {
    'en-US': 'approve the tool call',
    'zh-CN': '审批工具调用',
  },
  'ai.runtime.action.agentUnresolvable': {
    'en-US': 'The model or variant for Agent {{selectedAgent}} cannot be resolved; pick another agent.',
    'zh-CN': 'Agent {{selectedAgent}} 的模型或变体无法解析，请选择其他 Agent。',
  },
  'ai.common.loadingResourceEditor': {
    'en-US': 'Loading resource editor',
    'zh-CN': '正在加载资源编辑器',
  },
  'ai.common.loadingConfirmDialog': {
    'en-US': 'Loading confirmation dialog',
    'zh-CN': '正在加载确认对话框',
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
    'zh-CN': '定义模型信息与 Variant',
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
    'zh-CN': '生效 Model',
  },
  'ai.catalog.card.modelDefaultSuffix': {
    'en-US': ' (model default)',
    'zh-CN': '（模型默认）',
  },
  'ai.catalog.card.unknownVariant': {
    'en-US': 'unresolved',
    'zh-CN': '未解析',
  },
  'ai.catalog.card.tools': {
    'en-US': 'Tools',
    'zh-CN': 'Tools',
  },
  'ai.catalog.card.skills': {
    'en-US': 'Skills',
    'zh-CN': 'Skills',
  },
  'ai.catalog.card.subagents': {
    'en-US': 'Subagents',
    'zh-CN': 'Subagents',
  },
  'ai.catalog.card.ref': {
    'en-US': 'Ref',
    'zh-CN': 'Ref',
  },
  'ai.catalog.card.modelId': {
    'en-US': 'Model ID',
    'zh-CN': 'Model ID',
  },
  'ai.catalog.card.limit': {
    'en-US': 'Limit',
    'zh-CN': 'Limit',
  },
  'ai.catalog.card.ability': {
    'en-US': 'Ability',
    'zh-CN': 'Ability',
  },
  'ai.catalog.card.default': {
    'en-US': 'Default',
    'zh-CN': 'Default',
  },
  'ai.catalog.card.variants': {
    'en-US': 'Variants',
    'zh-CN': 'Variants',
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
  'ai.catalog.card.configured': {
    'en-US': 'Configured',
    'zh-CN': '已配置',
  },
  'ai.catalog.card.unconfiguredApiKey': {
    'en-US': 'No API key (unauthenticated request)',
    'zh-CN': '无 API Key（无认证请求）',
  },
  'ai.catalog.card.emptyValue': {
    'en-US': '—',
    'zh-CN': '—',
  },
  'ai.catalog.card.unknownModel': {
    'en-US': 'unknown-model',
    'zh-CN': 'unknown-model',
  },
  'ai.catalog.card.contextLimit': {
    'en-US': 'ctx {{value}}',
    'zh-CN': 'ctx {{value}}',
  },
  'ai.catalog.card.outputLimit': {
    'en-US': 'out {{value}}',
    'zh-CN': 'out {{value}}',
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
    'zh-CN': 'tools',
  },
  'ai.catalog.card.reasoningAbility': {
    'en-US': 'reasoning',
    'zh-CN': 'reasoning',
  },
  'ai.catalog.form.name': {
    'en-US': 'Name',
    'zh-CN': 'Name',
  },
  'ai.catalog.form.nameHint': {
    'en-US': 'Logical identity used by Agents and branch settings.',
    'zh-CN': '逻辑标识，供 Agent 与分支配置引用。',
  },
  'ai.catalog.form.modelId': {
    'en-US': 'Model ID',
    'zh-CN': 'Model ID',
  },
  'ai.catalog.form.modelIdHint': {
    'en-US': 'Upstream model ID sent to the Provider; may differ from Name.',
    'zh-CN': '发送给 Provider 的上游模型 ID，可与 Name 不同。',
  },
  'ai.catalog.form.description': {
    'en-US': 'Description',
    'zh-CN': 'Description',
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
    'zh-CN': 'Default Model',
  },
  'ai.catalog.form.defaultVariantOverride': {
    'en-US': 'Default Variant Override',
    'zh-CN': 'Default Variant Override',
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
    'en-US': 'API Key (optional)',
    'zh-CN': 'API Key（可选）',
  },
  'ai.catalog.form.provider': {
    'en-US': 'Provider',
    'zh-CN': 'Provider',
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
  'ai.catalog.form.contextWindow': {
    'en-US': 'Context Window',
    'zh-CN': 'Context Window',
  },
  'ai.catalog.form.maxOutputTokens': {
    'en-US': 'Max Output Tokens',
    'zh-CN': 'Max Output Tokens',
  },
  'ai.catalog.form.limit': {
    'en-US': 'Limit',
    'zh-CN': 'Limit',
  },
  'ai.catalog.form.abilities': {
    'en-US': 'Abilities',
    'zh-CN': '功能',
  },
  'ai.catalog.form.tools': {
    'en-US': 'Tools',
    'zh-CN': 'Tools',
  },
  'ai.catalog.form.reasoning': {
    'en-US': 'Reasoning',
    'zh-CN': 'Reasoning',
  },
  'ai.catalog.form.inputTypes': {
    'en-US': 'Input types',
    'zh-CN': '输入类型',
  },
  'ai.catalog.form.pricing': {
    'en-US': 'Pricing',
    'zh-CN': 'Pricing',
  },
  'ai.catalog.form.inputPrice': {
    'en-US': 'Input',
    'zh-CN': 'Input',
  },
  'ai.catalog.form.outputPrice': {
    'en-US': 'Output',
    'zh-CN': 'Output',
  },
  'ai.catalog.form.cacheReadPrice': {
    'en-US': 'Cache Read',
    'zh-CN': 'Cache Read',
  },
  'ai.catalog.form.cacheWritePrice': {
    'en-US': 'Cache Write',
    'zh-CN': 'Cache Write',
  },
  'ai.catalog.form.longCacheWritePrice': {
    'en-US': 'Long Cache Write',
    'zh-CN': 'Long Cache Write',
  },
  'ai.catalog.form.reasoningPrice': {
    'en-US': 'Reasoning',
    'zh-CN': 'Reasoning',
  },
  'ai.catalog.form.defaultVariant': {
    'en-US': 'Default Variant',
    'zh-CN': 'Default Variant',
  },
  'ai.catalog.form.variants': {
    'en-US': 'Variants',
    'zh-CN': 'Variants',
  },
  'ai.catalog.form.variantId': {
    'en-US': 'Variant ID',
    'zh-CN': 'Variant ID',
  },
  'ai.catalog.form.reasoningEffort': {
    'en-US': 'Reasoning Effort',
    'zh-CN': '思考强度',
  },
  'ai.catalog.form.reasoningEffortPlaceholder': {
    'en-US': 'e.g. high, max, off',
    'zh-CN': '例如 high、max、off',
  },
  'ai.catalog.form.reasoningEffortAria': {
    'en-US': 'Reasoning Effort',
    'zh-CN': 'Reasoning Effort',
  },
  'ai.catalog.form.protocolOptions': {
    'en-US': 'Protocol Options',
    'zh-CN': '原生协议选项',
  },
  'ai.catalog.form.protocolOptionsAria': {
    'en-US': 'Protocol Options',
    'zh-CN': 'Protocol Options',
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
    'en-US': '(Use model default)',
    'zh-CN': '（使用模型默认）',
  },
  'ai.catalog.form.none': {
    'en-US': '(None)',
    'zh-CN': '（无）',
  },
  'ai.catalog.form.optionalCredential': {
    'en-US': 'Optional',
    'zh-CN': '可留空',
  },
  'ai.catalog.form.keepCredential': {
    'en-US': 'Leave blank to keep the current key',
    'zh-CN': '留空保留当前密钥',
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
    'en-US': 'No candidate Tools',
    'zh-CN': '暂无候选 Tools',
  },
  'ai.catalog.form.noCandidateSkills': {
    'en-US': 'No candidate Skills',
    'zh-CN': '暂无候选 Skills',
  },
  'ai.catalog.form.noCandidateSubagents': {
    'en-US': 'No candidate Subagents',
    'zh-CN': '暂无候选 Subagents',
  },
  'ai.catalog.form.inheritParentEnvironment': {
    'en-US': "Inherit parent session's current Environment when delegated to as a subagent",
    'zh-CN': '作为子 Agent 被委派时，继承父会话当前 Environment',
  },
  'ai.catalog.form.skillCatalogSource': {
    'en-US': 'Skill Catalog Environment',
    'zh-CN': 'Skill 目录 Environment',
  },
  'ai.catalog.form.skillCatalogSourceHint': {
    'en-US':
      'Only browses the selected Environment\u2019s current skill names for editing; the selection is transient and is never saved or bound to the Agent.',
    'zh-CN': '仅用于浏览所选 Environment 的当前技能名称以便编辑；选择是临时的，不会保存或绑定到 Agent。',
  },
  'ai.catalog.form.needModel': {
    'en-US': 'Create a Model first to configure an Agent.',
    'zh-CN': '需要先创建 Model 才能配置 Agent。',
  },
  'ai.catalog.form.fillFirstModel': {
    'en-US': 'Use the first Model to fill the default configuration',
    'zh-CN': '使用第一个 Model 填充默认配置',
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
    'zh-CN': '引用的 Model 未加载，Variant 选项不可用；已保存的覆盖值会保留。',
  },
  'ai.catalog.form.offline': {
    'en-US': 'Offline',
    'zh-CN': 'offline',
  },
  'ai.catalog.form.variantHint': {
    'en-US':
      'A Variant carries reasoning effort and protocol options; IDs must be unique, and Default Variant must point to one of them.',
    'zh-CN': 'Variant 承载思考强度与原生协议选项；id 唯一，Default Variant 必须指向其中一项。',
  },
  'ai.catalog.form.reasoningHint': {
    'en-US':
      ' Leave empty to keep provider protocol default; off = explicitly disable reasoning; provider-defined values (e.g. low, medium, high, max) are passed through.',
    'zh-CN':
      ' 留空保持 Provider 协议默认；off=显式关闭推理；支持厂商自定义值（如 low、medium、high、max 等）。',
  },
  'ai.catalog.form.reasoningDisabledHint': {
    'en-US':
      ' Reasoning is disabled. To configure reasoning effort, check Reasoning above to enable it.',
    'zh-CN': ' Reasoning 当前已关闭；若需配置思考强度，请勾选开启上方 Reasoning。',
  },
  'ai.catalog.form.maxOutputLimitHint': {
    'en-US': 'Model-level output budget; each request also narrows it by the remaining context.',
    'zh-CN': '模型级输出预算；每次请求还会按剩余上下文收敛。',
  },
  'ai.catalog.form.emptyPlaceholder': {
    'en-US': 'Empty',
    'zh-CN': '空',
  },
  'ai.catalog.form.inputModalityHint': {
    'en-US': 'Input modalities accepted by the model; keep at least one (do not clear all).',
    'zh-CN': '模型可接受的输入模态；至少保留一种（不能全部取消）。',
  },
  'ai.catalog.form.priceHint': {
    'en-US': 'Price is USD per million tokens. Currency is fixed to USD.',
    'zh-CN': '单价为 USD / 百万 tokens。币种固定 USD。',
  },
  'ai.catalog.form.priceAriaLabel': {
    'en-US': '{{label}} USD per million tokens',
    'zh-CN': '{{label}} USD per million tokens',
  },
  'ai.catalog.form.priceSuffix': {
    'en-US': '/1M',
    'zh-CN': '/1M',
  },
  'ai.catalog.form.deleteVariant': {
    'en-US': 'Delete Variant',
    'zh-CN': '删除 Variant',
  },
  'ai.catalog.form.addVariant': {
    'en-US': 'Add Variant',
    'zh-CN': '添加 Variant',
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
    'zh-CN': '思考强度不能超过 64 个字符；留空保持 Provider 协议默认。',
  },
  'ai.catalog.validation.variantRequired': {
    'en-US': 'Select a valid Variant',
    'zh-CN': '请选择有效的 Variant',
  },
  'ai.catalog.validation.variantAddOne': {
    'en-US': 'Add at least one Variant and enter its ID',
    'zh-CN': '请至少添加一个 Variant，并填写 ID',
  },
  'ai.catalog.validation.variantDuplicate': {
    'en-US': 'Variant IDs must be unique',
    'zh-CN': 'Variant ID 不能重复',
  },
  'ai.catalog.validation.protocolOptionsInvalidJson': {
    'en-US': 'Variant "{{ id }}" protocolOptions must be valid JSON',
    'zh-CN': 'Variant "{{ id }}" 的 protocolOptions 必须是有效的 JSON',
  },
  'ai.catalog.validation.protocolOptionsNotObject': {
    'en-US': 'Variant "{{ id }}" protocolOptions must be a JSON object',
    'zh-CN': 'Variant "{{ id }}" 的 protocolOptions 必须是 JSON 对象',
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
    'zh-CN': '请选择一个有效的默认 Variant',
  },
  'ai.catalog.validation.maxOutputContext': {
    'en-US': 'Max output length cannot exceed the context window',
    'zh-CN': '最大输出长度不能超过上下文窗口',
  },
  'ai.catalog.validation.contextWindow': {
    'en-US': 'Enter a valid context window (a positive integer)',
    'zh-CN': '请填写有效的上下文窗口（正整数）',
  },
  'ai.catalog.validation.maxOutput': {
    'en-US': 'Enter a valid max output length (a positive integer)',
    'zh-CN': '请填写有效的最大输出长度（正整数）',
  },
  'ai.catalog.validation.inputModality': {
    'en-US': 'Select at least one input type (TEXT is recommended)',
    'zh-CN': '请至少选择一种输入类型（建议保留 TEXT）',
  },
  'ai.catalog.validation.modelId': {
    'en-US': 'Enter a Model ID',
    'zh-CN': '请填写 Model ID',
  },
  'ai.catalog.validation.pricing': {
    'en-US': 'Check pricing; use zero or a positive number.',
    'zh-CN': '请检查价格：填写 0 或正数即可',
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
    'zh-CN': '请选择 Provider',
  },
  'ai.catalog.validation.providerType': {
    'en-US': 'Select a valid Provider Type',
    'zh-CN': '请选择有效的 Provider Type',
  },
  'ai.catalog.validation.model': {
    'en-US': 'Select a Default Model',
    'zh-CN': '请选择 Default Model',
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
    'zh-CN': '请添加 Variant',
  },
  'ai.catalog.validation.defaultVariantField': {
    'en-US': 'Select a default Variant',
    'zh-CN': '请选择默认 Variant',
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
  'ai.chat.create': {
    'en-US': 'Create Chat',
    'zh-CN': '新建 Chat',
  },
  'ai.chat.createDescription': {
    'en-US': 'Create a Chat (an Agent is required)',
    'zh-CN': '新建 Chat（需选择 Agent）',
  },
  'ai.chat.edit': {
    'en-US': 'Edit Chat',
    'zh-CN': '编辑 Chat',
  },
  'ai.chat.deleteTitle': {
    'en-US': 'Delete Chat',
    'zh-CN': '删除 Chat',
  },
  'ai.chat.deleteDescription': {
    'en-US': 'Are you sure you want to delete Chat "{{title}}"? This action cannot be undone.',
    'zh-CN': '确定要删除 Chat“{{title}}”吗？删除后不可恢复。',
  },
  'ai.chat.untitled': {
    'en-US': 'Untitled Chat',
    'zh-CN': 'Untitled Chat',
  },
  'ai.chat.chatLabel': {
    'en-US': 'Chat',
    'zh-CN': 'Chat',
  },
  'ai.chat.agent': {
    'en-US': 'Agent',
    'zh-CN': 'Agent',
  },
  'ai.chat.environment': {
    'en-US': 'Environment',
    'zh-CN': 'Environment',
  },
  'ai.chat.updated': {
    'en-US': 'Updated',
    'zh-CN': 'Updated',
  },
  'ai.chat.enterAria': {
    'en-US': 'Open Chat {{label}}',
    'zh-CN': '进入 Chat {{label}}',
  },
  'ai.chat.missingAgent': {
    'en-US': '(Deleted or missing)',
    'zh-CN': '（已删除/缺失）',
  },
  'ai.chat.namePlaceholder': {
    'en-US': 'Chat name (duplicates allowed)',
    'zh-CN': 'Chat 名称（可重名）',
  },
  'ai.chat.selectAgent': {
    'en-US': 'Select an Agent',
    'zh-CN': '请选择 Agent',
  },
  'ai.chat.selected': {
    'en-US': 'Selected',
    'zh-CN': '已选',
  },
  'ai.chat.selectAgentTitle': {
    'en-US': 'Select Agent',
    'zh-CN': '选择 Agent',
  },
  'ai.chat.nameRequired': {
    'en-US': 'Enter a Chat name',
    'zh-CN': '请填写 Chat 名称',
  },
  'ai.chat.nameFieldRequired': {
    'en-US': 'Enter a name',
    'zh-CN': '请填写名称',
  },
  'ai.chat.agentRequired': {
    'en-US': 'Select an Agent',
    'zh-CN': '请选择 Agent',
  },
  'ai.chat.loading': {
    'en-US': 'Loading Chat…',
    'zh-CN': '正在加载 Chat…',
  },
  'ai.chat.loadFailed': {
    'en-US': 'Chat failed to load',
    'zh-CN': 'Chat 加载失败',
  },
  'ai.chat.backToList': {
    'en-US': 'Back to Chat list',
    'zh-CN': '返回列表',
  },
  'ai.chat.backToChatList': {
    'en-US': 'Back to Chat list',
    'zh-CN': '返回 Chat 列表',
  },
  'ai.chat.layout': {
    'en-US': 'Layout',
    'zh-CN': '布局',
  },
  'ai.chat.blankTitle': {
    'en-US': 'New conversation',
    'zh-CN': '新对话',
  },
  'ai.chat.blankDescription': {
    'en-US': 'Send a message to create a new Thread. Use slash commands to change the Agent or model, or reuse an existing Thread.',
    'zh-CN': '输入消息以创建新 Thread。使用斜杠命令切换 Agent 或模型，或复用已有 Thread。',
  },
  'ai.chat.scope': {
    'en-US': 'Scope',
    'zh-CN': '范围',
  },
  'ai.chat.currentChat': {
    'en-US': 'Current Chat',
    'zh-CN': '当前 Chat',
  },
  'ai.chat.globalThread': {
    'en-US': 'Global Thread',
    'zh-CN': '全局 Thread',
  },
  'ai.chat.sort': {
    'en-US': 'Sort',
    'zh-CN': '排序',
  },
  'ai.chat.recentlyUpdated': {
    'en-US': 'Recently updated',
    'zh-CN': '最近更新',
  },
  'ai.chat.createdAt': {
    'en-US': 'Created',
    'zh-CN': '创建时间',
  },
  'ai.chat.loadingList': {
    'en-US': 'Loading…',
    'zh-CN': '加载中…',
  },
  'ai.chat.loadMore': {
    'en-US': 'Load more',
    'zh-CN': '继续加载',
  },
  'ai.chat.noOptions': {
    'en-US': 'No options',
    'zh-CN': '暂无选项',
  },
  'ai.chat.noAgents': {
    'en-US': 'No Agents available',
    'zh-CN': '暂无可用 Agent',
  },
  'ai.chat.selectSession': {
    'en-US': 'Select Session',
    'zh-CN': '选择 Session',
  },
  'ai.chat.noSessions': {
    'en-US': 'No Sessions',
    'zh-CN': '暂无 Session',
  },
  'ai.chat.selectThread': {
    'en-US': 'Select Thread',
    'zh-CN': '选择 Thread',
  },
  'ai.chat.noThreads': {
    'en-US': 'No Threads',
    'zh-CN': '暂无 Thread',
  },
  'ai.chat.selection.search': {
    'en-US': 'Search',
    'zh-CN': '搜索',
  },
  'ai.chat.selection.options': {
    'en-US': '{{title}} options',
    'zh-CN': '{{title}}选项',
  },
  'ai.chat.selection.noMatch': {
    'en-US': 'No matches for “{{query}}”',
    'zh-CN': '没有匹配“{{query}}”的选项',
  },
  'ai.chat.selection.meta': {
    'en-US': '{{visible}} / {{total}} · ↑↓ select · Enter confirm · Esc back{{cycle}}',
    'zh-CN': '{{visible}} / {{total}} · ↑↓ 选择 · Enter 确认 · Esc 返回{{cycle}}',
  },
  'ai.chat.selection.tabSort': {
    'en-US': 'Tab switch sort',
    'zh-CN': 'Tab 切换排序',
  },
  'ai.chat.history.title': {
    'en-US': 'History branches',
    'zh-CN': '历史分支',
  },
  'ai.chat.history.filter': {
    'en-US': 'Show records',
    'zh-CN': '显示记录',
  },
  'ai.chat.history.search': {
    'en-US': 'Search records',
    'zh-CN': '搜索记录',
  },
  'ai.chat.history.list': {
    'en-US': 'History list',
    'zh-CN': '历史列表',
  },
  'ai.chat.history.conversation': {
    'en-US': 'Conversation',
    'zh-CN': '对话',
  },
  'ai.chat.history.allRecords': {
    'en-US': 'All records',
    'zh-CN': '全部记录',
  },
  'ai.chat.history.user': {
    'en-US': 'User',
    'zh-CN': '用户',
  },
  'ai.chat.history.assistant': {
    'en-US': 'Assistant',
    'zh-CN': '助手',
  },
  'ai.chat.history.tool': {
    'en-US': 'Tool',
    'zh-CN': '工具',
  },
  'ai.chat.history.custom': {
    'en-US': 'Custom',
    'zh-CN': '自定义',
  },
  'ai.chat.history.system': {
    'en-US': 'System',
    'zh-CN': '系统',
  },
  'ai.chat.history.loading': {
    'en-US': 'Loading history branches…',
    'zh-CN': '正在加载历史分支…',
  },
  'ai.chat.history.loadFailed': {
    'en-US': 'History branches failed to load',
    'zh-CN': '历史分支加载失败',
  },
  'ai.chat.history.noMatch': {
    'en-US': 'No records match “{{query}}”',
    'zh-CN': '没有匹配 “{{query}}” 的记录',
  },
  'ai.chat.history.empty': {
    'en-US': 'No records to display',
    'zh-CN': '没有可显示的记录',
  },
  'ai.chat.history.noBody': {
    'en-US': 'No content',
    'zh-CN': '无正文',
  },
  'ai.chat.history.currentPath': {
    'en-US': 'current path',
    'zh-CN': '当前路径',
  },
  'ai.chat.history.currentPosition': {
    'en-US': 'current Thread position',
    'zh-CN': '当前线程位置',
  },
  'ai.chat.history.entryAria': {
    'en-US': '{{kind}} · {{preview}}{{path}}{{head}}',
    'zh-CN': '{{kind}} · {{preview}}{{path}}{{head}}',
  },
  'ai.chat.history.hint': {
    'en-US': '{{current}} / {{total}} · ↑↓ select · Enter confirm · Esc back',
    'zh-CN': '{{current}} / {{total}} · ↑↓ 选择 · Enter 确认 · Esc 返回',
  },
  'ai.chat.history.cancel': {
    'en-US': 'Cancel',
    'zh-CN': '取消',
  },
  'ai.chat.history.continue': {
    'en-US': 'Continue current Thread from here',
    'zh-CN': '从这里继续当前 Thread',
  },
  'ai.chat.sessionSubtitle': {
    'en-US': 'Session {{id}}',
    'zh-CN': 'Session {{id}}',
  },
  'ai.runtime.composer.placeholder': {
    'en-US': 'Enter a task (/ for commands)',
    'zh-CN': '输入任务（/打开命令）',
  },
  'ai.runtime.composer.ariaLabel': {
    'en-US': 'Send a message to AI',
    'zh-CN': '给 AI 发送消息',
  },
  'ai.runtime.composer.send': {
    'en-US': 'Send message',
    'zh-CN': '发送消息',
  },
  'ai.runtime.composer.previewLoading': {
    'en-US': 'Generating request preview…',
    'zh-CN': '正在生成请求预览…',
  },
  'ai.runtime.composer.openCommands': {
    'en-US': 'Open commands',
    'zh-CN': '打开命令表',
  },
  'ai.runtime.composer.permission': {
    'en-US': 'Permission mode',
    'zh-CN': '权限模式',
  },
  'ai.runtime.composer.permissionOptions': {
    'en-US': 'Permission options',
    'zh-CN': '权限选项',
  },
  'ai.runtime.composer.permissionDefault': {
    'en-US': 'Default',
    'zh-CN': 'Default',
  },
  'ai.runtime.composer.permissionYolo': {
    'en-US': 'YOLO',
    'zh-CN': 'YOLO',
  },
  'ai.runtime.composer.environment': {
    'en-US': 'Environment',
    'zh-CN': '环境',
  },
  'ai.runtime.composer.environmentOptions': {
    'en-US': 'Environment options',
    'zh-CN': '环境选项',
  },
  'ai.runtime.composer.environmentNone': {
    'en-US': 'None',
    'zh-CN': 'None',
  },
  'ai.runtime.composer.model': {
    'en-US': 'Model',
    'zh-CN': 'Model',
  },
  'ai.runtime.composer.modelVariant': {
    'en-US': 'Model and Variant',
    'zh-CN': 'Model 与 Variant',
  },
  'ai.runtime.composer.modelOptions': {
    'en-US': 'Model options',
    'zh-CN': 'Model 选项',
  },
  'ai.runtime.composer.modelSearch': {
    'en-US': 'Search models',
    'zh-CN': '搜索模型',
  },
  'ai.runtime.composer.modelSearchPlaceholder': {
    'en-US': 'Search models',
    'zh-CN': '搜索模型',
  },
  'ai.runtime.composer.variantOptions': {
    'en-US': 'Variant options',
    'zh-CN': 'Variant 选项',
  },
  'ai.runtime.composer.strip': {
    'en-US': 'Attachments',
    'zh-CN': '附件',
  },
  'ai.runtime.composer.uploading': {
    'en-US': 'Uploading…',
    'zh-CN': '上传中…',
  },
  'ai.runtime.composer.completeUnknown': {
    'en-US': 'Status unknown, retry',
    'zh-CN': '状态未知，可重试',
  },
  'ai.runtime.composer.retryComplete': {
    'en-US': 'Retry complete {{name}}',
    'zh-CN': '重试完成 {{name}}',
  },
  'ai.runtime.composer.uploadFailed': {
    'en-US': 'Upload failed',
    'zh-CN': '上传失败',
  },
  'ai.runtime.composer.uploadFailedDetail': {
    'en-US': 'Upload failed for {{name}}: {{reason}}',
    'zh-CN': '{{name}} 上传失败：{{reason}}',
  },
  'ai.runtime.composer.dismissNotification': {
    'en-US': 'Dismiss notification',
    'zh-CN': '关闭通知',
  },
  'ai.runtime.composer.removeAttachment': {
    'en-US': 'Remove attachment {{name}}',
    'zh-CN': '移除附件 {{name}}',
  },
  'ai.runtime.composer.imageTier': {
    'en-US': 'Image resolution for {{name}}',
    'zh-CN': '{{name}} 图片清晰度',
  },
  'ai.runtime.composer.tierOriginal': {
    'en-US': 'Original',
    'zh-CN': '原图',
  },
  'ai.runtime.composer.fileTooLarge': {
    'en-US': '{{name}} exceeds the {{limit}} upload limit',
    'zh-CN': '{{name}} 超过 {{limit}} 上传限制',
  },
  'ai.runtime.command.palette': {
    'en-US': 'Command palette',
    'zh-CN': '命令表',
  },
  'ai.runtime.command.noMatch': {
    'en-US': 'No matching commands',
    'zh-CN': '无匹配命令',
  },
  'ai.runtime.command.unavailable': {
    'en-US': 'Currently unavailable',
    'zh-CN': '当前不可用',
  },
  'ai.runtime.command.meta': {
    'en-US': '{{enabled}} available / {{total}} total',
    'zh-CN': '可用 {{enabled}} / 共 {{total}}',
  },
  'ai.runtime.command.matches': {
    'en-US': ' · {{count}} matches',
    'zh-CN': ' · 匹配 {{count}}',
  },
  'ai.runtime.command.hint': {
    'en-US': ' · ↑↓ select · Enter confirm',
    'zh-CN': ' · ↑↓ 选择 · Enter 确认',
  },
  'ai.runtime.command.disabledReason': {
    'en-US': 'Select or create a Thread first',
    'zh-CN': '选择或创建 Thread 后可用',
  },
  'ai.runtime.command.disabled.readOnly': {
    'en-US': 'Read-only mode',
    'zh-CN': '只读模式',
  },
  'ai.runtime.command.disabled.singleSession': {
    'en-US': 'This project supports only one session',
    'zh-CN': '当前项目仅支持单会话',
  },
  'ai.runtime.command.upload': {
    'en-US': 'Upload files',
    'zh-CN': '上传文件',
  },
  'ai.runtime.command.thread': {
    'en-US': 'Switch Thread',
    'zh-CN': '切换 Thread',
  },
  'ai.runtime.command.agent': {
    'en-US': 'Switch Agent',
    'zh-CN': '切换 Agent',
  },
  'ai.runtime.command.yolo': {
    'en-US': 'Toggle automatic approval of Tool calls',
    'zh-CN': '切换 YOLO 自动批准工具调用',
  },
  'ai.runtime.command.tree': {
    'en-US': 'View history and pick where to continue',
    'zh-CN': '查看历史并选择续接位置',
  },
  'ai.runtime.command.stop': {
    'en-US': 'Stop the current Thread and restore unprocessed messages',
    'zh-CN': '停止当前 Thread 并恢复尚未处理的消息',
  },
  'ai.runtime.command.new': {
    'en-US': 'Start a new Session / Thread',
    'zh-CN': '新建 Session / Thread',
  },
  'ai.runtime.command.debug': {
    'en-US': 'Toggle the debug view',
    'zh-CN': '切换调试视图',
  },
  'ai.runtime.command.compact': {
    'en-US': 'Compact the current Thread context',
    'zh-CN': '压缩当前 Thread 的上下文',
  },
  'ai.runtime.command.shortcuts': {
    'en-US': 'Show keyboard shortcuts',
    'zh-CN': '查看键盘快捷键',
  },
  'ai.runtime.command.uploadLabel': {
    'en-US': 'upload',
    'zh-CN': 'upload',
  },
  'ai.runtime.command.threadLabel': {
    'en-US': 'thread',
    'zh-CN': 'thread',
  },
  'ai.runtime.command.agentLabel': {
    'en-US': 'agent',
    'zh-CN': 'agent',
  },
  'ai.runtime.command.models': {
    'en-US': 'Choose the model and Variant',
    'zh-CN': '选择模型与 Variant',
  },
  'ai.runtime.command.yoloLabel': {
    'en-US': 'yolo',
    'zh-CN': 'yolo',
  },
  'ai.runtime.command.modelsLabel': {
    'en-US': 'models',
    'zh-CN': 'models',
  },
  'ai.runtime.command.treeLabel': {
    'en-US': 'tree',
    'zh-CN': 'tree',
  },
  'ai.runtime.command.stopLabel': {
    'en-US': 'stop',
    'zh-CN': 'stop',
  },
  'ai.runtime.command.newLabel': {
    'en-US': 'new',
    'zh-CN': 'new',
  },
  'ai.runtime.command.debugLabel': {
    'en-US': 'debug',
    'zh-CN': 'debug',
  },
  'ai.runtime.command.compactLabel': {
    'en-US': 'compact',
    'zh-CN': 'compact',
  },
  'ai.runtime.command.shortcutsLabel': {
    'en-US': 'shortcuts',
    'zh-CN': 'shortcuts',
  },
  'ai.runtime.command.rename-session': {
    'en-US': 'Rename the current Session',
    'zh-CN': '重命名当前 Session',
  },
  'ai.runtime.command.rename-sessionLabel': {
    'en-US': 'rename-session',
    'zh-CN': 'rename-session',
  },
  'ai.runtime.command.rename-thread': {
    'en-US': 'Rename the current Thread',
    'zh-CN': '重命名当前 Thread',
  },
  'ai.runtime.command.rename-threadLabel': {
    'en-US': 'rename-thread',
    'zh-CN': 'rename-thread',
  },
  'ai.runtime.command.goal': {
    'en-US': 'View, edit, or clear branch goal',
    'zh-CN': '查看、编辑或清除分支目标',
  },
  'ai.runtime.command.goalLabel': {
    'en-US': 'goal',
    'zh-CN': 'goal',
  },
  'ai.runtime.goal.title': {
    'en-US': 'Branch Goal',
    'zh-CN': '分支目标',
  },
  'ai.runtime.goal.currentGoal': {
    'en-US': 'Current Goal',
    'zh-CN': '当前目标',
  },
  'ai.runtime.goal.emptyGoal': {
    'en-US': 'No goal set for this branch',
    'zh-CN': '当前分支暂无设定目标',
  },
  'ai.runtime.goal.agentReportTitle': {
    'en-US': 'Agent Progress Report',
    'zh-CN': 'Agent 进展报告',
  },
  'ai.runtime.goal.agentReportDisclaimer': {
    'en-US': 'Reported by Agent (not system acceptance)',
    'zh-CN': '由 Agent 报告，非系统验收',
  },
  'ai.runtime.goal.statusCompleteReported': {
    'en-US': 'Completed (Agent Report)',
    'zh-CN': '已完成（Agent 报告）',
  },
  'ai.runtime.goal.statusBlockedReported': {
    'en-US': 'Blocked (Agent Report)',
    'zh-CN': '阻塞（Agent 报告）',
  },
  'ai.runtime.goal.staleReportNotice': {
    'en-US': 'Previous goal progress report is stale',
    'zh-CN': '历史目标的 Agent 报告已失效',
  },
  'ai.runtime.goal.setGoalLabel': {
    'en-US': 'Set New Goal',
    'zh-CN': '设置新目标',
  },
  'ai.runtime.goal.updateGoalLabel': {
    'en-US': 'Update Goal',
    'zh-CN': '更新目标',
  },
  'ai.runtime.goal.inputPlaceholder': {
    'en-US': 'Enter branch goal (max 2,000 characters)...',
    'zh-CN': '输入分支目标（最多 2000 字符）...',
  },
  'ai.runtime.goal.setGoalBtn': {
    'en-US': 'Set Goal',
    'zh-CN': '设置目标',
  },
  'ai.runtime.goal.clearGoalBtn': {
    'en-US': 'Clear Goal',
    'zh-CN': '清除目标',
  },
  'ai.runtime.goal.busy': {
    'en-US': 'Applying...',
    'zh-CN': '正在提交...',
  },
  'ai.runtime.goal.readOnlyNotice': {
    'en-US': 'Read-only mode; cannot modify goal.',
    'zh-CN': '只读模式，不可修改目标。',
  },
  'ai.runtime.goal.notAllowed': {
    'en-US': 'Goal is not supported on this branch',
    'zh-CN': '当前分支不支持设置目标',
  },
  'ai.runtime.goal.errorEmpty': {
    'en-US': 'Goal text cannot be empty',
    'zh-CN': '目标正文不能为空',
  },
  'ai.runtime.goal.errorTooLong': {
    'en-US': 'Goal text must be 2,000 characters or fewer',
    'zh-CN': '目标正文不能超过 2000 个字符',
  },
  'ai.runtime.goal.submitFailed': {
    'en-US': 'Failed to update goal',
    'zh-CN': '更新目标失败',
  },
  'ai.runtime.rename.sessionTitle': {
    'en-US': 'Rename Session',
    'zh-CN': '重命名 Session',
  },
  'ai.runtime.rename.threadTitle': {
    'en-US': 'Rename Thread',
    'zh-CN': '重命名 Thread',
  },
  'ai.runtime.rename.nameLabel': {
    'en-US': 'Name',
    'zh-CN': '名称',
  },
  'ai.runtime.rename.namePlaceholder': {
    'en-US': 'Enter a name',
    'zh-CN': '输入名称',
  },
  'ai.runtime.rename.nameRequired': {
    'en-US': 'Name must not be empty',
    'zh-CN': '名称不能为空',
  },
  'ai.runtime.rename.save': {
    'en-US': 'Save',
    'zh-CN': '保存',
  },
  'ai.runtime.rename.failed': {
    'en-US': 'Rename failed',
    'zh-CN': '重命名失败',
  },
  'ai.runtime.rename.loadingName': {
    'en-US': 'Loading name…',
    'zh-CN': '正在加载名称…',
  },
  'ai.runtime.rename.sessionUnavailable': {
    'en-US': 'Session is no longer available',
    'zh-CN': 'Session 已不可用',
  },
  'ai.runtime.rename.threadUnavailable': {
    'en-US': 'Thread is no longer available',
    'zh-CN': 'Thread 已不可用',
  },
  'ai.runtime.rename.titleAria': {
    'en-US': 'Rename',
    'zh-CN': '重命名',
  },
  'ai.runtime.agentTree.toggle': {
    'en-US': 'Agent relationships',
    'zh-CN': 'Agent 关系',
  },
  'ai.runtime.agentTree.panel': {
    'en-US': 'Agent relationships',
    'zh-CN': 'Agent 关系',
  },
  'ai.runtime.agentTree.refresh': {
    'en-US': 'Refresh',
    'zh-CN': '刷新',
  },
  'ai.runtime.agentTree.loading': {
    'en-US': 'Loading agent relationships…',
    'zh-CN': '正在加载 Agent 关系…',
  },
  'ai.runtime.agentTree.error': {
    'en-US': 'Agent relationships failed to load',
    'zh-CN': 'Agent 关系加载失败',
  },
  'ai.runtime.agentTree.refreshFailed': {
    'en-US': 'Agent relationships failed to refresh',
    'zh-CN': 'Agent 关系刷新失败',
  },
  'ai.runtime.agentTree.retry': {
    'en-US': 'Retry',
    'zh-CN': '重试',
  },
  'ai.runtime.agentTree.empty': {
    'en-US': 'No agent relationships',
    'zh-CN': '没有 Agent 关系',
  },
  'ai.runtime.agentTree.root': {
    'en-US': 'Main agent',
    'zh-CN': '主 Agent',
  },
  'ai.runtime.agentTree.current': {
    'en-US': 'Current',
    'zh-CN': '当前',
  },
  'ai.runtime.agentTree.turns': {
    'en-US': '{{count}} turns',
    'zh-CN': '{{count}} 回合',
  },
  'ai.runtime.agentTree.toolCalls': {
    'en-US': '{{count}} tool calls',
    'zh-CN': '{{count}} 次工具调用',
  },
  'ai.runtime.agentTree.outcome.COMPLETED': {
    'en-US': 'Completed',
    'zh-CN': '已完成',
  },
  'ai.runtime.agentTree.outcome.FAILED': {
    'en-US': 'Failed',
    'zh-CN': '失败',
  },
  'ai.runtime.agentTree.outcome.STOPPED': {
    'en-US': 'Stopped',
    'zh-CN': '已停止',
  },
  'ai.runtime.agentTree.outcome.CANCELLED': {
    'en-US': 'Cancelled',
    'zh-CN': '已取消',
  },
  'ai.runtime.shortcuts.title': {
    'en-US': 'Keyboard shortcuts',
    'zh-CN': '键盘快捷键',
  },
  'ai.runtime.thread.loading': {
    'en-US': 'Loading conversation…',
    'zh-CN': '正在加载会话…',
  },
  'ai.runtime.thread.transcript': {
    'en-US': 'Conversation messages',
    'zh-CN': '会话消息',
  },
  'ai.runtime.thread.loadFailed': {
    'en-US': 'Conversation failed to load',
    'zh-CN': '会话加载失败',
  },
  'ai.runtime.thread.empty': {
    'en-US': 'Send a message to start the conversation',
    'zh-CN': '发送消息，开始对话',
  },
  'ai.runtime.thread.keyboardHint': {
    'en-US': 'Enter to send · Shift+Enter for a new line',
    'zh-CN': 'Enter 发送 · Shift+Enter 换行',
  },
  'ai.runtime.thread.error': {
    'en-US': 'Error',
    'zh-CN': '错误',
  },
  'ai.runtime.thread.dismissError': {
    'en-US': 'Dismiss error',
    'zh-CN': '关闭错误',
  },
  'ai.runtime.thread.status': {
    'en-US': 'Thread status',
    'zh-CN': '会话状态',
  },
  'ai.runtime.thread.widgetZone': {
    'en-US': 'Thread activity',
    'zh-CN': '会话活动',
  },
  'ai.runtime.thread.queue': {
    'en-US': 'Queued messages',
    'zh-CN': '等待处理的消息',
  },
  'ai.runtime.thread.queued': {
    'en-US': 'queued',
    'zh-CN': 'queued',
  },
  'ai.runtime.thread.working': {
    'en-US': 'Working...',
    'zh-CN': 'Working...',
  },
  'ai.runtime.thread.status.STOPPED': {
    'en-US': 'Stopped',
    'zh-CN': '已停止',
  },
  'ai.runtime.thread.status.QUEUED': {
    'en-US': 'Queued',
    'zh-CN': '排队中',
  },
  'ai.runtime.thread.status.IDLE': {
    'en-US': 'Idle',
    'zh-CN': '空闲',
  },
  'ai.runtime.thread.status.CONTINUATION_DUE': {
    'en-US': 'Continuation due',
    'zh-CN': '待推进',
  },
  'ai.runtime.thread.status.APPLYING': {
    'en-US': 'Applying',
    'zh-CN': '应用中',
  },
  'ai.runtime.thread.status.MODEL_READY': {
    'en-US': 'Model ready',
    'zh-CN': '模型就绪',
  },
  'ai.runtime.thread.status.MODEL_DISPATCHING': {
    'en-US': 'Dispatching model',
    'zh-CN': '分派模型中',
  },
  'ai.runtime.thread.status.MODEL_RUNNING': {
    'en-US': 'Model running',
    'zh-CN': '模型运行中',
  },
  'ai.runtime.thread.status.TOOL_WAITING_APPROVAL': {
    'en-US': 'Waiting for approval',
    'zh-CN': '等待审批',
  },
  'ai.runtime.thread.status.TOOL_READY': {
    'en-US': 'Tool ready',
    'zh-CN': '工具就绪',
  },
  'ai.runtime.thread.status.TOOL_DISPATCHING': {
    'en-US': 'Dispatching tool',
    'zh-CN': '分派工具中',
  },
  'ai.runtime.thread.status.TOOL_RUNNING': {
    'en-US': 'Tool running',
    'zh-CN': '工具运行中',
  },
  'ai.runtime.message.stopped': {
    'en-US': 'Stopped',
    'zh-CN': '已停止',
  },
  'ai.runtime.message.copyAll': {
    'en-US': 'Copy all',
    'zh-CN': '复制全文',
  },
  'ai.runtime.message.assistantFailed': {
    'en-US': 'Assistant response failed',
    'zh-CN': '助手回复失败',
  },
  'ai.runtime.thinking.expand': {
    'en-US': 'Expand thinking',
    'zh-CN': '展开思考',
  },
  'ai.runtime.thinking.collapse': {
    'en-US': 'Collapse thinking',
    'zh-CN': '收起思考',
  },
  'ai.runtime.message.modelRequestFailed': {
    'en-US': 'Model request failed',
    'zh-CN': '模型请求失败',
  },
  'ai.runtime.message.attemptNumber': {
    'en-US': 'Request #{{attempt}}',
    'zh-CN': '请求 #{{attempt}}',
  },
  'ai.runtime.message.retryScheduled': {
    'en-US': 'Retry in {{seconds}}s (request #{{nextAttempt}})',
    'zh-CN': '{{seconds}} 秒后重试（请求 #{{nextAttempt}}）',
  },
  'ai.runtime.message.retryScheduledHistory': {
    'en-US': 'Retry scheduled (request #{{nextAttempt}})',
    'zh-CN': '已安排重试（请求 #{{nextAttempt}}）',
  },
  'ai.runtime.message.retryingNow': {
    'en-US': 'Retrying (request #{{nextAttempt}})',
    'zh-CN': '正在重试（请求 #{{nextAttempt}}）',
  },
  'ai.runtime.message.noArguments': {
    'en-US': '(no arguments)',
    'zh-CN': '（无参数）',
  },
  'ai.runtime.message.expandTool': {
    'en-US': 'Expand tool preview',
    'zh-CN': '展开工具预览',
  },
  'ai.runtime.message.collapseTool': {
    'en-US': 'Collapse tool preview',
    'zh-CN': '收起工具预览',
  },
  'ai.runtime.message.toolFailed': {
    'en-US': 'Tool execution failed.',
    'zh-CN': '工具执行失败。',
  },
  'ai.runtime.message.attachment': {
    'en-US': '{{type}} attachment',
    'zh-CN': '{{type}} 附件',
  },
  'ai.runtime.message.binaryPayload': {
    'en-US': 'binary payload',
    'zh-CN': '二进制内容',
  },
  'ai.runtime.message.downloadResource': {
    'en-US': 'Download {{name}}',
    'zh-CN': '下载 {{name}}',
  },
  'ai.runtime.message.resourceUnavailable': {
    'en-US': 'Resource unavailable',
    'zh-CN': '资源不可用',
  },
  'ai.runtime.message.previewResource': {
    'en-US': 'Preview {{name}}',
    'zh-CN': '预览 {{name}}',
  },
  'ai.runtime.message.closeResourcePreview': {
    'en-US': 'Close media preview',
    'zh-CN': '关闭媒体预览',
  },
  'ai.runtime.message.openResource': {
    'en-US': 'Open original {{name}}',
    'zh-CN': '打开原件 {{name}}',
  },
  'ai.runtime.status.environmentUnavailableText': {
    'en-US': '{{name}} (unavailable)',
    'zh-CN': '{{name}}（不可用）',
  },
  'ai.runtime.status.environmentText': {
    'en-US': '{{name}}',
    'zh-CN': '{{name}}',
  },
  'ai.runtime.status.environmentTitle': {
    'en-US': 'Environment: {{name}}',
    'zh-CN': '环境：{{name}}',
  },
  'ai.runtime.status.environmentUnavailableTitle': {
    'en-US': 'Environment: {{name}} (unavailable)',
    'zh-CN': '环境：{{name}}（不可用）',
  },
  'ai.runtime.status.environmentNoneText': {
    'en-US': 'No environment',
    'zh-CN': '未选择环境',
  },
  'ai.runtime.status.noData': {
    'en-US': 'No data',
    'zh-CN': '暂无数据',
  },
  'ai.runtime.status.branchUsageTitle': {
    'en-US': 'Cumulative usage\nUncached input: {{input}} tokens; output: {{output}} tokens\nCache read: {{cacheRead}} tokens; write: {{cacheWrite}} tokens\nCost: ${{cost}}; cache hit: {{cache}}\nAverage generation speed: {{speed}}',
    'zh-CN': '累计用量\n未缓存输入：{{input}} tokens；输出：{{output}} tokens\n缓存读取：{{cacheRead}} tokens；写入：{{cacheWrite}} tokens\n费用：${{cost}}；缓存命中：{{cache}}\n平均生成速度：{{speed}}',
  },
  'ai.runtime.status.contextText': {
    'en-US': 'ctx {{used}}/{{total}}',
    'zh-CN': 'ctx {{used}}/{{total}}',
  },
  'ai.runtime.status.contextTitleKnown': {
    'en-US': 'Last request context: ~{{used}} / {{total}} tokens',
    'zh-CN': '上次请求上下文：约 {{used}} / {{total}} tokens',
  },
  'ai.runtime.status.contextTitleUnknown': {
    'en-US': 'Last request context: no data (limit {{total}} tokens)',
    'zh-CN': '上次请求上下文：暂无数据（上限 {{total}} tokens）',
  },
  'ai.runtime.usage.metaTooltip': {
    'en-US': '↑ Uncached input · ↓ Output · R Cache read · W Cache write · $ Cost · cache Cache hit rate · tok/s Generation speed',
    'zh-CN': '↑ 未缓存输入 · ↓ 输出 · R 缓存读取 · W 缓存写入 · $ 费用 · cache 缓存命中率 · tok/s 生成速度',
  },
  'ai.runtime.notification.permissionTitle': {
    'en-US': 'Approval requested',
    'zh-CN': '等待审批',
  },
  'ai.runtime.notification.permissionBody': {
    'en-US': '{{title}} is waiting to run {{toolName}}.',
    'zh-CN': '{{title}} 正在等待运行 {{toolName}}。',
  },
  'ai.runtime.notification.permissionBodyWithReason': {
    'en-US': '{{title}} is waiting to run {{toolName}}: {{reason}}',
    'zh-CN': '{{title}} 正在等待运行 {{toolName}}：{{reason}}',
  },
  'ai.runtime.notification.completedTitle': {
    'en-US': 'Agent completed',
    'zh-CN': 'Agent 已完成',
  },
  'ai.runtime.notification.errorTitle': {
    'en-US': 'Agent failed',
    'zh-CN': 'Agent 执行失败',
  },
  'ai.runtime.notification.agentBody': {
    'en-US': '{{title}}',
    'zh-CN': '{{title}}',
  },
  'ai.runtime.notification.entry.SUBAGENT_RESULTTitle': {
    'en-US': 'Subagent result',
    'zh-CN': '子 Thread 结果',
  },
  'ai.runtime.notification.entry.TASK_BUDGETTitle': {
    'en-US': 'Task budget reminder',
    'zh-CN': '任务预算提醒',
  },
  'ai.runtime.notification.entry.unknownTitle': {
    'en-US': 'System notification',
    'zh-CN': '系统通知',
  },
  'ai.runtime.notification.entry.source': {
    'en-US': 'Source',
    'zh-CN': '来源',
  },
  'ai.runtime.notification.entry.error': {
    'en-US': 'Error',
    'zh-CN': '错误',
  },
  'ai.runtime.notification.entry.cancelled': {
    'en-US': 'Cancelled',
    'zh-CN': '已取消',
  },
  'ai.runtime.notification.entry.partial': {
    'en-US': 'Partial result',
    'zh-CN': '部分结果',
  },
  'ai.runtime.notification.entry.invalidReceipt': {
    'en-US': 'The subagent receipt is not a valid XML envelope and cannot be displayed.',
    'zh-CN': '子 Thread 回执不是合法的 XML 信封，无法展示。',
  },
  'ai.runtime.notification.entry.emptyText': {
    'en-US': 'No text content was delivered with this notification.',
    'zh-CN': '该通知没有附带文本内容。',
  },
  'ai.runtime.task.state.accepted': {
    'en-US': 'Accepted / Running in background',
    'zh-CN': '已接受 / 后台执行',
  },
  'ai.runtime.task.state.completed': {
    'en-US': 'Completed',
    'zh-CN': '已完成',
  },
  'ai.runtime.task.state.error': {
    'en-US': 'Failed',
    'zh-CN': '失败',
  },
  'ai.runtime.task.state.cancelled': {
    'en-US': 'Cancelled',
    'zh-CN': '已取消',
  },
  'ai.runtime.task.subagent': {
    'en-US': 'Subagent',
    'zh-CN': '子代理',
  },
  'ai.runtime.task.prompt': {
    'en-US': 'Prompt',
    'zh-CN': '任务提示',
  },
  'ai.runtime.task.thread': {
    'en-US': 'Thread ID',
    'zh-CN': 'Thread ID',
  },
  'ai.runtime.task.session': {
    'en-US': 'Session',
    'zh-CN': '会话',
  },
  'ai.runtime.task.maxTurns': {
    'en-US': 'Max turns',
    'zh-CN': '最大轮数',
  },
  'ai.runtime.task.report': {
    'en-US': 'Report',
    'zh-CN': '报告',
  },
  'ai.runtime.task.error': {
    'en-US': 'Error',
    'zh-CN': '错误',
  },
  'ai.thread.invalidId': {
    'en-US': 'Invalid thread ID',
    'zh-CN': '无效的 Thread ID',
  },
  'ai.thread.loadFailed': {
    'en-US': 'Failed to load thread',
    'zh-CN': '加载 Thread 失败',
  },
  'ai.thread.workspaceTitle': {
    'en-US': 'Thread: {{threadId}}',
    'zh-CN': 'Thread: {{threadId}}',
  },
  'ai.runtime.entry.rootTitle': {
    'en-US': 'Conversation started',
    'zh-CN': '会话开始',
  },
  'ai.runtime.entry.rootText': {
    'en-US': 'The conversation was created.',
    'zh-CN': '会话已创建。',
  },
  'ai.runtime.entry.rootSettings': {
    'en-US': 'Conversation started · Agent: {{agent}} · Model: {{model}} · Environment: {{environment}}',
    'zh-CN': '会话开始 · Agent: {{agent}} · 模型: {{model}} · 环境: {{environment}}',
  },
  'ai.runtime.entry.noEnvironment': {
    'en-US': 'none',
    'zh-CN': '无',
  },
  'ai.runtime.entry.invalidSettings': {
    'en-US': 'Settings snapshot could not be read',
    'zh-CN': '配置快照不可解析',
  },
  'ai.runtime.entry.agentChanged': {
    'en-US': 'Agent changed to {{agent}}',
    'zh-CN': 'Agent 改为 {{agent}}',
  },
  'ai.runtime.entry.modelChanged': {
    'en-US': 'Model changed to {{model}}',
    'zh-CN': '模型改为 {{model}}',
  },
  'ai.runtime.entry.environmentChanged': {
    'en-US': 'Environment changed to {{environment}}',
    'zh-CN': '环境改为 {{environment}}',
  },
  'ai.runtime.entry.environmentDetached': {
    'en-US': 'Environment detached',
    'zh-CN': '环境已解除',
  },
  'ai.runtime.entry.unknownType': {
    'en-US': 'unknown type',
    'zh-CN': '未知类型',
  },
  'ai.runtime.entry.unknownRole': {
    'en-US': 'Unknown role',
    'zh-CN': '未知角色',
  },
  'ai.runtime.entry.emptyTitle': {
    'en-US': '{{role}} message',
    'zh-CN': '{{role}} 消息',
  },
  'ai.runtime.entry.emptyText': {
    'en-US': 'This Entry has no displayable text, thinking, Tool call, or Tool result.',
    'zh-CN': '该消息 Entry 没有可展示的文本、思考、工具调用或工具结果。',
  },
  'ai.runtime.entry.unsupportedTitle': {
    'en-US': 'Unrecognized message',
    'zh-CN': '无法识别消息',
  },
  'ai.runtime.entry.unsupportedRoleText': {
    'en-US': 'Message role not supported: {{role}}.',
    'zh-CN': '暂不支持的消息角色：{{role}}。',
  },
  'ai.runtime.entry.unsupportedText': {
    'en-US': 'Message role or data is invalid.',
    'zh-CN': '消息角色或数据无效。',
  },
  'ai.runtime.entry.unknownTitle': {
    'en-US': 'Unrecognized Entry: {{type}}',
    'zh-CN': '未识别 Entry：{{type}}',
  },
  'ai.runtime.entry.unknownText': {
    'en-US': 'This entry type cannot be displayed.',
    'zh-CN': '无法显示此类型的记录。',
  },
  'ai.runtime.event.list': {
    'en-US': 'Events',
    'zh-CN': '事件',
  },
  'ai.runtime.event.systemPrompt': {
    'en-US': 'System prompt',
    'zh-CN': '系统提示词',
  },
  'ai.runtime.event.empty': {
    'en-US': 'No events yet',
    'zh-CN': '暂无事件',
  },
  'ai.runtime.event.emptyText': {
    'en-US': '(no summary)',
    'zh-CN': '（无摘要）',
  },
  'ai.runtime.event.abortedText': {
    'en-US': 'The assistant response was stopped by the user.',
    'zh-CN': '助手回复已被用户停止。',
  },
  'ai.runtime.event.unknownType': {
    'en-US': 'Unknown entry: {{type}}',
    'zh-CN': '未知条目：{{type}}',
  },
  'ai.runtime.event.unknownTool': {
    'en-US': 'unknown tool',
    'zh-CN': '未知工具',
  },
  'ai.runtime.event.detailTitle': {
    'en-US': 'Event details',
    'zh-CN': '事件详情',
  },
  'ai.runtime.event.closeDetail': {
    'en-US': 'Close event details',
    'zh-CN': '关闭事件详情',
  },
  'ai.runtime.debug.tabPreview': {
    'en-US': 'Request Preview',
    'zh-CN': '请求预览',
  },
  'ai.runtime.debug.tabEvents': {
    'en-US': 'Events',
    'zh-CN': '事件',
  },
  'ai.runtime.debug.tabDetail': {
    'en-US': 'Detail',
    'zh-CN': '详情',
  },
  'ai.runtime.debug.noSelection': {
    'en-US': 'No event or inspection target selected',
    'zh-CN': '未选择任何事件或检查项',
  },
  'ai.runtime.debug.noPreview': {
    'en-US': 'No request preview data',
    'zh-CN': '暂无请求预览数据',
  },
  'ai.runtime.debug.tabsAriaLabel': {
    'en-US': 'Debug views',
    'zh-CN': '调试视图',
  },
  'ai.runtime.debug.previewTitle': {
    'en-US': 'Next Request Preview',
    'zh-CN': '下一次请求预览',
  },
  'ai.runtime.debug.envPrefix': {
    'en-US': 'env: ',
    'zh-CN': '环境: ',
  },
  'ai.runtime.debug.noEnvironmentSelected': {
    'en-US': 'No environment selected',
    'zh-CN': '未选择环境',
  },
  'ai.runtime.debug.requestSnapshot': {
    'en-US': 'Request Snapshot',
    'zh-CN': '请求快照',
  },
  'ai.runtime.debug.requestSnapshotTitle': {
    'en-US': 'View normalized request snapshot for current invocation',
    'zh-CN': '查看当前调用规范化请求快照',
  },
  'ai.runtime.debug.copyPrompt': {
    'en-US': 'Copy Prompt',
    'zh-CN': '复制提示词',
  },
  'ai.runtime.debug.copyPromptTitle': {
    'en-US': 'Copy system prompt',
    'zh-CN': '复制系统提示词',
  },
  'ai.runtime.debug.copied': {
    'en-US': 'Copied',
    'zh-CN': '已复制',
  },
  'ai.runtime.debug.copyFailed': {
    'en-US': 'Copy failed',
    'zh-CN': '复制失败',
  },
  'ai.runtime.debug.planningError': {
    'en-US': 'Planning Error',
    'zh-CN': '规划错误',
  },
  'ai.runtime.debug.systemPromptTitle': {
    'en-US': 'System Prompt',
    'zh-CN': '系统提示词',
  },
  'ai.runtime.debug.emptyPrompt': {
    'en-US': '(empty)',
    'zh-CN': '（空）',
  },
  'ai.runtime.debug.toolsTitle': {
    'en-US': 'TOOLS',
    'zh-CN': '工具',
  },
  'ai.runtime.debug.toolsSent': {
    'en-US': 'sent',
    'zh-CN': '已发送',
  },
  'ai.runtime.debug.toolsFiltered': {
    'en-US': 'filtered',
    'zh-CN': '已过滤',
  },
  'ai.runtime.debug.toolAriaPrefix': {
    'en-US': 'Tool',
    'zh-CN': '工具',
  },
  'ai.runtime.debug.filteredToolAriaPrefix': {
    'en-US': 'Filtered tool',
    'zh-CN': '已过滤工具',
  },
  'ai.runtime.debug.none': {
    'en-US': 'none',
    'zh-CN': '无',
  },
  'ai.runtime.debug.skillsTitle': {
    'en-US': 'SKILLS',
    'zh-CN': '技能',
  },
  'ai.runtime.debug.noSkills': {
    'en-US': 'No skills',
    'zh-CN': '暂无技能',
  },
  'ai.runtime.debug.skillAriaPrefix': {
    'en-US': 'Skill',
    'zh-CN': '技能',
  },
  'ai.runtime.debug.delivery.local': {
    'en-US': 'local',
    'zh-CN': '本地',
  },
  'ai.runtime.debug.delivery.platform': {
    'en-US': 'platform',
    'zh-CN': '平台',
  },
  'ai.runtime.debug.subagentsLabel': {
    'en-US': 'Subagents:',
    'zh-CN': '子代理：',
  },
  'ai.runtime.debug.subagentAriaPrefix': {
    'en-US': 'Subagent',
    'zh-CN': '子代理',
  },
  'ai.runtime.debug.cacheLabel': {
    'en-US': 'Cache:',
    'zh-CN': '缓存：',
  },
  'ai.runtime.debug.cacheAriaPrefix': {
    'en-US': 'Cache',
    'zh-CN': '缓存',
  },
  'ai.runtime.debug.cacheRetention.none': {
    'en-US': 'No cache',
    'zh-CN': '无缓存',
  },
  'ai.runtime.debug.cacheRetention.short': {
    'en-US': 'Short (SHORT)',
    'zh-CN': '短期 (SHORT)',
  },
  'ai.runtime.debug.cacheRetention.long': {
    'en-US': 'Long (LONG)',
    'zh-CN': '长期 (LONG)',
  },
  'ai.runtime.debug.envSupport.none': {
    'en-US': 'Platform only (NONE)',
    'zh-CN': '平台独立 (NONE)',
  },
  'ai.runtime.debug.envSupport.optional': {
    'en-US': 'Optional environment (OPTIONAL)',
    'zh-CN': '可选环境 (OPTIONAL)',
  },
  'ai.runtime.debug.envSupport.required': {
    'en-US': 'Environment required (REQUIRED)',
    'zh-CN': '需要环境 (REQUIRED)',
  },
  'ai.runtime.debug.inspectorTitle': {
    'en-US': 'INSPECTOR',
    'zh-CN': '检查器',
  },
  'ai.runtime.debug.toolLabel': {
    'en-US': 'Tool',
    'zh-CN': '工具',
  },
  'ai.runtime.debug.skillLabel': {
    'en-US': 'Skill',
    'zh-CN': '技能',
  },
  'ai.runtime.debug.requestLabel': {
    'en-US': 'Request Snapshot',
    'zh-CN': '请求快照',
  },
  'ai.runtime.debug.inspectorClose': {
    'en-US': 'Close inspector',
    'zh-CN': '关闭检查器',
  },
  'ai.runtime.debug.inspector.name': {
    'en-US': 'Name',
    'zh-CN': '名称',
  },
  'ai.runtime.debug.inspector.status': {
    'en-US': 'Status',
    'zh-CN': '状态',
  },
  'ai.runtime.debug.inspector.toolState.sent': {
    'en-US': 'SENT',
    'zh-CN': '已发送 (SENT)',
  },
  'ai.runtime.debug.inspector.toolState.filtered': {
    'en-US': 'FILTERED',
    'zh-CN': '已过滤 (FILTERED)',
  },
  'ai.runtime.debug.filterReason.envNotSelected': {
    'en-US': 'Environment not selected',
    'zh-CN': '未选择环境',
  },
  'ai.runtime.debug.inspector.environmentSupport': {
    'en-US': 'Environment Support',
    'zh-CN': '环境支持',
  },
  'ai.runtime.debug.inspector.requiredEnvId': {
    'en-US': 'Required Environment ID',
    'zh-CN': '所需环境 ID',
  },
  'ai.runtime.debug.inspector.provenance': {
    'en-US': 'Contributor (Provenance)',
    'zh-CN': '来源 (Contributor)',
  },
  'ai.runtime.debug.inspector.description': {
    'en-US': 'Description',
    'zh-CN': '描述',
  },
  'ai.runtime.debug.inspector.inputSchemaJson': {
    'en-US': 'Input Schema JSON:',
    'zh-CN': '输入 Schema JSON：',
  },
  'ai.runtime.debug.inspector.skillName': {
    'en-US': 'Skill Name',
    'zh-CN': '技能名称',
  },
  'ai.runtime.debug.inspector.package': {
    'en-US': 'Package',
    'zh-CN': '包名 (Package)',
  },
  'ai.runtime.debug.inspector.delivery': {
    'en-US': 'Delivery',
    'zh-CN': '交付方式 (Delivery)',
  },
  'ai.runtime.debug.inspector.path': {
    'en-US': 'Path',
    'zh-CN': '路径',
  },
  'ai.runtime.debug.inspector.currentCommit': {
    'en-US': 'Current Commit',
    'zh-CN': '当前提交',
  },
  'ai.runtime.debug.inspector.observedHeadCommit': {
    'en-US': 'Observed HEAD Commit',
    'zh-CN': '观察到的 HEAD 提交',
  },
  'ai.runtime.debug.inspector.daemonInstalledCommit': {
    'en-US': 'Daemon Installed Commit',
    'zh-CN': 'Daemon 安装提交',
  },
  'ai.runtime.debug.inspector.promptXml': {
    'en-US': 'Prompt XML:',
    'zh-CN': '提示词 XML：',
  },
  'ai.runtime.debug.inspector.cacheTitle': {
    'en-US': 'Cache Policy',
    'zh-CN': '缓存策略',
  },
  'ai.runtime.debug.inspector.cacheRetention': {
    'en-US': 'Retention',
    'zh-CN': '留存档位',
  },
  'ai.runtime.debug.inspector.cacheAffinityKey': {
    'en-US': 'Affinity Key',
    'zh-CN': '前缀标识 (Affinity Key)',
  },
  'ai.runtime.debug.inspector.cacheBreakpoints': {
    'en-US': 'Breakpoints',
    'zh-CN': 'Cache 断点',
  },
  'ai.runtime.debug.inspector.cacheProviderDisclaimer': {
    'en-US': 'Provider automatic caching is not guaranteed',
    'zh-CN': '不保证 Provider 自动缓存',
  },
  'ai.runtime.debug.noFrozenInvocation': {
    'en-US': 'No active frozen invocation request. This view displays canonical request JSON only during an active invocation turn.',
    'zh-CN': '当前无活动的冻结调用请求。仅在活动调用回合中显示规范化请求 JSON。',
  },
  'ai.runtime.debug.previewFailed': {
    'en-US': 'Failed to preview request',
    'zh-CN': '请求预览失败',
  },
  'ai.runtime.debug.settingsChanged': {
    'en-US': 'Session settings have changed. Please confirm and try again.',
    'zh-CN': '会话设置已变化，请确认后重试',
  },
  'ai.runtime.debug.previewError.PREVIEW_STALE_CURSOR': {
    'en-US': 'Thread cursor is stale. Please refresh and try again.',
    'zh-CN': '会话游标已过期，请刷新状态后重试',
  },
  'ai.runtime.debug.previewError.PREVIEW_QUEUED_COMMANDS': {
    'en-US': 'Queued commands are pending. Unable to preview.',
    'zh-CN': '队列中有未处理命令，无法预览',
  },
  'ai.runtime.debug.previewError.PREVIEW_THREAD_BUSY': {
    'en-US': 'Thread is currently running. Unable to preview.',
    'zh-CN': '会话正在运行中，无法预览',
  },
  'ai.runtime.debug.previewError.PREVIEW_COMPACTION_REQUIRED': {
    'en-US': 'Thread compaction required. Please compact and try again.',
    'zh-CN': '会话需要压缩，请压缩后重试',
  },
  'ai.runtime.debug.previewError.PREVIEW_ATTACHMENT_NOT_READY': {
    'en-US': 'Attachments are not ready. Please wait for uploads to finish.',
    'zh-CN': '附件尚未就绪，请等待上传完成后重试',
  },
  'ai.runtime.debug.previewError.PREVIEW_PLANNING_FAILED': {
    'en-US': 'Planning failed. Please check context and configuration.',
    'zh-CN': '规划请求失败，请检查上下文与配置',
  },
  'ai.runtime.debug.previewError.PREVIEW_PROVIDER_UNAVAILABLE': {
    'en-US': 'Model provider is currently unavailable. Please try again later.',
    'zh-CN': '模型服务不可用，请稍后重试',
  },
  'ai.runtime.debug.previewError.PREVIEW_UNSUPPORTED': {
    'en-US': 'Request preview is not supported for current thread or model.',
    'zh-CN': '当前会话或模型不支持请求预览',
  },
  'ai.runtime.debug.previewError.PREVIEW_ENCODING_FAILED': {
    'en-US': 'Request payload encoding failed. Please check inputs.',
    'zh-CN': '请求载荷编码失败，请检查输入内容',
  },
  'ai.runtime.debug.previewError.UNKNOWN_409': {
    'en-US': 'Unable to preview. Please refresh and try again.',
    'zh-CN': '无法预览，请刷新状态后重试',
  },
  'ai.runtime.debug.previewDisabled.emptyDraft': {
    'en-US': 'Draft is empty',
    'zh-CN': '草稿为空',
  },
  'ai.runtime.debug.previewDisabled.slashCommand': {
    'en-US': 'Slash command cannot be previewed',
    'zh-CN': '斜杠命令无法预览',
  },
  'ai.runtime.debug.previewDisabled.goalCommand': {
    'en-US': 'Goal command cannot be previewed',
    'zh-CN': '目标命令无法预览',
  },
  'ai.runtime.debug.previewDisabled.uploading': {
    'en-US': 'Attachments are uploading',
    'zh-CN': '附件上传中',
  },
  'ai.runtime.debug.previewDisabled.busy': {
    'en-US': 'Thread is busy',
    'zh-CN': '会话忙碌',
  },
  'ai.runtime.debug.previewDisabled.queued': {
    'en-US': 'Commands are queued',
    'zh-CN': '队列中有排队命令',
  },
  'ai.runtime.debug.previewDisabled.readOnly': {
    'en-US': 'Read-only mode',
    'zh-CN': '只读模式',
  },
  'ai.runtime.debug.previewDisabled.unsupported': {
    'en-US': 'Preview not available',
    'zh-CN': '当前不支持预览',
  },
  'ai.runtime.debug.inspector.draftPreviewTitle': {
    'en-US': 'Request Preview',
    'zh-CN': '请求预览',
  },
  'ai.runtime.debug.inspector.clickTimeSnapshot': {
    'en-US': 'Click-time Snapshot',
    'zh-CN': '点击快照',
  },
  'ai.runtime.debug.inspector.providerType': {
    'en-US': 'Provider Type',
    'zh-CN': 'Provider 类型',
  },
  'ai.runtime.debug.inspector.modelName': {
    'en-US': 'Model Name',
    'zh-CN': '模型名称',
  },
  'ai.runtime.debug.inspector.bodyByteSize': {
    'en-US': 'Payload Size',
    'zh-CN': '载荷大小',
  },
  'ai.runtime.debug.inspector.generatedAt': {
    'en-US': 'Generated At',
    'zh-CN': '生成时间',
  },
  'ai.runtime.debug.inspector.sourceHeadEntryId': {
    'en-US': 'Base Entry ID',
    'zh-CN': '基础 Entry ID',
  },
  'ai.runtime.debug.inspector.snapshotNotice': {
    'en-US': 'Notice',
    'zh-CN': '快照说明',
  },
  'ai.runtime.debug.inspector.requestBodyJson': {
    'en-US': 'Provider Request Body (JSON)',
    'zh-CN': 'Provider 请求体 (JSON)',
  },
  'ai.runtime.event.detail.entryId': {
    'en-US': 'Entry ID',
    'zh-CN': 'Entry ID',
  },
  'ai.runtime.event.detail.entryType': {
    'en-US': 'Entry type',
    'zh-CN': 'Entry 类型',
  },
  'ai.runtime.event.detail.createTime': {
    'en-US': 'Time',
    'zh-CN': '时间',
  },
  'ai.runtime.event.detail.turn': {
    'en-US': 'Turn',
    'zh-CN': '回合',
  },
  'ai.runtime.event.detail.role': {
    'en-US': 'Role',
    'zh-CN': '角色',
  },
  'ai.runtime.event.detail.status': {
    'en-US': 'Status',
    'zh-CN': '状态',
  },
  'ai.runtime.event.detail.rawStatus': {
    'en-US': 'Raw status',
    'zh-CN': '原始状态',
  },
  'ai.runtime.event.detail.invocationId': {
    'en-US': 'Invocation ID',
    'zh-CN': '调用 ID',
  },
  'ai.runtime.event.detail.modelInvocationId': {
    'en-US': 'Model invocation ID',
    'zh-CN': '模型调用 ID',
  },
  'ai.runtime.event.detail.assistantEntryId': {
    'en-US': 'Assistant entry ID',
    'zh-CN': '助手 Entry ID',
  },
  'ai.runtime.event.detail.turnStartEntryId': {
    'en-US': 'Turn start entry ID',
    'zh-CN': '回合起始 Entry ID',
  },
  'ai.runtime.event.detail.requestHeadEntryId': {
    'en-US': 'Request head entry ID',
    'zh-CN': '请求头 Entry ID',
  },
  'ai.runtime.event.detail.attempt': {
    'en-US': 'Attempt',
    'zh-CN': '尝试次数',
  },
  'ai.runtime.event.detail.sequence': {
    'en-US': 'Sequence',
    'zh-CN': '序号',
  },
  'ai.runtime.event.detail.toolName': {
    'en-US': 'Tool',
    'zh-CN': '工具',
  },
  'ai.runtime.event.detail.toolCallId': {
    'en-US': 'Tool call ID',
    'zh-CN': '工具调用 ID',
  },
  'ai.runtime.event.detail.rendererKey': {
    'en-US': 'Renderer',
    'zh-CN': '渲染器',
  },
  'ai.runtime.event.detail.callIndex': {
    'en-US': 'Call index',
    'zh-CN': '调用索引',
  },
  'ai.runtime.event.detail.environment': {
    'en-US': 'Environment',
    'zh-CN': '运行环境',
  },
  'ai.runtime.event.detail.arguments': {
    'en-US': 'Arguments',
    'zh-CN': '参数',
  },
  'ai.runtime.event.detail.approval': {
    'en-US': 'Approval',
    'zh-CN': '审批',
  },
  'ai.runtime.event.detail.result': {
    'en-US': 'Result',
    'zh-CN': '结果',
  },
  'ai.runtime.event.detail.error': {
    'en-US': 'Error',
    'zh-CN': '错误',
  },
  'ai.runtime.event.detail.realtime': {
    'en-US': 'Realtime stream',
    'zh-CN': '实时流',
  },
  'ai.runtime.event.detail.checkpoint': {
    'en-US': 'Checkpoint',
    'zh-CN': '检查点',
  },
  'ai.runtime.event.detail.partialText': {
    'en-US': 'Partial output',
    'zh-CN': '部分输出',
  },
  'ai.runtime.event.detail.partialThinking': {
    'en-US': 'Partial thinking',
    'zh-CN': '部分思考',
  },
  'ai.runtime.event.detail.errorCode': {
    'en-US': 'Error code',
    'zh-CN': '错误码',
  },
  'ai.runtime.event.detail.errorMessage': {
    'en-US': 'Error message',
    'zh-CN': '错误信息',
  },
  'ai.runtime.event.detail.failedAt': {
    'en-US': 'Failed at',
    'zh-CN': '失败时间',
  },
  'ai.runtime.event.detail.retryAt': {
    'en-US': 'Retry at',
    'zh-CN': '重试时间',
  },
  'ai.runtime.event.detail.chars': {
    'en-US': 'Streamed characters',
    'zh-CN': '已流式字符数',
  },
  'ai.runtime.event.detail.outcome': {
    'en-US': 'Outcome',
    'zh-CN': '结果',
  },
  'ai.runtime.event.detail.input': {
    'en-US': 'Input tokens',
    'zh-CN': '输入 tokens',
  },
  'ai.runtime.event.detail.output': {
    'en-US': 'Output tokens',
    'zh-CN': '输出 tokens',
  },
  'ai.runtime.event.detail.cacheRead': {
    'en-US': 'Cache read tokens',
    'zh-CN': '缓存读 tokens',
  },
  'ai.runtime.event.detail.cacheWrite': {
    'en-US': 'Cache write tokens',
    'zh-CN': '缓存写 tokens',
  },
  'ai.runtime.event.detail.reasoning': {
    'en-US': 'Reasoning tokens',
    'zh-CN': '推理 tokens',
  },
  'ai.runtime.event.detail.providerTotal': {
    'en-US': 'Provider total tokens',
    'zh-CN': 'Provider 总 tokens',
  },
  'ai.runtime.event.detail.cost': {
    'en-US': 'Cost',
    'zh-CN': '费用',
  },
  'ai.runtime.event.status.PENDING': {
    'en-US': 'Pending',
    'zh-CN': '等待中',
  },
  'ai.runtime.event.status.RUNNING': {
    'en-US': 'Running',
    'zh-CN': '运行中',
  },
  'ai.runtime.event.status.COMPLETED': {
    'en-US': 'Completed',
    'zh-CN': '已完成',
  },
  'ai.runtime.event.status.FAILED': {
    'en-US': 'Failed',
    'zh-CN': '失败',
  },
  'ai.runtime.event.status.STOPPED': {
    'en-US': 'Stopped',
    'zh-CN': '已停止',
  },
  'ai.runtime.entry.assistantRequestFailed': {
    'en-US': 'Assistant request failed',
    'zh-CN': '助手请求失败',
  },
  'ai.runtime.entry.toolFailed': {
    'en-US': 'Tool execution failed.',
    'zh-CN': '工具执行失败。',
  },
  'ai.runtime.action.requestFailed': {
    'en-US': 'Request failed',
    'zh-CN': '请求失败',
  },
  'ai.runtime.action.threadStateChanged': {
    'en-US': '{{action}}: Thread state changed ({{error}}); refreshed. Please try again',
    'zh-CN': '{{action}}：Thread 状态已变化（{{error}}），已刷新，请重试',
  },
  'ai.runtime.action.unknownCommand': {
    'en-US': 'Unknown command: {{command}}',
    'zh-CN': '未知命令：{{command}}',
  },
  'ai.runtime.action.blankAgent': {
    'en-US': '(no Agent)',
    'zh-CN': '（无 Agent）',
  },
  'ai.runtime.action.agentMissing': {
    'en-US': '(Agent deleted or missing)',
    'zh-CN': '（Agent 已删除/缺失）',
  },
  'ai.runtime.action.agentSwitchDisabled': {
    'en-US': 'Agent switching is not available in this mode',
    'zh-CN': '当前模式不支持切换 Agent',
  },
  'ai.runtime.action.branchingDisabled': {
    'en-US': 'Branch switching and forking are not available in this mode',
    'zh-CN': '当前模式不支持分支切换或分叉',
  },
  'ai.runtime.action.firstSendFailed': {
    'en-US': 'First send failed',
    'zh-CN': '首发失败',
  },
  'ai.runtime.action.updateAgentFailed': {
    'en-US': 'Failed to update Agent',
    'zh-CN': '更新 Agent 失败',
  },
  'ai.runtime.action.updateYoloFailed': {
    'en-US': 'Failed to update YOLO',
    'zh-CN': '更新 YOLO 失败',
  },
  'ai.runtime.action.firstSendMissingSession': {
    'en-US': 'Chat Thread creation did not return a Session',
    'zh-CN': '创建 Chat Thread 未返回 Session',
  },
  'ai.runtime.action.unavailableScene': {
    'en-US': 'This scene cannot use /{{command}}',
    'zh-CN': '当前场景不可用：/{{command}}',
  },
  'ai.runtime.action.rebindConflict': {
    'en-US': 'Unable to relocate Thread: state changed ({{error}}); refresh and try again',
    'zh-CN': '无法重定位 Thread：状态已变化（{{error}}），请刷新后重试',
  },
  'ai.runtime.action.rebindFailed': {
    'en-US': 'Failed to relocate Thread',
    'zh-CN': '重定位 Thread 失败',
  },
  'ai.runtime.action.rootNotBranchable': {
    'en-US': 'The root Entry cannot be used as an editable message branch',
    'zh-CN': '根节点不能作为可编辑消息分支',
  },
  'ai.runtime.action.threadNotLoaded': {
    'en-US': 'Thread has not loaded yet',
    'zh-CN': 'Thread 尚未加载',
  },
  'ai.runtime.action.threadRunning': {
    'en-US': 'This Thread is running and cannot be relocated; use /stop first',
    'zh-CN': '当前 Thread 正在运行，无法重定位；请先 /stop',
  },
  'ai.runtime.action.sendFailed': {
    'en-US': 'Send message failed',
    'zh-CN': '发送消息失败',
  },
  'ai.runtime.action.draftSaveFailed': {
    'en-US': 'Draft could not be saved; check browser storage and retry',
    'zh-CN': '草稿未能保存，请检查浏览器存储后重试',
  },
  'ai.runtime.action.draftLoadFailed': {
    'en-US': 'Draft could not be loaded; the editor may not show the latest draft',
    'zh-CN': '草稿加载失败，当前编辑区可能不是最新草稿',
  },
  'ai.runtime.action.draftRestoreFailed': {
    'en-US': 'Unsent input could not be restored to the editor; retry',
    'zh-CN': '未消费的输入未能恢复到编辑区，请重试',
  },
  'ai.runtime.action.draftWriteConflict': {
    'en-US': 'Draft out of sync: unsent input was restored elsewhere, your current edit is kept',
    'zh-CN': '草稿未同步：另一处已恢复未发送内容，当前输入已保留',
  },
  'ai.runtime.action.retryDraftRestore': {
    'en-US': 'Retry restoring draft',
    'zh-CN': '重试恢复草稿',
  },
  'ai.runtime.action.stopFailed': {
    'en-US': 'Stop failed',
    'zh-CN': '停止失败',
  },
  'ai.runtime.action.compactFailed': {
    'en-US': 'Compaction failed',
    'zh-CN': '压缩失败',
  },
  'ai.runtime.action.compactUnavailable': {
    'en-US': 'Manual compaction is currently unavailable',
    'zh-CN': '当前无法手动压缩',
  },
  'ai.runtime.action.acceptancePending': {
    'en-US': 'A previous acceptance is still awaiting a definite result',
    'zh-CN': '上一条接受请求仍在等待确定结果',
  },
  'ai.runtime.action.abandonedPendingNotice': {
    'en-US': 'The pending message was discarded locally. Commands already accepted by the server are not cancelled, and the message will not be resent automatically.',
    'zh-CN': '已放弃未决消息。这不会取消服务端可能已接受的命令，也不会自动重发。',
  },
  'ai.runtime.action.storageFailed': {
    'en-US': 'Could not save the send record; the message was not sent. Please retry.',
    'zh-CN': '无法保存发送记录，未发送消息；请重试。',
  },
  'ai.runtime.action.storageClearFailed': {
    'en-US': 'Could not clear the pending message record. Please retry.',
    'zh-CN': '清除未确认的消息记录失败，请重试。',
  },
  'ai.runtime.action.operationPending': {
    'en-US': 'This Thread has a pending operation; wait for it to finish or retry the original operation',
    'zh-CN': '当前 Thread 仍有未完成的操作，请等待完成或重试原操作',
  },
  'ai.common.loadingMcpServer': {
    'en-US': 'Loading MCP Servers',
    'zh-CN': '正在加载 MCP 服务',
  },
  'ai.environment.loading': {
    'en-US': 'Loading Environments',
    'zh-CN': '正在加载 Environment',
  },
  'ai.environment.loadFailed': {
    'en-US': 'Failed to load environments',
    'zh-CN': '加载 Environment 失败',
  },
  'ai.environment.empty': {
    'en-US': 'There are no Environments',
    'zh-CN': '当前没有 Environment',
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
    'en-US': 'Requires JDK 21 and Bash. Overwriting restarts the service and connects to this environment, interrupting tool calls in progress. Existing data is kept.',
    'zh-CN': '需要 JDK 21 和 Bash。覆盖安装会重启服务并连接到此环境，中断当前工具调用；已有数据保留。',
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
  'ai.environment.install.invalidJavaHome': {
    'en-US': "Java home is invalid: use an absolute path without $, % or ~.",
    'zh-CN': "Java home 无效：需为绝对路径，且不含 $、% 或 ~。",
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
    'en-US': "LSP servers are invalid: check server IDs, command, extensions (required, dot-prefixed) and markers.",
    'zh-CN': "LSP servers 配置无效：请检查 server ID、command、extensions（必填，以 . 开头）和 markers。",
  },
  'ai.environment.close': {
    'en-US': 'Close',
    'zh-CN': '关闭',
  },
  'ai.environment.unavailable': {
    'en-US': 'Unavailable',
    'zh-CN': '不可用',
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
  'ai.skillPackages.title': {
    'en-US': 'Skill Packages',
    'zh-CN': '技能包',
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
  'ai.skillPackages.version': {
    'en-US': 'Version',
    'zh-CN': '版本',
  },
  'ai.skillPackages.newVersion': {
    'en-US': 'New Version',
    'zh-CN': '新版本',
  },
  'ai.skillPackages.descriptionLabel': {
    'en-US': 'Description',
    'zh-CN': '描述',
  },
  'ai.skillPackages.skillsCount': {
    'en-US': 'Skills Count',
    'zh-CN': '包含技能数量',
  },
  'ai.skillPackages.updatedAt': {
    'en-US': 'Updated At',
    'zh-CN': '更新时间',
  },
  'ai.skillPackages.empty': {
    'en-US': 'No Skill Packages found',
    'zh-CN': '暂无技能包',
  },
  'ai.skillPackages.loading': {
    'en-US': 'Loading Skill Packages...',
    'zh-CN': '正在加载技能包...',
  },
  'ai.skillPackages.skillsSection': {
    'en-US': 'Skill Definitions',
    'zh-CN': '技能定义列表',
  },
  'ai.skillPackages.addSkill': {
    'en-US': 'Add Skill Definition',
    'zh-CN': '添加技能定义',
  },
  'ai.skillPackages.removeSkill': {
    'en-US': 'Remove Skill',
    'zh-CN': '移除技能',
  },
  'ai.skillPackages.skillName': {
    'en-US': 'Skill Name',
    'zh-CN': '技能名称',
  },
  'ai.skillPackages.skillDescription': {
    'en-US': 'Skill Description',
    'zh-CN': '技能描述',
  },
  'ai.skillPackages.skillContent': {
    'en-US': 'Skill Content',
    'zh-CN': '技能内容 (Markdown / 指令)',
  },
  'ai.skillPackages.nameRequired': {
    'en-US': 'Package name is required',
    'zh-CN': '技能包名称不能为空',
  },
  'ai.skillPackages.nameInvalidChars': {
    'en-US': 'Package name cannot contain : / @ \\',
    'zh-CN': '技能包名称不能包含 : / @ \\',
  },
  'ai.skillPackages.versionRequired': {
    'en-US': 'Package version is required',
    'zh-CN': '技能包版本不能为空',
  },
  'ai.skillPackages.atLeastOneSkill': {
    'en-US': 'At least one skill definition is required',
    'zh-CN': '至少需要一条技能定义',
  },
  'ai.skillPackages.skillNameRequired': {
    'en-US': 'Skill name cannot be empty',
    'zh-CN': '技能名称不能为空',
  },
  'ai.skillPackages.skillDescriptionRequired': {
    'en-US': 'Skill description cannot be empty',
    'zh-CN': '技能描述不能为空',
  },
  'ai.skillPackages.skillContentRequired': {
    'en-US': 'Skill content cannot be empty',
    'zh-CN': '技能内容不能为空',
  },
  'ai.skillPackages.duplicateSkillName': {
    'en-US': 'Duplicate skill name: {{name}}',
    'zh-CN': '存在重复的技能名称：{{name}}',
  },
  'ai.skillPackages.repositoryUrl': {
    'en-US': 'Repository URL',
    'zh-CN': 'Git 仓库地址',
  },
  'ai.skillPackages.branch': {
    'en-US': 'Branch',
    'zh-CN': '分支',
  },
  'ai.skillPackages.currentCommit': {
    'en-US': 'Current Commit',
    'zh-CN': '当前发布 Commit',
  },
  'ai.skillPackages.observedHeadCommit': {
    'en-US': 'Observed Branch HEAD',
    'zh-CN': '最近观察到的分支 HEAD',
  },
  'ai.skillPackages.checkStatus': {
    'en-US': 'Status',
    'zh-CN': '状态',
  },
  'ai.skillPackages.check': {
    'en-US': 'Check',
    'zh-CN': '检查更新',
  },
  'ai.skillPackages.update': {
    'en-US': 'Update',
    'zh-CN': '发布更新',
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
  'ai.mcp.title': {
    'en-US': 'MCP Servers',
    'zh-CN': 'MCP 服务',
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
  'ai.mcp.empty': {
    'en-US': 'No MCP servers configured',
    'zh-CN': '暂无配置的 MCP 服务',
  },
  'ai.mcp.name': {
    'en-US': 'Server Name',
    'zh-CN': '服务名称',
  },
  'ai.mcp.url': {
    'en-US': 'Endpoint URL',
    'zh-CN': '服务地址 (URL)',
  },
  'ai.mcp.headers': {
    'en-US': 'Headers (JSON)',
    'zh-CN': '自定义请求头 (JSON)',
  },
  'ai.mcp.headersHint': {
    'en-US': 'JSON object of request headers. Values may contain ${VAR} placeholders.',
    'zh-CN': '请求头 JSON 对象，属性值支持 ${VAR} 环境变量占位符。',
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
  'ai.mcp.statusFailed': {
    'en-US': 'Failed',
    'zh-CN': '失败',
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
  'ai.catalog.form.environment': {
    'en-US': 'Bound Environment',
    'zh-CN': '绑定环境',
  },
  'ai.nav.interactions': {
    'en-US': 'Pending',
    'zh-CN': '待处理',
  },
  'ai.nav.interactionsAria': {
    'en-US': 'Pending Interactions',
    'zh-CN': '待处理事项',
  },
  'ai.interaction.pendingTitle': {
    'en-US': 'Pending Interactions',
    'zh-CN': '待处理事项',
  },
  'ai.interaction.empty': {
    'en-US': 'No pending interactions',
    'zh-CN': '暂无待处理项',
  },
  'ai.interaction.refresh': {
    'en-US': 'Refresh',
    'zh-CN': '刷新',
  },
  'ai.interaction.loadMore': {
    'en-US': 'Load More',
    'zh-CN': '加载更多',
  },
  'ai.interaction.sourceChat': {
    'en-US': 'Chat',
    'zh-CN': '对话',
  },
  'ai.interaction.openChatSource': {
    'en-US': 'Open chat',
    'zh-CN': '打开对话',
  },
  'ai.interaction.sourceIssue': {
    'en-US': 'Task chat',
    'zh-CN': '任务对话',
  },
  'ai.interaction.openIssueSource': {
    'en-US': 'Open task chat',
    'zh-CN': '打开任务对话',
  },
  'ai.interaction.submit': {
    'en-US': 'Submit Answers',
    'zh-CN': '提交回答',
  },
  'ai.interaction.submitting': {
    'en-US': 'Submitting...',
    'zh-CN': '正在提交...',
  },
  'ai.interaction.decline': {
    'en-US': 'Decline',
    'zh-CN': '拒绝回答',
  },
  'ai.interaction.declined': {
    'en-US': 'Declined by user',
    'zh-CN': '已拒绝回答',
  },
  'ai.interaction.retry': {
    'en-US': 'Retry',
    'zh-CN': '重试',
  },
  'ai.interaction.customInputPlaceholder': {
    'en-US': 'Enter custom answer',
    'zh-CN': '输入自定义回答',
  },
  'ai.interaction.recommended': {
    'en-US': 'Recommended',
    'zh-CN': '推荐',
  },
  'ai.interaction.singleChoice': {
    'en-US': 'Single choice',
    'zh-CN': '单选',
  },
  'ai.interaction.multipleChoice': {
    'en-US': 'Multiple choice',
    'zh-CN': '多选',
  },
  'ai.interaction.nonRecoverable': {
    'en-US': 'This item has changed. Refresh to see the latest status.',
    'zh-CN': '此事项已变更，请刷新查看最新状态。',
  },
  'ai.interaction.allow': {
    'en-US': 'Allow',
    'zh-CN': '允许',
  },
  'ai.interaction.deny': {
    'en-US': 'Deny',
    'zh-CN': '拒绝',
  },
  'ai.interaction.approvalTitle': {
    'en-US': 'Permission Approval',
    'zh-CN': '权限审批',
  },
  'ai.interaction.approvalReason': {
    'en-US': 'Reason',
    'zh-CN': '审批原因',
  },
  'ai.interaction.waitingInput': {
    'en-US': 'Waiting for Input',
    'zh-CN': '等待输入',
  },
  'ai.interaction.waitingApproval': {
    'en-US': 'Waiting for Approval',
    'zh-CN': '等待审批',
  },
} satisfies LocaleCatalog
