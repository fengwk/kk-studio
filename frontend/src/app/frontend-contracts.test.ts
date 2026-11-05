import { describe, expect, it } from 'vitest'
import { PRIMARY_NAV_ITEMS } from '@/app/navigation'
import { aiExtension } from '@/app/ai-console/ai-extension.definition'
import { createProjectsApi, projectsApi } from '@/features/projects/projects-api'
import { projectsExtension } from '@/features/projects/extensions/projects-extension.definition'

describe('Frontend entry contracts', () => {
  it('exposes canonical Project API and routing contracts', () => {
    // 测试意图：Project API 直接由源模块提供完整 CRUD、工作流、快照与 Issue 治理生命周期方法。
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
    expect(typeof apiInstance.stopIssue).toBe('function')

    // 路由契约：Project 扩展对外暴露统一的列表与详情入口
    const projectPaths = projectsExtension.pages?.map((page) => page.path)
    expect(projectPaths).toEqual(['projects', 'projects/:projectId'])
  })

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
