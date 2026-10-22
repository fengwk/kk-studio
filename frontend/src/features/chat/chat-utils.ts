import { includesSearch } from '@/shared/lib/search-utils'
import type { CatalogVersion } from '@/shared/api/contracts/base'
import type { ChatDTO } from '@/shared/api/generated/contracts'

function catalogVersionValue(version: CatalogVersion): bigint {
  try {
    return BigInt(version)
  } catch {
    return 0n
  }
}

export function compareCatalogVersions(
  left: CatalogVersion,
  right: CatalogVersion,
): number {
  const delta = catalogVersionValue(left) - catalogVersionValue(right)
  if (delta < 0n) {
    return -1
  }
  if (delta > 0n) {
    return 1
  }
  return 0
}

export function preferNewerChat(
  current: ChatDTO | undefined,
  incoming: ChatDTO,
): ChatDTO {
  if (!current || compareCatalogVersions(current.version, incoming.version) <= 0) {
    return incoming
  }
  return current
}

/** 合并服务器列表响应，同时避免较低 Chat 版本使 cache 回退。 */
export function mergeChatList(
  current: ChatDTO[] | undefined,
  incoming: ChatDTO[],
): ChatDTO[] {
  if (!current) {
    return incoming
  }
  const currentById = new Map(current.map((chat) => [chat.id, chat]))
  return incoming.map((chat) => preferNewerChat(currentById.get(chat.id), chat))
}

/** 将权威 Chat 响应应用到已加载的列表 cache。 */
export function mergeChatIntoList(
  current: ChatDTO[] | undefined,
  incoming: ChatDTO,
): ChatDTO[] | undefined {
  if (!current) {
    return current
  }
  let found = false
  const merged = current.map((chat) => {
    if (chat.id !== incoming.id) {
      return chat
    }
    found = true
    return preferNewerChat(chat, incoming)
  })
  return found ? merged : [...merged, incoming]
}

export function filterChats(chats: ChatDTO[], search: string): ChatDTO[] {
  return chats
    .filter((chat) => includesSearch(chat.title ?? '', search))
    .sort((left, right) => {
      const delta = right.createTime - left.createTime
      if (delta !== 0) {
        return delta
      }
      return String(right.id).localeCompare(String(left.id), undefined, { numeric: true })
    })
}

/** 把 wire Instant（epoch 秒）格式化为本地 `YYYY-MM-DD HH:mm`；非法值显示 '-'。 */
export function formatBackendDate(value: number): string {
  const date = new Date(value * 1000)
  if (!Number.isFinite(date.getTime())) {
    return '-'
  }
  return `${String(date.getFullYear()).padStart(4, '0')}-${String(
    date.getMonth() + 1,
  ).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')} ${String(
    date.getHours(),
  ).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}
