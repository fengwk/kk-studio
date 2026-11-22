import fs from 'node:fs'
import path from 'node:path'
import type { TestInfo } from '@playwright/test'
import { expect, test, type Page } from './fixture'
import type { AgentDTO, ChatPageDTO } from '@/shared/api/generated/contracts'

const HARNESS_URL = '/browser-tests/chat-defaults-harness.html'

/** 视觉用例共用的视口矩阵：桌面 1440x900 与移动 390x844。 */
const VISUAL_VIEWPORTS = [
  { name: 'desktop', width: 1440, height: 900 },
  { name: 'mobile', width: 390, height: 844 },
] as const

async function setupEnvironmentRoute(page: Page) {
  await page.route('**/api/platform/environments**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: 'env-docker-node',
          name: 'docker-node',
          status: 'READY',
          version: '1',
          createdAt: 1790812800000,
          updatedAt: 1790812800000,
        },
        {
          id: 'env-ubuntu',
          name: 'ubuntu',
          status: 'READY',
          version: '1',
          createdAt: 1790812800000,
          updatedAt: 1790812800000,
        },
      ]),
    })
  })
}

test.describe('Chat defaults & metadata contracts', () => {
  test('mobile create dialog keeps the YOLO switch and footer reachable', async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 360, height: 640 })
    await setupEnvironmentRoute(page)
    await page.goto(HARNESS_URL)
    await page.getByTestId('open-create-btn').click()
    const dialog = page.getByRole('dialog')
    const yolo = dialog.getByRole('switch', { name: 'YOLO 模式' })
    const box = (await yolo.boundingBox())!
    expect(box.width).toBeGreaterThanOrEqual(44)
    expect(box.height).toBeGreaterThanOrEqual(44)
    await yolo.click()
    await expect(yolo).toHaveAttribute('aria-checked', 'true')
    await expect(dialog.getByRole('button', { name: '创建' })).toBeVisible()
    await page.screenshot({ path: testInfo.outputPath('chat-create-mobile.png'), animations: 'disabled' })
  })

  test('Agent search confirms without submitting the Chat form', async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 360, height: 640 })
    await setupEnvironmentRoute(page)
    await page.goto(HARNESS_URL)
    await page.getByTestId('open-create-btn').click()
    const dialog = page.getByRole('dialog')
    await dialog.getByPlaceholder('对话名称').fill('Review Chat')
    const field = dialog.getByRole('button', { name: 'Agent', exact: true })
    const search = dialog.getByRole('combobox', { name: '搜索' })

    await field.click()
    await expect(search).toBeFocused()
    await expect(dialog.getByRole('option', { name: /assistant/ })).toHaveAttribute('aria-current', 'true')
    await search.press('ArrowDown')
    await expect(dialog.getByRole('option', { name: /reviewer/ })).toHaveAttribute('aria-selected', 'true')
    await expect(field).toHaveText('assistant')
    await search.press('Escape')
    await expect(search).not.toBeVisible()
    await expect(field).toBeFocused()

    await field.press('Enter')
    await search.fill('missing')
    await expect(dialog.getByRole('status')).toContainText('没有匹配')
    await search.press('Enter')
    await expect(field).toHaveText('assistant')
    await search.fill('pull requests')
    const reviewer = dialog.getByRole('option', { name: 'reviewer Checks pull requests' })
    await expect(reviewer).toBeInViewport()
    await page.screenshot({ path: testInfo.outputPath('agent-search-mobile.png'), animations: 'disabled' })
    await search.press('Enter')
    await expect(search).not.toBeVisible()
    await expect(field).toBeFocused()
    await expect(field).toHaveText('reviewer')
    await expect(page.getByTestId('submitted-payload')).toHaveText('none')
    await dialog.getByRole('button', { name: '创建' }).click()
    await expect(page.getByTestId('submitted-payload')).toContainText('"agentName":"reviewer"')
  })

  test('create Chat: toggle YOLO, select environment, and submit intended configuration', async ({ page }) => {
    await setupEnvironmentRoute(page)
    await page.goto(HARNESS_URL)

    await page.getByTestId('open-create-btn').click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()

    // 默认 YOLO 为 false，环境为不选择环境 (null)
    const yoloSwitch = page.getByRole('switch', { name: 'YOLO 模式' })
    await expect(yoloSwitch).toHaveAttribute('aria-checked', 'false')

    const envSelect = page.getByRole('button', { name: '默认环境' })
    await expect(envSelect).toContainText('不选择环境')

    // 开启 YOLO
    await yoloSwitch.click()
    await expect(yoloSwitch).toHaveAttribute('aria-checked', 'true')

    // 选择环境 ubuntu
    await envSelect.click()
    const optionUbuntu = page.getByRole('option', { name: 'ubuntu' })
    await expect(optionUbuntu).toBeVisible()
    await optionUbuntu.click()
    await expect(envSelect).toContainText('ubuntu')

    // 填写标题并提交
    const nameInput = page.getByPlaceholder('对话名称')
    await nameInput.fill('YOLO Custom Chat')
    await page.getByRole('button', { name: '创建' }).click()

    await expect(dialog).not.toBeVisible()

    // 验证提交的真实 payload 包含所选 YOLO 与环境
    const payloadText = await page.getByTestId('submitted-payload').textContent()
    expect(payloadText).not.toBe('none')
    const submitted = JSON.parse(payloadText!)
    expect(submitted).toEqual({
      mode: 'create',
      title: 'YOLO Custom Chat',
      agentName: 'assistant',
      yoloEnabled: true,
      environmentName: 'ubuntu',
    })
  })

  test('create Chat: default selection submits yoloEnabled=false and environmentName=null', async ({ page }) => {
    await setupEnvironmentRoute(page)
    await page.goto(HARNESS_URL)

    await page.getByTestId('open-create-btn').click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()

    const nameInput = page.getByPlaceholder('对话名称')
    await nameInput.fill('Safe Chat')
    await page.getByRole('button', { name: '创建' }).click()

    await expect(dialog).not.toBeVisible()

    const payloadText = await page.getByTestId('submitted-payload').textContent()
    const submitted = JSON.parse(payloadText!)
    expect(submitted).toEqual({
      mode: 'create',
      title: 'Safe Chat',
      agentName: 'assistant',
      yoloEnabled: false,
      environmentName: null,
    })
  })

  test('edit Chat: echoes initial values, toggles YOLO off, and clears environment to null', async ({ page }) => {
    await setupEnvironmentRoute(page)
    await page.goto(HARNESS_URL)

    await page.getByTestId('open-edit-btn').click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()

    const nameInput = page.getByPlaceholder('对话名称')
    await expect(nameInput).toHaveValue('Existing Chat')

    const yoloSwitch = page.getByRole('switch', { name: 'YOLO 模式' })
    await expect(yoloSwitch).toHaveAttribute('aria-checked', 'true')

    const envSelect = page.getByRole('button', { name: '默认环境' })
    await expect(envSelect).toContainText('docker-node')

    // 关闭 YOLO
    await yoloSwitch.click()
    await expect(yoloSwitch).toHaveAttribute('aria-checked', 'false')

    // 清空环境为 null（选择“不选择环境”）
    await envSelect.click()
    const optionNoEnv = page.getByRole('option', { name: '不选择环境' })
    await expect(optionNoEnv).toBeVisible()
    await optionNoEnv.click()
    await expect(envSelect).toContainText('不选择环境')

    // 保存修改
    await page.getByRole('button', { name: '保存修改' }).click()
    await expect(dialog).not.toBeVisible()

    const payloadText = await page.getByTestId('submitted-payload').textContent()
    const submitted = JSON.parse(payloadText!)
    expect(submitted).toEqual({
      mode: 'edit',
      title: 'Existing Chat',
      agentName: 'assistant',
      yoloEnabled: false,
      environmentName: null,
    })
  })

  test('edit Chat: preserves unavailable environment as selectable option', async ({ page }) => {
    await setupEnvironmentRoute(page)
    await page.goto(HARNESS_URL)

    await page.getByTestId('open-edit-unavailable-btn').click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()

    const envSelect = page.getByRole('button', { name: '默认环境' })
    await expect(envSelect).toContainText('deleted-env · 不可用')

    await envSelect.click()
    const unavailableOption = page.getByRole('option', { name: 'deleted-env · 不可用' })
    await expect(unavailableOption).toBeVisible()
  })

  test('ChatCard: renders YOLO tag and default environment in metadata', async ({ page }) => {
    await setupEnvironmentRoute(page)
    await page.goto(HARNESS_URL)

    const cards = page.locator('.resource-card')
    await expect(cards).toHaveCount(2)

    // 第一张卡片：YOLO 开启，docker-node
    const card1 = cards.nth(0)
    await expect(card1).toContainText('YOLO Docker Chat')
    await expect(card1).toContainText('YOLO 模式')
    await expect(card1).toContainText('开启')
    await expect(card1).toContainText('默认环境')
    await expect(card1).toContainText('docker-node')

    // 第二张卡片：YOLO 关闭，不选择环境
    const card2 = cards.nth(1)
    await expect(card2).toContainText('Safe Null Env Chat')
    await expect(card2).toContainText('YOLO 模式')
    await expect(card2).toContainText('关闭')
    await expect(card2).toContainText('默认环境')
    await expect(card2).toContainText('不选择环境')
  })

  test('desktop layout 1280px: fits without horizontal overflow', async ({ page }) => {
    await setupEnvironmentRoute(page)
    await page.setViewportSize({ width: 1280, height: 800 })
    await page.goto(`${HARNESS_URL}?open=create`)

    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()

    const pageOverflow = await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth + 1,
    )
    expect(pageOverflow).toBe(true)

    const dialogOverflow = await dialog.evaluate(
      (el) => el.scrollWidth <= el.clientWidth + 1,
    )
    expect(dialogOverflow).toBe(true)
  })

  test('mobile layout 390px: fits without horizontal overflow and form controls remain operable', async ({ page }) => {
    await setupEnvironmentRoute(page)
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto(`${HARNESS_URL}?open=create`)

    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()

    const pageOverflow = await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth + 1,
    )
    expect(pageOverflow).toBe(true)

    const dialogOverflow = await dialog.evaluate(
      (el) => el.scrollWidth <= el.clientWidth + 1,
    )
    expect(dialogOverflow).toBe(true)

    // 检查移动端各控件均正常渲染且可触达
    const yoloSwitch = page.getByRole('switch', { name: 'YOLO 模式' })
    await expect(yoloSwitch).toBeVisible()
    await yoloSwitch.click()
    await expect(yoloSwitch).toHaveAttribute('aria-checked', 'true')

    const envSelect = page.getByRole('button', { name: '默认环境' })
    await expect(envSelect).toBeVisible()
    await envSelect.click()
    const option = page.getByRole('option', { name: 'ubuntu' })
    await expect(option).toBeVisible()
    await option.click()
    await expect(envSelect).toContainText('ubuntu')

    await expect(page.getByRole('button', { name: '创建' })).toBeVisible()
    await expect(page.getByRole('button', { name: '取消' })).toBeVisible()
  })

  // YOLO 行排版契约：标签与说明各自独立成行，开关垂直居中留在文字块右侧。
  const yoloRowCopy = {
    'zh-CN': {
      label: 'YOLO 模式',
      description: '自动批准工具调用，跳过人工确认',
    },
    'en-US': {
      label: 'YOLO Mode',
      description: 'Automatically approve tool calls without manual confirmation',
    },
  } as const

  for (const locale of ['zh-CN', 'en-US'] as const) {
    for (const viewport of VISUAL_VIEWPORTS) {
      test(`create Chat YOLO row: separate label/description with the switch centered beside (${locale} ${viewport.width}x${viewport.height})`, async ({ page }, testInfo) => {
        const copy = yoloRowCopy[locale]
        await page.setViewportSize({ width: viewport.width, height: viewport.height })
        await setupEnvironmentRoute(page)
        await page.goto(`${HARNESS_URL}?open=create&locale=${locale}`)

        const dialog = page.getByRole('dialog')
        await expect(dialog).toBeVisible()

        const row = dialog.locator('.create-chat-yolo-row')
        const text = row.locator('.create-chat-yolo-text')
        const label = row.locator('.field-label')
        const description = row.locator('.create-chat-yolo-description')
        const yoloSwitch = row.getByRole('switch')

        // 标签与说明是两个独立元素，文案各自完整，开关仍由标签提供无障碍名称。
        await expect(label).toHaveText(copy.label)
        await expect(description).toHaveText(copy.description)
        await expect(yoloSwitch).toHaveAccessibleName(copy.label)
        await expect(yoloSwitch).toHaveAttribute('aria-checked', 'false')

        // 滚到行可见位置，让几何断言和截图都基于同一个稳定状态。
        await row.scrollIntoViewIfNeeded()

        const labelBox = (await label.boundingBox())!
        const descriptionBox = (await description.boundingBox())!
        const textBox = (await text.boundingBox())!
        const switchBox = (await yoloSwitch.boundingBox())!

        // 标签在上、说明在下且左缘对齐：说明换行也不会挤回标签同一行。
        expect(labelBox.y + labelBox.height).toBeLessThanOrEqual(descriptionBox.y + 0.5)
        expect(Math.abs(descriptionBox.x - labelBox.x)).toBeLessThanOrEqual(1)

        // 开关在文字块右侧，并与文字块的垂直中心对齐。
        expect(switchBox.x).toBeGreaterThanOrEqual(textBox.x + textBox.width)
        const textCenterY = textBox.y + textBox.height / 2
        const switchCenterY = switchBox.y + switchBox.height / 2
        expect(Math.abs(switchCenterY - textCenterY)).toBeLessThanOrEqual(1)

        // 该行与弹窗、页面都不产生横向溢出。
        expect(await row.evaluate((el) => el.scrollWidth <= el.clientWidth + 1)).toBe(true)
        expect(await dialog.evaluate((el) => el.scrollWidth <= el.clientWidth + 1)).toBe(true)
        expect(
          await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1),
        ).toBe(true)

        await page.screenshot({
          path: testInfo.outputPath(`chat-yolo-row-${locale}-${viewport.name}-${viewport.width}x${viewport.height}.png`),
          animations: 'disabled',
        })
      })
    }
  }
})

