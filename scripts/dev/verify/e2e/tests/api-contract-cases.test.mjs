import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

import { providerCreateBody, providerUpdateBody } from '../lib/fixtures.mjs'
import { problemError } from './fixtures/problem.mjs'
import { ALL_CASES, getCase } from '../run-matrix.mjs'

const RUN_MATRIX = fileURLToPath(new URL('../run-matrix.mjs', import.meta.url))
const DEFERRED_OUTPUT_FIXTURE = fileURLToPath(
  new URL('./fixtures/deferred-output.mjs', import.meta.url),
)

const FREE_L1_CASES = [
  'crud.provider.http_retry_override',
  'crud.chat.default_environment',
  'settings.system_contract_cas',
]

/**
 * 只 fake HTTP 的 Chat + Environment 有状态后端：按当前契约建模 required-nullable 默认环境、
 * 整体替换（title/agentName/environmentName 全量）与未知/非 canonical Environment 的 400 拒绝，用来真实执行
 * `crud.chat.default_environment` case 并断言它发出的 method/path/body。
 */
function chatDefaultEnvironmentFixture() {
  const agent = {
    name: 'fixture-agent',
    type: 'USER',
    execution: { driver: 'builtin', settings: { modelName: 'fixture-model', variant: 'default' } },
  }
  const environments = new Map()
  const chats = new Map()
  const requests = []
  let seq = 0
  const nextId = () => `00000000-0000-4000-8000-${String(++seq).padStart(12, '0')}`
  const call = async (method, path, body) => {
    requests.push({ method, path, body })
    const pathname = new URL(path, 'http://fixture.local').pathname
    if (method === 'GET' && pathname === '/api/platform/agents') {
      return { status: 200, json: [agent] }
    }
    if (method === 'POST' && pathname === '/api/platform/environments') {
      const environment = {
        id: nextId(),
        name: body.name,
        note: body.note,
        daemonConfig: body.daemonConfig,
        version: '0',
        registrationToken: `tok-${body.name}`,
      }
      environments.set(environment.id, environment)
      return { status: 201, json: environment }
    }
    if (method === 'POST' && pathname === '/api/chat/chats') {
      const chat = {
        id: nextId(),
        title: body.title,
        agentName: body.agentName,
        environmentName: body.environmentName ?? null,
        yoloEnabled: body.yoloEnabled === true,
        version: '0',
      }
      chats.set(chat.id, chat)
      return { status: 201, json: { ...chat } }
    }
    const chatMatch = pathname.match(/^\/api\/chat\/chats\/([^/]+)$/)
    if (chatMatch) {
      const id = decodeURIComponent(chatMatch[1])
      const chat = chats.get(id)
      if (method === 'GET') {
        return { status: 200, json: { ...chat } }
      }
      if (method === 'PUT') {
        if (body.expectedVersion !== chat.version) {
          throw problemError(409, 'version_conflict', 'version conflict', pathname)
        }
        chat.title = body.title
        chat.agentName = body.agentName
        if (body.environmentName === null) {
          chat.environmentName = null
        } else {
          if (String(body.environmentName).includes('/')) {
            throw problemError(
              400,
              'validation',
              "environmentName must not contain '/'",
              pathname,
            )
          }
          if (![...environments.values()].some((env) => env.name === body.environmentName)) {
            throw problemError(
              400,
              'validation',
              `unknown environment: ${body.environmentName}`,
              pathname,
            )
          }
          chat.environmentName = body.environmentName
        }
        chat.version = String(BigInt(chat.version) + 1n)
        return { status: 200, json: { ...chat } }
      }
      if (method === 'DELETE') {
        chats.delete(id)
        return { status: 204, json: null }
      }
    }
    const environmentMatch = pathname.match(/^\/api\/platform\/environments\/([^/]+)$/)
    if (environmentMatch) {
      const id = decodeURIComponent(environmentMatch[1])
      if (method === 'GET') {
        return { status: 200, json: { ...environments.get(id) } }
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
  ])
  assert.equal(Object.hasOwn(base, 'modelHttpRetryStatusCodes'), false, 'omitted override must be absent')
  assert.deepEqual(base.credential, { operation: 'KEEP' }, 'KEEP must preserve the stored secret')
  assert.equal(base.modelCallTimeoutMillis, '1800000')
  assert.equal(base.modelCallIdleTimeoutMillis, '120000')

  assert.deepEqual(providerUpdateBody({ modelHttpRetryStatusCodes: [500, 503] }).modelHttpRetryStatusCodes, [
    500,
    503,
  ])
  assert.equal(providerUpdateBody({ modelHttpRetryStatusCodes: null }).modelHttpRetryStatusCodes, null)
  assert.deepEqual(providerUpdateBody({ modelHttpRetryStatusCodes: [] }).modelHttpRetryStatusCodes, [])

  const created = providerCreateBody('suffix')
  assert.equal(created.name, 'e2e-provider-suffix')
  assert.equal(created.modelCallTimeoutMillis, '1800000')
  assert.equal(created.modelCallIdleTimeoutMillis, '120000')
  assert.deepEqual(
    created.credential,
    { operation: 'SET', value: 'sk-e2e-test' },
    'provider create only accepts SET credentials',
  )
  assert.equal(Object.hasOwn(created, 'modelHttpRetryStatusCodes'), false)
})

