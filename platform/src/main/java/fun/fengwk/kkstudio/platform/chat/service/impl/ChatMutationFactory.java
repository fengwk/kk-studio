package fun.fengwk.kkstudio.platform.chat.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.environment.EnvironmentWorkspacePath;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettingsProvider;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.chat.service.model.Chat;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.chat.ChatCreateDTO;
import fun.fengwk.kkstudio.share.ai.chat.ChatUpdateDTO;

import java.util.UUID;

/**
 * 规范化 Chat 可变字段并分配 Chat id。
 *
 * <p>更新语义：{@code null} 字段保留当前值。提供时 Chat title 必填；提供的 {@code agentName} 必须非空白。 catalog 存在性由 {@link
 * ChatGuard} 校验。
 */
@Component
public class ChatMutationFactory {

  private static final String RESOURCE = "chat";
  private static final int TITLE_MAX_LENGTH = 256;

  private final AgentEditableSupport editableSupport;
  private final ToolSettingsProvider toolSettingsProvider;

  public ChatMutationFactory(
      AgentEditableSupport editableSupport, ToolSettingsProvider toolSettingsProvider) {
    this.editableSupport = editableSupport;
    this.toolSettingsProvider = toolSettingsProvider;
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
    chat.setWorkspacePath(parseNullableWorkspacePath(createDTO.getWorkspacePath()));
    chat.setYoloEnabled(
        createDTO.getYoloEnabled() == null
            ? toolSettingsProvider.get().defaultYolo()
            : createDTO.getYoloEnabled());
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
    if (updateDTO.isWorkspacePathProvided()) {
      chat.setWorkspacePath(parseNullableWorkspacePath(updateDTO.getWorkspacePath()));
    }
    if (updateDTO.isYoloEnabledProvided()) {
      if (updateDTO.getYoloEnabled() == null) {
        throw new AiValidationException(RESOURCE, "yoloEnabled must not be null");
      }
      chat.setYoloEnabled(updateDTO.getYoloEnabled());
    }
  }

  private String parseNullableWorkspacePath(String raw) {
    if (raw == null) {
      return null;
    }
    try {
      return EnvironmentWorkspacePath.requireCanonicalRelativePath(raw);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(
          RESOURCE, "invalid workspacePath: " + error.getMessage(), error);
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
}
