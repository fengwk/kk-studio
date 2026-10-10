import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import { MemoryRouter } from 'react-router'
import { Bot, Trash2 } from 'lucide-react'
import '@/styles.css'
import { Button } from '@/shared/ui/controls/Button'
import { ResourceCard } from '@/shared/ui/cards/ResourceCard'
import { ResourceCardSkeleton } from '@/shared/ui/cards/ResourceCardSkeleton'
import { ResourceGrid } from '@/shared/ui/cards/ResourceGrid'
import { CreateCard } from '@/shared/ui/feedback/CreateCard'
import { AiConsoleFrame } from '@/features/ai/extensions/AiConsoleFrame'

export function LoadingSkeletonHarnessApp() {
  const [clickCount, setClickCount] = useState(0)
  const [btnLoading, setBtnLoading] = useState(true)
  const [consoleState, setConsoleState] = useState<'loading' | 'success' | 'error'>('loading')

  return (
    <div className="harness-root" style={{ padding: 16 }}>
      <section className="harness-controls" style={{ marginBottom: 24, display: 'flex', gap: 12, flexWrap: 'wrap' }}>
        <button id="toggle-console-loading" onClick={() => setConsoleState('loading')}>
          设为 Loading
        </button>
        <button id="toggle-console-success" onClick={() => setConsoleState('success')}>
          设为 Success
        </button>
        <button id="toggle-console-error" onClick={() => setConsoleState('error')}>
          设为 Error
        </button>
        <button id="toggle-btn-loading" onClick={() => setBtnLoading((v) => !v)}>
          切换按钮 Loading
        </button>
      </section>

      <section style={{ marginBottom: 32 }}>
        <h2>按钮与防重复提交测试</h2>
        <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
          <Button
            id="test-loading-button"
            variant="primary"
            loading={btnLoading}
            onClick={() => setClickCount((c) => c + 1)}
          >
            确认提交
          </Button>
          <Button
            id="test-compact-button"
            size="compact"
            variant="ghost"
            loading={btnLoading}
            onClick={() => setClickCount((c) => c + 1)}
          >
            紧凑操作
          </Button>
          <span id="click-counter">点击次数: {clickCount}</span>
        </div>
      </section>

      <section style={{ marginBottom: 32 }}>
        <h2>独立骨架屏测试 (约 280px 高度)</h2>
        <div id="standalone-skeleton-wrap">
          <ResourceCardSkeleton label="加载资源中..." />
        </div>
      </section>

      <section>
        <h2>AiConsoleFrame 加载态/数据态/错误态测试</h2>
        <div id="console-frame-container" style={{ border: '1px solid var(--border)', borderRadius: 'var(--radius-lg)' }}>
          <AiConsoleFrame
            search=""
            onSearchChange={() => undefined}
            busy={consoleState === 'loading'}
            error={consoleState === 'error' ? new Error('模拟资源加载错误') : null}
            mutationError={null}
            content={
              <ResourceGrid>
                <CreateCard title="新建模型" subtitle="创建新的语言模型" onClick={() => undefined} />
                <ResourceCard
                  icon={<Bot />}
                  title="GPT-4o"
                  subtitle="商业模型"
                  meta={[['类型', 'LLM'], ['状态', 'Ready']]}
                  actions={
                    <Button size="compact" variant="ghost">
                      <Trash2 size={14} /> 删除
                    </Button>
                  }
                />
              </ResourceGrid>
            }
          />
        </div>
      </section>
    </div>
  )
}

const container = document.getElementById('root')
if (container) {
  createRoot(container).render(
    <MemoryRouter>
      <LoadingSkeletonHarnessApp />
    </MemoryRouter>,
  )
}
