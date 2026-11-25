import { expect, test, type Page } from './fixture'

const path = '/browser-tests/workspace-shell-harness.html'
const id = (suffix: number) => `10000000-0000-4000-8000-${String(suffix).padStart(12, '0')}`
const spaceId = id(1), otherSpace = id(2), archivedSpace = id(12)
const root = id(4), session = id(5), thread = id(6), canvas = id(7), second = id(8)
const secondThread = id(9), archivedThread = id(13), backgroundThread = id(14)

async function setup(page: Page, initialOptions?: {
  archivedInteractions?: boolean
  interactions?: Array<Record<string, unknown>>
}) {
  const tree = {
    treeVersion: '1',
    folders: [
      {
        id: root,
        name: 'Projects',
        children: [],
        resources: [
          {
            id: session,
            title: 'Release planning',
            kind: 'SESSION',
            threads: [
              { id: thread, title: 'Release notes', state: 'RUNNING', lastActivityAt: 1, version: '0' },
              { id: secondThread, title: 'Checklist', state: 'NEEDS_YOU', lastActivityAt: 2, version: '0' },
            ],
          },
          { id: canvas, title: 'Diagram', kind: 'CANVAS' },
        ],
      },
      { id: second, name: 'Archive', children: [], resources: [] },
    ],
  }
  const archivedTree = {
    treeVersion: '1',
    folders: [
      {
        id: id(15),
        name: 'Legacy',
        children: [],
        resources: [
          {
            id: id(16),
            title: 'Postmortem',
            kind: 'SESSION',
            threads: [{ id: archivedThread, title: 'Root cause', state: 'NEEDS_YOU', lastActivityAt: 5, version: '0' }],
          },
        ],
      },
    ],
  }
  const spaces = [
    { id: spaceId, title: 'Studio', version: '1', archivedAt: null as number | null, defaultYoloEnabled: false },
    { id: otherSpace, title: 'Support', version: '1', archivedAt: null as number | null, defaultYoloEnabled: false },
    { id: archivedSpace, title: 'Old Space', version: '1', archivedAt: 100 as number | null, defaultYoloEnabled: false },
  ]
  const interactions = initialOptions?.interactions ?? [
    {
      type: 'ENVIRONMENT_WAIT',
      rootThreadId: id(30),
      environmentId: id(31),
      environmentName: 'runner',
      waitingCount: 1,
      createdAt: 10,
      owner: { kind: 'CHAT_SPACE', id: otherSpace, title: 'Support' },
    },
    {
      type: 'INPUT',
      toolInvocationId: id(22),
      threadId: thread,
      rootThreadId: thread,
      sessionId: session,
      toolCallId: 'call-1',
      toolName: 'question',
      argumentsJson: '{}',
      createdAt: 1,
      owner: { kind: 'CHAT_SPACE', id: otherSpace, title: 'Support' },
    },
    {
      type: 'APPROVAL',
      toolInvocationId: id(23),
      threadId: thread,
      rootThreadId: thread,
      sessionId: session,
      toolCallId: 'call-2',
      toolName: 'bash',
      argumentsJson: '{}',
      queuedSameFileCount: 0,
      createdAt: 2,
      owner: { kind: 'CHAT_SPACE', id: otherSpace, title: 'Support' },
    },
  ]
  let sendWsEvent: ((frame: unknown) => void) | undefined
  let nextFolderSeq = 10

  await page.routeWebSocket('**/ws/events', socket => {
    sendWsEvent = frame => socket.send(JSON.stringify(frame))
    socket.send(JSON.stringify({ type: 'connected', eventNodeId: id(20), connectionId: id(21), heartbeatIntervalMillis: '60000' }))
    socket.onMessage(raw => {
      const frame = JSON.parse(String(raw))
      if (frame.type === 'subscribe') socket.send(JSON.stringify({ type: 'subscribed', resource: frame.resource, cursor: '0' }))
    })
  })

  await page.route('**/api/**', route => {
    const url = new URL(route.request().url()), method = route.request().method()
    if (url.pathname === '/api/chat-spaces') {
      if (method !== 'POST') return route.fulfill({ json: spaces })
      expect(route.request().headers()['idempotency-key']).toBeTruthy()
      const item = { ...spaces[0]!, id: id(3), title: route.request().postDataJSON().title, archivedAt: null }
      spaces.push(item)
      return route.fulfill({ json: item })
    }
    const space = spaces.find(item => url.pathname === `/api/chat-spaces/${item.id}`)
    if (space && method === 'PATCH') {
      const body = route.request().postDataJSON()
      expect(body.expectedVersion).toBe(space.version)
      space.version = String(Number(space.version) + 1)
      if (body.title) space.title = body.title
      if ('archived' in body) space.archivedAt = body.archived ? 100 : null
      return route.fulfill({ json: space })
    }
    if (url.pathname === `/api/chat-spaces/${archivedSpace}/tree`) return route.fulfill({ json: archivedTree })
    if (url.pathname.endsWith('/tree')) return route.fulfill({ json: tree })
    if (url.pathname.endsWith(`/threads/${thread}`)) {
      return route.fulfill({
        json: { sessionId: session, sessionTitle: 'Release planning', folderId: root, title: 'Release notes', state: 'RUNNING', version: '0' },
      })
    }
    if (url.pathname.endsWith(`/threads/${secondThread}`)) {
      return route.fulfill({
        json: { sessionId: session, sessionTitle: 'Release planning', folderId: root, title: 'Checklist', state: 'NEEDS_YOU', version: '0' },
      })
    }
    if (url.pathname.endsWith(`/threads/${archivedThread}`)) {
      return route.fulfill({
        json: { sessionId: id(16), sessionTitle: 'Postmortem', folderId: id(15), title: 'Root cause', state: 'NEEDS_YOU', version: '0' },
      })
    }
    if (url.pathname === '/api/harness/interactions') {
      expect(url.searchParams.has('rootThreadId')).toBe(false)
      return route.fulfill({ json: { items: interactions, total: interactions.length, nextCursor: null } })
    }
    if (url.pathname.endsWith('/folders') && method === 'POST') {
      expect(route.request().headers()['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/)
      const body = route.request().postDataJSON() as { name: string; parentId?: string; expectedTreeVersion: string }
      expect(body.expectedTreeVersion).toBe(tree.treeVersion)
      if (body.name === 'Projects') {
        return route.fulfill({
          status: 409,
          contentType: 'application/problem+json',
          body: JSON.stringify({ code: 'FOLDER_NAME_EXISTS', reason: 'FOLDER_NAME_EXISTS', detail: 'Duplicate' }),
        })
      }
      const created = id(nextFolderSeq++)
      tree.treeVersion = String(Number(tree.treeVersion) + 1)
      const node = { id: created, name: body.name, children: [], resources: [] }
      if (body.parentId) {
        const parent = tree.folders.find(folder => folder.id === body.parentId)
        parent?.children.push(node as never)
      } else {
        tree.folders.push(node)
      }
      return route.fulfill({ json: { id: created, treeVersion: tree.treeVersion } })
    }
    const folderMatch = /\/folders\/([0-9a-f-]+)$/.exec(url.pathname)
    if (folderMatch) {
      const targetId = folderMatch[1]!
      const findAndApply = (list: Array<{ id: string; name: string; children: Array<{ id: string; name: string; children: unknown[]; resources: unknown[] }>; resources: unknown[] }>): boolean => {
        const idx = list.findIndex(folder => folder.id === targetId)
        if (idx >= 0) {
          if (method === 'PATCH') {
            const body = route.request().postDataJSON() as { name?: string; parentId?: string | null }
            if (body.name) list[idx]!.name = body.name
            else if ('parentId' in body && body.parentId) {
              const [moved] = list.splice(idx, 1)
              const dest = tree.folders.find(folder => folder.id === body.parentId)
              if (moved) dest?.children.push(moved as never)
            }
          } else if (method === 'DELETE') {
            list.splice(idx, 1)
          }
          return true
        }
        return list.some(folder => findAndApply(folder.children as typeof list))
      }
      if (method === 'DELETE' && targetId === root) {
        return route.fulfill({
          status: 409,
          contentType: 'application/problem+json',
          body: JSON.stringify({ code: 'FOLDER_NOT_EMPTY', reason: 'FOLDER_NOT_EMPTY', detail: 'Not empty' }),
        })
      }
      findAndApply(tree.folders)
      tree.treeVersion = String(Number(tree.treeVersion) + 1)
      return route.fulfill({ json: { treeVersion: tree.treeVersion } })
    }
    if (url.pathname.endsWith(`/resources/CANVAS/${canvas}`) && method === 'PATCH') {
      const body = route.request().postDataJSON()
      expect(body).toMatchObject({ folderId: second, expectedTreeVersion: tree.treeVersion })
      tree.folders[1]!.resources.push(tree.folders[0]!.resources.pop()!)
      tree.treeVersion = String(Number(tree.treeVersion) + 1)
      return route.fulfill({ json: { treeVersion: tree.treeVersion } })
    }
    return route.fulfill({ status: 404, body: `Unhandled ${url.pathname}` })
  })

  return {
    interactions,
    pushInteractionChange: (rootId: string) => sendWsEvent?.({
      type: 'event',
      resource: { kind: 'interactions' },
      name: 'changed',
      data: { rootThreadId: rootId },
    }),
  }
}

test('opening a drawer item gives the pane picker focus and returns to the sidebar trigger on Escape', async ({ page }) => {
  await setup(page)
  await page.setViewportSize({ width: 390, height: 900 })
  await page.goto(path)
  const trigger = page.getByRole('button', { name: 'Open sidebar' })
  await trigger.click()
  await page.getByRole('treeitem', { name: 'Diagram' }).click()
  await expect(page.locator('.workspace-main')).not.toHaveAttribute('inert')
  await trigger.click()
  await page.getByRole('treeitem', { name: 'Diagram' }).click()
  const picker = page.getByRole('dialog', { name: 'Open in pane' })
  await expect(picker).toBeVisible()
  await expect(picker.locator(':focus')).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(picker).toBeHidden()
  await expect(trigger).toBeFocused()
})

test('responsive sidebar drawer, collapse rail, resize and acceptance screenshots', async ({ page }) => {
  await setup(page)
  for (const width of [1440, 1024, 390, 320]) {
    await page.setViewportSize({ width, height: 900 })
    await page.goto(path)
    if (width <= 390) {
      await page.getByRole('button', { name: 'Open sidebar' }).click()
      await expect(page.locator('.workspace-shell')).toHaveAttribute('data-drawer', 'true')
    }
    await expect(page.getByRole('treeitem', { name: 'Projects' })).toBeVisible()
    await page.screenshot({ path: `../reports/r130-shell/workspace-${width}.png`, animations: 'disabled' })
    if (width <= 390) {
      await page.keyboard.press('Escape')
      await expect(page.getByRole('treeitem', { name: 'Projects' })).toBeHidden()
      await expect(page.locator('.workspace-main')).not.toHaveAttribute('inert')
      await expect(page.getByRole('button', { name: 'Open sidebar' })).toBeFocused()
    }
  }
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto(path)
  const separator = page.getByRole('separator', { name: 'Resize sidebar' })
  await separator.focus()
  await separator.press('ArrowRight')
  await expect(separator).toHaveAttribute('aria-valuenow', '280')
  await separator.dblclick()
  await expect(separator).toHaveAttribute('aria-valuenow', '264')
  await page.getByRole('button', { name: 'Close sidebar' }).click()
  await expect(page.locator('.workspace-rail')).toBeVisible()
  await page.getByRole('button', { name: 'Open sidebar' }).click()
  await expect(page.locator('.workspace-sidebar')).toBeVisible()
})

test('creates root and child folders inline, handles errors, renames with F2 and deletes', async ({ page }) => {
  await setup(page)
  await page.goto(path)

  // Root inline folder validation & Escape cancel
  await page.getByRole('button', { name: 'New folder' }).click()
  const emptyInput = page.getByRole('textbox', { name: 'New folder' })
  await expect(emptyInput).toBeFocused()
  await emptyInput.press('Enter')
  await expect(page.getByRole('alert')).toHaveText('Enter a name.')
  await emptyInput.press('Escape')
  await expect(page.getByRole('textbox', { name: 'New folder' })).toHaveCount(0)

  // Root inline create with 409 duplicate recovery
  await page.getByRole('button', { name: 'New folder' }).click()
  const input = page.getByRole('textbox', { name: 'New folder' })
  await input.fill('Projects')
  await input.press('Enter')
  await expect(page.getByRole('alert')).toHaveText('This name already exists.')
  await expect(input).toHaveValue('Projects')
  await input.fill('Notes')
  await input.press('Enter')

  // Child folder inline create via row action menu
  const notesRow = page.getByRole('treeitem', { name: 'Notes', exact: true })
  await notesRow.hover()
  await page.getByRole('button', { name: 'Actions for Notes' }).click()
  await page.getByRole('menuitem', { name: 'New folder' }).click()
  const childInput = page.getByRole('textbox', { name: 'New folder' })
  await childInput.fill('Drafts')
  await childInput.press('Enter')
  const draftsRow = page.getByRole('treeitem', { name: 'Drafts', exact: true })
  await expect(draftsRow).toHaveAttribute('aria-level', '2')

  // Delete non-empty error keeps row, deleting empty succeeds
  await notesRow.press('F2')
  const rename = page.getByRole('textbox', { name: 'Rename Notes' })
  await rename.fill('Specs')
  await rename.press('Enter')
  await expect(page.getByRole('treeitem', { name: 'Specs' })).toBeVisible()

  await draftsRow.press('Delete')
  await page.getByRole('alertdialog').getByRole('button', { name: 'Confirm' }).click()
  await expect(draftsRow).toHaveCount(0)

  const projectsRow = page.getByRole('treeitem', { name: 'Projects', exact: true })
  await projectsRow.press('Delete')
  await page.getByRole('alertdialog').getByRole('button', { name: 'Confirm' }).click()
  await expect(page.getByRole('alertdialog')).toContainText('The folder is not empty.')
  await page.getByRole('alertdialog').getByRole('button', { name: 'Cancel' }).click()
})

test('switches, creates, renames, archives and restores Chat Spaces', async ({ page }) => {
  await setup(page)
  await page.goto(path)
  const trigger = page.locator('.workspace-space-trigger')
  await trigger.click()
  await page.getByRole('menuitem', { name: 'Support' }).click()
  await expect(trigger).toHaveText('Support')
  await trigger.click()
  await page.getByRole('menuitem', { name: 'New Chat Space' }).click()
  await page.getByRole('dialog').getByRole('textbox').fill('Research')
  await page.getByRole('dialog').getByRole('button', { name: 'Confirm' }).click()
  await expect(trigger).toHaveText('Research')
  await trigger.click()
  await page.getByRole('menuitem', { name: 'Research' }).press('F2')
  await page.getByRole('textbox', { name: 'Rename Research' }).fill('Lab')
  await page.getByRole('textbox', { name: 'Rename Research' }).press('Enter')
  await expect(trigger).toHaveText('Lab')
  await trigger.click()
  await page.getByRole('menuitem', { name: 'Archive current space' }).click()
  await expect(trigger).toHaveText('Studio')
  await trigger.click()
  await page.getByRole('menuitem', { name: 'Archived' }).click()
  await page.getByRole('menuitem', { name: 'Lab' }).click()
  await expect(trigger).toHaveText('Lab')
})

test('refreshes inbox via WebSocket, deduplicates roots and switches to archived space on click', async ({ page }) => {
  const { interactions, pushInteractionChange } = await setup(page)
  await page.goto(path)
  const inbox = page.getByRole('button', { name: 'Pending interactions' })
  await expect(inbox).toHaveText('1')

  // Simulate WS interactions changed pushing an archived-space and background-task interaction
  interactions.push(
    {
      type: 'INPUT',
      toolInvocationId: id(24),
      threadId: archivedThread,
      rootThreadId: archivedThread,
      sessionId: id(16),
      toolCallId: 'call-3',
      toolName: 'question',
      argumentsJson: '{}',
      createdAt: 5,
      owner: { kind: 'CHAT_SPACE', id: archivedSpace, title: 'Old Space' },
    },
    {
      type: 'INPUT',
      toolInvocationId: id(25),
      threadId: backgroundThread,
      rootThreadId: backgroundThread,
      sessionId: id(17),
      toolCallId: 'call-4',
      toolName: 'question',
      argumentsJson: '{}',
      createdAt: 9,
      owner: { kind: 'SYSTEM', id: id(18), title: '' },
    },
  )
  pushInteractionChange(archivedThread)
  await expect(inbox).toHaveText('3')

  await inbox.click()
  const panel = page.getByRole('region', { name: 'Pending interactions' })
  await expect(panel.getByRole('button')).toHaveCount(3)
  await expect(panel.getByRole('button', { name: /Background task/ })).toBeDisabled()
  await panel.getByRole('button', { name: 'Root cause Old Space' }).click()
  await expect(page.locator('.workspace-space-trigger')).toHaveText('Old Space')
  await expect(page.getByTestId('thread')).toHaveText(archivedThread)
})

test('moves a canvas with native drag', async ({ page }) => {
  await setup(page)
  await page.goto(path)
  const diagram = page.getByRole('treeitem', { name: 'Diagram' })
  await diagram.dragTo(page.getByRole('treeitem', { name: 'Archive' }))
  await expect(page.locator(`[data-tree-row="${second}"]`)).toHaveAttribute('aria-expanded', 'true')
  await expect(diagram).toBeVisible()
})

test('moves a folder with touch long press', async ({ browser }) => {
  const context = await browser.newContext({ hasTouch: true, viewport: { width: 1024, height: 900 } })
  const page = await context.newPage()
  await setup(page)
  await page.goto(path)
  const cdp = await context.newCDPSession(page)
  const archive = page.getByRole('treeitem', { name: 'Archive', exact: true })
  const projects = page.getByRole('treeitem', { name: 'Projects', exact: true })
  const from = (await archive.boundingBox())!
  const to = (await projects.boundingBox())!

  const x1 = Math.round(from.x + from.width / 2), y1 = Math.round(from.y + from.height / 2)
  const x2 = Math.round(to.x + to.width / 2), y2 = Math.round(to.y + to.height / 2)
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: x1, y: y1, id: 1 }] })
  await page.waitForTimeout(430)
  await expect(archive).toHaveAttribute('data-tree-dragging', '')
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: x2, y: y2, id: 1 }] })
  await expect(projects).toHaveAttribute('data-tree-drop', 'into')
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
  await expect(archive).toHaveAttribute('aria-level', '2')
  await context.close()
})

