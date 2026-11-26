import { useEffect, useId, useMemo, useState, type FormEvent } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, apiErrorDetail } from '@/shared/api/client'
import type {
  ChatSpaceDTO,
  ScheduleDTO,
  SchedulePreviewDTO,
  ScheduleTargetType,
  ScheduleTriggerDTO,
  ScheduleTriggerFrequency,
  ScheduleTriggerType,
  WorkspaceTreeDTO,
} from '@/shared/api/generated/contracts'
import { useI18n } from '@/shared/i18n'
import { createUuid } from '@/shared/lib/uuid'
import { Button } from '@/shared/ui/controls/Button'
import { FieldError } from '@/shared/ui/controls/FieldError'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { TextArea } from '@/shared/ui/controls/TextArea'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { workspaceKeys } from './queries'
import { ScheduleTargetField } from './ScheduleTargetField'
import { ScheduleTimeField } from './ScheduleTimeField'
import {
  buildTargetFromDraft,
  buildTimeZoneOptions,
  buildTriggerFromDraft,
  extractTurnCuts,
  flattenFolders,
  formatScheduleFullTime,
  formatScheduleRunTime,
  getLocalTimeZone,
  listTreeSessions,
  todayInTimeZone,
  validateScheduleTitle,
} from './schedule-helpers'
import {
  createSchedule,
  getThreadEntriesSnapshot,
  listAgentSummaries,
  listSchedules,
  patchSchedule,
  previewSchedule,
} from './workspace-api'
import { isVersionConflict } from './workspace-action-retry'

type FieldErrorKey =
  | 'title'
  | 'message'
  | 'folderId'
  | 'agentName'
  | 'sessionId'
  | 'sourceThreadId'
  | 'turnEndEntryId'
  | 'threadId'
  | 'onceDate'
  | 'onceTime'
  | 'repeatTime'
  | 'minute'
  | 'cron'

export interface ScheduleEditorDialogProps {
  space: ChatSpaceDTO
  tree: WorkspaceTreeDTO
  schedule: ScheduleDTO | null
  onReloaded: (schedule: ScheduleDTO) => void
  onClose: () => void
  onSaved: (schedule: ScheduleDTO) => void
  onRequestDelete: (schedule: ScheduleDTO) => void
}

