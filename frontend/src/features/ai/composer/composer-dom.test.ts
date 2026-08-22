import { describe, expect, it } from 'vitest'
import {
  extractPartsFromEditor,
  extractPartsKeyFromEditor,
  normalizeEditorDom,
  renderPartsToEditor,
} from '@/features/ai/composer/composer-dom'
import {
  createAttachmentPart,
  createResourcePart,
  createTextPart,
  partsToMessageContents,
} from '@/features/ai/composer/composer-parts'

function textNode(text: string): Text {
  return document.createTextNode(text)
}

function pillSpan(attributes: Record<string, string>, text: string): HTMLSpanElement {
  const pill = document.createElement('span')
  for (const [name, value] of Object.entries(attributes)) {
    pill.setAttribute(name, value)
  }
  pill.textContent = text
  return pill
}

function attachmentPill(uploadId: string, filename: string): HTMLSpanElement {
  return pillSpan(
    {
      'data-part-id': `part-${uploadId}`,
      'data-part-type': 'attachment',
      'data-upload-id': uploadId,
      'data-filename': filename,
    },
    `[${filename}]`,
  )
}

function resourcePill(blobId: string, name: string, preview?: string): HTMLSpanElement {
  const attributes: Record<string, string> = {
    'data-part-id': `part-${blobId}`,
    'data-part-type': 'resource',
    'data-blob-id': blobId,
    'data-name': name,
  }
  if (preview !== undefined) {
    attributes['data-preview'] = preview
  }
  return pillSpan(attributes, `[${name}]`)
}

/** 读取 root 的 childNodes 序列，断言其恰好是给定文本与 pill 引用。 */
function expectCanonicalDom(
  root: HTMLElement,
  expected: Array<{ kind: 'text'; text: string } | { kind: 'pill'; uploadId?: string; blobId?: string }>,
): void {
  expect(root.childNodes.length).toBe(expected.length)
  expected.forEach((item, index) => {
    const node = root.childNodes[index]
    if (item.kind === 'text') {
      expect(node?.nodeType).toBe(Node.TEXT_NODE)
      expect((node as Text).textContent).toBe(item.text)
    } else {
      expect(node?.nodeType).toBe(Node.ELEMENT_NODE)
      expect((node as HTMLElement).tagName.toLowerCase()).toBe('span')
      expect((node as HTMLElement).getAttribute('data-part-type')).toBe(
        item.uploadId !== undefined ? 'attachment' : 'resource',
      )
      expect(
        (node as HTMLElement).getAttribute(item.uploadId !== undefined ? 'data-upload-id' : 'data-blob-id'),
      ).toBe(item.uploadId ?? item.blobId)
    }
  })
}

