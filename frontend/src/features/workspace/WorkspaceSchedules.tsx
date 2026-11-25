import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ChevronDown, ChevronRight, MoreHorizontal } from 'lucide-react'
import { ApiError, apiErrorDetail } from '@/shared/api/client'
import type { ChatSpaceDTO, ScheduleDTO, WorkspaceTreeDTO } from '@/shared/api/generated/contracts'
import { useI18n } from '@/shared/i18n'
import { createUuid } from '@/shared/lib/uuid'
import { Button } from '@/shared/ui/controls/Button'
import { IconButton } from '@/shared/ui/controls/IconButton'
import { Switch } from '@/shared/ui/controls/Switch'
import { ConfirmActionModal } from '@/shared/ui/overlays/ConfirmActionModal'
import { Menu } from '@/shared/ui/overlays/Menu'
import type { PaneTarget } from './pane-target'
import { workspaceKeys } from './queries'
import { ScheduleEditorDialog } from './ScheduleEditorDialog'
import {
  formatScheduleFullTime,
  formatScheduleNextRun,
  formatScheduleRunTime,
  INITIAL_HISTORY_CURSORS,
  popNewerCursor,
  pushOlderCursor,
  resolveRunReasonText,
  type HistoryCursorState,
} from './schedule-helpers'
import {
  deleteSchedule,
  listScheduleRuns,
  listSchedules,
  patchSchedule,
  runScheduleNow,
} from './workspace-api'
import './workspace-schedules.css'

export interface WorkspaceSchedulesProps {
  space: ChatSpaceDTO
  tree: WorkspaceTreeDTO
  open: (target: PaneTarget, anchor?: HTMLElement | null) => void
  report: (message: string) => void
}

function ScheduleHistoryPanel({
  id,
  spaceId,
  scheduleId,
  cursorState,
  onCursorChange,
  open,
}: {
  id: string
  spaceId: string
  scheduleId: string
  cursorState: HistoryCursorState
  onCursorChange: (updater: (prev: HistoryCursorState) => HistoryCursorState) => void
  open: (target: PaneTarget, anchor?: HTMLElement | null) => void
}) {
  const { locale, t } = useI18n()
  const newerRef = useRef<HTMLButtonElement>(null)
  const olderRef = useRef<HTMLButtonElement>(null)
  const pendingFocusRef = useRef<'newer' | 'older' | null>(null)
  const runsQuery = useQuery({
    queryKey: workspaceKeys.scheduleRuns(spaceId, scheduleId, cursorState.current),
    queryFn: ({ signal }) => listScheduleRuns(spaceId, scheduleId, cursorState.current, signal),
    placeholderData: previous => previous,
  })

  useEffect(() => {
    if (!runsQuery.data || runsQuery.isPlaceholderData || !pendingFocusRef.current) return
    const direction = pendingFocusRef.current
    pendingFocusRef.current = null
    const hasNewerPage = cursorState.previous.length > 0
    const hasOlderPage = Boolean(runsQuery.data.olderCursor)
    if (direction === 'older' && !hasOlderPage && hasNewerPage) {
      newerRef.current?.focus()
    } else if (direction === 'newer' && !hasNewerPage && hasOlderPage) {
      olderRef.current?.focus()
    }
  }, [cursorState.previous.length, runsQuery.data, runsQuery.isPlaceholderData])

  if (runsQuery.isError) {
    return (
      <div id={id} className="workspace-schedule-history">
        <p className="workspace-schedule-empty">{t('shared.requestFailed')}</p>
      </div>
    )
  }

  if (!runsQuery.data) {
    return (
      <div id={id} className="workspace-schedule-history">
        <p className="workspace-schedule-empty">{t('workspace.loading')}</p>
      </div>
    )
  }

  const { items, olderCursor } = runsQuery.data
  const hasNewer = cursorState.previous.length > 0
  const hasOlder = Boolean(olderCursor)

  return (
    <div id={id} className="workspace-schedule-history">
      {items.length === 0 ? (
        <p className="workspace-schedule-empty">{t('workspace.schedule.historyEmpty')}</p>
      ) : (
        <ul className="workspace-schedule-runs">
          {items.map(run => {
            const runTime = formatScheduleRunTime(run.scheduledAt, run.timeZone, locale)
            const fullTime = formatScheduleFullTime(run.scheduledAt, run.timeZone, locale)
            const isoTime = new Date(run.scheduledAt).toISOString()

            if (run.result === 'SUBMITTED' && run.thread) {
              const targetThread = run.thread
              return (
                <li key={run.id}>
                  <button
                    type="button"
                    className="workspace-schedule-record"
                    data-ui-focus="row"
                    onClick={event => open({ kind: 'thread', threadId: targetThread.id }, event.currentTarget)}
                  >
                    <time dateTime={isoTime} title={fullTime}>{runTime}</time>
                    <span className="workspace-schedule-record-main">
                      <span className="workspace-schedule-record-label" title={targetThread.title}>
                        {targetThread.title}
                      </span>
                      <ChevronRight aria-hidden="true" />
                    </span>
                  </button>
                </li>
              )
            }

            const statusLabel =
              run.result === 'SUBMITTED'
                ? t('workspace.schedule.threadDeleted')
                : resolveRunReasonText(run.result, run.reason, t)

            return (
              <li key={run.id}>
                <div className="workspace-schedule-record">
                  <time dateTime={isoTime} title={fullTime}>{runTime}</time>
                  <span className="workspace-schedule-record-muted">{statusLabel}</span>
                </div>
              </li>
            )
          })}
        </ul>
      )}

      {(hasNewer || hasOlder) && (
        <div className="workspace-schedule-pager">
          <button
            ref={newerRef}
            type="button"
            className="workspace-schedule-pager-btn"
            data-ui-focus="row"
            disabled={!hasNewer}
            onClick={() => {
              if (!runsQuery.isFetching) {
                pendingFocusRef.current = 'newer'
                onCursorChange(popNewerCursor)
              }
            }}
          >
            {t('workspace.schedule.newer')}
          </button>
          <button
            ref={olderRef}
            type="button"
            className="workspace-schedule-pager-btn"
            data-ui-focus="row"
            disabled={!hasOlder}
            onClick={() => {
              if (!runsQuery.isFetching && olderCursor) {
                pendingFocusRef.current = 'older'
                onCursorChange(prev => pushOlderCursor(prev, olderCursor))
              }
            }}
          >
            {t('workspace.schedule.older')}
          </button>
        </div>
      )}
    </div>
  )
}

