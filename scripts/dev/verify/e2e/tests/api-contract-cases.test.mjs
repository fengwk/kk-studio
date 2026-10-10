import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

// 注册副作用：crud / system-settings 模块内新增或扩展了 HTTP 重试覆盖与内置 Agent identity 的免费 L1 契约 case。
import '../cases/crud.mjs'
import '../cases/system-settings.mjs'
import { providerCreateBody, providerUpdateBody } from '../lib/fixtures.mjs'
import { HttpError } from '../lib/http.mjs'
import { ALL_CASES, getCase } from '../lib/registry.mjs'

const RUN_MATRIX = fileURLToPath(new URL('../run-matrix.mjs', import.meta.url))

const FREE_L1_CASES = [
  'crud.provider.http_retry_override',
  'crud.agent.builtin_identity',
  'crud.chat.default_environment',
  'settings.system_contract_cas',
]

/**
 * 只 fake HTTP 的 Chat + Environment 有状态后端：按当前契约建模 required-nullable 默认环境、
 * 部分更新（省略保留 / 显式 null 清空）与未知/非 canonical Environment 的 400 拒绝，用来真实执行
 * `crud.chat.default_environment` case 并断言它发出的 method/path/body（含 omit-null 语义）。
 */
function chatDefaultEnvironmentFixture() {
  const agent = {
    name: 'fixture-agent',
    type: 'USER',
    model: 'fixture-provider/fixture-model',
    variant: 'default',
  }
  const environments = new Map()
  const chats = new Map()
  const requests = []
  let seq = 0
  const nextId = () => `00000000-0000-4000-8000-${String(++seq).padStart(12, '0')}`
  const call = async (method, path, body) => {
    requests.push({ method, path, body })
    const pathname = new URL(path, 'http://fixture.local').pathname
    if (method === 'GET' && pathname === '/api/ai/catalog/agents') {
      return { status: 200, json: { data: { results: [agent] } } }
    }
    if (method === 'POST' && pathname === '/api/harness/environments') {
      const environment = {
        id: nextId(),
        name: body.name,
        version: '0',
        registrationToken: `tok-${body.name}`,
      }
      environments.set(environment.id, environment)
      return { status: 201, json: { data: environment } }
    }
    if (method === 'POST' && pathname === '/api/ai/chats') {
      const chat = {
        id: nextId(),
        title: body.title,
        agentName: body.agentName,
        environmentName: body.environmentName ?? null,
        yoloEnabled: body.yoloEnabled === true,
        version: '0',
      }
      chats.set(chat.id, chat)
      return { status: 201, json: { data: { ...chat } } }
    }
    const chatMatch = pathname.match(/^\/api\/ai\/chats\/([^/]+)$/)
    if (chatMatch) {
      const id = decodeURIComponent(chatMatch[1])
      const chat = chats.get(id)
      if (method === 'GET') {
        return { status: 200, json: { data: { ...chat } } }
      }
      if (method === 'PUT') {
        if (body.expectedVersion !== chat.version) {
          throw new HttpError(409, JSON.stringify({ message: 'version conflict' }), pathname)
        }
        if (body.title !== undefined) chat.title = body.title
        if (Object.hasOwn(body, 'environmentName')) {
          if (body.environmentName === null) {
            chat.environmentName = null
          } else {
            if (String(body.environmentName).includes('/')) {
              throw new HttpError(
                400,
                JSON.stringify({ message: "environmentName must not contain '/'" }),
                pathname,
              )
            }
            if (![...environments.values()].some((env) => env.name === body.environmentName)) {
              throw new HttpError(
                400,
                JSON.stringify({ message: `unknown environment: ${body.environmentName}` }),
                pathname,
              )
            }
            chat.environmentName = body.environmentName
          }
        }
        chat.version = String(Number(chat.version) + 1)
        return { status: 200, json: { data: { ...chat } } }
      }
      if (method === 'DELETE') {
        chats.delete(id)
        return { status: 204, json: null }
      }
    }
    const environmentMatch = pathname.match(/^\/api\/harness\/environments\/([^/]+)$/)
    if (environmentMatch) {
      const id = decodeURIComponent(environmentMatch[1])
      if (method === 'GET') {
        return { status: 200, json: { data: { ...environments.get(id) } } }
      }
      if (method === 'DELETE') {
        environments.delete(id)
        return { status: 204, json: null }
      }
    }
    throw new Error(`unexpected call ${method} ${pathname}`)
  }
  return { ctx: { call }, requests }
}

test('provider HTTP retry and builtin Agent contracts are registered as free L1 cases', () => {
  // 测试意图：这些是零模型成本的 API 契约回归，必须注册为 L1 且无 real/tools 依赖，
  // 才能进入默认免费矩阵。
  for (const id of FREE_L1_CASES) {
    const caseDef = getCase(id)
    assert.ok(caseDef, `case ${id} should be found via getCase`)
    assert.ok(
      ALL_CASES.some((c) => c.id === id),
      `case ${id} should be present in ALL_CASES`,
    )
    assert.equal(caseDef.level, 'L1', `case ${id} level must be L1`)
    assert.deepEqual(Array.from(caseDef.requires), [], `case ${id} requires must be empty`)
  }
})

