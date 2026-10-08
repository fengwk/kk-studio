import { createRoot } from 'react-dom/client'
import { useState } from 'react'
import { Bot, Pencil, Trash2 } from 'lucide-react'
import '@/styles.css'
import { ResourceCard } from '@/shared/ui/cards/ResourceCard'
import { ResourceGrid } from '@/shared/ui/cards/ResourceGrid'
import { Button } from '@/shared/ui/controls/Button'
import { CreateCard } from '@/shared/ui/feedback/CreateCard'
import { AgentForm, ModelForm, ProviderForm } from '@/features/ai/catalog/AiResourceForms'
import { emptyAgentDraft, emptyModelDraft, emptyProviderDraft } from '@/features/ai/catalog/ai-resource-draft-codecs'
import { setLocale } from '@/shared/i18n'

setLocale('zh-CN')

const LONG_TITLE =
  'a-very-long-resource-name-that-must-be-truncated-with-the-full-name-kept-available'

const LONG_VALUE =
  '这是一段很长的说明文字，用于验证资源卡在窄容器内自然换行，而不是横向溢出或挤掉底部动作区。'

/**
 * 在真实浏览器中验证共享资源卡的实际几何与颜色：
 * 16px 内边距、12px 圆角、40px 图标框、15px/600 标题、13px 正文、12px 辅助信息、
 * 28px 紧凑动作、无固定 min-height、窄屏单列且无横向溢出、长标题省略但保留完整名称。
 */
export function ResourceCardHarnessApp() {
  return (
    <div className="harness-root" style={{ padding: 24 }}>
      <ResourceGrid>
        <CreateCard title="新建资源" subtitle="创建一个新的资源" onClick={() => undefined} />
        <ResourceCard
          className="harness-standard"
          icon={<Bot />}
          title="标准资源卡"
          subtitle="标准副标题"
          badge={
            <span id="standard-badge" className="status-pill is-ready">
              Ready
            </span>
          }
          meta={[
            ['类型', 'agent'],
            { label: '工具', tags: ['read', 'bash', 'grep', 'write'], limit: 2 },
            { pairs: [{ label: '超时', value: '180s' }, { label: '空闲', value: '1500ms' }] },
          ]}
          actions={
            <>
              <Button size="compact">
                <Pencil aria-hidden="true" />
                编辑
              </Button>
              <Button id="card-disabled-action" variant="ghost" size="compact" danger disabled>
                <Trash2 aria-hidden="true" />
                删除
              </Button>
            </>
          }
        />
        <ResourceCard
          className="harness-second"
          icon={<Bot />}
          title="第二张资源卡"
          subtitle="同一栅格的相邻卡片"
          meta={[['类型', 'model']]}
        />
      </ResourceGrid>

      {/* 非栅格容器：单独测量“没有默认固定 min-height”和窄容器下的长内容表现。 */}
      <div className="harness-solo" style={{ marginTop: 24 }}>
        <ResourceCard
          className="harness-minimal"
          icon={<Bot />}
          title="最小内容"
          meta={[['类型', 'agent']]}
        />
        <ResourceCard
          className="harness-long"
          icon={<Bot />}
          title={LONG_TITLE}
          subtitle={LONG_VALUE}
          meta={[
            { label: '详情', value: LONG_VALUE, wrap: true },
            ['空值', ''],
          ]}
          actions={
            <Button id="card-focus-target" size="compact">
              主要动作
            </Button>
          }
        />
      </div>
    </div>
  )
}

const rootEl = document.getElementById('root')
if (rootEl) {
  createRoot(rootEl).render(new URLSearchParams(location.search).has('forms')
    ? <ResourceFormsHarness /> : <ResourceCardHarnessApp />)
}

function ResourceFormsHarness() {
  const [provider, setProvider] = useState({ ...emptyProviderDraft(), name: 'demo-provider' })
  const [model, setModel] = useState({
    ...emptyModelDraft({ name: 'demo-provider' }),
    name: 'demo-model',
    modelId: 'wire-model',
  })
  const [agent, setAgent] = useState({ ...emptyAgentDraft(), name: 'demo-agent' })
  return (
    <main style={{ padding: 16, maxWidth: 840, margin: '0 auto' }}>
      <section aria-label="Provider form">
        <ProviderForm draft={provider} mode="edit" fieldErrors={{ baseUrl: '地址无效' }} onChange={setProvider} />
      </section>
      <section aria-label="Model form">
        <ModelForm draft={model} mode="edit" providers={[]} onChange={setModel} />
      </section>
      <section aria-label="Agent form">
        <AgentForm draft={agent} mode="edit" models={[]} onChange={setAgent} />
      </section>
    </main>
  )
}
