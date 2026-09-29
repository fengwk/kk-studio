import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { PRIMARY_NAV_ITEMS } from '@/app/navigation'
import { aiExtension } from '@/features/ai/extensions/ai-extension.definition'
import { createProjectsApi, projectsApi } from '@/features/projects'
import { projectsExtension } from '@/features/projects/extensions/projects-extension.definition'

function resolvePath(relativePath: string): string {
  return fileURLToPath(new URL(relativePath, import.meta.url))
}

describe('Frontend architecture and entry contracts', () => {
  it('exposes canonical Project API and routing contracts through features/projects', () => {
    // 测试意图：验证 features/projects 作为 Project 领域的唯一公开入口，
    // 对外提供完整的 CRUD、工作流、快照与 Issue 治理生命周期方法，并保持清晰的路由契约。
    expect(projectsApi).toBeDefined()
    expect(typeof createProjectsApi).toBe('function')

    const apiInstance = createProjectsApi()
    expect(typeof apiInstance.listProjects).toBe('function')
    expect(typeof apiInstance.createProject).toBe('function')
    expect(typeof apiInstance.getProject).toBe('function')
    expect(typeof apiInstance.getProjectSnapshot).toBe('function')
    expect(typeof apiInstance.createIssue).toBe('function')
    expect(typeof apiInstance.getIssue).toBe('function')
    expect(typeof apiInstance.transitionIssue).toBe('function')
    expect(typeof apiInstance.resolveUnknown).toBe('function')
    expect(typeof apiInstance.stopIssue).toBe('function')

    // 路由契约：Project 扩展对外暴露统一的列表与详情入口
    const projectPaths = projectsExtension.pages?.map((p) => p.path)
    expect(projectPaths).toEqual(['projects', 'projects/:projectId'])
  })

  it('declares authentic navigation and workspace routes for AI and interactions', () => {
    // 测试意图：验证顶层主导航包含 interactions 真实入口，
    // 且 AI 扩展中声明了 /threads/:threadId 等沉浸式工作区路由。
    const navIds = PRIMARY_NAV_ITEMS.map((item) => item.id)
    expect(navIds).toContain('interactions')
    expect(PRIMARY_NAV_ITEMS.find((item) => item.id === 'interactions')?.to).toBe('/interactions')

    const threadRoute = aiExtension.pages?.find((p) => p.path === 'threads/:threadId')
    expect(threadRoute).toBeDefined()
    expect(threadRoute?.workspace).toBe(true)
  })

  it('protects shared .code-textarea styles and prevents legacy comfyui page selectors', () => {
    // 测试意图：确保 styles.css 中多个特性共享的 .code-textarea 样式得到持久保障，
    // 同时避免已下线的旧 comfyui 页面级样式选择器重新混入全局样式表。
    const stylesPath = resolvePath('../styles.css')
    const stylesContent = readFileSync(stylesPath, 'utf8')

    // 共享规则 .code-textarea 必须完好保留
    expect(stylesContent).toContain('.code-textarea')

    // 不包含已清理的 .comfyui-* 页面样式规则
    const comfyuiClassMatches = stylesContent.match(/\.comfyui-[\w-]+/g) ?? []
    expect(comfyuiClassMatches).toEqual([])
  })
})
