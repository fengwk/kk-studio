import { test, expect } from './fixture'

const zones = [
  { timezoneId: 'Asia/Shanghai', instant: '2026-10-08 21:15', crossDay: '2026-10-08 09:15' },
  { timezoneId: 'UTC', instant: '2026-10-08 13:15', crossDay: '2026-10-08 01:15' },
  { timezoneId: 'America/New_York', instant: '2026-10-08 09:15', crossDay: '2026-10-07 21:15' },
]

for (const { timezoneId, instant, crossDay } of zones) {
  for (const width of [390, 1280]) {
    test(`${timezoneId}: Chat and Session/Thread local dates at ${width}px`, async ({
      browser, baseURL,
    }, testInfo) => {
      // Explicit contexts make the regression independent of the machine's timezone.
      const context = await browser.newContext({
        timezoneId, viewport: { width, height: 900 }, baseURL,
      })
      const errors: string[] = []
      context.on('weberror', (error) => errors.push(error.error().message))
      // No backend, model or other external service is allowed in this harness.
      await context.route('**/*', (route) => {
        const url = new URL(route.request().url())
        return url.origin === new URL(baseURL!).origin ? route.continue() : route.abort()
      })
      try {
        const page = await context.newPage()
        await page.goto('/browser-tests/local-time-harness.html')
        const expectedDates = [
          ['instant', instant], ['offset', instant], ['cross-day', crossDay],
          ['wall-clock', '2026-10-08 13:15'], ['empty', '-'], ['invalid', '-'],
          ['seconds', instant], ['milliseconds', instant],
        ]
        for (const [id, expected] of expectedDates) {
          const meta = page.getByTestId(`chat-${id}`).locator('.resource-card-meta-value').last()
          await expect(meta).toHaveText(expected)
          await expect(meta).toHaveAttribute('title', expected)
          if (id === 'seconds' || id === 'milliseconds') continue
          for (const [panel, suffix] of [['sessions', '1 Threads'], ['threads', 'IDLE']]) {
            const option = page.getByTestId(panel).getByRole('option')
              .filter({ has: page.locator('.thread-selection-item-title', { hasText: id }) })
            await expect(option.locator('.thread-selection-item-subtitle'))
              .toHaveText(`${expected} · ${suffix}`)
          }
        }
        // The compact timestamp remains on one line in the real card at both widths.
        const meta = page.getByTestId('chat-instant').locator('.resource-card-meta-value').last()
        expect(await meta.evaluate((element) => {
          const range = document.createRange()
          range.selectNodeContents(element)
          return range.getClientRects().length === 1 && element.scrollWidth <= element.clientWidth
        })).toBe(true)
        for (const panel of ['sessions', 'threads']) {
          await expect(page.getByTestId(panel).getByRole('option')
            .filter({ has: page.locator('.thread-selection-item-title', { hasText: 'array' }) })
            .locator('.thread-selection-item-subtitle')).toContainText('2026-10-08 13:15')
        }
        for (const id of ['instant', 'cross-day']) {
          const card = page.getByTestId(`chat-${id}`)
          await card.scrollIntoViewIfNeeded()
          await card.screenshot({ path: testInfo.outputPath(`chat-${id}.png`) })
        }
        await page.getByTestId('sessions').screenshot({ path: testInfo.outputPath('sessions.png') })
        await page.getByTestId('threads').screenshot({ path: testInfo.outputPath('threads.png') })
        expect(errors).toEqual([])
      } finally {
        await context.close()
      }
    })
  }
}
