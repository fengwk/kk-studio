import { lazy, Suspense, useEffect } from 'react'
import { useNavigate, useParams } from 'react-router'
import { notifyProjectsChanged } from '@/features/projects/useProjectsInvalidation'
import type { ExtensionComponentProps } from '@/platform/extensions/types'
import { useApplicationEvents } from '@/shared/app-events'
import { useI18n } from '@/shared/i18n'

const ProjectsPage = lazy(async () => {
  const module = await import('@/features/projects/ProjectsPage')
  return { default: module.ProjectsPage }
})
const ProjectDetailPage = lazy(async () => {
  const module = await import('@/features/projects/ProjectDetailPage')
  return { default: module.ProjectDetailPage }
})

export function ProjectsRoute({ children }: ExtensionComponentProps) {
  const navigate = useNavigate()
  const { t } = useI18n()
  return (
    <>
      <Suspense fallback={<div className="state-block" role="status">{t('platform.loadingProjects')}</div>}>
        <ProjectsPage onSelectProject={(projectId) => navigate(`/projects/${projectId}`)} />
      </Suspense>
      {children}
    </>
  )
}

export function ProjectDetailRoute({ children }: ExtensionComponentProps) {
  const navigate = useNavigate()
  const { projectId } = useParams<{ projectId: string }>()
  const { t } = useI18n()
  if (!projectId) {
    return null
  }
  return (
    <>
      <Suspense fallback={<div className="state-block" role="status">{t('platform.loadingProject')}</div>}>
        <ProjectDetailPage projectId={projectId} onBack={() => navigate('/projects')} />
      </Suspense>
      {children}
    </>
  )
}

/** Bridges the global projects WebSocket resource into feature-local invalidation hooks. */
export function ProjectsInvalidationBridge() {
  const events = useApplicationEvents()

  useEffect(
    () =>
      events.subscribe(
        { kind: 'projects' },
        {
          onSubscribed: () => notifyProjectsChanged(),
          onEvent: (name, data) => {
            if (name !== 'changed' || typeof data !== 'object' || data == null) {
              return
            }
            const projectId = (data as { projectId?: unknown }).projectId
            if (typeof projectId === 'string') {
              notifyProjectsChanged({ projectId })
            }
          },
          onResync: () => notifyProjectsChanged(),
          onError: () => notifyProjectsChanged(),
        },
      ),
    [events],
  )

  return null
}
