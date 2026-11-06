import { afterEach, describe, expect, it, vi } from "vitest";
import { request } from "@/shared/api/client";
import type {
  EnvironmentDTO,
  EnvironmentEventDTO,
  EnvironmentInstallCodeDTO,
  EnvironmentRuntimeStatusDTO,
  EnvironmentUpdateStartDTO,
} from "@/shared/api/generated/contracts";
import { createEnvironmentService } from "@/features/environment/environment-service";

vi.mock("@/shared/api/client", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/shared/api/client")>();
  return { ...actual, request: vi.fn() };
});

const apiRequest = vi.mocked(request);
const service = createEnvironmentService();

afterEach(() => apiRequest.mockReset());

function environment(overrides: Partial<EnvironmentDTO> = {}): EnvironmentDTO {
  return {
    id: "env-1",
    name: "local",
    status: "READY",
    ready: true,
    statusExpiresAt: 1_760_000_000_000,
    capabilities: [],
    version: "1",
    createdAt: 1_759_000_000_000,
    updatedAt: 1_759_000_000_000,
    ...overrides,
  };
}

describe("environmentService", () => {
  it("saves nested daemon settings with the CAS version", async () => {
    apiRequest.mockResolvedValue(environment({ version: "9" }));
    const config = {
      operatingSystem: "linux" as const,
      daemon: { studioUrl: "https://studio.example.com" },
    };
    expect(await service.saveInstallConfig("env/a", "8", config)).toEqual(
      environment({ version: "9" }),
    );
    expect(apiRequest).toHaveBeenCalledWith(
      "PUT /api/platform/environments/{environmentId}/install-settings",
      {
        path: { environmentId: "env/a" },
        body: { expectedVersion: "8", installConfig: config },
      },
    );
  });

  /** 测试意图：验证 Environment 列表查询命中平台环境资源。 */
  it("lists environments from the platform resource", async () => {
    apiRequest.mockResolvedValue([]);
    await service.listEnvironments();
    expect(apiRequest).toHaveBeenCalledWith(
      "GET /api/platform/environments",
      {},
    );
  });

  /** 测试意图：验证单个 Environment 详情以路径参数定位。 */
  it("gets a single environment by its identifier", async () => {
    apiRequest.mockResolvedValue(environment());
    await service.getEnvironment("env-1");
    expect(apiRequest).toHaveBeenCalledWith(
      "GET /api/platform/environments/{environmentId}",
      {
        path: { environmentId: "env-1" },
      },
    );
  });

  /** 测试意图：验证创建 Environment 返回含 registrationToken 的卡片。 */
  it("creates an environment card", async () => {
    apiRequest.mockResolvedValue(
      environment({ name: "dev", registrationToken: "tok-123" }),
    );
    const result = await service.createEnvironment({ name: "dev" });
    expect(apiRequest).toHaveBeenCalledWith("POST /api/platform/environments", {
      body: { name: "dev" },
    });
    expect(result.registrationToken).toBe("tok-123");
  });

  /** 测试意图：签发 5 分钟安装 code，body 只有 expectedVersion。 */
  it("issues a short install code", async () => {
    const issued: EnvironmentInstallCodeDTO = {
      code: "code'汉字",
      expiresAt: 1_760_000_300_000,
    };
    apiRequest.mockResolvedValue(issued);
    const result = await service.createInstallCode("env/a", "8");
    expect(apiRequest).toHaveBeenCalledWith(
      "POST /api/platform/environments/{environmentId}/install-codes",
      { path: { environmentId: "env/a" }, body: { expectedVersion: "8" } },
    );
    expect(result).toEqual(issued);
  });

  /** 测试意图：轮换 registration token 把 expectedVersion 放进 body。 */
  it("rotates the registration token with the CAS version", async () => {
    apiRequest.mockResolvedValue(
      environment({ registrationToken: "tok-new", version: "2" }),
    );
    const result = await service.rotateToken("env-1", "1");
    expect(apiRequest).toHaveBeenCalledWith(
      "POST /api/platform/environments/{environmentId}/rotate-token",
      { path: { environmentId: "env-1" }, body: { expectedVersion: "1" } },
    );
    expect(result.registrationToken).toBe("tok-new");
  });

  /** 测试意图：删除 Environment 用 query 传递 expectedVersion。 */
  it("deletes an environment with the expected version", async () => {
    apiRequest.mockResolvedValue(undefined);
    await service.deleteEnvironment("env-1", "3");
    expect(apiRequest).toHaveBeenCalledWith(
      "DELETE /api/platform/environments/{environmentId}",
      {
        path: { environmentId: "env-1" },
        query: { expectedVersion: "3" },
      },
    );
  });

  /** 测试意图：事件列表按路径参数定位环境。 */
  it("lists environment events by identifier", async () => {
    const events: EnvironmentEventDTO[] = [
      {
        occurredAt: 1_759_000_000_000,
        level: "INFO",
        type: "READY",
        message: "Connected",
      },
    ];
    apiRequest.mockResolvedValue(events);
    const result = await service.listEnvironmentEvents("env/special:1");
    expect(apiRequest).toHaveBeenCalledWith(
      "GET /api/platform/environments/{environmentId}/events",
      { path: { environmentId: "env/special:1" } },
    );
    expect(result).toEqual(events);
  });

  it("reads the current runtime snapshot", async () => {
    const runtime: EnvironmentRuntimeStatusDTO = {
      revision: "7",
      status: {
        daemonVersion: "1.2.3",
        update: {
          targetVersion: "1.3.0",
          downloadedBytes: "8388608",
          totalBytes: null,
        },
      },
    };
    apiRequest.mockResolvedValue(runtime);
    const result = await service.getEnvironmentRuntime("env/a");
    expect(apiRequest).toHaveBeenCalledWith(
      "GET /api/platform/environments/{environmentId}/runtime",
      { path: { environmentId: "env/a" } },
    );
    expect(result).toEqual(runtime);
  });

  it("starts a managed daemon update and returns command status with the snapshot", async () => {
    const started: EnvironmentUpdateStartDTO = {
      commandStatus: "SENT_UNCONFIRMED",
      runtimeStatus: {
        revision: "8",
        status: { daemonVersion: "1.2.3", update: null },
      },
    };
    apiRequest.mockResolvedValue(started);
    const result = await service.startEnvironmentUpdate("env/a");
    expect(apiRequest).toHaveBeenCalledWith(
      "POST /api/platform/environments/{environmentId}/update-daemon",
      { path: { environmentId: "env/a" } },
    );
    expect(result).toEqual(started);
  });
});
