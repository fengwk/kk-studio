import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useLayoutEffect, useState } from 'react'
import { useBoundBranchPanel } from '@/features/thread/runtime/controllers/useBoundBranchPanel'
import { useBoundThreadPanelViews } from '@/features/thread/runtime/controllers/useBoundThreadPanelViews'
import { RootInteractionsProvider } from '@/features/thread/runtime/interactions/root-interactions'
import { ActiveThreadTree } from '@/features/thread/runtime/panes/ActiveThreadTree'
import { ConnectedThreadView } from '@/features/thread/runtime/panes/ChildThreadView'
import { CompactionBranchView } from '@/features/thread/runtime/panes/CompactionBranchView'
import { RootThreadControlArea } from '@/features/thread/runtime/panes/RootThreadControlArea'
import { ThreadPane } from '@/features/thread/runtime/panes/ThreadPane'
import { ThreadNavigationContext, type ThreadNavigationOptions } from '@/features/thread/runtime/panes/thread-navigation-context'
import { useActiveThreadTree } from '@/features/thread/runtime/queries/useActiveThreadTree'
import { EntryBranchContext, EntryForkContext } from '@/features/thread/runtime/thread-panel/entry-branch-context'
import { THREAD_COMMANDS, type ThreadCommand } from '@/features/thread/runtime/thread-panel/thread-commands'
import { useThreadProjection, type ThreadProjection } from '@/features/thread/runtime/useThreadProjection'
import { hasMessageContent } from '@/features/thread/composer/composer-parts'
import type { PaneRendererProps } from '@/features/workspace/pane-target'
import { workspaceKeys } from '@/features/workspace/queries'
import { isNotFoundError, request } from '@/shared/api/client'
import { getBuiltinModelSelection } from '@/shared/api/contracts/ai-runtime'
import type { EnvironmentSummaryDTO, WorkspaceFolderDTO, WorkspaceTreeDTO } from '@/shared/api/generated/contracts'
import { useI18n } from '@/shared/i18n'
import { queryKeys } from '@/shared/lib/query-keys'
import { Button } from '@/shared/ui/controls/Button'
import { useComposerModels } from './useComposerModels'
import { TurnCutDialog } from './TurnCutDialog'
import './thread-workspace.css'

type Props = PaneRendererProps<{ kind: 'thread'; threadId: string }>
const emptyEnvironments: EnvironmentSummaryDTO[] = []
const commands = THREAD_COMMANDS.filter(({ id }) => ['upload', 'models', 'yolo', 'stop', 'debug'].includes(id))

export function ThreadPaneRenderer(props: Props) {
  return <LoadedPane key={`${props.space.id}:${props.target.threadId}`} {...props} />
}

function LoadedPane(props: Props) {
  const projection = useThreadProjection(props.target.threadId)
  const environments = useQuery({
    queryKey: queryKeys.environments.summaries,
    queryFn: () => request('GET /api/platform/environment-summaries', {}),
  }).data ?? emptyEnvironments
  if (projection.isRoot === true && !projection.unavailable && !isNotFoundError(projection.snapshotQuery.error)) {
    return <RootPane {...props} projection={projection} environments={environments} />
  }
  return <ReadOnlyPane projection={projection} environments={environments} onUnsentChange={props.onUnsentChange} />
}

function ReadOnlyPane({ projection, environments, onUnsentChange }: {
  projection: ThreadProjection
  environments: EnvironmentSummaryDTO[]
  onUnsentChange: Props['onUnsentChange']
}) {
  useLayoutEffect(() => { onUnsentChange(false) }, [onUnsentChange])
  const unavailable = projection.unavailable || isNotFoundError(projection.snapshotQuery.error)
  return <ThreadPane projection={{ ...projection, unavailable }} environments={environments} activity={{}}
    views={{ initialConversationScrollTop: null }} />
}

