import type { KeyboardEvent, Ref, RefObject } from 'react'
import {
  ensureCaretSlots,
  findAdjacentPill,
} from '@/features/thread/composer/composer-dom'
import {
  mergeTextParts,
  partsKey,
  removePartsByIds,
  type ComposerPart,
} from '@/features/thread/composer/composer-parts'
import { canNavigateHistoryFromCaret } from '@/features/thread/runtime/thread-panel/composer-editor-dom'
import type { ComposerEditorHandle } from '@/features/thread/runtime/thread-panel/useComposerEditorSync'
import { useComposerClipboard } from '@/features/thread/runtime/thread-panel/useComposerClipboard'
import { useComposerEditorSync } from '@/features/thread/runtime/thread-panel/useComposerEditorSync'
import { useI18n } from '@/shared/i18n'
import '@/features/thread/runtime/thread-panel/composer-editor.css'

export type { ComposerEditorHandle } from '@/features/thread/runtime/thread-panel/useComposerEditorSync'

export interface ComposerEditorProps {
  /** 受控 ordered parts；外部同步（恢复/重放）在非组合态下重建 DOM。 */
  parts: ComposerPart[]
  /** 不可编辑时只保留草稿展示（contentEditable=false + aria-disabled）。 */
  disabled: boolean
  /** 草稿回流入口：DOM 提取结果或键盘整颗删除 pill 后的 parts。 */
  onPartsChange: (parts: ComposerPart[]) => void
  /** 编辑交互（输入/粘贴/拖放/pill 编辑/预览前对齐）通知：外层据此关闭覆盖层。 */
  onEditStart?: () => void
  /** 编辑器 mousedown：外层据此关闭底栏设置菜单（命令表由编辑行为本身关闭）。 */
  onEditorMouseDown?: () => void
  /** 编辑器未消费的按键（命令表导航、提交）；返回 true 表示已消费。 */
  onKeyDown?: (event: KeyboardEvent<HTMLDivElement>) => boolean
  /** 光标位于首/末行时的方向键历史回放导航；返回是否发生导航。 */
  onNavigateHistory?: (direction: 'previous' | 'next') => boolean
  /** 粘贴/拖放携带的文件；上传注册表与附件编排归外层所有。 */
  onFiles?: (files: File[]) => void
  /** 编辑器根节点，由外层的焦点状态机持有（聚焦重试、光标归位、全局 Escape）。 */
  editorRef: RefObject<HTMLDivElement | null>
  ref?: Ref<ComposerEditorHandle>
}

/**
 * ordered Pill Composer 的 contenteditable 编辑边界。
 *
 * 只负责编辑器自身能力：DOM 与 {@link ComposerPart} 的双向转换、IME 组合、
 * caret/selection、attachment/resource pill 节点交互、纯文本粘贴/拖放与
 * 历史回放渲染。上传注册表、草稿历史、命令表/工具栏与提交编排由外层
 * （{@link ThreadComposer}）持有，本组件不依赖任何业务 API 或 Thread 身份。
 *
 * 同步状态机在 {@link useComposerEditorSync}，剪贴板与拖放在
 * {@link useComposerClipboard}；本组件只分派按键并持有元素属性。
 */
export function ComposerEditor({
  ref,
  parts,
  disabled,
  onPartsChange,
  onEditStart,
  onEditorMouseDown,
  onKeyDown,
  onNavigateHistory,
  onFiles,
  editorRef,
}: ComposerEditorProps) {
  const { t } = useI18n()

  const {
    isComposingRef,
    syncFromDom,
    handleCompositionStart,
    handleCompositionEnd,
    handleInput,
  } = useComposerEditorSync({ parts, editorRef, onPartsChange, onEditStart, handleRef: ref })

  /** 编辑器消费 pill 删除与历史回放按键，其余按键交给外层（命令表/提交）。 */
  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (disabled) {
      // contentEditable=false 仍会收到按键：不可编辑时不得触发提交、命令表或历史回放。
      return
    }
    if (event.nativeEvent.isComposing || event.keyCode === 229 || isComposingRef.current) {
      return
    }
    if (event.key === 'Backspace' || event.key === 'Delete') {
      const el = editorRef.current
      const pill = el ? findAdjacentPill(el, event.key === 'Backspace' ? 'before' : 'after') : null
      if (pill) {
        // 整颗 pill 删除：直接移除 DOM 节点并回流 parts（保持光标稳定）。
        event.preventDefault()
        event.stopPropagation()
        const partId = pill.getAttribute('data-part-id')
        pill.remove()
        if (editorRef.current) {
          // 相邻 caret slot 随 pill 消失需要重组，否则残留的占位会成为可见边界。
          ensureCaretSlots(editorRef.current)
        }
        if (partId) {
          const next = mergeTextParts(removePartsByIds(parts, new Set([partId])))
          if (partsKey(next) !== partsKey(parts)) {
            onPartsChange(next)
            return
          }
        }
        syncFromDom()
        return
      }
    }
    if (onKeyDown?.(event)) {
      return
    }
    // 方向键到达首/末行边界时才接管为历史回放；否则交给 contenteditable 移动光标。
    if (
      (event.key === 'ArrowDown' || event.key === 'ArrowUp')
      && !event.shiftKey
      && !event.ctrlKey
      && !event.metaKey
      && !event.altKey
    ) {
      const el = editorRef.current
      const direction = event.key === 'ArrowUp' ? 'previous' : 'next'
      if (
        el
        && canNavigateHistoryFromCaret(el, direction)
        && onNavigateHistory?.(direction)
      ) {
        event.preventDefault()
        event.stopPropagation()
      }
    }
  }

  const { handlePaste, handleDrop, handleCopy, handleCut } = useComposerClipboard({
    disabled,
    editorRef,
    onFiles,
    syncFromDom,
  })

  const draftIsEmpty = parts.every(
    (part) => part.type === 'text' && part.text.trim() === '',
  )

  return (
    <div
      ref={editorRef}
      className="composer-editor"
      contentEditable={!disabled}
      role="textbox"
      aria-multiline="true"
      aria-label={t('ai.runtime.composer.ariaLabel')}
      aria-disabled={disabled}
      data-placeholder={t('ai.runtime.composer.placeholder')}
      data-placeholder-visible={draftIsEmpty}
      onCompositionStart={handleCompositionStart}
      onCompositionEnd={handleCompositionEnd}
      onInput={handleInput}
      onKeyDown={handleKeyDown}
      onPaste={handlePaste}
      onDrop={handleDrop}
      onCopy={handleCopy}
      onCut={handleCut}
      onMouseDown={() => onEditorMouseDown?.()}
    />
  )
}
