import { useI18n } from '@/shared/i18n'
import { Select, type SelectOption } from '@/shared/ui/controls/Select'
import { parseLayout, type ChatLayout } from '@/features/chat/chat-pane-state'

/** 布局值与它展示的 pane 数量：数量既是排序依据，也是选项文案里的量词来源。 */
const LAYOUTS: Array<{ value: ChatLayout; panes: number }> = [
  { value: 'single', panes: 1 },
  { value: 'split-2', panes: 2 },
  { value: 'split-3', panes: 3 },
  { value: 'grid-4', panes: 4 },
  { value: 'grid-5', panes: 5 },
  { value: 'grid-6', panes: 6 },
  { value: 'grid-7', panes: 7 },
  { value: 'grid-8', panes: 8 },
]

interface ChatLayoutSelectorProps {
  layout: ChatLayout
  onChange: (layout: ChatLayout) => void
  selectId?: string
}

/**
 * 面板布局选择器：选项名带量词（中文「3 个窗格」、英文按数量区分单复数），
 * 读屏不再只听到裸数字；未识别的值回落到 single。
 */
export function ChatLayoutSelector({
  layout,
  onChange,
  selectId = 'chat-layout-select',
}: ChatLayoutSelectorProps) {
  const { t } = useI18n()
  const options: SelectOption[] = LAYOUTS.map(({ value, panes }) => ({
    value,
    label: panes === 1
      ? t('ai.chat.paneCount.one')
      : t('ai.chat.paneCount.other', { count: panes }),
  }))
  return (
    <Select
      id={selectId}
      className="chat-layout-selector"
      compact
      value={layout}
      options={options}
      aria-label={t('ai.chat.layout')}
      onChange={(next) => onChange(parseLayout(next))}
    />
  )
}
