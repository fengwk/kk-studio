import { expect, test } from './fixture'
import type { Locator, Page } from './fixture'

/**
 * 工具卡片真实浏览器回归：统一 ThreadDisclosure、稳定尾部短窗口、外层阅读锚点与长卡片收起。
 *
 * 覆盖只能在真实浏览器验证的契约——收起/展开的几何、短窗口不随展开变化、完整日志在卡片内
 * 独立有界滚动、流式尾部窗口、手动展开不抢外层锚点、媒体加载不移动卡片、
 * 流式转终态保持身份、长卡片由 Header chevron 收起。
 */

type HarnessApi = {
  task(): void
  taskRunning(): void
  idle(lines?: number): void
  idleMid(lines?: number): void
  streaming(lines?: number): void
  grow(lines: number): void
  handoff(stage?: number): void
  prependHistory(): void
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
/** 稳定的尾部短窗口：收起与展开都只显示最后 10 个逻辑行，自身不纵向滚动。 */
const bashPreview = (page: Page): Locator =>
  bashCard(page).locator('.thread-disclosure-preview .thread-tool-output').first()
/** 静态省略提示：只是说明文字，不是第二个展开入口。 */
const bashHint = (page: Page): Locator =>
  bashCard(page).locator('.thread-disclosure-preview .thread-disclosure-hint').first()
/** 完整日志只在展开后的详情区出现；它是卡片内唯一的有界滚动视口。 */
const bashDetails = (page: Page): Locator =>
  bashCard(page).locator('.thread-disclosure-body .thread-tool-details').first()
const toggle = (page: Page): Locator => page.locator('.thread-disclosure-toggle').first()

/** 详情区自己声明有界滚动，并且真的在卡片内滚动，而不是撑开外层 transcript。 */
async function expectBoundedDetails(target: Locator): Promise<void> {
  const metrics = await target.evaluate((element) => {
    const style = getComputedStyle(element)
    return {
      overflowY: style.overflowY,
      maxHeight: style.maxHeight,
      scrollHeight: element.scrollHeight,
      clientHeight: element.clientHeight,
    }
  })
  expect(metrics.overflowY).toBe('auto')
  expect(metrics.maxHeight).not.toBe('none')
  expect(metrics.scrollHeight).toBeGreaterThan(metrics.clientHeight)
}

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

test('running task header opens its durable child without a result receipt', async ({ page }) => {
  await harness(page, 'taskRunning')
  const card = page.locator('.thread-turn-tool')
  const action = card.getByRole('button', { name: '查看子 Agent 执行' })
  await expect(action).toHaveAttribute('type', 'button')
  expect(await action.getAttribute('href')).toBeNull()
  await expect(card.locator('.thread-disclosure-body')).toHaveCount(0)
  await action.click()
  await expect(page.getByLabel('selected thread')).toHaveText('00000000-0000-4000-8000-000000000006')
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
    const actions = card.getByRole('button', { name: '查看子 Agent 执行' })
    await expect(actions).toHaveCount(1)
    await expect(actions).toHaveAttribute('type', 'button')
    expect(await actions.getAttribute('href')).toBeNull()
    await actions.click()
    await expect(page.getByLabel('selected thread')).toHaveText('00000000-0000-4000-8000-000000000005')

    // 展开后完整参数与 prompt 可见，链接仍在 Header 操作区。
    await card.locator('.thread-disclosure-toggle').click()
    await expect(card.locator('.thread-disclosure-toggle')).toHaveAttribute('aria-expanded', 'true')
    await expect(card.locator('.thread-tool-summary')).toContainText('thread_id=00000000-0000-4000-8000-000000000004')
    await expect(card).toContainText('Keep this user text: Thread ID user-owned-marker')
    const typography = await actions.evaluate((element) => {
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

  // 挂载即贴底；bash 收起时只显示最后 10 个逻辑行，省略行数写在短窗口上方。
  await expect.poll(outerDistance).toBeLessThanOrEqual(1)
  await expect(bashPreview(page)).toContainText('build step 20')
  expect((await bashPreview(page).textContent())!.split('\n')).not.toContain('build step 1')
  await expect(bashHint(page)).toHaveText('… 上方还有 10 行')

  // 流式增长：外层继续贴底，尾部窗口继续跟随。
  await harness(page, 'grow', 60)
  await expect.poll(outerDistance).toBeLessThanOrEqual(1)
  await expect(bashPreview(page)).toContainText('build step 60')
  await expect(bashPreview(page)).not.toContainText('build step 1')
  await expect(bashHint(page)).toHaveText('… 上方还有 50 行')

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

  await scrollTranscript(page, -100)
  await expect(body).toHaveAttribute('data-following', 'false')
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
  await expect(bashPreview(page)).toBeVisible()
  await expect(bashDetails(page)).toBeVisible()
  await expect(bashPreview(page)).not.toHaveClass(/is-bounded/)

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

test('keeps one card through draft, invocation and empty durable result', async ({ page }) => {
  await harness(page, 'handoff', 0)
  const card = bashCard(page)
  await card.evaluate((element: HTMLElement) => { element.dataset.handoffProbe = 'draft' })
  for (const stage of [1, 2, 3, 4, 5, 6]) {
    await harness(page, 'handoff', stage)
    await expect(page.locator('.thread-turn-tool')).toHaveCount(1)
    await expect(card).toHaveAttribute('data-handoff-probe', 'draft')
    if (stage === 3) {
      await expect(card).toContainText('build step 40')
      await toggle(page).click()
      await expect(toggle(page)).toHaveAttribute('aria-expanded', 'true')
      await expect(toggle(page)).toBeFocused()
    } else if (stage === 4) {
      await expect(card).toContainText('transient final')
      await expect(card).not.toContainText('build step 40')
      await expect(toggle(page)).toHaveAttribute('aria-expanded', 'true')
      await expect(toggle(page)).toBeFocused()
    } else if (stage >= 5) {
      await expect(card).toContainText('bash')
      await expect(card.locator('.thread-tool-output')).toHaveCount(0)
    }
  }
})

test('follows geometry-only growth and preserves the visible anchor when prepending history', async ({ page }) => {
  await harness(page, 'streaming', 40)
  const body = dialogue(page)
  const gap = () => scrollMetrics(body).then((m) => m.scrollHeight - m.scrollTop - m.clientHeight)
  const growBox = () => body.locator('.thread-blocks').evaluate((element: HTMLElement) => {
    element.style.minHeight = `${element.offsetHeight + 560}px`
  })
  await expect.poll(gap).toBeLessThanOrEqual(1)
  // No React update, new message or stream revision: just the observed box growing.
  const height = (await scrollMetrics(body)).scrollHeight
  await growBox()
  await expect.poll(async () => (await scrollMetrics(body)).scrollHeight - height).toBeGreaterThanOrEqual(560)
  await expect.poll(gap).toBeLessThanOrEqual(1)

  await scrollTranscript(page, -700)
  await expect(body).toHaveAttribute('data-following', 'false')
  await flushLayout(page)
  const before = await scrollTop(body)
  await growBox()
  await flushLayout(page)
  expect(await scrollTop(body)).toBe(before)
  // Remove the synthetic extra height, then prepend actual messages above a visible node.
  await body.locator('.thread-blocks').evaluate((element: HTMLElement) => { element.style.minHeight = '' })
  const anchor = page.getByText('filler reply 4', { exact: false })
  await body.evaluate((element, y) => { element.scrollTop += y - element.getBoundingClientRect().top - 40 }, await topEdge(anchor))
  await flushLayout(page)
  const y = await topEdge(anchor)
  await harness(page, 'prependHistory')
  await expect.poll(async () => Math.abs(await topEdge(anchor) - y)).toBeLessThanOrEqual(1)

  await scrollTranscript(page, 10_000)
  await expect(body).toHaveAttribute('data-following', 'true')
  await growBox()
  await expect.poll(gap).toBeLessThanOrEqual(1)
})

test('preserves the short preview and expanded details across stream settlement', async ({ page }) => {
  await harness(page, 'handoff', 3)
  const card = bashCard(page)
  const preview = bashPreview(page)
  const details = bashDetails(page)
  await expect(toggle(page)).toHaveAttribute('aria-expanded', 'false')
  await toggle(page).click()
  await expect(preview).toContainText('build step 40')
  await expect(preview).not.toContainText('build step 1')
  await expect(details).toContainText('build step 1')
  await card.evaluate((element: HTMLElement) => { element.dataset.handoffProbe = 'streaming' })

  await harness(page, 'handoff', 7)
  await expect(card).toHaveAttribute('data-handoff-probe', 'streaming')
  await expect(toggle(page)).toHaveAttribute('aria-expanded', 'true')
  await expect(preview).toContainText('build step 40')
  await expect(preview).not.toContainText('build step 1')
  await expect(details).toContainText('build step 1')
  await expect(details).toContainText('build step 40')
  await expect(preview).not.toHaveClass(/is-bounded/)
})

test('keeps the collapsed bash preview stable and opens the full log in a bounded details region', async ({ page }) => {
  await harness(page, 'idleMid', 80)
  const preview = bashPreview(page)
  const hint = bashHint(page)
  const details = bashDetails(page)
  const tail = Array.from({ length: 10 }, (_, index) => `build step ${index + 71} completed`).join('\n')

  // 终态 bash 收起为最后 10 行；提示只是静态说明，唯一展开入口是 Header chevron。
  expect(await preview.textContent()).toBe(tail)
  await expect(hint).toHaveText('… 上方还有 70 行')
  await expect(bashCard(page).locator('button.thread-disclosure-hint')).toHaveCount(0)
  await expect(details).toHaveCount(0)
  // 短窗口是普通正文：不创建内部纵向滚动。
  expect(await preview.evaluate((element) => element.scrollHeight <= element.clientHeight + 1)).toBe(true)
  await expect(preview).not.toHaveClass(/is-bounded/)

  await toggle(page).click()
  await expect(toggle(page)).toHaveAttribute('aria-expanded', 'true')
  await expect(details).toBeVisible()
  await expect(details).toHaveAttribute('aria-label', '完整详情')

  // 展开只追加详情区：短窗口与提示都保持不变。
  expect(await preview.textContent()).toBe(tail)
  await expect(hint).toHaveText('… 上方还有 70 行')

  // 完整日志进入卡片内独立的有界滚动区。
  expect(await details.locator('.thread-tool-output').textContent()).toBe(
    Array.from({ length: 80 }, (_, index) => `build step ${index + 1} completed`).join('\n'),
  )
  await expectBoundedDetails(details)
  expect(await details.evaluate((element) => element.scrollTop)).toBe(0)

  // 收起后详情区消失，短窗口依旧不变。
  await toggle(page).click()
  await expect(toggle(page)).toHaveAttribute('aria-expanded', 'false')
  await expect(details).toHaveCount(0)
  expect(await preview.textContent()).toBe(tail)
  await expect(hint).toHaveText('… 上方还有 70 行')
})

test('keeps read text collapsed, previews read images after expanding, and keeps unknown attachments collapsed', async ({ page }) => {
  await harness(page, 'idle', 20)
  await expect(bashPreview(page)).toBeVisible()
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
  await expect(bashDetails(page)).toContainText('build step 1')
  await expect(bashPreview(page)).toContainText('build step 40')

  await page.keyboard.press('Space')
  await expect(toggle(page)).toHaveAttribute('aria-expanded', 'false')
  await expect(bashPreview(page)).toBeVisible()
  await expect(bashPreview(page)).not.toContainText('build step 1')
  await expect(bashPreview(page)).toContainText('build step 40')
})

test('expanding details and loading media do not pause following', async ({ page }) => {
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

  // 回底后展开详情不会暂停跟随。
  await scrollTranscript(page, 10_000)
  await expect.poll(outerDistance).toBeLessThanOrEqual(1)
  await readToggle.click()
  await expect(readToggle).toHaveAttribute('aria-expanded', 'true')
  await flushLayout(page)

  // 正文在详情区里从顶部读：详情是卡片内唯一的有界滚动视口，展开不接管外层位置。
  const readDetails = readCard.locator('.thread-tool-details').first()
  await expect(readDetails).toBeVisible()
  await expectBoundedDetails(readDetails)
  expect(await readDetails.evaluate((element) => element.scrollTop)).toBe(0)

  await expect.poll(outerDistance).toBeLessThanOrEqual(1)

  // 图片解码也只改变几何，不改变跟随状态。
  const mediaCard = page.locator('.thread-turn-tool').nth(2)

  await harness(page, 'addImageMid', 160)
  await expect(mediaCard).toHaveCount(1)
  await clickWithoutScrolling(mediaCard.locator('.thread-disclosure-toggle'))
  const collapsedHeight = await body.evaluate((element) => element.scrollHeight)

  const image = mediaCard.locator('img').first()
  await expect(image).toBeVisible()
  await image.evaluate((element: HTMLImageElement) => element.decode())
  await flushLayout(page)

  expect(await body.evaluate((element) => element.scrollHeight)).toBeGreaterThan(collapsedHeight)
  await expect.poll(outerDistance).toBeLessThanOrEqual(1)
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

  // 3) 原生锚定可修正 scrollTop，但钳制不能重新开启贴底。
  await harness(page, 'growTail', 200)
  await expect(body).toHaveAttribute('data-following', 'false')
  expect(await outerDistance()).toBeGreaterThan(1)

  // 4) 用户真实滚轮回到新的底部：恢复跟随，后续增长重新贴底。
  await scrollTranscript(page, 10_000)
  await expect.poll(outerDistance).toBeLessThanOrEqual(1)
  await harness(page, 'growTail', 260)
  await expect.poll(outerDistance).toBeLessThanOrEqual(1)
})

test('uses the header chevron as the only collapse control for long expanded cards', async ({ page }) => {
  await harness(page, 'longExpanded', 120)
  const card = bashCard(page)
  const headerToggle = toggle(page)
  await card.scrollIntoViewIfNeeded()

  await expect(card.locator('.thread-disclosure-collapse')).toHaveCount(0)
  await headerToggle.click()
  await expect(headerToggle).toHaveAttribute('aria-expanded', 'true')
  await expect(card.locator('.thread-disclosure-collapse')).toHaveCount(0)

  await headerToggle.click()
  await expect(headerToggle).toHaveAttribute('aria-expanded', 'false')
  await expect(card.locator('.thread-disclosure-collapse')).toHaveCount(0)
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

test('all durable states keep accessible labels and a neutral shell without status icons', async ({ page }) => {
  await page.evaluate(() => window.toolCardHarness.durableStates())
  const states = ['queued', 'approval', 'input', 'running', 'succeeded', 'failed', 'cancelled', 'unknown']
  for (const state of states) {
    await expect(page.locator(`[data-invocation-state="${state}"]`)).toHaveCount(1)
    await expect(page.locator(`[data-invocation-state="${state}"]`)).toHaveAttribute('aria-label', /bash: .+/)
  }
  // 成功不再用对勾表达，外壳对所有状态都是中性色；空输出不创建详情区。
  await expect(page.locator('.thread-tool-success-icon')).toHaveCount(0)
  await expect(page.locator('.thread-disclosure.thread-block-tool.is-neutral')).toHaveCount(states.length)
  await expect(page.locator('.thread-disclosure-toggle')).toHaveCount(0)
  // 只有需要用户注意的状态才在 Header 上显示状态文字。
  const visibleStates = ['approval', 'input', 'failed', 'cancelled', 'unknown']
  await expect(page.locator('.thread-tool-state-label')).toHaveCount(visibleStates.length)
  for (const state of visibleStates) {
    await expect(page.locator(`[data-invocation-state="${state}"] .thread-tool-state-label`)).toHaveCount(1)
  }
  for (const state of states.filter((state) => !visibleStates.includes(state))) {
    await expect(page.locator(`[data-invocation-state="${state}"] .thread-tool-state-label`)).toHaveCount(0)
  }
  await expect(page.locator('[data-invocation-state="running"]')).toHaveAttribute('aria-busy', 'true')
})
