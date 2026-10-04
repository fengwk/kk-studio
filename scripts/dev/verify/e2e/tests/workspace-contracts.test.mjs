import assert from 'node:assert/strict'
import test from 'node:test'

import { runWorkspaceContractMatrix } from '../ui/workspace-contracts.mjs'

// 直接执行真实 UI case ui.settings.system_contract_cas，只把 HTTP 与页面替换成纯内存替身，
// 从而在任何环境（无网络、无服务、无 Playwright）下锁住 externalUpdate / finally restore
// 两个显式 PUT 是否完整携带七个必填 section（含 network）并 CAS 到正确版本。

const CASE_ID = 'ui.settings.system_contract_cas'
const SECTIONS = ['advanced', 'aiRuntime', 'environment', 'integrations', 'network', 'storageMedia', 'tool']
const EXPECTED_KEYS = [...SECTIONS, 'expectedVersion'].sort()
const ORIGINAL_DELAY = '2501'
const CHANGED_DELAY = '2502'
const EXTERNAL_DELAY = '2503'
const STALE_DELAY = '2504'

function createSettingsWorld(network) {
  let version = 7n
  const state = {
    tool: { permission: { bash: [{ pattern: '*', action: 'ASK' }] }, defaultYolo: false },
    aiRuntime: {
      retryMaxRetries: 3,
      retryBackoffStrategy: 'EXPONENTIAL',
      retryBaseDelayMillis: ORIGINAL_DELAY,
      retryMaxDelayMillis: '60000',
    },
    environment: { maxResourceBytes: '16777216', heartbeatTimeoutMillis: '60000' },
    network,
    integrations: { enabled: false },
    storageMedia: { maxBytes: '1000000' },
    advanced: { resourceMaxBytes: '16777216' },
  }
  const world = {
    // 并发写入者替身：PUT 到达时若命中则由场景注入响应（用于异常路径）。
    onPut: null,
    puts: [],
    get() {
      return { ...structuredClone(state), version: String(version) }
    },
    advanceVersion() {
      version += 1n
    },
    // 严守七 section + expectedVersion：键缺失/多余一律 400，CAS 不匹配 409。
    put(body) {
      world.puts.push({ body: structuredClone(body), versionAtCall: String(version) })
      const keys = Object.keys(body).sort()
      if (keys.length !== EXPECTED_KEYS.length || !keys.every((key, index) => key === EXPECTED_KEYS[index])) {
        return { status: 400, json: { error: { message: `PUT body keys mismatch: ${JSON.stringify(keys)}` } } }
      }
      const injected = world.onPut ? world.onPut(body) : null
      if (injected) {
        return injected
      }
      if (body.expectedVersion !== String(version)) {
        return { status: 409, json: { error: { message: 'version conflict' } } }
      }
      for (const section of SECTIONS) {
        state[section] = structuredClone(body[section])
      }
      version += 1n
      return { status: 200, json: { data: world.get() } }
    },
    call(method, requestPath, body) {
      assert.equal(requestPath, '/api/settings')
      if (method === 'GET') {
        return Promise.resolve({ status: 200, json: { data: world.get() } })
      }
      if (method === 'PUT') {
        return Promise.resolve(world.put(body))
      }
      return Promise.reject(new Error(`unexpected call ${method} ${requestPath}`))
    },
  }
  return world
}

// 最小 Settings 页面替身：只实现真实 case 触达的 tab/输入/保存/冲突刷新交互。
function createSettingsPage(world) {
  const view = { draft: null, knownVersion: null, latestVisible: false, conflictVisible: false }
  let loadResolvers = []

  function hydrate() {
    const snapshot = world.get()
    view.draft = snapshot.aiRuntime.retryBaseDelayMillis
    view.knownVersion = snapshot.version
  }

  function resolveLoad() {
    const resolvers = loadResolvers
    loadResolvers = []
    for (const resolve of resolvers) {
      resolve()
    }
  }

  function draftBody() {
    const snapshot = world.get()
    return {
      tool: snapshot.tool,
      aiRuntime: { ...snapshot.aiRuntime, retryBaseDelayMillis: view.draft },
      environment: snapshot.environment,
      network: snapshot.network,
      integrations: snapshot.integrations,
      storageMedia: snapshot.storageMedia,
      advanced: snapshot.advanced,
      expectedVersion: view.knownVersion,
    }
  }

  const page = {
    getByRole(role, options = {}) {
      if (role === 'tab') {
        return { async click() {} }
      }
      if (role === 'button' && options.name === '保存') {
        return {
          async click() {
            const response = world.put(draftBody())
            if (response.status === 200) {
              view.knownVersion = response.json.data.version
              view.latestVisible = true
              view.conflictVisible = false
            } else if (response.status === 409) {
              view.conflictVisible = true
            } else {
              throw new Error(`settings save failed: ${response.status}`)
            }
          },
        }
      }
      if (role === 'alertdialog' && options.name === '持久状态已变化') {
        return {
          async waitFor() {
            assert.equal(view.conflictVisible, true, 'conflict dialog must be visible')
          },
          getByRole(innerRole, innerOptions = {}) {
            return {
              async click() {
                if (innerRole === 'button' && innerOptions.name === '刷新') {
                  hydrate()
                  view.conflictVisible = false
                  resolveLoad()
                }
              },
            }
          },
        }
      }
      throw new Error(`unexpected role locator: ${role} ${JSON.stringify(options)}`)
    },
    getByLabel(label) {
      assert.equal(label, '基础延迟（毫秒）')
      return {
        async waitFor() {
          assert.notEqual(view.draft, null, 'base delay input must be hydrated')
        },
        async inputValue() {
          return view.draft
        },
        async fill(value) {
          view.draft = value
        },
      }
    },
    getByText(text) {
      assert.equal(text, '已是最新')
      return {
        async waitFor() {
          assert.equal(view.latestVisible, true, '"已是最新" must be visible')
        },
      }
    },
    waitForEvent(name) {
      assert.equal(name, 'load')
      return new Promise((resolve) => {
        loadResolvers.push(resolve)
      })
    },
  }

  return { page, hydrate }
}

