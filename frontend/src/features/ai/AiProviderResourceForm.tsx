import { FieldLabel } from '@/features/ai/FieldLabel'
import { FormSelect } from '@/features/ai/FormSelect'
import { providerTypes } from '@/features/ai/ai-console-types'
import type { ProviderDraft } from '@/features/ai/ai-console-types'
import type { ResourceFieldKey } from '@/features/ai/ai-resource-form-validation'

export function ProviderForm({
  draft,
  mode = 'create',
  fieldErrors = {},
  onChange,
}: {
  draft: ProviderDraft
  mode?: 'create' | 'edit'
  fieldErrors?: Partial<Record<ResourceFieldKey, string>>
  onChange: (draft: ProviderDraft) => void
}) {
  return (
    <>
      <label className={`form-group${fieldErrors.name ? ' is-error' : ''}`}>
        <FieldLabel required>Name</FieldLabel>
        <input value={draft.name} onChange={(event) => onChange({ ...draft, name: event.target.value })} placeholder="minimax" required />
        {fieldErrors.name ? <span className="field-error">{fieldErrors.name}</span> : null}
      </label>
      <label className="form-group">
        <FieldLabel>API Key（可选）</FieldLabel>
        <input
          type="password"
          autoComplete="off"
          value={draft.credential}
          onChange={(event) => onChange({ ...draft, credential: event.target.value })}
          placeholder={mode === 'edit' ? '留空保留当前密钥' : '可留空'}
        />
        <small>
          {mode === 'edit'
            ? '留空会保留已配置的 API Key；密钥不会回显。'
            : '留空会以无 Authorization 方式请求 OpenAI-compatible 端点。'}
        </small>
      </label>
      <label className="form-group">
        <FieldLabel>Description</FieldLabel>
        <input value={draft.description} onChange={(event) => onChange({ ...draft, description: event.target.value })} placeholder="用途说明" />
      </label>
      <label className="form-group">
        <FieldLabel required>Provider Type</FieldLabel>
        <FormSelect
          aria-label="Provider Type"
          value={draft.providerType}
          required
          options={providerTypes.map((providerType) => ({ value: providerType, label: providerType }))}
          onChange={(providerType) => onChange({ ...draft, providerType })}
        />
      </label>
      <label className={`form-group${fieldErrors.baseUrl ? ' is-error' : ''}`}>
        <FieldLabel>Base URL</FieldLabel>
        <input value={draft.baseUrl} onChange={(event) => onChange({ ...draft, baseUrl: event.target.value })} placeholder="https://api.example.com/v1" />
        {fieldErrors.baseUrl ? <span className="field-error">{fieldErrors.baseUrl}</span> : null}
      </label>
      <label className="form-group">
        <FieldLabel>Model Call Timeout (ms)</FieldLabel>
        <input
          value={draft.modelCallTimeoutMillis}
          onChange={(event) => onChange({ ...draft, modelCallTimeoutMillis: event.target.value })}
          placeholder="1800000"
          inputMode="numeric"
        />
      </label>
      <label className="form-group">
        <FieldLabel>Model Call Idle Timeout (ms)</FieldLabel>
        <input
          value={draft.modelCallIdleTimeoutMillis}
          onChange={(event) => onChange({ ...draft, modelCallIdleTimeoutMillis: event.target.value })}
          placeholder="120000"
          inputMode="numeric"
        />
      </label>
    </>
  )
}
