package fun.fengwk.kkstudio.core.ai.chat.service.converter;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.ai.chat.service.ChatIds;
import fun.fengwk.kkstudio.core.ai.chat.service.model.Chat;
import fun.fengwk.kkstudio.core.ai.error.CatalogVersions;
import fun.fengwk.kkstudio.share.ai.chat.ChatDTO;

/** Converts Chat domain rows to public DTOs. */
@Component
public class ChatConverter {

  public ChatDTO convert(Chat chat) {
    if (chat == null) {
      return null;
    }
    ChatDTO dto = new ChatDTO();
    dto.setId(ChatIds.format(chat.getId()));
    dto.setTitle(chat.getTitle());
    dto.setAgentName(chat.getAgentName());
    dto.setEnvironmentName(chat.getEnvironmentName());
    dto.setYoloEnabled(chat.isYoloEnabled());
    dto.setVersion(CatalogVersions.format(chat.getVersion()));
    dto.setCreateTime(chat.getCreateTime());
    dto.setUpdateTime(chat.getUpdateTime());
    return dto;
  }
}
