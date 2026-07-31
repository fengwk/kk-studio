import { useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { StateBlock } from '@/shared/ui/console/AiConsoleCommonCards'
import { FieldLabel } from '@/shared/ui/console/FieldLabel'
import { FormSelect } from '@/shared/ui/console/FormSelect'
import { harnessService } from '@/shared/api/harness-service'
import type {
  HarnessRealtimeStreamPolicyDTO,
  HarnessRetryPolicyDTO,
} from '@/shared/api/contracts/ai-runtime'
import { queryKeys } from '@/shared/lib/query-keys'
import { NavigationSlot } from '@/platform/workbench/WorkbenchSlots'
import { translate, useI18n } from '@/shared/i18n'

interface RetryPolicyDraft {
  maxRetries: string
  backoffStrategy: HarnessRetryPolicyDTO['backoffStrategy']
  baseDelaySeconds: string
  maxDelaySeconds: string
}

function toRetryDraft(policy: HarnessRetryPolicyDTO): RetryPolicyDraft {
  return {
    maxRetries: String(policy.maxRetries),
    backoffStrategy: policy.backoffStrategy,
    baseDelaySeconds: String(policy.baseDelayMillis / 1_000),
    maxDelaySeconds: String(policy.maxDelayMillis / 1_000),
  }
}

function readWholeNumber(value: string, label: string, min: number, max: number): number {
  if (!/^\d+$/.test(value.trim())) {
    throw new Error(translate('ai.settings.invalidInteger', { label }))
  }
  const parsed = Number(value)
  if (!Number.isSafeInteger(parsed) || parsed < min || parsed > max) {
    throw new Error(translate('ai.settings.integerRange', { label, min, max }))
  }
  return parsed
}

function readDelayMillis(value: string, label: string): number {
  const normalized = value.trim()
  if (!/^\d+(?:\.\d{1,3})?$/.test(normalized)) {
    throw new Error(translate('ai.settings.invalidSeconds', { label }))
  }
  const millis = Math.round(Number(normalized) * 1_000)
  if (!Number.isSafeInteger(millis) || millis < 1_000 || millis > 60_000) {
    throw new Error(translate('ai.settings.secondsRange', { label }))
  }
  return millis
}

function toRetryPolicy(draft: RetryPolicyDraft): HarnessRetryPolicyDTO {
  const maxRetries = readWholeNumber(
    draft.maxRetries,
    translate('ai.settings.maxRetries'),
    0,
    10,
  )
  const baseDelayMillis = readDelayMillis(
    draft.baseDelaySeconds,
    translate('ai.settings.baseDelay'),
  )
  const maxDelayMillis = readDelayMillis(
    draft.maxDelaySeconds,
    translate('ai.settings.maxDelay'),
  )
  if (maxDelayMillis < baseDelayMillis) {
    throw new Error(translate('ai.settings.maxLessThanBase'))
  }
  return {
    maxRetries,
    backoffStrategy: draft.backoffStrategy,
    baseDelayMillis,
    maxDelayMillis,
  }
}

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message.trim() ? error.message : fallback
}

/** AI 二级设置页：独立维护全局 retry 与 realtime Stream 投影策略。 */
export function HarnessSettingsPage() {
  return (
    <section className="screen active">
      <nav className="subbar">
        <NavigationSlot />
      </nav>
      <div className="screen-body">
        <div className="harness-settings-stack">
          <RetryPolicySettingsCard />
          <RealtimeStreamPolicySettingsCard />
        </div>
      </div>
    </section>
  )
}

