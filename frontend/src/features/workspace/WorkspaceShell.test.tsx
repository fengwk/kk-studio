import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApplicationEventProvider } from '@/shared/app-events'
import type { ChatSpaceDTO, WorkspaceTreeDTO } from '@/shared/api/generated/contracts'
import { setLocale } from '@/shared/i18n'
import { FakeWebSocketHarness } from '@/test-support/fake-websocket'
import type { PaneRenderers, PaneTarget } from './pane-target'
import { isPaneTarget } from './pane-target'
import { workspaceKeys } from './queries'
import type { TreeRow } from './resource-tree'
import { deleteTreeRow, moveTreeRow } from './workspace-api'
import { WorkspaceShell } from './WorkspaceShell'

const id = (n: number) => `10000000-0000-4000-8000-${String(n).padStart(12, '0')}`
const userId = id(1)
const spaceId = id(2)
const space2Id = id(3)
const archivedSpaceId = id(4)
const folder1Id = id(10)
const folder2Id = id(11)
const session1Id = id(20)
const thread1Id = id(21)
const thread2Id = id(22)
const session2Id = id(23)
const thread3Id = id(24)
const canvas1Id = id(30)
const env1Id = id(40)
const view1Id = id(41)

const baseSpaces: ChatSpaceDTO[] = [
  {
    id: spaceId,
    title: 'Studio',
    defaultAgentName: 'Coder',
    defaultEnvironmentId: null,
    defaultYoloEnabled: false,
    archivedAt: null,
    version: '1',
  },
  {
    id: space2Id,
    title: 'Lab',
    defaultAgentName: null,
    defaultEnvironmentId: null,
    defaultYoloEnabled: false,
    archivedAt: null,
    version: '1',
  },
  {
    id: archivedSpaceId,
    title: 'Old Space',
    defaultAgentName: null,
    defaultEnvironmentId: null,
    defaultYoloEnabled: false,
    archivedAt: 1000,
    version: '1',
  },
]

function makeTree(): WorkspaceTreeDTO {
  return {
    treeVersion: '5',
    folders: [
      {
        id: folder1Id,
        name: 'General',
        children: [
          { id: folder2Id, name: 'Nested', children: [], resources: [] },
        ],
        resources: [
          {
            id: session1Id,
            title: 'Debug Session',
            kind: 'SESSION',
            threads: [
              { id: thread1Id, title: 'Main Thread', state: 'NEEDS_YOU', lastActivityAt: 10, version: '1' },
              { id: thread2Id, title: 'Worker Thread', state: 'RUNNING', lastActivityAt: 20, version: '2' },
            ],
          },
          {
            id: session2Id,
            title: 'Solo Session',
            kind: 'SESSION',
            threads: [
              { id: thread3Id, title: 'Only Thread', state: 'WAITING', lastActivityAt: 30, version: '1' },
            ],
          },
          {
            id: canvas1Id,
            title: 'Architecture Canvas',
            kind: 'CANVAS',
            threads: [],
          },
        ],
      },
    ],
  }
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  })
}

function createStubRenderers(): Partial<PaneRenderers> {
  return {
    draft: ({ target, active, openBeside, onUnsentChange, replace, openSpaceSettings }) => (
      <div data-testid={`draft-${target.draftId}`} data-active={String(active)}>
        <span>Draft {target.folderId ?? 'root'}</span>
        <button type="button" onClick={() => onUnsentChange(true)}>mark-unsent</button>
        <button type="button" onClick={() => onUnsentChange(false)}>clear-unsent</button>
        <button type="button" onClick={() => openBeside({ kind: 'canvas', canvasId: canvas1Id })}>open-beside-canvas</button>
        <button type="button" onClick={() => replace({ kind: 'thread', threadId: thread1Id })}>replace-thread</button>
        <button type="button" onClick={() => replace({ kind: 'vscode', environmentId: env1Id, viewId: view1Id })}>replace-vscode</button>
        <button type="button" onClick={openSpaceSettings}>open-settings</button>
      </div>
    ),
    thread: ({ target }) => <div data-testid={`thread-${target.threadId}`}>Thread {target.threadId}</div>,
    canvas: ({ target }) => <div data-testid={`canvas-${target.canvasId}`}>Canvas {target.canvasId}</div>,
    vscode: ({ target }) => <div data-testid={`vscode-${target.viewId}`}>VSCode {target.viewId}</div>,
  }
}

