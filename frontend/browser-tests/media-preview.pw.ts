import { readFileSync } from 'node:fs'
import { test, expect } from '@playwright/test'

const css = readFileSync(new URL('../src/styles.css', import.meta.url), 'utf8')

for (const sample of [
  { name: 'landscape', width: 512, height: 288, container: 900 },
  { name: 'portrait', width: 288, height: 512, container: 900 },
  { name: 'small', width: 80, height: 40, container: 900 },
  { name: 'narrow', width: 512, height: 288, container: 240 },
  { name: 'tall', width: 512, height: 2048, container: 900 },
]) {
  // 锁定真实布局：不放大、无比例变形、窄容器不溢出，不能由 jsdom 的样式断言替代。
  test(`preview uses intrinsic dimensions: ${sample.name}`, async ({ page }) => {
    await page.setViewportSize({ width: 1100, height: 1000 })
    await page.setContent(`
      <div style="width:${sample.container}px">
        <figure class="resource-media-preview">
          <button class="resource-media-preview-trigger">
            <img alt="preview">
            <span class="resource-media-preview-name">preview.png</span>
          </button>
        </figure>
      </div>
    `)
    await page.addStyleTag({ content: css })
    await page.locator('img').evaluate(async (image, dimensions) => {
      const canvas = document.createElement('canvas')
      canvas.width = dimensions.width
      canvas.height = dimensions.height
      image.src = canvas.toDataURL('image/png')
      await image.decode()
    }, sample)
    const box = await page.locator('img').boundingBox()
    const factor = Math.min(1, sample.container / sample.width, 760 / sample.width, 680 / sample.height)
    expect(box!.width).toBeCloseTo(sample.width * factor, 0)
    expect(box!.height).toBeCloseTo(sample.height * factor, 0)
    const button = await page.locator('button').boundingBox()
    expect(button!.width).toBeCloseTo(box!.width, 0)
  })
}
