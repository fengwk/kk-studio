import fs from 'node:fs'
import path from 'node:path'
import type { TestInfo } from '@playwright/test'
import { expect, test, tokenColor, type Locator, type Page } from './fixture'

/**
 * Thread 运行时视觉矩阵：FTR-2 无效问卷、FTR-3 命令面板、FTR-6 上传失败提示。
 *
 * 断言只依赖契约本身：无障碍语义（role/aria 关系）、语义 token 的计算样式、
 * 真实几何（是否被裁切、是否换行）以及真实提交请求；不读取实现内部状态类。
 * 截图只在显式设置 `KK_RUNTIME_MATRIX_SHOTS_DIR` 时落盘，默认不在仓库内产生文件。
 */
const SHOTS_DIR = process.env.KK_RUNTIME_MATRIX_SHOTS_DIR
const QUESTIONNAIRE_HARNESS_URL = '/browser-tests/questionnaire-card-harness.html'
const COMPOSER_HARNESS_URL = '/browser-tests/composer-editor-harness.html'

const LOCALES = ['zh-CN', 'en-US'] as const
type Locale = (typeof LOCALES)[number]

const VIEWPORTS = [
  { width: 1440, height: 900, name: '1440x900' },
  { width: 390, height: 844, name: '390x844' },
] as const

const PALETTE_LABEL: Record<Locale, string> = { 'zh-CN': '命令表', 'en-US': 'Command palette' }
const DECLINE_LABEL: Record<Locale, string> = { 'zh-CN': '拒绝回答', 'en-US': 'Decline' }
const DISMISS_LABEL: Record<Locale, string> = { 'zh-CN': '关闭通知', 'en-US': 'Dismiss notification' }
/** 通用兜底文案：无效问卷必须给出具体失败原因，不能退回它。 */
const OPERATION_FAILED: Record<Locale, string> = {
  'zh-CN': '操作失败，请稍后重试',
  'en-US': 'Operation failed. Please try again.',
}
/** 失败说明必须描述失败本身，而不是正文陈述。 */
const FAILURE_WORDING: Record<Locale, RegExp> = {
  'zh-CN': /无法|失败|不可/,
  'en-US': /cannot|could not|unable|failed/,
}

const UPLOAD_FILENAME = 'release-notes-2026-11-22T09-19-23-716085Z.md'

/** 应用事件通道不启动后端，避免 harness 依赖真实 WS。 */
const socketBlocked = new WeakSet<Page>()
async function blockApplicationEvents(page: Page): Promise<void> {
  if (socketBlocked.has(page)) {
    return
  }
  socketBlocked.add(page)
  await page.routeWebSocket(/\/ws\/events$/, () => {})
}

async function expectNoHorizontalOverflow(page: Page): Promise<void> {
  const metrics = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
  }))
  expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth + 1)
}

/** 元素文字没有被横向裁切：内容宽度不超过可视宽度。 */
async function expectNoClippedText(locator: Locator): Promise<void> {
  const sizes = await locator.evaluate((element) => ({
    scrollWidth: element.scrollWidth,
    clientWidth: element.clientWidth,
  }))
  expect(sizes.clientWidth).toBeGreaterThan(0)
  expect(sizes.scrollWidth).toBeLessThanOrEqual(sizes.clientWidth + 1)
}

/** 元素完整落在视口内（含纵向），用于定位在浮层/底部的元素。 */
async function expectWithinViewport(page: Page, locator: Locator): Promise<void> {
  const box = await locator.boundingBox()
  const viewport = page.viewportSize()
  expect(box).not.toBeNull()
  expect(viewport).not.toBeNull()
  expect(box!.x).toBeGreaterThanOrEqual(-0.5)
  expect(box!.y).toBeGreaterThanOrEqual(-0.5)
  expect(box!.x + box!.width).toBeLessThanOrEqual(viewport!.width + 0.5)
  expect(box!.y + box!.height).toBeLessThanOrEqual(viewport!.height + 0.5)
}

/** 元素没有被左右裁切；纵向允许页面滚动。 */
async function expectWithinViewportWidth(page: Page, locator: Locator): Promise<void> {
  const box = await locator.boundingBox()
  const viewport = page.viewportSize()
  expect(box).not.toBeNull()
  expect(viewport).not.toBeNull()
  expect(box!.x).toBeGreaterThanOrEqual(-0.5)
  expect(box!.x + box!.width).toBeLessThanOrEqual(viewport!.width + 0.5)
}

