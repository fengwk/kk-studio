// Apply only to the original Catalog modules in this offline build. Each anchor
// must match exactly once so upstream edits cannot silently re-enable online UI.
// The offline demo consumes the already-normalized draft, not an HTTP mock.
// Match the runtime's rule order and use decimal strings (no float rounding).
import path from 'node:path'

const PROTOTYPE_PICKERS = new Set(['@preview-catalog/DateInput', '@preview-catalog/TimeInput'])

async function offlinePricingPreview({ pricing, inputTokens, requestedAt, serviceTier }, signal) {
  signal.throwIfAborted()
  const empty = { rates: null, activeFrom: null, matchedRules: [] }
  if (!pricing || (pricing.changes.length && requestedAt === undefined)) return empty
  const date = requestedAt === undefined ? '' : new Date(requestedAt).toISOString().slice(0, 10)
  const active = pricing.changes.filter((change) => change.from <= date).at(-1)
  const { base, rules } = active ?? pricing
  const rates = { ...base }
  const matchedRules = []
  const fields = ['input', 'output', 'cacheRead', 'cacheWrite', 'reasoning']
  const clockMinutes = (value) => Number(value.slice(0, 2)) * 60 + Number(value.slice(3))
  for (const rule of rules) {
    let matched = false
    switch (rule.type) {
      case 'input_token_range':
        matched = inputTokens >= (rule.minInputTokens ?? 0) && inputTokens < (rule.maxInputTokensExclusive ?? Infinity)
        if (matched) {
          for (const field of fields) if (rule[field] !== undefined) rates[field] = rule[field]
          if (rule.cacheWriteByTtl !== undefined) rates.cacheWriteByTtl = rule.cacheWriteByTtl
        }
        break
      case 'service_tier_multiplier':
        matched = serviceTier === rule.serviceTier
        break
      case 'time_multiplier': {
        if (requestedAt === undefined) break
        const parts = new Intl.DateTimeFormat('en-GB', {
          timeZone: rule.timezone, weekday: 'short', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
        }).formatToParts(requestedAt)
        const part = (name) => parts.find((item) => item.type === name)?.value
        const minutes = Number(part('hour')) * 60 + Number(part('minute'))
        const day = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'].indexOf(part('weekday')) + 1
        matched = minutes >= clockMinutes(rule.start) && minutes < clockMinutes(rule.end)
          && (!rule.weekdays?.length || rule.weekdays.includes(day))
        break
      }
    }
    if (matched) matchedRules.push(rule)
  }
  const activeFrom = active?.from ?? null
  if (rules.some((rule) => (rule.type === 'time_multiplier' && requestedAt === undefined)
    || (rule.type === 'service_tier_multiplier' && !serviceTier))) {
    return { rates: null, activeFrom, matchedRules }
  }

  function multiply(value, factor) {
    const places = (text) => text.split('.')[1]?.length ?? 0
    const scale = places(value) + places(factor)
    const product = String(BigInt(value.replace('.', '')) * BigInt(factor.replace('.', '')))
    if (!scale) return product
    const digits = product.padStart(scale + 1, '0')
    return (digits.slice(0, -scale) + '.' + digits.slice(-scale)).replace(/0+$/, '').replace(/\.$/, '')
  }
  for (const type of ['service_tier_multiplier', 'time_multiplier']) {
    const rule = matchedRules.find((rule) => rule.type === type)
    if (!rule) continue
    for (const field of fields) {
      if (rates[field] != null) rates[field] = multiply(rates[field], rule[field + 'Factor'] ?? rule.factor ?? '1')
    }
    rates.cacheWriteByTtl = Object.fromEntries(Object.entries(rates.cacheWriteByTtl).map(([ttl, rate]) => [
      ttl, multiply(rate, rule.cacheWriteFactor ?? rule.factor ?? '1'),
    ]))
  }
  return { rates, activeFrom, matchedRules }
}

