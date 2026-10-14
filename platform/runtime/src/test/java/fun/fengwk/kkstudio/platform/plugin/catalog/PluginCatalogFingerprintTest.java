package fun.fengwk.kkstudio.platform.plugin.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionOutputSpec;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.harness.common.schema.BooleanSchema;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.IntegerSchema;
import fun.fengwk.kkstudio.harness.common.schema.ObjectSchema;
import fun.fengwk.kkstudio.harness.common.schema.SchemaElement;
import fun.fengwk.kkstudio.harness.contributor.api.ContextProjector;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorDescriptor;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.harness.contributor.api.StateDeclaration;
import fun.fengwk.kkstudio.harness.contributor.api.StateMode;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigSchema;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@link PluginCatalogFingerprint} 的确定性契约测试。
 *
 * <p>覆盖指纹格式门禁、对象键顺序（含 DB jsonb 回读后的乱序键）不改变指纹、集合顺序不改变目录指纹，以及 schema / secrets / authSpec 实际变化必须改变指纹。
 */
class PluginCatalogFingerprintTest {

  private static final PluginAuthSpec NONE = new PluginAuthSpec.None();
  private static final String OWNER = "owner.contrib";
  private static final String UNRELATED = "unrelated.contrib";
  private static final UUID ENVIRONMENT_UUID =
      UUID.fromString("11111111-2222-3333-4444-555555555555");

  @Test
  void isFingerprintAcceptsOnlyLowercaseSha256() {
    assertTrue(PluginCatalogFingerprint.isFingerprint("0".repeat(64)));
    assertTrue(PluginCatalogFingerprint.isFingerprint("abcdef0123456789".repeat(4)));
    assertFalse(PluginCatalogFingerprint.isFingerprint(null));
    assertFalse(PluginCatalogFingerprint.isFingerprint("A".repeat(64)));
    assertFalse(PluginCatalogFingerprint.isFingerprint("0".repeat(63)));
    assertFalse(PluginCatalogFingerprint.isFingerprint("0".repeat(65)));
    assertFalse(PluginCatalogFingerprint.isFingerprint("g".repeat(64)));
  }

  /** 属性、嵌套属性与默认值的键顺序（含 jsonb 回读乱序）都不改变 schema 指纹。 */
  @Test
  void schemaFingerprintIgnoresPropertyAndDefaultKeyOrder() {
    String first =
        PluginCatalogFingerprint.schema(
            schema(valuesSchema(true), "{\"flag\":true,\"outer\":{\"left\":1,\"right\":2}}"), NONE);
    String permuted =
        PluginCatalogFingerprint.schema(
            schema(valuesSchema(false), "{\"outer\":{\"right\":2,\"left\":1},\"flag\":true}"),
            NONE);

    assertTrue(PluginCatalogFingerprint.isFingerprint(first));
    assertEquals(first, permuted);
  }

  /** 秘密字段声明与认证形态必须参与 Schema 指纹。 */
  @Test
  void schemaFingerprintCoversSecretsAndAuthSpec() {
    PluginConfigSchema schema = declaredSecretsSchema();
    String baseline = PluginCatalogFingerprint.schema(schema, NONE);
    String staticKeyFingerprint =
        PluginCatalogFingerprint.schema(
            declaredSecretsSchema(), new PluginAuthSpec.StaticKey("token", Set.of()));

    assertNotEquals(baseline, staticKeyFingerprint, "authSpec must participate in the fingerprint");
    assertEquals(
        staticKeyFingerprint,
        PluginCatalogFingerprint.schema(
            declaredSecretsSchema(), new PluginAuthSpec.StaticKey("token", Set.of())));

    String otherSecretDescription =
        PluginCatalogFingerprint.schema(
            new PluginConfigSchema(
                valuesSchema(true), "{}", linkedSecrets(Map.of("token", "another description"))),
            NONE);
    assertNotEquals(baseline, otherSecretDescription, "secret description must be fingerprinted");

    String deepLink =
        PluginCatalogFingerprint.schema(schema, new PluginAuthSpec.DeepLink(List.of("cn", "us")));
    assertNotEquals(baseline, deepLink);

    String withContext =
        PluginCatalogFingerprint.schema(
            declaredSecretsSchema(), new PluginAuthSpec.StaticKey("token", Set.of("region")));
    assertNotEquals(
        staticKeyFingerprint, withContext, "static key context fields must be fingerprinted");
  }