function createHarness(world) {
  const pageErrors = []
  const consoleErrors = []
  const executed = []
  const { page, hydrate } = createSettingsPage(world)
  const ui = {
    apiCtx: { call: (method, requestPath, body) => world.call(method, requestPath, body) },
    consoleErrors,
    expectNoFatal(errors, logs) {
      assert.deepEqual(errors, [])
      assert.deepEqual(logs, [])
    },
    goto: async (requestPath) => {
      assert.equal(requestPath, '/settings')
      hydrate()
    },
    page,
    pageErrors,
    // 只运行目标 case，其余 case 回调全部跳过，避免触达无关 UI 路径。
    run: async (id, title, fn) => {
      if (id !== CASE_ID) {
        return
      }
      executed.push(id)
      await fn('/tmp/kk-studio-workspace-contracts-test')
    },
    shot: async () => {},
  }
  return { ui, executed }
}

function assertFullContract(body, label) {
  assert.deepEqual(
    Object.keys(body).filter((key) => key !== 'expectedVersion').sort(),
    [...SECTIONS].sort(),
    `${label} must carry exactly the seven sections`,
  )
  assert.match(body.expectedVersion, /^(0|[1-9][0-9]*)$/, `${label} expectedVersion must be canonical decimal`)
}

for (const scenario of [
  { name: 'direct (proxyUrl null)', network: { proxyUrl: null, noProxyHosts: 'localhost,127.*,::1' } },
  { name: 'explicit proxy', network: { proxyUrl: 'http://proxy.example:8080', noProxyHosts: 'localhost,127.*' } },
]) {
  test(`system_contract_cas carries ${scenario.name} network through external update and restore`, async () => {
    // 测试意图：执行真实 case 的 externalUpdate 与 finally restore，断言 network 原样回传且 CAS 版本正确。
    const world = createSettingsWorld(structuredClone(scenario.network))
    const { ui, executed } = createHarness(world)
    await runWorkspaceContractMatrix(ui)
    assert.deepEqual(executed, [CASE_ID])

    const savePut = world.puts[0]
    assert.equal(savePut.body.aiRuntime.retryBaseDelayMillis, CHANGED_DELAY)
    assert.equal(savePut.body.expectedVersion, '7')

    const externalPut = world.puts.find((entry) => entry.body.aiRuntime.retryBaseDelayMillis === EXTERNAL_DELAY)
    assert.ok(externalPut, 'case must PUT the external update')
    assertFullContract(externalPut.body, 'externalUpdate')
    assert.deepEqual(externalPut.body.network, scenario.network)
    assert.equal(externalPut.body.expectedVersion, '8')

    const stalePut = world.puts.find((entry) => entry.body.aiRuntime.retryBaseDelayMillis === STALE_DELAY)
    assert.ok(stalePut, 'case must PUT the stale draft')
    assert.equal(stalePut.body.expectedVersion, '8')

    const restorePut = world.puts.find((entry) => entry.body.aiRuntime.retryBaseDelayMillis === ORIGINAL_DELAY)
    assert.ok(restorePut, 'finally must PUT the restore')
    assertFullContract(restorePut.body, 'restore')
    assert.deepEqual(restorePut.body.network, scenario.network)
    assert.equal(restorePut.body.expectedVersion, '9')
    assert.equal(world.get().aiRuntime.retryBaseDelayMillis, ORIGINAL_DELAY)
  })
}

test('system_contract_cas still restores from the latest version when the external PUT loses CAS', async () => {
  // 测试意图：外部 PUT 因并发推进版本失败抛错时，finally 仍用重读到的最新版本恢复原值，不绕过恢复。
  const network = { proxyUrl: 'http://proxy.example:8080', noProxyHosts: 'localhost,127.*' }
  const world = createSettingsWorld(structuredClone(network))
  world.onPut = (body) => {
    if (body.aiRuntime.retryBaseDelayMillis === EXTERNAL_DELAY) {
      world.advanceVersion()
      world.onPut = null
      return { status: 409, json: { error: { message: 'version conflict' } } }
    }
    return null
  }
  const { ui } = createHarness(world)
  await assert.rejects(runWorkspaceContractMatrix(ui), /external settings update failed/)
  assert.equal(world.get().aiRuntime.retryBaseDelayMillis, ORIGINAL_DELAY)

  const restorePut = world.puts.find((entry) => entry.body.aiRuntime.retryBaseDelayMillis === ORIGINAL_DELAY)
  assert.ok(restorePut, 'finally must still restore after the abrupt exit')
  assertFullContract(restorePut.body, 'restore')
  assert.deepEqual(restorePut.body.network, network)
  assert.equal(restorePut.body.expectedVersion, '9')
  assert.equal(world.puts.length, 3, 'abrupt path must not add extra PUTs')
})
