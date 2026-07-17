import type { RootActivityDTO } from '@/shared/api/contracts'

export function parseRootActivity(data: string): RootActivityDTO | null {
  try {
    const activity = JSON.parse(data) as Partial<RootActivityDTO>
    if (
      !activity.rootSessionId
      || !activity.sessionId
      || !activity.runId
      || !activity.type
      || !isPositiveDecimal(activity.eventId)
      || !Number.isInteger(activity.sequence)
      || (activity.sequence ?? -1) < 0
    ) {
      return null
    }
    return activity as RootActivityDTO
  } catch {
    return null
  }
}

export function mergeRootActivity(activities: RootActivityDTO[], activity: RootActivityDTO): RootActivityDTO[] {
  if (activities.some((candidate) => candidate.eventId === activity.eventId)) {
    return activities
  }
  return [...activities, activity].sort((left, right) => compareRootActivityIds(left.eventId, right.eventId))
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