/** 显式配置截图目录时才落盘，并作为用例附件保留。 */
async function shoot(page: Page, info: TestInfo, name: string): Promise<void> {
  const file = path.join(SHOTS_DIR!, `${name}.png`)
  fs.mkdirSync(path.dirname(file), { recursive: true })
  await page.screenshot({ path: file, fullPage: true })
  await info.attach(name, { path: file, contentType: 'image/png' })
}

/* ------------------------------- FTR-2 无效问卷 ------------------------------- */

async function openInvalidQuestionnaire(page: Page, locale: Locale): Promise<Locator> {
  await page.goto(`${QUESTIONNAIRE_HARNESS_URL}?locale=${locale}&state=invalid`)
  const card = page.locator('.interaction-questionnaire-card.is-invalid')
  await expect(card).toBeVisible()
  return card
}

test.describe('FTR-2 invalid questionnaire', () => {
  test('names the blocked question, keeps raw parameters collapsed and offers the existing decline action', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await blockApplicationEvents(page)

    const submissions: Record<string, unknown>[] = []
    await page.route(
      '**/api/harness/threads/thread-questionnaire-harness/tool-invocations/int-questionnaire-invalid/input',
      async (route) => {
        submissions.push(route.request().postDataJSON() as Record<string, unknown>)
        await route.fulfill({
          json: {
            threadId: 'thread-questionnaire-harness',
            toolInvocationId: 'int-questionnaire-invalid',
            submissionId: 'sub-1',
            actor: 'user',
            acceptedAt: 1791194400000,
            materialized: true,
          },
        })
      },
    )

    const bannerTexts: string[] = []
    for (const locale of LOCALES) {
      const card = await openInvalidQuestionnaire(page, locale)

      // ① 失败以 banner + 图标 + role=alert 呈现，而不是一段普通正文加原始 JSON。
      const banner = card.locator('.interaction-error-banner')
      await expect(banner).toHaveCount(1)
      await expect(banner).toHaveAttribute('role', 'alert')
      await expect(banner.locator('svg')).toHaveCount(1)
      await expect(banner.locator('svg')).toHaveAttribute('aria-hidden', 'true')
      await expect(banner.locator('pre')).toHaveCount(0)

      const bannerText = (await banner.innerText()).trim()
      bannerTexts.push(bannerText)
      expect(bannerText.length).toBeGreaterThan(0)
      expect(bannerText).not.toBe(OPERATION_FAILED[locale])
      expect(bannerText).toMatch(FAILURE_WORDING[locale])

      // ② 失败表面沿用 danger 语义 token，不能与普通正文同色。
      const bannerStyle = await banner.evaluate((element) => {
        const style = getComputedStyle(element)
        return {
          color: style.color,
          background: style.backgroundColor,
          border: style.borderTopColor,
        }
      })
      expect(bannerStyle.color).toBe(await tokenColor(page, '--danger'))
      expect(bannerStyle.background).toBe(await tokenColor(page, '--danger-soft'))
      expect(bannerStyle.border).toBe(await tokenColor(page, '--danger-border'))

      // ③ 原始参数只是按需诊断：默认收起，展开后逐字完整且换行显示。
      const rawArguments = await page
        .locator('.questionnaire-harness')
        .getAttribute('data-invalid-arguments')
      expect(rawArguments).toBeTruthy()
      const details = card.locator('details')
      await expect(details).toHaveCount(1)
      await expect(details).toHaveJSProperty('open', false)
      await expect(details.locator('pre')).toBeHidden()
      await details.locator('summary').click()
      await expect(details).toHaveJSProperty('open', true)
      const raw = details.locator('pre')
      await expect(raw).toBeVisible()
      expect(await raw.textContent()).toBe(rawArguments)
      await expectNoClippedText(raw)

      // ④ 现有的拒答动作仍然可用，并真的把 DECLINE 提交出去；无效问卷不提供提交回答。
      await expect(card.locator('.interaction-submit-btn')).toHaveCount(0)
      const decline = card.getByRole('button', { name: DECLINE_LABEL[locale] })
      await expect(decline).toBeEnabled()
      const expectedSubmissions = locale === 'zh-CN' ? 1 : 2
      await decline.click()
      await expect.poll(() => submissions.length).toBe(expectedSubmissions)
      expect(submissions[expectedSubmissions - 1]).toMatchObject({ type: 'DECLINE' })
      expect(String(submissions[expectedSubmissions - 1].submissionId ?? '')).not.toBe('')
    }

    // 中英文说明不同：文案来自 i18n，而不是被硬编码成一种语言。
    expect(bannerTexts[0]).not.toBe(bannerTexts[1])
  })

  for (const locale of LOCALES) {
    for (const viewport of VIEWPORTS) {
      test(`invalid questionnaire is neither clipped nor overflowing at ${viewport.name} (${locale})`, async ({
        page,
      }) => {
        await page.setViewportSize({ width: viewport.width, height: viewport.height })
        await blockApplicationEvents(page)
        const card = await openInvalidQuestionnaire(page, locale)

        await expectNoHorizontalOverflow(page)
        await expectWithinViewport(page, card)

        const banner = card.locator('.interaction-error-banner')
        await expectNoClippedText(banner)
        await expectWithinViewportWidth(page, banner)

        const decline = card.getByRole('button', { name: DECLINE_LABEL[locale] })
        await expect(decline).toBeEnabled()
        await expectWithinViewport(page, decline)

        // 展开诊断后原始参数按行换行，页面仍不出现横向滚动。
        await card.locator('details summary').click()
        const raw = card.locator('details pre')
        await expect(raw).toBeVisible()
        await expectNoClippedText(raw)
        await expectNoHorizontalOverflow(page)
        await expectWithinViewportWidth(page, decline)
      })
    }
  }
})

