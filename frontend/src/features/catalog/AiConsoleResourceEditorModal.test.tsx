import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ResourceEditorModal } from '@/features/catalog/AiConsoleResourceEditorModal'
import { emptyAgentDraft } from '@/features/catalog/ai-agent-draft-codec'
import { emptyModelGroupDraft } from '@/features/catalog/ai-model-group-draft-codec'
import { emptyModelDraft } from '@/features/catalog/ai-model-draft-codec'
import { emptyProviderDraft } from '@/features/catalog/ai-provider-draft-codec'
import { setLocale } from '@/shared/i18n'

describe('model create kind tabs', () => {
  it('shows tabs only in create mode and keeps edit pinned to the existing kind', () => {
    setLocale('zh-CN')
    const baseProps = {
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
    const view = render(
      <ResourceEditorModal
        {...baseProps}
        modal={{ kind: 'model', mode: 'create', resourceKind: 'MODEL' }}
      />,
    )
    expect(screen.getByRole('tablist', { name: '资源类型' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Model' })).toHaveAttribute('aria-selected', 'true')

    view.rerender(
      <ResourceEditorModal
        {...baseProps}
        modal={{ kind: 'model', mode: 'edit', resourceKind: 'GROUP', name: 'group-a', expectedVersion: '1' }}
      />,
    )
    expect(screen.queryByRole('tablist')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '编辑 模型组' })).toBeInTheDocument()
  })
})
