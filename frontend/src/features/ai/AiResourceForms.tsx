import { Plus, Trash2 } from 'lucide-react'
import type { ReactNode } from 'react'
import type { AgentModelDTO, AgentProviderDTO } from '@/shared/api/contracts'
import type { AgentDraft, KeyValueDraft, ModelDraft, ProviderDraft, VariantDraft } from '@/features/ai/ai-console-types'
import { applyAgentModelSelection, variantOptionsFromDraft, variantOptionsFromModel } from '@/features/ai/ai-draft-normalizers'
import { emptyAgentDraft } from '@/features/ai/ai-console-utils'
import { providerTypes } from '@/features/ai/ai-console-types'

let editorIdSeed = 0

function nextEditorId(prefix: string): string {
  editorIdSeed += 1
  return `${prefix}-${editorIdSeed}`
}

function blankVariant(name = ''): VariantDraft {
  return {
    id: nextEditorId('variant-row'),
    name,
    temperature: '',
    maxOutputTokens: '',
    extras: [],
  }
}

export function ProviderForm({
  draft,
  onChange,
}: {
  draft: ProviderDraft
  onChange: (draft: ProviderDraft) => void
}) {
  return (
    <>
      <label className="form-group">
        <span>Name</span>
        <input value={draft.name} onChange={(event) => onChange({ ...draft, name: event.target.value })} placeholder="minimax" required />
      </label>
      <label className="form-group">
        <span>Description</span>
        <input value={draft.description} onChange={(event) => onChange({ ...draft, description: event.target.value })} placeholder="用途说明" />
      </label>
      <label className="form-group">
        <span>Provider Type</span>
        <select value={draft.providerType} onChange={(event) => onChange({ ...draft, providerType: event.target.value })} required>
          {providerTypes.map((providerType) => (
            <option key={providerType} value={providerType}>
              {providerType}
            </option>
          ))}
        </select>
      </label>
      <label className="form-group">
        <span>Base URL</span>
        <input value={draft.baseUrl} onChange={(event) => onChange({ ...draft, baseUrl: event.target.value })} placeholder="https://api.example.com/v1" />
      </label>
      <label className="form-group">
        <span>API Key</span>
        <input
          type="password"
          autoComplete="off"
          value={draft.apiKey}
          onChange={(event) => onChange({ ...draft, apiKey: event.target.value })}
          placeholder="sk-..."
        />
      </label>
      <label className="form-group">
        <span>Timeout Millis</span>
        <input value={draft.timeoutMillis} onChange={(event) => onChange({ ...draft, timeoutMillis: event.target.value })} placeholder="60000" inputMode="numeric" />
      </label>
      <label className="form-group">
        <span>Stream Idle Timeout Millis</span>
        <input
          value={draft.streamIdleTimeoutMillis}
          onChange={(event) => onChange({ ...draft, streamIdleTimeoutMillis: event.target.value })}
          placeholder="60000"
          inputMode="numeric"
        />
      </label>
    </>
  )
}

