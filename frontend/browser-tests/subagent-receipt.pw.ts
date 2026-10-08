import { expect, test } from './fixture'

/**
 * S1 浏览器回归：真实 React MessageList + 现行 styles，验证 SUBAGENT_RESULT 回执信息卡。
 * 覆盖 info 语义（浅青蓝/细边框，非 scrim）、默认折叠、独立来源链接、键盘展开、
 * 完整正文展开，以及长 agent 名/窄 pane 不撑出摘要与来源。
 */
const HARNESS_URL = '/browser-tests/subagent-receipt-harness.html'
const TASK_TAIL = 'FINAL_TASK_TAIL_MARKER'

test.describe('Subagent receipt card', () => {
  test('renders collapsed info cards with info tokens and never the scrim surface', async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(HARNESS_URL)

    const cards = page.locator('.thread-subagent-receipt')
    await expect(cards).toHaveCount(5)

    // 每个回执一行摘要：agent、真实终态、任务预览，且正文默认折叠。
    for (let index = 0; index < 5; index += 1) {
      await expect(cards.nth(index).locator('.thread-subagent-receipt-detail')).toHaveCount(0)
      await expect(cards.nth(index).getByRole('button', { expanded: false })).toHaveCount(1)
      await expect(cards.nth(index).locator('.thread-system-message-header')).toHaveCount(0)
      const order = await cards.nth(index).evaluate((element) => {
        const summary = element.querySelector('.thread-subagent-receipt-summary')!
        const link = summary.querySelector('a')!
        const toggle = summary.querySelector('button')!
        return {
          last: summary.lastElementChild === toggle,
          separate: !toggle.contains(link),
          ordered: link.getBoundingClientRect().right <= toggle.getBoundingClientRect().left,
          returnedVisible: summary.textContent?.includes('已返回'),
        }
      })
      expect(order).toEqual({ last: true, separate: true, ordered: true, returnedVisible: false })
    }

    const card = cards.nth(0)
    await expect(card).toHaveAttribute('data-subagent-state', 'completed')
    const metrics = await card.evaluate((element) => {
      const probe = document.createElement('span')
      probe.style.borderColor = 'color-mix(in srgb, var(--info) 35%, transparent)'
      document.body.appendChild(probe)
      const expectedBorder = getComputedStyle(probe).borderTopColor
      probe.remove()
      const style = getComputedStyle(element)
      return {
        background: style.backgroundColor,
        borderWidth: style.borderTopWidth,
        borderColor: style.borderTopColor,
        expectedBorder,
      }
    })
    // 浅青蓝 info-soft 染色 + 1px info 细边框；不是 scrim 纯黑底。
    expect(metrics.background).toBe('rgba(119, 199, 245, 0.16)')
    expect(metrics.borderWidth).toBe('1px')
    expect(metrics.borderColor).toBe(metrics.expectedBorder)
    expect(metrics.background).not.toBe('rgba(0, 0, 0, 0.72)')

    // 对照：其它系统通知仍走通用系统卡（scrim 底），未被回执样式污染。
    const generic = page.locator('.thread-notification:not(.thread-subagent-receipt)').first()
    await expect(generic).toBeVisible()
    expect(await generic.evaluate((element) => getComputedStyle(element).backgroundColor))
      .toBe('rgba(0, 0, 0, 0.72)')

    // 同一来源 subagent 的两条回执各自保留身份与独立来源链接。
    await expect(cards.nth(0).getByRole('link', { name: '查看 subagent 执行' }))
      .toHaveAttribute('href', '/threads/child-thread-1')
    await expect(cards.nth(4).getByRole('link', { name: '查看 subagent 执行' }))
      .toHaveAttribute('href', '/threads/child-thread-1')

    await page.screenshot({ path: testInfo.outputPath('subagent-receipt-collapsed.png') })
  })

  test('expands to the full task and report without truncation or horizontal overflow', async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(HARNESS_URL)

    const card = page.locator('.thread-subagent-receipt').nth(0)
    const toggle = card.getByRole('button', { expanded: false })
    // 折叠态只出现截断后的预览，完整任务正文还没进入 DOM。
    await expect(card).not.toContainText(TASK_TAIL)

    await toggle.click()
    const detail = card.locator('.thread-subagent-receipt-detail')
    await expect(detail).toBeVisible()
    await expect(card.getByRole('button', { expanded: true })).toHaveCount(1)

    // 完整 task 与 Markdown 报告都在，且正文不被截断或横向裁掉。
    await expect(detail).toContainText(TASK_TAIL)
    await expect(detail).toContainText('任务')
    await expect(detail).toContainText('delivered the change')
    const overflow = await detail.evaluate((element) => ({
      scrollWidth: element.scrollWidth,
      clientWidth: element.clientWidth,
      overflow: getComputedStyle(element).overflow,
    }))
    expect(overflow.scrollWidth).toBeLessThanOrEqual(overflow.clientWidth + 1)
    expect(overflow.overflow).not.toBe('hidden')

    await page.screenshot({ path: testInfo.outputPath('subagent-receipt-expanded.png') })
  })

  test('toggles with the keyboard and keeps the source link separately focusable', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(HARNESS_URL)

    const card = page.locator('.thread-subagent-receipt').nth(1)
    const toggle = card.locator('.thread-subagent-receipt-toggle')
    await toggle.focus()
    await page.keyboard.press('Enter')
    await expect(toggle).toHaveAttribute('aria-expanded', 'true')
    await expect(card.locator('.thread-subagent-receipt-detail')).toBeVisible()

    await page.keyboard.press(' ')
    await expect(toggle).toHaveAttribute('aria-expanded', 'false')

    // DOM 顺序与视觉一致：链接在最后的展开按钮之前。
    await toggle.focus()
    await page.keyboard.press('Shift+Tab')
    const focused = await page.evaluate(() => ({
      tag: document.activeElement?.tagName ?? '',
      href: document.activeElement?.getAttribute('href') ?? '',
    }))
    expect(focused.tag).toBe('A')
    expect(focused.href).toBe('/threads/child-thread-2')
  })

  for (const width of [360, 320]) {
  test(`stays inside the pane at ${width}px with a long agent name`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 800 })
    await page.goto(HARNESS_URL)

    const cards = page.locator('.thread-subagent-receipt')
    await expect(cards).toHaveCount(5)

    // 长 agent 名在自身范围内换行，不截断、不撑破摘要与来源。
    const longAgentCard = cards.nth(3)
    const geometry = await longAgentCard.evaluate((element) => {
      const agent = element.querySelector('.thread-subagent-receipt-agent') as HTMLElement
      const source = element.querySelector('.thread-subagent-receipt-source') as HTMLElement
      const preview = element.querySelector('.thread-subagent-receipt-preview') as HTMLElement
      return {
        cardRight: element.getBoundingClientRect().right,
        cardLeft: element.getBoundingClientRect().left,
        cardScrollWidth: element.scrollWidth,
        cardClientWidth: element.clientWidth,
        agentHeight: agent.getBoundingClientRect().height,
        agentText: agent.textContent ?? '',
        agentRight: agent.getBoundingClientRect().right,
        sourceRight: source.getBoundingClientRect().right,
        sourceWidth: source.getBoundingClientRect().width,
        previewOverflow: getComputedStyle(preview).overflow,
        previewTextOverflow: getComputedStyle(preview).textOverflow,
      }
    })
    expect(geometry.cardScrollWidth).toBeLessThanOrEqual(geometry.cardClientWidth + 1)
    // 长身份被完整保留并折行成多行。
    expect(geometry.agentHeight).toBeGreaterThan(24)
    expect(geometry.agentText).toContain('long-subagent-identity-name-that-must-wrap-inside-the-card')
    expect(geometry.agentRight).toBeLessThanOrEqual(geometry.cardRight + 1)
    expect(geometry.sourceRight).toBeLessThanOrEqual(geometry.cardRight + 1)
    expect(geometry.sourceWidth).toBeGreaterThan(100)
    expect(geometry.previewOverflow).toBe('hidden')
    expect(geometry.previewTextOverflow).toBe('ellipsis')
    await testInfo.attach('geometry', { body: JSON.stringify({ width, geometry }, null, 2), contentType: 'application/json' })

    // 任何回执与整页都不产生横向滚动。
    for (let index = 0; index < 5; index += 1) {
      const overflow = await cards.nth(index).evaluate((element) => ({
        scrollWidth: element.scrollWidth,
        clientWidth: element.clientWidth,
      }))
      expect(overflow.scrollWidth).toBeLessThanOrEqual(overflow.clientWidth + 1)
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1))
      .toBe(true)

    await page.screenshot({ path: testInfo.outputPath(`subagent-receipt-${width}.png`) })
  })
  }
})
