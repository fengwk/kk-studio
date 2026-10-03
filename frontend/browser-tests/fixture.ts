import { test as base, expect, type Page, type Locator } from '@playwright/test'

/**
 * 统一布局测试夹具：
 * 自动收集当前页面以及上下文衍生页面的 pageerror 事件。
 * 在每个测试用例 teardown 阶段，严格断言 expect(pageErrors).toEqual([])。
 * 确保页面发生的任何未捕获异常（即便页面已成功渲染或 locator 存在）都会判定测试失败，绝不静默放行。
 */
export const test = base.extend<{ pageErrors: Error[] }>({
  pageErrors: [
    async ({ context, page }, use) => {
      const pageErrors: Error[] = []
      const listener = (error: Error) => {
        pageErrors.push(error)
      }
      page.on('pageerror', listener)
      context.on('page', (newPage) => {
        newPage.on('pageerror', listener)
      })

      await use(pageErrors)

      expect(pageErrors).toEqual([])
    },
    { auto: true },
  ],
})

export { expect }
export type { Page, Locator }
