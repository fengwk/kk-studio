import { useId } from 'react'
import { FieldError } from '@/shared/ui/controls/FieldError'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { Select } from '@/shared/ui/controls/Select'
import { Checkbox } from '@/shared/ui/controls/Checkbox'
import { TagInput } from '@/shared/ui/controls/TagInput'
import { useFieldErrorId } from '@/shared/ui/controls/field-error'
import type { ProviderType } from '@/shared/api/generated/contracts'
import { PROVIDER_TYPE_VALUES } from '@/shared/api/generated/contracts'
import type { ProviderDraft } from '@/features/catalog/ai-console-types'
import type { ResourceFieldKey } from '@/features/catalog/ai-resource-form-validation'
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
  const isCustomRetry = draft.modelHttpRetryStatusCodes !== null && draft.modelHttpRetryStatusCodes !== undefined
  const canClearCredential = mode === 'edit' && draft.credentialConfigured
  const credentialId = useId()
  const nameId = useId()
  const nameErrorId = useFieldErrorId(fieldErrors.name)
  const baseUrlId = useId()
  const baseUrlErrorId = useFieldErrorId(fieldErrors.baseUrl)
  return (
    <>
      <div className={`form-group${fieldErrors.name ? ' is-error' : ''}`}>
        <FieldLabel required htmlFor={nameId}>{t('ai.catalog.form.name')}</FieldLabel>
        <TextInput
          id={nameId}
          invalid={Boolean(fieldErrors.name)}
          aria-describedby={nameErrorId}
          value={draft.name}
          onChange={(event) => onChange({ ...draft, name: event.target.value })}
          placeholder="minimax"
          readOnly={mode === 'edit'}
          required
        />
        {fieldErrors.name ? <FieldError id={nameErrorId}>{fieldErrors.name}</FieldError> : null}
      </div>
      <div className="form-group">
        <FieldLabel htmlFor={credentialId} help={t(mode === 'edit' ? 'ai.catalog.form.credentialEditHint' : 'ai.catalog.form.credentialCreateHint')}>
          {t('ai.catalog.form.apiKeyOptional')}
        </FieldLabel>
        <TextInput
          id={credentialId}
          type="password"
          autoComplete="off"
          value={draft.credential}
          disabled={draft.clearCredential}
          onChange={(event) => onChange({ ...draft, credential: event.target.value })}
          placeholder={
            draft.clearCredential
              ? t('ai.catalog.form.credentialCleared')
              : mode === 'edit'
                ? t('ai.catalog.form.keepCredential')
                : t('ai.catalog.form.optionalCredential')
          }
        />
      </div>
      {canClearCredential ? (
        <div className="form-group">
          <Checkbox
            checked={draft.clearCredential}
            label={t('ai.catalog.form.clearCredential')}
            onChange={(checked) =>
              onChange({
                ...draft,
                clearCredential: checked,
                credential: checked ? '' : draft.credential,
              })
            }
          />
        </div>
      ) : null}
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
        {mode === 'edit' ? (
          <TextInput
            value={draft.providerType}
            aria-label={t('ai.catalog.form.providerType')}
            readOnly
          />
        ) : (
          <Select
            aria-label={t('ai.catalog.form.providerType')}
            value={draft.providerType}
            required
            options={PROVIDER_TYPE_VALUES.map((providerType) => ({ value: providerType, label: providerType }))}
            onChange={(providerType) =>
              onChange({ ...draft, providerType: providerType as ProviderType })
            }
          />
        )}
      </label>
      <div className={`form-group${fieldErrors.baseUrl ? ' is-error' : ''}`}>
        <FieldLabel htmlFor={baseUrlId}>{t('ai.catalog.form.baseUrl')}</FieldLabel>
        <TextInput
          id={baseUrlId}
          invalid={Boolean(fieldErrors.baseUrl)}
          aria-describedby={baseUrlErrorId}
          value={draft.baseUrl}
          onChange={(event) => onChange({ ...draft, baseUrl: event.target.value })}
          placeholder="https://api.example.invalid/v1"
        />
        {fieldErrors.baseUrl ? <FieldError id={baseUrlErrorId}>{fieldErrors.baseUrl}</FieldError> : null}
      </div>
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
      <div className="form-group">
        <FieldLabel>{t('ai.catalog.form.httpRetryPolicy')}</FieldLabel>
        <Select
          aria-label={t('ai.catalog.form.httpRetryPolicy')}
          value={isCustomRetry ? 'custom' : 'inherit'}
          options={[
            { value: 'inherit', label: t('ai.catalog.form.httpRetryInherit') },
            { value: 'custom', label: t('ai.catalog.form.httpRetryCustom') },
          ]}
          onChange={(value) => {
            if (value === 'inherit') {
              onChange({ ...draft, modelHttpRetryStatusCodes: null })
            } else {
              onChange({
                ...draft,
                modelHttpRetryStatusCodes: draft.modelHttpRetryStatusCodes ?? [],
              })
            }
          }}
        />
        {isCustomRetry ? (
          <TagInput
            value={draft.modelHttpRetryStatusCodes ?? []}
            onChange={(next) => onChange({ ...draft, modelHttpRetryStatusCodes: next })}
            min={400}
            max={599}
            placeholder={t('ai.catalog.form.httpRetryPlaceholder')}
            ariaLabel={t('ai.catalog.form.httpRetryCustom')}
          />
        ) : null}
      </div>
    </>
  )
}
