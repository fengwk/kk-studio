import { act, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import type { AgentDraft, ModelDraft, ProviderDraft } from '@/features/ai/catalog/ai-console-types'
import { emptyModelDraft } from '@/features/ai/catalog/ai-model-draft-codec'
import { validateResourceDraft } from '@/features/ai/catalog/ai-resource-form-validation'
import { toEditableProvider, toEditableProviderUpdate } from '@/features/ai/catalog/ai-provider-draft-codec'
import { AgentForm, ModelForm, ProviderForm } from '@/features/ai/catalog/AiResourceForms'
import { chooseSelectOption } from '@/test-support/chooseSelectOption'
import { setLocale } from '@/shared/i18n'

async function selectFormOption(
  user: ReturnType<typeof userEvent.setup>,
  label: string,
  option: string,
) {
  await chooseSelectOption(user, label, option)
}

describe('AiResourceForms', () => {
  it('edits provider fields with structured inputs', async () => {
    const user = userEvent.setup()
    render(<ProviderFormHarness />)

    await user.type(screen.getByPlaceholderText('minimax'), 'provider-a')
    await user.type(screen.getByPlaceholderText('用途说明'), 'provider desc')
    await selectFormOption(user, 'Provider Type', 'anthropic')
    await user.type(
      screen.getByPlaceholderText('https://api.example.com/v1'),
      'https://proxy.example/v1',
    )
    await user.type(screen.getByPlaceholderText('可留空'), 'secret')
    const totalTimeoutInput = screen.getByPlaceholderText('1800000')
    await user.clear(totalTimeoutInput)
    await user.type(totalTimeoutInput, '240000')
    const idleTimeoutInput = screen.getByPlaceholderText('120000')
    await user.clear(idleTimeoutInput)
    await user.type(idleTimeoutInput, '3000')

    expect(screen.getByDisplayValue('provider-a')).toBeInTheDocument()
    expect(screen.getByDisplayValue('provider desc')).toBeInTheDocument()
    expect(screen.getByLabelText('Provider Type')).toHaveAttribute('data-value', 'anthropic')
    expect(screen.getByDisplayValue('https://proxy.example/v1')).toBeInTheDocument()
    expect(screen.getByDisplayValue('secret')).toBeInTheDocument()
    expect(screen.getByDisplayValue('secret')).toHaveAttribute('type', 'password')
    expect(screen.getByDisplayValue('secret')).toHaveAttribute('autocomplete', 'off')
    expect(screen.getByDisplayValue('240000')).toBeInTheDocument()
    expect(screen.getByDisplayValue('3000')).toBeInTheDocument()
    expect(screen.getByText('留空会以无 Authorization 方式请求 OpenAI-compatible 端点。')).toBeInTheDocument()
  })

  it('manages HTTP retry policy: inherit, custom, empty list, staged invalid withoutEnter, correct, duplicate, submit payload', async () => {
    const user = userEvent.setup()
    let currentDraft: ProviderDraft = {
      name: 'provider-test',
      description: '',
      providerType: 'openai',
      baseUrl: 'https://api.test/v1',
      credential: '',
      modelCallTimeoutMillis: '1800000',
      modelCallIdleTimeoutMillis: '120000',
      modelHttpRetryStatusCodes: null,
    }

    function ProviderLifecycleHarness() {
      const [draft, setDraft] = useState<ProviderDraft>(currentDraft)
      return (
        <ProviderForm
          draft={draft}
          mode="create"
          onChange={(next) => {
            currentDraft = next
            setDraft(next)
          }}
        />
      )
    }

    function validateCurrent() {
      return validateResourceDraft(
        { kind: 'provider', mode: 'create' },
        {
          providerDraft: currentDraft,
          modelDraft: emptyModelDraft(),
          agentDraft: {
            name: '',
            description: '',
            systemPrompt: '',
            model: '',
            variant: 'default',
            inheritParentEnvironment: true,
            tools: [],
            skills: [],
            subagents: [],
          },
        },
      )
    }

    render(<ProviderLifecycleHarness />)

    // 1. Initially "继承系统配置" is checked; modelHttpRetryStatusCodes is null
    expect(screen.getByLabelText('继承系统配置')).toBeChecked()
    expect(screen.getByLabelText('自定义重试名单')).not.toBeChecked()
    expect(currentDraft.modelHttpRetryStatusCodes).toBeNull()
    expect(validateCurrent().ok).toBe(true)
    expect(toEditableProvider(currentDraft).modelHttpRetryStatusCodes).toBeNull()
    expect(toEditableProviderUpdate(currentDraft).modelHttpRetryStatusCodes).toBeNull()

    // 2. Toggle to "自定义重试名单" -> draft becomes []
    await user.click(screen.getByLabelText('自定义重试名单'))
    expect(screen.getByLabelText('自定义重试名单')).toBeChecked()
    expect(currentDraft.modelHttpRetryStatusCodes).toEqual([])
    expect(validateCurrent().ok).toBe(true)
    expect(toEditableProvider(currentDraft).modelHttpRetryStatusCodes).toEqual([])
    expect(toEditableProviderUpdate(currentDraft).modelHttpRetryStatusCodes).toEqual([])

    // 3. Add valid custom status codes via TagInput
    const tagInput = screen.getByRole('textbox', { name: 'HTTP 错误重试策略' })
    await user.type(tagInput, '408{enter}')
    expect(screen.getByText('408')).toBeInTheDocument()
    expect(currentDraft.modelHttpRetryStatusCodes).toEqual([408])
    expect(validateCurrent().ok).toBe(true)
    expect(toEditableProvider(currentDraft).modelHttpRetryStatusCodes).toEqual([408])

    await user.type(tagInput, '429{enter}')
    expect(screen.getByText('429')).toBeInTheDocument()
    expect(currentDraft.modelHttpRetryStatusCodes).toEqual([408, 429])
    expect(validateCurrent().ok).toBe(true)
    expect(toEditableProvider(currentDraft).modelHttpRetryStatusCodes).toEqual([408, 429])

    // 4. Staged invalid input without Enter immediately blocks save
    await user.type(tagInput, '200') // invalid out-of-range, NO enter
    expect(currentDraft.modelHttpRetryStatusCodes).toEqual([408, 429, '200'])
    expect(validateCurrent().ok).toBe(false)
    expect(() => toEditableProvider(currentDraft)).toThrow()

    // 5. Correct the input to 500 and press Enter
    await user.clear(tagInput)
    await user.type(tagInput, '500{enter}')
    expect(screen.getByText('500')).toBeInTheDocument()
    expect(currentDraft.modelHttpRetryStatusCodes).toEqual([408, 429, 500])
    expect(validateCurrent().ok).toBe(true)
    expect(toEditableProvider(currentDraft).modelHttpRetryStatusCodes).toEqual([408, 429, 500])

    // 6. Staged duplicate without Enter blocks save
    await user.type(tagInput, '429') // duplicate with existing chip 429, NO enter
    expect(currentDraft.modelHttpRetryStatusCodes).toEqual([408, 429, 500, '429'])
    expect(validateCurrent().ok).toBe(false)
    expect(() => toEditableProvider(currentDraft)).toThrow()

    // Clear the duplicate input
    await user.clear(tagInput)
    expect(currentDraft.modelHttpRetryStatusCodes).toEqual([408, 429, 500])
    expect(validateCurrent().ok).toBe(true)

    // 7. Toggle back to "继承系统配置" -> draft becomes null
    await user.click(screen.getByLabelText('继承系统配置'))
    expect(screen.getByLabelText('继承系统配置')).toBeChecked()
    expect(currentDraft.modelHttpRetryStatusCodes).toBeNull()
    expect(validateCurrent().ok).toBe(true)
    expect(toEditableProvider(currentDraft).modelHttpRetryStatusCodes).toBeNull()
    expect(toEditableProviderUpdate(currentDraft).modelHttpRetryStatusCodes).toBeNull()
  })

  it('explains that an empty edit credential preserves the existing secret without echoing it', () => {
    const { container } = render(<ProviderFormHarness mode="edit" />)

    expect(screen.getByText('留空会保留已配置的 API Key；密钥不会回显。')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('留空保留当前密钥')).toHaveValue('')
    expect(screen.getByPlaceholderText('minimax')).toHaveAttribute('readonly')
    const labels = Array.from(container.querySelectorAll('label > span')).map((label) => label.textContent)
    expect(labels[0]).toMatch(/^Name(?: \*)?$/)
    expect(labels[1]).toBe('API Key（可选）')
  })

  it('edits the new model abilities, pricing, variants, and wire modelId', async () => {
    const user = userEvent.setup()
    render(<ModelFormHarness />)

    expect(screen.getByRole('heading', { name: 'Limit' })).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'Name', exact: true })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '功能' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Pricing' })).toBeInTheDocument()
    expect(screen.getByLabelText('Tools')).toBeChecked()
    expect(screen.getByLabelText('Reasoning')).toBeChecked()
    expect(screen.getByLabelText('TEXT')).toBeChecked()
    expect(screen.queryByText('Capabilities')).not.toBeInTheDocument()
    expect(screen.getByText(/币种固定 USD/)).toBeInTheDocument()
    expect(screen.getByLabelText('Reasoning Effort 1')).toBeInTheDocument()
    expect(screen.getByText('思考强度')).toBeInTheDocument()
    // 空值即协议默认：思考强度不强必填，默认空
    expect(screen.getByLabelText('Reasoning Effort 1')).toHaveValue('')
    expect(screen.getByLabelText('Reasoning Effort 1')).not.toBeRequired()

    await user.type(screen.getByLabelText('Model ID'), 'upstream-claude-3')
    expect(screen.getByDisplayValue('upstream-claude-3')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '添加 Variant' }))
    const secondNameInput = screen.getByLabelText('Variant ID 2')
    expect(secondNameInput).toHaveValue('variant-2')
    await user.clear(secondNameInput)
    await user.type(secondNameInput, 'creative')
    const secondEffortInput = screen.getByLabelText('Reasoning Effort 2')
    expect(secondEffortInput).toHaveAttribute('maxLength', '64')
    await user.type(secondEffortInput, 'max')
    expect(secondEffortInput).toHaveValue('max')
    expect(screen.getByDisplayValue('creative')).toBeInTheDocument()

    await selectFormOption(user, 'Default Variant', 'creative')
    expect(screen.getByLabelText('Default Variant')).toHaveAttribute('data-value', 'creative')
  })

  it.each(['deleted-provider', 'off-page-provider'])(
    'shows an edit model provider identity as unavailable for %s',
    (providerName) => {
      const draft: ModelDraft = {
        ...emptyModelDraft({ name: providerName }),
        providerName,
        name: 'orphaned-model',
      }
      render(
        <ModelForm
          draft={draft}
          mode="edit"
          providers={[
            {
              name: 'loaded-provider',
              description: null,
              providerType: 'openai',
              baseUrl: null,
              configured: true,
              modelCallTimeoutMillis: 1800000,
              modelCallIdleTimeoutMillis: 120000,
              version: '1',
              createTime: null,
              updateTime: null,
              modelHttpRetryStatusCodes: null,
            },
          ]}
          onChange={() => undefined}
        />,
      )

      const providerSelect = screen.getByLabelText('Provider')
      expect(providerSelect).toHaveAttribute('data-value', providerName)
      expect(providerSelect).toBeDisabled()
      expect(providerSelect).toHaveAttribute('aria-describedby', 'model-provider-identity-status')
      expect(providerSelect).toHaveTextContent(`${providerName} (不可用)`)
      expect(screen.getByRole('status')).toHaveTextContent('不可用；保存其他字段时仍保留原始身份。')
    },
  )

  it('keeps default variant aligned and disables reasoning effort when reasoning is disabled', async () => {
    const user = userEvent.setup()
    render(<ModelFormHarness />)

    await user.click(screen.getByRole('button', { name: '添加 Variant' }))
    const secondNameInput = screen.getByLabelText('Variant ID 2')
    await user.clear(secondNameInput)
    await user.type(secondNameInput, 'creative')
    await selectFormOption(user, 'Default Variant', 'creative')

    fireEvent.change(secondNameInput, { target: { value: 'creative-2' } })
    expect(screen.getByLabelText('Default Variant')).toHaveAttribute('data-value', 'creative-2')

    await user.click(screen.getAllByRole('button', { name: '删除 Variant' })[1])
    expect(screen.getByLabelText('Default Variant')).toHaveAttribute('data-value', 'medium')

    await user.click(screen.getByLabelText('Reasoning'))
    expect(screen.getByLabelText('Reasoning Effort 1')).toBeDisabled()
    expect(screen.getByText(/Reasoning 当前已关闭/)).toBeInTheDocument()
    expect(screen.getByText(/勾选开启上方 Reasoning/)).toBeInTheDocument()
  })

  it('keeps at least one input modality and toggles IMAGE alongside TEXT', async () => {
    const user = userEvent.setup()
    render(<ModelFormHarness />)

    expect(screen.getByLabelText('IMAGE')).not.toBeChecked()
    await user.click(screen.getByLabelText('IMAGE'))
    expect(screen.getByLabelText('IMAGE')).toBeChecked()
    expect(screen.getByLabelText('TEXT')).toBeChecked()

    // 取消 TEXT 后 IMAGE 仍保留，允许非 TEXT 的模态组合
    await user.click(screen.getByLabelText('TEXT'))
    expect(screen.getByLabelText('TEXT')).not.toBeChecked()
    expect(screen.getByLabelText('IMAGE')).toBeChecked()

    // 全部取消时回退到 TEXT，保证至少保留一种模态
    await user.click(screen.getByLabelText('IMAGE'))
    expect(screen.getByLabelText('TEXT')).toBeChecked()
    expect(screen.getByLabelText('IMAGE')).not.toBeChecked()
  })

  /** 空思考强度表示不覆盖协议默认：开启/重新开启 Reasoning 都不得自动补全。 */
  it('keeps an empty reasoning effort as the protocol default and restores effort inputs on re-enable', async () => {
    const user = userEvent.setup()
    render(<ModelFormHarness />)

    // 新增 variant 的思考强度默认为空（协议默认），且提示文案说明空值语义
    await user.click(screen.getByRole('button', { name: '添加 Variant' }))
    expect(screen.getByLabelText('Reasoning Effort 2')).toHaveValue('')
    expect(screen.getByText(/留空保持 Provider 协议默认/)).toBeInTheDocument()

    await user.click(screen.getByLabelText('Reasoning'))
    expect(screen.getByLabelText('Reasoning Effort 1')).toBeDisabled()
    expect(screen.getByLabelText('Reasoning Effort 2')).toBeDisabled()
    expect(screen.getByText(/Reasoning 当前已关闭/)).toBeInTheDocument()

    // 重新开启后仍然是空值，不得被 variant id / medium 补全
    await user.click(screen.getByLabelText('Reasoning'))
    expect(screen.getByLabelText('Reasoning Effort 1')).toBeEnabled()
    expect(screen.getByLabelText('Reasoning Effort 2')).toBeEnabled()
    expect(screen.getByLabelText('Reasoning Effort 1')).toHaveValue('')
    expect(screen.getByLabelText('Reasoning Effort 2')).toHaveValue('')
  })

  it('allows editing protocolOptions on variants and keeps it available when reasoning is toggled off', async () => {
    const user = userEvent.setup()
    render(<ModelFormHarness />)

    const protocolInput = screen.getByLabelText('Protocol Options 1')
    expect(protocolInput).toBeInTheDocument()
    expect(protocolInput).toHaveValue('')

    fireEvent.change(protocolInput, { target: { value: '{\n  "temperature": 0.5\n}' } })
    expect(protocolInput).toHaveValue('{\n  "temperature": 0.5\n}')

    // 禁用 Reasoning
    await user.click(screen.getByLabelText('Reasoning'))
    expect(screen.getByLabelText('Reasoning Effort 1')).toBeDisabled()

    // protocolOptions 依然可用且保留已编辑的值
    expect(screen.getByLabelText('Protocol Options 1')).toBeInTheDocument()
    expect(screen.getByLabelText('Protocol Options 1')).toHaveValue('{\n  "temperature": 0.5\n}')

    // 可以在 Reasoning 禁用时新增 variant 并编辑其 protocolOptions
    await user.click(screen.getByRole('button', { name: '添加 Variant' }))
    const protocolInput2 = screen.getByLabelText('Protocol Options 2')
    expect(protocolInput2).toBeInTheDocument()
    expect(protocolInput2).toHaveValue('')
    fireEvent.change(protocolInput2, { target: { value: '{\n  "top_p": 0.9\n}' } })
    expect(protocolInput2).toHaveValue('{\n  "top_p": 0.9\n}')
  })

  it('defaults reasoning to false on new model creation and marks new variant effort as disabled', async () => {
    const user = userEvent.setup()
    function DefaultNewModelHarness() {
      const [draft, setDraft] = useState<ModelDraft>(emptyModelDraft({ name: 'minimax' }))
      return (
        <ModelForm
          draft={draft}
          mode="create"
          providers={[
            {
              name: 'minimax',
              description: null,
              providerType: 'openai',
              baseUrl: null,
              configured: true,
              modelCallTimeoutMillis: 1800000,
              modelCallIdleTimeoutMillis: 120000,
              createTime: '2026-06-20T02:00:00',
              updateTime: '2026-06-20T02:00:00',
              modelHttpRetryStatusCodes: null,
            },
          ]}
          onChange={setDraft}
        />
      )
    }
    render(<DefaultNewModelHarness />)

    // 新建时默认 reasoning 为 false
    expect(screen.getByLabelText('Reasoning')).not.toBeChecked()
    const effort1 = screen.getByLabelText('Reasoning Effort 1')
    expect(effort1).toBeInTheDocument()
    expect(effort1).toBeDisabled()

    // 新增 variant 同样始终渲染且为 disabled
    await user.click(screen.getByRole('button', { name: '添加 Variant' }))
    const effort2 = screen.getByLabelText('Reasoning Effort 2')
    expect(effort2).toBeInTheDocument()
    expect(effort2).toBeDisabled()
  })

  it('preserves pre-filled reasoningEffort when toggled off and restores when toggled on', async () => {
    const user = userEvent.setup()
    render(<ModelFormHarness />)

    const effort1 = screen.getByLabelText('Reasoning Effort 1')
    await user.type(effort1, 'high')
    expect(effort1).toHaveValue('high')

    // 关闭 Reasoning：输入框置灰，但草稿值依然保留
    await user.click(screen.getByLabelText('Reasoning'))
    expect(screen.getByLabelText('Reasoning')).not.toBeChecked()
    expect(effort1).toBeDisabled()
    expect(effort1).toHaveValue('high')

    // 重新开启 Reasoning：输入框激活，草稿值完整恢复
    await user.click(screen.getByLabelText('Reasoning'))
    expect(screen.getByLabelText('Reasoning')).toBeChecked()
    expect(effort1).toBeEnabled()
    expect(effort1).toHaveValue('high')
  })

  it('binds aria-describedby to the disabled reason in both zh-CN and en-US', async () => {
    const user = userEvent.setup()

    // 1. 中文测试
    act(() => setLocale('zh-CN'))
    const { unmount } = render(<ModelFormHarness />)
    const reasoningCheckbox = screen.getByLabelText('Reasoning')
    await user.click(reasoningCheckbox) // 关闭 Reasoning

    const zhEffort = screen.getByLabelText('Reasoning Effort 1')
    expect(zhEffort).toBeDisabled()
    const hintId = zhEffort.getAttribute('aria-describedby')
    expect(hintId).toBeTruthy()
    const hintElement = document.getElementById(hintId!)
    expect(hintElement).not.toBeNull()
    expect(hintElement?.textContent).toContain('Reasoning 当前已关闭')
    expect(hintElement?.textContent).toContain('勾选开启上方 Reasoning')

    // 重新开启后，不再设置 aria-describedby 到关闭提示
    await user.click(reasoningCheckbox)
    expect(zhEffort).toBeEnabled()
    expect(zhEffort).not.toHaveAttribute('aria-describedby')
    unmount()

    // 2. 英文测试
    act(() => setLocale('en-US'))
    render(<ModelFormHarness />)
    const enReasoningCheckbox = screen.getByLabelText('Reasoning')
    await user.click(enReasoningCheckbox) // 关闭 Reasoning

    const enEffort = screen.getByLabelText('Reasoning Effort 1')
    expect(enEffort).toBeDisabled()
    const enHintId = enEffort.getAttribute('aria-describedby')
    expect(enHintId).toBeTruthy()
    const enHintElement = document.getElementById(enHintId!)
    expect(enHintElement).not.toBeNull()
    expect(enHintElement?.textContent).toContain('Reasoning is disabled')
    expect(enHintElement?.textContent).toContain('check Reasoning above to enable')

    act(() => setLocale('zh-CN'))
  })

  /** 同屏表单必须分别关联自己的提示，不能复用固定 DOM id。 */
  it('uses separate disabled-reason hint ids when two model forms are mounted', async () => {
    const user = userEvent.setup()
    render(
      <>
        <ModelFormHarness />
        <ModelFormHarness />
      </>,
    )

    for (const checkbox of screen.getAllByLabelText('Reasoning')) {
      await user.click(checkbox)
    }
    const efforts = screen.getAllByLabelText('Reasoning Effort 1')
    const hintIds = efforts.map((effort) => effort.getAttribute('aria-describedby'))
    expect(new Set(hintIds).size).toBe(2)
    for (const effort of efforts) {
      expect(effort).toBeDisabled()
      const hintId = effort.getAttribute('aria-describedby')
      expect(hintId).toBeTruthy()
      expect(document.getElementById(hintId!)?.textContent).toContain('Reasoning 当前已关闭')
    }
  })

  it('sanitizes integer limits and decimal pricing while editing', async () => {
    const user = userEvent.setup()
    render(<ModelFormHarness />)

    const contextInput = screen.getByPlaceholderText('128000')
    await user.clear(contextInput)
    await user.type(contextInput, '12a3')
    expect(contextInput).toHaveValue('123')

    const maxOutputInput = screen.getByPlaceholderText('8192')
    await user.clear(maxOutputInput)
    await user.type(maxOutputInput, '7.5')
    expect(maxOutputInput).toHaveValue('75')

    const inputPrice = screen.getByLabelText('Input USD per million tokens')
    await user.clear(inputPrice)
    await user.type(inputPrice, '1.2.3')
    expect(inputPrice).toHaveValue(1.23)

    const outputPrice = screen.getByLabelText('Output USD per million tokens')
    await user.clear(outputPrice)
    await user.type(outputPrice, '4.5.6')
    expect(outputPrice).toHaveValue(4.56)
  })

  it('avoids duplicate generated variant ids and keeps the final variant undeletable', async () => {
    const user = userEvent.setup()
    render(<ModelFormHarness />)
    fireEvent.change(screen.getByLabelText('Variant ID 1'), { target: { value: 'variant-2' } })
    expect(screen.getByRole('button', { name: '删除 Variant' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: '添加 Variant' }))
    expect(screen.getByLabelText('Variant ID 2')).toHaveValue('variant-3')
  })

  it('keeps the default variant unchanged when renaming a non-default variant', async () => {
    const user = userEvent.setup()
    render(<ModelFormHarness />)

    await user.click(screen.getByRole('button', { name: '添加 Variant' }))
    const secondNameInput = screen.getByLabelText('Variant ID 2')
    await user.clear(secondNameInput)
    await user.type(secondNameInput, 'creative')
    expect(screen.getByLabelText('Default Variant')).toHaveAttribute('data-value', 'medium')

    await user.clear(secondNameInput)
    await user.type(secondNameInput, 'creative-2')
    expect(screen.getByLabelText('Default Variant')).toHaveAttribute('data-value', 'medium')
  })

  it('renders every model field error kind inline', () => {
    render(
      <ModelForm
        draft={{
          ...emptyModelDraft(),
          providerName: 'minimax',
          // defaultVariant 不在 variants 中时 Select 回退到第一个 option
          defaultVariant: 'stale',
          reasoning: true,
        }}
        mode="create"
        providers={[
          {
            name: 'minimax',
            description: null,
            providerType: 'openai',
            baseUrl: null,
            configured: true,
            modelCallTimeoutMillis: 1800000,
            modelCallIdleTimeoutMillis: 120000,
            version: '1',
            createTime: null,
            updateTime: null,
            modelHttpRetryStatusCodes: null,
          },
        ]}
        fieldErrors={{
          providerName: '请选择 Provider',
          name: '请填写 Model 名称',
          contextWindow: '上下文窗口无效',
          maxOutputTokens: '最大输出长度无效',
          inputModalities: '请至少选择一种输入类型',
          pricing: '价格必须为非负数',
          defaultVariant: '请选择默认 Variant',
          variants: 'Variant ID 不能重复',
        }}
        onChange={() => undefined}
      />,
    )

    expect(screen.getByText('请选择 Provider')).toBeInTheDocument()
    expect(screen.getByText('请填写 Model 名称')).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'Name', exact: true })).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText('Input USD per million tokens')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByText('上下文窗口无效')).toBeInTheDocument()
    expect(screen.getByText('最大输出长度无效')).toBeInTheDocument()
    expect(screen.getByText('请至少选择一种输入类型')).toBeInTheDocument()
    expect(screen.getByText('价格必须为非负数')).toBeInTheDocument()
    expect(screen.getByText('请选择默认 Variant')).toBeInTheDocument()
    expect(screen.getByText('Variant ID 不能重复')).toBeInTheDocument()
  })

  it('allows editing model name in edit mode while keeping provider disabled', async () => {
    const user = userEvent.setup()
    render(<EditModelFormHarness />)

    const providerSelect = screen.getByLabelText('Provider')
    expect(providerSelect).toBeDisabled()

    const nameInput = screen.getByPlaceholderText('MiniMax-M2.7')
    expect(nameInput).not.toHaveAttribute('readonly')

    await user.clear(nameInput)
    await user.type(nameInput, 'MiniMax-M2.8')

    expect(nameInput).toHaveValue('MiniMax-M2.8')
  })

  it('edits agent model binding and tools without json editing', async () => {
    const user = userEvent.setup()
    render(<AgentFormHarness />)

    expect(screen.getByRole('button', { name: '使用第一个 Model 填充默认配置' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '使用第一个 Model 填充默认配置' }))
    expect(screen.getByLabelText('Default Model')).toHaveAttribute('data-value', 'minimax/MiniMax-M2.7')
    expect(screen.getByLabelText('Default Variant Override')).toHaveAttribute('data-value', '')

    await selectFormOption(user, 'Default Model', 'anthropic/Claude-Sonnet-4.5')
    expect(screen.getByLabelText('Default Variant Override')).toHaveAttribute('data-value', '')
    expect(screen.getByText('暂无候选 Tools')).toBeInTheDocument()
  })

  it('shows the model prerequisite hint when no model is available', () => {
    render(
      <AgentForm
        draft={{
          name: '',
          description: '',
          systemPrompt: '',
          model: '',
          variant: 'default',
          inheritParentEnvironment: true,
          tools: [],
          skills: [],
          subagents: [],
        }}
        models={[]}
        onChange={() => undefined}
      />,
    )

    expect(screen.getByText('需要先创建 Model 才能配置 Agent。')).toBeInTheDocument()
  })
})

