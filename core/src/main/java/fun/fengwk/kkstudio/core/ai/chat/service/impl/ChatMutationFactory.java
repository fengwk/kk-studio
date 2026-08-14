package fun.fengwk.kkstudio.core.ai.chat.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.ai.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.core.ai.chat.service.model.Chat;
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettingsProvider;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.share.ai.chat.ChatCreateDTO;
import fun.fengwk.kkstudio.share.ai.chat.ChatUpdateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.EnvironmentBindingDTO;

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
    chat.setEnvironment(parseNullableEnvironmentBinding(createDTO.getEnvironment()));
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
    if (updateDTO.isEnvironmentProvided()) {
      chat.setEnvironment(parseNullableEnvironmentBinding(updateDTO.getEnvironment()));
    }
    if (updateDTO.isYoloEnabledProvided()) {
      if (updateDTO.getYoloEnabled() == null) {
        throw new AiValidationException(RESOURCE, "yoloEnabled must not be null");
      }
      chat.setYoloEnabled(updateDTO.getYoloEnabled());
    }
  }

  /**
   * 规范化可空的完整 Environment binding：null 表示 clear（无默认环境）；提供时必须为完整 {@code {name, workspacePath}}
   * 对象（同存同空由对象存在性保证），两字段分别经 {@link EnvironmentName} 与 workspace path validator 严格校验，非法文本按 Chat
   * 资源契约翻译为 {@link AiValidationException}（HTTP 400）。
   */
  private EnvironmentBinding parseNullableEnvironmentBinding(EnvironmentBindingDTO dto) {
    if (dto == null) {
      return null;
    }
    if (dto.getName() == null) {
      throw new AiValidationException(RESOURCE, "invalid environment: name must not be null");
    }
    if (dto.getWorkspacePath() == null) {
      throw new AiValidationException(
          RESOURCE, "invalid environment: workspacePath must not be null");
    }
    try {
      return new EnvironmentBinding(new EnvironmentName(dto.getName()), dto.getWorkspacePath());
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(RESOURCE, "invalid environment: " + error.getMessage());
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
