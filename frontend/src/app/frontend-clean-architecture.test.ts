import { readdirSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import ts from 'typescript'
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

  it('forbids native select elements in production UI in favor of the shared Select', () => {
    // 测试意图：生产代码的下拉必须使用共享 Select（主题一致、键盘与 Escape 语义统一），
    // 禁止退回浏览器原生 <select>。豁免项会在各自 owner 的切片落地后删除，不得长期保留。
    const PENDING_REMOVAL_EXEMPTIONS = new Set([
      // 分支 owner 正在用 HistoryTree + history-tree.css 取代该组件；
      // 集成后此文件被删除，本豁免必须同步移除（否则门禁形同虚设）。
      'features/ai/chat/HistoryBranchPanel.tsx',
    ])

    const srcRootPath = path.resolve(process.cwd(), 'src')
    function collectFiles(dirPath: string): string[] {
      const entries = readdirSync(dirPath, { withFileTypes: true })
      const results: string[] = []
      for (const entry of entries) {
        const full = path.join(dirPath, entry.name)
        if (entry.isDirectory()) {
          if (entry.name !== 'node_modules' && entry.name !== '__tests__') {
            results.push(...collectFiles(full))
          }
        } else if (/\.(ts|tsx)$/.test(entry.name) && !entry.name.includes('.test.')) {
          results.push(full)
        }
      }
      return results
    }

    const violations: string[] = []
    for (const filePath of collectFiles(srcRootPath)) {
      const relFile = path.relative(srcRootPath, filePath).replace(/\\/g, '/')
      if (PENDING_REMOVAL_EXEMPTIONS.has(relFile)) {
        continue
      }
      if (/<select[\s>]/.test(readFileSync(filePath, 'utf8'))) {
        violations.push(relFile)
      }
    }

    expect(violations).toEqual([])
    // 豁免清单必须真实存在，避免过期豁免掩盖已删除文件。
    for (const exempt of PENDING_REMOVAL_EXEMPTIONS) {
      expect(readFileSync(path.join(srcRootPath, exempt), 'utf8')).toContain('<select')
    }
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

  describe('Architectural boundary verification and violation detection', () => {
    const srcRoot = path.resolve(process.cwd(), 'src')

    interface BoundaryViolation {
      file: string
      specifier: string
      reason: 'platform-depends-on-app' | 'platform-depends-on-features' | 'ai-depends-on-projects'
    }

    function checkImportBoundaries(filePath: string, fileContent?: string): BoundaryViolation[] {
      const content = fileContent ?? readFileSync(filePath, 'utf8')
      const imports = ts.preProcessFile(content).importedFiles.map((f) => f.fileName)
      const violations: BoundaryViolation[] = []
      const relFile = path.relative(srcRoot, filePath).replace(/\\/g, '/')
      const isPlatform = relFile.startsWith('platform/')
      const isAi = relFile.startsWith('features/ai/')

      for (const imp of imports) {
        let resolvedTarget: string | null = null
        if (imp.startsWith('@/')) {
          resolvedTarget = path.normalize(imp.slice(2)).replace(/\\/g, '/')
        } else if (imp.startsWith('.')) {
          const absTarget = path.resolve(path.dirname(filePath), imp)
          const relToSrc = path.relative(srcRoot, absTarget).replace(/\\/g, '/')
          if (!relToSrc.startsWith('..') && !path.isAbsolute(relToSrc)) {
            resolvedTarget = path.normalize(relToSrc).replace(/\\/g, '/')
          }
        }
        if (!resolvedTarget) {
          continue
        }

        if (isPlatform) {
          if (resolvedTarget === 'app' || resolvedTarget.startsWith('app/')) {
            violations.push({ file: relFile, specifier: imp, reason: 'platform-depends-on-app' })
          }
          if (resolvedTarget === 'features' || resolvedTarget.startsWith('features/')) {
            violations.push({ file: relFile, specifier: imp, reason: 'platform-depends-on-features' })
          }
        }
        if (isAi) {
          if (resolvedTarget === 'features/projects' || resolvedTarget.startsWith('features/projects/')) {
            violations.push({ file: relFile, specifier: imp, reason: 'ai-depends-on-projects' })
          }
        }
      }
      return violations
    }

    function scanProductionFiles(dirPath: string): string[] {
      const entries = readdirSync(dirPath, { withFileTypes: true })
      const results: string[] = []
      for (const entry of entries) {
        const full = path.join(dirPath, entry.name)
        if (entry.isDirectory()) {
          if (entry.name !== 'node_modules' && entry.name !== '__tests__') {
            results.push(...scanProductionFiles(full))
          }
        } else if (/\.(ts|tsx)$/.test(entry.name) && !entry.name.includes('.test.')) {
          results.push(full)
        }
      }
      return results
    }

    it('rigorously detects synthetic violation inputs for both alias and relative imports', () => {
      // 测试意图：确保架构门禁能真实触发违规输入报错，而不是单纯扫当前文件树假通过。
      const mockPlatformFile = path.join(srcRoot, 'platform/shell/MockShell.ts')
      const mockAiFile = path.join(srcRoot, 'features/ai/runtime/MockAgent.ts')

      // 1. Platform 不能依赖 app（alias 与 relative 均触发）
      const p1 = checkImportBoundaries(mockPlatformFile, 'import { x } from "@/app/navigation"')
      expect(p1).toEqual([
        { file: 'platform/shell/MockShell.ts', specifier: '@/app/navigation', reason: 'platform-depends-on-app' },
      ])
      const p2 = checkImportBoundaries(mockPlatformFile, 'import { x } from "../../app/navigation"')
      expect(p2).toEqual([
        { file: 'platform/shell/MockShell.ts', specifier: '../../app/navigation', reason: 'platform-depends-on-app' },
      ])

      // 2. Platform 不能依赖 features（alias 与 relative 均触发）
      const p3 = checkImportBoundaries(mockPlatformFile, 'import { x } from "@/features/ai"')
      expect(p3).toEqual([
        { file: 'platform/shell/MockShell.ts', specifier: '@/features/ai', reason: 'platform-depends-on-features' },
      ])
      const p4 = checkImportBoundaries(mockPlatformFile, 'import { x } from "../../features/ai"')
      expect(p4).toEqual([
        { file: 'platform/shell/MockShell.ts', specifier: '../../features/ai', reason: 'platform-depends-on-features' },
      ])

      // 3. AI feature 不能反向依赖 projects（alias 与 relative 均触发）
      const a1 = checkImportBoundaries(mockAiFile, 'import { x } from "@/features/projects/projects-api"')
      expect(a1).toEqual([
        { file: 'features/ai/runtime/MockAgent.ts', specifier: '@/features/projects/projects-api', reason: 'ai-depends-on-projects' },
      ])
      const a2 = checkImportBoundaries(mockAiFile, 'import { x } from "../../projects/projects-api"')
      expect(a2).toEqual([
        { file: 'features/ai/runtime/MockAgent.ts', specifier: '../../projects/projects-api', reason: 'ai-depends-on-projects' },
      ])

      // 4. 合法导入（Platform 引用 shared 或同层 platform，AI 引用 shared 或同层 ai）零违规
      const validPlatform = checkImportBoundaries(mockPlatformFile, 'import { x } from "@/shared/api"; import { y } from "./types"')
      expect(validPlatform).toEqual([])
      const validAi = checkImportBoundaries(mockAiFile, 'import { x } from "@/shared/api"; import { y } from "@/features/ai/catalog"')
      expect(validAi).toEqual([])
    })

    it('enforces production platform layer does not import app or features', () => {
      // 测试意图：验证当前仓库生产 platform 代码严格无 @/app、@/features 及相对反向依赖
      const platformFiles = scanProductionFiles(path.join(srcRoot, 'platform'))
      expect(platformFiles.length).toBeGreaterThan(0)

      const violations = platformFiles.flatMap((file) => checkImportBoundaries(file))
      expect(violations).toEqual([])
    })

    it('enforces AI feature does not reversely import projects feature', () => {
      // 测试意图：验证当前仓库生产 features/ai 代码完全与 features/projects 解耦
      const aiFiles = scanProductionFiles(path.join(srcRoot, 'features/ai'))
      expect(aiFiles.length).toBeGreaterThan(0)

      const violations = aiFiles.flatMap((file) => checkImportBoundaries(file))
      expect(violations).toEqual([])
    })
  })
})
