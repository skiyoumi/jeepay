# 部署到自己的服务器（私有镜像仓库 + Caddy）

适用场景：本地构建镜像 → 推送到阿里云个人版 ACR → 服务器只拉镜像运行，
HTTPS 由服务器上**已有的 Caddy** 终结。

本文以 `<registry>` = `crpi-1a0zvjxwrmr36txn.cn-hongkong.personal.cr.aliyuncs.com/skiyoumi` 为例。

> ⚠️ **不要用 `docs/deploy/shell.md` 里的 install.sh。**
> 那个脚本拉的是华为云 SWR 上的官方预编译镜像（`jeepay-*:3.2.0`），
> **不包含 ezfp 渠道的代码和数据库记录**，装出来是空的。

---

## 一、本地：构建并推送镜像

### 1. 编译后端 jar

三个 Dockerfile 都是 `COPY ./target/xxx.jar`，**必须先跑 Maven**，否则打进镜像的是旧 jar。

```bash
cd /path/to/jeepay
mvn clean package -DskipTests
```

产物：`jeepay-payment/target/jeepay-payment.jar`（manager / merchant 同理）。

### 2. 登录镜像仓库

ACR 密码在阿里云控制台「容器镜像服务 → 访问凭证」里设置/查看，**只能你自己登录**：

```bash
docker login --username=本初独然为月 crpi-1a0zvjxwrmr36txn.cn-hongkong.personal.cr.aliyuncs.com
```

### 3. 构建并推送六个镜像

前端在 `jeepay-ui/` 独立仓库，`PLATFORM` 决定构建哪个平台。
后端镜像必须带上 `BASE_IMAGE` 构建参数 —— 华为云 SWR 上的 `jeepay/eclipse-temurin:17-jre`
只有 arm64 变体，amd64 服务器上会 `exec format error`（原因见 `docker-compose.override.yml` 注释）。

```bash
set -e
REG=crpi-1a0zvjxwrmr36txn.cn-hongkong.personal.cr.aliyuncs.com/skiyoumi
TAG=3.2.9-ezfp          # 建议用版本号，便于回滚

# 三个后端
docker build -t $REG/jeepay-payment:$TAG --build-arg BASE_IMAGE=eclipse-temurin:17-jre ./jeepay-payment
docker build -t $REG/jeepay-manager:$TAG --build-arg BASE_IMAGE=eclipse-temurin:17-jre ./jeepay-manager
docker build -t $REG/jeepay-merchant:$TAG --build-arg BASE_IMAGE=eclipse-temurin:17-jre ./jeepay-merchant

# 三个前端（构建上下文是 jeepay-ui 目录，靠 PLATFORM 区分）
docker build -t $REG/jeepay-ui-payment:$TAG  --build-arg PLATFORM=cashier  ../jeepay-ui
docker build -t $REG/jeepay-ui-manager:$TAG  --build-arg PLATFORM=manager  ../jeepay-ui
docker build -t $REG/jeepay-ui-merchant:$TAG --build-arg PLATFORM=merchant ../jeepay-ui

docker push $REG/jeepay-payment:$TAG
docker push $REG/jeepay-manager:$TAG
docker push $REG/jeepay-merchant:$TAG
docker push $REG/jeepay-ui-payment:$TAG
docker push $REG/jeepay-ui-manager:$TAG
docker push $REG/jeepay-ui-merchant:$TAG
```

> 前端 `npm install` 走的是 `registry.npmmirror.com`，国内可直连；
> 若超时，先执行 `docker build --network=host ...`。

---

## 二、服务器：拉取并启动

### 1. 准备目录

只需 `git clone` 一次本仓库，用到的只有编排文件、SQL 脚本、MQ 配置和 `conf/`：

```bash
git clone -b feature/ezfp-channel <你的仓库地址> jeepay && cd jeepay
cp .env.prod.example .env.prod
vi .env.prod      # 改 REGISTRY / IMAGE_TAG / MYSQL_ROOT_PASSWORD
```

### 2. 修改 `conf/*/application.yml`（三份都要改）

| 配置项 | 默认值 | 生产环境应改为 |
| --- | --- | --- |
| `spring.datasource.password` | `rootroot` | 与 `.env.prod` 的 `MYSQL_ROOT_PASSWORD` 一致 |
| `isys.allow-cors` | `true` | 不需要跨域就改 `false`（你走同域反代，用不到） |
| `isys.oss.file-root-path` | `/jeepayhomes/service/uploads` | 保持默认即可，已由 compose 挂成命名卷 |
| `logging.level.com.jeequan.jeepay` | `debug` | 生产建议 `info`，否则日志量很大 |

`cache-config: false` 保持不变 —— 这样改系统配置不需要重启服务。

> 三份文件里 `spring.datasource.url` 写的是主机名 `mysql`、Redis 写的是 `redis`，
> 这是 compose 网络内的服务名，**不要改成 IP**，本方案下它们仍然在同一个 compose 网络里。

### 3. 启动

```bash
docker compose --env-file .env.prod -f docker-compose.prod.yml pull
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d
docker compose --env-file .env.prod -f docker-compose.prod.yml ps
```

`docker-compose.prod.yml` 与 `docker-compose.yml` 的关键差异：

- 六个业务服务**只 pull 不 build**；
- **所有端口只绑 `127.0.0.1`**，MySQL / Redis / RocketMQ 完全不对外暴露；
- 前端容器用固定 IP 作为 `BACKEND_HOST`（nginx 的 `proxy_pass` 带变量时是请求期解析，
  必须配 `resolver` 才能用服务名，固定 IP 可绕开）。

