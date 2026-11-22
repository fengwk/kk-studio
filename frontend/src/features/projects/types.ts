/** 项目失效事件只携带可选的项目身份。 */
export interface ProjectsChangedEventPayload {
  projectId?: string
}

/** 详情打开意图：看板上的动作决定详情打开后直接展开哪个表单。 */
export type IssueDetailIntent = 'block'
