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
import { ModelsPanel, ProvidersPanel } from '@/features/ai/catalog/AiConsolePanels'
import type { AgentModelView } from '@/features/ai/catalog/AgentModelView'
import type { AgentProviderDTO } from '@/shared/api/contracts/ai-catalog'
import { setLocale } from '@/shared/i18n'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { PluginsTab } from '@/features/ai/plugins/PluginsTab'

setLocale(new URLSearchParams(location.search).get('locale') === 'en-US' ? 'en-US' : 'zh-CN')

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
  const params = new URLSearchParams(location.search)
  createRoot(rootEl).render(
    params.has('plugins') ? (
      <QueryClientProvider
        client={
          new QueryClient({
            defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
          })
        }
      >
        <main style={{ padding: 16 }}>
          <PluginsTab />
        </main>
      </QueryClientProvider>
    ) : params.has('forms') ? (
      <ResourceFormsHarness />
    ) : params.has('panels') ? (
      <PanelsHarness />
    ) : (
      <ResourceCardHarnessApp />
    ),
  )
}

const SAMPLE_MODEL: AgentModelView = {
  providerName: 'openai',
  name: 'gpt-4o',
  modelId: 'gpt-4o',
  description: 'OpenAI 旗舰多模态思考与生成模型',
  config: {
    limit: { context: 128000, output: 4096 },
    abilities: {
      tools: true,
      reasoning: true,
      inputModalities: ['TEXT', 'IMAGE'],
    },
    pricing: {
      currency: 'USD',
      pricingTier: 'default',
      serviceTier: 'default',
      serviceTierMultiplier: 1,
      version: 'v1',
      inputPerMillionTokens: 2.5,
      outputPerMillionTokens: 10,
      cacheReadPerMillionTokens: 1.25,
      cacheWritePerMillionTokens: 2.5,
      cacheWriteLongPerMillionTokens: 3.5,
      reasoningPerMillionTokens: 10,
    },
    defaultVariant: 'standard',
    variants: [
      { id: 'standard', reasoningEffort: 'HIGH' },
      { id: 'fast', reasoningEffort: 'low' },
    ],
  },
  version: '1',
  createTime: null,
  updateTime: null,
}

const LONG_MODEL: AgentModelView = {
  ...SAMPLE_MODEL,
  name: 'gpt-4o-extended',
  modelId: 'gpt-4o-extended-wire-long-identifier',
  description:
    '这是一段较长的模型描述文本，用于验证卡片在拥有更多业务元数据、多个变体标签以及长说明时能够按内容自然撑开高度，而不会受到固定高度限制。',
  config: {
    ...SAMPLE_MODEL.config,
    variants: [
      { id: 'variant-alpha-primary', reasoningEffort: 'HIGH' },
      { id: 'variant-beta-secondary', reasoningEffort: 'medium' },
      { id: 'variant-gamma-experimental', reasoningEffort: 'low' },
    ],
  },
}

const SAMPLE_PROVIDER: AgentProviderDTO = {
  name: 'openai',
  description: 'OpenAI 官方 API 服务提供方',
  providerType: 'OPENAI',
  baseUrl: 'https://api.openai.com/v1',
  configured: true,
  modelCallTimeoutMillis: 60000,
  modelCallIdleTimeoutMillis: 15000,
  modelHttpRetryStatusCodes: [429, 500, 502, 503],
}

function PanelsHarness() {
  const [lastAction, setLastAction] = useState('')

  return (
    <main style={{ padding: 24, display: 'flex', flexDirection: 'column', gap: 32 }}>
      <div id="action-log" data-last-action={lastAction}>
        {lastAction || 'idle'}
      </div>

      <section id="section-models-empty" aria-label="模型空列表">
        <h2>模型（空态）</h2>
        <ModelsPanel
          models={[]}
          deletePending={false}
          onCreate={() => setLastAction('create-model-empty')}
          onEdit={() => {}}
          onDelete={() => {}}
        />
      </section>

      <section id="section-models-populated" aria-label="模型列表">
        <h2>模型（有资源卡）</h2>
        <ModelsPanel
          models={[SAMPLE_MODEL, LONG_MODEL]}
          deletePending={false}
          onCreate={() => setLastAction('create-model-populated')}
          onEdit={() => {}}
          onDelete={() => {}}
        />
      </section>

      <section id="section-providers-empty" aria-label="供应商空列表">
        <h2>供应商（空态）</h2>
        <ProvidersPanel
          providers={[]}
          deletePending={false}
          onCreate={() => setLastAction('create-provider-empty')}
          onEdit={() => {}}
          onDelete={() => {}}
        />
      </section>

      <section id="section-providers-populated" aria-label="供应商列表">
        <h2>供应商（有资源卡）</h2>
        <ProvidersPanel
          providers={[SAMPLE_PROVIDER]}
          deletePending={false}
          onCreate={() => setLastAction('create-provider-populated')}
          onEdit={() => {}}
          onDelete={() => {}}
        />
      </section>
    </main>
  )
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
