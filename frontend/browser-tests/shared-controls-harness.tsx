import { useState, type ReactNode } from 'react'
import { createRoot } from 'react-dom/client'
import '@/styles.css'
import { Button } from '@/shared/ui/controls/Button'
import { Checkbox } from '@/shared/ui/controls/Checkbox'
import { NumberInput } from '@/shared/ui/controls/NumberInput'
import { SearchField } from '@/shared/ui/controls/SearchField'
import { Select } from '@/shared/ui/controls/Select'
import { TextArea } from '@/shared/ui/controls/TextArea'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { Dialog } from '@/shared/ui/overlays/Dialog'

const OPTIONS = [
  { value: 'alpha', label: '选项 A' },
  { value: 'beta', label: '选项 B' },
]

/** 复刻真实表单的 .form-group 包裹（label + 控件），用于验证共享控件不被旧表单规则改写。 */
function Field({
  label,
  children,
  error = false,
}: {
  label: string
  children: ReactNode
  /** 复刻旧表单的 .form-group.is-error 包裹，验证共享控件不被其 !important 规则改写。 */
  error?: boolean
}) {
  return (
    <div className={error ? 'form-group is-error' : 'form-group'} style={{ width: 320 }}>
      <span>{label}</span>
      {children}
    </div>
  )
}

/**
 * 真实上下文中的共享表单控件：正常 / 校验失败 / 禁用 三种状态。
 * 这里刻意放进 .form-group（以及弹层的 .modal-body），因为旧表单规则正作用在这些祖先上。
 */
