#!/usr/bin/env bash
#
# 打包服务器部署所需的最小文件集（服务器上不需要 git clone 整个仓库）
#
# 用法：
#   bash docs/deploy/make-bundle.sh [输出目录]
#   默认输出目录：./deploy-out
#
# 产物：
#   deploy-out/jeepay-deploy/            解压前的原始文件，可直接编辑后再上传
#   deploy-out/jeepay-deploy-<日期>.tar.gz   上传用压缩包
#
# 之所以只打包这些：docker-compose.prod.yml 里所有宿主机绑定挂载（bind mount）
# 总共只有 9 个路径，见下方 FILES。其余内容（Java 源码、pom.xml、jeepay-ui 源码、
# .git 等）都已在镜像里，服务器上是纯 pull 运行，不需要任何构建文件。

set -euo pipefail

cd "$(dirname "$0")/../.."

OUT_DIR="${1:-deploy-out}"
BUNDLE_NAME="jeepay-deploy"
BUNDLE_DIR="${OUT_DIR}/${BUNDLE_NAME}"
STAMP="$(date +%Y%m%d)"

# 需要上传的文件 / 目录（与 docker-compose.prod.yml 的挂载一一对应）
FILES=(
  "docker-compose.prod.yml"                    # 编排文件本体
  "conf/payment/application.yml"               # 三个后端配置，挂载覆盖镜像内同名文件
  "conf/manager/application.yml"
  "conf/merchant/application.yml"
  "docker/rocketmq/broker/conf/broker.conf"    # brokerIP1 等，挂载覆盖
  "conf/nginx/default.conf.template"           # 比镜像内多一行 resolver，供 ui-* 用服务名反代
  "docs/sql/init.sql"                          # MySQL 首次初始化执行
  "docs/sql/patch.sql"
  "docs/deploy/aliyun-acr-caddy.md"            # 部署说明
  "docs/deploy/caddy-snippet.md"               # Caddy 站点片段
)

# 可选：商户种子数据（由 make-seed.sh 生成）。
# init.sql 只建表 + 预置运营平台超管，不含商户/应用/渠道参数；
# 带上这份种子，服务器上导入后就不必重新建商户、重填 ezfp 的 PID 与密钥。
OPTIONAL_FILES=(
  "deploy-out/seed-mch.sql:seed-mch.sql"
)

# 需要预建的目录：容器内以非 root 用户写日志，宿主机目录不存在时
# Docker 会自动创建但属主是 root，容易引发写日志失败
DIRS=(
  "conf/payment" "conf/manager" "conf/merchant" "conf/nginx"
  "docker/rocketmq/broker/conf"
  "docs/sql" "docs/deploy"
  "logs/payment" "logs/manager" "logs/merchant"
)

echo "==> 清理并重建 ${BUNDLE_DIR}"
rm -rf "${BUNDLE_DIR}"
mkdir -p "${BUNDLE_DIR}" "${OUT_DIR}"

for f in "${FILES[@]}"; do
  if [[ ! -f "$f" ]]; then
    echo "!! 缺少文件：$f" >&2
    exit 1
  fi
  mkdir -p "${BUNDLE_DIR}/$(dirname "$f")"
  cp "$f" "${BUNDLE_DIR}/$f"
done

for d in "${DIRS[@]}"; do
  mkdir -p "${BUNDLE_DIR}/$d"
done

# 可选的商户种子数据：缺少时自动调用 make-seed.sh 生成。
# 注意顺序 —— 本脚本开头会 rm -rf 输出目录，所以生成动作必须放在清理之后。
for entry in "${OPTIONAL_FILES[@]}"; do
  src="${entry%%:*}"
  dst="${entry##*:}"
  if [[ ! -f "$src" ]]; then
    echo "==> 未找到 $src，尝试用 make-seed.sh 自动生成"
    # 本机 MySQL 没起 / 没有商户时失败是正常的，不阻断打包
    bash docs/deploy/make-seed.sh >/dev/null 2>&1 || true
  fi
  if [[ -f "$src" ]]; then
    cp "$src" "${BUNDLE_DIR}/$dst"
    echo "    + 已带上种子数据 $dst（商户、登录账号、应用、渠道参数）"
  else
    echo "    ! 未带上 $dst —— 服务器上商户平台将无账号可登录，"
    echo "      且需重新建商户、重填 ezfp 的 PID 与密钥。"
  fi