  /** values schema 与默认值的内容变化必须改变指纹。 */
  @Test
  void schemaFingerprintChangesWithSchemaOrDefaults() {
    String baseline =
        PluginCatalogFingerprint.schema(
            schema(valuesSchema(true), "{\"flag\":true,\"outer\":{\"left\":1,\"right\":2}}"), NONE);
    String otherDefaults =
        PluginCatalogFingerprint.schema(
            schema(valuesSchema(true), "{\"flag\":false,\"outer\":{\"left\":1,\"right\":2}}"),
            NONE);
    InputSchema original = valuesSchema(true);
    InputSchema changedDescription =
        new InputSchema(
            "changed schema description",
            original.properties(),
            original.required(),
            original.additionalProperties());
    String otherSchema =
        PluginCatalogFingerprint.schema(
            schema(changedDescription, "{\"flag\":true,\"outer\":{\"left\":1,\"right\":2}}"), NONE);

    assertNotEquals(baseline, otherDefaults);
    assertNotEquals(otherDefaults, otherSchema);
    assertNotEquals(baseline, otherSchema);
  }

  /** 目录指纹对 contributor 集合顺序不敏感，对真实内容变化敏感。 */
  @Test
  void catalogFingerprintIgnoresContributorOrder() {
    HarnessContributor first = contributor("first.contrib", Set.of());
    HarnessContributor second = contributor("second.contrib", Set.of("first.contrib"));
    CanvasFunctionCatalog canvas = CanvasFunctionCatalog.from(List.of());

    String forward =
        PluginCatalogFingerprint.catalog(HarnessCatalog.from(List.of(first, second)), canvas);
    String reversed =
        PluginCatalogFingerprint.catalog(HarnessCatalog.from(List.of(second, first)), canvas);
    String changed =
        PluginCatalogFingerprint.catalog(
            HarnessCatalog.from(List.of(contributor("second.contrib", Set.of()), first)), canvas);

    assertEquals(forward, reversed);
    assertNotEquals(forward, changed, "requires must participate in the catalog fingerprint");
  }

  /** 能力指纹只覆盖该插件实际拥有的贡献：无辜的额外 contributor 不改变它。 */
  @Test
  void capabilityFingerprintScopesToOwnedContributions() {
    HarnessContributor owned = contributor("owned.contrib", Set.of());
    HarnessContributor builtin = contributor("builtin.contrib", Set.of());
    CanvasFunctionCatalog canvas = CanvasFunctionCatalog.from(List.of());

    String alone =
        PluginCatalogFingerprint.capability(
            HarnessCatalog.from(List.of(owned)),
            canvas,
            Set.of(new ContributorId("owned.contrib")),
            Set.of());
    String withBuiltin =
        PluginCatalogFingerprint.capability(
            HarnessCatalog.from(List.of(builtin, owned)),
            canvas,
            Set.of(new ContributorId("owned.contrib")),
            Set.of());
    String withBuiltinOwned =
        PluginCatalogFingerprint.capability(
            HarnessCatalog.from(List.of(builtin, owned)),
            canvas,
            Set.of(new ContributorId("owned.contrib"), new ContributorId("builtin.contrib")),
            Set.of());

    assertEquals(alone, withBuiltin);
    assertNotEquals(alone, withBuiltinOwned);
  }

  /** 目录指纹必须覆盖真实目录的全部元数据：工具前置需求、custom entry、projector、Canvas outputs 与引用策略。 */
  @Test
  void catalogFingerprintCoversEveryContributionMetadataType() {
    List<CanvasFunctionOutputSpec> singleOutput =
        List.of(CanvasFunctionOutputSpec.of(CanvasResourceKind.IMAGE));
    List<CanvasFunctionOutputSpec> multiOutput =
        List.of(
            CanvasFunctionOutputSpec.of(CanvasResourceKind.IMAGE),
            CanvasFunctionOutputSpec.named(CanvasResourceKind.VIDEO, "clip"));

    String baseline = catalogFingerprint(StateMode.READ, 10, 2, 3, singleOutput);
    assertTrue(PluginCatalogFingerprint.isFingerprint(baseline));

    assertNotEquals(
        baseline,
        catalogFingerprint(StateMode.WRITE, 10, 2, 3, singleOutput),
        "state access mode must be fingerprinted");
    assertNotEquals(
        baseline,
        catalogFingerprint(StateMode.READ, 11, 2, 3, singleOutput),
        "custom entry priority must be fingerprinted");
    assertNotEquals(
        baseline,
        catalogFingerprint(StateMode.READ, 10, 3, 3, singleOutput),
        "context projector priority must be fingerprinted");
    assertNotEquals(
        baseline,
        catalogFingerprint(StateMode.READ, 10, 2, 4, singleOutput),
        "reference policy maxReferences must be fingerprinted");
    assertNotEquals(
        baseline,
        catalogFingerprint(StateMode.READ, 10, 2, 3, multiOutput),
        "the Canvas output plan must be fingerprinted");
    assertEquals(baseline, catalogFingerprint(StateMode.READ, 10, 2, 3, singleOutput));
  }

