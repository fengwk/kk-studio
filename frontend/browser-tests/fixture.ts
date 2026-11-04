import { test as base, expect, type WebError, type Page, type Locator } from '@playwright/test'

/** 首屏挂载等待上限：超过仍无子节点且脚本请求失败时判定为传输中断。 */
const BOOT_MOUNT_TIMEOUT = 3000

/** #root 已挂载（或页面本就没有 React 根）才算首屏完成。 */
async function appMounted(page: Page): Promise<boolean> {
  try {
    await page.waitForFunction(() => {
      const root = document.getElementById('root')
      return root == null || root.childElementCount > 0
    }, undefined, { timeout: BOOT_MOUNT_TIMEOUT })
    return true
  } catch {
    return false
  }
}

/**
 * 宿主机网络状态变化（并行任务创建/删除 Docker 网桥、VPN 重连等）会让 Chromium 以
 * net::ERR_NETWORK_CHANGED 中止在途请求。首屏 module 脚本被中止时 SPA 根本不会挂载，
 * 后续断言只能等成一次难以解读的空白页超时。
 *
 * 这里只处理「本次导航有脚本请求在传输层失败，且结束后 #root 仍未挂载」：重载一次后
 * 继续原用例。断言与流程不变；真实损坏的 bundle 重载后仍然空白，仍由原用例失败。
 */
function guardTransientBootFailure(page: Page) {
  let scriptRequestFailed = false
  page.on('requestfailed', (request) => {
    if (request.resourceType() === 'script' && request.failure() != null) {
      scriptRequestFailed = true
    }
  })
  const goto = page.goto.bind(page)
  page.goto = async (url, options) => {
    scriptRequestFailed = false
    const response = await goto(url, options)
    if (!scriptRequestFailed || await appMounted(page)) {
      return response
    }
    scriptRequestFailed = false
    const reloaded = await page.reload({ timeout: options?.timeout, waitUntil: options?.waitUntil })
    await appMounted(page)
    return reloaded ?? response
  }
}

// 监听 context 级 weberror 未捕获异常，teardown 断言无报错，覆盖首屏与新窗口
export const test = base.extend<{ pageErrors: Error[] }>({
  // 包裹默认 page：仅用于首屏传输中断时的一次重载，不放宽任何用例断言。
  page: async ({ page }, runTest) => {
    guardTransientBootFailure(page)
    await runTest(page)
  },
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
