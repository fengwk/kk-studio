package fun.fengwk.kkstudio.core.harness.session.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.harness.session.service.HarnessSessionCommandService;
import fun.fengwk.kkstudio.core.harness.thread.command.RuntimeConfigSnapshotResolver;
import fun.fengwk.kkstudio.core.harness.tool.configuration.ToolSettingsProvider;
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSnapshot;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandTransactions;
import fun.fengwk.kkstudio.share.model.HarnessSessionCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionDTO;

import java.time.Instant;
import java.util.Objects;

@Service
public class HarnessSessionCommandServiceImpl implements HarnessSessionCommandService {
  /** Dev/e2e seed installs agent_definition id=1 as the bootstrap default agent. */
  private static final long DEFAULT_AGENT_DEFINITION_ID = 1L;

  private final ThreadCommandTransactions transactions;
  private final RuntimeConfigSnapshotResolver snapshotResolver;
  private final ToolSettingsProvider toolSettingsProvider;
  private final HarnessSessionDtoConverter sessionConverter;

  public HarnessSessionCommandServiceImpl(
      ThreadCommandTransactions transactions,
      RuntimeConfigSnapshotResolver snapshotResolver,
      ToolSettingsProvider toolSettingsProvider,
      HarnessSessionDtoConverter sessionConverter) {
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.snapshotResolver = Objects.requireNonNull(snapshotResolver, "snapshotResolver");
    this.toolSettingsProvider =
        Objects.requireNonNull(toolSettingsProvider, "toolSettingsProvider");
    this.sessionConverter = Objects.requireNonNull(sessionConverter, "sessionConverter");
  }

  @Override
  @Transactional
  public HarnessSessionDTO createSession(HarnessSessionCreateDTO request) {
    Objects.requireNonNull(request, "request");
    boolean yolo = toolSettingsProvider.get().defaultYolo();
    RuntimeConfigSnapshot bootstrap =
        snapshotResolver.resolveAgent(DEFAULT_AGENT_DEFINITION_ID, yolo);
    return sessionConverter.convert(
        transactions.createSession(request.getTitle(), bootstrap, Instant.now()).session());
  }
}
