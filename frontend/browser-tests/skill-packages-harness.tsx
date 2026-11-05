import { createRoot } from 'react-dom/client'
import { MemoryRouter } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import '@/styles.css'
import { setLocale } from '@/shared/i18n'
import { ApiError } from '@/shared/api/client'
import { agentService } from '@/shared/api/agent-service'
import { storageService } from '@/shared/api/storage-service'
import { environmentService } from '@/features/environment/environment-service'
import { SkillPackagesPage } from '@/features/catalog/skills/SkillPackagesPage'
import type {
  EnvironmentCardDTO,
  SkillEnvironmentSyncDTO,
  SkillManifestEntryDTO,
  SkillPackageDetailDTO,
  SkillPackageFileDTO,
  SkillPackageGitCreateDTO,
  SkillPackagePreviewDTO,
  SkillPackageSummaryDTO,
  SkillPackageUploadPublishDTO,
} from '@/shared/api/generated/contracts'

setLocale('zh-CN')

const SEED_COMMIT = '1111111111111111111111111111111111111111'
const NEW_HEAD = '9999999999999999999999999999999999999999'
const SEEDED_AT = 1784505600

interface FakePackage {
  detail: SkillPackageDetailDTO
  files: SkillPackageFileDTO[]
  environments: SkillEnvironmentSyncDTO[]
}

const DEV_SKILLS: SkillManifestEntryDTO[] = [
  { name: 'dev', description: 'developer workflow' },
  { name: 'bash', description: 'bash execution' },
]

function summaryOf(detail: SkillPackageDetailDTO): SkillPackageSummaryDTO {
  return {
    packageName: detail.packageName,
    description: detail.description,
    packageVersion: detail.packageVersion,
    publishedAt: detail.publishedAt,
    skills: detail.skills,
    source: detail.source,
    updateAvailable: detail.updateAvailable,
    version: detail.version,
    createTime: detail.createTime,
    updateTime: detail.updateTime,
  }
}

function gitDetail(
  packageName: string,
  overrides: Partial<SkillPackageDetailDTO> = {},
): SkillPackageDetailDTO {
  return {
    packageName,
    description: '核心开发技能集',
    packageVersion: SEED_COMMIT,
    publishedAt: SEEDED_AT,
    skills: DEV_SKILLS,
    source: 'GIT',
    git: { repositoryUrl: `https://github.com/example/${packageName}.git`, branch: 'main', hasToken: false },
    headCommit: null,
    headCheckedAt: null,
    headCheckError: null,
    updateAvailable: false,
    artifactSha256: 'a'.repeat(64),
    artifactSizeBytes: '2048',
    version: '1',
    createTime: SEEDED_AT,
    updateTime: SEEDED_AT,
    ...overrides,
  }
}

const packages = new Map<string, FakePackage>([
  [
    'core-tools',
    {
      detail: gitDetail('core-tools', { headCommit: SEED_COMMIT, headCheckedAt: SEEDED_AT }),
      files: [
        { path: 'bash/SKILL.md', size: '512' },
        { path: 'dev/SKILL.md', size: '2048' },
        { path: 'dev/scripts/run.sh', size: '64' },
      ],
      environments: [],
    },
  ],
  [
    'browser-skills',
    {
      detail: gitDetail('browser-skills', {
        description: 'Browser automation package',
        skills: [{ name: 'opencli', description: 'browser cli' }],
        headCommit: NEW_HEAD,
        headCheckedAt: SEEDED_AT,
        updateAvailable: true,
        version: '2',
      }),
      files: [{ path: 'opencli/SKILL.md', size: '1024' }],
      environments: [],
    },
  ],
  [
    'agent-used',
    {
      detail: gitDetail('agent-used', { description: 'Referenced by an agent' }),
      files: [{ path: 'dev/SKILL.md', size: '2048' }],
      environments: [],
    },
  ],
  [
    'legacy-pack',
    {
      detail: {
        ...gitDetail('legacy-pack'),
        description: '上传渠道的历史包',
        source: 'UPLOAD',
        git: null,
        packageVersion: '2026.08.1',
        skills: [
          { name: 'dev', description: 'developer workflow' },
          { name: 'legacy', description: 'legacy skill' },
        ],
        headCheckedAt: null,
        version: '4',
      },
      files: [
        { path: 'dev/SKILL.md', size: '2048' },
        { path: 'legacy/SKILL.md', size: '640' },
      ],
      environments: [
        {
          environmentId: 'env-nas',
          installedSha256: 'a'.repeat(64),
          localPath: '/opt/skills/legacy-pack',
          error: null,
          upToDate: true,
        },
        {
          environmentId: 'env-nas-2',
          installedSha256: null,
          localPath: null,
          error: 'archive download timed out',
          upToDate: false,
        },
        {
          environmentId: 'env-lab',
          installedSha256: 'b'.repeat(64),
          localPath: '/srv/skills/legacy-pack',
          error: null,
          upToDate: false,
        },
      ],
    },
  ],
])

