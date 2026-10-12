import { existsSync, mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import type { AddressInfo } from 'node:net'
import { WebSocketServer, type WebSocket } from 'ws'

import {
  CAPABILITY_CATALOG_VERSION,
  decodeEnvelope,
  encodeInvoke,
  type Envelope,
  type MessageType,
} from '../../src/protocol.js'
import * as native from '../../src/native/index.js'
import { serverMessage } from '../helpers.js'

/**
 * 真实 Daemon 控制面测试基座：虚构 loopback control server + 真实 native private 数据目录。
 *
 * 供 main 装配回归与编译产物 CLI 回归共用，避免复制同一套 fixture。能力/权限/进程约束仍然走真实实现。
 */

export const ENVIRONMENT_ID = '4e0d3c2b-1a09-4f8e-9d7c-6b5a49382716'
export const WELCOME_PAYLOAD = {
  maxResourceBytes: 1024 * 1024,
  temporaryResourceTtlSeconds: 60,
  temporaryResourceCleanupIntervalSeconds: 30,
}
export const TOKEN = 'main-acceptance-registration-token'

export interface ControlFixture {
  root: string
  dataDir: string
  configPath: string
  tokenPath: string
  url: string
  received: Envelope[]
  send(type: MessageType, options?: { invocationId?: string | null; payload?: Record<string, unknown> }): void
  /** 关闭 loopback server 并删除真实私有数据目录。 */
  dispose(): Promise<void>
}

/** 真实 loopback control server：协商 permessage-deflate，对 HELLO 回 WELCOME。 */
export async function createControlServer(): Promise<ControlFixture> {
  const server = new WebSocketServer({ port: 0, perMessageDeflate: true })
  await new Promise<void>((resolve) => server.once('listening', () => resolve()))
  const port = (server.address() as AddressInfo).port
  const received: Envelope[] = []
  const sockets: WebSocket[] = []
  server.on('connection', (socket) => {
    sockets.push(socket)
    socket.on('message', (data, isBinary) => {
      if (isBinary) {
        return
      }
      const envelope = decodeEnvelope(data.toString('utf8'))
      received.push(envelope)
      if (envelope.messageType === 'HELLO') {
        socket.send(serverMessage('WELCOME', { environmentId: ENVIRONMENT_ID, payload: WELCOME_PAYLOAD }))
      }
    })
  })

  // 通过真实 native private 边界创建 owner-only 数据目录，Windows 由 DACL 承载。
  const root = await native.ensurePrivateDirectory(mkdtempSync(join(tmpdir(), 'kk-daemon-main-')))
  return {
    root,
    dataDir: root,
    configPath: join(root, 'daemon.json'),
    tokenPath: join(root, 'daemon.token'),
    url: `http://127.0.0.1:${port}`,
    received,
    send(type, options = {}) {
      const socket = sockets[sockets.length - 1]
      socket?.send(serverMessage(type, { environmentId: ENVIRONMENT_ID, ...options }))
    },
    async dispose() {
      for (const socket of sockets) {
        socket.terminate()
      }
      await new Promise<void>((resolve) => server.close(() => resolve()))
      rmSync(root, { recursive: true, force: true })
    },
  }
}

/** Windows 上没有系统 bash 时退回到常见 Git Bash 路径；其它平台交给配置默认值。 */
export function resolveTestBash(): string | undefined {
  if (process.platform !== 'win32') {
    return undefined
  }
  const candidates = [process.env.ProgramW6432, process.env.ProgramFiles, process.env['ProgramFiles(x86)']]
    .filter((base): base is string => typeof base === 'string' && base.length > 0)
    .map((base) => join(base, 'Git', 'bin', 'bash.exe'))
  candidates.push('C:\\Program Files\\Git\\bin\\bash.exe')
  return candidates.find((candidate) => existsSync(candidate))
}

/** 用真实 native private 边界写入 daemon.json 与 owner-only daemon.token；studioUrl 指向 loopback server。 */
export async function writeConfig(
  fixture: ControlFixture,
  overrides: Record<string, unknown> = {},
): Promise<void> {
  const bash = resolveTestBash()
  await native.writePrivateFile(
    fixture.configPath,
    JSON.stringify({
      studioUrl: fixture.url,
      note: 'main acceptance',
      ...(bash === undefined ? {} : { bashExecutable: bash }),
      ...overrides,
    }),
  )
  await native.writePrivateFile(fixture.tokenPath, TOKEN)
}

export function invoke(
  capabilityId: string,
  args: Record<string, unknown>,
  timeoutMillis = 0,
): Record<string, unknown> {
  return JSON.parse(
    encodeInvoke({ capabilityId, capabilityVersion: CAPABILITY_CATALOG_VERSION, arguments: args, timeoutMillis }),
  ) as Record<string, unknown>
}

export function response(
  fixture: ControlFixture,
  invocationId: string,
  types: MessageType[],
): Envelope | undefined {
  return fixture.received.find(
    (envelope) => envelope.invocationId === invocationId && types.includes(envelope.messageType),
  )
}

export function lastTerminal(fixture: ControlFixture, invocationId: string): MessageType | undefined {
  for (let index = fixture.received.length - 1; index >= 0; index--) {
    const envelope = fixture.received[index] as Envelope
    if (
      envelope.invocationId === invocationId &&
      (envelope.messageType === 'COMPLETED' ||
        envelope.messageType === 'FAILED' ||
        envelope.messageType === 'CANCELLED')
    ) {
      return envelope.messageType
    }
  }
  return undefined
}

export function contentOf(envelope: Envelope | undefined): string {
  if (envelope === undefined) {
    return ''
  }
  const result = envelope.payload.result as { contents?: Array<{ type: string; text?: string }> } | undefined
  return (result?.contents ?? []).map((content) => content.text ?? '').join('')
}

/** bash 双引号：路径统一正斜杠，转义双引号、反斜杠与命令替换。 */
export function shellQuote(value: string): string {
  return `"${unixPath(value).replace(/(["\\$`])/g, '\\$1')}"`
}

export function unixPath(value: string): string {
  return value.replace(/\\/g, '/')
}

/**
 * 长进程内联脚本：先把真实 PID 写入 `<pidFile>.tmp`，再 rename 原子替换到目标文件。
 *
 * <p>直接 writeFileSync 目标会让消费者在「文件已建但尚未写完」的窗口里读到空文件（`Number('') === 0`）；
 * 先写临时文件再 rename 保证消费者只可能看到完整 PID。回归测试用同一常量做真实 spawn 观察。
 */
export const LONG_RUNNING_PID_SCRIPT =
  'const fs=require("fs");fs.writeFileSync(process.argv[1]+".tmp",String(process.pid));fs.renameSync(process.argv[1]+".tmp",process.argv[1]);setInterval(function(){},1000)'

/** 经 bash 引号保护地运行真实 Node：发布 PID 文件后持续存活，供取消/停机收敛断言使用。 */
export function longRunningNodeCommand(pidFile: string): string {
  return `${shellQuote(process.execPath)} -e '${LONG_RUNNING_PID_SCRIPT}' ${shellQuote(unixPath(pidFile))}`
}

/** 短命令同样用真实 Node 执行，不依赖宿主 echo/sleep 的实现差异。 */
export function shortNodeCommand(text: string): string {
  return `${shellQuote(process.execPath)} -e 'process.stdout.write(${JSON.stringify(text)})'`
}
