import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { createProjectsApi, projectsApi } from '@/features/projects'

function resolvePath(relativePath: string): string {
  return fileURLToPath(new URL(relativePath, import.meta.url))
}

describe('Frontend clean architecture and hygiene guards', () => {
  it('enforces single authoritative Project API in features/projects without duplicate shared definitions', () => {
    // 测试意图：确保 Project 领域的 API、契约与编解码只收敛在 features/projects，
    // 杜绝 shared/api 下出现重复的 project-service 或 contracts/project 孤立副本。
    const sharedApiDir = resolvePath('../shared/api')
    const sharedContractsDir = resolvePath('../shared/api/contracts')

    const sharedApiFiles = readdirSync(sharedApiDir)
    const sharedContractFiles = readdirSync(sharedContractsDir)

    // shared/api 及其 contracts 目录下不得存在任何 project 命名的文件
    expect(sharedApiFiles.filter((f) => f.includes('project'))).toEqual([])
    expect(sharedContractFiles.filter((f) => f.includes('project'))).toEqual([])
    expect(existsSync(resolvePath('../shared/api/contracts/project.ts'))).toBe(false)
    expect(existsSync(resolvePath('../shared/api/project-service.ts'))).toBe(false)

    // features/projects 为唯一公开入口，导出的 API 实例与工厂方法结构稳定
    expect(projectsApi).toBeDefined()
    expect(typeof createProjectsApi).toBe('function')

    const apiInstance = createProjectsApi()
    // 校验标准方法存在且无废弃别名（如 resolveUnknownIssue）
    expect(typeof apiInstance.resolveUnknown).toBe('function')
    expect((apiInstance as Record<string, unknown>).resolveUnknownIssue).toBeUndefined()
  })

  it('prevents legacy comfyui page class selectors from re-entering styles.css while preserving shared rules', () => {
    // 测试意图：确保 styles.css 中已废弃的旧 comfyui 页面级样式类不会重新引入，
    // 同时保证被多个在用组件依赖的通用规则（如 .code-textarea）与系统设置集成配置未被误删。
    const stylesPath = resolvePath('../styles.css')
    const stylesContent = readFileSync(stylesPath, 'utf8')

    // 检查任何形如 .comfyui-* 的 CSS 类选择器均不存在
    const comfyuiClassMatches = stylesContent.match(/\.comfyui-[\w-]+/g) ?? []
    expect(comfyuiClassMatches).toEqual([])

    // 共享规则 .code-textarea 必须完好保留
    expect(stylesContent).toContain('.code-textarea')
  })
})