test('verifies deep link, status grouping, open flow deduplication, unsent confirmation and openBeside', async ({ page }) => {
  await setup(page)
  await page.goto(`${path}?thread=${thread}`)
  await expect(page.getByTestId('thread')).toHaveText(thread)

  // Status view shows current space groups
  await page.getByRole('tab', { name: 'Status' }).click()
  await expect(page.getByRole('heading', { name: 'Needs you' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Running' })).toBeVisible()

  // Split right -> open Checklist directly in clean right draft, then fill left? No: fill right draft first
  await page.getByRole('button', { name: 'Split right' }).click()
  await page.getByRole('textbox', { name: 'Draft text' }).fill('Unsent')

  // Open Release notes into right pane -> Picker selects active right pane -> confirms unsent -> moves Thread without duplicating
  await page.getByRole('button', { name: 'Release notes', exact: true }).click()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('alertdialog', { name: 'Discard unsent input?' })).toBeVisible()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Confirm' }).click()
  await expect(page.getByTestId('thread')).toHaveCount(1)
  await expect(page.getByTestId('thread')).toHaveText(thread)
  await expect(page.getByRole('textbox', { name: 'Draft text' })).toHaveValue('')

  // Clicking Checklist in Status tab opens PanePicker because right pane is focused; press 'a' to open in left clean draft
  await page.getByRole('button', { name: 'Checklist', exact: true }).click()
  await page.keyboard.press('a')
  await expect(page.getByTestId('thread')).toHaveCount(2)

  // openBeside from a pane with no room to split shows a close-pane notice; closing one pane lets openBeside succeed
  await page.getByLabel('Checklist').getByRole('button', { name: 'Split down' }).click()
  await page.getByRole('button', { name: 'Open beside' }).click()
  await expect(page.getByRole('alertdialog', { name: 'Close a pane before opening another' })).toBeVisible()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Confirm' }).click()
  await page.getByLabel('Checklist').getByRole('button', { name: 'Close pane' }).click()
  await page.getByRole('button', { name: 'Open beside' }).click()
  await expect(page.getByTestId('canvas')).toBeVisible()
})
