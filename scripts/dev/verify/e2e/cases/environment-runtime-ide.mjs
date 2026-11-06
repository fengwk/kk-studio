import { assert, cid, expectHttpError } from '../lib/http.mjs'
import { createEnvironmentFixture, deleteEnvironmentFixture } from '../lib/fixtures.mjs'
import { createCaseAdder } from '../lib/registry.mjs'

export const cases = []
const registerCase = createCaseAdder(cases)

/**
 * IDE 视图身份：固定规范小写 UUID。
 *
 * <p>本组用例只验证“前置条件不满足时明确拒绝”的语义：既不建立真实浏览器会话，也不依赖任何 Daemon 或模型，
 * 因此可以在免费 L1 矩阵里稳定复现。
 */
const IDE_VIEW_ID = '11111111-1111-4111-8111-111111111111'
const IDE_DOCUMENT_ID = '22222222-2222-4222-8222-222222222222'
const IDE_EVENT_NODE_ID = '33333333-3333-4333-8333-333333333333'
const IDE_CONNECTION_ID = '44444444-4444-4444-8444-444444444444'
const IDE_CLAIM_ID = '55555555-5555-4555-8555-555555555555'

/**
 * canonical problem 断言：状态、code 与 reason 由 expectHttpError 按产品抛出处的契约锁定。
 *
 * <p>这里只补信封元数据；只看状态码不足以证明契约，机器码才是分支依据。
 */
function assertProblemEnvelope(error) {
  let body = null
  try {
    body = JSON.parse(error.body)
  } catch {
    body = null
  }
  assert(body && typeof body === 'object', `error body must be a problem object: ${error.body}`)
  assert(body.type === 'about:blank' && typeof body.title === 'string', `invalid problem metadata: ${error.body}`)
  return body
}

registerCase({
  id: 'environment.runtime_absent_unavailable',
  level: 'L1',
  title: 'Environment 运行快照缺失时明确 unavailable，不伪造空快照',
  docs: 'GET /api/platform/environments/{id}/runtime 在没有本代 READY 快照（无 READY owner 路由）时返回 409 unavailable + ENVIRONMENT_UNAVAILABLE，响应不含 revision/daemonVersion/update 等运行快照字段，绝不伪造 update=null 的假快照；非 canonical environmentId 返回 400 validation；用例创建的 Environment 在结束时按权威 version 清理',
  async run(ctx) {
    const environment = await createEnvironmentFixture(ctx, `e2e-runtime-absent-${cid().slice(0, 8)}`)

    try {
      const error = await expectHttpError(
        () => ctx.call('GET', `/api/platform/environments/${encodeURIComponent(environment.id)}/runtime`),
        { status: 409, code: 'unavailable', reason: 'ENVIRONMENT_UNAVAILABLE' },
      )
      assertProblemEnvelope(error)
      assert(
        !/"revision"\s*:/.test(error.body),
        `runtime absence must not fabricate a revision: ${error.body}`,
      )
      assert(
        !/"daemonVersion"\s*:/.test(error.body),
        `runtime absence must not fabricate a daemon version: ${error.body}`,
      )
      assert(
        !/"update"\s*:/.test(error.body),
        `runtime absence must not fabricate an update slot: ${error.body}`,
      )

      // 非 canonical environmentId 在进入服务之前即被拒绝，不落到运行快照读取。
      const invalid = await expectHttpError(
        () => ctx.call('GET', '/api/platform/environments/not-a-uuid/runtime'),
        { status: 400, code: 'validation' },
      )
      assertProblemEnvelope(invalid)
    } finally {
      await deleteEnvironmentFixture(ctx, environment.id)
    }
  },
})

