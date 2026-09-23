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
import com.jeequan.jeepay.core.constants.CS;
import com.jeequan.jeepay.core.entity.PayOrder;
import com.jeequan.jeepay.core.exception.ResponseException;
import com.jeequan.jeepay.core.model.params.ezfp.EzfpNormalMchParams;
import com.jeequan.jeepay.core.utils.AmountUtil;
import com.jeequan.jeepay.pay.channel.AbstractChannelNoticeService;
import com.jeequan.jeepay.pay.model.MchAppConfigContext;
import com.jeequan.jeepay.pay.rqrs.msg.ChannelRetMsg;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.MutablePair;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletRequest;

/*
 * 易支付 支付回调接口实现类
 *
 * 回调为 GET/POST 表单形式(非 JSON)，参数：pid、trade_no、out_trade_no、type、
 * name、money、trade_status、sign、sign_type。
 * 只有 trade_status = TRADE_SUCCESS 表示支付成功；
 * 验签通过后需返回纯文本 success，否则渠道会持续重发通知。
 *
 * @author jeepay
 * @site https://www.jeequan.com
 * @date 2026/9/22 10:00
 */
@Service
@Slf4j
public class EzfpChannelNoticeService extends AbstractChannelNoticeService {

    @Override
    public String getIfCode() {
        return CS.IF_CODE.EZFP;
    }

    @Override
    public MutablePair<String, Object> parseParams(HttpServletRequest request, String urlOrderId, NoticeTypeEnum noticeTypeEnum) {

        try {
            JSONObject params = getReqParamJSON();
            // 易支付回调的 out_trade_no 即 Jeepay 的支付订单号
            String payOrderId = params.getString("out_trade_no");
            return MutablePair.of(payOrderId, params);

        } catch (Exception e) {
            log.error("易支付回调参数解析失败", e);
            throw ResponseException.buildText("ERROR");
        }
    }

    @Override
    public ChannelRetMsg doNotice(HttpServletRequest request, Object params, PayOrder payOrder, MchAppConfigContext mchAppConfigContext, NoticeTypeEnum noticeTypeEnum) {

        try {
            EzfpNormalMchParams ezfpParams = (EzfpNormalMchParams) configContextQueryService.queryNormalMchParams(
                    mchAppConfigContext.getMchNo(), mchAppConfigContext.getAppId(), getIfCode());

            JSONObject jsonParams = (JSONObject) params;

            // 验签(签名算法内部会自动剔除 sign、sign_type 与空值)
            String checkSign = jsonParams.getString("sign");
            if (StringUtils.isBlank(checkSign)
                    || !EzfpKit.getSign(jsonParams, ezfpParams.getKey()).equalsIgnoreCase(checkSign.trim())) {
                log.error("易支付回调验签失败, payOrderId={}", payOrder.getPayOrderId());
                throw ResponseException.buildText("ERROR");
            }

            // 校验金额，防止金额被篡改
            String notifyMoney = jsonParams.getString("money");
            String expectMoney = AmountUtil.convertCent2Dollar(payOrder.getAmount());
            if (StringUtils.isBlank(notifyMoney) || !notifyMoney.equals(expectMoney)) {
                log.error("易支付回调金额不一致, payOrderId={}, 回调={}, 订单={}",
                        payOrder.getPayOrderId(), notifyMoney, expectMoney);
                throw ResponseException.buildText("ERROR");
            }

            ChannelRetMsg result = new ChannelRetMsg();
            result.setChannelOrderId(jsonParams.getString("trade_no"));
            // 易支付要求回调响应固定返回 success
            ResponseEntity okResponse = textResp("success");
            result.setResponseEntity(okResponse);

            // 默认支付中，仅 TRADE_SUCCESS 判定为支付成功
            result.setChannelState(ChannelRetMsg.ChannelState.WAITING);
            if (EzfpKit.TRADE_SUCCESS.equalsIgnoreCase(jsonParams.getString("trade_status"))) {
                result.setChannelState(ChannelRetMsg.ChannelState.CONFIRM_SUCCESS);
            }

            return result;

        } catch (Exception e) {
            log.error("易支付回调处理失败", e);
            throw ResponseException.buildText("ERROR");
        }
    }

}