/* -------------------------------- FTR-3 命令面板 -------------------------------- */

/**
 * 把 harness 滚动容器拉到底部：Composer 是最后一项，滚到底就能让命令表与提示条
 * 连同 dock 一起落在视口内，与真实页面里输入区贴着视口底部一致。
 */
async function alignComposerOnScreen(page: Page): Promise<void> {
  await page.getByTestId('harness-scroll').evaluate((element) => {
    element.scrollTop = element.scrollHeight
  })
}

async function openCommandPalette(page: Page, locale: Locale): Promise<{ listbox: Locator; editor: Locator }> {
  await page.goto(`${COMPOSER_HARNESS_URL}?locale=${locale}`)
  const pane = page.locator('[data-testid="root-pane"]')
  const editor = pane.locator('.composer-editor')
  await editor.click()
  await editor.fill('/')
  const listbox = page.getByRole('listbox', { name: PALETTE_LABEL[locale] })
  await expect(listbox).toBeVisible()
  await expect(editor).toBeFocused()
  await alignComposerOnScreen(page)
  return { listbox, editor }
}

test.describe('FTR-3 command palette', () => {
  for (const locale of LOCALES) {
    test(`options belong to one listbox and the focused editor announces the active one (${locale})`, async ({
      page,
    }) => {
      await page.setViewportSize({ width: 1440, height: 900 })
      await blockApplicationEvents(page)
      const { listbox, editor } = await openCommandPalette(page, locale)

      // ① option 的 ARIA 拥有者是 listbox：中间的 ul/li 只能是呈现层。
      const ownership = await listbox.evaluate((element) => {
        const options = Array.from(element.querySelectorAll<HTMLElement>('[role="option"]'))
        const ownedByListbox = options.map((option) => {
          let node = option.parentElement
          while (node != null && node !== element) {
            const role = node.getAttribute('role')
            if (role !== 'presentation' && role !== 'none') {
              return false
            }
            node = node.parentElement
          }
          return node === element
        })
        return { ownedByListbox, ids: options.map((option) => option.id) }
      })
      expect(ownership.ids.length).toBeGreaterThan(1)
      expect(ownership.ownedByListbox.every(Boolean)).toBe(true)
      expect(ownership.ids.every((id) => id.length > 0)).toBe(true)
      expect(new Set(ownership.ids).size).toBe(ownership.ids.length)

      // ② 焦点留在编辑器，编辑器用 aria-controls / aria-activedescendant 指向当前项。
      const listboxId = await listbox.getAttribute('id')
      expect(listboxId).toBeTruthy()
      await expect(editor).toHaveAttribute('aria-controls', listboxId!)
      const firstActiveId = await editor.getAttribute('aria-activedescendant')
      expect(firstActiveId).toBe(ownership.ids[0])
      await expect(listbox).toHaveAttribute('aria-activedescendant', firstActiveId!)
      await expect(page.locator(`[id="${firstActiveId}"]`)).toHaveAttribute('aria-selected', 'true')
      await expect(listbox.locator('[aria-selected="true"]')).toHaveCount(1)

      // ③ 活动项在视觉上可区分（沿用既有高亮），并且状态由 aria-selected 同步表达。
      const activeBackground = await page
        .locator(`[id="${firstActiveId}"]`)
        .evaluate((element) => getComputedStyle(element).backgroundColor)
      const inactiveBackground = await page
        .locator(`[id="${ownership.ids[2]}"]`)
        .evaluate((element) => getComputedStyle(element).backgroundColor)
      expect(activeBackground).toBe(await tokenColor(page, '--surface-active'))
      expect(inactiveBackground).toBe('rgba(0, 0, 0, 0)')
      expect(activeBackground).not.toBe(inactiveBackground)

      // ④ 键盘游标移动后，指向的属性同步到下一项，仍只有一个活动项。
      await page.keyboard.press('ArrowDown')
      const secondActiveId = await editor.getAttribute('aria-activedescendant')
      expect(secondActiveId).toBe(ownership.ids[1])
      expect(secondActiveId).not.toBe(firstActiveId)
      await expect(listbox).toHaveAttribute('aria-activedescendant', secondActiveId!)
      await expect(page.locator(`[id="${secondActiveId}"]`)).toHaveAttribute('aria-selected', 'true')
      await expect(page.locator(`[id="${firstActiveId}"]`)).toHaveAttribute('aria-selected', 'false')
      await expect(listbox.locator('[aria-selected="true"]')).toHaveCount(1)

      // ⑤ Enter 执行当前项，面板关闭后编辑器不再声明不可见的活动项。
      await page.keyboard.press('Enter')
      await expect(page.locator('#last-command-val')).toHaveText('agent')
      await expect(listbox).toHaveCount(0)
      await expect(editor).not.toHaveAttribute('aria-controls')
      await expect(editor).not.toHaveAttribute('aria-activedescendant')
    })
  }

  for (const locale of LOCALES) {
    for (const viewport of VIEWPORTS) {
      test(`command palette stays inside ${viewport.name} without clipping labels (${locale})`, async ({ page }) => {
        await page.setViewportSize({ width: viewport.width, height: viewport.height })
        await blockApplicationEvents(page)
        const { listbox } = await openCommandPalette(page, locale)

        await expectNoHorizontalOverflow(page)
        await expectWithinViewport(page, listbox)

        // 一次读取全部选项的文字尺寸：标签与说明都不能被裁成省略号，也不能横向出界。
        const measurements = await listbox.evaluate((element) => {
          const sizes = (node: Element | null) => (node == null ? null : {
            scrollWidth: node.scrollWidth,
            clientWidth: node.clientWidth,
          })
          return Array.from(element.querySelectorAll<HTMLElement>('[role="option"]')).map((option) => {
            const rect = option.getBoundingClientRect()
            return {
              label: sizes(option.querySelector('.thread-command-label')),
              description: sizes(option.querySelector('.thread-command-desc')),
              left: rect.left,
              right: rect.right,
            }
          })
        })
        expect(measurements.length).toBeGreaterThan(1)
        const viewportWidth = page.viewportSize()!.width
        for (const measurement of measurements) {
          for (const part of [measurement.label, measurement.description]) {
            expect(part).not.toBeNull()
            expect(part!.clientWidth).toBeGreaterThan(0)
            expect(part!.scrollWidth).toBeLessThanOrEqual(part!.clientWidth + 1)
          }
          expect(measurement.left).toBeGreaterThanOrEqual(-0.5)
          expect(measurement.right).toBeLessThanOrEqual(viewportWidth + 0.5)
        }
        await expectNoClippedText(listbox.locator('.thread-command-meta'))
      })
    }
  }
})

