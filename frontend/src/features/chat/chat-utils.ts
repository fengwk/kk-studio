import { includesSearch } from '@/shared/lib/search-utils'
import { compareCatalogVersions } from '@/shared/lib/catalog-version'
import type { ChatDTO, ChatPageDTO } from '@/shared/api/generated/contracts'

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
export function mergeChatPage(
  current: ChatPageDTO | undefined,
  incoming: ChatPageDTO,
): ChatPageDTO {
  if (!current) {
    return incoming
  }
  const currentById = new Map(current.items.map((chat) => [chat.id, chat]))
  return {
    ...incoming,
    items: incoming.items.map((chat) => preferNewerChat(currentById.get(chat.id), chat)),
  }
}

/** 追加页与已加载列表按 id 去重，保留首次出现顺序（页面窗口内不重复展示同一 Chat）。 */
export function appendUniqueChats(current: ChatDTO[], incoming: ChatDTO[]): ChatDTO[] {
  const seen = new Set(current.map((chat) => chat.id))
  return [...current, ...incoming.filter((chat) => !seen.has(chat.id))]
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
