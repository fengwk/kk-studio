import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { ThreadComposer } from '@/features/ai/runtime/thread-panel/ThreadComposer'
import { ThreadCommandPalette } from '@/features/ai/runtime/thread-panel/ThreadCommandPalette'
import { ThreadWidgetStack } from '@/features/ai/runtime/thread-panel/ThreadWidgetStack'
import {
  createAttachmentPart,
  createTextPart,
  type ComposerPart,
} from '@/features/ai/composer/composer-parts'
import type { ThreadComposerSettingsInput } from '@/features/ai/runtime/thread-panel/ThreadComposerControls'
import type { ThreadCommand } from '@/features/ai/runtime/thread-panel/thread-commands'

function ControlledComposer({
  onSubmit,
  onCommand,
  initial = [],
  historicalUserMessages = [],
  queuedUserMessages = [],
  focusOnEscape = false,
  settings,
}: {
  onSubmit: () => void
  onCommand: (command: ThreadCommand) => void
  initial?: ComposerPart[]
  historicalUserMessages?: readonly string[]
  queuedUserMessages?: readonly string[]
  focusOnEscape?: boolean
  settings?: ThreadComposerSettingsInput
}) {
  const [parts, setParts] = useState(initial)
  return (
    <ThreadComposer
      parts={parts}
      pending={false}
      disabled={false}
      onPartsChange={setParts}
      onSubmit={onSubmit}
      onCommand={onCommand}
      historicalUserMessages={historicalUserMessages}
      queuedUserMessages={queuedUserMessages}
      focusOnEscape={focusOnEscape}
      settings={settings}
    />
  )
}

