import { providerTypes } from '@/features/ai/ai-console-types'
import type { ProviderDraft } from '@/features/ai/ai-console-types'

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
    </>
  )
}
