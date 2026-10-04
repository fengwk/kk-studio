import { test, expect } from './fixture'

const HARNESS = '/browser-tests/settings-sync-harness.html'

test('sync tab exposes only the import and export actions', async ({ page }) => {
  await page.goto(HARNESS)
  await expect(page.getByRole('button', { name: '导入' })).toBeVisible()
  const exportButton = page.getByRole('button', { name: '导出' })
  await expect(exportButton).toBeEnabled()
  // 凭据提醒只在导出弹窗内，页签正文保持简短。
  await expect(page.getByText('配置文件包含密钥，请妥善保管。')).toHaveCount(0)
})

test('export modal groups agents/models/providers together and defaults to the full selection', async ({
  page,
}) => {
  await page.goto(HARNESS)
  await page.getByRole('button', { name: '导出' }).click()
  const dialog = page.getByRole('dialog', { name: '导出配置' })

  await expect(dialog.getByText('Agent、模型与提供商')).toBeVisible()
  await expect(dialog.getByText('配置文件包含密钥，请妥善保管。')).toBeVisible()
  // 7 个种类开关 + 10 个条目：不存在额外凭据开关。
  await expect(dialog.getByRole('checkbox')).toHaveCount(17)
  await expect(dialog.getByRole('checkbox', { name: /凭据|credential/i })).toHaveCount(0)
  for (const label of [
    'Agent: reviewer',
    'Agent: planner',
    '模型: openai/gpt-4o',
    '模型: anthropic/claude',
    '提供商: openai',
    '提供商: anthropic',
    '技能包: core-tools',
    '环境: dev-env',
    'MCP 服务: fetch',
    '设置: settings',
  ]) {
    await expect(dialog.getByRole('checkbox', { name: label, exact: true })).toBeChecked()
  }
  await expect(dialog.getByText('共 7 类 · 10 项（含依赖）')).toBeVisible()
})

test('dependencies follow the selected source and cannot be removed on their own', async ({ page }) => {
  await page.goto(HARNESS)
  await page.getByRole('button', { name: '导出' }).click()
  const dialog = page.getByRole('dialog', { name: '导出配置' })
  const model = dialog.getByRole('checkbox', { name: '模型: openai/gpt-4o', exact: true })

  // 取消勾选 Model，但它仍被选中的 Agent 依赖，必须保持勾选且锁定。
  await model.click()
  await expect(model).toBeChecked()
  await expect(model).toBeDisabled()
  await expect(dialog.getByText('依赖').first()).toBeVisible()

  // 取消唯一来源后，依赖随之释放。
  await dialog.getByRole('checkbox', { name: 'Agent: reviewer', exact: true }).click()
  await dialog.getByRole('checkbox', { name: 'Agent: planner', exact: true }).click()
  await expect(model).not.toBeChecked()
})

test('export downloads the returned YAML as kk-studio-config.yaml', async ({ page }) => {
  await page.goto(HARNESS)
  await page.getByRole('button', { name: '导出' }).click()
  const dialog = page.getByRole('dialog', { name: '导出配置' })

  const [download] = await Promise.all([
    page.waitForEvent('download'),
    dialog.getByRole('button', { name: '导出' }).click(),
  ])
  expect(download.suggestedFilename()).toBe('kk-studio-config.yaml')
  await expect(page.getByText('已导出 kk-studio-config.yaml')).toBeVisible()
  await expect(page.getByRole('dialog')).toHaveCount(0)
})

test('export sends only directly selected roots while the UI shows the full scope', async ({ page }) => {
  await page.goto(HARNESS)
  await page.getByRole('button', { name: '导出' }).click()
  const dialog = page.getByRole('dialog', { name: '导出配置' })

  for (const kind of ['Agent', '模型', '提供商', '技能包', '环境', 'MCP 服务', '设置']) {
    await dialog.getByRole('checkbox', { name: kind, exact: true }).click()
  }
  await dialog.getByRole('checkbox', { name: 'Agent: reviewer', exact: true }).click()
  // UI 展示完整闭包（reviewer + 4 个依赖，覆盖 5 个种类）。
  await expect(dialog.getByText('共 5 类 · 5 项（含依赖）')).toBeVisible()

  const [download] = await Promise.all([
    page.waitForEvent('download'),
    dialog.getByRole('button', { name: '导出' }).click(),
  ])
  expect(download.suggestedFilename()).toBe('kk-studio-config.yaml')
  // 提交的只有直接勾选的 root，依赖由后端补齐。
  expect(await page.evaluate(() => window.__syncExportRequest)).toEqual([
    { kind: 'agents', name: 'reviewer' },
  ])
})