/**
 * FTC-4：「加载更多」失败只影响这一次追加请求。
 * 已加载卡片保留，失败在分页处以 role=alert 的本地化文案 + 重试呈现，
 * 不得并入页面级 error（否则整个列表会被一个错误块替掉）。
 */
const loadMoreFailureCopy = {
  'zh-CN': {
    loadMore: '加载更多',
    retry: '重试',
    failure: '服务暂时异常，请稍后重试',
    pageFailure: '资源加载失败',
  },
  'en-US': {
    loadMore: 'Load more',
    retry: 'Retry',
    failure: 'The service is temporarily unavailable. Please try again later.',
    pageFailure: 'Failed to load resources',
  },
} as const

/** 失败回执的原始技术文案：任何语言下都不得直接展示给用户。 */
const RAW_FAILURE_TITLE = 'list chats failed'

const LIST_AGENTS: AgentDTO[] = [
  {
    name: 'assistant',
    execution: { driver: 'builtin', settings: { modelName: 'minimax/MiniMax', variant: 'default' } },
    config: { tools: [], skills: [], subagents: [] },
    version: '1',
    createdAt: 1790812800000,
    updatedAt: 1790812800000,
  },
]

const FIRST_CHAT_PAGE: ChatPageDTO = {
  items: [
    {
      id: 'chat-release-review',
      title: 'Release review',
      agentName: 'assistant',
      environmentName: 'docker-node',
      yoloEnabled: false,
      version: '3',
      createdAt: 1790812800000,
      updatedAt: 1790816400000,
    },
    {
      id: 'chat-runtime-triage',
      title: 'Runtime triage',
      agentName: 'assistant',
      environmentName: null,
      yoloEnabled: true,
      version: '2',
      createdAt: 1790809200000,
      updatedAt: 1790812800000,
    },
  ],
  nextCursor: 'cursor-page-2',
}

