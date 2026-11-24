// Register only in the offline preview build. Never use with production settings pages.
export function extraConfigPreviewPlugin(sourceRoot) {
  const roots = [`${sourceRoot}/features/catalog/`, `${sourceRoot}/features/settings/`, `${sourceRoot}/features/environment/`]
  return {
    name: 'extra-config-preview',
    enforce: 'pre',
    transform(source, id) {
      const root = roots.find((prefix) => id.startsWith(prefix))
      if (!root || id.includes('?')) return
      const file = id.slice(root.length)
      let code = source
      function replace(anchor, replacement = '') {
        const count = typeof anchor === 'string' ? code.split(anchor).length - 1
          : [...code.matchAll(new RegExp(anchor.source, 'g'))].length
        if (count !== 1) throw new Error(`${file}: expected one preview anchor: ${anchor}`)
        code = code.replace(anchor, replacement)
      }
      function localMcpFields() {
        replace(/import \{ TextArea \}[^\n]*\n/)
        replace(/const HEADERS_PLACEHOLDER = [^\n]*\n/)
        replace(/            +<label className="form-group">\n +<FieldLabel>\{t\('ai.mcp.headers'\)\}<\/FieldLabel>[\s\S]*?<\/label>\n/)
        replace('https://example.com/mcp', 'https://mcp.example.invalid/mcp')
      }
      switch (file) {
        case 'sync/SyncTab.tsx':
          replace('      <p className="settings-hint">{t(\'settings.sync.description\')}</p>\n')
          break
        case 'sync/SyncExportModal.tsx':
          for (const key of ['export.description', 'credentialsNotice', 'export.dependencyHint']) {
            replace(`        <p className="settings-hint">{t('settings.sync.${key}')}</p>\n`)
          }
          break
        case 'sync/SyncImportModal.tsx':
          replace("              <p className=\"settings-hint\">{t('settings.sync.import.previewHint')}</p>\n")
          break
        case 'sync/download-config-yaml.ts':
          replace("'kk-studio-config.yaml'", "'kk-studio-preview.yaml'")
          break
        case 'AiProviderResourceForm.tsx':
          replace(/import \{ Checkbox \}[^\n]*\n/)
          replace(/  \/\/ 只有编辑[^\n]*\n  const canClearCredential[^\n]*\n/)
          replace(/      <label className="form-group">\n        <FieldLabel>\{t\('ai.catalog.form.apiKeyOptional'\)\}[\s\S]*?\n      \) : null\}\n/)
          replace(/\) : \(\n          <small className="form-group-hint">\n            \{t\('ai.catalog.form.httpRetryInheritHint'\)\}\n          <\/small>\n        \)\}/, ') : null}')
          replace('https://api.example.com/v1', 'https://api.example.invalid/v1')
          break
        case 'skills/SkillPackageSourceFields.tsx':
          replace('const { t } = useI18n()', 'const { t, locale } = useI18n()')
          replace('https://github.com/org/repo.git', 'https://git.example.invalid/team/example-skills.git')
          replace("{t('ai.skillPackages.source.UPLOAD')}", "{locale === 'zh-CN' ? '本地压缩包' : 'Local archive'}")
          replace(/          <label className="form-group">\n            <FieldLabel>\{t\('ai.skillPackages.token'\)\}[\s\S]*?<\/label>\n/)
          replace("            <small>{t('ai.skillPackages.archiveFileHint')}</small>\n")
          break
        case 'mcp/CreateMcpServerDialog.tsx':
          localMcpFields()
          replace("import { Mono } from '@/shared/ui/data/Mono'\n")
          replace("import { MCP_NAME_PATTERN_SOURCE } from './mcp-utils'\n")
          replace(/              <span className="inline-hint">\n                \{t\('ai.mcp.nameHint'\)\}[\s\S]*?<\/span>\n/)
          replace("              <span className=\"inline-hint\">{t('ai.mcp.urlHint')}</span>\n")
          break
        case 'mcp/EditMcpServerDialog.tsx':
          replace(/import \{ RefreshCw \}[^\n]*\n/)
          replace(/import \{ LoadingIndicator \}[^\n]*\n/)
          replace(/import \{ StateBlock \}[^\n]*\n/)
          replace(/        \{form.loading \? \([\s\S]*?        \) : \(\n/)
          replace('          </form>\n        )}', '          </form>')
          replace(/              <Button\n                variant="secondary"\n                className="modal-footer-leading-action"[\s\S]*?<\/Button>\n/)
          replace('modal-footer modal-footer-with-leading-action', 'modal-footer')
          replace('  onDiscover,\n  onRetryLoad,\n')
          localMcpFields()
          break
        case 'EnvironmentConfigurationSection.tsx':
          replace("import { Mono } from '@/shared/ui/data/Mono'", "import { Mono } from '@/shared/ui/data/Mono'\nimport { HelpHint } from '@/shared/ui/controls/HelpHint'")
          replace("          <h3 className=\"env-mgmt-section-title\">{t('ai.environment.config.title')}</h3>", `<div className="field-label-row"><h3 className="env-mgmt-section-title">{t('ai.environment.config.title')}</h3><HelpHint label={t('shared.helpAbout', { topic: t('ai.environment.config.title') })}>{t('ai.environment.config.hotUpdateHelp')}</HelpHint></div>`)
          replace("      <p className=\"field-help\">{t('ai.environment.config.hotUpdateHelp')}</p>\n")
          break
        case 'SystemSettingsSchemaRenderer.tsx':
          replace("  const hint = field.hintKey == null ? undefined : t(requireTranslationKey(field.hintKey))", `  const hint = field.path === 'backendNetwork.proxyUrl'
    ? '只影响保存后新发起的 HTTP 请求。'
    : field.path === 'backendNetwork.noProxyHosts'
      ? '用逗号分隔规则；CIDR 只匹配请求 URL 中的 IP。'
      : field.path === 'tool.permission' && field.hintKey ? t(requireTranslationKey(field.hintKey)) : undefined`)
          replace("import { requireTranslationKey, useI18n } from '@/shared/i18n'", "import { requireTranslationKey, useI18n } from '@/shared/i18n'\nimport { HelpHint } from '@/shared/ui/controls/HelpHint'")
          replace('{hint ? <span className="settings-field-description">{hint}</span> : null}', "{hint ? <HelpHint label={'帮助：' + label}>{hint}</HelpHint> : null}")
          break
        case 'settings-primitives.tsx':
          replace("import { TextInput } from '@/shared/ui/controls/TextInput'", "import { TextInput } from '@/shared/ui/controls/TextInput'\nimport { HelpHint } from '@/shared/ui/controls/HelpHint'")
          replace(/export function SettingsTextField\([\s\S]*?  const text = description \?\? hint/, (match) => match.replace('const text = description ?? hint', 'const text = hint'))
          replace('      <label className="settings-field-label" htmlFor={fieldId}>\n        {label}\n      </label>\n      {text ? <span className="settings-field-description">{text}</span> : null}\n      <TextInput', `      <div className="field-label-row">\n        <label className="settings-field-label" htmlFor={fieldId}>{label}</label>\n        {text ? <HelpHint label={\`帮助：\${label}\`}>{text}</HelpHint> : null}\n      </div>\n      <TextInput`)
          replace('        placeholder={placeholder}\n        maxLength={maxLength}', '        placeholder={fieldPath === \'backendNetwork.proxyUrl\' ? \'直连\' : placeholder}\n        maxLength={maxLength}')
          break
        case 'permission/PermissionEditor.tsx':
          replace("import { ArrowDown, ArrowUp, X } from 'lucide-react'", "import { ArrowDown, ArrowUp, Plus, X } from 'lucide-react'")
          replace('{t(\'settings.permission.addRule\')}', '<Plus aria-hidden="true" />{t(\'settings.permission.addRule\')}')
          replace('{t(\'settings.permission.addTool\')}', '<Plus aria-hidden="true" />{t(\'settings.permission.addTool\')}')
          break
        default:
          return
      }
      return { code, map: null }
    },
  }
}