function environment(id: string, name: string, ready: boolean): EnvironmentCardDTO {
  return {
    id,
    name,
    installConfig: null,
    registrationToken: null,
    status: ready ? 'ONLINE' : 'OFFLINE',
    ready,
    statusExpiresAt: null,
    lastSeen: null,
    operatingSystem: null,
    timeZone: null,
    userName: null,
    homeDirectory: null,
    note: null,
    daemonVersion: null,
    capabilities: [],
    version: '1',
    createTime: 0,
    updateTime: 0,
  }
}

environmentService.listEnvironments = async () => [
  environment('env-nas', 'NAS', true),
  environment('env-nas-2', 'NAS 备份', true),
  environment('env-lab', '实验室', false),
]

/** 每个响应都拷一份，组件不会反向修改假后端状态。 */
function clone<T>(value: T): T {
  return structuredClone(value)
}

function requirePackage(packageName: string): FakePackage {
  const pkg = packages.get(packageName)
  if (!pkg) {
    throw new ApiError(`skill package not found: ${packageName}`, 404, 'resource_not_found', {
      detail: `Skill package ${packageName} not found`,
    })
  }
  return pkg
}

const delay = (ms = 40) => new Promise<void>((resolve) => setTimeout(resolve, ms))

agentService.listSkillPackages = async () =>
  Array.from(packages.values())
    .map((pkg) => clone(summaryOf(pkg.detail)))
    .sort((left, right) => left.packageName.localeCompare(right.packageName))

agentService.getSkillPackage = async (name: string) => {
  await delay(20)
  return clone(requirePackage(name).detail)
}

agentService.listSkillPackageFiles = async (name: string) => clone(requirePackage(name).files)

agentService.listSkillPackageEnvironments = async (name: string) =>
  clone(requirePackage(name).environments)

agentService.createSkillPackage = async (payload: SkillPackageGitCreateDTO) => {
  await delay(120)
  if (packages.has(payload.packageName)) {
    throw new ApiError('duplicate', 409, 'duplicate', {
      detail: `Skill package ${payload.packageName} already exists`,
    })
  }
  const detail = gitDetail(payload.packageName, {
    description: payload.description ?? null,
    packageVersion: SEED_COMMIT,
    git: { repositoryUrl: payload.repositoryUrl, branch: payload.branch, hasToken: Boolean(payload.token) },
    headCommit: null,
    headCheckedAt: null,
    version: '0',
  })
  packages.set(payload.packageName, {
    detail,
    files: clone([
      { path: 'bash/SKILL.md', size: '512' },
      { path: 'dev/SKILL.md', size: '2048' },
    ]),
    environments: [],
  })
  return clone(detail)
}

agentService.checkSkillPackage = async (name: string) => {
  await delay(120)
  const pkg = requirePackage(name)
  pkg.detail = {
    ...pkg.detail,
    headCommit: NEW_HEAD,
    headCheckedAt: Date.now(),
    headCheckError: null,
    updateAvailable: NEW_HEAD !== pkg.detail.packageVersion,
    version: String(Number(pkg.detail.version) + 1),
  }
  return clone(pkg.detail)
}

