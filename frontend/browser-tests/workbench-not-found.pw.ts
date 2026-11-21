import { expect, test, type Page } from './fixture'
import { isInside, routeApplicationEvents, shoot } from './visual-shots'

/**
 * WorkbenchShell 未匹配路由的真实 404 回归：挂载真实 AppShell + StudioRoutes，
 * 用最小 contributions 触发 `*` 路由，验证空态落在内容区内、动作使用共享按钮外观
 * 并与说明文字居中对齐；1440×900 与 390×844、zh-CN 与 en-US 各跑一遍。
 */

const VIEWPORTS = [
  { name: '1440x900', width: 1440, height: 900 },
  { name: '390x844', width: 390, height: 844 },
] as const

const LOCALES = ['zh-CN', 'en-US'] as const

const COPY = {
  'zh-CN': {
    heading: '页面不可用',
    description: '该页面扩展不存在或已卸载。',
    back: '返回对话',
  },
  'en-US': {
    heading: 'Page unavailable',
    description: 'The page extension is missing or has been unloaded.',
    back: 'Back to Chat',
  },
} as const

/** 壳层只读全局待处理计数与事件通道；两者都给空快照，不启动后端。 */
async function transport(page: Page) {
  await routeApplicationEvents(page)
  await page.route('**/api/**', (route) =>
    route.fulfill({ json: { items: [], total: 0 } }),
  )
}

for (const viewport of VIEWPORTS) {
  for (const locale of LOCALES) {
    const suffix = `${viewport.name}-${locale}`

    test(`missing page renders a centered shared empty state — ${suffix}`, async ({ page }, testInfo) => {
      await page.setViewportSize({ width: viewport.width, height: viewport.height })
      await transport(page)
      await page.goto(`/browser-tests/workbench-not-found-harness.html?locale=${locale}`)

      const screenBody = page.locator('.screen-body').first()
      await expect(screenBody).toBeVisible()
      const emptyState = screenBody.locator('.ui-empty-state')
      await expect(emptyState).toBeVisible()
      await expect(emptyState).toHaveCSS('text-align', 'center')

      const copy = COPY[locale]
      await expect(emptyState).toContainText(copy.heading)
      await expect(emptyState).toContainText(copy.description)

      const link = emptyState.getByRole('link', { name: copy.back })
      await expect(link).toBeVisible()
      await expect(link).toHaveClass(/\bbtn-primary\b/)
      await expect(link).toHaveAttribute('href', '/chats')

      // 共享按钮外观：32px 高、渐变底、无下划线、圆角，而不是裸文本链接。
      const style = await link.evaluate((element) => {
        const computed = getComputedStyle(element)
        return {
          height: element.getBoundingClientRect().height,
          backgroundImage: computed.backgroundImage,
          textDecoration: computed.textDecorationLine,
          radius: Number.parseFloat(computed.borderTopLeftRadius),
        }
      })
      expect(style.height).toBe(32)
      expect(style.backgroundImage).not.toBe('none')
      expect(style.textDecoration).toBe('none')
      expect(style.radius).toBeGreaterThan(0)

      // 位于内容区内，并与空态说明文字居中对齐；不再贴视口左下角。
      expect(await isInside(link, screenBody)).toBe(true)
      const [linkBox, emptyBox, bodyBox] = await Promise.all([
        link.boundingBox(),
        emptyState.boundingBox(),
        screenBody.boundingBox(),
      ])
      expect(linkBox, 'link box').not.toBeNull()
      expect(emptyBox, 'empty state box').not.toBeNull()
      expect(bodyBox, 'screen body box').not.toBeNull()
      if (linkBox === null || emptyBox === null || bodyBox === null) {
        return
      }
      expect(linkBox.x + linkBox.width / 2).toBeCloseTo(emptyBox.x + emptyBox.width / 2, 0)
      expect(linkBox.y).toBeLessThan(bodyBox.y + bodyBox.height * 0.75)
      expect(linkBox.y + linkBox.height).toBeLessThan(viewport.height - 16)

      await shoot(page, testInfo, `workbench-not-found-${suffix}`)
    })
  }
}