export function catalogEditorPreviewPlugin(sourceRoot) {
  const catalog = `${sourceRoot}/features/catalog/`
  return {
    name: 'catalog-editor-preview',
    enforce: 'pre',
    resolveId(source) {
      if (!PROTOTYPE_PICKERS.has(source)) return
      return path.resolve(sourceRoot, '../../docs/workspace-prototype/workspace', `${source.slice(source.lastIndexOf('/') + 1)}.tsx`)
    },
    transform(source, id) {
      if (!id.startsWith(catalog) || id.includes('?')) return
      const file = id.slice(catalog.length)
      let code = source
      function replace(anchor, replacement = '') {
        const matches = typeof anchor === 'string' ? code.split(anchor).length - 1 : [...code.matchAll(new RegExp(anchor.source, 'g'))].length
        if (matches !== 1) throw new Error(`${file}: expected a single preview anchor: ${anchor}`)
        code = code.replace(anchor, replacement)
      }
      switch (file) {
        case 'AiConsoleResourceEditorModal.tsx':
          replace(/  const bodyClassName = [\s\S]*?\n  const body = \(/, '  const body = (')
          replace(/className=\{bodyClassName\}>[\s\S]*?(?=      <div className="catalog-editor-content">)/, 'className="modal-body catalog-editor-body">\n')
          replace("sections.length > 0 ? 'catalog-editor-card' : ''", "sections.length > 0 ? 'catalog-editor-card catalog-preview-editor' : ''")
          break
        case 'CapabilitySelectionPanels.tsx':
          replace('label, manageLabel, searchLabel', 'label, searchLabel')
          replace(/  const selectedNames = [^\n]*\n/)
          replace(/      <details className="capability-selection-details">\n        <summary>\{manageLabel\}<\/summary>\n/)
          replace(/      <\/details>\n      \{selectedNames.length > 0 \? \([\s\S]*?\) : null\}\n/)
          break
        case 'AiAgentResourceForm.tsx':
          replace("import { Select } from '@/shared/ui/controls/Select'\n")
          replace(/        <label className="form-group">\n          <FieldLabel required>\{t\('ai.catalog.form.executionDriver'\)\}<\/FieldLabel>[\s\S]*?        <\/label>\n/)
          break
        case 'AiAgentResourceCard.tsx':
          for (const [key, tags] of [['agentTools', 'tools'], ['agentSkills', 'skillTags'], ['agentSubagents', 'subagents']]) {
            replace(`        { label: t('ai.catalog.form.${key}'), tags: ${tags}, limit: 2 },`,
              `        ...(${tags}.length ? [{ label: t('ai.catalog.form.${key}'), tags: ${tags}, limit: 2 }] : []),`)
          }
          break
        case 'AiModelResourceForm.tsx':
          replace("import { useId, useLayoutEffect, useState } from 'react'", "import { useId } from 'react'")
          replace('  const { t } = useI18n()\n  const [catalogOpen, setCatalogOpen] = useState(false)', '  const { t } = useI18n()\n  const [catalogOpen, setCatalogOpen] = useState(false)')
          for (const module of ['AiModelCatalogPicker', 'ai-model-catalog-apply', 'ai-model-capabilities', 'AiRemoteModelIdPicker']) {
            replace(new RegExp(`import [^\n]+ from '@/features/catalog/${module}'\n`))
          }
          replace(/import \{ toUserFacingErrorMessage \}[^\n]*\n/)
          replace(/import \{ Button \}[^\n]*\n/)
          replace('  loadRemoteModelIds,\n')
          replace(/  const \[catalogOpen, setCatalogOpen\][^\n]*\n/)
          replace(/  const providerName = draft.providerName.trim\(\)[\s\S]*?(?=  const defaultVariant =)/, `
  const activeProvider = providers.find((item) => item.name === draft.providerName)
  const retentions = activeProvider?.promptCacheRetentions ?? []
  const modelCapabilities: ModelCapabilitiesDTO = {
    promptCacheState: retentions.length ? 'CONFIGURABLE' : 'PROVIDER_MANAGED',
    defaultPromptCacheRetention: 'NONE',
    promptCacheOptions: retentions.map((retention) => ({ retention })),
  }
`)
          replace(/            list=\{mode === 'create'[^\n]*\n/)
          replace(/          \{mode === 'create' \? \(\n            <RemoteModelIdPicker[\s\S]*?\) : null\}\n/)
          replace(/        <div className="catalog-picker-launcher">[\s\S]*?\) : null\}\n/)
          replace(/            capabilitiesLoading=\{requestKey[\s\S]*?\n(?=            onChange=)/)
          replace('aria-label={t(\'ai.catalog.form.defaultVariant\')}\n', 'aria-label={t(\'ai.catalog.form.defaultVariant\')}\n            required\n')
          for (const hint of ['nameHint', 'modelIdHint']) {
            replace(`          <span className="inline-hint">{t('ai.catalog.form.${hint}')}</span>\n`)
          }
          break
        case 'ai-model-form-sections.tsx':
          // These protocol values remain in the draft and submission codec.
          replace("import { FieldLabel } from '@/shared/ui/controls/FieldLabel'\n", "import { FieldLabel } from '@/shared/ui/controls/FieldLabel'\nimport { HelpHint } from '@/shared/ui/controls/HelpHint'\n")
          replace("<FieldLabel htmlFor={inputLimitId}>{t('ai.catalog.form.inputLimit')}</FieldLabel>", `<div className="field-label-row"><FieldLabel htmlFor={inputLimitId}>{t('ai.catalog.form.inputLimit')}</FieldLabel><HelpHint label={locale === 'zh-CN' ? '帮助：输入上限' : 'Help: input limit'}>{locale === 'zh-CN' ? '只限制单次请求的输入，不改变模型上下文窗口。' : 'Caps input per request without changing the context window.'}</HelpHint></div>`)
          replace("<FieldLabel required htmlFor={maxOutputTokensId}>{t('ai.catalog.form.maxOutputTokens')}</FieldLabel>", `<div className="field-label-row"><FieldLabel required htmlFor={maxOutputTokensId}>{t('ai.catalog.form.maxOutputTokens')}</FieldLabel><HelpHint label={locale === 'zh-CN' ? '帮助：最大输出 token 数' : 'Help: max output tokens'}>{t('ai.catalog.form.maxOutputLimitHint')}</HelpHint></div>`)
          for (const field of ['midConversationSystemMessages', 'messageLevelEffort']) {
            replace(new RegExp(`        <Checkbox\n          checked=\\{draft\\.${field}\\}[\\s\\S]*?\n        />\n`))
          }
          replace(/          <span id="model-input-limit-hint"[\s\S]*?<\/span>\n/)
          replace('import { joinDescribedBy, useFieldErrorId }', 'import { useFieldErrorId }')
          replace("aria-describedby={joinDescribedBy('model-input-limit-hint', inputLimitErrorId)}", 'aria-describedby={inputLimitErrorId}')
          replace('  const { t } = useI18n()\n  const contextWindowId', '  const { t, locale } = useI18n()\n  const contextWindowId')
          replace('placeholder="272000"', "placeholder={locale === 'zh-CN' ? '跟随上下文窗口' : 'Uses the context window'}")
          replace(/          <span className="inline-hint">\{t\('ai.catalog.form.maxOutputLimitHint'\)\}<\/span>\n/)
          replace('  const errorId = useFieldErrorId(error)', '  const headingId = useId()\n  const errorId = useFieldErrorId(error)')
          replace(/    <fieldset[\s\S]*?<div className="capability-options">/, `    <section className={\`structured-section\${error ? ' is-error' : ''}\`}
      role="group" aria-labelledby={headingId} aria-describedby={errorId}>
      <div className="structured-section-head"><h3 id={headingId}>{t('ai.catalog.form.inputTypes')}</h3></div>
      <div className="ability-toggle-row">`)
          replace('    </fieldset>', '    </section>')
          replace(/              className=\{`capability-option[^\n]*\n/)
          break
        case 'AiVariantListEditor.tsx':
          replace(/              <details className="advanced-options">\n                <summary>[^\n]*\n/)
          replace('              </details>\n')
          replace("import { useId } from 'react'\n")
          replace("import { FieldLabel } from '@/shared/ui/controls/FieldLabel'\n", "import { FieldLabel } from '@/shared/ui/controls/FieldLabel'\nimport { HelpHint } from '@/shared/ui/controls/HelpHint'\n")
          replace('const { t } = useI18n()', 'const { t, locale } = useI18n()')
          replace('  const reasoningHintId = useId()\n')
          replace(/const cacheStateMessage = \{[\s\S]*?\} as const\n\n/)
          replace(/  const cacheStatus = [\s\S]*?(?=  const defaultRetention)/)
          replace(/      <p className="inline-hint" id=\{reasoningHintId\}>[\s\S]*?<\/p>\n/)
          replace(/                \) : \(\n                  <div className="form-group">[\s\S]*?\n                \)\}/, '                ) : null}')
          replace(/                <label className="form-group">\n                  <FieldLabel>\{t\('ai.catalog.form.reasoningEffort'\)\}[\s\S]*?<\/label>/, (field) => `{reasoning && (${field.replace('<label', '<div').replace('</label>', '</div>')})}`)
          replace('                    aria-describedby={!reasoning ? reasoningHintId : undefined}\n                    disabled={!reasoning}\n')
          replace("placeholder={t('ai.catalog.form.reasoningEffortPlaceholder')}", "placeholder={locale === 'zh-CN' ? '使用模型默认' : 'Use model default'}")
          replace("<FieldLabel>{t('ai.catalog.form.reasoningEffort')}</FieldLabel>", `<div className="field-label-row"><FieldLabel htmlFor={'x-effort-' + variant.draftId}>{t('ai.catalog.form.reasoningEffort')}</FieldLabel><HelpHint label={locale === 'zh-CN' ? '帮助：思考强度' : 'Help: reasoning effort'}>{locale === 'zh-CN' ? '填 off 关闭思考。' : 'Enter off to disable reasoning.'}</HelpHint></div>`)
          replace('value={variant.reasoningEffort}', "id={'x-effort-' + variant.draftId} value={variant.reasoningEffort}")
          replace("                  <span className=\"inline-hint\">{t('ai.catalog.form.protocolOptionsHint')}</span>\n")
          break
        case 'ModelPricingPlanEditor.tsx':
          replace("import { previewModelPricing } from '@/features/catalog/ai-model-pricing-preview'\n", "import { cloneElement, isValidElement } from 'react'\n")
          replace('  return <label className="form-group"><span>{label}</span>{children}</label>', `  const id = useId()
  if (isValidElement<{ id?: string }>(children) && (children.type === DateInput || children.type === TimeInput)) {
    const triggerId = children.props.id ?? id
    return <div className="form-group"><label className="field-label" htmlFor={triggerId}>{label}</label>{cloneElement(children, { id: triggerId })}</div>
  }
  return <label className="form-group"><span>{label}</span>{children}</label>`)
          replace("import { TextInput } from '@/shared/ui/controls/TextInput'\n", "import { TextInput } from '@/shared/ui/controls/TextInput'\nimport { DateInput } from '@preview-catalog/DateInput'\nimport { TimeInput } from '@preview-catalog/TimeInput'\nimport { HelpHint } from '@/shared/ui/controls/HelpHint'\nimport { Plus } from 'lucide-react'\n")
          replace("import './ai-pricing.css'", `import './ai-pricing.css'\n\nconst previewModelPricing = ${offlinePricingPreview.toString()}`)
          replace('  const [newRule, setNewRule] = useState(\'\')\n')
          for (const group of ['inputTokenRanges', 'serviceTiers', 'timePeriods']) {
            replace(`        setNewRule(draftKey)\n        updateRows('${group}'`, `        updateRows('${group}'`)
          }
          replace('<details className="pricing-rule-details" open={newRule === key || undefined}>\n          <summary>{index + 1}. {summary}</summary>', '<section className="pricing-rule-details">\n          <h5 className="catalog-preview-price-heading">{index + 1}. {summary}</h5>')
          replace('          </div>\n        </details>', '          </div>\n        </section>')
          replace('<details className="pricing-factor-details">\n      <summary>{t(\'ai.catalog.form.pricingFactorOverrides\')}</summary>', `<div className="pricing-factor-details"><h6 className="catalog-preview-price-heading">{t('ai.catalog.form.pricingFactorOverrides')}</h6>`)
          replace('    </details>\n  </>', '    </div>\n  </>')
          replace('placeholder={factors.factor}', "placeholder={locale === 'zh-CN' ? `继承 ×${factors.factor}` : `Inherit ×${factors.factor}`}")
          replace('  const { t } = useI18n()\n  return <>\n    <Field label=', '  const { t, locale } = useI18n()\n  return <>\n    <Field label=')
          replace(/<Field label=\{t\('ai\.catalog\.form\.pricingFromDate'\)\}>\s*<TextInput type="date" value=\{newDate\} onChange=\{\(event\) => setNewDate\(event\.target\.value\)\} \/>\s*<\/Field>/, "<Field label={t('ai.catalog.form.pricingFromDate')}><DateInput value={newDate} onChange={setNewDate} /></Field>")
          replace(/<Field label=\{t\('ai\.catalog\.form\.pricingFromDate'\)\}>\s*<TextInput type="date" value=\{selected\.from\} onChange=\{\(event\) => \{/, "<Field label={t('ai.catalog.form.pricingFromDate')}><DateInput value={selected.from} onChange={(from) => {")
          replace("            const from = event.target.value\n", '')
          replace('          }} />\n        </Field>', '          }} /></Field>')
          replace('<Field label={t(\'ai.catalog.form.pricingStart\')}><TextInput placeholder="HH:mm" value={period.start}\n              onChange={(event) => update({ start: event.target.value })} /></Field>', '<Field label={t(\'ai.catalog.form.pricingStart\')}><TimeInput value={period.start}\n              onChange={(start) => update({ start })} /></Field>')
          replace(/            <Field label=\{t\('ai.catalog.form.pricingEnd'\)\}><TextInput[\s\S]*?<\/Field>/, `<Field label={t('ai.catalog.form.pricingEnd')}><TimeInput allowEndOfDay value={period.end}\n              onChange={(end) => update({ end })} /></Field>`)
          replace("  const { t } = useI18n()\n  const { base, inputTokenRanges", "  const { t, locale } = useI18n()\n  const { base, inputTokenRanges")
          replace('  const { t } = useI18n()\n  const today = new Date().toISOString().slice(0, 10)', "  const { t, locale } = useI18n()\n  const today = new Date().toISOString().slice(0, 10)")
          replace('<span>{t(\'ai.catalog.form.pricingSelectPlan\')}</span>', `<div className="field-label-row"><span>{t('ai.catalog.form.pricingSelectPlan')}</span>{(!selected || selected.from && selected.from <= today) && hasConfiguredPricing(value) ? <HelpHint label={locale === 'zh-CN' ? '帮助：定价历史' : 'Help: pricing history'}>{t('ai.catalog.form.pricingHistoryWarning')}</HelpHint> : null}</div>`)
          replace(/\{\(!selected \|\| selected\.from && selected\.from <= today\) && hasConfiguredPricing\(value\)\n\s*\? <p className="inline-hint">\{t\('ai\.catalog\.form\.pricingHistoryWarning'\)\}<\/p> : null\}\n/)
          replace("  const { t } = useI18n()\n  const timeId", "  const { t, locale } = useI18n()\n  const dateId")
          replace("<Section title={t('ai.catalog.form.pricingPreview')}>", "<Section title={locale === 'zh-CN' ? '离线费率试算' : 'Offline pricing preview'}>")
          replace("  const [requestedAt, setRequestedAt] = useState('')\n", "  const [requestedDate, setRequestedDate] = useState('')\n  const [requestedTime, setRequestedTime] = useState('')\n")
          replace("  const signature = JSON.stringify([value, inputTokens, requestedAt, serviceTier])", "  const requestedAt = requestedDate ? requestedDate + 'T' + (requestedTime || '00:00') : ''\n  const signature = JSON.stringify([value, inputTokens, requestedAt, serviceTier])")
          replace(/    <p className="inline-hint">\{t\('ai.catalog.form.pricingPreviewScope'\)\}<\/p>\n/)
          replace(/      <Field label=\{t\('ai.catalog.form.pricingPreviewTime'\)\}><TextInput id=\{timeId\} type="datetime-local" value=\{requestedAt\}\n        onChange=\{\(event\) => setRequestedAt\(event\.target\.value\)\} \/><\/Field>/, `      <Field label={locale === 'zh-CN' ? '请求日期（UTC）' : 'Request date (UTC)'}><DateInput id={dateId} value={requestedDate}\n        onChange={setRequestedDate} /></Field>\n      <Field label={locale === 'zh-CN' ? '请求时间（UTC）' : 'Request time (UTC)'}><TimeInput value={requestedTime} onChange={setRequestedTime} /></Field>`)
          replace('document.getElementById(timeId)?.focus()', 'document.getElementById(dateId)?.focus()')
          replace("placeholder={field === 'cacheWrite' ? t('ai.catalog.form.pricingNotSet') : configured ? required : unpriced}", "placeholder={field === 'cacheWrite' || !configured ? t('ai.catalog.form.pricingNotSet') : required}")
          replace("  const unpriced = t('ai.catalog.form.pricingUnpriced')\n")
          replace("      {!configured && <p className=\"inline-hint\">{t('ai.catalog.form.pricingNotConfigured')}</p>}\n")
          for (const group of ['inputTokenRanges', 'serviceTiers', 'timePeriods']) {
            replace(`      <button type="button" className="ghost-inline-btn" onClick={() => {\n        const draftKey = createPricingDraftKey()\n        updateRows('${group}'`, `      <Button variant="inline" onClick={() => {\n        const draftKey = createPricingDraftKey()\n        updateRows('${group}'`)
            const key = group === 'inputTokenRanges' ? 'addPricingRange' : group === 'serviceTiers' ? 'addPricingTier' : 'addPricingTime'
            replace(`>{t('ai.catalog.form.${key}')}</button>`, `><Plus aria-hidden="true" />{t('ai.catalog.form.${key}')}</Button>`)
          }
          replace("{t('ai.catalog.form.addPricingChange')}</Button>", "<Plus aria-hidden=\"true\" />{t('ai.catalog.form.addPricingChange')}</Button>")
          replace("{t('ai.catalog.form.pricingCreateDraft')}</Button>", "<Plus aria-hidden=\"true\" />{t('ai.catalog.form.pricingCreateDraft')}</Button>")
          break
        case 'AiModelGroupResourceForm.tsx':
          replace("import { FieldLabel } from '@/shared/ui/controls/FieldLabel'\n", "import { FieldLabel } from '@/shared/ui/controls/FieldLabel'\nimport { HelpHint } from '@/shared/ui/controls/HelpHint'\n")
          replace('<FieldLabel required>{t(\'ai.catalog.form.name\')}</FieldLabel>', `<FieldLabel required>{t('ai.catalog.form.name')}</FieldLabel> <HelpHint label={locale === 'zh-CN' ? '帮助：名称' : 'Help: name'}>{t('ai.catalog.form.groupNameHint')}</HelpHint>`)
          replace('        <span className="inline-hint">{t(\'ai.catalog.form.groupNameHint\')}</span>\n')
          replace('<FieldLabel>{t(\'ai.catalog.form.fallbackStatusCodes\')}</FieldLabel>', `<FieldLabel>{t('ai.catalog.form.fallbackStatusCodes')}</FieldLabel> <HelpHint label={locale === 'zh-CN' ? '帮助：立即降级状态码' : 'Help: immediate fallback status codes'}>{t('ai.catalog.form.fallbackStatusCodesHint')}</HelpHint>`)
          replace('        <span className="inline-hint">{t(\'ai.catalog.form.fallbackStatusCodesHint\')}</span>\n')
          replace('<FieldLabel>{t(\'ai.catalog.form.cooldownSeconds\')}</FieldLabel>', `<FieldLabel>{t('ai.catalog.form.cooldownSeconds')}</FieldLabel> <HelpHint label={locale === 'zh-CN' ? '帮助：冷却时间' : 'Help: cooldown'}>{t('ai.catalog.form.cooldownHint')}</HelpHint>`)
          replace('        <span className="inline-hint">{t(\'ai.catalog.form.cooldownHint\')}</span>\n')
          replace('  const { t } = useI18n()\n  const candidates = models.filter(', '  const { t, locale } = useI18n()\n  const candidates = models.filter(')
          break
        case 'PricingTtlInputs.tsx':
          replace("import { TextInput } from '@/shared/ui/controls/TextInput'\n", "import { TextInput } from '@/shared/ui/controls/TextInput'\nimport { IconButton } from '@/shared/ui/controls/IconButton'\nimport { Button } from '@/shared/ui/controls/Button'\nimport { Plus, X } from 'lucide-react'\n")
          replace(/            <button\n              type="button"\n              className="ghost-inline-btn"\n              aria-label=\{t\('ai.catalog.form.removePricingRule'\)\}[\s\S]*?\n            \{issue \?/, `            <IconButton label={t('ai.catalog.form.removePricingRule')} danger size="compact"
              onClick={() => onChange(rows.filter((candidate) => candidate.draftKey !== row.draftKey))}><X size={14} aria-hidden="true" /></IconButton>
            {issue ?`)
          replace(/      <button\n        type="button"\n        className="ghost-inline-btn"\n        onClick=\{\(\) => onChange\(\[\.\.\.rows, \{ draftKey: createPricingDraftKey\(\), seconds: '', rate: '' \}\]\)\}>\n        \{t\('ai.catalog.form.addPricingTtl'\)\}\n      <\/button>/, `      <Button variant="inline" onClick={() => onChange([...rows, { draftKey: createPricingDraftKey(), seconds: '', rate: '' }])}>
        <Plus aria-hidden="true" />{t('ai.catalog.form.addPricingTtl')}
      </Button>`)
          break
        default:
          return
      }
      return { code, map: null }
    },
  }
}
