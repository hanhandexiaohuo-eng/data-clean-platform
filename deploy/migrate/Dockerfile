# ============================================================
# 迁移执行器镜像
# ------------------------------------------------------------
# 基础镜像固定 MySQL 8.4 大版本，与数据库服务保持一致；
# 升级策略：跟随数据库主版本升级，并在升级前重跑迁移演练。
# 仅包含 mysql 客户端与迁移脚本，不包含应用代码或任何配置文件。
# ============================================================
FROM mysql:8.4

COPY db/migration /migrations
COPY deploy/migrate/run-migrations.sh /usr/local/bin/run-migrations.sh

RUN chmod 0555 /usr/local/bin/run-migrations.sh

ENV MIGRATION_DIR=/migrations
USER mysql

ENTRYPOINT ["/usr/local/bin/run-migrations.sh"]
