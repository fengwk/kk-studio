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

export function formatCountLabel(count: number, unit: string, empty = '—'): string {
  if (count <= 0) {
    return empty
  }
  return `${count} ${unit}`
}
