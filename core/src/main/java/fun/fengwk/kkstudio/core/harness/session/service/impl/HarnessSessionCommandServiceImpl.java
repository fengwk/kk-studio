package fun.fengwk.kkstudio.core.harness.session.service.impl;

import fun.fengwk.kkstudio.core.agent.definition.repo.impl.mapper.AgentDefinitionMapper;
import fun.fengwk.kkstudio.core.agent.definition.repo.impl.model.AgentDefinitionDO;
import fun.fengwk.kkstudio.core.harness.run.service.HarnessRunTransactionService;
import fun.fengwk.kkstudio.core.harness.session.HarnessAgentSnapshotResolver;
import fun.fengwk.kkstudio.core.harness.session.service.HarnessSessionCommandService;
import fun.fengwk.kkstudio.core.harness.session.store.MysqlHarnessSessionStore;
import fun.fengwk.kkstudio.core.harness.session.store.SnowflakeSessionIdGenerator;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionEntryMapper;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionMapper;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionDO;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionEntryDO;
import fun.fengwk.kkstudio.core.harness.session.support.HarnessIds;
import fun.fengwk.kkstudio.harness.runtime.run.AgentRun;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshotEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.share.model.HarnessSessionCreateDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionEntryDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionMessageCreateDTO;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** T15 harness session command service: create-root and submit-user-message. */
@Service
public class HarnessSessionCommandServiceImpl implements HarnessSessionCommandService {

  private final HarnessSessionMapper sessionMapper;
  private final HarnessSessionEntryMapper entryMapper;
  private final MysqlHarnessSessionStore sessionStore;
  private final AgentDefinitionMapper agentDefinitionMapper;
  private final SnowflakeSessionIdGenerator sessionIdGenerator;
  private final HarnessAgentSnapshotResolver snapshotResolver;
  private final HarnessRunTransactionService runTransactions;
  private final HarnessSessionDtoConverter converter;

  public HarnessSessionCommandServiceImpl(
      HarnessSessionMapper sessionMapper,
      HarnessSessionEntryMapper entryMapper,
      MysqlHarnessSessionStore sessionStore,
      AgentDefinitionMapper agentDefinitionMapper,
      SnowflakeSessionIdGenerator sessionIdGenerator,
      HarnessAgentSnapshotResolver snapshotResolver,
      HarnessRunTransactionService runTransactions,
      HarnessSessionDtoConverter converter) {
    this.sessionMapper = sessionMapper;
    this.entryMapper = entryMapper;
    this.sessionStore = sessionStore;
    this.agentDefinitionMapper = agentDefinitionMapper;
    this.sessionIdGenerator = sessionIdGenerator;
    this.snapshotResolver = snapshotResolver;
    this.runTransactions = runTransactions;
    this.converter = converter;
  }

  @Override
  @Transactional
  public HarnessSessionDTO createRootSession(HarnessSessionCreateDTO createDTO) {
    Objects.requireNonNull(createDTO, "createDTO");
    long agentDefinitionId =
        HarnessIds.parsePositive(createDTO.getAgentDefinitionId(), "agentDefinitionId");
    AgentDefinitionDO definition = agentDefinitionMapper.getById(agentDefinitionId);
    if (definition == null) {
      throw new IllegalArgumentException("unknown agent definition: " + agentDefinitionId);
    }
    Instant now = Instant.now();
    long sessionId = sessionIdGenerator.newSessionId();
    long snapshotEntryId = sessionIdGenerator.newEntryId();

    Session session = Session.root(sessionId, agentDefinitionId, createDTO.getTitle(), false, now);
    sessionStore.create(session);
    AgentSnapshot snapshot = snapshotResolver.snapshotForDefinition(definition);
    AgentSnapshotEntryPayload payload = new AgentSnapshotEntryPayload(snapshot);
    sessionStore.append(
        new SessionEntry(snapshotEntryId, sessionId, null, null, payload.type(), payload, now),
        null,
        session.version());
    return converter.convert(sessionMapper.find(sessionId));
  }

  @Override
  @Transactional
  public HarnessSessionEntryDTO submitUserMessage(
      String sessionId, HarnessSessionMessageCreateDTO createDTO) {
    Objects.requireNonNull(createDTO, "createDTO");
    long parsedSessionId = HarnessIds.parsePositive(sessionId, "sessionId");
    String content = createDTO.getContent();
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("content must not be blank");
    }
    Long expectedLeafEntryId =
        createDTO.getExpectedLeafEntryId() == null
            ? null
            : HarnessIds.parsePositive(createDTO.getExpectedLeafEntryId(), "expectedLeafEntryId");
    Instant now = Instant.now();

    AgentMessage message =
        new AgentMessage(
            AgentMessageRole.USER, List.<AgentMessageContent>of(new TextMessageContent(content)));
    AgentRun run =
        runTransactions.submitUserMessage(parsedSessionId, expectedLeafEntryId, message, now);

    // MysqlHarnessSessionStore attaches the new USER entry as the new leaf; fetch and project it.
    HarnessSessionDO refreshed = sessionMapper.find(parsedSessionId);
    if (refreshed == null) {
      throw new IllegalStateException("session disappeared after submit: " + sessionId);
    }
    if (refreshed.getLeafEntryId() == null) {
      throw new IllegalStateException("session leaf missing after submit: " + sessionId);
    }
    HarnessSessionEntryDO entryRow = entryMapper.find(parsedSessionId, refreshed.getLeafEntryId());
    if (entryRow == null) {
      throw new IllegalStateException(
          "submitted leaf entry missing: " + refreshed.getLeafEntryId());
    }
    // Best-effort: also surface the run id through the DTO runId field so the controller can echo.
    HarnessSessionEntryDTO entryDTO = converter.convert(entryRow);
    entryDTO.setRunId(HarnessIds.format(run.id()));
    return entryDTO;
  }
}