export function ModelForm({
  draft,
  mode,
  providers,
  onChange,
}: {
  draft: ModelDraft
  mode: 'create' | 'edit'
  providers: AgentProviderDTO[]
  onChange: (draft: ModelDraft) => void
}) {
  const variantOptions = variantOptionsFromDraft(draft.variants, draft.defaultVariant)
  const selectedDefaultVariant = variantOptions.includes(draft.defaultVariant.trim()) ? draft.defaultVariant.trim() : variantOptions[0]

  function commitVariants(nextVariants: VariantDraft[], preferredDefaultVariant?: string) {
    const nextOptions = variantOptionsFromDraft(nextVariants, preferredDefaultVariant || draft.defaultVariant)
    const preferred = preferredDefaultVariant?.trim() || draft.defaultVariant.trim()
    onChange({
      ...draft,
      variants: nextVariants,
      defaultVariant: preferred && nextOptions.includes(preferred) ? preferred : nextOptions[0],
    })
  }

  return (
    <>
      <label className="form-group">
        <span>Provider</span>
        <select value={draft.provider} onChange={(event) => onChange({ ...draft, provider: event.target.value })} required disabled={mode === 'edit'}>
          {providers.map((provider) => (
            <option key={provider.id} value={provider.name}>
              {provider.name}
            </option>
          ))}
        </select>
      </label>
      <label className="form-group">
        <span>Name</span>
        <input value={draft.name} onChange={(event) => onChange({ ...draft, name: event.target.value })} placeholder="MiniMax-M2.7" required />
      </label>
      <label className="form-group">
        <span>Description</span>
        <input value={draft.description} onChange={(event) => onChange({ ...draft, description: event.target.value })} placeholder="模型说明" />
      </label>
      <label className="form-group">
        <span>Default Variant</span>
        <select value={selectedDefaultVariant} onChange={(event) => onChange({ ...draft, defaultVariant: event.target.value })}>
          {variantOptions.map((variantName) => (
            <option key={variantName} value={variantName}>
              {variantName}
            </option>
          ))}
        </select>
      </label>

      <VariantListEditor
        label="Variants"
        variants={draft.variants}
        defaultVariant={selectedDefaultVariant}
        onChange={commitVariants}
        onResetDefault={() => commitVariants([blankVariant(selectedDefaultVariant || 'default')], selectedDefaultVariant)}
      />
      <CapabilitiesSection entries={draft.capabilities} onChange={(capabilities) => onChange({ ...draft, capabilities })} />
      <LimitsSection entries={draft.limits} onChange={(limits) => onChange({ ...draft, limits })} />
      <PricingSection entries={draft.pricing} onChange={(pricing) => onChange({ ...draft, pricing })} />
    </>
  )
}

export function AgentForm({
  draft,
  models,
  onChange,
}: {
  draft: AgentDraft
  models: AgentModelDTO[]
  onChange: (draft: AgentDraft) => void
}) {
  const selectedModel = models.find((model) => model.providerName === draft.defaultProvider && model.name === draft.defaultModel)
  const variantOptions = variantOptionsFromModel(selectedModel)
  const selectedDefaultVariant = variantOptions.includes(draft.defaultVariant.trim()) ? draft.defaultVariant.trim() : variantOptions[0]

  return (
    <>
      <label className="form-group">
        <span>Name</span>
        <input value={draft.name} onChange={(event) => onChange({ ...draft, name: event.target.value })} placeholder="default-assistant" required />
      </label>
      <label className="form-group">
        <span>Description</span>
        <input value={draft.description} onChange={(event) => onChange({ ...draft, description: event.target.value })} placeholder="用途说明" />
      </label>
      <label className="form-group">
        <span>Model</span>
        <select value={`${draft.defaultProvider}/${draft.defaultModel}`} onChange={(event) => onChange(applyAgentModelSelection(draft, event.target.value, models))} required>
          {models.map((model) => (
            <option key={`${model.providerName}/${model.name}`} value={`${model.providerName}/${model.name}`}>
              {model.name} ({model.providerName})
            </option>
          ))}
        </select>
      </label>
      <label className="form-group">
        <span>Default Variant</span>
        <select value={selectedDefaultVariant} onChange={(event) => onChange({ ...draft, defaultVariant: event.target.value })} disabled={models.length === 0}>
          {variantOptions.map((variantName) => (
            <option key={variantName} value={variantName}>
              {variantName}
            </option>
          ))}
        </select>
      </label>
      <label className="form-group">
        <span>System Prompt</span>
        <textarea value={draft.systemPrompt} onChange={(event) => onChange({ ...draft, systemPrompt: event.target.value })} placeholder="系统提示词" rows={4} />
      </label>

      <StringListEditor label="Tools" items={draft.tools} onChange={(tools) => onChange({ ...draft, tools })} itemPlaceholder="tool name" />
      <StringListEditor label="Subagents" items={draft.subagents} onChange={(subagents) => onChange({ ...draft, subagents })} itemPlaceholder="subagent name" />
      <StringListEditor label="Skills" items={draft.skills} onChange={(skills) => onChange({ ...draft, skills })} itemPlaceholder="skill name" />

      {models.length === 0 && (
        <div className="inline-hint" role="status">
          需要先创建 Model 才能配置 Agent。
        </div>
      )}
      {models.length > 0 && !draft.defaultModel && (
        <button className="ghost-inline-btn" type="button" onClick={() => onChange(emptyAgentDraft(models[0]))}>
          使用第一个 Model 填充默认配置
        </button>
      )}
    </>
  )
}