function FormControls({ scope }: { scope: string }) {
  const [name, setName] = useState('资源名称')
  const [disabledName, setDisabledName] = useState('不可编辑')
  const [kind, setKind] = useState('alpha')
  const [invalidKind, setInvalidKind] = useState('alpha')
  const [disabledKind, setDisabledKind] = useState('alpha')
  const [timeoutValue, setTimeoutValue] = useState('30')
  const [plainTimeout, setPlainTimeout] = useState('30')
  const [disabledTimeout, setDisabledTimeout] = useState('30')
  const [enabled, setEnabled] = useState(true)
  const [plainEnabled, setPlainEnabled] = useState(true)
  const [note, setNote] = useState('说明文字')
  const [search, setSearch] = useState('')

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12, width: 320 }}>
      <Field label={`${scope} 名称`}>
        <TextInput
          className="control-text"
          aria-label={`${scope} 文本输入`}
          value={name}
          onChange={(event) => setName(event.target.value)}
        />
      </Field>
      <Field label={`${scope} 名称（校验失败）`}>
        <TextInput
          className="control-text-invalid"
          aria-label={`${scope} 校验失败文本输入`}
          invalid
          value=""
          onChange={() => undefined}
        />
      </Field>
      <Field label={`${scope} 名称（禁用）`}>
        <TextInput
          className="control-text-disabled"
          aria-label={`${scope} 禁用文本输入`}
          disabled
          value={disabledName}
          onChange={(event) => setDisabledName(event.target.value)}
        />
      </Field>

      <Field label={`${scope} 类型`}>
        <Select
          className="control-select"
          aria-label={`${scope} 下拉`}
          value={kind}
          options={OPTIONS}
          onChange={setKind}
        />
      </Field>
      <Field label={`${scope} 类型（校验失败）`}>
        <Select
          className="control-select-invalid"
          aria-label={`${scope} 校验失败下拉`}
          aria-invalid
          value={invalidKind}
          options={OPTIONS}
          onChange={setInvalidKind}
        />
      </Field>
      <Field label={`${scope} 类型（禁用）`}>
        <Select
          className="control-select-disabled"
          aria-label={`${scope} 禁用下拉`}
          disabled
          value={disabledKind}
          options={OPTIONS}
          onChange={setDisabledKind}
        />
      </Field>
      <Field label={`${scope} 紧凑类型`}>
        <Select
          className="control-select-compact"
          aria-label={`${scope} 紧凑下拉`}
          compact
          value={disabledKind}
          options={OPTIONS}
          onChange={setDisabledKind}
        />
      </Field>

      <Field label={`${scope} 超时`}>
        <NumberInput
          className="control-number"
          aria-label={`${scope} 数值输入`}
          value={timeoutValue}
          onChange={setTimeoutValue}
        />
      </Field>
      <Field label={`${scope} 超时（校验失败）`}>
        <NumberInput
          className="control-number-invalid"
          aria-label={`${scope} 校验失败数值输入`}
          invalid
          value="0"
          onChange={() => undefined}
        />
      </Field>
      <Field label={`${scope} 超时（禁用）`}>
        <NumberInput
          className="control-number-disabled"
          aria-label={`${scope} 禁用数值输入`}
          disabled
          value={disabledTimeout}
          onChange={setDisabledTimeout}
        />
      </Field>
      <Field label={`${scope} 自定义类名数值`}>
        <NumberInput
          className="control-number-decorated"
          aria-label={`${scope} 带修饰类名的数值输入`}
          value={plainTimeout}
          onChange={setPlainTimeout}
        />
      </Field>

      <Field label={`${scope} 启用`}>
        <Checkbox
          className="control-checkbox"
          aria-label={`${scope} 复选框`}
          checked={enabled}
          onChange={setEnabled}
        />
      </Field>
      <Field label={`${scope} 启用（校验失败）`}>
        <Checkbox
          className="control-checkbox-invalid"
          aria-label={`${scope} 校验失败复选框`}
          invalid
          checked={false}
          onChange={() => undefined}
        />
      </Field>
      <Field label={`${scope} 启用（禁用）`}>
        <Checkbox
          className="control-checkbox-disabled"
          aria-label={`${scope} 禁用复选框`}
          disabled
          checked={plainEnabled}
          onChange={setPlainEnabled}
        />
      </Field>

      <Field label={`${scope} 说明`}>
        <TextArea
          className="control-textarea"
          aria-label={`${scope} 多行文本`}
          value={note}
          onChange={(event) => setNote(event.target.value)}
        />
      </Field>
      <Field label={`${scope} 旧式错误包裹（文本）`} error>
        <TextInput
          className="control-text-error-context"
          aria-label={`${scope} 旧式错误包裹文本`}
          invalid
          value=""
          onChange={() => undefined}
        />
      </Field>
      <Field label={`${scope} 旧式错误包裹（数值）`} error>
        <NumberInput
          className="control-number-error-context"
          aria-label={`${scope} 旧式错误包裹数值`}
          invalid
          value="0"
          onChange={() => undefined}
        />
      </Field>

      <Field label={`${scope} 说明（校验失败）`}>
        <TextArea
          className="control-textarea-invalid"
          aria-label={`${scope} 校验失败多行文本`}
          invalid
          value=""
          onChange={() => undefined}
        />
      </Field>

      <Field label={`${scope} 搜索`}>
        <SearchField
          value={search}
          onChange={setSearch}
          aria-label={`${scope} 搜索框`}
        />
      </Field>
    </div>
  )
}

/** 页级搜索入口的真实上下文：工具条内联展示，不与 form-group 同处一行。 */
function PageSection() {
  const [search, setSearch] = useState('')
  return (
    <section style={{ padding: 24 }}>
      <h1 style={{ fontSize: 15 }}>表单上下文</h1>
      <div className="harness-toolbar" style={{ display: 'flex', alignItems: 'center', width: 320 }}>
        <SearchField value={search} onChange={setSearch} aria-label="页级搜索框" />
      </div>
      <FormControls scope="页内" />
    </section>
  )
}

function ModalSection() {
  const [open, setOpen] = useState(false)
  return (
    <section style={{ padding: 0 }}>
      <Button id="open-controls-dialog" onClick={() => setOpen(true)}>
        打开弹层
      </Button>
      {open ? (
        <Dialog
          title="共享控件（弹层内）"
          closeLabel="关闭"
          className="harness-dialog"
          onClose={() => setOpen(false)}
        >
          <div className="modal-body">
            <FormControls scope="弹层内" />
          </div>
        </Dialog>
      ) : null}
    </section>
  )
}

export function SharedControlsHarnessApp() {
  return (
    <div className="harness-root">
      <PageSection />
      <ModalSection />
    </div>
  )
}

const rootEl = document.getElementById('root')
if (rootEl) {
  createRoot(rootEl).render(<SharedControlsHarnessApp />)
}
