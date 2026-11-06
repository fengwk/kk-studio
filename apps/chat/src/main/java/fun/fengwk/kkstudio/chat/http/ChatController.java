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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.chat.ChatService;
import fun.fengwk.kkstudio.chat.dto.ChatCreateDTO;
import fun.fengwk.kkstudio.chat.dto.ChatDTO;
import fun.fengwk.kkstudio.chat.dto.ChatPageDTO;
import fun.fengwk.kkstudio.chat.dto.ChatSessionPageDTO;
import fun.fengwk.kkstudio.chat.dto.ChatUpdateDTO;
import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.common.number.DecimalLongs;
import fun.fengwk.kkstudio.harness.api.HarnessConflictReason;
import fun.fengwk.kkstudio.platform.http.ApiResponse;
import fun.fengwk.kkstudio.share.runtime.HarnessSessionSummaryDTO;

import java.util.Arrays;
import java.util.Set;
import java.util.function.Supplier;

/** Chat 集合 CRUD、keyset 分页与 Chat owner 的 Harness Session 摘要查询。 */
@AllArgsConstructor
@RequestMapping("/api/chats")
@RestController
public class ChatController {

  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 100;
  private static final Set<String> HARNESS_RESOURCES =
      Set.of("thread", "session", "entry", "execution", "agent_task");

  private final ChatService chatService;

  @GetMapping
  public ApiResponse<ChatPageDTO> listChats(
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit) {
    return ApiResponse.ok(
        withRuntimeTranslation(() -> chatService.listChats(cursor, parseLimit(limit))));
  }

  @PostMapping
  public ApiResponse<ChatDTO> createChat(@RequestBody(required = false) ChatCreateDTO createDTO) {
    ChatCreateDTO body = createDTO == null ? new ChatCreateDTO() : createDTO;
    return ApiResponse.created(chatService.createChat(body));
  }

  @GetMapping("/{chatId}")
  public ApiResponse<ChatDTO> getChat(@PathVariable("chatId") String chatId) {
    return ApiResponse.ok(withRuntimeTranslation(() -> chatService.getChat(chatId)));
  }

  @PutMapping("/{chatId}")
  public ApiResponse<ChatDTO> updateChat(
      @PathVariable("chatId") String chatId, @RequestBody ChatUpdateDTO updateDTO) {
    return ApiResponse.ok(withRuntimeTranslation(() -> chatService.updateChat(chatId, updateDTO)));
  }

  @DeleteMapping("/{chatId}")
  public ApiResponse<Void> deleteChat(
      @PathVariable("chatId") String chatId,
      @RequestParam("expectedVersion") String expectedVersion) {
    withRuntimeTranslation(
        () -> {
          chatService.deleteChat(chatId, expectedVersion);
          return null;
        });
    return ApiResponse.noContent();
  }

  /** 返回该 Chat 的归属 Session 摘要一页（按归属时间从新到旧）。 */
  @GetMapping("/{chatId}/sessions")
  public ApiResponse<ChatSessionPageDTO> listChatSessions(
      @PathVariable("chatId") String chatId,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit) {
    return ApiResponse.ok(
        withRuntimeTranslation(() -> chatService.listSessions(chatId, cursor, parseLimit(limit))));
  }

  /** 返回单个归属 Session 摘要；用于深链与重命名时的按 id 解析。 */
  @GetMapping("/{chatId}/sessions/{sessionId}")
  public ApiResponse<HarnessSessionSummaryDTO> getChatSession(
      @PathVariable("chatId") String chatId, @PathVariable("sessionId") String sessionId) {
    return ApiResponse.ok(withRuntimeTranslation(() -> chatService.getSession(chatId, sessionId)));
  }

  /** 分页上限：缺省 50；显式给出时必须是 [1, 100] 内的 canonical 十进制整数。 */
  private static int parseLimit(String limit) {
    if (limit == null) {
      return DEFAULT_LIMIT;
    }
    long parsed = DecimalLongs.parsePositive(limit, "limit");
    if (parsed > MAX_LIMIT) {
      throw new IllegalArgumentException("limit must be within [1, " + MAX_LIMIT + "]");
    }
    return (int) parsed;
  }

  /** 将 Harness Runtime 与边界参数解析异常翻译为统一 HTTP 错误响应；领域错误交给集中 advice。 */
  private static <T> T withRuntimeTranslation(Supplier<T> operation) {
    try {
      return operation.get();
    } catch (DomainException error) {
      if (error.code() == DomainErrorCode.NOT_FOUND
          && error.resource() != null
          && HARNESS_RESOURCES.contains(error.resource())) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, error.getMessage(), error);
      }
      if (error.code() == DomainErrorCode.CONFLICT
          && Arrays.stream(HarnessConflictReason.values())
              .anyMatch(reason -> reason.name().equals(error.reason()))) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, error.getMessage(), error);
      }
      throw error;
    } catch (IllegalArgumentException error) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
    }
  }
}
