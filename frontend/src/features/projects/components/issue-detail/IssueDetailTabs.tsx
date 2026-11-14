import { Activity, Bot, Coins, FileText, ListOrdered, Paperclip } from 'lucide-react'
import { useI18n } from '@/shared/i18n'
import { Tabs } from '@/shared/ui/controls/Tabs'

export type IssueDetailTabKey = 'spec' | 'activities' | 'runs' | 'stageBudgets' | 'agentThreads' | 'evidence'

interface IssueDetailTabsProps {
  activeTab: IssueDetailTabKey
  onChange: (tab: IssueDetailTabKey) => void
  counts: {
    activities: number
    runs: number
    stageBudgets: number
    agentThreads: number
    evidence: number
  }
  children: React.ReactNode
}

/**
 * Issue 详情的六个 tab（Spec、活动、Run、阶段预算、Agent 线程、证据）及其内容区。
 *
 * <p>计数直接取自当前快照长度，标签只在快照到达后渲染。
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
              <span>{t('projects.issue.tabActivities', { count: counts.activities })}</span>
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
              <span>{t('projects.issue.tabEvidence', { count: counts.evidence })}</span>
            </>
          ),
        },
      ]}
    >
      {children}
    </Tabs>
  )
}