describe('extractPartsFromEditor', () => {
  it('extracts nothing from an empty editor', () => {
    // 空编辑器没有文本也没有 pill，不应产生任何 part。
    const root = document.createElement('div')
    expect(extractPartsFromEditor(root)).toEqual([])
  })

  it('merges consecutive text nodes into one part and skips empty ones', () => {
    // DOM 契约：相邻文本节点在提取时合并为一个 text part；空文本节点不产生 part。
    const root = document.createElement('div')
    root.append(textNode('hello'), textNode(''), textNode('world'))
    const parts = extractPartsFromEditor(root)
    expect(parts).toHaveLength(1)
    expect(parts[0]).toMatchObject({ type: 'text', text: 'helloworld' })
  })

  it('skips attachment pills without upload id and requires both resource fields', () => {
    // 缺 data-upload-id 的 attachment pill 直接丢弃；resource pill 必须同时有 blobId 与 name。
    const root = document.createElement('div')
    root.append(
      textNode('a'),
      pillSpan(
        {
          'data-part-id': 'part-missing-upload',
          'data-part-type': 'attachment',
          'data-filename': 'orphan.txt',
        },
        '[orphan.txt]',
      ),
      textNode('b'),
      pillSpan(
        {
          'data-part-id': 'part-resource-no-blob',
          'data-part-type': 'resource',
          'data-name': 'no-blob.txt',
        },
        '[no-blob.txt]',
      ),
      pillSpan(
        {
          'data-part-id': 'part-resource-no-name',
          'data-part-type': 'resource',
          'data-blob-id': '00000000-0000-0000-0000-000000000002',
        },
        '[no-name]',
      ),
      textNode('c'),
    )
    // 被跳过的 pill 不产生 part，两侧文本因此合并为一个 text part。
    expect(partsToMessageContents(extractPartsFromEditor(root))).toEqual([
      { type: 'TEXT', text: 'abc' },
    ])
  })

  it('extracts resource pills with and without preview in document order', () => {
    // 只有声明了 data-preview 的 resource 才有 preview 字段，两种形态按文档顺序保留。
    const root = document.createElement('div')
    root.append(
      resourcePill('00000000-0000-0000-0000-000000000001', 'with-preview.txt', 'data:preview'),
      textNode(' between '),
      resourcePill('00000000-0000-0000-0000-000000000002', 'no-preview.txt'),
    )
    const parts = extractPartsFromEditor(root)
    expect(partsToMessageContents(parts)).toEqual([
      {
        type: 'RESOURCE',
        blobId: '00000000-0000-0000-0000-000000000001',
        name: 'with-preview.txt',
        preview: 'data:preview',
      },
      { type: 'TEXT', text: ' between ' },
      {
        type: 'RESOURCE',
        blobId: '00000000-0000-0000-0000-000000000002',
        name: 'no-preview.txt',
      },
    ])
  })

  it('preserves interleaved order of pills and text parts', () => {
    // 混合形态按文档顺序输出，partId 与引用字段保持 pill 上的原始值。
    const root = document.createElement('div')
    root.append(
      textNode('alpha'),
      attachmentPill('upload-1', 'a.txt'),
      resourcePill('00000000-0000-0000-0000-000000000001', 'b.txt'),
      textNode('omega'),
    )
    const parts = extractPartsFromEditor(root)
    expect(parts.map((part) => part.type)).toEqual(['text', 'attachment', 'resource', 'text'])
    expect(parts).toMatchObject([
      { type: 'text', text: 'alpha' },
      { type: 'attachment', partId: 'part-upload-1', uploadId: 'upload-1', filename: 'a.txt' },
      {
        type: 'resource',
        partId: 'part-00000000-0000-0000-0000-000000000001',
        blobId: '00000000-0000-0000-0000-000000000001',
        name: 'b.txt',
      },
      { type: 'text', text: 'omega' },
    ])
  })

  it('drops attachment pills without data-part-id and no upload id', () => {
    // 无 data-part-id 的 pill 不满足 isPillElement 契约，直接忽略（不会走到兜底 partId）。
    const root = document.createElement('div')
    root.append(
      pillSpan(
        { 'data-part-type': 'attachment', 'data-upload-id': 'upload-9', 'data-filename': 'x.txt' },
        '[x.txt]',
      ),
    )
    expect(extractPartsFromEditor(root)).toEqual([])
  })

  it('derives the parts key from extraction, normalizing equivalent orderings', () => {
    // extractPartsKeyFromEditor 直接使用提取结果：文本顺序固定时，等价 DOM 形态产生相同 key。
    const a = document.createElement('div')
    a.append(textNode('a'), textNode('b'))
    const b = document.createElement('div')
    b.append(textNode('ab'))
    expect(extractPartsKeyFromEditor(a)).toBe(extractPartsKeyFromEditor(b))
  })
})

