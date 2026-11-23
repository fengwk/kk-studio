import { expect, test, type Locator, type Page } from './fixture'
import type { ModelCreateDTO } from '@/shared/api/generated/contracts'
import { FIXTURE_CREATED_MODEL } from './model-browser-fixtures'
import {
  FIXTURE_DEFAULTS_PAGE,
  FIXTURE_MODELS,
  FIXTURE_PROVIDER,
} from './model-browser-fixtures'

const HARNESS_URL = '/browser-tests/model-browser-harness.html'
const VIEWPORTS = [
  { width: 1280, height: 900 },
  { width: 390, height: 844 },
]

/**
 * 浏览器内 API fixture：模型页使用真实 service/fetch 路径，但请求全部由测试进程
 * 以固定数据回应，不连接后端、生产端口或真实模型目录。
 */
async function installFakeCatalogServer(page: Page): Promise<{
  calls: string[]
  createBodies: ModelCreateDTO[]
  previewBodies: unknown[]
}> {
  const calls: string[] = []
  const createBodies: ModelCreateDTO[] = []
  const previewBodies: unknown[] = []
  let models = [...FIXTURE_MODELS]
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
          body: JSON.stringify(data),
        })

      if (request.method() === 'GET' && path === '/api/platform/providers') {
        await json([FIXTURE_PROVIDER])
        return
      }
      if (request.method() === 'GET' && path === '/api/platform/models') {
        await json(models)
        return
      }
      if (request.method() === 'POST' && path === '/api/platform/models') {
        createBodies.push(request.postDataJSON() as ModelCreateDTO)
        models = [...models, FIXTURE_CREATED_MODEL]
        await json(FIXTURE_CREATED_MODEL)
        return
      }
      if (request.method() === 'GET' && path === '/api/platform/models/capabilities') {
        const modelId = new URL(request.url()).searchParams.get('modelId')
        if (modelId === 'auto-model') {
          await json({ promptCacheOptions: [], defaultPromptCacheRetention: 'NONE', promptCacheState: 'PROVIDER_MANAGED' })
          return
        }
        if (modelId === 'long-model') {
          await json({ promptCacheOptions: [{ retention: 'LONG' }], defaultPromptCacheRetention: 'LONG', promptCacheState: 'CONFIGURABLE' })
          return
        }
        await json({
          promptCacheOptions: [
            { retention: 'NONE' },
            { retention: 'SHORT', ttlSeconds: '3600', writeMultiplier: 2 },
            { retention: 'LONG' },
          ],
          defaultPromptCacheRetention: 'SHORT',
          promptCacheState: 'CONFIGURABLE',
        })
        return
      }
      if (request.method() === 'POST' && path === '/api/platform/models/pricing-preview') {
        previewBodies.push(request.postDataJSON())
        await json({
          rates: { input: '3.20', output: '9.60', cacheRead: '0.1', cacheWrite: null, cacheWriteByTtl: {}, reasoning: null },
          activeFrom: '2027-01-01',
          matchedRules: [{ type: 'service_tier_multiplier', serviceTier: 'priority', factor: '2' }],
        })
        return
      }
      if (path === '/api/platform/model-catalog' || path === '/api/platform/model-catalog/refresh') {
        await json(FIXTURE_DEFAULTS_PAGE)
        return
      }
      await route.fulfill({
        status: 404,
        contentType: 'application/problem+json',
        body: JSON.stringify({
          status: 404,
          type: 'about:blank',
          code: 'not_found',
          title: `Unexpected fixture request: ${request.method()} ${path}`,
        }),
      })
    },
  )
  return { calls, createBodies, previewBodies }
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

/**
 * 基础费率标签在 zh-CN 界面下按精确标签定位（输入/输出/缓存读取/缓存写入），
 * 并限定在基础价格分组的第一行内，避免与 TTL 行或档位行的同名标签混用。
 */
const RATE_LABELS = {
  input: '输入',
  output: '输出',
  cacheRead: '缓存读取',
  cacheWrite: '缓存写入',
} as const

function baseRateInput(pricing: Locator, field: keyof typeof RATE_LABELS): Locator {
  return pricing.locator('.pricing-plan-section').first().locator('.form-grid-2').first()
    .getByLabel(RATE_LABELS[field], { exact: true })
}

