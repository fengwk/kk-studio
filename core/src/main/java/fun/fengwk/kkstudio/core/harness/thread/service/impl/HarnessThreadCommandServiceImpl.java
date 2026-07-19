package fun.fengwk.kkstudio.core.harness.thread.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fun.fengwk.kkstudio.core.agent.definition.repo.impl.mapper.AgentDefinitionMapper;
import fun.fengwk.kkstudio.core.agent.definition.repo.impl.model.AgentDefinitionDO;
import fun.fengwk.kkstudio.core.harness.session.HarnessAgentSnapshotResolver;
import fun.fengwk.kkstudio.core.harness.session.support.HarnessIds;
import fun.fengwk.kkstudio.core.harness.thread.service.HarnessThreadCommandService;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.AgentThread;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInput;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadKick;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadTransactions;
import fun.fengwk.kkstudio.share.model.HarnessThreadAgentSetDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadCustomMessageCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadInputDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadMessageCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadModelSetDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadStopDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadStopResultDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadToolsetSetDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadYoloSetDTO;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
public class HarnessThreadCommandServiceImpl implements HarnessThreadCommandService {
  private final ThreadTransactions transactions;
  private final AgentDefinitionMapper agentDefinitionMapper;
  private final HarnessAgentSnapshotResolver snapshotResolver;
  private final ThreadKick threadKick;
  private final HarnessThreadDtoConverter converter;

  public HarnessThreadCommandServiceImpl(
      ThreadTransactions transactions,
      AgentDefinitionMapper agentDefinitionMapper,
      HarnessAgentSnapshotResolver snapshotResolver,
      ThreadKick threadKick,
      HarnessThreadDtoConverter converter) {
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.agentDefinitionMapper =
        Objects.requireNonNull(agentDefinitionMapper, "agentDefinitionMapper");
    this.snapshotResolver = Objects.requireNonNull(snapshotResolver, "snapshotResolver");
    this.threadKick = Objects.requireNonNull(threadKick, "threadKick");
    this.converter = Objects.requireNonNull(converter, "converter");
  }

  @Override
  @Transactional
  public HarnessThreadDTO createThread(String sessionId, HarnessThreadCreateDTO createDTO) {
    Objects.requireNonNull(createDTO, "createDTO");
    long parsedSessionId = HarnessIds.parsePositive(sessionId, "sessionId");
    long fromEntryId = HarnessIds.parsePositive(createDTO.getFromEntryId(), "fromEntryId");
    AgentThread thread =
        transactions.createThreadFromEntry(parsedSessionId, fromEntryId, Instant.now());
    return converter.convert(thread);
  }

