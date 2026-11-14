import type { PendingIssueAction } from '../../pending-action-sidecar'
import type { ProjectsApi } from '../../projects-api'

/**
 * 按冻结的侧车记录重放一次请求。
 *
 * <p>版本、request 幂等键与 payload 全部取自侧车快照：重试不得读取刷新后的 Issue，
 * 否则「精确重试」会退化成用新版本重发旧意图。
 */
export async function sendRetriedAction(
  api: ProjectsApi,
  issueId: string,
  { kind, requestKey, expectedVersion, payload }: PendingIssueAction,
): Promise<void> {
  switch (kind) {
    case 'TRANSITION':
      await api.transitionIssue(issueId, {
        expectedVersion,
        requestKey,
        toState: payload.toState,
      })
      return
    case 'RECOVER':
      await api.recoverIssue(issueId, { expectedVersion, requestKey })
      return
    case 'REOPEN':
      await api.reopenIssue(issueId, { expectedVersion, requestKey })
      return
    case 'RESUME':
      await api.resumeIssue(issueId, { expectedVersion, requestKey })
      return
    case 'BLOCK':
      await api.blockIssue(issueId, {
        expectedVersion,
        requestKey,
        reason: payload.reason,
      })
      return
    case 'STOP':
      await api.stopIssue(issueId, {
        expectedVersion,
        requestKey,
        detail: payload.detail,
      })
      return
    case 'RESET_BUDGET':
      await api.resetStageBudget(issueId, {
        expectedVersion,
        requestKey,
        state: payload.state,
        maxRuns: payload.maxRuns,
      })
      return
    case 'ACTIVITY':
      await api.appendIssueActivity(issueId, {
        expectedVersion,
        requestKey,
        kind: payload.kind,
        body: payload.body,
      })
  }
}
