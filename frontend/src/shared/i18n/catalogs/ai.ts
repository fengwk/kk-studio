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
    'en-US': 'The agent {agent} has no resolvable model; pick another agent.',
    'zh-CN': 'Agent {agent} 没有可解析的模型，请选择其他 Agent。',
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
    'zh-CN': '配置聚合：Model / tools / skills / policy',
  },
  'ai.catalog.createModel': {
    'en-US': 'Create Model',
    'zh-CN': '新建 Model',
  },
  'ai.catalog.createModelDescription': {
    'en-US': 'Define model information and variants',
    'zh-CN': '定义模型信息与 variants',
  },
  'ai.catalog.createProvider': {
    'en-US': 'Create Provider',
    'zh-CN': '新建 Provider',
  },
  'ai.catalog.createProviderDescription': {
    'en-US': 'Configure provider and credentials',
    'zh-CN': '配置供应商与凭据',
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
    'en-US': 'Immutable logical identity used by Agents and branch settings.',
    'zh-CN': '不可变逻辑身份，供 Agent 与 branch settings 引用。',
  },
  'ai.catalog.form.modelId': {
    'en-US': 'Model ID',
    'zh-CN': 'Model ID',
  },
  'ai.catalog.form.modelIdHint': {
    'en-US': 'Real upstream wire model id sent to the Provider; may differ from Name.',
    'zh-CN': '发往 Provider 的真实 wire 模型标识；可与 Name 不同。',
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
  'ai.catalog.form.reasoningEffortAria': {
    'en-US': 'Reasoning Effort',
    'zh-CN': 'Reasoning Effort',
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
    'en-US': 'A Variant carries only reasoning effort; IDs must be unique, and Default Variant must point to one of them.',
    'zh-CN': 'Variant 只承载思考强度；id 唯一，Default Variant 必须指向其中一项。',
  },
  'ai.catalog.form.reasoningHint': {
    'en-US':
      ' Leave empty to keep the provider protocol default; off = explicitly disable reasoning; high/medium/low map to the provider levels.',
    'zh-CN':
      ' 留空表示不覆盖 Provider 协议默认；off=显式关闭推理；high/medium/low 映射到厂商级别。',
  },
  'ai.catalog.form.reasoningDisabledHint': {
    'en-US': ' To configure reasoning effort, enable Reasoning above first.',
    'zh-CN': ' 若需配置思考强度，请先勾选上方 Reasoning。',
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
      'Reasoning effort must be high / medium / low / off; leave it empty to keep the provider default.',
    'zh-CN': '思考强度只能为 high / medium / low / off；留空表示不覆盖 Provider 协议默认。',
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
    'en-US': 'Create a persistent Chat (an Agent is required)',
    'zh-CN': '创建持久 Chat（必须选择 Agent）',
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
  'ai.runtime.composer.uploadFailed': {
    'en-US': 'Upload failed',
    'zh-CN': '上传失败',
  },
  'ai.runtime.composer.retryUpload': {
    'en-US': 'Retry upload {{name}}',
    'zh-CN': '重试上传 {{name}}',
  },
  'ai.runtime.composer.removeAttachment': {
    'en-US': 'Remove attachment {{name}}',
    'zh-CN': '移除附件 {{name}}',
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
  'ai.runtime.command.upload': {
    'en-US': 'Upload files and insert ordered attachment references',
    'zh-CN': '上传文件并插入有序附件引用',
  },
  'ai.runtime.command.thread': {
    'en-US': 'Switch the Thread bound to this Pane (does not modify any Thread)',
    'zh-CN': '切换当前 Pane 绑定的 Thread（不修改任何 Thread）',
  },
  'ai.runtime.command.agent': {
    'en-US': 'Change this Chat’s visible Agent; the next message uses it',
    'zh-CN': '修改当前 Chat 可见的 Agent；下一条消息直接使用',
  },
  'ai.runtime.command.yolo': {
    'en-US': 'Toggle automatic approval of Tool calls',
    'zh-CN': '切换 YOLO 自动批准工具调用',
  },
  'ai.runtime.command.tree': {
    'en-US': 'Open history and relocate the current Thread to the selected Entry',
    'zh-CN': '打开历史面板，把当前 Thread 重定位到所选 Entry',
  },
  'ai.runtime.command.stop': {
    'en-US': 'Stop the current Thread and restore unprocessed messages',
    'zh-CN': '停止当前 Thread 并恢复尚未处理的消息',
  },
  'ai.runtime.command.new': {
    'en-US': 'Return to a blank pane; sending creates a new Session / Thread',
    'zh-CN': '回到空面板；发送后创建新的 Session / Thread',
  },
  'ai.runtime.command.debug': {
    'en-US': 'Toggle the durable Entry debug view and live model / tool invocations',
    'zh-CN': '切换持久 Entry 调试视图与活跃 model / tool 调用',
  },
  'ai.runtime.command.compact': {
    'en-US': 'Request manual context compaction for this Thread',
    'zh-CN': '请求压缩当前 Thread 的上下文',
  },
  'ai.runtime.command.shortcuts': {
    'en-US': 'Show the keyboard shortcut catalog',
    'zh-CN': '查看键盘快捷键目录',
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
    'en-US': 'Choose the model and variant for the next message',
    'zh-CN': '选择下一条消息使用的模型与 Variant',
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
    'zh-CN': '会话组件区',
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
  'ai.runtime.message.toolCall': {
    'en-US': 'Tool call ·',
    'zh-CN': '工具调用 ·',
  },
  'ai.runtime.message.toolResult': {
    'en-US': 'Tool result ·',
    'zh-CN': '工具结果 ·',
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
  'ai.runtime.message.waitingTool': {
    'en-US': 'Waiting for tool result…',
    'zh-CN': '等待工具结果…',
  },
  'ai.runtime.message.noTextOutput': {
    'en-US': 'No text output',
    'zh-CN': '无文本输出',
  },
  'ai.runtime.message.rawEntry': {
    'en-US': 'View raw Entry',
    'zh-CN': '查看原始 Entry',
  },
  'ai.runtime.message.attachment': {
    'en-US': '{{type}} attachment',
    'zh-CN': '{{type}} attachment',
  },
  'ai.runtime.message.binaryPayload': {
    'en-US': 'binary payload',
    'zh-CN': 'binary payload',
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
    'en-US': 'env:{{name}} (unavailable)',
    'zh-CN': 'env:{{name}} (unavailable)',
  },
  'ai.runtime.status.environmentText': {
    'en-US': 'env:{{name}}',
    'zh-CN': 'env:{{name}}',
  },
  'ai.runtime.status.environmentNoneText': {
    'en-US': 'none env',
    'zh-CN': 'none env',
  },
  'ai.runtime.status.branchUsageTitle': {
    'en-US': 'Branch usage: {{usage}}',
    'zh-CN': '分支用量：{{usage}}',
  },
  'ai.runtime.status.contextText': {
    'en-US': 'ctx {{used}}/{{total}}',
    'zh-CN': 'ctx {{used}}/{{total}}',
  },
  'ai.runtime.status.contextTitle': {
    'en-US': 'Estimated context usage: {{used}} / {{total}} tokens',
    'zh-CN': '估算上下文用量：{{used}} / {{total}} tokens',
  },
  'ai.runtime.status.cacheHitText': {
    'en-US': 'cache {{percent}}%',
    'zh-CN': 'cache {{percent}}%',
  },
  'ai.runtime.status.cacheHitTitle': {
    'en-US': 'Estimated cache hit rate: {{percent}}%',
    'zh-CN': '估算缓存命中率：{{percent}}%',
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
  'ai.runtime.task.widget': {
    'en-US': 'Subagent tasks',
    'zh-CN': '子任务',
  },
  'ai.runtime.task.running': {
    'en-US': '{{count}} running',
    'zh-CN': '{{count}} 个运行中',
  },
  'ai.runtime.task.state.queued': {
    'en-US': 'queued',
    'zh-CN': '排队中',
  },
  'ai.runtime.task.state.runningModel': {
    'en-US': 'running model',
    'zh-CN': '模型运行中',
  },
  'ai.runtime.task.state.runningTool': {
    'en-US': 'running tool',
    'zh-CN': '工具运行中',
  },
  'ai.runtime.task.state.waitingApproval': {
    'en-US': 'waiting approval',
    'zh-CN': '等待审批',
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
  'ai.runtime.task.turns': {
    'en-US': '{{count}} turns',
    'zh-CN': '{{count}} 轮',
  },
  'ai.runtime.task.toolCalls': {
    'en-US': '{{count}} tool calls',
    'zh-CN': '{{count}} 次工具调用',
  },
  'ai.runtime.task.subagent': {
    'en-US': 'Subagent',
    'zh-CN': '子代理',
  },
  'ai.runtime.task.prompt': {
    'en-US': 'Prompt',
    'zh-CN': '任务提示',
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
  'ai.runtime.task.approvalRequested': {
    'en-US': 'Awaiting approval for {{toolName}}',
    'zh-CN': '等待审批：{{toolName}}',
  },
  'ai.runtime.task.allowTool': {
    'en-US': 'Allow {{toolName}}',
    'zh-CN': '允许 {{toolName}}',
  },
  'ai.runtime.task.denyTool': {
    'en-US': 'Deny {{toolName}}',
    'zh-CN': '拒绝 {{toolName}}',
  },
  'ai.runtime.entry.rootTitle': {
    'en-US': 'Conversation started',
    'zh-CN': '会话开始',
  },
  'ai.runtime.entry.rootText': {
    'en-US': 'The conversation tree root was created.',
    'zh-CN': '已创建会话树根节点。',
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
    'en-US': 'Unrecognized message Entry',
    'zh-CN': '无法识别消息 Entry',
  },
  'ai.runtime.entry.unsupportedRoleText': {
    'en-US': 'Message role not supported: {{role}}. Expand the raw payload to inspect it.',
    'zh-CN': '暂不支持的消息角色：{{role}}。原始 payload 可展开查看。',
  },
  'ai.runtime.entry.unsupportedText': {
    'en-US': 'Message role or payload is invalid. Expand the raw payload to inspect it.',
    'zh-CN': '消息角色或 payload 无效。原始 payload 可展开查看。',
  },
  'ai.runtime.entry.unknownTitle': {
    'en-US': 'Unrecognized Entry: {{type}}',
    'zh-CN': '未识别 Entry：{{type}}',
  },
  'ai.runtime.entry.unknownText': {
    'en-US': 'This Entry type has no dedicated renderer yet. Expand the raw payload to inspect it.',
    'zh-CN': '该 Entry 类型尚无专用渲染器，原始 payload 可展开查看。',
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
  'ai.runtime.event.detail.toolId': {
    'en-US': 'Tool ID',
    'zh-CN': '工具 ID',
  },
  'ai.runtime.event.detail.toolVersion': {
    'en-US': 'Tool version',
    'zh-CN': '工具版本',
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
  'ai.runtime.action.firstSendFailed': {
    'en-US': 'First send failed',
    'zh-CN': '首发失败',
  },
  'ai.runtime.action.updateAgentFailed': {
    'en-US': 'Failed to update Agent',
    'zh-CN': '更新 Agent 失败',
  },
  'ai.runtime.action.updateEnvironmentFailed': {
    'en-US': 'Failed to update Environment',
    'zh-CN': '更新 Environment 失败',
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
  'ai.runtime.action.operationPending': {
    'en-US': 'The current Thread still has an operation awaiting completion or exact replay',
    'zh-CN': '当前 Thread 仍有操作等待完成或精确重试',
  },
  'ai.common.loadingMcpServer': {
    'en-US': 'Loading MCP Servers',
    'zh-CN': '正在加载 MCP 服务',
  },
  'ai.environment.loading': {
    'en-US': 'Loading Environments',
    'zh-CN': '正在加载 Environments',
  },
  'ai.environment.loadFailed': {
    'en-US': 'Failed to load environments',
    'zh-CN': '加载失败',
  },
  'ai.environment.empty': {
    'en-US': 'There are no Environments',
    'zh-CN': '当前没有 Environment',
  },
  'ai.environment.create': {
    'en-US': 'Create Environment',
    'zh-CN': '创建环境',
  },
  'ai.environment.edit': {
    'en-US': 'Edit Environment',
    'zh-CN': '编辑环境',
  },
  'ai.environment.rename': {
    'en-US': 'Rename Environment',
    'zh-CN': '重命名环境',
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
  'ai.environment.tokenTitle': {
    'en-US': 'Registration Token',
    'zh-CN': 'Registration Token',
  },
  'ai.environment.tokenNotice': {
    'en-US': 'This is the newly rotated token. Existing connections keep running; the next connection must use it. You can copy the current token later from the Environment card.',
    'zh-CN': '这是刚刚重新生成的 Token。已有连接继续运行，下一次连接必须使用它；之后可随时从环境卡片复制当前 Token。',
  },
  'ai.environment.copyToken': {
    'en-US': 'Copy Token',
    'zh-CN': '复制 Token',
  },
  'ai.environment.copyTokenFailed': {
    'en-US': 'Failed to copy the token. Check clipboard permissions and try again.',
    'zh-CN': '复制 Token 失败，请检查剪贴板权限后重试。',
  },
  'ai.environment.tokenCopied': {
    'en-US': 'Copied!',
    'zh-CN': '已复制！',
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
    'zh-CN': '最近查看',
  },
  'ai.environment.capabilities': {
    'en-US': 'Capabilities',
    'zh-CN': 'Capabilities',
  },
  'ai.environment.skills': {
    'en-US': 'Skills',
    'zh-CN': 'Skills',
  },
  'ai.environment.rootPath': {
    'en-US': 'Root Path',
    'zh-CN': 'Root 路径',
  },
  'ai.mcp.title': {
    'en-US': 'MCP Servers',
    'zh-CN': 'MCP 服务',
  },
  'ai.mcp.create': {
    'en-US': 'Create MCP Server',
    'zh-CN': '创建 MCP 服务',
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
    'en-US': 'Are you sure you want to delete MCP server "{name}"?',
    'zh-CN': '确认删除 MCP 服务「{name}」？',
  },
  'ai.mcp.refresh': {
    'en-US': 'Refresh Tools',
    'zh-CN': '刷新工具',
  },
  'ai.mcp.refreshSuccess': {
    'en-US': 'Tools refreshed successfully',
    'zh-CN': '工具刷新成功',
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
    'zh-CN': 'Endpoint URL',
  },
  'ai.mcp.bearerToken': {
    'en-US': 'Bearer Token',
    'zh-CN': 'Bearer Token',
  },
  'ai.mcp.tokenMode': {
    'en-US': 'Token Setting',
    'zh-CN': 'Token 设置',
  },
  'ai.mcp.tokenKeep': {
    'en-US': 'Keep current token',
    'zh-CN': '保留当前 Token',
  },
  'ai.mcp.tokenClear': {
    'en-US': 'Clear token (anonymous)',
    'zh-CN': '清除 Token (匿名访问)',
  },
  'ai.mcp.tokenSet': {
    'en-US': 'Replace token',
    'zh-CN': '替换新 Token',
  },
  'ai.mcp.timeout': {
    'en-US': 'Timeout (ms)',
    'zh-CN': '超时时间 (毫秒)',
  },
  'ai.mcp.configured': {
    'en-US': 'Configured',
    'zh-CN': '已配置',
  },
  'ai.mcp.anonymous': {
    'en-US': 'Anonymous',
    'zh-CN': '匿名访问',
  },
  'ai.mcp.loading': {
    'en-US': 'Loading MCP servers',
    'zh-CN': '正在加载 MCP 服务',
  },
  'ai.mcp.loadFailed': {
    'en-US': 'Failed to load MCP servers',
    'zh-CN': '加载 MCP 服务失败',
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
} satisfies LocaleCatalog
