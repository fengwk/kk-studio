import type { RootActivityDTO } from '@/shared/api/contracts'

export function normalizeRootActivity(raw: Partial<RootActivityDTO> | null | undefined): RootActivityDTO | null {
  if (
    !raw
    || !raw.rootSessionId
    || !raw.sessionId
    || !raw.threadId
    || !raw.eventType
    || !isPositiveDecimal(raw.eventId)
  ) {
    return null
  }
  return {
    rootSessionId: String(raw.rootSessionId),
    sessionId: String(raw.sessionId),
    threadId: String(raw.threadId),
    eventId: String(raw.eventId),
    eventType: String(raw.eventType),
    payloadJson: typeof raw.payloadJson === 'string' ? raw.payloadJson : '',
    createTime: raw.createTime as RootActivityDTO['createTime'],
  }
}

export function parseRootActivity(data: string): RootActivityDTO | null {
  try {
    return normalizeRootActivity(JSON.parse(data) as Partial<RootActivityDTO>)
  } catch {
    return null
  }
}

export function mergeRootActivity(activities: RootActivityDTO[], activity: RootActivityDTO): RootActivityDTO[] {
  const normalized = normalizeRootActivity(activity)
  if (!normalized) {
    return activities
  }
  if (activities.some((candidate) => candidate.eventId === normalized.eventId)) {
    return activities
  }
  return [...activities, normalized].sort((left, right) => compareRootActivityIds(left.eventId, right.eventId))
}

export function mergeRootActivityLists(base: RootActivityDTO[], incoming: RootActivityDTO[]): RootActivityDTO[] {
  return incoming.reduce(mergeRootActivity, base)
}

function isPositiveDecimal(value: unknown): value is string {
  return typeof value === 'string' && /^[1-9]\d*$/.test(value)
}

export function compareRootActivityIds(left: string, right: string): number {
  if (left.length !== right.length) {
    return left.length - right.length
  }
  return left === right ? 0 : left < right ? -1 : 1
}
