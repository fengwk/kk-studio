import { expect, test } from './fixture'
import type { Page } from './fixture'

const HARNESS = '/browser-tests/markdown-table-harness.html'

async function renderDiagram(page: Page, source: string, options = '') {
  await page.goto(`${HARNESS}?mermaid=${encodeURIComponent(source)}${options}`)
  const svg = page.locator('.md-mermaid svg')
  await expect(svg).toBeVisible()
  return svg
}

test.describe('Mermaid rendering and sanitization', () => {
  test('renders HTML labels as plain diagram text', async ({ page }) => {
    // KChart 输出纯 SVG：标签里的 HTML 只保留文字，不会作为元素进入页面。
    const svg = await renderDiagram(page, 'flowchart TD\n A["<b>Input</b>"] --> B["Output"]')
    await expect(svg).toContainText('Input')
    await expect(svg).toContainText('Output')
    await expect(svg.locator('b')).toHaveCount(0)
    await expect(svg).not.toContainText('<b>')
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

  test('keeps the top label inside a frameless Mermaid radar chart', async ({ page }, info) => {
    const svg = await renderDiagram(page, [
      'radar-beta',
      'axis a[Top], b[Right], c[Bottom], d[Left]',
      'curve c1[Current]{4, 3, 2, 1}',
    ].join('\n'))
    const topLabel = svg.getByText('Top', { exact: true })
    await expect(topLabel).toBeVisible()
    expect((await topLabel.boundingBox())!.y).toBeGreaterThanOrEqual((await svg.boundingBox())!.y)
    await page.locator('.md-mermaid-shell').screenshot({ path: info.outputPath('mermaid-valid.png') })
  })

  test('keeps the source and shows the syntax error location and fix', async ({ page }, info) => {
    const source = 'flowchart TD\nA -->'
    await page.goto(`${HARNESS}?mermaid=${encodeURIComponent(source)}&locale=zh-CN`)
    await expect(page.locator('.md-mermaid svg')).toHaveCount(0)
    const code = page.locator('code.language-mermaid')
    const error = page.locator('.md-mermaid-error')
    await expect(code).toHaveText(source)
    await expect(error).toHaveText('无法渲染此图。第 2 行：连线后面缺少节点。在连线运算符后写节点 id。')
    await code.scrollIntoViewIfNeeded()
    await error.scrollIntoViewIfNeeded()
    await page.screenshot({ path: info.outputPath('mermaid-invalid.png') })
  })

  for (const locale of ['zh-CN', 'en-US']) {
    for (const width of [390, 1440]) {
      test(`places Mermaid copy above the diagram in ${locale} at ${width}px`, async ({ page }, info) => {
        // 即使图在窄屏横向滚动，复制按钮也不会遮住参与者或图内文字。
        await page.setViewportSize({ width, height: width === 390 ? 844 : 900 })
        const svg = await renderDiagram(page, [
          'sequenceDiagram',
          'participant A as Kubelet',
          'participant B as Service',
          'participant C as Database',
          'A->>B: SIGTERM',
          'B->>C: pool.end()',
        ].join('\n'), `&code&locale=${locale}`)
        const shell = page.locator('.md-mermaid-shell')
        const copy = shell.locator('.md-code-copy')
        await copy.focus()
        await expect(copy).toHaveCSS('opacity', '1')
        const buttonBox = (await copy.boundingBox())!
        const svgBox = (await svg.boundingBox())!
        expect(buttonBox.y + buttonBox.height).toBeLessThanOrEqual(svgBox.y)
        await shell.screenshot({ path: info.outputPath(`mermaid-copy-${locale}-${width}.png`) })
      })
    }
  }

  test('removes executable label content before inserting the rendered SVG', async ({ page }) => {
    // 检查实际写入页面的 DOM，而非 mock 返回值；这不是特定公告利用条件的完整 PoC。
    await page.addInitScript(() => {
      Reflect.set(window, '__mermaidXss', false)
    })
    const svg = await renderDiagram(page, [
      'flowchart TD',
      'A["<b>Allowed</b><img src=missing onerror=window.__mermaidXss=true><script>window.__mermaidXss=true</script><a href=javascript:window.__mermaidXss=true>Unsafe link</a>"] --> B["Output"]',
    ].join('\n'))
    await expect(svg).toContainText('Allowed')
    await expect(svg).toContainText('Unsafe link')
    await expect(svg).toContainText('Output')
    await expect(svg.locator('script, img, a, foreignObject')).toHaveCount(0)
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

  test('keeps title and marker references local to each copy of an identical diagram', async ({ page }) => {
    // Two copies can reuse a layout, but their SVG IDs live in the same document.
    const source = 'flowchart LR\n A[Start] --> B[End]'
    const repeated = [source, '```', '', '```mermaid', source].join('\n')
    await page.goto(`${HARNESS}?mermaid=${encodeURIComponent(repeated)}`)
    const diagrams = page.locator('.md-mermaid svg')
    await expect(diagrams).toHaveCount(2)
    const groups = await diagrams.evaluateAll((roots) => roots.map((root) => ({
      ids: Array.from(root.querySelectorAll('[id]'), (item) => item.id),
      title: root.getAttribute('aria-labelledby'),
      markers: Array.from(root.querySelectorAll('[marker-end]'), (item) => item.getAttribute('marker-end')),
    })))
    for (const { ids, title, markers } of groups) {
      expect(ids).toContain(title)
      expect(markers.length).toBeGreaterThan(0)
      for (const ref of markers) expect(ids).toContain(ref?.match(/^url\(#(.+)\)$/)?.[1])
    }
    const ids = groups.flatMap((group) => group.ids)
    expect(new Set(ids).size).toBe(ids.length)
  })

  test('opens diagram links in a new tab', async ({ page }) => {
    // click 链接保留为普通链接，但与 Markdown 链接一样不能在当前页离开应用。
    const svg = await renderDiagram(page, 'flowchart LR\n A[Docs] --> B[API]\n click A "https://example.com/docs" "Open docs"')
    const link = svg.locator('a')
    await expect(link).toHaveAttribute('href', 'https://example.com/docs')
    await expect(link).toHaveAttribute('target', '_blank')
    await expect(link).toHaveAttribute('rel', 'noreferrer noopener')
  })

  test('fits a small diagram to its drawing and aligns it with the preceding prose', async ({ page }) => {
    // 紧凑图不能是占满消息列的 SVG（即使外层盒子与正文已对齐）。
    const svg = await renderDiagram(page, 'flowchart TD\n A[Start] --> B[End]')
    const prose = page.getByText('普通段落。', { exact: true })
    const layout = await svg.evaluate((element) => ({
      left: element.getBoundingClientRect().left,
      width: element.getBoundingClientRect().width,
      hostWidth: element.parentElement!.clientWidth,
    }))
    expect(Math.abs(layout.left - (await prose.boundingBox())!.x)).toBeLessThan(1)
    expect(layout.width).toBeLessThan(layout.hostWidth / 2)
  })

  test('keeps a diagram wider than the message at 1:1 and scrolls it inside its block', async ({ page }) => {
    // 缩放会让图中文字小于排版字号：放不下的图保持原尺寸，在图框内横向滚动，页面本身不加宽。
    const chain = Array.from({ length: 14 }, (_, index) => `S${index}[Stage ${index}]`).join(' --> ')
    const svg = await renderDiagram(page, `flowchart LR\n ${chain}`)
    const layout = await svg.evaluate((element) => {
      const host = element.parentElement as HTMLElement
      host.scrollLeft = 100
      return {
        scrollLeft: host.scrollLeft,
        width: Number(element.getAttribute('width')),
        drawn: element.getBoundingClientRect().width,
        hostWidth: host.clientWidth,
        hostScroll: host.scrollWidth,
        overflow: getComputedStyle(host).overflowX,
        pageScroll: document.documentElement.scrollWidth,
        viewport: document.documentElement.clientWidth,
      }
    })
    expect(Math.abs(layout.drawn - layout.width)).toBeLessThan(1)
    expect(layout.width).toBeGreaterThan(layout.hostWidth)
    expect(layout.hostScroll).toBeGreaterThan(layout.hostWidth)
    expect(layout.overflow).toBe('auto')
    expect(layout.scrollLeft).toBeGreaterThan(0)
    expect(layout.pageScroll).toBeLessThanOrEqual(layout.viewport)
  })
})
