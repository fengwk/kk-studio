import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ModalBackdrop, ModalHeader } from '@/shared/ui/console/AiConsoleModalLayout'
import { useI18n } from '@/shared/i18n'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'
import { environmentService } from '@/shared/api/environment-service'
import { queryKeys } from '@/shared/lib/query-keys'
import {
  environmentEventLevelClass,
  formatTimestamp,
} from '@/features/ai/environment/environment-utils'
import { EnvironmentHostSection } from '@/features/ai/environment/EnvironmentHostSection'

export interface EnvironmentManagementModalProps {
  environment: EnvironmentCardDTO
  onClose: () => void
}

export function EnvironmentManagementModal({
  environment,
  onClose,
}: EnvironmentManagementModalProps) {
  const { t, locale } = useI18n()

  const eventsQuery = useQuery({
    queryKey: queryKeys.environments.events(environment.id),
    queryFn: () => environmentService.listEnvironmentEvents(environment.id),
    refetchInterval: 10_000,
  })

  const orderedEvents = useMemo(() => {
    const list = eventsQuery.data ?? []
    return [...list].reverse()
  }, [eventsQuery.data])

  return (
    <ModalBackdrop onClose={onClose}>
      <div
        className="modal-card env-management-modal-card"
        role="dialog"
        aria-modal="true"
        aria-label={`${t('ai.environment.managementTitle')} - ${environment.name}`}
        onMouseDown={(e) => e.stopPropagation()}
      >
        <ModalHeader
          title={`${t('ai.environment.managementTitle')} - ${environment.name}`}
          onClose={onClose}
        />

        <div className="modal-body env-mgmt-body">
          <EnvironmentHostSection environment={environment} />

          <section className="env-mgmt-section" aria-label={t('ai.environment.events.title')}>
            <div className="env-mgmt-section-header">
              <div>
                <h3 className="env-mgmt-section-title">{t('ai.environment.events.title')}</h3>
                {(eventsQuery.data?.length ?? 0) > 0 ? (
                  <span className="env-mgmt-badge-count">{eventsQuery.data!.length}</span>
                ) : null}
              </div>
            </div>

            {orderedEvents.length === 0 ? (
              <div className="env-events-empty">
                <p>{t('ai.environment.events.empty')}</p>
              </div>
            ) : (
              <div className="env-events-list">
                {orderedEvents.map((evt, idx) => {
                  const levelClass = environmentEventLevelClass(evt.level)
                  const timeStr = formatTimestamp(evt.time, locale)
                  return (
                    <div
                      key={`${evt.type}-${idx}`}
                      className={`env-event-item ${levelClass}`}
                    >
                      <div className="env-event-header">
                        <span className={`env-event-level ${levelClass}`}>{evt.level}</span>
                        <span className="env-event-type">{evt.type}</span>
                        {timeStr ? <span className="env-event-time">{timeStr}</span> : null}
                      </div>
                      <div className="env-event-message">{evt.message}</div>
                    </div>
                  )
                })}
              </div>
            )}
          </section>
        </div>
      </div>
    </ModalBackdrop>
  )
}
