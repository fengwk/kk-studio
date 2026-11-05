import { fileURLToPath } from 'node:url'
import { expect, test, type Page } from './fixture'

/** 截图保存到 workspace 记录目录，不进入产物仓库。 */
const SHOTS = fileURLToPath(new URL('../../../screenshots', import.meta.url))

const archive = {
  name: 'upload-pack.zip',
  mimeType: 'application/zip',
  buffer: Buffer.from('PK\x05\x06' + '\0'.repeat(18), 'binary'),
}

function card(page: Page, packageName: string) {
  return page.locator('article.resource-card').filter({ hasText: packageName })
}

test.describe('Skill Packages real browser verification', () => {
  test('creates a Git package from the repository source', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 })
    await page.goto('/browser-tests/skill-packages-harness.html')

    await expect(card(page, 'core-tools')).toContainText('已是最新')
    // 每张卡只有一个主按钮：有更新时是“更新到 xxx”，否则是“检查更新”。
    await expect(card(page, 'browser-skills').getByRole('button', { name: /更新到 9999999999/ })).toBeVisible()
    await expect(card(page, 'core-tools').getByRole('button', { name: '检查更新 core-tools' })).toBeVisible()
    expect(await card(page, 'browser-skills').locator('button.btn-primary').count()).toBe(1)
    await page.screenshot({ path: `${SHOTS}/skill-packages-list-desktop-1280.png`, fullPage: true })

    await page.getByRole('button', { name: '创建技能包' }).click()
    const dialog = page.getByRole('dialog', { name: '创建技能包' })
    await expect(dialog).toBeVisible()
    await expect(dialog.getByRole('tab', { name: 'Git 仓库' })).toHaveAttribute('aria-selected', 'true')

    await dialog.getByPlaceholder('my-skills').fill('new-git-pack')
    await dialog.getByPlaceholder('https://github.com/org/repo.git').fill('https://github.com/example/new-git-pack.git')
    await dialog.getByPlaceholder('main').fill('main')
    await dialog.getByRole('button', { name: '确认创建' }).click()

    await expect(page.getByRole('dialog', { name: '创建技能包' })).toHaveCount(0)
    await expect(card(page, 'new-git-pack')).toContainText('Git 仓库')
    await expect(card(page, 'new-git-pack')).toContainText('1111111111')
    await expect(card(page, 'new-git-pack').getByRole('button', { name: '检查更新 new-git-pack' })).toBeVisible()
    await page.screenshot({ path: `${SHOTS}/skill-packages-created-git-1280.png`, fullPage: true })

    // 检查更新后主按钮切换为“更新到 <observed head>”，仍然只有一个主按钮。
    await card(page, 'new-git-pack').getByRole('button', { name: '检查更新 new-git-pack' }).click()
    await expect(card(page, 'new-git-pack').getByText('有更新可用')).toBeVisible()
    await expect(card(page, 'new-git-pack').getByRole('button', { name: /更新到 9999999999 new-git-pack/ })).toBeVisible()
    expect(await card(page, 'new-git-pack').locator('button.btn-primary').count()).toBe(1)
  })

  test('uploads an archive with real progress, previews the changes and publishes', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 })
    await page.goto('/browser-tests/skill-packages-harness.html')

    await page.getByRole('button', { name: '创建技能包' }).click()
    const dialog = page.getByRole('dialog', { name: '创建技能包' })
    await dialog.getByRole('tab', { name: '上传压缩包' }).click()
    await dialog.getByPlaceholder('my-skills').fill('upload-pack')
    await dialog.getByPlaceholder('1.0.0').fill('2026.09.1')
    await dialog.getByLabel('技能包压缩包').setInputFiles(archive)
    await expect(dialog.getByText('upload-pack.zip · 22 B')).toBeVisible()

    await dialog.getByRole('button', { name: '解析预览' }).click()

    // 校验与打包只呈现阶段状态；上传阶段呈现真实进度事件驱动的百分比。
    const progress = dialog.getByRole('progressbar', { name: '上传进度' })
    await expect(progress).toBeVisible()
    await expect(dialog.getByRole('status')).toContainText('正在上传归档')
    await expect
      .poll(async () => Number(await progress.getAttribute('value')))
      .toBeGreaterThan(0)
    await page.screenshot({ path: `${SHOTS}/skill-packages-uploading-desktop-1280.png` })

    const preview = page.getByRole('dialog', { name: '预览并发布' })
    await expect(preview).toBeVisible({ timeout: 15000 })
    await expect(preview).toContainText('归档预览')
    await expect(preview.getByText('文件数')).toBeVisible()
    await expect(preview.getByText('解压后大小')).toBeVisible()
    await expect(preview).toContainText('browser automation')
    await expect(preview).toContainText('未打包目录：docs')
    // 变化摘要：新增 browser，移除的 dropped 仍被 Agent 引用。
    await expect(preview).toContainText('browser')
    await expect(preview.getByText('dropped').first()).toBeVisible()
    await expect(preview).toContainText('仍有 Agent 引用这些技能时发布会失败，请先调整对应 Agent。')
    // 预览区没有第二个 footer，发布只有一处入口。
    expect(await preview.getByRole('button', { name: '发布', exact: true }).count()).toBe(1)
    await page.screenshot({ path: `${SHOTS}/skill-packages-upload-preview-1280.png`, fullPage: true })

    await preview.getByRole('button', { name: '发布', exact: true }).click()

    await expect(page.getByRole('dialog', { name: '预览并发布' })).toHaveCount(0)
    await expect(card(page, 'upload-pack')).toContainText('上传压缩包')
    await expect(card(page, 'upload-pack')).toContainText('2026.09.1')
    await page.screenshot({ path: `${SHOTS}/skill-packages-published-desktop-1280.png`, fullPage: true })
  })

  test('shows the real archive tree, environment sync states and archive download', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 })
    await page.goto('/browser-tests/skill-packages-harness.html')

    await card(page, 'upload-pack').getByRole('button', { name: '详情 upload-pack' }).click()
    const detail = page.getByRole('dialog', { name: /详情: upload-pack/ })
    await expect(detail).toBeVisible()

    // 文件树来自服务端归档索引：目录层级、文件名与解压后文件数都是真实路径。
    await expect(detail.getByText('SKILL.md')).toHaveCount(2)
    await expect(detail.locator('.skill-tree-name', { hasText: 'dropped' }).first()).toBeVisible()
    await expect(detail).toContainText('共 2 个文件')

    // 各环境同步状态：已同步、同步失败、待同步。
    await expect(detail.getByText('NAS', { exact: true })).toBeVisible()
    await expect(detail.getByText('已同步')).toBeVisible()
    await expect(detail.getByText('同步失败')).toBeVisible()
    await expect(detail.getByText('archive download timed out')).toBeVisible()
    await expect(detail.getByText('待同步')).toBeVisible()
    await page.screenshot({ path: `${SHOTS}/skill-packages-detail-desktop-1280.png`, fullPage: true })

    await detail.getByRole('button', { name: '下载归档' }).click()
    await expect
      .poll(async () => page.evaluate(() => Number((window as unknown as Record<string, unknown>).__skillArchiveRequests ?? 0)))
      .toBe(1)

    await detail.getByRole('button', { name: '重新同步 NAS 备份' }).click()
    await expect(detail.getByText('archive download timed out')).toBeVisible()
  })

  test('rejects deleting a package that is still referenced by an agent', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 })
    await page.goto('/browser-tests/skill-packages-harness.html')

    await card(page, 'agent-used').getByRole('button', { name: '详情 agent-used' }).click()
    const detail = page.getByRole('dialog', { name: /详情: agent-used/ })
    await expect(detail).toBeVisible()
    await detail.getByRole('button', { name: '删除技能包 agent-used' }).click()

    const confirm = page.getByRole('alertdialog')
    await expect(confirm).toContainText('确认删除技能包“agent-used”？')
    await confirm.getByRole('button', { name: '删除技能包' }).click()

    // 拒绝原因来自后端 errors.detail，弹窗保持打开。
    await expect(confirm).toContainText('Skill package is still referenced by Agent demo-agent')
    await expect(confirm).toBeVisible()
    await page.screenshot({ path: `${SHOTS}/skill-packages-delete-rejected-1280.png`, fullPage: true })

    await confirm.getByRole('button', { name: '取消' }).click()
    await expect(confirm).toHaveCount(0)
    await expect(detail).toBeVisible()
    await expect(card(page, 'agent-used')).toBeVisible()
  })

  test('keeps the list and dialogs usable on a 390px viewport', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto('/browser-tests/skill-packages-harness.html')

    await expect(card(page, 'core-tools')).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true)
    await page.screenshot({ path: `${SHOTS}/skill-packages-list-mobile-390.png`, fullPage: true })

    await page.getByRole('button', { name: '创建技能包' }).click()
    const dialog = page.getByRole('dialog', { name: '创建技能包' })
    await expect(dialog).toBeVisible()
    await dialog.getByRole('tab', { name: '上传压缩包' }).click()
    await dialog.getByPlaceholder('my-skills').fill('mobile-pack')
    await expect(dialog.getByPlaceholder('1.0.0')).toBeVisible()

    // 弹窗触区不小于 44px，正文可滚动且没有横向溢出。
    const footerButtons = dialog.locator('.modal-footer button')
    const boxes = await footerButtons.evaluateAll((elements) =>
      elements.map((element) => element.getBoundingClientRect().height))
    expect(boxes.every((height) => height >= 44)).toBe(true)
    expect(await dialog.evaluate((element) => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
    await page.screenshot({ path: `${SHOTS}/skill-packages-create-mobile-390.png` })

    await dialog.getByLabel('技能包压缩包').setInputFiles(archive)
    await dialog.getByPlaceholder('1.0.0').fill('1.0.0')
    await dialog.getByRole('button', { name: '解析预览' }).click()

    const preview = page.getByRole('dialog', { name: '预览并发布' })
    await expect(preview).toBeVisible({ timeout: 15000 })
    expect(await preview.evaluate((element) => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
    await page.screenshot({ path: `${SHOTS}/skill-packages-preview-mobile-390.png` })
  })
})
