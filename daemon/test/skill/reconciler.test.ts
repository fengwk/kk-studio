import { describe, it, expect, vi } from 'vitest'
import { create } from '@bufbuild/protobuf'

import {
  SkillPackageSchema,
  SkillPackageStateSchema,
  type SkillPackage,
  type SkillPackageState,
} from '../../src/generated/control_pb.js'
import { createSkillReconciler } from '../../src/skill/reconcile.js'
import type { SkillPackageInstaller } from '../../src/skill/installer.js'

describe('createSkillReconciler', () => {
  it('single-flight: set arriving mid-round runs exactly one more round with the latest set', async () => {
    const reports: SkillPackageState[][] = []
    let round1Resolve!: () => void
    const round1Hold = new Promise<void>((r) => {
      round1Resolve = r
    })
    let installCallCount = 0

    const installer = {
      skillsRoot: () => '/mock/skills',
      readMarker: vi.fn().mockResolvedValue(null),
      listInstalledPackages: vi.fn().mockResolvedValue([]),
      remove: vi.fn().mockResolvedValue(undefined),
      install: vi.fn().mockImplementation(async (packageName: string) => {
        installCallCount++
        if (packageName === 'pkg-round-1') {
          await round1Hold
        }
        return create(SkillPackageStateSchema, {
          packageName,
          installedSha256: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
          localPath: `/mock/skills/${packageName}`,
        })
      }),
    } as unknown as SkillPackageInstaller

    const reconciler = createSkillReconciler(installer, (states) => {
      reports.push(states)
    })

    // 1. Start round 1
    reconciler.onDesiredSet([
      create(SkillPackageSchema, {
        packageName: 'pkg-round-1',
        sha256: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
      }),
    ])

    // Wait a tick for round 1 to start and enter install()
    await new Promise((r) => setTimeout(r, 20))
    expect(installCallCount).toBe(1)

    // 2. While round 1 is blocked in install, send set 2, then set 3
    reconciler.onDesiredSet([
      create(SkillPackageSchema, {
        packageName: 'pkg-round-2',
        sha256: 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
      }),
    ])
    reconciler.onDesiredSet([
      create(SkillPackageSchema, {
        packageName: 'pkg-round-3',
        sha256: 'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
      }),
    ])

    // Release round 1
    round1Resolve()

    // Wait for reconciler to drain
    await new Promise((r) => setTimeout(r, 50))

    // Exactly two rounds should have completed: round 1 (pkg-round-1) and round 2 (pkg-round-3, skipping set 2)
    expect(reports.length).toBe(2)
    expect(reports[0]?.map((p) => p.packageName)).toEqual(['pkg-round-1'])
    expect(reports[1]?.map((p) => p.packageName)).toEqual(['pkg-round-3'])
    expect(installer.install).toHaveBeenCalledWith(
      'pkg-round-1',
      'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
      0n,
      '',
    )
    expect(installer.install).toHaveBeenCalledWith(
      'pkg-round-3',
      'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
      0n,
      '',
    )
    expect(installer.install).not.toHaveBeenCalledWith(
      'pkg-round-2',
      expect.anything(),
      expect.anything(),
      expect.anything(),
    )
  })

  it('removes stale marker directories not present in desired set', async () => {
    let installedList = [
      {
        packageName: 'stale-pkg',
        installedSha256: '1111111111111111111111111111111111111111111111111111111111111111',
        localPath: '/mock/skills/stale-pkg',
      },
      {
        packageName: 'keep-pkg',
        installedSha256: '2222222222222222222222222222222222222222222222222222222222222222',
        localPath: '/mock/skills/keep-pkg',
      },
    ]

    const removeFn = vi.fn().mockImplementation(async (pkgName: string) => {
      installedList = installedList.filter((p) => p.packageName !== pkgName)
    })

    const installer = {
      skillsRoot: () => '/mock/skills',
      readMarker: vi.fn().mockImplementation(async (pkgName: string) => {
        const found = installedList.find((p) => p.packageName === pkgName)
        return found ? found.installedSha256 : null
      }),
      listInstalledPackages: vi.fn().mockImplementation(async () => installedList),
      remove: removeFn,
      install: vi.fn(),
    } as unknown as SkillPackageInstaller

    const reported: SkillPackageState[][] = []
    const reconciler = createSkillReconciler(installer, (states) => {
      reported.push(states)
    })

    // Desired set only requests keep-pkg
    reconciler.onDesiredSet([
      create(SkillPackageSchema, {
        packageName: 'keep-pkg',
        sha256: '2222222222222222222222222222222222222222222222222222222222222222',
      }),
    ])

    await new Promise((r) => setTimeout(r, 50))

    expect(removeFn).toHaveBeenCalledWith('stale-pkg')
    expect(reported.length).toBe(1)
    expect(reported[0]?.map((p) => p.packageName)).toEqual(['keep-pkg'])
  })

  it('per-package failure continues subsequent packages and reports errors', async () => {
    const installer = {
      skillsRoot: () => '/mock/skills',
      readMarker: vi.fn().mockResolvedValue(null),
      listInstalledPackages: vi.fn().mockResolvedValue([]),
      remove: vi.fn().mockResolvedValue(undefined),
      install: vi.fn().mockImplementation(async (pkgName: string) => {
        if (pkgName === 'fail-pkg') {
          return create(SkillPackageStateSchema, {
            packageName: 'fail-pkg',
            error: 'Download timed out',
          })
        }
        return create(SkillPackageStateSchema, {
          packageName: 'success-pkg',
          installedSha256: '3333333333333333333333333333333333333333333333333333333333333333',
          localPath: '/mock/skills/success-pkg',
        })
      }),
    } as unknown as SkillPackageInstaller

    const reported: SkillPackageState[][] = []
    const reconciler = createSkillReconciler(installer, (states) => {
      reported.push(states)
    })

    reconciler.onDesiredSet([
      create(SkillPackageSchema, {
        packageName: 'fail-pkg',
        sha256: '4444444444444444444444444444444444444444444444444444444444444444',
      }),
      create(SkillPackageSchema, {
        packageName: 'success-pkg',
        sha256: '3333333333333333333333333333333333333333333333333333333333333333',
      }),
    ])

    await new Promise((r) => setTimeout(r, 50))

    expect(reported.length).toBe(1)
    const states = reported[0]!
    expect(states.length).toBe(2)
    const failState = states.find((s) => s.packageName === 'fail-pkg')
    const successState = states.find((s) => s.packageName === 'success-pkg')

    expect(failState?.error).toBe('Download timed out')
    expect(failState?.installedSha256).toBeUndefined()

    expect(successState?.error).toBeUndefined()
    expect(successState?.installedSha256).toBe(
      '3333333333333333333333333333333333333333333333333333333333333333',
    )
  })

  it('unions desired set with still-present unmanaged marker directories in final report', async () => {
    // A directory exists that is NOT in desired set, and its removal failed
    const persistentInstalled = [
      {
        packageName: 'stuck-pkg',
        installedSha256: '5555555555555555555555555555555555555555555555555555555555555555',
        localPath: '/mock/skills/stuck-pkg',
      },
    ]

    const installer = {
      skillsRoot: () => '/mock/skills',
      readMarker: vi.fn().mockImplementation(async (pkg: string) => {
        if (pkg === 'stuck-pkg') return '5555555555555555555555555555555555555555555555555555555555555555'
        return null
      }),
      listInstalledPackages: vi.fn().mockResolvedValue(persistentInstalled),
      remove: vi.fn().mockRejectedValue(new Error('EBUSY')),
      install: vi.fn().mockImplementation(async (packageName: string) => {
        return create(SkillPackageStateSchema, {
          packageName,
          installedSha256: '6666666666666666666666666666666666666666666666666666666666666666',
          localPath: `/mock/skills/${packageName}`,
        })
      }),
    } as unknown as SkillPackageInstaller

    const reported: SkillPackageState[][] = []
    const reconciler = createSkillReconciler(installer, (states) => {
      reported.push(states)
    })

    reconciler.onDesiredSet([
      create(SkillPackageSchema, {
        packageName: 'desired-pkg',
        sha256: '6666666666666666666666666666666666666666666666666666666666666666',
      }),
    ])

    await new Promise((r) => setTimeout(r, 50))

    expect(reported.length).toBe(1)
    const states = reported[0]!
    expect(states.length).toBe(2)
    const names = states.map((s) => s.packageName).sort()
    expect(names).toEqual(['desired-pkg', 'stuck-pkg'])
  })
})
