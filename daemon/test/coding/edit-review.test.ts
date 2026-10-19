import { afterEach, describe, expect, it } from 'vitest'
import { createHash } from 'node:crypto'
import { mkdtemp, readFile, realpath, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileCapabilities, buildDiff, parseLineEndingDocument } from '../../src/coding/files.js'
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

  it('9. 1500 行仅首末变化精确 2 删 2 增且不退化', () => {
    const original = ['first_old', ...Array.from({ length: 1498 }, (_, i) => `mid_${i}`), 'last_old']
    const next = ['first_new', ...Array.from({ length: 1498 }, (_, i) => `mid_${i}`), 'last_new']
    const diff = buildDiff(
      parseLineEndingDocument(`${original.join('\n')}\n`),
      parseLineEndingDocument(`${next.join('\n')}\n`),
    )
    const lines = diff.split('\n')
    const removed = lines.filter((line) => line.startsWith('-'))
    const added = lines.filter((line) => line.startsWith('+'))
    expect(removed).toHaveLength(2)
    expect(added).toHaveLength(2)
    expect(removed[0]).toContain(' 1|first_old')
    expect(added[0]).toContain(' 1|first_new')
    expect(removed[1]).toContain('1500|last_old')
    expect(added[1]).toContain('1500|last_new')
    expect(diff).toContain('...')
  })

  it('10. 50000 行仅首末变化精确 2 删 2 增且快速计算', () => {
    const count = 50000
    const original = ['first_old', ...Array.from({ length: count - 2 }, (_, i) => `line_${i}`), 'last_old']
    const next = ['first_new', ...Array.from({ length: count - 2 }, (_, i) => `line_${i}`), 'last_new']
    const diff = buildDiff(
      parseLineEndingDocument(`${original.join('\n')}\n`),
      parseLineEndingDocument(`${next.join('\n')}\n`),
    )
    const lines = diff.split('\n')
    const removed = lines.filter((line) => line.startsWith('-'))
    const added = lines.filter((line) => line.startsWith('+'))
    expect(removed).toHaveLength(2)
    expect(added).toHaveLength(2)
    expect(removed[0]).toContain(' 1|first_old')
    expect(added[0]).toContain(' 1|first_new')
    expect(removed[1]).toContain(`${count}|last_old`)
    expect(added[1]).toContain(`${count}|last_new`)
    expect(diff).toContain('...')
  })

  it('11. 空文件与非空文件之间的纯增与纯删 diff', () => {
    const emptyDoc = parseLineEndingDocument('')
    const populatedDoc = parseLineEndingDocument('line1\nline2\nline3\n')

    // 空到非空：纯新增
    const addDiff = buildDiff(emptyDoc, populatedDoc)
    const addLines = addDiff.split('\n')
    expect(addLines.filter((l) => l.startsWith('-'))).toHaveLength(0)
    expect(addLines.filter((l) => l.startsWith('+'))).toHaveLength(3)
    expect(addDiff).toBe('+ 1|line1\n+ 2|line2\n+ 3|line3')

    // 非空到空：纯删除
    const removeDiff = buildDiff(populatedDoc, emptyDoc)
    const removeLines = removeDiff.split('\n')
    expect(removeLines.filter((l) => l.startsWith('-'))).toHaveLength(3)
    expect(removeLines.filter((l) => l.startsWith('+'))).toHaveLength(0)
    expect(removeDiff).toBe('- 1|line1\n- 2|line2\n- 3|line3')
  })

  it('12. 文件内纯插入与纯删除行（无修改行）', () => {
    const baseDoc = parseLineEndingDocument('alpha\nomega\n')
    const insertedDoc = parseLineEndingDocument('alpha\ninserted1\ninserted2\nomega\n')

    const insertDiff = buildDiff(baseDoc, insertedDoc)
    expect(insertDiff).toContain('+ 2|inserted1')
    expect(insertDiff).toContain('+ 3|inserted2')
    expect(insertDiff.split('\n').filter((l) => l.startsWith('-'))).toHaveLength(0)

    const deleteDiff = buildDiff(insertedDoc, baseDoc)
    expect(deleteDiff).toContain('- 2|inserted1')
    expect(deleteDiff).toContain('- 3|inserted2')
    expect(deleteDiff.split('\n').filter((l) => l.startsWith('+'))).toHaveLength(0)
  })

  it('13. EOF 换行差异：末尾无换行被修改时输出 \\ No newline at end of file', () => {
    // 原文末尾无换行，新文末尾无换行
    const noEof1 = parseLineEndingDocument('foo')
    const noEof2 = parseLineEndingDocument('bar')
    const diff1 = buildDiff(noEof1, noEof2)
    expect(diff1).toBe('- 1|foo\n\\ No newline at end of file\n+ 1|bar\n\\ No newline at end of file')

    // 原文末尾有换行，新文末尾无换行
    const withEof = parseLineEndingDocument('foo\n')
    const diff2 = buildDiff(withEof, noEof1)
    expect(diff2).toBe('- 1|foo\n+ 1|foo\n\\ No newline at end of file')

    // 原文末尾无换行，新文末尾有换行
    const diff3 = buildDiff(noEof1, withEof)
    expect(diff3).toBe('- 1|foo\n\\ No newline at end of file\n+ 1|foo')
  })

  it('14. EOF 换行差异：普通 context 在末尾无换行时不额外伪造换行标记', () => {
    // 首行被修改，末行无修改且无 EOF 换行
    const docA = parseLineEndingDocument('line1\nline2')
    const docB = parseLineEndingDocument('LINE1\nline2')
    const diff = buildDiff(docA, docB)
    expect(diff).toContain('- 1|line1')
    expect(diff).toContain('+ 1|LINE1')
    expect(diff).toContain(' 2|line2')
    expect(diff).not.toContain('\\ No newline at end of file')
  })

  it('15. EOF 换行差异：fs.edit 端到端替换末尾无换行文件并在 review.diff 中包含 \\ No newline at end of file', async () => {
    const dir = await tempDir()
    const file = join(dir, 'noeol.txt')
    await writeFile(file, 'no newline', 'utf8')
    const context = makeContext(dir)
    const result = await invoke(
      edit,
      'fs.edit',
      { path: file, old_string: 'newline', new_string: 'EOF' },
      context,
    )
    expect(result.error).toBe(false)
    expect(textOf(result)).toBe('Edited successfully.\nReplacements: 1')
    const review = result.details.editReview as { diff: string }
    expect(review.diff).toContain('\\ No newline at end of file')
    expect(await readFile(file, 'utf8')).toBe('no EOF')
  })

  it('16. 重复行：多行相同内容修改某一行时精确对齐', () => {
    const original = parseLineEndingDocument('dup\ndup\ndup\ndup\n')
    const next = parseLineEndingDocument('dup\nchanged\ndup\ndup\n')
    const diff = buildDiff(original, next)
    expect(diff).toContain('+ 2|changed')
    expect(diff).toContain('- 4|dup')
    const lines = diff.split('\n')
    expect(lines.filter((l) => l.startsWith('-'))).toHaveLength(1)
    expect(lines.filter((l) => l.startsWith('+'))).toHaveLength(1)
  })

  it('17. 上下文 4/8 边界：前导/尾随 4 行边界', () => {
    // LEADING 上下文刚好 4 行：全部展示，无省略
    const leading4Orig = parseLineEndingDocument('c1\nc2\nc3\nc4\nold\n')
    const leading4Next = parseLineEndingDocument('c1\nc2\nc3\nc4\nnew\n')
    const diffLeading4 = buildDiff(leading4Orig, leading4Next)
    expect(diffLeading4).not.toContain('...')
    expect(diffLeading4).toContain(' 1|c1')
    expect(diffLeading4).toContain(' 4|c4')

    // LEADING 上下文 5 行（> 4）：第 1 行被折叠，出现 ...
    const leading5Orig = parseLineEndingDocument('c1\nc2\nc3\nc4\nc5\nold\n')
    const leading5Next = parseLineEndingDocument('c1\nc2\nc3\nc4\nc5\nnew\n')
    const diffLeading5 = buildDiff(leading5Orig, leading5Next)
    expect(diffLeading5).toContain('...\n')
    expect(diffLeading5).not.toContain(' 1|c1')
    expect(diffLeading5).toContain(' 2|c2')
    expect(diffLeading5).toContain(' 5|c5')

    // TRAILING 上下文刚好 4 行：全部展示，无省略
    const trailing4Orig = parseLineEndingDocument('old\nc1\nc2\nc3\nc4\n')
    const trailing4Next = parseLineEndingDocument('new\nc1\nc2\nc3\nc4\n')
    const diffTrailing4 = buildDiff(trailing4Orig, trailing4Next)
    expect(diffTrailing4).not.toContain('...')
    expect(diffTrailing4).toContain(' 2|c1')
    expect(diffTrailing4).toContain(' 5|c4')

    // TRAILING 上下文 5 行（> 4）：末尾第 5 行被折叠，末尾出现 ...
    const trailing5Orig = parseLineEndingDocument('old\nc1\nc2\nc3\nc4\nc5\n')
    const trailing5Next = parseLineEndingDocument('new\nc1\nc2\nc3\nc4\nc5\n')
    const diffTrailing5 = buildDiff(trailing5Orig, trailing5Next)
    expect(diffTrailing5).toContain('...')
    expect(diffTrailing5.endsWith('...')).toBe(true)
    expect(diffTrailing5).toContain(' 2|c1')
    expect(diffTrailing5).toContain(' 5|c4')
    expect(diffTrailing5).not.toContain(' 6|c5')
  })

  it('18. 上下文 4/8 边界：两 hunk 间 INTER_HUNK 在 8 行与 9 行的临界行为', () => {
    // 两处改动之间恰好有 8 行相同内容（<= 2 * DIFF_CONTEXT_LINES = 8）
    // 应该完整输出这 8 行 context，不出现省略号 ...
    const middle8 = Array.from({ length: 8 }, (_, i) => `mid_${i}`)
    const orig8 = parseLineEndingDocument(['change1_old', ...middle8, 'change2_old'].join('\n') + '\n')
    const next8 = parseLineEndingDocument(['change1_new', ...middle8, 'change2_new'].join('\n') + '\n')
    const diff8 = buildDiff(orig8, next8)
    expect(diff8).not.toContain('...')
    for (let i = 0; i < 8; i++) {
      expect(diff8).toContain(`mid_${i}`)
    }

    // 两处改动之间有 9 行相同内容（> 8）
    // 应该保留前 4 行和后 4 行 context，中间出现省略号 ...，第 5 个 mid 被省略
    const middle9 = Array.from({ length: 9 }, (_, i) => `mid_${i}`)
    const orig9 = parseLineEndingDocument(['change1_old', ...middle9, 'change2_old'].join('\n') + '\n')
    const next9 = parseLineEndingDocument(['change1_new', ...middle9, 'change2_new'].join('\n') + '\n')
    const diff9 = buildDiff(orig9, next9)
    expect(diff9).toContain('...\n')
    expect(diff9).toContain('mid_0')
    expect(diff9).toContain('mid_1')
    expect(diff9).toContain('mid_2')
    expect(diff9).toContain('mid_3')
    expect(diff9).not.toContain('mid_4')
    expect(diff9).toContain('mid_5')
    expect(diff9).toContain('mid_6')
    expect(diff9).toContain('mid_7')
    expect(diff9).toContain('mid_8')
  })

  it('19. fs.edit persisted 成功正文仅含成功文本与替换数，不包含 diff，diff 在 details.editReview', async () => {
    const dir = await tempDir()
    const file = join(dir, 'persisted.txt')
    await writeFile(file, 'hello world\n', 'utf8')
    const context = makeContext(dir)
    const result = await invoke(
      edit,
      'fs.edit',
      { path: file, old_string: 'world', new_string: 'kk-studio' },
      context,
    )
    expect(result.error).toBe(false)
    expect(textOf(result)).toBe('Edited successfully.\nReplacements: 1')
    expect(textOf(result)).not.toContain('world')
    expect(textOf(result)).not.toContain('kk-studio')
    expect(textOf(result)).not.toContain('-')
    expect(textOf(result)).not.toContain('+')
    expect(result.details.editReview).toBeDefined()
    const review = result.details.editReview as { diff: string; sha256: string; path: string }
    expect(review.diff).toContain('- 1|hello world')
    expect(review.diff).toContain('+ 1|hello kk-studio')
  })

  it('20. 文档完全相同时 buildDiff 返回空字符串', () => {
    const doc = parseLineEndingDocument('a\nb\nc\n')
    expect(buildDiff(doc, doc)).toBe('')
  })
})
