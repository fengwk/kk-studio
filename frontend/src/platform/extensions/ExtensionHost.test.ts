import { describe, expect, it } from 'vitest'
import { ExtensionHost } from '@/platform/extensions/ExtensionHost'

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

  it('keeps the first equal-priority contribution and rejects duplicate extension ids', () => {
    const host = new ExtensionHost()
    host.register({ id: 'first', statuses: [{ id: 'status', component: () => null }] })
    host.register({ id: 'second', statuses: [{ id: 'status', component: () => null }] })

    expect(host.statuses.list()).toHaveLength(1)
    expect(() => host.register({ id: 'first' })).toThrow('Extension id already registered: first')
  })

  it('unregisters every contribution and supports host disposal', () => {
    const host = new ExtensionHost()
    host.register({ id: 'one', pages: [{ id: 'page', path: 'page', component: () => null }], commands: [{ id: 'run', title: 'Run', run: () => undefined }] })
    host.register({ id: 'two', widgets: [{ id: 'widget', slot: 'widget', component: () => null }] })

    host.unregister('one')
    expect(host.pages.list()).toEqual([])
    expect(host.commands.list()).toEqual([])
    host.dispose()
    expect(host.widgets.list()).toEqual([])
  })
})
