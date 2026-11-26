#!/bin/sh
# ============================================================
# DataClean 数据库迁移执行器（容器入口）
# ------------------------------------------------------------
# 机制：
#   1. 等待 MySQL 可用，并校验目标库存在；
#   2. 使用 MySQL 命名锁（GET_LOCK）保证同一时间只有一个迁移在执行；
#   3. schema_migration 表记录已执行版本、描述、校验和、耗时与成功状态；
#   4. 按 V<数字>__<描述>.sql 的数字顺序执行未完成迁移；
#   5. 已成功版本校验文件哈希，防止历史迁移被篡改；
#   6. 失败版本保留 success=0 记录并终止，修复后可重试（迁移文件均为幂等增量）；
#   7. 存量库首次接入：按已存在的表自动建立基线（1=核心表，2=核心表+用户表），
#      只执行基线之后的增量迁移。
#
# 安全：
#   - 仅通过环境变量接收数据库连接信息，密码放入 MYSQL_PWD，不出现在命令行参数；
#   - 日志不打印密码、连接串、SQL 参数或业务数据；
#   - 不自动备份、不删除任何业务表或数据。
# ============================================================
set -eu

log() {
    printf '%s [migrate] %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*"
}

fail() {
    log "错误：$*"
    exit 1
}

: "${DB_HOST:?DB_HOST 未设置}"
: "${DB_NAME:?DB_NAME 未设置}"
: "${DB_USER:?DB_USER 未设置}"
: "${DB_PASSWORD:?DB_PASSWORD 未设置}"

DB_PORT="${DB_PORT:-3306}"
MIGRATION_DIR="${MIGRATION_DIR:-/migrations}"
LOCK_TIMEOUT="${MIGRATION_LOCK_TIMEOUT:-60}"

case "$DB_NAME" in
    *[!A-Za-z0-9_]*|'') fail "DB_NAME 仅允许字母、数字与下划线" ;;
esac

export MYSQL_PWD="$DB_PASSWORD"

mysql_cmd() {
    mysql --protocol=TCP -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" \
        --default-character-set=utf8mb4 --batch --skip-column-names --unbuffered "$@"
}

sql_exec() {
    mysql_cmd -D"$DB_NAME" -e "$1"
}

# ---------- 1. 等待数据库 ----------
attempt=0
until mysql_cmd -e "SELECT 1" >/dev/null 2>&1; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 60 ]; then
        fail "等待 MySQL($DB_HOST:$DB_PORT) 超时"
    fi
    sleep 2
done
log "MySQL 已就绪：$DB_HOST:$DB_PORT"

db_exists="$(mysql_cmd -e "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME='$DB_NAME'")"
[ "$db_exists" = "1" ] || fail "目标数据库 $DB_NAME 不存在，请先创建（全新环境由 MySQL 容器初始化）"

# ---------- 2. 迁移命名锁 ----------
LOCK_NAME="dataclean_migrate_${DB_NAME}"
LOCK_OUT="$(mktemp /tmp/migrate-lock.XXXXXX)"
LOCK_PID=""

cleanup() {
    if [ -n "$LOCK_PID" ]; then
        kill "$LOCK_PID" 2>/dev/null || true
        wait "$LOCK_PID" 2>/dev/null || true
    fi
    rm -f "$LOCK_OUT"
}

trap cleanup EXIT
trap 'exit 1' INT TERM

# 后台连接持有 GET_LOCK；结果写入文件后该连接继续 SLEEP，直到进程退出。
# 注意：这里直接调用 mysql 而不是函数，确保 $! 就是 mysql 进程本身，
# 退出时 kill 能可靠关闭会话并释放命名锁。
mysql --protocol=TCP -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" \
    --default-character-set=utf8mb4 --batch --skip-column-names --unbuffered \
    -D"$DB_NAME" -e "SELECT GET_LOCK('$LOCK_NAME', $LOCK_TIMEOUT); SELECT SLEEP(86400);" \
    >"$LOCK_OUT" 2>&1 &
LOCK_PID=$!

lock_result=""
wait_count=0
while [ -z "$lock_result" ]; do
    if grep -qx '1' "$LOCK_OUT" 2>/dev/null; then
        lock_result=1
    elif grep -qx '0' "$LOCK_OUT" 2>/dev/null; then
        lock_result=0
    else
        wait_count=$((wait_count + 1))
        if [ "$wait_count" -ge $((LOCK_TIMEOUT + 5)) ]; then
            fail "获取迁移锁超时"
        fi
        sleep 1
    fi
done
[ "$lock_result" = "1" ] || fail "获取迁移锁失败，可能已有其他迁移正在执行"
log "已获取迁移锁：$LOCK_NAME"

