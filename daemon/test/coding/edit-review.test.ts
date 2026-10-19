import { afterEach, describe, expect, it } from 'vitest'
import { createHash } from 'node:crypto'
import { mkdtemp, readFile, realpath, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileCapabilities } from '../../src/coding/files.js'
import type { CapabilityCall, CapabilityContext, CapabilityExecutor, CapabilityResult, ExpectedFile } from '../../src/contracts.js'
import { makeContext, textOf } from './helpers.js'

const tempDirs: string[] = []

async function tempDir(): Promise<string> {
  // Match the canonical review path even when the OS temp directory is an alias.
  const dir = await realpath(await mkdtemp(join(tmpdir(), 'kk-edit-review-')))
  tempDirs.push(dir)
  return dir
}

afterEach(async () => {
  await Promise.all(tempDirs.splice(0).map((dir) => rm(dir, { recursive: true, force: true })))
})

const editPreview = fileCapabilities['fs.edit.preview']!
const edit = fileCapabilities['fs.edit']!

function invoke(
  executor: CapabilityExecutor,
  capabilityId: string,
  args: Record<string, unknown>,
  context: CapabilityContext,
  expectedFile?: ExpectedFile,
): Promise<CapabilityResult> {
  const call: CapabilityCall = {
    invocationId: 'inv-test',
    capabilityId,
    arguments: args,
    timeoutMillis: 60000,
    expectedFile,
  }
  return executor(call, context)
}