describe('pane-target and workspace-api', () => {
  it('validates PaneTarget shapes strictly', () => {
    expect(isPaneTarget(null)).toBe(false)
    expect(isPaneTarget({ kind: 'unknown' })).toBe(false)
    expect(isPaneTarget({ kind: 'draft', draftId: id(1) })).toBe(true)
    expect(isPaneTarget({ kind: 'draft', draftId: id(1), folderId: id(2) })).toBe(true)
    expect(isPaneTarget({ kind: 'draft', draftId: id(1), extra: 1 })).toBe(false)
    expect(isPaneTarget({ kind: 'draft', draftId: 'bad' })).toBe(false)
    expect(isPaneTarget({ kind: 'thread', threadId: id(1) })).toBe(true)
    expect(isPaneTarget({ kind: 'thread', threadId: id(1), extra: 1 })).toBe(false)
    expect(isPaneTarget({ kind: 'canvas', canvasId: id(1) })).toBe(true)
    expect(isPaneTarget({ kind: 'vscode', environmentId: id(1), viewId: id(2) })).toBe(true)
    expect(isPaneTarget({ kind: 'vscode', environmentId: id(1), viewId: 'nope' })).toBe(false)
  })

  it('dispatches moveTreeRow and deleteTreeRow requests correctly and rejects moving threads', async () => {
    const fetchMock = vi.fn(async () => jsonResponse({}))
    vi.stubGlobal('fetch', fetchMock)

    const folderRow: TreeRow = { kind: 'folder', id: folder1Id, name: 'General', parentId: null, level: 1, children: [] }
    const sessionRow: TreeRow = { kind: 'session', id: session1Id, name: 'Sess', parentId: folder1Id, level: 2, children: [] }
    const canvasRow: TreeRow = { kind: 'canvas', id: canvas1Id, name: 'Canv', parentId: folder1Id, level: 2, children: [] }
    const threadRow: TreeRow = {
      kind: 'thread',
      id: thread1Id,
      name: 'Main',
      parentId: session1Id,
      level: 3,
      children: [],
      thread: { id: thread1Id, title: 'Main', state: 'IDLE', lastActivityAt: 1, version: '2' },
    }

    await moveTreeRow(spaceId, folderRow, { parentId: folder2Id, beforeId: null, position: 'into' }, '5')
    await moveTreeRow(spaceId, sessionRow, { parentId: folder2Id, beforeId: null, position: 'into' }, '5')
    await moveTreeRow(spaceId, canvasRow, { parentId: folder2Id, beforeId: null, position: 'into' }, '5')
    expect(() => moveTreeRow(spaceId, threadRow, { parentId: folder2Id, beforeId: null, position: 'into' }, '5')).toThrow('Threads cannot be moved')

    await deleteTreeRow(spaceId, { kind: 'folder', id: folder1Id, expectedTreeVersion: '5' })
    await deleteTreeRow(spaceId, { kind: 'session', id: session1Id, expectedTreeVersion: '5' })
    await deleteTreeRow(spaceId, { kind: 'thread', id: thread1Id, expectedTreeVersion: '5', expectedVersion: '2' })
    await deleteTreeRow(spaceId, { kind: 'canvas', id: canvas1Id, expectedTreeVersion: '5', expectedRevision: '9' })

    expect(fetchMock).toHaveBeenCalledTimes(7)
    vi.unstubAllGlobals()
  })
})

