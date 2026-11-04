package fun.fengwk.kkstudio.project.reconcile;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.platform.EvidenceBlobs;
import fun.fengwk.kkstudio.project.platform.IssueThreadView;
import fun.fengwk.kkstudio.project.service.IssueEvidenceService;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 在调谐事务中发布最终答复引用的证据。 */
@Component
class IssueReconcileEvidence {
  private final IssueEvidenceService issueEvidenceService;
  private final EvidenceBlobs evidenceBlobPort;

  IssueReconcileEvidence(
      IssueEvidenceService issueEvidenceService, EvidenceBlobs evidenceBlobPort) {
    this.issueEvidenceService =
        Objects.requireNonNull(issueEvidenceService, "issueEvidenceService");
    this.evidenceBlobPort = Objects.requireNonNull(evidenceBlobPort, "evidenceBlobPort");
  }

  /**
   * 只发布最终 assistant 答复明确引用、且来源 Run Session 当前持有的 Resource。
   *
   * <p>中间消息、工具结果与用户附件保持私有。{@code finalAnswerEntryId} 必须落在本次 Run 的可见区间内。
   */
  void publishEvidence(
      Issue issue,
      IssueRun run,
      IssueAgentThread binding,
      IssueThreadView view,
      UUID finalAnswerEntryId) {
    if (finalAnswerEntryId == null || binding == null) {
      return;
    }
    EntryPath path = view.entryPath();
    List<Entry> entries = path.entries();
    List<UUID> entryIds = entries.stream().map(Entry::id).toList();
    int start = entryIds.indexOf(run.getStartEntryId());
    int end = entryIds.indexOf(view.headEntryId());
    int answer = entryIds.indexOf(finalAnswerEntryId);
    if (start < 0 || answer <= start || end < answer) {
      return;
    }
    Entry entry = entries.get(answer);
    if (!(entry.payload() instanceof MessagePayload msg)) {
      return;
    }
    if (msg.message().role() != AgentMessageRole.ASSISTANT) {
      return;
    }
    UUID sessionId = view.sessionId();
    for (AgentMessageContent content : msg.message().contents()) {
      if (content instanceof ResourceMessageContent res
          && isPublishableResource(res, binding, sessionId)) {
        issueEvidenceService.publishBlob(
            issue.getId(), binding.agentName(), run.getId(), res.blobId(), res.name());
      }
    }
  }

  /**
   * 确定性预校验 Resource 是否属于可发布的合法受管 Blob：非空、显示名合法、Agent 名合法、且 Blob 存在且为 ACTIVE 状态。
   *
   * <p>在调用 `@Transactional` 参与方 {@code publishBlob} 之前完成所有前置判定，避免异常逃逸导致共享事务被标记为 rollback-only。
   */
  private boolean isPublishableResource(
      ResourceMessageContent res, IssueAgentThread binding, UUID sessionId) {
    if (res == null || res.blobId() == null || sessionId == null) {
      return false;
    }
    if (!evidenceBlobPort.isSessionBlobRef(sessionId, res.blobId())) {
      return false;
    }
    if (binding == null || binding.agentName() == null || binding.agentName().isBlank()) {
      return false;
    }
    String name = res.name();
    if (name == null
        || name.isBlank()
        || !name.equals(name.strip())
        || name.codePointCount(0, name.length()) > 512) {
      return false;
    }
    String agentName = binding.agentName();
    if (agentName.indexOf('/') >= 0
        || !agentName.equals(agentName.strip())
        || agentName.codePointCount(0, agentName.length()) > 64) {
      return false;
    }
    return evidenceBlobPort.isBlobActive(res.blobId());
  }
}
