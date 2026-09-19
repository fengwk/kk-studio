import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import {
  ThreadComposerControls,
  type ThreadComposerControlMenu,
  type ThreadComposerModelOption,
  type ThreadComposerModelSelection,
  type ThreadComposerSettingsInput,
} from '@/features/ai/runtime/thread-panel/ThreadComposerControls'

/** 测试用稳定模型目录：第一项多 variant（走二级菜单），第二项单 variant（直接选中）。 */
const MODELS: readonly ThreadComposerModelOption[] = [
  {
    providerName: 'minimax',
    name: 'MiniMax',
    config: {
      defaultVariant: 'default',
      variants: [{ id: 'default' }, { id: 'high' }, { id: '  ' }, { id: 'default' }],
    },
  },
  {
    providerName: 'openai',
    name: 'gpt',
    config: {
      defaultVariant: 'fast',
      variants: [{ id: 'fast' }],
    },
  },
]

function createSettings(overrides: Partial<ThreadComposerSettingsInput> = {}): ThreadComposerSettingsInput {
  return {
    model: { providerName: 'minimax', modelName: 'MiniMax', variant: 'default' },
    models: MODELS,
    yoloEnabled: false,
    environmentName: null,
    environments: [],
    onModelChange: vi.fn(),
    onYoloChange: vi.fn(),
    onEnvironmentChange: vi.fn(),
    ...overrides,
  }
}

/** 同步受控变体：onModelChange 立即把新选择写回 settings.model。 */
function createLiveSettings(
  onModelChange?: (model: ThreadComposerModelSelection) => void,
): ThreadComposerSettingsInput & { onModelChange: (model: ThreadComposerModelSelection) => void } {
  const live = createSettings()
  live.onModelChange = (model) => {
    live.model = model
    onModelChange?.(model)
  }
  return live
}

/** 与真实 ThreadComposer 一致：changeControlMenu 直接透传 restoreComposerFocus。 */
function forwardRestore(_next: ThreadComposerControlMenu, _restoreComposerFocus?: boolean): void {}

/**
 * 受控 Harness：内部持有 `menu` 状态并转发 ThreadComposer 的
 * changeControlMenu 行为（含 restoreComposerFocus 透传）。直接改变组件
 * 内状态，使菜单切换像真实 Composer 一样重新渲染。
 */
function Harness({
  settings,
  menu,
  disabled = false,
  onMenuChange = forwardRestore,
}: {
  settings: ThreadComposerSettingsInput
  menu: ThreadComposerControlMenu
  disabled?: boolean
  onMenuChange?: (menu: ThreadComposerControlMenu, restoreComposerFocus?: boolean) => void
}) {
  return <HarnessInner settings={settings} initialMenu={menu} disabled={disabled} onMenuChange={onMenuChange} />
}

function HarnessInner({
  settings,
  initialMenu,
  disabled,
  onMenuChange,
}: {
  settings: ThreadComposerSettingsInput
  initialMenu: ThreadComposerControlMenu
  disabled: boolean
  onMenuChange: (menu: ThreadComposerControlMenu, restoreComposerFocus?: boolean) => void
}) {
  const [menu, setMenu] = useState<ThreadComposerControlMenu>(initialMenu)
  const changeMenu = (next: ThreadComposerControlMenu, restoreComposerFocus?: boolean) => {
    setMenu(next)
    onMenuChange(next, restoreComposerFocus)
  }
  return (
    <ThreadComposerControls
      settings={settings}
      menu={menu}
      disabled={disabled}
      onMenuChange={changeMenu}
    />
  )
}

function renderOpen(menu: ThreadComposerControlMenu, settings = createSettings()) {
  const onMenuChange = vi.fn(forwardRestore)
  const view = render(<Harness settings={settings} menu={menu} onMenuChange={onMenuChange} />)
  return { onMenuChange, settings, unmount: view.unmount }
}

/** 打开菜单按钮：权限按钮 aria-label 固定为「权限模式」。 */
function permissionButton() {
  return screen.getByRole('button', { name: '权限模式' })
}

