import type { DraftSaveOutcome } from '@/features/thread/runtime/thread-draft-store'

/** 单个草稿字段的写入屏障：最新 token + 是否有未落盘内容。 */
export interface DraftWriteField {
  token: number
  dirty: boolean
}

/**
 * 整份字段写入的公共收尾：完成回调先过 originThreadId + epoch 栅栏与字段内 token 栅栏
 * （旧 completion 绝不把新编辑标成已保存），再决定收尾。
 */
export function persistDraftField({
  field,
  save,
  originThreadId,
  originEpoch,
  isStillBound,
  onStale,
  onFailure,
}: {
  field: DraftWriteField
  save: () => Promise<DraftSaveOutcome>
  originThreadId: string
  originEpoch: number
  isStillBound: (originThreadId: string, originEpoch: number) => boolean
  /** 被存储层判定为陈旧：编辑区内容确定不在记录里。 */
  onStale: () => void
  /** 写入抛错：编辑区内容仍留在本地。 */
  onFailure: () => void
}): void {
  const token = field.token + 1
  field.token = token
  // 该字段最新一次写入的完成才代表编辑区内容已落盘；同链 FIFO 保证在途写入先于任何后续合并落盘。
  void save()
    .then((outcome) => {
      if (!isStillBound(originThreadId, originEpoch) || field.token !== token) {
        return
      }
      if (outcome.status === 'STALE') {
        field.dirty = true
        onStale()
        return
      }
      field.dirty = false
    })
    .catch(() => {
      if (!isStillBound(originThreadId, originEpoch) || field.token !== token) {
        return
      }
      field.dirty = true
      onFailure()
    })
}