agentService.publishSkillPackage = async (name: string, data: { expectedVersion: string; targetCommit: string }) => {
  await delay(120)
  const pkg = requirePackage(name)
  if (data.expectedVersion !== pkg.detail.version) {
    throw new ApiError('version conflict', 409, 'version_conflict', {
      detail: 'The skill package was changed by someone else',
    })
  }
  pkg.detail = {
    ...pkg.detail,
    packageVersion: data.targetCommit,
    updateAvailable: false,
    publishedAt: Math.floor(Date.now() / 1000),
    version: String(Number(pkg.detail.version) + 1),
  }
  return clone(pkg.detail)
}

agentService.editSkillPackage = async (name: string, data: { expectedVersion: string; description?: string | null; branch?: string | null }) => {
  await delay(80)
  const pkg = requirePackage(name)
  if (data.expectedVersion !== pkg.detail.version) {
    throw new ApiError('version conflict', 409, 'version_conflict', {
      detail: 'The skill package was changed by someone else',
    })
  }
  pkg.detail = {
    ...pkg.detail,
    description: data.description ?? null,
    git: pkg.detail.git && data.branch ? { ...pkg.detail.git, branch: data.branch } : pkg.detail.git,
    version: String(Number(pkg.detail.version) + 1),
  }
  return clone(pkg.detail)
}

agentService.deleteSkillPackage = async (name: string, expectedVersion: string) => {
  await delay(80)
  const pkg = requirePackage(name)
  if (name === 'agent-used') {
    throw new ApiError('in use', 409, 'in_use', {
      detail: 'Skill package is still referenced by Agent demo-agent',
    })
  }
  if (expectedVersion !== pkg.detail.version) {
    throw new ApiError('version conflict', 409, 'version_conflict', {
      detail: 'The skill package was changed by someone else',
    })
  }
  packages.delete(name)
}

const PREVIEW_SKILLS: SkillManifestEntryDTO[] = [
  { name: 'browser', description: 'browser automation' },
  { name: 'dev', description: 'developer workflow' },
]

const PREVIEW_FILES: SkillPackageFileDTO[] = [
  { path: 'browser/SKILL.md', size: '1024' },
  { path: 'browser/scripts/open.ts', size: '2048' },
  { path: 'dev/SKILL.md', size: '2048' },
]

const staged = new Map<string, { packageName: string; packageVersion: string; description: string | null }>()

agentService.previewSkillPackageUpload = async (payload) => {
  await delay(200)
  const preview: SkillPackagePreviewDTO = {
    stagingUploadId: 'stage-1',
    packageName: payload.packageName,
    packageVersion: payload.packageVersion,
    description: payload.description ?? null,
    skills: clone(PREVIEW_SKILLS),
    ignoredDirectories: ['docs'],
    archiveSha256: 'c'.repeat(64),
    archiveSizeBytes: '4096',
    contentSizeBytes: '16384',
    fileCount: 3,
  }
  staged.set(preview.stagingUploadId, {
    packageName: preview.packageName,
    packageVersion: preview.packageVersion,
    description: preview.description ?? null,
  })
  return preview
}

agentService.publishSkillPackageUpload = async (payload: SkillPackageUploadPublishDTO) => {
  await delay(160)
  const pending = staged.get(payload.stagingUploadId)
  if (!pending) {
    throw new ApiError('staging upload expired', 409, 'version_conflict', {
      detail: 'The staged archive has expired',
    })
  }
  if (pending.packageName === 'agent-used') {
    throw new ApiError('in use', 409, 'in_use', {
      detail: 'Skill package is still referenced by Agent demo-agent',
    })
  }
  const existing = packages.get(pending.packageName)
  if (existing && payload.expectedVersion !== existing.detail.version) {
    throw new ApiError('version conflict', 409, 'version_conflict', {
      detail: 'The skill package was changed by someone else',
    })
  }
  const detail: SkillPackageDetailDTO = {
    ...gitDetail(pending.packageName),
    description: pending.description,
    source: 'UPLOAD',
    git: null,
    packageVersion: pending.packageVersion,
    skills: clone(PREVIEW_SKILLS),
    headCheckError: null,
    headCheckedAt: null,
    updateAvailable: false,
    version: existing ? String(Number(existing.detail.version) + 1) : '0',
    publishedAt: Math.floor(Date.now() / 1000),
  }
  packages.set(pending.packageName, {
    detail,
    files: clone(PREVIEW_FILES),
    environments: [
      {
        environmentId: 'env-nas',
        installedSha256: 'c'.repeat(64),
        localPath: '/opt/skills/browser',
        error: null,
        upToDate: true,
      },
      {
        environmentId: 'env-nas-2',
        installedSha256: null,
        localPath: null,
        error: 'archive download timed out',
        upToDate: false,
      },
      {
        environmentId: 'env-lab',
        installedSha256: 'd'.repeat(64),
        localPath: '/srv/skills/browser',
        error: null,
        upToDate: false,
      },
    ],
  })
  staged.delete(payload.stagingUploadId)
  return clone(detail)
}

