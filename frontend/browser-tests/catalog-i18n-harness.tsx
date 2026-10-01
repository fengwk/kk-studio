import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import { MemoryRouter } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import '@/styles.css'
import { useI18n, setLocale } from '@/shared/i18n'
import { AiNavigation } from '@/features/ai/extensions/AiNavigation'
import { ModelForm } from '@/features/ai/catalog/AiResourceForms'
import { emptyModelDraft } from '@/features/ai/catalog/ai-model-draft-codec'
import type { ModelDraft } from '@/features/ai/catalog/ai-console-types'
import { SkillPackagesPage } from '@/features/ai/skills/SkillPackagesPage'
import { agentService } from '@/shared/api/agent-service'
import type { SkillPackageDTO } from '@/shared/api/contracts/ai-catalog'

setLocale('zh-CN')

const SAMPLE_PACKAGES: SkillPackageDTO[] = [
  {
    packageName: 'core-tools',
    description: '核心开发技能集',
    repositoryUrl: 'https://github.com/example/skills.git',
    branch: 'main',
    currentCommit: '1111111111111111111111111111111111111111',
    observedHeadCommit: null,
    headCheckedAt: null,
    headCheckError: null,
    checkStatus: 'UNCHECKED',
    skills: [
      { name: 'dev', description: 'developer workflow' },
      { name: 'bash', description: 'bash execution' },
    ],
    version: '1',
    createTime: '2026-07-20T00:00:00.000Z',
    updateTime: '2026-07-20T01:00:00.000Z',
  },
  {
    packageName: 'browser-skills',
    description: 'Browser automation package',
    repositoryUrl: 'https://github.com/example/browser.git',
    branch: 'main',
    currentCommit: '2222222222222222222222222222222222222222',
    observedHeadCommit: '3333333333333333333333333333333333333333',
    headCheckedAt: '2026-07-20T02:00:00.000Z',
    headCheckError: null,
    checkStatus: 'UPDATE_AVAILABLE',
    skills: [{ name: 'opencli', description: 'browser cli' }],
    version: '2',
    createTime: '2026-07-20T00:00:00.000Z',
    updateTime: '2026-07-20T02:00:00.000Z',
  },
  {
    packageName: 'diagnostics-kit',
    description: 'Upstream error demo',
    repositoryUrl: 'https://github.com/example/diag.git',
    branch: 'main',
    currentCommit: '4444444444444444444444444444444444444444',
    observedHeadCommit: null,
    headCheckedAt: '2026-07-20T03:00:00.000Z',
    headCheckError: 'fatal: repository not reachable',
    checkStatus: 'CHECK_FAILED',
    skills: [],
    version: '1',
    createTime: '2026-07-20T00:00:00.000Z',
    updateTime: '2026-07-20T03:00:00.000Z',
  },
]

// 本地化回归只读取固定数据，不允许弹窗取消或校验流程发起 mutation。
agentService.listSkillPackages = async () => SAMPLE_PACKAGES
const rejectMutation = async (): Promise<never> => {
  throw new Error('Unexpected mutation in catalog i18n harness')
}
agentService.createSkillPackage = rejectMutation
agentService.editSkillPackage = rejectMutation
agentService.deleteSkillPackage = rejectMutation
agentService.checkSkillPackage = rejectMutation
agentService.publishSkillPackage = rejectMutation

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: false, gcTime: 0 },
  },
})

export function ModelFormDemo() {
  const [draft, setDraft] = useState<ModelDraft>({
    ...emptyModelDraft(),
    name: 'demo-model',
    modelId: 'demo-id',
    providerName: 'openai',
  })

  return (
    <div style={{ padding: '24px', maxWidth: '840px', margin: '0 auto' }}>
      <h2>Model Form Demo</h2>
      <ModelForm
        draft={draft}
        mode="create"
        providers={[
          {
            name: 'openai',
            description: null,
            providerType: 'openai',
            baseUrl: null,
            configured: true,
            modelCallTimeoutMillis: 1800000,
            modelCallIdleTimeoutMillis: 120000,
            createTime: '',
            updateTime: '',
          },
        ]}
        onChange={setDraft}
      />
    </div>
  )
}

export function HarnessApp() {
  const { locale, setLocale } = useI18n()
  const [tab, setTab] = useState<'model' | 'skills' | 'nav'>('model')

  return (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/skill-packages']}>
        <div style={{ borderBottom: '1px solid #2d3732', padding: '12px 24px', display: 'flex', gap: '12px', alignItems: 'center' }}>
          <button
            type="button"
            id="toggle-locale-btn"
            className="action-enter-btn"
            onClick={() => setLocale(locale === 'zh-CN' ? 'en-US' : 'zh-CN')}
          >
            Locale: {locale} (Click to switch)
          </button>
          <div style={{ display: 'flex', gap: '8px' }}>
            <button
              type="button"
              id="tab-model-btn"
              className={tab === 'model' ? 'btn-primary' : 'ghost-inline-btn'}
              onClick={() => setTab('model')}
            >
              Model Form
            </button>
            <button
              type="button"
              id="tab-skills-btn"
              className={tab === 'skills' ? 'btn-primary' : 'ghost-inline-btn'}
              onClick={() => setTab('skills')}
            >
              Skill Packages
            </button>
            <button
              type="button"
              id="tab-nav-btn"
              className={tab === 'nav' ? 'btn-primary' : 'ghost-inline-btn'}
              onClick={() => setTab('nav')}
            >
              Ai Navigation
            </button>
          </div>
        </div>

        <main>
          {tab === 'model' && <ModelFormDemo />}
          {tab === 'skills' && <SkillPackagesPage />}
          {tab === 'nav' && (
            <div style={{ padding: '24px' }}>
              <h2>Navigation Demo</h2>
              <AiNavigation />
            </div>
          )}
        </main>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

const rootEl = document.getElementById('root')
if (rootEl) {
  createRoot(rootEl).render(<HarnessApp />)
}
