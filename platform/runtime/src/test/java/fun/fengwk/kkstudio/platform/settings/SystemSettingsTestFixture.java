package fun.fengwk.kkstudio.platform.settings;

import fun.fengwk.kkstudio.harness.api.retry.InvocationRetryBackoffStrategy;
import fun.fengwk.kkstudio.harness.api.retry.ModelHttpErrorPolicy;
import fun.fengwk.kkstudio.harness.model.ImageInputTier;
import fun.fengwk.kkstudio.platform.harness.tool.permission.PermissionAction;
import fun.fengwk.kkstudio.platform.harness.tool.permission.PermissionRule;

import java.util.List;
import java.util.Map;

/** Test-only default aggregate mirroring the single seed row owned by {@code V1__schema.sql}. */
public final class SystemSettingsTestFixture {

  public static final SystemSettings.Tool TOOL =
      new SystemSettings.Tool(
          Map.of(
              "write", List.of(new PermissionRule("*", PermissionAction.ASK)),
              "edit", List.of(new PermissionRule("*", PermissionAction.ASK)),
              "bash", List.of(new PermissionRule("*", PermissionAction.ASK))),
          5_000L,
          5_000L);

  public static final SystemSettings.AiRuntime AI_RUNTIME =
      new SystemSettings.AiRuntime(
          3,
          InvocationRetryBackoffStrategy.EXPONENTIAL,
          2_000L,
          60_000L,
          null,
          20_000,
          16_384,
          2,
          10,
          0,
          50,
          ModelHttpErrorPolicy.DEFAULT_RETRY_STATUS_CODES);

  public static final SystemSettings.Environment ENVIRONMENT =
      new SystemSettings.Environment(16L * 1024 * 1024, 60_000L);

  public static final SystemSettings.BackendNetwork BACKEND_NETWORK =
      new SystemSettings.BackendNetwork(null, "localhost,127.*,::1");

  public static final SystemSettings.StorageMedia STORAGE_MEDIA =
      new SystemSettings.StorageMedia(
          86_400L, 1_800L, 3_600L, 30_000L, 512, 80, ImageInputTier.P720, 259_200L, 1_800L);

  public static final SystemSettings.Advanced ADVANCED =
      new SystemSettings.Advanced(
          16L * 1024 * 1024,
          30_000L,
          10_000L,
          1_000L,
          1_000L,
          1_000L,
          1_000L,
          512,
          2L * 1024 * 1024,
          10_000L,
          20_000L,
          5_000L,
          1_000L);

  public static final SystemSettings DEFAULT =
      new SystemSettings(TOOL, AI_RUNTIME, ENVIRONMENT, BACKEND_NETWORK, STORAGE_MEDIA, ADVANCED);

  private SystemSettingsTestFixture() {}
}
