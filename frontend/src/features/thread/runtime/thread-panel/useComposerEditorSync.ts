import { useEffect, useImperativeHandle, useRef, type FormEvent, type Ref } from 'react'
import {
  COMPOSING_ATTRIBUTE,
  ensureCaretSlots,
  extractPartsFromEditor,
  extractPartsKeyFromEditor,
  insertPillsAtCaret,
  normalizeEditorDom,
  placeCaretAtEnd,
  renderPartsToEditor,
} from '@/features/thread/composer/composer-dom'
import { mergeTextParts, partsKey, type ComposerPart, type ImageInputTier } from '@/features/thread/composer/composer-parts'

export interface ComposerEditorHandle {
  /**
   * 把受控 parts 对齐到 DOM 并返回 DOM 当前提取的 merged parts（编辑器未挂载时为 null）。
   * 供提交/预览前校验 DOM 与受控草稿是否一致。
   */
  syncDraft: () => ComposerPart[] | null
  /** 在光标处插入非文本 parts（attachment/resource pill），随后回流草稿。 */
  insertParts: (parts: ComposerPart[]) => void
  /**
   * 移除引用指定上传句柄的 attachment pill 并强制回流草稿（上传失败的 DOM 回滚）。
   * IME 组合期间延后到 compositionend，避免重建组合态 DOM。
   */
  removeAttachmentPills: (uploadIds: readonly string[]) => void
  /** 就地更新已渲染 attachment pill 的 imageTier，避免整树重建丢失光标与选区。 */
  updateAttachmentImageTier: (uploadIds: readonly string[], tier: ImageInputTier) => void
}

/**
 * contenteditable 与受控 parts 之间的同步状态机。
 *
 * 承载三件事，它们互相依赖、必须一起演进：
 * - DOM 提取回流（输入/粘贴/拖放/pill 操作后的统一入口）；
 * - IME 组合期间的暂存（外部插入与上传失败都延后到 compositionend）；
 * - 受控下发（parts 变化时重建 DOM，并保留焦点与光标）。
 *
 * 组件本体只管按键分派与元素属性。
 */