function CapabilitiesSection({
  entries,
  onChange,
}: {
  entries: KeyValueDraft[]
  onChange: (entries: KeyValueDraft[]) => void
}) {
  return (
    <StructuredSection label="Capabilities">
      <div className="metadata-grid metadata-grid-two">
        <label className="checkbox-field">
          <input
            type="checkbox"
            checked={readBooleanMetadataEntry(entries, 'tools')}
            onChange={(event) => onChange(writeBooleanMetadataEntry(entries, 'tools', event.target.checked))}
          />
          <span>Tools</span>
        </label>
        <label className="form-group">
          <span>Input Modalities</span>
          <input
            value={readMetadataEntry(entries, 'input')}
            onChange={(event) => onChange(writeMetadataEntry(entries, 'input', event.target.value))}
            placeholder="text, image"
          />
        </label>
        <label className="form-group">
          <span>Output Modalities</span>
          <input
            value={readMetadataEntry(entries, 'output')}
            onChange={(event) => onChange(writeMetadataEntry(entries, 'output', event.target.value))}
            placeholder="text"
          />
        </label>
      </div>
    </StructuredSection>
  )
}

function LimitsSection({
  entries,
  onChange,
}: {
  entries: KeyValueDraft[]
  onChange: (entries: KeyValueDraft[]) => void
}) {
  return (
    <StructuredSection label="Limits">
      <div className="metadata-grid metadata-grid-three">
        <label className="form-group">
          <span>Context Window</span>
          <input
            value={readMetadataEntry(entries, 'context')}
            onChange={(event) => onChange(writeMetadataEntry(entries, 'context', event.target.value))}
            placeholder="1000000"
            inputMode="numeric"
          />
        </label>
        <label className="form-group">
          <span>Max Input</span>
          <input
            value={readMetadataEntry(entries, 'input')}
            onChange={(event) => onChange(writeMetadataEntry(entries, 'input', event.target.value))}
            placeholder="1000000"
            inputMode="numeric"
          />
        </label>
        <label className="form-group">
          <span>Max Output</span>
          <input
            value={readMetadataEntry(entries, 'output')}
            onChange={(event) => onChange(writeMetadataEntry(entries, 'output', event.target.value))}
            placeholder="1000000"
            inputMode="numeric"
          />
        </label>
      </div>
    </StructuredSection>
  )
}

function PricingSection({
  entries,
  onChange,
}: {
  entries: KeyValueDraft[]
  onChange: (entries: KeyValueDraft[]) => void
}) {
  return (
    <StructuredSection label="Pricing">
      <div className="metadata-grid metadata-grid-two">
        <label className="form-group">
          <span>Input</span>
          <input
            value={readMetadataEntry(entries, 'input')}
            onChange={(event) => onChange(writeMetadataEntry(entries, 'input', event.target.value))}
            placeholder="0.0012"
            inputMode="decimal"
          />
        </label>
        <label className="form-group">
          <span>Output</span>
          <input
            value={readMetadataEntry(entries, 'output')}
            onChange={(event) => onChange(writeMetadataEntry(entries, 'output', event.target.value))}
            placeholder="0.0012"
            inputMode="decimal"
          />
        </label>
        <label className="form-group">
          <span>Cache Read</span>
          <input
            value={readMetadataEntry(entries, 'cacheRead')}
            onChange={(event) => onChange(writeMetadataEntry(entries, 'cacheRead', event.target.value))}
            placeholder="0.0003"
            inputMode="decimal"
          />
        </label>
        <label className="form-group">
          <span>Cache Write</span>
          <input
            value={readMetadataEntry(entries, 'cacheWrite')}
            onChange={(event) => onChange(writeMetadataEntry(entries, 'cacheWrite', event.target.value))}
            placeholder="0.0016"
            inputMode="decimal"
          />
        </label>
      </div>
    </StructuredSection>
  )
}

