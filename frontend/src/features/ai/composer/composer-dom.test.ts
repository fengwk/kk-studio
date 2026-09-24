import { describe, expect, it } from 'vitest'
import {
  extractPartsFromEditor,
  extractPartsKeyFromEditor,
  findAdjacentPill,
  insertPillsAtCaret,
  insertTextAtCaret,
  isPillElement,
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

/** 在 container 的 childOffset 处放置 collapsed caret（jsdom Selection API）。 */
function setCaret(container: Node, offset: number): void {
  const range = document.createRange()
  range.setStart(container, offset)
  range.collapse(true)
  const selection = document.getSelection()
  selection?.removeAllRanges()
  selection?.addRange(range)
}

/** 把 root 挂载到 document.body，返回挂载后的元素（jsdom 只跟踪已挂载节点的 selection）。 */
function mountRoot(root: HTMLElement): HTMLElement {
  document.body.appendChild(root)
  return root
}

/** 卸载测试中挂载的 root，避免跨用例 selection/节点残留。 */
function unmountRoot(root: HTMLElement): void {
  root.remove()
  document.getSelection()?.removeAllRanges()
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

  it('preserves exact caret position inside nested styled spans after normalization', () => {
    // 粘贴富文本或深层嵌套 span 规范化后：光标精确保留在对应字符后，不跳跃到起点或末尾。
    const root = mountRoot(document.createElement('div'))
    try {
      const p = document.createElement('p')
      const span = document.createElement('span')
      const targetText = textNode('nested')
      span.appendChild(targetText)
      p.append(textNode('hello '), span, textNode(' world'))
      root.appendChild(p)

      // 光标放在 "nested" 的第 4 个字符后（"nest|ed"）
      setCaret(targetText, 4)

      normalizeEditorDom(root)
      expectCanonicalDom(root, [{ kind: 'text', text: 'hello nested world' }])

      const selection = document.getSelection()
      expect(selection?.rangeCount).toBe(1)
      const range = selection!.getRangeAt(0)
      expect(range.collapsed).toBe(true)
      // "hello " 长度为 6，加上 "nest" 4，应为 10
      expect(range.startContainer).toBe(root.firstChild)
      expect(range.startOffset).toBe(10)
    } finally {
      unmountRoot(root)
    }
  })

  it('preserves caret at line position after whole-line Backspace and div/br normalization', () => {
    // 整行删除/退格后规范化：光标保留在逻辑行位置，不发生退回 0 的光标跳跃。
    const root = mountRoot(document.createElement('div'))
    try {
      const line1 = document.createElement('div')
      line1.appendChild(textNode('line 1'))
      const line2 = document.createElement('div')
      // 模拟用户在空行上按下 Backspace 产生空 div
      root.append(line1, line2)

      // 光标位于空行 line2 的起点
      setCaret(line2, 0)

      normalizeEditorDom(root)
      expectCanonicalDom(root, [{ kind: 'text', text: 'line 1\n' }])

      const selection = document.getSelection()
      expect(selection?.rangeCount).toBe(1)
      const range = selection!.getRangeAt(0)
      expect(range.collapsed).toBe(true)
      // 光标位于 line 1\n 的末尾（字符偏移 7）
      expect(range.startContainer).toBe(root.firstChild)
      expect(range.startOffset).toBe(7)
    } finally {
      unmountRoot(root)
    }
  })

  it('preserves caret around attachment pills during normalization', () => {
    // 附件 pill 邻近位置光标映射：pill 之后插入的光标在规范化后仍位于 pill 之后、后续文本之前。
    const root = mountRoot(document.createElement('div'))
    try {
      const block = document.createElement('div')
      const pill = attachmentPill('upload-1', 'test.txt')
      const afterText = textNode('content')
      block.append(pill, afterText)
      root.appendChild(block)

      // 光标放在 afterText 起点（pill 紧接着的位置）
      setCaret(afterText, 0)

      normalizeEditorDom(root)
      expectCanonicalDom(root, [
        { kind: 'pill', uploadId: 'upload-1' },
        { kind: 'text', text: 'content' },
      ])

      const selection = document.getSelection()
      const range = selection!.getRangeAt(0)
      expect(range.collapsed).toBe(true)
      // 位于 pill 之后的 text 节点起点 0 处
      expect(range.startContainer).toBe(root.childNodes[1])
      expect(range.startOffset).toBe(0)
    } finally {
      unmountRoot(root)
    }
  })

  it('preserves non-collapsed selection range across normalization', () => {
    // 选区（Range）跨块或富文本包装时：规范化后起止字符偏移保持一致。
    const root = mountRoot(document.createElement('div'))
    try {
      const div = document.createElement('div')
      const t1 = textNode('abcdef')
      div.appendChild(t1)
      root.appendChild(div)

      const range = document.createRange()
      range.setStart(t1, 2)
      range.setEnd(t1, 5)
      const selection = document.getSelection()
      selection?.removeAllRanges()
      selection?.addRange(range)

      normalizeEditorDom(root)
      expectCanonicalDom(root, [{ kind: 'text', text: 'abcdef' }])

      const activeRange = document.getSelection()!.getRangeAt(0)
      expect(activeRange.collapsed).toBe(false)
      expect(activeRange.startContainer).toBe(root.firstChild)
      expect(activeRange.startOffset).toBe(2)
      expect(activeRange.endContainer).toBe(root.firstChild)
      expect(activeRange.endOffset).toBe(5)
    } finally {
      unmountRoot(root)
    }
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

  it('preserves imageTier on attachment and resource pills through render and extraction', () => {
    // 测试意图：验证 attachment 与 resource 的 imageTier 在 DOM pill (data-image-tier) 渲染与提取中完整保留
    const root = document.createElement('div')
    const parts = [
      createAttachmentPart('upload-img', 'photo.png', '1080P'),
      createAttachmentPart('upload-raw', 'original.png', 'ORIGINAL'),
      createResourcePart('00000000-0000-0000-0000-000000000001', 'ref.png', undefined, '720P'),
    ]
    renderPartsToEditor(root, parts)
    const pills = root.querySelectorAll('.composer-pill')
    expect(pills[0].getAttribute('data-image-tier')).toBe('1080P')
    expect(pills[1].getAttribute('data-image-tier')).toBe('ORIGINAL')
    expect(pills[2].getAttribute('data-image-tier')).toBe('720P')

    const restored = extractPartsFromEditor(root)
    expect(partsToMessageContents(restored)).toEqual([
      { type: 'ATTACHMENT', uploadId: 'upload-img', imageTier: '1080P' },
      { type: 'ATTACHMENT', uploadId: 'upload-raw', imageTier: 'ORIGINAL' },
      {
        type: 'RESOURCE',
        blobId: '00000000-0000-0000-0000-000000000001',
        name: 'ref.png',
        imageTier: '720P',
      },
    ])
  })
})

describe('isPillElement', () => {
  it('rejects text nodes and null', () => {
    // 非元素节点（文本节点）与 null 不满足 pill 契约，必须返回 false。
    expect(isPillElement(textNode('x'))).toBe(false)
    expect(isPillElement(null)).toBe(false)
  })
})

describe('insertPillsAtCaret with a live selection', () => {
  it('inserts pills at the caret and moves the caret behind the last pill', () => {
    // 光标在编辑器内：pills 插入光标处，selection 折叠到最后一颗 pill 之后。
    const root = mountRoot(document.createElement('div'))
    try {
      root.append(textNode('ab'))
      setCaret(root.firstChild!, 1)
      insertPillsAtCaret(root, [
        createAttachmentPart('upload-1', 'a.txt'),
        createResourcePart('00000000-0000-0000-0000-000000000001', 'r.txt'),
      ])
      // deleteContents 会把光标处文本切分为前后两段：text -> pill -> pill -> text。
      expect(root.childNodes).toHaveLength(4)
      expect((root.childNodes[0] as Text).textContent).toBe('a')
      expect((root.childNodes[1] as HTMLElement).getAttribute('data-upload-id')).toBe('upload-1')
      expect((root.childNodes[2] as HTMLElement).getAttribute('data-blob-id')).toBe(
        '00000000-0000-0000-0000-000000000001',
      )
      expect((root.childNodes[3] as Text).textContent).toBe('b')
      const caret = document.getSelection()?.getRangeAt(0)
      expect(caret?.collapsed).toBe(true)
      expect(caret?.startContainer).toBe(root)
      expect(caret?.startOffset).toBe(3)
    } finally {
      unmountRoot(root)
    }
  })

  it('appends pills at the end when the selection is outside the editor', () => {
    // selection 指向编辑器之外时退化为追加到末尾。
    const root = mountRoot(document.createElement('div'))
    const outside = mountRoot(document.createElement('div'))
    try {
      root.append(textNode('a'))
      setCaret(outside, 0)
      insertPillsAtCaret(root, [createAttachmentPart('upload-2', 'b.txt')])
      expect(root.childNodes).toHaveLength(2)
      expect((root.childNodes[1] as HTMLElement).getAttribute('data-upload-id')).toBe('upload-2')
    } finally {
      unmountRoot(root)
      unmountRoot(outside)
    }
  })
})

describe('insertTextAtCaret', () => {
  it('appends text at the end when there is no selection', () => {
    // 无 selection（rangeCount 为 0）时退化为追加到编辑器末尾，保证文本不丢失。
    const root = mountRoot(document.createElement('div'))
    try {
      root.append(textNode('ab'))
      document.getSelection()?.removeAllRanges()
      insertTextAtCaret(root, 'cd')
      expect(root.textContent).toBe('abcd')
      expect(root.lastChild?.nodeType).toBe(Node.TEXT_NODE)
      expect(root.lastChild?.textContent).toBe('cd')
    } finally {
      unmountRoot(root)
    }
  })

  it('leaves the editor untouched when the selection is outside it', () => {
    // selection 指向编辑器之外的节点时视为不可编辑区域，不做任何 DOM 修改。
    const root = mountRoot(document.createElement('div'))
    const outside = mountRoot(document.createElement('div'))
    try {
      setCaret(outside, 0)
      insertTextAtCaret(root, 'x')
      expect(root.textContent).toBe('')
    } finally {
      unmountRoot(root)
      unmountRoot(outside)
    }
  })

  it('inserts text at the caret and keeps the caret after the new node', () => {
    // 正常路径：在光标处插入文本节点，selection 折叠到插入节点之后。
    const root = mountRoot(document.createElement('div'))
    try {
      root.append(textNode('ab'))
      setCaret(root.firstChild!, 1)
      insertTextAtCaret(root, 'X')
      expect(root.textContent).toBe('aXb')
      expect(root.childNodes[1]?.textContent).toBe('X')
      const caret = document.getSelection()?.getRangeAt(0)
      expect(caret?.collapsed).toBe(true)
      expect(caret?.startContainer).toBe(root)
      expect(caret?.startOffset).toBe(2)
    } finally {
      unmountRoot(root)
    }
  })
})

describe('insertPillsAtCaret', () => {
  it('does nothing when no pill parts are given', () => {
    // 只有 text part 时不创建任何节点，编辑器内容保持不变。
    const root = document.createElement('div')
    root.append(textNode('a'))
    insertPillsAtCaret(root, [createTextPart('ignored')])
    expect(root.childNodes).toHaveLength(1)
    expect((root.childNodes[0] as Text).textContent).toBe('a')
  })

  it('appends pills at the end when there is no selection', () => {
    // 无 selection（光标不在编辑器内）时退化为追加到末尾，attachment/resource 字段齐全。
    const root = mountRoot(document.createElement('div'))
    try {
      document.getSelection()?.removeAllRanges()
      insertPillsAtCaret(root, [
        createAttachmentPart('upload-1', 'a.txt'),
        createResourcePart('00000000-0000-0000-0000-000000000001', 'r.txt', 'preview'),
      ])
      expect(root.childNodes).toHaveLength(2)
      const attachment = root.childNodes[0] as HTMLElement
      expect(attachment.getAttribute('data-part-type')).toBe('attachment')
      expect(attachment.getAttribute('data-upload-id')).toBe('upload-1')
      expect(attachment.getAttribute('data-filename')).toBe('a.txt')
      expect(attachment.textContent).toBe('[a.txt]')
      const resource = root.childNodes[1] as HTMLElement
      expect(resource.getAttribute('data-part-type')).toBe('resource')
      expect(resource.getAttribute('data-blob-id')).toBe('00000000-0000-0000-0000-000000000001')
      expect(resource.getAttribute('data-preview')).toBe('preview')
      expect(resource.textContent).toBe('[r.txt]')
    } finally {
      unmountRoot(root)
    }
  })
})

describe('findAdjacentPill', () => {
  it('returns null when there is no selection', () => {
    // 无 selection 时没有可判断的邻接位置。
    const root = mountRoot(document.createElement('div'))
    try {
      root.append(textNode('a'), attachmentPill('upload-1', 'a.txt'))
      document.getSelection()?.removeAllRanges()
      expect(findAdjacentPill(root, 'before')).toBeNull()
      expect(findAdjacentPill(root, 'after')).toBeNull()
    } finally {
      unmountRoot(root)
    }
  })

  it('returns null for a non-collapsed selection', () => {
    // 跨选区删除交给浏览器默认行为，不进入 pill 邻接判断。
    const root = mountRoot(document.createElement('div'))
    try {
      root.append(textNode('a'), attachmentPill('upload-1', 'a.txt'))
      const range = document.createRange()
      range.selectNodeContents(root)
      const selection = document.getSelection()
      selection?.removeAllRanges()
      selection?.addRange(range)
      expect(findAdjacentPill(root, 'before')).toBeNull()
    } finally {
      unmountRoot(root)
    }
  })

  it('returns null when the selection is outside the editor', () => {
    // 光标在编辑器之外时不做邻接判断。
    const root = mountRoot(document.createElement('div'))
    const outside = mountRoot(document.createElement('div'))
    try {
      setCaret(outside, 0)
      expect(findAdjacentPill(root, 'before')).toBeNull()
      expect(findAdjacentPill(root, 'after')).toBeNull()
    } finally {
      unmountRoot(root)
      unmountRoot(outside)
    }
  })

  it('finds the pill before a text caret at offset zero', () => {
    // 文本节点容器 + before：offset 为 0 时命中 previousSibling 上的 pill。
    const root = mountRoot(document.createElement('div'))
    try {
      root.append(attachmentPill('upload-1', 'a.txt'), textNode('b'))
      setCaret(root.childNodes[1]!, 0)
      expect(findAdjacentPill(root, 'before')).toBe(root.childNodes[0])
    } finally {
      unmountRoot(root)
    }
  })

  it('finds the pill after a text caret at the node end', () => {
    // 文本节点容器 + after：offset 等于文本长度时命中 nextSibling 上的 pill。
    const root = mountRoot(document.createElement('div'))
    try {
      root.append(textNode('a'), attachmentPill('upload-1', 'a.txt'))
      setCaret(root.childNodes[0]!, 1)
      expect(findAdjacentPill(root, 'after')).toBe(root.childNodes[1])
    } finally {
      unmountRoot(root)
    }
  })

  it('returns null when a text caret is not on a node boundary', () => {
    // 文本中间偏移：before 需要 offset 0、after 需要 offset 等于长度，否则无候选。
    const root = mountRoot(document.createElement('div'))
    try {
      root.append(textNode('ab'), attachmentPill('upload-1', 'a.txt'))
      setCaret(root.childNodes[0]!, 1)
      expect(findAdjacentPill(root, 'before')).toBeNull()
      expect(findAdjacentPill(root, 'after')).toBeNull()
    } finally {
      unmountRoot(root)
    }
  })

  it('finds the pill by element child index in both directions', () => {
    // caret 直接落在编辑器元素上：before 取 offset-1、after 取 offset 处子节点。
    const root = mountRoot(document.createElement('div'))
    try {
      root.append(attachmentPill('upload-1', 'a.txt'), textNode('b'))
      setCaret(root, 1)
      expect(findAdjacentPill(root, 'before')).toBe(root.childNodes[0])
      setCaret(root, 0)
      expect(findAdjacentPill(root, 'after')).toBe(root.childNodes[0])
    } finally {
      unmountRoot(root)
    }
  })

  it('returns null at element caret boundaries without a pill', () => {
    // 元素容器边界：before 需要 offset > 0、after 需要 offset < 子节点数。
    const root = mountRoot(document.createElement('div'))
    try {
      root.append(attachmentPill('upload-1', 'a.txt'), textNode('b'))
      setCaret(root, 0)
      expect(findAdjacentPill(root, 'before')).toBeNull()
      setCaret(root, 2)
      expect(findAdjacentPill(root, 'after')).toBeNull()
    } finally {
      unmountRoot(root)
    }
  })

  it('returns null when the adjacent node is not a pill', () => {
    // 邻接节点是普通文本节点时不返回（删除交给浏览器默认行为）。
    const root = mountRoot(document.createElement('div'))
    try {
      root.append(textNode('a'), textNode('b'))
      setCaret(root.childNodes[0]!, 1)
      expect(findAdjacentPill(root, 'after')).toBeNull()
    } finally {
      unmountRoot(root)
    }
  })

  it('returns null when a text caret offset is inside the text node', () => {
    // 文本节点容器 + after 的「非末尾偏移」侧：offset 不等于文本长度时无候选。
    const root = mountRoot(document.createElement('div'))
    try {
      root.append(textNode('ab'), attachmentPill('upload-1', 'a.txt'))
      setCaret(root.childNodes[0]!, 1)
      expect(findAdjacentPill(root, 'after')).toBeNull()
    } finally {
      unmountRoot(root)
    }
  })

  it('returns null when a text caret before has no pill sibling at offset zero', () => {
    // 文本节点容器 + before 的「非零偏移」侧：offset 不为 0 时 previousSibling 无候选。
    const root = mountRoot(document.createElement('div'))
    try {
      root.append(textNode('ab'), attachmentPill('upload-1', 'a.txt'))
      setCaret(root.childNodes[0]!, 1)
      expect(findAdjacentPill(root, 'before')).toBeNull()
    } finally {
      unmountRoot(root)
    }
  })
})
