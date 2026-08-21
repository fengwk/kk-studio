import { Select } from '@/shared/ui/console/Select'
import { useI18n, type AppLocale } from '@/shared/i18n'

const localeOptions: Array<{ value: AppLocale; label: string }> = [
  { value: 'en-US', label: 'English' },
  { value: 'zh-CN', label: '中文' },
]

export function LocaleSelector({ className }: { className?: string }) {
  const { locale, setLocale, t } = useI18n()
  const currentLabel = localeOptions.find((option) => option.value === locale)?.label ?? locale
  return (
    <Select
      className={['locale-selector', className].filter(Boolean).join(' ')}
      compact
      value={locale}
      options={localeOptions}
      aria-label={`${t('platform.localeSelector')}: ${currentLabel}`}
      listboxLabel={t('platform.localeSelector')}
      onChange={(next) => setLocale(next as AppLocale)}
    />
  )
}
