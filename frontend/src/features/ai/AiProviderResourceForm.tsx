import { FormSelect } from '@/features/ai/FormSelect'
import { providerTypes } from '@/features/ai/ai-console-types'
import type { ProviderDraft } from '@/features/ai/ai-console-types'
import type { ResourceFieldKey } from '@/features/ai/ai-resource-form-validation'

export function ProviderForm({
  draft,
  fieldErrors = {},
  onChange,
}: {
  draft: ProviderDraft
  fieldErrors?: Partial<Record<ResourceFieldKey, string>>
  onChange: (draft: ProviderDraft) => void
}) {
  return (
    <>
      <label className={`form-group${fieldErrors.name ? ' is-error' : ''}`}>
        <span>Name</span>
        <input value={draft.name} onChange={(event) => onChange({ ...draft, name: event.target.value })} placeholder="minimax" required />
        {fieldErrors.name ? <span className="field-error">{fieldErrors.name}</span> : null}
      </label>
      <label className="form-group">
        <span>Description</span>
        <input value={draft.description} onChange={(event) => onChange({ ...draft, description: event.target.value })} placeholder="用途说明" />
      </label>
      <label className="form-group">
        <span>Provider Type</span>
        <FormSelect
          aria-label="Provider Type"
          value={draft.providerType}
          required
          options={providerTypes.map((providerType) => ({ value: providerType, label: providerType }))}
          onChange={(providerType) => onChange({ ...draft, providerType })}
        />
      </label>
      <label className={`form-group${fieldErrors.baseUrl ? ' is-error' : ''}`}>
        <span>Base URL</span>
        <input value={draft.baseUrl} onChange={(event) => onChange({ ...draft, baseUrl: event.target.value })} placeholder="https://api.example.com/v1" />
        {fieldErrors.baseUrl ? <span className="field-error">{fieldErrors.baseUrl}</span> : null}
      </label>
      <label className="form-group">
        <span>API Key</span>
        <input
          type="password"
          autoComplete="off"
          value={draft.credential}
          onChange={(event) => onChange({ ...draft, credential: event.target.value })}
          placeholder="sk-..."
        />
      </label>
      <label className="form-group">
        <span>Model Call Timeout (ms)</span>
        <input
          value={draft.modelCallTimeoutMillis}
          onChange={(event) => onChange({ ...draft, modelCallTimeoutMillis: event.target.value })}
          placeholder="1800000"
          inputMode="numeric"
        />
      </label>
      <label className="form-group">
        <span>Model Call Idle Timeout (ms)</span>
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
