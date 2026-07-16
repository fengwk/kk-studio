package fun.fengwk.kkstudio.core.comfyui.workflow_api.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.core.comfyui.workflow_api.service.model.ComfyuiWorkflowApi;
import fun.fengwk.kkstudio.core.comfyui.workflow_api.service.runtime.ComfyuiWorkflowApiBindings;
import fun.fengwk.kkstudio.core.comfyui.workflow_api.service.runtime.ComfyuiWorkflowApiBindingsParser;
import fun.fengwk.kkstudio.share.model.ComfyuiWorkflowApiCreateDTO;
import fun.fengwk.kkstudio.share.model.ComfyuiWorkflowApiUpdateDTO;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ComfyuiWorkflowApiMutationFactory 的聚焦行为测试。
 *
 * <p>这里使用一个无 Op 的 stub parser，避免对该切片尚未投入的运行时 Workflow 校验逻辑形成耦合；{@link
 * ComfyuiWorkflowApiBindingsParser} 的全面覆盖见其独立测试。
 *
 * @author fengwk
 */
public class ComfyuiWorkflowApiMutationFactoryTest {

  private static final String WORKFLOW_JSON = "{\"3\":{\"class_type\":\"KSampler\",\"inputs\":{}}}";
  private static final String BINDINGS_JSON =
      "[{\"name\":\"seed\",\"kind\":\"parameter\","
          + "\"nodeId\":\"3\",\"inputName\":\"seed\",\"valueType\":\"integer\",\"defaultValue\":42}]";

  @Test
  public void shouldNormalizeCreatePayloadAndAssignId() {
    ComfyuiWorkflowApiMutationFactory factory = newFactory();
    ComfyuiWorkflowApiCreateDTO createDTO = new ComfyuiWorkflowApiCreateDTO();
    createDTO.setApiName("  demo-flow  ");
    createDTO.setName("  Demo Flow  ");
    createDTO.setDescription("  desc  ");
    createDTO.setWorkflowJson("  " + WORKFLOW_JSON + "  ");
    createDTO.setInputBindingsJson("  " + BINDINGS_JSON + "  ");
    createDTO.setDefaultSelector(null);
    createDTO.setEnabled(true);

    ComfyuiWorkflowApiMutationFactory.Mutation mutation = factory.newCreateMutation(createDTO);
    ComfyuiWorkflowApi row = factory.newWorkflow(mutation);

    assertEquals("demo-flow", mutation.apiName());
    assertEquals("Demo Flow", mutation.name());
    assertEquals("desc", mutation.description());
    assertEquals(WORKFLOW_JSON, mutation.workflowJson());
    assertEquals(BINDINGS_JSON, mutation.inputBindingsJson());
    assertNull(mutation.defaultSelector());
    assertTrue(mutation.enabled());
    assertNotNull(row.getId());
    assertEquals("demo-flow", row.getApiName());
    assertEquals("Demo Flow", row.getName());
  }

  @Test
  public void shouldReuseCurrentApiNameWhenUpdateOmitsIt() {
    ComfyuiWorkflowApiMutationFactory factory = newFactory();
    ComfyuiWorkflowApiUpdateDTO updateDTO = new ComfyuiWorkflowApiUpdateDTO();
    updateDTO.setName("  New Name  ");
    updateDTO.setDescription(" ");
    updateDTO.setWorkflowJson(WORKFLOW_JSON);
    updateDTO.setInputBindingsJson("[]");
    updateDTO.setEnabled(Boolean.FALSE);

    ComfyuiWorkflowApiMutationFactory.Mutation mutation =
        factory.newUpdateMutation("demo-flow", updateDTO);
    assertEquals("demo-flow", mutation.apiName());
    assertEquals("New Name", mutation.name());
    assertNull(mutation.description());
    assertFalse(mutation.enabled());
  }

  @Test
  public void shouldRejectInvalidApiNameOnCreate() {
    ComfyuiWorkflowApiMutationFactory factory = newFactory();
    ComfyuiWorkflowApiCreateDTO createDTO = baseCreate();

    createDTO.setApiName("");
    assertThrows(IllegalArgumentException.class, () -> factory.newCreateMutation(createDTO));

    createDTO.setApiName("Demo-Flow");
    assertThrows(IllegalArgumentException.class, () -> factory.newCreateMutation(createDTO));

    createDTO.setApiName("-leading-dash");
    assertThrows(IllegalArgumentException.class, () -> factory.newCreateMutation(createDTO));

    createDTO.setApiName("a".repeat(65));
    assertThrows(IllegalArgumentException.class, () -> factory.newCreateMutation(createDTO));

    createDTO.setApiName("demo_flow");
    assertThrows(IllegalArgumentException.class, () -> factory.newCreateMutation(createDTO));
  }

  @Test
  public void shouldRejectMissingWorkflowOrBindings() {
    ComfyuiWorkflowApiMutationFactory factory = newFactory();
    ComfyuiWorkflowApiCreateDTO createDTO = baseCreate();
    createDTO.setWorkflowJson("   ");
    assertThrows(IllegalArgumentException.class, () -> factory.newCreateMutation(createDTO));

    createDTO.setWorkflowJson(WORKFLOW_JSON);
    createDTO.setInputBindingsJson(null);
    assertThrows(IllegalArgumentException.class, () -> factory.newCreateMutation(createDTO));
  }

  @Test
  public void shouldRejectNullBodyAndOversizeFields() {
    ComfyuiWorkflowApiMutationFactory factory = newFactory();
    assertThrows(IllegalArgumentException.class, () -> factory.newCreateMutation(null));
    assertThrows(IllegalArgumentException.class, () -> factory.newUpdateMutation("demo", null));

    ComfyuiWorkflowApiCreateDTO dto = baseCreate();
    dto.setName("");
    assertThrows(IllegalArgumentException.class, () -> factory.newCreateMutation(dto));

    dto.setName("a".repeat(ComfyuiWorkflowApiMutationFactory.MAX_NAME_LENGTH + 1));
    assertThrows(IllegalArgumentException.class, () -> factory.newCreateMutation(dto));

    dto.setName("ok");
    dto.setDescription("x".repeat(513));
    assertThrows(IllegalArgumentException.class, () -> factory.newCreateMutation(dto));
  }

  private static ComfyuiWorkflowApiCreateDTO baseCreate() {
    ComfyuiWorkflowApiCreateDTO createDTO = new ComfyuiWorkflowApiCreateDTO();
    createDTO.setApiName("demo-flow");
    createDTO.setName("Demo Flow");
    createDTO.setWorkflowJson(WORKFLOW_JSON);
    createDTO.setInputBindingsJson(BINDINGS_JSON);
    createDTO.setEnabled(Boolean.TRUE);
    return createDTO;
  }

  private static ComfyuiWorkflowApiMutationFactory newFactory() {
    return new ComfyuiWorkflowApiMutationFactory(new StubBindingsParser());
  }

  /** 校验只关心 mutation factory 自身的字段处理，不重复验证 binding parser。 */
  private static final class StubBindingsParser extends ComfyuiWorkflowApiBindingsParser {
    StubBindingsParser() {
      super(new ObjectMapper());
    }

    @Override
    public ComfyuiWorkflowApiBindings parse(
        String workflowJson, String inputBindingsJson, String defaultSelector) {
      return new ComfyuiWorkflowApiBindings(null, List.of(), defaultSelector);
    }
  }
}
