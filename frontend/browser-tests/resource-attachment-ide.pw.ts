import { expect, test } from '@playwright/test'

const RESOURCE_ROUTE = '/browser-tests/resource-attachment-ide-harness.html'

test('renders Open in IDE for a private text resource and shows pending feedback', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 800 })
  await page.goto(RESOURCE_ROUTE)

  const openButton = page.getByRole('button', { name: 'Open in IDE' })
  await expect(openButton).toBeVisible()
  await expect(openButton).toHaveAttribute('title', /read-only UTF-8 text copy/)
  await openButton.click()
  await expect(page.getByRole('status')).toHaveText('Opening in IDE…')
  await expect(page.getByRole('button', { name: 'Opening in IDE…' })).toBeDisabled()
  await expect(page.getByRole('button', { name: 'Open in IDE' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Download' })).toBeVisible()
})

test('localizes the bounded-text failure returned by the attachment action', async ({ page }) => {
  await page.goto(`${RESOURCE_ROUTE}?fail=1`)
  await page.getByRole('button', { name: 'Open in IDE' }).click()
  await expect(page.getByRole('alert')).toHaveText(
    'This text attachment exceeds the IDE preview size limit.',
  )
})
