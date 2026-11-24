import { CopyPlus, GitBranch } from 'lucide-react'
import type { HistoryTreeRow } from '@/features/thread/history-tree'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import type { HarnessSessionEntryDTO } from '@/shared/api/generated/contracts'

/**
 * 历史树底栏：当前位置提示、不可分支原因，以及三个显式动作。
 *
 * 只有 ROOT 与已关闭 TURN_END 可分支，其余行禁用动作并给出原因。
 */
export function HistoryTreeFooter({
  loading,
  selectedRow,
  selectedIndex,
  rowCount,
  onClose,
  onFork,
  onForkSession,
}: {
  loading: boolean
  selectedRow: HistoryTreeRow | null
  selectedIndex: number
  rowCount: number
  onClose: () => void
  onFork: (entry: HarnessSessionEntryDTO) => void
  /** 显式“从此处新建会话”：缺失时（只读/非 Chat 宿主）不渲染该动作。 */
  onForkSession?: (entry: HarnessSessionEntryDTO) => void
}) {
  const { t } = useI18n()
  return (
    <div className="history-tree-footer">
      <span className="history-tree-hint">
        {t('ai.chat.history.hint', {
          current: selectedIndex < 0 ? 0 : selectedIndex + 1,
          total: rowCount,
        })}
      </span>
      {selectedRow != null && !selectedRow.canFork ? (
        <span className="history-tree-disabled-reason" role="note">
          {t('ai.chat.branch.notBoundary')}
        </span>
      ) : null}
      <div className="history-tree-actions">
        <Button variant="secondary" onClick={onClose}>
          {t('ai.chat.history.cancel')}
        </Button>
        {onForkSession ? (
          <Button
            variant="secondary"
            disabled={loading || selectedRow?.canFork !== true}
            title={selectedRow?.canFork === false
              ? t('ai.chat.branch.notBoundary')
              : t('ai.chat.branch.forkSessionHint')}
            onClick={() => selectedRow?.canFork && onForkSession(selectedRow.entry)}
          >
            <CopyPlus size={14} aria-hidden="true" />
            <span>{t('ai.chat.branch.forkSession')}</span>
          </Button>
        ) : null}
        <Button
          disabled={loading || selectedRow?.canFork !== true}
          title={selectedRow?.canFork === false
            ? t('ai.chat.branch.notBoundary')
            : t('ai.chat.branch.fromHereHint')}
          onClick={() => selectedRow?.canFork && onFork(selectedRow.entry)}
        >
          <GitBranch size={14} aria-hidden="true" />
          <span>{t('ai.chat.branch.fromHere')}</span>
        </Button>
      </div>
    </div>
  )
}
