import { useEffect, useState } from 'react'
import { CodeXml } from 'lucide-react'
import type { EnvironmentDTO } from '@/shared/api/generated/contracts'
import { useI18n } from '@/shared/i18n'
import { ENVIRONMENT_STATUS_LABEL_KEY, environmentDisplayStatus } from '@/features/environment/environment-utils'
import { IconButton } from '@/shared/ui/controls/IconButton'
import { useIdeWorkspace, type VsCodeTarget } from './ide-workspace-context'
import './environment-list.css'

export interface EnvironmentListProps {
  open: (target: VsCodeTarget, anchor: HTMLButtonElement) => void
  findOpenView?: (environmentId: string) => string | undefined
}

export function EnvironmentList({ open, findOpenView }: EnvironmentListProps) {
  const { environments, resolveTarget } = useIdeWorkspace()
  const { t } = useI18n()
  const [now, setNow] = useState(Date.now)

  useEffect(() => {
    const deadlines = environments
      .map((environment) => environment.statusExpiresAt)
      .filter((deadline): deadline is number => deadline != null && deadline > now)
    if (deadlines.length === 0) return
    const timer = window.setTimeout(() => setNow(Date.now()), Math.min(...deadlines) - Date.now() + 1)
    return () => window.clearTimeout(timer)
  }, [environments, now])

  return (
    <nav className="environment-list" aria-label={t('ai.environment.ide.listLabel')}>
      {environments.map((environment: EnvironmentDTO) => {
        const status = environmentDisplayStatus(environment)
        const available = status === 'READY'
        const connection = t(ENVIRONMENT_STATUS_LABEL_KEY[status])
        return (
          <div className="environment-list__item" key={environment.id}>
            <span className={`environment-list__status is-${status.toLowerCase()}`} role="img" aria-label={connection} title={connection} />
            <span className="environment-list__name" title={environment.name}>{environment.name}</span>
            {available && (
              <IconButton
                className="environment-list__open"
                size="compact"
                label={t('ai.environment.ide.open', { name: environment.name })}
                title={t('ai.environment.ide.open', { name: environment.name })}
                onClick={({ currentTarget }) => {
                  const target = resolveTarget(environment.id, findOpenView?.(environment.id))
                  if (target != null) open(target, currentTarget)
                }}
              >
                <CodeXml size={16} aria-hidden="true" />
              </IconButton>
            )}
          </div>
        )
      })}
    </nav>
  )
}
