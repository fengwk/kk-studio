import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'

import type { CapabilityContext, DaemonConfig, LspServerConfig, ResourceUploader } from '../../src/contracts.js'
import { createLspFacade, type LspFacade } from '../../src/coding/lsp.js'
import { fakeDaemonLspConfig, awaitEvent } from './fake-lsp-helper.js'
import { TestProcessSupervisor } from './test-process-supervisor.js'

const uploader: ResourceUploader = {
  upload: async () => {
    throw new Error('unused')
  },
}

function config(root: string, lsp: Record<string, LspServerConfig>): DaemonConfig {
  return {
    environmentId: '11111111-2222-4333-8444-555555555555',
    configVersion: 0n,
    configPath: path.join(root, 'daemon.json'),
    dataDir: root,
    tokenPath: path.join(root, 'daemon.token'),
    studioUrl: 'https://studio.example.com',
    gatewayUri: 'wss://studio.example.com/ws/daemon',
    bashExecutable: process.execPath,
    bashExecutableSetting: null,
    lsp,
    proxyUrl: null,
    distributionDir: root,
  }
}

describe('LSP generation hot swap', () => {
  let tempDir = ''
  let supervisor: TestProcessSupervisor
  let group: LspFacade | null = null

  beforeEach(() => {
    tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'lsp-facade-test-'))
    tempDir = fs.realpathSync.native(tempDir)
    supervisor = new TestProcessSupervisor()
  })

  afterEach(async () => {
    try {
      await group?.close()
      group = null
      await supervisor.close()
    } finally {
      if (tempDir) {
        fs.rmSync(tempDir, { recursive: true, force: true })
      }
    }
  })

  // 新调用立即使用新 generation；旧 generation 的在途执行结束后才关闭，禁用后新请求立即不可用。
  it('keeps the old generation alive until its in-flight call finishes and disables new calls immediately', async () => {
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')
    const slowTranscript = path.join(tempDir, 'slow.jsonl')
    const fastTranscript = path.join(tempDir, 'fast.jsonl')
    const initial = config(tempDir, fakeDaemonLspConfig('java', 'slow-definition', slowTranscript))
    group = createLspFacade(supervisor)
    void group.update(initial.lsp)
    const controller = new AbortController()
    const context: CapabilityContext = {
      config: initial,
      maxOutputBytes: 16 * 1024 * 1024,
      signal: controller.signal,
      processes: supervisor,
      resources: uploader,
      progress: async () => {},
      confirmExecutionExit: () => {},
    }
    const oldCall = group.capabilities['lsp.goto-definition']!(
      {
        invocationId: 'old',
        capabilityId: 'lsp.goto-definition',
        arguments: { path: file, line: 1 },
        timeoutMillis: 10_000,
      },
      context,
    )
    await awaitEvent(
      slowTranscript,
      (event) => event.event === 'recv' && event.message?.method === 'textDocument/definition',
    )
    const oldChild = supervisor.children[0]!
    expect(oldChild.exitCode).toBeNull()

    const next = config(tempDir, fakeDaemonLspConfig('java', 'normal', fastTranscript))
    const firstRetirement = group.prepare(next.lsp).publish()
    const fresh = await group.capabilities['lsp.goto-definition']!(
      {
        invocationId: 'new',
        capabilityId: 'lsp.goto-definition',
        arguments: { path: file, line: 1 },
        timeoutMillis: 10_000,
      },
      { ...context, config: next },
    )
    expect(fresh.error).toBe(false)
    await awaitEvent(
      fastTranscript,
      (event) => event.event === 'recv' && event.message?.method === 'textDocument/definition',
    )
    // 新 generation 已完成调用，旧 generation 的执行仍未结束，因此旧子进程必须继续存活。
    expect(oldChild.exitCode).toBeNull()

    const secondRetirement = group.prepare({}).publish()
    const disabled = await group.capabilities['lsp.goto-definition']!(
      {
        invocationId: 'disabled',
        capabilityId: 'lsp.goto-definition',
        arguments: { path: file, line: 1 },
        timeoutMillis: 5_000,
      },
      { ...context, config: { ...next, lsp: {} } },
    )
    expect(disabled.error).toBe(true)
    expect((disabled.contents[0] as { text: string }).text).toContain('LSP is not configured')
    await secondRetirement

    controller.abort()
    const oldResult = await oldCall
    expect(oldResult.error).toBe(true)
    expect((oldResult.contents[0] as { text: string }).text).toBe('Error: Operation cancelled')
    await firstRetirement
    expect(oldChild.exitCode ?? oldChild.signalCode).not.toBeNull()
  })
})
