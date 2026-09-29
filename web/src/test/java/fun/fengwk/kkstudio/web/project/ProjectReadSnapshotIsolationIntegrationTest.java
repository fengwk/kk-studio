package fun.fengwk.kkstudio.web.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.service.IssueService;
import fun.fengwk.kkstudio.project.service.ProjectService;
import fun.fengwk.kkstudio.share.project.IssueDetailDTO;
import fun.fengwk.kkstudio.share.project.ProjectSnapshotDTO;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 聚合读取的隔离级别实证（真实 PostgreSQL 事务）。
 *
 * <p>测试意图：{@link ProjectSnapshotAssembler} 与 {@link IssueDetailAssembler} 的整段读取必须落在同一个只读 {@code
 * REPEATABLE_READ} 事务里。判别方式是在该事务已经完成第一次读取之后，用**独立事务**（{@code REQUIRES_NEW}，因此是另一条连接并立即提交）
 * 写入一条新事实：{@code REPEATABLE_READ} 下这条提交对本次聚合不可见，只有随后的新事务才看得到。若事务注解失效而回落到 PostgreSQL 默认的 {@code
 * READ_COMMITTED}，本测试会因为聚合提前看到并发提交而失败。
 *
 * <p>这证明的是读取事务的隔离语义与注解确实经 Spring 代理生效，不代表响应组装本身具备更强保证。
 */
class ProjectReadSnapshotIsolationIntegrationTest extends WebPostgresTestSupport {

  @Autowired private ProjectSnapshotAssembler projectSnapshotAssembler;
  @Autowired private IssueDetailAssembler issueDetailAssembler;
  @Autowired private PlatformTransactionManager transactionManager;

  @MockitoSpyBean private ProjectService projectService;
  @MockitoSpyBean private IssueService issueService;

  /** 意图：Project Snapshot 读取期间并发提交的 Issue 不得出现在同一次聚合里。 */
  @Test
  void projectSnapshotIgnoresFactsCommittedAfterItsReadSnapshot() {
    Project project = projectService.createProject("RR Snapshot Project", "Desc", false);
    AtomicBoolean injected = new AtomicBoolean();
    doAnswer(
            invocation -> {
              Project resolved = (Project) invocation.callRealMethod();
              // 第一次读取 Project 时快照已经建立，此刻由独立事务提交一条新 Issue。
              if (injected.compareAndSet(false, true)) {
                commitInNewTransaction(
                    () -> issueService.createIssue(project.getId(), "Concurrent Issue", "Desc"));
              }
              return resolved;
            })
        .when(projectService)
        .getProject(any());

    ProjectSnapshotDTO snapshotTakenBeforeCommit =
        projectSnapshotAssembler.assemble(project.getId());
    assertTrue(
        snapshotTakenBeforeCommit.getIssues().isEmpty(), "REPEATABLE_READ 快照不得看到建立之后才提交的 Issue");

    ProjectSnapshotDTO snapshotTakenAfterCommit =
        projectSnapshotAssembler.assemble(project.getId());
    assertEquals(1, snapshotTakenAfterCommit.getIssues().size(), "新事务必须看到已提交事实");
    assertEquals(
        "Concurrent Issue", snapshotTakenAfterCommit.getIssues().get(0).getIssue().getTitle());
  }

  /** 意图：Issue 详情读取期间并发提交的评论不得出现在同一次聚合里。 */
  @Test
  void issueDetailIgnoresFactsCommittedAfterItsReadSnapshot() {
    Project project = projectService.createProject("RR Detail Project", "Desc", false);
    Issue issue = issueService.createIssue(project.getId(), "RR Detail Issue", "Desc");
    AtomicBoolean injected = new AtomicBoolean();
    doAnswer(
            invocation -> {
              Issue resolved = (Issue) invocation.callRealMethod();
              // 第一次读取 Issue 时快照已经建立，此刻由独立事务提交一条新评论。
              if (injected.compareAndSet(false, true)) {
                commitInNewTransaction(
                    () ->
                        issueService.appendComment(
                            issue.getId(),
                            resolved.getVersion(),
                            "rr-concurrent-comment",
                            "concurrent"));
              }
              return resolved;
            })
        .when(issueService)
        .getIssue(any());

    IssueDetailDTO snapshotTakenBeforeCommit = issueDetailAssembler.assemble(issue.getId(), 0L, 50);
    IssueDetailDTO snapshotTakenAfterCommit = issueDetailAssembler.assemble(issue.getId(), 0L, 50);

    assertEquals(
        snapshotTakenBeforeCommit.getActivities().size() + 1,
        snapshotTakenAfterCommit.getActivities().size(),
        "REPEATABLE_READ 快照不得看到建立之后才提交的评论，新事务必须看到");
  }

  /** 用独立连接与独立事务立即提交一次写入，从而把「并发提交」精确插入到外层快照事务的两次读取之间。 */
  private void commitInNewTransaction(Runnable writer) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    template.executeWithoutResult(status -> writer.run());
  }
}
