package fun.fengwk.kkstudio.core.ai.chat.service;

import fun.fengwk.kkstudio.share.ai.chat.ChatCreateDTO;
import fun.fengwk.kkstudio.share.ai.chat.ChatDTO;
import fun.fengwk.kkstudio.share.ai.chat.ChatUpdateDTO;

import java.util.List;

/** Chat collection CRUD surface. */
public interface ChatService {

  List<ChatDTO> listChats();

  ChatDTO getChat(String id);

  ChatDTO createChat(ChatCreateDTO createDTO);

  ChatDTO updateChat(String id, ChatUpdateDTO updateDTO);

  void deleteChat(String id, String expectedVersion);
}