function findMetadataEntryIndex(entries: KeyValueDraft[], key: string): number {
  return entries.findIndex((entry) => entry.key.trim() === key)
}

function readMetadataEntry(entries: KeyValueDraft[], key: string): string {
  const entry = entries.find((candidate) => candidate.key.trim() === key)
  return entry?.value ?? ''
}

function readBooleanMetadataEntry(entries: KeyValueDraft[], key: string): boolean {
  return readMetadataEntry(entries, key).trim() === 'true'
}

function writeMetadataEntry(entries: KeyValueDraft[], key: string, value: string): KeyValueDraft[] {
  const index = findMetadataEntryIndex(entries, key)
  if (!value.trim()) {
    return index < 0 ? entries : entries.filter((_, entryIndex) => entryIndex !== index)
  }

  if (index < 0) {
    return [...entries, { id: nextEditorId('kv-row'), key, value }]
  }

  return entries.map((entry, entryIndex) => (entryIndex === index ? { ...entry, key, value } : entry))
}

function writeBooleanMetadataEntry(entries: KeyValueDraft[], key: string, checked: boolean): KeyValueDraft[] {
  const value = checked ? 'true' : 'false'
  const index = findMetadataEntryIndex(entries, key)
  if (index < 0) {
    return [...entries, { id: nextEditorId('kv-row'), key, value }]
  }
  return entries.map((entry, entryIndex) => (entryIndex === index ? { ...entry, key, value } : entry))
}

function KeyValueEditor({
  label,
  entries,
  onChange,
  valuePlaceholder,
}: {
  label: string
  entries: KeyValueDraft[]
  onChange: (entries: KeyValueDraft[]) => void
  valuePlaceholder?: string
}) {
  function updateEntry(index: number, patch: Partial<KeyValueDraft>) {
    onChange(entries.map((entry, entryIndex) => (entryIndex === index ? { ...entry, ...patch } : entry)))
  }

  function addEntry() {
    onChange([...entries, { id: nextEditorId('kv-row'), key: '', value: '' }])
  }

  function removeEntry(index: number) {
    onChange(entries.filter((_, entryIndex) => entryIndex !== index))
  }

  return (
    <StructuredSection
      label={label}
      actions={
        <button className="ghost-inline-btn" type="button" onClick={addEntry}>
          <Plus aria-hidden="true" />
          添加
        </button>
      }
    >
      {entries.length === 0 && <p className="inline-hint">当前没有字段，按需添加 key / value。</p>}
      <div className="editor-stack">
        {entries.map((entry, index) => (
          <div className="editor-row" key={entry.id}>
            <input
              className="editor-input"
              value={entry.key}
              onChange={(event) => updateEntry(index, { key: event.target.value })}
              placeholder="key"
              aria-label={`${label} key ${index + 1}`}
            />
            <input
              className="editor-input"
              value={entry.value}
              onChange={(event) => updateEntry(index, { value: event.target.value })}
              placeholder={valuePlaceholder || 'value'}
              aria-label={`${label} value ${index + 1}`}
            />
            <button className="icon-action-btn" type="button" aria-label={`删除 ${label} 字段 ${index + 1}`} onClick={() => removeEntry(index)}>
              <Trash2 aria-hidden="true" />
            </button>
          </div>
        ))}
      </div>
    </StructuredSection>
  )
}