test('settings contract accepts an empty existing HTTP retry list without mutating it on failure', async () => {
  const before = {
    version: '0',
    createdAt: 1767225600000,
    updatedAt: 1767225600000,
    tool: {},
    aiRuntime: { retryBaseDelayMillis: '2500', modelHttpRetryStatusCodes: [] },
    environment: {},
    backendNetwork: { proxyUrl: null, noProxyHosts: '' },
    storageMedia: {
      uploadExpiresSeconds: '3600',
      s3PresignDefaultExpiresSeconds: '900',
      s3PresignMaxExpiresSeconds: '3600',
      canvasMediaProcessTimeoutMillis: '60000',
      thumbnailMaxDimension: 1024,
      thumbnailQuality: 80,
      temporaryResourceTtlSeconds: '259200',
      temporaryResourceCleanupIntervalSeconds: '1800',
    },
    advanced: {},
  }
  const mutationError = new Error('synthetic update failure')
  let puts = 0
  const ctx = {
    async call(method, path) {
      assert.equal(path, '/api/platform/settings')
      if (method === 'GET') return { json: structuredClone(before) }
      assert.equal(method, 'PUT')
      puts++
      throw mutationError
    },
  }
  await assert.rejects(getCase('settings.system_contract_cas').run(ctx), (error) => error === mutationError)
  assert.equal(puts, 1, 'empty existing list must pass validation and reach the CAS update')
  assert.deepEqual(before.aiRuntime.modelHttpRetryStatusCodes, [])

  // 精确字段契约在更新前拒绝顶层与 section 的未知成员。
  for (const invalid of [
    { ...before, unexpected: true },
    { ...before, storageMedia: { ...before.storageMedia, unexpected: true } },
  ]) {
    await assert.rejects(
      getCase('settings.system_contract_cas').run({
        async call(method) {
          assert.equal(method, 'GET')
          return { json: invalid }
        },
      }),
      /fields must be/,
    )
  }
})

test('tool catalog case accepts its public fields and rejects extra fields', async () => {
  // 真实 case 检查工具目录形状，不允许未知成员绕过。
  const entries = [{ name: 'read', description: 'fixture', environmentSupport: 'NONE' }]
  const run = (tools) => getCase('catalog.internal_tools_hidden').run({
    async call() { return { json: tools } },
  })
  await run(entries)
  await assert.rejects(run([{ ...entries[0], unexpected: true }]), /fields must be/)
})

test('chat default environment case executes the create/replace/clear wire with full replacement', async () => {
  // 测试意图：真实执行 crud.chat.default_environment（只 fake HTTP），证明它创建的 POST 请求体确实省略
  // yoloEnabled（由服务端默认 false）并携带默认环境、PUT 整体替换总是携带 title/agentName/environmentName，
  // 显式清空携带 environmentName:null，且非法值确实被发出并拒绝。
  const fake = chatDefaultEnvironmentFixture()
  await getCase('crud.chat.default_environment').run(fake.ctx)

  const create = fake.requests.find(
    (request) => request.method === 'POST' && request.path === '/api/chat/chats',
  )
  const environmentCreate = fake.requests.find(
    (request) => request.method === 'POST' && request.path === '/api/platform/environments',
  )
  // 创建请求必须真的省略 yoloEnabled：只有服务端默认才产生响应里的 false。
  assert.equal(Object.hasOwn(create.body, 'yoloEnabled'), false)
  assert.equal(create.body.environmentName, environmentCreate.body.name)
  assert.equal(environmentCreate.body.daemonConfig.studioUrl, 'http://127.0.0.1:18081')

  const chatPuts = fake.requests.filter(
    (request) => request.method === 'PUT' && request.path.startsWith('/api/chat/chats/'),
  )
  // 整体替换：每次 PUT 都必须回传 title、agentName 与 environmentName。
  assert.ok(chatPuts.length > 0)
  for (const request of chatPuts) {
    assert.equal(Object.hasOwn(request.body, 'title'), true)
    assert.equal(Object.hasOwn(request.body, 'agentName'), true)
    assert.equal(Object.hasOwn(request.body, 'environmentName'), true)
  }
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

test('run-matrix drains deferred stdout/stderr before exiting', () => {
  // 测试意图：预加载夹具把 stdout/stderr 写入推迟到定时器（模拟真实管道下的异步写入），
  // 入口必须在退出前 drain 这些 pending 写入，避免直接 process.exit 截断尾部 case 或吞掉错误输出。
  for (const flag of ['--list', '--docs']) {
    const result = spawnSync(
      process.execPath,
      ['--import', DEFERRED_OUTPUT_FIXTURE, RUN_MATRIX, flag],
      { encoding: 'utf8' },
    )
    assert.equal(result.status, 0, `${flag} exited ${result.status}: ${result.stderr}`)
    assert.equal(result.stderr, '', `${flag} must not write to stderr: ${result.stderr}`)
    for (const id of FREE_L1_CASES) {
      assert.ok(result.stdout.includes(id), `${flag} output must include ${id}`)
    }
    const caseLines = result.stdout.split('\n').filter((line) => /^\[L\d\] /.test(line))
    const totalMatch = result.stdout.match(/Total registered: (\d+)/)
    assert.ok(totalMatch, `${flag} output must report Total registered`)
    assert.equal(
      Number(totalMatch[1]),
      caseLines.length,
      `${flag} enumerated cases must match the reported total`,
    )
  }

  // 未知参数路径同样走自然退出：exit 2、错误写入 stderr，且不产生 stdout。
  const bogus = '--bogus-flag'
  const result = spawnSync(
    process.execPath,
    ['--import', DEFERRED_OUTPUT_FIXTURE, RUN_MATRIX, bogus],
    { encoding: 'utf8' },
  )
  assert.equal(result.status, 2, `unknown arg must exit 2: ${result.stderr}`)
  assert.equal(result.stdout, '', 'unknown arg must not write to stdout')
  assert.ok(result.stderr.includes('Error: unknown arg'), result.stderr)
  assert.ok(result.stderr.includes(bogus), result.stderr)
})
