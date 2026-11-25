import { useId } from 'react'
import type { ModelDraft } from '@/features/catalog/ai-console-types'
import type { ResourceFieldKey } from '@/features/catalog/ai-resource-form-validation'
import { Checkbox } from '@/shared/ui/controls/Checkbox'
import { FieldError } from '@/shared/ui/controls/FieldError'
import { NumberInput } from '@/shared/ui/controls/NumberInput'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { useFieldErrorId } from '@/shared/ui/controls/field-error'
import { useI18n } from '@/shared/i18n'
import type { ModelInputModality } from '@/shared/api/generated/contracts'
import { MODEL_INPUT_MODALITY_VALUES } from '@/shared/api/generated/contracts'
import { toggleInputModality } from './ai-model-form-rules'

/** 上下文窗口 / 输入上限 / 最大输出长度；三者都保持字符串型草稿。 */
export function ModelLimitFields({
  draft,
  fieldErrors,
  onChange,
}: {
  draft: ModelDraft
  fieldErrors: Partial<Record<ResourceFieldKey, string>>
  onChange: (draft: ModelDraft) => void
}) {
  const { t } = useI18n()
  const contextWindowId = useId()
  const inputLimitId = useId()
  const maxOutputTokensId = useId()
  const contextWindowErrorId = useFieldErrorId(fieldErrors.contextWindow)
  const inputLimitErrorId = useFieldErrorId(fieldErrors.inputLimit)
  const maxOutputTokensErrorId = useFieldErrorId(fieldErrors.maxOutputTokens)
  return (
    <section className="structured-section" aria-labelledby="model-limit-heading">
      <div className="structured-section-head">
        <h3 id="model-limit-heading">{t('ai.catalog.form.limit')}</h3>
      </div>
      <div className="form-grid-2">
        <div className={`form-group${fieldErrors.contextWindow ? ' is-error' : ''}`}>
          <FieldLabel required htmlFor={contextWindowId}>{t('ai.catalog.form.contextWindow')}</FieldLabel>
          <NumberInput
            id={contextWindowId}
            value={draft.contextWindow}
            min={1}
            placeholder="128000"
            onChange={(contextWindow) => onChange({ ...draft, contextWindow })}
            aria-label={t('ai.catalog.form.contextWindow')}
            aria-describedby={contextWindowErrorId}
            invalid={Boolean(fieldErrors.contextWindow)}
          />
          {fieldErrors.contextWindow ? (
            <FieldError id={contextWindowErrorId}>{fieldErrors.contextWindow}</FieldError>
          ) : null}
        </div>
        <div className={`form-group${fieldErrors.inputLimit ? ' is-error' : ''}`}>
          <FieldLabel htmlFor={inputLimitId} help={t('ai.catalog.form.inputLimitHelp')}>{t('ai.catalog.form.inputLimit')}</FieldLabel>
          <NumberInput
            id={inputLimitId}
            value={draft.inputLimit}
            min={1}
            placeholder={t('ai.catalog.form.inputLimitPlaceholder')}
            onChange={(inputLimit) => onChange({ ...draft, inputLimit })}
            aria-label={t('ai.catalog.form.inputLimit')}
            aria-describedby={inputLimitErrorId}
            invalid={Boolean(fieldErrors.inputLimit)}
          />
          {fieldErrors.inputLimit ? (
            <FieldError id={inputLimitErrorId}>{fieldErrors.inputLimit}</FieldError>
          ) : null}
        </div>
        <div className={`form-group${fieldErrors.maxOutputTokens ? ' is-error' : ''}`}>
          <FieldLabel required htmlFor={maxOutputTokensId} help={t('ai.catalog.form.maxOutputLimitHint')}>{t('ai.catalog.form.maxOutputTokens')}</FieldLabel>
          <NumberInput
            id={maxOutputTokensId}
            value={draft.maxOutputTokens}
            min={1}
            placeholder="8192"
            onChange={(maxOutputTokens) => onChange({ ...draft, maxOutputTokens })}
            aria-label={t('ai.catalog.form.maxOutputTokens')}
            aria-describedby={maxOutputTokensErrorId}
            invalid={Boolean(fieldErrors.maxOutputTokens)}
          />
          {fieldErrors.maxOutputTokens ? (
            <FieldError id={maxOutputTokensErrorId}>{fieldErrors.maxOutputTokens}</FieldError>
          ) : null}
        </div>
      </div>
    </section>
  )
}

/** 能力开关：只切换布尔位，不隐式改动其它字段。 */
export function ModelAbilityToggles({
  draft,
  onChange,
}: {
  draft: ModelDraft
  onChange: (draft: ModelDraft) => void
}) {
  const { t } = useI18n()
  return (
    <section className="structured-section" aria-labelledby="model-abilities-heading">
      <div className="structured-section-head">
        <h3 id="model-abilities-heading">{t('ai.catalog.form.abilities')}</h3>
      </div>
      <div className="ability-toggle-row">
        <Checkbox
          checked={draft.tools}
          onChange={(tools) => onChange({ ...draft, tools })}
          label={t('ai.catalog.form.tools')}
        />
        {/* Reasoning 只切换能力开关；空思考强度表示不覆盖协议默认，不自动补全。 */}
        <Checkbox
          checked={draft.reasoning}
          onChange={(reasoning) => onChange({ ...draft, reasoning })}
          label={t('ai.catalog.form.reasoning')}
        />
        <Checkbox
          checked={draft.midConversationSystemMessages}
          onChange={(midConversationSystemMessages) =>
            onChange({ ...draft, midConversationSystemMessages })
          }
          label={t('ai.catalog.form.midConversationSystemMessages')}
        />
        <Checkbox
          checked={draft.messageLevelEffort}
          onChange={(messageLevelEffort) => onChange({ ...draft, messageLevelEffort })}
          label={t('ai.catalog.form.messageLevelEffort')}
        />
      </div>
    </section>
  )
}

export function ModelInputModalityField({
  draft,
  error,
  onChange,
}: {
  draft: ModelDraft
  error?: string
  onChange: (draft: ModelDraft) => void
}) {
  const { t } = useI18n()
  const headingId = useId()
  const errorId = useFieldErrorId(error)
  function toggle(value: ModelInputModality) {
    onChange({ ...draft, inputModalities: toggleInputModality(draft.inputModalities, value) })
  }
  return (
    <section className={`structured-section${error ? ' is-error' : ''}`} role="group" aria-labelledby={headingId} aria-describedby={errorId}>
      <div className="structured-section-head"><h3 id={headingId}>{t('ai.catalog.form.inputTypes')}</h3></div>
      <div className="ability-toggle-row">
        {MODEL_INPUT_MODALITY_VALUES.map((item) => {
          const checked = draft.inputModalities.includes(item)
          return (
            <Checkbox
              key={item}
              checked={checked}
              onChange={() => toggle(item)}
            >
              <span>{item}</span>
            </Checkbox>
          )
        })}
      </div>
      {error ? <FieldError id={errorId}>{error}</FieldError> : null}
    </section>
  )
}
