import { expect, test } from './fixture'

for (const locale of ['zh-CN', 'en-US'] as const) {
  for (const [width, height] of [[1440, 900], [390, 844]] as const) {
    test(`Agent editor sections are stacked in ${locale} at ${width}px`, async ({ page }) => {
      await page.setViewportSize({ width, height })
      await page.goto(`/browser-tests/catalog-editor-harness.html?scenario=agent&locale=${locale}`)
      const dialog = page.getByRole('dialog')
      await expect(dialog.getByRole('navigation')).toHaveCount(0)
      const capabilities = dialog.locator('#catalog-section-capabilities')
      await expect(capabilities.getByRole('searchbox')).toHaveCount(3)
      const body = dialog.locator('.modal-body')
      expect(await body.evaluate((el) => el.scrollWidth <= el.clientWidth)).toBe(true)
      if (locale === 'zh-CN') {
        await page.screenshot({ path: `../reports/r130-editors/product-agent-${width}.png` })
      }
    })
  }
}
