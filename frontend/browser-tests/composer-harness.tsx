import { useState, useCallback, useEffect } from 'react'
import { createRoot } from 'react-dom/client'
import '@/styles.css'
import { setLocale } from '@/shared/i18n'
import { ThreadComposer } from '@/features/ai/runtime/thread-panel/ThreadComposer'
import { THREAD_COMMANDS, type ThreadCommand } from '@/features/ai/runtime/thread-panel/thread-commands'
import type { ComposerPart } from '@/features/ai/composer/composer-parts'
import type { ThreadComposerSettingsInput } from '@/features/ai/runtime/thread-panel/ThreadComposerControls'

setLocale('zh-CN')

const INITIAL_MODELS = [
  {
    providerName: 'minimax',
    name: 'MiniMax-M2.7',
    config: {
      defaultVariant: 'default',
      variants: [{ id: 'default' }, { id: 'fast' }],
    },
  },
  {
    providerName: 'openai',
    name: 'gpt-4o',
    config: {
      defaultVariant: 'default',
      variants: [{ id: 'default' }],
    },
  },
]

export function ComposerHarnessApp() {
  const [pane1Parts, setPane1Parts] = useState<ComposerPart[]>([])
  const [pane2Parts, setPane2Parts] = useState<ComposerPart[]>([])
  const [yoloEnabled, setYoloEnabled] = useState(false)
  const [model, setModel] = useState({ providerName: 'minimax', modelName: 'MiniMax-M2.7', variant: 'default' })
  const [environmentName, setEnvironmentName] = useState<string | null>(null)
  const [modalOpen, setModalOpen] = useState(false)
  const [lastCommand, setLastCommand] = useState<string | null>(null)
  const [submitPayloads, setSubmitPayloads] = useState<string[]>([])

  const handleCommand = useCallback((cmd: ThreadCommand) => {
    setLastCommand(cmd.id)
  }, [])

  const handleSubmit = useCallback((_payload: ComposerPart[], localDraft: ComposerPart[]) => {
    setSubmitPayloads((prev) => [...prev, JSON.stringify(localDraft)])
    setPane1Parts([])
  }, [])

  const settings: ThreadComposerSettingsInput = {
    model,
    models: INITIAL_MODELS,
    yoloEnabled,
    environmentName,
    environments: [{ name: 'node-dev' }, { name: 'python-dev' }],
    onModelChange: setModel,
    onYoloChange: setYoloEnabled,
    onEnvironmentChange: setEnvironmentName,
  }

  // 模态弹窗自身的 Escape 处理：上层 Modal 优先
  useEffect(() => {
    if (!modalOpen) return
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !e.defaultPrevented) {
        e.preventDefault()
        e.stopPropagation()
        setModalOpen(false)
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [modalOpen])

  return (
    <div className="harness-container">
      <div className="harness-topbar">
        <button id="outside-btn" type="button">
          外部按钮 (Outside)
        </button>
        <button id="open-modal-btn" type="button" onClick={() => setModalOpen(true)}>
          打开模态框 (Open Modal)
        </button>
        <div className="debug-output">
          最后执行命令: <span id="last-command-val">{lastCommand ?? 'none'}</span> | 提交次数:{' '}
          <span id="submit-count-val">{submitPayloads.length}</span>
        </div>
      </div>

      <div className="harness-panes">
        <div className="pane-box" id="pane-1-wrapper" data-testid="pane-1">
          <div className="pane-header">Pane 1 (Active Pane, focusOnEscape: true)</div>
          <ThreadComposer
            parts={pane1Parts}
            pending={false}
            disabled={false}
            focusOnEscape={true}
            active={true}
            onPartsChange={setPane1Parts}
            onSubmit={handleSubmit}
            onCommand={handleCommand}
            commands={THREAD_COMMANDS}
            settings={settings}
          />
        </div>

        <div className="pane-box" id="pane-2-wrapper" data-testid="pane-2">
          <div className="pane-header">Pane 2 (Background Pane, focusOnEscape: false)</div>
          <ThreadComposer
            parts={pane2Parts}
            pending={false}
            disabled={false}
            focusOnEscape={false}
            active={true}
            onPartsChange={setPane2Parts}
            onSubmit={(_p, d) => {
              setSubmitPayloads((prev) => [...prev, JSON.stringify(d)])
              setPane2Parts([])
            }}
            onCommand={handleCommand}
            commands={THREAD_COMMANDS}
          />
        </div>
      </div>

      {modalOpen && (
        <div className="modal-backdrop modal-backdrop-test" role="dialog" aria-modal="true" aria-label="测试模态框">
          <div className="modal-content-test">
            <h3>测试模态框 (Modal)</h3>
            <p>此时按 Escape 应当仅关闭本模态框，不触发底层 Composer 焦点抢占。</p>
            <button id="modal-close-btn" type="button" onClick={() => setModalOpen(false)}>
              关闭 (Close)
            </button>
          </div>
        </div>
      )}
    </div>
  )
}

const rootElement = document.getElementById('root')
if (rootElement) {
  createRoot(rootElement).render(<ComposerHarnessApp />)
}
