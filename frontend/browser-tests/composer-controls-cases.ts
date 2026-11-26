import fs from 'node:fs'
import path from 'node:path'
import { expect, test, type Locator, type Page } from './fixture'

/**
 * Review 第 2 轮第 2 项回归：390 宽 Composer 控件行的截断优先级。
 *
 * 要求模型与环境在空间不足时仍保留可辨认前缀；
 * 1440 宽不得回退。断言只依赖可访问名、真实几何与计算样式，不读实现内部类名状态。
 */
const HARNESS_URL = '/browser-tests/composer-controls-harness.html'
const SHOTS_DIR = process.env.KK_COMPOSER_CONTROLS_SHOTS_DIR

const LOCALES = ['zh-CN', 'en-US'] as const
type Locale = (typeof LOCALES)[number]

/** 触发按钮的可访问名，来自 ai.runtime.composer.{environment,modelVariant}。 */
const CONTROL_LABEL: Record<Locale, { environment: string; model: string; permission: string; permissionValue: string }> = {
  'zh-CN': { environment: '环境', model: '模型与预设', permission: '权限模式', permissionValue: '默认' },
  'en-US': { environment: 'Environment', model: 'Model and Variant', permission: 'Permission mode', permissionValue: 'Default' },
}

/** 与 R2-9 证据同一组真实选择：模型名带 variant 后缀，环境名保持 dev-workstation。 */
const MODEL_LABEL = 'gpt-mock-large (default)'
const ENVIRONMENT_LABEL = 'dev-workstation'
/** 模型名必须保留的可辨认前缀（英文、中文界面同为模型名的开头 4 个字符）。 */
const RECOGNIZABLE_PREFIX = 'gpt-'

const VIEWPORTS = [
  { width: 1440, height: 900, name: '1440x900' },
  { width: 390, height: 844, name: '390x844' },
] as const

interface LabelMetrics {
  text: string
  clientWidth: number
  scrollWidth: number
  textOverflow: string
  whiteSpace: string
}

interface Rect {
  x: number
  width: number
}

async function openHarness(page: Page, locale: Locale): Promise<{
  row: Locator
  environment: Locator
  model: Locator
}> {
  await page.goto(`${HARNESS_URL}?locale=${locale}`)
  const row = page.locator('.thread-composer-controls')
  const environment = page.getByRole('button', { name: CONTROL_LABEL[locale].environment, exact: true })
  const model = page.getByRole('button', { name: CONTROL_LABEL[locale].model, exact: true })
  await expect(row).toBeVisible()
  await expect(environment).toBeVisible()
  await expect(model).toBeVisible()
  return { row, environment, model }
}

/** 控件内的标签 span：省略号裁切就发生在这一层。 */
function labelOf(control: Locator): Locator {
  return control.locator('span').first()
}

function readLabel(control: Locator): Promise<LabelMetrics> {
  return labelOf(control).evaluate((span) => {
    const style = getComputedStyle(span)
    return {
      text: span.textContent ?? '',
      clientWidth: span.clientWidth,
      scrollWidth: span.scrollWidth,
      textOverflow: style.textOverflow,
      whiteSpace: style.whiteSpace,
    }
  })
}

async function rectOf(locator: Locator): Promise<Rect> {
  const box = await locator.boundingBox()
  expect(box, '元素必须有可见几何').not.toBeNull()
  return { x: box!.x, width: box!.width }
}

function expectWhole(metrics: LabelMetrics, label: string, what: string): void {
  expect(metrics.text, `${what} 文本`).toBe(label)
  expect(metrics.scrollWidth, `${what} 应完整显示`).toBeLessThanOrEqual(metrics.clientWidth + 1)
}

/** 省略号样式必须来自控件标签自身，而不是被父级裁掉。 */
function expectEllipsisStyle(metrics: LabelMetrics, what: string): void {
  expect(metrics.textOverflow, `${what} 使用省略号`).toBe('ellipsis')
  expect(metrics.whiteSpace, `${what} 单行不换行`).toBe('nowrap')
}

