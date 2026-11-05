import type {
  HarnessSessionEntryDTO,
  HarnessSessionEntryPageDTO,
} from '@/shared/api/generated/contracts'

/** 构造 Session Entry 分页响应；默认单页已到末页。 */
export function entryPage(
  items: HarnessSessionEntryDTO[],
  nextCursor: string | null = null,
): HarnessSessionEntryPageDTO {
  return { items, nextCursor }
}