/* ------------------------------ FTR-6 上传失败提示 ------------------------------ */

async function triggerLongUploadFailure(page: Page): Promise<{ alert: Locator; reason: string }> {
  const reason = await page.locator('#runtime-harness-facts').getAttribute('data-upload-failure-reason')
  expect(reason).toBeTruthy()
  await page.click('#fail-upload-long-btn')
  await page.locator('input[type="file"]').setInputFiles({
    name: UPLOAD_FILENAME,
    mimeType: 'text/markdown',
    buffer: Buffer.from('# release notes\n'),
  })
  const alert = page.locator('.thread-composer').getByRole('alert')
  await expect(alert).toContainText(reason!)
  await alignComposerOnScreen(page)
  return { alert, reason: reason! }
}

/** 提示条文字与几何：完整原因、可换行、不被单行省略裁掉。 */
async function expectReadableToast(page: Page, alert: Locator, reason: string): Promise<void> {
  const viewport = page.viewportSize()
  expect(viewport).not.toBeNull()
  const message = alert.locator('.composer-toast-message')
  const text = (await message.textContent()) ?? ''
  // 完整原因逐字出现，没有被省略号或截断吞掉。
  expect(text).toContain(reason)
  expect(text).toContain(UPLOAD_FILENAME)
  expect(text).not.toContain('…')

  const metrics = await message.evaluate((element) => {
    const style = getComputedStyle(element)
    const rect = element.getBoundingClientRect()
    return {
      whiteSpace: style.whiteSpace,
      textOverflow: style.textOverflow,
      scrollWidth: element.scrollWidth,
      clientWidth: element.clientWidth,
      lineHeight: Number.parseFloat(style.lineHeight),
      height: rect.height,
    }
  })
  // 允许换行、按需在任意位置断行，且不是单行省略。
  expect(metrics.whiteSpace).toBe('pre-wrap')
  expect(metrics.textOverflow).not.toBe('ellipsis')
  expect(metrics.clientWidth).toBeGreaterThan(0)
  expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth + 1)
  // 窄屏下长原因必须真的折行，而不是压在一行里被裁掉。
  if (viewport!.width <= 480) {
    expect(metrics.height).toBeGreaterThanOrEqual(metrics.lineHeight * 2 - 1)
  }

  await expectWithinViewport(page, alert)
}

