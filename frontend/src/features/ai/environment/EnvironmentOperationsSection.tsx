import { Square } from 'lucide-react'
import { formatTimestamp } from '@/features/ai/environment/environment-utils'
import { useI18n } from '@/shared/i18n'
import type { EnvironmentOperationDTO } from '@/shared/api/contracts/ai-environment'

export interface EnvironmentOperationsSectionProps {
  operations: EnvironmentOperationDTO[]
  loading?: boolean
  error?: string | null
  onCancel: (operation: EnvironmentOperationDTO) => void
}

export function EnvironmentOperationsSection({
  operations,
  loading,
  error,
  onCancel,
}: EnvironmentOperationsSectionProps) {
  const { t, locale } = useI18n()

  return (
    <section className="env-mgmt-section" aria-labelledby="env-section-operations">
      <div className="env-mgmt-section-header">
        <div>
          <h3 id="env-section-operations">{t('ai.environment.operations.title')}</h3>
          <span className="env-mgmt-badge-count">{operations.length}</span>
        </div>
      </div>

      {loading ? (
        <div className="state-block">{t('ai.environment.operations.loading')}</div>
      ) : error ? (
        <div className="state-block error">{error}</div>
      ) : operations.length === 0 ? (
        <div className="state-block">{t('ai.environment.operations.empty')}</div>
      ) : (
        <div className="env-operations-list">
          {operations.map((op) => {
            const isPending = op.status === 'PENDING'
            const statusClass = `status-pill is-${(op.status || 'unknown').toLowerCase()}`
            const created = formatTimestamp(op.createdAt, locale)
            const started = formatTimestamp(op.startedAt, locale)
            const finished = formatTimestamp(op.finishedAt, locale)
            const deadline = formatTimestamp(op.deadlineAt, locale)

            const timeParts: string[] = []
            if (created) {
              timeParts.push(`${t('ai.environment.operations.created')}: ${created}`)
            }
            if (started) {
              timeParts.push(`${t('ai.environment.operations.started')}: ${started}`)
            }
            if (finished) {
              timeParts.push(`${t('ai.environment.operations.finished')}: ${finished}`)
            }
            if (deadline) {
              timeParts.push(`${t('ai.environment.operations.deadline')}: ${deadline}`)
            }

            return (
              <article key={op.id} className="env-op-item">
                <div className="env-op-header">
                  <div className="env-op-badges">
                    <span className="meta-chip is-type">{op.operationType}</span>
                    <span className={statusClass}>{op.status}</span>
                  </div>
                  <div className="env-op-header-meta">
                    <code className="env-op-id" title={op.id}>
                      {op.id}
                    </code>
                    {isPending && (
                      <button
                        type="button"
                        className="action-enter-btn danger btn-sm"
                        aria-label={`${t('ai.environment.operations.cancel')} ${op.id}`}
                        onClick={() => onCancel(op)}
                      >
                        <Square aria-hidden="true" />
                        {t('ai.environment.operations.cancel')}
                      </button>
                    )}
                  </div>
                </div>

                <div className="env-op-details">
                  <div className="env-meta-row">
                    <span className="lbl">{t('ai.environment.operations.targetResource')}</span>
                    <span className="val">
                      <span className="meta-chip is-resource">{op.resourceType}</span>{' '}
                      <code>{op.resourceId}</code>
                    </span>
                  </div>

                  <div className="env-meta-row">
                    <span className="lbl">{t('ai.environment.operations.resourceVersionLabel')}</span>
                    <span className="val">
                      {t('ai.environment.operations.resourceVersion', { version: op.resourceVersion })}
                    </span>
                  </div>

                  {timeParts.length > 0 && (
                    <div className="env-meta-row env-op-times">
                      <span className="lbl">{t('ai.environment.operations.times')}</span>
                      <span className="val">{timeParts.join(' · ')}</span>
                    </div>
                  )}
                </div>

                {op.parameterSummary && Object.keys(op.parameterSummary).length > 0 && (
                  <div className="env-op-result-box">
                    <span className="lbl">{t('ai.environment.operations.parameterSummary')}</span>
                    <pre className="env-op-result-json">
                      {JSON.stringify(op.parameterSummary, null, 2)}
                    </pre>
                  </div>
                )}

                {op.resultSummary && Object.keys(op.resultSummary).length > 0 && (
                  <div className="env-op-result-box">
                    <span className="lbl">{t('ai.environment.operations.resultSummary')}</span>
                    <pre className="env-op-result-json">
                      {JSON.stringify(op.resultSummary, null, 2)}
                    </pre>
                  </div>
                )}

                {(op.failureCode || op.failureMessage) && (
                  <div className="env-op-failure" role="alert">
                    {op.failureCode ? <strong>[{op.failureCode}] </strong> : null}
                    {op.failureMessage ?? t('ai.environment.operations.failure')}
                  </div>
                )}
              </article>
            )
          })}
        </div>
      )}
    </section>
  )
}
