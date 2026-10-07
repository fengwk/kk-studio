import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Bot } from 'lucide-react'
import { describe, expect, it, vi } from 'vitest'
import { ResourceCard } from '@/shared/ui/cards/ResourceCard'
import { ResourceGrid } from '@/shared/ui/cards/ResourceGrid'
import { Button } from '@/shared/ui/controls/Button'

describe('ResourceCard', () => {
  it('renders icon, title and subtitle, keeping the full name for truncated titles', () => {
    const longTitle = 'a-very-long-resource-name-that-would-be-truncated-in-a-narrow-card'
    render(
      <ResourceCard icon={<Bot data-testid="card-icon" />} title={longTitle} subtitle="short description" />,
    )

    const heading = screen.getByRole('heading', { level: 3 })
    expect(heading).toHaveTextContent(longTitle)
    // 长标题可截断，但完整名称必须仍可通过 title 取得。
    expect(heading).toHaveAttribute('title', longTitle)
    expect(screen.getByText('short description')).toHaveAttribute('title', 'short description')
    expect(screen.getByTestId('card-icon')).toBeInTheDocument()
  })

  it('renders metadata rows independently of the action slot', () => {
    const { rerender } = render(
      <ResourceCard
        icon={<Bot />}
        title="resource"
        meta={[
          ['Kind', 'agent'],
          { label: 'Tools', tags: ['read', 'bash', 'grep', 'write'], limit: 2 },
          { pairs: [{ label: 'Timeout', value: '180s' }, { label: 'Idle', value: '1500ms' }] },
        ]}
      />,
    )

    // 只有元信息时不得凭空产生动作按钮。
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(screen.getByText('Kind')).toBeInTheDocument()
    expect(screen.getByText('agent')).toBeInTheDocument()
    expect(screen.getByText('Tools')).toBeInTheDocument()
    expect(screen.getByText('read')).toBeInTheDocument()
    expect(screen.getByText('bash')).toBeInTheDocument()
    // 超出 limit 的标签折叠为 +N，并保留完整清单的提示。
    expect(screen.queryByText('grep')).not.toBeInTheDocument()
    expect(screen.getByText('+2')).toBeInTheDocument()
    expect(screen.getByText('read').closest('.resource-card-tags')).toHaveAttribute(
      'title',
      'read, bash, grep, write',
    )
    expect(screen.getByText('180s')).toBeInTheDocument()

    // 加上动作后元信息保持原样（两者互相独立）。
    rerender(
      <ResourceCard
        icon={<Bot />}
        title="resource"
        meta={[['Kind', 'agent']]}
        actions={<Button size="compact">编辑</Button>}
      />,
    )
    expect(screen.getByRole('button', { name: '编辑' })).toBeInTheDocument()
    expect(screen.getByText('Kind')).toBeInTheDocument()
    expect(screen.getByText('agent')).toBeInTheDocument()
  })

  it('keeps empty rows so structure is stable, without inventing placeholder values', () => {
    render(
      <ResourceCard
        icon={<Bot />}
        title="resource"
        meta={[
          ['Unknown', ''],
          { label: 'Tags', tags: [] },
        ]}
      />,
    )

    const unknownLabel = screen.getByText('Unknown')
    expect(unknownLabel.nextElementSibling).toHaveTextContent('')
    expect(screen.getByText('Tags')).toBeInTheDocument()
    // 空标签行不产生任何 chip，也不显示伪占位符。
    expect(document.querySelectorAll('.resource-card-tag')).toHaveLength(0)
    expect(screen.queryByText('—')).not.toBeInTheDocument()
  })

  it('renders boolean and ReactNode facts without disguising them as empty', () => {
    render(
      <ResourceCard
        icon={<Bot />}
        title="resource"
        meta={[
          ['已启用', false],
          ['数量', 0],
          { label: '状态', value: <span aria-label="缺少 Agent">missing</span> },
        ]}
      />,
    )

    // false / 0 是真实事实：必须显式渲染，且不能标记为空值占位。
    const disabledFact = screen.getByText('false')
    expect(disabledFact).not.toHaveClass('is-empty')
    expect(screen.getByText('0')).toBeInTheDocument()

    // ReactNode 值保留自身无障碍语义，不再叠加 title 提示。
    const node = screen.getByLabelText('缺少 Agent')
    expect(node).toBeInTheDocument()
    expect(node).not.toHaveAttribute('title')
  })

  it('keeps action semantics: disabled actions do not fire, danger/compact actions stay reachable', async () => {
    const user = userEvent.setup()
    const onDelete = vi.fn()
    const onEdit = vi.fn()
    render(
      <ResourceCard
        icon={<Bot />}
        title="resource"
        actions={
          <>
            <Button size="compact" onClick={onEdit}>
              编辑
            </Button>
            <Button variant="ghost" size="compact" danger disabled onClick={onDelete}>
              删除
            </Button>
          </>
        }
      />,
    )

    await user.click(screen.getByRole('button', { name: '编辑' }))
    expect(onEdit).toHaveBeenCalledOnce()

    const deleteButton = screen.getByRole('button', { name: '删除' })
    expect(deleteButton).toBeDisabled()
    await user.click(deleteButton)
    expect(onDelete).not.toHaveBeenCalled()
  })

  it('places business content between the head and the metadata', () => {
    render(
      <ResourceCard icon={<Bot />} title="resource" meta={[['Kind', 'agent']]}>
        <p>业务内容</p>
      </ResourceCard>,
    )

    const card = document.querySelector('.resource-card') as HTMLElement
    const order = Array.from(card.children).map((child) => child.className)
    expect(order).toEqual([
      'resource-card-head',
      'resource-card-content',
      'resource-card-meta',
    ])
    expect(screen.getByText('业务内容')).toBeInTheDocument()
  })
})

describe('ResourceGrid', () => {
  it('lays out create and resource entries in one shared grid', () => {
    render(
      <ResourceGrid>
        <span>first</span>
        <span>second</span>
      </ResourceGrid>,
    )

    const grid = document.querySelector('.resource-grid') as HTMLElement
    expect(grid).toBeInTheDocument()
    expect(Array.from(grid.children).map((child) => child.textContent)).toEqual([
      'first',
      'second',
    ])
  })
})
