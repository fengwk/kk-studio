package fun.fengwk.kkstudio.core.harness.thread.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fun.fengwk.kkstudio.core.harness.session.support.HarnessIds;
import fun.fengwk.kkstudio.core.harness.thread.command.RuntimeConfigSnapshotResolver;
import fun.fengwk.kkstudio.core.harness.thread.service.HarnessThreadCommandService;
import fun.fengwk.kkstudio.core.harness.tool.configuration.ToolSettingsProvider;
import fun.fengwk.kkstudio.harness.kernel.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.kernel.thread.ThreadInputPayload;
import fun.fengwk.kkstudio.harness.kernel.thread.ThreadInputType;
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.CustomMessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.port.ActivationNotifier;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.RuntimeConfigInputPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.RuntimeEntryInputPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandTransactions;
import fun.fengwk.kkstudio.share.model.HarnessThreadAgentSetDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadCustomMessageCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadInputDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadMessageCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadModelSetDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadStopDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadStopResultDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadYoloSetDTO;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** final Thread command facade：在 enqueue 前冻结所有配置 payload，提交后仅 best-effort signal。 */
@Slf4j
@Service
public class HarnessThreadCommandServiceImpl implements HarnessThreadCommandService {

  private final ThreadCommandTransactions transactions;
  private final RuntimeConfigSnapshotResolver snapshotResolver;
  private final ToolSettingsProvider toolSettingsProvider;
  private final ActivationNotifier activationNotifier;
  private final KernelHarnessThreadDtoConverter converter;

  public HarnessThreadCommandServiceImpl(
      ThreadCommandTransactions transactions,
      RuntimeConfigSnapshotResolver snapshotResolver,
      ToolSettingsProvider toolSettingsProvider,
      ActivationNotifier activationNotifier,
      KernelHarnessThreadDtoConverter converter) {
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.snapshotResolver = Objects.requireNonNull(snapshotResolver, "snapshotResolver");
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
    return converter.convert(
        transactions.createBranch(parsedSessionId, fromEntryId, Instant.now()));
  }

  @Override
  @Transactional
  public HarnessThreadInputDTO submitUserMessage(
      String threadId, HarnessThreadMessageCreateDTO createDTO) {
    Objects.requireNonNull(createDTO, "createDTO");
    long id = HarnessIds.parsePositive(threadId, "threadId");
    Optional<HarnessThreadInputDTO> existing = retry(id, createDTO.getClientMessageId());
    if (existing.isPresent()) {
      return existing.get();
    }
    String content = requireContent(createDTO.getContent());
    AgentMessage message =
        new AgentMessage(
            AgentMessageRole.USER, List.<AgentMessageContent>of(new TextMessageContent(content)));
    return enqueue(
        id,
        new RuntimeEntryInputPayload(
            ThreadInputType.USER_MESSAGE, new MessageEntryPayload(message)),
        createDTO.getClientMessageId());
  }

  @Override
  @Transactional
  public HarnessThreadInputDTO submitCustomMessage(
      String threadId, HarnessThreadCustomMessageCreateDTO createDTO) {
    Objects.requireNonNull(createDTO, "createDTO");
    long id = HarnessIds.parsePositive(threadId, "threadId");
    Optional<HarnessThreadInputDTO> existing = retry(id, createDTO.getClientMessageId());
    if (existing.isPresent()) {
      return existing.get();
    }
    AgentMessage message =
        new AgentMessage(
            parseCustomRole(createDTO.getRole()),
            List.<AgentMessageContent>of(
                new TextMessageContent(requireContent(createDTO.getContent()))));
    return enqueue(
        id,
        new RuntimeEntryInputPayload(
            ThreadInputType.CUSTOM_MESSAGE, new CustomMessageEntryPayload(message)),
        createDTO.getClientMessageId());
  }

  @Override
  @Transactional
  public HarnessThreadInputDTO queueYolo(String threadId, HarnessThreadYoloSetDTO request) {
    Objects.requireNonNull(request, "request");
    long id = HarnessIds.parsePositive(threadId, "threadId");
    Optional<HarnessThreadInputDTO> existing = retry(id, request.getClientMessageId());
    if (existing.isPresent()) {
      return existing.get();
    }
    if (request.getYoloEnabled() == null) {
      throw new IllegalArgumentException("yoloEnabled must not be null");
    }
    RuntimeConfigSnapshot current =
        transactions
            .lockAndFindCurrentConfig(id)
            .orElseThrow(() -> new IllegalStateException("thread has no runtime config: " + id));
    RuntimeConfigSnapshot frozen =
        snapshotResolver.replaceYolo(current, Boolean.TRUE.equals(request.getYoloEnabled()));
    return enqueue(
        id,
        new RuntimeConfigInputPayload(ThreadInputType.SET_YOLO, frozen),
        request.getClientMessageId());
  }