test.describe('FTR-6 upload failure toast', () => {
  test('keeps the full failure reason readable and only dismisses it on user action', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await blockApplicationEvents(page)
    await page.clock.install()
    await page.goto(`${COMPOSER_HARNESS_URL}?locale=zh-CN`)
    const { alert, reason } = await triggerLongUploadFailure(page)

    // 失败真实发生：失败的 pill 已回滚，不会留下看似上传成功的附件。
    await expect(page.locator('.composer-pill')).toHaveCount(0)

    // 旧实现 5 秒后自动清除；失败原因需要用户读完，因此必须一直保留。
    await page.clock.fastForward(6_000)
    await expect(alert).toBeVisible()

    await expectReadableToast(page, alert, reason)

    // 关闭入口可访问、在提示条内可达，点击后才消失。
    const dismiss = alert.getByRole('button', { name: DISMISS_LABEL['zh-CN'] })
    await expect(dismiss).toBeVisible()
    await expectWithinViewportWidth(page, dismiss)
    await dismiss.click()
    await expect(alert).toHaveCount(0)
  })

  for (const locale of LOCALES) {
    for (const viewport of VIEWPORTS) {
      test(`long upload failure reason stays whole at ${viewport.name} (${locale})`, async ({ page }) => {
        await page.setViewportSize({ width: viewport.width, height: viewport.height })
        await blockApplicationEvents(page)
        await page.goto(`${COMPOSER_HARNESS_URL}?locale=${locale}`)
        const { alert, reason } = await triggerLongUploadFailure(page)

        await expectNoHorizontalOverflow(page)
        await expectReadableToast(page, alert, reason)
        await expect(alert.getByRole('button', { name: DISMISS_LABEL[locale] })).toBeVisible()
      })
    }
  }
})

/* --------------------------------- 视觉矩阵截图 --------------------------------- */

test.describe('runtime visual matrix screenshots', () => {
  test.skip(SHOTS_DIR == null, '设置 KK_RUNTIME_MATRIX_SHOTS_DIR 才输出视觉矩阵截图')

  test('captures invalid questionnaire, command palette and long upload toast at 1440x900 and 390x844 in both locales', async ({
    page,
  }, info) => {
    await blockApplicationEvents(page)

    for (const locale of LOCALES) {
      for (const viewport of VIEWPORTS) {
        await page.setViewportSize({ width: viewport.width, height: viewport.height })
        const suffix = `${locale}-${viewport.name}`

        await openInvalidQuestionnaire(page, locale)
        await shoot(page, info, `ftr2-invalid-questionnaire-${suffix}`)

        await openCommandPalette(page, locale)
        await shoot(page, info, `ftr3-command-palette-${suffix}`)

        await page.goto(`${COMPOSER_HARNESS_URL}?locale=${locale}`)
        await triggerLongUploadFailure(page)
        await shoot(page, info, `ftr6-upload-toast-${suffix}`)
      }
    }
  })
})