  @Override
  @Transactional
  public HarnessThreadInputDTO submitUserMessage(
      String threadId, HarnessThreadMessageCreateDTO createDTO) {
    Objects.requireNonNull(createDTO, "createDTO");
    long id = HarnessIds.parsePositive(threadId, "threadId");
    String content = createDTO.getContent();
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("content must not be blank");
    }
    AgentMessage message =
        new AgentMessage(
            AgentMessageRole.USER, List.<AgentMessageContent>of(new TextMessageContent(content)));
    ThreadInput input =
        transactions.submitUserMessage(id, message, createDTO.getClientMessageId(), Instant.now());
    afterCommitKick(id);
    return converter.convert(input);
  }

  @Override
  @Transactional
  public HarnessThreadInputDTO submitCustomMessage(
      String threadId, HarnessThreadCustomMessageCreateDTO createDTO) {
    Objects.requireNonNull(createDTO, "createDTO");
    long id = HarnessIds.parsePositive(threadId, "threadId");
    String content = createDTO.getContent();
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("content must not be blank");
    }
    AgentMessage message =
        new AgentMessage(
            parseCustomRole(createDTO.getRole()),
            List.<AgentMessageContent>of(new TextMessageContent(content)));
    ThreadInput input =
        transactions.submitCustomMessage(
            id, message, createDTO.getClientMessageId(), Instant.now());
    afterCommitKick(id);
    return converter.convert(input);
  }

  @Override
  @Transactional
  public HarnessThreadInputDTO queueYolo(String threadId, HarnessThreadYoloSetDTO request) {
    Objects.requireNonNull(request, "request");
    if (request.getYoloEnabled() == null) {
      throw new IllegalArgumentException("yoloEnabled must not be null");
    }
    long id = HarnessIds.parsePositive(threadId, "threadId");
    ThreadInput input =
        transactions.submitSetYolo(
            id,
            Boolean.TRUE.equals(request.getYoloEnabled()),
            request.getClientMessageId(),
            Instant.now());
    afterCommitKick(id);
    return converter.convert(input);
  }

  @Override
  @Transactional
  public HarnessThreadInputDTO queueAgent(String threadId, HarnessThreadAgentSetDTO request) {
    Objects.requireNonNull(request, "request");
    long id = HarnessIds.parsePositive(threadId, "threadId");
    long agentDefinitionId =
        HarnessIds.parsePositive(request.getAgentDefinitionId(), "agentDefinitionId");
    AgentDefinitionDO definition = requireDefinition(agentDefinitionId);
    AgentSnapshot snapshot = snapshotResolver.snapshotForDefinition(definition);
    ThreadInput input =
        transactions.submitSetAgent(
            id, agentDefinitionId, snapshot, request.getClientMessageId(), Instant.now());
    afterCommitKick(id);
    return converter.convert(input);
  }

  @Override
  @Transactional
  public HarnessThreadInputDTO queueModel(String threadId, HarnessThreadModelSetDTO request) {
    Objects.requireNonNull(request, "request");
    long id = HarnessIds.parsePositive(threadId, "threadId");
    ThreadInput input =
        transactions.submitSetModel(
            id,
            request.getModelId(),
            request.getVariant(),
            request.getClientMessageId(),
            Instant.now());
    afterCommitKick(id);
    return converter.convert(input);
  }

  @Override
  @Transactional
  public HarnessThreadInputDTO queueToolset(String threadId, HarnessThreadToolsetSetDTO request) {
    Objects.requireNonNull(request, "request");
    long id = HarnessIds.parsePositive(threadId, "threadId");
    ThreadInput input =
        transactions.submitSetToolset(
            id, request.getTools(), request.getClientMessageId(), Instant.now());
    afterCommitKick(id);
    return converter.convert(input);
  }

  @Override
  @Transactional
  public HarnessThreadStopResultDTO stop(String threadId, HarnessThreadStopDTO request) {
    Objects.requireNonNull(request, "request");
    long id = HarnessIds.parsePositive(threadId, "threadId");
    ThreadTransactions.StopResult result =
        transactions.stop(id, request.getClientRequestId(), Instant.now());
    HarnessThreadStopResultDTO dto = new HarnessThreadStopResultDTO();
    dto.setStopId(Long.toString(result.stop().id()));
    dto.setCancelledInputs(result.cancelledInputs().stream().map(converter::convert).toList());
    dto.setRestoredMessages(result.restoredMessages());
    return dto;
  }

  @Override
  @Transactional
  public HarnessThreadDTO retry(String threadId) {
    long id = HarnessIds.parsePositive(threadId, "threadId");
    HarnessThreadDTO dto = converter.convert(transactions.retry(id, Instant.now()));
    afterCommitKick(id);
    return dto;
  }

  private AgentDefinitionDO requireDefinition(long agentDefinitionId) {
    AgentDefinitionDO definition = agentDefinitionMapper.getById(agentDefinitionId);
    if (definition == null) {
      throw new IllegalArgumentException("unknown agent definition: " + agentDefinitionId);
    }
    return definition;
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

  private void afterCommitKick(long threadId) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      threadKick.kick(threadId);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            threadKick.kick(threadId);
          }
        });
  }
}
