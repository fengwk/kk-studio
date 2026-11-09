import { expect, test } from './fixture'
import type { Locator, Page } from './fixture'

/**
 * 工具卡片真实浏览器回归：统一 ThreadDisclosure、配额截取、外层阅读锚点与长卡片收起。
 *
 * 覆盖只能在真实浏览器验证的契约——收起/展开的几何、无内部滚动、流式尾部窗口、
 * 手动展开不抢外层锚点、媒体加载不移动卡片、流式转终态保持身份、长卡片底部收起。
 */

type HarnessApi = {
  task(): void
  idle(lines?: number): void
  idleMid(lines?: number): void
  streaming(lines?: number): void
  grow(lines: number): void
  settle(lines?: number): void
  readImage(): void
  readUnknown(): void
  streamingTail(lines?: number): void
  growTail(lines: number): void
  shrinkTail(lines?: number): void
  addImageMid(lines: number): void
  unknownLong(chars?: number): void
  longExpanded(lines?: number): void
  restorePosition(offset: number): void
}

async function harness(page: Page, method: keyof HarnessApi, arg?: number): Promise<void> {
  await page.evaluate(
    ([name, value]) => {
      const api = window.toolCardHarness as unknown as Record<string, (size?: number) => void>
      api[name as string](value as number)
    },
    [method, arg] as const,
  )
  await flushLayout(page)
}

/** 等 React 提交与 transcript 的下一帧布局工作，不用固定毫秒延时猜测何时完成。 */
async function flushLayout(page: Page): Promise<void> {
  await page.evaluate(() => new Promise<void>((resolve) => {
    requestAnimationFrame(() => requestAnimationFrame(() => resolve()))
  }))
}

const dialogue = (page: Page): Locator => page.locator('.thread-dialogue')
const bashCard = (page: Page): Locator => page.locator('.thread-turn-tool').first()
const bashViewport = (page: Page): Locator =>
  page.locator('.thread-turn-tool').first().locator('.thread-tool-output').first()
const toggle = (page: Page): Locator => page.locator('.thread-disclosure-toggle').first()

async function topEdge(target: Locator): Promise<number> {
  const box = await target.boundingBox()
  return box?.y ?? Number.NaN
}

async function scrollTop(target: Locator): Promise<number> {
  return target.evaluate((element) => element.scrollTop)
}

/**
 * 真实用户滚轮：唯一允许「恢复跟随」的意图来源。指针放在 transcript 顶部条带
 * （普通消息/卡片 Header，必然不是内层可滚动视口），确保滚轮作用在外层。
 */
async function scrollTranscript(page: Page, deltaY: number): Promise<void> {
  const box = await dialogue(page).boundingBox()
  await page.mouse.move((box?.x ?? 0) + 20, (box?.y ?? 0) + 12)
  await page.mouse.wheel(0, deltaY)
}

async function scrollMetrics(target: Locator): Promise<{
  scrollTop: number
  scrollHeight: number
  clientHeight: number
}> {
  return target.evaluate((element) => ({
    scrollTop: element.scrollTop,
    scrollHeight: element.scrollHeight,
    clientHeight: element.clientHeight,
  }))
}

/**
 * 真实鼠标点击会先 scrollIntoView，破坏本用例要测量的阅读锚点。
 * 这里直接触发 DOM click，行为与键盘/鼠标激活一致，但不移动外层。
 */
async function clickWithoutScrolling(target: Locator): Promise<void> {
  await target.evaluate((element) => (element as HTMLElement).click())
}

test.beforeEach(async ({ page }) => {
  await page.goto('/browser-tests/tool-card-harness.html')
  await expect(page.locator('.harness-hint')).toHaveText('tool card browser harness')
})