/** 推理费率单独成行（zh-CN 推理费率）。 */
function reasoningRateInput(pricing: Locator): Locator {
  return pricing.locator('.pricing-plan-section').first().getByLabel('推理费率', { exact: true })
}

async function readModelIdentity(dialog: Locator) {
  return {
    provider: await dialog.getByRole('button', { name: '提供商', exact: true }).textContent(),
    name: await dialog.getByLabel('名称', { exact: true }).inputValue(),
    modelId: await dialog.getByLabel('模型 ID', { exact: true }).inputValue(),
    description: await dialog.getByLabel('说明', { exact: true }).inputValue(),
  }
}

/**
 * 模型卡与模型编辑器的精确文案对照。同一份表同时用于两件事：
 * 当前 locale 下每个标签精确命中一次，另一种 locale 的旧文案必须零命中。
 */
const MODEL_COPY = {
  'zh-CN': {
    cardLabels: ['提供商', '模型 ID', '请求限制', '功能', '价格', '默认预设', '模型预设'],
    cardLimit: '上下文 128000 · 输出 4096',
    cardAbilities: '工具 · TEXT',
    presetTags: ['standard', 'fast'],
    presetsHeading: '预设列表',
    variantHint: '模型预设包含思考强度与原生协议选项。预设 ID 不可重复，默认预设必须是其中一项。',
    fields: {
      provider: { role: 'button', name: '提供商' },
      name: { role: 'textbox', name: '名称' },
      // 创建态的模型 ID 带 datalist 远程候选，可访问角色是 combobox。
      modelId: { role: 'combobox', name: '模型 ID' },
      description: { role: 'textbox', name: '说明' },
      contextWindow: { role: 'textbox', name: '上下文窗口' },
      inputLimit: { role: 'textbox', name: '输入上限' },
      maxOutputTokens: { role: 'textbox', name: '最大输出 token 数' },
      tools: { role: 'checkbox', name: '工具' },
      reasoning: { role: 'checkbox', name: '推理' },
      defaultVariant: { role: 'button', name: '默认预设' },
      variantId: { role: 'textbox', name: '预设 ID 1' },
      reasoningEffort: { role: 'textbox', name: '思考强度 1' },
      addVariant: { role: 'button', name: '添加预设' },
      deleteVariant: { role: 'button', name: '删除预设' },
    },
    // 原生协议选项折叠在 <details> 内，未展开时不在可访问性树里，只能按 aria-label 断言。
    protocolOptions: '原生协议选项 1',
  },
  'en-US': {
    cardLabels: ['Provider', 'Model ID', 'Limit', 'Abilities', 'Pricing', 'Default Variant', 'Model presets'],
    cardLimit: 'ctx 128000 · out 4096',
    cardAbilities: 'tools · TEXT',
    presetTags: ['standard', 'fast'],
    presetsHeading: 'Preset list',
    variantHint:
      'A Variant carries reasoning effort and protocol options; IDs must be unique, and Default Variant must point to one of them.',
    fields: {
      provider: { role: 'button', name: 'Provider' },
      name: { role: 'textbox', name: 'Name' },
      modelId: { role: 'combobox', name: 'Model ID' },
      description: { role: 'textbox', name: 'Description' },
      contextWindow: { role: 'textbox', name: 'Context Window' },
      inputLimit: { role: 'textbox', name: 'Input Limit' },
      maxOutputTokens: { role: 'textbox', name: 'Max Output Tokens' },
      tools: { role: 'checkbox', name: 'Tools' },
      reasoning: { role: 'checkbox', name: 'Reasoning' },
      defaultVariant: { role: 'button', name: 'Default Variant' },
      variantId: { role: 'textbox', name: 'Variant ID 1' },
      reasoningEffort: { role: 'textbox', name: 'Reasoning Effort 1' },
      addVariant: { role: 'button', name: 'Add Variant' },
      deleteVariant: { role: 'button', name: 'Delete Variant' },
    },
    protocolOptions: 'Protocol Options 1',
  },
} as const

