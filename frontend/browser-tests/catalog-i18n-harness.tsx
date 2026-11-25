import { Button } from '@/shared/ui/controls/Button'
import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import { MemoryRouter } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import '@/styles.css'
import { useI18n, setLocale } from '@/shared/i18n'
import { ModelForm } from '@/features/catalog/AiModelResourceForm'
import { emptyModelDraft } from '@/features/catalog/ai-model-draft-codec'
import type { ModelDraft } from '@/features/catalog/ai-console-types'
import { SkillPackagesPage } from '@/features/catalog/skills/SkillPackagesPage'
import { agentService } from '@/shared/api/agent-service'
import type { SkillPackageDetailDTO } from '@/shared/api/generated/contracts'

setLocale('zh-CN')

const SAMPLE_PACKAGES: SkillPackageDetailDTO[] = [
  {
    packageName: 'core-tools',
    description: '核心开发技能集',
    packageVersion: '1111111111111111111111111111111111111111',
    publishedAt: 1784505600000,
    skills: [
      { name: 'dev', description: 'developer workflow' },
      { name: 'bash', description: 'bash execution' },
    ],
    source: 'GIT',
    git: {
      repositoryUrl: 'https://github.com/example/skills.git',
      branch: 'main',
      token: { configured: false },
    },
    headCommit: null,
    headCheckedAt: null,
    headCheckError: null,
    updateAvailable: false,
    artifactSha256: 'mock-sha256-core',
    artifactSizeBytes: '1024',
    version: '1',
    createdAt: 1784505600000,
    updatedAt: 1784509200000,
  },
  {
    packageName: 'browser-skills',
    description: 'Browser automation package',
    packageVersion: '2222222222222222222222222222222222222222',
    publishedAt: 1784505600000,
    skills: [{ name: 'opencli', description: 'browser cli' }],
    source: 'GIT',
    git: {
      repositoryUrl: 'https://github.com/example/browser.git',
      branch: 'main',
      token: { configured: true },
    },
    headCommit: '3333333333333333333333333333333333333333',
    headCheckedAt: 1784512800000,
    headCheckError: null,
    updateAvailable: true,
    artifactSha256: 'mock-sha256-browser',
    artifactSizeBytes: '2048',
    version: '2',
    createdAt: 1784505600000,
    updatedAt: 1784512800000,
  },
  {
    packageName: 'diagnostics-kit',
    description: 'Upstream error demo',
    packageVersion: '4444444444444444444444444444444444444444',
    publishedAt: 1784505600000,
    skills: [],
    source: 'GIT',
    git: {
      repositoryUrl: 'https://github.com/example/diag.git',
      branch: 'main',
      token: { configured: false },
    },
    headCommit: null,
    headCheckedAt: 1784516400000,
    headCheckError: 'fatal: repository not reachable',
    updateAvailable: false,
    artifactSha256: 'mock-sha256-diag',
    artifactSizeBytes: '512',
    version: '1',
    createdAt: 1784505600000,
    updatedAt: 1784516400000,
  },
]

// 本地化回归只读取固定数据，不允许弹窗取消或校验流程发起 mutation。
const SAMPLE_DETAILS = new Map(SAMPLE_PACKAGES.map((pkg) => [pkg.packageName, pkg]))
agentService.listSkillPackages = async () =>
  SAMPLE_PACKAGES.map(({ packageName, description, packageVersion, publishedAt, skills, source, updateAvailable, version, createdAt, updatedAt }) => ({
    packageName,
    description,
    packageVersion,
    publishedAt,
    skills,
    source,
    updateAvailable,
    version,
    createdAt,
    updatedAt,
  }))
agentService.getSkillPackage = async (name: string) => {
  const detail = SAMPLE_DETAILS.get(name)
  if (!detail) {
    throw new Error(`Unknown skill package: ${name}`)
  }
  return detail
}
agentService.listSkillPackageFiles = async () => [
  { path: 'dev/SKILL.md', size: '2048' },
  { path: 'dev/scripts/run.sh', size: '64' },
  { path: 'bash/SKILL.md', size: '512' },
]
const rejectMutation = async (): Promise<never> => {
  throw new Error('Unexpected mutation in catalog i18n harness')
}
agentService.createSkillPackage = rejectMutation
agentService.editSkillPackage = rejectMutation
agentService.deleteSkillPackage = rejectMutation
agentService.checkSkillPackage = rejectMutation
agentService.publishSkillPackage = rejectMutation
agentService.getSkillPackageArchiveUrl = rejectMutation
agentService.listAgents = async () => []

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
            promptCacheRetentions: ['NONE', 'SHORT', 'LONG'],
            baseUrl: null,
            credential: { configured: true },
            modelCallTimeoutMillis: '1800000',
            modelCallIdleTimeoutMillis: '120000',
            modelHttpRetryStatusCodes: null,
            version: '1',
            createdAt: 0,
            updatedAt: 0,
          },
        ]}
        onChange={setDraft}
      />
    </div>
  )
}

export function HarnessApp() {
  const { locale, setLocale } = useI18n()
  const [tab, setTab] = useState<'model' | 'skills'>('model')

  return (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/skill-packages']}>
        <div style={{ borderBottom: '1px solid #2d3732', padding: '12px 24px', display: 'flex', gap: '12px', alignItems: 'center' }}>
          <Button
            type="button"
            id="toggle-locale-btn"
            variant="secondary"
            onClick={() => setLocale(locale === 'zh-CN' ? 'en-US' : 'zh-CN')}
          >
            Locale: {locale} (Click to switch)
          </Button>
          <div style={{ display: 'flex', gap: '8px' }}>
            <Button
              type="button"
              id="tab-model-btn"
              variant={tab === 'model' ? 'primary' : 'secondary'}
              onClick={() => setTab('model')}
            >
              Model Form
            </Button>
            <Button
              type="button"
              id="tab-skills-btn"
              variant={tab === 'skills' ? 'primary' : 'secondary'}
              onClick={() => setTab('skills')}
            >
              Skill Packages
            </Button>
          </div>
        </div>

        <main>
          {tab === 'model' && <ModelFormDemo />}
          {tab === 'skills' && <SkillPackagesPage />}
        </main>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

const rootEl = document.getElementById('root')
if (rootEl) {
  createRoot(rootEl).render(<HarnessApp />)
}