const SECOND_CHAT_PAGE: ChatPageDTO = {
  items: [
    {
      id: 'chat-docs-polish',
      title: 'Docs polish',
      agentName: 'assistant',
      environmentName: 'docker-node',
      yoloEnabled: false,
      version: '1',
      createdAt: 1790805600000,
      updatedAt: 1790809200000,
    },
  ],
  nextCursor: null,
}

/** 首页成功；首次「加载更多」返回 problem+json 500，重试返回末页。 */
async function setupChatListRoutes(page: Page) {
  await page.route('**/api/platform/agents', (route) => route.fulfill({ json: LIST_AGENTS }))
  let nextPageAttempts = 0
  await page.route('**/api/chat/chats**', async (route) => {
    if (new URL(route.request().url()).searchParams.get('cursor') == null) {
      await route.fulfill({ json: FIRST_CHAT_PAGE })
      return
    }
    nextPageAttempts += 1
    if (nextPageAttempts === 1) {
      await route.fulfill({
        status: 500,
        contentType: 'application/problem+json',
        body: JSON.stringify({ title: RAW_FAILURE_TITLE, status: 500 }),
      })
      return
    }
    await route.fulfill({ json: SECOND_CHAT_PAGE })
  })
}

/** 截图默认落到 Playwright 报告目录；设 KK_CHAT_PAGINATION_SHOTS_DIR 时写入该目录（可指向 worktree 外的 shots/）。 */
async function captureListShot(page: Page, testInfo: TestInfo, name: string): Promise<void> {
  const shotsDir = process.env.KK_CHAT_PAGINATION_SHOTS_DIR
  const target = shotsDir ? path.join(shotsDir, name) : testInfo.outputPath(name)
  if (shotsDir) {
    fs.mkdirSync(shotsDir, { recursive: true })
  }
  await page.screenshot({ path: target, animations: 'disabled' })
}

