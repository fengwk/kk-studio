package fun.fengwk.kkstudio.platform.project.model;

/**
 * Issue 公开证据的来源。
 *
 * <p>{@code EXECUTOR}：执行者最终答复明确引用（规范 {@code kkstudio:/resources/<blobId>}）且来源 Run 的 Session 在提交时
 * 确实持有的产物；{@code HUMAN}：人经 Issue 上传入口显式公开的附件。其余资源始终私有。
 */
public enum IssueEvidenceOrigin {
  EXECUTOR,
  HUMAN
}
