package fun.fengwk.kkstudio.harness.runtime.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.codec.ModelInvocationRequestJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.codec.ProviderRequestJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.skill.SkillBinding;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ReconcileTestSupport;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.codec.ToolDescriptorJsonCodec;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

class ModelInvocationRequestTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ToolDescriptorJsonCodec TOOL_CODEC = new ToolDescriptorJsonCodec();

  @Test
  void roundTripsAnExactProviderToolProjection() {
    ModelInvocationRequest request = request();

    assertEquals(
        request,
        new ModelInvocationRequestJsonCodec()
            .decode(new ModelInvocationRequestJsonCodec().encode(request)));
    assertEquals(
        request.providerRequest(),
        new ProviderRequestJsonCodec()
            .decode(new ProviderRequestJsonCodec().encode(request.providerRequest())));
    assertEquals("environment-tools", request.toolBindings().getFirst().environmentName());
    assertEquals(
        new SkillBinding("research", "Research facts", "environment-skills"),
        request.skillBindings().getFirst());
    assertEquals(true, request.yoloEnabled());
  }

  @Test
  void rejectsMissingExtraDuplicateAndMismatchedProviderTools() {
    ToolBinding binding = binding("lookup", "Look up facts");
    ProviderRequest base = providerRequest(List.of());
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModelInvocationRequest(base, List.of(binding), List.of(), false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelInvocationRequest(
                providerRequest(List.of(providerTool(binding), providerTool(binding))),
                List.of(binding),
                List.of(),
                false));

    ProviderToolDefinition wrongDescription =
        new ProviderToolDefinition(
            "lookup",
            "different",
            TOOL_CODEC.encodeInputSchema(binding.descriptor().inputSchema()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ModelInvocationRequest(
                providerRequest(List.of(wrongDescription)), List.of(binding), List.of(), false));
  }

  @Test
  void codecRejectsAChangedDurableProviderToolProjection() throws Exception {
    ModelInvocationRequest request = request();
    ModelInvocationRequestJsonCodec codec = new ModelInvocationRequestJsonCodec();
    var root = JSON.readTree(codec.encode(request));
    ((ObjectNode) root.path("providerRequest").path("tools").get(0)).put("description", "changed");

    assertThrows(IllegalArgumentException.class, () -> codec.decode(JSON.writeValueAsString(root)));
  }

  private static ModelInvocationRequest request() {
    ToolBinding binding =
        ToolBinding.of(
            environmentDescriptor("lookup", "Look up facts"),
            ToolType.ENVIRONMENT,
            "environment-tools");
    SkillBinding skill = new SkillBinding("research", "Research facts", "environment-skills");
    return new ModelInvocationRequest(
        providerRequest(List.of(providerTool(binding))), List.of(binding), List.of(skill), true);
  }

  private static ProviderRequest providerRequest(List<ProviderToolDefinition> tools) {
    ProviderRequest base = ReconcileTestSupport.providerRequest();
    return new ProviderRequest(
        base.model(), base.variant(), base.messages(), tools, base.cacheControl());
  }

  private static ProviderToolDefinition providerTool(ToolBinding binding) {
    return new ProviderToolDefinition(
        binding.descriptor().name(),
        binding.descriptor().description(),
        TOOL_CODEC.encodeInputSchema(binding.descriptor().inputSchema()));
  }

  private static ToolBinding binding(String name, String description) {
    return ToolBinding.of(platformDescriptor(name, description));
  }

  private static ToolDescriptor platformDescriptor(String name, String description) {
    return descriptor(name, description, ToolType.PLATFORM);
  }

  private static ToolDescriptor environmentDescriptor(String name, String description) {
    return descriptor(name, description, ToolType.ENVIRONMENT);
  }

  private static ToolDescriptor descriptor(String name, String description, ToolType type) {
    return new ToolDescriptor(
        name,
        "1",
        type,
        description,
        name,
        new ToolParamsSchema(
            "lookup input", Map.of("query", new ToolStringSchema("query")), Set.of("query"), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(1));
  }
}
