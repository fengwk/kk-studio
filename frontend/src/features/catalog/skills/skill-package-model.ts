import { ApiError } from '@/shared/api/client'
import type {
  SkillEnvironmentSyncDTO,
  SkillManifestEntryDTO,
  SkillPackageDetailDTO,
  SkillPackageFileDTO,
  SkillPackageSummaryDTO,
} from '@/shared/api/generated/contracts'
import { queryKeys } from '@/shared/lib/query-keys'
import type { AppLocale } from '@/shared/i18n'

/** 技能包相关查询统一挂在 skills.packages 下，写入后一次失效即可回读列表、详情与环境状态。 */
export const skillPackageQueryKeys = {
  list: queryKeys.skills.packages,
  detail: (packageName: string) => [...queryKeys.skills.packages, 'detail', packageName] as const,
  files: (packageName: string) => [...queryKeys.skills.packages, 'files', packageName] as const,
  environments: (packageName: string) =>
    [...queryKeys.skills.packages, 'environments', packageName] as const,
  agentSkillReferences: ['agents', 'skill-references'] as const,
}

/** Git 包的检查结论；观察值只存在于详情，列表摘要只携带 updateAvailable。 */
export type SkillCheckStatus = 'UNCHECKED' | 'UP_TO_DATE' | 'UPDATE_AVAILABLE' | 'CHECK_FAILED'

export function deriveCheckStatus(
  detail: Pick<
    SkillPackageDetailDTO,
    'headCommit' | 'headCheckedAt' | 'headCheckError' | 'updateAvailable'
  >,
): SkillCheckStatus {
  if (detail.headCheckError) {
    return 'CHECK_FAILED'
  }
  if (detail.updateAvailable) {
    return 'UPDATE_AVAILABLE'
  }
  if (detail.headCommit || detail.headCheckedAt) {
    return 'UP_TO_DATE'
  }
  return 'UNCHECKED'
}

/** 提交与摘要只展示前 10 位，完整值留给 title 与复制。 */
export function formatCommit(commit: string | null | undefined): string {
  if (!commit) {
    return '—'
  }
  return commit.length > 10 ? commit.slice(0, 10) : commit
}

/** 上传版本是用户填写的任意文本；Git 版本是提交 SHA，两者只在展示时区分。 */
export function displayPackageVersion(
  pkg: Pick<SkillPackageSummaryDTO, 'source' | 'packageVersion'>,
): string {
  return pkg.source === 'GIT' ? formatCommit(pkg.packageVersion) : pkg.packageVersion
}

export function isGitPackage(pkg: Pick<SkillPackageSummaryDTO, 'source'>): boolean {
  return pkg.source === 'GIT'
}

export function formatDateTime(timestamp: number, locale: AppLocale): string {
  return new Date(timestamp).toLocaleString(locale, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  })
}

