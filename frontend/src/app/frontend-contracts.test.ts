import { describe, expect, it } from 'vitest'
import { PRIMARY_NAV_ITEMS } from '@/app/navigation'
import { aiExtension } from '@/app/ai-console/ai-extension.definition'

describe('Frontend entry contracts', () => {
  it('declares authentic navigation and workspace routes for AI and interactions', () => {
    // 测试意图：顶层主导航包含 interactions 真实入口，AI 扩展声明沉浸式工作区路由。
    const navIds = PRIMARY_NAV_ITEMS.map((item) => item.id)
    expect(navIds).toContain('interactions')
    expect(PRIMARY_NAV_ITEMS.find((item) => item.id === 'interactions')?.to).toBe('/interactions')

    const threadRoute = aiExtension.pages?.find((page) => page.path === 'threads/:threadId')
    expect(threadRoute).toBeDefined()
    expect(threadRoute?.workspace).toBe(true)
  })
})
