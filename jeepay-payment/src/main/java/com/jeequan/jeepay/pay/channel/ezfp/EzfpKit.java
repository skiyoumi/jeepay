/*
 * Copyright (c) 2021-2031, 河北计全科技有限公司 (https://www.jeequan.com & jeequan@126.com).
 * <p>
 * Licensed under the GNU LESSER GENERAL PUBLIC LICENSE 3.0;
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.gnu.org/licenses/lgpl.html
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jeequan.jeepay.pay.channel.ezfp;

import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/*
 * 易支付(彩虹易支付协议) 工具类
 *
 * 签名规则：参数按 ASCII 升序排列后拼接为 k1=v1&k2=v2... 形式(末尾无 &)，
 *          再在末尾直接拼接商户密钥(无 key= 前缀、无分隔符)，最后 MD5 取小写。
 *
 * 注意事项：
 *   1. 需剔除 sign、sign_type 以及空值参数；
 *   2. 本算法与 JeepayKit.getSign 不一致(后者是拼 "key=" + key 且输出大写)，故不能复用；
 *   3. 易支付下单接口的响应报文中不含 sign 字段，无法对响应验签，只能依靠回调验签。
 *
 * @author jeepay
 * @site https://www.jeequan.com
 * @date 2026/9/22 10:00
 */
@Slf4j
public class EzfpKit {

    private static final String CHARSET = "UTF-8";

    /** 支付方式(type 参数) */
    public static final String TYPE_ALIPAY = "alipay";
    public static final String TYPE_WXPAY = "wxpay";
    public static final String TYPE_QQPAY = "qqpay";

    /**
     * 查单接口(api/findorder)成功返回状态码
     */
    public static final int CODE_SUCCESS = 200;

    /**
     * 下单接口(mapi.php)成功返回状态码
     *
     * 踩坑记录：同一套协议里两个接口的成功码并不一致，
     *   mapi.php   成功 -> {"code":1,  "msg":"获取成功!","trade_no":..,"qrcode":..}
     *   findorder  成功 -> {"code":200,"msg":"获取成功!","data":{..}}
     * 二者失败时都返回 {"code":201,"msg":"..."}。
     * 早期实现统一按 200 判断，导致 mapi.php 明明下单成功(已返回二维码)却被判为失败，
     * 且把 msg 中的"获取成功!"当成错误信息展示给商户。
     * 部分分支把 mapi.php 也改成 200，故两个成功码都接受。
     */
    public static final int CODE_SUCCESS_MAPI = 1;

    /** 判断 mapi.php 下单是否成功 */
    public static boolean isPaySuccess(JSONObject resObj) {
        if (resObj == null) {
            return false;
        }
        int code = resObj.getIntValue("code");
        return code == CODE_SUCCESS_MAPI || code == CODE_SUCCESS;
    }

    /** 异步通知中的支付成功状态值 */
    public static final String TRADE_SUCCESS = "TRADE_SUCCESS";

    /** 签名类型 */
    public static final String SIGN_TYPE_MD5 = "MD5";

    /**
     * 计算签名
     *
     * @param map 待签名参数(可包含 sign/sign_type/空值，内部会自动剔除)
     * @param key 商户密钥
     */
    public static String getSign(Map<String, Object> map, String key) {

        // TreeMap 按参数名自然升序排列，等价于 ASCII 升序
        Map<String, Object> sortedParams = new TreeMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String paramName = entry.getKey();
            Object paramValue = entry.getValue();

            if (paramName == null || "sign".equals(paramName) || "sign_type".equals(paramName)) {
                continue;
            }
            if (paramValue == null || StringUtils.isEmpty(paramValue.toString())) {
                continue;
            }
            sortedParams.put(paramName, paramValue);
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : sortedParams.entrySet()) {
            if (sb.length() > 0) {
                sb.append("&");
            }
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }

        // 末尾直接拼接商户密钥，无分隔符
        sb.append(key);

