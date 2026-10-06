import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import {
  createRef,
  useEffect,
  useRef,
  useState,
  type KeyboardEvent as ReactKeyboardEvent,
  type RefObject,
} from 'react'
import { describe, expect, it, vi } from 'vitest'
import {
  ComposerEditor,
  type ComposerEditorHandle,
} from '@/features/ai/runtime/thread-panel/ComposerEditor'
import {
  createAttachmentPart,
  createTextPart,
  type ComposerPart,
} from '@/features/ai/composer'

/** 稳定空数组：避免默认参数每次渲染生成新引用触发同步 effect 死循环。 */
const EMPTY_PARTS: ComposerPart[] = []

interface HarnessProps {
  apiRef?: RefObject<ComposerEditorHandle | null>
  initialParts?: ComposerPart[]
  disabled?: boolean
  onPartsChange?: (parts: ComposerPart[]) => void
  onEditStart?: () => void
  onEditorMouseDown?: () => void
  onKeyDown?: (event: ReactKeyboardEvent<HTMLDivElement>) => boolean
  onNavigateHistory?: (direction: 'previous' | 'next') => boolean
  onFiles?: (files: File[]) => void
}

/** 受控 Harness：内部回声 parts；外部传入新的 initialParts 引用时整体替换（模拟恢复/重放）。 */
function Harness({
  apiRef,
  initialParts = EMPTY_PARTS,
  disabled = false,
  onPartsChange,
  onEditStart,
  onEditorMouseDown,
  onKeyDown,
  onNavigateHistory,
  onFiles,
}: HarnessProps) {
  const [parts, setParts] = useState<ComposerPart[]>(initialParts)
  const editorRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (initialParts !== EMPTY_PARTS) {
      setParts(initialParts)
    }
  }, [initialParts])
  return (
    <div>
      <ComposerEditor
        ref={apiRef}
        editorRef={editorRef}
        parts={parts}
        disabled={disabled}
        onPartsChange={(next) => {
          onPartsChange?.(next)
          setParts(next)
        }}
        onEditStart={onEditStart}
        onEditorMouseDown={onEditorMouseDown}
        onKeyDown={onKeyDown}
        onNavigateHistory={onNavigateHistory}
        onFiles={onFiles}
      />
      <pre data-testid="parts">{JSON.stringify(parts)}</pre>
    </div>
  )
}

function editorOf(): HTMLElement {
  return screen.getByRole('textbox', { name: '给 AI 发送消息' })
}

function partsSnapshot(): ComposerPart[] {
  return JSON.parse(screen.getByTestId('parts').textContent ?? '[]') as ComposerPart[]
}

/** 便于断言 ordered 草稿形态：text 原样，非文本 parts 记类型占位。 */
function textSnapshot(): string {
  return partsSnapshot()
    .map((part) => (part.type === 'text' ? part.text : `[${part.type}]`))
    .join('')
}

function placeCaretAtChild(editor: HTMLElement, childIndex: number) {
  const range = document.createRange()
  range.setStart(editor, childIndex)
  range.collapse(true)
  const selection = window.getSelection()
  selection?.removeAllRanges()
  selection?.addRange(range)
}

function placeCaretAtEnd(editor: HTMLElement) {
  const range = document.createRange()
  range.selectNodeContents(editor)
  range.collapse(false)
  const selection = window.getSelection()
  selection?.removeAllRanges()
  selection?.addRange(range)
}

function placeCaretInText(editor: HTMLElement, charOffset: number) {
  const node = editor.firstChild
  if (node == null || node.nodeType !== Node.TEXT_NODE) {
    throw new Error('expected a leading text node')
  }
  const range = document.createRange()
  range.setStart(node, charOffset)
  range.collapse(true)
  const selection = window.getSelection()
  selection?.removeAllRanges()
  selection?.addRange(range)
}

/**
 * jsdom 不会在 contenteditable 中推进 document selection（每次键入都插到原 caret），
 * 因此测试用 Selection API 逐字符插入并手动维护 caret，与真实浏览器行为一致。
 */
