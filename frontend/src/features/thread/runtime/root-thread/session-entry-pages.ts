import { harnessService } from '@/shared/api/harness-service'
import type { HarnessSessionEntryDTO } from '@/shared/api/generated/contracts'

/**
 * 按 keyset 游标逐页读取 Session Entry，并累积为完整历史。
 *
 * 历史树与草稿祖先路径都需要完整 Session 历史，分页只约束单次传输与查询规模，现有交互保持完整视图。
 * 每页都透传 AbortSignal，历史目标切换时后续页立即停止。
 */
export async function collectSessionEntries(
  sessionId: string,
  signal?: AbortSignal,
): Promise<HarnessSessionEntryDTO[]> {
  const entries: HarnessSessionEntryDTO[] = []
  let cursor: string | null = null
  do {
    const page = await harnessService.listSessionEntries(sessionId, { cursor, signal })
    entries.push(...page.items)
    cursor = page.nextCursor
  } while (cursor != null)
  return entries
}
