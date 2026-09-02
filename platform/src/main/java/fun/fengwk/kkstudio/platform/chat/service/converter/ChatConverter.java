package fun.fengwk.kkstudio.platform.chat.service.converter;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.chat.service.ChatIds;
import fun.fengwk.kkstudio.platform.chat.service.model.Chat;
import fun.fengwk.kkstudio.platform.error.CatalogVersions;
import fun.fengwk.kkstudio.share.ai.chat.ChatDTO;

/** 将 Chat 领域行转换为公开 DTO。 */
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
    dto.setWorkspacePath(chat.getWorkspacePath());
    dto.setYoloEnabled(chat.isYoloEnabled());
    dto.setVersion(CatalogVersions.format(chat.getVersion()));
    dto.setCreateTime(chat.getCreateTime());
    dto.setUpdateTime(chat.getUpdateTime());
    return dto;
  }
}