export function useComposerEditorSync({
  parts,
  editorRef,
  onPartsChange,
  onEditStart,
  handleRef,
}: {
  parts: ComposerPart[]
  editorRef: React.RefObject<HTMLDivElement | null>
  onPartsChange: (parts: ComposerPart[]) => void
  onEditStart?: () => void
  handleRef?: Ref<ComposerEditorHandle>
}) {
  const isComposingRef = useRef(false)
  // 组合期间到达的上传失败：DOM pill 回滚延后到 compositionend，避免重建组合态 DOM。
  const pendingFailedUploadIdsRef = useRef<Set<string>>(new Set())
  // 组合期间到达的程序插入（附件选择/拖放）：同样延后到 compositionend。
  const pendingInsertedPartsRef = useRef<ComposerPart[]>([])

  /** 从当前 DOM 提取 parts 并回流（输入/粘贴/拖放/pill 操作后的统一入口）。 */
  function syncFromDom(force = false): ComposerPart[] | null {
    const el = editorRef.current
    if (!el) {
      return null
    }
    onEditStart?.()
    normalizeEditorDom(el)
    ensureCaretSlots(el)
    const next = mergeTextParts(extractPartsFromEditor(el))
    if (force || partsKey(next) !== partsKey(parts)) {
      onPartsChange(next)
    }
    return next
  }

  /** 移除引用指定 uploadId 的 attachment pill；返回是否真的移除了节点。 */
  function removePills(uploadIds: readonly string[]): boolean {
    const el = editorRef.current
    if (!el) {
      return false
    }
    let removed = false
    for (const uploadId of uploadIds) {
      const pills = el.querySelectorAll<HTMLElement>(
        `span[data-part-type="attachment"][data-upload-id="${uploadId}"]`,
      )
      pills.forEach((pill) => {
        pill.remove()
        removed = true
      })
    }
    if (removed) {
      // 失败 pill 的相邻 slot 需同步重组，避免占位残留成多余边界。
      ensureCaretSlots(el)
    }
    return removed
  }

  /** DOM 与 props 对齐（外部同步或初始渲染）；重建时保留焦点与光标。 */
  useEffect(() => {
    const el = editorRef.current
    if (!el || isComposingRef.current) {
      // 组合期间不重建 DOM；待同步的 parts 在 compositionend 后统一对齐。
      return
    }
    // parts 状态保持相邻 text 已合并的规范形态；比较/渲染前先规范化，
    // 避免「重建 -> 提取合并 -> 键不一致」的循环。
    // partsKey 含 attachment 的客户端 uploadId：同名但不同上传会触发重建，
    // 确保 DOM pill 的 partId 永远来自当前 parts（不留过期 partId）。
    const expectedParts = mergeTextParts(parts)
    const expected = partsKey(expectedParts)
    if (extractPartsKeyFromEditor(el) !== expected) {
      const hadFocus = document.activeElement === el
      renderPartsToEditor(el, expectedParts)
      if (hadFocus) {
        placeCaretAtEnd(el)
      }
    }
  }, [editorRef, parts])

  function handleCompositionStart() {
    isComposingRef.current = true
    editorRef.current?.setAttribute(COMPOSING_ATTRIBUTE, 'true')
  }

  function handleCompositionEnd() {
    isComposingRef.current = false
    editorRef.current?.removeAttribute(COMPOSING_ATTRIBUTE)
    const el = editorRef.current
    if (el) {
      const pendingInserted = pendingInsertedPartsRef.current
      pendingInsertedPartsRef.current = []
      if (pendingInserted.length > 0) {
        insertPillsAtCaret(el, pendingInserted)
      }
      const pendingFailed = pendingFailedUploadIdsRef.current
      if (pendingFailed.size > 0) {
        removePills(Array.from(pendingFailed))
      }
      pendingFailedUploadIdsRef.current.clear()
    }
    // 组合结束后 DOM（用户刚提交的文本）是事实源：先把组合结果与延后的附件改动
    // 一起回流草稿，组合期间到达的外部值由下一次受控同步收敛。
    syncFromDom(true)
  }

  /**
   * 输入事件统一回流草稿。Enter / Shift+Enter 的换行由浏览器默认行为产生
   * （div/br），在此经 normalizeEditorDom 收敛为纯文本 '\n'，不额外拦截按键。
   */
  function handleInput(event: FormEvent<HTMLDivElement>) {
    if (isComposingRef.current) {
      return
    }
    const native = event.nativeEvent as InputEvent
    if (native?.isComposing) {
      return
    }
    syncFromDom()
  }

  useImperativeHandle(handleRef, () => ({
    syncDraft: () => syncFromDom(false),
    insertParts: (pillParts) => {
      const el = editorRef.current
      if (!el) {
        return
      }
      if (isComposingRef.current) {
        // 组合期间的程序插入延后到 compositionend：不得改动组合态 DOM。
        pendingInsertedPartsRef.current.push(...pillParts)
        return
      }
      // 文件插入发生在当前光标处（粘贴/拖放/选择），而不是追加到末尾；
      // 插入后重新提取回流 parts，保持 DOM 与状态同构。
      insertPillsAtCaret(el, pillParts)
      syncFromDom()
    },
    removeAttachmentPills: (uploadIds) => {
      if (isComposingRef.current) {
        for (const uploadId of uploadIds) {
          pendingFailedUploadIdsRef.current.add(uploadId)
        }
        return
      }
      // 上传可能在最新 parts effect 生效前失败；以当前 DOM 强制覆盖受控草稿，
      // 避免旧闭包误判相等后把失败 pill 重新插回。
      if (removePills(uploadIds)) {
        syncFromDom(true)
      }
    },
    updateAttachmentImageTier: (uploadIds, tier) => {
      const el = editorRef.current
      if (!el) {
        return
      }
      for (const uploadId of uploadIds) {
        const pills = el.querySelectorAll<HTMLElement>(
          `span[data-part-type="attachment"][data-upload-id="${uploadId}"]`,
        )
        pills.forEach((pill) => {
          pill.dataset.imageTier = tier
        })
      }
    },
  }))

  return {
    isComposingRef,
    syncFromDom,
    handleCompositionStart,
    handleCompositionEnd,
    handleInput,
  }
}
