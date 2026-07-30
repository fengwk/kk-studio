package fun.fengwk.kkstudio.core.chat.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.agent.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.ai.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;
import fun.fengwk.kkstudio.core.chat.repo.ChatRepository;
import fun.fengwk.kkstudio.core.chat.service.ChatIds;
import fun.fengwk.kkstudio.core.chat.service.model.Chat;

/**
 * Guard for Chat CRUD paths. All failures are typed domain errors so the centralized web translator
 * can map them to HTTP status codes.
 */
@Component
public class ChatGuard {

  private static final String RESOURCE = "chat";
  private static final String AGENT_RESOURCE = "agent_definition";

  private final ChatRepository chatRepository;
  private final AgentDefinitionRepository agentDefinitionRepository;

  public ChatGuard(
      ChatRepository chatRepository, AgentDefinitionRepository agentDefinitionRepository) {
    this.chatRepository = chatRepository;
    this.agentDefinitionRepository = agentDefinitionRepository;
  }

  public Chat requireChat(String id) {
    long parsed = ChatIds.parsePositive(id, "id");
    Chat chat = chatRepository.getById(parsed);
    if (chat == null) {
      throw new AiResourceNotFoundException(RESOURCE, RESOURCE + " not found: " + id);
    }
    return chat;
  }

  public Chat requireChat(long id) {
    Chat chat = chatRepository.getById(id);
    if (chat == null) {
      throw new AiResourceNotFoundException(RESOURCE, RESOURCE + " not found: " + id);
    }
    return chat;
  }

  /**
   * Validates a non-null default agent id against the current Agent catalog. Blank/null is allowed
   * and means "no default". Missing agent raises {@link AiValidationException}.
   */
  public void ensureDefaultAgentExistsIfPresent(Long defaultAgentId) {
    if (defaultAgentId == null) {
      return;
    }
    if (agentDefinitionRepository.getById(defaultAgentId) == null) {
      throw new AiValidationException(
          AGENT_RESOURCE, "unknown " + AGENT_RESOURCE + ": " + defaultAgentId);
    }
  }
}
