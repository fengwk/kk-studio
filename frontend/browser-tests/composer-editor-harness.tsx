import { useCallback, useMemo, useState } from 'react'
import { createRoot } from 'react-dom/client'
import '@/styles.css'
import { setLocale } from '@/shared/i18n'
import { ThreadComposer } from '@/features/ai/runtime/thread-panel/ThreadComposer'
import { THREAD_COMMANDS } from '@/features/ai/runtime/thread-panel/thread-commands'
import type { ComposerPart, HashFile, StorageService } from '@/features/ai/composer'

setLocale('zh-CN')

/** 浏览器回归可控失败开关：下一次 reserve 在延迟后失败，便于在异步失败前发起 IME 组合。 */
let failNextUpload = false
let uploadCounter = 0

/**
 * 确定性上传面：reserve 走 PENDING 直传契约，complete 返回同一句柄（id 不变），
 * 用于在真实浏览器中驱动 pill 插入/回滚而不依赖后端。
 */
function createFakeStorage(): StorageService {
  return {
    reserveUpload: async (): Promise<Awaited<ReturnType<StorageService['reserveUpload']>>> => {
      uploadCounter += 1
      const id = `up-${uploadCounter}`
      if (failNextUpload) {
        failNextUpload = false
        // 与组合输入并发：失败刻意慢于 IME 组合建立，验证回滚延后到 compositionend。
        await new Promise((resolve) => setTimeout(resolve, 800))
        throw new Error('reserve unavailable')
      }
      return {
        id,
        state: 'PENDING',
        blobId: null,
        presignedPut: { method: 'PUT', url: `https://s3.test/${id}`, headers: {} },
        expiresAt: null,
      }
    },
    completeUpload: async (uploadId: string) => ({
      id: uploadId,
      state: 'READY',
      blobId: `blob-${uploadId}`,
      presignedPut: null,
      expiresAt: null,
    }),
    deleteUpload: async () => undefined,
    getBlobDownloadUrl: async () => ({ url: 'https://s3.test/orig', expiresAt: null }),
    getBlobPreviewUrl: async () => ({ url: 'https://s3.test/prev', expiresAt: null }),
    uploadFile: async () => undefined,
  } as unknown as StorageService
}

const hashFile: HashFile = async () => 'a'.repeat(64)

export function ComposerEditorHarness() {
  const [parts, setParts] = useState<ComposerPart[]>([])
  const [submitCount, setSubmitCount] = useState(0)
  const [lastSubmitted, setLastSubmitted] = useState<string | null>(null)
  const [active, setActive] = useState(true)
  const [disabled, setDisabled] = useState(false)
  const storage = useMemo(() => createFakeStorage(), [])

  const handleSubmit = useCallback((_payload: ComposerPart[], localDraft: ComposerPart[]) => {
    setSubmitCount((count) => count + 1)
    setLastSubmitted(JSON.stringify(localDraft))
    setParts([])
  }, [])

  return (
    <div style={{ padding: 16, display: 'flex', flexDirection: 'column', gap: 12 }}>
      <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
        <button id="outside-btn" type="button">
          外部按钮
        </button>
        <button
          id="fail-upload-btn"
          type="button"
          onClick={() => {
            failNextUpload = true
          }}
        >
          使下一次上传失败
        </button>
        <label>
          <input
            id="active-toggle"
            type="checkbox"
            checked={active}
            onChange={(event) => setActive(event.currentTarget.checked)}
          />
          根 Composer 可见
        </label>
        <label>
          <input
            id="disabled-toggle"
            type="checkbox"
            checked={disabled}
            onChange={(event) => setDisabled(event.currentTarget.checked)}
          />
          草稿只读
        </label>
      </div>
      <pre id="parts-json" style={{ margin: 0 }}>
        {JSON.stringify(parts)}
      </pre>
      <div>
        提交次数：<span id="submit-count">{submitCount}</span>
      </div>
      <div>
        最近提交：<span id="last-submitted">{lastSubmitted ?? 'none'}</span>
      </div>
      <div id="root-pane" data-testid="root-pane">
        <ThreadComposer
          parts={parts}
          pending={false}
          disabled={disabled}
          focusOnEscape
          active={active}
          onPartsChange={setParts}
          onSubmit={handleSubmit}
          onCommand={() => undefined}
          commands={THREAD_COMMANDS}
          storageService={storage}
          hashFile={hashFile}
        />
      </div>
    </div>
  )
}

const rootElement = document.getElementById('root')
if (rootElement) {
  createRoot(rootElement).render(<ComposerEditorHarness />)
}
