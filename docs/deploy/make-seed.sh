#!/usr/bin/env bash
#
# 从本机 jeepay 数据库导出「商户 + 应用 + 渠道参数」的最小种子数据
#
# 为什么需要：docs/sql/init.sql 只建表并预置了运营平台超管(jeepay)，
# 不含任何商户、应用、渠道参数。服务器全新初始化后，商户平台是登录不进去的
# （无账号），ezfp 的 PID/密钥也要重新填。用本脚本导出后导入服务器即可免去重配。
#
# 用法：
#   bash docs/deploy/make-seed.sh [商户号] [输出文件]
#   默认商户号：M1790068530 (modelscube)
#   默认输出：deploy-out/seed-mch.sql
#
# 前置条件：本机 jeepay-mysql 容器正在运行，且库里已有配置好的商户。

set -euo pipefail

cd "$(dirname "$0")/../.."

MCH_NO="${1:-M1790068530}"
OUT="${2:-deploy-out/seed-mch.sql}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-jeepay-mysql}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASS="${MYSQL_PASS:-rootroot}"
DB="${DB:-jeepaydb}"

q() { docker exec "$MYSQL_CONTAINER" mysql -u"$MYSQL_USER" -p"$MYSQL_PASS" -N -B "$DB" -e "$1" 2>/dev/null; }

if ! docker ps --format '{{.Names}}' | grep -qx "$MYSQL_CONTAINER"; then
  echo "!! 容器 $MYSQL_CONTAINER 未运行，请先启动本机 jeepay" >&2
  exit 1
fi

MCH_NAME=$(q "SELECT mch_name FROM t_mch_info WHERE mch_no='$MCH_NO'")
if [[ -z "$MCH_NAME" ]]; then
  echo "!! 库里没有商户 $MCH_NO" >&2
  exit 1
fi

# 该商户下的所有应用（通常只有一个），每个应用对应一份渠道参数
APP_IDS=$(q "SELECT app_id FROM t_mch_app WHERE mch_no='$MCH_NO'")
if [[ -z "$APP_IDS" ]]; then
  echo "!! 商户 $MCH_NO 下没有应用" >&2
  exit 1
fi

mkdir -p "$(dirname "$OUT")"

dump() { docker exec "$MYSQL_CONTAINER" mysqldump -u"$MYSQL_USER" -p"$MYSQL_PASS" \
           --no-create-info --skip-triggers --skip-add-locks --complete-insert \
           --skip-lock-tables --set-gtid-purged=OFF "$DB" "$@" 2>/dev/null; }

# 商户登录账号的 sys_user_id 先查出来：--where 里若写成子查询，
# mysqldump 默认加 --lock-tables，子查询引用的表不在锁定范围内会报 1100 错误。
USER_IDS=$(q "SELECT sys_user_id FROM t_sys_user WHERE belong_info_id='$MCH_NO'" | paste -sd, -)
AUTH_WHERE="1=0"
[[ -n "$USER_IDS" ]] && AUTH_WHERE="user_id IN ($USER_IDS)"

{
  echo "-- 商户「$MCH_NAME」($MCH_NO) 的最小种子数据"
  echo "-- 由 docs/deploy/make-seed.sh 生成，仅含商户/账号/应用/渠道参数，不含订单与流水"
  echo "--"
  echo "-- 用途：服务器上用 init.sql 建好表之后导入，免去重新建商户、重填渠道参数"
  echo "-- 导入：docker exec -i jeepay-mysql mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" jeepaydb < $(basename "$OUT")"
  echo
  echo "-- 1) 商户主体"
  dump t_mch_info --where="mch_no='$MCH_NO'"

  echo "-- 2) 商户登录账号（sys_type=MCH，belong_info_id=商户号）"
  dump t_sys_user --where="belong_info_id='$MCH_NO'"
  dump t_sys_user_auth --where="$AUTH_WHERE"

  echo "-- 3) 商户应用"
  dump t_mch_app --where="mch_no='$MCH_NO'"

  echo "-- 4) 各应用的渠道参数（含 ezfp 的网关地址/PID/密钥）"
  for app in $APP_IDS; do
    dump t_pay_interface_config --where="info_type=3 AND info_id='$app'"
  done

  echo "-- 5) 商户支付通道开通记录"
  dump t_mch_pay_passage --where="mch_no='$MCH_NO'"
} > "$OUT"

COUNT=$(grep -c '^INSERT INTO' "$OUT" || true)
echo "==> 已生成 $OUT"
echo "    商户: $MCH_NAME ($MCH_NO)"
echo "    应用: $(echo $APP_IDS | tr '\n' ' ')"
echo "    INSERT 语句: $COUNT 条"
echo
echo "    打包进部署包: bash docs/deploy/make-bundle.sh"
echo "    （make-bundle.sh 会自动带上本文件；服务器上导入即可）"