registerCase({
  id: 'environment.ide_view_no_ready_owner_rejected',
  level: 'L1',
  title: 'IDE 视图在没有 READY owner 时明确拒绝，不伪造绑定',
  docs: '在新建且没有 READY owner 的 Environment 上，IDE 打开、列举目录、切换目录、终端、移动布局和关闭返回 409 unavailable + IDE_UNAVAILABLE，且不伪造绑定；非 canonical environmentId 返回 400 validation；结束时按权威 version 清理。本用例只证明无 owner 路由拒绝，不证明 exact-intent 强制校验；READY owner 下的精确意图校验需单独验收，此处未验证',
  async run(ctx) {
    const environment = await createEnvironmentFixture(ctx, `e2e-ide-no-owner-${cid().slice(0, 8)}`)
    const viewsPath = `/api/ide/environments/${encodeURIComponent(environment.id)}/views`
    const openBody = {
      viewId: IDE_VIEW_ID,
      documentId: IDE_DOCUMENT_ID,
      eventNodeId: IDE_EVENT_NODE_ID,
      connectionId: IDE_CONNECTION_ID,
      claimId: IDE_CLAIM_ID,
      mode: 'create',
      directory: '/workspace',
    }

    try {
      const openError = await expectHttpError(
        () => ctx.call('POST', viewsPath, openBody),
        { status: 409, code: 'unavailable', reason: 'IDE_UNAVAILABLE' },
      )
      assertProblemEnvelope(openError)
      assert(
        !/"entryUrl"\s*:/.test(openError.body) && !/"bindingEpoch"\s*:/.test(openError.body),
        `rejected open must not fabricate a view binding: ${openError.body}`,
      )

      // 没有 READY owner 时，目录切换、终端聚焦与关闭同样由路由拒绝。
      const directoryError = await expectHttpError(
        () =>
          ctx.call('PUT', `${viewsPath}/${IDE_VIEW_ID}/directory`, {
            documentId: IDE_DOCUMENT_ID,
            bindingEpoch: '1',
            directory: '/workspace/subdir',
          }),
        { status: 409, code: 'unavailable', reason: 'IDE_UNAVAILABLE' },
      )
      assertProblemEnvelope(directoryError)

      const terminalError = await expectHttpError(
        () =>
          ctx.call('POST', `${viewsPath}/${IDE_VIEW_ID}/focus-terminal`, {
            documentId: IDE_DOCUMENT_ID,
            bindingEpoch: '1',
          }),
        { status: 409, code: 'unavailable', reason: 'IDE_UNAVAILABLE' },
      )
      assertProblemEnvelope(terminalError)

      const listError = await expectHttpError(
        () => ctx.call('GET', `/api/ide/environments/${encodeURIComponent(environment.id)}/directories`),
        { status: 409, code: 'unavailable', reason: 'IDE_UNAVAILABLE' },
      )
      assertProblemEnvelope(listError)
      const mobileError = await expectHttpError(
        () => ctx.call('POST', `${viewsPath}/${IDE_VIEW_ID}/apply-mobile-layout`, {
          documentId: IDE_DOCUMENT_ID,
          bindingEpoch: '1',
        }),
        { status: 409, code: 'unavailable', reason: 'IDE_UNAVAILABLE' },
      )
      assertProblemEnvelope(mobileError)

      const closeError = await expectHttpError(
        () =>
          ctx.call('POST', `${viewsPath}/${IDE_VIEW_ID}/close`, {
            documentId: IDE_DOCUMENT_ID,
            bindingEpoch: '1',
            discard: true,
          }),
        { status: 409, code: 'unavailable', reason: 'IDE_UNAVAILABLE' },
      )
      assertProblemEnvelope(closeError)

      // 非 canonical environmentId 在进入路由判定之前即被拒绝。
      const invalid = await expectHttpError(
        () => ctx.call('POST', '/api/ide/environments/not-a-uuid/views', openBody),
        { status: 400, code: 'validation' },
      )
      assertProblemEnvelope(invalid)
    } finally {
      await deleteEnvironmentFixture(ctx, environment.id)
    }
  },
})
