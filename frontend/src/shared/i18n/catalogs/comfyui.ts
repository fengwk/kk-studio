import type { LocaleCatalog } from '@/shared/i18n/types'

export const comfyuiCatalog = {
  'comfyui.navigation.tools': {
    'en-US': 'Tools',
    'zh-CN': '工具',
  },
  'comfyui.page.loading': {
    'en-US': 'Loading ComfyUI workflows',
    'zh-CN': '正在加载 ComfyUI 工作流',
  },
  'comfyui.page.workflowLoadFailed': {
    'en-US': 'Failed to load workflows',
    'zh-CN': '工作流加载失败',
  },
  'comfyui.page.workflowOperationFailed': {
    'en-US': 'Workflow operation failed',
    'zh-CN': '工作流操作失败',
  },
  'comfyui.extension.loading': {
    'en-US': 'Loading ComfyUI',
    'zh-CN': '正在加载 ComfyUI',
  },
  'comfyui.extension.loadingEditor': {
    'en-US': 'Loading workflow editor',
    'zh-CN': '正在加载工作流编辑器',
  },
  'comfyui.extension.loadingConfirmDialog': {
    'en-US': 'Loading confirmation dialog',
    'zh-CN': '正在加载确认对话框',
  },
  'comfyui.workflows.createTitle': {
    'en-US': 'New ComfyUI Workflow',
    'zh-CN': '新建 ComfyUI Workflow',
  },
  'comfyui.workflows.createSubtitle': {
    'en-US': 'Configure an API-format workflow, input bindings, and a result selector',
    'zh-CN': '配置 API-format workflow、输入绑定与结果选择器',
  },
  'comfyui.card.enabled': {
    'en-US': 'Enabled',
    'zh-CN': '已启用',
  },
  'comfyui.card.disabled': {
    'en-US': 'Disabled',
    'zh-CN': '已禁用',
  },
  'comfyui.card.metaApi': {
    'en-US': 'API',
    'zh-CN': 'API',
  },
  'comfyui.card.metaBindings': {
    'en-US': 'Bindings',
    'zh-CN': '绑定',
  },
  'comfyui.card.metaSelector': {
    'en-US': 'Selector',
    'zh-CN': '选择器',
  },
  'comfyui.card.wholeResult': {
    'en-US': 'whole result',
    'zh-CN': '完整结果',
  },
  'comfyui.card.bindingError': {
    'en-US': 'Configuration error',
    'zh-CN': '配置错误',
  },
  'comfyui.card.bindingParseError': {
    'en-US': 'Binding configuration could not be parsed: {{error}}',
    'zh-CN': '绑定配置无法解析：{{error}}',
  },
  'comfyui.card.run': {
    'en-US': 'Run',
    'zh-CN': '运行',
  },
  'comfyui.card.edit': {
    'en-US': 'Edit',
    'zh-CN': '编辑',
  },
  'comfyui.card.delete': {
    'en-US': 'Delete',
    'zh-CN': '删除',
  },
  'comfyui.card.runAria': {
    'en-US': 'Run {{name}}',
    'zh-CN': '运行 {{name}}',
  },
  'comfyui.card.editAria': {
    'en-US': 'Edit {{name}}',
    'zh-CN': '编辑 {{name}}',
  },
  'comfyui.card.deleteAria': {
    'en-US': 'Delete {{name}}',
    'zh-CN': '删除 {{name}}',
  },
  'comfyui.editor.createTitle': {
    'en-US': 'New ComfyUI Workflow',
    'zh-CN': '新建 ComfyUI Workflow',
  },
  'comfyui.editor.editTitle': {
    'en-US': 'Edit ComfyUI Workflow',
    'zh-CN': '编辑 ComfyUI Workflow',
  },
  'comfyui.editor.apiNameLabel': {
    'en-US': 'API name',
    'zh-CN': 'API 名称',
  },
  'comfyui.editor.apiNamePlaceholder': {
    'en-US': 'image-upscale',
    'zh-CN': 'image-upscale',
  },
  'comfyui.editor.apiNameHint': {
    'en-US': 'Starts with a lowercase letter; only lowercase letters, numbers, and hyphens are allowed.',
    'zh-CN': '小写字母开头，仅允许小写字母、数字和连字符。',
  },
  'comfyui.editor.displayNameLabel': {
    'en-US': 'Display name',
    'zh-CN': '显示名称',
  },
  'comfyui.editor.displayNamePlaceholder': {
    'en-US': 'Image Upscale',
    'zh-CN': 'Image Upscale',
  },
  'comfyui.editor.descriptionLabel': {
    'en-US': 'Description',
    'zh-CN': '描述',
  },
  'comfyui.editor.descriptionPlaceholder': {
    'en-US': "Describe the workflow's purpose",
    'zh-CN': '工作流用途说明',
  },
  'comfyui.editor.workflowJsonLabel': {
    'en-US': 'API-format workflow JSON',
    'zh-CN': 'API-format workflow JSON',
  },
  'comfyui.editor.inputBindingsLabel': {
    'en-US': 'Input bindings JSON',
    'zh-CN': '输入绑定 JSON',
  },
  'comfyui.editor.inputBindingsHint': {
    'en-US': 'Must be an array; each item declares name, kind, nodeId, and inputName.',
    'zh-CN': '必须是数组；每项声明 name、kind、nodeId 和 inputName。',
  },
  'comfyui.editor.selectorLabel': {
    'en-US': 'Default JSONPath selector',
    'zh-CN': '默认 JSONPath 选择器',
  },
  'comfyui.editor.selectorPlaceholder': {
    'en-US': '$.outputs',
    'zh-CN': '$.outputs',
  },
  'comfyui.editor.enabledLabel': {
    'en-US': 'Enabled',
    'zh-CN': '已启用',
  },
  'comfyui.editor.createSubmit': {
    'en-US': 'Create',
    'zh-CN': '确认创建',
  },
  'comfyui.editor.saveSubmit': {
    'en-US': 'Save changes',
    'zh-CN': '保存修改',
  },
  'comfyui.run.ariaLabel': {
    'en-US': 'Run ComfyUI Workflow {{name}}',
    'zh-CN': '运行 ComfyUI Workflow {{name}}',
  },
  'comfyui.run.title': {
    'en-US': 'Run · {{name}}',
    'zh-CN': '运行 · {{name}}',
  },
  'comfyui.run.bindingError': {
    'en-US': 'Input binding configuration error: {{error}}',
    'zh-CN': '输入绑定配置错误：{{error}}',
  },
  'comfyui.run.cannotRun': {
    'en-US': 'Cannot run: {{error}}',
    'zh-CN': '无法运行：{{error}}',
  },
  'comfyui.run.selectorLabel': {
    'en-US': 'JSONPath selector',
    'zh-CN': 'JSONPath 选择器',
  },
  'comfyui.run.selectorPlaceholder': {
    'en-US': 'whole result',
    'zh-CN': '完整结果',
  },
  'comfyui.run.refresh': {
    'en-US': 'Refresh results',
    'zh-CN': '刷新结果',
  },
  'comfyui.run.statusAria': {
    'en-US': 'Run status',
    'zh-CN': '运行状态',
  },
  'comfyui.run.runId': {
    'en-US': 'Run ID',
    'zh-CN': '运行 ID',
  },
  'comfyui.run.status': {
    'en-US': 'Status',
    'zh-CN': '状态',
  },
  'comfyui.run.outputs': {
    'en-US': 'Outputs',
    'zh-CN': '输出',
  },
  'comfyui.run.executionStatus': {
    'en-US': 'Execution status',
    'zh-CN': '执行状态',
  },
  'comfyui.run.executionError': {
    'en-US': 'Execution error',
    'zh-CN': '执行错误',
  },
  'comfyui.run.result': {
    'en-US': 'Result',
    'zh-CN': '结果',
  },
  'comfyui.run.cancel': {
    'en-US': 'Cancel',
    'zh-CN': '取消',
  },
  'comfyui.run.uploading': {
    'en-US': 'Uploading and submitting...',
    'zh-CN': '上传并提交中...',
  },
  'comfyui.run.runAgain': {
    'en-US': 'Run again',
    'zh-CN': '再次运行',
  },
  'comfyui.run.runWorkflow': {
    'en-US': 'Run workflow',
    'zh-CN': '运行工作流',
  },
  'comfyui.run.booleanRequiredPlaceholder': {
    'en-US': 'Select true or false',
    'zh-CN': '请选择 true 或 false',
  },
  'comfyui.run.booleanOptionalPlaceholder': {
    'en-US': 'Not provided (keep workflow value)',
    'zh-CN': '未提供（保留 workflow 原值）',
  },
  'comfyui.run.selectedFile': {
    'en-US': 'Selected: {{name}}',
    'zh-CN': '已选择：{{name}}',
  },
  'comfyui.run.downloadFallback': {
    'en-US': 'download',
    'zh-CN': '下载',
  },
  'comfyui.confirm.deleteTitle': {
    'en-US': 'Delete ComfyUI Workflow',
    'zh-CN': '删除 ComfyUI Workflow',
  },
  'comfyui.confirm.deleteDescription': {
    'en-US': 'This will delete workflow {{name}} ({{apiName}}).',
    'zh-CN': '将删除工作流 {{name}}（{{apiName}}）。',
  },
  'comfyui.confirm.delete': {
    'en-US': 'Delete',
    'zh-CN': '确认删除',
  },
  'comfyui.validation.apiNameRequired': {
    'en-US': 'API name is required',
    'zh-CN': 'API name 不能为空',
  },
  'comfyui.validation.apiNameInvalid': {
    'en-US': 'API name must start with a lowercase letter and contain only lowercase letters, numbers, and hyphens (maximum 64 characters)',
    'zh-CN': 'API name 必须以小写字母开头，且只能包含小写字母、数字和连字符（最多 64 个字符）',
  },
  'comfyui.validation.displayNameRequired': {
    'en-US': 'Display name is required',
    'zh-CN': 'Display name 不能为空',
  },
  'comfyui.validation.workflowJsonRequired': {
    'en-US': 'Workflow JSON is required',
    'zh-CN': 'Workflow JSON 不能为空',
  },
  'comfyui.validation.workflowJsonObject': {
    'en-US': 'Workflow JSON must be a JSON object',
    'zh-CN': 'Workflow JSON 必须是 JSON 对象',
  },
  'comfyui.validation.inputBindingsJsonRequired': {
    'en-US': 'Input bindings JSON is required',
    'zh-CN': 'Input bindings JSON 不能为空',
  },
  'comfyui.validation.inputBindingsJsonArray': {
    'en-US': 'Input bindings JSON must be an array',
    'zh-CN': 'Input bindings JSON 必须是数组',
  },
  'comfyui.validation.inputBindingsJsonArrayRepair': {
    'en-US': 'Input bindings JSON must be an array. Fix it in the workflow editor.',
    'zh-CN': 'Input bindings JSON 必须是数组，请在编辑工作流时修正',
  },
  'comfyui.validation.workflowJsonLabel': {
    'en-US': 'Workflow JSON',
    'zh-CN': 'Workflow JSON',
  },
  'comfyui.validation.inputBindingsJsonLabel': {
    'en-US': 'Input bindings JSON',
    'zh-CN': 'Input bindings JSON',
  },
  'comfyui.validation.parameterLabel': {
    'en-US': 'Parameter {{name}}',
    'zh-CN': '参数 {{name}}',
  },
  'comfyui.validation.bindingItem': {
    'en-US': 'Binding item #{{index}}',
    'zh-CN': '绑定项 #{{index}}',
  },
  'comfyui.validation.bindingItemObject': {
    'en-US': '{{prefix}} must be a JSON object',
    'zh-CN': '{{prefix}} 必须是 JSON 对象',
  },
  'comfyui.validation.duplicateBindingName': {
    'en-US': 'Binding name {{name}} is duplicated. Keep binding names unique.',
    'zh-CN': '绑定名称 {{name}} 重复，请保持唯一',
  },
  'comfyui.validation.bindingKind': {
    'en-US': '{{prefix}}.kind must be parameter or file',
    'zh-CN': '{{prefix}}.kind 必须是 parameter 或 file',
  },
  'comfyui.validation.bindingRequiredBoolean': {
    'en-US': '{{prefix}}.required must be a boolean',
    'zh-CN': '{{prefix}}.required 必须是布尔值',
  },
  'comfyui.validation.bindingDescriptionString': {
    'en-US': '{{prefix}}.description must be a string',
    'zh-CN': '{{prefix}}.description 必须是字符串',
  },
  'comfyui.validation.fileBindingOptions': {
    'en-US': '{{prefix}} is a file binding and cannot set valueType or defaultValue',
    'zh-CN': '{{prefix}} 是文件绑定，不能设置 valueType 或 defaultValue',
  },
  'comfyui.validation.bindingValueType': {
    'en-US': '{{prefix}}.valueType must be string, integer, number, boolean, or json',
    'zh-CN': '{{prefix}}.valueType 必须是 string、integer、number、boolean 或 json',
  },
  'comfyui.validation.defaultString': {
    'en-US': '{{prefix}}.defaultValue must be a string',
    'zh-CN': '{{prefix}}.defaultValue 必须是字符串',
  },
  'comfyui.validation.defaultInteger': {
    'en-US': '{{prefix}}.defaultValue must be an integer',
    'zh-CN': '{{prefix}}.defaultValue 必须是整数',
  },
  'comfyui.validation.defaultNumber': {
    'en-US': '{{prefix}}.defaultValue must be a number',
    'zh-CN': '{{prefix}}.defaultValue 必须是数字',
  },
  'comfyui.validation.defaultBoolean': {
    'en-US': '{{prefix}}.defaultValue must be a boolean',
    'zh-CN': '{{prefix}}.defaultValue 必须是布尔值',
  },
  'comfyui.validation.fieldRequired': {
    'en-US': '{{prefix}}.{{field}} is required',
    'zh-CN': '{{prefix}}.{{field}} 不能为空',
  },
  'comfyui.validation.invalidJson': {
    'en-US': '{{label}} is not valid JSON',
    'zh-CN': '{{label}} 不是合法 JSON',
  },
  'comfyui.validation.parameterBooleanRequired': {
    'en-US': 'Parameter {{name}} must explicitly select true or false',
    'zh-CN': '参数 {{name}} 必须明确选择 true 或 false',
  },
  'comfyui.validation.parameterBoolean': {
    'en-US': 'Parameter {{name}} must be true or false',
    'zh-CN': '参数 {{name}} 必须是 true 或 false',
  },
  'comfyui.validation.parameterRequired': {
    'en-US': 'Parameter {{name}} is required',
    'zh-CN': '参数 {{name}} 为必填项',
  },
  'comfyui.validation.parameterInteger': {
    'en-US': 'Parameter {{name}} must be an integer',
    'zh-CN': '参数 {{name}} 必须是整数',
  },
  'comfyui.validation.parameterNumber': {
    'en-US': 'Parameter {{name}} must be a number',
    'zh-CN': '参数 {{name}} 必须是数字',
  },
  'comfyui.error.operationFailed': {
    'en-US': 'Operation failed',
    'zh-CN': '操作失败',
  },
  'comfyui.upload.invalidMethod': {
    'en-US': 'Invalid presigned upload method: {{method}}',
    'zh-CN': '预签名上传方法无效：{{method}}',
  },
  'comfyui.upload.directFailure': {
    'en-US': 'Direct file upload failed (HTTP {{status}})',
    'zh-CN': '文件直传失败（HTTP {{status}}）',
  },
  'comfyui.validation.requiredFile': {
    'en-US': 'File {{name}} is required',
    'zh-CN': '文件 {{name}} 为必填项',
  },
} satisfies LocaleCatalog