for (const width of [1440, 360, 320]) {
  test(`task header action and balanced header geometry at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 820 })
    await harness(page, 'task')
    const card = page.locator('.thread-turn-tool')
    await expect(card.locator('.thread-tool-summary')).toHaveText(
      'task Explorer [max_turns=4]',
    )
    await expect(card.locator('.thread-tool-state-label, .task-tool-fields')).toHaveCount(0)
    // 收起时不显示 prompt，也不展示完整 thread_id。
    await expect(card).not.toContainText('Keep this user text: Thread ID user-owned-marker')
    await expect(card.locator('.thread-tool-summary')).not.toContainText('00000000-')
    const links = card.getByRole('link')
    await expect(links).toHaveCount(1)
    await expect(links).toHaveAccessibleName('查看 subagent 执行')
    await expect(links).toHaveAttribute('href', '/threads/00000000-0000-4000-8000-000000000005')
    await links.click()
    await expect(page.getByLabel('selected thread')).toHaveText('00000000-0000-4000-8000-000000000005')

    // 展开后完整参数与 prompt 可见，链接仍在 Header 操作区。
    await card.locator('.thread-disclosure-toggle').click()
    await expect(card.locator('.thread-disclosure-toggle')).toHaveAttribute('aria-expanded', 'true')
    await expect(card.locator('.thread-tool-summary')).toContainText('thread_id=00000000-0000-4000-8000-000000000004')
    await expect(card).toContainText('Keep this user text: Thread ID user-owned-marker')
    const typography = await links.evaluate((element) => {
      const style = getComputedStyle(element)
      const body = getComputedStyle(document.querySelector('.task-tool-renderer .thread-tool-pre')!)
      return {
        link: [style.fontFamily, style.fontSize, style.fontWeight],
        body: [body.fontFamily, body.fontSize, body.fontWeight],
      }
    })
    expect(typography.link).toEqual(typography.body)
    expect(typography.link[0].toLowerCase()).not.toContain('monospace')
    await page.screenshot({ path: testInfo.outputPath(`task-card-expanded-${width}.png`) })

    await card.locator('.thread-disclosure-toggle').click()
    const geometry = await card.evaluate((element) => {
      const surface = element.getBoundingClientRect()
      const header = element.querySelector('.thread-disclosure-header')!.getBoundingClientRect()
      const summary = element.querySelector('.thread-tool-name')!.getBoundingClientRect()
      const button = element.querySelector('.thread-disclosure-toggle')!.getBoundingClientRect()
      return {
        top: header.top - surface.top, bottom: surface.bottom - header.bottom,
        center: Math.abs(summary.y + summary.height / 2 - button.y - button.height / 2),
        overflow: element.scrollWidth - element.clientWidth,
      }
    })
    expect(Math.abs(geometry.top - geometry.bottom)).toBeLessThanOrEqual(1)
    expect(geometry.center).toBeLessThanOrEqual(1)
    expect(geometry.overflow).toBeLessThanOrEqual(1)
    await testInfo.attach('geometry', { body: JSON.stringify({ width, geometry, typography }, null, 2), contentType: 'application/json' })
    await page.screenshot({ path: testInfo.outputPath(`task-card-${width}.png`) })
  })
}

test('follows streaming growth, keeps the tail window, pauses on user scroll-up, and resumes at the bottom', async ({ page }) => {
  await harness(page, 'streaming', 20)
  const body = dialogue(page)
  const outerDistance = () =>
    scrollMetrics(body).then((m) => m.scrollHeight - m.scrollTop - m.clientHeight)

  // 挂载即贴底；bash 收起时只显示最后 10 个逻辑行。
  await expect.poll(outerDistance).toBeLessThanOrEqual(1)
  await expect(bashViewport(page)).toContainText('build step 20')
  expect((await bashViewport(page).textContent())!.split('\n')).not.toContain('build step 1')
  await expect(page.locator('.thread-disclosure-hint').first()).toHaveText('… 10 earlier lines')

  // 流式增长：外层继续贴底，尾部窗口继续跟随。
  await harness(page, 'grow', 60)
  await expect.poll(outerDistance).toBeLessThanOrEqual(1)
  await expect(bashViewport(page)).toContainText('build step 60')
  await expect(bashViewport(page)).not.toContainText('build step 1')
  await expect(page.locator('.thread-disclosure-hint').first()).toHaveText('… 50 earlier lines')

  // 用户用真实滚轮向历史方向滚动：立即暂停跟随，后续增长不得把手势拉回底部。
  await scrollTranscript(page, -240)
  await expect.poll(outerDistance).toBeGreaterThan(1)
  const pausedTop = await scrollTop(body)
  await harness(page, 'grow', 120)
  expect(await scrollTop(body)).toBe(pausedTop)

  // 用户用真实滚轮主动回到底部：恢复跟随。
  await scrollTranscript(page, 10_000)
  await expect.poll(outerDistance).toBeLessThanOrEqual(1)
  await harness(page, 'grow', 160)
  await expect.poll(outerDistance).toBeLessThanOrEqual(1)
})

test('keeps the outer reading anchor when a tool card is expanded manually while idle', async ({ page }) => {
  // 卡片位于中间、下方仍有内容：如果展开触发了强制贴底，下方内容就会被拉走。
  await harness(page, 'idleMid', 40)
  const body = dialogue(page)
  const card = bashCard(page)

  await toggle(page).scrollIntoViewIfNeeded()
  await body.evaluate((element) => {
    const target = document.querySelector('.thread-turn-tool')
    element.scrollTop = Math.max(0, (target?.getBoundingClientRect().top ?? 0)
      + element.scrollTop - element.getBoundingClientRect().top - 120)
  })
  const anchor = await scrollTop(body)
  const cardBoxBefore = await card.boundingBox()

  // 空闲态手动展开：内容只在卡片内向下增长，外层不得被强制贴底，卡片顶边必须不动。
  await toggle(page).click()
  await flushLayout(page)
  await expect(toggle(page)).toHaveAttribute('aria-expanded', 'true')
  await expect(bashViewport(page)).toBeVisible()
  await expect(bashViewport(page)).not.toHaveClass(/is-bounded/)

  const cardBoxAfter = await card.boundingBox()
  expect(cardBoxAfter?.y).toBeCloseTo(cardBoxBefore?.y ?? 0, 0)
  expect(await scrollTop(body)).toBe(anchor)
  const metrics = await scrollMetrics(body)
  expect(metrics.scrollHeight - metrics.scrollTop - metrics.clientHeight).toBeGreaterThan(10)
})

test('keeps the restored reading position while a tool card image loads', async ({ page }) => {
  await harness(page, 'readImage')
  const body = dialogue(page)
  const readCard = page.locator('.thread-turn-tool').first()
  await expect(readCard).toBeVisible()

  // 与产品一致地恢复到上次阅读位置（不经过滚动事件），权威 MIME 仍在解析（延迟 600ms）。
  await harness(page, 'restorePosition', 120)
  await expect.poll(() => scrollTop(body)).toBe(120)
  const anchor = await scrollTop(body)
  const cardTopBefore = await topEdge(readCard)
  const heightBefore = await readCard.boundingBox()
  const scrollHeightBefore = await body.evaluate((element) => element.scrollHeight)

  // 只读记录默认收起；展开后按权威 MIME 预览，加载完成时阅读位置与卡片顶边都不动。
  await clickWithoutScrolling(readCard.locator('.thread-disclosure-toggle'))
  const image = readCard.locator('img').first()
  await expect(image).toBeVisible()
  await image.evaluate((element: HTMLImageElement) => element.decode())
  await flushLayout(page)

  expect(await readCard.boundingBox().then((box) => box?.height ?? 0))
    .toBeGreaterThan(heightBefore?.height ?? 0)
  expect(await body.evaluate((element) => element.scrollHeight))
    .toBeGreaterThan(scrollHeightBefore)
  expect(await scrollTop(body)).toBe(anchor)
  expect(await topEdge(readCard)).toBeCloseTo(cardTopBefore, 0)
})

test('keeps identity and expanded state across streaming to terminal', async ({ page }) => {
  await harness(page, 'streaming', 60)
  const card = bashCard(page)
  const viewport = bashViewport(page)
  await card.scrollIntoViewIfNeeded()
  await expect(toggle(page)).toHaveAttribute('aria-expanded', 'false')

  // 用户展开卡片后标记 DOM 身份。
  await toggle(page).click()
  await expect(toggle(page)).toHaveAttribute('aria-expanded', 'true')
  await expect(viewport).toContainText('build step 1')
  const nodeIdentity = await card.evaluate((element) => {
    ;(element as HTMLElement).dataset.harnessProbe = 'streaming-node'
    return (element as HTMLElement).dataset.harnessProbe
  })
  expect(nodeIdentity).toBe('streaming-node')

  // 流式转终态：同一条 bash 调用的持久结果到达。
  await harness(page, 'settle', 60)

  // 同一 DOM 节点（身份未重置），展开状态保留，完整日志仍在。
  await expect(page.locator('.thread-turn-tool')).toHaveCount(1)
  expect(await card.getAttribute('data-harness-probe')).toBe('streaming-node')
  await expect(toggle(page)).toHaveAttribute('aria-expanded', 'true')
  await expect(viewport).toContainText('build step 1')
  await expect(viewport).toContainText('build step 60')
  await expect(viewport).not.toHaveClass(/is-bounded/)
})

test('expands the full log from the hint and never creates an inner scroll region', async ({ page }) => {
  await harness(page, 'idleMid', 80)
  const viewport = bashViewport(page)

  // 终态 bash 收起为最后 10 行。
  expect(await viewport.textContent()).toBe(
    Array.from({ length: 10 }, (_, index) => `build step ${index + 71} completed`).join('\n'),
  )
  await expect(page.locator('.thread-disclosure-hint').first()).toHaveText('… 70 earlier lines')

  await page.locator('.thread-disclosure-hint').first().click()
  await expect(page.locator('.thread-disclosure-hint')).toHaveCount(0)
  expect(await viewport.textContent()).toBe(
    Array.from({ length: 80 }, (_, index) => `build step ${index + 1} completed`).join('\n'),
  )
  // 展开视图没有内部滚动：pre 自身不可滚动。
  expect(await viewport.evaluate((element) => element.scrollHeight <= element.clientHeight + 1)).toBe(true)
  await expect(viewport).not.toHaveClass(/is-bounded/)
})

test('keeps read text collapsed, previews read images after expanding, and keeps unknown attachments collapsed', async ({ page }) => {
  await harness(page, 'idle', 20)
  await expect(bashViewport(page)).toBeVisible()
  await expect(page.locator('.thread-disclosure-toggle')).toHaveCount(1)

  await harness(page, 'readUnknown')
  const unknownCard = page.locator('.thread-turn-tool').first()
  await expect(page.locator('.thread-turn-tool')).toHaveCount(1)
  // 未知 MIME 的 read 附件保持收起：没有内联图片，也没有展开后的正文。
  await expect(unknownCard.locator('.thread-disclosure-body')).toHaveCount(0)
  await expect(unknownCard.locator('img')).toHaveCount(0)

  await harness(page, 'readImage')
  const imageCard = page.locator('.thread-turn-tool').first()
  await expect(imageCard.locator('img')).toHaveCount(0)
  await imageCard.locator('.thread-disclosure-toggle').click()
  await expect(imageCard.locator('img').first()).toBeVisible()
})

test('exposes the collapse toggle to keyboard users with an explicit state', async ({ page }) => {
  await harness(page, 'idle', 40)
  const card = bashCard(page)
  await card.scrollIntoViewIfNeeded()

  await expect(toggle(page)).toHaveAttribute('aria-expanded', 'false')
  await toggle(page).focus()
  await page.keyboard.press('Enter')
  await expect(toggle(page)).toHaveAttribute('aria-expanded', 'true')
  await expect(bashViewport(page)).toContainText('build step 1')

  await page.keyboard.press('Space')
  await expect(toggle(page)).toHaveAttribute('aria-expanded', 'false')
  await expect(bashViewport(page)).toBeVisible()
  await expect(bashViewport(page)).not.toContainText('build step 1')
  await expect(bashViewport(page)).toContainText('build step 40')
})

test('keeps the card anchor while streaming growth runs with expansion and media load', async ({ page }) => {
  await harness(page, 'streamingTail', 40)
  const body = dialogue(page)
  const bash = bashCard(page)
  const readCard = page.locator('.thread-turn-tool').nth(1)
  const readToggle = readCard.locator('.thread-disclosure-toggle')
  const outerDistance = () =>
    scrollMetrics(body).then((m) => m.scrollHeight - m.scrollTop - m.clientHeight)

  // 挂载即贴底：外部仍在跟随（此时任何外部增长都会把卡片往上推）。
  await expect.poll(outerDistance, { timeout: 2500 }).toBeLessThanOrEqual(1)

  // 1) 用户回看 transcript：外层暂停，随后并发增长不得移动卡片。
  await scrollTranscript(page, -240)
  await expect.poll(outerDistance).toBeGreaterThan(1)
  const pausedTop = await scrollTop(body)
  const pausedBashTop = await topEdge(bash)
  await harness(page, 'growTail', 90)
  expect(await scrollTop(body)).toBe(pausedTop)
  expect(await topEdge(bash)).toBeCloseTo(pausedBashTop, 0)

  // 2) 用户用真实滚轮回底恢复跟随，然后展开末尾 read 卡片（交互意图）→ 再次暂停。
  await scrollTranscript(page, 10_000)
  await expect.poll(outerDistance).toBeLessThanOrEqual(1)
  await readToggle.click()
  await expect(readToggle).toHaveAttribute('aria-expanded', 'true')
  await flushLayout(page)

  // 展开的静态正文从顶部读，不创建内部滚动。
  expect(await readCard.locator('.thread-tool-output').first().evaluate((el) => el.scrollTop)).toBe(0)
  await expect(readCard.locator('.thread-tool-output').first()).not.toHaveClass(/is-bounded/)

  // 展开让内容在下方长出来：外层已不贴底，出现贴底就说明阅读位置被抢走。
  const expandedTop = await scrollTop(body)
  const expandedBashTop = await topEdge(bash)
  const expandedReadTop = await topEdge(readCard)
  expect(await outerDistance()).toBeGreaterThan(10)

  await harness(page, 'growTail', 140)
  expect(await scrollTop(body)).toBe(expandedTop)
  expect(await topEdge(bash)).toBeCloseTo(expandedBashTop, 0)
  expect(await topEdge(readCard)).toBeCloseTo(expandedReadTop, 0)

  // 3) 流式期间新增 read 图片卡片并完成解码：布局确实增大，但不是 stream signal，
  //    外层与卡片顶边都必须原地不动。
  const mediaCard = page.locator('.thread-turn-tool').nth(2)
  const mediaTop = await scrollTop(body)
  const mediaBashTop = await topEdge(bash)
  const mediaReadTop = await topEdge(readCard)

  await harness(page, 'addImageMid', 160)
  await expect(mediaCard).toHaveCount(1)
  await clickWithoutScrolling(mediaCard.locator('.thread-disclosure-toggle'))
  const collapsedHeight = await body.evaluate((element) => element.scrollHeight)

  const image = mediaCard.locator('img').first()
  await expect(image).toBeVisible()
  await image.evaluate((element: HTMLImageElement) => element.decode())
  await flushLayout(page)

  // 媒体确实加载并改变布局，但外层与卡片顶边都必须原地不动。
  expect(await body.evaluate((element) => element.scrollHeight)).toBeGreaterThan(collapsedHeight)
  expect(await scrollTop(body)).toBe(mediaTop)
  expect(await topEdge(bash)).toBeCloseTo(mediaBashTop, 0)
  expect(await topEdge(readCard)).toBeCloseTo(mediaReadTop, 0)
})

test('does not reopen following when a shrink clamps the outer position to the bottom', async ({ page }) => {
  // 需求：用户暂停后内容收起，浏览器把外层 scrollTop 原生钳制到新的底部（位置变小且
  // 距离为 0）——这不是用户手势，下一轮流式更新仍不得贴底；只有用户真实滚回底部
  // （滚轮意图）才恢复跟随。
  await harness(page, 'streamingTail', 40)
  const body = dialogue(page)
  const outerDistance = () =>
    scrollMetrics(body).then((m) => m.scrollHeight - m.scrollTop - m.clientHeight)
  await expect.poll(outerDistance, { timeout: 2500 }).toBeLessThanOrEqual(1)

  // 1) 用户真实滚轮上滚：外层暂停跟随。
  await scrollTranscript(page, -240)
  await expect.poll(() => outerDistance()).toBeGreaterThan(1)
  const pausedTop = await scrollTop(body)
  await harness(page, 'growTail', 60)
  expect(await scrollTop(body)).toBe(pausedTop)

  // 2) 内容大幅收起：外层 scrollTop 被浏览器钳制到新的底部。
  await harness(page, 'shrinkTail', 60)
  await expect.poll(outerDistance).toBeLessThanOrEqual(1)
  const clampedTop = await scrollTop(body)
  expect(clampedTop).toBeLessThan(pausedTop)

  // 3) 下一轮流式更新：暂停没有被 clamp 重开，位置一动不动。
  await harness(page, 'growTail', 200)
  expect(await scrollTop(body)).toBe(clampedTop)
  expect(await outerDistance()).toBeGreaterThan(1)

  // 4) 用户真实滚轮回到新的底部：恢复跟随，后续增长重新贴底。
  await scrollTranscript(page, 10_000)
  await expect.poll(outerDistance).toBeLessThanOrEqual(1)
  await harness(page, 'growTail', 260)
  await expect.poll(outerDistance).toBeLessThanOrEqual(1)
})

test('shows the bottom collapse action only for an expanded card taller than the viewport and aligns its top', async ({ page }) => {
  await harness(page, 'longExpanded', 120)
  const body = dialogue(page)
  const card = bashCard(page)
  await card.scrollIntoViewIfNeeded()

  await expect(card.locator('.thread-disclosure-collapse')).toHaveCount(0)
  await toggle(page).click()
  await expect(toggle(page)).toHaveAttribute('aria-expanded', 'true')
  const collapse = card.locator('.thread-disclosure-collapse')
  await expect(collapse).toBeVisible()

  await collapse.scrollIntoViewIfNeeded()
  await collapse.click()
  await expect(toggle(page)).toHaveAttribute('aria-expanded', 'false')
  await expect(card.locator('.thread-disclosure-collapse')).toHaveCount(0)

  // 收起后把卡片顶部滚到可视区顶部（按 transcript 的 padding 留少量容差）。
  const dialogueBox = await body.boundingBox()
  const cardBox = await card.boundingBox()
  expect((cardBox?.y ?? 0) - (dialogueBox?.y ?? 0)).toBeLessThan(24)
  expect(await scrollTop(body)).toBeGreaterThan(0)
})

test.describe('narrow pane header', () => {
  test.use({ viewport: { width: 420, height: 640 } })

  test('clamps long parameters and pins the toggle to the first row top-right', async ({ page }) => {
    await harness(page, 'unknownLong', 4000)
    const body = dialogue(page)
    const plainCard = page.locator('.thread-turn-tool').nth(0)
    const bodyCard = page.locator('.thread-turn-tool').nth(1)
    const detail = plainCard.locator('.thread-tool-summary-detail')
    const header = plainCard.locator('.thread-disclosure-header')
    const toggle = plainCard.locator('.thread-disclosure-toggle')

    await plainCard.scrollIntoViewIfNeeded()
    await expect(detail).toBeVisible()

    // 数千字参数完整保留在 DOM 中可选中复制，收起时只做两行视觉截断。
    const text = await detail.textContent()
    expect(text?.length ?? 0).toBeGreaterThan(4_000)
    expect(text).toContain('tail-marker')
    expect(await detail.getAttribute('title')).toBeNull()

    // 收起时 header 被截断，chevron 必须出现；展开后完整参数可见。
    await expect(toggle).toHaveAttribute('aria-expanded', 'false')
    const collapsedHeader = await header.boundingBox()
    expect(collapsedHeader?.height ?? 0).toBeLessThan(60)
    await toggle.click()
    await expect(toggle).toHaveAttribute('aria-expanded', 'true')
    const expandedHeader = await header.boundingBox()
    expect(expandedHeader?.height ?? 0).toBeGreaterThan(collapsedHeader?.height ?? 0)

    // 无横向溢出：详情自身与整个对话流都不产生水平滚动。
    const detailOverflow = await detail.evaluate((element) => ({
      scrollWidth: element.parentElement!.scrollWidth,
      clientWidth: element.parentElement!.clientWidth,
    }))
    expect(detailOverflow.scrollWidth).toBeLessThanOrEqual(detailOverflow.clientWidth + 1)
    const overflow = await body.evaluate((element) => ({
      scrollWidth: element.scrollWidth,
      clientWidth: element.clientWidth,
    }))
    expect(overflow.scrollWidth).toBeLessThanOrEqual(overflow.clientWidth + 1)

    // 选中复制能拿到全部原文。
    const copied = await detail.evaluate((element) => {
      const range = document.createRange()
      range.selectNodeContents(element)
      const selection = window.getSelection()
      selection?.removeAllRanges()
      selection?.addRange(range)
      return selection?.toString() ?? ''
    })
    expect(copied).toBe(text)

    // 有结果正文的 MCP 卡片：preview 可见，箭头固定在首行右上角，不覆盖参数文本。
    await bodyCard.scrollIntoViewIfNeeded()
    const bodyDetail = bodyCard.locator('.thread-tool-summary-detail')
    const bodyToggle = bodyCard.locator('.thread-disclosure-toggle')
    const bodyHeader = bodyCard.locator('.thread-disclosure-header')
    await expect(bodyDetail).toContainText('line one')
    const bodyText = await bodyDetail.textContent()
    expect(bodyText).toContain('\n')
    await expect(bodyCard.locator('.thread-disclosure-preview')).toContainText('no matches')
    await expect(bodyCard.locator('.thread-disclosure-body')).toHaveCount(0)

    const bodyHeaderBox = await bodyHeader.boundingBox()
    const bodyDetailBox = await bodyDetail.boundingBox()
    const toggleBox = await bodyToggle.boundingBox()
    expect(bodyHeaderBox?.height ?? 0).toBeGreaterThan(20)
    // 箭头位于首行（顶部）与参数区右侧的留白里，不覆盖参数文本。
    expect((toggleBox?.y ?? 0) - (bodyHeaderBox?.y ?? 0)).toBeLessThan(24)
    expect(toggleBox?.x ?? 0).toBeGreaterThanOrEqual(
      (bodyDetailBox?.x ?? 0) + (bodyDetailBox?.width ?? 0) - 1,
    )
    const detailScroll = await bodyDetail.evaluate((element) => ({
      scrollWidth: element.parentElement!.scrollWidth,
      clientWidth: element.parentElement!.clientWidth,
    }))
    expect(detailScroll.scrollWidth).toBeLessThanOrEqual(detailScroll.clientWidth + 1)
  })

  test('long paths start after the tool name on the same line and wrap without losing tail icons', async ({ page }) => {
    await page.evaluate(() => window.toolCardHarness.longPath())
    const card = bashCard(page)
    const metrics = await card.evaluate((element) => {
      const name = element.querySelector('.thread-tool-name')!
      const detail = element.querySelector('.thread-tool-summary-detail')!
      const range = document.createRange()
      range.setStart(detail.firstChild!, 0)
      range.setEnd(detail.firstChild!, 1)
      const first = range.getBoundingClientRect()
      range.selectNodeContents(name)
      const nameBox = range.getBoundingClientRect()
      const tail = element.querySelector('.thread-disclosure-toggle')!.getBoundingClientRect()
      const summary = element.querySelector('.thread-tool-summary')!.getBoundingClientRect()
      return { firstY: first.y, nameY: nameBox.y, firstX: first.x, nameEnd: nameBox.right,
        summaryEnd: summary.right, tailX: tail.x,
        scroll: element.scrollWidth, width: element.clientWidth }
    })
    expect(Math.abs(metrics.firstY - metrics.nameY)).toBeLessThan(2)
    expect(metrics.firstX).toBeGreaterThan(metrics.nameEnd)
    expect(metrics.tailX).toBeGreaterThanOrEqual(metrics.summaryEnd)
    expect(metrics.scroll).toBeLessThanOrEqual(metrics.width + 1)
    await expect(card.locator('.thread-disclosure-header svg')).toHaveCount(1)
    await expect(card.locator('.thread-disclosure-toggle')).toBeVisible()
    await expect(card.locator('.thread-tool-summary-detail')).toContainText('end.txt')
  })
})

test('all durable states retain accessible labels without visible status icons or text', async ({ page }) => {
  await page.evaluate(() => window.toolCardHarness.durableStates())
  const states = ['queued', 'approval', 'input', 'running', 'succeeded', 'failed', 'cancelled', 'unknown']
  for (const state of states) {
    await expect(page.locator(`[data-invocation-state="${state}"]`)).toHaveCount(1)
    await expect(page.locator(`[data-invocation-state="${state}"]`)).toHaveAttribute('aria-label', /bash: .+/)
  }
  await expect(page.locator('.thread-tool-state-label, .thread-disclosure-toggle')).toHaveCount(0)
  await expect(page.locator('[data-invocation-state="running"]')).toHaveAttribute('aria-busy', 'true')
})
