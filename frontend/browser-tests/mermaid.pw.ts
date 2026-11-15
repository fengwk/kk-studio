import { expect, test } from './fixture'
import type { Page } from './fixture'

const HARNESS = '/browser-tests/markdown-table-harness.html'

async function renderDiagram(page: Page, source: string) {
  await page.goto(`${HARNESS}?mermaid=${encodeURIComponent(source)}`)
  const svg = page.locator('.md-mermaid svg')
  await expect(svg).toBeVisible()
  return svg
}

test.describe('Mermaid rendering and sanitization', () => {
  test('renders a flowchart while preserving safe HTML labels', async ({ page }) => {
    // 使用真实 Mermaid/DOMPurify，防止安全补丁破坏已支持的 HTML 标签。
    const svg = await renderDiagram(page, 'flowchart TD\n A["<b>Input</b>"] --> B["Output"]')
    await expect(svg.locator('b')).toHaveText('Input')
    await expect(svg).toContainText('Output')
  })

  test('renders a sequence diagram through the shared Markdown component', async ({ page }) => {
    const svg = await renderDiagram(page, [
      'sequenceDiagram',
      'participant A as Alice',
      'participant B as Bob',
      'A->>B: hello',
    ].join('\n'))
    await expect(svg).toContainText('Alice')
    await expect(svg).toContainText('Bob')
    await expect(svg).toContainText('hello')
  })

  test('removes executable label content before inserting the rendered SVG', async ({ page }) => {
    // 检查实际写入页面的 DOM，而非 mock 返回值；这不是特定公告利用条件的完整 PoC。
    await page.addInitScript(() => {
      Reflect.set(window, '__mermaidXss', false)
    })
    const svg = await renderDiagram(page, [
      'flowchart TD',
      'A["<b>Allowed</b><img src=missing onerror=window.__mermaidXss=true><script>window.__mermaidXss=true</script><a href=javascript:window.__mermaidXss=true>Unsafe link</a>"] --> B["Output"]',
    ].join('\n'))
    await expect(svg.locator('b')).toHaveText('Allowed')
    await expect(svg).toContainText('Output')
    await expect(svg.locator('script')).toHaveCount(0)
    const executableAttributes = await svg.evaluate((root) =>
      [root, ...root.querySelectorAll('*')].flatMap((element) =>
        Array.from(element.attributes)
          .filter((attribute) =>
            /^on/i.test(attribute.name)
            || (['href', 'src'].includes(attribute.localName) && /^\s*javascript:/i.test(attribute.value)),
          )
          .map((attribute) => `${attribute.name}=${attribute.value}`),
      ),
    )
    expect(executableAttributes).toEqual([])
    expect(await page.evaluate(() => Reflect.get(window, '__mermaidXss'))).toBe(false)
  })
})
