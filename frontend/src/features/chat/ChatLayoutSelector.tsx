import { useI18n } from '@/shared/i18n'
import { Select } from '@/shared/ui/controls/Select'
import { parseLayout, type ChatLayout } from '@/features/chat/chat-pane-state'

const LAYOUTS: Array<{ value: ChatLayout; label: string }> = [
  { value: 'single', label: '1' },
  { value: 'split-2', label: '2' },
  { value: 'split-3', label: '3' },
  { value: 'grid-4', label: '4' },
  { value: 'grid-5', label: '5' },
  { value: 'grid-6', label: '6' },
  { value: 'grid-7', label: '7' },
  { value: 'grid-8', label: '8' },
]

interface ChatLayoutSelectorProps {
  layout: ChatLayout
  onChange: (layout: ChatLayout) => void
  selectId?: string
}

/** 面板布局选择器：标签就是可见 pane 数量，未识别的值回落到 single。 */
export function ChatLayoutSelector({
  layout,
  onChange,
  selectId = 'chat-layout-select',
}: ChatLayoutSelectorProps) {
  const { t } = useI18n()
  return (
    <Select
      id={selectId}
      className="chat-layout-selector"
      compact
      value={layout}
      options={LAYOUTS}
      aria-label={t('ai.chat.layout')}
      onChange={(next) => onChange(parseLayout(next))}
    />
  )
}
