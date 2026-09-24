package fun.fengwk.kkstudio.platform.project.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.project.repo.IssueHarnessInvocationRepository;
import fun.fengwk.kkstudio.platform.project.repo.impl.mapper.IssueHarnessInvocationMapper;

import java.util.UUID;

@AllArgsConstructor
@Repository
public class PostgresqlIssueHarnessInvocationRepository
    implements IssueHarnessInvocationRepository {

  private final IssueHarnessInvocationMapper issueHarnessInvocationMapper;

  @Override
  public boolean hasNonTerminalByProjectId(UUID projectId) {
    return issueHarnessInvocationMapper.hasNonTerminalByProjectId(projectId);
  }
}
