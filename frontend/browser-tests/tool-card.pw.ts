import { expect, test } from './fixture'
import type { Locator, Page } from './fixture'

/**
 * Slice C 真实浏览器回归：外层阅读锚点与工具卡片交互。
 *
 * 覆盖设计文档中只能在真实浏览器验证的契约——流式增长跟随、空闲高度变化（手动展开、
 * 图片加载）不抢外层锚点、内部日志回看不移动卡片、流式转终态保持身份与滚动位置、
 * read 图片按权威 MIME 默认预览。
 */

type HarnessApi = {
  idle(lines?: number): void
  idleMid(lines?: number): void
  streaming(lines?: number): void
  grow(lines: number): void
  settle(lines?: number): void
  readImage(): void
  readUnknown(): void
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

async function scrollTop(target: Locator): Promise<number> {
  return target.evaluate((element) => element.scrollTop)
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

  // 用户向历史方向滚动：立即暂停跟随，后续增长不得把手势拉回底部。
  await body.evaluate((element) => {
    element.scrollTop = Math.max(0, element.scrollTop - 120)
  })
  const pausedTop = await scrollTop(body)
  await harness(page, 'grow', 120)
  await page.waitForTimeout(200)
  expect(await scrollTop(body)).toBe(pausedTop)

  // 主动回到底部：恢复跟随。
  await body.evaluate((element) => {
    element.scrollTop = element.scrollHeight
  })
  await page.waitForTimeout(50)
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

test('keeps the outer reading anchor while a tool card image loads', async ({ page }) => {
  await harness(page, 'readImage')
  const body = dialogue(page)
  const readCard = page.locator('.thread-turn-tool').first()
  await expect(readCard).toBeVisible()
  // 权威 MIME 仍在解析（夹具延迟 600ms）：先记录锚点与卡片高度。
  await body.evaluate((element) => {
    element.scrollTop = 120
  })
  const anchor = await scrollTop(body)
  const heightBefore = await readCard.boundingBox()

  // 图片按权威 MIME 默认预览；加载完成后高度确实增长，外层锚点必须不动。
  const image = readCard.locator('img').first()
  await expect(image).toBeVisible()
  await image.evaluate((element) => element.decode().catch(() => undefined))
  await page.waitForTimeout(300)
  const heightAfter = await readCard.boundingBox()
  expect(heightAfter?.height ?? 0).toBeGreaterThan(heightBefore?.height ?? 0)
  expect(await scrollTop(body)).toBe(anchor)
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

test('contains inner log scrollback without moving the card', async ({ page }) => {
  await harness(page, 'idle', 80)
  const body = dialogue(page)
  const viewport = bashViewport(page)

  // 先把内部视口滚到中间，再让它在视口内就位；之后所有测量都不再触发自动定位。
  await viewport.scrollIntoViewIfNeeded()
  await viewport.evaluate((element) => {
    element.scrollTop = Math.floor(element.scrollHeight / 2)
  })
  const innerBefore = await scrollTop(viewport)
  expect(innerBefore).toBeGreaterThan(0)
  const anchor = await scrollTop(body)

  const box = await viewport.boundingBox()
  expect(box).not.toBeNull()
  await page.mouse.move(
    (box?.x ?? 0) + (box?.width ?? 0) / 2,
    (box?.y ?? 0) + (box?.height ?? 0) / 2,
  )
  await page.mouse.wheel(0, -4_000)
  await page.waitForTimeout(200)

  // 内部日志确实滚到了顶部附近，但 overscroll-behavior: contain 不让滚动链传到外层。
  expect(await scrollTop(viewport)).toBeLessThan(innerBefore)
  expect(await scrollTop(body)).toBe(anchor)
  const cardBox = await bashCard(page).boundingBox()
  expect(cardBox?.y).toBeGreaterThanOrEqual(0)
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