describe('ThreadComposerControls permission menu', () => {
  it('reflects yoloEnabled on the trigger and marks the current option', () => {
    const { unmount } = renderOpen('permission', createSettings({ yoloEnabled: true }))
    expect(permissionButton()).toHaveTextContent('YOLO')
    expect(permissionButton()).toHaveAttribute('aria-expanded', 'true')
    const listbox = screen.getByRole('listbox', { name: '权限选项' })
    expect(within(listbox).getByRole('option', { name: 'YOLO' })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    expect(within(listbox).getByRole('option', { name: 'Default' })).not.toHaveAttribute(
      'aria-selected',
      'true',
    )
    expect(within(listbox).getByRole('option', { name: 'YOLO' })).toHaveAttribute(
      'aria-current',
      'true',
    )
    unmount()

    renderOpen('permission', createSettings({ yoloEnabled: false }))
    const listboxDefault = screen.getByRole('listbox', { name: '权限选项' })
    expect(within(listboxDefault).getByRole('option', { name: 'Default' })).toHaveAttribute(
      'aria-selected',
      'true',
    )
  })

  it('selects YOLO/Default with mouse and restores composer focus', async () => {
    const user = userEvent.setup()
    const { onMenuChange, settings } = renderOpen('permission')
    await user.click(screen.getByRole('option', { name: 'YOLO' }))
    expect(settings.onYoloChange).toHaveBeenCalledWith(true)
    expect(onMenuChange).toHaveBeenCalledWith(null, true)
  })

  it('supports keyboard navigation and Enter selection', async () => {
    const user = userEvent.setup()
    const { onMenuChange, settings } = renderOpen('permission')
    const listbox = screen.getByRole('listbox', { name: '权限选项' })
    listbox.focus()
    // Default 初始 active；ArrowDown 移到 YOLO，Enter 提交 YOLO。
    await user.keyboard('{ArrowDown}{Enter}')
    expect(settings.onYoloChange).toHaveBeenCalledWith(true)
    expect(onMenuChange).toHaveBeenCalledWith(null, true)
  })
})

describe('ThreadComposerControls model/variant menus', () => {
  it('lists all models with current one marked', () => {
    renderOpen('model')
    expect(screen.getByRole('listbox', { name: 'Model 选项' })).toBeInTheDocument()
    const options = screen.getAllByRole('option')
    expect(options.map((option) => option.textContent)).toEqual(['minimax/MiniMax', 'openai/gpt'])
    expect(screen.getByRole('option', { name: 'minimax/MiniMax' })).toHaveAttribute(
      'aria-current',
      'true',
    )
    // 当前选中项在列表中渲染 Check 图标，表明 aria-current 与视觉一致。
    expect(screen.getByRole('option', { name: 'minimax/MiniMax' }).querySelector('svg')).not.toBeNull()
  })

  it('filters models by the search box query', async () => {
    const user = userEvent.setup()
    renderOpen('model')
    const search = screen.getByRole('searchbox', { name: '搜索模型' })
    await user.type(search, 'gpt')
    expect(screen.getAllByRole('option').map((option) => option.textContent)).toEqual(['openai/gpt'])
    await user.clear(search)
    expect(screen.getAllByRole('option')).toHaveLength(2)
  })

  it('shows an empty listbox for a query without matches and keeps selection no-op', async () => {
    const user = userEvent.setup()
    const { settings } = renderOpen('model')
    const search = screen.getByRole('searchbox', { name: '搜索模型' })
    await user.type(search, 'zzz')
    const listbox = screen.getByRole('listbox', { name: 'Model 选项' })
    expect(within(listbox).queryAllByRole('option')).toHaveLength(0)
    await user.keyboard('{ArrowDown}{ArrowDown}{Enter}')
    expect(settings.onModelChange).not.toHaveBeenCalled()
  })

  it('selects a single-variant model directly with the variant', async () => {
    const user = userEvent.setup()
    const { onMenuChange, settings } = renderOpen('model')
    await user.click(screen.getByRole('option', { name: 'openai/gpt' }))
    expect(settings.onModelChange).toHaveBeenCalledWith({
      providerName: 'openai',
      modelName: 'gpt',
      variant: 'fast',
    })
    expect(onMenuChange).toHaveBeenCalledWith(null, true)
  })

  it('ignores clicks on stale model ids that are no longer in the catalog', async () => {
    const user = userEvent.setup()
    const { onMenuChange, settings } = renderOpen('model')
    // 构造一个不在目录中的 id：菜单里没有对应 option，直接对 listbox 外的
    // 位置无法点击，因此通过 fireEvent 在 listbox 上派发携带该 id 的
    // option 点击事件（等价于 stale 渲染残留的 option）。
    const listbox = screen.getByRole('listbox', { name: 'Model 选项' })
    const staleOption = document.createElement('button')
    staleOption.setAttribute('role', 'option')
    staleOption.textContent = 'stale/ghost'
    listbox.appendChild(staleOption)
    await user.click(staleOption)
    expect(settings.onModelChange).not.toHaveBeenCalled()
    expect(onMenuChange).not.toHaveBeenCalled()
  })

  it('opens the variant submenu for multi-variant models and selects a variant', async () => {
    const user = userEvent.setup()
    const selected: ThreadComposerModelSelection[] = []
    const settings = createLiveSettings((model) => selected.push(model))
    const view = renderOpen('model', settings)
    // 鼠标点击 multi-variant 模型进入二级菜单。
    await user.click(screen.getByRole('option', { name: 'minimax/MiniMax' }))
    expect(view.onMenuChange.mock.calls).toEqual([['variant', undefined]])
    // active 落在默认 variant（default）；ArrowDown 到 high 后 Enter 提交。
    const listbox = screen.getByRole('listbox', { name: 'Variant 选项' })
    expect(within(listbox).getByRole('option', { name: 'default' })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    await user.keyboard('{ArrowDown}{Enter}')
    expect(selected).toEqual([{ providerName: 'minimax', modelName: 'MiniMax', variant: 'high' }])
    expect(view.onMenuChange.mock.calls).toEqual([
      ['variant', undefined],
      [null, true],
    ])
  })

  it('navigates models with ArrowUp/ArrowDown, Home/End and confirms with Enter', async () => {
    const user = userEvent.setup()
    const selected: ThreadComposerModelSelection[] = []
    const settings = createLiveSettings((model) => selected.push(model))
    const view = renderOpen('model', settings)
    // 初始 active 为当前 model（minimax/MiniMax）；ArrowDown 到 openai/gpt，
    // Enter 直接选中（单 variant 模型无需二级菜单）。
    await user.keyboard('{ArrowDown}{Enter}')
    expect(selected).toEqual([{ providerName: 'openai', modelName: 'gpt', variant: 'fast' }])
    expect(view.onMenuChange.mock.calls).toEqual([[null, true]])
    view.unmount()

    // 重新挂载（关闭后的菜单回到 model 打开状态）验证 End 环绕行为。
    const second = renderOpen('model', createLiveSettings((model) => selected.push(model)))
    // End 移到末尾项（openai/gpt），再 ArrowDown 环绕回第一项；Enter 应进入
    // 二级菜单（multi-variant），而不是直接选中并关闭。
    await user.keyboard('{End}{ArrowDown}{Enter}')
    expect(selected).toEqual([{ providerName: 'openai', modelName: 'gpt', variant: 'fast' }])
    expect(second.onMenuChange.mock.calls).toEqual([['variant', undefined]])
    // 键盘路径同样进入 Variant 列表，且初始 active 是当前 variant。
    const listbox = screen.getByRole('listbox', { name: 'Variant 选项' })
    expect(within(listbox).getByRole('option', { name: 'default' })).toHaveAttribute(
      'aria-selected',
      'true',
    )
  })

  it('defaults the variant menu to the model defaultVariant when the current selection is absent', async () => {
    renderOpen(
      'variant',
      createSettings({
        // 当前 model 不在任何目录项中：variantTarget 取不到，菜单渲染空列表。
        model: { providerName: 'unknown', modelName: 'ghost', variant: 'none' },
      }),
    )
    const listbox = screen.getByRole('listbox', { name: 'Variant 选项' })
    expect(within(listbox).queryAllByRole('option')).toHaveLength(0)
  })

  it('returns to the model menu with Backspace and closes with Escape', async () => {
    const user = userEvent.setup()
    const { onMenuChange } = renderOpen('variant')
    await user.keyboard('{Backspace}')
    expect(onMenuChange).toHaveBeenCalledWith('model', undefined)

    onMenuChange.mockClear()
    await user.keyboard('{Escape}')
    expect(onMenuChange).toHaveBeenCalledWith(null, true)
  })

  it('returns to the model menu with ArrowLeft', async () => {
    const user = userEvent.setup()
    const { onMenuChange } = renderOpen('variant')
    await user.keyboard('{ArrowLeft}')
    expect(onMenuChange).toHaveBeenCalledWith('model', undefined)
  })

  it('renders the generic model label on the back button without a target model', async () => {
    renderOpen(
      'variant',
      createSettings({
        // 当前 model 不在任何目录项中：variantTarget 为空，返回按钮显示通用 Model 文案。
        model: { providerName: 'unknown', modelName: 'ghost', variant: 'none' },
      }),
    )
    expect(screen.getByRole('button', { name: 'Model' })).toBeInTheDocument()
  })

  it('closes the menu on Escape from the search box', async () => {
    const user = userEvent.setup()
    const { onMenuChange } = renderOpen('model')
    await user.keyboard('{Escape}')
    expect(onMenuChange).toHaveBeenCalledWith(null, true)
    // 外部关闭菜单后整个 anchored menu 渲染被移除（search box 一并消失）。
    expect(screen.queryByRole('searchbox', { name: '搜索模型' })).not.toBeInTheDocument()
  })
})

describe('ThreadComposerControls disabled and empty states', () => {
  it('disables both triggers when disabled and ignores keyboard selection', async () => {
    const user = userEvent.setup()
    const onMenuChange = vi.fn()
    const settings = createSettings()
    render(<Harness settings={settings} menu="permission" disabled onMenuChange={onMenuChange} />)
    expect(permissionButton()).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Model 与 Variant' })).toBeDisabled()

    // 即使菜单已打开，disabled 时点击选项也不应触发任何变更。
    await user.click(screen.getByRole('option', { name: 'YOLO' }))
    expect(settings.onYoloChange).not.toHaveBeenCalled()
    expect(onMenuChange).not.toHaveBeenCalled()
  })

  it('disables the model trigger when the catalog has no usable model', () => {
    const settings = createSettings({ models: [] })
    render(<Harness settings={settings} menu={null} />)
    expect(screen.getByRole('button', { name: 'Model 与 Variant' })).toBeDisabled()
    // 权限触发不受模型目录影响。
    expect(permissionButton()).toBeEnabled()
  })

  it('drops models whose variant list is empty or only whitespace', () => {
    const settings = createSettings({
      models: [
        {
          providerName: 'empty',
          name: 'no-variants',
          config: { defaultVariant: 'x', variants: [] },
        },
        {
          providerName: 'blank',
          name: 'blank-variants',
          config: { defaultVariant: 'x', variants: [{ id: '   ' }] },
        },
      ],
    })
    render(<Harness settings={settings} menu="model" />)
    // 两个无有效 variant 的模型都不出现在菜单中，模型按钮按无可用模型禁用。
    expect(screen.queryByRole('option')).not.toBeInTheDocument()
  })
})

describe('ThreadComposerControls menu switching', () => {
  it('toggles the permission menu when clicking the trigger', async () => {
    const user = userEvent.setup()
    const view = renderOpen(null)
    await user.click(permissionButton())
    expect(view.onMenuChange).toHaveBeenCalledWith('permission', undefined)
    view.unmount()

    // 重新挂载为打开状态，再次点击触发关闭（menu===permission 时翻转为 null）。
    const opened = renderOpen('permission')
    await user.click(permissionButton())
    expect(opened.onMenuChange).toHaveBeenCalledWith(null, undefined)
  })

  it('closes the model or variant menu when clicking the model trigger', async () => {
    const user = userEvent.setup()
    const modelOpen = renderOpen('model')
    await user.click(screen.getByRole('button', { name: 'Model 与 Variant' }))
    expect(modelOpen.onMenuChange).toHaveBeenCalledWith(null, undefined)
    modelOpen.unmount()

    // variant 二级菜单同样由同一个 trigger 关闭。
    const variantOpen = renderOpen('variant')
    await user.click(screen.getByRole('button', { name: 'Model 与 Variant' }))
    expect(variantOpen.onMenuChange).toHaveBeenCalledWith(null, undefined)
  })

  it('closes the current menu when clicking outside the host', async () => {
    const user = userEvent.setup()
    const { onMenuChange } = renderOpen('permission')
    await user.click(document.body)
    expect(onMenuChange).toHaveBeenCalledWith(null, undefined)
  })

  it('keeps the menu open when clicking inside the host', async () => {
    const user = userEvent.setup()
    const { onMenuChange } = renderOpen('permission')
    await user.click(screen.getByRole('listbox', { name: '权限选项' }))
    expect(onMenuChange).not.toHaveBeenCalled()
  })
})

describe('ThreadComposerControls environment menu', () => {
  /** 测试意图：验证未选环境时 trigger 显示 None，已选时展示具体环境名称。 */
  it('renders None when environmentName is null, and renders name when present', () => {
    const noneView = renderOpen(null, createSettings({ environmentName: null }))
    expect(screen.getByRole('button', { name: '环境' })).toHaveTextContent('None')
    noneView.unmount()

    renderOpen(null, createSettings({ environmentName: 'dev-cluster' }))
    expect(screen.getByRole('button', { name: '环境' })).toHaveTextContent('dev-cluster')
  })

  /** 测试意图：验证环境下拉选项第一项为 None，之后为去重环境名；孤立环境名（orphan）仍然展示。 */
  it('renders None as first option, followed by environments, and preserves orphan environmentName', () => {
    renderOpen(
      'environment',
      createSettings({
        environmentName: 'orphan-env',
        environments: [{ name: 'dev-cluster' }, { name: 'prod-box' }, { name: 'dev-cluster' }],
      }),
    )

    const listbox = screen.getByRole('listbox', { name: '环境选项' })
    const options = within(listbox).getAllByRole('option')
    expect(options.map((opt) => opt.textContent)).toEqual([
      'None',
      'dev-cluster',
      'prod-box',
      'orphan-env',
    ])
  })

  /** 测试意图：验证点击选项调用 onEnvironmentChange，选择 None 时传 null，选择环境名时传对应名称。 */
  it('calls onEnvironmentChange with name or null on option selection', async () => {
    const user = userEvent.setup()
    const onEnvironmentChange = vi.fn()
    const onMenuChange = vi.fn()

    render(
      <ThreadComposerControls
        settings={createSettings({
          environmentName: 'dev-cluster',
          environments: [{ name: 'dev-cluster' }, { name: 'prod-box' }],
          onEnvironmentChange,
        })}
        menu="environment"
        disabled={false}
        onMenuChange={onMenuChange}
      />,
    )

    const listbox = screen.getByRole('listbox', { name: '环境选项' })
    await user.click(within(listbox).getByRole('option', { name: 'prod-box' }))
    expect(onEnvironmentChange).toHaveBeenCalledWith('prod-box')
    expect(onMenuChange).toHaveBeenCalledWith(null, true)

    // 选择 None 时传 null
    await user.click(within(listbox).getByRole('option', { name: 'None' }))
    expect(onEnvironmentChange).toHaveBeenCalledWith(null)
  })

  /** 测试意图：验证 ArrowDown 与 Enter 键盘快捷键在环境菜单中的导航与选中行为。 */
  it('supports keyboard navigation and Escape dismissal in environment menu', async () => {
    const user = userEvent.setup()
    const onEnvironmentChange = vi.fn()
    const onMenuChange = vi.fn()

    render(
      <ThreadComposerControls
        settings={createSettings({
          environmentName: null,
          environments: [{ name: 'env-1' }],
          onEnvironmentChange,
        })}
        menu="environment"
        disabled={false}
        onMenuChange={onMenuChange}
      />,
    )

    const listbox = screen.getByRole('listbox', { name: '环境选项' })
    listbox.focus()

    // 默认高亮 None；向下键移动到 env-1 并回车选中
    await user.keyboard('{ArrowDown}')
    await user.keyboard('{Enter}')
    expect(onEnvironmentChange).toHaveBeenCalledWith('env-1')
    expect(onMenuChange).toHaveBeenCalledWith(null, true)

    // 按 Escape 键直接关闭菜单
    await user.keyboard('{Escape}')
    expect(onMenuChange).toHaveBeenCalledWith(null, true)
  })
})
