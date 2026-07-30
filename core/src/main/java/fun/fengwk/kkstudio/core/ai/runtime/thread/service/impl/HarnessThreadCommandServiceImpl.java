package fun.fengwk.kkstudio.core.ai.runtime.thread.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.ai.runtime.session.service.impl.HarnessSessionDtoConverter;
import fun.fengwk.kkstudio.core.ai.runtime.session.support.HarnessIds;
import fun.fengwk.kkstudio.core.ai.runtime.thread.service.HarnessThreadCommandService;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettingsProvider;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandCoordinator;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandCoordinator.EnqueueResult;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandCoordinator.StopResult;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadAgentSetDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadBootstrapDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadBootstrapResultDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCustomMessageCreateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadHeadUpdateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadInputDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadMessageCreateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadModelSetDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadStopDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadStopResultDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadYoloSetDTO;

import java.util.Objects;
import java.util.Optional;

/** Thin Core Thread command boundary: decimal/DTO parse and product defaults. */
@Service
public class HarnessThreadCommandServiceImpl implements HarnessThreadCommandService {

  private final ThreadCommandCoordinator coordinator;
  private final ToolSettingsProvider toolSettingsProvider;
  private final HarnessThreadDtoConverter converter;
  private final HarnessSessionDtoConverter sessionConverter;

  public HarnessThreadCommandServiceImpl(
      ThreadCommandCoordinator coordinator,
      ToolSettingsProvider toolSettingsProvider,
      HarnessThreadDtoConverter converter,
      HarnessSessionDtoConverter sessionConverter) {
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    this.toolSettingsProvider =
        Objects.requireNonNull(toolSettingsProvider, "toolSettingsProvider");
    this.converter = Objects.requireNonNull(converter, "converter");
    this.sessionConverter = Objects.requireNonNull(sessionConverter, "sessionConverter");
  }

  @Override
  @Transactional
  public HarnessThreadDTO createThread() {
    return converter.convert(coordinator.createThread());
  }

  @Override
  @Transactional
  public HarnessThreadBootstrapResultDTO bootstrapThread(
      String threadId, HarnessThreadBootstrapDTO dto) {
    Objects.requireNonNull(dto, "dto");
    long id = HarnessIds.parsePositive(threadId, "threadId");
    long definitionId = HarnessIds.parsePositive(dto.getAgentDefinitionId(), "agentDefinitionId");
    if (dto.getYoloEnabled() == null) {
      throw new IllegalArgumentException("yoloEnabled must not be null");
    }
    ThreadCommandCoordinator.BootstrapResult result =
        coordinator.bootstrapThread(
            id,
            requireExpectedEpoch(dto.getExpectedExecutionEpoch()),
            dto.getTitle(),
            definitionId,
            Boolean.TRUE.equals(dto.getYoloEnabled()));
    HarnessThreadBootstrapResultDTO response = new HarnessThreadBootstrapResultDTO();
    response.setSession(sessionConverter.convert(result.session()));
    response.setThread(converter.convert(result.thread()));
    return response;
  }

  @Override
  @Transactional
  public HarnessThreadDTO updateHead(String threadId, HarnessThreadHeadUpdateDTO dto) {
    Objects.requireNonNull(dto, "dto");
    long id = HarnessIds.parsePositive(threadId, "threadId");
    Long headEntryId =
        dto.getHeadEntryId() == null
            ? null
            : HarnessIds.parsePositive(dto.getHeadEntryId(), "headEntryId");
    return converter.convert(
        coordinator.updateHead(
            id, requireExpectedEpoch(dto.getExpectedExecutionEpoch()), headEntryId));
  }

  private static long requireExpectedEpoch(Long value) {
    if (value == null || value < 0) {
      throw new IllegalArgumentException("expectedExecutionEpoch must not be negative");
    }
    return value;
  }

  @Override
  @Transactional
  public HarnessThreadInputDTO submitUserMessage(
      String threadId, HarnessThreadMessageCreateDTO createDTO) {
    Objects.requireNonNull(createDTO, "createDTO");
    long id = HarnessIds.parsePositive(threadId, "threadId");
    return converter.convert(
        coordinator
            .submitUserMessage(
                id,
                createDTO.getContent(),
                createDTO.getClientMessageId(),
                requireExpectedEpoch(createDTO.getExpectedExecutionEpoch()))
            .input());
  }

  @Override
  @Transactional
  public HarnessThreadInputDTO submitCustomMessage(
      String threadId, HarnessThreadCustomMessageCreateDTO createDTO) {
    Objects.requireNonNull(createDTO, "createDTO");
    long id = HarnessIds.parsePositive(threadId, "threadId");
    return converter.convert(
        coordinator
            .submitCustomMessage(
                id,
                createDTO.getRole(),
                createDTO.getContent(),
                createDTO.getClientMessageId(),
                requireExpectedEpoch(createDTO.getExpectedExecutionEpoch()))
            .input());
  }

  @Override
  @Transactional
  public HarnessThreadInputDTO queueYolo(String threadId, HarnessThreadYoloSetDTO request) {
    Objects.requireNonNull(request, "request");
    long id = HarnessIds.parsePositive(threadId, "threadId");
    Optional<EnqueueResult> existing =
        coordinator.findExistingInput(id, request.getClientMessageId());
    if (existing.isPresent()) {
      return converter.convert(existing.get().input());
    }
    if (request.getYoloEnabled() == null) {
      throw new IllegalArgumentException("yoloEnabled must not be null");
    }
    return converter.convert(
        coordinator
            .queueYolo(
                id,
                Boolean.TRUE.equals(request.getYoloEnabled()),
                request.getClientMessageId(),
                requireExpectedEpoch(request.getExpectedExecutionEpoch()))
            .input());
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
      return converter.convert(existing.get().input());
    }
    long definitionId =
        HarnessIds.parsePositive(request.getAgentDefinitionId(), "agentDefinitionId");
    return converter.convert(
        coordinator
            .queueAgent(
                id,
                definitionId,
                toolSettingsProvider.get().defaultYolo(),
                request.getClientMessageId(),
                requireExpectedEpoch(request.getExpectedExecutionEpoch()))
            .input());
  }

  @Override
  @Transactional
  public HarnessThreadInputDTO queueModel(String threadId, HarnessThreadModelSetDTO request) {
    Objects.requireNonNull(request, "request");
    long id = HarnessIds.parsePositive(threadId, "threadId");
    Optional<EnqueueResult> existing =
        coordinator.findExistingInput(id, request.getClientMessageId());
    if (existing.isPresent()) {
      return converter.convert(existing.get().input());
    }
    long modelId = HarnessIds.parsePositive(request.getModelId(), "modelId");
    return converter.convert(
        coordinator
            .queueModel(
                id,
                modelId,
                request.getVariant(),
                request.getClientMessageId(),
                requireExpectedEpoch(request.getExpectedExecutionEpoch()))
            .input());
  }

  @Override
  @Transactional
  public HarnessThreadStopResultDTO stop(String threadId, HarnessThreadStopDTO request) {
    Objects.requireNonNull(request, "request");
    long id = HarnessIds.parsePositive(threadId, "threadId");
    StopResult result =
        coordinator.stop(id, requireExpectedEpoch(request.getExpectedExecutionEpoch()));
    HarnessThreadStopResultDTO dto = new HarnessThreadStopResultDTO();
    dto.setExecutionEpoch(result.executionEpoch());
    dto.setCancelledInputs(result.cancelledInputs().stream().map(converter::convert).toList());
    return dto;
  }
}
