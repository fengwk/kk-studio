import { assert, expectHttpError } from '../lib/http.mjs'
import { createCaseAdder } from '../lib/registry.mjs'

export const cases = []
const registerCase = createCaseAdder(cases)

function problemBody(error) {
  const body = JSON.parse(error.body)
  assert(body && typeof body === 'object', `expected problem object: ${error.body}`)
  assert(body.type === 'about:blank' && body.status === error.status, `invalid problem metadata: ${error.body}`)
  return body
}

async function localizedGet(ctx, requestPath, language, { status = 400, code = 'validation' } = {}) {
  return expectHttpError(
    () =>
      ctx.request(requestPath, { headers: { 'Accept-Language': language } }),
    { status, code },
  )
}

registerCase({
  id: 'i18n.error_response_accept_language',
  level: 'L1',
  title: 'HTTP 错误响应按 Accept-Language 返回英文或中文',
  docs: 'Domain error 与类型化参数校验保持 problem status/code/detail，仅本地化 title；不支持语言回退英文',
  async run(ctx) {
    const unknownSpacePath = '/api/chat-spaces/00000000-0000-0000-0000-000000000999/tree'
    const englishDomain = problemBody(
      await localizedGet(ctx, unknownSpacePath, 'en-US', { status: 404, code: 'not_found' }),
    )
    const chineseDomain = problemBody(
      await localizedGet(ctx, unknownSpacePath, 'zh-CN', { status: 404, code: 'not_found' }),
    )
    assert(englishDomain.code === 'not_found', JSON.stringify(englishDomain))
    assert(englishDomain.reason === 'WORKSPACE_NOT_FOUND' && englishDomain.title === 'The space was not found.', JSON.stringify(englishDomain))
    assert(chineseDomain.code === 'not_found', JSON.stringify(chineseDomain))
    assert(chineseDomain.reason === englishDomain.reason && chineseDomain.title === '未找到空间。', JSON.stringify(chineseDomain))
    assert(chineseDomain.detail === 'Space not found', JSON.stringify(chineseDomain))

    // Catalog controller 的类型不匹配由 Domain advice 归一为 validation。
    const mismatchPath = '/api/platform/model-catalog?limit=abc'
    const englishValidation = problemBody(await localizedGet(ctx, mismatchPath, 'en-US'))
    const chineseValidation = problemBody(await localizedGet(ctx, mismatchPath, 'zh-CN'))
    const fallbackValidation = problemBody(await localizedGet(ctx, mismatchPath, 'fr-FR'))
    assert(englishValidation.code === 'validation', JSON.stringify(englishValidation))
    assert(
      englishValidation.title === 'The limit parameter has an invalid value.',
      JSON.stringify(englishValidation),
    )
    assert(chineseValidation.code === 'validation', JSON.stringify(chineseValidation))
    assert(
      chineseValidation.title === 'limit 参数的值无效。',
      JSON.stringify(chineseValidation),
    )
    assert(
      chineseValidation.detail === 'limit has an invalid value',
      JSON.stringify(chineseValidation),
    )
    assert(
      fallbackValidation.title === englishValidation.title,
      JSON.stringify(fallbackValidation),
    )

    // 归属校验在控制器转换参数前拒绝非 canonical Thread ID。
    const responseStatusPath = '/api/harness/threads/not-a-number'
    const englishHttp = problemBody(await localizedGet(ctx, responseStatusPath, 'en-US'))
    const chineseHttp = problemBody(await localizedGet(ctx, responseStatusPath, 'zh-CN'))
    const fallbackHttp = problemBody(await localizedGet(ctx, responseStatusPath, 'fr-FR'))
    for (const problem of [englishHttp, chineseHttp, fallbackHttp]) {
      assert(problem.reason === 'INVALID_ARGUMENT' && problem.detail === 'Expected a canonical resource ID', JSON.stringify(problem))
    }
    assert(englishHttp.title === 'Invalid resource request.', JSON.stringify(englishHttp))
    assert(chineseHttp.title !== englishHttp.title, JSON.stringify(chineseHttp))
    assert(fallbackHttp.title === englishHttp.title, JSON.stringify(fallbackHttp))
  },
})
