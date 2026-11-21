import type { ReactNode } from 'react'
import { SearchField } from '@/shared/ui/controls/SearchField'
import './list-page-header.css'

export interface ListPageHeaderSearch {
  value: string
  onChange: (value: string) => void
  placeholder?: string
  'aria-label'?: string
}

export interface ListPageHeaderProps {
  /** 页面唯一主标题；各语言文案由调用方提供。 */
  title: string
  /** 标题元素 id，供页面的 aria-labelledby 引用。 */
  titleId?: string
  /** 标题旁的事实计数（如“3 项待处理”），由调用方用 i18n 生成。 */
  count?: string
  /** 页级搜索；没有可搜索的数据时省略。 */
  search?: ListPageHeaderSearch
  /** 标题右侧的页级动作，排在搜索之后。 */
  actions?: ReactNode
}

/**
 * 资源列表页页头：左侧唯一主标题（可带计数），右侧搜索与页级动作。
 *
 * <p>项目、画布库与待处理页共用同一字号、间距和对齐，页面容器与数据仍由各页负责：
 * 这里只排布标题与控件，不读取查询、不持有交互状态。
 */
export function ListPageHeader({
  title,
  titleId,
  count,
  search,
  actions,
}: ListPageHeaderProps) {
  return (
    <header className="list-page-header">
      <div className="list-page-header-heading">
        <h1 className="list-page-header-title" id={titleId}>{title}</h1>
        {count ? <span className="list-page-header-count">{count}</span> : null}
      </div>
      {search || actions ? (
        <div className="list-page-header-actions">
          {search ? (
            <SearchField
              value={search.value}
              onChange={search.onChange}
              placeholder={search.placeholder}
              aria-label={search['aria-label']}
            />
          ) : null}
          {actions}
        </div>
      ) : null}
    </header>
  )
}