function RootPane({ active, onUnsentChange, projection, environments, replace, space }: Props & {
  projection: ThreadProjection
  environments: EnvironmentSummaryDTO[]
}) {
  const { t } = useI18n()
  const cache = useQueryClient()
  const { threadId, timeline, sessionId } = projection
  const { controller, draft, settingsStatus, selectModel, selectEnvironment, setYoloEnabled, ...settings } = useBoundBranchPanel({ projection })
  const views = useBoundThreadPanelViews(threadId, projection)
  const tree = useActiveThreadTree(threadId)
  const models = useComposerModels()
  const [cut, setCut] = useState<{ kind: 'branch' | 'fork'; endEntryId: string; initialName: string }>()
  const [inspections, setInspections] = useState<({ threadId: string } & ThreadNavigationOptions)[]>([])
  const inspection = inspections.at(-1)
  const covered = inspection != null
  const debug = views.mode === 'debug'
  const unsent = hasMessageContent(controller.draft) || controller.pendingMessage != null
  useLayoutEffect(() => { onUnsentChange(unsent) }, [onUnsentChange, unsent])

  function inspect(id: string, options?: ThreadNavigationOptions) {
    setInspections((previous) => id === threadId && !options?.afterEntryId ? [] : [...previous, { threadId: id, ...options }])
  }
  function back() {
    setInspections((previous) => previous.slice(0, -1))
  }
  function startCut(kind: 'branch' | 'fork', endEntryId: string) {
    const folders = cache.getQueryData<WorkspaceTreeDTO>(workspaceKeys.tree(space.id))?.folders ?? []
    const session = findSession(folders, sessionId)
    const names = new Set(session?.threads?.map(({ title }) => title))
    let number = 1
    while (names.has(t('ai.runtime.branch.suggestedTitle', { number }))) number += 1
    const title = session?.title ?? projection.thread?.name ?? ''
    setCut({ kind, endEntryId, initialName: kind === 'branch'
      ? t('ai.runtime.branch.suggestedTitle', { number })
      : t('ai.runtime.branch.suggestedFork', { title }) })
  }
  function handleCommand({ id }: ThreadCommand) {
    if (id === 'debug') views.switchMode('debug')
    if (id === 'stop') void controller.stopThread()
  }

  return <ThreadNavigationContext.Provider value={inspect}>
    <div className="workspace-thread-view" hidden={covered}>
      <RootInteractionsProvider rootThreadId={threadId} approvalsVisible={!debug && !covered}>
        <EntryBranchContext.Provider value={(id) => startCut('branch', id)}>
          <EntryForkContext.Provider value={(id) => startCut('fork', id)}>
            {debug && <Button variant="secondary" onClick={() => views.switchMode('conversation')}>{t('ai.runtime.debug.close')}</Button>}
            <ThreadPane projection={projection} environments={environments} views={views}
              activity={{
                widgets: <ActiveThreadTree tree={tree} currentThreadId={threadId} />,
                actionError: controller.actionError ?? controller.draftRestoreError ?? settings.settingsError ?? settings.yoloError,
                onDismissActionError: () => {
                  controller.dismissActionError()
                  settings.dismissSettingsError()
                  settings.dismissYoloError()
                },
                onStop: () => void controller.stopThread(),
                stopPending: controller.stopPending || controller.stopReplayPending,
              }}
              controls={<RootThreadControlArea rootThreadId={threadId} messages={timeline.messages} queuedMessages={timeline.queuedMessages}
                hidden={debug || covered}
                notice={controller.pendingMessage?.unknownOutcome && <div className="thread-draft-notice">
                  <Button disabled={controller.pending} onClick={() => void controller.retryPendingMessage()}>{t('shared.conflict.retry')}</Button>
                  <Button variant="secondary" disabled={controller.pending} onClick={controller.abandonPendingMessage}>{t('shared.cancel')}</Button>
                </div>}
                composer={{
                  parts: controller.draft,
                  pending: controller.pending,
                  disabled: controller.disabled || !draft,
                  focusOnEscape: active,
                  scope: threadId,
                  onPartsChange: controller.setDraft,
                  onSubmit: (payload, localDraft) => void controller.submitMessage(payload, localDraft),
                  commands,
                  onCommand: handleCommand,
                  settings: draft && {
                    models: models.data ?? [],
                    modelSelection: getBuiltinModelSelection(draft.execution),
                    environmentName: draft.environmentName,
                    environments,
                    yoloEnabled: draft.yoloEnabled,
                    status: settingsStatus,
                    onModelChange: selectModel,
                    onEnvironmentChange: selectEnvironment,
                    onYoloChange: setYoloEnabled,
                  },
                }} />}
            />
          </EntryForkContext.Provider>
        </EntryBranchContext.Provider>
      </RootInteractionsProvider>
    </div>
    {inspection && (inspection.afterEntryId
      ? <CompactionBranchView key={inspection.afterEntryId} {...inspection} afterEntryId={inspection.afterEntryId} onBack={back} />
      : <ConnectedThreadView key={inspection.threadId} threadId={inspection.threadId} environments={environments} active={active}
        onParent={back} navigation={<Button variant="secondary" onClick={back}>{t('ai.runtime.childThread.back')}</Button>} />)}
    {cut && <TurnCutDialog kind={cut.kind} initialName={cut.initialName} turnEndEntryId={cut.endEntryId}
      sourceThreadId={threadId} sessionId={sessionId} spaceId={space.id} onClose={() => setCut(undefined)}
      onCreated={(createdThreadId) => replace({ kind: 'thread', threadId: createdThreadId })} />}
  </ThreadNavigationContext.Provider>
}

function findSession(folders: WorkspaceFolderDTO[], sessionId: string): WorkspaceFolderDTO['resources'][number] | undefined {
  for (const folder of folders) {
    const found = folder.resources.find(({ kind, id }) => kind === 'SESSION' && id === sessionId)
      ?? findSession(folder.children, sessionId)
    if (found) return found
  }
}
