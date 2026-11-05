import { mkdtempSync, writeFileSync, chmodSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { create } from '@bufbuild/protobuf'

import type { DaemonConfig, ProcessSupervisor, ManagedProcess, ProcessSpec, CapabilityExecutor } from '../src/contracts.js'
import { processCapability } from '../src/coding/process.js'
import { PROTOCOL_VERSION } from '../src/protocol/control/codec.js'
import type {
  ControlConnection,
  ControlTransport,
  ControlTransportListener,
} from '../src/transport/index.js'
import {
  EnvelopeSchema,
  MessageType,
  type Envelope,
} from '../src/generated/control_pb.js'

/** 创建一个 owner-only 的临时 daemon 根，含 daemon.json 与 daemon.token。 */
export function createDaemonRoot(token = 'test-registration-token'): { root: string; configPath: string } {
  const root = mkdtempSync(join(tmpdir(), 'kk-daemon-test-'))
  const configPath = join(root, 'daemon.json')
  writeFileSync(configPath, JSON.stringify({ studioUrl: 'https://studio.example.com' }), 'utf8')
  chmodSync(configPath, 0o600)
  const tokenPath = join(root, 'daemon.token')
  writeFileSync(tokenPath, token, 'utf8')
  chmodSync(tokenPath, 0o600)
  return { root, configPath }
}

export function testConfig(overrides: Partial<DaemonConfig> = {}): DaemonConfig {
  const { root, configPath } = createDaemonRoot()
  return {
    configPath,
    dataDir: root,
    tokenPath: join(root, 'daemon.token'),
    studioUrl: 'https://studio.example.com',
    gatewayUri: 'wss://studio.example.com/api/harness/environment-daemon',
    bashExecutable: 'bash',
    note: null,
    lsp: {},
    distributionDir: root,
    ...overrides,
  }
}

/** 可注入的 fake ProcessSupervisor；runtime 只把它透传给 capability context。 */
export const fakeProcessSupervisor: ProcessSupervisor = {
  async start(spec: ProcessSpec): Promise<ManagedProcess> {
    throw new Error(`not implemented: ${spec.executable}`)
  },
  async close(): Promise<void> {},
}

/** 测试用进程能力表。 */
export const processCapabilities: Record<string, CapabilityExecutor> = {
  'process.exec': processCapability,
}

export class FakeConnection implements ControlConnection {
  sent: Envelope[] = []
  open = true
  sendInterceptor?: (envelope: Envelope) => boolean | void

  send(envelope: Envelope): boolean {
    if (!this.open) {
      return false
    }
    if (this.sendInterceptor) {
      const res = this.sendInterceptor(envelope)
      if (typeof res === 'boolean' && !res) {
        return false
      }
    }
    this.sent.push(envelope)
    return true
  }

  close(): void {
    this.open = false
  }

  isOpen(): boolean {
    return this.open
  }
}

/** 内存 control transport；测试通过 receive/disconnect 驱动入站事件。 */
export class FakeTransport implements ControlTransport {
  listener: ControlTransportListener | null = null
  connection: FakeConnection | null = null
  connectCount = 0
  closed = false

  async connect(listener: ControlTransportListener): Promise<ControlConnection> {
    this.listener = listener
    this.connection = new FakeConnection()
    this.connectCount++
    return this.connection
  }

  async close(): Promise<void> {
    this.closed = true
  }

  receive(envelope: Envelope): void {
    this.listener?.onEnvelope(envelope)
  }

  disconnect(): void {
    const connection = this.connection
    if (connection !== null) {
      connection.open = false
    }
    this.listener?.onDisconnected()
  }

  get sent(): Envelope[] {
    return this.connection?.sent ?? []
  }
}

/** 让已排队的 promise/microtask 与 setTimeout(0) 回调执行完。 */
export function tick(times = 1): Promise<void> {
  let chain = Promise.resolve()
  for (let index = 0; index < times; index++) {
    chain = chain.then(() => new Promise<void>((resolve) => setTimeout(resolve, 0)))
  }
  return chain
}

export async function waitFor(predicate: () => boolean, timeoutMillis = 2000): Promise<void> {
  const deadline = Date.now() + timeoutMillis
  while (!predicate()) {
    if (Date.now() > deadline) {
      throw new Error('waitFor timed out')
    }
    await tick()
  }
}
