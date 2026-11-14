import { Button } from '@/shared/ui/controls/Button'
import { useI18n } from '@/shared/i18n'
import type { JSONPath } from 'jsonc-parser'

interface UnsetOptionalFieldButtonProps {
  name: string
  path: JSONPath
  disabled: boolean
  /** 点击前的本地清理（令牌失效、草稿与文件错误清空），由持有本地状态的字段提供。 */
  onBeforeRemove?: () => void
  onRemove: (path: JSONPath) => void
}

/**
 * 可选字段的显式省略入口：只有它删除键，文本框清空永远是写入合法空串。
 * 字段当前不存在时没有可省略的状态，因此调用方不渲染。
 */
export function UnsetOptionalFieldButton({
  name,
  path,
  disabled,
  onBeforeRemove,
  onRemove,
}: UnsetOptionalFieldButtonProps) {
  const { t } = useI18n()
  return (
    <Button
      variant="ghost"
      size="compact"
      disabled={disabled}
      aria-label={t('plugins.configDialog.unsetOptionalField', { name })}
      onClick={() => {
        onBeforeRemove?.()
        onRemove(path)
      }}
    >
      {t('plugins.configDialog.unsetOptionalFieldLabel')}
    </Button>
  )
}
