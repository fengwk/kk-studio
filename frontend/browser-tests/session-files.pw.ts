import type { Route } from '@playwright/test'
import { expect, test, type Page } from './fixture'

const HARNESS_URL = '/browser-tests/session-files-harness.html'
const SESSION_ID = '11111111-2222-4333-8444-555555555555'
const OWNER_ID = 'a1000000-0000-4000-8000-000000000009'
const FILES_API = `/api/sessions/${SESSION_ID}/files`
const SESSION_ROOT = `vfs:/sessions/${SESSION_ID}/`

const IMAGE_SVG = [
  '<svg xmlns="http://www.w3.org/2000/svg" width="320" height="180" viewBox="0 0 320 180">',
  '<rect width="320" height="180" fill="#173d28"/>',
  '<circle cx="96" cy="90" r="42" fill="#71e79a"/>',
  '<rect x="164" y="64" width="112" height="52" rx="8" fill="#77c7f5"/>',
  '</svg>',
].join('')

interface FileEntry {
  name: string
  path: string
  directory: boolean
  sizeBytes?: string | null
  revision?: number | null
  updatedAt?: string | null
}

interface RecordedRequest {
  endpoint: '' | '/content' | '/download'
  params: URLSearchParams
}

function listing(path: string, entries: FileEntry[]) {
  return {
    sessionId: SESSION_ID,
    path,
    entries,
  }
}

const ROOT_LISTING = listing(SESSION_ROOT, [
  { name: 'docs/', path: 'docs/', directory: true },
  { name: 'notes.txt', path: 'notes.txt', directory: false, sizeBytes: '42', revision: 1, updatedAt: '1790812800000' },
  { name: 'diagram.svg', path: 'diagram.svg', directory: false, sizeBytes: '260', revision: 1, updatedAt: '1790812800000' },
  { name: 'binary.bin', path: 'binary.bin', directory: false, sizeBytes: '4', revision: 1, updatedAt: '1790812800000' },
  { name: 'missing.txt', path: 'missing.txt', directory: false, sizeBytes: '1', revision: 1, updatedAt: '1790812800000' },
])

const DOCS_LISTING = listing(`${SESSION_ROOT}docs/`, [
  { name: 'readme.md', path: 'docs/readme.md', directory: false, sizeBytes: '12', revision: 1, updatedAt: '1790812800000' },
])

const FIRST_TEXT_WINDOW = {
  sessionId: SESSION_ID,
  path: `${SESSION_ROOT}notes.txt`,
  sizeBytes: '64',
  revision: 2,
  updatedAt: '1790812800000',
  text: [
    `path: ${SESSION_ROOT}notes.txt`,
    'ends_with_newline: yes',
    'range: 1:1-2:9',
    'truncated: yes',
    'truncation_reason: line_limit',
    'next: 3:1',
    '',
    '1|alpha',
    '2|beta',
    '',
    '[TRUNCATED: More file content remains. Next position: line 3, column 1.]',
  ].join('\n'),
}

const SECOND_TEXT_WINDOW = {
  sessionId: SESSION_ID,
  path: `${SESSION_ROOT}notes.txt`,
  sizeBytes: '64',
  revision: 2,
  updatedAt: '1790812800000',
  text: [
    `path: ${SESSION_ROOT}notes.txt`,
    'ends_with_newline: no',
    'range: 3:1-3:7',
    '',
    '3|gamma',
  ].join('\n'),
}

const README_WINDOW = {
  sessionId: SESSION_ID,
  path: `${SESSION_ROOT}docs/readme.md`,
  sizeBytes: '12',
  revision: 1,
  updatedAt: '1790812800000',
  text: [
    `path: ${SESSION_ROOT}docs/readme.md`,
    'ends_with_newline: yes',
    'range: 1:1-2:6',
    '',
    '1|hello',
    '2|world',
  ].join('\n'),
}

function problem(status: number, code: string, title: string, detail: string) {
  return {
    type: 'about:blank',
    status,
    code,
    title,
    detail,
  }
}

