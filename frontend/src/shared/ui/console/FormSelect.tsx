import { Select, type SelectOption } from '@/shared/ui/console/Select'

export type FormSelectOption = SelectOption

/** 表单下拉的稳定入口；实现为自定义 listbox，避免操作系统原生 option 菜单。 */
export function FormSelect(props: {
  id?: string
  value: string
  options: FormSelectOption[]
  onChange: (value: string) => void
  disabled?: boolean
  required?: boolean
  placeholder?: string
  'aria-label'?: string
  'aria-describedby'?: string
  'aria-invalid'?: boolean
}) {
  return <Select {...props} />
}
