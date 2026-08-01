package fun.fengwk.kkstudio.core.ai.runtime.thread.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.ai.runtime.session.support.HarnessIds;
import fun.fengwk.kkstudio.core.ai.runtime.thread.service.HarnessThreadCommandService;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandCoordinator;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandCoordinator.StopResult;
import fun.fengwk.kkstudio.harness.runtime.thread.TurnSettings;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCustomMessageCreateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadHeadUpdateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadInputDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadMessageCreateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadStopDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadStopResultDTO;

import java.util.Objects;

/** Thin Core Thread command boundary: decimal/DTO parse and product defaults. */
@Service
public class HarnessThreadCommandServiceImpl implements HarnessThreadCommandService {

  private final ThreadCommandCoordinator coordinator;
  private final HarnessThreadDtoConverter converter;

  public HarnessThreadCommandServiceImpl(
      ThreadCommandCoordinator coordinator, HarnessThreadDtoConverter converter) {
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    this.converter = Objects.requireNonNull(converter, "converter");
  }

  @Override
  @Transactional
  public HarnessThreadDTO createThread(String title) {
    return converter.convert(coordinator.createThread(title));
  }

  @Override
  @Transactional
  public HarnessThreadDTO updateHead(String threadId, HarnessThreadHeadUpdateDTO dto) {
    Objects.requireNonNull(dto, "dto");
    long id = HarnessIds.parsePositive(threadId, "threadId");
    long headEntryId = HarnessIds.parsePositive(dto.getHeadEntryId(), "headEntryId");
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
                turnSettings(
                    createDTO.getAgentName(),
                    createDTO.getEnvironmentName(),
                    createDTO.getYoloEnabled()),
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
                turnSettings(
                    createDTO.getAgentName(),
                    createDTO.getEnvironmentName(),
                    createDTO.getYoloEnabled()),
                createDTO.getRole(),
                createDTO.getContent(),
                createDTO.getClientMessageId(),
                requireExpectedEpoch(createDTO.getExpectedExecutionEpoch()))
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

  private static TurnSettings turnSettings(
      String agentName, String environmentName, Boolean yoloEnabled) {
    if (yoloEnabled == null) {
      throw new IllegalArgumentException("yoloEnabled must not be null");
    }
    return new TurnSettings(agentName, environmentName, yoloEnabled);
  }
}
