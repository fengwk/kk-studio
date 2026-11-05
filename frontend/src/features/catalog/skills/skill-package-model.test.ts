import { describe, expect, it } from 'vitest'
import { ApiError } from '@/shared/api/client'
import type { SkillPackageDetailDTO, SkillPackageFileDTO } from '@/shared/api/generated/contracts'
import {
  addedSkills,
  buildFileTree,
  buildPreviewTree,
  deriveCheckStatus,
  displayPackageVersion,
  environmentSyncStatus,
  errorDetail,
  formatBytes,
  formatCommit,
  formatDateTime,
  isDescriptionWithinLimit,
  isSupportedSkillArchive,
  isValidPackageName,
  isValidPackageVersion,
  normalizeDescription,
  removedSkills,
  type SkillTreeNode,
} from '@/features/catalog/skills/skill-package-model'

function file(path: string, size = '10'): SkillPackageFileDTO {
  return { path, size }
}

/** 把树压平成 “路径:类型” 便于断言结构与排序。 */
function flatten(node: SkillTreeNode, prefix = '', depth = 0): string[] {
  const name = depth === 0 ? node.name : `${prefix}/${node.name}`
  const lines = [`${name}:${node.kind}`]
  for (const child of node.children ?? []) {
    lines.push(...flatten(child, name, depth + 1))
  }
  return lines
}

describe('skill package model', () => {
  it('restores the archive tree from server file paths with directories first', () => {
    const tree = buildFileTree('core-tools', [
      file('dev/SKILL.md', '2048'),
      file('dev/scripts/run.sh', '64'),
      file('browser/SKILL.md'),
      file('README.md'),
    ])

    expect(flatten(tree)).toEqual([
      'core-tools:directory',
      'core-tools/browser:directory',
      'core-tools/browser/SKILL.md:file',
      'core-tools/dev:directory',
      'core-tools/dev/scripts:directory',
      'core-tools/dev/scripts/run.sh:file',
      'core-tools/dev/SKILL.md:file',
      'core-tools/README.md:file',
    ])
    expect(tree.children?.[1].children?.[1]).toMatchObject({
      name: 'SKILL.md',
      kind: 'file',
      sizeBytes: '2048',
    })
  })

  it('ignores empty path segments instead of inventing archive content', () => {
    const tree = buildFileTree('core-tools', [file('/'), file('dev/SKILL.md')])
    expect(flatten(tree)).toEqual(['core-tools:directory', 'core-tools/dev:directory', 'core-tools/dev/SKILL.md:file'])
  })

  it('builds the upload preview tree from the manifest and marks ignored directories', () => {
    const tree = buildPreviewTree(
      'core-tools',
      [{ name: 'dev', description: 'developer workflow' }],
      ['docs'],
    )
    expect(flatten(tree)).toEqual([
      'core-tools:directory',
      'core-tools/dev:directory',
      'core-tools/dev/SKILL.md:file',
      'core-tools/docs:directory',
    ])
    expect(tree.children?.[1].ignored).toBe(true)
  })

  it('derives added and removed skills from the published manifest', () => {
    const current = [{ name: 'dev', description: '' }, { name: 'old', description: '' }]
    const next = [{ name: 'dev', description: '' }, { name: 'new', description: '' }]

    expect(addedSkills(current, next).map((skill) => skill.name)).toEqual(['new'])
    expect(removedSkills(current, next).map((skill) => skill.name)).toEqual(['old'])
  })

  it('reports every check status from detail fields', () => {
    const base: Pick<
      SkillPackageDetailDTO,
      'headCommit' | 'headCheckedAt' | 'headCheckError' | 'updateAvailable'
    > = {
      headCommit: null,
      headCheckedAt: null,
      headCheckError: null,
      updateAvailable: false,
    }

    expect(deriveCheckStatus(base)).toBe('UNCHECKED')
    expect(deriveCheckStatus({ ...base, headCommit: 'a'.repeat(40) })).toBe('UP_TO_DATE')
    expect(deriveCheckStatus({ ...base, headCheckedAt: 1 })).toBe('UP_TO_DATE')
    expect(deriveCheckStatus({ ...base, updateAvailable: true })).toBe('UPDATE_AVAILABLE')
    expect(deriveCheckStatus({ ...base, updateAvailable: true, headCheckError: 'boom' })).toBe('CHECK_FAILED')
  })

  it('formats identifiers, versions and byte sizes for display only', () => {
    expect(formatCommit(null)).toBe('—')
    expect(formatCommit('0123456789abcdef')).toBe('0123456789')
    expect(displayPackageVersion({ source: 'GIT', packageVersion: 'a'.repeat(40) })).toBe('aaaaaaaaaa')
    expect(displayPackageVersion({ source: 'UPLOAD', packageVersion: '2026.08.1' })).toBe('2026.08.1')
    expect(formatBytes('512')).toBe('512 B')
    expect(formatBytes(2048)).toBe('2.0 KB')
    expect(formatBytes('1572864')).toBe('1.5 MB')
    expect(formatBytes('not-a-size')).toBe('—')
    // wire 上的 Instant 是 epoch 秒：按毫秒解析会退化成 1970 年。
    expect(formatDateTime(1784505600, 'en-US')).toContain('2026')
  })

  it('validates package names, versions, descriptions and archive extensions', () => {
    expect(isValidPackageName('my-skills')).toBe(true)
    expect(isValidPackageName('My-Skills')).toBe(false)
    expect(isValidPackageName('-leading')).toBe(false)
    expect(isValidPackageName('double--hyphen')).toBe(false)
    expect(isValidPackageName('a'.repeat(65))).toBe(false)

    expect(isValidPackageVersion('2026.08.1')).toBe(true)
    expect(isValidPackageVersion('')).toBe(false)
    expect(isValidPackageVersion('line\nbreak')).toBe(false)
    expect(isValidPackageVersion('v'.repeat(129))).toBe(false)

    expect(isDescriptionWithinLimit('x'.repeat(1024))).toBe(true)
    expect(isDescriptionWithinLimit('x'.repeat(1025))).toBe(false)
    expect(normalizeDescription('  multi\nline  ')).toBe('multi\nline')
    expect(normalizeDescription('   ')).toBeNull()

    expect(isSupportedSkillArchive('skills.zip')).toBe(true)
    expect(isSupportedSkillArchive('skills.tar.gz')).toBe(true)
    expect(isSupportedSkillArchive('skills.tar')).toBe(false)
  })

  it('maps environment sync projections to failure, synced and pending', () => {
    expect(
      environmentSyncStatus({
        environmentId: 'env-1',
        installedSha256: 'sha',
        localPath: '/skills',
        error: null,
        upToDate: true,
      }),
    ).toBe('SYNCED')
    expect(
      environmentSyncStatus({
        environmentId: 'env-1',
        installedSha256: null,
        localPath: null,
        error: null,
        upToDate: false,
      }),
    ).toBe('PENDING')
    expect(
      environmentSyncStatus({
        environmentId: 'env-1',
        installedSha256: null,
        localPath: null,
        error: 'install failed',
        upToDate: false,
      }),
    ).toBe('FAILED')
  })

  it('prefers the backend errors.detail over the transport message', () => {
    expect(
      errorDetail(new ApiError('Request failed with status 409', 409, 'in_use', { detail: 'Skill package is in use by Agent demo' })),
    ).toBe('Skill package is in use by Agent demo')
    expect(errorDetail(new ApiError('Request failed with status 500', 500))).toBe(
      'Request failed with status 500',
    )
    expect(errorDetail('plain failure')).toBe('plain failure')
  })
})
