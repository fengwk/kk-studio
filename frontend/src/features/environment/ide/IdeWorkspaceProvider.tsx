import { useCallback, useEffect, useMemo, useRef, useState, type PropsWithChildren } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useApplicationEventConnectionState } from '@/shared/app-events'
import { useResourceSync } from '@/shared/app-events/useResourceSync'
import { queryKeys } from '@/shared/lib/query-keys'
import { createUuid, isCanonicalUuid } from '@/shared/lib/uuid'
import { environmentService } from '@/features/environment/environment-service'
import { environmentDisplayStatus } from '@/features/environment/environment-utils'
import { useEnvironmentStatusRecheck } from '@/features/environment/useEnvironmentStatusRecheck'
import { IdeWorkspaceContext, type VsCodeTarget } from './ide-workspace-context'
import { initializeIdeSession, type IdeSessionStorage } from './ide-session-state'
import { useIdeBrowserSession } from './ide-browser-session'
import { useIdeClaimReconciliation } from './useIdeClaimReconciliation'
import { isBoundToConnection } from './ide-view-state'
import { openIdeAttachment } from './environment-ide-service'

export function IdeWorkspaceProvider({ children, storage }: PropsWithChildren<{ storage?: IdeSessionStorage }>) {
  const queryClient = useQueryClient()
  const connectionState = useApplicationEventConnectionState()
  const [initial] = useState(() => initializeIdeSession(storage))
  const [storageUnavailable, setStorageUnavailable] = useState(!initial.sessionSaved && !initial.recoveryError)
  const lifecycle = useRef<{
    clone: () => void; restore: () => void; suspend: () => void; reconcileEnvironments: (available: Set<string>) => void
  } | null>(null)
  const query = useQuery({
    queryKey: queryKeys.environments.summaries,
    queryFn: environmentService.listEnvironmentSummaries,
  })
  const invalidate = useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: queryKeys.environments.summaries })
  }, [queryClient])
  useResourceSync({ kind: 'environments' }, { sync: invalidate, syncOn: (name) => name === 'changed' })
  const environments = useMemo(() => [...(query.data ?? [])].sort((a, b) => a.name.localeCompare(b.name)), [query.data])
  useEnvironmentStatusRecheck(environments, invalidate)
  const environmentMap = useMemo(() => new Map(environments.map((environment) => [environment.id, environment])), [environments])
  const currentEnvironments = useRef(environmentMap)
  useEffect(() => { currentEnvironments.current = environmentMap }, [environmentMap])
  const getEnvironmentMap = useCallback(() => currentEnvironments.current, [])
  const session = useIdeBrowserSession({
    initial, storage,
    onClone: () => lifecycle.current?.clone(),
    onRestore: () => lifecycle.current?.restore(),
    onSuspend: () => lifecycle.current?.suspend(),
  })
  const claim = useIdeClaimReconciliation({
    storage, initial, session, getEnvironmentMap, onStorageStatusChange: setStorageUnavailable,
  })
  useEffect(() => {
    lifecycle.current = {
      clone: claim.resetForClone, restore: claim.restoreUsage, suspend: claim.withdrawUsage,
      reconcileEnvironments: claim.reconcileEnvironments,
    }
  })
  useEffect(() => {
    if (query.isSuccess) lifecycle.current?.reconcileEnvironments(new Set(environmentMap.keys()))
  }, [query.isSuccess, environmentMap])

  function resolveTarget(environmentId: string, existingViewId?: string): VsCodeTarget | null {
    const environment = currentEnvironments.current.get(environmentId)
    if (initial.recoveryError || environment == null || environmentDisplayStatus(environment) !== 'READY') return null
    const viewId = existingViewId ?? Object.values(claim.viewsRef.current).find((view) => (
      view.environmentId === environmentId && view.status !== 'detached' && view.status !== 'gone'
    ))?.viewId ?? createUuid()
    return { kind: 'vscode', environmentId, viewId }
  }

  function attachmentView(environmentId: string) {
    const environment = currentEnvironments.current.get(environmentId)
    const identity = claim.identity
    if (initial.recoveryError || identity == null || environment == null || environmentDisplayStatus(environment) !== 'READY') return undefined
    return Object.values(claim.viewsRef.current).find((view) => (
      view.environmentId === environmentId && !view.closeInFlight && view.bindingEpoch != null
      && view.daemonInstanceId != null && isBoundToConnection(view, identity.eventNodeId, identity.connectionId)
    ))
  }

  async function openAttachment(environmentId: string, sessionId: string, resourceId: string) {
    const view = attachmentView(environmentId)
    if (view == null || !isCanonicalUuid(sessionId) || !isCanonicalUuid(resourceId)) {
      throw new Error('IDE view is not ready for this attachment')
    }
    await openIdeAttachment(environmentId, view.viewId, sessionId, resourceId, {
      browserSessionId: session.browserSessionId,
      bindingEpoch: view.bindingEpoch!,
      expectedDaemonInstanceId: view.daemonInstanceId!,
    })
  }

  return (
    <IdeWorkspaceContext.Provider value={{
      ...claim, environments, resolveTarget, openAttachment, canOpenAttachment: (id) => attachmentView(id) != null,
      connectionState, sessionStatus: session.status, recoveryError: initial.recoveryError,
      storageUnavailable: storageUnavailable || (!session.sessionSaved && !initial.recoveryError),
    }}>
      {children}
    </IdeWorkspaceContext.Provider>
  )
}