describe('WorkspaceShell', () => {
  beforeEach(() => {
    setLocale('en-US')
    localStorage.clear()
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    setLocale('zh-CN')
    localStorage.clear()
    vi.unstubAllGlobals()
  })

  function renderShell(overrides: {
    search?: string
    consume?: () => void
    closeView?: (viewId: string) => Promise<boolean>
    selectSpace?: (id: string) => void
    openInteraction?: (spaceId: string, threadId: string) => void
    goToSettings?: () => void
    spaces?: ChatSpaceDTO[]
  } = {}) {
    const sockets = new FakeWebSocketHarness()
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const selectSpace = overrides.selectSpace ?? vi.fn()
    const openInteraction = overrides.openInteraction ?? vi.fn()
    const goToSettings = overrides.goToSettings ?? vi.fn()
    const closeView = overrides.closeView ?? vi.fn(async () => true)
    const consume = overrides.consume ?? vi.fn()
    let removeViewFn: ((viewId: string) => void) | undefined
    let envHelpers: { open: (t: PaneTarget) => void; findOpenView: (e: string) => string | undefined } | undefined

    const view = render(
      <QueryClientProvider client={queryClient}>
        <ApplicationEventProvider socketFactory={sockets.factory}>
          <WorkspaceShell
            userId={userId}
            space={(overrides.spaces ?? baseSpaces)[0]!}
            spaces={overrides.spaces ?? baseSpaces}
            selectSpace={selectSpace}
            openInteraction={openInteraction}
            goToSettings={goToSettings}
            renderers={createStubRenderers()}
            account={unsent => <span data-testid="account-unsent">{String(unsent)}</span>}
            environments={props => {
              envHelpers = props
              return (
                <div>
                  <button type="button" onClick={() => props.open({ kind: 'vscode', environmentId: env1Id, viewId: view1Id })}>open-env-vscode</button>
                  <span data-testid="open-view-id">{props.findOpenView(env1Id) ?? 'none'}</span>
                </div>
              )
            }}
            closeView={closeView}
            wrapPanes={(content, remove) => {
              removeViewFn = remove
              return <div data-testid="pane-wrapper">{content}</div>
            }}
            link={{ search: overrides.search ?? '', consume }}
          />
        </ApplicationEventProvider>
      </QueryClientProvider>,
    )

    return {
      view,
      sockets,
      queryClient,
      selectSpace,
      openInteraction,
      goToSettings,
      closeView,
      consume,
      getRemoveView: () => removeViewFn,
      getEnvHelpers: () => envHelpers,
    }
  }

  it('shows loading and error states before tree loads, and handles space menu, defaults, archive, and space creation', async () => {
    let failTree = true
    let failRename = true
    let failDefaults = true
    const tree = makeTree()

    vi.mocked(fetch).mockImplementation(async (input, init) => {
      const url = new URL(String(input), 'http://localhost')
      const method = (init?.method ?? 'GET').toUpperCase()
      if (url.pathname === `/api/chat-spaces/${spaceId}/tree` && method === 'GET') {
        if (failTree) return jsonResponse({ detail: 'bad' }, 500)
        return jsonResponse(tree)
      }
      if (url.pathname === '/api/harness/interactions') {
        return jsonResponse({ items: [], nextCursor: null, total: 0 })
      }
      if (url.pathname === `/api/chat-spaces/${spaceId}` && method === 'PATCH') {
        const body = JSON.parse(String(init?.body)) as { title?: string; archived?: boolean }
        if (body.title !== undefined && failRename) {
          failRename = false
          return jsonResponse({ reason: 'CHAT_SPACE_TITLE_EXISTS', detail: 'exists' }, 409)
        }
        if (body.title === undefined && body.archived === undefined && failDefaults) {
          failDefaults = false
          return jsonResponse({ detail: 'save failed' }, 500)
        }
        return jsonResponse({ ...baseSpaces[0]!, ...body, version: '2' })
      }
      if (url.pathname === `/api/chat-spaces/${archivedSpaceId}` && method === 'PATCH') {
        return jsonResponse({ ...baseSpaces[2]!, archivedAt: null, version: '2' })
      }
      if (url.pathname === '/api/chat-spaces' && method === 'POST') {
        return jsonResponse({
          id: id(99),
          title: 'Created Space',
          defaultAgentName: 'Coder',
          defaultEnvironmentId: null,
          defaultYoloEnabled: false,
          archivedAt: null,
          version: '1',
        }, 201)
      }
      if (url.pathname === '/api/platform/agent-summaries') {
        return jsonResponse([{ name: 'Coder', description: '' }, { name: 'Planner', description: '' }])
      }
      if (url.pathname === '/api/platform/environment-summaries') {
        return jsonResponse([{ id: env1Id, name: 'Dev Env', status: 'RUNNING' }])
      }
      return jsonResponse({}, 404)
    })

    const { queryClient, selectSpace, goToSettings } = renderShell()
    expect(screen.getByText('Loading workspace')).toBeInTheDocument()
    expect(await screen.findByText('Resource unavailable')).toBeInTheDocument()

    failTree = false
    await queryClient.resetQueries({ queryKey: workspaceKeys.tree(spaceId) })
    expect(await screen.findByRole('tree', { name: 'Directory' })).toBeInTheDocument()

    // Settings button
    fireEvent.click(screen.getByRole('button', { name: 'Settings' }))
    expect(goToSettings).toHaveBeenCalledTimes(1)

    // SpaceMenu: open, keyboard nav, inline rename empty error -> 409 error -> success
    const spaceBtn = screen.getByRole('button', { name: 'Studio' })
    fireEvent.click(spaceBtn)
    const menu = screen.getByRole('menu', { name: 'Chat Spaces' })
    fireEvent.keyDown(menu, { key: 'ArrowDown' })
    fireEvent.keyDown(menu, { key: 'End' })
    fireEvent.keyDown(menu, { key: 'Home' })
    fireEvent.keyDown(menu, { key: 'ArrowUp' })

    // Click current space to start inline rename
    fireEvent.click(within(menu).getByRole('menuitem', { name: 'Studio' }))
    const renameInput = within(menu).getByLabelText('Rename Studio')
    fireEvent.change(renameInput, { target: { value: '   ' } })
    fireEvent.submit(renameInput.closest('form')!)
    expect(within(menu).getByRole('alert')).toHaveTextContent('Enter a name.')

    // Escape cancels inline edit, then F2 starts it again
    fireEvent.keyDown(menu, { key: 'Escape' })
    const studioItem = within(menu).getByRole('menuitem', { name: 'Studio' })
    fireEvent.keyDown(studioItem, { key: 'F2' })
    const renameInput2 = within(menu).getByLabelText('Rename Studio')
    fireEvent.change(renameInput2, { target: { value: 'Studio Renamed' } })
    fireEvent.submit(renameInput2.closest('form')!)
    expect(await within(menu).findByRole('alert')).toHaveTextContent('This name already exists.')

    fireEvent.submit(renameInput2.closest('form')!)
    await waitFor(() => expect(screen.queryByRole('menu', { name: 'Chat Spaces' })).toBeNull())

    // Select another space
    fireEvent.click(spaceBtn)
    fireEvent.click(screen.getByRole('menuitem', { name: 'Lab' }))
    expect(selectSpace).toHaveBeenCalledWith(space2Id)

    // Archived spaces toggle and restore
    fireEvent.click(spaceBtn)
    fireEvent.click(screen.getByRole('menuitem', { name: 'Archived' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Old Space' }))
    await waitFor(() => expect(selectSpace).toHaveBeenCalledWith(archivedSpaceId))

    // Archive current space selects next active space
    fireEvent.click(spaceBtn)
    fireEvent.click(screen.getByRole('menuitem', { name: 'Archive current space' }))
    await waitFor(() => expect(selectSpace).toHaveBeenCalledWith(space2Id))

    // New Chat Space dialog
    fireEvent.click(spaceBtn)
    fireEvent.click(screen.getByRole('menuitem', { name: 'New Chat Space' }))
    const createDialog = await screen.findByRole('dialog', { name: 'New Chat Space' })
    fireEvent.change(within(createDialog).getByRole('textbox'), { target: { value: 'Created Space' } })
    fireEvent.click(within(createDialog).getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(selectSpace).toHaveBeenCalledWith(id(99)))

    // SpaceDefaults dialog: error then success
    fireEvent.click(spaceBtn)
    fireEvent.click(screen.getByRole('menuitem', { name: 'Space defaults' }))
    const defaultsDialog = await screen.findByRole('dialog', { name: 'Space defaults' })
    await waitFor(() => expect(within(defaultsDialog).getByRole('button', { name: 'Confirm' })).toBeEnabled())

    fireEvent.click(within(defaultsDialog).getByLabelText('Default agent'))
    fireEvent.click(await screen.findByRole('option', { name: 'Planner' }))
    fireEvent.click(within(defaultsDialog).getByLabelText('Default environment'))
    fireEvent.click(await screen.findByRole('option', { name: 'Dev Env' }))
    fireEvent.click(within(defaultsDialog).getByRole('checkbox'))

    fireEvent.click(within(defaultsDialog).getByRole('button', { name: 'Confirm' }))
    expect(await within(defaultsDialog).findByRole('alert')).toHaveTextContent('Request failed')
    fireEvent.click(within(defaultsDialog).getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Space defaults' })).toBeNull())

    // Outside pointerdown and Tab close SpaceMenu
    fireEvent.click(spaceBtn)
    expect(screen.getByRole('menu', { name: 'Chat Spaces' })).toBeInTheDocument()
    fireEvent.pointerDown(document.body)
    expect(screen.queryByRole('menu', { name: 'Chat Spaces' })).toBeNull()
    fireEvent.click(spaceBtn)
    fireEvent.keyDown(screen.getByRole('menu', { name: 'Chat Spaces' }), { key: 'Tab' })
    expect(screen.queryByRole('menu', { name: 'Chat Spaces' })).toBeNull()
  })

  it('drives tree navigation, filtering, CRUD, status tab, sidebar collapse/drawer/resize, and realtime sync', async () => {
    const tree = makeTree()
    let failFolderCreate = true
    let failCanvasRevision = true

    vi.mocked(fetch).mockImplementation(async (input, init) => {
      const url = new URL(String(input), 'http://localhost')
      const method = (init?.method ?? 'GET').toUpperCase()
      if (url.pathname === `/api/chat-spaces/${spaceId}/tree`) return jsonResponse(tree)
      if (url.pathname === '/api/harness/interactions') return jsonResponse({ items: [], nextCursor: null, total: 0 })
      if (url.pathname === `/api/chat-spaces/${spaceId}/folders` && method === 'POST') {
        if (failFolderCreate) {
          failFolderCreate = false
          return jsonResponse({ reason: 'FOLDER_NAME_EXISTS', detail: 'dup' }, 409)
        }
        const newFolderId = id(55)
        tree.folders.push({ id: newFolderId, name: 'NewDir', children: [], resources: [] })
        return jsonResponse({ id: newFolderId, name: 'NewDir' }, 201)
      }
      if (url.pathname === `/api/chat-spaces/${spaceId}/canvases` && method === 'POST') {
        const newCanvasId = id(56)
        tree.folders[0]!.resources.push({ id: newCanvasId, title: 'New Canvas', kind: 'CANVAS', threads: [] })
        return jsonResponse({ id: newCanvasId, title: 'New Canvas' }, 201)
      }
      if (url.pathname.includes('/folders/') && method === 'PATCH') return jsonResponse({ treeVersion: '6' })
      if (url.pathname.includes('/resources/') && method === 'PATCH') return jsonResponse({ treeVersion: '6' })
      if (url.pathname.includes('/threads/') && method === 'PATCH') return jsonResponse({ version: '3' })
      if (url.pathname === `/api/canvas/canvases/${canvas1Id}` && method === 'GET') {
        if (failCanvasRevision) {
          failCanvasRevision = false
          return jsonResponse({ detail: 'unavailable' }, 500)
        }
        return jsonResponse({ document: { id: canvas1Id, title: 'Architecture Canvas', revision: '7', content: '{}' } })
      }
      if (url.pathname.includes(`/canvases/${canvas1Id}`) && method === 'DELETE') {
        return jsonResponse({ reason: 'RESOURCE_IN_USE', detail: 'in use' }, 409)
      }
      if (url.pathname.includes(`/sessions/${session2Id}`) && method === 'DELETE') {
        return new Response(null, { status: 204 })
      }
      if (url.pathname.includes(`/threads/${thread2Id}`) && method === 'DELETE') {
        return new Response(null, { status: 204 })
      }
      return jsonResponse({}, 404)
    })

    const { sockets } = renderShell()
    const treeEl = await screen.findByRole('tree', { name: 'Directory' })

    // Tree filter
    const filterInput = screen.getByPlaceholderText('Filter by name')
    fireEvent.change(filterInput, { target: { value: 'Worker' } })
    expect(within(treeEl).getByRole('treeitem', { name: 'Worker Thread' })).toBeInTheDocument()
    fireEvent.change(filterInput, { target: { value: '' } })

    // Keyboard navigation across tree rows
    const generalRow = within(treeEl).getByRole('treeitem', { name: 'General' })
    generalRow.focus()
    fireEvent.keyDown(generalRow, { key: 'ArrowDown' })
    fireEvent.keyDown(generalRow, { key: 'ArrowRight' })
    fireEvent.keyDown(generalRow, { key: 'End' })
    fireEvent.keyDown(generalRow, { key: 'Home' })
    fireEvent.keyDown(generalRow, { key: 'ArrowLeft' })
    fireEvent.keyDown(generalRow, { key: 'ArrowRight' }) // re-expand

    // Inline create folder: empty error -> Escape -> create again -> 409 error -> success
    fireEvent.click(screen.getByRole('button', { name: 'New folder' }))
    const folderInput = within(treeEl).getByRole('textbox', { name: 'New folder' })
    fireEvent.submit(folderInput.closest('form')!)
    expect(within(treeEl).getByRole('alert')).toHaveTextContent('Enter a name.')
    fireEvent.keyDown(folderInput, { key: 'Escape' })
    expect(within(treeEl).queryByRole('textbox', { name: 'New folder' })).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: 'New folder' }))
    const folderInput2 = within(treeEl).getByRole('textbox', { name: 'New folder' })
    fireEvent.change(folderInput2, { target: { value: 'NewDir' } })
    fireEvent.submit(folderInput2.closest('form')!)
    expect(await within(treeEl).findByRole('alert')).toHaveTextContent('This name already exists.')
    fireEvent.submit(folderInput2.closest('form')!)
    expect(await within(treeEl).findByRole('treeitem', { name: 'NewDir' })).toBeInTheDocument()

    // Context menu on General folder -> New canvas
    fireEvent.click(within(treeEl).getByRole('button', { name: 'Actions for General' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'New canvas' }))
    const canvasDialog = await screen.findByRole('dialog', { name: 'New canvas' })
    fireEvent.change(within(canvasDialog).getByRole('textbox'), { target: { value: 'New Canvas' } })
    fireEvent.click(within(canvasDialog).getByRole('button', { name: 'Confirm' }))
    expect(await screen.findByTestId(`canvas-${id(56)}`)).toBeInTheDocument()

    // Rename row via keyboard F2
    const canvasRow = within(treeEl).getByRole('treeitem', { name: 'Architecture Canvas' })
    fireEvent.keyDown(canvasRow, { key: 'F2' })
    const renameRowInput = within(treeEl).getByLabelText('Rename Architecture Canvas')
    fireEvent.change(renameRowInput, { target: { value: 'Renamed Canvas' } })
    fireEvent.submit(renameRowInput.closest('form')!)
    await waitFor(() => expect(within(treeEl).queryByLabelText('Rename Architecture Canvas')).toBeNull())

    // Delete canvas: first readCanvasRevision fails (reports alert), then succeeds and DELETE returns 409 error in modal
    fireEvent.keyDown(canvasRow, { key: 'Delete' })
    const failNotice = await screen.findByRole('alertdialog', { name: 'Request failed' })
    fireEvent.click(within(failNotice).getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())

    fireEvent.keyDown(canvasRow, { key: 'Delete' })
    const delCanvasModal = await screen.findByRole('alertdialog', { name: 'Delete Architecture Canvas?' })
    fireEvent.click(within(delCanvasModal).getByRole('button', { name: 'Confirm' }))
    expect(await within(delCanvasModal).findByText('This resource is still in use.')).toBeInTheDocument()
    fireEvent.click(within(delCanvasModal).getByRole('button', { name: 'Cancel' }))

    // Delete single-thread session vs multi-thread thread (expand sessions first)
    fireEvent.click(within(treeEl).getByRole('treeitem', { name: 'Solo Session' }))
    const onlyThreadRow = within(treeEl).getByRole('treeitem', { name: 'Only Thread' })
    fireEvent.keyDown(onlyThreadRow, { key: 'Delete' })
    const delSessionModal = await screen.findByRole('alertdialog', { name: 'Delete Only Thread?' })
    fireEvent.click(within(delSessionModal).getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())

    fireEvent.click(within(treeEl).getByRole('treeitem', { name: 'Debug Session' }))
    const workerRow = within(treeEl).getByRole('treeitem', { name: 'Worker Thread' })
    fireEvent.keyDown(workerRow, { key: 'Delete' })
    const delWorkerModal = await screen.findByRole('alertdialog', { name: 'Delete Worker Thread?' })
    fireEvent.click(within(delWorkerModal).getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())

    // Status tab shows NEEDS_YOU thread and clicking opens it (via PanePicker since pane A holds New Canvas)
    fireEvent.click(screen.getByRole('tab', { name: 'Status' }))
    fireEvent.click(screen.getByRole('button', { name: 'Main Thread' }))
    const statusPicker = await screen.findByRole('dialog', { name: 'Open in pane' })
    fireEvent.click(within(statusPicker).getByRole('button', { name: /^A:/ }))
    expect(await screen.findByTestId(`thread-${thread1Id}`)).toBeInTheDocument()

    // Collapse & expand desktop sidebar
    fireEvent.click(screen.getAllByRole('button', { name: 'Close sidebar' })[0]!)
    const openBtns = screen.getAllByRole('button', { name: 'Open sidebar' })
    expect(openBtns.length).toBeGreaterThanOrEqual(2)
    fireEvent.click(openBtns[0]!)

    // Resize sidebar with keyboard and double-click
    const separator = screen.getByRole('separator', { name: 'Resize sidebar' })
    fireEvent.keyDown(separator, { key: 'ArrowRight' })
    expect(separator).toHaveAttribute('aria-valuenow', '280')
    fireEvent.doubleClick(separator)
    expect(separator).toHaveAttribute('aria-valuenow', '264')

    // Mobile drawer open & Escape close
    const mobileTriggers = screen.getAllByRole('button', { name: 'Open sidebar' })
    fireEvent.click(mobileTriggers.at(-1)!)
    expect(document.querySelector('.workspace-shell')).toHaveAttribute('data-drawer', 'true')
    fireEvent.keyDown(document.querySelector('.workspace-shell')!, { key: 'Escape' })
    expect(document.querySelector('.workspace-shell')).not.toHaveAttribute('data-drawer')

    // Realtime WebSocket events
    await waitFor(() => expect(sockets.sockets.length).toBe(1))
    const sock = sockets.sockets[0]!
    act(() => {
      sock.open()
      sock.emitServer({ type: 'connected', eventNodeId: id(1), connectionId: id(2), heartbeatIntervalMillis: '60000' })
      sock.emitServer({ type: 'subscribed', resource: { kind: 'chat-space', id: spaceId }, cursor: '0' })
      sock.emitServer({ type: 'event', resource: { kind: 'chat-space', id: spaceId }, name: 'tree', data: { treeVersion: '2' } })
      sock.emitServer({ type: 'event', resource: { kind: 'chat-space', id: spaceId }, name: 'space', data: { version: '2' } })
      sock.emitServer({ type: 'event', resource: { kind: 'chat-space', id: spaceId }, name: 'schedules', data: {} })
    })
  })

  it('handles deep links, unsent confirmation, pane picker, openBeside, vscode closeView, and removeView', async () => {
    const tree = makeTree()
    const failPlacement = false
    const closeViewResult = false

    vi.mocked(fetch).mockImplementation(async (input) => {
      const url = new URL(String(input), 'http://localhost')
      if (url.pathname === `/api/chat-spaces/${spaceId}/tree`) return jsonResponse(tree)
      if (url.pathname === '/api/harness/interactions') return jsonResponse({ items: [], nextCursor: null, total: 0 })
      if (url.pathname === `/api/chat-spaces/${spaceId}/threads/${thread1Id}`) {
        if (failPlacement) return jsonResponse({}, 404)
        return jsonResponse({ sessionId: session1Id, sessionTitle: 'Debug', folderId: folder1Id, title: 'Main Thread', state: 'IDLE', version: '1' })
      }
      return jsonResponse({}, 404)
    })

    const consume = vi.fn()
    const closeView = vi.fn(async () => closeViewResult)
    const { getEnvHelpers, getRemoveView } = renderShell({
      search: `?thread=${thread1Id}`,
      consume,
      closeView,
    })

    // Deep link opens thread1Id
    expect(await screen.findByTestId(`thread-${thread1Id}`)).toBeInTheDocument()
    expect(consume).toHaveBeenCalled()

    // Open beside adds a split pane with canvas1Id (or opens in empty pane)
    const treeEl = screen.getByRole('tree', { name: 'Directory' })
    fireEvent.click(within(treeEl).getByRole('button', { name: 'Actions for General' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'New session' }))

    // Now PanePicker appears because focused pane already shows thread1Id
    const picker = await screen.findByRole('dialog', { name: 'Open in pane' })
    fireEvent.click(within(picker).getByRole('button', { name: /^A:/ }))
    const draftNode = await screen.findByText(/Draft/)
    const draftCard = draftNode.closest('[data-testid^="draft-"]')!

    // Mark unsent -> trying to open canvas triggers PanePicker -> selecting occupied unsent pane triggers discard modal
    fireEvent.click(within(draftCard as HTMLElement).getByText('mark-unsent'))
    expect(screen.getByTestId('account-unsent')).toHaveTextContent('true')

    fireEvent.click(within(treeEl).getByRole('treeitem', { name: 'Architecture Canvas' }))
    const picker2 = await screen.findByRole('dialog', { name: 'Open in pane' })
    fireEvent.click(within(picker2).getByRole('button', { name: /^A:/ }))
    const discardModal = await screen.findByRole('alertdialog', { name: 'Discard unsent input?' })
    fireEvent.click(within(discardModal).getByRole('button', { name: 'Confirm' }))
    expect(await screen.findByTestId(`canvas-${canvas1Id}`)).toBeInTheDocument()

    // openBeside splits when room/count allows, opening canvas beside the draft
    fireEvent.click(within(treeEl).getByRole('button', { name: 'Actions for General' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'New session' }))
    const picker3 = await screen.findByRole('dialog', { name: 'Open in pane' })
    fireEvent.click(within(picker3).getByRole('button', { name: /^A:/ }))
    const draftCard2 = (await screen.findByText(/Draft/)).closest('[data-testid^="draft-"]')!
    fireEvent.click(within(draftCard2 as HTMLElement).getByText('open-beside-canvas'))
    expect(await screen.findByTestId(`canvas-${canvas1Id}`)).toBeInTheDocument()

    // Replace draft in pane A with vscode, check findOpenView, attempt close when closeView fails (stays mounted), then removeView
    fireEvent.click(within(draftCard2 as HTMLElement).getByText('replace-vscode'))
    expect(await screen.findByTestId(`vscode-${view1Id}`)).toBeInTheDocument()
    expect(getEnvHelpers()?.findOpenView(env1Id)).toBe(view1Id)

    // Attempt to open thread1Id into vscode pane when closeView returns false -> stays vscode
    fireEvent.click(within(treeEl).getByRole('treeitem', { name: 'Debug Session' }))
    fireEvent.click(within(treeEl).getByRole('treeitem', { name: 'Main Thread' }))
    const picker4 = await screen.findByRole('dialog', { name: 'Open in pane' })
    fireEvent.click(within(picker4).getByRole('button', { name: /VS Code/ }))
    await waitFor(() => expect(closeView).toHaveBeenCalledWith(view1Id))
    expect(screen.getByTestId(`vscode-${view1Id}`)).toBeInTheDocument()

    // removeView cleans up the vscode pane
    act(() => { getRemoveView()?.(view1Id) })
    await waitFor(() => expect(screen.queryByTestId(`vscode-${view1Id}`)).toBeNull())
    expect(getEnvHelpers()?.findOpenView(env1Id)).toBeUndefined()
  })
})
