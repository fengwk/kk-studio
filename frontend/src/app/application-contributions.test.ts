import { describe, expect, it } from 'vitest'
import {
  applicationContributions,
  composeApplicationContributions,
} from '@/app/application-contributions'
import type { TrustedReactExtension } from '@/platform/extensions/types'

const Page = () => null

describe('application contributions', () => {
  it('provides one frozen static table for the production extension set', () => {
    // 组合根只在模块加载时组装一次：外层数组不可变，tool renderer 按冻结 key 查表。
    expect(Object.isFrozen(applicationContributions.pages)).toBe(true)
    expect(Object.isFrozen(applicationContributions.dialogs)).toBe(true)
    expect(Object.isFrozen(applicationContributions.overlays)).toBe(true)
    expect('register' in applicationContributions).toBe(false)
    expect('subscribe' in applicationContributions).toBe(false)
    expect('set' in applicationContributions.toolRenderers).toBe(false)
    expect(Object.isFrozen(applicationContributions.toolRenderers)).toBe(true)
    expect(applicationContributions.toolRenderers.get('task')?.id).toBe('task')
  })

  it('preserves declaration order and flattens each contribution kind', () => {
    const first: TrustedReactExtension = {
      id: 'first',
      pages: [{ id: 'first.page', path: 'first', component: Page }],
      dialogs: [{ id: 'first.dialog', component: Page }],
      overlays: [{ id: 'first.overlay', component: Page }],
      toolRenderers: [{ id: 'first.tool', component: Page }],
    }
    const second: TrustedReactExtension = {
      id: 'second',
      pages: [{ id: 'second.page', path: 'second', component: Page }],
      toolRenderers: [{ id: 'second.tool', component: Page }],
    }

    const contributions = composeApplicationContributions([first, second])

    expect(contributions.pages.map((page) => page.id)).toEqual(['first.page', 'second.page'])
    expect(contributions.dialogs.map((dialog) => dialog.id)).toEqual(['first.dialog'])
    expect(contributions.overlays.map((overlay) => overlay.id)).toEqual(['first.overlay'])
    expect([...contributions.toolRenderers.keys()]).toEqual(['first.tool', 'second.tool'])
  })

  it('rejects duplicate ids at the composition boundary', () => {
    // 同一 kind 的 id 必须全局唯一；没有 priority 或后注册覆盖的兜底链。
    expect(() => composeApplicationContributions([
      { id: 'one', pages: [{ id: 'duplicate', path: 'one', component: Page }] },
      { id: 'two', pages: [{ id: 'duplicate', path: 'two', component: Page }] },
    ])).toThrow('Duplicate contribution id in pages: duplicate')

    expect(() => composeApplicationContributions([
      { id: 'one', toolRenderers: [{ id: 'task', component: Page }] },
      { id: 'two', toolRenderers: [{ id: 'task', component: Page }] },
    ])).toThrow('Duplicate contribution id in toolRenderers: task')

    expect(() => composeApplicationContributions([
      { id: 'same' },
      { id: 'same' },
    ])).toThrow('Duplicate extension id: same')
  })
})
