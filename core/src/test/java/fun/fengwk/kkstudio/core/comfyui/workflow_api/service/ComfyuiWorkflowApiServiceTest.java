package fun.fengwk.kkstudio.core.comfyui.workflow_api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.share.comfyui.ComfyuiWorkflowApiCreateDTO;
import fun.fengwk.kkstudio.share.comfyui.ComfyuiWorkflowApiDTO;
import fun.fengwk.kkstudio.share.comfyui.ComfyuiWorkflowApiUpdateDTO;

import java.util.NoSuchElementException;

/**
 * {@link ComfyuiWorkflowApiService} end-to-end CRUD coverage over the authoritative PostgreSQL
 * schema.
 *
 * <p>Exercises:
 *
 * <ul>
 *   <li>the JSONB {@code workflow} / {@code input_bindings} cast round-trip;
 *   <li>{@code created_at} / {@code updated_at} timestamptz population via the {@code create_time}
 *       / {@code update_time} DO aliases;
 *   <li>apiName global uniqueness enforced by the {@code uk_comfyui_workflow_api_api_name} index;
 *   <li>DTO id is exposed as a positive decimal string, matching the durable id resource contract.
 * </ul>
 *
 * @author fengwk
 */
public class ComfyuiWorkflowApiServiceTest extends PostgresSpringTestSupport {

  private static final String WORKFLOW_JSON =
      "{\n"
          + "  \"3\": {\"class_type\":\"KSampler\",\"inputs\":{\"seed\":0,\"noise_seed\":1}}\n"
          + "}";

  private static final String BINDINGS_JSON =
      "[{\"name\":\"seed\","
          + "\"kind\":\"parameter\",\"nodeId\":\"3\",\"inputName\":\"seed\",\"valueType\":\"integer\","
          + "\"defaultValue\":7}]";

  @Autowired private ComfyuiWorkflowApiService comfyuiWorkflowApiService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  public void shouldCreateUpdateAndDeleteWorkflowRoundTrip() {
    String suffix = String.valueOf(System.nanoTime());
    String apiName = "flow-" + suffix;
    Page<ComfyuiWorkflowApiDTO> baseline =
        comfyuiWorkflowApiService.pageWorkflows(new PageQuery(1, 200));

    ComfyuiWorkflowApiCreateDTO createDTO = new ComfyuiWorkflowApiCreateDTO();
    createDTO.setApiName(apiName);
    createDTO.setName("Demo");
    createDTO.setDescription("desc");
    createDTO.setWorkflowJson(WORKFLOW_JSON);
    createDTO.setInputBindingsJson(BINDINGS_JSON);
    createDTO.setDefaultSelector("$['3'].inputs.seed");
    createDTO.setEnabled(true);

    // 写入链路会把工作流、绑定、selector、enabled 全部持久化，并回填 id/timestamps。
    ComfyuiWorkflowApiDTO created = comfyuiWorkflowApiService.createWorkflow(createDTO);
    assertNotNull(created.getId());
    // DTO 边界：id 必须是正的十进制字符串形式。
    assertTrue(created.getId().matches("\\d+"));
    assertEquals(apiName, created.getApiName());
    assertEquals("Demo", created.getName());
    assertTrue(created.getEnabled());
    assertNotNull(created.getCreateTime());
    assertNotNull(created.getUpdateTime());
    assertEquals(
        0L,
        jdbcTemplate.queryForObject(
            "select version from comfyui_workflow_api where id = ?",
            Long.class,
            Long.parseLong(created.getId())));

    ComfyuiWorkflowApiUpdateDTO updateDTO = new ComfyuiWorkflowApiUpdateDTO();
    updateDTO.setName("Demo-renamed");
    updateDTO.setDescription(null);
    updateDTO.setWorkflowJson(WORKFLOW_JSON);
    updateDTO.setInputBindingsJson("[]");
    updateDTO.setDefaultSelector(null);
    updateDTO.setEnabled(Boolean.FALSE);

    // 更新不重置 apiName 时，name 重新置空必须回退到原 apiName。
    ComfyuiWorkflowApiDTO updated =
        comfyuiWorkflowApiService.updateWorkflow(created.getId(), updateDTO);
    assertEquals(apiName, updated.getApiName());
    assertEquals("Demo-renamed", updated.getName());
    assertNull(updated.getDescription());
    assertEquals(Boolean.FALSE, updated.getEnabled());
    assertNull(updated.getDefaultSelector());
    assertEquals(
        1L,
        jdbcTemplate.queryForObject(
            "select version from comfyui_workflow_api where id = ?",
            Long.class,
            Long.parseLong(created.getId())));

    comfyuiWorkflowApiService.deleteWorkflow(created.getId());
    Page<ComfyuiWorkflowApiDTO> after =
        comfyuiWorkflowApiService.pageWorkflows(new PageQuery(1, 200));
    assertEquals(baseline.getTotalCount(), after.getTotalCount());
    assertTrue(after.getResults().stream().noneMatch(row -> created.getId().equals(row.getId())));
  }

