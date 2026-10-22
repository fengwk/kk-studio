import { test as base, expect, type WebError, type Page, type Locator } from '@playwright/test'

// 监听 context 级 weberror 未捕获异常，teardown 断言无报错，覆盖首屏与新窗口
export const test = base.extend<{ pageErrors: Error[] }>({
  pageErrors: [
    async ({ context }, runTest) => {
      const pageErrors: Error[] = []
      const listener = (e: WebError) => {
        pageErrors.push(e.error())
      }
      context.on('weberror', listener)
      try {
        await runTest(pageErrors)
      } finally {
        context.off('weberror', listener)
      }
      expect(pageErrors).toEqual([])
    },
    { auto: true },
  ],
})

export { expect }
export type { Page, Locator }

/** 在页面上解析颜色 token 的计算值；与元素计算样式同一序列化形态（color-mix 结果为 color(srgb …)）。 */
export function tokenColor(page: Page, token: string): Promise<string> {
  return page.evaluate((name) => {
    const probe = document.createElement('span')
    probe.style.color = `var(${name})`
    document.body.appendChild(probe)
    const color = getComputedStyle(probe).color
    probe.remove()
    return color
  }, token)
}
