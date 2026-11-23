import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import '@/styles.css'
import { setLocale, type AppLocale } from '@/shared/i18n'
import { ThreadComposer } from '@/features/thread/runtime/thread-panel/ThreadComposer'
import { THREAD_COMMANDS } from '@/features/thread/runtime/thread-panel/thread-commands'
import type { ComposerPart } from '@/features/thread/composer/composer-parts'
import type {
  ThreadComposerModelOption,
  ThreadComposerModelSelection,
} from '@/features/thread/runtime/thread-panel/ThreadComposerControls'

/**
 * 390 宽 Composer 控件行回归基座：`?locale=zh-CN|en-US`。
 *
 * 模型名、variant 后缀与环境名取 R2-9 截图同一组真实值（`gpt-mock-large (default)`
 * 与 `dev-workstation`），用来验证窄屏下两者都保留可辨认的前缀。
 * 页面只铺满视口，不加额外内边距，使 Composer 的行宽与真实 Thread 面板一致。
 */
const params = new URLSearchParams(window.location.search)
const locale: AppLocale = params.get('locale') === 'en-US' ? 'en-US' : 'zh-CN'
setLocale(locale)

const MODEL_NAME = 'gpt-mock-large'
const ENVIRONMENT_NAME = 'dev-workstation'

const MODELS: readonly ThreadComposerModelOption[] = [
  {
    type: 'MODEL',
    name: MODEL_NAME,
    config: { defaultVariant: 'default', variants: [{ id: 'default' }, { id: 'fast' }] },
  },
  {
    type: 'MODEL',
    name: 'gpt-mock-fast',
    config: { defaultVariant: 'fast', variants: [{ id: 'fast' }] },
  },
]

export function ComposerControlsHarnessApp() {
  const [parts, setParts] = useState<ComposerPart[]>([])
  const [model, setModel] = useState<ThreadComposerModelSelection>({
    modelName: MODEL_NAME,
    variant: 'default',
  })
  const [environmentName, setEnvironmentName] = useState<string | null>(ENVIRONMENT_NAME)
  const [yoloEnabled, setYoloEnabled] = useState(false)

  return (
    <div className="composer-controls-harness">
      <ThreadComposer
        parts={parts}
        pending={false}
        disabled={false}
        onPartsChange={setParts}
        onSubmit={() => setParts([])}
        onCommand={() => undefined}
        commands={THREAD_COMMANDS.map((command) => ({ ...command }))}
        settings={{
          modelSelection: model,
          models: MODELS,
          yoloEnabled,
          environmentName,
          environments: [{ name: ENVIRONMENT_NAME }, { name: 'python-dev' }],
          onModelChange: setModel,
          onYoloChange: setYoloEnabled,
          onEnvironmentChange: setEnvironmentName,
        }}
      />
    </div>
  )
}

const rootElement = document.getElementById('root')
if (rootElement) {
  createRoot(rootElement).render(<ComposerControlsHarnessApp />)
}
