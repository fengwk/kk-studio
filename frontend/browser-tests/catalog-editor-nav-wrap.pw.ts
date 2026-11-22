import { expect, test, type Locator } from './fixture'

/**
 * Agent 编辑弹窗区块导航的换行回归。
 *
 * <p>导航列只有 148px：中文标签只允许在「 / 」分隔处换行，「子 Agent」这类术语不能被拆成
 * 「子」+「Agent」；英文标签按单词换行，不省略、不出列。窄屏导航是横向跳转条，不换行。
 */

interface CapabilityLink {
  text: string
  /** 不可拆分的术语：换行后任何一行都不允许出现它的前半或后半。 */
  term: string | null
}

const CAPABILITY_LINK: Record<'zh-CN' | 'en-US', CapabilityLink> = {
  'zh-CN': { text: '工具 / 技能 / 子 Agent', term: '子 Agent' },
  'en-US': { text: 'Tools, skills and subagents', term: null },
}

/** 逐字符取行盒并还原每个视觉行，用来判断换行实际落在哪里。 */
async function visualLines(locator: Locator): Promise<string[]> {
  return locator.evaluate((element) => {
    const lines = new Map<number, string>()
    const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT)
    while (walker.nextNode()) {
      const node = walker.currentNode as Text
      for (let index = 0; index < node.length; index++) {
        const range = document.createRange()
        range.setStart(node, index)
        range.setEnd(node, index + 1)
        const box = range.getClientRects()[0]
        if (!box) {
          continue
        }
        // 半像素差异归并到同一条视觉行。
        const top = Math.round(box.top / 2) * 2
        lines.set(top, (lines.get(top) ?? '') + node.data[index])
      }
    }
    return [...lines.entries()]
      .sort((left, right) => left[0] - right[0])
      .map(([, line]) => line.trim())
      .filter((line) => line !== '')
  })
}

for (const locale of ['zh-CN', 'en-US'] as const) {
  for (const [width, height] of [[1440, 900], [390, 844]] as const) {
    test(`section navigation wraps only between separators in ${locale} at ${width}x${height}`, async ({ page }, info) => {
      await page.setViewportSize({ width, height })
      await page.goto(`/browser-tests/catalog-editor-harness.html?scenario=agent${locale === 'en-US' ? '&locale=en-US' : ''}`)
      const dialog = page.getByRole('dialog')
      const nav = dialog.getByRole('navigation')
      const link = nav.getByRole('button', { name: /子 Agent|subagents/ })
      const expected = CAPABILITY_LINK[locale]

      // 视觉行拼回来必须与标签一致：没有字被吞掉或重复。
      const label = (await link.textContent())!.replace(/\u00A0/g, ' ').replace(/\s+/g, ' ').trim()
      expect(label).toBe(expected.text)
      const lines = await visualLines(link)
      expect(lines.join(' ').replace(/\s+/g, ' ')).toBe(label)

      // 术语不拆：含「子」的那一行必须同时含「Agent」，术语不会被折算到两行。
      if (expected.term) {
        const [firstHalf, secondHalf] = [expected.term.slice(0, 1), expected.term.split(' ').at(-1)!]
        const termLines = lines.filter((line) => line.includes(firstHalf))
        expect(termLines).toHaveLength(1)
        expect(termLines[0]).toContain(secondHalf)
      }

      if (width >= 1440) {
        // 宽屏导航是窄列：标签在列内换行，不横向溢出到表单列。
        expect(await nav.evaluate((element) => getComputedStyle(element).flexDirection)).toBe('column')
        expect(await link.evaluate((element) => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
        expect(await nav.evaluate((element) => element.scrollWidth <= element.clientWidth + 1)).toBe(true)
      } else {
        // 窄屏导航是横向跳转条：标签整体不折行，靠横向滚动可达。
        expect(await nav.evaluate((element) => getComputedStyle(element).flexDirection)).toBe('row')
        expect(lines).toHaveLength(1)
      }

      await page.screenshot({ path: info.outputPath(`catalog-editor-nav-${locale}-${width}x${height}.png`) })
    })
  }
}
