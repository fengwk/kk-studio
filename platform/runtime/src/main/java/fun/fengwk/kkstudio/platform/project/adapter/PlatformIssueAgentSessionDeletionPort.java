package fun.fengwk.kkstudio.platform.project.adapter;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.platform.orchestration.OwnerRef;
import fun.fengwk.kkstudio.platform.orchestration.SessionDeletionOrchestrator;
import fun.fengwk.kkstudio.project.port.IssueAgentSessionDeletionPort;

import java.util.UUID;

/**
 * {@link IssueAgentSessionDeletionPort} 的 platform 宿主适配：以 Issue+Agent owner 复用共享 Harness Session
 * 深删除。
 */
@AllArgsConstructor
@Service
public class PlatformIssueAgentSessionDeletionPort implements IssueAgentSessionDeletionPort {

  private final SessionDeletionOrchestrator orchestrator;

  @Override
  public void deleteIssueAgentSessions(UUID issueId, String agentName) {
    orchestrator.deleteSessionsByOwner(new OwnerRef.IssueAgent(issueId, agentName));
  }
}
