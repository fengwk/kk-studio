import fs from 'node:fs'
import path from 'node:path'
import type { Locator, Page, TestInfo } from '@playwright/test'

/**
 * 共享 UI 视觉回归的截图开关：只有显式设置 `KK_SHARED_UI_SHOTS_DIR` 才落盘，
 * 默认只跑几何/样式断言，不在仓库内产生截图。
 */
const shotsDir = process.env.KK_SHARED_UI_SHOTS_DIR

/** 按指定目录保存截图；目录未配置时什么也不做。 */
export async function shoot(
  page: Page,
  testInfo: TestInfo,
  name: string,
): Promise<void> {
  if (!shotsDir) {
    return
  }
  const file = path.join(shotsDir, `${name}.png`)
  fs.mkdirSync(path.dirname(file), { recursive: true })
  await page.screenshot({ path: file })
  await testInfo.attach(name, { path: file, contentType: 'image/png' })
}

export interface SpacingMetrics {
  /** 计算样式的列间距；normal 记为 0。 */
  columnGap: number
  /** 同一行相邻可见动作之间的最小水平间距；不足两个动作时为 NaN。 */
  minHorizontalGap: number
}

/** 读取弹窗底栏这类横向动作区的真实间距：既看声明间距，也看相邻按钮的实际几何。 */
export function spacingMetrics(locator: Locator): Promise<SpacingMetrics> {
  return locator.evaluate((root) => {
    const style = getComputedStyle(root)
    const readGap = (value: string) => (value === 'normal' ? 0 : Number.parseFloat(value))
    const rects = Array.from(root.querySelectorAll('button, a'))
      .map((element) => element.getBoundingClientRect())
      .filter((rect) => rect.width > 0 && rect.height > 0)
      .sort((left, right) => left.left - right.left)
    let minHorizontalGap = Number.NaN
    if (rects.length > 1) {
      for (let index = 1; index < rects.length; index += 1) {
        for (const other of rects.slice(0, index)) {
          const current = rects[index]
          if (current.top >= other.bottom || other.top >= current.bottom) continue
          const gap = current.left - other.right
          minHorizontalGap = Number.isNaN(minHorizontalGap) ? gap : Math.min(minHorizontalGap, gap)
        }
      }
    }
    return {
      columnGap: readGap(style.columnGap),
      minHorizontalGap,
    }
  })
}

/**
 * 测量每个 tab 内“图标右边界 → 首个文本节点左边界”的水平间距。
 * 图标与文字同属一个 tab，间距由共享 `.ui-tab`（含其图标包裹层）统一提供。
 */
export function tabIconTextGaps(tablist: Locator): Promise<number[]> {
  return tablist.evaluate((root) => {
    const tabs = Array.from(root.querySelectorAll('[role="tab"]'))
    return tabs.map((tab) => {
      const icon = tab.querySelector('svg')
      if (!icon) {
        return Number.NaN
      }
      const walker = document.createTreeWalker(tab, NodeFilter.SHOW_TEXT)
      let node = walker.nextNode()
      while (node !== null && (node.textContent ?? '').trim() === '') {
        node = walker.nextNode()
      }
      if (node === null) {
        return Number.NaN
      }
      const range = document.createRange()
      range.selectNodeContents(node)
      return range.getBoundingClientRect().left - icon.getBoundingClientRect().right
    })
  })
}

/**
 * 每个单词实际占用的行内矩形数量：>1 说明该单词被拆到了多行（按字符断词）。
 * 中文标签没有空格，整段文本视为一个单词。
 */
export function wordRectCounts(locator: Locator): Promise<number[]> {
  return locator.evaluate((element) => {
    const node = element.firstChild
    if (node === null || node.nodeType !== Node.TEXT_NODE) {
      return []
    }
    const text = node.textContent ?? ''
    const words = [...text.matchAll(/\S+/g)]
    return words.map((match) => {
      const start = match.index ?? 0
      const range = document.createRange()
      range.setStart(node, start)
      range.setEnd(node, start + match[0].length)
      return range.getClientRects().length
    })
  })
}

/** 元素矩形与容器矩形同一个坐标系下的包含判断。 */
export async function isInside(inner: Locator, outer: Locator): Promise<boolean> {
  const [innerBox, outerBox] = await Promise.all([inner.boundingBox(), outer.boundingBox()])
  if (innerBox === null || outerBox === null) {
    return false
  }
  return (
    innerBox.x >= outerBox.x - 0.5
    && innerBox.y >= outerBox.y - 0.5
    && innerBox.x + innerBox.width <= outerBox.x + outerBox.width + 0.5
    && innerBox.y + innerBox.height <= outerBox.y + outerBox.height + 0.5
  )
}

/** 离线应用事件通道：只完成 connected/subscribed 握手，不启动后端。 */
export async function routeApplicationEvents(page: Page): Promise<void> {
  await page.routeWebSocket('**/ws/events', (socket) => {
    let connected = false
    socket.onMessage((raw) => {
      if (!connected) {
        connected = true
        socket.send(JSON.stringify({
          type: 'connected',
          eventNodeId: 'b0000000-0000-4000-8000-000000000001',
          connectionId: 'b0000000-0000-4000-8000-000000000002',
          // connected 帧必须携带服务端 heartbeat 间隔，否则客户端判定畸形且永不确认订阅。
          heartbeatIntervalMillis: '20000',
        }))
      }
      const message = JSON.parse(String(raw)) as { type: string; resource?: unknown }
      if (message.type === 'subscribe') {
        socket.send(JSON.stringify({ type: 'subscribed', resource: message.resource, cursor: '0' }))
      }
    })
  })
}
