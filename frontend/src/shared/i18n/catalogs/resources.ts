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
  'ai.catalog.card.builtinBadge': {
    'en-US': 'Built-in',
    'zh-CN': '内置',
  },
  'ai.catalog.card.modelUnconfigured': {
    'en-US': 'Not configured',
    'zh-CN': '未配置',
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
  'ai.catalog.form.remoteModelsFetch': {
    'en-US': 'Fetch models from Provider',
    'zh-CN': '从 Provider 拉取模型',
  },
  'ai.catalog.form.remoteModelsLoading': {
    'en-US': 'Fetching models…',
    'zh-CN': '正在拉取模型…',
  },
  'ai.catalog.form.remoteModelsLoaded': {
    'en-US': '{{count}} models found; pick one in Model ID.',
    'zh-CN': '找到 {{count}} 个模型，可在 Model ID 中选择。',
  },
  'ai.catalog.form.remoteModelsEmpty': {
    'en-US': 'The Provider listed no models.',
    'zh-CN': 'Provider 未列出任何模型。',
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
  'ai.catalog.form.pricingRules': {
    'en-US': 'Conditional prices',
    'zh-CN': '条件价格',
  },
  'ai.catalog.form.pricingRulesHint': {
    'en-US': 'Each rule can limit a daily time window and/or a minimum context size. The first matching rule replaces the prices above; requests that match no rule use the prices above.',
    'zh-CN': '每条规则可限定每天的时段和/或最小上下文长度。计费时用第一条满足的规则替换上面的价格，都不满足时按上面的价格计费。',
  },
  'ai.catalog.form.addPricingRule': {
    'en-US': 'Add rule',
    'zh-CN': '添加规则',
  },
  'ai.catalog.form.deletePricingRule': {
    'en-US': 'Delete rule',
    'zh-CN': '删除规则',
  },
  'ai.catalog.form.pricingTimeWindow': {
    'en-US': 'Only during a daily time window',
    'zh-CN': '仅在每天的指定时段',
  },
  'ai.catalog.form.pricingTimeZone': {
    'en-US': 'Time zone',
    'zh-CN': '时区',
  },
  'ai.catalog.form.pricingStart': {
    'en-US': 'From',
    'zh-CN': '开始',
  },
  'ai.catalog.form.pricingEnd': {
    'en-US': 'Until',
    'zh-CN': '结束',
  },
  'ai.catalog.form.pricingMinContext': {
    'en-US': 'Minimum context tokens',
    'zh-CN': '最小上下文 tokens',
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
  'ai.catalog.form.modelUnconfigured': {
    'en-US': 'Not configured',
    'zh-CN': '未配置',
  },
  'ai.catalog.form.modelUnconfiguredHint': {
    'en-US':
      'This built-in Agent has no Model configured. Select a Model and save to activate it.',
    'zh-CN': '该内置 Agent 尚未配置 Model。选择 Model 后保存即可生效。',
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
  'ai.skillPackages.token': {
    'en-US': 'Access Token',
    'zh-CN': '访问令牌',
  },
  'ai.skillPackages.tokenPlaceholder': {
    'en-US': 'Personal Access Token (optional)',
    'zh-CN': 'Personal Access Token（可选）',
  },
  'ai.skillPackages.tokenCreateHint': {
    'en-US': 'Optional Personal Access Token (PAT) for private repositories. Stored encrypted and never echoed.',
    'zh-CN': '私有仓库的访问令牌（PAT），可选。加密存储且绝不回显。',
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
} satisfies LocaleCatalog
