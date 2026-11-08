import { mkdtempSync, writeFileSync, chmodSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { create } from '@bufbuild/protobuf'

import type { DaemonConfig, ProcessSupervisor, ManagedProcess, ProcessSpec, CapabilityExecutor } from '../src/contracts.js'
import { processCapability } from '../src/coding/process.js'
import { PROTOCOL_VERSION } from '../src/protocol/control/codec.js'
import { ensurePrivateDirectory } from '../src/native/private-fs.js'
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

/** 测试卡片的固定 canonical UUID。 */
export const TEST_ENVIRONMENT_ID = '11111111-2222-4333-8444-555555555555'

/**
 * 本测试文件的私有根：Windows 的 %TEMP% 默认 DACL 会继承给子目录，裸 mkdtemp 不是 owner-only。
 *
 * <p>先用生产 `ensurePrivateDirectory` 建立真实私有根，之后每个 fixture 在其下创建子目录；
 * 子目录和文件继承真实 owner-only DACL，不模拟 ACL，也不逐 fixture 调外部 icacls 增加批量开销。
 * 该根每个测试模块只建一次。
 */
const privateTestRoot = await ensurePrivateDirectory(mkdtempSync(join(tmpdir(), 'kk-daemon-private-')))
process.once('exit', () => {
  try {
    rmSync(privateTestRoot, { recursive: true, force: true })
  } catch {
    // 进程退出阶段尽力清理；失败不改写测试结果。
  }
})

/** 在私有测试根下创建真实 owner-only 语义的新目录。 */
export function createPrivateTestDirectory(prefix = 'kk-daemon-test-'): string {
  return mkdtempSync(join(privateTestRoot, prefix))
}

/** 创建一个 owner-only 的临时 daemon 根，含新结构 daemon.json 与 daemon.token。 */
export function createDaemonRoot(token = 'test-registration-token'): { root: string; configPath: string } {
  const root = createPrivateTestDirectory()
  const configPath = join(root, 'daemon.json')
  writeFileSync(
    configPath,
    JSON.stringify({
      environmentId: TEST_ENVIRONMENT_ID,
      version: '0',
      daemonConfig: { studioUrl: 'https://studio.example.com' },
    }),
    'utf8',
  )
  chmodSync(configPath, 0o600)
  const tokenPath = join(root, 'daemon.token')
  writeFileSync(tokenPath, token, 'utf8')
  chmodSync(tokenPath, 0o600)
  return { root, configPath }
}

export function testConfig(overrides: Partial<DaemonConfig> = {}): DaemonConfig {
  const { root, configPath } = createDaemonRoot()
  return {
    environmentId: TEST_ENVIRONMENT_ID,
    configVersion: 0n,
    configPath,
    dataDir: root,
    tokenPath: join(root, 'daemon.token'),
    studioUrl: 'https://studio.example.com',
    gatewayUri: 'wss://studio.example.com/ws/daemon',
    bashExecutable: 'bash',
    bashExecutableSetting: null,
    lsp: {},
    proxyUrl: null,
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
  /** 模拟 close 同步触发 transport 的 onDisconnected 回调。 */
  closeNotifier?: () => void

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
    this.closeNotifier?.()
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

  /** 模拟服务端 NO_ERROR GOAWAY；连接由生命周期按断开路径关闭。 */
  goaway(): void {
    this.listener?.onGoaway()
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
