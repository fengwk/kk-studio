package fun.fengwk.kkstudio.project.reconcile;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.ResourceMessageContent;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.platform.EvidenceBlobs;
import fun.fengwk.kkstudio.project.platform.IssueThreadView;
import fun.fengwk.kkstudio.project.service.IssueEvidenceService;

import java.util.List;
import java.util.UUID;

class IssueReconcileEvidenceTest {
  private final IssueEvidenceService service = mock(IssueEvidenceService.class);
  private final EvidenceBlobs blobs = mock(EvidenceBlobs.class);
  private final IssueReconcileEvidence evidence = new IssueReconcileEvidence(service, blobs);
  private final Issue issue = mock(Issue.class);
  private final IssueRun run = mock(IssueRun.class);
  private final IssueAgentThread binding = mock(IssueAgentThread.class);
  private final IssueThreadView view = mock(IssueThreadView.class);
  private final EntryPath path = mock(EntryPath.class);
  private final Entry start = mock(Entry.class);
  private final Entry answer = mock(Entry.class);
  private final UUID startId = UUID.randomUUID();
  private final UUID answerId = UUID.randomUUID();
  private final UUID sessionId = UUID.randomUUID();
  private final UUID blobId = UUID.randomUUID();

  private void setupAnswer(AgentMessageRole role, ResourceMessageContent resource) {
    when(view.entryPath()).thenReturn(path);
    when(path.entries()).thenReturn(List.of(start, answer));
    when(start.id()).thenReturn(startId);
    when(answer.id()).thenReturn(answerId);
    when(run.getStartEntryId()).thenReturn(startId);
    when(view.headEntryId()).thenReturn(answerId);
    when(view.sessionId()).thenReturn(sessionId);
    MessagePayload payload = mock(MessagePayload.class);
    AgentMessage message = mock(AgentMessage.class);
    when(answer.payload()).thenReturn(payload);
    when(payload.message()).thenReturn(message);
    when(message.role()).thenReturn(role);
    when(message.contents()).thenReturn(List.of(resource));
  }

  /** 只发布受 Session 持有且活跃的资源，顺序不能改变。 */
  @Test
  void publishesAfterOrderedChecks() {
    setupAnswer(AgentMessageRole.ASSISTANT, ResourceMessageContent.media(blobId, "a.txt"));
    UUID issueId = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    when(issue.getId()).thenReturn(issueId);
    when(run.getId()).thenReturn(runId);
    when(binding.agentName()).thenReturn("agent");
    when(blobs.isSessionBlobRef(sessionId, blobId)).thenReturn(true);
    when(blobs.isBlobActive(blobId)).thenReturn(true);
    evidence.publishEvidence(issue, run, binding, view, answerId);
    InOrder order = inOrder(blobs, service);
    order.verify(blobs).isSessionBlobRef(sessionId, blobId);
    order.verify(blobs).isBlobActive(blobId);
    order.verify(service).publishBlob(issueId, "agent", runId, blobId, "a.txt");
  }

  /** 窗口、消息类型及角色不合法时不得触及发布端口。 */
  @Test
  void rejectsInvalidAnswer() {
    evidence.publishEvidence(issue, run, binding, view, null);
    evidence.publishEvidence(issue, run, null, view, answerId);
    when(view.entryPath()).thenReturn(path);
    when(path.entries()).thenReturn(List.of(start, answer));
    when(start.id()).thenReturn(startId);
    when(answer.id()).thenReturn(answerId);
    when(run.getStartEntryId()).thenReturn(startId);
    when(view.headEntryId()).thenReturn(answerId);
    evidence.publishEvidence(issue, run, binding, view, startId);
    evidence.publishEvidence(issue, run, binding, view, UUID.randomUUID());
    when(run.getStartEntryId()).thenReturn(UUID.randomUUID());
    evidence.publishEvidence(issue, run, binding, view, answerId);
    when(run.getStartEntryId()).thenReturn(startId);
    when(view.headEntryId()).thenReturn(startId);
    evidence.publishEvidence(issue, run, binding, view, answerId);
    when(view.headEntryId()).thenReturn(answerId);
    evidence.publishEvidence(issue, run, binding, view, answerId);
    setupAnswer(AgentMessageRole.USER, ResourceMessageContent.media(blobId, "a.txt"));
    evidence.publishEvidence(issue, run, binding, view, answerId);
    verifyNoInteractions(blobs, service);
  }

  /** 非法名称、未持有和非活跃资源在调用事务参与方之前被过滤。 */
  @Test
  void filtersResourcesBeforePublication() {
    ResourceMessageContent resource = mock(ResourceMessageContent.class);
    setupAnswer(AgentMessageRole.ASSISTANT, resource);
    evidence.publishEvidence(issue, run, binding, view, answerId);
    when(resource.blobId()).thenReturn(blobId);
    evidence.publishEvidence(issue, run, binding, view, answerId);
    when(blobs.isSessionBlobRef(sessionId, blobId)).thenReturn(true);
    for (String agent : List.of("", "bad/name", " agent", "a".repeat(65))) {
      when(binding.agentName()).thenReturn(agent);
      when(resource.name()).thenReturn("name");
      evidence.publishEvidence(issue, run, binding, view, answerId);
    }
    when(binding.agentName()).thenReturn(null);
    evidence.publishEvidence(issue, run, binding, view, answerId);
    when(binding.agentName()).thenReturn("agent");
    for (String name : List.of(" ", " name", "n".repeat(513))) {
      when(resource.name()).thenReturn(name);
      evidence.publishEvidence(issue, run, binding, view, answerId);
    }
    when(resource.name()).thenReturn(null);
    evidence.publishEvidence(issue, run, binding, view, answerId);
    verify(blobs, never()).isBlobActive(any());
    when(resource.name()).thenReturn("name");
    evidence.publishEvidence(issue, run, binding, view, answerId);
    verify(blobs).isBlobActive(blobId);
    verify(service, never()).publishBlob(any(), any(), any(), any(), any());
  }

  /** 发布异常必须向外传播，由外层事务回滚。 */
  @Test
  void propagatesPublishFailure() {
    setupAnswer(AgentMessageRole.ASSISTANT, ResourceMessageContent.media(blobId, "a.txt"));
    when(binding.agentName()).thenReturn("agent");
    when(blobs.isSessionBlobRef(sessionId, blobId)).thenReturn(true);
    when(blobs.isBlobActive(blobId)).thenReturn(true);
    doThrow(new IllegalStateException("failure"))
        .when(service)
        .publishBlob(any(), any(), any(), any(), any());
    when(issue.getId()).thenReturn(UUID.randomUUID());
    when(run.getId()).thenReturn(UUID.randomUUID());
    assertThrows(
        IllegalStateException.class,
        () -> evidence.publishEvidence(issue, run, binding, view, answerId));
  }
}
