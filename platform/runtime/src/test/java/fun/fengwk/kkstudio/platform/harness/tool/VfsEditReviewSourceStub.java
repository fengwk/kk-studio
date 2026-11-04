package fun.fengwk.kkstudio.platform.harness.tool;

import fun.fengwk.kkstudio.harness.api.tool.ToolEditReview;
import fun.fengwk.kkstudio.harness.environment.capability.ExpectedFile;
import fun.fengwk.kkstudio.harness.spi.ToolInvocationRequest;
import fun.fengwk.kkstudio.platform.vfs.VfsEditRejectedException;
import fun.fengwk.kkstudio.platform.vfs.VfsEditReviewSource;

import java.util.UUID;

/** 不涉及 vfs: 编辑预览的网关测试使用：任何预览请求都确定性拒绝。 */
public final class VfsEditReviewSourceStub implements VfsEditReviewSource {

  public static final VfsEditReviewSourceStub INSTANCE = new VfsEditReviewSourceStub();

  private VfsEditReviewSourceStub() {}

  @Override
  public ToolEditReview prepare(
      ToolInvocationRequest request, UUID rootSessionId, UUID rootThreadId) {
    throw new VfsEditRejectedException("vfs edit preview is not available in this test");
  }

  @Override
  public void verifySnapshot(UUID rootSessionId, UUID rootThreadId, ExpectedFile expectedFile) {
    throw new VfsEditRejectedException("vfs edit preview is not available in this test");
  }
}
