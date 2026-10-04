import type { LocaleCatalog } from '@/shared/i18n/types'

/** 设置同步（导入/导出）文案：只描述用户动作与结果，不暴露内部实现。 */
export const syncCatalog = {
  'settings.tabs.sync': {
    'en-US': 'Sync',
    'zh-CN': '同步',
  },
  'settings.sync.description': {
    'en-US': 'Import or export configuration saved on the server.',
    'zh-CN': '导入或导出服务端已保存的配置。',
  },
  'settings.sync.import': {
    'en-US': 'Import',
    'zh-CN': '导入',
  },
  'settings.sync.export': {
    'en-US': 'Export',
    'zh-CN': '导出',
  },
  'settings.sync.credentialsNotice': {
    'en-US': 'The configuration file contains credentials; please keep it safe.',
    'zh-CN': '配置文件包含密钥，请妥善保管。',
  },
  'settings.sync.loading': {
    'en-US': 'Loading configuration…',
    'zh-CN': '正在加载配置…',
  },
  'settings.sync.loadFailed': {
    'en-US': 'Failed to load configuration.',
    'zh-CN': '配置加载失败。',
  },
  'settings.sync.retry': {
    'en-US': 'Retry',
    'zh-CN': '重试',
  },
  'settings.sync.group.agentsModels': {
    'en-US': 'Agents, Models & Providers',
    'zh-CN': 'Agent、模型与提供商',
  },
  'settings.sync.kind.agents': {
    'en-US': 'Agents',
    'zh-CN': 'Agent',
  },
  'settings.sync.kind.models': {
    'en-US': 'Models',
    'zh-CN': '模型',
  },
  'settings.sync.kind.providers': {
    'en-US': 'Providers',
    'zh-CN': '提供商',
  },
  'settings.sync.kind.skillPackages': {
    'en-US': 'Skill Packages',
    'zh-CN': '技能包',
  },
  'settings.sync.kind.environments': {
    'en-US': 'Environments',
    'zh-CN': '环境',
  },
  'settings.sync.kind.mcpServers': {
    'en-US': 'MCP Servers',
    'zh-CN': 'MCP 服务',
  },
  'settings.sync.kind.settings': {
    'en-US': 'Settings',
    'zh-CN': '设置',
  },
  'settings.sync.export.title': {
    'en-US': 'Export configuration',
    'zh-CN': '导出配置',
  },
  'settings.sync.export.description': {
    'en-US': 'Choose what to export; dependencies are added automatically.',
    'zh-CN': '选择要导出的内容，依赖项会自动包含。',
  },
  'settings.sync.export.dependencyHint': {
    'en-US': 'Items marked as dependencies cannot be removed on their own.',
    'zh-CN': '作为依赖的条目不能单独移除。',
  },
  'settings.sync.export.dependency': {
    'en-US': 'Dependency',
    'zh-CN': '依赖',
  },
  'settings.sync.export.empty': {
    'en-US': 'Select at least one item to export.',
    'zh-CN': '请至少选择一项。',
  },
  'settings.sync.export.scopeSummary': {
    'en-US': '{{kinds}} kinds · {{items}} items (including dependencies)',
    'zh-CN': '共 {{kinds}} 类 · {{items}} 项（含依赖）',
  },
  'settings.sync.export.kindCount': {
    'en-US': '{{kind}} × {{count}}',
    'zh-CN': '{{kind}} × {{count}}',
  },
  'settings.sync.export.confirm': {
    'en-US': 'Export',
    'zh-CN': '导出',
  },
  'settings.sync.export.exporting': {
    'en-US': 'Exporting…',
    'zh-CN': '导出中…',
  },
  'settings.sync.export.done': {
    'en-US': 'Exported {{filename}}',
    'zh-CN': '已导出 {{filename}}',
  },
  'settings.sync.export.failed': {
    'en-US': 'Export failed.',
    'zh-CN': '导出失败。',
  },
  'settings.sync.import.title': {
    'en-US': 'Import configuration',
    'zh-CN': '导入配置',
  },
  'settings.sync.import.previewHint': {
    'en-US': 'Review the planned changes before importing.',
    'zh-CN': '导入前请确认以下变更计划。',
  },
  'settings.sync.import.fileName': {
    'en-US': 'File: {{name}}',
    'zh-CN': '文件：{{name}}',
  },
  'settings.sync.import.createdHeading': {
    'en-US': 'Will be added',
    'zh-CN': '将新增',
  },
  'settings.sync.import.updatedHeading': {
    'en-US': 'Will be overwritten',
    'zh-CN': '将覆盖',
  },
  'settings.sync.import.previewSkippedHeading': {
    'en-US': 'Will be skipped',
    'zh-CN': '将跳过',
  },
  'settings.sync.import.nothingUsable': {
    'en-US': 'This file contains no configuration that can be imported.',
    'zh-CN': '此文件没有可导入的配置。',
  },
  'settings.sync.import.confirm': {
    'en-US': 'Confirm import',
    'zh-CN': '确认导入',
  },
  'settings.sync.import.confirmPartial': {
    'en-US': 'Import available only',
    'zh-CN': '仅导入可用配置',
  },
  'settings.sync.import.importing': {
    'en-US': 'Importing…',
    'zh-CN': '导入中…',
  },
  'settings.sync.import.invalidFile': {
    'en-US': 'Choose a .yaml or .yml file.',
    'zh-CN': '请选择 .yaml 或 .yml 文件。',
  },
  'settings.sync.import.readFailed': {
    'en-US': 'Failed to read the file.',
    'zh-CN': '文件读取失败。',
  },
  'settings.sync.import.checkFailed': {
    'en-US': 'Failed to check the configuration.',
    'zh-CN': '配置检查失败。',
  },
  'settings.sync.import.failed': {
    'en-US': 'Import failed.',
    'zh-CN': '导入失败。',
  },
  'settings.sync.import.importedHeading': {
    'en-US': 'Imported',
    'zh-CN': '已导入',
  },
  'settings.sync.import.skippedHeading': {
    'en-US': 'Skipped',
    'zh-CN': '已跳过',
  },
  'settings.sync.import.nothingImported': {
    'en-US': 'Nothing was imported.',
    'zh-CN': '没有可导入的配置。',
  },
  'settings.sync.import.dirtyHint': {
    'en-US': 'Settings has unsaved changes; they are kept as they are.',
    'zh-CN': '设置中有未保存的更改，将保持原样。',
  },
  'settings.sync.import.reloadSettings': {
    'en-US': 'Reload settings',
    'zh-CN': '重新加载设置',
  },
} satisfies LocaleCatalog
