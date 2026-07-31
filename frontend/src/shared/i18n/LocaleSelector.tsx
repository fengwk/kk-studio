import { useI18n, type AppLocale } from '@/shared/i18n'

const localeOptions: Array<{ value: AppLocale; label: string }> = [
  { value: 'en-US', label: 'English' },
  { value: 'zh-CN', label: '中文' },
]

export function LocaleSelector({ className }: { className?: string }) {
  const { locale, setLocale, t } = useI18n()

  return (
    <div
      className={['locale-selector', className].filter(Boolean).join(' ')}
      role="group"
      aria-label={t('platform.localeSelector')}
    >
      {localeOptions.map((option) => (
        <button
          key={option.value}
          type="button"
          aria-pressed={locale === option.value}
          onClick={() => setLocale(option.value)}
        >
          {option.label}
        </button>
      ))}
    </div>
  )
}
