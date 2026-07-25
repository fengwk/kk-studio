package fun.fengwk.kkstudio.core.harness.session.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.harness.session.service.HarnessSessionCommandService;
import fun.fengwk.kkstudio.core.harness.tool.configuration.ToolSettingsProvider;
import fun.fengwk.kkstudio.harness.runtime.session.SessionCommandCoordinator;
import fun.fengwk.kkstudio.share.model.HarnessSessionCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionDTO;

import java.util.Objects;

/**
 * Thin Core Session command boundary: product defaults, Spring transaction and DTO mapping. Runtime
 * bootstrap orchestration lives in {@link SessionCommandCoordinator}.
 */
@Service
public class HarnessSessionCommandServiceImpl implements HarnessSessionCommandService {
  /** Dev/e2e seed installs agent_definition id=1 as the bootstrap default agent. */
  private static final long DEFAULT_AGENT_DEFINITION_ID = 1L;

  private final SessionCommandCoordinator coordinator;
  private final ToolSettingsProvider toolSettingsProvider;
  private final HarnessSessionDtoConverter sessionConverter;

  public HarnessSessionCommandServiceImpl(
      SessionCommandCoordinator coordinator,
      ToolSettingsProvider toolSettingsProvider,
      HarnessSessionDtoConverter sessionConverter) {
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    this.toolSettingsProvider =
        Objects.requireNonNull(toolSettingsProvider, "toolSettingsProvider");
    this.sessionConverter = Objects.requireNonNull(sessionConverter, "sessionConverter");
  }

  @Override
  @Transactional
  public HarnessSessionDTO createSession(HarnessSessionCreateDTO request) {
    Objects.requireNonNull(request, "request");
    return sessionConverter.convert(
        coordinator.createSession(
            request.getTitle(),
            DEFAULT_AGENT_DEFINITION_ID,
            toolSettingsProvider.get().defaultYolo()));
  }
}