  /** 能力指纹按 owner 过滤两域全部贡献类型：无辜的额外 contributor 与未拥有的 function 都不改变它。 */
  @Test
  void capabilityFingerprintFiltersEveryDomainByOwner() {
    HarnessCatalog sharedHarness =
        HarnessCatalog.from(List.of(ownerContributor(), unrelatedContributor()));
    CanvasFunctionCatalog sharedCanvas = CanvasFunctionCatalog.from(List.of(adapter("owned.fn")));

    Set<ContributorId> owner = Set.of(new ContributorId(OWNER));
    Set<ContributorId> bothOwners = Set.of(new ContributorId(OWNER), new ContributorId(UNRELATED));

    String ownedOnly =
        PluginCatalogFingerprint.capability(sharedHarness, sharedCanvas, owner, Set.of("owned.fn"));
    String ownerInIsolation =
        PluginCatalogFingerprint.capability(
            HarnessCatalog.from(List.of(ownerContributor())),
            CanvasFunctionCatalog.from(List.of(adapter("owned.fn"))),
            owner,
            Set.of("owned.fn"));
    assertEquals(ownedOnly, ownerInIsolation, "unowned contributions must be filtered out");

    assertNotEquals(
        ownedOnly,
        PluginCatalogFingerprint.capability(
            sharedHarness, sharedCanvas, bothOwners, Set.of("owned.fn")),
        "owning another contributor must change the capability fingerprint");
    assertNotEquals(
        ownedOnly,
        PluginCatalogFingerprint.capability(sharedHarness, sharedCanvas, owner, Set.of()),
        "dropping the owned function must change the capability fingerprint");
  }

