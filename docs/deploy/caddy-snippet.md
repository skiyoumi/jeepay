# 已有 Caddy 时的站点片段

把下面三段**追加到你现有的 Caddyfile 末尾**，不要新建文件、也不要加全局 `{}` 块
（全局块一个 Caddyfile 只能有一个，重复会启动失败）。

替换 `pay.example.com` / `mch.example.com` / `mgr.example.com` 为你的真实域名，
并确保 A 记录已解析到本机公网 IP。改完执行 `caddy reload --config /etc/caddy/Caddyfile`
或 `systemctl reload caddy`。

端口对应 `docker-compose.prod.yml` 里绑在 127.0.0.1 的三个 UI 容器：

| 域名 | 后端 | 作用 |
| --- | --- | --- |
| pay.xxx | 127.0.0.1:9226 | 收银台静态页 + `/api/` 反代到 payment(9216)。系统配置的「支付网关地址」填它；易支付的 `notify_url`、`return_url`、扫码二维码图片都走它 |
| mch.xxx | 127.0.0.1:9228 | 商户平台。系统配置的「商户平台网址」填它 |
| mgr.xxx | 127.0.0.1:9227 | 运营平台。系统配置的「运营平台网址」「公共oss访问地址」填它 |

```caddy
pay.example.com {
	encode gzip
	reverse_proxy 127.0.0.1:9226
}

mch.example.com {
	encode gzip
	reverse_proxy 127.0.0.1:9228
}

mgr.example.com {
	encode gzip
	reverse_proxy 127.0.0.1:9227
}
```

## 两点注意事项

1. **不要**在片段里重复写 `email`、`log`、`admin`、`acme_dns` 之类的全局选项，
   你现有的全局块会继续生效，证书签发不受影响。

2. Caddy 默认会带上 `X-Forwarded-For` / `X-Forwarded-Proto` / `X-Forwarded-Host`，
   而三个后端都已配置 `server.forward-headers-strategy: framework`，
   所以收银台 `return_url`、微信 H5 `redirect_url` 能正确拼出 `https://` 协议头。
   如果你现有配置里对这几个头做过 `header_up` 覆盖，请确认没有把它们清掉。

## 只想暴露一个域名？

Caddy 的 `reverse_proxy` 不能按路径拆到不同上游，需要用 `handle` 分派。
但 Jeepay 三个前端各自是独立的 Vue 应用（各自的静态资源路径、各自的 `/api/`），
挂在同一个域名下需要额外改写路径前缀，改造成本远高于多申请两个子域名 + 一张泛域名证书。
因此**推荐用三个子域名**。