  @Override
  @Transactional
  public HarnessThreadInputDTO queueAgent(String threadId, HarnessThreadAgentSetDTO request) {
    Objects.requireNonNull(request, "request");
    long id = HarnessIds.parsePositive(threadId, "threadId");
    Optional<HarnessThreadInputDTO> existing = retry(id, request.getClientMessageId());
    if (existing.isPresent()) {
      return existing.get();
    }
    long definitionId =
        HarnessIds.parsePositive(request.getAgentDefinitionId(), "agentDefinitionId");
    boolean yolo =
        transactions
            .lockAndFindCurrentConfig(id)
            .map(snapshot -> snapshot.policy().yoloEnabled())
            .orElseGet(() -> toolSettingsProvider.get().defaultYolo());
    RuntimeConfigSnapshot frozen = snapshotResolver.resolveAgent(definitionId, yolo);
    return enqueue(
        id,
        new RuntimeConfigInputPayload(ThreadInputType.SET_AGENT, frozen),
        request.getClientMessageId());
  }

  @Override
  @Transactional
  public HarnessThreadInputDTO queueModel(String threadId, HarnessThreadModelSetDTO request) {
    Objects.requireNonNull(request, "request");
    long id = HarnessIds.parsePositive(threadId, "threadId");
    Optional<HarnessThreadInputDTO> existing = retry(id, request.getClientMessageId());
    if (existing.isPresent()) {
      return existing.get();
    }
    long modelId = HarnessIds.parsePositive(request.getModelId(), "modelId");
    RuntimeConfigSnapshot current =
        transactions
            .lockAndFindCurrentConfig(id)
            .orElseThrow(() -> new IllegalStateException("thread has no runtime config: " + id));
    RuntimeConfigSnapshot frozen =
        snapshotResolver.replaceModel(current, modelId, request.getVariant());
    return enqueue(
        id,
        new RuntimeConfigInputPayload(ThreadInputType.SET_MODEL, frozen),
        request.getClientMessageId());
  }

  @Override
  @Transactional
  public HarnessThreadStopResultDTO stop(String threadId, HarnessThreadStopDTO request) {
    Objects.requireNonNull(request, "request");
    long id = HarnessIds.parsePositive(threadId, "threadId");
    requireClientRequestId(request.getClientRequestId());
    ThreadCommandTransactions.StopResult result = transactions.stop(id, Instant.now());
    afterCommitNotify(result.target());
    HarnessThreadStopResultDTO dto = new HarnessThreadStopResultDTO();
    dto.setStopId(Long.toString(result.executionEpoch()));
    dto.setCancelledInputs(result.cancelledInputs().stream().map(converter::convert).toList());
    dto.setRestoredMessages(List.of());
    return dto;
  }

  private HarnessThreadInputDTO enqueue(
      long threadId, ThreadInputPayload payload, String idempotencyKey) {
    ThreadCommandTransactions.EnqueueResult result =
        transactions.enqueue(threadId, payload, idempotencyKey, Instant.now());
    afterCommitNotify(result.target());
    return converter.convert(result.input());
  }

  private Optional<HarnessThreadInputDTO> retry(long threadId, String idempotencyKey) {
    return transactions
        .findExistingInput(threadId, idempotencyKey)
        .map(
            result -> {
              afterCommitNotify(result.target());
              return converter.convert(result.input());
            });
  }

  private static String requireContent(String content) {
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("content must not be blank");
    }
    return content;
  }

  private static void requireClientRequestId(String clientRequestId) {
    if (clientRequestId == null || clientRequestId.isBlank()) {
      throw new IllegalArgumentException("clientRequestId must not be blank");
    }
  }

  private static AgentMessageRole parseCustomRole(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("role must not be blank");
    }
    AgentMessageRole role;
    try {
      role = AgentMessageRole.valueOf(value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("unsupported custom message role: " + value, error);
    }
    if (role != AgentMessageRole.SYSTEM && role != AgentMessageRole.USER) {
      throw new IllegalArgumentException("custom message role must be system or user");
    }
    return role;
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