describe('ThreadComposer interactions', () => {
  // Esc 切换焦点并保留草稿；blur 收起后隐藏状态不执行命令也不发送普通消息；再次 Esc 聚焦后自动重开菜单。
  it('preserves slash text across repeated Escape and reopens after editing', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    const onCommand = vi.fn()
    render(<ControlledComposer focusOnEscape onSubmit={onSubmit} onCommand={onCommand} />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    await user.type(editor, '/de')
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()

    // 第 1 次 Esc：聚焦时 blur 并收起菜单，保留草稿 /de
    await user.keyboard('{Escape}')
    expect(editor).toHaveTextContent('/de')
    expect(editor).not.toHaveFocus()
    expect(screen.queryByLabelText('命令表')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '发送消息' })).toBeDisabled()

    // 失焦状态下按 Enter：无隐藏命令执行，也不发送普通消息
    await user.keyboard('{Enter}')
    expect(onSubmit).not.toHaveBeenCalled()
    expect(onCommand).not.toHaveBeenCalled()
    expect(editor).toHaveTextContent('/de')

    // 第 2 次 Esc：未聚焦时通过 focusOnEscape 重新聚焦 editor，/de 自动重开菜单
    await user.keyboard('{Escape}')
    expect(editor).toHaveFocus()
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    expect(editor).toHaveTextContent('/de')

    // 继续输入补全并执行可见选项
    await user.keyboard('bug')
    expect(editor).toHaveTextContent('/debug')
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    await user.keyboard('{Enter}')
    expect(onCommand).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ id: 'debug' }))
    expect(onSubmit).not.toHaveBeenCalled()
    expect(editor).toBeEmptyDOMElement()
  })

  // + 菜单覆盖 slash 提示时，一次 Esc 必须关闭二者，不能退回自动弹出的 slash 菜单。
  it('closes plus and slash menus without losing text or attachments', async () => {
    const user = userEvent.setup()
    render(<ControlledComposer
      initial={[createTextPart('/de'), createAttachmentPart('upload-1', 'a.png')]}
      focusOnEscape
      onSubmit={vi.fn()}
      onCommand={vi.fn()}
    />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    // 含附件时保持原有 slash 识别规则；菜单关闭不得删除任意内容块。
    await user.click(screen.getByRole('button', { name: '打开命令表' }))
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    const before = editor.textContent
    fireEvent.keyDown(window, { key: 'Escape' })
    await user.keyboard('{Escape}')
    expect(editor.textContent).toBe(before)
    expect(editor.querySelector('[data-part-type="attachment"]')).not.toBeNull()
    expect(screen.queryByLabelText('命令表')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '打开命令表' }))
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
  })

  // 隐藏提示不会解禁命令，也不能执行上一次活动选项。
  it('does not execute a disabled complete command after dismissal', async () => {
    const user = userEvent.setup()
    const onCommand = vi.fn()
    const onSubmit = vi.fn()
    render(<ThreadComposer
      parts={[createTextPart('/debug')]}
      commands={[{ id: 'debug', label: 'debug', description: '', disabled: true }]}
      pending={false}
      disabled={false}
      onPartsChange={vi.fn()}
      onSubmit={onSubmit}
      onCommand={onCommand}
    />)
    await user.click(screen.getByLabelText('给 AI 发送消息'))
    await user.keyboard('{Escape}{Enter}')
    expect(onCommand).not.toHaveBeenCalled()
    expect(onSubmit).not.toHaveBeenCalled()
    expect(screen.getByLabelText('给 AI 发送消息')).toHaveTextContent('/debug')
  })

  it('supports slash mode command execution and Escape close', async () => {
    const user = userEvent.setup()
    const onCommand = vi.fn()
    render(<ControlledComposer onSubmit={vi.fn()} onCommand={onCommand} />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    await user.type(editor, '/yolo')
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    await user.keyboard('{Enter}')
    expect(onCommand).toHaveBeenCalledWith(expect.objectContaining({ id: 'yolo' }))
  })

  it('navigates slash options with ArrowUp/ArrowDown then confirms with Enter', async () => {
    const user = userEvent.setup()
    const onCommand = vi.fn()
    render(<ControlledComposer onSubmit={vi.fn()} onCommand={onCommand} />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    await user.type(editor, '/')
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    // 稳定产品顺序保持不变：thread -> agent -> environment；初始 active 是第一项。
    await user.keyboard('{ArrowDown}{Enter}')
    expect(onCommand).toHaveBeenCalledWith(expect.objectContaining({ id: 'agent' }))
  })

  it('navigates historical users, queued users, then restores the current draft', async () => {
    const user = userEvent.setup()
    render(
      <ControlledComposer
        initial={[createTextPart('current draft')]}
        historicalUserMessages={['history 1', 'history 2']}
        queuedUserMessages={['queued 1', 'queued 2']}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
      />,
    )
    const editor = screen.getByLabelText('给 AI 发送消息')
    await user.click(editor)

    // 游标从末尾草稿槽位开始，向上按时间倒序遍历三个数据源。
    await user.keyboard('{ArrowUp}')
    expect(editor.textContent).toBe('queued 2')
    await user.keyboard('{ArrowUp}')
    expect(editor.textContent).toBe('queued 1')
    await user.keyboard('{ArrowUp}')
    expect(editor.textContent).toBe('history 2')
    await user.keyboard('{ArrowUp}')
    expect(editor.textContent).toBe('history 1')
    await user.keyboard('{ArrowUp}')
    expect(editor.textContent).toBe('history 1')

    await user.keyboard('{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}')
    expect(editor.textContent).toBe('current draft')
    await user.keyboard('{ArrowDown}')
    expect(editor.textContent).toBe('current draft')
  })

  it('promotes an edited recalled message to the new draft slot', async () => {
    const user = userEvent.setup()
    render(
      <ControlledComposer
        initial={[createTextPart('original draft')]}
        historicalUserMessages={['history']}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
      />,
    )
    const editor = screen.getByLabelText('给 AI 发送消息')
    await user.click(editor)
    await user.keyboard('{ArrowUp}')
    await user.type(editor, ' edited')

    // 编辑会结束浏览；再次向上取最近历史，向下则返回刚编辑出的新草稿。
    await user.keyboard('{ArrowUp}')
    expect(editor.textContent).toBe('history')
    await user.keyboard('{ArrowDown}')
    expect(editor.textContent).toBe('history edited')
  })

  it('uses an empty virtual draft slot and does not discard attachment drafts', async () => {
    const user = userEvent.setup()
    const { rerender } = render(
      <ControlledComposer
        historicalUserMessages={['history']}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
      />,
    )
    const editor = screen.getByLabelText('给 AI 发送消息')
    await user.click(editor)
    await user.keyboard('{ArrowUp}')
    expect(editor.textContent).toBe('history')
    await user.keyboard('{ArrowDown}')
    expect(editor.textContent).toBe('')

    rerender(
      <ControlledComposer
        key="attachment"
        initial={[
          createTextPart('keep attachment'),
          createAttachmentPart('upload-1', 'a.txt'),
        ]}
        historicalUserMessages={['history']}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
      />,
    )
    const attachmentEditor = screen.getByLabelText('给 AI 发送消息')
    await user.click(attachmentEditor)
    await user.keyboard('{ArrowUp}')
    expect(attachmentEditor).toHaveTextContent('keep attachment')
    expect(attachmentEditor.querySelector('[data-upload-id="upload-1"]')).not.toBeNull()
  })

  it('keeps multiline caret navigation until it reaches the history boundary', () => {
    render(
      <ControlledComposer
        initial={[createTextPart('line 1\nline 2')]}
        historicalUserMessages={['history']}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
      />,
    )
    const editor = screen.getByLabelText('给 AI 发送消息')
    editor.focus()
    const selection = document.getSelection()
    const range = document.createRange()
    range.selectNodeContents(editor)
    range.collapse(false)
    selection?.removeAllRanges()
    selection?.addRange(range)

    // 第二行上的 ArrowUp 仍由 contenteditable 处理，不会误召回历史。
    fireEvent.keyDown(editor, { key: 'ArrowUp' })
    expect(editor.textContent).toBe('line 1\nline 2')

    range.selectNodeContents(editor)
    range.collapse(true)
    selection?.removeAllRanges()
    selection?.addRange(range)
    fireEvent.keyDown(editor, { key: 'ArrowUp' })
    expect(editor.textContent).toBe('history')
  })

  it('scrolls a recalled multiline message to its ending caret', () => {
    render(
      <ControlledComposer
        initial={[createTextPart('current draft')]}
        historicalUserMessages={['line 1\nline 2\nline 3']}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
      />,
    )
    const editor = screen.getByLabelText('给 AI 发送消息')
    Object.defineProperty(editor, 'scrollHeight', {
      configurable: true,
      value: 480,
    })
    editor.scrollTop = 0
    editor.focus()
    const selection = document.getSelection()
    const range = document.createRange()
    range.selectNodeContents(editor)
    range.collapse(false)
    selection?.removeAllRanges()
    selection?.addRange(range)

    // 历史内容超过 editor 高度时，末尾光标与内部滚动位置必须同步。
    fireEvent.keyDown(editor, { key: 'ArrowUp' })

    expect(editor.textContent).toBe('line 1\nline 2\nline 3')
    expect(editor.scrollTop).toBe(480)
  })

  it('opens from slash or plus and closes with Escape', async () => {
    const user = userEvent.setup()
    render(<ControlledComposer onSubmit={vi.fn()} onCommand={vi.fn()} />)
    const add = screen.getByRole('button', { name: '打开命令表' })
    await user.click(add)
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    await user.keyboard('{Escape}')
    expect(screen.queryByLabelText('命令表')).not.toBeInTheDocument()
    await user.type(screen.getByLabelText('给 AI 发送消息'), '/stop')
    const palette = await screen.findByLabelText('命令表')
    expect(palette).toBeInTheDocument()
    // Slash 查询只存在于 Composer；命令表不再重复渲染搜索标题行。
    expect(document.querySelector('.thread-command-search')).toBeNull()
    await user.keyboard('{Escape}')
    expect(screen.queryByLabelText('命令表')).not.toBeInTheDocument()
  })

  it('focuses only the enabled Composer when Escape is pressed outside the editor', async () => {
    const user = userEvent.setup()
    render(
      <>
        <button type="button">Transcript action</button>
        <ControlledComposer onSubmit={vi.fn()} onCommand={vi.fn()} />
        <ControlledComposer focusOnEscape onSubmit={vi.fn()} onCommand={vi.fn()} />
      </>,
    )
    const outside = screen.getByRole('button', { name: 'Transcript action' })
    const editors = screen.getAllByLabelText('给 AI 发送消息')
    await user.click(outside)
    expect(outside).toHaveFocus()

    await user.keyboard('{Escape}')

    // 多 Composer 页面只能由当前交互作用域接管 Escape，避免多个输入框争抢焦点。
    await waitFor(() => expect(editors[1]).toHaveFocus())
    expect(editors[0]).not.toHaveFocus()
    const selection = document.getSelection()
    expect(selection?.rangeCount).toBe(1)
    expect(editors[1]?.contains(selection?.getRangeAt(0).commonAncestorContainer ?? null)).toBe(true)
    const caret = selection?.getRangeAt(0)
    const trailing = document.createRange()
    trailing.selectNodeContents(editors[1]!)
    trailing.setStart(caret!.endContainer, caret!.endOffset)
    expect(caret?.collapsed).toBe(true)
    expect(trailing.toString()).toBe('')
  })

  it('does not steal Escape while a modal overlay is open', async () => {
    const user = userEvent.setup()
    render(
      <>
        <ControlledComposer focusOnEscape onSubmit={vi.fn()} onCommand={vi.fn()} />
        <div className="modal-backdrop">
          <button type="button">Modal action</button>
        </div>
      </>,
    )
    const modalAction = screen.getByRole('button', { name: 'Modal action' })
    await user.click(modalAction)
    await user.keyboard('{Escape}')

    // Overlay 拥有优先 Escape 语义，Composer 不得把焦点从弹层控件后方抢走。
    expect(modalAction).toHaveFocus()
    expect(screen.getByLabelText('给 AI 发送消息')).not.toHaveFocus()
  })

  it('does not consume Escape while the Composer is disabled', async () => {
    const user = userEvent.setup()
    render(
      <>
        <button type="button">Disabled scope action</button>
        <ThreadComposer
          parts={[]}
          pending
          disabled
          focusOnEscape
          onPartsChange={vi.fn()}
          onSubmit={vi.fn()}
          onCommand={vi.fn()}
        />
      </>,
    )
    const outside = screen.getByRole('button', { name: 'Disabled scope action' })
    await user.click(outside)
    await user.keyboard('{Escape}')

    // 不可编辑时没有可恢复的输入目标，Escape 应留给当前控件/浏览器处理。
    expect(outside).toHaveFocus()
    expect(screen.getByLabelText('给 AI 发送消息')).not.toHaveFocus()
  })

  it('keeps the Composer mounted and restores its ending caret after an interaction panel closes', async () => {
    const props = {
      parts: [createTextPart('preserved draft')],
      pending: false,
      disabled: false,
      focusOnEscape: true,
      onPartsChange: vi.fn(),
      onSubmit: vi.fn(),
      onCommand: vi.fn(),
    }
    const { container, rerender } = render(<ThreadComposer {...props} active />)
    const editor = screen.getByLabelText('给 AI 发送消息')

    rerender(<ThreadComposer {...props} active={false} />)
    expect(container.querySelector('.thread-composer')).toHaveAttribute('hidden')
    expect(editor).toHaveTextContent('preserved draft')

    rerender(<ThreadComposer {...props} active />)
    await waitFor(() => expect(editor).toHaveFocus())
    // 隐藏只切换交互槽位，不卸载草稿；回来后直接从末尾继续输入。
    expect(editor).toHaveTextContent('preserved draft')
    const selection = document.getSelection()
    const caret = selection?.getRangeAt(0)
    const trailing = document.createRange()
    trailing.selectNodeContents(editor)
    trailing.setStart(caret!.endContainer, caret!.endOffset)
    expect(trailing.toString()).toBe('')
  })

  it('keeps send enabled while a previous mutation is still pending', () => {
    render(
      <ThreadComposer
        parts={[createTextPart('hello')]}
        pending
        disabled={false}
        onPartsChange={vi.fn()}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
      />,
    )
    expect(screen.getByRole('button', { name: '发送消息' })).toBeEnabled()
  })

  it('cancels delayed focus work when the composer unmounts', () => {
    vi.useFakeTimers()
    try {
      const { unmount } = render(
        <ThreadComposer
          parts={[createTextPart('hello')]}
          pending={false}
          disabled={false}
          onPartsChange={vi.fn()}
          onSubmit={vi.fn()}
          onCommand={vi.fn()}
        />,
      )

      fireEvent.click(screen.getByRole('button', { name: '发送消息' }))
      expect(vi.getTimerCount()).toBe(1)
      unmount()
      expect(vi.getTimerCount()).toBe(0)
    } finally {
      vi.useRealTimers()
    }
  })

  it('renders only the shared plus command entry, without standalone actor-state, Stop, or Retry controls', () => {
    render(
      <ThreadComposer
        parts={[]}
        pending={false}
        disabled={false}
        onPartsChange={vi.fn()}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
      />,
    )
    expect(screen.getByRole('button', { name: '打开命令表' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Stop' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument()
    expect(screen.queryByText('RUNNING')).not.toBeInTheDocument()
    expect(screen.queryByText('IDLE')).not.toBeInTheDocument()
  })

  it('disables the complete composer while no Thread projection is available', () => {
    render(
      <ThreadComposer
        parts={[createTextPart('message')]}
        pending={false}
        disabled
        onPartsChange={vi.fn()}
        onSubmit={vi.fn()}
        onCommand={vi.fn()}
      />,
    )
    expect(screen.getByLabelText('给 AI 发送消息')).toHaveAttribute('aria-disabled', 'true')
    expect(screen.getByRole('button', { name: '发送消息' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '打开命令表' })).toBeDisabled()
  })

  it('handles true sequential Escape shortcuts with control menu priority: 1st closes control menu, 2nd blurs composer, 3rd refocuses editor via focusOnEscape', async () => {
    const user = userEvent.setup()
    const dummySettings: ThreadComposerSettingsInput = {
      model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
      models: [{ providerName: 'minimax', name: 'MiniMax', config: { defaultVariant: 'default', variants: [{ id: 'default' }] } }],
      yoloEnabled: false,
      environmentName: null,
      environments: [],
      onModelChange: vi.fn(),
      onYoloChange: vi.fn(),
      onEnvironmentChange: vi.fn(),
    }
    render(<ControlledComposer focusOnEscape settings={dummySettings} onSubmit={vi.fn()} onCommand={vi.fn()} />)
    const editor = screen.getByLabelText('给 AI 发送消息')

    // 聚焦输入 /th
    await user.type(editor, '/th')
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()

    // 打开权限菜单（上层 control menu）
    await user.click(screen.getByRole('button', { name: '权限模式' }))
    expect(await screen.findByRole('listbox', { name: '权限选项' })).toBeInTheDocument()

    // 真正连续快捷键 1：Esc 关闭权限菜单，优先消费 Esc，保持 editor 聚焦并呈现 /th 命令表
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('listbox', { name: '权限选项' })).not.toBeInTheDocument()
    expect(editor).toHaveFocus()
    expect(screen.getByLabelText('命令表')).toBeInTheDocument()

    // 真正连续快捷键 2：Esc 此时聚焦且无上层菜单，blur 整个 composer 并收起
    await user.keyboard('{Escape}')
    expect(editor).not.toHaveFocus()
    expect(screen.queryByLabelText('命令表')).not.toBeInTheDocument()
    expect(editor).toHaveTextContent('/th')

    // 真正连续快捷键 3：Esc 未聚焦时全局 focusOnEscape 恢复焦点，并因 /th 自动重开命令表
    await user.keyboard('{Escape}')
    await waitFor(() => expect(editor).toHaveFocus())
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()
    expect(editor).toHaveTextContent('/th')
  })

  it('switches focus via Tab within composer region without closing the command palette', async () => {
    const user = userEvent.setup()
    render(<ControlledComposer onSubmit={vi.fn()} onCommand={vi.fn()} />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    await user.type(editor, '/th')
    expect(await screen.findByLabelText('命令表')).toBeInTheDocument()

    // Tab 移动焦点到 + 按钮（仍在区域内）
    await user.tab()
    expect(screen.getByRole('button', { name: '打开命令表' })).toHaveFocus()
    // 菜单依然保持打开！
    expect(screen.getByLabelText('命令表')).toBeInTheDocument()
  })

  it('toggles focus on Escape for plain text and preserves text content without opening menu', async () => {
    const user = userEvent.setup()
    render(<ControlledComposer focusOnEscape onSubmit={vi.fn()} onCommand={vi.fn()} />)
    const editor = screen.getByLabelText('给 AI 发送消息')
    await user.type(editor, 'ordinary text')
    expect(screen.queryByLabelText('命令表')).not.toBeInTheDocument()
    expect(editor).toHaveFocus()

    // 聚焦时 Esc：blur 失焦，普通文本保留
    await user.keyboard('{Escape}')
    expect(editor).not.toHaveFocus()
    expect(editor).toHaveTextContent('ordinary text')
    expect(screen.queryByLabelText('命令表')).not.toBeInTheDocument()

    // 未聚焦时 Esc：focus 聚焦，文本保留，仍无菜单
    await user.keyboard('{Escape}')
    await waitFor(() => expect(editor).toHaveFocus())
    expect(editor).toHaveTextContent('ordinary text')
    expect(screen.queryByLabelText('命令表')).not.toBeInTheDocument()
  })
})

describe('ThreadCommandPalette', () => {
  it('filters and selects a slash command', async () => {
    const user = userEvent.setup()
    const onSelect = vi.fn()
    render(
      <ThreadCommandPalette
        open
        query="stop"
        activeIndex={0}
        onActiveIndexChange={vi.fn()}
        onSelect={onSelect}
      />,
    )
    await user.click(screen.getByRole('option', { name: /^stop/ }))
    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ id: 'stop' }))
  })
})

describe('ThreadWidgetStack', () => {
  it('renders queued messages under the working zone and stays empty when idle', () => {
    const { container, rerender } = render(
      <ThreadWidgetStack
        working
        queuedMessages={[
          { idempotencyKey: 'queued-1', role: 'user', text: '稍后处理这条', sequence: 1 },
        ]}
      />,
    )
    expect(screen.getByText('Working...')).toBeInTheDocument()
    expect(screen.getByText('稍后处理这条')).toBeInTheDocument()
    const zone = screen.getByLabelText('会话组件区')
    const zoneText = zone.textContent ?? ''
    expect(zoneText.indexOf('Working...')).toBeLessThan(zoneText.indexOf('稍后处理这条'))

    rerender(<ThreadWidgetStack working={false} />)
    expect(container.querySelector('.thread-widget-zone')?.childNodes.length ?? 0).toBe(0)
  })
})
