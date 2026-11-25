import type {
  HarnessSessionEntryDTO,
  ScheduleTargetDTO,
  ScheduleTargetType,
  ScheduleTriggerDTO,
  ScheduleTriggerFrequency,
  ScheduleTriggerType,
  WorkspaceFolderDTO,
} from '@/shared/api/generated/contracts'
import type { TranslationKey } from '@/shared/i18n'
import { isRecord } from '@/shared/lib/value'

export interface FolderOption {
  id: string
  label: string
}

export interface SessionOption {
  id: string
  label: string
  threads: { id: string; title: string }[]
}

export interface TurnCutOption {
  entryId: string
  turnNumber: number
  summary: string
}

export interface HistoryCursorState {
  current: string | null
  previous: (string | null)[]
}

export const INITIAL_HISTORY_CURSORS: HistoryCursorState = {
  current: null,
  previous: [],
}

export function pushOlderCursor(state: HistoryCursorState, olderCursor: string): HistoryCursorState {
  return {
    current: olderCursor,
    previous: [...state.previous, state.current],
  }
}

export function popNewerCursor(state: HistoryCursorState): HistoryCursorState {
  if (state.previous.length === 0) return INITIAL_HISTORY_CURSORS
  const nextPrevious = state.previous.slice(0, -1)
  const nextCurrent = state.previous[state.previous.length - 1] ?? null
  return {
    current: nextCurrent,
    previous: nextPrevious,
  }
}

export function flattenFolders(folders: readonly WorkspaceFolderDTO[], prefix = ''): FolderOption[] {
  const result: FolderOption[] = []
  for (const folder of folders) {
    const label = prefix ? `${prefix} / ${folder.name}` : folder.name
    result.push({ id: folder.id, label })
    if (folder.children.length > 0) {
      result.push(...flattenFolders(folder.children, label))
    }
  }
  return result
}

export function listTreeSessions(folders: readonly WorkspaceFolderDTO[], prefix = ''): SessionOption[] {
  const result: SessionOption[] = []
  for (const folder of folders) {
    const folderLabel = prefix ? `${prefix} / ${folder.name}` : folder.name
    for (const resource of folder.resources) {
      if (resource.kind === 'SESSION') {
        result.push({
          id: resource.id,
          label: `${folderLabel} / ${resource.title}`,
          threads: (resource.threads ?? []).map(thread => ({ id: thread.id, title: thread.title })),
        })
      }
    }
    if (folder.children.length > 0) {
      result.push(...listTreeSessions(folder.children, folderLabel))
    }
  }
  return result
}

export function extractTurnCuts(entries: readonly HarnessSessionEntryDTO[]): TurnCutOption[] {
  const cuts: TurnCutOption[] = []
  let turnNumber = 0
  let currentSummary = ''
  for (const entry of entries) {
    if (entry.entryType === 'TURN_START') {
      turnNumber += 1
      currentSummary = ''
    } else if (entry.entryType === 'MESSAGE' || entry.entryType === 'CUSTOM_MESSAGE') {
      if (isRecord(entry.payload) && isRecord(entry.payload.message)) {
        const message = entry.payload.message
        if (message.role === 'USER' && Array.isArray(message.contents) && currentSummary === '') {
          const text = message.contents
            .map(part => (isRecord(part) && typeof part.text === 'string' ? part.text : ''))
            .filter(Boolean)
            .join(' ')
            .replace(/\s+/gu, ' ')
            .trim()
          if (text) {
            if (turnNumber === 0) turnNumber = 1
            currentSummary = text.length > 36 ? `${text.slice(0, 35)}…` : text
          }
        }
      }
    } else if (entry.entryType === 'TURN_END') {
      if (turnNumber === 0) turnNumber = 1
      cuts.push({ entryId: entry.entryId, turnNumber, summary: currentSummary })
    }
  }
  return cuts
}

function datePartsInZone(timestamp: number, timeZone: string, locale: string) {
  const formatter = new Intl.DateTimeFormat(locale, {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    weekday: 'short',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  })
  const parts = formatter.formatToParts(new Date(timestamp))
  const get = (type: Intl.DateTimeFormatPartTypes) => parts.find(part => part.type === type)?.value ?? ''
  const rawWeekday = get('weekday')
  const weekday = locale === 'zh-CN' ? rawWeekday.replace(/^星期/u, '周') : rawWeekday
  return {
    year: get('year'),
    month: get('month'),
    day: get('day'),
    weekday,
    hour: get('hour'),
    minute: get('minute'),
  }
}

