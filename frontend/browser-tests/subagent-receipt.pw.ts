import { expect, test, tokenColor } from './fixture'

/**
 * S1 浏览器回归：真实 React MessageList + 现行 styles，验证 SUBAGENT_RESULT 回执信息卡。
 * 覆盖统一容器的 info/error 语义、默认 0 行、独立来源链接、键盘展开、结果优先、
 * 固定错误块（收起 3 行 + 提示）、完整正文无内部滚动，以及窄 pane 不撑出摘要与来源。
 */
const HARNESS_URL = '/browser-tests/subagent-receipt-harness.html'
const TASK_TAIL = 'FINAL_TASK_TAIL_MARKER'

test.describe('Subagent receipt card', () => {
  test('renders collapsed info cards with container tones and never the scrim surface', async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(HARNESS_URL)

    const cards = page.locator('.thread-subagent-receipt')
    await expect(cards).toHaveCount(6)

    // 每张回执默认 0 行：header 只有身份、终态图标与任务短预览，正文不在 DOM 中。
    for (let index = 0; index < 6; index += 1) {
      await expect(cards.nth(index).locator('.thread-disclosure-body')).toHaveCount(0)
      await expect(cards.nth(index).getByRole('button', { expanded: false })).toHaveCount(1)
      await expect(cards.nth(index).locator('.thread-system-message-header')).toHaveCount(0)
      if (index === 1 || index === 5) {
        // 失败回执的固定错误块是唯一允许出现在收起态 preview 的内容。
        await expect(cards.nth(index).locator('.thread-disclosure-preview')).toHaveCount(1)
        await expect(cards.nth(index).locator('.thread-disclosure-preview .thread-disclosure-error'))
          .toHaveCount(1)
        await expect(cards.nth(index).locator('.thread-subagent-receipt-section')).toHaveCount(0)
      } else {
        await expect(cards.nth(index).locator('.thread-disclosure-preview')).toHaveCount(0)
      }
      const order = await cards.nth(index).evaluate((element) => {
        const header = element.querySelector('.thread-disclosure-header')!
        const text = header.querySelector('.thread-disclosure-header-text')!
        const link = header.querySelector('a')!
        const toggle = header.querySelector('.thread-disclosure-toggle')!
        return {
          textFirst: header.firstElementChild === text,
          lastIsToggle: header.lastElementChild === toggle,
          separate: !toggle.contains(link),
          ordered: link.getBoundingClientRect().right <= toggle.getBoundingClientRect().left,
          returnedVisible: text.textContent?.includes('已返回'),
        }
      })
      expect(order).toEqual({
        textFirst: true,
        lastIsToggle: true,
        separate: true,
        ordered: true,
        returnedVisible: false,
      })
    }

    const card = cards.nth(0)
    await expect(card).toHaveAttribute('data-subagent-state', 'completed')
    await expect(card).toHaveClass(/is-info/)
    const info = await card.evaluate((element) => {
      const style = getComputedStyle(element)
      return {
        background: style.backgroundColor,
        backgroundImage: style.backgroundImage,
        borderWidth: style.borderTopWidth,
        borderColor: style.borderTopColor,
      }
    })
    // 统一容器使用 info 细边框 + info-soft 叠色；不是 scrim 纯黑底。
    const scrim = await tokenColor(page, '--scrim')
    expect(info.background).toBe(await tokenColor(page, '--surface'))
    expect(info.backgroundImage).toContain('linear-gradient')
    expect(info.borderWidth).toBe('1px')
    expect(info.borderColor).toBe(await tokenColor(page, '--info-border'))
    expect(info.background).not.toBe(scrim)

    // 失败回执使用 error 色调；取消仍保持 info 色调。
    await expect(cards.nth(1)).toHaveClass(/is-error/)
    expect(await cards.nth(1).evaluate((element) => getComputedStyle(element).borderTopColor))
      .toBe(await tokenColor(page, '--danger-border'))
    await expect(cards.nth(2)).toHaveClass(/is-info/)
    await expect(cards.nth(2)).toHaveAttribute('data-subagent-state', 'cancelled')

    // 对照：其它系统通知仍走通用系统卡（neutral/scrim 底），默认 0 行。
    const generic = page.locator('.thread-notification:not(.thread-subagent-receipt)').first()
    await expect(generic).toBeVisible()
    expect(await generic.evaluate((element) => getComputedStyle(element).backgroundColor))
      .toBe(scrim)
    await expect(generic.locator('.thread-disclosure-preview')).toHaveCount(0)

    // 同一来源 subagent 的两条回执各自保留身份与独立来源链接；可见文字不含 UUID。
    for (const index of [0, 4]) {
      const link = cards.nth(index).getByRole('link', { name: '查看 subagent 执行' })
      await expect(link).toHaveAttribute('href', '/threads/child-thread-1')
      await expect(link).toHaveText('查看执行')
    }

    await page.screenshot({ path: testInfo.outputPath('subagent-receipt-collapsed.png') })
  })

  test('expands to the result first and the full task in the body without inner scrolling', async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(HARNESS_URL)

    const card = page.locator('.thread-subagent-receipt').nth(0)
    const toggle = card.getByRole('button', { expanded: false })
    // 折叠态只出现截断后的预览，完整任务正文与结果还没进入 DOM。
    await expect(card).not.toContainText(TASK_TAIL)
    await expect(card).not.toContainText('delivered the change')

    await toggle.click()
    const preview = card.locator('.thread-disclosure-preview')
    const body = card.locator('.thread-disclosure-body')
    await expect(preview).toBeVisible()
    await expect(body).toBeVisible()
    await expect(card.getByRole('button', { expanded: true })).toHaveCount(1)

    // 结果优先：结果分区在 preview，任务原文在 body，两者都完整显示。
    await expect(preview.locator('.thread-subagent-receipt-label')).toHaveText('结果')
    await expect(preview).toContainText('delivered the change')
    await expect(body.locator('.thread-subagent-receipt-label')).toHaveText('任务')
    await expect(body).toContainText(TASK_TAIL)
    expect(await preview.evaluate((element) => element.getBoundingClientRect().top))
      .toBeLessThan(await body.evaluate((element) => element.getBoundingClientRect().top))

    // 展开态没有内部滚动区，也不产生横向溢出。
    const innerScrollables = await card.evaluate((element) =>
      Array.from(element.querySelectorAll('*')).filter((child) => {
        const style = getComputedStyle(child)
        const scrollable = style.overflowY === 'auto' || style.overflowY === 'scroll'
        return scrollable && child.scrollHeight > child.clientHeight + 1
      }).length)
    expect(innerScrollables).toBe(0)
    const overflow = await card.evaluate((element) => ({
      scrollWidth: element.scrollWidth,
      clientWidth: element.clientWidth,
    }))
    expect(overflow.scrollWidth).toBeLessThanOrEqual(overflow.clientWidth + 1)

    await page.screenshot({ path: testInfo.outputPath('subagent-receipt-expanded.png') })
  })

  test('keeps a failed reason fixed in the preview and expands it from three lines', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(HARNESS_URL)

    // 短失败原因：收起态即可见，且按纯文本呈现。
    const shortErrorCard = page.locator('.thread-subagent-receipt').nth(1)
    const shortError = shortErrorCard.locator('.thread-disclosure-error')
    await expect(shortError).toBeVisible()
    await expect(shortError).toContainText('provider **failed** while streaming')
    await expect(shortError.locator('strong')).toHaveCount(0)
    await expect(shortErrorCard.locator('.thread-disclosure-body')).toHaveCount(0)

    await shortErrorCard.getByRole('button', { name: '展开详情' }).click()
    await expect(shortErrorCard.locator('.thread-disclosure-preview').locator('.thread-disclosure-error').first())
      .toBeVisible()
    await expect(shortErrorCard.locator('.thread-disclosure-body')).toContainText('partial findings: three call sites')

    // 长失败原因：收起 3 行 + 提示，展开后完整显示，错误块始终固定在 preview 顶部。
    const longErrorCard = page.locator('.thread-subagent-receipt').nth(5)
    const longError = longErrorCard.locator('.thread-disclosure-error')
    await expect(longError).toBeVisible()
    const collapsedText = await longError.locator('.thread-disclosure-error-text').textContent()
    expect(collapsedText?.split('\n')).toEqual(['failure line one', 'failure line two', 'failure line three'])
    const hint = longErrorCard.getByRole('button', { name: '… 3 more lines' })
    await expect(hint).toBeVisible()

    await hint.click()
    await expect(longErrorCard.getByRole('button', { name: '收起详情' }))
      .toHaveAttribute('aria-expanded', 'true')
    await expect(longError.locator('.thread-disclosure-error-text')).toContainText('failure line six')
    await expect(longErrorCard.getByRole('button', { name: '… 3 more lines' })).toHaveCount(0)
    // 固定错误块仍在 preview 顶部，结果在其后。
    const firstPreviewChild = longErrorCard.locator('.thread-disclosure-preview').locator(':scope > *').first()
    await expect(firstPreviewChild).toHaveClass(/thread-disclosure-error/)
    await expect(longErrorCard.locator('.thread-disclosure-preview')).toContainText('diagnostic result text')
    await expect(longErrorCard.locator('.thread-disclosure-body')).toContainText('partial diagnostics')
  })

  test('toggles with the keyboard and keeps the source link separately focusable', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(HARNESS_URL)

    const card = page.locator('.thread-subagent-receipt').nth(1)
    const toggle = card.locator('.thread-disclosure-toggle')
    await toggle.focus()
    await page.keyboard.press('Enter')
    await expect(toggle).toHaveAttribute('aria-expanded', 'true')
    await expect(card.locator('.thread-disclosure-body')).toBeVisible()

    await page.keyboard.press(' ')
    await expect(toggle).toHaveAttribute('aria-expanded', 'false')

    // DOM 顺序与视觉一致：来源链接在最后的展开按钮之前。
    await toggle.focus()
    await page.keyboard.press('Shift+Tab')
    const focused = await page.evaluate(() => ({
      tag: document.activeElement?.tagName ?? '',
      href: document.activeElement?.getAttribute('href') ?? '',
      label: document.activeElement?.getAttribute('aria-label') ?? '',
    }))
    expect(focused.tag).toBe('A')
    expect(focused.href).toBe('/threads/child-thread-2')
    expect(focused.label).toBe('查看 subagent 执行')
  })

  for (const width of [360, 320]) {
    test(`stays inside the pane at ${width}px with a long agent name`, async ({ page }, testInfo) => {
      await page.setViewportSize({ width, height: 800 })
      await page.goto(HARNESS_URL)

      const cards = page.locator('.thread-subagent-receipt')
      await expect(cards).toHaveCount(6)

      // 收起时 header 由容器统一截断为 2 行；展开后完整显示长身份。
      const longAgentCard = cards.nth(3)
      const headerText = longAgentCard.locator('.thread-disclosure-header-text')
      await expect(headerText).toHaveClass(/is-clamped/)
      expect(await headerText.evaluate((element) =>
        getComputedStyle(element).getPropertyValue('-webkit-line-clamp'))).toBe('2')

      const geometry = await longAgentCard.evaluate((element) => {
        const header = element.querySelector('.thread-disclosure-header-text') as HTMLElement
        const agent = element.querySelector('.thread-subagent-receipt-agent') as HTMLElement
        const source = element.querySelector('.thread-subagent-receipt-source') as HTMLElement
        return {
          cardRight: element.getBoundingClientRect().right,
          cardScrollWidth: element.scrollWidth,
          cardClientWidth: element.clientWidth,
          agentText: agent.textContent ?? '',
          headerOverflow: getComputedStyle(header).overflow,
          sourceRight: source.getBoundingClientRect().right,
          sourceText: source.textContent ?? '',
        }
      })
      expect(geometry.cardScrollWidth).toBeLessThanOrEqual(geometry.cardClientWidth + 1)
      // 长身份被完整保留在 DOM 中，收起态由 CSS clamp 控制显示行数。
      expect(geometry.agentText).toContain('long-subagent-identity-name-that-must-wrap-inside-the-card')
      expect(geometry.headerOverflow).toBe('hidden')
      expect(geometry.sourceRight).toBeLessThanOrEqual(geometry.cardRight + 1)
      expect(geometry.sourceText).toBe('查看执行')

      await longAgentCard.getByRole('button', { name: '展开详情' }).click()
      await expect(longAgentCard.locator('.thread-disclosure-header-text')).not.toHaveClass(/is-clamped/)
      expect(await longAgentCard.locator('.thread-subagent-receipt-agent').evaluate((element) =>
        element.textContent ?? '')).toContain('long-subagent-identity-name-that-must-wrap-inside-the-card')

      // 任何回执与整页都不产生横向滚动。
      for (let index = 0; index < 6; index += 1) {
        const overflow = await cards.nth(index).evaluate((element) => ({
          scrollWidth: element.scrollWidth,
          clientWidth: element.clientWidth,
        }))
        expect(overflow.scrollWidth).toBeLessThanOrEqual(overflow.clientWidth + 1)
      }
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1))
        .toBe(true)

      await testInfo.attach('geometry', { body: JSON.stringify({ width, geometry }, null, 2), contentType: 'application/json' })
      await page.screenshot({ path: testInfo.outputPath(`subagent-receipt-${width}.png`) })
    })
  }
})
