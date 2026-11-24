import { CodeXml } from 'lucide-react'
import { ENVIRONMENT_STATUS_LABEL_KEY } from '@/features/environment/environment-utils'
import { useI18n } from '@/shared/i18n'
import { IconButton } from '@/shared/ui/controls/IconButton'
import { environmentActions, environments } from '../mock/thread-data'
import workbench from '../mock/workbench.webp?inline'
import type { EnvironmentView } from './projection'

const actionIcons = { vscode: CodeXml } satisfies Record<EnvironmentView, typeof CodeXml>

export function EnvironmentList({ open }: { open: (view: EnvironmentView, id: string, anchor: HTMLButtonElement) => void }) {
  const { t } = useI18n()
  if (!environments.length) return null
  return <section className="x-environment-list" aria-labelledby="x-environment-title">
    <h2 id="x-environment-title">环境</h2>
    <ul>
      {environments.map(({ id, name, ready, status }) => {
        const connection = t(ENVIRONMENT_STATUS_LABEL_KEY[status])
        return <li key={id}>
          <span className="x-environment-dot" role="img" aria-label={connection} title={connection} data-status={status} />
          <span className="x-environment-name" title={name} data-allow-truncate>{name}</span>
          {ready && <div className="x-environment-actions">
            {environmentActions.map(({ view, label }) => {
              const Icon = actionIcons[view]
              return <IconButton key={view} size="compact" className="x-ib"
                label={`在 ${name} 打开 ${label}`} title={`打开 ${label}`} onClick={({ currentTarget }) => open(view, id, currentTarget)}>
                <Icon aria-hidden="true" />
              </IconButton>
            })}
          </div>}
        </li>
      })}
    </ul>
  </section>
}

export function VsCodeWorkbench() {
  return <img className="x-ide-workbench" src={workbench} alt="VS Code 工作台静态画面" />
}
