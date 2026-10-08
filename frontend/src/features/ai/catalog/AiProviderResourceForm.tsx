import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { Select } from '@/shared/ui/controls/Select'
import { providerTypes } from '@/features/ai/catalog/ai-console-types'
import type { AgentProviderType } from '@/shared/api/contracts/ai-catalog'
import type { ProviderDraft } from '@/features/ai/catalog/ai-console-types'
import type { ResourceFieldKey } from '@/features/ai/catalog/ai-resource-form-validation'
import { useI18n } from '@/shared/i18n'

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
  const { t } = useI18n()
  return (
    <>
      <label className={`form-group${fieldErrors.name ? ' is-error' : ''}`}>
        <FieldLabel required>{t('ai.catalog.form.name')}</FieldLabel>
        <TextInput
          invalid={Boolean(fieldErrors.name)}
          value={draft.name}
          onChange={(event) => onChange({ ...draft, name: event.target.value })}
          placeholder="minimax"
          readOnly={mode === 'edit'}
          required
        />
        {fieldErrors.name ? <span className="field-error">{fieldErrors.name}</span> : null}
      </label>
      <label className="form-group">
        <FieldLabel>{t('ai.catalog.form.apiKeyOptional')}</FieldLabel>
        <TextInput
          type="password"
          autoComplete="off"
          value={draft.credential}
          onChange={(event) => onChange({ ...draft, credential: event.target.value })}
          placeholder={
            mode === 'edit'
              ? t('ai.catalog.form.keepCredential')
              : t('ai.catalog.form.optionalCredential')
          }
        />
        <small>
          {mode === 'edit'
            ? t('ai.catalog.form.credentialEditHint')
            : t('ai.catalog.form.credentialCreateHint')}
        </small>
      </label>
      <label className="form-group">
        <FieldLabel>{t('ai.catalog.form.description')}</FieldLabel>
        <TextInput
          value={draft.description}
          onChange={(event) => onChange({ ...draft, description: event.target.value })}
          placeholder={t('ai.catalog.form.descriptionPlaceholder')}
        />
      </label>
      <label className="form-group">
        <FieldLabel required>{t('ai.catalog.form.providerType')}</FieldLabel>
        <Select
          aria-label={t('ai.catalog.form.providerType')}
          value={draft.providerType}
          required
          options={providerTypes.map((providerType) => ({ value: providerType, label: providerType }))}
          onChange={(providerType) =>
            onChange({ ...draft, providerType: providerType as AgentProviderType })
          }
        />
      </label>
      <label className={`form-group${fieldErrors.baseUrl ? ' is-error' : ''}`}>
        <FieldLabel>{t('ai.catalog.form.baseUrl')}</FieldLabel>
        <TextInput invalid={Boolean(fieldErrors.baseUrl)} value={draft.baseUrl} onChange={(event) => onChange({ ...draft, baseUrl: event.target.value })} placeholder="https://api.example.com/v1" />
        {fieldErrors.baseUrl ? <span className="field-error">{fieldErrors.baseUrl}</span> : null}
      </label>
      <label className="form-group">
        <FieldLabel>{t('ai.catalog.form.modelCallTimeout')}</FieldLabel>
        <TextInput
          value={draft.modelCallTimeoutMillis}
          onChange={(event) => onChange({ ...draft, modelCallTimeoutMillis: event.target.value })}
          placeholder="1800000"
          inputMode="numeric"
        />
      </label>
      <label className="form-group">
        <FieldLabel>{t('ai.catalog.form.modelCallIdleTimeout')}</FieldLabel>
        <TextInput
          value={draft.modelCallIdleTimeoutMillis}
          onChange={(event) => onChange({ ...draft, modelCallIdleTimeoutMillis: event.target.value })}
          placeholder="120000"
          inputMode="numeric"
        />
      </label>
    </>
  )
}
