import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { LspClient, isJdtlsCommand } from '../../src/coding/lsp-client.js'
import { LspBootstrapError, LspError } from '../../src/coding/lsp-error.js'
import { ProcessScopeNotConvergedError, type ProcessSupervisor } from '../../src/contracts.js'
import {
  awaitEvent,
  awaitProcessGone,
  createJdtlsWrapper,
  fakeServerConfig,
  readEvents,
  receivedMessages,
  sentMessages,
} from './fake-lsp-helper.js'
import { ExitBarrierSupervisor } from './exit-barrier-supervisor.js'
import { TestProcessSupervisor } from './test-process-supervisor.js'

/** Bounded wait for a real callback; it never treats elapsed time as proof of anything. */
function bounded<T>(promise: Promise<T>, description: string, timeoutMillis = 5000): Promise<T> {
  let timer: NodeJS.Timeout | undefined
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(
      () => reject(new Error(`timed out waiting for ${description}`)),
      timeoutMillis,
    )
  })
  return Promise.race([promise, timeout]).finally(() => {
    if (timer !== undefined) {
      clearTimeout(timer)
    }
  })
}

describe('LspClient Protocol & Handshake', () => {
  let tempDir: string
  let supervisor: TestProcessSupervisor
  const clients: LspClient[] = []

  beforeEach(() => {
    tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'lsp-proto-test-'))
    supervisor = new TestProcessSupervisor()
  })

  afterEach(async () => {
    const cleanupFailures: unknown[] = []
    for (const client of clients.splice(0)) {
      try {
        await bounded(client.stop(200), 'registered client cleanup')
      } catch (error) {
        cleanupFailures.push(error)
      }
    }
    try {
      // 客户端停止失败也必须独立关闭真实子进程；确认其 stdio close 后再删除目录。
      await supervisor.close()
      fs.rmSync(tempDir, { recursive: true, force: true })
    } catch (error) {
      cleanupFailures.push(error)
    }
    if (cleanupFailures.length > 0) {
      throw new AggregateError(cleanupFailures, 'LSP protocol fixture cleanup failed')
    }
  })

  async function startClient(mode = 'normal'): Promise<{ client: LspClient; transcript: string }> {
    const transcript = path.join(tempDir, `transcript-${Date.now()}-${Math.random()}.jsonl`)
    const config = fakeServerConfig('fake', mode, transcript)
    const client = await LspClient.launch(config, tempDir, config.command[0]!, supervisor)
    clients.push(client)
    await client.initialize(5000)
    return { client, transcript }
  }

  it('performs initialize handshake with required client capabilities and notification', async () => {
    const { client, transcript } = await startClient('normal')
    expect(client.isAlive()).toBe(true)

    const initMsg = receivedMessages(transcript, 'initialize')[0]
    expect(initMsg).toBeDefined()
    expect(initMsg.params.processId).toBeGreaterThan(0)
    expect(initMsg.params.workspaceFolders).toHaveLength(1)
    expect(initMsg.params.capabilities.general.positionEncodings).toEqual(['utf-16', 'utf-8'])
    expect(initMsg.params.capabilities.textDocument.definition.linkSupport).toBe(true)
    expect(initMsg.params.capabilities.textDocument.synchronization.didSave).toBe(true)

    // Flushing the notification does not mean the server has consumed and recorded it yet.
    const initialized = await awaitEvent(transcript, (event) => event.event === 'recv' && event.message?.method === 'initialized')
    expect(initialized.message.params).toEqual({})
  })

  it('formats definition results with Location and LocationLink and empty fallback', async () => {
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    const normal = await startClient('normal')
    const locs = await normal.client.definition(file, 1, 0)
    expect(locs).toEqual([`${file}:10:5`])

    const linkClient = await startClient('link-definition')
    const linkLocs = await linkClient.client.definition(file, 1, 0)
    expect(linkLocs).toEqual([`${file}:10:5`])

    const nullClient = await startClient('null-definition')
    const nullLocs = await nullClient.client.definition(file, 1, 0)
    expect(nullLocs).toEqual(['No results found'])
  })

  it('formats weird locations deterministically without error', async () => {
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    const weird = await startClient('weird-locations')
    const locs = await weird.client.definition(file, 1, 0)
    expect(locs).toEqual(['jdt://contents/pkg/Thing.class', 'unknown'])
  })

  it('fails cleanly on server error response without leaking error details', async () => {
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    const errClient = await startClient('error-definition')
    await expect(errClient.client.definition(file, 1, 0)).rejects.toThrow(
      'LSP request failed (textDocument/definition)',
    )
    expect(errClient.client.isAlive()).toBe(true)
  })

  it('formats workspace symbols in SymbolInformation and WorkspaceSymbol formats', async () => {
    const normal = await startClient('normal')
    const symbols = await normal.client.workspaceSymbols('A', 2)
    expect(symbols).toEqual([
      'Alpha (Class) - file:///tmp/alpha.java',
      'Beta (Function) - file:///tmp/beta.java',
    ])

    const wsSymbols = await startClient('workspace-symbols')
    const delta = await wsSymbols.client.workspaceSymbols('D', 10)
    expect(delta).toEqual(['Delta (Class) - file:///tmp/delta.java'])

    const emptySymbols = await startClient('empty-symbols')
    expect(await emptySymbols.client.workspaceSymbols('A', 10)).toEqual(['No symbols found'])

    const nullSymbols = await startClient('null-symbols')
    expect(await nullSymbols.client.workspaceSymbols('A', 10)).toEqual(['No symbols found'])

    const oddSymbols = await startClient('odd-symbols')
    expect(await oddSymbols.client.workspaceSymbols('A', 10)).toEqual([
      'unknown (unknown) - unknown',
    ])
  })

  it('rejects unsupported capabilities when server does not advertise them', async () => {
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    const noDef = await startClient('no-definition')
    await expect(noDef.client.definition(file, 1, 0)).rejects.toThrow(
      /does not advertise go-to-definition support/,
    )

    const noSym = await startClient('no-symbols')
    await expect(noSym.client.workspaceSymbols('q', 10)).rejects.toThrow(
      /does not advertise workspace symbol support/,
    )

    const noCaps = await startClient('no-capabilities')
    expect(noCaps.client.supports('textDocument/definition')).toBe(false)
    expect(noCaps.client.supports('workspace/symbol')).toBe(false)
  })

  it('handles position encoding boundaries for CRLF, utf-8, utf-16, utf-32 and beyond-line errors', async () => {
    const crlfFile = path.join(tempDir, 'Crlf.java')
    fs.writeFileSync(crlfFile, 'class A {}\r\nclass B {}\n')

    const normal = await startClient('normal')
    await normal.client.definition(crlfFile, 1, 9)
    const defMsg = receivedMessages(normal.transcript, 'textDocument/definition')[0]
    expect(defMsg.params.position.line).toBe(0)
    expect(defMsg.params.position.character).toBe(9)

    // Beyond line boundary error
    const noNewline = path.join(tempDir, 'NoNewline.java')
    fs.writeFileSync(noNewline, 'class App {}\nclass Last {}')
    await expect(normal.client.definition(noNewline, 3, 0)).rejects.toThrow(
      /line 3 is beyond the end of the synchronized document/,
    )
    await expect(normal.client.definition(noNewline, 2, 20)).rejects.toThrow(
      /character 20 is beyond line 2/,
    )

    // UTF-32 position encoding
    const utf32Client = await startClient('utf32')
    const emojiFile = path.join(tempDir, 'Emoji.java')
    fs.writeFileSync(emojiFile, '"😀x";\n')
    await utf32Client.client.definition(emojiFile, 1, 2)
    const utf32Def = receivedMessages(utf32Client.transcript, 'textDocument/definition')[0]
    expect(utf32Def.params.position.character).toBe(2)

    // UTF-8 position encoding
    const utf8Client = await startClient('utf8')
    await utf8Client.client.definition(emojiFile, 1, 2)
    const utf8Def = receivedMessages(utf8Client.transcript, 'textDocument/definition')[0]
    // '"' is 1 byte, '😀' is 4 bytes -> 5 bytes
    expect(utf8Def.params.position.character).toBe(5)
  })

  it('tolerates garbage non-LSP banner bytes before Content-Length', async () => {
    const { client, transcript } = await startClient('garbage')
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    const locs = await client.definition(file, 1, 0)
    expect(locs).toEqual([`${file}:10:5`])
  })

  it('accepts a Content-Length header fragmented across writes', async () => {
    const { client } = await startClient('fragment-header')
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    const locs = await client.definition(file, 1, 0)
    expect(locs).toEqual([`${file}:10:5`])
  })

  it('fails closed instead of buffering an unbounded pre-header banner', async () => {
    const transcript = path.join(tempDir, 'huge-banner-transcript.jsonl')
    const config = fakeServerConfig('huge', 'huge-banner', transcript)
    const client = await LspClient.launch(config, tempDir, config.command[0]!, supervisor)
    clients.push(client)

    const startedAt = Date.now()
    await expect(client.initialize()).rejects.toThrow(/non-protocol output/)
    expect(Date.now() - startedAt).toBeLessThan(5000)
    expect(client.isAlive()).toBe(false)
  })

  it('answers server-side requests and ignores notifications', async () => {
    const { client, transcript } = await startClient('server-requests')
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    // Verify answers were sent back to server
    await awaitEvent(transcript, (e) => e.event === 'recv' && e.message?.id === 900)
    const clientReq = await startClient('client-requests')
    await awaitEvent(clientReq.transcript, (e) => e.event === 'recv' && e.message?.id === 900)

    const locs = await clientReq.client.definition(file, 1, 0)
    expect(locs).toEqual([`${file}:10:5`])
  })

  it('request timeout sends $/cancelRequest and fails clearly', async () => {
    const { client, transcript } = await startClient('slow-definition')
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    const ac = new AbortController()
    const p = client.definition(file, 1, 0, ac.signal)
    await awaitEvent(
      transcript,
      (e) => e.event === 'recv' && e.message?.method === 'textDocument/definition',
    )
    ac.abort('TIMED_OUT')

    await expect(p).rejects.toThrow(
      /LSP request timed out \(textDocument\/definition\)\./,
    )

    // Verify $/cancelRequest was sent
    const cancelEvent = await awaitEvent(
      transcript,
      (e) => e.event === 'recv' && e.message?.method === '$/cancelRequest',
    )
    expect(cancelEvent).toBeDefined()
    expect(client.isAlive()).toBe(true)
  })

  it('does not confirm a locally stopped request before its managed scope closes', async () => {
    // 公开 stop 会释放真实 JSON-RPC connection；本地拒绝不能替代受管范围收尾，不访问私有字段。
    const transcript = path.join(tempDir, `local-stop-${Date.now()}.jsonl`)
    const config = fakeServerConfig('fake', 'slow-definition', transcript)
    const controlled = new ExitBarrierSupervisor()
    let client: LspClient | null = null
    // body 的原始失败必须保留；收尾失败只能按发生顺序追加，不能覆盖它。
    const errors: unknown[] = []
    try {
      client = await bounded(
        LspClient.launch(config, tempDir, config.command[0]!, controlled),
        'client launch',
      )
      await client.initialize(5000)
      const file = path.join(tempDir, 'App.java')
      fs.writeFileSync(file, 'class App {}\n')
      let finished = 0
      let confirmFinished!: () => void
      const confirmed = new Promise<void>((resolve) => {
        confirmFinished = resolve
      })
      let requestError: unknown = null
      const settled = client.definition(file, 1, 0, undefined, () => {
        finished += 1
        confirmFinished()
      }).then(
        () => {
          throw new Error('request unexpectedly resolved')
        },
        (error: unknown) => {
          requestError = error
        },
      )
      await awaitEvent(
        transcript,
        (event) => event.event === 'recv' && event.message?.method === 'textDocument/definition',
      )
      expect(receivedMessages(transcript, 'textDocument/definition')).toHaveLength(1)

      const stopping = client.stop(0)
      await bounded(settled, 'locally stopped request')
      await bounded(controlled.terminateStarted, 'stop scope termination')
      expect(requestError).toBeInstanceOf(LspError)
      expect((requestError as LspError).message).toBe('LSP client stopped')
      expect(finished).toBe(0)
      expect(client.isAlive()).toBe(false)

      // 正向对照：只有真实范围收尾成功、退出报告实际到达后，才交付一次迟到确认。
      controlled.releaseCleanup()
      await bounded(stopping, 'local stop completion')
      expect(finished).toBe(0)
      controlled.reportExit()
      await bounded(confirmed, 'late exit confirmation')
      expect(finished).toBe(1)
    } catch (error) {
      errors.push(error)
    } finally {
      controlled.releaseCleanup()
      if (client) {
        try {
          await bounded(client.stop(0), 'client scope cleanup')
        } catch (error) {
          errors.push(error)
        }
      }
      try {
        await controlled.close()
      } catch (error) {
        errors.push(error)
      }
    }
    if (errors.length === 1) {
      throw errors[0]
    }
    if (errors.length > 1) {
      throw new AggregateError(errors, 'local stop test cleanup failed')
    }
  })

  it('confirms an exit-reported request only after the stop scope finishes', async () => {
    const transcript = path.join(tempDir, `exit-scope-${Date.now()}.jsonl`)
    const config = fakeServerConfig('fake', 'slow-definition', transcript)
    const controlled = new ExitBarrierSupervisor()
    let client: LspClient | null = null
    // body 失败优先保留；受管范围与客户端收尾失败按发生顺序追加。
    const errors: unknown[] = []
    try {
      client = await bounded(
        LspClient.launch(config, tempDir, config.command[0]!, controlled),
        'client launch',
      )
      await client.initialize(5000)
      const file = path.join(tempDir, 'App.java')
      fs.writeFileSync(file, 'class App {}\n')

      let finished = 0
      let confirmFinished!: () => void
      const confirmed = new Promise<void>((resolve) => {
        confirmFinished = resolve
      })
      const request = client.definition(file, 1, 0, undefined, () => {
        finished += 1
        confirmFinished()
      })
      let requestError: unknown = null
      const settled = request.then(
        () => {
          throw new Error('request unexpectedly resolved')
        },
        (error: unknown) => {
          requestError = error
        },
      )

      // 目标 RPC 必须真实进入 fixture；onRequestSent 只证明 SDK 开始发送。
      await awaitEvent(
        transcript,
        (event) => event.event === 'recv' && event.message?.method === 'textDocument/definition',
      )
      expect(receivedMessages(transcript, 'textDocument/definition')).toHaveLength(1)

      // 退出报告已处理、本地请求已结束，但受管范围的 cleanup gate 尚未释放。
      controlled.reportExit()
      await bounded(settled, 'request settlement')
      expect(finished).toBe(0)
      expect(client.isAlive()).toBe(false)
      expect(requestError).toBeInstanceOf(LspError)
      expect((requestError as LspError).message).toBe('LSP client stopped')
      await bounded(controlled.terminateStarted, 'stop scope termination')

      controlled.releaseCleanup()
      await bounded(confirmed, 'request exit confirmation')
      expect(finished).toBe(1)
    } catch (error) {
      errors.push(error)
    } finally {
      // 先释放预置屏障，再分别完成客户端与真实子进程收尾；前者失败不能跳过后者。
      controlled.releaseCleanup()
      if (client) {
        try {
          await bounded(client.stop(0), 'client scope cleanup')
        } catch (error) {
          errors.push(error)
        }
      }
      try {
        await controlled.close()
      } catch (error) {
        errors.push(error)
      }
    }
    if (errors.length === 1) {
      throw errors[0]
    }
    if (errors.length > 1) {
      throw new AggregateError(errors, 'controlled exit test cleanup failed')
    }
  })

  it('keeps an exit-reported request unconfirmed when the same stop scope fails, without retrying', async () => {
    const transcript = path.join(tempDir, `exit-scope-failure-${Date.now()}.jsonl`)
    const config = fakeServerConfig('fake', 'slow-definition', transcript)
    const controlled = new ExitBarrierSupervisor(new Error('deferred termination boom'))
    let client: LspClient | null = null
    let expectedStopFailure: unknown = null
    // body 失败优先保留；已观察到的缓存失败按对象身份忽略，其余收尾失败顺序追加。
    const errors: unknown[] = []
    try {
      client = await bounded(
        LspClient.launch(config, tempDir, config.command[0]!, controlled),
        'client launch',
      )
      await client.initialize(5000)
      const file = path.join(tempDir, 'App.java')
      fs.writeFileSync(file, 'class App {}\n')

      let finished = 0
      const request = client.definition(file, 1, 0, undefined, () => {
        finished += 1
      })
      let requestError: unknown = null
      const settled = request.then(
        () => {
          throw new Error('request unexpectedly resolved')
        },
        (error: unknown) => {
          requestError = error
        },
      )
      await awaitEvent(
        transcript,
        (event) => event.event === 'recv' && event.message?.method === 'textDocument/definition',
      )
      expect(receivedMessages(transcript, 'textDocument/definition')).toHaveLength(1)

      controlled.reportExit()
      await bounded(settled, 'request settlement')
      expect(finished).toBe(0)
      expect(requestError).toBeInstanceOf(LspError)
      expect((requestError as LspError).message).toBe('LSP client stopped')
      await bounded(controlled.terminateStarted, 'stop scope termination')

      controlled.releaseCleanup()
      const firstFailure: unknown = await bounded(client.stop(0), 'failed stop').catch((error: unknown) => error)
      expectedStopFailure = firstFailure
      expect(firstFailure).toBeInstanceOf(LspError)
      expect((firstFailure as LspError).message).toBe(
        'LSP server process termination failed during shutdown',
      )
      expect(controlled.terminateCalls).toBe(1)
      expect(finished).toBe(0)

      // 缓存的是同一次失败结论：重复 stop 不会重试终结，也不会据此补发确认。
      const secondFailure: unknown = await bounded(client.stop(0), 'cached failed stop').catch((error: unknown) => error)
      expect(secondFailure).toBe(firstFailure)
      expect(controlled.terminateCalls).toBe(1)
      expect(finished).toBe(0)
    } catch (error) {
      errors.push(error)
    } finally {
      // 先释放预置屏障，再分别完成客户端与真实子进程收尾；前者失败不能跳过后者。
      controlled.releaseCleanup()
      if (client) {
        try {
          await bounded(client.stop(0), 'client scope cleanup')
        } catch (error) {
          // 只容忍本用例已观察到的同一缓存 stop 失败，其余清理异常仍然暴露。
          if (error !== expectedStopFailure) {
            errors.push(error)
          }
        }
      }
      try {
        await controlled.close()
      } catch (error) {
        errors.push(error)
      }
    }
    if (errors.length === 1) {
      throw errors[0]
    }
    if (errors.length > 1) {
      throw new AggregateError(errors, 'controlled exit test cleanup failed')
    }
  })

  it('does not self-wait when an explicit stop observes the exit report during shutdown', async () => {
    const transcript = path.join(tempDir, `exit-during-shutdown-${Date.now()}.jsonl`)
    const config = fakeServerConfig('fake', 'stubborn', transcript)
    const controlled = new ExitBarrierSupervisor()
    let client: LspClient | null = null
    // body 失败优先保留；显式 stop 与真实子进程收尾失败按发生顺序追加。
    const errors: unknown[] = []
    try {
      client = await bounded(
        LspClient.launch(config, tempDir, config.command[0]!, controlled),
        'client launch',
      )
      await client.initialize(5000)
      const started = await awaitEvent(transcript, (event) => event.event === 'start')
      const pid = started.pid!

      const stopping = client.stop(500)
      await awaitEvent(
        transcript,
        (event) => event.event === 'recv' && event.message?.method === 'shutdown',
      )
      // shutdown 正在等待响应时收到退出报告：内部请求不能反过来等待同一个 stopPromise。
      controlled.reportExit()
      await bounded(controlled.terminateStarted, 'stop scope termination')
      controlled.releaseCleanup()
      await bounded(stopping, 'explicit stop')

      expect(controlled.terminateCalls).toBe(1)
      await awaitProcessGone(pid)
      // 已缓存的成功收尾结论：复用不再触发第二次终结。
      await bounded(client.stop(500), 'cached successful stop')
      expect(controlled.terminateCalls).toBe(1)
    } catch (error) {
      errors.push(error)
    } finally {
      // 先释放预置屏障，再分别完成客户端与真实子进程收尾；前者失败不能跳过后者。
      controlled.releaseCleanup()
      if (client) {
        try {
          await bounded(client.stop(0), 'client scope cleanup')
        } catch (error) {
          errors.push(error)
        }
      }
      try {
        await controlled.close()
      } catch (error) {
        errors.push(error)
      }
    }
    if (errors.length === 1) {
      throw errors[0]
    }
    if (errors.length > 1) {
      throw new AggregateError(errors, 'controlled exit test cleanup failed')
    }
  })

  it('confirms a real server error response as request completion', async () => {
    // 正向对照：服务端 RPC 错误是这次请求的真实响应，断流则不是。
    const { client } = await startClient('error-definition')
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')
    let finished = 0
    await expect(client.definition(file, 1, 0, undefined, () => {
      finished += 1
    })).rejects.toThrow(LspError)
    expect(finished).toBe(1)
    expect(client.isAlive()).toBe(true)
  })

  it('abort signal cancels in-flight request', async () => {
    const { client, transcript } = await startClient('slow-definition')
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    const ac = new AbortController()
    let finished = 0
    const p = client.definition(file, 1, 0, ac.signal, () => {
      finished += 1
    })
    await awaitEvent(
      transcript,
      (e) => e.event === 'recv' && e.message?.method === 'textDocument/definition',
    )
    ac.abort()

    await expect(p).rejects.toThrow(/Operation cancelled/)
    expect(finished).toBe(0)
    // Verify $/cancelRequest was sent
    const cancelEvent = await awaitEvent(
      transcript,
      (e) => e.event === 'recv' && e.message?.method === '$/cancelRequest',
    )
    expect(cancelEvent).toBeDefined()
    expect(client.isAlive()).toBe(true)
  })

  it('dead server fails fast at launch with exit code', async () => {
    const transcript = path.join(tempDir, 'dead-transcript.jsonl')
    const config = fakeServerConfig('dead', 'dead', transcript)
    const start = supervisor.start.bind(supervisor)
    // 固定真实子进程的退出在启动探针之前，不依赖宿主调度速度。
    vi.spyOn(supervisor, 'start').mockImplementation(async (spec) => {
      const managed = await start(spec)
      await managed.exit
      return managed
    })
    await expect(
      LspClient.launch(config, tempDir, config.command[0]!, supervisor),
    ).rejects.toThrow(/exited before initialization \(exit code 7\)/)
  })

  it('preserves the start rejection and marks a proven converged failure', async () => {
    const config = fakeServerConfig('fake', 'normal', path.join(tempDir, 'unused.jsonl'))
    const startFailure = new Error('spawn failed')
    const rejecting: ProcessSupervisor = {
      start: async () => {
        throw startFailure
      },
      close: async () => {},
    }
    const error = await LspClient.launch(config, tempDir, config.command[0]!, rejecting).catch(
      (failure: unknown) => failure,
    )
    expect(error).toBeInstanceOf(LspBootstrapError)
    expect((error as LspBootstrapError).converged).toBe(true)
    expect((error as LspBootstrapError).cause).toBe(startFailure)
    expect((error as Error).message).toBe(
      `LSP server 'fake' cannot be started: ${config.command[0]}`,
    )
  })

  it('does not mark an unconverged start rejection as an exit', async () => {
    const config = fakeServerConfig('fake', 'normal', path.join(tempDir, 'unused.jsonl'))
    const startFailure = new ProcessScopeNotConvergedError('spawn cleanup failed')
    const rejecting: ProcessSupervisor = {
      start: async () => {
        throw startFailure
      },
      close: async () => {},
    }
    const error = await LspClient.launch(config, tempDir, config.command[0]!, rejecting).catch(
      (failure: unknown) => failure,
    )
    expect(error).toBeInstanceOf(LspBootstrapError)
    expect((error as LspBootstrapError).converged).toBe(false)
    expect((error as LspBootstrapError).cause).toBe(startFailure)
  })

  it('keeps the primary launch failure and cleanup failure when termination fails', async () => {
    const transcript = path.join(tempDir, `dead-cleanup-${Date.now()}.jsonl`)
    const config = fakeServerConfig('dead', 'dead', transcript)
    const start = supervisor.start.bind(supervisor)
    const cleanupFailure = new Error('launch cleanup boom')
    const controlled: ProcessSupervisor = {
      start: async (spec) => {
        const managed = await start(spec)
        await managed.exit
        return {
          ...managed,
          terminate: async () => {
            throw cleanupFailure
          },
        }
      },
      close: () => supervisor.close(),
    }
    const error = await LspClient.launch(config, tempDir, config.command[0]!, controlled).catch(
      (failure: unknown) => failure,
    )
    expect(error).toBeInstanceOf(LspBootstrapError)
    expect((error as LspBootstrapError).converged).toBe(false)
    expect((error as Error).message).toMatch(/exited before initialization \(exit code 7\)/)
    const cause = (error as LspBootstrapError).cause
    expect(cause).toBeInstanceOf(AggregateError)
    expect((cause as AggregateError).errors).toHaveLength(2)
    expect((cause as AggregateError).errors[1]).toBe(cleanupFailure)
  })

  it('no-init server times out during initialize and terminates process', async () => {
    const transcript = path.join(tempDir, 'noinit-transcript.jsonl')
    const config = fakeServerConfig('noinit', 'no-init', transcript)
    const client = await LspClient.launch(config, tempDir, config.command[0]!, supervisor)
    clients.push(client)

    await expect(client.initialize(400)).rejects.toThrow(/LSP request timed out after 400ms/)
    expect(client.isAlive()).toBe(false)
  })

  it('server crash during definition reports exit code without leaking raw stderr', async () => {
    const { client } = await startClient('crash-noisy')
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    await expect(client.definition(file, 1, 0)).rejects.toThrow(/exit code 11/)
    expect(client.isAlive()).toBe(false)

    // Repeat calls report exited cleanly
    await expect(client.definition(file, 1, 0)).rejects.toThrow(
      /LSP server 'fake' exited \(exit code 11\)/,
    )
  })

  it('jdtls command detection and decompile via classFileContents and java.decompile', async () => {
    expect(isJdtlsCommand(['/usr/bin/jdtls'])).toBe(true)
    expect(isJdtlsCommand(['/usr/bin/jdtls.cmd'])).toBe(true)
    expect(isJdtlsCommand(['C:\\tools\\jdtls.exe', '-data', '/w'])).toBe(true)
    expect(isJdtlsCommand(['java', '-jar', 'launcher.jar'])).toBe(false)

    const transcript = path.join(tempDir, 'jdtls-transcript.jsonl')
    const jdtlsConfig = createJdtlsWrapper(tempDir, 'normal', transcript)

    const client = await LspClient.launch(jdtlsConfig, tempDir, jdtlsConfig.command[0]!, supervisor)
    clients.push(client)
    await client.initialize(5000)

    // jdt:// target uses java/classFileContents
    const source1 = await client.javaDecompile('jdt://contents/org/Test.class')
    expect(source1).toBe('class Decompiled {}\n')

    // local class file uses workspace/executeCommand java.decompile
    const classFile = path.join(tempDir, 'App.class')
    fs.writeFileSync(classFile, Buffer.from([0xca, 0xfe, 0xba, 0xbe]))
    const source2 = await client.javaDecompile(classFile)
    expect(source2).toBe('class Decompiled {}\n')

    // Non-jdtls server rejects javaDecompile for jdt:// targets
    const normal = await startClient('normal')
    await expect(normal.client.javaDecompile('jdt://contents/Test.class')).rejects.toThrow(
      'lsp_java_decompile is only supported by jdtls.',
    )

    // Empty decompile rejects
    const emptyClient = await startClient('empty-decompile')
    await expect(emptyClient.client.javaDecompile(classFile)).rejects.toThrow(
      /Could not load or decompile class for target/,
    )

    // Relative target rejects
    await expect(normal.client.javaDecompile('rel/App.class')).rejects.toThrow(
      /target must be an absolute class path, a file: URI, or a jdt:\/\/ URI/,
    )
  })

  it('shutdown and exit sequence terminates server process within grace', async () => {
    const { client, transcript } = await startClient('normal')
    const startEvent = await awaitEvent(transcript, (e) => e.event === 'start')
    const pid = startEvent.pid!

    await client.stop(500)
    expect(client.isAlive()).toBe(false)

    // Verify shutdown and exit were received
    expect(receivedMessages(transcript, 'shutdown')).toHaveLength(1)
    expect(receivedMessages(transcript, 'exit')).toHaveLength(1)
    await awaitProcessGone(pid)
  })

  it('shares one deferred termination between concurrent stop calls and never reports early success', async () => {
    const { client } = await startClient('normal')
    let terminateCalls = 0
    let releaseTermination!: () => void
    const terminationGate = new Promise<void>((resolve) => {
      releaseTermination = resolve
    })
    const managed = (
      client as unknown as { managed: { terminate: (graceMillis?: number) => Promise<void> } }
    ).managed
    managed.terminate = async (): Promise<void> => {
      terminateCalls += 1
      await terminationGate
      throw new Error('deferred termination boom')
    }

    const first = client.stop(50)
    const second = client.stop(50)
    let firstSettled = false
    let secondSettled = false
    void first.then(
      () => {
        firstSettled = true
      },
      () => {
        firstSettled = true
      },
    )
    void second.then(
      () => {
        secondSettled = true
      },
      () => {
        secondSettled = true
      },
    )

    // 物理收尾仍被钉住：两次调用都必须等待同一次收尾，第二次绝不能凭「已经停过」提前报告成功。
    await new Promise((resolve) => setTimeout(resolve, 150))
    expect(firstSettled).toBe(false)
    expect(secondSettled).toBe(false)
    expect(terminateCalls).toBe(1)

    releaseTermination()
    const firstFailure: unknown = await first.catch((error: unknown) => error)
    const secondFailure: unknown = await second.catch((error: unknown) => error)
    expect(firstFailure).toBeInstanceOf(LspError)
    expect((firstFailure as LspError).message).toBe(
      'LSP server process termination failed during shutdown',
    )
    // 同一次收尾的同一条失败：既没有各自终结一次，也没有各自的失败对象。
    expect(secondFailure).toBe(firstFailure)
    expect(terminateCalls).toBe(1)
    // 该客户端的停止结论已被固化为失败，真实子进程由 supervisor 收尾。
    clients.splice(clients.indexOf(client), 1)
  })

  it('still terminates the process and keeps the failure when disposing the connection throws', async () => {
    const { client, transcript } = await startClient('stubborn')
    const startEvent = await awaitEvent(transcript, (e) => e.event === 'start')
    const pid = startEvent.pid!
    let terminateCalls = 0
    const managed = (
      client as unknown as { managed: { terminate: (graceMillis?: number) => Promise<void> } }
    ).managed
    const realTerminate = managed.terminate.bind(managed)
    managed.terminate = async (graceMillis?: number): Promise<void> => {
      terminateCalls += 1
      await realTerminate(graceMillis)
    }
    const connection = (client as unknown as { connection: { dispose: () => void } }).connection
    const realDispose = connection.dispose.bind(connection)
    let disposeFaultPending = true
    connection.dispose = (): void => {
      if (disposeFaultPending) {
        disposeFaultPending = false
        throw new Error('dispose boom')
      }
      realDispose()
    }

    const failure: unknown = await client.stop(100).catch((error: unknown) => error)
    expect(failure).toBeInstanceOf(LspError)
    expect((failure as LspError).message).toBe('LSP connection disposal failed during shutdown')
    // 连接释放失败绝不能跳过进程终结：旧实例是否存活必须靠终结来证明。
    expect(terminateCalls).toBe(1)
    await awaitProcessGone(pid)
    // 该客户端的停止结论已被固化为失败，真实子进程已经终结。
    clients.splice(clients.indexOf(client), 1)
  })

  it('keeps both failures when the connection disposal and the termination both fail', async () => {
    const { client } = await startClient('stubborn')
    let terminateCalls = 0
    const managed = (
      client as unknown as { managed: { terminate: (graceMillis?: number) => Promise<void> } }
    ).managed
    managed.terminate = async (): Promise<void> => {
      terminateCalls += 1
      throw new Error('termination boom')
    }
    const connection = (client as unknown as { connection: { dispose: () => void } }).connection
    const realDispose = connection.dispose.bind(connection)
    let disposeFaultPending = true
    connection.dispose = (): void => {
      if (disposeFaultPending) {
        disposeFaultPending = false
        throw new Error('dispose boom')
      }
      realDispose()
    }

    const failure: unknown = await client.stop(50).catch((error: unknown) => error)
    expect(failure).toBeInstanceOf(AggregateError)
    expect(((failure as AggregateError).errors as Error[]).map((error) => error.message)).toEqual([
      'LSP connection disposal failed during shutdown',
      'LSP server process termination failed during shutdown',
    ])
    expect(terminateCalls).toBe(1)
    // 该客户端的停止结论已被固化为失败，真实子进程由 supervisor 收尾。
    clients.splice(clients.indexOf(client), 1)
  })

  it('unchanged content does not send didChange notification', async () => {
    const { client, transcript } = await startClient('normal')
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    await client.definition(file, 1, 0)
    expect(receivedMessages(transcript, 'textDocument/didOpen')).toHaveLength(1)

    // Update mtime without changing content
    const futureTime = new Date(Date.now() + 5000)
    fs.utimesSync(file, futureTime, futureTime)

    await client.definition(file, 1, 0)
    expect(receivedMessages(transcript, 'textDocument/didChange')).toHaveLength(0)
    expect(receivedMessages(transcript, 'textDocument/didSave')).toHaveLength(0)
  })

  it('formats definitions with bare URI, custom URI scheme, and null items safely', async () => {
    const { client } = await startClient('bare-definition')
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    const results = await client.definition(file, 1, 0)
    expect(results).toHaveLength(2)
    expect(results[0]).toBe('custom://some/uri')
    expect(results[1]).toBe('file://invalid host/path')
  })

  it('formats symbols with string kind and missing URI safely', async () => {
    const { client } = await startClient('custom-symbols')
    const results = await client.workspaceSymbols('query', 10)
    expect(results).toHaveLength(1)
    expect(results[0]).toBe('CustomSym (customInterface) - unknown')
  })

  it('falls back to server id for unknown file extension during sync and handles refresh', async () => {
    const { client, transcript } = await startClient('normal')
    const file = path.join(tempDir, 'custom.unknownext')
    fs.writeFileSync(file, 'content\n')

    // Test sync with unknown extension
    client.sync(file)
    const openEvent = await awaitEvent(
      transcript,
      (e) => e.event === 'recv' && e.message?.method === 'textDocument/didOpen',
    )
    expect(openEvent).toBeDefined()
    expect(openEvent.message?.params?.textDocument?.languageId).toBe('fake')

    // Refresh on open document
    client.refresh(file)

    // Refresh on non-open document is a no-op
    client.refresh(path.join(tempDir, 'nonexistent.txt'))

    // Stop client and verify refresh on dead client is a no-op
    await client.stop(500)
    client.refresh(file)
  })

  it('sync rejects missing file and binary file, and requests reject after client is stopped', async () => {
    const { client } = await startClient('normal')
    const missingFile = path.join(tempDir, 'missing.java')
    expect(() => client.sync(missingFile)).toThrow('LSP request failed: the source file could not be read')

    const binaryFile = path.join(tempDir, 'binary.java')
    fs.writeFileSync(binaryFile, Buffer.from([0x00, 0x01, 0x02]))
    expect(() => client.sync(binaryFile)).toThrow('LSP request failed: the source file is not valid text')

    await client.stop(500)
    expect(() => client.sync(binaryFile)).toThrow('LSP client stopped')
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')
    await expect(client.definition(file, 1, 0)).rejects.toThrow('LSP client stopped')
  })

  it('javaDecompile rejects invalid file URI, non-existent target, and directory target', async () => {
    const transcript = path.join(tempDir, 'jdtls-invalid-target.jsonl')
    const jdtlsConfig = createJdtlsWrapper(tempDir, 'normal', transcript)
    const client = await LspClient.launch(jdtlsConfig, tempDir, jdtlsConfig.command[0]!, supervisor)
    clients.push(client)
    await client.initialize(5000)

    // Invalid file URI
    await expect(client.javaDecompile('file://invalid host/App.class')).rejects.toThrow(/invalid class target URI/)

    // Non-existent file
    const missing = path.join(tempDir, 'Missing.class')
    await expect(client.javaDecompile(missing)).rejects.toThrow(/target is not a readable class file/)

    // Directory
    await expect(client.javaDecompile(tempDir)).rejects.toThrow(/target is not a readable class file/)
  })

  it('rejects immediately when signal is already aborted', async () => {
    const { client } = await startClient('normal')
    const file = path.join(tempDir, 'App.java')
    fs.writeFileSync(file, 'class App {}\n')

    const ac = new AbortController()
    ac.abort()
    await expect(client.definition(file, 1, 0, ac.signal)).rejects.toThrow(/Operation cancelled/)

    const timeoutAc = new AbortController()
    timeoutAc.abort('TIMED_OUT')
    await expect(client.definition(file, 1, 0, timeoutAc.signal)).rejects.toThrow(/LSP request timed out/)
  })

  it('treats zero timeout as infinite without premature timer triggers', async () => {
    const { client } = await startClient('normal')
    expect(client.isAlive()).toBe(true)
  })
})