test('providerUpdateBody builds the editable PUT body without name and preserves override states', () => {
  // 测试意图：Provider PUT 的身份在 path，请求体携带 `name` 会被严格反序列化拒绝；覆盖三态通过
  // 是否出现 `modelHttpRetryStatusCodes` 键表达（省略 / null / 数组），helper 不得擅自注入该键。
  const base = providerUpdateBody()
  assert.equal(Object.hasOwn(base, 'name'), false, 'PUT body must not carry the resource name')
  assert.deepEqual(Object.keys(base).sort(), [
    'baseUrl',
    'credential',
    'description',
    'modelCallIdleTimeoutMillis',
    'modelCallTimeoutMillis',
    'providerType',
  ])
  assert.equal(Object.hasOwn(base, 'modelHttpRetryStatusCodes'), false, 'omitted override must be absent')
  assert.equal(base.credential, '', 'empty credential must preserve the stored secret')

  assert.deepEqual(providerUpdateBody({ modelHttpRetryStatusCodes: [500, 503] }).modelHttpRetryStatusCodes, [
    500,
    503,
  ])
  assert.equal(providerUpdateBody({ modelHttpRetryStatusCodes: null }).modelHttpRetryStatusCodes, null)
  assert.deepEqual(providerUpdateBody({ modelHttpRetryStatusCodes: [] }).modelHttpRetryStatusCodes, [])

  const created = providerCreateBody('suffix')
  assert.equal(created.name, 'e2e-provider-suffix')
  assert.equal(Object.hasOwn(created, 'modelHttpRetryStatusCodes'), false)
})

test('settings contract accepts an empty existing HTTP retry list without mutating it on failure', async () => {
  const before = {
    version: '0',
    tool: {},
    aiRuntime: { retryBaseDelayMillis: '2500', modelHttpRetryStatusCodes: [] },
    environment: {},
    network: { proxyUrl: null, noProxyHosts: '' },
    integrations: { minimaxH3: {} },
    storageMedia: {
      temporaryResourceTtlSeconds: '259200',
      temporaryResourceCleanupIntervalSeconds: '1800',
    },
    advanced: {},
  }
  const mutationError = new Error('synthetic update failure')
  let puts = 0
  const ctx = {
    async call(method, path) {
      assert.equal(path, '/api/settings')
      if (method === 'GET') return { json: { data: structuredClone(before) } }
      assert.equal(method, 'PUT')
      puts++
      throw mutationError
    },
  }
  await assert.rejects(getCase('settings.system_contract_cas').run(ctx), (error) => error === mutationError)
  assert.equal(puts, 1, 'empty existing list must pass validation and reach the CAS update')
  assert.deepEqual(before.aiRuntime.modelHttpRetryStatusCodes, [])
})

test('chat default environment case executes the create/retain/clear wire with omit-null semantics', async () => {
  // 测试意图：真实执行 crud.chat.default_environment（只 fake HTTP），证明它按契约发送默认环境、
  // 省略 environmentName 的 PUT 不携带该键、显式清空携带 environmentName:null，且非法值确实被发出并拒绝。
  const fake = chatDefaultEnvironmentFixture()
  await getCase('crud.chat.default_environment').run(fake.ctx)

  const create = fake.requests.find(
    (request) => request.method === 'POST' && request.path === '/api/ai/chats',
  )
  const environmentCreate = fake.requests.find(
    (request) => request.method === 'POST' && request.path === '/api/harness/environments',
  )
  assert.equal(create.body.yoloEnabled, false)
  assert.equal(create.body.environmentName, environmentCreate.body.name)

  const chatPuts = fake.requests.filter(
    (request) => request.method === 'PUT' && request.path.startsWith('/api/ai/chats/'),
  )
  // 只改 title 的 PUT 省略 environmentName：键必须缺席（部分更新保留当前默认环境）。
  assert.equal(Object.hasOwn(chatPuts[0].body, 'environmentName'), false)
  // 显式清空：键必须存在且为 null。
  const clear = chatPuts.find(
    (request) => Object.hasOwn(request.body, 'environmentName') && request.body.environmentName === null,
  )
  assert.ok(clear, 'explicit null must appear in the PUT wire')
  // 非法值确实被发出（而不是被 case 本地吞掉）。
  assert.ok(chatPuts.some((request) => request.body.environmentName === 'bad/name'))
  assert.ok(chatPuts.some((request) => /^missing-env-/.test(String(request.body.environmentName))))
})

test('run-matrix --list/--docs execute fully offline and include the new contract cases', () => {
  // 测试意图：矩阵列举是纯进程内的静态入口，不启动服务、不发网络请求；新增 case 必须出现在
  // 两个入口的输出中，且逐条列出的 case 数与报告的注册总数自洽。
  for (const flag of ['--list', '--docs']) {
    const result = spawnSync(process.execPath, [RUN_MATRIX, flag], { encoding: 'utf8' })
    assert.equal(result.status, 0, `${flag} exited ${result.status}: ${result.stderr}`)
    assert.equal(result.stderr, '', `${flag} must not write to stderr: ${result.stderr}`)
    for (const id of FREE_L1_CASES) {
      assert.ok(result.stdout.includes(id), `${flag} output must include ${id}`)
    }
    const caseLines = result.stdout
      .split('\n')
      .filter((line) => /^\[L\d\] /.test(line))
    const totalMatch = result.stdout.match(/Total registered: (\d+)/)
    assert.ok(totalMatch, `${flag} output must report Total registered`)
    assert.equal(
      Number(totalMatch[1]),
      caseLines.length,
      `${flag} enumerated cases must match the reported total`,
    )
  }
})
