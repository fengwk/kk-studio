package fun.fengwk.kkstudio.core.harness.thread.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fun.fengwk.kkstudio.core.harness.session.support.HarnessIds;
import fun.fengwk.kkstudio.core.harness.thread.service.HarnessThreadCommandService;
import fun.fengwk.kkstudio.core.harness.tool.configuration.ToolSettingsProvider;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.runtime.port.ActivationNotifier;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandCoordinator;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandCoordinator.EnqueueResult;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandCoordinator.StopResult;
import fun.fengwk.kkstudio.share.model.HarnessThreadAgentSetDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadCustomMessageCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadInputDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadMessageCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadModelSetDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadStopResultDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadYoloSetDTO;

import java.util.Objects;
import java.util.Optional;

/**
 * Thin Core Thread command boundary: decimal/DTO parse, product defaults, Spring transaction and
 * after-commit activation. Runtime orchestration lives in {@link ThreadCommandCoordinator}.
 */
@Slf4j
@Service
public class HarnessThreadCommandServiceImpl implements HarnessThreadCommandService {

  private final ThreadCommandCoordinator coordinator;
  private final ToolSettingsProvider toolSettingsProvider;
  private final ActivationNotifier activationNotifier;
  private final HarnessThreadDtoConverter converter;

  public HarnessThreadCommandServiceImpl(
      ThreadCommandCoordinator coordinator,
      ToolSettingsProvider toolSettingsProvider,
      ActivationNotifier activationNotifier,
      HarnessThreadDtoConverter converter) {
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    this.toolSettingsProvider =
        Objects.requireNonNull(toolSettingsProvider, "toolSettingsProvider");
    this.activationNotifier = Objects.requireNonNull(activationNotifier, "activationNotifier");
    this.converter = Objects.requireNonNull(converter, "converter");
  }

  @Override
  @Transactional
  public HarnessThreadDTO createThread(String sessionId, HarnessThreadCreateDTO createDTO) {
    Objects.requireNonNull(createDTO, "createDTO");
    long parsedSessionId = HarnessIds.parsePositive(sessionId, "sessionId");
    long fromEntryId = HarnessIds.parsePositive(createDTO.getFromEntryId(), "fromEntryId");
    return converter.convert(coordinator.createBranch(parsedSessionId, fromEntryId));
  }

  @Override
  @Transactional
  public HarnessThreadInputDTO submitUserMessage(
      String threadId, HarnessThreadMessageCreateDTO createDTO) {
    Objects.requireNonNull(createDTO, "createDTO");
    long id = HarnessIds.parsePositive(threadId, "threadId");
    return convertAndNotify(
        coordinator.submitUserMessage(id, createDTO.getContent(), createDTO.getClientMessageId()));
  }

  @Override
  @Transactional
  public HarnessThreadInputDTO submitCustomMessage(
      String threadId, HarnessThreadCustomMessageCreateDTO createDTO) {
    Objects.requireNonNull(createDTO, "createDTO");
    long id = HarnessIds.parsePositive(threadId, "threadId");
    return convertAndNotify(
        coordinator.submitCustomMessage(
            id, createDTO.getRole(), createDTO.getContent(), createDTO.getClientMessageId()));
  }

  @Override
  @Transactional
  public HarnessThreadInputDTO queueYolo(String threadId, HarnessThreadYoloSetDTO request) {
    Objects.requireNonNull(request, "request");
    long id = HarnessIds.parsePositive(threadId, "threadId");
    Optional<EnqueueResult> existing =
        coordinator.findExistingInput(id, request.getClientMessageId());
    if (existing.isPresent()) {
      return convertAndNotify(existing.get());
    }
    if (request.getYoloEnabled() == null) {
      throw new IllegalArgumentException("yoloEnabled must not be null");
    }
    return convertAndNotify(
        coordinator.queueYolo(
            id, Boolean.TRUE.equals(request.getYoloEnabled()), request.getClientMessageId()));
  }

  @Override
  @Transactional
  public HarnessThreadInputDTO queueAgent(String threadId, HarnessThreadAgentSetDTO request) {
    Objects.requireNonNull(request, "request");
    long id = HarnessIds.parsePositive(threadId, "threadId");
    // Short-circuit before parsing live definition ids so retries never re-validate deleted/changed
    // resources.
    Optional<EnqueueResult> existing =
        coordinator.findExistingInput(id, request.getClientMessageId());
    if (existing.isPresent()) {
      return convertAndNotify(existing.get());
    }
    long definitionId =
        HarnessIds.parsePositive(request.getAgentDefinitionId(), "agentDefinitionId");
    return convertAndNotify(
        coordinator.queueAgent(
            id,
            definitionId,
            toolSettingsProvider.get().defaultYolo(),
            request.getClientMessageId()));
  }

  @Override
  @Transactional
  public HarnessThreadInputDTO queueModel(String threadId, HarnessThreadModelSetDTO request) {
    Objects.requireNonNull(request, "request");
    long id = HarnessIds.parsePositive(threadId, "threadId");
    Optional<EnqueueResult> existing =
        coordinator.findExistingInput(id, request.getClientMessageId());
    if (existing.isPresent()) {
      return convertAndNotify(existing.get());
    }
    long modelId = HarnessIds.parsePositive(request.getModelId(), "modelId");
    return convertAndNotify(
        coordinator.queueModel(id, modelId, request.getVariant(), request.getClientMessageId()));
  }

  @Override
  @Transactional
  public HarnessThreadStopResultDTO stop(String threadId) {
    long id = HarnessIds.parsePositive(threadId, "threadId");
    StopResult result = coordinator.stop(id);
    afterCommitNotify(result.target());
    HarnessThreadStopResultDTO dto = new HarnessThreadStopResultDTO();
    dto.setExecutionEpoch(result.executionEpoch());
    dto.setCancelledInputs(result.cancelledInputs().stream().map(converter::convert).toList());
    return dto;
  }

  private HarnessThreadInputDTO convertAndNotify(EnqueueResult result) {
    afterCommitNotify(result.target());
    return converter.convert(result.input());
  }

  private void afterCommitNotify(ExecutionTarget target) {
    Runnable notify =
        () -> {
          try {
            activationNotifier.notifyAfterCommit(target);
          } catch (RuntimeException error) {
            log.warn("best-effort activation notification failed for {}", target, error);
          }
        };
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      notify.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            notify.run();
          }
        });
  }
}
