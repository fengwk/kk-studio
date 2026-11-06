package fun.fengwk.kkstudio.platform.harness.http;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.SessionEntryPage;
import fun.fengwk.kkstudio.harness.api.session.Session;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessNameUpdateDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessSessionDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessSessionEntryDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessSessionEntryPageDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadSummaryDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessUsageCostDTO;
import fun.fengwk.kkstudio.platform.harness.thread.query.UsageCostProjectionService;
import fun.fengwk.kkstudio.platform.orchestration.HarnessOwnerQueryService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Runtime Session 的 Thread 摘要 / Entry tree 查询与 Session 名称重命名。 */
@RestController
@RequestMapping("/api/harness/sessions")
public class HarnessSessionController {

  private static final int DEFAULT_ENTRIES_LIMIT = 100;
  private static final int MAX_ENTRIES_LIMIT = 200;

  private final HarnessOwnerQueryService harnessQueryService;
  private final HarnessRuntime runtime;
  private final UsageCostProjectionService usageCostProjectionService;

  public HarnessSessionController(
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
  public HarnessSessionDTO rename(
      @PathVariable UUID sessionId, @RequestBody HarnessNameUpdateDTO request) {
    Session renamed =
        runtime.renameSession(
            HarnessRuntimeRequestMapper.toRenameSessionCommand(sessionId, request));
    return HarnessRuntimeResponseMapper.toSessionDto(renamed);
  }

  /** 返回 Session 下按 Runtime 确定性顺序排列的 Thread 摘要。 */
  @GetMapping("/{sessionId}/threads")
  public List<HarnessThreadSummaryDTO> listThreads(@PathVariable UUID sessionId) {
    return harnessQueryService.listThreadSummaries(sessionId);
  }

  /** 按存储创建顺序返回一页 Session Entry；父节点必先于子节点出现，每个 Entry 带上读取时费用投影。 */
  @GetMapping("/{sessionId}/entries")
  public HarnessSessionEntryPageDTO listEntries(
      @PathVariable UUID sessionId,
      @RequestParam(name = "cursor", required = false) UUID cursor,
      @RequestParam(name = "limit", required = false) Integer limit) {
    int pageLimit = limit == null ? DEFAULT_ENTRIES_LIMIT : limit;
    if (pageLimit < 1 || pageLimit > MAX_ENTRIES_LIMIT) {
      throw DomainException.validation("limit", "limit must be within [1, 200]");
    }
    SessionEntryPage page = harnessQueryService.listSessionEntries(sessionId, cursor, pageLimit);
    Map<UUID, HarnessUsageCostDTO> usageCosts = usageCostProjectionService.project(page.entries());
    List<HarnessSessionEntryDTO> items = new ArrayList<>(page.entries().size());
    for (Entry entry : page.entries()) {
      items.add(HarnessRuntimeResponseMapper.toEntryDto(entry, usageCosts.get(entry.id())));
    }
    HarnessSessionEntryPageDTO result = new HarnessSessionEntryPageDTO();
    result.setItems(items);
    result.setNextCursor(page.hasMore() ? page.entries().getLast().id().toString() : null);
    return result;
  }
}
