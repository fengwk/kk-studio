import { expect, test } from './fixture'

/**
 * H 切片浏览器回归：真实 React MessageList + 现行 styles，验证完整成功压缩摘要系统卡片。
 * 覆盖 desktop/mobile、默认折叠、完整正文与保留标签逐行、普通 Markdown、
 * 安全 HTML 不执行/不请求、唯一有界滚动区与刷新展开稳定。
 */
const HARNESS_URL = '/browser-tests/compaction-card.html'

test.describe('Compaction summary system card', () => {
  test('defaults to collapsed and renders the full summary in one bounded scroll region', async ({ page }) => {
    const imageRequests: string[] = []
    page.on('request', (request) => {
      if (request.url().includes('example.invalid')) {
        imageRequests.push(request.url())
      }
    })
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(HARNESS_URL)

    const cards = page.locator('[data-entry-kind="compaction"]')
    await expect(cards).toHaveCount(3)

    // 默认折叠：三张卡片都没有正文，toggle 均为收起态。
    for (let index = 0; index < 3; index += 1) {
      await expect(cards.nth(index).locator('.thread-compaction-body')).toHaveCount(0)
      await expect(cards.nth(index).getByRole('button', { name: '展开压缩摘要' }))
        .toHaveAttribute('aria-expanded', 'false')
    }

    // 长摘要：展开后只有一层有界纵向滚动区，静态正文从顶部开始。
    const longCard = cards.nth(0)
    await longCard.getByRole('button', { name: '展开压缩摘要' }).click()
    const body = longCard.locator('.thread-compaction-body')
    await expect(body).toBeVisible()
    await expect(longCard.getByRole('button', { name: '收起压缩摘要' }))
      .toHaveAttribute('aria-expanded', 'true')

    const metrics = await body.evaluate((element) => ({
      maxHeight: getComputedStyle(element).maxHeight,
      overflowY: getComputedStyle(element).overflowY,
      scrollHeight: element.scrollHeight,
      clientHeight: element.clientHeight,
      scrollTop: element.scrollTop,
    }))
    expect(metrics.overflowY).toBe('auto')
    expect(metrics.maxHeight).toBe('320px')
    expect(metrics.scrollHeight).toBeGreaterThan(metrics.clientHeight)
    expect(metrics.scrollTop).toBe(0)

    const verticalScrollables = await longCard.evaluate((card) =>
      Array.from(card.querySelectorAll('*')).filter((element) => {
        const style = getComputedStyle(element)
        const scrollable = style.overflowY === 'auto' || style.overflowY === 'scroll'
        return scrollable && element.scrollHeight > element.clientHeight + 1
      }).length)
    expect(verticalScrollables).toBe(1)
    // Markdown 根自身不产生第二层纵向滚动。
    expect(await longCard.locator('.md-root').evaluate((root) => getComputedStyle(root).overflowY))
      .toBe('visible')

    // 唯一滚动区可滚动，整份正文都在 DOM 中（不截断首 N 行）。
    await body.evaluate((element) => { element.scrollTop = 400 })
    expect(await body.evaluate((element) => element.scrollTop)).toBeGreaterThan(0)
    await expect(body).toContainText('第 40 段')

    // 保留标签与普通 Markdown：标题、列表、代码块照常渲染，<read-files> 逐行保留为文本。
    const fileCard = cards.nth(1)
    await fileCard.getByRole('button', { name: '展开压缩摘要' }).click()
    await expect(fileCard.locator('.md-root h1')).toHaveText('会话压缩摘要')
    await expect(fileCard.locator('.md-root h2').first()).toHaveText('关键决策')
    await expect(fileCard.locator('.md-root h2')).toHaveCount(2)
    await expect(fileCard.locator('.md-root li')).toHaveCount(2)
    await expect(fileCard.locator('.md-code-block')).toContainText('TURN_PREFIX')
    await expect(fileCard).toContainText('<read-files>')
    await expect(fileCard).toContainText('src/features/ai/runtime/thread-timeline-builder.ts')
    await expect(fileCard.locator('read-files')).toHaveCount(0)
    await expect(fileCard.locator('modified-files')).toHaveCount(0)

    const rawLineCount = await fileCard.evaluate((card) => {
      const root = card.querySelector('.md-root')
      if (root == null) {
        return 0
      }
      const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT)
      let node = walker.nextNode()
      while (node != null) {
        if (node.textContent?.includes('<read-files>')) {
          const range = document.createRange()
          range.selectNodeContents(node.parentElement ?? root)
          return range.getClientRects().length
        }
        node = walker.nextNode()
      }
      return 0
    })
    // <read-files> + 两条逐行路径 + </read-files> 至少四行，换行未被折叠。
    expect(rawLineCount).toBeGreaterThanOrEqual(4)

    const layout = await fileCard.evaluate((card) => {
      const root = card.querySelector('.md-root') as HTMLElement
      const paragraph = root.querySelector('p') as HTMLElement
      const heading = root.querySelector('h2') as HTMLElement
      const spans = root.querySelectorAll('.md-raw-text')
      const lastLineTop = (element: Element) => {
        const range = document.createRange()
        range.selectNodeContents(element)
        const rects = Array.from(range.getClientRects())
        return Math.round(rects[rects.length - 1].top)
      }
      const firstLineTop = (element: Element) => {
        const range = document.createRange()
        range.selectNodeContents(element)
        return Math.round(range.getClientRects()[0].top)
      }
      return {
        gap: Math.round((heading.getBoundingClientRect().top - paragraph.getBoundingClientRect().bottom) * 10) / 10,
        readEndTop: lastLineTop(spans[0]),
        modifiedStartTop: firstLineTop(spans[1]),
      }
    })
    // 行内规则不再给 Markdown 块之间引入多余空隙（pre-wrap 修复前约 47px）。
    expect(layout.gap).toBeGreaterThan(0)
    expect(layout.gap).toBeLessThan(25)
    // 两个保留标签块各自独占行，不被折到同一行。
    expect(layout.modifiedStartTop).toBeGreaterThan(layout.readEndTop)

    // 原始 HTML 安全渲染：不执行脚本，也不产生图片请求。
    const unsafeCard = cards.nth(2)
    await unsafeCard.getByRole('button', { name: '展开压缩摘要' }).click()
    await expect(unsafeCard.locator('script')).toHaveCount(0)
    await expect(unsafeCard.locator('img')).toHaveCount(0)
    await expect(unsafeCard).toContainText('<script>')
    expect(imageRequests).toEqual([])
  })

  test('keeps the expanded state after the timeline is re-projected', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(HARNESS_URL)

    const card = page.locator('[data-entry-kind="compaction"]').nth(1)
    await card.getByRole('button', { name: '展开压缩摘要' }).click()
    await expect(card.getByRole('button', { name: '收起压缩摘要' }))
      .toHaveAttribute('aria-expanded', 'true')

    // 刷新投影：重建 messages 数组但保持摘要 Entry 身份，展开状态不得重置。
    await page.locator('#refresh-timeline').click()

    await expect(card.getByRole('button', { name: '收起压缩摘要' }))
      .toHaveAttribute('aria-expanded', 'true')
    await expect(card.locator('.thread-compaction-body')).toBeVisible()
  })

  test('does not move a reading card when later streaming content grows', async ({ page }) => {
    // 展开与键盘回看接入真实外层 Hook；后续流式增长不能抢回卡片。
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(HARNESS_URL)
    await page.locator('#start-stream').click()
    await expect(page.getByText('stream line 2', { exact: true })).toBeVisible()

    const outer = page.locator('.thread-dialogue')
    const card = page.locator('[data-entry-kind="compaction"]').first()
    await card.getByRole('button', { name: '展开压缩摘要' }).click()
    const body = card.locator('.thread-compaction-body')
    await body.focus()
    await body.press('PageDown')
    await expect.poll(() => body.evaluate((element) => element.scrollTop)).toBeGreaterThan(0)

    const anchor = await outer.evaluate((element) => element.scrollTop)
    const top = (await card.boundingBox())!.y
    await page.locator('#grow-stream').click()
    await expect(outer).toContainText('stream line 42')
    await expect.poll(() => outer.evaluate((element) =>
      element.scrollHeight - element.scrollTop - element.clientHeight)).toBeGreaterThan(210)
    expect(await outer.evaluate((element) => element.scrollTop)).toBe(anchor)
    expect((await card.boundingBox())!.y).toBeCloseTo(top, 0)
  })

  test('stays usable on a narrow mobile viewport', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto(HARNESS_URL)

    const card = page.locator('[data-entry-kind="compaction"]').nth(1)
    await expect(card).toBeVisible()
    await expect(page.getByText('上下文已压缩').first()).toBeVisible()

    // 展开控制在第一行最右。
    const cardBox = await card.boundingBox()
    const toggleBox = await card.locator('.thread-compaction-toggle').boundingBox()
    expect(cardBox).not.toBeNull()
    expect(toggleBox).not.toBeNull()
    if (cardBox != null && toggleBox != null) {
      const toggleRight = toggleBox.x + toggleBox.width
      expect(toggleRight).toBeGreaterThan(cardBox.x + cardBox.width - 24)
    }

    await card.getByRole('button', { name: '展开压缩摘要' }).click()
    await expect(card.locator('.thread-compaction-body')).toBeVisible()
    // 窄屏不产生横向滚动。
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1))
      .toBe(true)
  })
})
