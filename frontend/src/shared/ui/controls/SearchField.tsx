import { Search } from 'lucide-react'
import { useI18n } from '@/shared/i18n'
import './SearchField.css'

export interface SearchFieldProps {
  value: string
  onChange: (value: string) => void
  /** 占位文案；省略时使用共享的“搜索资源”文案。 */
  placeholder?: string
  invalid?: boolean
  'aria-describedby'?: string
  'aria-label'?: string
}

/**
 * 共享页级搜索框：图标 + 输入，统一各资源的搜索入口样式。
 */
export function SearchField({
  value,
  onChange,
  placeholder,
  invalid,
  'aria-describedby': describedBy,
  'aria-label': ariaLabel,
}: SearchFieldProps) {
  const { t } = useI18n()

  return (
    <label className="searchbox" data-ui-focus="within">
      <Search aria-hidden="true" />
      <input
        className="ui-search-input"
        data-ui-focus="delegate"
        value={value}
        onChange={(event) => onChange(event.target.value)}
        placeholder={placeholder ?? t('shared.searchResources')}
        aria-label={ariaLabel}
        aria-invalid={invalid || undefined}
        aria-describedby={describedBy}
      />
    </label>
  )
}