function RetryPolicySettingsCard() {
  const { t, locale } = useI18n()
  const queryClient = useQueryClient()
  const policyQuery = useQuery({
    queryKey: queryKeys.harness.retryPolicy,
    queryFn: () => harnessService.getRetryPolicy(),
  })
  const [draft, setDraft] = useState<RetryPolicyDraft | null>(null)
  const [validationError, setValidationError] = useState<string | null>(null)

  useEffect(() => {
    if (policyQuery.data) {
      setDraft(toRetryDraft(policyQuery.data))
      setValidationError(null)
    }
  }, [policyQuery.data])

  const saveMutation = useMutation({
    mutationFn: (next: HarnessRetryPolicyDTO) => harnessService.updateRetryPolicy(next),
    onSuccess: async (saved) => {
      setDraft(toRetryDraft(saved))
      setValidationError(null)
      await queryClient.invalidateQueries({ queryKey: queryKeys.harness.retryPolicy })
    },
  })

  const explanation = useMemo(() => {
    void locale
    if (!draft) {
      return ''
    }
    if (draft.backoffStrategy === 'FIXED') {
      return t('ai.settings.fixedExplanation', { seconds: draft.baseDelaySeconds || '?' })
    }
    return t('ai.settings.exponentialExplanation', { seconds: draft.maxDelaySeconds || '?' })
  }, [draft, locale, t])

  function submit() {
    if (!draft) {
      return
    }
    try {
      setValidationError(null)
      saveMutation.mutate(toRetryPolicy(draft))
    } catch (error) {
      setValidationError(errorMessage(error, t('ai.settings.saveRetryFailed')))
    }
  }

  if (policyQuery.isLoading) {
    return <StateBlock title={t('ai.settings.loadingRetry')} />
  }
  if (policyQuery.error) {
    return <StateBlock title={errorMessage(policyQuery.error, t('ai.settings.retryLoadFailed'))} tone="danger" />
  }
  if (!draft) {
    return null
  }

  return (
    <section className="harness-settings-card">
      <header>
        <h2>{t('ai.settings.retryTitle')}</h2>
        <p>{t('ai.settings.retryDescription')}</p>
      </header>
      <form
        className="harness-settings-form"
        noValidate
        onSubmit={(event) => {
          event.preventDefault()
          submit()
        }}
      >
        {validationError || saveMutation.error ? (
          <p className="form-error-banner" role="alert">
            {validationError ?? errorMessage(saveMutation.error, t('ai.settings.saveRetryFailed'))}
          </p>
        ) : null}
        <label className="form-group">
          <FieldLabel required>{t('ai.settings.maxRetries')}</FieldLabel>
          <input
            aria-label={t('ai.settings.maxRetries')}
            type="number"
            min="0"
            max="10"
            required
            inputMode="numeric"
            value={draft.maxRetries}
            onChange={(event) => setDraft({ ...draft, maxRetries: event.target.value })}
          />
          <small>{t('ai.settings.maxRetriesHint')}</small>
        </label>
        <label className="form-group">
          <FieldLabel required>{t('ai.settings.backoffStrategy')}</FieldLabel>
          <FormSelect
            aria-label={t('ai.settings.backoffStrategy')}
            value={draft.backoffStrategy}
            required
            options={[
              { value: 'EXPONENTIAL', label: t('ai.settings.exponentialBackoff') },
              { value: 'FIXED', label: t('ai.settings.fixedInterval') },
            ]}
            onChange={(backoffStrategy) =>
              setDraft({
                ...draft,
                backoffStrategy: backoffStrategy as HarnessRetryPolicyDTO['backoffStrategy'],
              })
            }
          />
        </label>
        <label className="form-group">
          <FieldLabel required>{t('ai.settings.baseDelay')}</FieldLabel>
          <input
            aria-label={t('ai.settings.baseDelay')}
            type="number"
            min="1"
            max="60"
            step="0.001"
            required
            inputMode="numeric"
            value={draft.baseDelaySeconds}
            onChange={(event) => setDraft({ ...draft, baseDelaySeconds: event.target.value })}
          />
        </label>
        <label className="form-group">
          <FieldLabel required>{t('ai.settings.maxDelay')}</FieldLabel>
          <input
            aria-label={t('ai.settings.maxDelay')}
            type="number"
            min="1"
            max="60"
            step="0.001"
            required
            inputMode="numeric"
            value={draft.maxDelaySeconds}
            onChange={(event) => setDraft({ ...draft, maxDelaySeconds: event.target.value })}
          />
        </label>
        <p className="harness-settings-help">{explanation}</p>
        <div className="harness-settings-actions">
          <button className="btn-primary" type="submit" disabled={saveMutation.isPending}>
            {saveMutation.isPending ? t('ai.settings.saving') : t('ai.settings.saveRetry')}
          </button>
        </div>
      </form>
    </section>
  )
}

function RealtimeStreamPolicySettingsCard() {
  const { t } = useI18n()
  const queryClient = useQueryClient()
  const policyQuery = useQuery({
    queryKey: queryKeys.harness.realtimeStreamPolicy,
    queryFn: () => harnessService.getRealtimeStreamPolicy(),
  })
  const [maxLength, setMaxLength] = useState('')
  const [validationError, setValidationError] = useState<string | null>(null)

  useEffect(() => {
    if (policyQuery.data) {
      setMaxLength(String(policyQuery.data.maxLength))
      setValidationError(null)
    }
  }, [policyQuery.data])

  const saveMutation = useMutation({
    mutationFn: (next: HarnessRealtimeStreamPolicyDTO) =>
      harnessService.updateRealtimeStreamPolicy(next),
    onSuccess: async (saved) => {
      setMaxLength(String(saved.maxLength))
      setValidationError(null)
      await queryClient.invalidateQueries({ queryKey: queryKeys.harness.realtimeStreamPolicy })
    },
  })

  function submit() {
    try {
      setValidationError(null)
      saveMutation.mutate({
        maxLength: readWholeNumber(
          maxLength,
          t('ai.settings.maxEvents'),
          1,
          Number.MAX_SAFE_INTEGER,
        ),
      })
    } catch (error) {
      setValidationError(errorMessage(error, t('ai.settings.saveRealtimeFailed')))
    }
  }

  if (policyQuery.isLoading) {
    return <StateBlock title={t('ai.settings.loadingRealtime')} />
  }
  if (policyQuery.error) {
    return (
      <StateBlock
        title={errorMessage(policyQuery.error, t('ai.settings.realtimeLoadFailed'))}
        tone="danger"
      />
    )
  }

  return (
    <section className="harness-settings-card">
      <header>
        <h2>{t('ai.settings.realtimeTitle')}</h2>
        <p>{t('ai.settings.realtimeDescription')}</p>
      </header>
      <form
        className="harness-settings-form"
        noValidate
        onSubmit={(event) => {
          event.preventDefault()
          submit()
        }}
      >
        {validationError || saveMutation.error ? (
          <p className="form-error-banner" role="alert">
            {validationError ?? errorMessage(saveMutation.error, t('ai.settings.saveRealtimeFailed'))}
          </p>
        ) : null}
        <label className="form-group">
          <FieldLabel required>{t('ai.settings.maxEvents')}</FieldLabel>
          <input
            aria-label={t('ai.settings.maxEvents')}
            type="number"
            min="1"
            step="1"
            required
            inputMode="numeric"
            value={maxLength}
            onChange={(event) => setMaxLength(event.target.value)}
          />
          <small>{t('ai.settings.maxEventsHint')}</small>
        </label>
        <p className="harness-settings-help">{t('ai.settings.realtimeHelp')}</p>
        <div className="harness-settings-actions">
          <button className="btn-primary" type="submit" disabled={saveMutation.isPending}>
            {saveMutation.isPending ? t('ai.settings.saving') : t('ai.settings.saveRealtime')}
          </button>
        </div>
      </form>
    </section>
  )
}
