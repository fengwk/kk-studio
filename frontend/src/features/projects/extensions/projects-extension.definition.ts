import {
  ProjectDetailRoute,
  ProjectsInvalidationBridge,
  ProjectsRoute,
} from '@/features/projects/extensions/projects-extension'
import type { TrustedReactExtension } from '@/platform/extensions/types'

export const projectsExtension: TrustedReactExtension = {
  id: 'builtin.projects',
  pages: [
    {
      id: 'projects.home',
      path: 'projects',
      component: ProjectsRoute,
      navGroup: 'projects',
      priority: 80,
    },
    {
      id: 'projects.detail',
      path: 'projects/:projectId',
      component: ProjectDetailRoute,
      navGroup: 'projects',
      priority: 80,
    },
  ],
  overlays: [{ id: 'projects.invalidation', component: ProjectsInvalidationBridge }],
}
