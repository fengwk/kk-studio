import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { randomUUID } from 'node:crypto'
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import git from 'isomorphic-git'
import {
  SkillPackageInstaller,
  assertRealDirectory,
  validatePackageEntryPath,
  validateTargetCommit,
  validatePackageName,
  validateBranch,
  validateRepositoryUrl,
} from '../../src/skill/installer.js'
import { SkillSyncException } from '../../src/skill/types.js'
import { ensurePrivateDirectory, verifyPrivateFile } from '../../src/native/index.js'
import { createTestPrivateFs } from './test-private-fs.js'

describe('SkillPackageInstaller', () => {
  let tempDir: string
  let remoteRepoDir: string
  let skillsRoot: string
  let stagingRoot: string
  let backupRoot: string
  let installer: SkillPackageInstaller

  beforeEach(() => {
    tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'skill-inst-test-'))
    remoteRepoDir = path.join(tempDir, 'remote-repo')
    skillsRoot = path.join(tempDir, 'skills')
    stagingRoot = path.join(tempDir, 'staging')
    backupRoot = path.join(tempDir, 'backup')

    installer = new SkillPackageInstaller(skillsRoot, stagingRoot, backupRoot, {
      privateFs: createTestPrivateFs(),
    })
  })

  afterEach(() => {
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

  it('default installer wires the native private FS: owner-only paths and external-link refusal', async () => {
    const { commit1 } = await createFixtureRepo()
    const remoteUrl = pathToFileURL(remoteRepoDir).toString()

    // No privateFs injected: the installer must default to the native owner-only primitives.
    const defaultInstaller = new SkillPackageInstaller(skillsRoot, stagingRoot, backupRoot)
    const installed = await defaultInstaller.install('owner-only-pkg', remoteUrl, 'main', commit1)

    // Platform-agnostic owner-only proof through the native port: directories re-verify as
    // owner-only, files re-verify as owner-only (POSIX 0600 / Windows DACL). This runs on every
    // platform, so the installer core is never skipped and Windows is never asserted with a fake mode.
    const skillDir = path.join(installed.localPath, 'weather-skill')
    const skillFile = path.join(skillDir, 'SKILL.md')
    const commitFile = path.join(installed.localPath, '.kkstudio-commit')
    await expect(ensurePrivateDirectory(installed.localPath)).resolves.toBe(installed.localPath)
    await expect(ensurePrivateDirectory(skillDir)).resolves.toBe(skillDir)
    await expect(verifyPrivateFile(skillFile)).resolves.toBeUndefined()
    await expect(verifyPrivateFile(commitFile)).resolves.toBeUndefined()

    if (process.platform !== 'win32') {
      const mode = (p: string): number => fs.lstatSync(p).mode & 0o777
      expect(mode(skillsRoot)).toBe(0o700)
      expect(mode(stagingRoot)).toBe(0o700)
      expect(mode(backupRoot)).toBe(0o700)
      expect(mode(installed.localPath)).toBe(0o700)
      expect(mode(skillDir)).toBe(0o700)
      expect(mode(skillFile)).toBe(0o600)
      expect(mode(commitFile)).toBe(0o600)
    }

    // External link: a symlinked skills root must be refused, never followed or chmod-ed.
    const linkedSkillsRoot = path.join(tempDir, 'linked-skills')
    const externalDir = path.join(tempDir, 'external-dir')
    fs.mkdirSync(externalDir, { recursive: true })
    fs.symlinkSync(externalDir, linkedSkillsRoot)
    const linkedInstaller = new SkillPackageInstaller(
      linkedSkillsRoot,
      path.join(tempDir, 'linked-staging'),
      path.join(tempDir, 'linked-backup'),
    )
    await expect(linkedInstaller.install('link-pkg', remoteUrl, 'main', commit1)).rejects.toThrow(
      /symbolic link/,
    )
    expect(fs.readdirSync(externalDir)).toEqual([])
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

  it('commit with symlink is rejected without checking out or writing symlink to staging', async () => {
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

    // Previous install is byte-for-byte preserved
    expect(fs.readFileSync(path.join(pkgDir, 'weather-skill', 'SKILL.md'), 'utf8')).toBe(
      '# Weather Skill\nVersion 1.0\n',
    )
    expect(fs.readFileSync(path.join(pkgDir, '.kkstudio-commit'), 'utf8')).toBe(`${commit1}\n`)

    // Staging and backup are cleaned up, no symlinks ever created
    assertDirectoryEmpty(stagingRoot)
    assertDirectoryEmpty(backupRoot)
  })

  it('commit with submodule is rejected with UNSAFE_PACKAGE_ENTRY and preserves prior install', async () => {
    const { commit1 } = await createFixtureRepo()
    const remoteUrl = pathToFileURL(remoteRepoDir).toString()

    await installer.install('submodule-pkg', remoteUrl, 'main', commit1)
    const pkgDir = path.join(skillsRoot, 'submodule-pkg')

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
        parent: [commit1],
        author: { name: 'test', email: 'test@example.com', timestamp: 0, timezoneOffset: 0 },
        committer: { name: 'test', email: 'test@example.com', timestamp: 0, timezoneOffset: 0 },
      },
    })

    await expect(
      installer.install('submodule-pkg', remoteUrl, 'main', subCommit),
    ).rejects.toMatchObject({
      code: 'UNSAFE_PACKAGE_ENTRY',
      message: 'Submodules are not allowed in skill package',
    })

    expect(fs.readFileSync(path.join(pkgDir, 'weather-skill', 'SKILL.md'), 'utf8')).toBe(
      '# Weather Skill\nVersion 1.0\n',
    )
    assertDirectoryEmpty(stagingRoot)
    assertDirectoryEmpty(backupRoot)
  })

  it('supports file:// repositories where .git is a gitfile pointing to another gitdir', async () => {
    const { commit1 } = await createFixtureRepo()

    // Create a secondary working tree with a .git file
    const gitfileRepoDir = path.join(tempDir, 'gitfile-repo')
    await fs.promises.mkdir(gitfileRepoDir, { recursive: true })
    const dotGitFile = path.join(gitfileRepoDir, '.git')
    await fs.promises.writeFile(dotGitFile, `gitdir: ${path.join(remoteRepoDir, '.git')}\n`)

    const fileUrl = pathToFileURL(gitfileRepoDir).toString()
    const installed = await installer.install('gitfile-pkg', fileUrl, 'main', commit1)

    expect(installed.packageName).toBe('gitfile-pkg')
    expect(installed.installedCommit).toBe(commit1)
    const skillFile = path.join(skillsRoot, 'gitfile-pkg', 'weather-skill', 'SKILL.md')
    expect(fs.readFileSync(skillFile, 'utf8')).toBe('# Weather Skill\nVersion 1.0\n')
  })

  it('supports file:// repositories with worktree layout (.git file + commondir)', async () => {
    const { commit1 } = await createFixtureRepo()

    // Simulate git worktree layout:
    // remoteRepoDir/.git/worktrees/wt1/
    //   commondir -> ../..
    //   HEAD -> ref: refs/heads/main
    const wtDir = path.join(tempDir, 'worktree-repo')
    await fs.promises.mkdir(wtDir, { recursive: true })
    const wtGitdir = path.join(remoteRepoDir, '.git', 'worktrees', 'wt1')
    await fs.promises.mkdir(wtGitdir, { recursive: true })
    await fs.promises.writeFile(path.join(wtGitdir, 'commondir'), '../..\n')
    await fs.promises.writeFile(path.join(wtGitdir, 'HEAD'), 'ref: refs/heads/main\n')

    await fs.promises.writeFile(path.join(wtDir, '.git'), `gitdir: ${wtGitdir}\n`)

    const fileUrl = pathToFileURL(wtDir).toString()
    const installed = await installer.install('worktree-pkg', fileUrl, 'main', commit1)

    expect(installed.packageName).toBe('worktree-pkg')
    expect(installed.installedCommit).toBe(commit1)
    const skillFile = path.join(skillsRoot, 'worktree-pkg', 'weather-skill', 'SKILL.md')
    expect(fs.readFileSync(skillFile, 'utf8')).toBe('# Weather Skill\nVersion 1.0\n')
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

  it('recoverArtifacts only touches product-named artifacts, restores missing package and preserves foreign files', async () => {
    // 1. Leftover staging matching product name
    const leftoverStaging1 = path.join(stagingRoot, `leftover-pkg.${randomUUID()}`)
    fs.mkdirSync(leftoverStaging1, { recursive: true })
    fs.writeFileSync(path.join(leftoverStaging1, 'temp.txt'), 'leftover')

    const leftoverStaging2 = path.join(stagingRoot, `leftover-pkg.${randomUUID()}.clone`)
    fs.mkdirSync(leftoverStaging2, { recursive: true })
    fs.writeFileSync(path.join(leftoverStaging2, 'temp.txt'), 'leftover-clone')

    // Staging non-product file/dir (MUST BE LEFT UNTOUCHED)
    const stagingForeignFile = path.join(stagingRoot, 'manual-unrelated.txt')
    fs.writeFileSync(stagingForeignFile, 'keep-me')
    const stagingForeignDir = path.join(stagingRoot, 'unrelated-dir')
    fs.mkdirSync(stagingForeignDir, { recursive: true })

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

    // 4. Non-conforming backup file/dir (MUST BE LEFT UNTOUCHED)
    const corruptBackup = path.join(backupRoot, 'corrupted-file.bin')
    fs.writeFileSync(corruptBackup, 'garbage')
    const badNameBackup = path.join(backupRoot, `bad:name.${randomUUID()}`)
    fs.mkdirSync(badNameBackup, { recursive: true })

    await installer.recoverArtifacts()

    // Verify product-named staging cleaned, but foreign entries untouched
    expect(fs.existsSync(leftoverStaging1)).toBe(false)
    expect(fs.existsSync(leftoverStaging2)).toBe(false)
    expect(fs.existsSync(stagingForeignFile)).toBe(true)
    expect(fs.existsSync(stagingForeignDir)).toBe(true)

    // Verify restored backup
    const restoredDir = path.join(skillsRoot, 'restored-pkg')
    expect(fs.existsSync(restoredDir)).toBe(true)
    expect(fs.readFileSync(path.join(restoredDir, 'recovered.txt'), 'utf8')).toBe('good')

    // Verify existing package untouched and its redundant backup cleaned
    expect(fs.existsSync(existingPackage)).toBe(true)
    expect(fs.readFileSync(path.join(existingPackage, 'current.txt'), 'utf8')).toBe('current')
    expect(fs.existsSync(existingBackup)).toBe(false)

    // Verify foreign/non-conforming backups left untouched
    expect(fs.existsSync(corruptBackup)).toBe(true)
    expect(fs.existsSync(badNameBackup)).toBe(true)
  })

  it('recoverArtifacts throws clear safe error if restore fails and leaves backup intact', async () => {
    const restorableUuid = randomUUID()
    const restorableBackup = path.join(backupRoot, `fail-restore-pkg.${restorableUuid}`)
    fs.mkdirSync(restorableBackup, { recursive: true })
    fs.writeFileSync(path.join(restorableBackup, 'important.txt'), 'safe')

    const failingRecoveryInstaller = new SkillPackageInstaller(skillsRoot, stagingRoot, backupRoot, {
      privateFs: createTestPrivateFs(),
      directoryMover: async () => {
        throw new Error('disk permission denied during move')
      },
    })

    await expect(failingRecoveryInstaller.recoverArtifacts()).rejects.toThrow(
      /failed to restore backup/,
    )

    // The backup must NOT be deleted
    expect(fs.existsSync(restorableBackup)).toBe(true)
    expect(fs.readFileSync(path.join(restorableBackup, 'important.txt'), 'utf8')).toBe('safe')
  })

  it('rejects 64-hex SHA-256 target commit clearly before network or materialization', async () => {
    const commit64 = '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'
    const remoteUrl = pathToFileURL(remoteRepoDir).toString()

    await expect(
      installer.install('sha256-pkg', remoteUrl, 'main', commit64),
    ).rejects.toMatchObject({
      code: 'INVALID_TARGET_COMMIT',
      message:
        'Target commit must be a 40-character SHA-1 commit; SHA-256 commits are not supported by the local Git backend',
    })

    // No staging or backup directories touched
    assertDirectoryEmpty(stagingRoot)
    assertDirectoryEmpty(backupRoot)
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
      privateFs: createTestPrivateFs(),
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

  it('publish swap failure surfaces distinct error if rollback of previous package also fails', async () => {
    const { commit1, commit2 } = await createFixtureRepo()
    const remoteUrl = pathToFileURL(remoteRepoDir).toString()

    await installer.install('rollback-fail-pkg', remoteUrl, 'main', commit1)
    const pkgDir = path.join(skillsRoot, 'rollback-fail-pkg')

    const doubleFaultInstaller = new SkillPackageInstaller(skillsRoot, stagingRoot, backupRoot, {
      privateFs: createTestPrivateFs(),
      directoryMover: async (source, target) => {
        // Step 1: Moving existing package to backupDir succeeds
        if (source === pkgDir && target.startsWith(backupRoot)) {
          await fs.promises.rename(source, target)
          return
        }
        // Step 2: Swap stagingDir to packageDir fails
        if (source.startsWith(stagingRoot) && target === pkgDir) {
          throw new Error('initial swap failure')
        }
        // Step 3: Rollback backupDir to packageDir also fails
        if (source.startsWith(backupRoot) && target === pkgDir) {
          throw new Error('rollback move failure')
        }
        await fs.promises.rename(source, target)
      },
    })

    await expect(
      doubleFaultInstaller.install('rollback-fail-pkg', remoteUrl, 'main', commit2),
    ).rejects.toMatchObject({
      code: 'INSTALL_FAILED',
      message: 'Failed to swap installed package directory and rollback of the previous package failed',
    })

    // Neither false success nor swallow occurred
    expect(fs.existsSync(pkgDir)).toBe(false)
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
      privateFs: createTestPrivateFs(),
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

  it('assertRealDirectory throws if path does not exist or is a symlink', async () => {
    await expect(assertRealDirectory(path.join(tempDir, 'non-existent'), 'testDir')).rejects.toThrow(
      /testDir does not exist/,
    )

    const realDir = path.join(tempDir, 'real-target')
    fs.mkdirSync(realDir)
    const symlinkDir = path.join(tempDir, 'symlink-dir')
    fs.symlinkSync(realDir, symlinkDir)

    await expect(assertRealDirectory(symlinkDir, 'symDir')).rejects.toThrow(
      /symDir must be a real non-symlink directory/,
    )
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

  it('validateTargetCommit accepts 40-char SHA-1 and rejects 64-char SHA-256 with specific message', () => {
    const commit40 = '0123456789abcdef0123456789abcdef01234567'
    const commit64 = '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'

    expect(() => validateTargetCommit(commit40)).not.toThrow()
    expect(() => validateTargetCommit(commit64)).toThrowError(
      'Target commit must be a 40-character SHA-1 commit; SHA-256 commits are not supported by the local Git backend',
    )

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

  it('file:// protocol handles missing repo, invalid gitfile, bare repo, and worktree refs', async () => {
    const { commit1 } = await createFixtureRepo()

    // 1. Nonexistent directory
    await expect(
      installer.install('nonexist-pkg', 'file:///path/does/not/exist/anywhere', 'main', commit1),
    ).rejects.toMatchObject({
      code: 'GIT_FETCH_FAILED',
      message: 'Git branch fetch failed',
    })

    // 2. Directory with no .git and no HEAD
    const emptyDir = path.join(tempDir, 'empty-dir')
    fs.mkdirSync(emptyDir, { recursive: true })
    await expect(
      installer.install('empty-pkg', pathToFileURL(emptyDir).toString(), 'main', commit1),
    ).rejects.toMatchObject({
      code: 'GIT_FETCH_FAILED',
      message: 'Git branch fetch failed',
    })

    // 3. Directory with .git file that lacks "gitdir:" prefix
    const badGitfileDir = path.join(tempDir, 'bad-gitfile-dir')
    fs.mkdirSync(badGitfileDir, { recursive: true })
    fs.writeFileSync(path.join(badGitfileDir, '.git'), 'invalid-content-without-prefix\n')
    await expect(
      installer.install('badgit-pkg', pathToFileURL(badGitfileDir).toString(), 'main', commit1),
    ).rejects.toMatchObject({
      code: 'GIT_FETCH_FAILED',
      message: 'Git branch fetch failed',
    })

    // 4. Bare repository support (HEAD file exists directly in sourcePath)
    const bareDir = path.join(tempDir, 'bare-repo')
    await fs.promises.mkdir(bareDir, { recursive: true })
    await git.init({ fs, dir: bareDir, bare: true })
    // Write a dummy skill commit in bare repo
    const blob = await git.writeBlob({ fs, dir: bareDir, blob: Buffer.from('# Bare Skill\n') })
    const tree = await git.writeTree({
      fs,
      dir: bareDir,
      tree: [{ mode: '100644', path: 'SKILL.md', oid: blob, type: 'blob' }],
    })
    const bareCommit = await git.writeCommit({
      fs,
      dir: bareDir,
      commit: {
        message: 'bare init',
        tree,
        parent: [],
        author: { name: 't', email: 't@t', timestamp: 0, timezoneOffset: 0 },
        committer: { name: 't', email: 't@t', timestamp: 0, timezoneOffset: 0 },
      },
    })
    await git.writeRef({ fs, dir: bareDir, ref: 'refs/heads/main', value: bareCommit })
    const bareInstalled = await installer.install(
      'bare-pkg',
      pathToFileURL(bareDir).toString(),
      'main',
      bareCommit,
    )
    expect(bareInstalled.packageName).toBe('bare-pkg')
    expect(bareInstalled.installedCommit).toBe(bareCommit)

    // 5. Worktree where branch ref exists in gitdir but not in common dir
    const wtDir = path.join(tempDir, 'wt-custom-ref')
    await fs.promises.mkdir(wtDir, { recursive: true })
    const wtGitdir = path.join(remoteRepoDir, '.git', 'worktrees', 'wt-custom')
    await fs.promises.mkdir(path.join(wtGitdir, 'refs', 'heads'), { recursive: true })
    await fs.promises.writeFile(path.join(wtGitdir, 'commondir'), '../..\n')
    await fs.promises.writeFile(path.join(wtGitdir, 'refs', 'heads', 'custom-branch'), commit1 + '\n')
    await fs.promises.writeFile(path.join(wtDir, '.git'), `gitdir: ${wtGitdir}\n`)

    const wtInstalled = await installer.install(
      'wt-custom-pkg',
      pathToFileURL(wtDir).toString(),
      'custom-branch',
      commit1,
    )
    expect(wtInstalled.packageName).toBe('wt-custom-pkg')

    // 6. Non-empty file URL host on POSIX throws inside fileURLToPath
    if (process.platform !== 'win32') {
      await expect(
        installer.install('badhost-pkg', 'file://unsupported-host/path', 'main', commit1),
      ).rejects.toMatchObject({
        code: 'GIT_FETCH_FAILED',
        message: 'Git branch fetch failed',
      })
    }

    // 7. Missing branch ref in valid repo
    await expect(
      installer.install('missing-branch-pkg', pathToFileURL(remoteRepoDir).toString(), 'nonexistent-branch', commit1),
    ).rejects.toMatchObject({
      code: 'GIT_FETCH_FAILED',
      message: 'Git branch fetch failed',
    })

    // 8. Missing commit in valid repo
    const unknownCommit = '0123456789012345678901234567890123456789'
    await expect(
      installer.install('missing-commit-pkg', pathToFileURL(remoteRepoDir).toString(), 'main', unknownCommit),
    ).rejects.toMatchObject({
      code: 'COMMIT_NOT_FOUND',
      message: 'Target commit could not be resolved from remote repository',
    })
  })

  it('detects existing target collisions and parent path conflicts in staging', async () => {
    const { commit1 } = await createFixtureRepo()
    const remoteUrl = pathToFileURL(remoteRepoDir).toString()

    // 1. Existing file collision in staging before write
    const collisionFs = createTestPrivateFs()
    const origEnsure = collisionFs.ensurePrivateDirectory
    collisionFs.ensurePrivateDirectory = async (p: string) => {
      const res = await origEnsure(p)
      if (p.includes('collision-pkg')) {
        // Pre-create the target file so lstat sees it
        const targetPath = path.join(p, 'weather-skill', 'SKILL.md')
        await fs.promises.mkdir(path.dirname(targetPath), { recursive: true })
        await fs.promises.writeFile(targetPath, 'already-there')
      }
      return res
    }
    const collisionInstaller = new SkillPackageInstaller(skillsRoot, stagingRoot, backupRoot, {
      privateFs: collisionFs,
    })
    await expect(
      collisionInstaller.install('collision-pkg', remoteUrl, 'main', commit1),
    ).rejects.toMatchObject({
      code: 'UNSAFE_PACKAGE_ENTRY',
      message: 'Target file already exists or is a symbolic link',
    })

    // 2. Symlink in parent path
    const symlinkParentFs = createTestPrivateFs()
    symlinkParentFs.ensurePrivateDirectory = async (p: string) => {
      const res = await origEnsure(p)
      if (p.includes('symparent-pkg')) {
        const dummyTarget = path.join(tempDir, 'dummy-dir')
        await fs.promises.mkdir(dummyTarget, { recursive: true })
        const symlinkPath = path.join(p, 'weather-skill')
        try {
          await fs.promises.symlink(dummyTarget, symlinkPath)
        } catch {}
      }
      return res
    }
    const symparentInstaller = new SkillPackageInstaller(skillsRoot, stagingRoot, backupRoot, {
      privateFs: symlinkParentFs,
    })
    await expect(
      symparentInstaller.install('symparent-pkg', remoteUrl, 'main', commit1),
    ).rejects.toMatchObject({
      code: 'UNSAFE_PACKAGE_ENTRY',
      message: 'Symbolic link encountered in path',
    })

    // 3. Non-directory file in parent path
    const fileParentFs = createTestPrivateFs()
    fileParentFs.ensurePrivateDirectory = async (p: string) => {
      const res = await origEnsure(p)
      if (p.includes('fileparent-pkg')) {
        const filePath = path.join(p, 'weather-skill')
        await fs.promises.writeFile(filePath, 'not-a-directory')
      }
      return res
    }
    const fileparentInstaller = new SkillPackageInstaller(skillsRoot, stagingRoot, backupRoot, {
      privateFs: fileParentFs,
    })
    await expect(
      fileparentInstaller.install('fileparent-pkg', remoteUrl, 'main', commit1),
    ).rejects.toMatchObject({
      code: 'UNSAFE_PACKAGE_ENTRY',
      message: 'Path element is not a directory',
    })

    // 4. Unexpected error in materialization is mapped to INSTALL_FAILED
    const failingFs = createTestPrivateFs()
    failingFs.writePrivateFile = async () => {
      throw new Error('disk read/write failure')
    }
    const failingInstaller = new SkillPackageInstaller(skillsRoot, stagingRoot, backupRoot, {
      privateFs: failingFs,
    })
    await expect(
      failingInstaller.install('fail-io-pkg', remoteUrl, 'main', commit1),
    ).rejects.toMatchObject({
      code: 'INSTALL_FAILED',
      message: 'Skill package installation failed',
    })

    // 5. In-flight abort during materialization throws Operation cancelled
    const abortFs = createTestPrivateFs()
    abortFs.writePrivateFile = async () => {
      const err = new Error('Operation cancelled')
      err.name = 'AbortError'
      throw err
    }
    const abortInstaller = new SkillPackageInstaller(skillsRoot, stagingRoot, backupRoot, {
      privateFs: abortFs,
    })
    await expect(
      abortInstaller.install('abort-mat-pkg', remoteUrl, 'main', commit1),
    ).rejects.toMatchObject({
      name: 'AbortError',
      message: 'Operation cancelled',
    })
  })

  it('recoverArtifacts is resilient to non-existent directories and preserves edge-case non-conforming names', async () => {
    const nonExistentStaging = path.join(tempDir, 'staging-no-dir')
    const nonExistentBackup = path.join(tempDir, 'backup-no-dir')
    const noDirInstaller = new SkillPackageInstaller(skillsRoot, nonExistentStaging, nonExistentBackup, {
      privateFs: createTestPrivateFs(),
    })

    // Does not throw when directories are absent
    await expect(noDirInstaller.recoverArtifacts()).resolves.toBeUndefined()

    // Test non-conforming backup entries preserved
    fs.mkdirSync(backupRoot, { recursive: true })
    const validUuid = randomUUID()
    const nonConforming = [
      `a`.repeat(129) + `.${validUuid}`,
      `.${validUuid}`,
      `..${validUuid}`,
      `bad\u001fname.${validUuid}`,
      `bad\u0080name.${validUuid}`,
      `pkg.short-uuid`,
      `pkg.${validUuid}.clone`,
    ]
    for (const name of nonConforming) {
      const p = path.join(backupRoot, name)
      fs.mkdirSync(p, { recursive: true })
      fs.writeFileSync(path.join(p, 'data.txt'), 'keep')
    }

    await installer.recoverArtifacts()

    for (const name of nonConforming) {
      expect(fs.existsSync(path.join(backupRoot, name))).toBe(true)
    }
  })

  it('validates URLs, branches and package names across all edge cases', () => {
    // Malformed URLs
    expect(() => validateRepositoryUrl('http://[')).toThrowError(/malformed/)
    expect(() => validateRepositoryUrl('not-a-url')).toThrowError(/malformed/)
    expect(() => validateRepositoryUrl('http://user@host.com/repo.git')).toThrowError(/user info/)
    expect(() => validateRepositoryUrl('http://user:pass@host.com/repo.git')).toThrowError(/user info/)
    expect(() => validateRepositoryUrl('http://@host.com/repo.git')).toThrowError(/user info/)
    expect(() => validateRepositoryUrl('http://' + 'a'.repeat(2048) + '.com')).toThrowError(/exceed 2048/)
    expect(() => validateRepositoryUrl('')).toThrowError(/must not be blank/)
    expect(() => validateRepositoryUrl('ftp://host.com/repo.git')).toThrowError(/supported/)

    // Branches
    expect(() => validateBranch('')).toThrowError(/must not be blank/)
    expect(() => validateBranch('a'.repeat(256))).toThrowError(/exceed 255/)
    expect(() => validateBranch(' branch')).toThrowError(/whitespace/)
    expect(() => validateBranch('branch ')).toThrowError(/whitespace/)
    expect(() => validateBranch('bad\u001fbranch')).toThrowError(/control characters/)
    expect(() => validateBranch('bad\u007fbranch')).toThrowError(/control characters/)
    expect(() => validateBranch('bad\u0080branch')).toThrowError(/control characters/)

    // Package names
    expect(() => validatePackageName('')).toThrowError(/must not be blank/)
    expect(() => validatePackageName('a'.repeat(129))).toThrowError(/exceed 128/)
    expect(() => validatePackageName(' pkg')).toThrowError(/whitespace/)
    expect(() => validatePackageName('pkg ')).toThrowError(/whitespace/)
    expect(() => validatePackageName('.')).toThrowError(/must not be \. or \.\./)
    expect(() => validatePackageName('..')).toThrowError(/must not be \. or \.\./)
    expect(() => validatePackageName('bad\u001fpkg')).toThrowError(/control characters/)
    expect(() => validatePackageName('bad\u007fpkg')).toThrowError(/control characters/)
    expect(() => validatePackageName('bad\u0080pkg')).toThrowError(/control characters/)
    expect(() => validatePackageName('bad:pkg')).toThrowError(/invalid characters/)
    expect(() => validatePackageName('bad/pkg')).toThrowError(/invalid characters/)
    expect(() => validatePackageName('bad@pkg')).toThrowError(/invalid characters/)
    expect(() => validatePackageName('bad\\pkg')).toThrowError(/invalid characters/)
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
    if (!fs.existsSync(dir)) {
      return
    }
    const entries = fs.readdirSync(dir)
    expect(entries).toEqual([])
  }
})
