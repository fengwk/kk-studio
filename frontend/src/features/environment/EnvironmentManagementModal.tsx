import { useQuery } from '@tanstack/react-query'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { Button } from '@/shared/ui/controls/Button'
import { useI18n } from '@/shared/i18n'
import { environmentService } from '@/features/environment/environment-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { formatBackendDateSeconds } from '@/shared/lib/date-format'
import {
  ENVIRONMENT_EVENT_LEVEL_LABEL_KEY,
  ENVIRONMENT_EVENT_TYPE_LABEL_KEY,
  environmentEventLevelClass,
} from '@/features/environment/environment-utils'
import { EnvironmentHostSection } from '@/features/environment/EnvironmentHostSection'
import { EnvironmentConfigurationSection } from '@/features/environment/EnvironmentConfigurationSection'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'
import type { EnvironmentDTO } from '@/shared/api/generated/contracts'
import './environment-management.css'

export interface EnvironmentManagementModalProps {
  environment: EnvironmentDTO
  onClose: () => void
  actions?: { label: string; run: () => void; danger?: boolean }[]
}

export function EnvironmentManagementModal({
  environment,
  onClose,
  actions = [],
}: EnvironmentManagementModalProps) {
  const { t } = useI18n()
  const eventsQuery = useQuery({
    queryKey: queryKeys.environments.events(environment.id),
    queryFn: () => environmentService.listEnvironmentEvents(environment.id),
    refetchInterval: 10_000,
  })
  const orderedEvents = [...(eventsQuery.data ?? [])].reverse()
  const retryEvents = () => void eventsQuery.refetch({ cancelRefetch: false })

  return (
    <Dialog
      className="env-management-modal-card modal-touch-target"
      title={t('ai.environment.managementTitle', { name: environment.name })}
      onClose={onClose}
    >
      <div className="modal-body env-mgmt-body">
        {actions.length > 0 && (
          <div className="environment-management-actions">
            {actions.map(({ label, run, danger }) => (
              <Button
                key={label}
                variant="secondary"
                danger={danger}
                onClick={() => {
                  onClose()
                  run()
                }}
              >
                {label}
              </Button>
            ))}
          </div>
        )}
        <div className="environment-diagnostics-content">
              <EnvironmentHostSection environment={environment} />
              <EnvironmentConfigurationSection environment={environment} />
              <section className="env-mgmt-section" aria-label={t('ai.environment.events.title')}>
                <div className="env-mgmt-section-header">
                  <div>
                    <h3 className="env-mgmt-section-title">{t('ai.environment.events.title')}</h3>
                    {orderedEvents.length > 0 && (
                      <span className="env-mgmt-badge-count">{orderedEvents.length}</span>
                    )}
                  </div>
                </div>
                {eventsQuery.isPending ? (
                  <StateBlock title={t('ai.environment.events.loading')} />
                ) : eventsQuery.isError ? (
                  <div className="env-events-failure">
                    <StateBlock tone="danger" title={t('ai.environment.events.loadFailed')} />
                    <Button
                      size="compact"
                      variant="secondary"
                      disabled={eventsQuery.isFetching}
                      onClick={retryEvents}
                    >
                      {t('shared.retry')}
                    </Button>
                  </div>
                ) : orderedEvents.length === 0 ? (
                  <StateBlock title={t('ai.environment.events.empty')} />
                ) : (
                  <div className="env-events-list">
                    {orderedEvents.map((evt, idx) => {
                      const levelClass = environmentEventLevelClass(evt.level)
                      const timeStr = formatBackendDateSeconds(evt.occurredAt)
                      return (
                        <div key={`${evt.type}-${idx}`} className={`env-event-item ${levelClass}`}>
                          <div className="env-event-header">
                            <span
                              className={`env-event-level ${levelClass}`}
                              title={evt.level}
                            >
                              {t(ENVIRONMENT_EVENT_LEVEL_LABEL_KEY[evt.level])}
                            </span>
                            <span className="env-event-type" title={evt.type}>
                              {t(ENVIRONMENT_EVENT_TYPE_LABEL_KEY[evt.type])}
                            </span>
                            {timeStr ? <span className="env-event-time">{timeStr}</span> : null}
                          </div>
                          {evt.message && <div className="env-event-message">{t(
                            evt.type === 'DISCONNECTED' && evt.level === 'INFO'
                              ? 'ai.environment.events.message.released'
                              : `ai.environment.events.message.${evt.type}`,
                          )}</div>}
                        </div>
                      )
                    })}
                  </div>
                )}
              </section>
        </div>
      </div>
      <div className="modal-footer">
        <Button variant="secondary" onClick={onClose}>{t('shared.close')}</Button>
      </div>
    </Dialog>
  )
}
