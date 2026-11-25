import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { ResourceEditorModal } from '@/features/catalog/AiConsoleResourceEditorModal'
import { emptyAgentDraft } from '@/features/catalog/ai-agent-draft-codec'
import { emptyModelGroupDraft } from '@/features/catalog/ai-model-group-draft-codec'
import { emptyModelDraft } from '@/features/catalog/ai-model-draft-codec'
import { emptyProviderDraft } from '@/features/catalog/ai-provider-draft-codec'
import { setLocale } from '@/shared/i18n'

type ModelKind = 'MODEL' | 'GROUP'

function baseProps() {
  return {
    providers: [],
    models: [],
    providerDraft: emptyProviderDraft(),
    modelDraft: emptyModelDraft(),
    modelGroupDraft: emptyModelGroupDraft(),
    agentDraft: emptyAgentDraft(),
    pending: false,
    onClose: vi.fn(),
    onProviderDraftChange: vi.fn(),
    onModelDraftChange: vi.fn(),
    onModelGroupDraftChange: vi.fn(),
    onModelCreateKindChange: vi.fn(),
    onAgentDraftChange: vi.fn(),
    onSubmit: (event: React.FormEvent<HTMLFormElement>) => event.preventDefault(),
  }
}

/** 宿主持有资源类型，与页面一致：切换后选中态、焦点与面板必须同步。 */
function CreateModelHarness({ onKindChange }: { onKindChange?: (kind: ModelKind) => void }) {
  const [kind, setKind] = useState<ModelKind>('MODEL')
  return (
    <ResourceEditorModal
      {...baseProps()}
      modal={{ kind: 'model', mode: 'create', resourceKind: kind }}
      onModelCreateKindChange={(next) => {
        onKindChange?.(next)
        setKind(next)
      }}
    />
  )
}

describe('model create kind tabs', () => {
  it('shows tabs only in create mode and keeps edit pinned to the existing kind', () => {
    setLocale('zh-CN')
    const view = render(
      <ResourceEditorModal
        {...baseProps()}
        modal={{ kind: 'model', mode: 'create', resourceKind: 'MODEL' }}
      />,
    )
    expect(screen.getByRole('tablist', { name: '资源类型' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: '模型' })).toHaveAttribute('aria-selected', 'true')

    view.rerender(
      <ResourceEditorModal
        {...baseProps()}
        modal={{ kind: 'model', mode: 'edit', resourceKind: 'GROUP', name: 'group-a', expectedVersion: '1' }}
      />,
    )
    expect(screen.queryByRole('tablist')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '编辑模型组：group-a' })).toBeInTheDocument()
  })

  // 测试意图：类型切换必须是与选中页签互指的 tablist/tab/tabpanel，且只有一个 Tab 停靠点。
  it('wires the tablist to a single tabpanel with a roving tabindex', () => {
    setLocale('zh-CN')
    render(<CreateModelHarness />)

    const modelTab = screen.getByRole('tab', { name: '模型' })
    const groupTab = screen.getByRole('tab', { name: '模型组' })
    const panel = screen.getByRole('tabpanel')

    expect(modelTab).toHaveAttribute('aria-selected', 'true')
    expect(modelTab).toHaveAttribute('tabindex', '0')
    expect(groupTab).toHaveAttribute('aria-selected', 'false')
    expect(groupTab).toHaveAttribute('tabindex', '-1')
    expect(modelTab).toHaveAttribute('aria-controls', panel.id)
    expect(panel).toHaveAttribute('aria-labelledby', modelTab.id)
    // 表单正文在面板内，切换后的内容才属于当前页签。
    expect(within(panel).getByRole('region', { name: '基本信息' })).toBeInTheDocument()
  })

  // 测试意图：点击与方向键/Home 都切换资源类型（选中态与焦点一起移动），不再需要逐个 Tab。
  it('switches kind by click and arrow keys, moving focus and the panel with it', async () => {
    setLocale('zh-CN')
    const user = userEvent.setup()
    const onKindChange = vi.fn()
    render(<CreateModelHarness onKindChange={onKindChange} />)

    const modelTab = screen.getByRole('tab', { name: '模型' })
    const groupTab = screen.getByRole('tab', { name: '模型组' })

    await user.click(groupTab)
    expect(onKindChange).toHaveBeenLastCalledWith('GROUP')
    expect(groupTab).toHaveAttribute('aria-selected', 'true')
    const groupPanel = screen.getByRole('tabpanel')
    expect(groupPanel).toHaveAttribute('aria-labelledby', groupTab.id)
    // 模型组表单不分区块：面板内容确实随类型切换。
    expect(within(groupPanel).queryByRole('navigation', { name: '区块导航' })).not.toBeInTheDocument()

    await user.keyboard('{ArrowLeft}')
    expect(onKindChange).toHaveBeenLastCalledWith('MODEL')
    expect(modelTab).toHaveFocus()
    expect(modelTab).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tabpanel')).toHaveAttribute('aria-labelledby', modelTab.id)

    await user.keyboard('{ArrowRight}')
    expect(groupTab).toHaveFocus()
    expect(groupTab).toHaveAttribute('aria-selected', 'true')

    await user.keyboard('{Home}')
    expect(modelTab).toHaveFocus()
    expect(modelTab).toHaveAttribute('aria-selected', 'true')
  })
})
