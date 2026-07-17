package fun.fengwk.kkstudio.core.harness.tool.service;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionMapper;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionDO;
import fun.fengwk.kkstudio.core.harness.tool.configuration.ToolSettingsProvider;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettings;

import java.util.Objects;

/** 在事务锁内解析全局 Tool settings 与 Root Session 动态 YOLO。 */
@Component
public class ToolPolicyResolver {
  private final HarnessSessionMapper sessionMapper;
  private final ToolSettingsProvider settingsProvider;

  public ToolPolicyResolver(
      HarnessSessionMapper sessionMapper, ToolSettingsProvider settingsProvider) {
    this.sessionMapper = Objects.requireNonNull(sessionMapper, "sessionMapper");
    this.settingsProvider = Objects.requireNonNull(settingsProvider, "settingsProvider");
  }

  public ResolvedPolicy resolve(HarnessSessionDO session) {
    Objects.requireNonNull(session, "session");
    HarnessSessionDO root = findRoot(session);
    if (root == null
        || root.getParentSessionId() != null
        || !Objects.equals(root.getRootSessionId(), root.getId())) {
      throw new IllegalStateException("invalid root session for permission resolution");
    }
    return new ResolvedPolicy(settingsProvider.get(), Boolean.TRUE.equals(root.getYoloEnabled()));
  }

  private HarnessSessionDO findRoot(HarnessSessionDO session) {
    if (Objects.equals(session.getRootSessionId(), session.getId())) {
      return session;
    }
    if (session.getRootSessionId() == null) {
      return null;
    }
    return sessionMapper.findForUpdate(session.getRootSessionId());
  }

  public record ResolvedPolicy(ToolSettings settings, boolean yoloEnabled) {}
}