describe('EditReview suite', () => {
  it('1. preview never writes and hash matches createHash on readFile', async () => {
    const dir = await tempDir()
    const file = join(dir, 'file.txt')
    const originalContent = 'hello\nworld\n'
    await writeFile(file, originalContent, 'utf8')

    const rawBytes = await readFile(file)
    const expectedHash = createHash('sha256').update(rawBytes).digest('hex')

    const context = makeContext(dir)
    const result = await invoke(
      editPreview,
      'fs.edit.preview',
      { path: file, old_string: 'world', new_string: 'kk-studio' },
      context,
    )

    expect(result.error).toBe(false)
    expect(result.contents[0]?.type).toBe('json')
    const review = (result.contents[0] as { type: 'json'; json: Record<string, unknown> }).json
    expect(review.path).toBe(file)
    expect(review.sha256).toBe(expectedHash)
    expect(review.diff).toContain('- 2|world')
    expect(review.diff).toContain('+ 2|kk-studio')

    // Verify file on disk is unchanged
    const afterBytes = await readFile(file)
    expect(afterBytes.toString('utf8')).toBe(originalContent)
  })

  it('2. multihunk replace_all actual numbered diff same as success details', async () => {
    const dir = await tempDir()
    const file = join(dir, 'multi.txt')
    const lines = [
      'line 01',
      'line 02',
      'line 03',
      'line 04',
      'line 05',
      'line 06',
      'target alpha',
      'line 08',
      'line 09',
      'line 10',
      'line 11',
      'line 12',
      'target beta',
      'line 14',
      'line 15',
      'line 16',
      'line 17',
      'line 18',
      'line 19',
    ]
    await writeFile(file, lines.join('\n') + '\n', 'utf8')
    const rawBytes = await readFile(file)
    const expectedHash = createHash('sha256').update(rawBytes).digest('hex')

    const context = makeContext(dir)
    const previewResult = await invoke(
      editPreview,
      'fs.edit.preview',
      { path: file, old_string: 'target', new_string: 'replaced', replace_all: true },
      context,
    )

    expect(previewResult.error).toBe(false)
    const previewReview = (previewResult.contents[0] as { type: 'json'; json: { path: string; diff: string; sha256: string } }).json
    expect(previewReview.diff).toContain('- 7|target alpha')
    expect(previewReview.diff).toContain('+ 7|replaced alpha')
    expect(previewReview.diff).toContain('-13|target beta')
    expect(previewReview.diff).toContain('+13|replaced beta')
    expect(previewReview.diff).toContain('...')

    const commitResult = await invoke(
      edit,
      'fs.edit',
      { path: file, old_string: 'target', new_string: 'replaced', replace_all: true },
      context,
      { path: file, sha256: previewReview.sha256 },
    )

    expect(commitResult.error).toBe(false)
    expect(textOf(commitResult)).toBe('Edited successfully.\nReplacements: 2')
    expect(commitResult.details.editReview).toEqual(previewReview)

    const updated = await readFile(file, 'utf8')
    expect(updated).toContain('replaced alpha')
    expect(updated).toContain('replaced beta')
    expect(updated).not.toContain('target')
  })

  it('3. UTF16 BOM CRLF preserved and expected hash', async () => {
    const dir = await tempDir()
    const file = join(dir, 'utf16.txt')
    const bom = Buffer.from([0xff, 0xfe])
    const body = '第一行\r\n第二行\r\n第三行\r\n'
    const rawBytes = Buffer.concat([bom, Buffer.from(body, 'utf16le')])
    await writeFile(file, rawBytes)

    const expectedHash = createHash('sha256').update(rawBytes).digest('hex')
    const context = makeContext(dir)

    const previewResult = await invoke(
      editPreview,
      'fs.edit.preview',
      { path: file, old_string: '第二行', new_string: '修改行' },
      context,
    )

    expect(previewResult.error).toBe(false)
    const previewReview = (previewResult.contents[0] as { type: 'json'; json: { path: string; diff: string; sha256: string } }).json
    expect(previewReview.sha256).toBe(expectedHash)
    expect(previewReview.diff).toContain('- 2|第二行')
    expect(previewReview.diff).toContain('+ 2|修改行')

    const commitResult = await invoke(
      edit,
      'fs.edit',
      { path: file, old_string: '第二行', new_string: '修改行' },
      context,
      { path: file, sha256: expectedHash },
    )

    expect(commitResult.error).toBe(false)
    expect(commitResult.details.editReview).toEqual(previewReview)

    const writtenBytes = await readFile(file)
    expect(Array.from(writtenBytes.subarray(0, 2))).toEqual([0xff, 0xfe])
    const decoded = writtenBytes.subarray(2).toString('utf16le')
    expect(decoded).toBe('第一行\r\n修改行\r\n第三行\r\n')
  })

  it('4. changed hash refused unchanged bytes', async () => {
    const dir = await tempDir()
    const file = join(dir, 'file.txt')
    const originalContent = 'initial content\n'
    await writeFile(file, originalContent, 'utf8')

    const context = makeContext(dir)
    const badHash = '0'.repeat(64)

    const result = await invoke(
      edit,
      'fs.edit',
      { path: file, old_string: 'initial', new_string: 'modified' },
      context,
      { path: file, sha256: badHash },
    )

    expect(result.error).toBe(true)
    expect(textOf(result)).toContain('File changed since edit review; nothing was written. Review a new edit before approving it.')

    const currentContent = await readFile(file, 'utf8')
    expect(currentContent).toBe(originalContent)
  })

  it('5. wrong path refused', async () => {
    const dir = await tempDir()
    const file = join(dir, 'file.txt')
    const originalContent = 'initial content\n'
    await writeFile(file, originalContent, 'utf8')

    const rawBytes = await readFile(file)
    const hash = createHash('sha256').update(rawBytes).digest('hex')
    const context = makeContext(dir)

    const result = await invoke(
      edit,
      'fs.edit',
      { path: file, old_string: 'initial', new_string: 'modified' },
      context,
      { path: join(dir, 'other.txt'), sha256: hash },
    )

    expect(result.error).toBe(true)
    expect(textOf(result)).toContain('File changed since edit review; nothing was written. Review a new edit before approving it.')

    const currentContent = await readFile(file, 'utf8')
    expect(currentContent).toBe(originalContent)
  })

  it('6. Promise.all two approved executes only one commit', async () => {
    const dir = await tempDir()
    const file = join(dir, 'file.txt')
    const originalContent = 'initial\n'
    await writeFile(file, originalContent, 'utf8')

    const rawBytes = await readFile(file)
    const hash = createHash('sha256').update(rawBytes).digest('hex')
    const context = makeContext(dir)

    const call1 = invoke(
      edit,
      'fs.edit',
      { path: file, old_string: 'initial', new_string: 'winner1' },
      context,
      { path: file, sha256: hash },
    )
    const call2 = invoke(
      edit,
      'fs.edit',
      { path: file, old_string: 'initial', new_string: 'winner2' },
      context,
      { path: file, sha256: hash },
    )

    const [res1, res2] = await Promise.all([call1, call2])
    const errors = [res1.error, res2.error]
    expect(errors.filter((e) => !e).length).toBe(1)
    expect(errors.filter((e) => e).length).toBe(1)

    const failed = res1.error ? res1 : res2
    expect(textOf(failed)).toContain('File changed since edit review; nothing was written. Review a new edit before approving it.')

    const finalContent = await readFile(file, 'utf8')
    expect(finalContent === 'winner1\n' || finalContent === 'winner2\n').toBe(true)
  })

  it('7. >30KiB changed line complete (no trunc)', async () => {
    const dir = await tempDir()
    const file = join(dir, 'large.txt')
    await writeFile(file, 'old\n', 'utf8')

    const largeLine = 'x'.repeat(40 * 1024)
    const context = makeContext(dir)

    const previewResult = await invoke(
      editPreview,
      'fs.edit.preview',
      { path: file, old_string: 'old', new_string: largeLine },
      context,
    )

    expect(previewResult.error).toBe(false)
    const review = (previewResult.contents[0] as { type: 'json'; json: { diff: string; sha256: string } }).json
    expect(review.diff.length).toBeGreaterThan(40 * 1024)
    expect(review.diff).not.toContain('truncated')
    expect(review.diff).toContain(`+ 1|${largeLine}`)

    const commitResult = await invoke(
      edit,
      'fs.edit',
      { path: file, old_string: 'old', new_string: largeLine },
      context,
      { path: file, sha256: review.sha256 },
    )

    expect(commitResult.error).toBe(false)
    const commitReview = commitResult.details.editReview as { diff: string }
    expect(commitReview.diff.length).toBeGreaterThan(40 * 1024)
    expect(commitReview.diff).not.toContain('truncated')
  })

  it('8. ~600KiB one old and new characters diff exceeds 1MiB refuses preview and commit', async () => {
    const dir = await tempDir()
    const file = join(dir, 'huge.txt')
    const oldLine = 'a'.repeat(600 * 1024)
    const newLine = 'b'.repeat(600 * 1024)
    await writeFile(file, oldLine + '\n', 'utf8')

    const rawBytes = await readFile(file)
    const hash = createHash('sha256').update(rawBytes).digest('hex')
    const context = makeContext(dir)

    const previewResult = await invoke(
      editPreview,
      'fs.edit.preview',
      { path: file, old_string: oldLine, new_string: newLine },
      context,
    )
    expect(previewResult.error).toBe(true)
    expect(textOf(previewResult)).toContain('Complete edit diff exceeds the review transport limit; split this edit into smaller changes.')

    const commitResult = await invoke(
      edit,
      'fs.edit',
      { path: file, old_string: oldLine, new_string: newLine },
      context,
      { path: file, sha256: hash },
    )
    expect(commitResult.error).toBe(true)
    expect(textOf(commitResult)).toContain('Complete edit diff exceeds the review transport limit; split this edit into smaller changes.')

    const onDisk = await readFile(file, 'utf8')
    expect(onDisk).toBe(oldLine + '\n')
  })

  it('YOLO (no expected) succeeds and provides actual review metadata', async () => {
    const dir = await tempDir()
    const file = join(dir, 'yolo.txt')
    await writeFile(file, 'before\n', 'utf8')

    const rawBytes = await readFile(file)
    const hash = createHash('sha256').update(rawBytes).digest('hex')
    const context = makeContext(dir)

    const result = await invoke(
      edit,
      'fs.edit',
      { path: file, old_string: 'before', new_string: 'after' },
      context,
    )

    expect(result.error).toBe(false)
    expect(textOf(result)).toBe('Edited successfully.\nReplacements: 1')
    expect(result.details.editReview).toMatchObject({
      path: file,
      sha256: hash,
      diff: expect.stringContaining('+ 1|after'),
    })
    expect(await readFile(file, 'utf8')).toBe('after\n')
  })
})