async function fulfillJson(route: Route, status: number, body: unknown) {
  await route.fulfill({ status, contentType: status >= 400 ? 'application/problem+json' : 'application/json', json: body })
}

/** 按生成契约拦截三个只读端点，并记录每次请求的参数供断言。 */
async function installSessionFilesApi(page: Page): Promise<RecordedRequest[]> {
  const requests: RecordedRequest[] = []
  await page.route(
    (url) => new URL(url).pathname.startsWith(FILES_API),
    async (route) => {
      const request = route.request()
      const url = new URL(request.url())
      const endpoint = url.pathname.slice(FILES_API.length) as RecordedRequest['endpoint']
      requests.push({ endpoint, params: url.searchParams })
      const path = url.searchParams.get('path')

      if (endpoint === '') {
        if (path == null) {
          await fulfillJson(route, 200, ROOT_LISTING)
          return
        }
        if (path === `${SESSION_ROOT}docs/`) {
          await fulfillJson(route, 200, DOCS_LISTING)
          return
        }
        await fulfillJson(route, 404, problem(
          404, 'not_found', 'The requested resource was not found.', `No such file: ${path}`,
        ))
        return
      }

      if (endpoint === '/content') {
        if (path === `${SESSION_ROOT}docs/readme.md`) {
          await fulfillJson(route, 200, README_WINDOW)
          return
        }
        if (path === `${SESSION_ROOT}binary.bin`) {
          await fulfillJson(route, 422, problem(
            422, 'validation', 'HTTP error (status 422).', 'file appears to be binary',
          ))
          return
        }
        if (path === `${SESSION_ROOT}missing.txt`) {
          await fulfillJson(route, 404, problem(
            404, 'not_found', 'The requested resource was not found.', `No such file: ${path}`,
          ))
          return
        }
        const offset = url.searchParams.get('offset') ?? '1'
        await fulfillJson(route, 200, offset === '3' ? SECOND_TEXT_WINDOW : FIRST_TEXT_WINDOW)
        return
      }

      if (path === `${SESSION_ROOT}diagram.svg`) {
        await route.fulfill({
          status: 200,
          headers: {
            'Content-Type': 'image/svg+xml',
            'Content-Disposition': 'attachment; filename="diagram.svg"; filename*=UTF-8\'\'diagram.svg',
          },
          body: Buffer.from(IMAGE_SVG, 'utf8'),
        })
        return
      }
      if (path === `${SESSION_ROOT}notes.txt`) {
        await route.fulfill({
          status: 200,
          headers: {
            'Content-Type': 'text/plain',
            'Content-Disposition': 'attachment; filename="notes.txt"; filename*=UTF-8\'\'notes.txt',
          },
          body: Buffer.from('alpha\nbeta\ngamma\n', 'utf8'),
        })
        return
      }
      await fulfillJson(route, 404, problem(
        404, 'not_found', 'The requested resource was not found.', `No such file: ${path}`,
      ))
    },
  )
  return requests
}

function sessionFilesDialog(page: Page) {
  return page.getByRole('dialog', { name: '会话文件' })
}

