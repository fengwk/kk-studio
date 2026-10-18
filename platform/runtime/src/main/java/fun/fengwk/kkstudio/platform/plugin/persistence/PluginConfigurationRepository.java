package fun.fengwk.kkstudio.platform.plugin.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code plugin_configuration} 存取：每个已安装 Plugin 一行当前普通配置。
 *
 * <p>写入以 revision 做 CAS 并在同一事务发出 {@value #CHANNEL} 通知，所有节点据此重建运行期目录。
 */
@Repository
public class PluginConfigurationRepository {

  /** 普通配置或凭据变化的 PostgreSQL 通知通道。 */
  public static final String CHANNEL = "plugin_changed";

  private final JdbcClient jdbc;

  public PluginConfigurationRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  public List<PluginConfigurationRow> findAll() {
    return jdbc.sql("select plugin_id, revision, values_json from plugin_configuration")
        .query(
            (rs, row) ->
                new PluginConfigurationRow(
                    rs.getString("plugin_id"), rs.getLong("revision"), rs.getString("values_json")))
        .list();
  }

  public Optional<PluginConfigurationRow> find(String pluginId) {
    return jdbc.sql(
            "select plugin_id, revision, values_json from plugin_configuration"
                + " where plugin_id = :pluginId")
        .param("pluginId", pluginId)
        .query(
            (rs, row) ->
                new PluginConfigurationRow(
                    rs.getString("plugin_id"), rs.getLong("revision"), rs.getString("values_json")))
        .optional();
  }

  /** 首次启动时为新安装的 Plugin 写入默认值；已有行保持不变。 */
  public void insertIfMissing(String pluginId, String valuesJson) {
    jdbc.sql(
            "insert into plugin_configuration (plugin_id, revision, values_json)"
                + " values (:pluginId, 1, :valuesJson) on conflict (plugin_id) do nothing")
        .param("pluginId", pluginId)
        .param("valuesJson", valuesJson)
        .update();
  }

  /** 以 revision CAS 保存普通配置；未命中返回 false。 */
  public boolean update(String pluginId, long expectedRevision, String valuesJson) {
    return jdbc.sql(
                "update plugin_configuration set values_json = :valuesJson,"
                    + " revision = revision + 1, updated_at = current_timestamp"
                    + " where plugin_id = :pluginId and revision = :expectedRevision")
            .param("valuesJson", valuesJson)
            .param("pluginId", pluginId)
            .param("expectedRevision", expectedRevision)
            .update()
        == 1;
  }

  /** 在当前事务内发出变更提示；只在提交后对其它会话可见。 */
  public void notifyChanged(String pluginId) {
    jdbc.sql("select pg_notify('" + CHANNEL + "', :pluginId)")
        .param("pluginId", pluginId)
        .query()
        .singleValue();
  }
}
