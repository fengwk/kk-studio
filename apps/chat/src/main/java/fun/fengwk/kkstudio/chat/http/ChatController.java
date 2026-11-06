package fun.fengwk.kkstudio.chat.http;

import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.chat.ChatService;
import fun.fengwk.kkstudio.chat.dto.ChatCreateDTO;
import fun.fengwk.kkstudio.chat.dto.ChatDTO;
import fun.fengwk.kkstudio.chat.dto.ChatPageDTO;
import fun.fengwk.kkstudio.chat.dto.ChatSessionPageDTO;
import fun.fengwk.kkstudio.chat.dto.ChatUpdateDTO;
import fun.fengwk.kkstudio.chat.dto.ChatYoloUpdateDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessSessionSummaryDTO;

import java.util.UUID;

/** Chat 集合 CRUD、keyset 分页与 Chat owner 的 Harness Session 摘要查询。 */
@AllArgsConstructor
@RequestMapping("/api/chat/chats")
@RestController
public class ChatController {

  private final ChatService chatService;

  @GetMapping
  public ChatPageDTO listChats(
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", defaultValue = "50") int limit) {
    return chatService.listChats(cursor, limit);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ChatDTO createChat(@RequestBody ChatCreateDTO createDTO) {
    return chatService.createChat(createDTO);
  }

  @GetMapping("/{chatId}")
  public ChatDTO getChat(@PathVariable("chatId") UUID chatId) {
    return chatService.getChat(chatId.toString());
  }

  @PutMapping("/{chatId}")
  public ChatDTO updateChat(
      @PathVariable("chatId") UUID chatId, @RequestBody ChatUpdateDTO updateDTO) {
    return chatService.updateChat(chatId.toString(), updateDTO);
  }

  @PutMapping("/{chatId}/yolo")
  public ChatDTO updateYolo(
      @PathVariable("chatId") UUID chatId, @RequestBody ChatYoloUpdateDTO updateDTO) {
    return chatService.updateYolo(chatId.toString(), updateDTO);
  }

  @DeleteMapping("/{chatId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteChat(
      @PathVariable("chatId") UUID chatId, @RequestParam("expectedVersion") long expectedVersion) {
    chatService.deleteChat(chatId.toString(), expectedVersion);
  }

  /** 返回该 Chat 的归属 Session 摘要一页（按归属时间从新到旧）。 */
  @GetMapping("/{chatId}/sessions")
  public ChatSessionPageDTO listChatSessions(
      @PathVariable("chatId") UUID chatId,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", defaultValue = "50") int limit) {
    return chatService.listSessions(chatId.toString(), cursor, limit);
  }

  /** 返回单个归属 Session 摘要；用于深链与重命名时的按 id 解析。 */
  @GetMapping("/{chatId}/sessions/{sessionId}")
  public HarnessSessionSummaryDTO getChatSession(
      @PathVariable("chatId") UUID chatId, @PathVariable("sessionId") UUID sessionId) {
    return chatService.getSession(chatId.toString(), sessionId.toString());
  }
}