        String signStr = sb.toString();
        log.info("易支付签名原文:{}", signStr);
        return md5(signStr, CHARSET);
    }

    /**
     * 计算 MD5 值(小写十六进制)
     */
    public static String md5(String value, String charset) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(value.getBytes(charset));

            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                String hex = Integer.toHexString(b & 0xff);
                if (hex.length() == 1) {
                    sb.append('0');
                }
                sb.append(hex);
            }
            return sb.toString();

        } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
            throw new RuntimeException("易支付签名计算失败", e);
        }
    }

    /** 规整网关地址，保证以 / 结尾 */
    public static String getGatewayUrl(String gatewayUrl) {
        if (StringUtils.isBlank(gatewayUrl)) {
            return gatewayUrl;
        }
        String url = gatewayUrl.trim();
        if (!url.endsWith("/")) {
            url = url + "/";
        }
        return url;
    }

    /** API 接口下单地址 **/
    public static String getMapiUrl(String gatewayUrl) {
        return getGatewayUrl(gatewayUrl) + "mapi.php";
    }

    /** 页面跳转下单地址 **/
    public static String getSubmitUrl(String gatewayUrl) {
        return getGatewayUrl(gatewayUrl) + "submit.php";
    }

    /** 单笔订单查询地址 **/
    public static String getQueryUrl(String gatewayUrl) {
        return getGatewayUrl(gatewayUrl) + "api/findorder";
    }

    /**
     * 构造 URL 查询串(按 UTF-8 对 k/v 做百分号编码)
     *
     * 不使用 JeepayKit.genUrlParams：该方法仅在值包含 "+" 时才编码，
     * 商品名中出现 & = # 空格 等字符会把查询串拆坏(易支付下单参数含中文商品名，风险较高)。
     *
     * 注意：仅用于拼装传输用的 URL，签名必须基于编码前的原始值计算。
     */
    public static String buildQueryString(Map<String, Object> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue().toString();
            if (sb.length() > 0) {
                sb.append("&");
            }
            sb.append(encode(entry.getKey())).append("=").append(encode(value));
        }
        return sb.toString();
    }

    /**
     * 构造自动提交的支付表单(供 submit.php 使用)
     *
     * 易支付官方文档与官方 Java Demo 均要求 submit.php 以 POST 提交
     * (Demo 直接输出一段自动 submit 的 &lt;form&gt;)，故页面跳转类支付在
     * payDataType=form 时返回本表单内容，由前端渲染后自动跳转。
     *
     * 注意：表单值必须是【未编码的原始值】，URL 编码由浏览器提交时自行完成；
     *      此处只做 HTML 属性转义，否则商品名中的 &amp; &lt; &gt; " 会破坏表单结构。
     */
    public static String buildSubmitForm(String gatewayUrl, Map<String, Object> params) {
        StringBuilder sb = new StringBuilder();
        sb.append("<form id='ezfpPayForm' action='").append(escapeHtml(getSubmitUrl(gatewayUrl)))
                .append("' method='post'>");
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue().toString();
            sb.append("<input type='hidden' name='").append(escapeHtml(entry.getKey()))
                    .append("' value='").append(escapeHtml(value)).append("'/>");
        }
        sb.append("</form>")
                .append("<script>document.forms['ezfpPayForm'].submit();</script>");
        return sb.toString();
    }

    /** HTML 属性转义：商品名等文本可能含 & < > " ' ，不转义会把表单结构破坏掉 */
    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /** UTF-8 百分号编码，编码失败时退回原值，避免因个别字符导致整个下单流程失败 */
    private static String encode(String value) {
        if (value == null) {
            return "";
        }
        try {
            return URLEncoder.encode(value, CHARSET);
        } catch (UnsupportedEncodingException e) {
            log.error("易支付参数编码失败, value={}", value, e);
            return value;
        }
    }

    /**
     * 把渠道返回的二维码图片地址补全为绝对地址
     * (易支付的 code_url 可能是不带域名的相对路径，如 Payewm.jpg)
     */
    public static String toAbsoluteUrl(String gatewayUrl, String path) {
        if (StringUtils.isBlank(path)) {
            return path;
        }
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        String baseUrl = getGatewayUrl(gatewayUrl);
        if (baseUrl == null) {
            return path;
        }
        return baseUrl + StringUtils.removeStart(path, "/");
    }

}
