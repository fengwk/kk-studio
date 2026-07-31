package fun.fengwk.kkstudio.core.ai.chat.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.ai.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.ai.chat.repo.ChatRepository;
import fun.fengwk.kkstudio.core.ai.chat.service.ChatIds;
import fun.fengwk.kkstudio.core.ai.chat.service.model.Chat;
import fun.fengwk.kkstudio.core.ai.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;

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

  /** Validates a required default Agent id against the current catalog. */
  public void ensureDefaultAgentExists(Long defaultAgentId) {
    if (defaultAgentId == null) {
      throw new AiValidationException(RESOURCE, "defaultAgentId must not be null");
    }
    if (agentDefinitionRepository.getById(defaultAgentId) == null) {
      throw new AiValidationException(
          AGENT_RESOURCE, "unknown " + AGENT_RESOURCE + ": " + defaultAgentId);
    }
  }
}