agentService.cancelSkillPackageUpload = async (stagingUploadId: string) => {
  staged.delete(stagingUploadId)
}

agentService.getSkillPackageArchiveUrl = async (name: string) => {
  const counter = Number((window as unknown as Record<string, unknown>).__skillArchiveRequests ?? 0) + 1
  ;(window as unknown as Record<string, unknown>).__skillArchiveRequests = counter
  return {
    filename: `${name}-${SEED_COMMIT.slice(0, 7)}.zip`,
    sha256: 'a'.repeat(64),
    sizeBytes: '4096',
    download: {
      method: 'GET',
      url: 'data:application/zip;base64,UEsFBgAAAAAAAAAAAAAAAAAAAAAAAA==',
      headers: {},
      expiresAt: String(Date.now() + 900_000),
      mediaType: 'application/zip',
      sizeBytes: '4096',
    },
  }
}

agentService.resyncEnvironmentSkills = async () => undefined

agentService.listAgents = async () => ({
  pageNumber: 1,
  pageSize: 100,
  totalCount: '1',
  results: [
    {
      name: 'demo-agent',
      type: 'USER' as const,
      description: null,
      systemPrompt: null,
      execution: null,
      config: { tools: [], skills: [{ packageName: 'legacy-pack', name: 'legacy' }], subagents: [] },
      version: '1',
      createTime: 0,
      updateTime: 0,
    },
  ],
})

// 上传直传由可控的 XHR 替身驱动：只在浏览器内模拟进度事件，服务端校验仍由预览接口承担。
storageService.reserveUpload = async () => ({
  id: 'upload-1',
  state: 'PENDING',
  blobId: null,
  presignedPut: { method: 'PUT', url: 'http://harness.local/upload', headers: {} },
  expiresAt: Date.now() + 900_000,
})
storageService.completeUpload = async (uploadId: string) => ({
  id: uploadId,
  state: 'READY',
  blobId: 'blob-1',
  presignedPut: null,
  expiresAt: Date.now() + 900_000,
})

interface ProgressEventLike {
  loaded: number
  total: number
  lengthComputable: boolean
}

class ProgressXhr {
  static readonly STEP_MS = 100
  static readonly STEPS = 10

  upload: { onprogress: ((event: ProgressEventLike) => void) | null } = { onprogress: null }
  onload: (() => void) | null = null
  onerror: (() => void) | null = null
  onabort: (() => void) | null = null
  status = 0

  open() {
    return undefined
  }

  setRequestHeader() {
    return undefined
  }

  abort() {
    this.onabort?.()
  }

  send(body: File) {
    const total = Math.max(body.size, 4)
    const chunk = Math.max(1, Math.ceil(total / ProgressXhr.STEPS))
    let loaded = 0
    const tick = () => {
      loaded = Math.min(total, loaded + chunk)
      this.upload.onprogress?.({ loaded, total, lengthComputable: true })
      if (loaded < total) {
        setTimeout(tick, ProgressXhr.STEP_MS)
        return
      }
      this.status = 200
      this.onload?.()
    }
    setTimeout(tick, ProgressXhr.STEP_MS)
  }
}

window.XMLHttpRequest = ProgressXhr as unknown as typeof XMLHttpRequest

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false, gcTime: 0 } },
})

const rootEl = document.getElementById('root')
if (rootEl) {
  createRoot(rootEl).render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/skill-packages']}>
        <SkillPackagesPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}