test.describe('Model, Model Group and catalog real browser acceptance', () => {
  test('keeps the model grid/search/single create card; create tabs own independent drafts; edit has no type switch', async ({
    page,
  }) => {
    const { calls } = await installFakeCatalogServer(page)

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
      await expect(dialog).toHaveAccessibleName('创建 模型')
      await expect(dialog.getByRole('tab', { name: '模型', exact: true })).toHaveAttribute(
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
      await expect(dialog).toHaveAccessibleName('创建 模型组')
      await expect(dialog.getByPlaceholder('coding')).toHaveValue('')
      await dialog.getByPlaceholder('coding').fill('draft-group')
      await dialog.getByRole('tab', { name: '模型', exact: true }).click()
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
      await expect(dialog.getByRole('button', { name: '成员预设 1', exact: true })).toContainText(
        'standard',
      )

      await dialog.getByRole('button', { name: '添加模型', exact: true }).click()
      await selectListOption(
        page,
        dialog.getByRole('button', { name: '成员模型 2' }),
        'backup',
      )
      await expect(dialog.getByRole('button', { name: '成员预设 2', exact: true })).toContainText(
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
      await expect(dialog.getByLabel('预设 ID 1', { exact: true })).toHaveCount(0)
      await expect(dialog.getByRole('button', { name: '提供商', exact: true })).toHaveCount(0)

      // 状态码复用 TagInput；冷却时间是非负整数，默认 0。
      await expect(dialog.locator('.ui-tag-item').filter({ hasText: '429' })).toBeVisible()
      const cooldown = dialog.getByLabel('冷却时间', { exact: true })
      await expect(cooldown).toHaveValue('0')
      await cooldown.fill('30')
      await expect(cooldown).toHaveValue('30')

      await expectNoHorizontalOverflow(page, dialog)
      await expect(dialog.getByRole('button', { name: '创建' })).toBeVisible()
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

      // 有草稿时关闭先确认；放弃修改后才关闭创建弹窗。
      await dialog.getByRole('button', { name: '关闭' }).click()
      const discard = page.getByRole('alertdialog', { name: '放弃未保存的修改？' })
      await discard.getByRole('button', { name: '放弃修改', exact: true }).click()
      await expect(page.getByRole('dialog')).toHaveCount(0)

      if (viewport.width === 1280) {
        // 编辑已有资源直接进入对应表单，不显示类型 Tab。
        await page.getByRole('button', { name: '编辑 main', exact: true }).click()
        dialog = page.getByRole('dialog')
        await expect(dialog).toHaveAccessibleName('编辑模型：main')
        await expect(dialog.getByRole('tab')).toHaveCount(0)
        await expect(dialog.getByRole('button', { name: '提供商', exact: true })).toBeDisabled()
        await dialog.getByRole('button', { name: '关闭' }).click()

        await page.getByRole('button', { name: '编辑 coding', exact: true }).click()
        dialog = page.getByRole('dialog')
        await expect(dialog).toHaveAccessibleName('编辑模型组：coding')
        await expect(dialog.getByRole('tab')).toHaveCount(0)
        await expect(dialog.locator('.group-member-row')).toHaveCount(2)
        await expect(dialog.locator('.group-member-row').nth(0)).toContainText('main')
        await expect(dialog.locator('.group-member-row').nth(1)).toContainText('backup')
        await expect(dialog.getByLabel('冷却时间', { exact: true })).toHaveValue('42')
        await dialog.getByRole('button', { name: '关闭' }).click()
      }
    }

    expect(calls).not.toContain('POST /api/platform/models')
    expect(calls).not.toContain('PUT /api/platform/models/main')
  })

  test('changing a create-form model replaces unsupported retention with automatic NONE and submits it', async ({
    page,
  }) => {
    const { createBodies } = await installFakeCatalogServer(page)
    await page.setViewportSize({ width: 1280, height: 900 })
    await gotoHarness(page)
    await page.locator('.create-card').click()
    const dialog = page.getByRole('dialog')
    await selectListOption(page, dialog.getByLabel('提供商', { exact: true }), 'acme')
    await dialog.getByLabel('名称', { exact: true }).fill('auto-cache')
    const modelId = dialog.getByLabel('模型 ID', { exact: true })
    await modelId.fill('long-model')
    await expect(dialog.getByLabel('提示缓存留存 1')).toHaveAttribute('data-value', 'LONG')

    await modelId.fill('auto-model')
    await expect(dialog.getByText('自动（由提供商管理）')).toBeVisible()
    await expect(dialog.getByLabel('提示缓存留存 1')).toHaveCount(0)
    await expect(dialog.getByRole('button', { name: '创建', exact: true })).toBeEnabled()
    await dialog.getByRole('button', { name: '创建', exact: true }).click()
    await expect(page.getByRole('dialog')).toHaveCount(0)
    expect(createBodies).toHaveLength(1)
    const submitted = createBodies[0]
    expect(submitted?.type).toBe('MODEL')
    if (submitted?.type === 'MODEL') {
      expect(submitted.config.variants[0]?.promptCacheRetention).toBe('NONE')
    }
  })

  test('creates an unpriced model, submits no invented rates, and reopens the saved draft', async ({
    page,
  }) => {
    const { createBodies } = await installFakeCatalogServer(page)
    await page.setViewportSize({ width: 1280, height: 900 })
    await gotoHarness(page)
    await page.locator('.create-card').click()

    let dialog = page.getByRole('dialog')
    await selectListOption(page, dialog.getByLabel('提供商', { exact: true }), 'acme')
    await dialog.getByLabel('名称', { exact: true }).fill('new-model')
    await dialog.getByLabel('模型 ID', { exact: true }).fill('acme-new-model')
    await expect(dialog.getByLabel('提示缓存留存 1')).toHaveAttribute('data-value', 'SHORT')
    const advancedOptions = dialog.locator('.advanced-options').first()
    await expect(advancedOptions).not.toHaveAttribute('open', '')
    await expect(advancedOptions.getByLabel('原生协议选项 1', { exact: true })).not.toBeVisible()
    await advancedOptions.locator('summary').click()
    await expect(advancedOptions.getByLabel('原生协议选项 1', { exact: true })).toBeVisible()

    const pricing = dialog.locator('section[aria-labelledby="model-pricing-heading"]')
    const inputPrice = baseRateInput(pricing, 'input')
    await expect(inputPrice).toHaveValue('')
    await expect(inputPrice).toHaveAttribute('placeholder', '未定价')
    // 统一单位提示固定在定价标题旁；空费率表示未知，不代表免费。
    await expect(pricing).toContainText('USD /')
    await dialog.getByRole('button', { name: '创建', exact: true }).click()

    await expect(page.getByRole('dialog')).toHaveCount(0)
    const newModelCard = page.locator('.resource-card').filter({ hasText: 'new-model' })
    await expect(newModelCard).toContainText('未定价')
    expect(createBodies).toHaveLength(1)
    const submitted = createBodies[0]
    expect(submitted?.type).toBe('MODEL')
    if (submitted?.type === 'MODEL') {
      expect(submitted.config.pricing).toBeNull()
      expect(submitted.config.variants[0]?.promptCacheRetention).toBe('SHORT')
    }

    await newModelCard.getByRole('button', { name: '编辑 new-model', exact: true }).click()
    dialog = page.getByRole('dialog')
    await expect(dialog.getByLabel('名称', { exact: true })).toHaveValue('new-model')
    const reopenedPricing = dialog.locator('section[aria-labelledby="model-pricing-heading"]')
    await expect(baseRateInput(reopenedPricing, 'input')).toHaveValue('')
    await expect(dialog.getByLabel('提示缓存留存 1')).toHaveAttribute('data-value', 'SHORT')
    await dialog.getByRole('button', { name: '关闭', exact: true }).click()
  })

  test('catalog rows preview without writing draft; fill applies limits/abilities/pricing/variants only', async ({
    page,
  }) => {
    const { calls, previewBodies } = await installFakeCatalogServer(page)

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
      await expect(dialog.getByText('models.dev', { exact: true })).toBeVisible()
      const contextWindow = dialog.getByLabel('上下文窗口', { exact: true })
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
        dialog.getByRole('button', { name: '使用参数', exact: true }),
      ).toHaveCount(0)
      await expect(dialog.locator('.modal-footer').getByRole('button', { name: '取消', exact: true })).toBeVisible()
      await page.screenshot({
        path: test.info().outputPath(`catalog-preview-${viewport.width}.png`),
        fullPage: true,
      })

      // 不支持价格转换的候选保留原价并说明原因；Picker 关闭后经真实按钮重开再选可转换候选。
      await candidates.nth(1).getByRole('button', { name: '一键填入', exact: true }).click()
      await expect(page.locator('.catalog-candidate-list')).toHaveCount(0)
      await expect(contextWindow).toHaveValue('32768')
      const pricing = dialog.locator('section[aria-labelledby="model-pricing-heading"]')
      await expect(baseRateInput(pricing, 'input')).toHaveValue('1')
      await expect(pricing).toContainText('目录价格无法等价导入，原有价格已保留。')
      await pricing.getByText('查看目录价格', { exact: true }).click()
      await expect(pricing.locator('.pricing-import-notice')).toContainText('目录没有可导入的完整价格。')

      await dialog.getByRole('button', { name: '获取默认模型参数', exact: true }).click()
      const reopenedCandidates = page.locator('.catalog-candidate')
      await expect(reopenedCandidates).toHaveCount(2)
      await reopenedCandidates.nth(0).getByRole('button', { name: '一键填入', exact: true }).click()
      await expect(page.locator('.catalog-candidate-list')).toHaveCount(0)
      await expect(page.getByRole('dialog')).toHaveCount(1)
      await expect(contextWindow).toHaveValue('200000')
      await expect(dialog.getByLabel('最大输出 token 数', { exact: true })).toHaveValue('100000')
      await expect(dialog.getByRole('checkbox', { name: '推理', exact: true })).toBeChecked()
      await expect(dialog.getByRole('checkbox', { name: 'IMAGE', exact: true })).toBeChecked()
      await readModelIdentity(dialog).then((current) => expect(current).toEqual(identity))
      await expect(dialog.getByRole('button', { name: '默认预设', exact: true })).toContainText(
        'turbo',
      )
      await expect(dialog.getByLabel('预设 ID 1', { exact: true })).toHaveValue('turbo')
      await expect(dialog.getByLabel('预设 ID 2', { exact: true })).toHaveValue('thinking')
      // 基础价格四项费率标签可见，零价/未定价仅在值上区分。
      await expect(baseRateInput(pricing, 'input')).toHaveValue('3')
      await expect(baseRateInput(pricing, 'output')).toHaveValue('15')
      await expect(baseRateInput(pricing, 'cacheRead')).toHaveValue('0.3')
      await expect(baseRateInput(pricing, 'cacheWrite')).toHaveValue('3.75')
      await expect(pricing.getByRole('heading', { name: '输入档位' })).toBeVisible()
      await expect(pricing.getByRole('heading', { name: '服务档' })).toBeVisible()
      await expect(pricing.getByRole('heading', { name: '时段规则' })).toBeVisible()
      await expect(pricing.getByLabel('TTL 秒数', { exact: true })).toHaveValue('3600')
      await expect(reasoningRateInput(pricing)).toHaveValue('9')
      await expect(dialog.getByLabel('提示缓存留存 1')).toHaveAttribute('data-value', 'SHORT')

      // 既有规则默认折叠：摘要保留范围/档位/时段与倍率，点摘要才展开字段。
      const rangeGroup = pricing.locator('.pricing-plan-section').nth(1)
      await expect(rangeGroup.locator('.pricing-plan-row')).toHaveCount(1)
      await expect(rangeGroup.locator('.pricing-rule-details')).not.toHaveAttribute('open', '')
      await expect(rangeGroup.locator('.pricing-rule-details > summary')).toContainText('200001')
      await rangeGroup.locator('.pricing-rule-details > summary').click()
      await expect(rangeGroup.locator('.pricing-rule-details')).toHaveAttribute('open', '')
      // Start (inclusive) / End (exclusive) 是区间行的两个边界字段，上限留空即为无上限。
      const rangeBounds = rangeGroup.locator('.pricing-rule-details')
      await expect(rangeBounds.getByLabel('起点（含）', { exact: true })).toHaveValue('200001')
      await expect(rangeBounds.getByLabel('终点（不含）', { exact: true })).toHaveValue('')
      const tierGroup = pricing.locator('.pricing-plan-section').nth(2)
      await tierGroup.locator('.pricing-rule-details > summary').click()
      await expect(tierGroup.getByLabel('服务档名称')).toHaveValue('priority')
      const timeGroup = pricing.locator('.pricing-plan-section').nth(3)
      await expect(timeGroup.getByLabel('时区')).toHaveValue('UTC')
      await timeGroup.locator('.pricing-rule-details > summary').click()
      await expect(timeGroup.getByLabel('开始时间')).toHaveValue('00:00')
      await expect(timeGroup.getByLabel('结束时间')).toHaveValue('24:00')
      await timeGroup.getByLabel('时区').fill('Europe/Paris')
      await expect(timeGroup.getByLabel('时区')).toHaveValue('Europe/Paris')
      await timeGroup.getByLabel('时区').fill('UTC')

      await pricing.scrollIntoViewIfNeeded()
      await page.screenshot({
        path: test.info().outputPath(`catalog-pricing-${viewport.width}.png`),
        fullPage: true,
      })

      // 试算使用后端返回的价格与命中规则，而不是浏览器按浮点数本地计算。
      await expect(pricing.getByRole('heading', { name: '费率试算' })).toBeVisible()
      await pricing.getByLabel('输入 tokens', { exact: true }).fill('250000')
      await pricing.getByLabel('请求开始时间（UTC）', { exact: true }).fill('2027-01-05T02:00')
      await pricing.getByLabel('实际返回服务档', { exact: true }).fill('priority')
      const beforePreview = calls.length
      await pricing.getByRole('button', { name: '试算价格', exact: true }).click()
      const previewResult = pricing.locator('.pricing-preview-result')
      await expect(previewResult).toContainText('$3.20')
      await expect(previewResult).toContainText('priority · ×2')
      await expect(previewResult).toContainText('2027-01-01')
      expect(calls.slice(beforePreview).filter((call) => !call.startsWith('GET ')))
        .toEqual(['POST /api/platform/models/pricing-preview'])
      expect(previewBodies.at(-1)).toMatchObject({
        inputTokens: 250000,
        requestedAt: Date.parse('2027-01-05T02:00:00Z'),
        serviceTier: 'priority',
        pricing: { base: { input: '3', output: '15' } },
      })

      await expectNoHorizontalOverflow(page, dialog)
      await expect(dialog.getByRole('button', { name: '保存修改', exact: true })).toBeVisible()
      await page.screenshot({
        path: test.info().outputPath(`catalog-pricing-${viewport.width}-preview.png`),
        fullPage: true,
      })

      await dialog.getByRole('button', { name: '关闭' }).click()
    }
  })

  test('switches back to the conflicting price plan on save instead of hiding the overlap', async ({ page }) => {
    const { calls, createBodies } = await installFakeCatalogServer(page)
    await page.setViewportSize({ width: 1280, height: 900 })
    await gotoHarness(page)
    await page.getByRole('button', { name: '编辑 main', exact: true }).click()
    const dialog = page.getByRole('dialog')
    const pricing = dialog.locator('section[aria-labelledby="model-pricing-heading"]')
    const planSelect = pricing.getByRole('button', { name: '价格方案', exact: true })

    // 调价草稿从当前方案派生，基础方案本身保持不变。
    const futureDate = new Date(Date.now() + 2 * 86_400_000).toISOString().slice(0, 10)
    await pricing.getByRole('button', { name: '添加调价', exact: true }).click()
    await pricing.getByLabel('生效日期（UTC）', { exact: true }).fill(futureDate)
    await pricing.getByRole('button', { name: '创建调价草稿', exact: true }).click()
    await expect(planSelect).toHaveText(futureDate)

    // 每条区间都有有效的覆盖价，确保行内只报区间重叠，不靠保存时才提示。
    const rangeGroup = pricing.locator('.pricing-plan-section').nth(1)
    for (const [min, max, input] of [['0', '1000', '1.5'], ['500', '2000', '2']]) {
      await rangeGroup.getByRole('button', { name: '添加输入档位', exact: true }).click()
      const row = rangeGroup.locator('.pricing-rule-details').last()
      await expect(row).toHaveAttribute('open', '')
      await row.getByLabel('起点（含）', { exact: true }).fill(min)
      await row.getByLabel('终点（不含）', { exact: true }).fill(max)
      await row.getByLabel(RATE_LABELS.input, { exact: true }).fill(input)
    }
    await expect(rangeGroup.getByRole('alert')).toHaveCount(2)
    await expect(rangeGroup.getByRole('alert')).toHaveText([
      '输入区间与规则 2 重叠。',
      '输入区间与规则 1 重叠。',
    ])

    // 切回基础方案后保存：冲突的方案必须被重新选中并显示，而不是静默提交。
    await selectListOption(page, planSelect, '基础方案 · 当前生效')
    await expect(rangeGroup.locator('.pricing-rule-details')).toHaveCount(0)
    await dialog.getByRole('button', { name: '保存修改', exact: true }).click()
    await expect(planSelect).toHaveText(futureDate)
    await expect(pricing.getByRole('alert').filter({ hasText: '输入区间与规则' })).toHaveCount(2)
    await expect(rangeGroup.locator('.pricing-rule-details')).toHaveCount(2)
    expect(createBodies).toEqual([])
    await page.screenshot({
      path: test.info().outputPath('pricing-conflict-on-save.png'),
      fullPage: true,
    })

    // 冲突在保存阶段被拒绝：整份定价不会被提交，弹窗保持打开。
    expect(calls.filter((call) => call.startsWith('PUT ') || call.startsWith('POST '))).toEqual([])
    await expect(dialog).toBeVisible()
  })

  test('new labels are readable in zh-CN and en-US at 1280 and 390 without overflow', async ({
    page,
  }) => {
    await installFakeCatalogServer(page)

    const cases = [
      {
        locale: 'zh-CN',
        title: '创建 模型组',
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
        const copy = MODEL_COPY[item.locale]
        const staleCopy = MODEL_COPY[item.locale === 'zh-CN' ? 'en-US' : 'zh-CN']

        // 模型卡行标签整体精确相等：既覆盖当前语言的全部卡片标签，也证明另一种语言的旧标签已消失。
        const mainCard = page.locator('.resource-card')
          .filter({ has: page.getByRole('heading', { name: 'main', exact: true }) })
        await expect(mainCard).toHaveCount(1)
        await expect(mainCard.locator('.resource-card-meta-label')).toHaveText([...copy.cardLabels])
        // 行顺序已由上一行锁定：2 请求限制 / 3 功能 / 6 预设标签。
        await expect(
          mainCard.locator('.resource-card-meta-row').nth(2).locator('.resource-card-meta-value'),
        ).toHaveText(copy.cardLimit)
        await expect(
          mainCard.locator('.resource-card-meta-row').nth(3).locator('.resource-card-meta-value'),
        ).toHaveText(copy.cardAbilities)
        await expect(
          mainCard.locator('.resource-card-meta-row').nth(6).locator('.resource-card-tag'),
        ).toHaveText([...copy.presetTags])

        await page.locator('.create-card').click()
        const dialog = page.getByRole('dialog')
        for (const field of Object.values(copy.fields)) {
          await expect(dialog.getByRole(field.role, { name: field.name, exact: true })).toHaveCount(1)
        }
        for (const stale of Object.values(staleCopy.fields)) {
          await expect(
            dialog.getByRole(stale.role, { name: stale.name, exact: true }),
          ).toHaveCount(0)
        }
        // 折叠在 <details> 内的字段不在可访问性树里，按 aria-label 精确断言。
        await expect(dialog.locator(`[aria-label="${copy.protocolOptions}"]`)).toHaveCount(1)
        await expect(dialog.locator(`[aria-label="${staleCopy.protocolOptions}"]`))
          .toHaveCount(0)
        const presetsSection = page.locator('.structured-section')
          .filter({ has: page.locator('.variant-stack') })
        await expect(
          presetsSection.getByRole('heading', { name: copy.presetsHeading, exact: true }),
        ).toHaveCount(1)
        await expect(presetsSection).toContainText(copy.variantHint)

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
          name: item.locale === 'zh-CN' ? '创建' : 'Create',
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
      const trigger = page.getByRole('button', { name: '模型与预设', exact: true })
      await expect(trigger).toContainText('main (standard)')
      await trigger.click()

      const listbox = page.getByRole('listbox', { name: '模型选项', exact: true })
      await expect(listbox).toBeVisible()
      await expect(listbox.getByRole('option')).toHaveText(['main', 'backup', 'coding'])
      await expect(listbox.getByRole('option', { name: 'coding', exact: true })).toHaveText('coding')
      await page.screenshot({
        path: test.info().outputPath(`chat-group-menu-${viewport.width}.png`),
        fullPage: true,
      })

      await listbox.getByRole('option', { name: 'coding', exact: true }).click()
      await expect(page.getByRole('listbox')).toHaveCount(0)
      await expect(page.getByRole('listbox', { name: '预设选项' })).toHaveCount(0)
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
