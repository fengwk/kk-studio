package fun.fengwk.kkstudio.platform.project.repo.impl;

import lombok.AllArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.project.model.IssueAgentSession;
import fun.fengwk.kkstudio.platform.project.repo.IssueAgentSessionRepository;
import fun.fengwk.kkstudio.platform.project.repo.impl.mapper.IssueAgentSessionMapper;
import fun.fengwk.kkstudio.platform.project.repo.impl.model.IssueAgentSessionDO;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@AllArgsConstructor
@Repository
public class PostgresqlIssueAgentSessionRepository implements IssueAgentSessionRepository {

  private final IssueAgentSessionMapper issueAgentSessionMapper;

  @Override
  public IssueAgentSession bindOrGet(IssueAgentSession session) {
    if (session == null) {
      return null;
    }
    if (session.getIssueId() != null && session.getAgentName() != null) {
      IssueAgentSessionDO existing =
          issueAgentSessionMapper.findByIssueIdAndAgentName(
              session.getIssueId(), session.getAgentName());
      if (existing != null) {
        return toModel(existing);
      }
    }
    if (session.getSessionId() != null) {
      IssueAgentSessionDO existing =
          issueAgentSessionMapper.findBySessionId(session.getSessionId());
      if (existing != null) {
        return toModel(existing);
      }
    }
    if (session.getThreadId() != null) {
      IssueAgentSessionDO existing = issueAgentSessionMapper.findByThreadId(session.getThreadId());
      if (existing != null) {
        return toModel(existing);
      }
    }

    try {
      IssueAgentSessionDO inserted = issueAgentSessionMapper.insert(toDO(session));
      if (inserted != null) {
        return toModel(inserted);
      }
    } catch (DuplicateKeyException ex) {
      if (session.getIssueId() != null && session.getAgentName() != null) {
        IssueAgentSessionDO existing =
            issueAgentSessionMapper.findByIssueIdAndAgentName(
                session.getIssueId(), session.getAgentName());
        if (existing != null) {
          return toModel(existing);
        }
      }
      if (session.getSessionId() != null) {
        IssueAgentSessionDO existing =
            issueAgentSessionMapper.findBySessionId(session.getSessionId());
        if (existing != null) {
          return toModel(existing);
        }
      }
      if (session.getThreadId() != null) {
        IssueAgentSessionDO existing =
            issueAgentSessionMapper.findByThreadId(session.getThreadId());
        if (existing != null) {
          return toModel(existing);
        }
      }
      if (session.getId() != null) {
        IssueAgentSessionDO existing = issueAgentSessionMapper.getById(session.getId());
        if (existing != null) {
          return toModel(existing);
        }
      }
      throw ex;
    }
    return getById(session.getId());
  }

  @Override
  public IssueAgentSession getById(UUID id) {
    return toModel(issueAgentSessionMapper.getById(id));
  }

  @Override
  public IssueAgentSession findByIssueIdAndAgentName(UUID issueId, String agentName) {
    return toModel(issueAgentSessionMapper.findByIssueIdAndAgentName(issueId, agentName));
  }

  @Override
  public IssueAgentSession findBySessionId(UUID sessionId) {
    return toModel(issueAgentSessionMapper.findBySessionId(sessionId));
  }

  @Override
  public IssueAgentSession findByThreadId(UUID threadId) {
    return toModel(issueAgentSessionMapper.findByThreadId(threadId));
  }

  @Override
  public List<IssueAgentSession> listByIssueId(UUID issueId) {
    return issueAgentSessionMapper.listByIssueId(issueId).stream()
        .map(this::toModel)
        .collect(Collectors.toList());
  }

  @Override
  public boolean deleteById(UUID id) {
    return issueAgentSessionMapper.deleteById(id) == 1;
  }

  @Override
  public int deleteByIssueId(UUID issueId) {
    return issueAgentSessionMapper.deleteByIssueId(issueId);
  }

  private IssueAgentSessionDO toDO(IssueAgentSession session) {
    if (session == null) {
      return null;
    }
    IssueAgentSessionDO target = new IssueAgentSessionDO();
    target.setId(session.getId());
    target.setIssueId(session.getIssueId());
    target.setAgentName(session.getAgentName());
    target.setSessionId(session.getSessionId());
    target.setThreadId(session.getThreadId());
    target.setCreatedAt(session.getCreatedAt());
    target.setUpdatedAt(session.getUpdatedAt());
    return target;
  }

  private IssueAgentSession toModel(IssueAgentSessionDO row) {
    if (row == null) {
      return null;
    }
    return IssueAgentSession.builder()
        .id(row.getId())
        .issueId(row.getIssueId())
        .agentName(row.getAgentName())
        .sessionId(row.getSessionId())
        .threadId(row.getThreadId())
        .createdAt(row.getCreatedAt())
        .updatedAt(row.getUpdatedAt())
        .build();
  }
}
