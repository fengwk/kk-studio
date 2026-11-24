import { Button } from '@/shared/ui/controls/Button'
import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import '@/styles.css'
import { setLocale } from '@/shared/i18n'
import { Select } from '@/shared/ui/controls/Select'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { ConfirmActionModal } from '@/shared/ui/overlays/ConfirmActionModal'

// 语言由查询串决定：默认 zh-CN，`locale=en-US` 用于校验英文界面文案。
const locale = new URLSearchParams(window.location.search).get('locale') === 'en-US' ? 'en-US' : 'zh-CN'
setLocale(locale)

/** 夹具文案跟随语言，组件内部文案（关闭/确认）由 i18n 提供。 */
const copy = locale === 'en-US'
  ? {
      openDialog: 'Open foundation dialog',
      openConfirm: 'Open confirm dialog',
      dialogTitle: 'Foundation dialog',
      modelLabel: 'Model',
      compactLabel: 'Compact model',
      cancel: 'Cancel',
      save: 'Save',
      confirmTitle: 'Delete record?',
      confirmDescription: 'This deletes the record.',
      confirmLabel: 'Delete',
    }
  : {
      openDialog: '打开基础弹窗',
      openConfirm: '打开确认弹窗',
      dialogTitle: '基础交互弹窗',
      modelLabel: '模型',
      compactLabel: '紧凑模型',
      cancel: '取消',
      save: '保存',
      confirmTitle: '删除记录？',
      confirmDescription: '此操作会删除记录。',
      confirmLabel: '删除',
    }

/** 选项值带 g/p/y/q/j 下伸部：触发器裁切时会在截图与几何断言里暴露。 */
const MODEL_OPTIONS = [
  { value: 'gpt-4o-jpy', label: 'gpt-4o-jpy' },
  { value: 'gpt-5.1-pro', label: 'gpt-5.1-pro' },
  { value: 'qwen3-max-preview', label: 'qwen3-max-preview' },
]

/**
 * 在浏览器中验证共享 Dialog 的焦点归还、嵌套 Select 的 Escape 处理顺序，
 * 以及 Select 触发器与弹窗底栏的真实几何。
 */
export function LayoutFoundationHarnessApp() {
  const [dialogOpen, setDialogOpen] = useState(false)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [model, setModel] = useState('gpt-4o-jpy')
  const [compactModel, setCompactModel] = useState('qwen3-max-preview')

  return (
    <div style={{ padding: 24, display: 'flex', flexDirection: 'column', gap: 12, alignItems: 'flex-start' }}>
      <h1 style={{ margin: 0, fontSize: 16 }}>Layout Foundation Harness</h1>
      <Button type="button" id="open-foundation-dialog" onClick={() => setDialogOpen(true)}>
        {copy.openDialog}
      </Button>
      <Button type="button" id="open-confirm-dialog" onClick={() => setConfirmOpen(true)}>
        {copy.openConfirm}
      </Button>
      <div style={{ width: 260 }}>
        <div className="form-group">
          <span>{copy.modelLabel}</span>
          <Select
            id="standard-model-select"
            aria-label={copy.modelLabel}
            value={model}
            options={MODEL_OPTIONS}
            onChange={setModel}
          />
        </div>
      </div>
      <div id="compact-model-select-group" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <span>{copy.compactLabel}</span>
        <Select
          id="compact-model-select"
          aria-label={copy.compactLabel}
          compact
          value={compactModel}
          options={MODEL_OPTIONS}
          onChange={setCompactModel}
        />
      </div>
      {dialogOpen ? (
        <Dialog className="modal-touch-target" title={copy.dialogTitle} onClose={() => setDialogOpen(false)}>
          <div className="modal-body">
            <div className="form-group">
              <span>{copy.modelLabel}</span>
              <Select
                aria-label={copy.modelLabel}
                value={model}
                options={MODEL_OPTIONS}
                onChange={setModel}
              />
            </div>
          </div>
          <div className="modal-footer">
            <Button type="button" variant="secondary" onClick={() => setDialogOpen(false)}>
              {copy.cancel}
            </Button>
            <Button type="button" onClick={() => setDialogOpen(false)}>
              {copy.save}
            </Button>
          </div>
        </Dialog>
      ) : null}
      <ConfirmActionModal
        className="modal-touch-target"
        modal={confirmOpen ? {
          title: copy.confirmTitle,
          description: copy.confirmDescription,
          confirmLabel: copy.confirmLabel,
          tone: 'danger',
          onConfirm: () => setConfirmOpen(false),
        } : null}
        pending={false}
        onClose={() => setConfirmOpen(false)}
      />
    </div>
  )
}

const rootEl = document.getElementById('root')
if (rootEl) {
  createRoot(rootEl).render(<LayoutFoundationHarnessApp />)
}
