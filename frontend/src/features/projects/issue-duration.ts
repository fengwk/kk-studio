import type { InterpolationValues, TranslationKey } from '@/shared/i18n'
import type { IssueRunDTO } from '@/shared/api/generated/contracts'

type Translate = (key: TranslationKey, values?: InterpolationValues) => string

const SECOND_MS = 1000
const MINUTE_SECONDS = 60
const HOUR_SECONDS = 60 * MINUTE_SECONDS

/**
 * 毫秒时长按最多两级单位呈现：小时级补分钟，分钟级补秒，不足一分钟只有秒。
 *
 * 舍去不足一秒的部分，不把剩余预算显示得比实际更多。
 */
export function formatDuration(ms: number, t: Translate): string {
  const totalSeconds = Math.max(0, Math.floor(ms / SECOND_MS))
  const hours = Math.floor(totalSeconds / HOUR_SECONDS)
  const minutes = Math.floor((totalSeconds % HOUR_SECONDS) / MINUTE_SECONDS)
  const seconds = totalSeconds % MINUTE_SECONDS
  if (hours > 0) {
    // 小时级的秒数对判断执行时长没有决策价值，只补到分钟。
    const hourPart = t('projects.issue.duration.hour', { value: hours })
    return minutes > 0
      ? `${hourPart} ${t('projects.issue.duration.minute', { value: minutes })}`
      : hourPart
  }
  if (minutes > 0) {
    const minutePart = t('projects.issue.duration.minute', { value: minutes })
    return seconds > 0
      ? `${minutePart} ${t('projects.issue.duration.second', { value: seconds })}`
      : minutePart
  }
  return t('projects.issue.duration.second', { value: seconds })
}

/**
 * Run 卡片的时长事实：结束过的 Run 报耗时，仍在执行的 Run 报剩余预算。
 *
 * 终态 Run 冻结 `endedAt`、活动 Run 没有结束时间戳，因此结束时间戳是否存在
 * 就能区分两者：耗时只由起止时间戳得出，剩余预算只来自服务端的活动余额，两者不会互相冒充。
 */
export function runDurationText(run: IssueRunDTO, t: Translate): string {
  if (run.endedAt == null) {
    return t('projects.issue.runRemainingBudget', {
      duration: formatDuration(Number(run.remainingExecutionMs), t),
    })
  }
  return t('projects.issue.runElapsed', {
    duration: formatDuration(run.endedAt - run.startedAt, t),
  })
}
