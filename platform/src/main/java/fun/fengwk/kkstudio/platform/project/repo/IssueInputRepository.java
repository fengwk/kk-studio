package fun.fengwk.kkstudio.platform.project.repo;

import fun.fengwk.kkstudio.platform.project.model.IssueInput;
import fun.fengwk.kkstudio.platform.project.model.IssueInputKind;

import java.util.List;
import java.util.UUID;

public interface IssueInputRepository {

  boolean append(IssueInput input);

  IssueInput findByIdempotencyKey(UUID issueId, String idempotencyKey);

  List<IssueInput> listByIssueId(UUID issueId);

  List<IssueInput> listAfterSequence(UUID issueId, long afterSequence);

  IssueInput findFirstAfterSequence(UUID issueId, long afterSequence);

  IssueInput findFirstByKindAfterSequence(UUID issueId, IssueInputKind kind, long afterSequence);
}
