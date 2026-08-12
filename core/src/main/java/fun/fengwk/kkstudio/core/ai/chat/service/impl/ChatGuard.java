package fun.fengwk.kkstudio.core.ai.chat.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.ai.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.core.ai.chat.repo.ChatRepository;
import fun.fengwk.kkstudio.core.ai.chat.service.ChatIds;
import fun.fengwk.kkstudio.core.ai.chat.service.model.Chat;
import fun.fengwk.kkstudio.core.ai.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;

import java.util.UUID;

/** Chat CRUD 路径的守卫。所有失败都是类型化领域错误，以便集中的 web 翻译器映射为 HTTP 状态码。 */
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
    UUID parsed = ChatIds.parseUuid(id, "id");
    Chat chat = chatRepository.getById(parsed);
    if (chat == null) {
      throw new AiResourceNotFoundException(RESOURCE, RESOURCE + " not found: " + id);
    }
    return chat;
  }

  public Chat requireChat(UUID id) {
    Chat chat = chatRepository.getById(id);
    if (chat == null) {
      throw new AiResourceNotFoundException(RESOURCE, RESOURCE + " not found: " + id);
    }
    return chat;
  }

  /** 对照当前 catalog 校验可见的 Agent 名。 */
  public void ensureAgentExists(String agentName) {
    if (agentName == null || agentName.isBlank()) {
      throw new AiValidationException(RESOURCE, "agentName must not be blank");
    }
    if (agentDefinitionRepository.getByName(agentName) == null) {
      throw new AiValidationException(
          AGENT_RESOURCE, "unknown " + AGENT_RESOURCE + ": " + agentName);
    }
  }
}
