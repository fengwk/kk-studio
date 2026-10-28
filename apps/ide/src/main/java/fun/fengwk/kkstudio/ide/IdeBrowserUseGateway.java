package fun.fengwk.kkstudio.ide;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 浏览器事件节点使用登记的窄查询端口。
 *
 * <p>生产实现委托 {@code IdeBrowserUseClient}：本节点是事件节点时读本地登记，否则按 {@code eventNodeId} 单跳认证查询。查询失败必须抛出不可用，
 * 不能用「没有证据」伪造成「没有使用」；claim 前只接受完全相同的 environment/view/document/claim 意图。
 */
public interface IdeBrowserUseGateway {

  /** 该物理连接/document 是否仍存活且登记了完全相同的打开/恢复意图。 */
  boolean findExactIntent(
      UUID eventNodeId,
      UUID connectionId,
      UUID documentId,
      EnvironmentId environmentId,
      UUID viewId,
      UUID claimId);

  /** 该连接/document 当前仍可续租的已绑定持有项；空表示没有证明。 */
  Optional<Proofs> activeBoundProofs(UUID eventNodeId, UUID connectionId, UUID documentId);

  /** 一个 document 当前带 epoch 的绑定列表（可能包含其它 Environment；调用方按 environment 过滤）。 */
  record Proofs(UUID documentId, long revision, List<Proof> views) {

    public Proofs {
      if (revision <= 0) {
        throw new IllegalArgumentException("revision must be positive");
      }
      views = List.copyOf(views);
    }
  }

  /** 单条已绑定使用证明。 */
  record Proof(EnvironmentId environmentId, UUID viewId, UUID documentId, long bindingEpoch) {

    public Proof {
      if (bindingEpoch <= 0) {
        throw new IllegalArgumentException("bindingEpoch must be positive");
      }
    }
  }
}