export function formatScheduleNextRun(timestamp: number, timeZone: string, locale: string): string {
  try {
    const p = datePartsInZone(timestamp, timeZone, locale)
    return `${p.month}-${p.day} ${p.weekday} ${p.hour}:${p.minute}`
  } catch {
    return new Date(timestamp).toISOString().slice(0, 16).replace('T', ' ')
  }
}

export function formatScheduleRunTime(timestamp: number, timeZone: string, locale: string): string {
  try {
    const p = datePartsInZone(timestamp, timeZone, locale)
    return `${p.year}-${p.month}-${p.day} ${p.weekday} ${p.hour}:${p.minute}`
  } catch {
    return new Date(timestamp).toISOString().slice(0, 16).replace('T', ' ')
  }
}

export function formatScheduleFullTime(timestamp: number, timeZone: string, locale: string): string {
  try {
    const p = datePartsInZone(timestamp, timeZone, locale)
    return `${p.year}-${p.month}-${p.day} ${p.hour}:${p.minute} (${timeZone})`
  } catch {
    return `${new Date(timestamp).toISOString()} (${timeZone})`
  }
}

export function todayInTimeZone(timeZone: string, now = Date.now()): string {
  try {
    const p = datePartsInZone(now, timeZone, 'en-CA')
    if (p.year && p.month && p.day) return `${p.year}-${p.month}-${p.day}`
  } catch {
    // Fallback to UTC ISO date below when an invalid IANA zone is provided.
  }
  return new Date(now).toISOString().slice(0, 10)
}

export function getLocalTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
}

export function buildTimeZoneOptions(localTz: string, existingTz?: string | null): string[] {
  const zones = [
    ...(existingTz ? [existingTz] : []),
    localTz,
    'Asia/Shanghai',
    'UTC',
    'Asia/Tokyo',
    'Europe/London',
    'America/Los_Angeles',
  ]
  return [...new Set(zones.filter(Boolean))]
}

export const SCHEDULE_WEEKDAYS = [
  'MONDAY',
  'TUESDAY',
  'WEDNESDAY',
  'THURSDAY',
  'FRIDAY',
  'SATURDAY',
  'SUNDAY',
] as const

export const WEEKDAY_LABEL_KEYS: Record<(typeof SCHEDULE_WEEKDAYS)[number], TranslationKey> = {
  MONDAY: 'workspace.schedule.monday',
  TUESDAY: 'workspace.schedule.tuesday',
  WEDNESDAY: 'workspace.schedule.wednesday',
  THURSDAY: 'workspace.schedule.thursday',
  FRIDAY: 'workspace.schedule.friday',
  SATURDAY: 'workspace.schedule.saturday',
  SUNDAY: 'workspace.schedule.sunday',
}

export interface ScheduleFormDraft {
  title: string
  message: string
  targetType: ScheduleTargetType
  folderId: string
  agentName: string
  sessionId: string
  sourceThreadId: string
  turnEndEntryId: string
  threadId: string
  triggerType: ScheduleTriggerType
  frequency: ScheduleTriggerFrequency
  weekday: string
  repeatTime: string
  minute: string
  cron: string
  onceDate: string
  onceTime: string
  timeZone: string
}

export function validateScheduleTitle(raw: string): TranslationKey | null {
  const title = raw.trim()
  if (!title) return 'workspace.schedule.nameRequired'
  if (title.length > 256) return 'workspace.schedule.nameTooLong'
  if (/\p{Cc}/u.test(title)) return 'workspace.schedule.nameControlChars'
  return null
}

