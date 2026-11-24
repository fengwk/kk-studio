package fun.fengwk.kkstudio.platform.harness.http;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.ThreadTreeNode;
import fun.fengwk.kkstudio.harness.api.thread.ThreadKind;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;

import java.util.List;
import java.util.UUID;

/** The single Platform projection boundary for publicly addressable execution trees. */
public final class PublicExecutionTreeQuery {

  private PublicExecutionTreeQuery() {}

  /** Resolves a target, rejects a private target as not found, and removes private descendants. */
  public static List<ThreadTreeNode> get(HarnessRuntime runtime, UUID targetThreadId) {
    List<ThreadTreeNode> nodes = runtime.getThreadTree(targetThreadId);
    if (nodes.stream()
        .anyMatch(
            node ->
                node.thread().id().equals(targetThreadId)
                    && node.thread().kind() == ThreadKind.COMPACTION)) {
      throw DomainException.notFound("thread", "thread " + targetThreadId + " does not exist");
    }
    return nodes.stream().filter(node -> node.thread().kind() != ThreadKind.COMPACTION).toList();
  }
}
