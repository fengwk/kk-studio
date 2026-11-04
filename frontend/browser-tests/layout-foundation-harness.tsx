import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import '@/styles.css'
import { setLocale } from '@/shared/i18n'
import { Select } from '@/shared/ui/controls/Select'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { ConfirmActionModal } from '@/shared/ui/overlays/ConfirmActionModal'

setLocale('zh-CN')

/**
 * 在浏览器中验证共享 Dialog 的焦点归还，以及嵌套 Select 的 Escape 处理顺序。
 */
export function LayoutFoundationHarnessApp() {
  const [dialogOpen, setDialogOpen] = useState(false)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [model, setModel] = useState('alpha')

  return (
    <div style={{ padding: 24, display: 'flex', flexDirection: 'column', gap: 12, alignItems: 'flex-start' }}>
      <h1 style={{ margin: 0, fontSize: 16 }}>Layout Foundation Harness</h1>
      <button type="button" id="open-foundation-dialog" className="btn-primary" onClick={() => setDialogOpen(true)}>
        打开基础弹窗
      </button>
      <button type="button" className="btn-primary" onClick={() => setConfirmOpen(true)}>
        打开确认弹窗
      </button>
      {dialogOpen ? (
        <Dialog className="modal-touch-target" title="基础交互弹窗" onClose={() => setDialogOpen(false)}>
          <div className="modal-body">
            <div className="form-group">
              <span>模型</span>
              <Select
                aria-label="模型"
                value={model}
                options={[
                  { value: 'alpha', label: 'Alpha' },
                  { value: 'bravo', label: 'Bravo' },
                ]}
                onChange={setModel}
              />
            </div>
          </div>
          <div className="modal-footer">
            <button type="button" className="ghost-btn" onClick={() => setDialogOpen(false)}>
              取消
            </button>
          </div>
        </Dialog>
      ) : null}
      <ConfirmActionModal
        className="modal-touch-target"
        modal={confirmOpen ? {
          title: '删除记录？',
          description: '此操作会删除记录。',
          confirmLabel: '删除',
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
