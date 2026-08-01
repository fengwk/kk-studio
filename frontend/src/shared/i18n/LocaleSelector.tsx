import { useI18n, type AppLocale } from '@/shared/i18n'

const localeOptions: Array<{ value: AppLocale; label: string }> = [
  { value: 'en-US', label: 'English' },
  { value: 'zh-CN', label: '中文' },
]

export function LocaleSelector({ className }: { className?: string }) {
  const { locale, setLocale, t } = useI18n()

  return (
    <select
      className={['locale-selector', className].filter(Boolean).join(' ')}
      aria-label={t('platform.localeSelector')}
      value={locale}
      onChange={(event) => setLocale(event.target.value as AppLocale)}
    >
      {localeOptions.map((option) => (
        <option
          key={option.value}
          value={option.value}
        >
          {option.label}
        </option>
      ))}
    </select>
  )
}
