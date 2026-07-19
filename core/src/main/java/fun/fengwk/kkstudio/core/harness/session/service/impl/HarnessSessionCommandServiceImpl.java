package fun.fengwk.kkstudio.core.harness.session.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.harness.session.service.HarnessSessionCommandService;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionMapper;
import fun.fengwk.kkstudio.core.harness.tool.configuration.ToolSettingsProvider;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadTransactions;
import fun.fengwk.kkstudio.share.model.HarnessSessionCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionDTO;

import java.time.Instant;
import java.util.Objects;

@Service
public class HarnessSessionCommandServiceImpl implements HarnessSessionCommandService {
  private final ToolSettingsProvider toolSettingsProvider;
  private final ThreadTransactions transactions;
  private final HarnessSessionMapper sessionMapper;
  private final HarnessSessionDtoConverter sessionConverter;

  public HarnessSessionCommandServiceImpl(
      ToolSettingsProvider toolSettingsProvider,
      ThreadTransactions transactions,
      HarnessSessionMapper sessionMapper,
      HarnessSessionDtoConverter sessionConverter) {
    this.toolSettingsProvider =
        Objects.requireNonNull(toolSettingsProvider, "toolSettingsProvider");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.sessionMapper = Objects.requireNonNull(sessionMapper, "sessionMapper");
    this.sessionConverter = Objects.requireNonNull(sessionConverter, "sessionConverter");
  }

  @Override
  @Transactional
  public HarnessSessionDTO createSession(HarnessSessionCreateDTO request) {
    Objects.requireNonNull(request, "request");
    boolean yoloEnabled =
        request.getYoloEnabled() == null
            ? toolSettingsProvider.get().defaultYolo()
            : request.getYoloEnabled();
    long sessionId =
        transactions.createSession(request.getTitle(), yoloEnabled, Instant.now()).sessionId();
    return sessionConverter.convert(sessionMapper.find(sessionId));
  }
}
