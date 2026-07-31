import { assert, expectHttpError, httpJson } from '../lib/http.mjs'
import { registerCase } from '../lib/registry.mjs'

function errorEnvelope(error) {
  const body = JSON.parse(error.body)
  assert(body && typeof body === 'object', `expected error envelope: ${error.body}`)
  return body
}

async function localizedGet(ctx, requestPath, language) {
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
    { status: requestPath.startsWith('/api/ai/chat/') ? 404 : 400 },
  )
}

registerCase({
  id: 'i18n.error_response_accept_language',
  level: 'L1',
  title: 'HTTP 错误响应按 Accept-Language 返回英文或中文',
  docs: 'Domain error 与 ResponseStatusException 保持 status/code/context/detail，仅本地化 message/title；不支持语言回退英文',
  async run(ctx) {
    const unknownChatPath = '/api/ai/chat/999999999999'
    const englishDomain = errorEnvelope(await localizedGet(ctx, unknownChatPath, 'en-US'))
    const chineseDomain = errorEnvelope(await localizedGet(ctx, unknownChatPath, 'zh-CN'))
    assert(englishDomain.code === 'resource_not_found', JSON.stringify(englishDomain))
    assert(englishDomain.message === 'The chat was not found.', JSON.stringify(englishDomain))
    assert(chineseDomain.code === 'resource_not_found', JSON.stringify(chineseDomain))
    assert(chineseDomain.message === '未找到 chat。', JSON.stringify(chineseDomain))
    assert(chineseDomain.errors?.resource === 'chat', JSON.stringify(chineseDomain))
    assert(
      chineseDomain.errors?.detail === 'chat not found: 999999999999',
      JSON.stringify(chineseDomain),
    )

    const invalidSortPath = '/api/ai/runtime/threads?sort=invalid&limit=1'
    const englishHttp = errorEnvelope(await localizedGet(ctx, invalidSortPath, 'en-US'))
    const chineseHttp = errorEnvelope(await localizedGet(ctx, invalidSortPath, 'zh-CN'))
    const fallbackHttp = errorEnvelope(await localizedGet(ctx, invalidSortPath, 'fr-FR'))
    assert(englishHttp.code === 'BAD_REQUEST', JSON.stringify(englishHttp))
    assert(englishHttp.message === 'The request is invalid.', JSON.stringify(englishHttp))
    assert(englishHttp.errors?.title === 'Bad Request', JSON.stringify(englishHttp))
    assert(chineseHttp.code === 'BAD_REQUEST', JSON.stringify(chineseHttp))
    assert(chineseHttp.message === '请求无效。', JSON.stringify(chineseHttp))
    assert(chineseHttp.errors?.title === '请求错误', JSON.stringify(chineseHttp))
    assert(
      chineseHttp.errors?.detail === 'sort must be recent or created',
      JSON.stringify(chineseHttp),
    )
    assert(fallbackHttp.message === englishHttp.message, JSON.stringify(fallbackHttp))
    assert(fallbackHttp.errors?.title === englishHttp.errors?.title, JSON.stringify(fallbackHttp))
  },
})
