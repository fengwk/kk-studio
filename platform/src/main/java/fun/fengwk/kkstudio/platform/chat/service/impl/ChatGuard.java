package fun.fengwk.kkstudio.platform.chat.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.chat.repo.ChatRepository;
import fun.fengwk.kkstudio.platform.chat.service.ChatIds;
import fun.fengwk.kkstudio.platform.chat.service.model.Chat;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;

import java.util.UUID;

/** Chat CRUD 路径的守卫。所有失败都是类型化领域错误，以便集中的 web 翻译器映射为 HTTP 状态码。 */
@Component
public class ChatGuard {

  private static final String RESOURCE = "chat";
  private static final String AGENT_RESOURCE = "agent_definition";
  private static final String ENVIRONMENT_RESOURCE = "environment";

  private final ChatRepository chatRepository;
  private final AgentDefinitionRepository agentDefinitionRepository;
  private final EnvironmentRepository environmentRepository;

  public ChatGuard(
      ChatRepository chatRepository,
      AgentDefinitionRepository agentDefinitionRepository,
      EnvironmentRepository environmentRepository) {
    this.chatRepository = chatRepository;
    this.agentDefinitionRepository = agentDefinitionRepository;
    this.environmentRepository = environmentRepository;
  }

  public Chat requireChat(String id) {
    UUID parsed = ChatIds.parseUuid(id, "id");
    Chat chat = chatRepository.getById(parsed);
    if (chat == null) {
      throw new AiResourceNotFoundException(RESOURCE);
    }
    return chat;
  }

  public Chat requireChat(UUID id) {
    Chat chat = chatRepository.getById(id);
    if (chat == null) {
      throw new AiResourceNotFoundException(RESOURCE);
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

  /** 对照当前 catalog 校验可见的 Environment 名；null 表示无默认环境，不做校验。 */
  public void ensureEnvironmentExists(String environmentName) {
    if (environmentName == null) {
      return;
    }
    if (!environmentRepository.existsByName(environmentName)) {
      throw new AiValidationException(
          ENVIRONMENT_RESOURCE, "unknown " + ENVIRONMENT_RESOURCE + ": " + environmentName);
    }
  }
}
