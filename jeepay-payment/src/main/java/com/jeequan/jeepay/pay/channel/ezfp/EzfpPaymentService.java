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

import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import com.alibaba.fastjson.JSONObject;
import com.jeequan.jeepay.core.constants.CS;
import com.jeequan.jeepay.core.entity.PayOrder;
import com.jeequan.jeepay.core.model.params.ezfp.EzfpNormalMchParams;
import com.jeequan.jeepay.core.utils.AmountUtil;
import com.jeequan.jeepay.pay.channel.AbstractPaymentService;
import com.jeequan.jeepay.pay.model.MchAppConfigContext;
import com.jeequan.jeepay.pay.rqrs.AbstractRS;
import com.jeequan.jeepay.pay.rqrs.msg.ChannelRetMsg;
import com.jeequan.jeepay.pay.rqrs.payorder.CommonPayDataRS;
import com.jeequan.jeepay.pay.rqrs.payorder.UnifiedOrderRQ;
import com.jeequan.jeepay.pay.util.PaywayUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.TreeMap;

/*
 * 支付接口： 易支付(彩虹易支付协议)
 * 支付方式： 二维码(ALI_QR / WX_NATIVE) 、 页面跳转(ALI_WAP / WX_H5)
 *
 * 说明：本渠道上游无退款接口，故未实现 IRefundService，
 *      运营/商户后台对该渠道发起退款会因找不到 <ifCode>RefundService Bean 而报错，属预期行为。
 *
 * @author jeepay
 * @site https://www.jeequan.com
 * @date 2026/9/22 10:00
 */
@Service
@Slf4j
public class EzfpPaymentService extends AbstractPaymentService {

    @Override
    public String getIfCode() {
        return CS.IF_CODE.EZFP;
    }

    @Override
    public boolean isSupport(String wayCode) {
        return true;
    }

    @Override
    public String preCheck(UnifiedOrderRQ rq, PayOrder payOrder) {
        return PaywayUtil.getRealPaywayService(this, payOrder.getWayCode()).preCheck(rq, payOrder);
    }

    @Override
    public AbstractRS pay(UnifiedOrderRQ rq, PayOrder payOrder, MchAppConfigContext mchAppConfigContext) throws Exception {
        return PaywayUtil.getRealPaywayService(this, payOrder.getWayCode()).pay(rq, payOrder, mchAppConfigContext);
    }

    /** 查询当前商户的易支付参数 **/
    protected EzfpNormalMchParams getEzfpParams(MchAppConfigContext mchAppConfigContext) {
        return (EzfpNormalMchParams) configContextQueryService.queryNormalMchParams(
                mchAppConfigContext.getMchNo(), mchAppConfigContext.getAppId(), getIfCode());
    }

    /**
     * 构造易支付公共请求参数
     *
     * @param type 易支付支付方式: alipay / wxpay / qqpay
     */
    protected Map<String, Object> buildCommonParams(PayOrder payOrder, EzfpNormalMchParams params, String type) {

        Map<String, Object> paramMap = new TreeMap<>();
        paramMap.put("pid", params.getPid());
        paramMap.put("type", type);
        paramMap.put("out_trade_no", payOrder.getPayOrderId());
        paramMap.put("notify_url", getNotifyUrl(payOrder.getPayOrderId()));
        paramMap.put("return_url", getReturnUrl(payOrder.getPayOrderId()));
        paramMap.put("name", StringUtils.defaultIfBlank(payOrder.getSubject(), "商品"));
        // 易支付按【元】计价，Jeepay 内部以【分】存储，需转换
        paramMap.put("money", AmountUtil.convertCent2Dollar(payOrder.getAmount()));
        paramMap.put("sitename", StringUtils.defaultIfBlank(payOrder.getSubject(), "Jeepay"));
        paramMap.put("sign_type", EzfpKit.SIGN_TYPE_MD5);
        return paramMap;
    }

