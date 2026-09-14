import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { McpConfigJsonEditor } from './McpConfigJsonEditor'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'
import { REMOTE_CONFIG_TEMPLATE } from './mcp-config-json'

const mockEnvironment: EnvironmentCardDTO = {
  id: '00000000-0000-4000-8000-000000000001',
  name: 'prod-linux-node',
  status: 'READY',
  ready: true,
  lastSeen: '2026-07-20T00:00:00.000Z',
  capabilities: [],
  rootPath: '/opt/studio',
  version: '1',
  createTime: '2026-07-20T00:00:00.000Z',
  updateTime: '2026-07-20T00:00:00.000Z',
}

describe('McpConfigJsonEditor', () => {
  /**
   * 测试意图：验证应用 Remote 模板与 Local 模板时正确触发 onChange 回调。
   */
  it('applies remote and local templates via toolbar buttons', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    const { rerender } = render(
      <McpConfigJsonEditor
        value="{}"
        onChange={onChange}
        environments={[mockEnvironment]}
      />,
    )

    await user.click(screen.getByRole('button', { name: 'Remote 模板' }))
    expect(onChange).toHaveBeenCalledWith(REMOTE_CONFIG_TEMPLATE)

    rerender(
      <McpConfigJsonEditor
        value="{}"
        onChange={onChange}
        environments={[mockEnvironment]}
      />,
    )
    await user.click(screen.getByRole('button', { name: 'Local 模板' }))
    expect(onChange).toHaveBeenCalledWith(expect.stringContaining(mockEnvironment.id))
  })

  /**
   * 测试意图：验证格式化与校验功能以及对重复键的即时报错。
   */
  it('formats valid JSON and rejects duplicate keys during format and validate', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()

    // 格式化合法 JSON
    const unformatted = '{"type":"remote","url":"https://example.com"}'
    const { rerender } = render(
      <McpConfigJsonEditor
        value={unformatted}
        onChange={onChange}
        environments={[]}
      />,
    )

    await user.click(screen.getByRole('button', { name: '格式化' }))
    expect(onChange).toHaveBeenCalledWith(JSON.stringify(JSON.parse(unformatted), null, 2))

    // 校验合法配置
    await user.click(screen.getByRole('button', { name: '校验' }))
    expect(screen.getByText('配置格式校验通过')).toBeInTheDocument()

    // 含有重复键时的格式化与校验报错
    const duplicate = '{"type":"remote","url":"https://example.com","type":"local"}'
    rerender(
      <McpConfigJsonEditor
        value={duplicate}
        onChange={onChange}
        environments={[]}
      />,
    )

    await user.click(screen.getByRole('button', { name: '格式化' }))
    expect(screen.getByRole('alert')).toHaveTextContent(/duplicate.*key/i)

    await user.click(screen.getByRole('button', { name: '校验' }))
    expect(screen.getByRole('alert')).toHaveTextContent(/duplicate.*key/i)
  })

  /**
   * 测试意图：验证 Local 模式下切换环境下拉选单时回写 JSON 中的 environmentId。
   */
  it('updates environmentId in JSON text when selecting an environment', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    const secondEnv: EnvironmentCardDTO = {
      ...mockEnvironment,
      id: '00000000-0000-4000-8000-000000000002',
      name: 'second-env',
    }

    const localJson = JSON.stringify(
      {
        type: 'local',
        environmentId: mockEnvironment.id,
        command: ['echo'],
      },
      null,
      2,
    )

    render(
      <McpConfigJsonEditor
        value={localJson}
        onChange={onChange}
        environments={[mockEnvironment, secondEnv]}
      />,
    )

    const select = screen.getByRole('combobox', { name: '关联环境' })
    expect(select).toHaveValue(mockEnvironment.id)

    await user.selectOptions(select, secondEnv.id)
    expect(onChange).toHaveBeenCalledWith(expect.stringContaining(secondEnv.id))
  })

  /**
   * 测试意图：验证 disabled 状态下所有交互元素（按钮、文本域、下拉选择器）均处于禁用态。
   */
  it('disables all mutating controls when disabled prop is true', () => {
    const localJson = JSON.stringify({ type: 'local', environmentId: mockEnvironment.id }, null, 2)
    render(
      <McpConfigJsonEditor
        value={localJson}
        onChange={vi.fn()}
        environments={[mockEnvironment]}
        disabled={true}
        error="Sample error"
      />,
    )

    expect(screen.getByRole('button', { name: 'Remote 模板' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Local 模板' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '格式化' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '校验' })).toBeDisabled()
    expect(screen.getByRole('combobox', { name: '关联环境' })).toBeDisabled()
    expect(screen.getByRole('textbox', { name: '配置 JSON' })).toBeDisabled()
    expect(screen.getByRole('alert')).toHaveTextContent('Sample error')
  })

  /**
   * 测试意图：验证 MCP JSON 工具栏所有操作按钮应用一致的次级强调样式 (.btn-secondary.btn-sm)。
   */
  it('renders all toolbar buttons with consistent btn-secondary btn-sm style', () => {
    render(
      <McpConfigJsonEditor
        value="{}"
        onChange={vi.fn()}
        environments={[mockEnvironment]}
      />,
    )

    const buttons = [
      screen.getByRole('button', { name: 'Remote 模板' }),
      screen.getByRole('button', { name: 'Local 模板' }),
      screen.getByRole('button', { name: '格式化' }),
      screen.getByRole('button', { name: '校验' }),
    ]

    for (const btn of buttons) {
      expect(btn).toHaveClass('btn-secondary')
      expect(btn).toHaveClass('btn-sm')
    }
  })
})
