import { assert, expectHttpError, httpJson } from '../lib/http.mjs'
import { registerCase } from '../lib/registry.mjs'

function errorEnvelope(error) {
  const body = JSON.parse(error.body)
  assert(body && typeof body === 'object', `expected error envelope: ${error.body}`)
  return body
}

async function localizedGet(ctx, requestPath, language, status = 400) {
  return expectHttpError(
    () =>
      httpJson(
        ctx.baseUrl,
        'GET',
        requestPath,
        undefined,
        60_000,
        { 'Accept-Language': language },
      ),
    { status },
  )
}

registerCase({
  id: 'i18n.error_response_accept_language',
  level: 'L1',
  title: 'HTTP 错误响应按 Accept-Language 返回英文或中文',
  docs: 'Domain error 与 ResponseStatusException 保持 status/code/context/detail，仅本地化 message/title；不支持语言回退英文',
  async run(ctx) {
    const unknownChatPath = '/api/ai/chat/00000000-0000-0000-0000-000000000999'
    const englishDomain = errorEnvelope(await localizedGet(ctx, unknownChatPath, 'en-US', 404))
    const chineseDomain = errorEnvelope(await localizedGet(ctx, unknownChatPath, 'zh-CN', 404))
    assert(englishDomain.code === 'resource_not_found', JSON.stringify(englishDomain))
    assert(englishDomain.message === 'The chat was not found.', JSON.stringify(englishDomain))
    assert(chineseDomain.code === 'resource_not_found', JSON.stringify(chineseDomain))
    assert(chineseDomain.message === '未找到 chat。', JSON.stringify(chineseDomain))
    assert(chineseDomain.errors?.resource === 'chat', JSON.stringify(chineseDomain))
    assert(
      chineseDomain.errors?.detail ===
        'chat not found: 00000000-0000-0000-0000-000000000999',
      JSON.stringify(chineseDomain),
    )

    // Catalog controller 的类型不匹配由 Domain advice 归一为 validation。
    const mismatchPath = '/api/ai/catalog/models?pageNumber=abc&pageSize=1'
    const englishValidation = errorEnvelope(await localizedGet(ctx, mismatchPath, 'en-US'))
    const chineseValidation = errorEnvelope(await localizedGet(ctx, mismatchPath, 'zh-CN'))
    const fallbackValidation = errorEnvelope(await localizedGet(ctx, mismatchPath, 'fr-FR'))
    assert(englishValidation.code === 'validation', JSON.stringify(englishValidation))
    assert(
      englishValidation.message === 'The pageNumber parameter has an invalid value.',
      JSON.stringify(englishValidation),
    )
    assert(chineseValidation.code === 'validation', JSON.stringify(chineseValidation))
    assert(
      chineseValidation.message === 'pageNumber 参数的值无效。',
      JSON.stringify(chineseValidation),
    )
    assert(
      chineseValidation.errors?.detail === 'pageNumber has invalid value: abc',
      JSON.stringify(chineseValidation),
    )
    assert(
      fallbackValidation.message === englishValidation.message,
      JSON.stringify(fallbackValidation),
    )

    // Controller-originated ResponseStatusException 保持 HTTP code/context，仅本地化 message/title。
    const responseStatusPath = '/api/ai/runtime/threads/not-a-number/snapshot'
    const englishHttp = errorEnvelope(await localizedGet(ctx, responseStatusPath, 'en-US'))
    const chineseHttp = errorEnvelope(await localizedGet(ctx, responseStatusPath, 'zh-CN'))
    const fallbackHttp = errorEnvelope(await localizedGet(ctx, responseStatusPath, 'fr-FR'))
    assert(englishHttp.code === 'BAD_REQUEST', JSON.stringify(englishHttp))
    assert(englishHttp.message === 'The request is invalid.', JSON.stringify(englishHttp))
    assert(englishHttp.errors?.title === 'Bad Request', JSON.stringify(englishHttp))
    assert(chineseHttp.code === 'BAD_REQUEST', JSON.stringify(chineseHttp))
    assert(chineseHttp.message === '请求无效。', JSON.stringify(chineseHttp))
    assert(chineseHttp.errors?.title === '请求错误', JSON.stringify(chineseHttp))
    assert(
      chineseHttp.errors?.detail === 'threadId must be a canonical UUID: not-a-number',
      JSON.stringify(chineseHttp),
    )
    assert(fallbackHttp.code === englishHttp.code, JSON.stringify(fallbackHttp))
    assert(fallbackHttp.message === englishHttp.message, JSON.stringify(fallbackHttp))
    assert(fallbackHttp.errors?.title === englishHttp.errors?.title, JSON.stringify(fallbackHttp))
  },
})
