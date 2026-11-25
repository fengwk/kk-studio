import { createContext, useContext } from 'react'
import type { PaneTarget } from '@/features/workspace/pane-target'
import type { EnvironmentSummaryDTO } from '@/shared/api/generated/contracts'
import type { ApplicationEventConnectionState } from '@/shared/app-events/connection'
import type { IdeBrowserSessionStatus } from './ide-browser-session'
import type { useIdeClaimReconciliation } from './useIdeClaimReconciliation'

export type VsCodeTarget = Extract<PaneTarget, { kind: 'vscode' }>

type IdeWorkspace = {
  environments: EnvironmentSummaryDTO[]
  sessionStatus: IdeBrowserSessionStatus
  connectionState: ApplicationEventConnectionState
  storageUnavailable: boolean
  recoveryError: boolean
  resolveTarget: (environmentId: string, existingViewId?: string) => VsCodeTarget | null
  canOpenAttachment: (environmentId: string) => boolean
  openAttachment: (environmentId: string, sessionId: string, resourceId: string) => Promise<void>
} & Pick<ReturnType<typeof useIdeClaimReconciliation>, 'views' | 'ensureView' | 'releaseFrame' | 'closeEnvironment' | 'changeDirectory' | 'handleIframeLoaded' | 'retryClaim' | 'dismissCloseError' | 'setPendingShell' | 'maybeRunPendingShell'>

export const IdeWorkspaceContext = createContext<IdeWorkspace | null>(null)

export function useIdeWorkspace(): IdeWorkspace {
  const context = useContext(IdeWorkspaceContext)
  if (context == null) throw new Error('IdeWorkspaceProvider is required')
  return context
}
