import { act, useState } from 'react'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it, vi } from 'vitest'
import { ChatLayoutSelector } from '@/features/chat/ChatLayoutSelector'
import type { ChatLayout } from '@/features/chat/chat-pane-state'
import { setLocale } from '@/shared/i18n'

async function openLayoutListbox(user: ReturnType<typeof userEvent.setup>, triggerName = '布局') {
  await user.click(screen.getByRole('button', { name: triggerName }))
  return screen.findByRole('listbox')
}

/** 选项的可访问名就是读屏播报的内容（勾选图标 aria-hidden，不参与命名）。 */
function optionNames(listbox: HTMLElement) {
  return within(listbox).getAllByRole('option').map((option) => option.textContent)
}

// 读屏只念选项名，所以每个布局选项都必须带量词，而不是裸数字。
it('names every layout option with a localized pane count', async () => {
  const user = userEvent.setup()
  render(<ChatLayoutSelector layout="single" onChange={vi.fn()} />)

  const listbox = await openLayoutListbox(user)
  expect(optionNames(listbox)).toEqual([
    '1 个窗格',
    '2 个窗格',
    '3 个窗格',
    '4 个窗格',
    '5 个窗格',
    '6 个窗格',
    '7 个窗格',
    '8 个窗格',
  ])
})

// 英文按量词区分单复数：只有 1 个面板用单数。
it('uses the English singular for one pane and the plural for 2-8 panes', async () => {
  act(() => setLocale('en-US'))
  const user = userEvent.setup()
  render(<ChatLayoutSelector layout="single" onChange={vi.fn()} />)

  const listbox = await openLayoutListbox(user, 'Layout')
  expect(optionNames(listbox)).toEqual([
    '1 pane',
    '2 panes',
    '3 panes',
    '4 panes',
    '5 panes',
    '6 panes',
    '7 panes',
    '8 panes',
  ])
})

// 用带量词的名字定位选项并选中它，结果必须落在对应的布局上（而不是同数字的其它项）。
it.each([
  { locale: 'zh-CN' as const, triggerName: '布局', label: '5 个窗格', layout: 'grid-5' as ChatLayout },
  { locale: 'en-US' as const, triggerName: 'Layout', label: '3 panes', layout: 'split-3' as ChatLayout },
])('selects $layout through the option name "$label"', async ({ locale, triggerName, label, layout }) => {
  act(() => setLocale(locale))
  const onChange = vi.fn()
  const user = userEvent.setup()
  render(<ChatLayoutSelector layout="single" onChange={onChange} />)

  const listbox = await openLayoutListbox(user, triggerName)
  await user.click(within(listbox).getByRole('option', { name: label }))

  expect(onChange).toHaveBeenCalledWith(layout)
})

// 选中后触发器展示同一份带量词文案，组合框自身的名字保持稳定。
it('shows the selected pane count on the trigger and keeps the combobox name', async () => {
  function Harness() {
    const [layout, setLayout] = useState<ChatLayout>('single')
    return <ChatLayoutSelector layout={layout} onChange={setLayout} />
  }
  const user = userEvent.setup()
  render(<Harness />)

  const trigger = screen.getByRole('button', { name: '布局' })
  expect(trigger).toHaveTextContent('1 个窗格')

  const listbox = await openLayoutListbox(user)
  await user.click(within(listbox).getByRole('option', { name: '8 个窗格' }))

  expect(screen.getByRole('button', { name: '布局' })).toHaveTextContent('8 个窗格')
})