    /**
     * 调用易支付 mapi.php 接口下单
     *
     * 关于失败时的状态判定(易支付与其它渠道的差异，容易踩坑)：
     *   1. 上游在【商户不存在/密钥错误/签名错误】等场景下不会返回 JSON 业务错误码，
     *      而是直接抛 PHP 500 + HTML 错误页。此时无法断定上游是否已建单，
     *      若判为 CONFIRM_FAIL 会把本地订单置为"支付失败"，与上游真实状态不一致；
     *      故统一归为 API_RET_ERROR / UNKNOWN —— 框架会把订单置为"支付中"，
     *      后续既能被异步通知纠正，也能被查单轮询纠正（已实测可自愈）。
     *   2. 只有上游【正常返回 JSON 且 code 不是成功码】时，才认为渠道明确拒绝了本次下单，
     *      此时判为 CONFIRM_FAIL 才是安全的。
     *      注意：mapi.php 的成功码是 1(不是 200)，详见 EzfpKit.isPaySuccess。
     *
     * @return 下单成功返回响应 JSONObject；失败返回 null，并已写入 channelRetMsg
     */
    protected JSONObject doApiPay(PayOrder payOrder, MchAppConfigContext mchAppConfigContext, String type, ChannelRetMsg channelRetMsg) {

        EzfpNormalMchParams params = getEzfpParams(mchAppConfigContext);

        Map<String, Object> paramMap = buildCommonParams(payOrder, params, type);
        paramMap.put("sign", EzfpKit.getSign(paramMap, params.getKey()));

        String payUrl = EzfpKit.getMapiUrl(params.getGatewayUrl()) + "?" + EzfpKit.buildQueryString(paramMap);

        String resStr;
        int httpStatus;
        try {
            log.info("发起支付[{}]请求：{}", getIfCode(), payUrl);
            HttpResponse response = HttpUtil.createPost(payUrl).timeout(60 * 1000).execute();
            httpStatus = response.getStatus();
            resStr = response.body();
            log.info("发起支付[{}]结果：HTTP {} {}", getIfCode(), httpStatus, resStr);
        } catch (Exception e) {
            // 超时/网络异常：请求可能已送达上游，状态不明确
            log.error("请求易支付下单接口异常", e);
            channelRetMsg.setChannelState(ChannelRetMsg.ChannelState.UNKNOWN);
            channelRetMsg.setChannelErrMsg("请求易支付下单接口异常");
            return null;
        }

        if (StringUtils.isEmpty(resStr)) {
            channelRetMsg.setChannelState(ChannelRetMsg.ChannelState.API_RET_ERROR);
            channelRetMsg.setChannelErrMsg("易支付下单接口无响应, HTTP " + httpStatus);
            return null;
        }

        JSONObject resObj;
        try {
            resObj = JSONObject.parseObject(resStr);
        } catch (Exception e) {
            // 非 JSON(典型为 500 + HTML)：上游拒绝了请求但无法确定是否已建单，按"支付中"处理
            log.error("易支付下单返回报文解析失败：HTTP {} {}", httpStatus, resStr, e);
            channelRetMsg.setChannelState(ChannelRetMsg.ChannelState.API_RET_ERROR);
            channelRetMsg.setChannelErrMsg(String.format("易支付下单返回非JSON报文(HTTP %d): %s",
                    httpStatus, StringUtils.abbreviate(resStr, 200)));
            return null;
        }

        // 上游明确拒绝：返回了 JSON 且 code 不是成功码，此时可安全判定为失败
        if (!EzfpKit.isPaySuccess(resObj)) {
            String errMsg = resObj == null ? "易支付下单返回报文为空" : resObj.getString("msg");
            channelRetMsg.setChannelState(ChannelRetMsg.ChannelState.CONFIRM_FAIL);
            channelRetMsg.setChannelErrMsg(StringUtils.defaultIfBlank(errMsg, "易支付下单失败"));
            return null;
        }

        // 校验下单金额，避免金额被篡改(响应无签名，此处只能防呆)
        String resMoney = resObj.getString("money");
        String expectMoney = AmountUtil.convertCent2Dollar(payOrder.getAmount());
        if (StringUtils.isNotBlank(resMoney) && !resMoney.equals(expectMoney)) {
            log.error("易支付下单金额不一致, payOrderId={}, 渠道={}, 订单={}", payOrder.getPayOrderId(), resMoney, expectMoney);
            channelRetMsg.setChannelState(ChannelRetMsg.ChannelState.CONFIRM_FAIL);
            channelRetMsg.setChannelErrMsg("易支付下单金额与订单金额不一致");
            return null;
        }

        channelRetMsg.setChannelOrderId(resObj.getString("trade_no"));
        channelRetMsg.setChannelOriginResponse(resStr);
        channelRetMsg.setChannelState(ChannelRetMsg.ChannelState.WAITING);
        return resObj;
    }

    /**
     * 从易支付响应中提取二维码数据填充响应对象
     *
     * 易支付返回两个可能的二维码字段：
     *   qrcode   — 待生成二维码的支付链接(推荐，由 Jeepay 或前端渲染成二维码图片)
     *   code_url — 渠道直接给出的二维码图片地址，可能是相对路径(如 Payewm.jpg)
     *
     * @return true 表示已填充成功
     */
    protected boolean fillQrCode(CommonPayDataRS res, String reqPayDataType, JSONObject resObj, String gatewayUrl) {

        String qrcode = resObj.getString("qrcode");
        String codeUrl = resObj.getString("code_url");
        String absCodeImgUrl = StringUtils.isNotBlank(codeUrl) ? EzfpKit.toAbsoluteUrl(gatewayUrl, codeUrl) : null;

        // 商户要求返回二维码图片地址
        if (CS.PAY_DATA_TYPE.CODE_IMG_URL.equals(reqPayDataType)) {
            if (StringUtils.isNotBlank(absCodeImgUrl)) {
                res.setCodeImgUrl(absCodeImgUrl);
                return true;
            }
            if (StringUtils.isNotBlank(qrcode)) {
                res.setCodeImgUrl(sysConfigService.getDBApplicationConfig().genScanImgUrl(qrcode));
                return true;
            }
            return false;
        }

        // 默认返回二维码内容(待生成二维码的链接)
        if (StringUtils.isNotBlank(qrcode)) {
            res.setCodeUrl(qrcode);
            return true;
        }
        if (StringUtils.isNotBlank(absCodeImgUrl)) {
            res.setCodeImgUrl(absCodeImgUrl);
            return true;
        }
        return false;
    }

}
