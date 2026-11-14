package fun.fengwk.kkstudio.project.reconcile;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.api.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.api.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.api.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.spi.AcceptancePreflight;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueActivity;
import fun.fengwk.kkstudio.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.repo.IssueActivityRepository;
import fun.fengwk.kkstudio.project.repo.IssueRunRepository;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * 面向活动 Run 的定向指示投递：按活动事实位置取下一个可投递 INSTRUCTION，投递给明确的当前 Run，并在版本 CAS 下推进游标。
 *
 * <p>普通评论与系统事件不卡游标：扫描越过它们直到活动流尾或遇到下一条 INSTRUCTION。是否已被任何 Run 观察只看事实位置，不要求指示恰好绑定当前 Run——上一个 Run 因匹配
 * root Join 安全收尾时未投递的业务指示会保留给下一个 Run。
 */
@Component
@AllArgsConstructor
final class IssueReconcileInstructions {

  /** 单次 reconcile 检视的 Activity 窗口上限：只读有界窗口，窗口未到流尾时先推进已检视游标再立即续扫。 */
  static final int ACTIVITY_SCAN_LIMIT = 200;

  private final IssueActivityRepository issueActivityRepository;
  private final IssueRunRepository issueRunRepository;
  private final IssueReconcileHarness harness;

  /** 投递下一条待投递指示；没有可投递指示时返回 false（扫描到的位置仍会推进游标）。 */
  boolean deliverNext(Issue issue, IssueRun run) {
    IssueActivity deliverable = nextDeliverable(run);
    if (deliverable == null) {
      return false;
    }
    AgentMessage message =
        new AgentMessage(
            AgentMessageRole.USER, List.of(new TextMessageContent(deliverable.getBody())));
    UUID idempotencyKey =
        UUID.nameUUIDFromBytes(
            ("issue-instruction:" + issue.getId() + ":" + deliverable.getSequence())
                .getBytes(StandardCharsets.UTF_8));
    harness
        .require()
        .acceptCommands(
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.Thread(run.getThreadId()),
                List.of(
                    new NewThreadCommand(new UserMessageCommandPayload(message), idempotencyKey))),
            AcceptancePreflight.IDENTITY);
    advance(run, deliverable.getSequence());
    return true;
  }

  private IssueActivity nextDeliverable(IssueRun run) {
    long cursor = run.getObservedActivitySequence();
    long maxSeen = cursor;
    while (true) {
      List<IssueActivity> window =
          issueActivityRepository.listPage(run.getIssueId(), cursor, ACTIVITY_SCAN_LIMIT);
      if (window.isEmpty()) {
        break;
      }
      for (IssueActivity activity : window) {
        maxSeen = Math.max(maxSeen, activity.getSequence());
        if (activity.getKind() == IssueActivityKind.INSTRUCTION) {
          if (activity.getSequence() > run.getObservedActivitySequence() + 1) {
            advance(run, activity.getSequence() - 1);
          }
          return activity;
        }
      }
      cursor = maxSeen;
      if (window.size() < ACTIVITY_SCAN_LIMIT) {
        break;
      }
    }
    if (maxSeen > run.getObservedActivitySequence()) {
      advance(run, maxSeen);
    }
    return null;
  }

  private void advance(IssueRun run, long sequence) {
    if (sequence <= run.getObservedActivitySequence()) {
      return;
    }
    long expectedVersion = run.getVersion();
    run.setObservedActivitySequence(sequence);
    if (!issueRunRepository.updateById(run, expectedVersion)) {
      throw new IllegalStateException("failed to advance run activity cursor");
    }
    run.setVersion(expectedVersion + 1);
  }
}