  /** 组装覆盖全部贡献元数据的目录，参数用于逐项改变后比较指纹。 */
  private static String catalogFingerprint(
      StateMode stateMode,
      int customEntryPriority,
      int projectorPriority,
      int maxReferences,
      List<CanvasFunctionOutputSpec> outputs) {
    Tool plainTool = dummyTool("plain_tool", ToolRequirements.none());
    Tool envTool =
        dummyTool("env_tool", ToolRequirements.environment(EnvironmentId.of(ENVIRONMENT_UUID)));
    Tool stateTool =
        dummyTool(
            "state_tool",
            new ToolRequirements(
                EnvironmentSupport.NONE, List.of(new StateDeclaration("custom.state", stateMode))));
    ContextProjector projector =
        view -> {
          throw new AssertionError("fingerprinting must not run context projectors");
        };
    HarnessContributor contributor =
        HarnessContributor.of(
            new ContributorDescriptor(new ContributorId("core"), "Core", "1.0", Set.of()),
            registrar -> {
              registrar.registerCustomEntryType("entry", "custom.state", customEntryPriority);
              registrar.registerTool("plain", plainTool, ToolVisibility.SELECTABLE, 5);
              registrar.registerTool("env", envTool, ToolVisibility.SELECTABLE, 1);
              registrar.registerTool("state", stateTool, ToolVisibility.INTERNAL, 3);
              registrar.registerContextProjector("proj", projector, projectorPriority);
            });

    CanvasFunctionAdapter adapter =
        new CanvasFunctionAdapter() {
          @Override
          public List<CanvasFunctionDefinition> functions() {
            return List.of(
                new CanvasFunctionDefinition(
                    "fn.one",
                    "function fn.one",
                    CanvasJson.parseObject(
                        "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"),
                    outputs,
                    new CanvasFunctionReferencePolicy(
                        Set.of(CanvasResourceKind.VIDEO, CanvasResourceKind.IMAGE),
                        maxReferences,
                        Map.of(CanvasResourceKind.IMAGE, 2))));
          }

          @Override
          public boolean enabled() {
            return true;
          }

          @Override
          public String unavailableReason() {
            return null;
          }

          @Override
          public void preflight(CanvasFunctionFrozenRun run) {}

          @Override
          public void submit(CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {}

          @Override
          public List<UUID> execute(
              CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {
            return List.of();
          }
        };
    return PluginCatalogFingerprint.catalog(
        HarnessCatalog.from(List.of(contributor)), CanvasFunctionCatalog.from(List.of(adapter)));
  }

  private static HarnessContributor ownerContributor() {
    return HarnessContributor.of(
        new ContributorDescriptor(new ContributorId(OWNER), "Owner", "1.0", Set.of()),
        registrar -> {
          registrar.registerCustomEntryType("owned-entry", "owned.state", 1);
          registrar.registerTool(
              "owned",
              dummyTool("owned_tool", ToolRequirements.none()),
              ToolVisibility.SELECTABLE,
              1);
          registrar.registerContextProjector("owned-proj", view -> List.of(), 1);
        });
  }

  private static HarnessContributor unrelatedContributor() {
    return HarnessContributor.of(
        new ContributorDescriptor(new ContributorId(UNRELATED), "Unrelated", "1.0", Set.of()),
        registrar -> {
          registrar.registerCustomEntryType("other-entry", "other.state", 1);
          registrar.registerTool(
              "other",
              dummyTool("other_tool", ToolRequirements.none()),
              ToolVisibility.SELECTABLE,
              1);
          registrar.registerContextProjector("other-proj", view -> List.of(), 1);
        });
  }

  private static CanvasFunctionAdapter adapter(String functionName) {
    CanvasFunctionDefinition definition =
        CanvasFunctionDefinition.of(
            functionName,
            "function " + functionName,
            CanvasJson.parseObject(
                "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"),
            CanvasResourceKind.IMAGE,
            new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()));
    return new CanvasFunctionAdapter() {
      @Override
      public List<CanvasFunctionDefinition> functions() {
        return List.of(definition);
      }

      @Override
      public boolean enabled() {
        return true;
      }

      @Override
      public String unavailableReason() {
        return null;
      }

      @Override
      public void preflight(CanvasFunctionFrozenRun run) {}

      @Override
      public void submit(CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {}

      @Override
      public List<UUID> execute(
          CanvasFunctionExecutionContext context, CanvasFunctionFrozenRun run) {
        return List.of();
      }
    };
  }

  private static Tool dummyTool(String name, ToolRequirements requirements) {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            name,
            "tool " + name,
            name,
            new InputSchema(null, Map.of(), Set.of(), false),
            ToolSideEffect.READ_ONLY,
            Duration.ZERO);
    return new Tool() {
      @Override
      public ToolDescriptor descriptor() {
        return descriptor;
      }

      @Override
      public ToolRequirements requirements() {
        return requirements;
      }

      @Override
      public ToolExecutionHandle execute(
          ToolExecutionRequest request, ToolExecutionListener listener) {
        throw new AssertionError("fingerprinting must not execute tools");
      }
    };
  }

  private static PluginConfigSchema schema(InputSchema valuesSchema, String defaultsJson) {
    return new PluginConfigSchema(valuesSchema, defaultsJson, Map.of());
  }

  private static PluginConfigSchema declaredSecretsSchema() {
    return new PluginConfigSchema(
        valuesSchema(true), "{}", linkedSecrets(Map.of("token", "API token")));
  }

  private static Map<String, String> linkedSecrets(Map<String, String> secrets) {
    return new LinkedHashMap<>(secrets);
  }

  /** 同一个 values schema，仅属性插入顺序不同：模拟 DB jsonb 回读后的键乱序。 */
  private static InputSchema valuesSchema(boolean flagFirst) {
    Map<String, SchemaElement> inner = new LinkedHashMap<>();
    if (flagFirst) {
      inner.put("left", new IntegerSchema(null));
      inner.put("right", new IntegerSchema(null));
    } else {
      inner.put("right", new IntegerSchema(null));
      inner.put("left", new IntegerSchema(null));
    }
    Map<String, SchemaElement> outer = new LinkedHashMap<>();
    SchemaElement nested = new ObjectSchema(null, inner, Set.of(), false);
    if (flagFirst) {
      outer.put("flag", new BooleanSchema(null));
      outer.put("outer", nested);
    } else {
      outer.put("outer", nested);
      outer.put("flag", new BooleanSchema(null));
    }
    return new InputSchema(null, outer, Set.of(), false);
  }

  private static HarnessContributor contributor(String id, Set<String> requires) {
    Set<ContributorId> required = new LinkedHashSet<>();
    for (String requiredId : requires) {
      required.add(new ContributorId(requiredId));
    }
    return HarnessContributor.of(
        new ContributorDescriptor(new ContributorId(id), "contributor " + id, "1.0", required),
        registrar -> {});
  }
}
