import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { interactionService } from '@/shared/api/interaction-service'
import { ApplicationEventTestProvider } from '@/test-support/application-event-test-provider'
import { QuestionnaireCard } from './QuestionnaireCard'
import { interactionDraftStore } from './interaction-draft-store'

const SINGLE_ARGUMENTS = JSON.stringify({
  questions: [
    {
      question: '选择迁移窗口',
      options: [
        { label: '本周六凌晨' },
        { label: '本周日凌晨', description: '回滚窗口更长' },
        { label: '下个版本发布后' },
      ],
    },
  ],
})

const MULTI_ARGUMENTS = JSON.stringify({
  questions: [
    {
      question: '选择需要归档的产物',
      multiple: true,
      options: [{ label: '构建日志' }, { label: '测试报告' }, { label: '截图' }],
    },
  ],
})

describe('QuestionnaireCard 选项语义与只读状态', () => {
  let queryClient: QueryClient

  const wrapper = ({ children }: { children: ReactNode }) => (
    <ApplicationEventTestProvider>
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    </ApplicationEventTestProvider>
  )

  const renderCard = (props: {
    argumentsJson: string
    result?: unknown
    interactionId?: string
  }) =>
    render(
      <QuestionnaireCard
        interactionId={props.interactionId ?? 'int-semantics'}
        threadId="thread-1"
        argumentsJson={props.argumentsJson}
        result={props.result}
      />,
      { wrapper },
    )

  beforeEach(() => {
    queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    interactionDraftStore.resetForTesting()
    vi.restoreAllMocks()
  })

  it('单选选项是单选组里唯一 Tab 停靠点，方向键移动焦点，空格与回车完成选择', async () => {
    const user = userEvent.setup()
    renderCard({ argumentsJson: SINGLE_ARGUMENTS })

    // 题目文本是单选组的可访问名称，选项以 radio + aria-checked 暴露状态。
    const group = screen.getByRole('radiogroup', { name: '选择迁移窗口' })
    const radios = screen.getAllByRole('radio')
    expect(radios).toHaveLength(3)
    expect(group).toContainElement(radios[0])
    expect(radios.map((radio) => radio.getAttribute('aria-checked'))).toEqual([
      'false',
      'false',
      'false',
    ])
    // 组内只有一个选项可由 Tab 进入。
    expect(radios.map((radio) => radio.getAttribute('tabindex'))).toEqual([
      '0',
      '-1',
      '-1',
    ])

    await user.tab()
    expect(radios[0]).toHaveFocus()

    // 方向键只在组内移动焦点，不改变选择。
    await user.keyboard('{ArrowDown}')
    expect(radios[1]).toHaveFocus()
    expect(radios[0]).toHaveAttribute('aria-checked', 'false')
    // 停靠点跟随最近聚焦的选项，Tab 离开再回来不会跳回第一项。
    expect(radios.map((radio) => radio.getAttribute('tabindex'))).toEqual([
      '-1',
      '0',
      '-1',
    ])

    await user.keyboard('[Space]')
    expect(radios[1]).toHaveAttribute('aria-checked', 'true')
    expect(radios[0]).toHaveAttribute('aria-checked', 'false')

    await user.keyboard('{ArrowDown}')
    expect(radios[2]).toHaveFocus()
    await user.keyboard('{Enter}')
    expect(radios[2]).toHaveAttribute('aria-checked', 'true')
    expect(radios[1]).toHaveAttribute('aria-checked', 'false')

    // 焦点在第 3 项时继续向下环绕回第 1 项。
    await user.keyboard('{ArrowDown}')
    expect(radios[0]).toHaveFocus()
    expect(radios[0]).toHaveAttribute('aria-checked', 'false')

    // Home/End 直接到组内首尾，选择不变。
    await user.keyboard('{End}')
    expect(radios[2]).toHaveFocus()
    await user.keyboard('{Home}')
    expect(radios[0]).toHaveFocus()
    expect(radios[2]).toHaveAttribute('aria-checked', 'true')
  })

  it('多选选项保持复选框语义，每一项独立切换且都可 Tab 进入', () => {
    renderCard({ argumentsJson: MULTI_ARGUMENTS })

    expect(screen.queryByRole('radiogroup')).toBeNull()
    expect(screen.getByRole('group', { name: '选择需要归档的产物' })).toBeInTheDocument()
    const checkboxes = screen.getAllByRole('checkbox')
    expect(checkboxes).toHaveLength(3)
    expect(checkboxes.map((checkbox) => checkbox.getAttribute('tabindex'))).toEqual([
      null,
      null,
      null,
    ])

    fireEvent.click(checkboxes[0])
    fireEvent.click(checkboxes[2])
    expect(checkboxes.map((checkbox) => checkbox.getAttribute('aria-checked'))).toEqual([
      'true',
      'false',
      'true',
    ])

    fireEvent.click(checkboxes[0])
    expect(checkboxes[0]).toHaveAttribute('aria-checked', 'false')
    expect(checkboxes[2]).toHaveAttribute('aria-checked', 'true')
  })

  it('单选的自定义输入仍清空预置选项，只提交自定义文本', () => {
    renderCard({ argumentsJson: SINGLE_ARGUMENTS })

    const firstRadio = screen.getAllByRole('radio')[0]
    fireEvent.click(firstRadio)
    expect(firstRadio).toHaveAttribute('aria-checked', 'true')

    fireEvent.change(screen.getByPlaceholderText('输入自定义回答'), {
      target: { value: '改为下周三' },
    })
    expect(firstRadio).toHaveAttribute('aria-checked', 'false')
    expect(screen.getByRole('button', { name: '提交回答' })).toBeEnabled()
  })

  it('提交锁定期间保留选中项并禁止继续操作，读屏仍可获知选中状态', async () => {
    // 提交中的冻结状态：请求不回包，卡片保持只读锁。
    vi.spyOn(interactionService, 'submitToolInput').mockImplementation(
      () => new Promise(() => {}),
    )
    renderCard({ argumentsJson: SINGLE_ARGUMENTS })

    fireEvent.click(screen.getAllByRole('radio')[1])
    fireEvent.click(screen.getByRole('button', { name: '提交回答' }))

    const radios = screen.getAllByRole('radio')
    for (const radio of radios) {
      expect(radio).toBeDisabled()
    }
    expect(radios[1]).toHaveAttribute('aria-checked', 'true')
    expect(radios[1]).toHaveClass('is-selected')
  })

  it('已回答的只读卡片按题展示所选项且不再渲染任何选择控件', () => {
    renderCard({
      argumentsJson: SINGLE_ARGUMENTS,
      result: { declined: false, answers: [['本周日凌晨']] },
    })

    expect(screen.getByText('本周日凌晨')).toBeInTheDocument()
    expect(screen.queryAllByRole('radio')).toHaveLength(0)
    expect(screen.queryAllByRole('checkbox')).toHaveLength(0)
    expect(screen.queryByPlaceholderText('输入自定义回答')).toBeNull()
    expect(screen.queryByRole('button', { name: '提交回答' })).toBeNull()
  })
})
