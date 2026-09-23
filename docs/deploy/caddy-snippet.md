# 追加到已有 Caddy 的站点片段

针对你这台服务器的实际情况（Caddy 是容器 `sub2api-caddy`，Caddyfile 在
`/usr/local/modelscube/deploy/Caddyfile`，用自己的证书而非 Let's Encrypt 自动签）。

---

## 一、上游不能用 `127.0.0.1`，也不能用 `host.docker.internal`

Caddy 是容器：

- `127.0.0.1` 指 **Caddy 容器自己**，连不到 jeepay；
- `host.docker.internal` 虽然你 Caddyfile 里对宿主机服务是这么用的
  （`hermes.modelscube.com`、`aiorder.modelscube.com`），但**对 jeepay 不适用** ——
  jeepay 的三个 UI 端口绑在宿主机的 `127.0.0.1`（见 `docker-compose.prod.yml`），
  容器经 `host.docker.internal` 过来走的是 docker 网桥网卡而不是回环网卡，
  会被拒绝连接。

所以用**容器名**上游，这也是你已经在用的方式（`sub2api:8080`、`k12-backend:3000`）。

jeepay 的编排文件把网络固定命名为 `jeepay-net`，接一次即可：

```bash
docker network connect jeepay-net sub2api-caddy

# 验证（应能解析出 IP）
docker exec sub2api-caddy nslookup jeepay-ui-payment
```

> ⚠️ `sub2api-caddy` 每次被**重建**（重跑 sub2api 那套 compose）后这个连接会丢失，
> 需要重新执行。想一劳永逸，在 sub2api 的 compose 里加：
>
> ```yaml
> services:
>   caddy:
>     networks:
>       - default
>       - jeepay-net
>
> networks:
>   jeepay-net:
>     external: true
> ```

---

## 二、追加站点配置

编辑宿主机上的 `/usr/local/modelscube/deploy/Caddyfile`，**追加到末尾**：

```caddy
# ============================================================================
# Jeepay 支付平台
# ============================================================================
pay.modelscube.com {
    tls /etc/caddy/ssl/server.crt /etc/caddy/ssl/server.key

    reverse_proxy jeepay-ui-payment:80 {
        header_up X-Real-IP {remote_host}
        header_up X-Forwarded-For {remote_host}
        header_up X-Forwarded-Proto https
        header_up X-Forwarded-Host {host}
    }
}

mch.modelscube.com {
    tls /etc/caddy/ssl/server.crt /etc/caddy/ssl/server.key

    reverse_proxy jeepay-ui-merchant:80 {
        header_up X-Real-IP {remote_host}
        header_up X-Forwarded-For {remote_host}
        header_up X-Forwarded-Proto https
        header_up X-Forwarded-Host {host}
    }
}

mgr.modelscube.com {
    tls /etc/caddy/ssl/server.crt /etc/caddy/ssl/server.key

    reverse_proxy jeepay-ui-manager:80 {
        header_up X-Real-IP {remote_host}
        header_up X-Forwarded-For {remote_host}
        header_up X-Forwarded-Proto https
        header_up X-Forwarded-Host {host}
    }
}
```

上游写的是容器内 nginx 的 **80**，**不是**宿主机的 9226/9228/9227 ——
那三个端口只绑在宿主机 `127.0.0.1`，是给你不上 Caddy 时本机调试用的。

重载：

```bash
docker exec sub2api-caddy caddy reload --config /etc/caddy/Caddyfile
```

> 已有的 `:80 { redir https://{host}{uri} }` 兜底块**不用动**，它会自动照管这三个新域名的
> http 跳转，与其它站点块共存的方式和你现在完全一致。

---

## 三、先确认证书覆盖这三个域名

你用的是自己的证书，**证书里没有这三个域名的话 Caddy 能起来但浏览器会报证书错误**
（Caddy 不会像 Let's Encrypt 那样自动签）：

```bash
openssl x509 -in /usr/local/modelscube/deploy/ssl/server.crt -noout -text \
  | grep -A1 "Subject Alternative Name"
```

输出里要有 `*.modelscube.com`（或把 pay/mch/mgr 三个具体域名都列出来）。
你现有 `hermes`、`aiorder`、`brain` 都是 `*.modelscube.com` 下的，大概率通配符已覆盖 ——
确认一下即可。

如果不覆盖，两个办法：

- 重新签一张带 `*.modelscube.com` 的证书，替换 `deploy/ssl/server.crt` / `server.key`；
- 或者直接用 Caddy 自动申请（**删掉 `tls` 那行**，并把 `{ email ... }` 全局块（如果已有）配好）。
  但混用两种签发方式容易乱，建议还是补证书。

另外，不管哪种方式，**A 记录都要先把 pay/mch/mgr 三个子域名解析到这台服务器**。

---

## 四、X-Forwarded-Proto 的正确处理

Caddy 这里 `header_up X-Forwarded-Proto https` 是**必须保留**的。

jeepay 后端配了 `server.forward-headers-strategy: framework`，靠这个头拼出
收银台 `return_url` 和微信 H5 `redirect_url` 的协议。链路是：

```
浏览器 --https--> Caddy --http--> nginx(ui-*) --http--> 后端
                                  ▲
                    这里【不能】覆盖 X-Forwarded-Proto
```

`conf/nginx/default.conf.template` 里特地**没有**写
`proxy_set_header X-Forwarded-Proto $scheme;` ——
因为 nginx 收到的是 Caddy 发来的 http，`$scheme` 恒为 `http`，
写了就会把 Caddy 设好的 `https` 覆盖掉。不写则保持 nginx 默认行为（原样透传）。

---

## 五、只想暴露两个域名？

运营平台 `mgr` 完全可以不挂公网（内网访问更安全），删掉第三段即可，
但系统配置里的「运营平台网址」「公共oss访问地址」仍要填一个能访问到的地址 ——
可以填 `https://mgr.modelscube.com` 但改用 IP 白名单，或换成内网地址。

三个前端各自是独立的 Vue 应用（各自的静态资源路径、各自的 `/api/`），
想合并到一个域名下要用 `handle` 按路径分派并改写路径前缀，改造成本高，不推荐。
