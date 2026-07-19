package fun.fengwk.kkstudio.core.chat.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.chat.repo.ChatRepository;
import fun.fengwk.kkstudio.core.chat.service.ChatIds;
import fun.fengwk.kkstudio.core.chat.service.model.Chat;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionMapper;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionDO;

import java.util.NoSuchElementException;

/**
 * Guard for Chat CRUD and membership paths.
 *
 * <p>{@code IllegalArgumentException} → 400, {@link NoSuchElementException} → 404.
 */
@Component
public class ChatGuard {

  private final ChatRepository chatRepository;
  private final AgentDefinitionRepository agentDefinitionRepository;
  private final HarnessSessionMapper harnessSessionMapper;

  public ChatGuard(
      ChatRepository chatRepository,
      AgentDefinitionRepository agentDefinitionRepository,
      HarnessSessionMapper harnessSessionMapper) {
    this.chatRepository = chatRepository;
    this.agentDefinitionRepository = agentDefinitionRepository;
    this.harnessSessionMapper = harnessSessionMapper;
  }

  public Chat requireChat(String id) {
    long parsed = ChatIds.parsePositive(id, "id");
    Chat chat = chatRepository.getById(parsed);
    if (chat == null) {
      throw new NoSuchElementException("chat not found: " + id);
    }
    return chat;
  }

  public Chat requireChat(long id) {
    Chat chat = chatRepository.getById(id);
    if (chat == null) {
      throw new NoSuchElementException("chat not found: " + id);
    }
    return chat;
  }

  /**
   * Validates a non-null default agent id against the current Agent catalog. Blank/null is allowed
   * and means "no default". Missing agent raises {@link IllegalArgumentException}.
   */
  public void ensureDefaultAgentExistsIfPresent(Long defaultAgentId) {
    if (defaultAgentId == null) {
      return;
    }
    if (agentDefinitionRepository.getById(defaultAgentId) == null) {
      throw new IllegalArgumentException("unknown agent definition: " + defaultAgentId);
    }
  }

  public HarnessSessionDO requireSession(String sessionId) {
    long parsed = ChatIds.parsePositive(sessionId, "sessionId");
    HarnessSessionDO session = harnessSessionMapper.find(parsed);
    if (session == null) {
      throw new NoSuchElementException("session not found: " + sessionId);
    }
    return session;
  }
}
