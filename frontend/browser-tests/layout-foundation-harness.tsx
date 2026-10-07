import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import '@/styles.css'
import { setLocale } from '@/shared/i18n'
import { Select } from '@/shared/ui/controls/Select'
import { Dialog } from '@/shared/ui/overlays/Dialog'

setLocale('zh-CN')

/**
 * 布局基座门禁的真实控件基座。
 *
 * 挂载生产共享 Dialog 与 Select（含弹窗内子控件），用于在真实浏览器中固化：
 * 关闭后焦点归还 opener、弹窗内 Select 的 Escape 优先收弹层再由下一次 Escape 关闭弹窗。
 * 不引入任何临时兼容层或替身控件——被验证的就是产品实际使用的实现。
 */
export function LayoutFoundationHarnessApp() {
  const [dialogOpen, setDialogOpen] = useState(false)
  const [model, setModel] = useState('alpha')

  return (
    <div style={{ padding: 24, display: 'flex', flexDirection: 'column', gap: 12, alignItems: 'flex-start' }}>
      <h1 style={{ margin: 0, fontSize: 16 }}>Layout Foundation Harness</h1>
      <button type="button" id="open-foundation-dialog" className="btn-primary" onClick={() => setDialogOpen(true)}>
        打开基础弹窗
      </button>
      {dialogOpen ? (
        <Dialog title="基础交互弹窗" onClose={() => setDialogOpen(false)}>
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
    </div>
  )
}

const rootEl = document.getElementById('root')
if (rootEl) {
  createRoot(rootEl).render(<LayoutFoundationHarnessApp />)
}