export function WorkspaceSchedules({ space, tree, open, report }: WorkspaceSchedulesProps) {
  const { locale, t } = useI18n()
  const queryClient = useQueryClient()
  const baseId = useId().replace(/[^a-zA-Z0-9_-]/gu, '')

  const schedulesQuery = useQuery({
    queryKey: workspaceKeys.schedules(space.id),
    queryFn: ({ signal }) => listSchedules(space.id, signal),
  })

  const [expandedId, setExpandedId] = useState<string | null>(null)
  const [cursorState, setCursorState] = useState<HistoryCursorState>(INITIAL_HISTORY_CURSORS)
  const [menu, setMenu] = useState<{ id: string; anchor: HTMLButtonElement } | null>(null)
  const [togglingId, setTogglingId] = useState<string | null>(null)
  const [runningId, setRunningId] = useState<string | null>(null)
  const [editor, setEditor] = useState<{ schedule: ScheduleDTO | null } | null>(null)
  const [deleting, setDeleting] = useState<{
    schedule: ScheduleDTO
    busy?: boolean
    error?: string
  } | null>(null)

  const menuButtonsRef = useRef(new Map<string, HTMLButtonElement>())
  const newButtonId = `${baseId}-new-schedule`

  const schedules = useMemo(() => schedulesQuery.data ?? [], [schedulesQuery.data])

  // Discard expanded history state if the expanded task no longer exists in the authoritative snapshot.
  useEffect(() => {
    if (expandedId && schedulesQuery.data && !schedulesQuery.data.some(item => item.id === expandedId)) {
      setExpandedId(null)
      setCursorState(INITIAL_HISTORY_CURSORS)
    }
  }, [expandedId, schedulesQuery.data])

  const latestEditingVersion = editor?.schedule
    ? schedules.find(item => item.id === editor.schedule!.id)?.version
    : undefined
  const latestDeletingVersion = deleting
    ? schedules.find(item => item.id === deleting.schedule.id)?.version
    : undefined

  function toggleExpand(scheduleId: string) {
    setCursorState(INITIAL_HISTORY_CURSORS)
    setExpandedId(current => (current === scheduleId ? null : scheduleId))
  }

  async function toggleSchedule(schedule: ScheduleDTO, enabled: boolean) {
    if (togglingId === schedule.id) return
    setTogglingId(schedule.id)
    try {
      await patchSchedule(space.id, schedule.id, {
        expectedVersion: schedule.version,
        enabled,
      })
      await queryClient.invalidateQueries({ queryKey: workspaceKeys.schedules(space.id), exact: true })
    } catch (error) {
      if (error instanceof ApiError && (error.reason === 'VERSION_CONFLICT' || error.code === 'VERSION_CONFLICT')) {
        await queryClient.invalidateQueries({ queryKey: workspaceKeys.schedules(space.id), exact: true })
        report(t('workspace.versionConflict'))
      } else {
        report(error instanceof ApiError ? apiErrorDetail(error) ?? t('shared.requestFailed') : t('shared.requestFailed'))
      }
    } finally {
      setTogglingId(current => (current === schedule.id ? null : current))
    }
  }

  async function handleRunNow(schedule: ScheduleDTO) {
    if (runningId === schedule.id) return
    setRunningId(schedule.id)
    try {
      await runScheduleNow(space.id, schedule.id, createUuid())
      setExpandedId(schedule.id)
      setCursorState(INITIAL_HISTORY_CURSORS)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: workspaceKeys.scheduleRuns(space.id, schedule.id, null), exact: true }),
        queryClient.invalidateQueries({ queryKey: workspaceKeys.schedules(space.id), exact: true }),
      ])
    } catch (error) {
      report(error instanceof ApiError ? apiErrorDetail(error) ?? t('shared.requestFailed') : t('shared.requestFailed'))
    } finally {
      setRunningId(current => (current === schedule.id ? null : current))
    }
  }

  function handleRequestDelete(schedule: ScheduleDTO) {
    // Move focus to the task menu button before closing editor so ConfirmActionModal restores focus there on Cancel.
    menuButtonsRef.current.get(schedule.id)?.focus()
    setEditor(null)
    setDeleting({ schedule })
  }

  async function handleConfirmDelete() {
    if (!deleting || deleting.busy) return
    const target = deleting.schedule
    setDeleting({ ...deleting, busy: true, error: undefined })
    try {
      await deleteSchedule(space.id, target.id, latestDeletingVersion ?? target.version)
      if (expandedId === target.id) {
        setExpandedId(null)
        setCursorState(INITIAL_HISTORY_CURSORS)
      }
      setDeleting(null)
      await queryClient.invalidateQueries({ queryKey: workspaceKeys.schedules(space.id), exact: true })
      queueMicrotask(() => document.getElementById(newButtonId)?.focus())
    } catch (error) {
      if (error instanceof ApiError && (error.reason === 'VERSION_CONFLICT' || error.code === 'VERSION_CONFLICT')) {
        await queryClient.invalidateQueries({ queryKey: workspaceKeys.schedules(space.id), exact: true })
        setDeleting({ schedule: target, busy: false, error: t('workspace.versionConflict') })
      } else {
        setDeleting({
          schedule: target,
          busy: false,
          error: error instanceof ApiError ? apiErrorDetail(error) ?? t('shared.requestFailed') : t('shared.requestFailed'),
        })
      }
    }
  }

  const now = schedulesQuery.dataUpdatedAt

  return (
    <div className="workspace-schedules">
      <div className="workspace-schedule-body">
        {schedulesQuery.isError ? (
          <p className="workspace-schedule-empty">{t('workspace.unavailable')}</p>
        ) : schedulesQuery.isLoading ? (
          <p className="workspace-schedule-empty">{t('workspace.loading')}</p>
        ) : schedules.length === 0 ? (
          <p className="workspace-schedule-empty">{t('workspace.schedule.empty')}</p>
        ) : (
          <ul className="workspace-schedule-list" aria-label={t('workspace.schedules')}>
            {schedules.map(schedule => {
              const isExpanded = expandedId === schedule.id
              const historyId = `${baseId}-history-${schedule.id}`
              const showNextRun = schedule.enabled && schedule.nextDueAt != null && schedule.nextDueAt > now
              return (
                <li key={schedule.id} className="workspace-schedule-card">
                  <div className="workspace-schedule-heading">
                    <div className="workspace-schedule-title" title={schedule.title}>
                      {schedule.title}
                    </div>
                    <Switch
                      checked={schedule.enabled}
                      label={t('workspace.schedule.toggle', { name: schedule.title })}
                      disabled={togglingId === schedule.id}
                      onChange={next => void toggleSchedule(schedule, next)}
                    />
                    <div className="workspace-schedule-menu">
                      <IconButton
                        ref={element => {
                          if (element) menuButtonsRef.current.set(schedule.id, element)
                          else menuButtonsRef.current.delete(schedule.id)
                        }}
                        className="workspace-touch"
                        label={t('workspace.actions', { name: schedule.title })}
                        aria-expanded={menu?.id === schedule.id}
                        onClick={event => {
                          const anchor = event.currentTarget
                          setMenu(current => (current?.id === schedule.id ? null : { id: schedule.id, anchor }))
                        }}
                      >
                        <MoreHorizontal />
                      </IconButton>
                      {menu?.id === schedule.id && (
                        <Menu
                          returnFocusTo={menu.anchor}
                          onClose={() => setMenu(null)}
                          items={[
                            {
                              label: t('workspace.schedule.runNow'),
                              disabled: runningId === schedule.id,
                              onSelect: () => void handleRunNow(schedule),
                            },
                            {
                              label: t('workspace.schedule.edit'),
                              onSelect: () => setEditor({ schedule }),
                            },
                          ]}
                        />
                      )}
                    </div>
                  </div>

                  {showNextRun && schedule.nextDueAt != null && (
                    <time
                      className="workspace-schedule-next"
                      dateTime={new Date(schedule.nextDueAt).toISOString()}
                      title={formatScheduleFullTime(schedule.nextDueAt, schedule.timeZone, locale)}
                    >
                      {t('workspace.schedule.nextRun', {
                        time: formatScheduleNextRun(schedule.nextDueAt, schedule.timeZone, locale),
                      })}
                    </time>
                  )}

                  <button
                    type="button"
                    className="workspace-schedule-disclosure"
                    data-ui-focus="row"
                    aria-expanded={isExpanded}
                    aria-controls={historyId}
                    aria-label={t('workspace.schedule.historyFor', { name: schedule.title })}
                    onClick={() => toggleExpand(schedule.id)}
                  >
                    <span>{t('workspace.schedule.history')}</span>
                    <ChevronDown aria-hidden="true" />
                  </button>

                  {isExpanded && (
                    <ScheduleHistoryPanel
                      id={historyId}
                      spaceId={space.id}
                      scheduleId={schedule.id}
                      cursorState={cursorState}
                      onCursorChange={setCursorState}
                      open={open}
                    />
                  )}
                </li>
              )
            })}
          </ul>
        )}
      </div>

      <Button
        id={newButtonId}
        type="button"
        variant="secondary"
        className="workspace-schedule-new workspace-touch"
        onClick={() => setEditor({ schedule: null })}
      >
        {t('workspace.schedule.new')}
      </Button>

      {editor && (
        <ScheduleEditorDialog
          space={space}
          tree={tree}
          schedule={editor.schedule}
          latestVersion={latestEditingVersion}
          onClose={() => setEditor(null)}
          onSaved={() => {
            setEditor(null)
            void queryClient.invalidateQueries({ queryKey: workspaceKeys.schedules(space.id), exact: true })
          }}
          onRequestDelete={handleRequestDelete}
        />
      )}

      <ConfirmActionModal
        pending={Boolean(deleting?.busy)}
        error={deleting?.error}
        onClose={() => setDeleting(null)}
        modal={
          deleting
            ? {
                title: t('workspace.schedule.deleteTitle', { name: deleting.schedule.title }),
                description: t('workspace.schedule.deletePrompt'),
                tone: 'danger',
                confirmLabel: t('workspace.schedule.delete'),
                onConfirm: () => void handleConfirmDelete(),
              }
            : null
        }
      />
    </div>
  )
}