test('import prechecks the file and requires explicit partial confirmation', async ({ page }) => {
  await page.goto(HARNESS)
  await page.locator('.settings-sync-file-input').setInputFiles({
    name: 'kk-studio-config.yaml',
    mimeType: 'application/yaml',
    buffer: Buffer.from('providers: []\n'),
  })
  const dialog = page.getByRole('dialog', { name: '导入配置' })
  // 选择文件只做预检查，确认前不执行导入。
  await expect(dialog.getByText('导入前请确认以下变更计划。')).toBeVisible()
  await expect(dialog.getByText('文件：kk-studio-config.yaml')).toBeVisible()
  await expect(dialog.getByText('将新增')).toBeVisible()
  await expect(dialog.getByText('将覆盖')).toBeVisible()
  await expect(dialog.getByText('将跳过')).toBeVisible()
  await expect(dialog.getByText('Agent: broken — unsupported tool')).toBeVisible()
  await expect(dialog.getByRole('button', { name: '确认导入' })).toHaveCount(0)
  expect(await page.evaluate(() => window.__syncImportYaml)).toBeUndefined()

  await dialog.getByRole('button', { name: '仅导入可用配置' }).click()
  await expect(dialog.getByText('已导入')).toBeVisible()
  await expect(dialog.getByText('已跳过')).toBeVisible()
  // YAML 内容提交给后端，但绝不渲染到页面。
  expect(await page.evaluate(() => window.__syncImportYaml)).toBe('providers: []\n')
  expect(await page.evaluate(() => window.__syncImportAllowPartial)).toBe(true)
  await expect(dialog.getByText('providers: []')).toHaveCount(0)
})

test('import confirms a fully importable plan without partial authorization', async ({ page }) => {
  await page.goto(HARNESS)
  await page.locator('#preview-mode').selectOption('full')
  await page.locator('.settings-sync-file-input').setInputFiles({
    name: 'kk.yaml',
    mimeType: 'application/yaml',
    buffer: Buffer.from('providers: []\n'),
  })
  const dialog = page.getByRole('dialog', { name: '导入配置' })
  await expect(dialog.getByRole('button', { name: '仅导入可用配置' })).toHaveCount(0)
  await dialog.getByRole('button', { name: '确认导入' }).click()

  await expect(dialog.getByText('已导入')).toBeVisible()
  expect(await page.evaluate(() => window.__syncImportAllowPartial)).toBe(false)
})

