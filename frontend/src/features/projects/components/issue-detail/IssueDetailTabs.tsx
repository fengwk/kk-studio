import { Activity, Bot, Coins, FileText, ListOrdered, Paperclip } from 'lucide-react'
import { useI18n } from '@/shared/i18n'
import { Tabs } from '@/shared/ui/controls/Tabs'

export type IssueDetailTabKey = 'spec' | 'activities' | 'runs' | 'stageBudgets' | 'agentThreads' | 'evidence'

interface IssueDetailTabsProps {
  activeTab: IssueDetailTabKey
  onChange: (tab: IssueDetailTabKey) => void
  counts: {
    activities: number
    /** 活动还有未加载的后续窗口：计数只说明已加载多少。 */
    activityHasMore: boolean
    runs: number
    stageBudgets: number
    agentThreads: number
    /** 证据计数只在服务端列表成功后才是权威值；未知用 null，不显示成 0。 */
    evidence: number | null
  }
  children: React.ReactNode
}

/**
 * Issue 详情的六个 tab（Spec、活动、Run、阶段预算、Agent 线程、证据）及其内容区。
 *
 * <p>计数直接取自当前快照长度，标签只在快照到达后渲染；证据计数在读取成功前为 null，
 * 标签因此不写数字；活动还有后续窗口时在计数后加“+”，说明标签只算已加载部分。
 */
export function IssueDetailTabs({ activeTab, onChange, counts, children }: IssueDetailTabsProps) {
  const { t } = useI18n()
  return (
    <Tabs
      className="issue-detail-tabs"
      ariaLabel={t('projects.issue.tabsAriaLabel')}
      activeId={activeTab}
      onChange={(id) => onChange(id as IssueDetailTabKey)}
      tabs={[
        {
          id: 'spec',
          label: (
            <>
              <FileText size={14} aria-hidden="true" />
              <span>{t('projects.issue.tabSpec')}</span>
            </>
          ),
        },
        {
          id: 'activities',
          label: (
            <>
              <Activity size={14} aria-hidden="true" />
              <span>
                {counts.activityHasMore
                  ? t('projects.issue.tabActivitiesMore', { count: counts.activities })
                  : t('projects.issue.tabActivities', { count: counts.activities })}
              </span>
            </>
          ),
        },
        {
          id: 'runs',
          label: (
            <>
              <ListOrdered size={14} aria-hidden="true" />
              <span>{t('projects.issue.tabRuns', { count: counts.runs })}</span>
            </>
          ),
        },
        {
          id: 'stageBudgets',
          label: (
            <>
              <Coins size={14} aria-hidden="true" />
              <span>{t('projects.issue.tabStageBudgets', { count: counts.stageBudgets })}</span>
            </>
          ),
        },
        {
          id: 'agentThreads',
          label: (
            <>
              <Bot size={14} aria-hidden="true" />
              <span>{t('projects.issue.tabAgentThreads', { count: counts.agentThreads })}</span>
            </>
          ),
        },
        {
          id: 'evidence',
          label: (
            <>
              <Paperclip size={14} aria-hidden="true" />
              <span>
                {counts.evidence == null
                  ? t('projects.issue.tabEvidenceUnknown')
                  : t('projects.issue.tabEvidence', { count: counts.evidence })}
              </span>
            </>
          ),
        },
      ]}
    >
      {children}
    </Tabs>
  )
}
