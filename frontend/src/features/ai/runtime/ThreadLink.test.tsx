import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ThreadLink, ThreadNavigationContext } from '@/features/ai/runtime/ThreadLink'

const CHILD = '00000000-0000-4000-8000-000000000002'

function LocationProbe() {
  const location = useLocation()
  return <span data-testid="location">{`${location.pathname}${location.search}`}</span>
}

function renderLink(
  { observe, target, capture }: {
    observe?: ((threadId: string) => void) | null
    target?: string
    /** 在上层捕获阶段取消默认行为，验证 defaultPrevented 时链接保留浏览器语义。 */
    capture?: boolean
  } = {},
) {
  return render(
    <MemoryRouter initialEntries={['/threads/root']}>
      <ThreadNavigationContext.Provider value={observe ?? null}>
        {capture ? (
          <div onClickCapture={(event) => event.preventDefault()}>
            <ThreadLink threadId={CHILD} target={target}>child link</ThreadLink>
          </div>
        ) : (
          <ThreadLink threadId={CHILD} target={target}>child link</ThreadLink>
        )}
      </ThreadNavigationContext.Provider>
      <Routes>
        <Route path="/threads/root" element={<LocationProbe />} />
        <Route path="/threads/:threadId" element={<LocationProbe />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('ThreadLink', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('keeps the standalone thread address when no pane navigation provider exists', async () => {
    const user = userEvent.setup()
    renderLink()

    expect(screen.getByRole('link', { name: 'child link' })).toHaveAttribute(
      'href',
      `/threads/${CHILD}`,
    )
    await user.click(screen.getByRole('link', { name: 'child link' }))
    expect(screen.getAllByTestId('location')[0]).toHaveTextContent(`/threads/${CHILD}`)
  })

  it('intercepts an ordinary click through the pane navigation provider without changing the route', async () => {
    const user = userEvent.setup()
    const observe = vi.fn()
    renderLink({ observe })

    await user.click(screen.getByRole('link', { name: 'child link' }))
    expect(observe).toHaveBeenCalledWith(CHILD)
    // 当前 pane 查看子代理：URL 保持在原地址。
    expect(screen.getAllByTestId('location')[0]).toHaveTextContent('/threads/root')
  })

  it.each([
    ['meta', { metaKey: true }],
    ['ctrl', { ctrlKey: true }],
    ['shift', { shiftKey: true }],
    ['alt', { altKey: true }],
  ])('keeps browser semantics for %s-modified clicks', async (_name, modifiers) => {
    const user = userEvent.setup()
    const observe = vi.fn()
    renderLink({ observe })

    await user.keyboard('{Meta>}')
    await user.click(screen.getByRole('link', { name: 'child link' }), modifiers)
    await user.keyboard('{/Meta}')
    expect(observe).not.toHaveBeenCalled()
  })

  it('keeps browser semantics for middle clicks and new-tab targets', async () => {
    const user = userEvent.setup()
    const observe = vi.fn()
    const { unmount } = renderLink({ observe })

    await user.pointer({ keys: '[MouseMiddle]', target: screen.getByRole('link', { name: 'child link' }) })
    expect(observe).not.toHaveBeenCalled()
    unmount()

    renderLink({ observe, target: '_blank' })
    expect(screen.getByRole('link', { name: 'child link' })).toHaveAttribute('target', '_blank')
    await user.click(screen.getByRole('link', { name: 'child link' }))
    expect(observe).not.toHaveBeenCalled()
  })

  it('respects an already prevented click', async () => {
    const user = userEvent.setup()
    const observe = vi.fn()
    renderLink({ observe, capture: true })

    await user.click(screen.getByRole('link', { name: 'child link' }))
    expect(observe).not.toHaveBeenCalled()
  })
})