test('import with no usable items offers no execute action', async ({ page }) => {
  await page.goto(HARNESS)
  await page.locator('#preview-mode').selectOption('empty')
  await page.locator('.settings-sync-file-input').setInputFiles({
    name: 'kk.yaml',
    mimeType: 'application/yaml',
    buffer: Buffer.from('agents: []\n'),
  })
  const dialog = page.getByRole('dialog', { name: '导入配置' })
  await expect(dialog.getByText('此文件没有可导入的配置。')).toBeVisible()
  await expect(dialog.getByRole('button', { name: '确认导入' })).toHaveCount(0)
  await expect(dialog.getByRole('button', { name: '仅导入可用配置' })).toHaveCount(0)
  await expect(dialog.getByRole('button', { name: '取消' })).toBeVisible()

  await dialog.getByRole('button', { name: '取消' }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  expect(await page.evaluate(() => window.__syncImportYaml)).toBeUndefined()
})

test('import check failure rejects with the settings alert and no confirm dialog', async ({ page }) => {
  await page.goto(HARNESS)
  await page.locator('#preview-mode').selectOption('fail')
  await page.locator('.settings-sync-file-input').setInputFiles({
    name: 'kk.yaml',
    mimeType: 'application/yaml',
    buffer: Buffer.from('providers: []\n'),
  })

  await expect(page.getByRole('alert')).toContainText('unsupported file structure')
  await expect(page.getByRole('dialog')).toHaveCount(0)
  expect(await page.evaluate(() => window.__syncImportYaml)).toBeUndefined()
})

test('import dialog closes on Escape from the preview', async ({ page }) => {
  await page.goto(HARNESS)
  await page.locator('.settings-sync-file-input').setInputFiles({
    name: 'kk.yaml',
    mimeType: 'application/yaml',
    buffer: Buffer.from('providers: []\n'),
  })
  const dialog = page.getByRole('dialog', { name: '导入配置' })
  await expect(dialog).toBeVisible()
  await page.keyboard.press('Escape')

  await expect(page.getByRole('dialog')).toHaveCount(0)
  expect(await page.evaluate(() => window.__syncImportYaml)).toBeUndefined()
})

test('import keeps an unsaved settings draft visible with an explicit reload affordance', async ({
  page,
}) => {
  await page.goto(HARNESS)
  await page.getByRole('button', { name: 'Dirty draft: false' }).click()
  await page.getByRole('button', { name: 'Dirty draft: true' }).waitFor()
  await page.locator('.settings-sync-file-input').setInputFiles({
    name: 'kk.yaml',
    mimeType: 'application/yaml',
    buffer: Buffer.from('providers: []\n'),
  })
  const dialog = page.getByRole('dialog', { name: '导入配置' })
  await dialog.getByRole('button', { name: '仅导入可用配置' }).click()

  await expect(dialog.getByText('设置中有未保存的更改，将保持原样。')).toBeVisible()
  await expect(dialog.getByRole('button', { name: '重新加载设置' })).toBeVisible()
})

for (const width of [1100, 360]) {
  test(`sync import dialog stays within the viewport at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 640 })
    await page.goto(HARNESS)
    await page.locator('.settings-sync-file-input').setInputFiles({
      name: 'kk.yaml',
      mimeType: 'application/yaml',
      buffer: Buffer.from('providers: []\n'),
    })
    const dialog = page.getByRole('dialog', { name: '导入配置' })
    const box = (await dialog.boundingBox())!

    expect(box.x).toBeGreaterThanOrEqual(0)
    expect(box.x + box.width).toBeLessThanOrEqual(width + 1)
    // 长清单在弹窗内滚动，底部操作始终可达。
    await expect(dialog.getByRole('button', { name: '仅导入可用配置' })).toBeVisible()
    const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth)
    expect(scrollWidth).toBeLessThanOrEqual(width + 1)
  })
}

for (const width of [1100, 360]) {
  test(`sync export dialog stays within the viewport at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 640 })
    await page.goto(HARNESS)
    await page.getByRole('button', { name: '导出' }).click()
    const dialog = page.getByRole('dialog', { name: '导出配置' })
    const box = (await dialog.boundingBox())!

    expect(box.x).toBeGreaterThanOrEqual(0)
    expect(box.x + box.width).toBeLessThanOrEqual(width + 1)
    // 长列表在弹窗内滚动，底部操作始终可达。
    await expect(dialog.getByRole('button', { name: '导出', exact: true })).toBeVisible()
    const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth)
    expect(scrollWidth).toBeLessThanOrEqual(width + 1)
  })
}

test('renders the English copy consistently when the locale switches', async ({ page }) => {
  await page.goto(HARNESS)
  await page.getByRole('button', { name: /Locale: zh-CN/ }).click()
  await expect(page.getByRole('button', { name: 'Import' })).toBeVisible()
  await page.getByRole('button', { name: 'Export' }).click()
  const dialog = page.getByRole('dialog', { name: 'Export configuration' })
  await expect(dialog.getByText('The configuration file contains credentials; please keep it safe.')).toBeVisible()
  await expect(dialog.getByText('Agents, Models & Providers')).toBeVisible()
})
