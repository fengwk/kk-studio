package fun.fengwk.kkstudio.core.ai.chat.service.impl;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.ai.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.core.ai.chat.service.model.Chat;
import fun.fengwk.kkstudio.core.ai.error.AiValidationException;
import fun.fengwk.kkstudio.core.persistence.id.PostgresqlSequenceIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettingsProvider;
import fun.fengwk.kkstudio.share.ai.chat.ChatCreateDTO;
import fun.fengwk.kkstudio.share.ai.chat.ChatUpdateDTO;

/**
 * Normalizes Chat mutable fields and allocates Chat ids.
 *
 * <p>Update semantics: {@code null} fields preserve the current value. Chat title is required when
 * supplied; a supplied {@code agentName} must be non-blank. Catalog existence is validated by
 * {@link ChatGuard}.
 */
@Component
public class ChatMutationFactory {

  private static final String RESOURCE = "chat";
  private static final int TITLE_MAX_LENGTH = 256;

  private final AgentEditableSupport editableSupport;
  private final PostgresqlSequenceIdGenerator idGenerator;
  private final ToolSettingsProvider toolSettingsProvider;

  public ChatMutationFactory(
      AgentEditableSupport editableSupport,
      PostgresqlSequenceIdGenerator idGenerator,
      ToolSettingsProvider toolSettingsProvider) {
    this.editableSupport = editableSupport;
    this.idGenerator = idGenerator;
    this.toolSettingsProvider = toolSettingsProvider;
  }

  public Chat newChat(ChatCreateDTO createDTO) {
    if (createDTO == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " body must not be null");
    }
    Chat chat = new Chat();
    chat.setId(idGenerator.next());
    String title = editableSupport.trimToNull(createDTO.getTitle());
    if (title == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " title must not be blank");
    }
    editableSupport.validateMaxLength(RESOURCE, "title", title, TITLE_MAX_LENGTH);
    chat.setTitle(title);
    chat.setAgentName(parseRequiredAgentName(createDTO.getAgentName()));
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
    if (updateDTO.isYoloEnabledProvided()) {
      if (updateDTO.getYoloEnabled() == null) {
        throw new AiValidationException(RESOURCE, "yoloEnabled must not be null");
      }
      chat.setYoloEnabled(updateDTO.getYoloEnabled());
    }
  }

  /** Normalizes the required Agent name text. */
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
