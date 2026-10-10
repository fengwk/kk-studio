package fun.fengwk.kkstudio.platform.chat.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.chat.service.model.Chat;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.chat.ChatCreateDTO;
import fun.fengwk.kkstudio.share.ai.chat.ChatUpdateDTO;

import java.util.UUID;

/**
 * 规范化 Chat 可变字段并分配 Chat id。
 *
 * <p>title/agentName 非 null 时更新并校验；Environment 省略保留、显式 null 清空，YOLO 省略保留、显式 null 拒绝。catalog 存在性由
 * {@link ChatGuard} 校验。创建省略 YOLO 时为 {@code false}；创建省略 Environment 时无默认环境。
 */
@Component
public class ChatMutationFactory {

  private static final String RESOURCE = "chat";
  private static final int TITLE_MAX_LENGTH = 256;

  private final AgentEditableSupport editableSupport;

  public ChatMutationFactory(AgentEditableSupport editableSupport) {
    this.editableSupport = editableSupport;
  }

  public Chat newChat(ChatCreateDTO createDTO) {
    if (createDTO == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " body must not be null");
    }
    Chat chat = new Chat();
    chat.setId(UUID.randomUUID());
    String title = editableSupport.trimToNull(createDTO.getTitle());
    if (title == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " title must not be blank");
    }
    editableSupport.validateMaxLength(RESOURCE, "title", title, TITLE_MAX_LENGTH);
    chat.setTitle(title);
    chat.setAgentName(parseRequiredAgentName(createDTO.getAgentName()));
    chat.setEnvironmentName(parseEnvironmentName(createDTO.getEnvironmentName()));
    chat.setYoloEnabled(Boolean.TRUE.equals(createDTO.getYoloEnabled()));
    return chat;
  }

  public void apply(Chat chat, ChatUpdateDTO updateDTO) {
    if (chat == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " must not be null");
    }
    if (updateDTO == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " body must not be null");
    }
    if (updateDTO.getTitle() != null) {
      String title = editableSupport.trimToNull(updateDTO.getTitle());
      if (title == null) {
        throw new AiValidationException(RESOURCE, RESOURCE + " title must not be blank");
      }
      editableSupport.validateMaxLength(RESOURCE, "title", title, TITLE_MAX_LENGTH);
      chat.setTitle(title);
    }
    if (updateDTO.getAgentName() != null) {
      chat.setAgentName(parseRequiredAgentName(updateDTO.getAgentName()));
    }
    if (updateDTO.isEnvironmentNameProvided()) {
      chat.setEnvironmentName(parseEnvironmentName(updateDTO.getEnvironmentName()));
    }
    if (updateDTO.isYoloEnabledProvided()) {
      if (updateDTO.getYoloEnabled() == null) {
        throw new AiValidationException(RESOURCE, "yoloEnabled must not be null");
      }
      chat.setYoloEnabled(updateDTO.getYoloEnabled());
    }
  }

  /** 规范化必填的 Agent 名文本。 */
  private String parseRequiredAgentName(String raw) {
    if (raw == null) {
      throw new AiValidationException(RESOURCE, "agentName must not be blank");
    }
    String trimmed = raw.strip();
    if (trimmed.isEmpty()) {
      throw new AiValidationException(RESOURCE, "agentName must not be blank");
    }
    if (!raw.equals(trimmed)) {
      throw new AiValidationException(
          RESOURCE, "agentName must not contain surrounding whitespace");
    }
    if (trimmed.indexOf('/') >= 0) {
      throw new AiValidationException(RESOURCE, "agentName must not contain '/'");
    }
    editableSupport.validateMaxLength(RESOURCE, "agentName", trimmed, 64);
    return trimmed;
  }

  /** 规范化 nullable Environment 名：null 表示无默认环境，非 null 复用 BranchSettings 的 canonical 规则。 */
  private String parseEnvironmentName(String raw) {
    try {
      return BranchSettings.requireCanonicalEnvironmentName(raw, "environmentName");
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(RESOURCE, error.getMessage());
    }
  }
}
