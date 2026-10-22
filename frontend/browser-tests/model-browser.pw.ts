import { expect, test, type Locator, type Page } from './fixture'
import {
  FIXTURE_DEFAULTS_PAGE,
  FIXTURE_MODELS,
  FIXTURE_PROVIDER,
  pageResult,
  resultEnvelope,
} from './model-browser-fixtures'

const HARNESS_URL = '/browser-tests/model-browser-harness.html'
const VIEWPORTS = [
  { width: 1280, height: 900 },
  { width: 390, height: 844 },
]

/**
 * 浏览器内 API fixture：模型页使用真实 service/axios 路径，但请求全部由测试进程
 * 以固定数据回应，不连接后端、生产端口或真实模型目录。
 */
async function installFakeCatalogServer(page: Page): Promise<string[]> {
  const calls: string[] = []
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const request = route.request()
      const path = new URL(request.url()).pathname
      calls.push(`${request.method()} ${path}`)
      const json = (data: unknown) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(resultEnvelope(data)),
        })

      if (request.method() === 'GET' && path === '/api/providers') {
        await json(pageResult([FIXTURE_PROVIDER]))
        return
      }
      if (request.method() === 'GET' && path === '/api/models') {
        await json(pageResult(FIXTURE_MODELS))
        return
      }
      if (path === '/api/model-defaults') {
        await json(FIXTURE_DEFAULTS_PAGE)
        return
      }
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({
          status: 404,
          code: 'NOT_FOUND',
          message: `Unexpected fixture request: ${request.method()} ${path}`,
          data: null,
        }),
      })
    },
  )
  return calls
}

async function gotoHarness(page: Page, params = '') {
  await page.goto(`${HARNESS_URL}${params}`)
}

/** 页面与弹窗都必须没有横向溢出；弹窗卡片本身也必须留在视口内。 */
async function expectNoHorizontalOverflow(page: Page, dialog?: Locator) {
  const viewport = page.viewportSize()
  expect(viewport).not.toBeNull()
  const documentMetrics = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
  }))
  expect(documentMetrics.scrollWidth).toBeLessThanOrEqual(documentMetrics.clientWidth + 1)

  if (!dialog) {
    return
  }
  const modalMetrics = await dialog.evaluate((element) => {
    const card = element.getBoundingClientRect()
    const body = element.querySelector('.modal-body')
    return {
      left: card.left,
      right: card.right,
      top: card.top,
      bottom: card.bottom,
      viewportWidth: window.innerWidth,
      viewportHeight: window.innerHeight,
      bodyScrollWidth: body?.scrollWidth ?? 0,
      bodyClientWidth: body?.clientWidth ?? 0,
    }
  })
  expect(modalMetrics.left).toBeGreaterThanOrEqual(0)
  expect(modalMetrics.right).toBeLessThanOrEqual(modalMetrics.viewportWidth + 1)
  expect(modalMetrics.top).toBeGreaterThanOrEqual(0)
  expect(modalMetrics.bottom).toBeLessThanOrEqual(modalMetrics.viewportHeight + 1)
  expect(modalMetrics.bodyScrollWidth).toBeLessThanOrEqual(modalMetrics.bodyClientWidth + 1)
}

async function selectListOption(page: Page, trigger: Locator, optionName: string) {
  await trigger.click()
  await page.getByRole('option', { name: optionName, exact: true }).click()
}

async function readModelIdentity(dialog: Locator) {
  return {
    provider: await dialog.getByRole('button', { name: 'Provider', exact: true }).textContent(),
    name: await dialog.getByLabel('Name', { exact: true }).inputValue(),
    modelId: await dialog.getByLabel('Model ID', { exact: true }).inputValue(),
    description: await dialog.getByLabel('Description', { exact: true }).inputValue(),
  }
}

