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
import { AiConsoleFrame } from '@/app/ai-console/AiConsoleFrame'

/** 与骨架同宽的真实栅格：浏览器断言用它的首行列数作为期望值，而不是写死 6 或某个像素宽度。 */
function RealGridProbe() {
  return (
    <ResourceGrid>
      {[1, 2, 3, 4, 5, 6].map((index) => (
        <ResourceCard
          key={index}
          className="harness-real-card"
          icon={<Bot />}
          title={`真实资源 ${index}`}
          subtitle="用于比对列数与卡片宽度"
          meta={[['类型', 'model']]}
          actions={
            <Button size="compact" variant="secondary">
              <Trash2 size={14} /> 删除
            </Button>
          }
        />
      ))}
    </ResourceGrid>
  )
}

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
            variant="secondary"
            loading={btnLoading}
            onClick={() => setClickCount((c) => c + 1)}
          >
            紧凑操作
          </Button>
          <span id="click-counter">点击次数: {clickCount}</span>
        </div>
      </section>

      <section style={{ marginBottom: 32 }}>
        <h2>普通资源骨架（按实际列数画首行）</h2>
        <div id="standalone-skeleton-wrap">
          <ResourceCardSkeleton label="加载资源中..." />
        </div>
      </section>

      <section style={{ marginBottom: 32 }}>
        <h2>环境摘要骨架</h2>
        <div id="environment-skeleton-wrap">
          <ResourceCardSkeleton label="加载环境..." variant="environment" />
        </div>
      </section>

      <section style={{ marginBottom: 32 }}>
        <h2>同宽真实栅格（列数与卡片宽度基准）</h2>
        <div id="real-grid-wrap">
          <RealGridProbe />
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
                    <Button size="compact" variant="secondary">
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
