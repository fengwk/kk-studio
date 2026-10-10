import { expect, test } from './fixture'

test('provider and system settings retry controls render correct theme, borders, focus rings, and interactions at 1280 and 390 viewports', async ({
  page,
}) => {
  for (const width of [1280, 390]) {
    await page.setViewportSize({ width, height: 900 })
    await page.goto('/browser-tests/retry-controls-harness.html')

    const providerSection = page.locator('section[aria-labelledby="provider-form-heading"]')
    const settingsSection = page.locator('section[aria-labelledby="system-settings-heading"]')

    await expect(providerSection).toBeVisible()
    await expect(settingsSection).toBeVisible()

    // 1. Verify computed border on embedded input fields is exactly 0 in both form contexts
    const providerTagInput = providerSection.locator('.ui-tag-input')
    const providerTagInputField = providerTagInput.locator('.ui-tag-input-field')
    const settingsTagInput = settingsSection.locator('.ui-tag-input')
    const settingsTagInputField = settingsTagInput.locator('.ui-tag-input-field')

    await expect(providerTagInputField).toBeVisible()
    await expect(settingsTagInputField).toBeVisible()

    const providerInputStyles = await providerTagInputField.evaluate((el) => {
      const computed = window.getComputedStyle(el)
      return {
        borderTopWidth: computed.borderTopWidth,
        borderRightWidth: computed.borderRightWidth,
        borderBottomWidth: computed.borderBottomWidth,
        borderLeftWidth: computed.borderLeftWidth,
        outlineWidth: computed.outlineWidth,
        boxShadow: computed.boxShadow,
      }
    })

    expect(providerInputStyles.borderTopWidth).toBe('0px')
    expect(providerInputStyles.borderRightWidth).toBe('0px')
    expect(providerInputStyles.borderBottomWidth).toBe('0px')
    expect(providerInputStyles.borderLeftWidth).toBe('0px')
    expect(providerInputStyles.outlineWidth).toBe('0px')
    expect(providerInputStyles.boxShadow).toBe('none')

    const settingsInputStyles = await settingsTagInputField.evaluate((el) => {
      const computed = window.getComputedStyle(el)
      return {
        borderTopWidth: computed.borderTopWidth,
        borderRightWidth: computed.borderRightWidth,
        borderBottomWidth: computed.borderBottomWidth,
        borderLeftWidth: computed.borderLeftWidth,
        outlineWidth: computed.outlineWidth,
        boxShadow: computed.boxShadow,
      }
    })

    expect(settingsInputStyles.borderTopWidth).toBe('0px')
    expect(settingsInputStyles.borderRightWidth).toBe('0px')
    expect(settingsInputStyles.borderBottomWidth).toBe('0px')
    expect(settingsInputStyles.borderLeftWidth).toBe('0px')
    expect(settingsInputStyles.outlineWidth).toBe('0px')
    expect(settingsInputStyles.boxShadow).toBe('none')

    // 2. Verify wrapper focus theme (border color green-primary, focus-ring box shadow)
    await providerTagInputField.focus()
    await expect(providerTagInput).toHaveCSS('border-top-color', 'rgb(113, 231, 154)')
    await expect(providerTagInput).toHaveCSS('box-shadow', /rgba\(113, 231, 154/)

    await settingsTagInputField.focus()
    await expect(settingsTagInput).toHaveCSS('border-top-color', 'rgb(113, 231, 154)')
    await expect(settingsTagInput).toHaveCSS('box-shadow', /rgba\(113, 231, 154/)

    // 3. Verify tag Enter and X removal interactions in ProviderForm
    await providerTagInputField.fill('502')
    await providerTagInputField.press('Enter')
    await expect(providerTagInput.getByText('502')).toBeVisible()

    const removeBtn408 = providerTagInput.getByLabel('移除 408')
    await expect(removeBtn408).toBeVisible()
    await removeBtn408.click()
    await expect(providerTagInput.getByText('408')).not.toBeVisible()

    // 4. Verify selection mode interaction in ProviderForm
    const retrySelectTrigger = providerSection.getByRole('button', { name: 'HTTP 错误重试策略' })
    await expect(retrySelectTrigger).toHaveAttribute('data-value', 'custom')

    // Switch to inherit
    await retrySelectTrigger.click()
    const inheritOption = page.getByRole('option', { name: '继承系统配置' })
    await inheritOption.click()

    await expect(retrySelectTrigger).toHaveAttribute('data-value', 'inherit')
    await expect(providerSection.locator('.ui-tag-input')).not.toBeVisible()
    await expect(
      providerSection.getByText('当前 Provider 继承系统全局配置的 HTTP 重试状态码名单。'),
    ).toBeVisible()

    // Switch back to custom
    await retrySelectTrigger.click()
    const customOption = page.getByRole('option', { name: '自定义重试名单' })
    await customOption.click()

    await expect(retrySelectTrigger).toHaveAttribute('data-value', 'custom')
    await expect(providerSection.locator('.ui-tag-input')).toBeVisible()

    // 5. Verify tag Enter and X removal interactions in SystemSettings
    await settingsTagInputField.fill('520')
    await settingsTagInputField.press('Enter')
    await expect(settingsTagInput.getByText('520')).toBeVisible()

    const settingsRemove408 = settingsTagInput.getByLabel('移除 408')
    await expect(settingsRemove408).toBeVisible()
    await settingsRemove408.click()
    await expect(settingsTagInput.getByText('408')).not.toBeVisible()

    // 6. Verify layout has no horizontal overflow
    const hasNoOverflow = await page.evaluate(() => {
      return document.documentElement.scrollWidth <= document.documentElement.clientWidth
    })
    expect(hasNoOverflow).toBe(true)

    // 7. Capture visual screenshots
    await page.screenshot({
      path: test.info().outputPath(`retry-controls-${width}.png`),
      fullPage: true,
    })
    await providerSection.screenshot({
      path: test.info().outputPath(`provider-retry-section-${width}.png`),
    })
    await settingsSection.screenshot({
      path: test.info().outputPath(`settings-retry-section-${width}.png`),
    })
  }
})
