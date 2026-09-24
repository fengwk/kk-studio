package fun.fengwk.kkstudio.platform.project.service;

import fun.fengwk.kkstudio.platform.project.model.IssueEvidence;

import java.util.List;
import java.util.UUID;

/**
 * Issue 公开证据：Issue 自有的已发布 Blob 引用及其对参与者 Session 的幂等授权。
 *
 * <p>公开面只有两条入口，且都不复制字节：执行者最终答复明确引用（规范 {@code kkstudio:/resources/<blobId>}）且来源 Run 的 Session
 * 在提交时确实持有该引用的产物，以及人经 Issue 上传入口显式提交的附件。其余资源保持私有，不因知道 blob id 或 URI 而 获得可见性。
 *
 * <p>证据一旦发布即由 Issue 持有：授权给当时已绑定与之后新绑定的参与者 Session 都是幂等的，撤回标记也不自动回收过去已披露的内容或既有 Session 授权；只有
 * Issue/Project 深删除才释放 Issue 持有引用。
 */
public interface IssueEvidenceService {

  /** 单次公开读取返回的证据条数上限：公开面有界，绝不无界展开整条证据历史。 */
  int MAX_EVIDENCE_LIMIT = 50;

  /** 有界读取该 Issue 的已发布证据（按发布时间倒序，最多 {@link #MAX_EVIDENCE_LIMIT} 条）。 */
  List<IssueEvidence> listEvidence(UUID issueId);

  /**
   * 人工上传转为 Issue 公开证据：事务内 {@code lockReady -> retain Issue ref -> delete upload}，不复制字节。
   *
   * @throws fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException Issue 或上传不存在
   * @throws fun.fengwk.kkstudio.platform.error.AiValidationException Issue 已归档，或上传未就绪/已过期/已被消费
   */
  IssueEvidence publishHumanUpload(UUID issueId, UUID uploadId);

  /**
   * 事务内从执行者的合格提交发布证据（必须由 Run 提交事务调用，与提交结果原子成立）。
   *
   * <p>逐条核验规范 URI 指向的 Blob 是否由来源 Run 的 Session 在提交时持有：不持有或来源归属缺失即拒绝整个提交，绝不静默公开私有 资源，也不产生部分证据。
   */
  void publishExecutorEvidence(UUID issueId, UUID runId, String agentName, String summary);

  /**
   * 事务内把该 Issue 已发布证据的指定 Blob 引用幂等授予一个参与者 Session（Session 必须已存在）。
   *
   * <p>只授予已发布 Blob，不存在通配可见性：Session 未被授权的历史附件仍然不可读。
   */
  void grantPublishedEvidence(UUID issueId, UUID sessionId);

  /** 事务内释放该 Issue 的全部公开证据：删除证据行并逐行 release Issue 持有的 Blob 引用（无 CASCADE）。返回删除的证据行数。 */
  int deleteByIssue(UUID issueId);
}
