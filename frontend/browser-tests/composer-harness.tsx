import { useState, useCallback, useEffect, useRef } from 'react'
import { createRoot } from 'react-dom/client'
import '@/styles.css'
import { setLocale } from '@/shared/i18n'
import { hasBlockingModal } from '@/shared/ui/blocking-overlay'
import { ThreadComposer, type ThreadComposerHandle } from '@/features/ai/runtime/thread-panel/ThreadComposer'
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
  const [pane1Key, setPane1Key] = useState(0)
  const [pane1Pending, setPane1Pending] = useState(false)
  const [pane1Disabled, setPane1Disabled] = useState(false)
  const [pane1FocusIntent, setPane1FocusIntent] = useState(false)
  const [firstSendMode, setFirstSendMode] = useState(false)
  const [activePane, setActivePane] = useState<'pane-1' | 'pane-2'>('pane-1')
  const pane1ComposerRef = useRef<ThreadComposerHandle | null>(null)

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
    if (firstSendMode) {
      // 模拟首发草稿接受与重挂载流程：
      // 1. 发送开始：pending=true, disabled=true
      setPane1Pending(true)
      setPane1Disabled(true)
      // 2. 模拟请求返回后绑定完成：更新 key 触发组件重挂载，pending 降沿，产生 focusIntent
      window.setTimeout(() => {
        setPane1Key((k) => k + 1)
        setPane1Pending(false)
        setPane1FocusIntent(true)
        // 3. 稍后解除 temporary disabled
        window.setTimeout(() => {
          setPane1Disabled(false)
        }, 60)
      }, 60)
    }
  }, [firstSendMode])

  // focusIntent 消费逻辑（等价于 useRootThreadControl）：
  // 在当前 pane 激活且 disabled 解除时消费一次，若遇阻塞 modal 或失焦则不抢焦
  useEffect(() => {
    if (!pane1FocusIntent) {
      return
    }
    if (activePane !== 'pane-1') {
      setPane1FocusIntent(false)
      return
    }
    if (!pane1Disabled) {
      if (!hasBlockingModal()) {
        pane1ComposerRef.current?.focus()
      }
      setPane1FocusIntent(false)
    }
  }, [pane1FocusIntent, activePane, pane1Disabled, modalOpen])

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
        <label style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 13, cursor: 'pointer' }}>
          <input
            id="chk-first-send-mode"
            type="checkbox"
            checked={firstSendMode}
            onChange={(e) => setFirstSendMode(e.target.checked)}
          />
          首次草稿发送模拟
        </label>
        <button id="btn-toggle-pane1-pending" type="button" onClick={() => setPane1Pending((p) => !p)}>
          切换 Pending
        </button>
        <button id="btn-toggle-pane1-disabled" type="button" onClick={() => setPane1Disabled((d) => !d)}>
          切换 Disabled
        </button>
        <button id="btn-switch-pane-2" type="button" onClick={() => setActivePane('pane-2')}>
          激活 Pane 2
        </button>
        <button id="btn-switch-pane-1" type="button" onClick={() => setActivePane('pane-1')}>
          激活 Pane 1
        </button>
        <div className="debug-output">
          最后执行命令: <span id="last-command-val">{lastCommand ?? 'none'}</span> | 提交次数:{' '}
          <span id="submit-count-val">{submitPayloads.length}</span>
        </div>
        <div
          id="pane1-state"
          className="debug-output"
          data-pending={String(pane1Pending)}
          data-disabled={String(pane1Disabled)}
          data-key={String(pane1Key)}
          data-active-pane={activePane}
          data-intent={String(pane1FocusIntent)}
        >
          Pane1: pending={String(pane1Pending)} disabled={String(pane1Disabled)} key={pane1Key} active={activePane}
        </div>
      </div>

      <div className="harness-panes">
        <div
          className="pane-box"
          id="pane-1-wrapper"
          data-testid="pane-1"
          onFocus={() => setActivePane('pane-1')}
        >
          <div className="pane-header">Pane 1 ({activePane === 'pane-1' ? 'Active Pane' : 'Background Pane'})</div>
          <ThreadComposer
            key={`pane-1-${pane1Key}`}
            ref={pane1ComposerRef}
            parts={pane1Parts}
            pending={pane1Pending}
            disabled={pane1Disabled}
            focusOnEscape={activePane === 'pane-1'}
            restoreOnActivate={activePane === 'pane-1'}
            active={true}
            onPartsChange={setPane1Parts}
            onSubmit={handleSubmit}
            onCommand={handleCommand}
            commands={THREAD_COMMANDS}
            settings={settings}
          />
        </div>

        <div
          className="pane-box"
          id="pane-2-wrapper"
          data-testid="pane-2"
          onFocus={() => setActivePane('pane-2')}
        >
          <div className="pane-header">Pane 2 ({activePane === 'pane-2' ? 'Active Pane' : 'Background Pane'})</div>
          <ThreadComposer
            parts={pane2Parts}
            pending={false}
            disabled={false}
            focusOnEscape={activePane === 'pane-2'}
            restoreOnActivate={activePane === 'pane-2'}
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
