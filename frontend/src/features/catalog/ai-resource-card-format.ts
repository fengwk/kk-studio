import { translate } from '@/shared/i18n'

/** 紧凑列表：最多展示 limit 项，其余折叠为 +N。 */
export function formatCompactList(
  items: Array<string | null | undefined> | null | undefined,
  limit = 3,
  empty = '—',
): string {
  const list = (items ?? []).map((item) => String(item ?? '').trim()).filter(Boolean)
  if (list.length === 0) {
    return empty
  }
  if (list.length <= limit) {
    return list.join(', ')
  }
  return `${list.slice(0, limit).join(', ')} +${list.length - limit}`
}

/**
 * 卡片上的毫秒时长：整秒按秒显示，否则保留毫秒，不做四舍五入，
 * 避免把未知精度伪装成整秒。单位与空格由 i18n 文案统一，中英文同一套规则。
 */
export function formatDurationMillis(value: number | string | null | undefined): string {
  if (value == null || value === '') {
    return translate('ai.catalog.card.emptyValue')
  }
  const millis = Number(value)
  if (!Number.isFinite(millis)) {
    return String(value)
  }
  return millis % 1000 === 0
    ? translate('ai.catalog.card.seconds', { value: millis / 1000 })
    : translate('ai.catalog.card.milliseconds', { value: millis })
}
