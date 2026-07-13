import { describe, expect, it, vi } from 'vitest'
import { ContributionRegistry, ExtensionHost } from '@/platform/extensions/ExtensionHost'

describe('ContributionRegistry', () => {
  it('keeps snapshots stable until a mutation and notifies once per mutation', () => {
    const registry = new ContributionRegistry<{ id: string; priority?: number }>()
    const before = registry.getSnapshot()
    expect(registry.getSnapshot()).toBe(before)
    const listener = vi.fn()
    registry.subscribe(listener)

    const dispose = registry.register({ id: 'first' }, 0)
    const added = registry.getSnapshot()
    expect(listener).toHaveBeenCalledTimes(1)
    expect(registry.getSnapshot()).toBe(added)

    dispose()
    dispose()
    expect(listener).toHaveBeenCalledTimes(2)
    expect(registry.getSnapshot()).toBe(registry.getSnapshot())
  })
})

describe('ExtensionHost', () => {
  it('orders contributions by priority and falls back when an override unloads', () => {
    const host = new ExtensionHost()
    host.register({ id: 'base', navigation: [{ id: 'ai', label: 'Base', path: 'sessions', priority: 10 }] })
    const disposeOverride = host.register({ id: 'override', navigation: [{ id: 'ai', label: 'Override', path: 'custom', priority: 20 }] })
    host.register({ id: 'later', navigation: [{ id: 'later', label: 'Later', path: 'later', priority: 10 }] })

    expect(host.navigation.list().map((item) => item.label)).toEqual(['Override', 'Later'])
    disposeOverride()
    expect(host.navigation.get('ai')?.label).toBe('Base')
  })

  it('publishes one host snapshot per mutation and has idempotent disposal', () => {
    const host = new ExtensionHost()
    const listener = vi.fn()
    const before = host.getSnapshot()
    host.subscribe(listener)

    const dispose = host.register({
      id: 'one',
      pages: [{ id: 'page', path: 'page', component: () => null }],
      commands: [{ id: 'run', title: 'Run', run: () => undefined }],
    })
    const registered = host.getSnapshot()
    expect(listener).toHaveBeenCalledTimes(1)
    expect(host.getSnapshot()).toBe(registered)
    expect(registered).not.toBe(before)

    dispose()
    dispose()
    host.unregister('one')
    expect(listener).toHaveBeenCalledTimes(2)

    host.dispose()
    expect(listener).toHaveBeenCalledTimes(2)
  })

  it('rejects invalid input and atomically rejects duplicate ids within an extension registry', () => {
    const host = new ExtensionHost()
    const before = host.getSnapshot()

    expect(() => host.register({ id: ' ', pages: [{ id: 'page', path: 'page', component: () => null }] })).toThrow('Extension id must be non-empty')
    expect(() => host.register({ id: 'invalid-contribution', statuses: [{ id: ' ', component: () => null }] })).toThrow('Contribution in statuses id must be non-empty')
    expect(() => host.register({ id: 'invalid-page', pages: [{ id: 'page', path: '/page', component: () => null }] })).toThrow('Page path must be a non-empty nested relative path')
    expect(() => host.register({ id: 'invalid-nav', navigation: [{ id: 'nav', label: 'Nav', path: '../nav' }] })).toThrow('Navigation path must be a non-empty nested relative path')
    expect(() => host.register({
      id: 'duplicate',
      pages: [
        { id: 'page', path: 'first', component: () => null },
        { id: 'page', path: 'second', component: () => null },
      ],
    })).toThrow('Duplicate contribution id in pages: page')

    expect(host.getSnapshot()).toBe(before)
    expect(host.pages.list()).toEqual([])
  })

  it('rejects duplicate extension ids', () => {
    const host = new ExtensionHost()
    host.register({ id: 'first', statuses: [{ id: 'status', component: () => null }] })
    expect(() => host.register({ id: 'first' })).toThrow('Extension id already registered: first')
  })
})
