import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { randomUUID } from 'node:crypto'
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import git from 'isomorphic-git'
import {
  SkillPackageInstaller,
  assertRealDirectorySync,
  createParentDirectoriesOwnerOnly,
  writeFileOwnerOnly,
  createOwnerOnlyDirectory,
  validatePackageEntryPath,
  validateTargetCommit,
  validatePackageName,
  validateBranch,
  validateRepositoryUrl,
} from '../../src/skill/installer.js'
import { SkillSyncException } from '../../src/skill/types.js'

describe('SkillPackageInstaller', () => {
  let tempDir: string
  let remoteRepoDir: string
  let skillsRoot: string
  let stagingRoot: string
  let backupRoot: string
  let installer: SkillPackageInstaller

  beforeEach(async () => {
    tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'skill-inst-test-'))
    remoteRepoDir = path.join(tempDir, 'remote-repo')
    skillsRoot = path.join(tempDir, 'skills')
    stagingRoot = path.join(tempDir, 'staging')
    backupRoot = path.join(tempDir, 'backup')

    installer = new SkillPackageInstaller(skillsRoot, stagingRoot, backupRoot)
  })

  afterEach(async () => {
    try {
      fs.rmSync(tempDir, { recursive: true, force: true })
    } catch {}
  })

  async function createFixtureRepo(): Promise<{ commit1: string; commit2: string }> {
    await fs.promises.mkdir(remoteRepoDir, { recursive: true })
    await git.init({ fs, dir: remoteRepoDir, defaultBranch: 'main' })

    const skillDir1 = path.join(remoteRepoDir, 'weather-skill')
    await fs.promises.mkdir(skillDir1, { recursive: true })
    await fs.promises.writeFile(path.join(skillDir1, 'SKILL.md'), '# Weather Skill\nVersion 1.0\n')
    await git.add({ fs, dir: remoteRepoDir, filepath: 'weather-skill/SKILL.md' })
    const commit1 = await git.commit({
      fs,
      dir: remoteRepoDir,
      author: { name: 'test', email: 'test@example.com' },
      message: 'initial commit',
    })

    await fs.promises.writeFile(path.join(skillDir1, 'SKILL.md'), '# Weather Skill\nVersion 2.0\n')
    await git.add({ fs, dir: remoteRepoDir, filepath: 'weather-skill/SKILL.md' })
    const commit2 = await git.commit({
      fs,
      dir: remoteRepoDir,
      author: { name: 'test', email: 'test@example.com' },
      message: 'second commit',
    })

    return { commit1, commit2 }
  }

  it('exact commit install produces expected tree and metadata (non-branch-head commit)', async () => {
    const { commit1 } = await createFixtureRepo()
    const remoteUrl = pathToFileURL(remoteRepoDir).toString()

    const installed = await installer.install('weather-package', remoteUrl, 'main', commit1)

    expect(installed.packageName).toBe('weather-package')
    expect(installed.installedCommit).toBe(commit1)
    const expectedPackageRoot = path.join(skillsRoot, 'weather-package')
    expect(installed.localPath).toBe(expectedPackageRoot)

    const skillFile = path.join(expectedPackageRoot, 'weather-skill', 'SKILL.md')
    expect(fs.existsSync(skillFile)).toBe(true)
    expect(fs.readFileSync(skillFile, 'utf8')).toBe('# Weather Skill\nVersion 1.0\n')

    const commitFile = path.join(expectedPackageRoot, '.kkstudio-commit')
    expect(fs.existsSync(commitFile)).toBe(true)
    expect(fs.readFileSync(commitFile, 'utf8')).toBe(`${commit1}\n`)
  })

  it('installing newer commit replaces existing tree', async () => {
    const { commit1, commit2 } = await createFixtureRepo()
    const remoteUrl = pathToFileURL(remoteRepoDir).toString()

    await installer.install('replace-pkg', remoteUrl, 'main', commit1)
    const pkgDir = path.join(skillsRoot, 'replace-pkg')
    const skillFile = path.join(pkgDir, 'weather-skill', 'SKILL.md')
    expect(fs.readFileSync(skillFile, 'utf8')).toBe('# Weather Skill\nVersion 1.0\n')

    const installed2 = await installer.install('replace-pkg', remoteUrl, 'main', commit2)
    expect(installed2.packageName).toBe('replace-pkg')
    expect(installed2.installedCommit).toBe(commit2)
    expect(fs.readFileSync(skillFile, 'utf8')).toBe('# Weather Skill\nVersion 2.0\n')
    expect(fs.readFileSync(path.join(pkgDir, '.kkstudio-commit'), 'utf8')).toBe(`${commit2}\n`)

    assertDirectoryEmpty(stagingRoot)
    assertDirectoryEmpty(backupRoot)
  })

  it('idempotent reinstall of same commit works and replaces prior content', async () => {
    const { commit1 } = await createFixtureRepo()
    const remoteUrl = pathToFileURL(remoteRepoDir).toString()

    await installer.install('idempotent-pkg', remoteUrl, 'main', commit1)
    const installedAgain = await installer.install('idempotent-pkg', remoteUrl, 'main', commit1)

    expect(installedAgain.packageName).toBe('idempotent-pkg')
    expect(installedAgain.installedCommit).toBe(commit1)
    const skillFile = path.join(skillsRoot, 'idempotent-pkg', 'weather-skill', 'SKILL.md')
    expect(fs.readFileSync(skillFile, 'utf8')).toBe('# Weather Skill\nVersion 1.0\n')

    assertDirectoryEmpty(stagingRoot)
    assertDirectoryEmpty(backupRoot)
  })

  it('commit with symlink is rejected with UNSAFE_PACKAGE_ENTRY and preserves previous install', async () => {
    const { commit1 } = await createFixtureRepo()
    const remoteUrl = pathToFileURL(remoteRepoDir).toString()

    await installer.install('symlink-pkg', remoteUrl, 'main', commit1)
    const pkgDir = path.join(skillsRoot, 'symlink-pkg')
    expect(fs.existsSync(path.join(pkgDir, 'weather-skill', 'SKILL.md'))).toBe(true)

    // Add a symlink commit
    const blobOid = await git.writeBlob({
      fs,
      dir: remoteRepoDir,
      blob: Buffer.from('weather-skill/SKILL.md'),
    })
    const treeOid = await git.writeTree({
      fs,
      dir: remoteRepoDir,
      tree: [
        {
          mode: '120000',
          path: 'bad-symlink',
          oid: blobOid,
          type: 'blob',
        },
      ],
    })
    const symCommit = await git.writeCommit({
      fs,
      dir: remoteRepoDir,
      commit: {
        message: 'symlink commit',
        tree: treeOid,
        parent: [commit1],
        author: { name: 'test', email: 'test@example.com', timestamp: 0, timezoneOffset: 0 },
        committer: { name: 'test', email: 'test@example.com', timestamp: 0, timezoneOffset: 0 },
      },
    })

    await expect(
      installer.install('symlink-pkg', remoteUrl, 'main', symCommit),
    ).rejects.toMatchObject({
      code: 'UNSAFE_PACKAGE_ENTRY',
      message: 'Symbolic links are not allowed in skill package',
    })

    // Assert previous install is byte-for-byte preserved
    expect(fs.readFileSync(path.join(pkgDir, 'weather-skill', 'SKILL.md'), 'utf8')).toBe(
      '# Weather Skill\nVersion 1.0\n',
    )
    expect(fs.readFileSync(path.join(pkgDir, '.kkstudio-commit'), 'utf8')).toBe(`${commit1}\n`)

    assertDirectoryEmpty(stagingRoot)
    assertDirectoryEmpty(backupRoot)
  })

  it('commit with submodule is rejected with UNSAFE_PACKAGE_ENTRY', async () => {
    await fs.promises.mkdir(remoteRepoDir, { recursive: true })
    await git.init({ fs, dir: remoteRepoDir, defaultBranch: 'main' })

    const treeOid = await git.writeTree({
      fs,
      dir: remoteRepoDir,
      tree: [
        {
          mode: '160000',
          path: 'submodule-entry',
          oid: '0123456789012345678901234567890123456789',
          type: 'commit',
        },
      ],
    })
    const subCommit = await git.writeCommit({
      fs,
      dir: remoteRepoDir,
      commit: {
        message: 'submodule commit',
        tree: treeOid,
        parent: [],
        author: { name: 'test', email: 'test@example.com', timestamp: 0, timezoneOffset: 0 },
        committer: { name: 'test', email: 'test@example.com', timestamp: 0, timezoneOffset: 0 },
      },
    })
    await git.writeRef({ fs, dir: remoteRepoDir, ref: 'refs/heads/main', value: subCommit })

    const remoteUrl = pathToFileURL(remoteRepoDir).toString()
    await expect(
      installer.install('submodule-pkg', remoteUrl, 'main', subCommit),
    ).rejects.toMatchObject({
      code: 'UNSAFE_PACKAGE_ENTRY',
      message: 'Submodules are not allowed in skill package',
    })

    assertDirectoryEmpty(stagingRoot)
    assertDirectoryEmpty(backupRoot)
  })

  it('fetch failure preserves previous install and leaves no artifacts', async () => {
    const { commit1 } = await createFixtureRepo()
    const remoteUrl = pathToFileURL(remoteRepoDir).toString()

    await installer.install('persist-pkg', remoteUrl, 'main', commit1)
    const pkgDir = path.join(skillsRoot, 'persist-pkg')

    const unknownCommit = '0123456789012345678901234567890123456789'
    await expect(
      installer.install('persist-pkg', remoteUrl, 'main', unknownCommit),
    ).rejects.toMatchObject({
      code: 'COMMIT_NOT_FOUND',
      message: 'Target commit could not be resolved from remote repository',
    })

    await expect(
      installer.install('persist-pkg', remoteUrl, 'missing-branch', unknownCommit),
    ).rejects.toMatchObject({
      code: 'GIT_FETCH_FAILED',
      message: 'Git branch fetch failed',
    })

    // Previous install is preserved
    expect(fs.readFileSync(path.join(pkgDir, 'weather-skill', 'SKILL.md'), 'utf8')).toBe(
      '# Weather Skill\nVersion 1.0\n',
    )
    expect(fs.readFileSync(path.join(pkgDir, '.kkstudio-commit'), 'utf8')).toBe(`${commit1}\n`)

    assertDirectoryEmpty(stagingRoot)
    assertDirectoryEmpty(backupRoot)
  })

  it('cache from another repository is never trusted', async () => {
    const { commit1: oldCommitId } = await createFixtureRepo()
    const anotherRepoDir = path.join(tempDir, 'another-repo')
    await fs.promises.mkdir(anotherRepoDir, { recursive: true })
    await git.init({ fs, dir: anotherRepoDir, defaultBranch: 'main' })

    const skillDir = path.join(anotherRepoDir, 'new-skill')
    await fs.promises.mkdir(skillDir, { recursive: true })
    await fs.promises.writeFile(path.join(skillDir, 'SKILL.md'), '# New Skill\n')
    await git.add({ fs, dir: anotherRepoDir, filepath: 'new-skill/SKILL.md' })
    const newCommitId = await git.commit({
      fs,
      dir: anotherRepoDir,
      author: { name: 'test', email: 'test@example.com' },
      message: 'new repo commit',
    })

    const oldUrl = pathToFileURL(remoteRepoDir).toString()
    const newUrl = pathToFileURL(anotherRepoDir).toString()

    await installer.install('rotated-pkg', oldUrl, 'main', oldCommitId)
    const pkgDir = path.join(skillsRoot, 'rotated-pkg')
    expect(fs.existsSync(path.join(pkgDir, 'weather-skill', 'SKILL.md'))).toBe(true)

    // Installing old commit against new repo must fail
    await expect(
      installer.install('rotated-pkg', newUrl, 'main', oldCommitId),
    ).rejects.toMatchObject({
      code: 'COMMIT_NOT_FOUND',
    })

    expect(fs.existsSync(path.join(pkgDir, 'weather-skill', 'SKILL.md'))).toBe(true)
    expect(fs.readFileSync(path.join(pkgDir, '.kkstudio-commit'), 'utf8')).toBe(`${oldCommitId}\n`)

    // Installing new commit succeeds
    const installed = await installer.install('rotated-pkg', newUrl, 'main', newCommitId)
    expect(installed.installedCommit).toBe(newCommitId)
    expect(fs.existsSync(path.join(pkgDir, 'weather-skill'))).toBe(false)
    expect(fs.readFileSync(path.join(pkgDir, 'new-skill', 'SKILL.md'), 'utf8')).toBe('# New Skill\n')
    expect(fs.readFileSync(path.join(pkgDir, '.kkstudio-commit'), 'utf8')).toBe(`${newCommitId}\n`)

    assertDirectoryEmpty(stagingRoot)
    assertDirectoryEmpty(backupRoot)
  })

  it('recoverArtifacts cleans staging and restores missing target from backup', async () => {
    // 1. Leftover staging
    const leftoverStaging = path.join(stagingRoot, `leftover.${randomUUID()}`)
    fs.mkdirSync(leftoverStaging, { recursive: true })
    fs.writeFileSync(path.join(leftoverStaging, 'temp.txt'), 'leftover')

    // 2. Restorable backup (missing in skills)
    const restorableUuid = randomUUID()
    const restorableBackup = path.join(backupRoot, `restored-pkg.${restorableUuid}`)
    fs.mkdirSync(restorableBackup, { recursive: true })
    fs.writeFileSync(path.join(restorableBackup, 'recovered.txt'), 'good')

    // 3. Existing package backup (already exists in skills, should be cleaned up)
    const existingPackage = path.join(skillsRoot, 'existing-pkg')
    fs.mkdirSync(existingPackage, { recursive: true })
    fs.writeFileSync(path.join(existingPackage, 'current.txt'), 'current')

    const existingBackup = path.join(backupRoot, `existing-pkg.${randomUUID()}`)
    fs.mkdirSync(existingBackup, { recursive: true })
    fs.writeFileSync(path.join(existingBackup, 'old.txt'), 'old')

    // 4. Corrupted file
    const corruptBackup = path.join(backupRoot, 'corrupted-file.bin')
    fs.writeFileSync(corruptBackup, 'garbage')

    installer.recoverArtifacts()

    assertDirectoryEmpty(stagingRoot)
    assertDirectoryEmpty(backupRoot)

    const restoredDir = path.join(skillsRoot, 'restored-pkg')
    expect(fs.existsSync(restoredDir)).toBe(true)
    expect(fs.readFileSync(path.join(restoredDir, 'recovered.txt'), 'utf8')).toBe('good')

    expect(fs.existsSync(existingPackage)).toBe(true)
    expect(fs.readFileSync(path.join(existingPackage, 'current.txt'), 'utf8')).toBe('current')
  })

  it('invalid arguments rejected without filesystem side effects', async () => {
    const validUrl = 'https://example.com/repo.git'
    const validCommit = '0123456789012345678901234567890123456789'
    const validBranch = 'main'

    // Bad package names
    const badPackages = [
      null,
      '',
      ' ',
      'bad/name',
      'bad\\name',
      'bad:name',
      'bad@name',
      '.',
      '..',
      ' leading',
      'trailing ',
      'bad\u0001name',
      'a'.repeat(129),
    ]
    for (const badPackage of badPackages) {
      // @ts-expect-error test invalid types
      await expect(installer.install(badPackage, validUrl, validBranch, validCommit)).rejects.toMatchObject({
        code: 'INVALID_PACKAGE_NAME',
      })
    }

    // Bad target commits
    const badCommits = [
      null,
      '',
      ' ',
      'short',
      '012345678901234567890123456789012345678', // 39
      '0123456789012345678901234567890123456789a', // 41
      '012345678901234567890123456789012345678G', // non-hex
      'ABCDEF0123456789ABCDEF0123456789ABCDEF01', // uppercase
    ]
    for (const badCommit of badCommits) {
      // @ts-expect-error test invalid types
      await expect(installer.install('valid-pkg', validUrl, validBranch, badCommit)).rejects.toMatchObject({
        code: 'INVALID_TARGET_COMMIT',
      })
    }

    // Bad URLs
    const badUrls = [
      null,
      '',
      ' ',
      'not-a-uri',
      'relative/path',
      'https://user:pass@example.com/repo.git',
      'https://user@example.com/repo.git',
      'https://' + 'a'.repeat(2048) + '.com',
      'git://example.com/repo.git',
      'ssh://example.com/repo.git',
    ]
    for (const badUrl of badUrls) {
      // @ts-expect-error test invalid types
      const promise = installer.install('valid-pkg', badUrl, validBranch, validCommit)
      await expect(promise).rejects.toMatchObject({
        code: 'INVALID_REPOSITORY_URL',
      })
      if (badUrl) {
        await promise.catch((err: SkillSyncException) => {
          expect(err.message.includes('user:pass')).toBe(false)
        })
      }
    }

    // Bad branches
    const badBranches = [
      null,
      '',
      ' ',
      ' leading',
      'trailing ',
      'bad\u0001branch',
      'a'.repeat(256),
    ]
    for (const badBranch of badBranches) {
      // @ts-expect-error test invalid types
      await expect(installer.install('valid-pkg', validUrl, badBranch, validCommit)).rejects.toMatchObject({
        code: 'INVALID_BRANCH',
      })
    }

    assertDirectoryEmpty(stagingRoot)
    assertDirectoryEmpty(backupRoot)
  })

  it('publish swap failure preserves previous install and rolls back', async () => {
    const { commit1, commit2 } = await createFixtureRepo()
    const remoteUrl = pathToFileURL(remoteRepoDir).toString()

    await installer.install('swap-pkg', remoteUrl, 'main', commit1)
    const pkgDir = path.join(skillsRoot, 'swap-pkg')
    expect(fs.readFileSync(path.join(pkgDir, 'weather-skill', 'SKILL.md'), 'utf8')).toBe(
      '# Weather Skill\nVersion 1.0\n',
    )

    let failSwap = true
    const faultyInstaller = new SkillPackageInstaller(skillsRoot, stagingRoot, backupRoot, {
      directoryMover: async (source, target) => {
        if (failSwap && source.startsWith(stagingRoot) && target === pkgDir) {
          throw new Error('simulated atomic swap failure')
        }
        await fs.promises.rename(source, target)
      },
    })

    await expect(
      faultyInstaller.install('swap-pkg', remoteUrl, 'main', commit2),
    ).rejects.toMatchObject({
      code: 'INSTALL_FAILED',
      message: 'Failed to swap installed package directory',
    })

    // Previous install preserved byte-for-byte
    expect(fs.readFileSync(path.join(pkgDir, 'weather-skill', 'SKILL.md'), 'utf8')).toBe(
      '# Weather Skill\nVersion 1.0\n',
    )
    expect(fs.readFileSync(path.join(pkgDir, '.kkstudio-commit'), 'utf8')).toBe(`${commit1}\n`)

    assertDirectoryEmpty(stagingRoot)
    assertDirectoryEmpty(backupRoot)
  })

  it('sets owner-only permissions (0600/0700) on POSIX', async () => {
    if (process.platform === 'win32') return

    await fs.promises.mkdir(remoteRepoDir, { recursive: true })
    await git.init({ fs, dir: remoteRepoDir, defaultBranch: 'main' })

    const blobExec = await git.writeBlob({
      fs,
      dir: remoteRepoDir,
      blob: Buffer.from('#!/bin/sh\necho hi\n'),
    })
    const blobRegular = await git.writeBlob({
      fs,
      dir: remoteRepoDir,
      blob: Buffer.from('hello regular\n'),
    })

    const binTreeOid = await git.writeTree({
      fs,
      dir: remoteRepoDir,
      tree: [{ mode: '100755', path: 'run.sh', oid: blobExec, type: 'blob' }],
    })

    const rootTreeOid = await git.writeTree({
      fs,
      dir: remoteRepoDir,
      tree: [
        { mode: '040000', path: 'bin', oid: binTreeOid, type: 'tree' },
        { mode: '100644', path: 'readme.txt', oid: blobRegular, type: 'blob' },
      ],
    })

    const commit = await git.writeCommit({
      fs,
      dir: remoteRepoDir,
      commit: {
        message: 'perm test',
        tree: rootTreeOid,
        parent: [],
        author: { name: 'test', email: 'test@example.com', timestamp: 0, timezoneOffset: 0 },
        committer: { name: 'test', email: 'test@example.com', timestamp: 0, timezoneOffset: 0 },
      },
    })
    await git.writeRef({ fs, dir: remoteRepoDir, ref: 'refs/heads/main', value: commit })

    const remoteUrl = pathToFileURL(remoteRepoDir).toString()
    await installer.install('perm-pkg', remoteUrl, 'main', commit)

    const pkgDir = path.join(skillsRoot, 'perm-pkg')
    const dirStat = fs.statSync(path.join(pkgDir, 'bin'))
    expect(dirStat.mode & 0o777).toBe(0o700)

    const execStat = fs.statSync(path.join(pkgDir, 'bin', 'run.sh'))
    expect(execStat.mode & 0o777).toBe(0o700)

    const regStat = fs.statSync(path.join(pkgDir, 'readme.txt'))
    expect(regStat.mode & 0o777).toBe(0o600)

    const commitStat = fs.statSync(path.join(pkgDir, '.kkstudio-commit'))
    expect(commitStat.mode & 0o777).toBe(0o600)
  })

  it('concurrent installs of the same package serialize while different packages proceed concurrently', async () => {
    const { commit1 } = await createFixtureRepo()
    const remoteUrl = pathToFileURL(remoteRepoDir).toString()

    const executionLog: string[] = []

    let slowMoverPkg1 = 0
    const delayInstaller = new SkillPackageInstaller(skillsRoot, stagingRoot, backupRoot, {
      directoryMover: async (source, target) => {
        if (target.includes('pkg-same')) {
          slowMoverPkg1++
          executionLog.push(`pkg-same-${slowMoverPkg1} start`)
          await new Promise((resolve) => setTimeout(resolve, 50))
          executionLog.push(`pkg-same-${slowMoverPkg1} end`)
        } else if (target.includes('pkg-diff')) {
          executionLog.push('pkg-diff start')
          await new Promise((resolve) => setTimeout(resolve, 10))
          executionLog.push('pkg-diff end')
        }
        await fs.promises.rename(source, target)
      },
    })

    await Promise.all([
      delayInstaller.install('pkg-same', remoteUrl, 'main', commit1),
      delayInstaller.install('pkg-same', remoteUrl, 'main', commit1),
      delayInstaller.install('pkg-diff', remoteUrl, 'main', commit1),
    ])

    // Notice: pkg-diff must have completed before the second pkg-same started
    const firstSameEnd = executionLog.indexOf('pkg-same-1 end')
    const secondSameStart = executionLog.indexOf('pkg-same-2 start')
    expect(firstSameEnd).toBeLessThan(secondSameStart)

    const diffStart = executionLog.indexOf('pkg-diff start')
    // diff started before same-1 finished
    expect(diffStart).toBeLessThan(firstSameEnd)
  })

  it('open factory and getters return expected paths', () => {
    const config = {
      configPath: path.join(tempDir, 'config.json'),
      dataDir: tempDir,
      tokenPath: path.join(tempDir, 'token'),
      studioUrl: 'https://example.com',
      gatewayUri: 'ws://example.com',
      bashExecutable: '/bin/bash',
      note: null,
      lsp: {},
      distributionDir: path.join(tempDir, 'dist'),
    }

    const inst = SkillPackageInstaller.open(config)
    expect(inst.skillsRoot()).toBe(path.join(tempDir, 'skills'))
    expect(inst.stagingRoot()).toBe(path.join(tempDir, 'skill-work', 'staging'))
    expect(inst.backupRoot()).toBe(path.join(tempDir, 'skill-work', 'backup'))
  })

  it('assertRealDirectorySync throws if path does not exist or is a symlink', () => {
    expect(() => {
      assertRealDirectorySync(path.join(tempDir, 'non-existent'), 'testDir')
    }).toThrow(/testDir does not exist/)

    const realDir = path.join(tempDir, 'real-target')
    fs.mkdirSync(realDir)
    const symlinkDir = path.join(tempDir, 'symlink-dir')
    fs.symlinkSync(realDir, symlinkDir)

    expect(() => {
      assertRealDirectorySync(symlinkDir, 'symDir')
    }).toThrow(/symDir must be a real non-symlink directory/)
  })

  it('recoverArtifacts cleans backups with non-conforming package names and uuids', () => {
    const badNames = [
      `bad:name.${randomUUID()}`,
      `bad@name.${randomUUID()}`,
      `bad\\name.${randomUUID()}`,
      `bad\u0001name.${randomUUID()}`,
      `.${randomUUID()}`,
      `..${randomUUID()}`,
      ` trailing .${randomUUID()}`,
      `valid-pkg.not-a-valid-uuid`,
      `no-dot-separator`,
    ]

    for (const badName of badNames) {
      const p = path.join(backupRoot, badName)
      fs.mkdirSync(p, { recursive: true })
      fs.writeFileSync(path.join(p, 'junk.txt'), 'junk')
    }

    installer.recoverArtifacts()
    assertDirectoryEmpty(backupRoot)
  })

  it('validates package entry path against unsafe patterns', () => {
    expect(() => validatePackageEntryPath('')).toThrowError(/Package entry path must not be blank/)
    expect(() => validatePackageEntryPath('   ')).toThrowError(/Package entry path must not be blank/)
    expect(() => validatePackageEntryPath('/leading/slash')).toThrowError(
      /Package entry path must not be absolute/,
    )
    expect(() => validatePackageEntryPath('\\leading\\backslash')).toThrowError(
      /Package entry path must not be absolute/,
    )
    expect(() => validatePackageEntryPath('C:\\windows\\path')).toThrowError(
      /Package entry path must not be absolute/,
    )
    expect(() => validatePackageEntryPath('foo//bar')).toThrowError(
      /Package entry path contains invalid segment/,
    )
    expect(() => validatePackageEntryPath('foo/./bar')).toThrowError(
      /Package entry path contains invalid segment/,
    )
    expect(() => validatePackageEntryPath('foo/../bar')).toThrowError(
      /Package entry path contains invalid segment/,
    )
    expect(() => validatePackageEntryPath('foo\\..\\bar')).toThrowError(
      /Package entry path contains invalid segment/,
    )

    // Valid path does not throw
    expect(() => validatePackageEntryPath('valid/nested/path.txt')).not.toThrow()
  })

  it('validateTargetCommit accepts 40-char and 64-char lowercase hex commits', () => {
    const commit40 = '0123456789abcdef0123456789abcdef01234567'
    const commit64 = '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'

    expect(() => validateTargetCommit(commit40)).not.toThrow()
    expect(() => validateTargetCommit(commit64)).not.toThrow()

    expect(() => validateTargetCommit(null)).toThrow()
    expect(() => validateTargetCommit('bad')).toThrow()
  })

  it('validator functions reject edge case inputs', () => {
    expect(() => validatePackageName('bad:name')).toThrow()
    expect(() => validatePackageName('bad/name')).toThrow()
    expect(() => validatePackageName('bad\\name')).toThrow()
    expect(() => validatePackageName('bad@name')).toThrow()
    expect(() => validatePackageName('bad\u0000name')).toThrow()
    expect(() => validatePackageName('.')).toThrow()
    expect(() => validatePackageName('..')).toThrow()

    expect(() => validateBranch('bad\u0000branch')).toThrow()
    expect(() => validateBranch(' ')).toThrow()

    expect(() => validateRepositoryUrl('ftp://example.com/repo.git')).toThrow()
  })

  it('createParentDirectoriesOwnerOnly detects symlinks and non-directory conflicts', async () => {
    const root = path.join(tempDir, 'parent-test-root')
    fs.mkdirSync(root, { recursive: true })

    // Create a file at root/conflict
    const conflictFile = path.join(root, 'conflict')
    fs.writeFileSync(conflictFile, 'a file')

    await expect(
      createParentDirectoriesOwnerOnly(root, path.join(conflictFile, 'sub')),
    ).rejects.toMatchObject({
      code: 'UNSAFE_PACKAGE_ENTRY',
      message: 'Path element is not a directory',
    })

    // Create a symlink directory at root/symdir
    const realDir = path.join(tempDir, 'real-sub')
    fs.mkdirSync(realDir, { recursive: true })
    const symDir = path.join(root, 'symdir')
    fs.symlinkSync(realDir, symDir)

    await expect(
      createParentDirectoriesOwnerOnly(root, path.join(symDir, 'sub')),
    ).rejects.toMatchObject({
      code: 'UNSAFE_PACKAGE_ENTRY',
      message: 'Symbolic link encountered in path',
    })
  })

  it('writeFileOwnerOnly rejects if file already exists', async () => {
    const targetFile = path.join(tempDir, 'already-exists.txt')
    fs.writeFileSync(targetFile, 'existing')

    await expect(
      writeFileOwnerOnly(targetFile, Buffer.from('new content'), false),
    ).rejects.toMatchObject({
      code: 'UNSAFE_PACKAGE_ENTRY',
      message: 'Target file already exists or is a symbolic link',
    })
  })

  it('createOwnerOnlyDirectory rejects if path is not a directory', async () => {
    const fileTarget = path.join(tempDir, 'file-target.txt')
    fs.writeFileSync(fileTarget, 'existing')

    await expect(createOwnerOnlyDirectory(fileTarget)).rejects.toThrow(
      /directory path must be a real directory/,
    )
  })

  it('install rejects immediately if signal is pre-aborted', async () => {
    const { commit1 } = await createFixtureRepo()
    const remoteUrl = pathToFileURL(remoteRepoDir).toString()
    const ac = new AbortController()
    ac.abort()

    await expect(
      installer.install('abort-early', remoteUrl, 'main', commit1, null, ac.signal),
    ).rejects.toMatchObject({
      name: 'AbortError',
      message: 'Operation cancelled',
    })
  })

  function assertDirectoryEmpty(dir: string): void {
    const entries = fs.readdirSync(dir)
    expect(entries).toEqual([])
  }
})
