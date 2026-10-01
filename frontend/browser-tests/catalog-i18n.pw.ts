import { expect, test } from '@playwright/test'

test.describe('Catalog & Skill Packages i18n Real React Browser Verification', () => {
  test('Model Form: Reasoning effort visible-disabled, responsive 2-col/stacked, preserved draft & aria hint', async ({
    page,
  }) => {
    // 1. 桌面端 1280x800
    await page.setViewportSize({ width: 1280, height: 800 })
    await page.goto('/browser-tests/catalog-i18n-harness.html')

    const modelTabBtn = page.locator('#tab-model-btn')
    await expect(modelTabBtn).toBeVisible()

    // 新建时默认 reasoning 为 false，Reasoning Effort 1 输入框始终渲染且为 disabled
    const reasoningCheckbox = page.getByRole('checkbox', { name: 'Reasoning' })
    await expect(reasoningCheckbox).not.toBeChecked()

    const effortInput = page.getByLabel('Reasoning Effort 1')
    await expect(effortInput).toBeVisible()
    await expect(effortInput).toBeDisabled()

    // 验证 aria-describedby 指向包含关闭原因的 hint
    const hintId = await effortInput.getAttribute('aria-describedby')
    expect(hintId).toBeTruthy()
    const hintEl = page.locator(`#${hintId}`)
    await expect(hintEl).toContainText('Reasoning 当前已关闭')
    await expect(hintEl).toContainText('勾选开启上方 Reasoning')

    // 桌面端 editor-grid 2 列布局：Variant ID 与 Reasoning Effort 处于同一水平行
    const variantIdInput = page.getByLabel('Variant ID 1')
    const boxIdDesktop = (await variantIdInput.boundingBox())!
    const boxEffortDesktop = (await effortInput.boundingBox())!
    expect(Math.abs(boxIdDesktop.y - boxEffortDesktop.y)).toBeLessThanOrEqual(4)
    expect(boxEffortDesktop.x).toBeGreaterThan(boxIdDesktop.x + boxIdDesktop.width - 4)

    // 2. 窄屏视口 500x800：editor-grid 响应式断点单列堆叠
    await page.setViewportSize({ width: 500, height: 800 })
    const boxIdNarrow = (await variantIdInput.boundingBox())!
    const boxEffortNarrow = (await effortInput.boundingBox())!
    expect(boxEffortNarrow.y).toBeGreaterThan(boxIdNarrow.y + boxIdNarrow.height - 4)

    // 恢复桌面端视口继续验证草稿保留
    await page.setViewportSize({ width: 1280, height: 800 })

    // 勾选开启 Reasoning
    await reasoningCheckbox.click()
    await expect(reasoningCheckbox).toBeChecked()
    await expect(effortInput).toBeEnabled()
    await expect(effortInput).not.toHaveAttribute('aria-describedby')

    // 填写思考强度
    await effortInput.fill('high')
    await expect(effortInput).toHaveValue('high')

    // 再次关闭 Reasoning：输入框置灰但保留已填写内容
    await reasoningCheckbox.click()
    await expect(reasoningCheckbox).not.toBeChecked()
    await expect(effortInput).toBeDisabled()
    await expect(effortInput).toHaveValue('high')

    // 再次开启 Reasoning：输入框恢复可用，草稿值 'high' 完整恢复
    await reasoningCheckbox.click()
    await expect(reasoningCheckbox).toBeChecked()
    await expect(effortInput).toBeEnabled()
    await expect(effortInput).toHaveValue('high')
  })

  test('Ai Navigation: Skill Packages tab localized to 技能包 in zh-CN and Skill Packages in en-US', async ({
    page,
  }) => {
    await page.goto('/browser-tests/catalog-i18n-harness.html')
    await page.click('#tab-nav-btn')

    // 默认 zh-CN：二级导航中必须是“技能包”
    const skillPkgLink = page.getByRole('link', { name: '技能包' })
    await expect(skillPkgLink).toBeVisible()

    // 切换至 en-US
    await page.click('#toggle-locale-btn')
    const skillPkgEnLink = page.getByRole('link', { name: 'Skill Packages' })
    await expect(skillPkgEnLink).toBeVisible()
    await expect(page.getByRole('link', { name: '技能包' })).toHaveCount(0)

    // 切回 zh-CN
    await page.click('#toggle-locale-btn')
    await expect(page.getByRole('link', { name: '技能包' })).toBeVisible()
  })

  test('Skill Packages Page: zh-CN & en-US cards, status pills, labels, modal fields and cancel/delete interpolation', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 800 })
    await page.goto('/browser-tests/catalog-i18n-harness.html')
    await page.click('#tab-skills-btn')

    // 1. 中文卡片与状态展示
    await expect(page.getByRole('button', { name: '创建技能包' })).toBeVisible()
    await expect(page.getByText('核心开发技能集')).toBeVisible()

    // 状态 pill
    const pills = page.getByTestId('check-status-pill')
    await expect(pills.nth(0)).toHaveText('未检查')
    await expect(pills.nth(1)).toHaveText('有更新可用')
    await expect(pills.nth(2)).toHaveText('检查失败')

    // 标签与上游原始诊断信息
    await expect(page.getByText('包含技能数量').first()).toBeVisible()
    await expect(page.getByText('技能').first()).toBeVisible()
    await expect(page.getByText('错误')).toBeVisible()
    await expect(page.getByText('fatal: repository not reachable')).toBeVisible()

    // 2. 实时切换为 en-US
    await page.click('#toggle-locale-btn')
    await expect(page.getByRole('button', { name: 'Create Package' })).toBeVisible()
    await expect(pills.nth(0)).toHaveText('Unchecked')
    await expect(pills.nth(1)).toHaveText('Update available')
    await expect(pills.nth(2)).toHaveText('Check failed')
    await expect(page.getByText('Skills Count').first()).toBeVisible()
    await expect(page.getByText('Skills').first()).toBeVisible()
    await expect(page.getByText('Error', { exact: true })).toBeVisible()

    // 3. 创建弹窗与校验提示（en-US）
    await page.click('button:has-text("Create Package")')
    const createModal = page.getByRole('dialog', { name: 'Create Package' })
    await expect(createModal).toBeVisible()

    // 测试取消按钮（Cancel）
    const cancelBtn = createModal.getByRole('button', { name: 'Cancel' })
    await expect(cancelBtn).toBeVisible()
    await cancelBtn.click()
    await expect(createModal).toHaveCount(0)

    // 重新打开并测试非法名称符号校验
    await page.click('button:has-text("Create Package")')
    const nameInput = page.getByRole('dialog').getByPlaceholder('my-skills')
    await nameInput.fill('invalid:name@pkg')
    await page.getByRole('dialog').getByRole('button', { name: 'Create' }).click()
    const errorAlert = page.getByRole('dialog').getByRole('alert')
    await expect(errorAlert).toContainText('Package name cannot contain : / @ \\')
    await page.getByRole('dialog').getByRole('button', { name: 'Cancel' }).click()

    // 4. 删除弹窗确认描述双括号插值验证
    const deleteBtn = page.getByRole('button', { name: 'Delete Package core-tools' })
    await deleteBtn.click()
    const deleteModal = page.getByRole('alertdialog')
    await expect(deleteModal).toBeVisible()
    await expect(deleteModal).toContainText('Are you sure you want to delete skill package "core-tools"?')

    // 取消删除
    await deleteModal.getByRole('button', { name: 'Cancel' }).click()
    await expect(deleteModal).toHaveCount(0)

    // 5. 切回 zh-CN 验证删除弹窗中文插值
    await page.click('#toggle-locale-btn')
    const zhDeleteBtn = page.getByRole('button', { name: '删除技能包 core-tools' })
    await zhDeleteBtn.click()
    const zhDeleteModal = page.getByRole('alertdialog')
    await expect(zhDeleteModal).toBeVisible()
    await expect(zhDeleteModal).toContainText('确认删除技能包“core-tools”？')
    await zhDeleteModal.getByRole('button', { name: '取消' }).click()
    await expect(zhDeleteModal).toHaveCount(0)
  })

  // 中英文长标签在卡片固定标签列中换行，不能覆盖旁边的提交与仓库信息。
  test('skill package metadata labels fit their columns on desktop and narrow screens in both languages', async ({
    page,
  }) => {
    await page.goto('/browser-tests/catalog-i18n-harness.html')
    await page.locator('#tab-skills-btn').click()
    const labels = page.locator('.skill-package-card .meta-row .lbl')
    await expect(labels.first()).toBeVisible()

    for (const width of [1280, 500]) {
      await page.setViewportSize({ width, height: 900 })
      for (const locale of ['zh-CN', 'en-US']) {
        await expect(page.locator('#toggle-locale-btn')).toContainText(locale)
        const overflow = await labels.evaluateAll((elements) =>
          elements
            .filter((element) => element.scrollWidth > element.clientWidth + 1)
            .map((element) => element.textContent),
        )
        expect(overflow).toEqual([])
        await page.locator('#toggle-locale-btn').click()
      }
    }
  })
})