function ProviderFormHarness({ mode = 'create' }: { mode?: 'create' | 'edit' }) {
  const [draft, setDraft] = useState<ProviderDraft>({
    name: '',
    description: '',
    providerType: 'openai',
    baseUrl: '',
    credential: '',
    modelCallTimeoutMillis: '1800000',
    modelCallIdleTimeoutMillis: '120000',
    modelHttpRetryStatusCodes: null,
  })
  return <ProviderForm draft={draft} mode={mode} onChange={setDraft} />
}

function ModelFormHarness() {
  const [draft, setDraft] = useState<ModelDraft>({
    ...emptyModelDraft(),
    providerName: 'minimax',
    reasoning: true,
  })
  return (
    <ModelForm
      draft={draft}
      mode="create"
      providers={[
        {
          name: 'minimax',
          description: null,
          providerType: 'openai',
          baseUrl: null,
          configured: true,
          modelCallTimeoutMillis: 1800000,
          modelCallIdleTimeoutMillis: 120000,
          createTime: '2026-06-20T02:00:00',
          updateTime: '2026-06-20T02:00:00',
          modelHttpRetryStatusCodes: null,
        },
      ]}
      onChange={setDraft}
    />
  )
}

function EditModelFormHarness() {
  const [draft, setDraft] = useState<ModelDraft>({
    ...emptyModelDraft(),
    providerName: 'minimax',
    name: 'MiniMax-M2.7',
    modelId: 'wire-minimax',
  })
  return (
    <ModelForm
      draft={draft}
      mode="edit"
      providers={[
        {
          name: 'minimax',
          description: null,
          providerType: 'openai',
          baseUrl: null,
          configured: true,
          modelCallTimeoutMillis: 1800000,
          modelCallIdleTimeoutMillis: 120000,
          version: '1',
          createTime: null,
          updateTime: null,
          modelHttpRetryStatusCodes: null,
        },
      ]}
      onChange={setDraft}
    />
  )
}

