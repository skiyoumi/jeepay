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
package com.jeequan.jeepay.pay.channel.ezfp.payway;

import com.jeequan.jeepay.core.constants.CS;
import com.jeequan.jeepay.core.entity.PayOrder;
import com.jeequan.jeepay.core.model.params.ezfp.EzfpNormalMchParams;
import com.jeequan.jeepay.pay.channel.ezfp.EzfpKit;
import com.jeequan.jeepay.pay.channel.ezfp.EzfpPaymentService;
import com.jeequan.jeepay.pay.model.MchAppConfigContext;
import com.jeequan.jeepay.pay.rqrs.AbstractRS;
import com.jeequan.jeepay.pay.rqrs.msg.ChannelRetMsg;
import com.jeequan.jeepay.pay.rqrs.payorder.UnifiedOrderRQ;
import com.jeequan.jeepay.pay.rqrs.payorder.payway.WxH5OrderRQ;
import com.jeequan.jeepay.pay.rqrs.payorder.payway.WxH5OrderRS;
import com.jeequan.jeepay.pay.util.ApiResBuilder;
import org.springframework.stereotype.Service;

import java.util.Map;

/*
 * 易支付 微信H5支付(页面跳转)
 *
 * 注意：与 AliWap 一致，走 submit.php 页面跳转接口，不预先调用渠道接口。
 *      上游 submit.php 官方要求 POST，故：
 *        payDataType=form  -> 返回自动提交表单(formContent)，推荐
 *        其它              -> 返回带查询串的跳转地址(payUrl)，若上游拒绝 GET 请改用 form
 *
 * @author jeepay
 * @site https://www.jeequan.com
 * @date 2026/9/22 10:00
 */
@Service("ezfpPaymentByWxH5Service") //Service Name需保持全局唯一性
public class WxH5 extends EzfpPaymentService {

    @Override
    public String preCheck(UnifiedOrderRQ rq, PayOrder payOrder) {
        return null;
    }

    @Override
    public AbstractRS pay(UnifiedOrderRQ rq, PayOrder payOrder, MchAppConfigContext mchAppConfigContext) {

        WxH5OrderRQ bizRQ = (WxH5OrderRQ) rq;
        EzfpNormalMchParams params = getEzfpParams(mchAppConfigContext);

        // 构造函数响应数据
        WxH5OrderRS res = ApiResBuilder.buildSuccess(WxH5OrderRS.class);
        ChannelRetMsg channelRetMsg = new ChannelRetMsg();
        res.setChannelRetMsg(channelRetMsg);

        Map<String, Object> paramMap = buildCommonParams(payOrder, params, EzfpKit.TYPE_WXPAY);
        paramMap.put("sign", EzfpKit.getSign(paramMap, params.getKey()));

        if (CS.PAY_DATA_TYPE.FORM.equals(bizRQ.getPayDataType())) {
            // 表单方式：前端渲染该 HTML 后自动以 POST 提交到 submit.php
            res.setFormContent(EzfpKit.buildSubmitForm(params.getGatewayUrl(), paramMap));
        } else {
            // 默认返回页面跳转地址，前端可直接打开或复制到手机端打开
            res.setPayUrl(EzfpKit.getSubmitUrl(params.getGatewayUrl()) + "?" + EzfpKit.buildQueryString(paramMap));
        }
        channelRetMsg.setChannelState(ChannelRetMsg.ChannelState.WAITING);
        return res;
    }

}
