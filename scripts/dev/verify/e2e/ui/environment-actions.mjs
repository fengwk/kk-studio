/**
 * Environment 卡片底部动作的唯一 inventory，顺序与前端渲染一致。
 *
 * ui-smoke 用它逐项校验真实页面；node:test 锁定七项集合及中英文匹配语义，避免动作增减后断言漂移。
 */
export const ENVIRONMENT_CARD_ACTIONS = [
  ['Install', /安装|Install/],
  ['Uninstall', /卸载|Uninstall/],
  ['Edit', /编辑|Edit/],
  ['Manage', /管理|Manage/],
  ['Update Daemon', /更新 Daemon|Update Daemon/],
  ['Rotate Token', /重新生成 Token|Rotate Token/],
  ['Delete', /删除|Delete/],
]