# ---------- 3. 迁移历史表 ----------
sql_exec "CREATE TABLE IF NOT EXISTS \`schema_migration\` (
    \`version\` INT NOT NULL COMMENT '迁移版本号',
    \`description\` VARCHAR(200) NOT NULL COMMENT '迁移描述',
    \`script\` VARCHAR(255) NOT NULL COMMENT '迁移文件名',
    \`checksum\` VARCHAR(64) NOT NULL COMMENT '文件 SHA-256（基线为 BASELINE）',
    \`success\` TINYINT NOT NULL DEFAULT 0 COMMENT '是否成功: 0-否 1-是',
    \`execution_ms\` INT NOT NULL DEFAULT 0 COMMENT '执行耗时(毫秒)',
    \`installed_on\` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次执行时间',
    \`updated_on\` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (\`version\`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据库迁移历史'" >/dev/null

# ---------- 4. 存量库基线识别 ----------
applied_count="$(sql_exec "SELECT COUNT(*) FROM \`schema_migration\` WHERE \`success\` = 1")"
if [ "$applied_count" = "0" ]; then
    has_core="$(sql_exec "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='$DB_NAME' AND TABLE_NAME='dataset'")"
    has_user="$(sql_exec "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='$DB_NAME' AND TABLE_NAME='user'")"
    if [ "$has_core" -gt 0 ]; then
        baseline=1
        if [ "$has_user" -gt 0 ]; then
            baseline=2
        fi
        log "检测到存量库且无迁移历史，建立基线版本=$baseline（不会重建已有表）"
        i=1
        while [ "$i" -le "$baseline" ]; do
            sql_exec "INSERT INTO \`schema_migration\` (\`version\`,\`description\`,\`script\`,\`checksum\`,\`success\`,\`execution_ms\`)
                      VALUES ($i, 'legacy-baseline', 'BASELINE', 'BASELINE', 1, 0)
                      ON DUPLICATE KEY UPDATE \`success\`=1, \`checksum\`='BASELINE'" >/dev/null
            i=$((i + 1))
        done
    else
        log "空库初始化：将从 V1 开始执行全部迁移"
    fi
fi

# ---------- 5. 按版本顺序执行 ----------
files="$(find "$MIGRATION_DIR" -maxdepth 1 -type f -name 'V*__*.sql' | awk -F/ '{
    name = $NF
    version = name
    sub(/^V/, "", version)
    sub(/__.*/, "", version)
    if (version ~ /^[0-9]+$/) {
        printf "%08d\t%s\n", version, $0
    }
}' | sort | cut -f 2)"

[ -n "$files" ] || fail "迁移目录中未找到 V<数字>__<描述>.sql 文件"

executed=0
skipped=0
for file in $files; do
    base="$(basename "$file")"
    case "$base" in
        V*__*.sql) ;;
        *) fail "非法迁移文件名：$base（仅允许 V<数字>__<字母数字下划线>.sql）" ;;
    esac

    version="$(printf '%s' "$base" | sed -E 's/^V([0-9]+)__.*/\1/')"
    description="$(printf '%s' "$base" | sed -E 's/^V[0-9]+__(.*)\.sql$/\1/')"
    case "$version" in
        ''|*[!0-9]*) fail "非法迁移版本号：$base" ;;
    esac
    case "$description" in
        ''|*[!A-Za-z0-9_]*) fail "非法迁移描述：$base（仅允许字母、数字、下划线）" ;;
    esac
    checksum="$(sha256sum "$file" | awk '{print $1}')"

    state="$(sql_exec "SELECT CONCAT(\`success\`, '|', \`checksum\`) FROM \`schema_migration\` WHERE \`version\` = $version")"
    if [ -n "$state" ]; then
        prev_success="${state%%|*}"
        prev_checksum="${state#*|}"
        if [ "$prev_success" = "1" ]; then
            if [ "$prev_checksum" != "BASELINE" ] && [ "$prev_checksum" != "$checksum" ]; then
                fail "V$version 已执行但文件内容发生变化（$base）。禁止修改已发布迁移，请新增更高版本迁移"
            fi
            log "跳过 V$version（已执行）"
            skipped=$((skipped + 1))
            continue
        fi
        log "检测到 V$version 上次执行失败，开始重试"
    fi

    log "开始执行 V$version：$base"
    start_seconds="$(date +%s)"
    if mysql_cmd -D"$DB_NAME" <"$file"; then
        elapsed_ms=$(( ($(date +%s) - start_seconds) * 1000 ))
        sql_exec "INSERT INTO \`schema_migration\`
                    (\`version\`,\`description\`,\`script\`,\`checksum\`,\`success\`,\`execution_ms\`)
                  VALUES ($version, '$description', '$base', '$checksum', 1, $elapsed_ms)
                  ON DUPLICATE KEY UPDATE
                    \`description\`='$description', \`script\`='$base', \`checksum\`='$checksum',
                    \`success\`=1, \`execution_ms\`=$elapsed_ms, \`installed_on\`=CURRENT_TIMESTAMP" >/dev/null
        executed=$((executed + 1))
        log "V$version 执行成功（${elapsed_ms}ms）"
    else
        elapsed_ms=$(( ($(date +%s) - start_seconds) * 1000 ))
        sql_exec "INSERT INTO \`schema_migration\`
                    (\`version\`,\`description\`,\`script\`,\`checksum\`,\`success\`,\`execution_ms\`)
                  VALUES ($version, '$description', '$base', '$checksum', 0, $elapsed_ms)
                  ON DUPLICATE KEY UPDATE
                    \`description\`='$description', \`script\`='$base', \`checksum\`='$checksum',
                    \`success\`=0, \`execution_ms\`=$elapsed_ms" >/dev/null 2>&1 || true
        fail "V$version 执行失败。该迁移为幂等增量脚本，修复失败原因后可重新执行"
    fi
done

log "迁移完成：新执行 $executed 个，跳过 $skipped 个"
