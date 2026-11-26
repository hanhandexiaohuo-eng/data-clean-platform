-- ============================================================
-- R10：回滚 V10（破坏性操作）
-- ------------------------------------------------------------
-- !! 执行前必须确认：
--    1. 已备份目标数据库；
--    2. 确认 saved_chart / dashboard / AI 治理配置与调用日志无需保留；
--    3. 应用已停止或已设置 AI_GOVERNANCE_ENABLED=false。
--
-- 本脚本只删除 V10 新增的 10 张表，不修改/删除任何既有表与数据
-- （chart_view、ai_config、ai_generation_log 等全部保留）。
-- ============================================================

DROP TABLE IF EXISTS `ai_circuit_state`;
DROP TABLE IF EXISTS `ai_usage`;
DROP TABLE IF EXISTS `ai_quota`;
DROP TABLE IF EXISTS `ai_call_log`;
DROP TABLE IF EXISTS `ai_scene_route`;
DROP TABLE IF EXISTS `ai_model`;
DROP TABLE IF EXISTS `ai_provider`;
DROP TABLE IF EXISTS `dashboard_item`;
DROP TABLE IF EXISTS `dashboard`;
DROP TABLE IF EXISTS `saved_chart`;
