import { describe, expect, it } from 'vitest'
import { MAX_ZOOM, MIN_ZOOM } from '@/features/canvas/data'
import { viewportsEqual } from '@/features/canvas/geometry'
import {
  canvasReducer,
  createInitialCanvasState,
  getActiveGenerator,
  getContextDescription,
} from '@/features/canvas/reducer'

const stage = { width: 1200, height: 800, dockTop: 680 }

describe('canvasReducer', () => {
  it('filters library ownership and opens the editor from a valid idea', () => {
    let state = createInitialCanvasState()
    state = canvasReducer(state, { type: 'set-library-filter', filter: 'mine' })
    expect(state.libraryFilter).toBe('mine')
    expect(state.toast).toBe('画布筛选已更新')

    state = canvasReducer(state, { type: 'set-idea', idea: '  ' })
    state = canvasReducer(state, { type: 'open-editor-from-idea' })
    expect(state.view).toBe('library')
    expect(state.toast).toBe('请先描述想完成的工作')

    state = canvasReducer(state, { type: 'set-selected-template', template: '技术方案' })
    state = canvasReducer(state, { type: 'set-idea', idea: '输出产品矩阵' })
    state = canvasReducer(state, { type: 'open-editor-from-idea' })
    expect(state.view).toBe('editor')
    expect(state.toast).toContain('技术方案')
  })

  it('moves nodes only when coordinates actually change', () => {
    let state = createInitialCanvasState()
    const web = state.nodes.find((node) => node.id === 'web')
    expect(web).toBeTruthy()
    const same = canvasReducer(state, {
      type: 'move-nodes',
      updates: [{ id: 'web', x: web!.x, y: web!.y }],
    })
    expect(same).toBe(state)
    expect(same.saveState).toBe('saved')

    state = canvasReducer(state, {
      type: 'move-nodes',
      updates: [{ id: 'web', x: web!.x + 40, y: web!.y + 20 }],
    })
    expect(state.nodes.find((node) => node.id === 'web')).toMatchObject({
      x: web!.x + 40,
      y: web!.y + 20,
    })
    expect(state.saveState).toBe('saving')
  })

  it('creates fixed-type generators and refuses runtime type conversion', () => {
    let state = canvasReducer(createInitialCanvasState(), { type: 'set-view', view: 'editor' })
    state = canvasReducer(state, { type: 'create-generator', mode: 'image', stage })
    const generator = getActiveGenerator(state)
    expect(generator?.generationMode).toBe('image')
    expect(generator?.status).toBe('draft')
    expect(state.addMenuOpen).toBe(false)
    expect(state.threadOpen).toBe(false)

    state = canvasReducer(state, { type: 'set-generation-prompt', value: '低饱和视觉探索' })
    state = canvasReducer(state, { type: 'mark-generator-draft-from-prompt' })
    state = canvasReducer(state, { type: 'submit-generation' })
    const generated = getActiveGenerator(state)
    expect(generated?.generationMode).toBe('image')
    expect(generated?.status).toBe('generated')
    // Generation 不能强制打开 Thread。
    expect(state.threadOpen).toBe(false)
  })

  it('controls agent runs, places a single direction-C result, and cleans it on retry', () => {
    let state = canvasReducer(createInitialCanvasState(), { type: 'set-view', view: 'editor' })
    state = canvasReducer(state, { type: 'set-agent-prompt', value: '整理竞品资料' })
    state = canvasReducer(state, { type: 'send-agent-message' })
    expect(state.nodes.find((node) => node.type === 'run' && node.status === 'running')).toBeTruthy()
    expect(state.threadOpen).toBe(true)
    expect(state.addMenuOpen).toBe(false)
    expect(state.activeGeneratorId).toBeNull()

    state = canvasReducer(state, { type: 'pause-agent-run' })
    expect(state.nodes.find((node) => node.type === 'run' && node.status === 'paused')).toBeTruthy()
    state = canvasReducer(state, { type: 'resume-agent-run' })
    state = canvasReducer(state, { type: 'tick-agent-run', stage })
    state = canvasReducer(state, { type: 'tick-agent-run', stage })
    state = canvasReducer(state, { type: 'tick-agent-run', stage })
    state = canvasReducer(state, { type: 'tick-agent-run', stage })

    const finished = state.nodes.find((node) => node.type === 'run')
    expect(finished && finished.type === 'run' && finished.status).toBe('succeeded')
    const generated = state.nodes.filter((node) => node.type === 'result' && node.generated)
    expect(generated).toHaveLength(1)
    expect(generated[0]?.variant).toBe('C')
    expect(generated[0]?.title).toContain('方向 C')
    expect(state.selectedIds).toEqual([generated[0]!.id])
    expect(state.saveState).toBe('saving')

    state = canvasReducer(state, { type: 'retry-agent-run' })
    expect(state.nodes.some((node) => node.type === 'result' && node.generated)).toBe(false)
    expect(state.nodes.find((node) => node.type === 'run' && node.status === 'running' && node.progress === 0)).toBeTruthy()

    state = canvasReducer(state, { type: 'reset-demo' })
    expect(state.messages).toEqual([])
    expect(state.view).toBe('editor')
  })

  it('tracks viewport no-op, context copy, and selection geometry helpers', () => {
    let state = createInitialCanvasState()
    const before = state.viewport
    state = canvasReducer(state, { type: 'set-viewport', viewport: { ...before } })
    expect(state.viewport).toBe(before)
    expect(viewportsEqual(before, state.viewport)).toBe(true)

    state = canvasReducer(state, { type: 'set-viewport', viewport: { x: 10, y: 20, scale: 9 } })
    expect(state.viewport.scale).toBeLessThanOrEqual(MAX_ZOOM)
    expect(state.viewport.scale).toBeGreaterThanOrEqual(MIN_ZOOM)

    expect(getContextDescription(state).description).toContain('网页、截图和研究资料')
    state = canvasReducer(state, { type: 'set-selection', ids: ['web', 'image'] })
    expect(getContextDescription(state)).toEqual({
      count: 2,
      description: '2 个选中对象将作为本次输入，并保留来源关系。',
    })
    state = canvasReducer(state, { type: 'set-context-mode', mode: 'whole' })
    expect(getContextDescription(state).count).toBe(state.nodes.length)
  })

  it('enforces overlay mutual exclusion across thread, add menu, and generation', () => {
    let state = canvasReducer(createInitialCanvasState(), { type: 'set-view', view: 'editor' })
    state = canvasReducer(state, { type: 'create-generator', mode: 'text', stage })
    expect(state.activeGeneratorId).toBeTruthy()

    state = canvasReducer(state, { type: 'set-add-menu-open', open: true })
    expect(state.addMenuOpen).toBe(true)
    expect(state.activeGeneratorId).toBeNull()
    expect(state.threadOpen).toBe(false)

    state = canvasReducer(state, { type: 'set-thread-open', open: true })
    expect(state.threadOpen).toBe(true)
    expect(state.addMenuOpen).toBe(false)
    expect(state.activeGeneratorId).toBeNull()

    state = canvasReducer(state, { type: 'create-generator', mode: 'video', stage })
    expect(state.activeGeneratorId).toBeTruthy()
    expect(state.threadOpen).toBe(false)
    expect(state.addMenuOpen).toBe(false)
  })

  it('handles add menu deferred actions and marks saving on edits', () => {
    let state = canvasReducer(createInitialCanvasState(), { type: 'set-view', view: 'editor' })
    state = canvasReducer(state, { type: 'handle-add-action', action: 'file', stage })
    expect(state.toast).toContain('文件导入')
    state = canvasReducer(state, { type: 'handle-add-action', action: 'frame', stage })
    expect(state.toast).toContain('Frame')
    state = canvasReducer(state, { type: 'handle-add-action', action: 'text', stage })
    expect(getActiveGenerator(state)?.generationMode).toBe('text')
    expect(state.saveState).toBe('saving')

    state = canvasReducer(state, { type: 'set-tool', tool: 'hand' })
    expect(state.tool).toBe('hand')
    state = canvasReducer(state, { type: 'set-tool', tool: 'hand', silent: true })
    expect(state.tool).toBe('hand')
  })
})
