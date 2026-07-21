import { useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { StateBlock } from '@/features/ai/AiConsoleCards'
import { FieldLabel } from '@/features/ai/FieldLabel'
import { FormSelect } from '@/features/ai/FormSelect'
import { harnessService } from '@/shared/api/harness-service'
import type { HarnessRetryPolicyDTO } from '@/shared/api/contracts'
import { queryKeys } from '@/shared/lib/query-keys'
import { NavigationSlot } from '@/platform/workbench/WorkbenchSlots'

interface RetryPolicyDraft {
  maxRetries: string
  backoffStrategy: HarnessRetryPolicyDTO['backoffStrategy']
  baseDelaySeconds: string
  maxDelaySeconds: string
}

function toDraft(policy: HarnessRetryPolicyDTO): RetryPolicyDraft {
  return {
    maxRetries: String(policy.maxRetries),
    backoffStrategy: policy.backoffStrategy,
    baseDelaySeconds: String(policy.baseDelayMillis / 1_000),
    maxDelaySeconds: String(policy.maxDelayMillis / 1_000),
  }
}

function readWholeNumber(value: string, label: string, min: number, max: number): number {
  if (!/^\d+$/.test(value.trim())) {
    throw new Error(`${label}必须是整数`)
  }
  const parsed = Number(value)
  if (!Number.isSafeInteger(parsed) || parsed < min || parsed > max) {
    throw new Error(`${label}必须在 ${min} 到 ${max} 之间`)
  }
  return parsed
}

function readDelayMillis(value: string, label: string): number {
  const normalized = value.trim()
  if (!/^\d+(?:\.\d{1,3})?$/.test(normalized)) {
    throw new Error(`${label}必须是秒数，最多保留三位小数`)
  }
  const millis = Math.round(Number(normalized) * 1_000)
  if (!Number.isSafeInteger(millis) || millis < 1_000 || millis > 60_000) {
    throw new Error(`${label}必须在 1 到 60 秒之间`)
  }
  return millis
}

function toPolicy(draft: RetryPolicyDraft): HarnessRetryPolicyDTO {
  const maxRetries = readWholeNumber(draft.maxRetries, '最大重试次数', 0, 10)
  const baseDelayMillis = readDelayMillis(draft.baseDelaySeconds, '基础间隔')
  const maxDelayMillis = readDelayMillis(draft.maxDelaySeconds, '最大间隔')
  if (maxDelayMillis < baseDelayMillis) {
    throw new Error('最大间隔不能小于基础间隔')
  }
  return {
    maxRetries,
    backoffStrategy: draft.backoffStrategy,
    baseDelayMillis,
    maxDelayMillis,
  }
}

function errorMessage(error: unknown): string {
  return error instanceof Error && error.message.trim() ? error.message : '保存重试策略失败'
}

/** AI 二级设置页：仅配置对未来瞬态失败生效的全局自动重试策略。 */
export function RetryPolicyPage() {
  const queryClient = useQueryClient()
  const policyQuery = useQuery({
    queryKey: queryKeys.harness.retryPolicy,
    queryFn: () => harnessService.getRetryPolicy(),
  })
  const [draft, setDraft] = useState<RetryPolicyDraft | null>(null)
  const [validationError, setValidationError] = useState<string | null>(null)
  const policy = policyQuery.data

  useEffect(() => {
    if (policy) {
      setDraft(toDraft(policy))
      setValidationError(null)
    }
  }, [policy])

  const saveMutation = useMutation({
    mutationFn: (next: HarnessRetryPolicyDTO) => harnessService.updateRetryPolicy(next),
    onSuccess: async (saved) => {
      setDraft(toDraft(saved))
      setValidationError(null)
      await queryClient.invalidateQueries({ queryKey: queryKeys.harness.retryPolicy })
    },
  })

  const explanation = useMemo(() => {
    if (!draft) {
      return ''
    }
    if (draft.backoffStrategy === 'FIXED') {
      return `每次失败后固定等待 ${draft.baseDelaySeconds || '?'} 秒。`
    }
    return `等待间隔按 2 倍递增，最大不超过 ${draft.maxDelaySeconds || '?'} 秒。`
  }, [draft])

  function submit() {
    if (!draft) {
      return
    }
    try {
      setValidationError(null)
      saveMutation.mutate(toPolicy(draft))
    } catch (error) {
      setValidationError(errorMessage(error))
    }
  }

  return (
    <section className="screen active">
      <nav className="subbar">
        <NavigationSlot />
      </nav>
      <div className="screen-body">
        {policyQuery.isLoading ? <StateBlock title="正在加载重试策略" /> : null}
        {policyQuery.error ? <StateBlock title={errorMessage(policyQuery.error)} tone="danger" /> : null}
        {!policyQuery.isLoading && !policyQuery.error && draft ? (
          <section className="retry-policy-card">
            <header>
              <h2>自动重试</h2>
              <p>仅对网络、限流和服务端等瞬态 Provider 故障生效。鉴权、计费、参数错误和取消会立即停止。</p>
            </header>
            <form
              className="retry-policy-form"
              onSubmit={(event) => {
                event.preventDefault()
                submit()
              }}
            >
              {validationError || saveMutation.error ? (
                <p className="form-error-banner" role="alert">
                  {validationError ?? errorMessage(saveMutation.error)}
                </p>
              ) : null}
              <label className="form-group">
                <FieldLabel required>最大重试次数</FieldLabel>
                <input
                  aria-label="最大重试次数"
                  type="number"
                  min="0"
                  max="10"
                  required
                  inputMode="numeric"
                  value={draft.maxRetries}
                  onChange={(event) => setDraft({ ...draft, maxRetries: event.target.value })}
                />
                <small>不包含首次请求；设为 0 时不自动重试。</small>
              </label>
              <label className="form-group">
                <FieldLabel required>退避策略</FieldLabel>
                <FormSelect
                  aria-label="退避策略"
                  value={draft.backoffStrategy}
                  required
                  options={[
                    { value: 'EXPONENTIAL', label: '指数退避' },
                    { value: 'FIXED', label: '固定间隔' },
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
                <FieldLabel required>基础间隔（秒）</FieldLabel>
                <input
                  aria-label="基础间隔（秒）"
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
                <FieldLabel required>最大间隔（秒）</FieldLabel>
                <input
                  aria-label="最大间隔（秒）"
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
              <p className="retry-policy-help">{explanation}</p>
              <div className="retry-policy-actions">
                <button className="btn-primary" type="submit" disabled={saveMutation.isPending}>
                  {saveMutation.isPending ? '保存中…' : '保存策略'}
                </button>
              </div>
            </form>
          </section>
        ) : null}
      </div>
    </section>
  )
}
