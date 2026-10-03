import { expect, test } from './fixture'

test.describe('Layout Foundation Infrastructure Contract', () => {
  test('static preview loads pre-built bundles without dev HMR or on-demand transpilation requests', async ({
    page,
  }) => {
    const requestedUrls: string[] = []
    page.on('request', (req) => {
      requestedUrls.push(req.url())
    })

    // 访问静态纯 React harness，验证真实组件在无后端代理下的构建产物加载契约
    await page.goto('/browser-tests/chat-layout-harness.html')

    // 等待真实 React 组件挂载完成，确保证实渲染管线已执行
    const trigger = page.locator('#chat-layout-select')
    await expect(trigger).toBeVisible()

    // 验证所有发起的网络请求：必须是静态 bundle，绝不能包含 dev 运行时、HMR 或实时转译请求
    expect(requestedUrls.length).toBeGreaterThan(0)

    const devSourceRequests = requestedUrls.filter((url) => url.includes('/src/'))
    const viteClientRequests = requestedUrls.filter((url) => url.includes('/@vite/client'))
    const viteDepsRequests = requestedUrls.filter((url) => url.includes('/.vite/deps/'))
    const staticBundleRequests = requestedUrls.filter(
      (url) => url.includes('/assets/') && url.endsWith('.js'),
    )

    expect(devSourceRequests).toEqual([])
    expect(viteClientRequests).toEqual([])
    expect(viteDepsRequests).toEqual([])
    expect(staticBundleRequests.length).toBeGreaterThan(0)
  })
})
