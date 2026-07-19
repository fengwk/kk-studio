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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.core.chat.service.ChatService;
import fun.fengwk.kkstudio.share.model.ChatCreateDTO;
import fun.fengwk.kkstudio.share.model.ChatDTO;
import fun.fengwk.kkstudio.share.model.ChatSessionAttachDTO;
import fun.fengwk.kkstudio.share.model.ChatUpdateDTO;
import fun.fengwk.kkstudio.share.model.HarnessSessionDTO;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Chat collection CRUD and Chat↔Session membership API.
 *
 * <p>所有路径 / DTO 边界上的 id 都是十进制字符串形式的 snowflake id。错误映射：malformed / non-positive id 与非法
 * defaultAgentId → 400；找不到 Chat / Session / membership → 404；其它状态冲突 → 409。
 *
 * <p>重复 attach 是幂等的（已关联则直接返回现有 Session）。Agent 删除后 Chat 可保留陈旧 defaultAgentId，不建立外键。
 */
@AllArgsConstructor
@RequestMapping("/api/chats")
@RestController
public class StudioChatController {

  private final ChatService chatService;

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
    try {
      return Results.ok(chatService.getChat(id));
    } catch (NoSuchElementException error) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, error.getMessage(), error);
    }
  }

  @PutMapping("/{id}")
  public Result<ChatDTO> updateChat(
      @PathVariable("id") String id, @RequestBody ChatUpdateDTO updateDTO) {
    try {
      return Results.ok(chatService.updateChat(id, updateDTO));
    } catch (NoSuchElementException error) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, error.getMessage(), error);
    }
  }

  @DeleteMapping("/{id}")
  public Result<Void> deleteChat(@PathVariable("id") String id) {
    try {
      chatService.deleteChat(id);
      return Results.noContent();
    } catch (NoSuchElementException error) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, error.getMessage(), error);
    } catch (IllegalStateException error) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, error.getMessage(), error);
    }
  }

  @GetMapping("/{id}/sessions")
  public Result<List<HarnessSessionDTO>> listSessions(@PathVariable("id") String id) {
    try {
      return Results.ok(chatService.listSessions(id));
    } catch (NoSuchElementException error) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, error.getMessage(), error);
    }
  }

  @PostMapping("/{id}/sessions")
  public Result<HarnessSessionDTO> attachSession(
      @PathVariable("id") String id, @RequestBody ChatSessionAttachDTO attachDTO) {
    if (attachDTO == null || attachDTO.getSessionId() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId must not be null");
    }
    try {
      return Results.created(chatService.attachSession(id, attachDTO.getSessionId()));
    } catch (NoSuchElementException error) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, error.getMessage(), error);
    } catch (IllegalStateException error) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, error.getMessage(), error);
    }
  }

  @DeleteMapping("/{id}/sessions/{sessionId}")
  public Result<Void> detachSession(
      @PathVariable("id") String id, @PathVariable("sessionId") String sessionId) {
    try {
      chatService.detachSession(id, sessionId);
      return Results.noContent();
    } catch (NoSuchElementException error) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, error.getMessage(), error);
    } catch (IllegalStateException error) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, error.getMessage(), error);
    }
  }
}
