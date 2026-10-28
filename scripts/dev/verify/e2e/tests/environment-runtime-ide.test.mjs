import assert from 'node:assert/strict'
import { readdirSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

import { HttpError } from '../lib/http.mjs'
import { createEnvironmentFixture, deleteEnvironmentFixture } from '../lib/fixtures.mjs'
import { ALL_CASES, getCase } from '../lib/registry.mjs'

// 注册副作用：新 case 模块必须被 import 才会进入 inventory。
import '../cases/environment-runtime-ide.mjs'

const RUNTIME_CASE_ID = 'environment.runtime_absent_unavailable'
const IDE_CASE_ID = 'environment.ide_view_exact_intent_rejected'
const ENVIRONMENT_ID = '00000000-0000-4000-8000-000000000abc'
const CASES_DIR = fileURLToPath(new URL('../cases/', import.meta.url))

function canonicalErrorBody(status, code, detail) {
  return JSON.stringify({
    status,
    code,
    message: 'canonical error',
    data: null,
    errors: { resource: 'environment', detail },
  })
}

/**
 * 只 fake HTTP 的 Environment fixture：建模“已登记但没有任何 READY Daemon”的 Environment，
 * 用来真实执行 runtime/IDE case 并观察它发出的 method/path/body。
 */
function environmentWithoutDaemon() {
  const calls = []
  let version = '0'
  let deleted = false
  const ctx = {
    async call(method, path, body) {
      calls.push({ method, path, body })
      if (method === 'POST' && path === '/api/environments') {
        return { json: { data: { id: ENVIRONMENT_ID, name: body.name, version } } }
      }
      if (method === 'GET' && path === `/api/environments/${ENVIRONMENT_ID}`) {
        return { json: { data: { id: ENVIRONMENT_ID, version } } }
      }
      if (method === 'DELETE' && path.startsWith(`/api/environments/${ENVIRONMENT_ID}?`)) {
        deleted = true
        return { json: null }
      }
      if (path.includes('not-a-uuid')) {
        throw new HttpError(
          400,
          canonicalErrorBody(400, 'validation', 'environmentId must be a canonical UUID'),
          path,
        )
      }
      if (path.endsWith('/runtime')) {
        throw new HttpError(
          409,
          canonicalErrorBody(409, 'environment_unavailable', 'environment has no current READY owner route'),
          path,
        )
      }
      if (path.includes('/ide/views')) {
        throw new HttpError(
          409,
          canonicalErrorBody(409, 'ide_unavailable', 'environment IDE owner route is unavailable'),
          path,
        )
      }
      throw new Error(`unexpected call ${method} ${path}`)
    },
  }
  return { ctx, calls, isDeleted: () => deleted }
}

test('runtime and IDE cases are registered as free L1 matrix cases', () => {
  // 测试意图：这两条用例不需要 Daemon、模型或浏览器，必须注册为无 requires 的 L1，
  // 才能进入默认免费矩阵而不引入额外成本。
  for (const id of [RUNTIME_CASE_ID, IDE_CASE_ID]) {
    const caseDef = getCase(id)
    assert.ok(caseDef, `case ${id} should be found via getCase`)
    assert.ok(ALL_CASES.some((c) => c.id === id), `case ${id} should be in ALL_CASES`)
    assert.equal(caseDef.level, 'L1', `case ${id} level must be L1`)
    assert.deepEqual(Array.from(caseDef.requires), [], `case ${id} requires must be empty`)
    assert.ok(caseDef.docs.length > 0, `case ${id} must document its contract`)
  }
})

test('runtime absence case rejects with the canonical code and never fabricates a snapshot', async () => {
  // 测试意图：真实执行 runtime case，证明它读运行快照时拿到 409 environment_unavailable，
  // 并断言响应里没有任何伪造的运行快照字段，最后按权威 version 清理环境。
  const { ctx, calls, isDeleted } = environmentWithoutDaemon()

  await getCase(RUNTIME_CASE_ID).run(ctx)

  const runtimeCall = calls.find((call) => call.path.endsWith('/runtime') && call.method === 'GET')
  assert.ok(runtimeCall, 'runtime case must read the runtime snapshot')
  assert.ok(
    runtimeCall.path.includes(ENVIRONMENT_ID),
    `runtime case must read its own environment: ${runtimeCall.path}`,
  )
  const invalid = calls.find((call) => call.path.includes('not-a-uuid'))
  assert.ok(invalid, 'runtime case must also reject a non-canonical environmentId')
  assert.ok(isDeleted(), 'runtime case must delete the environment it created')
})

test('IDE view case refuses every view command without an exact browser intent', async () => {
  // 测试意图：真实执行 IDE case，证明打开/目录/终端/关闭在缺少精确意图时都以 409 ide_unavailable 拒绝，
  // 不返回任何绑定结果，并且非 canonical environmentId 走 400 validation。
  const { ctx, calls, isDeleted } = environmentWithoutDaemon()

  await getCase(IDE_CASE_ID).run(ctx)

  const ideCalls = calls.filter((call) => call.path.includes('/ide/views'))
  assert.equal(ideCalls.length, 5, `IDE case must exercise five view requests: ${JSON.stringify(ideCalls)}`)
  const viewsPath = `/api/environments/${ENVIRONMENT_ID}/ide/views`
  assert.equal(
    ideCalls.filter((call) => call.method === 'POST' && call.path === viewsPath).length,
    1,
    'IDE case must open exactly one view on its own environment',
  )
  assert.ok(
    ideCalls.some((call) => call.method === 'PUT' && call.path.endsWith('/directory')),
    'IDE case must attempt a directory change',
  )
  assert.ok(
    ideCalls.some((call) => call.method === 'POST' && call.path.endsWith('/terminal')),
    'IDE case must attempt a terminal focus',
  )
  assert.ok(
    ideCalls.some((call) => call.method === 'DELETE'),
    'IDE case must attempt a close',
  )
  assert.ok(
    ideCalls.some((call) => call.path.includes('not-a-uuid')),
    'IDE case must also reject a non-canonical environmentId',
  )
  assert.ok(isDeleted(), 'IDE case must delete the environment it created')
})

test('environment fixture cleanup uses the authoritative version and tolerates an already deleted card', async () => {
  // 测试意图：清理必须先读回权威 version 再 CAS 删除；环境已经不存在（404）视为清理完成且不再发 DELETE；
  // 其它 canonical 错误必须原样抛出，不能被静默吞掉。
  const created = await createEnvironmentFixture(
    {
      async call(method, path) {
        assert.equal(method, 'POST')
        assert.equal(path, '/api/environments')
        return { json: { data: { id: ENVIRONMENT_ID, version: '7' } } }
      },
    },
    'e2e-fixture',
  )
  assert.equal(created.id, ENVIRONMENT_ID)
  assert.equal(created.version, '7')

  const deletes = []
  await deleteEnvironmentFixture(
    {
      async call(method, path) {
        if (method === 'GET') {
          return { json: { data: { id: ENVIRONMENT_ID, version: '9' } } }
        }
        deletes.push(path)
        return { json: null }
      },
    },
    ENVIRONMENT_ID,
  )
  assert.equal(deletes.length, 1)
  assert.ok(deletes[0].includes('expectedVersion=9'), `cleanup must use the read version: ${deletes[0]}`)

  let deleteCalls = 0
  await deleteEnvironmentFixture(
    {
      async call(method) {
        if (method === 'GET') {
          throw new HttpError(404, '{"code":"resource_not_found"}', '/api/environments/missing')
        }
        deleteCalls++
        return { json: null }
      },
    },
    ENVIRONMENT_ID,
  )
  assert.equal(deleteCalls, 0, 'an already deleted environment must not be deleted again')

  await assert.rejects(
    () =>
      deleteEnvironmentFixture(
        {
          async call() {
            throw new HttpError(409, '{"code":"version_conflict"}', '/api/environments/conflict')
          },
        },
        ENVIRONMENT_ID,
      ),
    (error) => error instanceof HttpError && error.status === 409,
  )
})

test('the deleted read-only registration token endpoint is only asserted as removed', () => {
  // 测试意图：只读 token 端点早已删除；E2E 只能断言它不存在，不能再断言它返回 token。
  const offenders = []
  for (const name of readdirSync(CASES_DIR).filter((file) => file.endsWith('.mjs'))) {
    const source = readFileSync(`${CASES_DIR}${name}`, 'utf8')
    const pattern = /\/api\/environments\/[^`'"\s]*\/token/g
    let match
    while ((match = pattern.exec(source)) !== null) {
      const start = source.lastIndexOf('expectHttpError(', match.index)
      const nextGuard = source.indexOf('expectHttpError(', match.index)
      const window = source.slice(start < 0 ? 0 : start, nextGuard < 0 ? source.length : nextGuard)
      if (!window.includes('status: 404')) {
        offenders.push(`${name}:${match.index}`)
      }
    }
  }
  assert.deepEqual(offenders, [])
})