export function buildTriggerFromDraft(draft: Pick<
  ScheduleFormDraft,
  'triggerType' | 'frequency' | 'weekday' | 'repeatTime' | 'minute' | 'cron' | 'onceDate' | 'onceTime'
>): { ok: true; trigger: ScheduleTriggerDTO } | { ok: false; field: 'onceDate' | 'onceTime' | 'repeatTime' | 'minute' | 'cron'; errorKey: TranslationKey } {
  if (draft.triggerType === 'ONCE') {
    if (!/^\d{4}-\d{2}-\d{2}$/u.test(draft.onceDate)) {
      return { ok: false, field: 'onceDate', errorKey: 'workspace.schedule.dateRequired' }
    }
    if (!/^\d{2}:\d{2}$/u.test(draft.onceTime)) {
      return { ok: false, field: 'onceTime', errorKey: 'workspace.schedule.timeRequired' }
    }
    return {
      ok: true,
      trigger: { type: 'ONCE', localDate: draft.onceDate, time: draft.onceTime },
    }
  }

  switch (draft.frequency) {
    case 'DAILY':
    case 'WEEKDAYS': {
      if (!/^\d{2}:\d{2}$/u.test(draft.repeatTime)) {
        return { ok: false, field: 'repeatTime', errorKey: 'workspace.schedule.timeRequired' }
      }
      return { ok: true, trigger: { type: 'REPEAT', frequency: draft.frequency, time: draft.repeatTime } }
    }
    case 'WEEKLY': {
      if (!/^\d{2}:\d{2}$/u.test(draft.repeatTime)) {
        return { ok: false, field: 'repeatTime', errorKey: 'workspace.schedule.timeRequired' }
      }
      return {
        ok: true,
        trigger: {
          type: 'REPEAT',
          frequency: 'WEEKLY',
          weekday: draft.weekday || 'MONDAY',
          time: draft.repeatTime,
        },
      }
    }
    case 'HOURLY': {
      const trimmed = draft.minute.trim()
      if (!/^\d{1,2}$/u.test(trimmed)) {
        return { ok: false, field: 'minute', errorKey: 'workspace.schedule.minuteInvalid' }
      }
      const minute = Number(trimmed)
      if (!Number.isInteger(minute) || minute < 0 || minute > 59) {
        return { ok: false, field: 'minute', errorKey: 'workspace.schedule.minuteInvalid' }
      }
      return { ok: true, trigger: { type: 'REPEAT', frequency: 'HOURLY', minute } }
    }
    case 'CRON': {
      const cron = draft.cron.trim().replace(/\s+/gu, ' ')
      const fields = cron ? cron.split(' ') : []
      if (fields.length !== 5) {
        return { ok: false, field: 'cron', errorKey: 'workspace.schedule.cronRequired' }
      }
      return { ok: true, trigger: { type: 'REPEAT', frequency: 'CRON', cron } }
    }
  }
}

export function buildTargetFromDraft(draft: Pick<
  ScheduleFormDraft,
  'targetType' | 'folderId' | 'agentName' | 'sessionId' | 'sourceThreadId' | 'turnEndEntryId' | 'threadId'
>): { ok: true; target: ScheduleTargetDTO } | { ok: false; field: 'folderId' | 'agentName' | 'sessionId' | 'sourceThreadId' | 'turnEndEntryId' | 'threadId'; errorKey: TranslationKey } {
  switch (draft.targetType) {
    case 'NEW_SESSION': {
      if (!draft.folderId) return { ok: false, field: 'folderId', errorKey: 'workspace.schedule.folderRequired' }
      if (!draft.agentName) return { ok: false, field: 'agentName', errorKey: 'workspace.schedule.agentRequired' }
      return { ok: true, target: { type: 'NEW_SESSION', folderId: draft.folderId, agentName: draft.agentName } }
    }
    case 'BRANCH': {
      if (!draft.sessionId) return { ok: false, field: 'sessionId', errorKey: 'workspace.schedule.sessionRequired' }
      if (!draft.sourceThreadId) return { ok: false, field: 'sourceThreadId', errorKey: 'workspace.schedule.threadRequired' }
      if (!draft.turnEndEntryId) return { ok: false, field: 'turnEndEntryId', errorKey: 'workspace.schedule.turnEndRequired' }
      return {
        ok: true,
        target: {
          type: 'BRANCH',
          sessionId: draft.sessionId,
          sourceThreadId: draft.sourceThreadId,
          turnEndEntryId: draft.turnEndEntryId,
        },
      }
    }
    case 'CONTINUE': {
      if (!draft.sessionId) return { ok: false, field: 'sessionId', errorKey: 'workspace.schedule.sessionRequired' }
      if (!draft.threadId) return { ok: false, field: 'threadId', errorKey: 'workspace.schedule.threadRequired' }
      return { ok: true, target: { type: 'CONTINUE', sessionId: draft.sessionId, threadId: draft.threadId } }
    }
  }
}

export function resolveRunReasonText(
  result: 'SUBMITTED' | 'SKIPPED' | 'NOT_SUBMITTED',
  reason: string | null | undefined,
  t: (key: TranslationKey) => string,
): string {
  switch (reason) {
    case 'EXPIRED':
      return t('workspace.schedule.reasonExpired')
    case 'EXECUTION_NOT_SETTLED':
      return t('workspace.schedule.reasonNotSettled')
    case 'RESOURCE_NOT_FOUND':
    case 'NOT_FOUND':
    case 'THREAD_NOT_FOUND':
    case 'INVALID_FOLDER':
    case 'INVALID_TURN_END':
      return t('workspace.schedule.reasonTargetUnavailable')
    default:
      if (reason && reason.trim()) return reason
      return t(result === 'SKIPPED' ? 'workspace.schedule.skipped' : 'workspace.schedule.notSubmitted')
  }
}