describe('normalizeEditorDom', () => {
  it('leaves already canonical DOM untouched', () => {
    // 已规范形态（仅文本节点与 pill）必须原样保留——保存光标位置。
    const root = document.createElement('div')
    root.append(textNode('text'), attachmentPill('upload-1', 'a.txt'))
    const before = Array.from(root.childNodes)
    normalizeEditorDom(root)
    expect(Array.from(root.childNodes)).toEqual(before)
    expectCanonicalDom(root, [
      { kind: 'text', text: 'text' },
      { kind: 'pill', uploadId: 'upload-1' },
    ])
  })

  it('normalizes an empty text node left at the caret', () => {
    // 规范形态检查覆盖空文本节点分支：空文本节点触发重建，最终被移除，不产生 part。
    const root = document.createElement('div')
    root.append(textNode('a'), textNode(''), textNode('b'))
    normalizeEditorDom(root)
    expectCanonicalDom(root, [{ kind: 'text', text: 'ab' }])
    expect(extractPartsFromEditor(root)).toEqual([
      expect.objectContaining({ type: 'text', text: 'ab' }),
    ])
  })

  it('normalizes a pill-leading caret line so the first boundary is dropped', () => {
    // 编辑器起点视为已处于边界：首个块在 pill 之前不产生前导换行，块内文本与 pill 顺序保留。
    const root = document.createElement('div')
    const block = document.createElement('div')
    block.append(attachmentPill('upload-1', 'a.txt'), textNode('after'))
    root.append(block)
    normalizeEditorDom(root)
    expectCanonicalDom(root, [
      { kind: 'pill', uploadId: 'upload-1' },
      { kind: 'text', text: 'after' },
    ])
  })

  it('normalizes pill then br and skips comment nodes during walk', () => {
    // pill 之后的 br 产生独立换行文本节点；注释等非文本非元素节点在 walk 中直接跳过。
    const root = document.createElement('div')
    root.append(
      attachmentPill('upload-1', 'a.txt'),
      document.createElement('br'),
      document.createComment('ignored'),
      textNode('x'),
    )
    normalizeEditorDom(root)
    expectCanonicalDom(root, [
      { kind: 'pill', uploadId: 'upload-1' },
      { kind: 'text', text: '\nx' },
    ])
  })

  it('merges adjacent text nodes and drops empty ones after rebuilding', () => {
    // normalize 重建后合并相邻文本节点，并移除空文本节点（mergeAdjacentTextNodes 收尾）。
    const root = document.createElement('div')
    root.append(
      document.createElement('br'),
      textNode('a'),
      textNode('b'),
      textNode(''),
      document.createElement('br'),
      textNode(''),
    )
    normalizeEditorDom(root)
    expectCanonicalDom(root, [{ kind: 'text', text: 'ab\n' }])
    expect(extractPartsFromEditor(root)).toEqual([
      expect.objectContaining({ type: 'text', text: 'ab\n' }),
    ])
  })

  it('merges more than two adjacent text nodes in one pass', () => {
    // 合并循环内 continue 路径：一次重建中连续合并三/四个相邻文本节点。
    const root = document.createElement('div')
    root.append(
      document.createElement('br'),
      textNode('a'),
      textNode('b'),
      textNode('c'),
      textNode('d'),
    )
    normalizeEditorDom(root)
    expectCanonicalDom(root, [{ kind: 'text', text: 'abcd' }])
  })

  it('keeps pill spans adjacent during the text-merge pass', () => {
    // mergeAdjacentTextNodes 的 else 分支：pill 之间的文本不跨 pill 合并（index 前进）。
    const root = document.createElement('div')
    const block = document.createElement('div')
    block.append(
      document.createElement('br'),
      textNode('left'),
      attachmentPill('upload-1', 'a.txt'),
      textNode('right'),
    )
    root.append(block, document.createElement('br'), textNode('tail'))
    normalizeEditorDom(root)
    expectCanonicalDom(root, [
      { kind: 'text', text: 'left' },
      { kind: 'pill', uploadId: 'upload-1' },
      { kind: 'text', text: 'right\ntail' },
    ])
  })

  it('merges text when a null textContent is treated as empty', () => {
    // mergeAdjacentTextNodes 用 `?? ''` 兜底：null textContent 视为空串，合并后只剩 'a'。
    const root = document.createElement('div')
    root.append(
      document.createElement('br'),
      textNode('a'),
      Object.assign(textNode('b'), { textContent: null }),
    )
    normalizeEditorDom(root)
    expectCanonicalDom(root, [{ kind: 'text', text: 'a' }])
  })

  it('merges text when a null textContent on the first node leaves only the second', () => {
    // 同一条兜底分支的镜像：第一个节点 textContent 为 null 时，合并产物只剩第二个节点的文本。
    const root = document.createElement('div')
    root.append(
      document.createElement('br'),
      Object.assign(textNode('a'), { textContent: null }),
      textNode('b'),
    )
    normalizeEditorDom(root)
    expectCanonicalDom(root, [{ kind: 'text', text: 'b' }])
  })

  it('keeps the non-empty boundary newline between pill spans in cleanup', () => {
    // mergeAdjacentTextNodes 的清理分支只删除空文本节点；'<br>' 折叠出的非空 '\n' 必须保留。
    // 注：重建语义保证清理循环的删除分支不可达（pushText/pushBoundary 已产出非空文本），
    // 此处只验证“非空边界不被误删”。
    const root = document.createElement('div')
    root.append(
      textNode(''),
      attachmentPill('upload-1', 'a.txt'),
      document.createElement('br'),
      textNode(''),
      attachmentPill('upload-2', 'b.txt'),
    )
    normalizeEditorDom(root)
    expectCanonicalDom(root, [
      { kind: 'pill', uploadId: 'upload-1' },
      { kind: 'text', text: '\n' },
      { kind: 'pill', uploadId: 'upload-2' },
    ])
  })

  it('drops an empty text node left after merging in the cleanup pass', () => {
    // 合并循环后清理空文本节点：合并产物为空时被移除，最终没有任何文本节点。
    const root = document.createElement('div')
    root.append(document.createElement('br'), textNode(''), textNode(''))
    normalizeEditorDom(root)
    expectCanonicalDom(root, [])
    expect(extractPartsFromEditor(root)).toEqual([])
  })

  it('unwraps block elements and br into a single newline', () => {
    // div/p 块边界与 <br> 都折叠为单个 '\n'；编辑器起点/终点边界被丢弃，相邻块边界去重，
    // 换行与后续文本合并进同一文本节点（Chrome div 模型）。
    const root = document.createElement('div')
    root.append(
      document.createElement('div'),
      document.createElement('p'),
      textNode('one'),
      document.createElement('br'),
      document.createElement('p'),
      document.createElement('div'),
      textNode('two'),
      document.createElement('br'),
      document.createElement('br'),
      textNode('three'),
    )
    normalizeEditorDom(root)
    expectCanonicalDom(root, [{ kind: 'text', text: 'one\ntwo\nthree' }])
  })

  it('normalizes text around whitespace and caret edge cases', () => {
    // 前导空白保留（编辑器起点视为已处于边界），连续 br 合并，尾随 br 不产生多余换行，
    // 空文本节点不产生内容。
    const root = document.createElement('div')
    root.append(
      textNode('  leading'),
      document.createElement('br'),
      document.createElement('br'),
      textNode('  spaced  '),
      document.createElement('br'),
      textNode(''),
      textNode('tail'),
      document.createElement('br'),
    )
    normalizeEditorDom(root)
    expectCanonicalDom(root, [{ kind: 'text', text: '  leading\n  spaced  \ntail' }])
  })

  it('normalizes nested pills into document order with block-boundary newlines', () => {
    // 嵌套在块内的 pill 解包上提；块内文本与 pill 保持相对顺序，块末尾边界换行合并进后续文本。
    const root = document.createElement('div')
    const block = document.createElement('div')
    block.append(
      textNode('before'),
      pillSpan(
        {
          'data-part-id': 'part-nested',
          'data-part-type': 'resource',
          'data-blob-id': '00000000-0000-0000-0000-000000000003',
          'data-name': 'nested.txt',
        },
        '[nested.txt]',
      ),
      textNode('after'),
    )
    root.append(block, textNode('tail'))
    normalizeEditorDom(root)
    expectCanonicalDom(root, [
      { kind: 'text', text: 'before' },
      { kind: 'pill', blobId: '00000000-0000-0000-0000-000000000003' },
      { kind: 'text', text: 'after\ntail' },
    ])
  })

  it('rendered round-trip content stays canonical and re-extracts identically', () => {
    // renderPartsToEditor 的输出已是规范形态；normalize 保持不动，extract 得到相同 parts。
    const root = document.createElement('div')
    const parts = [
      createTextPart('hello'),
      createAttachmentPart('upload-1', 'a.txt'),
      createResourcePart('00000000-0000-0000-0000-000000000001', 'b.txt', 'preview'),
      createTextPart('world'),
    ]
    renderPartsToEditor(root, parts)
    normalizeEditorDom(root)
    const restored = extractPartsFromEditor(root)
    expect(partsToMessageContents(restored)).toEqual(partsToMessageContents(parts))
  })
})
