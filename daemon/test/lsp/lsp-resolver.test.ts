import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { ExecutableResolver } from '../../src/coding/lsp-discovery.js'

describe('ExecutableResolver Unit Tests', () => {
  let tempDir: string
  let homeDir: string
  let binDir: string

  beforeEach(() => {
    tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'lsp-res-test-'))
    homeDir = path.join(tempDir, 'fake-home')
    binDir = path.join(tempDir, 'bin')
    fs.mkdirSync(homeDir, { recursive: true })
    fs.mkdirSync(binDir, { recursive: true })
  })

  afterEach(() => {
    try {
      fs.rmSync(tempDir, { recursive: true, force: true })
    } catch {}
  })

  it('expands ~, ~/, $HOME/, and ${HOME}/ correctly', () => {
    expect(ExecutableResolver.expandHome('~', homeDir)).toBe(homeDir)
    expect(ExecutableResolver.expandHome('~/bin/tool', homeDir)).toBe(path.join(homeDir, 'bin/tool'))
    expect(ExecutableResolver.expandHome('~\\bin\\tool', homeDir)).toBe(path.join(homeDir, 'bin\\tool'))
    expect(ExecutableResolver.expandHome('$HOME/bin/tool', homeDir)).toBe(path.join(homeDir, 'bin/tool'))
    expect(ExecutableResolver.expandHome('${HOME}/bin/tool', homeDir)).toBe(path.join(homeDir, 'bin/tool'))
    expect(ExecutableResolver.expandHome('other/path', homeDir)).toBe('other/path')
  })

  it('handles empty, blank, and invalid pathVariable elements', () => {
    expect(ExecutableResolver.resolve('tool', { pathVariable: '' })).toBeUndefined()
    expect(ExecutableResolver.resolve('tool', { pathVariable: '   ' })).toBeUndefined()
    expect(ExecutableResolver.resolve('tool', { pathVariable: undefined })).toBeUndefined()

    // PATH containing blank elements (e.g. "::" or leading/trailing colons)
    expect(ExecutableResolver.resolve('tool', { windows: false, pathVariable: `::${tempDir}/nope::` })).toBeUndefined()
  })

  it('rejects non-executable POSIX files and returns undefined when not found on PATH', () => {
    const nonExec = path.join(binDir, 'no-exec-tool')
    fs.writeFileSync(nonExec, '#!/bin/sh\n', { mode: 0o644 }) // not executable

    const resolved = ExecutableResolver.resolve('no-exec-tool', {
      windows: false,
      pathVariable: binDir,
    })
    expect(resolved).toBeUndefined()

    // Completely missing on PATH
    expect(ExecutableResolver.resolve('missing-tool-xyz', { windows: false, pathVariable: binDir })).toBeUndefined()
  })

  it('handles Windows PATHEXT recognized suffix and appended suffix searches', () => {
    // 1. Command already has recognized suffix (e.g. "mycmd.exe")
    const exeFile = path.join(binDir, 'mycmd.exe')
    fs.writeFileSync(exeFile, 'binary')

    const resolvedWithExt = ExecutableResolver.resolve('mycmd.exe', {
      windows: true,
      pathVariable: binDir,
      pathext: '.EXE;.CMD',
    })
    expect(resolvedWithExt).toBe(path.normalize(exeFile))

    // Recognized suffix but file does not exist
    const missingWithExt = ExecutableResolver.resolve('notfound.exe', {
      windows: true,
      pathVariable: binDir,
      pathext: '.EXE;.CMD',
    })
    expect(missingWithExt).toBeUndefined()

    // 2. Command without suffix matches appended suffix
    const cmdFile = path.join(binDir, 'script.cmd')
    fs.writeFileSync(cmdFile, '@echo off')

    const resolvedAppended = ExecutableResolver.resolve('script', {
      windows: true,
      pathVariable: binDir,
      pathext: '.EXE;.CMD',
    })
    expect(resolvedAppended).toBe(path.normalize(cmdFile))

    // Command without suffix but no matching extension exists
    const missingBare = ExecutableResolver.resolve('nonexistent', {
      windows: true,
      pathVariable: binDir,
      pathext: '.EXE;.CMD',
    })
    expect(missingBare).toBeUndefined()
  })

  it('rejects relative path containing separators', () => {
    expect(ExecutableResolver.resolve('some/relative/tool')).toBeUndefined()
    expect(ExecutableResolver.resolve('.\\relative\\tool')).toBeUndefined()
  })

  it('resolves ~ absolute command when executable exists', () => {
    const homeTool = path.join(homeDir, 'tool')
    fs.writeFileSync(homeTool, '#!/bin/sh\n', { mode: 0o755 })

    const resolved = ExecutableResolver.resolve('~/tool', {
      windows: false,
      home: homeDir,
    })
    expect(resolved).toBe(path.normalize(homeTool))
  })
})
