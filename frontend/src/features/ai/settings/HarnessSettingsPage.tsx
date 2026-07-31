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

function toRetryPolicy(draft: RetryPolicyDraft): HarnessRetryPolicyDTO {
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
      saveMutation.mutate(toRetryPolicy(draft))
    } catch (error) {
      setValidationError(errorMessage(error, '保存重试策略失败'))
    }
  }

  if (policyQuery.isLoading) {
    return <StateBlock title="正在加载重试策略" />
  }
  if (policyQuery.error) {
    return <StateBlock title={errorMessage(policyQuery.error, '加载重试策略失败')} tone="danger" />
  }
  if (!draft) {
    return null
  }

  return (
    <section className="harness-settings-card">
      <header>
        <h2>自动重试</h2>
        <p>仅对网络、限流和服务端等瞬态 Provider 故障生效。鉴权、计费、参数错误和取消会立即停止。</p>
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
            {validationError ?? errorMessage(saveMutation.error, '保存重试策略失败')}
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
        <p className="harness-settings-help">{explanation}</p>
        <div className="harness-settings-actions">
          <button className="btn-primary" type="submit" disabled={saveMutation.isPending}>
            {saveMutation.isPending ? '保存中…' : '保存重试策略'}
          </button>
        </div>
      </form>
    </section>
  )
}

function RealtimeStreamPolicySettingsCard() {
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
        maxLength: readWholeNumber(maxLength, '最大保留事件数', 1, Number.MAX_SAFE_INTEGER),
      })
    } catch (error) {
      setValidationError(errorMessage(error, '保存实时流设置失败'))
    }
  }

  if (policyQuery.isLoading) {
    return <StateBlock title="正在加载实时流设置" />
  }
  if (policyQuery.error) {
    return <StateBlock title={errorMessage(policyQuery.error, '加载实时流设置失败')} tone="danger" />
  }

  return (
    <section className="harness-settings-card">
      <header>
        <h2>实时流缓存</h2>
        <p>控制每个 Thread 在 Redis 中最多保留多少条 realtime event；它是短期投影，不影响 PostgreSQL 历史。</p>
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
            {validationError ?? errorMessage(saveMutation.error, '保存实时流设置失败')}
          </p>
        ) : null}
        <label className="form-group">
          <FieldLabel required>最大保留事件数</FieldLabel>
          <input
            aria-label="最大保留事件数"
            type="number"
            min="1"
            step="1"
            required
            inputMode="numeric"
            value={maxLength}
            onChange={(event) => setMaxLength(event.target.value)}
          />
          <small>数值越大，单个 Thread 的 Redis 内存上限越高。</small>
        </label>
        <p className="harness-settings-help">
          保存后在本实例的任意既有或新 Thread Stream 下一次写入生效；其他实例最多约一秒刷新。调小会在下一次写入裁剪；调大不会恢复已裁掉的事件。当前不设置 TTL，也不会主动处理空闲 Stream。
        </p>
        <div className="harness-settings-actions">
          <button className="btn-primary" type="submit" disabled={saveMutation.isPending}>
            {saveMutation.isPending ? '保存中…' : '保存实时流设置'}
          </button>
        </div>
      </form>
    </section>
  )
}
