package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.platform.studio.StudioHarnessQueryService;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionEntryDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadSummaryDTO;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapper;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeResponseMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** Runtime Session 的只读 Thread 摘要与完整 Entry tree 查询。 */
@RestController
@RequestMapping("/api/ai/runtime/sessions")
public class StudioHarnessSessionController {

  private final StudioHarnessQueryService harnessQueryService;

  public StudioHarnessSessionController(StudioHarnessQueryService harnessQueryService) {
    this.harnessQueryService = harnessQueryService;
  }

  /** 返回 Session 下按 Runtime 确定性顺序排列的 Thread 摘要。 */
  @GetMapping("/{sessionId}/threads")
  public Result<List<HarnessThreadSummaryDTO>> listThreads(@PathVariable String sessionId) {
    return Results.ok(
        withRuntimeTranslation(
            () -> {
              UUID id = HarnessRuntimeRequestMapper.parseUuid(sessionId, "sessionId");
              return harnessQueryService.listThreadSummaries(id);
            }));
  }

  /** 返回 Session 的完整 Entry tree；每个 Entry 通过 parentEntryId 连接到父节点。 */
  @GetMapping("/{sessionId}/entries")
  public Result<List<HarnessSessionEntryDTO>> listEntries(@PathVariable String sessionId) {
    return Results.ok(
        withRuntimeTranslation(
            () -> {
              UUID id = HarnessRuntimeRequestMapper.parseUuid(sessionId, "sessionId");
              List<Entry> entries = harnessQueryService.listSessionEntries(id);
              List<HarnessSessionEntryDTO> mapped = new ArrayList<>(entries.size());
              for (Entry entry : entries) {
                mapped.add(HarnessRuntimeResponseMapper.toEntryDto(entry));
              }
              return List.copyOf(mapped);
            }));
  }

  /** 将 Runtime 查询异常翻译为统一 HTTP 错误响应。 */
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
