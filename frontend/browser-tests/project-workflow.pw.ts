import type { WebSocketRoute } from '@playwright/test'
import { expect, test, type Page } from './fixture'
import type { ProjectDTO } from '@/features/projects/types'

const ID = 'a0000000-0000-0000-0000-000000000001'
const URL = '/browser-tests/project-workflow-harness.html'
function project(): ProjectDTO {
  return {
    id: ID, title: 'Workflow Project', description: 'Structured graph',
    workflow: { states: [
      { state: 'INIT', name: '待开始', next: ['WORK'] },
      { state: 'WORK', name: '处理中', next: ['DONE'] },
      { state: 'REVIEW', name: '评审', next: ['DONE'], enabled: false },
      { state: 'OLD', name: '归档来源', next: [], enabled: false },
      { state: 'BLOCKED', name: '阻塞' }, { state: 'DONE', name: '完成' },
    ] },
    yoloEnabled: false, nextIssueNumber: '1', version: '1', archivedAt: null,
    createdAt: '2026-09-20T00:00:00Z', updatedAt: '2026-09-20T00:00:00Z',
  }
}

async function transport(page: Page) {
  const state = {
    project: project(), references: ['OLD'], fail: false,
    snapshotReads: 0, writes: [] as Record<string, unknown>[],
    socket: null as WebSocketRoute | null,
  }
  await page.routeWebSocket('**/api/events/v1', (socket) => {
    state.socket = socket
    socket.onMessage((raw) => {
      const message = JSON.parse(String(raw))
      if (message.type === 'subscribe') socket.send(JSON.stringify({
        version: 1, type: 'subscribed', resource: message.resource, cursor: '0',
      }))
    })
  })
  await page.route('**/api/**', async (route) => {
    const path = new globalThis.URL(route.request().url()).pathname
    if (path === `/api/projects/${ID}/snapshot`) {
      state.snapshotReads++
      if (state.fail) {
        await route.fulfill({ status: 503, json: { status: 503, message: 'Reference service unavailable' } })
      } else {
        await route.fulfill({ json: { status: 200, data: {
          project: state.project, issues: [], referencedStateCodes: state.references,
        } } })
      }
    } else if (route.request().method() === 'PUT') {
      state.writes.push(route.request().postDataJSON())
      await route.fulfill({ json: { status: 200, data: { ...state.project, version: '10' } } })
    } else if (path === '/api/projects') {
      await route.fulfill({ json: { status: 200, data: [state.project] } })
    } else if (path === '/api/ai/catalog/agents') {
      await route.fulfill({ json: { status: 200, data: { results: [], totalCount: 0, pageNumber: 1, pageSize: 50 } } })
    } else if (path === '/api/harness/environments') {
      await route.fulfill({ json: { status: 200, data: [] } })
    } else {
      throw new Error(`Unexpected harness request: ${path}`)
    }
  })
  return state
}

test('single header, back and explicit failed-load retry', async ({ page }) => {
  const state = await transport(page)
  state.fail = true
  await page.goto(URL)
  await expect(page.getByRole('button', { name: '重试' })).toBeVisible()
  await expect(page.locator('header')).toHaveCount(1)
  state.fail = false
  await page.getByRole('button', { name: '重试' }).click()
  await expect(page.getByRole('heading', { name: 'Workflow Project', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '刷新', exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: '返回项目列表' }).click()
  await expect(page).toHaveURL(/\/list$/)
})

test('structured multi-edge editing, real Tabs styling and archived-only reference protection', async ({ page }) => {
  const state = await transport(page)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto(URL)
  await page.getByRole('button', { name: '编辑 / 工作流' }).click()
  const dialog = page.getByRole('dialog')
  const workflow = dialog.getByRole('tab', { name: '工作流', exact: true })
  await workflow.click()
  await expect(workflow).toHaveCSS('font-weight', '600')
  await expect(dialog.getByRole('tab', { name: '基础信息' })).toHaveCSS('font-weight', '500')
  const color = await workflow.evaluate((el) => getComputedStyle(el).borderBottomColor)
  expect(color).not.toBe('rgba(0, 0, 0, 0)')
  await expect(dialog.getByRole('button', { name: '删除 归档来源' })).toBeDisabled()
  // 共享 Checkbox 的原生 input 视觉隐藏；点击真实可见标签，不强制绕过可操作性检查。
  await dialog.getByText('评审（REVIEW）', { exact: true }).click()
  await expect(dialog.getByRole('checkbox', { name: '处理中（WORK）' })).toBeChecked()
  await dialog.getByRole('button', { name: '保存工作流' }).click()
  await expect.poll(() => state.writes.length).toBe(1)
  expect(state.writes[0]).toMatchObject({
    expectedVersion: '1',
    workflow: { states: [
      expect.objectContaining({ state: 'INIT', next: ['WORK', 'REVIEW'] }),
      ...project().workflow.states.slice(1).map((s) => expect.objectContaining({ state: s.state })),
    ] },
  })
  await page.screenshot({ path: '../reports/layout/project-workflow-desktop.png' })
})

test('single WS push updates constraints, not draft or frozen CAS; list entry reads references', async ({ page }) => {
  const state = await transport(page)
  await page.goto(URL)
  await page.getByRole('button', { name: '返回项目列表' }).click()
  await page.getByRole('button', { name: '编辑项目 Workflow Project' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByRole('tab', { name: '工作流', exact: true }).click()
  await expect.poll(() => state.snapshotReads).toBeGreaterThan(0)
  await dialog.getByLabel('显示名称').fill('Local Init')
  state.project = { ...state.project, version: '9', title: 'Remote Project' }
  state.references = ['OLD', 'REVIEW']
  state.socket!.send(JSON.stringify({
    version: 1, type: 'event', resource: { kind: 'projects' }, name: 'changed', data: { projectId: ID },
  }))
  await expect(dialog.getByRole('button', { name: '删除 评审' })).toBeDisabled()
  await expect(dialog.getByLabel('显示名称')).toHaveValue('Local Init')
  await dialog.getByRole('button', { name: '保存工作流' }).click()
  await expect.poll(() => state.writes.length).toBe(1)
  expect(state.writes[0]).toMatchObject({ expectedVersion: '1' })
})

test('narrow screen stacks the stage form without page or dialog horizontal overflow', async ({ page }) => {
  await transport(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto(URL)
  await page.getByRole('button', { name: '编辑 / 工作流' }).click()
  await page.getByRole('tab', { name: '工作流', exact: true }).click()
  const dialog = page.getByRole('dialog')
  const dimensions = await dialog.evaluate((el) => ({
    width: el.getBoundingClientRect().width, scroll: el.scrollWidth, client: el.clientWidth,
  }))
  expect(dimensions.width).toBeLessThanOrEqual(390)
  expect(dimensions.scroll).toBeLessThanOrEqual(dimensions.client + 1)
  const list = await dialog.getByRole('list').boundingBox()
  const form = await dialog.locator('.workflow-stage-form').boundingBox()
  expect(form!.y).toBeGreaterThan(list!.y)
  await dialog.getByLabel('显示名称').fill('窄屏草稿')
  await expect(dialog.getByLabel('显示名称')).toHaveValue('窄屏草稿')
  await page.screenshot({ path: '../reports/layout/project-workflow-narrow.png' })
})