function AgentFormHarness() {
  const [draft, setDraft] = useState<AgentDraft>({
    name: '',
    description: '',
    systemPrompt: '',
    model: '',
    variant: 'default',
    inheritParentEnvironment: true,
    tools: [],
    skills: [],
    subagents: [],
  })
  return (
    <AgentForm
      draft={draft}
      models={[
        {
          providerName: 'minimax',
          name: 'MiniMax-M2.7',
          description: null,
          config: {
            limit: { context: 128000, output: 8192 },
            abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
            pricing: {
              currency: 'USD',
              pricingTier: 'default',
              serviceTier: 'default',
              serviceTierMultiplier: 1,
              version: 'v1',
              inputPerMillionTokens: 0,
              outputPerMillionTokens: 0,
              cacheReadPerMillionTokens: 0,
              cacheWritePerMillionTokens: 0,
              cacheWriteLongPerMillionTokens: 0,
              reasoningPerMillionTokens: 0,
            },
            defaultVariant: 'default',
            variants: [{ id: 'default' }],
          },
          createTime: '2026-06-20T02:00:00',
          updateTime: '2026-06-20T02:00:00',
        },
        {
          providerName: 'anthropic',
          name: 'Claude-Sonnet-4.5',
          description: null,
          config: {
            limit: { context: 200000, output: 16000 },
            abilities: { tools: false, reasoning: true, inputModalities: ['TEXT'] },
            pricing: {
              currency: 'USD',
              pricingTier: 'default',
              serviceTier: 'default',
              serviceTierMultiplier: 1,
              version: 'v1',
              inputPerMillionTokens: 0,
              outputPerMillionTokens: 0,
              cacheReadPerMillionTokens: 0,
              cacheWritePerMillionTokens: 0,
              cacheWriteLongPerMillionTokens: 0,
              reasoningPerMillionTokens: 0,
            },
            defaultVariant: 'creative',
            variants: [{ id: 'creative' }, { id: 'precise' }],
          },
          createTime: '2026-06-20T02:00:00',
          updateTime: '2026-06-20T02:00:00',
        },
      ]}
      onChange={setDraft}
    />
  )
}
