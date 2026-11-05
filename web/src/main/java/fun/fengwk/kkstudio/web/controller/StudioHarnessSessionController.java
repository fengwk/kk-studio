package fun.fengwk.kkstudio.web.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.common.id.Uuids;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.SessionEntryPage;
import fun.fengwk.kkstudio.harness.api.session.Session;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.platform.harness.thread.query.UsageCostProjectionService;
import fun.fengwk.kkstudio.platform.http.ApiResponse;
import fun.fengwk.kkstudio.platform.http.RequestParameters;
import fun.fengwk.kkstudio.platform.orchestration.HarnessOwnerQueryService;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessNameUpdateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionEntryDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionEntryPageDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadSummaryDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessUsageCostDTO;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeRequestMapper;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeResponseMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Runtime Session 的 Thread 摘要 / Entry tree 查询与 Session 名称重命名。 */
@RestController
@RequestMapping("/api/sessions")
public class StudioHarnessSessionController {

  private static final int DEFAULT_ENTRIES_LIMIT = 100;
  private static final int MAX_ENTRIES_LIMIT = 200;

  private final HarnessOwnerQueryService harnessQueryService;
  private final HarnessRuntime runtime;
  private final UsageCostProjectionService usageCostProjectionService;

  public StudioHarnessSessionController(
      HarnessOwnerQueryService harnessQueryService,
      HarnessRuntime runtime,
      UsageCostProjectionService usageCostProjectionService) {
    this.harnessQueryService = Objects.requireNonNull(harnessQueryService, "harnessQueryService");
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.usageCostProjectionService =
        Objects.requireNonNull(usageCostProjectionService, "usageCostProjectionService");
  }

  /** 重命名 Session（name 由 Core 权威规范化；同名 no-op），返回最新权威 Session DTO。 */
  @PutMapping("/{sessionId}/name")
  public ApiResponse<HarnessSessionDTO> rename(
      @PathVariable String sessionId, @RequestBody HarnessNameUpdateDTO request) {
    return ApiResponse.ok(
        RuntimeErrors.translate(
            () -> {
              Session renamed =
                  runtime.renameSession(
                      HarnessRuntimeRequestMapper.toRenameSessionCommand(sessionId, request));
              return HarnessRuntimeResponseMapper.toSessionDto(renamed);
            }));
  }

  /** 返回 Session 下按 Runtime 确定性顺序排列的 Thread 摘要。 */
  @GetMapping("/{sessionId}/threads")
  public ApiResponse<List<HarnessThreadSummaryDTO>> listThreads(@PathVariable String sessionId) {
    return ApiResponse.ok(
        RuntimeErrors.translate(
            () -> {
              UUID id = Uuids.parseCanonical(sessionId, "sessionId");
              return harnessQueryService.listThreadSummaries(id);
            }));
  }

  /** 按存储创建顺序返回一页 Session Entry；父节点必先于子节点出现，每个 Entry 带上读取时费用投影。 */
  @GetMapping("/{sessionId}/entries")
  public ApiResponse<HarnessSessionEntryPageDTO> listEntries(
      @PathVariable String sessionId,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit) {
    return ApiResponse.ok(
        RuntimeErrors.translate(
            () -> {
              UUID id = Uuids.parseCanonical(sessionId, "sessionId");
              UUID afterEntryId = cursor == null ? null : Uuids.parseCanonical(cursor, "cursor");
              SessionEntryPage page =
                  harnessQueryService.listSessionEntries(
                      id,
                      afterEntryId,
                      RequestParameters.limit(limit, DEFAULT_ENTRIES_LIMIT, MAX_ENTRIES_LIMIT));
              Map<UUID, HarnessUsageCostDTO> usageCosts =
                  usageCostProjectionService.project(page.entries());
              List<HarnessSessionEntryDTO> items = new ArrayList<>(page.entries().size());
              for (Entry entry : page.entries()) {
                items.add(
                    HarnessRuntimeResponseMapper.toEntryDto(entry, usageCosts.get(entry.id())));
              }
              HarnessSessionEntryPageDTO result = new HarnessSessionEntryPageDTO();
              result.setItems(items);
              result.setNextCursor(
                  page.hasMore() ? page.entries().getLast().id().toString() : null);
              return result;
            }));
  }
}
