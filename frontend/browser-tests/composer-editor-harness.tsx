import { useCallback, useMemo, useState } from 'react'
import { createRoot } from 'react-dom/client'
import '@/styles.css'
import { setLocale, type AppLocale } from '@/shared/i18n'
import { ThreadComposer } from '@/features/thread/runtime/thread-panel/ThreadComposer'
import { THREAD_COMMANDS } from '@/features/thread/runtime/thread-panel/thread-commands'
import type { ComposerPart, StorageService } from '@/features/thread/composer'
import type { StorageUploadReserveRequestDTO } from '@/shared/api/generated/contracts'
import type { HashFile } from '@/shared/lib/upload-file'

const harnessParams = new URLSearchParams(window.location.search)
const harnessLocale: AppLocale = harnessParams.get('locale') === 'en-US' ? 'en-US' : 'zh-CN'
setLocale(harnessLocale)

/**
 * 视觉矩阵用的长失败原因：超过旧实现 120 码点的截断阈值，且带有不可断的
 * 对象名，用来验证提示条换行后仍完整可读而不是被省略。
 */
const LONG_UPLOAD_FAILURE_REASON: Record<AppLocale, string> = {
  'zh-CN':
    '存储服务拒绝了这次写入（HTTP 413 Payload Too Large）：release-notes-2026-11-22T09-19-23-716085Z.md 超过目标环境配置的单对象大小上限，请压缩后重试，或改存到其他位置再重新添加文件。',
  'en-US':
    'Storage rejected this write (HTTP 413 Payload Too Large): release-notes-2026-11-22T09-19-23-716085Z.md exceeds the per-object limit configured for the target environment. Compress the file and retry, or store it elsewhere and add the file again.',
}

declare global {
  interface Window {
    imageUploadTestResult: {
      request: StorageUploadReserveRequestDTO | null
      uploaded: Blob | null
    }
  }
}
window.imageUploadTestResult = { request: null, uploaded: null }

/** 浏览器回归可控失败开关：下一次 reserve 在延迟后失败，便于在异步失败前发起 IME 组合。 */
let failNextUpload = false
/** 下一次失败使用的服务端原因；默认沿用回归用例的短原因。 */
let nextUploadFailureReason = 'reserve unavailable'
let uploadCounter = 0

/** 让下一次上传以指定原因失败。 */
function failUploadWith(reason: string) {
  failNextUpload = true
  nextUploadFailureReason = reason
}

/**
 * 确定性上传面：reserve 走 PENDING 直传契约，complete 返回同一句柄（id 不变），
 * 用于在真实浏览器中驱动 pill 插入/回滚而不依赖后端。
 */
function createFakeStorage(): StorageService {
  return {
    reserveUpload: async (request: StorageUploadReserveRequestDTO): Promise<Awaited<ReturnType<StorageService['reserveUpload']>>> => {
      window.imageUploadTestResult.request = request
      uploadCounter += 1
      const id = `up-${uploadCounter}`
      if (failNextUpload) {
        failNextUpload = false
        const reason = nextUploadFailureReason
        nextUploadFailureReason = 'reserve unavailable'
        // 与组合输入并发：失败刻意慢于 IME 组合建立，验证回滚延后到 compositionend。
        await new Promise((resolve) => setTimeout(resolve, 800))
        throw new Error(reason)
      }
      return {
        id,
        type: 'PENDING',
        presignedPut: {
          method: 'PUT',
          url: `https://s3.test/${id}`,
          headers: {},
          expiresAt: 0,
          mediaType: null,
          sizeBytes: null,
        },
        expiresAt: 0,
        format: null,
        originalWidth: null,
        originalHeight: null,
        storedWidth: null,
        storedHeight: null,
      }
    },
    completeUpload: async (uploadId: string) => ({
      id: uploadId,
      type: 'READY',
      blobId: `blob-${uploadId}`,
      expiresAt: 0,
      format: null,
      originalWidth: null,
      originalHeight: null,
      storedWidth: null,
      storedHeight: null,
    }),
    deleteUpload: async () => undefined,
    uploadFile: async (_put: Parameters<StorageService['uploadFile']>[0], file: Blob) => {
      window.imageUploadTestResult.uploaded = file
    },
  } as unknown as StorageService
}

const hashFile: HashFile = async () => 'a'.repeat(64)

export function ComposerEditorHarness() {
  const [parts, setParts] = useState<ComposerPart[]>([])
  const [submitCount, setSubmitCount] = useState(0)
  const [lastSubmitted, setLastSubmitted] = useState<string | null>(null)
  const [lastCommand, setLastCommand] = useState<string | null>(null)
  const [active, setActive] = useState(true)
  const [disabled, setDisabled] = useState(false)
  const storage = useMemo(() => createFakeStorage(), [])

  const handleSubmit = useCallback((_payload: ComposerPart[], localDraft: ComposerPart[]) => {
    setSubmitCount((count) => count + 1)
    setLastSubmitted(JSON.stringify(localDraft))
    setParts([])
  }, [])

  return (
    // 与真实页面一致：Composer 停在视口底部，提示条与命令表在其上方展开，
    // 视觉矩阵才能断言「不被视口边缘裁切」；内容高于视口时容器内可滚动。
    <div
      data-testid="harness-scroll"
      style={{
        boxSizing: 'border-box',
        height: '100%',
        overflowY: 'auto',
        display: 'flex',
        flexDirection: 'column',
        gap: 12,
        padding: 16,
      }}
    >
      {/* 视觉矩阵用例从这里读取夹具事实，不在 spec 里重复一份文案。 */}
      <div
        id="runtime-harness-facts"
        hidden
        data-locale={harnessLocale}
        data-upload-failure-reason={LONG_UPLOAD_FAILURE_REASON[harnessLocale]}
      />
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12, alignItems: 'center' }}>
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
        <button
          id="fail-upload-long-btn"
          type="button"
          onClick={() => failUploadWith(LONG_UPLOAD_FAILURE_REASON[harnessLocale])}
        >
          使下一次上传以长原因失败
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
      <div>
        最近命令：<span id="last-command-val">{lastCommand ?? 'none'}</span>
      </div>
      <div style={{ flex: 1 }} />
      <div id="root-pane" data-testid="root-pane">
        <ThreadComposer
          parts={parts}
          pending={false}
          disabled={disabled}
          focusOnEscape
          active={active}
          onPartsChange={setParts}
          onSubmit={handleSubmit}
          onCommand={(command) => setLastCommand(command.id)}
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