> 使用 `-f docker-compose.prod.yml` 时 Compose **不会**自动合并 `docker-compose.override.yml`，
> 该文件是自包含的。从旧编排文件切过来时先 `down`，避免两套文件互相覆盖容器。

### 4. 首次初始化数据库

`init.sql` / `patch.sql` 只在 **MySQL 数据卷为空**时执行。首次 `up` 会全自动完成，
其中包括 ezfp 通道定义。若你之前已在本机跑过、想复用数据，导出导入即可：

```bash
# 本地导出（结构 + 数据）
docker exec jeepay-mysql mysqldump -uroot -prootroot --databases jeepaydb > jeepaydb.sql
# 传到服务器后导入
docker exec -i jeepay-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" < jeepaydb.sql
```

> `patch.sql` 在全新初始化时会多次报 `Duplicate column name` / `Duplicate entry`
> 之类的告警（它按“逐条补丁”设计，不判断是否已存在）。**这是无害的**，
> 容器初始化脚本不会因为单条 SQL 失败而中止，检查表结构存在即可。

---

## 三、Caddy 配置

你已经有 Caddy，只需把三个站点片段追加到现有 Caddyfile 末尾。
完整说明见 [`caddy-snippet.md`](./caddy-snippet.md)。摘要：

```caddy
pay.example.com { encode gzip; reverse_proxy 127.0.0.1:9226 }
mch.example.com { encode gzip; reverse_proxy 127.0.0.1:9228 }
mgr.example.com { encode gzip; reverse_proxy 127.0.0.1:9227 }
```

放行 80 / 443，其余端口全部关闭。

---

## 四、⚠️ 改掉本机测试时用的地址（最容易漏的一步）

本机联调时为了打通容器互访，把三个地址改成了 `host.docker.internal`，
**那是仅本机可解析的地址，上线后异步通知会全部收不到、二维码图片也加载不出来**。

登录运营平台（`https://mgr.你的域名`）→ **系统配置** → 应用配置，按下表修改：

| 配置项 | 本机测试值 | 生产环境值 |
| --- | --- | --- |
| 支付网关地址 | `http://host.docker.internal:9216` | `https://pay.你的域名` |
| 商户平台网址 | `http://host.docker.internal:9218` | `https://mch.你的域名` |
| 运营平台网址 | `http://127.0.0.1:9217` | `https://mgr.你的域名` |
| 公共oss访问地址 | `http://127.0.0.1:9217/api/anon/localOssFiles` | `https://mgr.你的域名/api/anon/localOssFiles` |

**这四项必须填带 `https://` 的域名，不能带结尾斜杠。**

因为 `cache-config: false`，保存后立即生效，**不需要重启任何服务**。

这几个地址的实际用途，改错了会在不同地方出问题：

- **支付网关地址**
  - 易支付异步通知的 `notify_url` = `{paySiteUrl}/api/pay/notify/ezfp`
    → 填错则支付成功后订单**永远不会自动变成支付成功**（只能靠查单轮询兜底）；
  - 易支付同步跳转 `return_url`；
  - 扫码支付的二维码图片地址 = `{paySiteUrl}/api/scan/imgs/xxx.png`
    → 填错则**浏览器里二维码是裂图**；
  - 商户后台「支付测试」调支付网关下单、订单详情里的退款/关单按钮，都走它；
  - 收银台页面本身也在 `9226` 这个容器上，即 `{paySiteUrl}/cashier/index.html#/hub/...`。
- **商户平台网址** → 「支付测试」的回调地址 `{mchSiteUrl}/api/anon/paytestNotify/payOrder`，
  填错则测试下单后商户平台看不到支付结果。
- **公共oss访问地址** → 后台上传的图片（通道图标等）的访问地址。

---

## 五、上线后自查清单

```bash
# 1. 六个业务容器是否都 Up (healthy)
docker compose --env-file .env.prod -f docker-compose.prod.yml ps

# 2. 支付网关日志有没有 ezfp 通道
docker exec jeepay-payment ls /jeepayhomes/service/app
docker logs --tail 100 jeepay-payment

# 3. 公网地址能通（应返回 200/302，不是 502）
curl -I https://pay.你的域名
curl -I https://mch.你的域名
curl -I https://mgr.你的域名
```

然后在商户平台用 ezfp 通道做一笔**真实小额支付**，确认：

1. 扫码后能正常付款（二维码不是裂图）；
2. 付款后订单状态**自动**变为「支付成功」—— 这一步走通才说明 `notify_url` 配对了；
3. 支付网关日志里出现 `支付查询[ezfp]` 或异步通知记录。

> 若第 1 步二维码没出来、或第 2 步一直停在「支付中」，
> 基本可以断定是第四节的 `paySiteUrl` 没改对。

---

## 六、更新版本

```bash
# 本地
mvn clean package -DskipTests
docker build -t $REG/jeepay-payment:新TAG --build-arg BASE_IMAGE=eclipse-temurin:17-jre ./jeepay-payment
docker push $REG/jeepay-payment:新TAG

# 服务器
vi .env.prod                      # IMAGE_TAG 改成新 TAG
docker compose --env-file .env.prod -f docker-compose.prod.yml pull
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d
```

只改了后端、前端没动时，也可以用 `up -d payment` 只重启单个服务，
但 `IMAGE_TAG` 是全量的，没重新构建的前端镜像会因 tag 不存在而拉取失败 ——
**稳妥做法是六个一起推、一起拉**。

---

## 七、回滚

镜像 tag 就是版本号，改回旧 tag 重新 `up -d` 即可：

```bash
vi .env.prod          # IMAGE_TAG=3.2.9
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d
```

数据库结构变更没有自动回滚，涉及表结构的大版本升级前先 `mysqldump` 备份。
