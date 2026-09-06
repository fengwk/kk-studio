package fun.fengwk.kkstudio.platform.comfyui.workflow_api.service.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.comfyui.workflow_api.repo.ComfyuiWorkflowApiRepository;
import fun.fengwk.kkstudio.platform.comfyui.workflow_api.service.model.ComfyuiWorkflowApi;

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
 *   <li>workflowId 为空或找不到 → 返回 {@code Optional.empty()}；
 *   <li>禁用卡片即使存在也必须被屏蔽（返回 {@code Optional.empty()}）；
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

  /** 测试意图：workflowId 为 null 或仓储中不存在时，返回 Optional.empty()。 */
  @Test
  public void shouldReturnEmptyForNullOrUnknownWorkflowId() {
    StubRepository repo = new StubRepository();
    ComfyuiWorkflowApiLookupService lookup = newLookup(repo);

    assertFalse(lookup.findEnabledBindings((UUID) null).isPresent());
    assertFalse(lookup.findEnabledBindings(UUID.randomUUID()).isPresent());
    assertEquals(1, repo.getByIdCalls.get());
  }

  /** 测试意图：即使卡片存在，只要 enabled 为 false，lookup 必须屏蔽并返回 Optional.empty()。 */
  @Test
  public void shouldHideDisabledWorkflows() {
    UUID id = UUID.randomUUID();
    StubRepository repo =
        new StubRepository().put(id, "disabled-api", WORKFLOW_JSON, BINDINGS_JSON, false);
    ComfyuiWorkflowApiLookupService lookup = newLookup(repo);

    assertFalse(lookup.findEnabledBindings(id).isPresent());
    assertEquals(1, repo.getByIdCalls.get());
  }

  /** 测试意图：卡片存在且 enabled 为 true 时，返回解析好的绑定模型。 */
  @Test
  public void shouldParseEnabledWorkflowIntoBindings() {
    UUID id = UUID.randomUUID();
    StubRepository repo =
        new StubRepository().put(id, "enabled-api", WORKFLOW_JSON, BINDINGS_JSON, true);
    ComfyuiWorkflowApiLookupService lookup = newLookup(repo);

    Optional<ComfyuiWorkflowApiBindings> resolved = lookup.findEnabledBindings(id);
    assertTrue(resolved.isPresent());
    ComfyuiWorkflowApiBindings b = resolved.get();
    assertEquals(1, b.bindings().size());
    assertEquals("seed", b.bindings().get(0).name());
    assertEquals(id, repo.lastLookupId.get());
  }

  /** 测试意图：绑定 JSON 不合法时，向上抛出 IllegalArgumentException。 */
  @Test
  public void shouldPropagateBindingParseFailures() {
    UUID id = UUID.randomUUID();
    StubRepository repo = new StubRepository().put(id, "enabled-api", "{bad}", "[]", true);
    ComfyuiWorkflowApiLookupService lookup = newLookup(repo);

    assertThrows(IllegalArgumentException.class, () -> lookup.findEnabledBindings(id));
  }

  private static ComfyuiWorkflowApiLookupService newLookup(StubRepository repo) {
    return new ComfyuiWorkflowApiLookupService(
        repo, new ComfyuiWorkflowApiBindingsParser(new ObjectMapper()));
  }

  /** 最小可配置的仓储 stub。 */
  private static final class StubRepository implements ComfyuiWorkflowApiRepository {

    private final Map<UUID, ComfyuiWorkflowApi> byId = new LinkedHashMap<>();
    final AtomicReference<UUID> lastLookupId = new AtomicReference<>();
    final AtomicInteger getByIdCalls = new AtomicInteger();

    StubRepository put(UUID id, String apiName, String workflow, String bindings, boolean enabled) {
      ComfyuiWorkflowApi row = new ComfyuiWorkflowApi();
      row.setId(id);
      row.setApiName(apiName);
      row.setName(apiName);
      row.setWorkflowJson(workflow);
      row.setInputBindingsJson(bindings);
      row.setEnabled(enabled);
      byId.put(id, row);
      return this;
    }

    @Override
    public ComfyuiWorkflowApi getById(UUID id) {
      getByIdCalls.incrementAndGet();
      lastLookupId.set(id);
      return byId.get(id);
    }

    @Override
    public Page<ComfyuiWorkflowApi> page(PageQuery q) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ComfyuiWorkflowApi getByApiName(String apiName) {
      throw new UnsupportedOperationException();
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
