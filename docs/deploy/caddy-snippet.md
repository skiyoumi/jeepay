# 追加到已有 Caddy 的站点片段

⚠️ 你的 Caddy 是**容器**（示例里叫 `sub2api-caddy`），不是宿主机服务。这带来两个和网上教程不一样的点：

## 一、上游地址不能写 127.0.0.1

Caddy 容器里的 `127.0.0.1` 是它**自己**，不是宿主机，所以 `reverse_proxy 127.0.0.1:9226`
必然连不上。正确做法是把 Caddy 容器接到 jeepay 的 Docker 网络上，用**容器名**当上游。

jeepay 的编排文件已经把这个网络固定命名为 `jeepay-net`，执行一次：

```bash
docker network connect jeepay-net sub2api-caddy
```

> 这一步是即时的，容器不用重启。但 `sub2api-caddy` 被**重建**（比如你重跑 sub2api 那套
> `docker compose up -d --force-recreate`）后连接会丢失，需要重新执行。
> 想一劳永逸，就在 sub2api 的 compose 里加一段：
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

验证连通（在 Caddy 容器内 ping 一下 UI 容器）：

```bash
docker exec sub2api-caddy nslookup jeepay-ui-payment
```

## 二、找到 Caddyfile 并追加这三段

Caddy 容器的配置文件在宿主机上的位置：

```bash
docker inspect sub2api-caddy --format '{{range .Mounts}}{{.Source}} -> {{.Destination}}{{"\n"}}{{end}}'
```

找到映射到 `/etc/caddy` 或 Caddyfile 的那条，编辑宿主机上的文件，
**追加到末尾**（不要新建文件、不要重复写全局 `{}` 块 —— 一个 Caddyfile 只能有一个全局块，
重复会导致启动失败）：

```caddy
pay.example.com {
	encode gzip
	reverse_proxy jeepay-ui-payment:80
}

mch.example.com {
	encode gzip
	reverse_proxy jeepay-ui-merchant:80
}

mgr.example.com {
	encode gzip
	reverse_proxy jeepay-ui-manager:80
}
```

把 `example.com` 换成你的真实域名，确保 A 记录已解析到这台服务器的公网 IP
（80/443 已经在 `sub2api-caddy` 上发布了，不用再动）。

重载：

```bash
docker exec sub2api-caddy caddy reload --config /etc/caddy/Caddyfile
```

> 配置文件的**容器内路径**以 `docker inspect` 输出为准，常见是 `/etc/caddy/Caddyfile`
> 或 `/etc/caddy/Caddyfile.d/xxx`。`reload` 用错路径会报错，按实际输出调整。
> 实在不确定就直接 `docker restart sub2api-caddy`（会短暂中断它现在代理的服务）。

## 三、端口对应关系

| 域名 | 上游容器 | 作用 |
| --- | --- | --- |
| pay.xxx | `jeepay-ui-payment:80` | 收银台静态页 + `/api/` 反代到 payment(9216)。系统配置的「支付网关地址」填它；易支付的 `notify_url`、`return_url`、扫码二维码图片都走它 |
| mch.xxx | `jeepay-ui-merchant:80` | 商户平台。系统配置的「商户平台网址」填它 |
| mgr.xxx | `jeepay-ui-manager:80` | 运营平台。系统配置的「运营平台网址」「公共oss访问地址」填它 |

注意上游写的是 **80**（容器内 nginx 的端口），不是宿主机的 9226/9228/9227 ——
那三个端口只绑在宿主机的 `127.0.0.1` 上，是给你不上 Caddy 时本机调试用的，Caddy 用不到。

## 四、X-Forwarded-Proto

Caddy 默认就会带上 `X-Forwarded-For` / `X-Forwarded-Proto` / `X-Forwarded-Host`，
jeepay 三个后端都配了 `server.forward-headers-strategy: framework`，
ui-* 里挂载的 nginx 模板也补了 `proxy_set_header X-Forwarded-Proto $scheme;`，
所以收银台 `return_url`、微信 H5 `redirect_url` 能正确拼出 `https://` 协议头。

如果你现有的 Caddyfile 里对这几个头做过 `header_up` 覆盖（比如 `header_up X-Forwarded-Proto http`），
请把这行去掉，否则支付回调地址会退化成 http。

## 五、只想暴露一个域名？

Jeepay 三个前端各自是独立的 Vue 应用（各自的静态资源路径、各自的 `/api/`），
挂在同一个域名下要用 `handle` 按路径分派并改写路径前缀，改造成本远高于
多申请两个子域名 + 一张泛域名证书。**推荐用三个子域名。**
