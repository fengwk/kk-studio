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

import fun.fengwk.kkstudio.core.ai.chat.service.ChatService;
import fun.fengwk.kkstudio.core.ai.chat.service.ChatThreadService;
import fun.fengwk.kkstudio.share.ai.chat.ChatCreateDTO;
import fun.fengwk.kkstudio.share.ai.chat.ChatDTO;
import fun.fengwk.kkstudio.share.ai.chat.ChatUpdateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.api.CursorPageDTO;

import java.util.List;
import java.util.function.Supplier;

/**
 * Chat collection CRUD API.
 *
 * <p>所有路径 / DTO 边界上的 id 都是正的十进制字符串（PostgreSQL sequence）。PUT 请求体与 DELETE 查询参数都要求必填的非负十进制字符串 {@code
 * expectedVersion}；stale 版本由中央 {@code RestControllerAdvice} 翻译为 HTTP 409，缺失资源为 404，其它失败为 400。
 */
@AllArgsConstructor
@RequestMapping("/api/ai/chat")
@RestController
public class StudioChatController {

  private final ChatService chatService;
  private final ChatThreadService chatThreadService;

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

  @GetMapping("/{chatId}/threads")
  public Result<CursorPageDTO<HarnessThreadDTO>> listChatThreads(
      @PathVariable String chatId,
      @RequestParam(defaultValue = "recent") String sort,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "20") Integer limit) {
    return Results.ok(
        withRequestValidation(() -> chatThreadService.listThreads(chatId, sort, cursor, limit)));
  }

  @PostMapping("/{chatId}/threads")
  public Result<HarnessThreadDTO> createChatThread(@PathVariable String chatId) {
    return Results.created(chatThreadService.createThread(chatId));
  }

  @PutMapping("/{chatId}/threads/{threadId}")
  public Result<Void> associateThread(@PathVariable String chatId, @PathVariable String threadId) {
    chatThreadService.associateThread(chatId, threadId);
    return Results.noContent();
  }

  private static <T> T withRequestValidation(Supplier<T> operation) {
    try {
      return operation.get();
    } catch (IllegalArgumentException error) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
    }
  }
}