async function typeInEditor(editor: HTMLElement, text: string) {
  editor.focus()
  for (const char of text) {
    const selection = window.getSelection()
    let range = selection?.rangeCount ? selection.getRangeAt(0) : null
    if (!range || !editor.contains(range.commonAncestorContainer)) {
      placeCaretAtEnd(editor)
      range = selection?.getRangeAt(0) ?? null
    }
    if (!range) {
      continue
    }
    const node = document.createTextNode(char)
    range.deleteContents()
    range.insertNode(node)
    range.setStartAfter(node)
    range.collapse(true)
    selection?.removeAllRanges()
    if (selection) {
      selection.addRange(range)
    }
    fireEvent.input(editor, { inputType: 'insertText', data: char })
  }
}

describe('ComposerEditor', () => {
  it('exposes the contenteditable textbox with placeholder and disabled semantics', () => {
    const { rerender } = render(<Harness />)
    const editor = editorOf()
    expect(editor).toHaveAttribute('role', 'textbox')
    expect(editor).toHaveAttribute('aria-multiline', 'true')
    expect(editor).toHaveAttribute('contenteditable', 'true')
    expect(editor).toHaveAttribute('data-placeholder', '输入任务（/打开命令）')
    expect(editor).toHaveAttribute('data-placeholder-visible', 'true')

    // 纯空白草稿仍显示占位；有内容后隐藏。
    rerender(<Harness initialParts={[createTextPart('   ')]} />)
    expect(editor).toHaveAttribute('data-placeholder-visible', 'true')
    rerender(<Harness initialParts={[createTextPart('draft')]} />)
    expect(editor).toHaveAttribute('data-placeholder-visible', 'false')

    rerender(<Harness initialParts={[createTextPart('draft')]} disabled />)
    expect(editor).toHaveAttribute('contenteditable', 'false')
    expect(editor).toHaveAttribute('aria-disabled', 'true')
  })

  it('extracts ordered parts from DOM edits and skips reflow when the draft is unchanged', async () => {
    const onPartsChange = vi.fn()
    render(<Harness onPartsChange={onPartsChange} />)
    const editor = editorOf()

    await typeInEditor(editor, 'hello')
    expect(partsSnapshot()).toEqual([expect.objectContaining({ type: 'text', text: 'hello' })])
    const callsAfterTyping = onPartsChange.mock.calls.length

    // 内容与受控 parts 的键一致时不再回流草稿（仍执行 DOM 规范化与编辑通知）。
    fireEvent.input(editor, { inputType: 'insertText', data: '' })
    expect(onPartsChange.mock.calls.length).toBe(callsAfterTyping)
  })

  it('keeps an active IME composition intact against external value updates', async () => {
    // 测试意图：组合期间输入事件不得改写受控草稿，外部 parts 变化也不得重建组合态 DOM。
    const { rerender } = render(<Harness initialParts={[createTextPart('a')]} />)
    const editor = editorOf()

    // 组合输入事件携带原生 isComposing（其他输入法接管组合）：即使 compositionstart
    // 尚未到达，也不得从半成品 DOM 回流草稿。
    fireEvent.input(editor, { isComposing: true })
    expect(textSnapshot()).toBe('a')

    fireEvent.compositionStart(editor)
    const composing = document.createElement('div')
    composing.textContent = 'ceshi'
    editor.appendChild(composing)
    fireEvent.input(editor, { isComposing: true })
    expect(textSnapshot()).toBe('a')

    rerender(<Harness initialParts={[createTextPart('external replacement')]} />)
    expect(editor.querySelector('div')).not.toBeNull()
    expect(editor.textContent).toBe('aceshi')

    // 组合结束：组合态块元素被规范化解包为纯文本并回流草稿。
    fireEvent.compositionEnd(editor)
    await waitFor(() => expect(textSnapshot()).toBe('aceshi'))
    expect(editor.querySelector('div')).toBeNull()
  })

  it('does not delegate keys consumed by an IME composition', () => {
    const onKeyDown = vi.fn(() => true)
    render(<Harness initialParts={[createTextPart('draft')]} onKeyDown={onKeyDown} />)
    const editor = editorOf()

    // 组合中的 Enter 由 IME 确认候选，编辑器既不消费也不上报。
    fireEvent.keyDown(editor, { key: 'Enter', isComposing: true })
    // 部分浏览器以 keyCode 229 标记组合按键。
    fireEvent.keyDown(editor, { key: 'Enter', keyCode: 229 })
    expect(onKeyDown).not.toHaveBeenCalled()

    fireEvent.keyDown(editor, { key: 'Enter' })
    expect(onKeyDown).toHaveBeenCalledTimes(1)
  })

  it('removes the adjacent attachment pill with Backspace by partId only', async () => {
    const first = createAttachmentPart('up-x', 'report.pdf')
    const second = createAttachmentPart('up-x', 'report.pdf')
    render(<Harness initialParts={[first, second]} />)
    const editor = editorOf()
    expect(editor.querySelectorAll('.composer-pill')).toHaveLength(2)

    // caret 位于第一颗 pill 之后：Backspace 只移除该 occurrence。
    placeCaretAtChild(editor, 1)
    expect(fireEvent.keyDown(editor, { key: 'Backspace' })).toBe(false)
    await waitFor(() => expect(partsSnapshot()).toHaveLength(1))
    expect(partsSnapshot()[0]).toMatchObject({ type: 'attachment', partId: second.partId })
    expect(editor.querySelectorAll('.composer-pill')).toHaveLength(1)
  })

  it('leaves plain-text Backspace and caret-less Delete to the browser', () => {
    const onPartsChange = vi.fn()
    render(<Harness initialParts={[createTextPart('ab')]} onPartsChange={onPartsChange} />)
    const editor = editorOf()

    placeCaretAtEnd(editor)
    expect(fireEvent.keyDown(editor, { key: 'Backspace' })).toBe(true)

    // 无 selection 时的 Delete 找不到邻接 pill，同样不拦截默认行为。
    window.getSelection()?.removeAllRanges()
    expect(fireEvent.keyDown(editor, { key: 'Delete' })).toBe(true)
    expect(onPartsChange).not.toHaveBeenCalled()
  })

  it('normalizes pasted plain text and preserves the caret without touching files', () => {
    render(<Harness initialParts={[createTextPart('ab')]} />)
    const editor = editorOf()
    const onFiles = vi.fn()
    editor.focus()
    placeCaretInText(editor, 1)

    const prevented = fireEvent.paste(editor, {
      clipboardData: {
        files: [],
        items: [],
        getData: (type: string) =>
          type === 'text/plain' ? 'line1\r\nline2\rline3' : '<p>line1</p>',
      },
    })
    expect(prevented).toBe(false)
    expect(partsSnapshot()).toEqual([expect.objectContaining({ text: 'aline1\nline2\nline3b' })])
    expect(editor.querySelector('p')).toBeNull()
    expect(onFiles).not.toHaveBeenCalled()

    const caret = window.getSelection()!.getRangeAt(0)
    const beforeCaret = document.createRange()
    beforeCaret.selectNodeContents(editor)
    beforeCaret.setEnd(caret.startContainer, caret.startOffset)
    expect(beforeCaret.toString()).toBe('aline1\nline2\nline3')
  })

  it('delegates pasted and dropped files to the outer attachment pipeline', () => {
    const onFiles = vi.fn()
    const onPartsChange = vi.fn()
    render(
      <Harness
        initialParts={[createTextPart('keep')]}
        onPartsChange={onPartsChange}
        onFiles={onFiles}
      />,
    )
    const editor = editorOf()
    const file = new File([new Uint8Array(4)], 'shot.png', { type: 'image/png' })

    fireEvent.paste(editor, { clipboardData: { files: [file], getData: () => '' } })
    expect(onFiles).toHaveBeenCalledWith([file])

    // 剪贴板 item 形式的文件同样交给外层（files 为空时回退 items，非文件条目被过滤）。
    const itemFile = new File([new Uint8Array(4)], 'clip.png', { type: 'image/png' })
    fireEvent.paste(editor, {
      clipboardData: {
        files: [],
        items: [{ kind: 'file', getAsFile: () => itemFile }, { kind: 'string', getAsFile: () => null }],
        getData: () => '',
      },
    })
    expect(onFiles).toHaveBeenLastCalledWith([itemFile])

    fireEvent.drop(editor, { dataTransfer: { files: [file], getData: () => '' } })
    expect(onFiles).toHaveBeenLastCalledWith([file])

    // 文件路径不回流草稿、不插入文本。
    expect(onPartsChange).not.toHaveBeenCalled()
    expect(editor.textContent).toBe('keep')
  })

  it('ignores paste without clipboard data and inserts dropped text at the caret', () => {
    render(<Harness initialParts={[createTextPart('pre')]} />)
    const editor = editorOf()
    placeCaretAtEnd(editor)

    expect(fireEvent.paste(editor, { clipboardData: null })).toBe(true)
    expect(textSnapshot()).toBe('pre')

    fireEvent.drop(editor, {
      dataTransfer: { files: [], getData: (type: string) => (type === 'text/plain' ? ' dropped ' : '') },
    })
    expect(textSnapshot()).toBe('pre dropped ')

    // dataTransfer 缺失时既不创建 pill 也不改草稿。
    fireEvent.drop(editor, {})
    expect(textSnapshot()).toBe('pre dropped ')
  })

  it('ignores paste, drop and keys while disabled even though the DOM still receives them', async () => {
    // 测试意图：contentEditable=false 只禁止编辑，paste/drop/keydown 仍会到达编辑器；
    // 不可编辑时不得编排附件、改写草稿、触发命令表/提交或历史回放。
    const onPartsChange = vi.fn()
    const onFiles = vi.fn()
    const onKeyDown = vi.fn(() => true)
    const onNavigateHistory = vi.fn(() => true)
    render(
      <Harness
        initialParts={[createTextPart('locked'), createAttachmentPart('local-9', 'shot.png')]}
        disabled
        onPartsChange={onPartsChange}
        onFiles={onFiles}
        onKeyDown={onKeyDown}
        onNavigateHistory={onNavigateHistory}
      />,
    )
    const editor = editorOf()
    expect(editor).toHaveAttribute('contenteditable', 'false')
    const file = new File([new Uint8Array(4)], 'shot.png', { type: 'image/png' })

    // 既不 preventDefault（不伪装已处理），也不进入附件/文本编排。
    expect(
      fireEvent.paste(editor, { clipboardData: { files: [file], getData: () => 'pasted' } }),
    ).toBe(true)
    expect(
      fireEvent.drop(editor, { dataTransfer: { files: [file], getData: () => 'dropped' } }),
    ).toBe(true)
    expect(onFiles).not.toHaveBeenCalled()
    expect(onPartsChange).not.toHaveBeenCalled()
    expect(editor.textContent).toBe('locked[shot.png]')

    // caret 位于 pill 之后：Backspace 不删除 pill；Enter/Arrow 不下发到外层。
    placeCaretAtChild(editor, 1)
    fireEvent.keyDown(editor, { key: 'Backspace' })
    placeCaretAtEnd(editor)
    fireEvent.keyDown(editor, { key: 'Enter' })
    fireEvent.keyDown(editor, { key: 'ArrowUp' })
    expect(onKeyDown).not.toHaveBeenCalled()
    expect(onNavigateHistory).not.toHaveBeenCalled()
    expect(onPartsChange).not.toHaveBeenCalled()
    expect(editor.querySelectorAll('.composer-pill')).toHaveLength(1)
  })

  it('normalizes Enter/Shift+Enter line breaks into one plain-text newline', async () => {
    // 测试意图：浏览器默认插入 <br>/块边界，换行统一由 input -> normalizeEditorDom 收敛。
    render(<Harness initialParts={[createTextPart('line1')]} />)
    const editor = editorOf()
    editor.focus()
    editor.appendChild(document.createElement('br'))
    editor.appendChild(document.createTextNode('line2'))
    placeCaretAtEnd(editor)

    fireEvent.input(editor, { inputType: 'insertLineBreak' })

    await waitFor(() =>
      expect(partsSnapshot()).toEqual([expect.objectContaining({ text: 'line1\nline2' })]),
    )
    expect(editor.querySelector('br')).toBeNull()
    expect(editor.textContent).toBe('line1\nline2')
  })

  it('hands arrow keys to history replay only at the caret boundary', () => {
    const onNavigateHistory = vi.fn(() => true)
    render(
      <Harness
        initialParts={[createTextPart('line 1\nline 2')]}
        onNavigateHistory={onNavigateHistory}
      />,
    )
    const editor = editorOf()

    // 无 selection：不导航。
    window.getSelection()?.removeAllRanges()
    expect(fireEvent.keyDown(editor, { key: 'ArrowUp' })).toBe(true)
    expect(onNavigateHistory).not.toHaveBeenCalled()

    // 非 collapsed selection：跨选区箭头交给浏览器。
    const selecting = document.createRange()
    selecting.selectNodeContents(editor)
    window.getSelection()?.removeAllRanges()
    window.getSelection()?.addRange(selecting)
    fireEvent.keyDown(editor, { key: 'ArrowUp' })
    expect(onNavigateHistory).not.toHaveBeenCalled()

    // 末行 ArrowUp：光标仍在编辑器内移动，不召回历史。
    placeCaretAtEnd(editor)
    expect(fireEvent.keyDown(editor, { key: 'ArrowUp' })).toBe(true)
    expect(onNavigateHistory).not.toHaveBeenCalled()

    // 首行 ArrowUp：光标已到起点，交接历史回放并拦截默认光标移动。
    const start = document.createRange()
    start.selectNodeContents(editor)
    start.collapse(true)
    window.getSelection()?.removeAllRanges()
    window.getSelection()?.addRange(start)
    expect(fireEvent.keyDown(editor, { key: 'ArrowUp' })).toBe(false)
    expect(onNavigateHistory).toHaveBeenCalledWith('previous')

    // 末行 ArrowDown 走 next 方向。
    placeCaretAtEnd(editor)
    expect(fireEvent.keyDown(editor, { key: 'ArrowDown' })).toBe(false)
    expect(onNavigateHistory).toHaveBeenLastCalledWith('next')

    // Shift+ArrowDown 属于选择操作，不接管。
    expect(fireEvent.keyDown(editor, { key: 'ArrowDown', shiftKey: true })).toBe(true)
  })

  it('does not swallow browser navigation when history replay is rejected', () => {
    const onNavigateHistory = vi.fn(() => false)
    render(
      <Harness initialParts={[createTextPart('draft')]} onNavigateHistory={onNavigateHistory} />,
    )
    const editor = editorOf()
    placeCaretAtEnd(editor)
    expect(fireEvent.keyDown(editor, { key: 'ArrowDown' })).toBe(true)
    expect(onNavigateHistory).toHaveBeenCalledWith('next')
  })

  it('reports edit start and editor mousedown separately for overlay dismissal', async () => {
    const onEditStart = vi.fn()
    const onEditorMouseDown = vi.fn()
    render(
      <Harness
        initialParts={[createTextPart('x')]}
        onEditStart={onEditStart}
        onEditorMouseDown={onEditorMouseDown}
      />,
    )
    const editor = editorOf()

    // mousedown 只通知底栏菜单；编辑通知由真正的编辑行为触发。
    fireEvent.mouseDown(editor)
    expect(onEditorMouseDown).toHaveBeenCalledTimes(1)
    expect(onEditStart).not.toHaveBeenCalled()

    await typeInEditor(editor, 'y')
    expect(onEditStart).toHaveBeenCalled()
  })

  it('syncs the controlled draft to the DOM and returns extracted parts', () => {
    const apiRef = createRef<ComposerEditorHandle>()
    const onPartsChange = vi.fn()
    render(
      <Harness
        apiRef={apiRef}
        initialParts={[createTextPart('old draft')]}
        onPartsChange={onPartsChange}
      />,
    )
    const editor = editorOf()

    // DOM 尚未同步到受控草稿：syncDraft 以 DOM 为准强制回流并返回提取结果。
    editor.textContent = 'new unsynchronized draft'
    let domParts: ComposerPart[] | null = null
    act(() => {
      domParts = apiRef.current?.syncDraft() ?? null
    })
    expect(domParts).toEqual([
      expect.objectContaining({ type: 'text', text: 'new unsynchronized draft' }),
    ])
    expect(onPartsChange).toHaveBeenCalledWith([
      expect.objectContaining({ type: 'text', text: 'new unsynchronized draft' }),
    ])

    // 已对齐时 syncDraft 幂等：不重复回流。
    const calls = onPartsChange.mock.calls.length
    let repeated: ComposerPart[] | null = null
    act(() => {
      repeated = apiRef.current?.syncDraft() ?? null
    })
    expect(repeated).toEqual([
      expect.objectContaining({ type: 'text', text: 'new unsynchronized draft' }),
    ])
    expect(onPartsChange.mock.calls.length).toBe(calls)
  })

  it('inserts attachment pills at the caret and reflows the draft', async () => {
    const apiRef = createRef<ComposerEditorHandle>()
    render(<Harness apiRef={apiRef} initialParts={[createTextPart('ab')]} />)
    const editor = editorOf()
    placeCaretInText(editor, 1)

    await act(async () => {
      apiRef.current?.insertParts([createAttachmentPart('local-1', 'mid.png', '720P')])
    })

    expect(partsSnapshot().map((part) => part.type)).toEqual(['text', 'attachment', 'text'])
    expect(textSnapshot()).toBe('a[attachment]b')
    const pill = editor.querySelector('.composer-pill')
    expect(pill).toHaveAttribute('data-upload-id', 'local-1')
    expect(pill).toHaveAttribute('data-image-tier', '720P')
  })

  it('rolls back failed upload pills and defers the DOM teardown during composition', async () => {
    const apiRef = createRef<ComposerEditorHandle>()
    const onPartsChange = vi.fn()
    render(
      <Harness
        apiRef={apiRef}
        initialParts={[createTextPart('keep'), createAttachmentPart('local-2', 'failing.bin')]}
        onPartsChange={onPartsChange}
      />,
    )
    const editor = editorOf()
    expect(editor.querySelectorAll('.composer-pill')).toHaveLength(1)

    // 未知句柄没有任何 pill：不回滚、不回流。
    act(() => apiRef.current?.removeAttachmentPills(['ghost']))
    expect(onPartsChange).not.toHaveBeenCalled()
    expect(editor.querySelectorAll('.composer-pill')).toHaveLength(1)

    // 组合期间：pill 保留原状（不重建组合态 DOM），等 compositionend 再清理。
    fireEvent.compositionStart(editor)
    act(() => apiRef.current?.removeAttachmentPills(['local-2']))
    expect(editor.querySelectorAll('.composer-pill')).toHaveLength(1)
    fireEvent.compositionEnd(editor)
    await waitFor(() => expect(editor.querySelectorAll('.composer-pill')).toHaveLength(0))
    expect(textSnapshot()).toBe('keep')
  })

  it('patches the image tier of rendered attachment pills without rebuilding the draft', () => {
    const apiRef = createRef<ComposerEditorHandle>()
    const onPartsChange = vi.fn()
    render(
      <Harness
        apiRef={apiRef}
        initialParts={[createAttachmentPart('local-3', 'shot.png', '720P')]}
        onPartsChange={onPartsChange}
      />,
    )
    const pill = editorOf().querySelector('.composer-pill')
    expect(pill).toHaveAttribute('data-image-tier', '720P')

    act(() => apiRef.current?.updateAttachmentImageTier(['local-3', 'up-3'], '1080P'))
    expect(pill).toHaveAttribute('data-image-tier', '1080P')
    expect(onPartsChange).not.toHaveBeenCalled()
  })
})