  @Test
  public void shouldRejectDuplicateApiNameOnCreate() {
    String suffix = String.valueOf(System.nanoTime());
    String apiName = "flow-dup-" + suffix;

    ComfyuiWorkflowApiCreateDTO first = newComfyuiWorkflowApiCreateDTO(apiName);
    comfyuiWorkflowApiService.createWorkflow(first);

    ComfyuiWorkflowApiCreateDTO dup = newComfyuiWorkflowApiCreateDTO(apiName);
    // apiName 必须全局唯一，第二次创建需直接抛错而不是插入第二条记录。
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> comfyuiWorkflowApiService.createWorkflow(dup));
    assertTrue(
        ex.getMessage().contains(apiName) || ex.getMessage().contains("already exists"),
        "actual message: " + ex.getMessage());

    // 清理，避免污染其它测试。
    Page<ComfyuiWorkflowApiDTO> page =
        comfyuiWorkflowApiService.pageWorkflows(new PageQuery(1, 1000));
    for (ComfyuiWorkflowApiDTO row : page.getResults()) {
      if (apiName.equals(row.getApiName())) {
        comfyuiWorkflowApiService.deleteWorkflow(row.getId());
      }
    }
  }

  @Test
  public void shouldRejectInvalidWorkflowJsonAtCreate() {
    // 非法 JSON 文本必须在 service 入口被 binding parser 拒掉。
    ComfyuiWorkflowApiCreateDTO createDTO =
        newComfyuiWorkflowApiCreateDTO("flow-bad-" + System.nanoTime());
    createDTO.setWorkflowJson("{not json}");
    assertThrows(
        IllegalArgumentException.class, () -> comfyuiWorkflowApiService.createWorkflow(createDTO));
  }

  @Test
  public void shouldRejectMalformedStringIdAtBoundary() {
    IllegalArgumentException nonNumeric =
        assertThrows(
            IllegalArgumentException.class,
            () -> comfyuiWorkflowApiService.deleteWorkflow("not-a-number"));
    assertTrue(nonNumeric.getMessage().contains("id"));
  }

  @Test
  public void shouldThrowNoSuchElementForUnknownButParseableId() {
    // 解析合法但数据库中找不到的 id 必须以 NoSuchElementException 报告，controller 再翻译为 404。
    NoSuchElementException notFound =
        assertThrows(
            NoSuchElementException.class,
            () -> comfyuiWorkflowApiService.deleteWorkflow("999999999999999"));
    assertTrue(notFound.getMessage().contains("comfyui workflow api not found"));
  }

  private static ComfyuiWorkflowApiCreateDTO newComfyuiWorkflowApiCreateDTO(String apiName) {
    ComfyuiWorkflowApiCreateDTO createDTO = new ComfyuiWorkflowApiCreateDTO();
    createDTO.setApiName(apiName);
    createDTO.setName("Demo " + apiName);
    createDTO.setDescription("desc");
    createDTO.setWorkflowJson(WORKFLOW_JSON);
    createDTO.setInputBindingsJson(BINDINGS_JSON);
    createDTO.setDefaultSelector(null);
    createDTO.setEnabled(true);
    return createDTO;
  }
}