done

# 放一个占位文件，保证空的 logs 目录能进 tar 包（tar 默认不打包空目录）
for d in logs/payment logs/manager logs/merchant; do
  echo "容器日志输出目录，勿删" > "${BUNDLE_DIR}/${d}/.gitkeep"
done

# 服务器上直接可用的 .env.prod，避免忘了 cp 这一步
cp .env.prod.example "${BUNDLE_DIR}/.env.prod"

cat > "${BUNDLE_DIR}/README-部署.txt" <<'EOF'
服务器部署包（不含源码，镜像从私有仓库拉取）
================================================

1. 修改配置
   vi .env.prod                      # REGISTRY(要写到仓库名，如 .../skiyoumi/jneelypay)
                                     # IMAGE_TAG(版本前缀) / MYSQL_ROOT_PASSWORD
   vi conf/payment/application.yml   # 三份都要改：
   vi conf/manager/application.yml   #   spring.datasource.password 与 .env.prod 一致
   vi conf/merchant/application.yml  #   isys.allow-cors -> false
                                     #   logging.level.com.jeequan.jeepay -> info

2. 启动
   docker compose --env-file .env.prod -f docker-compose.prod.yml pull
   docker compose --env-file .env.prod -f docker-compose.prod.yml up -d
   docker compose --env-file .env.prod -f docker-compose.prod.yml ps

3. 导入商户种子数据（若包内有 seed-mch.sql；等 MySQL healthy 后再执行）
   不导入的后果：商户平台没有任何账号、登录不进去，ezfp 的 PID/密钥也要从零重填。
   seed-mch.sql 已含商户、登录账号、应用、ezfp 通道参数。

   docker exec -i jeepay-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" jeepaydb < seed-mch.sql

   导入后用原商户账号密码登录（本机是 modelscube）。

4. Caddy（容器版，注意上游不能用 127.0.0.1）
   docker network connect jeepay-net sub2api-caddy
   把 docs/deploy/caddy-snippet.md 里的三个站点片段追加到现有 Caddyfile 末尾，
   不要重复写全局 {} 块。上游写容器名：
     pay.你的域名 -> jeepay-ui-payment:80
     mch.你的域名 -> jeepay-ui-merchant:80
     mgr.你的域名 -> jeepay-ui-manager:80
   然后 docker exec sub2api-caddy caddy reload --config /etc/caddy/Caddyfile

5. 上线后必改（运营平台 -> 系统配置 -> 应用配置）
   支付网关地址      -> https://pay.你的域名
   商户平台网址      -> https://mch.你的域名
   运营平台网址      -> https://mgr.你的域名
   公共oss访问地址   -> https://mgr.你的域名/api/anon/localOssFiles
   必须带 https://，结尾不要斜杠。不改会导致异步通知收不到、二维码裂图。

详细流程见 docs/deploy/aliyun-acr-caddy.md
EOF

TARBALL="${OUT_DIR}/${BUNDLE_NAME}-${STAMP}.tar.gz"
tar -czf "${TARBALL}" -C "${OUT_DIR}" "${BUNDLE_NAME}"

echo
echo "==> 打包完成：${TARBALL}  ($(du -h "${TARBALL}" | cut -f1))"
echo
echo "上传到服务器（替换成你的地址）："
echo "  scp ${TARBALL} root@你的服务器:/opt/"
echo
echo "服务器上："
echo "  cd /opt && tar -xzf ${BUNDLE_NAME}-${STAMP}.tar.gz && cd ${BUNDLE_NAME}"
echo
echo "包内文件："
tar -tzf "${TARBALL}" | sed 's/^/  /'
