package fun.fengwk.kkstudio.core.harness.session.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.harness.session.service.HarnessSessionCommandService;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandTransactions;
import fun.fengwk.kkstudio.share.model.HarnessSessionCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionDTO;

import java.time.Instant;
import java.util.Objects;

@Service
public class HarnessSessionCommandServiceImpl implements HarnessSessionCommandService {
  private final ThreadCommandTransactions transactions;
  private final HarnessSessionDtoConverter sessionConverter;

  public HarnessSessionCommandServiceImpl(
      ThreadCommandTransactions transactions, HarnessSessionDtoConverter sessionConverter) {
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.sessionConverter = Objects.requireNonNull(sessionConverter, "sessionConverter");
  }

  @Override
  @Transactional
  public HarnessSessionDTO createSession(HarnessSessionCreateDTO request) {
    Objects.requireNonNull(request, "request");
    return sessionConverter.convert(
        transactions.createSession(request.getTitle(), Instant.now()).session());
  }
}