test.describe('Model, Model Group and catalog real browser acceptance', () => {
  test('keeps the model grid/search/single create card; create tabs own independent drafts; edit has no type switch', async ({
    page,
  }) => {
    const calls = await installFakeCatalogServer(page)

    for (const viewport of VIEWPORTS) {
      await page.setViewportSize(viewport)
      await gotoHarness(page)

      await expect(page.locator('.create-card')).toHaveCount(1)
      await expect(page.locator('.resource-card')).toHaveCount(3)
      await expect(page.locator('.resource-grid')).toHaveCount(1)
      await expect(page.getByText('main', { exact: true })).toBeVisible()

      const search = page.locator('.searchbox input')
      await search.fill('main')
      await expect(page.locator('.resource-card')).toHaveCount(1)
      await expect(page.locator('.resource-card')).toContainText('main')
      await search.fill('')
      await expect(page.locator('.resource-card')).toHaveCount(3)
      await expectNoHorizontalOverflow(page)
      await page.screenshot({
        path: test.info().outputPath(`models-page-${viewport.width}.png`),
        fullPage: true,
      })

      await page.locator('.create-card').click()
      let dialog = page.getByRole('dialog')
      await expect(dialog).toHaveAccessibleName('新建 Model')
      await expect(dialog.getByRole('tab', { name: 'Model', exact: true })).toHaveAttribute(
        'aria-selected',
        'true',
      )
      await expect(dialog.getByRole('tab', { name: '模型组', exact: true })).toHaveAttribute(
        'aria-selected',
        'false',
      )

      // 同一 Modal 内切换类型只切换草稿；单 Model 草稿不被组表单覆盖。
      await dialog.getByPlaceholder('MiniMax-M2.7', { exact: true }).fill('draft-model')
      await dialog.getByRole('tab', { name: '模型组', exact: true }).click()
      await expect(dialog).toHaveAccessibleName('新建 模型组')
      await expect(dialog.getByPlaceholder('coding')).toHaveValue('')
      await dialog.getByPlaceholder('coding').fill('draft-group')
      await dialog.getByRole('tab', { name: 'Model', exact: true }).click()
      await expect(dialog.getByPlaceholder('MiniMax-M2.7', { exact: true })).toHaveValue('draft-model')
      await dialog.getByRole('tab', { name: '模型组', exact: true }).click()
      await expect(dialog.getByPlaceholder('coding')).toHaveValue('draft-group')

      // 组成员只允许单 Model；选择后沿用其默认 Variant，并可排序/删除。
      const firstMember = dialog.getByRole('button', { name: '成员模型 1' })
      await firstMember.click()
      const memberList = page.getByRole('listbox', { name: '成员模型 1' })
      await expect(memberList.getByRole('option')).toHaveCount(2)
      await expect(memberList.getByRole('option', { name: 'coding' })).toHaveCount(0)
      await memberList.getByRole('option', { name: 'main' }).click()
      await expect(dialog.getByRole('button', { name: '成员 Variant 1' })).toContainText(
        'standard',
      )

      await dialog.getByRole('button', { name: '添加模型', exact: true }).click()
      await selectListOption(
        page,
        dialog.getByRole('button', { name: '成员模型 2' }),
        'backup',
      )
      await expect(dialog.getByRole('button', { name: '成员 Variant 2' })).toContainText(
        'default',
      )
      await expect(dialog.locator('.group-member-row')).toHaveCount(2)

      // 派生能力/limit 为只读摘要，不出现组级价格、Provider 或 Variant 编辑。
      const summary = dialog.locator('.group-summary')
      await expect(summary).toContainText('共同能力')
      await expect(summary).toContainText('64000')
      await expect(summary).toContainText('2048')
      await expect(summary.locator('input, select, textarea')).toHaveCount(0)
      await expect(dialog.locator('section[aria-labelledby="model-pricing-heading"]')).toHaveCount(0)
      await expect(dialog.getByLabel('Variant ID 1')).toHaveCount(0)
      await expect(dialog.getByRole('button', { name: 'Provider', exact: true })).toHaveCount(0)

      // 状态码复用 TagInput；冷却时间是非负整数，默认 0。
      await expect(dialog.locator('.ui-tag-item').filter({ hasText: '429' })).toBeVisible()
      const cooldown = dialog.getByLabel('冷却时间', { exact: true })
      await expect(cooldown).toHaveValue('0')
      await cooldown.fill('30')
      await expect(cooldown).toHaveValue('30')

      await expectNoHorizontalOverflow(page, dialog)
      await expect(dialog.getByRole('button', { name: '确认创建' })).toBeVisible()
      await page.screenshot({
        path: test.info().outputPath(`group-create-${viewport.width}.png`),
        fullPage: true,
      })

      // 排序与删除真实改写草稿顺序，并重新编号。
      await dialog.getByRole('button', { name: '成员上移', exact: true }).nth(1).click()
      await expect(dialog.locator('.group-member-row').nth(0)).toContainText('backup')
      await dialog.getByRole('button', { name: '删除成员 2', exact: true }).click()
      await expect(dialog.locator('.group-member-row')).toHaveCount(1)
      await expect(dialog.locator('.group-member-row').nth(0)).toContainText('backup')

      await dialog.getByRole('button', { name: '关闭' }).click()
      await expect(page.getByRole('dialog')).toHaveCount(0)

      if (viewport.width === 1280) {
        // 编辑已有资源直接进入对应表单，不显示类型 Tab。
        await page.getByRole('button', { name: '编辑 main', exact: true }).click()
        dialog = page.getByRole('dialog')
        await expect(dialog).toHaveAccessibleName('编辑 Model')
        await expect(dialog.getByRole('tab')).toHaveCount(0)
        await expect(dialog.getByRole('button', { name: 'Provider', exact: true })).toBeDisabled()
        await dialog.getByRole('button', { name: '关闭' }).click()

        await page.getByRole('button', { name: '编辑 coding', exact: true }).click()
        dialog = page.getByRole('dialog')
        await expect(dialog).toHaveAccessibleName('编辑 模型组')
        await expect(dialog.getByRole('tab')).toHaveCount(0)
        await expect(dialog.locator('.group-member-row')).toHaveCount(2)
        await expect(dialog.locator('.group-member-row').nth(0)).toContainText('main')
        await expect(dialog.locator('.group-member-row').nth(1)).toContainText('backup')
        await expect(dialog.getByLabel('冷却时间', { exact: true })).toHaveValue('42')
        await dialog.getByRole('button', { name: '关闭' }).click()
      }
    }

    expect(calls).not.toContain('POST /api/models')
    expect(calls).not.toContain('PUT /api/models/main')
  })

  test('catalog rows preview without writing draft; fill applies limits/abilities/pricing/variants only', async ({
    page,
  }) => {
    await installFakeCatalogServer(page)

    for (const viewport of VIEWPORTS) {
      await page.setViewportSize(viewport)
      await gotoHarness(page)
      await page.getByRole('button', { name: '编辑 main', exact: true }).click()

      const dialog = page.getByRole('dialog')
      const identity = await readModelIdentity(dialog)
      expect(identity).toEqual({
        provider: 'acme',
        name: 'main',
        modelId: 'acme-main-v1',
        description: 'Primary fixture model',
      })

      await dialog.getByRole('button', { name: '获取默认模型参数', exact: true }).click()
      const candidates = page.locator('.catalog-candidate')
      await expect(candidates).toHaveCount(2)
      await expect(dialog.getByText('models.dev v2')).toBeVisible()
      const contextWindow = dialog.getByLabel('Context Window', { exact: true })
      await expect(contextWindow).toHaveValue('128000')

      // 行点击只展开当前行的简洁 preview，不改草稿。
      await candidates.nth(0).locator('.catalog-candidate-main').click()
      await expect(candidates.nth(0)).toHaveAttribute('data-expanded', 'true')
      await expect(candidates.nth(0).locator('.catalog-candidate-preview')).toContainText('200000')
      await expect(contextWindow).toHaveValue('128000')
      await readModelIdentity(dialog).then((current) => expect(current).toEqual(identity))

      await candidates.nth(1).locator('.catalog-candidate-main').click()
      await expect(page.locator('.catalog-candidate[data-expanded="true"]')).toHaveCount(1)
      await expect(candidates.nth(0)).not.toHaveAttribute('data-expanded', 'true')
      await expect(candidates.nth(1)).toHaveAttribute('data-expanded', 'true')
      const expandedPreview = candidates.nth(1).locator('.catalog-candidate-preview')
      await expect(expandedPreview).toBeVisible()
      await expect(expandedPreview).toContainText('32768')
      // 首次加载完成后的 debounce 不能再次清空用户展开的 preview。
      await page.waitForTimeout(400)
      await expect(expandedPreview).toBeVisible()
      expect((await expandedPreview.boundingBox())?.height ?? 0).toBeGreaterThan(20)
      await expect(contextWindow).toHaveValue('128000')
      await expect(page.getByRole('dialog')).toHaveCount(1)
      await expect(
        dialog.getByRole('button', { name: /使用参数|取消|Use parameters|Cancel/ }),
      ).toHaveCount(0)
      await page.screenshot({
        path: test.info().outputPath(`catalog-preview-${viewport.width}.png`),
        fullPage: true,
      })

      // 每行一键填入才是写草稿的唯一动作；填完收起候选区且不增加第二个 Modal。
      await candidates.nth(0).getByRole('button', { name: '一键填入', exact: true }).click()
      await expect(page.locator('.catalog-candidate-list')).toHaveCount(0)
      await expect(page.getByRole('dialog')).toHaveCount(1)
      await expect(contextWindow).toHaveValue('200000')
      await expect(dialog.getByLabel('Max Output Tokens', { exact: true })).toHaveValue('100000')
      await expect(dialog.getByRole('checkbox', { name: 'Reasoning', exact: true })).toBeChecked()
      await expect(dialog.getByRole('checkbox', { name: 'IMAGE', exact: true })).toBeChecked()
      await readModelIdentity(dialog).then((current) => expect(current).toEqual(identity))
      await expect(dialog.getByRole('button', { name: 'Default Variant', exact: true })).toContainText(
        'turbo',
      )
      await expect(dialog.getByLabel('Variant ID 1', { exact: true })).toHaveValue('turbo')
      await expect(dialog.getByLabel('Variant ID 2', { exact: true })).toHaveValue('thinking')

      // 价格区完整可用：base、TTL、reasoning 继承/覆盖、override 与 multiplier。
      const pricing = dialog.locator('section[aria-labelledby="model-pricing-heading"]')
      await expect(pricing.getByLabel('Input USD per million tokens')).toHaveValue('3')
      await expect(pricing.getByLabel('Output USD per million tokens')).toHaveValue('15')
      await expect(pricing.getByLabel('Cache Read USD per million tokens')).toHaveValue('0.3')
      await expect(pricing.getByLabel('Cache Write USD per million tokens')).toHaveValue('3.75')
      await pricing.scrollIntoViewIfNeeded()
      await page.screenshot({
        path: test.info().outputPath(`catalog-pricing-base-${viewport.width}.png`),
        fullPage: true,
      })

      await pricing.getByRole('button', { name: '缓存写入 TTL 覆盖', exact: true }).click()
      await expect(pricing.getByLabel('TTL 秒数', { exact: true })).toHaveValue('3600')
      const ttlRate = pricing.getByLabel('TTL 缓存写入价', { exact: true })
      await expect(ttlRate).toHaveValue('7.5')
      await ttlRate.fill('8.5')
      await expect(ttlRate).toHaveValue('8.5')

      const reasoningPricing = pricing.getByRole('checkbox', { name: '思考独立定价', exact: true })
      const reasoningPricingToggle = pricing.locator('label.ui-checkbox', { hasText: '思考独立定价' })
      await expect(reasoningPricing).toBeChecked()
      await expect(pricing.getByRole('textbox', { name: 'Reasoning', exact: true })).toHaveValue('9')
      await reasoningPricingToggle.click()
      await expect(reasoningPricing).not.toBeChecked()
      await expect(pricing.getByRole('textbox', { name: 'Reasoning', exact: true })).toHaveCount(0)
      await reasoningPricingToggle.click()
      await expect(reasoningPricing).toBeChecked()
      await expect(pricing.getByRole('textbox', { name: 'Reasoning', exact: true })).toHaveValue('0')
      await page.screenshot({
        path: test.info().outputPath(`catalog-pricing-ttl-${viewport.width}.png`),
        fullPage: true,
      })

      await pricing.getByRole('button', { name: '价格覆盖', exact: true }).click()
      const overrideRule = pricing.locator('.pricing-rule').filter({ hasText: 'ov-ctx' })
      await expect(overrideRule).toBeVisible()
      await overrideRule.locator('.pricing-rule-summary').click()
      const overrideInput = overrideRule.getByRole('textbox', { name: '覆盖 Input' })
      await expect(overrideInput).toHaveValue('6')
      await overrideInput.fill('6.5')
      await expect(overrideInput).toHaveValue('6.5')

      // 目录给出的全天时间必须可读可编辑；TTL patch 用显式空 map 表示清空继承。
      await expect(overrideRule.getByLabel('开始时间', { exact: true })).toHaveValue('00:00')
      await expect(overrideRule.getByLabel('结束时间', { exact: true })).toHaveValue('24:00')
      const ttlOverride = overrideRule.getByRole('checkbox', { name: '覆盖缓存写入 TTL', exact: true })
      await expect(ttlOverride).toBeChecked()
      await expect(overrideRule.getByLabel('TTL 秒数', { exact: true })).toHaveCount(0)
      await overrideRule.getByRole('button', { name: '添加 TTL 覆盖', exact: true }).click()
      await expect(overrideRule.getByLabel('TTL 秒数', { exact: true })).toHaveValue('300')
      if (viewport.width === 390) {
        // 仅在归档截图时增高 390 视口，使 24:00 与 TTL 覆盖开关同屏可见。
        await page.setViewportSize({ width: 390, height: 1400 })
        await overrideRule.getByLabel('结束时间', { exact: true }).scrollIntoViewIfNeeded()
        await page.screenshot({
          path: test.info().outputPath('catalog-pricing-time-ttl-390.png'),
          fullPage: true,
        })
        await page.setViewportSize(viewport)
      }
      await overrideRule.getByRole('button', { name: '删除 TTL 覆盖', exact: true }).click()
      await expect(overrideRule.getByLabel('TTL 秒数', { exact: true })).toHaveCount(0)
      await expect(ttlOverride).toBeChecked()

      await pricing.getByRole('button', { name: '价格倍率', exact: true }).click()
      const multiplierRule = pricing.locator('.pricing-rule').filter({ hasText: 'mx-tier' })
      await expect(multiplierRule).toBeVisible()
      await multiplierRule.locator('.pricing-rule-summary').click()
      const multiplierInput = multiplierRule.getByRole('textbox', { name: '倍率 Input' })
      await expect(multiplierInput).toHaveValue('1.5')
      await multiplierInput.fill('1.8')
      await expect(multiplierInput).toHaveValue('1.8')

      await expectNoHorizontalOverflow(page, dialog)
      await expect(dialog.getByRole('button', { name: '保存修改', exact: true })).toBeVisible()
      await page.screenshot({
        path: test.info().outputPath(`catalog-pricing-${viewport.width}.png`),
        fullPage: true,
      })

      await dialog.getByRole('button', { name: '关闭' }).click()
    }
  })

  test('new labels are readable in zh-CN and en-US at 1280 and 390 without overflow', async ({
    page,
  }) => {
    await installFakeCatalogServer(page)

    const cases = [
      {
        locale: 'zh-CN',
        title: '新建 模型组',
        groupTab: '模型组',
        members: '模型列表',
        addMember: '添加模型',
        codes: '立即降级状态码',
        cooldown: '冷却时间',
        abilities: '共同能力',
      },
      {
        locale: 'en-US',
        title: 'Create Model Group',
        groupTab: 'Model Group',
        members: 'Model list',
        addMember: 'Add model',
        codes: 'Immediate fallback status codes',
        cooldown: 'Cooldown time',
        abilities: 'Shared abilities',
      },
    ] as const

    for (const viewport of VIEWPORTS) {
      await page.setViewportSize(viewport)
      for (const item of cases) {
        await gotoHarness(page, `?locale=${item.locale}`)
        await expect(page.locator('html')).toHaveAttribute('lang', item.locale)
        await page.locator('.create-card').click()
        const dialog = page.getByRole('dialog')
        await dialog.getByRole('tab', { name: item.groupTab, exact: true }).click()
        await expect(dialog).toHaveAccessibleName(item.title)

        for (const label of [
          item.groupTab,
          item.members,
          item.addMember,
          item.codes,
          item.cooldown,
          item.abilities,
        ]) {
          await expect(dialog.getByText(label).first()).toBeVisible()
        }
        await expect(dialog.getByRole('button', { name: item.addMember, exact: true })).toBeVisible()
        await expect(page.locator('body')).not.toContainText('⟦missing:')
        await expectNoHorizontalOverflow(page, dialog)

        // 窄屏下仍可实际添加行并滚动到主按钮。
        await dialog.getByRole('button', { name: item.addMember, exact: true }).click()
        await expect(dialog.locator('.group-member-row')).toHaveCount(2)
        await expect(dialog.getByRole('button', {
          name: item.locale === 'zh-CN' ? '确认创建' : 'Create',
          exact: true,
        })).toBeVisible()

        await page.screenshot({
          path: test.info().outputPath(`i18n-${item.locale}-${viewport.width}.png`),
          fullPage: true,
        })
        await dialog.getByRole('button', { name: item.locale === 'zh-CN' ? '关闭' : 'Close' }).click()
      }
    }
  })

  test('chat selects a Model Group by its plain name without a Variant submenu, badge or subtitle', async ({
    page,
  }) => {
    for (const viewport of VIEWPORTS) {
      await page.setViewportSize(viewport)
      await gotoHarness(page, '?view=composer')
      const trigger = page.getByRole('button', { name: 'Model 与 Variant', exact: true })
      await expect(trigger).toContainText('main (standard)')
      await trigger.click()

      const listbox = page.getByRole('listbox', { name: 'Model 选项', exact: true })
      await expect(listbox).toBeVisible()
      await expect(listbox.getByRole('option')).toHaveText(['main', 'backup', 'coding'])
      await expect(listbox.getByRole('option', { name: 'coding', exact: true })).toHaveText('coding')
      await page.screenshot({
        path: test.info().outputPath(`chat-group-menu-${viewport.width}.png`),
        fullPage: true,
      })

      await listbox.getByRole('option', { name: 'coding', exact: true }).click()
      await expect(page.getByRole('listbox')).toHaveCount(0)
      await expect(page.getByRole('listbox', { name: 'Variant 选项' })).toHaveCount(0)
      await expect(trigger).toHaveText('coding')
      await expect(page.locator('#composer-selection')).toHaveText(
        '{"modelName":"coding","variant":null}',
      )
      await expect(page.locator('.thread-composer-controls')).not.toContainText('Model Group')
      await expect(page.locator('.thread-composer-controls')).not.toContainText('模型组')
      await expect(page.locator('.thread-composer-controls .resource-card-subtitle')).toHaveCount(0)
      await expectNoHorizontalOverflow(page)
      await page.screenshot({
        path: test.info().outputPath(`chat-group-${viewport.width}.png`),
        fullPage: true,
      })
    }
  })
})
