import { expect, test } from './fixture'

test.describe('Layout Foundation Infrastructure Contract', () => {
  // 验证静态 preview 加载预构建 bundle，无 dev 运行时、HMR 或实时转译请求
  test('static preview loads pre-built bundles without dev HMR or on-demand transpilation requests', async ({
    page,
  }) => {
    const requestedUrls: string[] = []
    page.on('request', (req) => {
      requestedUrls.push(req.url())
    })

    await page.goto('/browser-tests/chat-layout-harness.html')
    await expect(page.locator('#chat-layout-select')).toBeVisible()

    expect(requestedUrls.length).toBeGreaterThan(0)
    expect(requestedUrls.filter((url) => url.includes('/src/'))).toEqual([])
    expect(requestedUrls.filter((url) => url.includes('/@vite/client'))).toEqual([])
    expect(requestedUrls.filter((url) => url.includes('/.vite/deps/'))).toEqual([])
    expect(
      requestedUrls.filter((url) => url.includes('/assets/') && url.endsWith('.js')).length,
    ).toBeGreaterThan(0)
  })
})
