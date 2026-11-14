import type { ModelDraft } from '@/features/catalog/ai-console-types'
import type { ResourceFieldKey } from '@/features/catalog/ai-resource-form-validation'
import { Checkbox } from '@/shared/ui/controls/Checkbox'
import { NumberInput } from '@/shared/ui/controls/NumberInput'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { useI18n } from '@/shared/i18n'
import type { ModelInputModality } from '@/shared/api/generated/contracts'
import { AGENT_MODEL_INPUT_MODALITIES, toggleInputModality } from './ai-model-form-rules'

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
  return (
    <section className="structured-section" aria-labelledby="model-limit-heading">
      <div className="structured-section-head">
        <h3 id="model-limit-heading">{t('ai.catalog.form.limit')}</h3>
      </div>
      <div className="form-grid-2">
        <label className={`form-group${fieldErrors.contextWindow ? ' is-error' : ''}`}>
          <FieldLabel required>{t('ai.catalog.form.contextWindow')}</FieldLabel>
          <NumberInput
            value={draft.contextWindow}
            min={1}
            placeholder="128000"
            onChange={(contextWindow) => onChange({ ...draft, contextWindow })}
            aria-label={t('ai.catalog.form.contextWindow')}
          />
          {fieldErrors.contextWindow ? (
            <span className="field-error">{fieldErrors.contextWindow}</span>
          ) : null}
        </label>
        <label className={`form-group${fieldErrors.inputLimit ? ' is-error' : ''}`}>
          <FieldLabel>{t('ai.catalog.form.inputLimit')}</FieldLabel>
          <NumberInput
            value={draft.inputLimit}
            min={1}
            placeholder="272000"
            onChange={(inputLimit) => onChange({ ...draft, inputLimit })}
            aria-label={t('ai.catalog.form.inputLimit')}
            aria-describedby="model-input-limit-hint"
            invalid={Boolean(fieldErrors.inputLimit)}
          />
          <span id="model-input-limit-hint" className="inline-hint">
            {t('ai.catalog.form.inputLimitHint')}
          </span>
          {fieldErrors.inputLimit ? (
            <span className="field-error">{fieldErrors.inputLimit}</span>
          ) : null}
        </label>
        <label className={`form-group${fieldErrors.maxOutputTokens ? ' is-error' : ''}`}>
          <FieldLabel required>{t('ai.catalog.form.maxOutputTokens')}</FieldLabel>
          <NumberInput
            value={draft.maxOutputTokens}
            min={1}
            placeholder="8192"
            onChange={(maxOutputTokens) => onChange({ ...draft, maxOutputTokens })}
            aria-label={t('ai.catalog.form.maxOutputTokens')}
          />
          <span className="inline-hint">{t('ai.catalog.form.maxOutputLimitHint')}</span>
          {fieldErrors.maxOutputTokens ? (
            <span className="field-error">{fieldErrors.maxOutputTokens}</span>
          ) : null}
        </label>
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
  function toggle(value: ModelInputModality) {
    onChange({ ...draft, inputModalities: toggleInputModality(draft.inputModalities, value) })
  }
  return (
    <fieldset className={`form-group capability-picker${error ? ' is-error' : ''}`}>
      <legend>
        <FieldLabel required>{t('ai.catalog.form.inputTypes')}</FieldLabel>
      </legend>
      <p className="inline-hint">{t('ai.catalog.form.inputModalityHint')}</p>
      <div className="capability-options">
        {AGENT_MODEL_INPUT_MODALITIES.map((item) => {
          const checked = draft.inputModalities.includes(item)
          return (
            <Checkbox
              key={item}
              className={`capability-option${checked ? ' is-selected' : ''}`}
              checked={checked}
              onChange={() => toggle(item)}
            >
              <span>{item}</span>
            </Checkbox>
          )
        })}
      </div>
      {error ? <span className="field-error">{error}</span> : null}
    </fieldset>
  )
}