function StringListEditor({
  label,
  items,
  onChange,
  itemPlaceholder,
}: {
  label: string
  items: string[]
  onChange: (items: string[]) => void
  itemPlaceholder: string
}) {
  function updateItem(index: number, value: string) {
    onChange(items.map((item, itemIndex) => (itemIndex === index ? value : item)))
  }

  function addItem() {
    onChange([...items, ''])
  }

  function removeItem(index: number) {
    onChange(items.filter((_, itemIndex) => itemIndex !== index))
  }

  return (
    <StructuredSection
      label={label}
      actions={
        <button className="ghost-inline-btn" type="button" onClick={addItem}>
          <Plus aria-hidden="true" />
          添加
        </button>
      }
    >
      {items.length === 0 && <p className="inline-hint">当前为空，按需添加条目。</p>}
      <div className="editor-stack">
        {items.map((item, index) => (
          <div className="editor-row single" key={`${label}-${index}`}>
            <input
              className="editor-input"
              value={item}
              onChange={(event) => updateItem(index, event.target.value)}
              placeholder={itemPlaceholder}
              aria-label={`${label} ${index + 1}`}
            />
            <button className="icon-action-btn" type="button" aria-label={`删除 ${label} ${index + 1}`} onClick={() => removeItem(index)}>
              <Trash2 aria-hidden="true" />
            </button>
          </div>
        ))}
      </div>
    </StructuredSection>
  )
}

function VariantListEditor({
  label,
  variants,
  defaultVariant,
  onChange,
  onResetDefault,
}: {
  label: string
  variants: VariantDraft[]
  defaultVariant: string
  onChange: (variants: VariantDraft[], preferredDefaultVariant?: string) => void
  onResetDefault: () => void
}) {
  function updateVariant(index: number, patch: Partial<VariantDraft>) {
    const currentVariant = variants[index]
    const nextVariants = variants.map((variant, variantIndex) => (variantIndex === index ? { ...variant, ...patch } : variant))
    const preferredDefaultVariant =
      patch.name !== undefined && currentVariant?.name.trim() === defaultVariant.trim() ? patch.name.trim() || undefined : undefined
    onChange(nextVariants, preferredDefaultVariant)
  }

  function updateVariantExtras(index: number, extras: KeyValueDraft[]) {
    updateVariant(index, { extras })
  }

  function addVariant() {
    onChange([...variants, blankVariant()])
  }

  function removeVariant(index: number) {
    onChange(variants.filter((_, variantIndex) => variantIndex !== index))
  }

  return (
    <StructuredSection
      label={label}
      actions={
        <div className="structured-section-actions">
          <button className="ghost-inline-btn" type="button" onClick={addVariant}>
            <Plus aria-hidden="true" />
            添加 Variant
          </button>
          <button className="ghost-inline-btn" type="button" onClick={onResetDefault}>
            重置默认
          </button>
        </div>
      }
    >
      <div className="variant-stack">
        {variants.map((variant, index) => (
          <div className="variant-editor" key={variant.id}>
            <div className="editor-grid">
              <label className="form-group">
                <span>Name</span>
                <input value={variant.name} onChange={(event) => updateVariant(index, { name: event.target.value })} placeholder="default" />
              </label>
              <label className="form-group">
                <span>Temperature</span>
                <input
                  value={variant.temperature}
                  onChange={(event) => updateVariant(index, { temperature: event.target.value })}
                  placeholder="0.1"
                  inputMode="decimal"
                />
              </label>
              <label className="form-group">
                <span>Max Output Tokens</span>
                <input
                  value={variant.maxOutputTokens}
                  onChange={(event) => updateVariant(index, { maxOutputTokens: event.target.value })}
                  placeholder="256"
                  inputMode="numeric"
                />
              </label>
            </div>
            <KeyValueEditor
              label={`Variant Extras ${index + 1}`}
              entries={variant.extras}
              onChange={(extras) => updateVariantExtras(index, extras)}
              valuePlaceholder="value"
            />
            <button className="ghost-inline-btn danger" type="button" onClick={() => removeVariant(index)}>
              <Trash2 aria-hidden="true" />
              删除 Variant
            </button>
          </div>
        ))}
      </div>
    </StructuredSection>
  )
}

function StructuredSection({
  label,
  actions,
  children,
}: {
  label: string
  actions?: ReactNode
  children: ReactNode
}) {
  return (
    <section className="structured-section" aria-label={label}>
      <div className="structured-section-head">
        <strong>{label}</strong>
        {actions}
      </div>
      {children}
    </section>
  )
}