/** 字节数来自 Java long 的十进制字符串；展示时只做可读换算，不参与业务判断。 */
export function formatBytes(value: string | number): string {
  const bytes = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(bytes) || bytes < 0) {
    return '—'
  }
  if (bytes < 1024) {
    return `${bytes} B`
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`
  }
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

/** 统一展示后端错误上下文中的 errors.detail；没有 detail 时才退回传输层 message。 */
export function errorDetail(error: unknown): string {
  if (error instanceof ApiError) {
    const detail = error.errors?.detail
    if (typeof detail === 'string' && detail.trim()) {
      return detail
    }
    return error.message
  }
  if (error instanceof Error) {
    return error.message
  }
  return String(error)
}

const PACKAGE_NAME = /^[a-z0-9]+(?:-[a-z0-9]+)*$/

export const MAX_PACKAGE_NAME_CHARS = 64
export const MAX_PACKAGE_VERSION_CHARS = 128
export const MAX_DESCRIPTION_CHARS = 1024

export function isValidPackageName(value: string): boolean {
  return value.length > 0 && value.length <= MAX_PACKAGE_NAME_CHARS && PACKAGE_NAME.test(value)
}

/** 版本由用户填写，只拒绝空值、超长与控制字符；具体含义由发布者决定。 */
export function isValidPackageVersion(value: string): boolean {
  return (
    value.length > 0
    && value.length <= MAX_PACKAGE_VERSION_CHARS
    && !/\p{Cc}/u.test(value)
  )
}

export function normalizeDescription(value: string): string | null {
  const trimmed = value.trim()
  return trimmed ? trimmed : null
}

export function isDescriptionWithinLimit(value: string): boolean {
  return value.trim().length <= MAX_DESCRIPTION_CHARS
}

export function isSupportedSkillArchive(filename: string): boolean {
  const lower = filename.toLowerCase()
  return lower.endsWith('.zip') || lower.endsWith('.tar.gz')
}

/** 环境同步状态：错误优先，其次按已安装摘要是否等于当前归档判断。 */
export type EnvironmentSyncStatus = 'SYNCED' | 'FAILED' | 'PENDING'

export function environmentSyncStatus(environment: SkillEnvironmentSyncDTO): EnvironmentSyncStatus {
  if (environment.error) {
    return 'FAILED'
  }
  if (environment.upToDate) {
    return 'SYNCED'
  }
  return 'PENDING'
}

export interface SkillTreeNode {
  name: string
  kind: 'directory' | 'file'
  /** 解压后字节数；目录与服务端未给出大小时为空。 */
  sizeBytes?: string
  /** 根目录下没有被识别为技能、因此未打包的目录。 */
  ignored?: boolean
  children?: SkillTreeNode[]
}

/** 路径排序只用于稳定呈现，不改变服务端给出的归档顺序语义。 */
function sortNodes(nodes: SkillTreeNode[]): SkillTreeNode[] {
  return [...nodes].sort((left, right) => {
    if (left.kind !== right.kind) {
      return left.kind === 'directory' ? -1 : 1
    }
    return left.name < right.name ? -1 : left.name > right.name ? 1 : 0
  })
}

/**
 * 由当前版本归档文件清单还原目录树：路径来自服务端 archive_index，
 * 前端只按 `/` 分层，不推断未返回的内容。
 */
export function buildFileTree(
  packageName: string,
  files: Pick<SkillPackageFileDTO, 'path' | 'size'>[],
): SkillTreeNode {
  const root: SkillTreeNode = { name: packageName, kind: 'directory', children: [] }
  const directories = new Map<string, SkillTreeNode>([[packageName, root]])
  for (const file of files) {
    const segments = file.path.split('/').filter(Boolean)
    if (segments.length === 0) {
      continue
    }
    let parent = root
    let path = packageName
    for (const segment of segments.slice(0, -1)) {
      path = `${path}/${segment}`
      let directory = directories.get(path)
      if (!directory) {
        directory = { name: segment, kind: 'directory', children: [] }
        directories.set(path, directory)
        parent.children!.push(directory)
      }
      parent = directory
    }
    parent.children!.push({
      name: segments[segments.length - 1],
      kind: 'file',
      sizeBytes: file.size,
    })
  }
  const sortRecursively = (node: SkillTreeNode): SkillTreeNode => ({
    ...node,
    children: node.children ? sortNodes(node.children).map(sortRecursively) : undefined,
  })
  return sortRecursively(root)
}

/**
 * 上传预览只有服务端清单，没有解压后的路径：每个技能一个顶层目录并带 SKILL.md，
 * 未打包目录单独标记。归档的真实文件树在详情页由文件清单还原。
 */
export function buildPreviewTree(
  packageName: string,
  skills: SkillManifestEntryDTO[],
  ignoredDirectories: string[] = [],
): SkillTreeNode {
  const skillNodes: SkillTreeNode[] = skills.map((skill) => ({
    name: skill.name,
    kind: 'directory',
    children: [{ name: 'SKILL.md', kind: 'file' }],
  }))
  const ignoredNodes: SkillTreeNode[] = ignoredDirectories.map((directory) => ({
    name: directory,
    kind: 'directory',
    ignored: true,
  }))
  return {
    name: packageName,
    kind: 'directory',
    children: [...skillNodes, ...ignoredNodes],
  }
}

export function manifestNames(skills: SkillManifestEntryDTO[]): Set<string> {
  return new Set(skills.map((skill) => skill.name))
}

export function addedSkills(
  current: SkillManifestEntryDTO[],
  next: SkillManifestEntryDTO[],
): SkillManifestEntryDTO[] {
  const currentNames = manifestNames(current)
  return next.filter((skill) => !currentNames.has(skill.name))
}

export function removedSkills(
  current: SkillManifestEntryDTO[],
  next: SkillManifestEntryDTO[],
): SkillManifestEntryDTO[] {
  const nextNames = manifestNames(next)
  return current.filter((skill) => !nextNames.has(skill.name))
}

/** Agent 技能引用的复合键，避免同名技能跨包撞键。 */
export function skillReferenceKey(packageName: string, skillName: string): string {
  return `${packageName}\u0000${skillName}`
}

/** 触发浏览器下载：瞬时挂载 anchor，预签名 URL 由服务端下发，前端不读取内容。 */
export function triggerDownload(url: string, filename: string): void {
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.rel = 'noreferrer'
  anchor.style.display = 'none'
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
}
