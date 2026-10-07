package fun.fengwk.kkstudio.project.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.project.model.IssueWork;
import fun.fengwk.kkstudio.project.repo.IssueWorkRepository;
import fun.fengwk.kkstudio.project.repo.impl.mapper.IssueWorkCompletion;
import fun.fengwk.kkstudio.project.repo.impl.mapper.IssueWorkMapper;
import fun.fengwk.kkstudio.project.repo.impl.model.IssueWorkDO;

import java.time.Duration;
import java.util.UUID;

@AllArgsConstructor
@Repository
public class PostgresqlIssueWorkRepository implements IssueWorkRepository {

  private final IssueWorkMapper mapper;
  private final PostgresqlIssueWorkNotifier notifier;

  @Override
  public IssueWork getById(UUID issueId) {
    return toModel(mapper.getById(issueId));
  }

  @Override
  public IssueWork lockById(UUID issueId) {
    return toModel(mapper.lockById(issueId));
  }

  @Override
  public IssueWork requestWork(UUID issueId, Duration delay) {
    IssueWorkDO row = mapper.upsertRequest(issueId, delay);
    if (row != null) {
      notifier.notifyIfDue(issueId);
    }
    return toModel(row);
  }

  @Override
  public IssueWork claimNext(String leaseToken, Duration leaseDuration) {
    return toModel(mapper.claimNext(leaseToken, leaseDuration));
  }

  @Override
  public boolean renewLease(UUID issueId, String leaseToken, Duration leaseDuration) {
    return mapper.renewLease(issueId, leaseToken, leaseDuration) == 1;
  }

  @Override
  public boolean completeWork(UUID issueId, String leaseToken, long claimedWakeVersion) {
    IssueWorkCompletion completion = mapper.completeWork(issueId, leaseToken, claimedWakeVersion);
    if (completion == IssueWorkCompletion.RELEASED) {
      // released 是真实写入：新 wake 已释放租约并把 due 提前到当前时刻，必须提示后续调度。
      notifier.notifyIfDue(issueId);
    }
    return completion == IssueWorkCompletion.DELETED;
  }

  @Override
  public boolean rescheduleWork(
      UUID issueId, String leaseToken, long claimedWakeVersion, Duration delay) {
    boolean written = mapper.rescheduleWork(issueId, leaseToken, claimedWakeVersion, delay) == 1;
    if (written) {
      notifier.notifyIfDue(issueId);
    }
    return written;
  }

  @Override
  public int deleteByIssueId(UUID issueId) {
    return mapper.deleteByIssueId(issueId);
  }

  private static IssueWork toModel(IssueWorkDO row) {
    if (row == null) {
      return null;
    }
    return IssueWork.builder()
        .issueId(row.getIssueId())
        .wakeVersion(row.getWakeVersion() != null ? row.getWakeVersion() : 0L)
        .dueAt(row.getDueAt())
        .leaseToken(row.getLeaseToken())
        .leaseUntil(row.getLeaseUntil())
        .createdAt(row.getCreatedAt())
        .updatedAt(row.getUpdatedAt())
        .build();
  }
}
