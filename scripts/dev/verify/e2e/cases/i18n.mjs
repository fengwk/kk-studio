import { assert, expectHttpError, httpJson } from '../lib/http.mjs'
import { createCaseAdder } from '../lib/registry.mjs'

export const cases = []
const registerCase = createCaseAdder(cases)

function problemBody(error) {
  const body = JSON.parse(error.body)
  assert(body && typeof body === 'object', `expected problem object: ${error.body}`)
  assert(body.type === 'about:blank' && body.status === error.status, `invalid problem metadata: ${error.body}`)
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
  docs: 'Domain error 与类型化参数校验保持 problem status/code/detail，仅本地化 title；不支持语言回退英文',
  async run(ctx) {
    const unknownChatPath = '/api/chat/chats/00000000-0000-0000-0000-000000000999'
    const englishDomain = problemBody(await localizedGet(ctx, unknownChatPath, 'en-US', 404))
    const chineseDomain = problemBody(await localizedGet(ctx, unknownChatPath, 'zh-CN', 404))
    assert(englishDomain.code === 'not_found', JSON.stringify(englishDomain))
    assert(englishDomain.title === 'The chat was not found.', JSON.stringify(englishDomain))
    assert(chineseDomain.code === 'not_found', JSON.stringify(chineseDomain))
    assert(chineseDomain.title === '未找到 对话。', JSON.stringify(chineseDomain))
    assert(chineseDomain.detail === 'chat not found', JSON.stringify(chineseDomain))

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
      chineseValidation.detail === 'limit has invalid value: abc',
      JSON.stringify(chineseValidation),
    )
    assert(
      fallbackValidation.title === englishValidation.title,
      JSON.stringify(fallbackValidation),
    )

    // 类型化 UUID 参数经 Spring 转换失败，由 Domain advice 归一为 validation。
    const responseStatusPath = '/api/harness/threads/not-a-number'
    const englishHttp = problemBody(await localizedGet(ctx, responseStatusPath, 'en-US'))
    const chineseHttp = problemBody(await localizedGet(ctx, responseStatusPath, 'zh-CN'))
    const fallbackHttp = problemBody(await localizedGet(ctx, responseStatusPath, 'fr-FR'))
    assert(englishHttp.code === 'validation', JSON.stringify(englishHttp))
    assert(
      englishHttp.title === 'The threadId parameter has an invalid value.',
      JSON.stringify(englishHttp),
    )
    assert(chineseHttp.code === 'validation', JSON.stringify(chineseHttp))
    assert(
      chineseHttp.title === 'threadId 参数的值无效。',
      JSON.stringify(chineseHttp),
    )
    assert(
      chineseHttp.detail === 'threadId has an invalid value',
      JSON.stringify(chineseHttp),
    )
    assert(fallbackHttp.code === englishHttp.code, JSON.stringify(fallbackHttp))
    assert(fallbackHttp.title === englishHttp.title, JSON.stringify(fallbackHttp))
  },
})
