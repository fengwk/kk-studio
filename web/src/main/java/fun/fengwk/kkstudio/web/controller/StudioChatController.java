package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
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

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.platform.ai.chat.service.ChatIds;
import fun.fengwk.kkstudio.platform.ai.chat.service.ChatService;
import fun.fengwk.kkstudio.platform.studio.StudioHarnessQueryService;
import fun.fengwk.kkstudio.share.ai.chat.ChatCreateDTO;
import fun.fengwk.kkstudio.share.ai.chat.ChatDTO;
import fun.fengwk.kkstudio.share.ai.chat.ChatUpdateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionSummaryDTO;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** Chat 集合 CRUD 与 Chat owner 的 Harness Session 摘要查询。 */
@AllArgsConstructor
@RequestMapping("/api/ai/chat")
@RestController
public class StudioChatController {

  private final ChatService chatService;
  private final StudioHarnessQueryService harnessQueryService;

  @GetMapping
  public Result<List<ChatDTO>> listChats() {
    return Results.ok(chatService.listChats());
  }

  @PostMapping
  public Result<ChatDTO> createChat(@RequestBody(required = false) ChatCreateDTO createDTO) {
    ChatCreateDTO body = createDTO == null ? new ChatCreateDTO() : createDTO;
    return Results.created(chatService.createChat(body));
  }

  @GetMapping("/{id}")
  public Result<ChatDTO> getChat(@PathVariable("id") String id) {
    return Results.ok(chatService.getChat(id));
  }

  @PutMapping("/{id}")
  public Result<ChatDTO> updateChat(
      @PathVariable("id") String id, @RequestBody ChatUpdateDTO updateDTO) {
    return Results.ok(chatService.updateChat(id, updateDTO));
  }

  @DeleteMapping("/{id}")
  public Result<Void> deleteChat(
      @PathVariable("id") String id, @RequestParam("expectedVersion") String expectedVersion) {
    chatService.deleteChat(id, expectedVersion);
    return Results.noContent();
  }

  /** 返回该 Chat 关联的 Session 摘要（按归属时间从新到旧）。 */
  @GetMapping("/{chatId}/sessions")
  public Result<List<HarnessSessionSummaryDTO>> listChatSessions(@PathVariable String chatId) {
    UUID id = ChatIds.parseUuid(chatId, "chatId");
    return Results.ok(withRuntimeTranslation(() -> harnessQueryService.listChatSessions(id)));
  }

  /** 将 Harness Runtime 异常翻译为统一 HTTP 错误响应。 */
  private static <T> T withRuntimeTranslation(Supplier<T> operation) {
    try {
      return operation.get();
    } catch (HarnessRuntimeNotFoundException error) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, error.getMessage(), error);
    } catch (HarnessRuntimeConflictException error) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, error.getMessage(), error);
    } catch (IllegalArgumentException error) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
    }
  }
}
