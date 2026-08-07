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
import fun.fengwk.kkstudio.harness.runtime.CreatedThread;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.share.ai.chat.ChatCreateDTO;
import fun.fengwk.kkstudio.share.ai.chat.ChatDTO;
import fun.fengwk.kkstudio.share.ai.chat.ChatUpdateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCreateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadSnapshotDTO;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeWebMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Chat 集合 CRUD 与 Chat 作用域内 Thread 关联 API。
 *
 * <p>所有路径 / DTO 边界上的 id 都是正的十进制字符串（PostgreSQL sequence）。Chat Thread 列表、创建与 关联由本 Controller 编排
 * {@link HarnessRuntime} 与 {@link ChatThreadService}：创建先调用 {@code runtime.createThread} 再关联；关联前通过
 * {@code runtime.getThreadSnapshot} 校验 Thread 快照存在。 Runtime rejections 翻译为 404/409，非法请求为 400。
 */
@AllArgsConstructor
@RequestMapping("/api/ai/chat")
@RestController
public class StudioChatController {

  private final ChatService chatService;
  private final ChatThreadService chatThreadService;
  private final HarnessRuntime runtime;

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

  /** 返回该 Chat 关联的全部 Thread（按关联时间从新到旧），由一致快照映射。 */
  @GetMapping("/{chatId}/threads")
  public Result<List<HarnessThreadDTO>> listChatThreads(@PathVariable String chatId) {
    List<HarnessThreadDTO> threads =
        withRuntimeTranslation(
            () -> {
              List<Long> threadIds = chatThreadService.listThreadIds(chatId);
              List<HarnessThreadDTO> mapped = new ArrayList<>(threadIds.size());
              for (long threadId : threadIds) {
                ThreadSnapshot snapshot = runtime.getThreadSnapshot(threadId);
                mapped.add(HarnessRuntimeWebMapper.toThreadDto(snapshot));
              }
              return List.copyOf(mapped);
            });
    return Results.ok(threads);
  }

  /** 以完整 branch settings 原子创建 Thread，关联到 Chat 后返回创建快照。 */
  @PostMapping("/{chatId}/threads")
  public Result<HarnessThreadSnapshotDTO> createChatThread(
      @PathVariable String chatId, @RequestBody HarnessThreadCreateDTO createDTO) {
    HarnessThreadSnapshotDTO snapshot =
        withRuntimeTranslation(
            () -> {
              chatThreadService.requireChat(chatId);
              CreatedThread created =
                  runtime.createThread(HarnessRuntimeWebMapper.toCreateThreadCommand(createDTO));
              long threadId = created.thread().id();
              chatThreadService.associateThread(chatId, threadId);
              return HarnessRuntimeWebMapper.toSnapshotDto(runtime.getThreadSnapshot(threadId));
            });
    return Results.created(snapshot);
  }

  /** 校验 Thread 快照存在后幂等关联到 Chat。 */
  @PutMapping("/{chatId}/threads/{threadId}")
  public Result<Void> associateThread(@PathVariable String chatId, @PathVariable String threadId) {
    withRuntimeTranslation(
        () -> {
          long id = HarnessRuntimeWebMapper.parsePositiveId(threadId, "threadId");
          runtime.getThreadSnapshot(id);
          chatThreadService.associateThread(chatId, id);
          return null;
        });
    return Results.noContent();
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