test('browses directories and pages through a text window', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1280, height: 800 })
  const requests = await installSessionFilesApi(page)
  await page.goto(HARNESS_URL)

  const dialog = sessionFilesDialog(page)
  await expect(dialog).toBeVisible()
  await expect(dialog.getByTitle('docs/')).toBeVisible()
  await expect(dialog.getByTitle('notes.txt')).toBeVisible()
  await expect(dialog.getByTitle('diagram.svg')).toBeVisible()

  // 根目录省略 path，owner 参数来自当前对话。
  const rootList = requests.find((request) => request.endpoint === '')
  expect(rootList?.params.get('ownerKind')).toBe('CHAT')
  expect(rootList?.params.get('ownerId')).toBe(OWNER_ID)
  expect(rootList?.params.get('path')).toBeNull()

  await dialog.getByTitle('docs/').click()
  await expect(dialog.getByTitle('docs/readme.md')).toBeVisible()
  await expect.poll(() => requests.at(-1)?.params.get('path')).toBe(`${SESSION_ROOT}docs/`)

  await dialog.getByTestId('session-files-up').click()
  await expect(dialog.getByTitle('notes.txt')).toBeVisible()

  await dialog.getByTitle('notes.txt').click()
  const text = dialog.getByTestId('session-file-text')
  await expect(text).toContainText('1|alpha')
  await expect(text).toContainText('2|beta')
  await expect(dialog.getByTestId('session-files-range')).toContainText('行 1:1-2:9')
  await expect(dialog.getByText('窗口已截断')).toBeVisible()
  expect(requests.some((request) => request.endpoint === '/content'
    && request.params.get('path') === `${SESSION_ROOT}notes.txt`
    && request.params.get('offset') === '1'
    && request.params.get('limit') === '500')).toBe(true)

  await dialog.getByRole('button', { name: '下一页' }).click()
  await expect(text).toContainText('3|gamma')
  await expect(dialog.getByTestId('session-files-range')).toContainText('行 3:1-3:7')
  await dialog.getByRole('button', { name: '上一页' }).click()
  await expect(text).toContainText('1|alpha')

  await page.screenshot({ path: testInfo.outputPath('session-files-desktop-text.png') })
})

test('previews an image and downloads the original file', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1280, height: 800 })
  await installSessionFilesApi(page)
  await page.goto(HARNESS_URL)

  const dialog = sessionFilesDialog(page)
  await dialog.getByTitle('diagram.svg').click()
  const image = dialog.getByRole('img', { name: 'diagram.svg' })
  await expect(image).toBeVisible()
  await expect.poll(() => image.evaluate((element: HTMLImageElement) => element.naturalWidth)).toBe(320)

  await page.screenshot({ path: testInfo.outputPath('session-files-desktop-image.png') })

  const [download] = await Promise.all([
    page.waitForEvent('download'),
    dialog.getByRole('button', { name: '下载', exact: true }).click(),
  ])
  expect(download.suggestedFilename()).toBe('diagram.svg')
})

test('shows not found and unreadable file states', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1280, height: 800 })
  await installSessionFilesApi(page)
  await page.goto(HARNESS_URL)

  const dialog = sessionFilesDialog(page)
  await dialog.getByTitle('missing.txt').click()
  await expect(dialog.getByText('文件不存在或无权访问')).toBeVisible()
  await expect(dialog.getByText(`No such file: ${SESSION_ROOT}missing.txt`)).toBeVisible()

  await dialog.getByTitle('binary.bin').click()
  await expect(dialog.getByText('此文件不能作为文本预览')).toBeVisible()
  await expect(dialog.getByText('file appears to be binary')).toBeVisible()
  await expect(dialog.getByRole('button', { name: '下载', exact: true })).toBeVisible()

  await page.screenshot({ path: testInfo.outputPath('session-files-desktop-error.png') })
})

test('fills the mobile viewport and keeps touch targets', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await installSessionFilesApi(page)
  await page.goto(HARNESS_URL)

  const dialog = sessionFilesDialog(page)
  await expect(dialog).toBeVisible()
  const box = (await dialog.boundingBox())!
  expect(box.x).toBeCloseTo(0, 0)
  expect(box.y).toBeCloseTo(0, 0)
  expect(box.width).toBeCloseTo(390, 0)
  expect(box.height).toBeCloseTo(844, 0)

  for (const button of await dialog.getByRole('button').all()) {
    if (!(await button.isVisible())) {
      continue
    }
    const bounds = (await button.boundingBox())!
    expect(bounds.width).toBeGreaterThanOrEqual(44)
    expect(bounds.height).toBeGreaterThanOrEqual(44)
  }

  await dialog.getByTitle('notes.txt').click()
  await expect(dialog.getByTestId('session-file-text')).toContainText('1|alpha')
  await page.screenshot({ path: testInfo.outputPath('session-files-mobile.png') })
})
