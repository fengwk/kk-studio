package fun.fengwk.kkstudio.core.comfyui.workflow_api.service.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.comfyui.workflow_api.repo.ComfyuiWorkflowApiRepository;
import fun.fengwk.kkstudio.core.comfyui.workflow_api.service.model.ComfyuiWorkflowApi;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link ComfyuiWorkflowApiLookupService} 的聚焦行为测试。
 *
 * <p>通过 stub 仓储驱动，验证：
 *
 * <ul>
 *   <li>apiName 找不到 / 空白 → 返回 {@code Optional.empty()}；
 *   <li>禁用卡片即使存在也必须被屏蔽；
 *   <li>启用卡片必须经过 binding parser，得到与原始 schema 一致的绑定模型。
 * </ul>
 *
 * @author fengwk
 */
public class ComfyuiWorkflowApiLookupServiceTest {

  private static final String WORKFLOW_JSON =
      "{\n"
          + "  \"3\": {\"class_type\":\"KSampler\",\"inputs\":{\"seed\":0,\"noise_seed\":1}}\n"
          + "}";

  private static final String BINDINGS_JSON =
      "[{\"name\":\"seed\","
          + "\"kind\":\"parameter\",\"nodeId\":\"3\",\"inputName\":\"seed\",\"valueType\":\"integer\","
          + "\"defaultValue\":7}]";

  @Test
  public void shouldReturnEmptyForBlankOrUnknownApiName() {
    StubRepository repo = new StubRepository();
    ComfyuiWorkflowApiLookupService lookup = newLookup(repo);

    assertFalse(lookup.findEnabledBindings(null).isPresent());
    assertFalse(lookup.findEnabledBindings("  ").isPresent());
    assertFalse(lookup.findEnabledBindings("missing").isPresent());
    assertEquals(1, repo.enabledCalls.get());
  }

  @Test
  public void shouldHideDisabledWorkflows() {
    StubRepository repo =
        new StubRepository().put("disabled-api", WORKFLOW_JSON, BINDINGS_JSON, false);
    ComfyuiWorkflowApiLookupService lookup = newLookup(repo);

    // 禁用卡片必须被屏蔽，runtime 调用方会按 404 处理。
    assertFalse(lookup.findEnabledBindings("disabled-api").isPresent());
    assertEquals(1, repo.enabledCalls.get());
  }

  @Test
  public void shouldParseEnabledWorkflowIntoBindings() {
    StubRepository repo =
        new StubRepository().put("enabled-api", WORKFLOW_JSON, BINDINGS_JSON, true);
    ComfyuiWorkflowApiLookupService lookup = newLookup(repo);

    Optional<ComfyuiWorkflowApiBindings> resolved = lookup.findEnabledBindings("enabled-api");
    assertTrue(resolved.isPresent());
    ComfyuiWorkflowApiBindings b = resolved.get();
    assertEquals(1, b.bindings().size());
    assertEquals("seed", b.bindings().get(0).name());
    assertEquals("enabled-api", repo.enabledLastApiName.get());
  }

  @Test
  public void shouldPropagateBindingParseFailures() {
    StubRepository repo = new StubRepository().put("enabled-api", "{bad}", "[]", true);
    ComfyuiWorkflowApiLookupService lookup = newLookup(repo);

    // 已经入库但绑定 JSON 不合规的卡片再次启用 lookup 会把校验错误抛回调用方。
    assertThrows(IllegalArgumentException.class, () -> lookup.findEnabledBindings("enabled-api"));
  }

  private static ComfyuiWorkflowApiLookupService newLookup(StubRepository repo) {
    return new ComfyuiWorkflowApiLookupService(
        repo, new ComfyuiWorkflowApiBindingsParser(new ObjectMapper()));
  }

  /** 最小可配置的仓储 stub：仅实现 lookup 关心的两个方法，其它方法不抛错即可。 */
  private static final class StubRepository implements ComfyuiWorkflowApiRepository {

    private final Map<String, ComfyuiWorkflowApi> byApiName = new LinkedHashMap<>();
    final AtomicReference<String> enabledLastApiName = new AtomicReference<>();
    final AtomicInteger enabledCalls = new AtomicInteger();

    StubRepository put(String apiName, String workflow, String bindings, boolean enabled) {
      ComfyuiWorkflowApi row = new ComfyuiWorkflowApi();
      row.setId(new UUID(0L, System.nanoTime()));
      row.setApiName(apiName);
      row.setName(apiName);
      row.setWorkflowJson(workflow);
      row.setInputBindingsJson(bindings);
      row.setEnabled(enabled);
      byApiName.put(apiName, row);
      return this;
    }

    @Override
    public ComfyuiWorkflowApi getEnabledByApiName(String apiName) {
      enabledCalls.incrementAndGet();
      enabledLastApiName.set(apiName);
      ComfyuiWorkflowApi row = byApiName.get(apiName);
      if (row == null || !Boolean.TRUE.equals(row.getEnabled())) {
        return null;
      }
      return row;
    }

    @Override
    public Page<ComfyuiWorkflowApi> page(PageQuery q) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ComfyuiWorkflowApi getById(UUID id) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ComfyuiWorkflowApi getByApiName(String apiName) {
      return byApiName.get(apiName);
    }

    @Override
    public boolean create(ComfyuiWorkflowApi row) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean updateById(ComfyuiWorkflowApi row) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean deleteById(UUID id) {
      throw new UnsupportedOperationException();
    }
  }
}