export function ScheduleEditorDialog({
  space,
  tree,
  schedule,
  onReloaded,
  onClose,
  onSaved,
  onRequestDelete,
}: ScheduleEditorDialogProps) {
  const { locale, t } = useI18n()
  const queryClient = useQueryClient()
  const baseId = useId().replace(/[^a-zA-Z0-9_-]/gu, '')
  const isEdit = schedule !== null
  const localTz = useMemo(() => getLocalTimeZone(), [])
  const folders = useMemo(() => flattenFolders(tree.folders), [tree.folders])
  const sessions = useMemo(() => listTreeSessions(tree.folders), [tree.folders])

  const agentsQuery = useQuery({
    queryKey: workspaceKeys.agentSummaries(),
    queryFn: ({ signal }) => listAgentSummaries(signal),
  })

  const initialTimeZone = schedule?.timeZone ?? localTz
  const initialSessionId =
    schedule?.target.sessionId && sessions.some(s => s.id === schedule.target.sessionId)
      ? schedule.target.sessionId
      : (schedule?.target.sessionId ?? sessions[0]?.id ?? '')
  const initialSession = sessions.find(s => s.id === initialSessionId)

  const [title, setTitle] = useState(schedule?.title ?? '')
  const [message, setMessage] = useState(schedule?.message ?? '')
  const [targetType, setTargetType] = useState<ScheduleTargetType>(schedule?.target.type ?? 'NEW_SESSION')
  const [folderId, setFolderId] = useState(schedule?.target.folderId ?? folders[0]?.id ?? '')
  const [agentName, setAgentName] = useState(schedule?.target.agentName ?? space.defaultAgentName ?? '')
  const [sessionId, setSessionId] = useState(initialSessionId)
  const [sourceThreadId, setSourceThreadId] = useState(
    schedule?.target.sourceThreadId ?? initialSession?.threads[0]?.id ?? '',
  )
  const [turnEndEntryId, setTurnEndEntryId] = useState(schedule?.target.turnEndEntryId ?? '')
  const [threadId, setThreadId] = useState(
    schedule?.target.threadId ?? initialSession?.threads[0]?.id ?? '',
  )

  const [triggerType, setTriggerType] = useState<ScheduleTriggerType>(schedule?.trigger.type ?? 'REPEAT')
  const [frequency, setFrequency] = useState<ScheduleTriggerFrequency>(schedule?.trigger.frequency ?? 'DAILY')
  const [weekday, setWeekday] = useState(schedule?.trigger.weekday ?? 'MONDAY')
  const [repeatTime, setRepeatTime] = useState(
    schedule?.trigger.type === 'REPEAT' && schedule.trigger.time ? schedule.trigger.time : '09:00',
  )
  const [minute, setMinute] = useState(
    schedule?.trigger.minute != null ? String(schedule.trigger.minute) : '0',
  )
  const [cron, setCron] = useState(schedule?.trigger.cron ?? '0 9 * * 1-5')
  const [onceDate, setOnceDate] = useState(
    schedule?.trigger.type === 'ONCE' && schedule.trigger.localDate
      ? schedule.trigger.localDate
      : todayInTimeZone(initialTimeZone),
  )
  const [onceTime, setOnceTime] = useState(
    schedule?.trigger.type === 'ONCE' && schedule.trigger.time ? schedule.trigger.time : '09:00',
  )
  const [timeZone, setTimeZone] = useState(initialTimeZone)

  const [fieldErrors, setFieldErrors] = useState<Partial<Record<FieldErrorKey, string>>>({})
  const [formError, setFormError] = useState('')
  const [saving, setSaving] = useState(false)
  const [conflict, setConflict] = useState(false)

  async function reloadSchedule() {
    if (!schedule || saving) return
    setSaving(true)
    setFormError('')
    try {
      const schedules = await queryClient.fetchQuery({
        queryKey: workspaceKeys.schedules(space.id),
        queryFn: ({ signal }) => listSchedules(space.id, signal),
        staleTime: 0,
      })
      const latest = schedules.find(item => item.id === schedule.id)
      if (latest) onReloaded(latest)
      else setFormError(t('workspace.schedule.noLongerExists'))
    } catch {
      setFormError(t('shared.requestFailed'))
    } finally {
      setSaving(false)
    }
  }
  const [idempotencyKey, setIdempotencyKey] = useState(() => createUuid())
  const [hadWriteError, setHadWriteError] = useState(false)

  // Fill default agent once agent summaries arrive if not yet selected.
  useEffect(() => {
    const agents = agentsQuery.data
    if (!agents || agents.length === 0) return
    if (!agentName || (!isEdit && !agents.some(a => a.name === agentName))) {
      const preferred =
        space.defaultAgentName && agents.some(a => a.name === space.defaultAgentName)
          ? space.defaultAgentName
          : agents[0]!.name
      setAgentName(preferred)
    }
  }, [agentName, agentsQuery.data, isEdit, space.defaultAgentName])

  const selectedSession = useMemo(
    () => sessions.find(s => s.id === sessionId),
    [sessionId, sessions],
  )

  // Keep branch / continue thread selection coherent when switching session.
  function handleSessionChange(nextSessionId: string) {
    setSessionId(nextSessionId)
    const nextSession = sessions.find(s => s.id === nextSessionId)
    const firstThreadId = nextSession?.threads[0]?.id ?? ''
    setSourceThreadId(firstThreadId)
    setTurnEndEntryId('')
    setThreadId(firstThreadId)
    clearErrors('sessionId', 'sourceThreadId', 'turnEndEntryId', 'threadId')
    bumpIdempotencyAfterError()
  }

  const branchSnapshotQuery = useQuery({
    queryKey: workspaceKeys.threadEntries(sourceThreadId),
    queryFn: ({ signal }) => getThreadEntriesSnapshot(sourceThreadId, signal),
    enabled: targetType === 'BRANCH' && Boolean(sourceThreadId),
  })

  const turnCuts = useMemo(
    () => extractTurnCuts(branchSnapshotQuery.data?.entries ?? []),
    [branchSnapshotQuery.data],
  )

  useEffect(() => {
    if (targetType !== 'BRANCH' || !branchSnapshotQuery.data) return
    if (turnCuts.length === 0) {
      setTurnEndEntryId('')
      return
    }
    if (!turnCuts.some(cut => cut.entryId === turnEndEntryId)) {
      setTurnEndEntryId(turnCuts[0]!.entryId)
    }
  }, [branchSnapshotQuery.data, targetType, turnCuts, turnEndEntryId])

  function bumpIdempotencyAfterError() {
    if (hadWriteError) {
      setHadWriteError(false)
      setIdempotencyKey(createUuid())
    }
  }

  function clearErrors(...keys: FieldErrorKey[]) {
    setFormError('')
    setFieldErrors(previous => {
      let changed = false
      const next = { ...previous }
      for (const key of keys) {
        if (next[key]) {
          delete next[key]
          changed = true
        }
      }
      return changed ? next : previous
    })
  }

  const triggerCandidate = useMemo(
    () =>
      buildTriggerFromDraft({
        triggerType,
        frequency,
        weekday,
        repeatTime,
        minute,
        cron,
        onceDate,
        onceTime,
      }),
    [cron, frequency, minute, onceDate, onceTime, repeatTime, triggerType, weekday],
  )

  const [previewState, setPreviewState] = useState<{
    key: string
    data?: SchedulePreviewDTO
    error?: string
  }>({ key: '' })

  const previewKey = triggerCandidate.ok
    ? JSON.stringify({ trigger: triggerCandidate.trigger, timeZone })
    : ''

  useEffect(() => {
    if (!triggerCandidate.ok) {
      setPreviewState({ key: '' })
      return
    }
    const trigger: ScheduleTriggerDTO = triggerCandidate.trigger
    const requestKey = previewKey
    const controller = new AbortController()
    const delay = trigger.type === 'REPEAT' && (trigger.frequency === 'CRON' || trigger.frequency === 'HOURLY') ? 160 : 0
    const timer = setTimeout(() => {
      void previewSchedule(space.id, { trigger, timeZone }, controller.signal)
        .then(data => {
          if (!controller.signal.aborted) setPreviewState({ key: requestKey, data })
        })
        .catch((error: unknown) => {
          if (controller.signal.aborted) return
          const msg =
            error instanceof ApiError && (error.reason === 'INVALID_SCHEDULE' || error.status === 400)
              ? t(trigger.frequency === 'CRON' ? 'workspace.schedule.cronInvalid' : 'workspace.schedule.invalid')
              : t('shared.requestFailed')
          setPreviewState({ key: requestKey, error: msg })
        })
    }, delay)
    return () => {
      clearTimeout(timer)
      controller.abort()
    }
  }, [previewKey, space.id, t, timeZone, triggerCandidate])

  const previewResult = useMemo(() => {
    if (!triggerCandidate.ok) {
      return { text: t('workspace.schedule.previewPrompt'), invalid: false }
    }
    if (previewState.key !== previewKey) {
      return { text: t('workspace.schedule.previewPrompt'), invalid: false }
    }
    if (previewState.error) {
      return { text: previewState.error, invalid: true }
    }
    if (!previewState.data) {
      return { text: t('workspace.schedule.previewPrompt'), invalid: false }
    }
    const next = previewState.data.next
    if (next.length === 0) {
      return {
        text: t(triggerType === 'ONCE' ? 'workspace.schedule.pastTime' : 'workspace.schedule.noUpcomingRuns'),
        invalid: true,
      }
    }
    if (triggerType === 'ONCE') {
      return {
        text: t('workspace.schedule.previewOnce', {
          time: formatScheduleRunTime(next[0]!, timeZone, locale),
          zone: timeZone,
        }),
        invalid: false,
      }
    }
    const separator = locale === 'zh-CN' ? '、' : ', '
    return {
      text: t('workspace.schedule.previewRepeat', {
        times: next.slice(0, 3).map(ts => formatScheduleRunTime(ts, timeZone, locale)).join(separator),
        zone: timeZone,
      }),
      invalid: false,
    }
  }, [locale, previewKey, previewState, t, timeZone, triggerCandidate.ok, triggerType])

  const timeZones = useMemo(
    () => buildTimeZoneOptions(localTz, schedule?.timeZone),
    [localTz, schedule?.timeZone],
  )

  const folderOptions = useMemo(
    () => folders.map(folder => ({ value: folder.id, label: folder.label })),
    [folders],
  )
  const agentOptions = useMemo(() => {
    const list = (agentsQuery.data ?? []).map(agent => ({ value: agent.name, label: agent.name }))
    if (agentName && !list.some(item => item.value === agentName)) {
      return [{ value: agentName, label: agentName }, ...list]
    }
    return list
  }, [agentName, agentsQuery.data])
  const sessionOptions = useMemo(
    () => sessions.map(item => ({ value: item.id, label: item.label })),
    [sessions],
  )
  const threadOptions = useMemo(
    () => (selectedSession?.threads ?? []).map(thread => ({ value: thread.id, label: thread.title })),
    [selectedSession],
  )
  const turnCutOptions = useMemo(
    () =>
      turnCuts.map(cut => ({
        value: cut.entryId,
        label: cut.summary
          ? t('workspace.schedule.turnOption', { number: String(cut.turnNumber), summary: cut.summary })
          : t('workspace.schedule.turnOptionFallback', { number: String(cut.turnNumber) }),
      })),
    [t, turnCuts],
  )

  function focusErrorField(key: FieldErrorKey) {
    document.getElementById(`${baseId}-${key}`)?.focus()
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (saving || conflict) return

    const errors: Partial<Record<FieldErrorKey, string>> = {}
    let firstErrorKey: FieldErrorKey | undefined
    const setErr = (key: FieldErrorKey, msg: string) => {
      if (!errors[key]) {
        errors[key] = msg
        firstErrorKey ??= key
      }
    }

    const titleErrKey = validateScheduleTitle(title)
    if (titleErrKey) setErr('title', t(titleErrKey))

    const targetResult = buildTargetFromDraft({
      targetType,
      folderId,
      agentName,
      sessionId,
      sourceThreadId,
      turnEndEntryId,
      threadId,
    })
    if (!targetResult.ok) setErr(targetResult.field, t(targetResult.errorKey))

    if (!message.trim()) setErr('message', t('workspace.schedule.messageRequired'))

    if (!triggerCandidate.ok) setErr(triggerCandidate.field, t(triggerCandidate.errorKey))

    if (firstErrorKey) {
      setFieldErrors(errors)
      focusErrorField(firstErrorKey)
      return
    }

    if (!targetResult.ok || !triggerCandidate.ok) return

    setSaving(true)
    setFormError('')
    try {
      // Ensure preview matches the final trigger before creating/updating so past ONCE or bad cron fails with field context.
      let effectivePreview = previewState.key === previewKey ? previewState.data : undefined
      if (!effectivePreview) {
        try {
          effectivePreview = await previewSchedule(space.id, { trigger: triggerCandidate.trigger, timeZone })
          setPreviewState({ key: previewKey, data: effectivePreview })
        } catch (error) {
          if (error instanceof ApiError && (error.reason === 'INVALID_SCHEDULE' || error.status === 400)) {
            const errKey: FieldErrorKey = triggerType === 'ONCE' ? 'onceTime' : frequency === 'CRON' ? 'cron' : 'repeatTime'
            const msg = t(frequency === 'CRON' ? 'workspace.schedule.cronInvalid' : 'workspace.schedule.invalid')
            setFieldErrors({ [errKey]: msg })
            setPreviewState({ key: previewKey, error: msg })
            focusErrorField(errKey)
            return
          }
          throw error
        }
      }
      if (effectivePreview.next.length === 0) {
        const errKey: FieldErrorKey = triggerType === 'ONCE' ? 'onceTime' : frequency === 'CRON' ? 'cron' : 'repeatTime'
        const msg = t(triggerType === 'ONCE' ? 'workspace.schedule.pastTime' : 'workspace.schedule.noUpcomingRuns')
        setFieldErrors({ [errKey]: msg })
        focusErrorField(errKey)
        return
      }

      const saved = isEdit
        ? await patchSchedule(space.id, schedule.id, {
            expectedVersion: schedule.version,
            title: title.trim(),
            message: message.trim(),
            enabled: schedule.enabled,
            trigger: triggerCandidate.trigger,
            timeZone,
            target: targetResult.target,
          })
        : await createSchedule(
            space.id,
            {
              title: title.trim(),
              message: message.trim(),
              enabled: true,
              trigger: triggerCandidate.trigger,
              timeZone,
              target: targetResult.target,
            },
            idempotencyKey,
          )
      onSaved(saved)
    } catch (error) {
      setHadWriteError(true)
      if (error instanceof ApiError) {
        const reason = error.reason ?? error.code
        if (isVersionConflict(error)) {
          await queryClient.invalidateQueries({ queryKey: workspaceKeys.schedules(space.id), exact: true })
          setConflict(true)
          return
        }
        if (reason === 'INVALID_TURN_END') {
          setFieldErrors({ turnEndEntryId: t('workspace.schedule.turnEndRequired') })
          focusErrorField('turnEndEntryId')
          return
        }
        if (reason === 'INVALID_SCHEDULE' || error.status === 400) {
          setFormError(apiErrorDetail(error) ?? t('workspace.schedule.invalid'))
          return
        }
        setFormError(apiErrorDetail(error) ?? t('shared.requestFailed'))
      } else {
        setFormError(t('shared.requestFailed'))
      }
    } finally {
      setSaving(false)
    }
  }

  return (
    <Dialog
      className="workspace-schedule-dialog modal-touch-target"
      title={isEdit ? t('workspace.schedule.editTitle') : t('workspace.schedule.new')}
      pending={saving}
      onClose={onClose}
      initialFocusSelector={`#${baseId}-title`}
    >
      <form className="modal-card-form" onSubmit={event => void handleSubmit(event)} noValidate>
        <div className="modal-body workspace-schedule-form-body">
          <div className={`workspace-schedule-field form-group${fieldErrors.title ? ' is-error' : ''}`}>
            <FieldLabel htmlFor={`${baseId}-title`} required>{t('workspace.schedule.name')}</FieldLabel>
            <TextInput
              id={`${baseId}-title`}
              value={title}
              maxLength={256}
              disabled={saving}
              aria-invalid={Boolean(fieldErrors.title)}
              aria-describedby={fieldErrors.title ? `${baseId}-title-err` : undefined}
              onChange={event => {
                setTitle(event.target.value)
                clearErrors('title')
                bumpIdempotencyAfterError()
              }}
            />
            {fieldErrors.title && <FieldError id={`${baseId}-title-err`}>{fieldErrors.title}</FieldError>}
          </div>

          <fieldset className="workspace-schedule-fieldset">
            <legend>{t('workspace.schedule.target')}</legend>
            <ScheduleTargetField
              baseId={baseId}
              targetType={targetType}
              saving={saving}
              folders={folderOptions}
              agents={agentOptions}
              sessions={sessionOptions}
              threads={threadOptions}
              turnCuts={turnCutOptions}
              folderId={folderId}
              agentName={agentName}
              sessionId={sessionId}
              sourceThreadId={sourceThreadId}
              turnEndEntryId={turnEndEntryId}
              threadId={threadId}
              errors={fieldErrors}
              onTargetTypeChange={value => {
                setTargetType(value)
                clearErrors('folderId', 'agentName', 'sessionId', 'sourceThreadId', 'turnEndEntryId', 'threadId')
                bumpIdempotencyAfterError()
              }}
              onFolderChange={value => {
                setFolderId(value)
                clearErrors('folderId')
                bumpIdempotencyAfterError()
              }}
              onAgentChange={value => {
                setAgentName(value)
                clearErrors('agentName')
                bumpIdempotencyAfterError()
              }}
              onSessionChange={handleSessionChange}
              onSourceThreadChange={value => {
                setSourceThreadId(value)
                setTurnEndEntryId('')
                clearErrors('sourceThreadId', 'turnEndEntryId')
                bumpIdempotencyAfterError()
              }}
              onTurnEndChange={value => {
                setTurnEndEntryId(value)
                clearErrors('turnEndEntryId')
                bumpIdempotencyAfterError()
              }}
              onThreadChange={value => {
                setThreadId(value)
                clearErrors('threadId')
                bumpIdempotencyAfterError()
              }}
            />
          </fieldset>

          <div className={`workspace-schedule-field form-group${fieldErrors.message ? ' is-error' : ''}`}>
            <FieldLabel htmlFor={`${baseId}-message`} required>{t('workspace.schedule.message')}</FieldLabel>
            <TextArea
              id={`${baseId}-message`}
              rows={3}
              value={message}
              disabled={saving}
              placeholder={t('workspace.schedule.messagePlaceholder')}
              aria-invalid={Boolean(fieldErrors.message)}
              aria-describedby={fieldErrors.message ? `${baseId}-message-err` : undefined}
              onChange={event => {
                setMessage(event.target.value)
                clearErrors('message')
                bumpIdempotencyAfterError()
              }}
            />
            {fieldErrors.message && <FieldError id={`${baseId}-message-err`}>{fieldErrors.message}</FieldError>}
          </div>

          <fieldset className="workspace-schedule-fieldset">
            <legend>{t('workspace.schedule.when')}</legend>
            <ScheduleTimeField
              baseId={baseId}
              saving={saving}
              triggerType={triggerType}
              frequency={frequency}
              weekday={weekday}
              repeatTime={repeatTime}
              minute={minute}
              cron={cron}
              onceDate={onceDate}
              onceTime={onceTime}
              timeZone={timeZone}
              timeZones={timeZones}
              localTimeZone={localTz}
              errors={fieldErrors}
              onTriggerTypeChange={value => {
                setTriggerType(value)
                clearErrors('onceDate', 'onceTime', 'repeatTime', 'minute', 'cron')
                bumpIdempotencyAfterError()
              }}
              onFrequencyChange={value => {
                setFrequency(value)
                clearErrors('repeatTime', 'minute', 'cron')
                bumpIdempotencyAfterError()
              }}
              onWeekdayChange={value => {
                setWeekday(value)
                bumpIdempotencyAfterError()
              }}
              onRepeatTimeChange={value => {
                setRepeatTime(value)
                clearErrors('repeatTime')
                bumpIdempotencyAfterError()
              }}
              onMinuteChange={value => {
                setMinute(value)
                clearErrors('minute')
                bumpIdempotencyAfterError()
              }}
              onCronChange={value => {
                setCron(value)
                clearErrors('cron')
                bumpIdempotencyAfterError()
              }}
              onOnceDateChange={value => {
                setOnceDate(value)
                clearErrors('onceDate', 'onceTime')
                bumpIdempotencyAfterError()
              }}
              onOnceTimeChange={value => {
                setOnceTime(value)
                clearErrors('onceTime')
                bumpIdempotencyAfterError()
              }}
              onTimeZoneChange={value => {
                setTimeZone(value)
                clearErrors('onceTime')
                bumpIdempotencyAfterError()
              }}
            />

            <p
              className="workspace-schedule-preview"
              data-invalid={previewResult.invalid || undefined}
              title={
                previewState.data?.next[0]
                  ? formatScheduleFullTime(previewState.data.next[0], timeZone, locale)
                  : undefined
              }
            >
              {previewResult.text}
            </p>
          </fieldset>

          {conflict && (
            <div>
              <FieldError role="alert">{t('workspace.schedule.editConflict')}</FieldError>
              <Button type="button" variant="secondary" disabled={saving} onClick={() => void reloadSchedule()}>
                {t('workspace.schedule.reloadDiscard')}
              </Button>
            </div>
          )}
          {formError && <FieldError role="alert">{formError}</FieldError>}
        </div>

        <div className={isEdit ? 'modal-footer modal-footer-with-leading-action' : 'modal-footer'}>
          {isEdit && (
            <div className="modal-footer-leading-action">
              <Button
                type="button"
                variant="secondary"
                danger
                disabled={saving || conflict}
                onClick={() => onRequestDelete(schedule)}
              >
                {t('workspace.schedule.delete')}
              </Button>
            </div>
          )}
          <Button type="button" variant="secondary" disabled={saving} onClick={onClose}>
            {t('shared.cancel')}
          </Button>
          <Button type="submit" variant="primary" loading={saving} disabled={conflict}>
            {t('workspace.schedule.save')}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