/** 卡片标题是 `h3`：用 heading 角色定位，避免文本匹配落到包裹节点上。 */
function chatCard(page: Page, title: string) {
  return page.getByRole('heading', { name: title, exact: true })
}

test.describe('Chat list load-more failure', () => {
  for (const locale of ['zh-CN', 'en-US'] as const) {
    for (const viewport of VISUAL_VIEWPORTS) {
      test(`keeps loaded chats and offers a localized retry (${locale} ${viewport.width}x${viewport.height})`, async ({ page }, testInfo) => {
        const copy = loadMoreFailureCopy[locale]
        await page.setViewportSize({ width: viewport.width, height: viewport.height })
        await setupChatListRoutes(page)
        await page.goto(`${HARNESS_URL}?scenario=list&locale=${locale}`)

        await expect(chatCard(page, FIRST_CHAT_PAGE.items[0].title)).toBeVisible()
        await expect(chatCard(page, FIRST_CHAT_PAGE.items[1].title)).toBeVisible()

        await page.getByRole('button', { name: copy.loadMore, exact: true }).click()

        // 失败就地呈现：分页区内的本地化文案 + 重试，已加载卡片不动。
        const loadMoreBox = page.locator('.chat-cards-load-more')
        await expect(loadMoreBox.getByRole('alert')).toHaveText(copy.failure)
        await expect(page.getByRole('button', { name: copy.retry, exact: true })).toBeVisible()
        await expect(chatCard(page, FIRST_CHAT_PAGE.items[0].title)).toBeVisible()
        await expect(chatCard(page, FIRST_CHAT_PAGE.items[1].title)).toBeVisible()
        // 未被误当成整页失败：没有页面级错误块，也没有原始技术文案。
        await expect(page.locator('.screen-body .state-block')).toHaveCount(0)
        await expect(page.getByText(copy.pageFailure, { exact: true })).toHaveCount(0)
        await expect(page.getByText(RAW_FAILURE_TITLE)).toHaveCount(0)
        // 提示与按钮同行且可换行，窄屏不横向溢出。
        await loadMoreBox.scrollIntoViewIfNeeded()
        expect(await loadMoreBox.evaluate((el) => el.scrollWidth <= el.clientWidth + 1)).toBe(true)

        await captureListShot(
          page,
          testInfo,
          `chat-load-more-error-${locale}-${viewport.name}-${viewport.width}x${viewport.height}.png`,
        )

        // 重试成功：提示消失，追加页进入列表，分页区随之收起。
        await page.getByRole('button', { name: copy.retry, exact: true }).click()
        await expect(chatCard(page, SECOND_CHAT_PAGE.items[0].title)).toBeVisible()
        await expect(loadMoreBox.getByRole('alert')).toHaveCount(0)
        await expect(loadMoreBox).toHaveCount(0)
      })
    }
  }
})
