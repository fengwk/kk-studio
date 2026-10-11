import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  ExecutableResolver,
  LspDiscovery,
  type ResolvedServerConfig,
} from '../../src/coding/lsp-discovery.js'
import { LspError } from '../../src/coding/lsp-error.js'

describe('LspDiscovery & ExecutableResolver', () => {
  let tempDir: string

  beforeEach(() => {
    tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'lsp-disc-test-'))
  })

  afterEach(() => {
    try {
      fs.rmSync(tempDir, { recursive: true, force: true })
    } catch {}
  })

  it('selects server by extension case-insensitively', () => {
    const discovery = LspDiscovery.of([
      {
        id: 'java',
        command: ['java'],
        extensions: ['.java'],
        rootMarkers: [],
        firstMatchMarkers: [],
      },
      {
        id: 'ts',
        command: ['ts-server'],
        extensions: ['.ts', '.tsx'],
        rootMarkers: [],
        firstMatchMarkers: [],
      },
    ])

    expect(discovery.server('/p/App.JAVA')?.id).toBe('java')
    expect(discovery.server('/p/App.tsx')?.id).toBe('ts')
    expect(discovery.server('/p/readme.md')).toBeUndefined()
    expect(discovery.server('/p/Makefile')).toBeUndefined()
    expect(discovery.server(tempDir)).toBeUndefined()
  })

  it('rejects duplicate runtime server ids', () => {
    expect(() =>
      LspDiscovery.of([
        {
          id: 'dup',
          command: ['a'],
          extensions: ['.a'],
          rootMarkers: [],
          firstMatchMarkers: [],
        },
        {
          id: 'dup',
          command: ['b'],
          extensions: ['.b'],
          rootMarkers: [],
          firstMatchMarkers: [],
        },
      ]),
    ).toThrow(LspError)
  })

  it('prefers shallowest root marker inside git boundary', () => {
    const repo = path.join(tempDir, 'repo')
    fs.mkdirSync(path.join(repo, '.git'), { recursive: true })
    fs.writeFileSync(path.join(repo, 'pom.xml'), '<project/>')

    const moduleDir = path.join(repo, 'module')
    fs.mkdirSync(moduleDir, { recursive: true })
    fs.writeFileSync(path.join(moduleDir, 'pom.xml'), '<project/>')

    const srcDir = path.join(moduleDir, 'src')
    fs.mkdirSync(srcDir, { recursive: true })
    const file = path.join(srcDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    const server: ResolvedServerConfig = {
      id: 'java',
      command: ['java'],
      extensions: ['.java'],
      rootMarkers: ['pom.xml'],
      firstMatchMarkers: [],
    }
    const discovery = LspDiscovery.of([server])
    expect(discovery.workspaceRoot(server, file)).toBe(repo)
  })

  it('stops at worktree .git boundary and does not scan above', () => {
    const main = path.join(tempDir, 'main')
    fs.mkdirSync(path.join(main, '.git'), { recursive: true })
    fs.writeFileSync(path.join(main, 'pom.xml'), '<project/>')

    const worktree = path.join(tempDir, 'worktree')
    fs.mkdirSync(worktree, { recursive: true })
    fs.writeFileSync(path.join(worktree, '.git'), 'gitdir: ../main/.git/worktrees/worktree\n')

    const moduleDir = path.join(worktree, 'module')
    fs.mkdirSync(moduleDir, { recursive: true })
    fs.writeFileSync(path.join(moduleDir, 'pom.xml'), '<project/>')

    const file = path.join(moduleDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    const server: ResolvedServerConfig = {
      id: 'java',
      command: ['java'],
      extensions: ['.java'],
      rootMarkers: ['pom.xml'],
      firstMatchMarkers: [],
    }
    const discovery = LspDiscovery.of([server])
    expect(discovery.workspaceRoot(server, file)).toBe(moduleDir)
  })

  it('falls back to firstMatchMarker then file parent', () => {
    const repo = path.join(tempDir, 'repo')
    fs.mkdirSync(path.join(repo, '.git'), { recursive: true })

    const nested = path.join(repo, 'packages', 'web')
    fs.mkdirSync(nested, { recursive: true })
    fs.writeFileSync(path.join(nested, 'package.json'), '{}')

    const srcDir = path.join(nested, 'src')
    fs.mkdirSync(srcDir, { recursive: true })
    const file = path.join(srcDir, 'App.ts')
    fs.writeFileSync(file, 'export const x = 1;\n')

    const serverWithFirstMatch: ResolvedServerConfig = {
      id: 'ts',
      command: ['ts-server'],
      extensions: ['.ts'],
      rootMarkers: [],
      firstMatchMarkers: ['package.json'],
    }
    expect(LspDiscovery.of([serverWithFirstMatch]).workspaceRoot(serverWithFirstMatch, file)).toBe(
      nested,
    )

    const serverWithoutMarkers: ResolvedServerConfig = {
      id: 'ts',
      command: ['ts-server'],
      extensions: ['.ts'],
      rootMarkers: [],
      firstMatchMarkers: [],
    }
    expect(LspDiscovery.of([serverWithoutMarkers]).workspaceRoot(serverWithoutMarkers, file)).toBe(
      srcDir,
    )
  })

  it('non-git project uses file parent directory when no markers match', () => {
    const nested = path.join(tempDir, 'plain', 'nested')
    fs.mkdirSync(nested, { recursive: true })
    const file = path.join(nested, 'App.kt')
    fs.writeFileSync(file, 'val app = 1\n')

    const server: ResolvedServerConfig = {
      id: 'kt',
      command: ['kt-ls'],
      extensions: ['.kt'],
      rootMarkers: [],
      firstMatchMarkers: [],
    }
    expect(LspDiscovery.of([server]).workspaceRoot(server, file)).toBe(nested)
  })

  it('support() returns 3-state without launching a server', () => {
    const binDir = path.join(tempDir, 'bin')
    fs.mkdirSync(binDir, { recursive: true })
    const execPath = path.join(binDir, 'java-ls')
    fs.writeFileSync(execPath, '#!/bin/sh\nexit 0\n', { mode: 0o755 })

    const javaFile = path.join(tempDir, 'App.java')
    fs.writeFileSync(javaFile, 'class App {}\n')
    const pyFile = path.join(tempDir, 'App.py')
    fs.writeFileSync(pyFile, 'print(1)\n')

    const installed = LspDiscovery.of([
      {
        id: 'java',
        command: [execPath],
        extensions: ['.java'],
        rootMarkers: [],
        firstMatchMarkers: [],
      },
    ])
    const supportAvailable = installed.support(javaFile)
    expect(supportAvailable.supported).toBe(true)
    expect(supportAvailable.available).toBe(true)
    expect(supportAvailable.language).toBe('java')

    const supportPy = installed.support(pyFile)
    expect(supportPy.supported).toBe(false)
    expect(supportPy.available).toBe(false)
    expect(supportPy.language).toBeNull()

    const missing = LspDiscovery.of([
      {
        id: 'java',
        command: [path.join(tempDir, 'missing-bin')],
        extensions: ['.java'],
        rootMarkers: [],
        firstMatchMarkers: [],
      },
    ])
    const supportMissing = missing.support(javaFile)
    expect(supportMissing.supported).toBe(true)
    expect(supportMissing.available).toBe(false)
    expect(supportMissing.language).toBe('java')

    expect(() => missing.requireServer(pyFile)).toThrow(/No LSP server configured/)
    expect(() => missing.requireExecutable(missing.servers()[0]!)).toThrow(/is not installed/)
  })

  it('resolves absolute, ~, and $HOME commands and rejects non-executable', () => {
    const fakeHome = path.join(tempDir, 'home')
    const binDir = path.join(fakeHome, 'bin')
    fs.mkdirSync(binDir, { recursive: true })
    const execPath = path.join(binDir, 'home-ls')
    fs.writeFileSync(execPath, '#!/bin/sh\nexit 0\n', { mode: 0o755 })

    const platform = { home: fakeHome }
    expect(ExecutableResolver.resolve(execPath, platform)).toBe(execPath)
    expect(ExecutableResolver.resolve('~/bin/home-ls', platform)).toBe(execPath)
    expect(ExecutableResolver.resolve('$HOME/bin/home-ls', platform)).toBe(execPath)
    expect(ExecutableResolver.resolve('${HOME}/bin/home-ls', platform)).toBe(execPath)
    expect(ExecutableResolver.resolve('relative/bin', platform)).toBeUndefined()
  })

  it('resolves commands on PATH and supports windows PATHEXT simulation', () => {
    const binDir = path.join(tempDir, 'bin')
    fs.mkdirSync(binDir, { recursive: true })

    const posixCmd = path.join(binDir, 'mycmd')
    fs.writeFileSync(posixCmd, '#!/bin/sh\nexit 0\n', { mode: 0o755 })

    // POSIX path resolution
    expect(
      ExecutableResolver.resolve('mycmd', {
        windows: false,
        pathVariable: `${tempDir}/nope:${binDir}`,
      }),
    ).toBe(posixCmd)

    // Windows PATHEXT simulation
    const winCmd = path.join(binDir, 'mytool.cmd')
    fs.writeFileSync(winCmd, '@echo off\n')
    expect(
      ExecutableResolver.resolve('mytool', {
        windows: true,
        pathVariable: binDir,
        pathext: '.CMD;.EXE',
      }),
    ).toBe(winCmd)
  })

  it('handles empty configurations and empty commands gracefully', () => {
    const emptyDisc = LspDiscovery.fromConfiguration(undefined)
    expect(emptyDisc.servers()).toHaveLength(0)

    const explicitEmpty = LspDiscovery.empty()
    expect(explicitEmpty.servers()).toHaveLength(0)

    const noCmdServer: ResolvedServerConfig = {
      id: 'empty',
      command: [],
      extensions: ['.txt'],
      rootMarkers: [],
      firstMatchMarkers: [],
    }
    const disc = LspDiscovery.of([noCmdServer])
    expect(disc.executable(noCmdServer)).toBeUndefined()
    expect(() => disc.requireExecutable(noCmdServer)).toThrow(/is not installed/)
  })

  it('rejects directories as executable and handles root directory in workspaceRoot', () => {
    // Directory cannot be executed
    expect(ExecutableResolver.resolve(tempDir)).toBeUndefined()

    // Windows simulation where file exists
    const testFile = path.join(tempDir, 'file.txt')
    fs.writeFileSync(testFile, 'hello')
    expect(ExecutableResolver.resolve(testFile, { windows: true })).toBe(testFile)

    const server: ResolvedServerConfig = {
      id: 'test',
      command: ['node'],
      extensions: ['.txt'],
      rootMarkers: ['pom.xml'],
      firstMatchMarkers: [],
    }
    const disc = LspDiscovery.of([server])
    expect(disc.workspaceRoot(server, '/')).toBe(path.resolve('/'))
  })
})