/** 前缀在可视宽度内完整可见：截断点至少落在该前缀之后。 */
async function expectPrefixVisible(control: Locator, prefix: string): Promise<void> {
  const measurement = await labelOf(control).evaluate((span, visiblePrefix) => {
    const node = span.firstChild
    if (node == null || node.nodeType !== Node.TEXT_NODE) {
      return null
    }
    const text = node.textContent ?? ''
    const range = document.createRange()
    range.setStart(node, 0)
    range.setEnd(node, Math.min(visiblePrefix.length, text.length))
    const style = getComputedStyle(span)
    const canvas = document.createElement('canvas').getContext('2d')!
    canvas.font = `${style.fontWeight} ${style.fontSize} ${style.fontFamily}`
    const ellipsisWidth = span.scrollWidth > span.clientWidth ? canvas.measureText('…').width : 0
    return {
      text,
      prefixWidth: range.getBoundingClientRect().width,
      visibleWidth: span.clientWidth - ellipsisWidth,
    }
  }, prefix)

  expect(measurement, '标签必须是单个文本节点').not.toBeNull()
  expect(measurement!.text.startsWith(prefix), `标签以 ${prefix} 开头`).toBe(true)
  expect(
    measurement!.prefixWidth,
    `可辨认前缀 ${prefix} 必须完整可见`,
  ).toBeLessThanOrEqual(measurement!.visibleWidth + 0.5)
}

async function expectNoHorizontalOverflow(page: Page): Promise<void> {
  const metrics = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
  }))
  expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth + 1)
}

for (const locale of LOCALES) {
  test(`390 宽控件行保留可辨认的环境名和模型名 (${locale})`, async ({
    page,
  }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    const { row, environment, model } = await openHarness(page, locale)

    const environmentLabel = await readLabel(environment)
    const modelLabel = await readLabel(model)

    // 放不下才省略。不同系统的字体宽度可能让某个标签恰好完整显示。
    expectEllipsisStyle(environmentLabel, '环境标签')
    expectEllipsisStyle(modelLabel, '模型标签')

    await expectPrefixVisible(environment, ENVIRONMENT_LABEL.slice(0, 8))
    await expectPrefixVisible(model, RECOGNIZABLE_PREFIX)
    const { permission, permissionValue } = CONTROL_LABEL[locale]
    const permissionControl = row.getByRole('button', { name: permission, exact: true })
    expectWhole(await readLabel(permissionControl), permissionValue, '权限模式')
    const modelRect = await rectOf(model)
    const environmentRect = await rectOf(environment)
    expect(environmentRect.x + environmentRect.width).toBeLessThanOrEqual(modelRect.x + 0.5)
    await expect(model).toHaveAttribute('title', MODEL_LABEL)
    await expect(environment).toHaveAttribute('title', ENVIRONMENT_LABEL)

    // 控件不越出控件行，行内不折行；页面无横向溢出。
    const rowRect = await rectOf(row)
    expect(Math.abs(rowRect.width - (modelRect.x + modelRect.width - rowRect.x))).toBeLessThanOrEqual(1)
    expect(modelRect.x).toBeGreaterThanOrEqual(rowRect.x - 0.5)
    await expectNoHorizontalOverflow(page)
  })

  test(`1440 宽控件行不回退：模型与环境完整显示且分列两端 (${locale})`, async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    const { row, environment, model } = await openHarness(page, locale)

    const environmentLabel = await readLabel(environment)
    const modelLabel = await readLabel(model)
    expectWhole(environmentLabel, ENVIRONMENT_LABEL, '1440 宽环境标签')
    expectWhole(modelLabel, MODEL_LABEL, '1440 宽模型标签')

    const rowRect = await rectOf(row)
    const environmentRect = await rectOf(environment)
    const modelRect = await rectOf(model)
    expect(environmentRect.x, '环境仍排在模型左侧').toBeLessThan(modelRect.x)
    expect(
      rowRect.x + rowRect.width - (modelRect.x + modelRect.width),
      '模型仍贴住控件行右端',
    ).toBeLessThanOrEqual(1)
    await expectNoHorizontalOverflow(page)
  })
}

test.describe('composer controls visual matrix', () => {
  test.skip(SHOTS_DIR == null, '设置 KK_COMPOSER_CONTROLS_SHOTS_DIR 才输出视觉矩阵截图')

  test('captures the 390 and 1440 composer control row in both locales', async ({ page }, info) => {
    fs.mkdirSync(SHOTS_DIR!, { recursive: true })

    for (const locale of LOCALES) {
      for (const viewport of VIEWPORTS) {
        await page.setViewportSize({ width: viewport.width, height: viewport.height })
        await openHarness(page, locale)
        const file = path.join(SHOTS_DIR!, `composer-controls-${locale}-${viewport.name}.png`)
        await page.screenshot({ path: file })
        await info.attach(`composer-controls-${locale}-${viewport.name}`, {
          path: file,
          contentType: 'image/png',
        })
      }
    }
  })
})
