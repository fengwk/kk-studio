import { expect, test } from './fixture'
import type { Locator, Page } from './fixture'

/**
 * Slice C 真实浏览器回归：外层阅读锚点与工具卡片交互。
 *
 * 覆盖设计文档中只能在真实浏览器验证的契约——流式增长跟随、空闲高度变化（手动展开、
 * 图片加载）不抢外层锚点、内部日志回看不移动卡片、流式转终态保持身份与滚动位置、
 * read 图片按权威 MIME 默认预览；恢复跟随必须来自真实用户滚动意图（滚轮等），
 * 布局收缩把 scrollTop 钳制到底部不构成用户回底。
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
  addImageMid(lines: number): void
  unknownLong(chars?: number): void
  restorePosition(offset: number): void
}

function harness(page: Page, method: keyof HarnessApi, arg?: number): Promise<void> {
  return page.evaluate(
    ([name, value]) => {
      const api = window.toolCardHarness as unknown as Record<string, (size?: number) => void>
      api[name as string](value as number)
    },
    [method, arg] as const,
  )
}

const dialogue = (page: Page): Locator => page.locator('.thread-dialogue')
const bashCard = (page: Page): Locator => page.locator('.thread-turn-tool').first()
const bashViewport = (page: Page): Locator =>
  page.locator('.thread-turn-tool').first().locator('.thread-tool-output').first()
const toggle = (page: Page): Locator => page.locator('.thread-tool-toggle').first()

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

test.beforeEach(async ({ page }) => {
  await page.goto('/browser-tests/tool-card-harness.html')
  await expect(page.locator('.harness-hint')).toHaveText('tool card browser harness')
})

for (const width of [1440, 360, 320]) {
  test(`task readable links and balanced header geometry at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 820 })
    await harness(page, 'task')
    const card = page.locator('.thread-turn-tool')
    await expect(card.locator('.thread-tool-summary')).toHaveText(
      'task Explorer [max_turns=4 thread_id=00000000-0000-4000-8000-000000000004]',
    )
    await expect(card.locator('.thread-tool-state-label, .thread-tool-tail > svg, .task-tool-fields')).toHaveCount(0)
    await expect(card).toContainText('Keep this user text: Thread ID user-owned-marker')
    const links = card.getByRole('link', { name: 'View subagent execution' })
    await expect(links).toHaveCount(2)
    await expect(links.nth(1)).toHaveAttribute('href', '/threads/00000000-0000-4000-8000-000000000005')
    const typography = await links.nth(1).evaluate((element) => {
      const style = getComputedStyle(element)
      const body = getComputedStyle(document.querySelector('.task-tool-renderer .thread-tool-pre')!)
      return {
        link: [style.fontFamily, style.fontSize, style.fontWeight, style.color],
        body: [body.fontFamily, body.fontSize, body.fontWeight, body.color],
      }
    })
    expect(typography.link).toEqual(typography.body)
    expect(typography.link[0].toLowerCase()).not.toContain('monospace')
    await links.nth(1).click()
    await expect(page.getByLabel('selected thread')).toHaveText('00000000-0000-4000-8000-000000000005')
    await expect(card.locator('.thread-tool-toggle')).toHaveAttribute('aria-expanded', 'true')
    await page.screenshot({ path: testInfo.outputPath(`task-card-expanded-${width}.png`) })
    await card.locator('.thread-tool-toggle').click()
    const geometry = await card.evaluate((element) => {
      const surface = element.querySelector('.thread-tool-surface')!.getBoundingClientRect()
      const header = element.querySelector('.thread-tool-header')!.getBoundingClientRect()
      const summary = element.querySelector('.thread-tool-name')!.getBoundingClientRect()
      const toggle = element.querySelector('.thread-tool-toggle')!.getBoundingClientRect()
      return {
        top: header.top - surface.top, bottom: surface.bottom - header.bottom,
        center: Math.abs(summary.y + summary.height / 2 - toggle.y - toggle.height / 2),
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

test('follows streaming growth, pauses on user scroll-up, and resumes at the bottom', async ({ page }) => {
  await harness(page, 'streaming', 20)
  const body = dialogue(page)
  const inner = bashViewport(page)
  const outerDistance = () =>
    scrollMetrics(body).then((m) => m.scrollHeight - m.scrollTop - m.clientHeight)
  const innerDistance = () =>
    scrollMetrics(inner).then((m) => m.scrollHeight - m.scrollTop - m.clientHeight)

  // 挂载即贴底；持续日志同时跟随内部尾部。
  await expect.poll(outerDistance).toBeLessThanOrEqual(1)
  await expect.poll(innerDistance).toBeLessThanOrEqual(1)

  // 流式增长：外层继续贴底，内部尾部继续跟随。
  await harness(page, 'grow', 60)
  await expect.poll(outerDistance).toBeLessThanOrEqual(1)
  await expect.poll(innerDistance).toBeLessThanOrEqual(1)
  await expect(bashViewport(page)).toContainText('build step 60')
  await expect(bashViewport(page)).toContainText('build step 1')

  // 用户用真实滚轮向历史方向滚动：立即暂停跟随，后续增长不得把手势拉回底部。
  await scrollTranscript(page, -240)
  await expect.poll(outerDistance).toBeGreaterThan(1)
  const pausedTop = await scrollTop(body)
  await harness(page, 'grow', 120)
  await page.waitForTimeout(200)
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

  // 先收起卡片作为准备（收起会让下方内容上移，外层位置变化属于正常布局）。
  await toggle(page).scrollIntoViewIfNeeded()
  await toggle(page).click()
  await page.waitForTimeout(150)
  await expect(toggle(page)).toHaveAttribute('aria-expanded', 'false')

  // 让卡片位于可视区中部再测量：点击本身不再引发滚动。
  await body.evaluate((element) => {
    const target = document.querySelector('.thread-turn-tool')
    element.scrollTop = Math.max(0, (target?.getBoundingClientRect().top ?? 0)
      + element.scrollTop - element.getBoundingClientRect().top - 120)
  })
  const anchor = await scrollTop(body)
  const cardBoxBefore = await card.boundingBox()

  // 空闲态手动展开：内容只在卡片内向下增长，外层不得被强制贴底，卡片顶边必须不动。
  await toggle(page).click()
  await page.waitForTimeout(200)
  await expect(toggle(page)).toHaveAttribute('aria-expanded', 'true')
  await expect(bashViewport(page)).toBeVisible()

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

  // 图片按权威 MIME 默认预览；加载完成后卡片确实长高，阅读位置与卡片顶边都不动。
  const image = readCard.locator('img').first()
  await expect(image).toBeVisible()
  await image.evaluate((element) => element.decode().catch(() => undefined))
  await page.waitForTimeout(300)

  expect(await readCard.boundingBox().then((box) => box?.height ?? 0))
    .toBeGreaterThan(heightBefore?.height ?? 0)
  expect(await body.evaluate((element) => element.scrollHeight))
    .toBeGreaterThan(scrollHeightBefore)
  expect(await scrollTop(body)).toBe(anchor)
  expect(await topEdge(readCard)).toBeCloseTo(cardTopBefore, 0)
})

test('keeps identity, expanded state and inner scroll position across streaming to terminal', async ({ page }) => {
  await harness(page, 'streaming', 60)
  const card = bashCard(page)
  const viewport = bashViewport(page)
  await card.scrollIntoViewIfNeeded()

  // 用户在流式期间回看内部日志。
  await viewport.evaluate((element) => {
    element.scrollTop = 40
  })
  const innerBefore = await scrollTop(viewport)
  const nodeIdentity = await card.evaluate((element) => {
    ;(element as HTMLElement).dataset.harnessProbe = 'streaming-node'
    return (element as HTMLElement).dataset.harnessProbe
  })
  expect(nodeIdentity).toBe('streaming-node')

  // 流式转终态：同一条 bash 调用的持久结果到达。
  await harness(page, 'settle', 60)
  await page.waitForTimeout(150)

  // 同一 DOM 节点（身份未重置），展开状态保留，内部滚动位置不回零。
  await expect(page.locator('.thread-turn-tool')).toHaveCount(1)
  expect(await card.getAttribute('data-harness-probe')).toBe('streaming-node')
  await expect(toggle(page)).toHaveAttribute('aria-expanded', 'true')
  expect(await scrollTop(viewport)).toBe(innerBefore)
  await expect(viewport).toContainText('build step 1')
  await expect(viewport).toContainText('build step 60')
})

test('scrolls the inner log and chains naturally to the transcript at its boundary', async ({ page }) => {
  await harness(page, 'idleMid', 80)
  const body = dialogue(page)
  const viewport = bashViewport(page)

  // 终态 bash 结果是静态正文：挂载即从顶部读，不自动贴到底部。
  expect(await scrollTop(viewport)).toBe(0)

  // 输出视口使用等宽字体（--mono）。
  const fontFamily = await viewport.evaluate((element) => getComputedStyle(element).fontFamily)
  expect(fontFamily.toLowerCase()).toContain('monospace')

  // 先把内部视口滚到中间，再让它在视口内就位；之后所有测量都不再触发自动定位。
  await viewport.scrollIntoViewIfNeeded()
  await viewport.evaluate((element) => {
    element.scrollTop = Math.floor(element.scrollHeight / 2)
  })
  const innerBefore = await scrollTop(viewport)
  expect(innerBefore).toBeGreaterThan(0)

  const box = await viewport.boundingBox()
  expect(box).not.toBeNull()
  const centerX = (box?.x ?? 0) + (box?.width ?? 0) / 2
  const centerY = (box?.y ?? 0) + (box?.height ?? 0) / 2

  // 1) 内部仍有可滚空间：滚轮只滚内层，外层 transcript 不动。
  const outerAnchor = await scrollTop(body)
  await page.mouse.move(centerX, centerY)
  await page.mouse.wheel(0, -120)
  await page.waitForTimeout(150)
  expect(await scrollTop(viewport)).toBeLessThan(innerBefore)
  expect(await scrollTop(body)).toBe(outerAnchor)

  // 2) 外层先移动到有上滚空间的位置，再把内层置顶。
  await body.evaluate((element) => {
    element.scrollTop = 140
  })
  const outerBefore = await scrollTop(body)
  expect(outerBefore).toBeGreaterThan(0)
  await viewport.evaluate((element) => {
    element.scrollTop = 0
  })
  // 外层移动后重新定位指针，确保滚轮仍落在内层视口上。
  const movedBox = await viewport.boundingBox()
  expect(movedBox?.y ?? -1).toBeGreaterThanOrEqual(0)
  await page.mouse.move(
    (movedBox?.x ?? 0) + (movedBox?.width ?? 0) / 2,
    (movedBox?.y ?? 0) + (movedBox?.height ?? 0) / 2,
  )
  await page.mouse.wheel(0, -400)
  await page.waitForTimeout(200)

  // 内层已在边界无法再消费：overscroll-behavior auto 让滚轮自然链到外层。
  expect(await scrollTop(viewport)).toBe(0)
  expect(await scrollTop(body)).toBeLessThan(outerBefore)
})

test('keeps read text collapsed, previews read images, and keeps unknown attachments collapsed', async ({ page }) => {
  await harness(page, 'idle', 20)
  // bash 正文默认可见；read 文本默认收起（此处用 idle 场景的 bash 卡片代表默认可见）。
  await expect(bashViewport(page)).toBeVisible()

  await harness(page, 'readUnknown')
  await page.waitForTimeout(900)
  const unknownCard = page.locator('.thread-turn-tool').first()
  await expect(page.locator('.thread-turn-tool')).toHaveCount(1)
  // 未知 MIME 的 read 附件保持收起：没有内联图片，也没有正文。
  await expect(unknownCard.locator('.thread-tool-body')).toHaveCount(0)
  await expect(unknownCard.locator('img')).toHaveCount(0)

  await harness(page, 'readImage')
  await expect(page.locator('.thread-turn-tool').first().locator('img').first()).toBeVisible()
})

test('exposes the collapse toggle to keyboard users with an explicit state', async ({ page }) => {
  await harness(page, 'idle', 40)
  const card = bashCard(page)
  await card.scrollIntoViewIfNeeded()

  await expect(toggle(page)).toHaveAttribute('aria-expanded', 'true')
  await toggle(page).focus()
  await page.keyboard.press('Enter')
  await expect(toggle(page)).toHaveAttribute('aria-expanded', 'false')
  await expect(bashViewport(page)).toHaveCount(0)

  await page.keyboard.press('Space')
  await expect(toggle(page)).toHaveAttribute('aria-expanded', 'true')
  await expect(bashViewport(page)).toBeVisible()
})

test('keeps the card anchor while streaming growth runs with expansion, media load and inner read-back', async ({ page }) => {
  // 需求：流式期间用户展开卡片/媒体加载/回看内部日志时，外部并发增长绝不能把
  // 正在看的卡片拉走——保护的是卡片顶边与外层 scrollTop，而不仅仅是内层不再滚动。
  await harness(page, 'streamingTail', 40)
  const body = dialogue(page)
  const bash = bashCard(page)
  const viewport = bashViewport(page)
  const readCard = page.locator('.thread-turn-tool').nth(1)
  const readToggle = readCard.locator('.thread-tool-toggle')
  const outerDistance = () =>
    scrollMetrics(body).then((m) => m.scrollHeight - m.scrollTop - m.clientHeight)

  // 挂载即贴底：外部仍在跟随（此时任何外部增长都会把卡片往上推）。
  await expect.poll(outerDistance).toBeLessThanOrEqual(1, { timeout: 2500 })

  // 1) 用户回看内部日志：内层上滚冒泡阅读意图 → 外层暂停，随后并发增长不得移动卡片。
  await viewport.evaluate((element) => {
    element.scrollTop = 30
  })
  const pausedTop = await scrollTop(body)
  const pausedBashTop = await topEdge(bash)
  await harness(page, 'growTail', 90)
  await page.waitForTimeout(300)
  expect(await scrollTop(body)).toBe(pausedTop)
  expect(await topEdge(bash)).toBeCloseTo(pausedBashTop, 0)

  // 2) 用户用真实滚轮回底恢复跟随，然后展开末尾卡片（交互意图）→ 再次暂停。
  await scrollTranscript(page, 10_000)
  await expect.poll(outerDistance).toBeLessThanOrEqual(1)
  await readToggle.click()
  await expect(readToggle).toHaveAttribute('aria-expanded', 'true')
  await page.waitForTimeout(150)

  // 展开的静态正文（read 文本结果）从顶部读，绝不自动贴到底部。
  expect(await scrollTop(readCard.locator('.thread-tool-output').first())).toBe(0)

  // 展开让内容在下方长出来：外层已不贴底，出现贴底就说明阅读位置被抢走。
  const expandedTop = await scrollTop(body)
  const expandedBashTop = await topEdge(bash)
  const expandedReadTop = await topEdge(readCard)
  expect(await outerDistance()).toBeGreaterThan(10)

  await harness(page, 'growTail', 140)
  await page.waitForTimeout(300)
  expect(await scrollTop(body)).toBe(expandedTop)
  expect(await topEdge(bash)).toBeCloseTo(expandedBashTop, 0)
  expect(await topEdge(readCard)).toBeCloseTo(expandedReadTop, 0)

  // 3) 流式期间新增图片卡片并完成解码：布局确实增大，但不是 stream signal，
  //    外层与卡片顶边都必须原地不动。
  const mediaCard = page.locator('.thread-turn-tool').nth(2)
  const mediaTop = await scrollTop(body)
  const mediaBashTop = await topEdge(bash)
  const mediaReadTop = await topEdge(readCard)

  // 新卡片先以收起形态加入（正文高度 0），权威 MIME 解析完成后才默认预览。
  await harness(page, 'addImageMid', 160)
  await expect(mediaCard).toHaveCount(1)
  const collapsedHeight = await body.evaluate((element) => element.scrollHeight)

  const image = mediaCard.locator('img').first()
  await expect(image).toBeVisible()
  await image.evaluate((element) => element.decode().catch(() => undefined))
  await page.waitForTimeout(300)

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
  const viewport = bashViewport(page)
  const outerDistance = () =>
    scrollMetrics(body).then((m) => m.scrollHeight - m.scrollTop - m.clientHeight)
  await expect.poll(outerDistance).toBeLessThanOrEqual(1, { timeout: 2500 })

  // 1) 内层回看（冒泡阅读意图）＋ 用户真实滚轮上滚：外层暂停跟随。
  await viewport.evaluate((element) => {
    element.scrollTop = 20
  })
  await page.waitForTimeout(120)
  await scrollTranscript(page, -240)
  await expect.poll(() => outerDistance()).toBeGreaterThan(1)
  const pausedTop = await scrollTop(body)
  await harness(page, 'growTail', 60)
  await page.waitForTimeout(250)
  expect(await scrollTop(body)).toBe(pausedTop)

  // 2) 内容大幅收起：外层 scrollTop 被浏览器钳制到新的底部。
  await harness(page, 'shrinkTail', 60)
  await expect.poll(outerDistance).toBeLessThanOrEqual(1)
  const clampedTop = await scrollTop(body)
  expect(clampedTop).toBeLessThan(pausedTop)

  // 3) 下一轮流式更新：暂停没有被 clamp 重开，位置一动不动。
  await harness(page, 'growTail', 200)
  await page.waitForTimeout(300)
  expect(await scrollTop(body)).toBe(clampedTop)
  expect(await outerDistance(body)).toBeGreaterThan(1)

  // 4) 用户真实滚轮回到新的底部：恢复跟随，后续增长重新贴底。
  await scrollTranscript(page, 10_000)
  await expect.poll(outerDistance).toBeLessThanOrEqual(1)
  await harness(page, 'growTail', 260)
  await expect.poll(outerDistance).toBeLessThanOrEqual(1)
})

test.describe('narrow pane header', () => {
  test.use({ viewport: { width: 420, height: 640 } })

  test('wraps unknown/MCP parameters with the toggle pinned to the first row', async ({ page }) => {
    await harness(page, 'unknownLong', 4000)
    const body = dialogue(page)
    const plainCard = page.locator('.thread-turn-tool').nth(0)
    const bodyCard = page.locator('.thread-turn-tool').nth(1)
    const detail = plainCard.locator('.thread-tool-summary-detail')
    const header = plainCard.locator('.thread-tool-header')
    const toggle = plainCard.locator('.thread-tool-toggle')

    await plainCard.scrollIntoViewIfNeeded()
    await expect(detail).toBeVisible()

    // 数千字参数在可用宽度内折行（不再单行横向滚动），完整原文仍在 DOM 中可选中复制。
    const text = await detail.textContent()
    expect(text?.length ?? 0).toBeGreaterThan(4_000)
    expect(text).toContain('tail-marker')
    expect(await detail.getAttribute('title')).toBeNull()

    const headerBox = await header.boundingBox()
    const detailBox = await detail.boundingBox()
    // 折行：header 与详情都远超一行高度。
    expect(headerBox?.height ?? 0).toBeGreaterThan(40)
    expect(detailBox?.height ?? 0).toBeGreaterThan(40)

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

    // 无正文的未知卡片没有可展开箭头。
    await expect(toggle).toHaveCount(0)

    // 有正文的未知卡片：箭头固定在首行右上角，不覆盖参数文本，也没有横向滚动可推动它。
    await bodyCard.scrollIntoViewIfNeeded()
    const bodyDetail = bodyCard.locator('.thread-tool-summary-detail')
    const bodyToggle = bodyCard.locator('.thread-tool-toggle')
    const bodyHeader = bodyCard.locator('.thread-tool-header')
    await expect(bodyDetail).toContainText('line one')
    const bodyText = await bodyDetail.textContent()
    expect(bodyText).toContain('\n')
    await expect(bodyCard.locator('.thread-tool-body')).toHaveCount(0)

    const bodyHeaderBox = await bodyHeader.boundingBox()
    const bodyDetailBox = await bodyDetail.boundingBox()
    const toggleBox = await bodyToggle.boundingBox()
    expect(bodyHeaderBox?.height ?? 0).toBeGreaterThan(40)
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
      const tail = element.querySelector('.thread-tool-tail')!.getBoundingClientRect()
      const summary = element.querySelector('.thread-tool-summary')!.getBoundingClientRect()
      return { firstY: first.y, nameY: nameBox.y, firstX: first.x, nameEnd: nameBox.right,
        summaryEnd: summary.right, tailX: tail.x,
        scroll: element.scrollWidth, width: element.clientWidth }
    })
    expect(Math.abs(metrics.firstY - metrics.nameY)).toBeLessThan(2)
    expect(metrics.firstX).toBeGreaterThan(metrics.nameEnd)
    expect(metrics.tailX).toBeGreaterThanOrEqual(metrics.summaryEnd)
    expect(metrics.scroll).toBeLessThanOrEqual(metrics.width + 1)
    await expect(card.locator('.thread-tool-tail svg')).toHaveCount(1)
    await expect(card.locator('.thread-tool-toggle')).toBeVisible()
    await expect(card.locator('.thread-tool-summary-detail')).toContainText('end.txt')
  })
})

test('all durable states retain accessible labels without visible status icons or text', async ({ page }) => {
  await page.evaluate(() => window.toolCardHarness.durableStates())
  const states = ['queued', 'approval', 'input', 'dispatching', 'running', 'succeeded', 'failed', 'cancelled', 'unknown']
  for (const state of states) {
    await expect(page.locator(`[data-invocation-state="${state}"]`)).toHaveCount(1)
    await expect(page.locator(`[data-invocation-state="${state}"]`)).toHaveAttribute('aria-label', /bash: .+/)
  }
  await expect(page.locator('.thread-tool-tail > svg, .thread-tool-state-label')).toHaveCount(0)
  await expect(page.locator('[data-invocation-state="running"]')).toHaveAttribute('aria-busy', 'true')
})

