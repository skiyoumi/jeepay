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

import com.alibaba.fastjson.JSONObject;
import com.jeequan.jeepay.core.entity.PayOrder;
import com.jeequan.jeepay.core.model.params.ezfp.EzfpNormalMchParams;
import com.jeequan.jeepay.pay.channel.ezfp.EzfpKit;
import com.jeequan.jeepay.pay.channel.ezfp.EzfpPaymentService;
import com.jeequan.jeepay.pay.model.MchAppConfigContext;
import com.jeequan.jeepay.pay.rqrs.AbstractRS;
import com.jeequan.jeepay.pay.rqrs.msg.ChannelRetMsg;
import com.jeequan.jeepay.pay.rqrs.payorder.UnifiedOrderRQ;
import com.jeequan.jeepay.pay.rqrs.payorder.payway.AliQrOrderRQ;
import com.jeequan.jeepay.pay.rqrs.payorder.payway.AliQrOrderRS;
import com.jeequan.jeepay.pay.util.ApiResBuilder;
import org.springframework.stereotype.Service;

/*
 * 易支付 支付宝二维码支付(主扫)
 *
 * @author jeepay
 * @site https://www.jeequan.com
 * @date 2026/9/22 10:00
 */
@Service("ezfpPaymentByAliQrService") //Service Name需保持全局唯一性
public class AliQr extends EzfpPaymentService {

    @Override
    public String preCheck(UnifiedOrderRQ rq, PayOrder payOrder) {
        return null;
    }

    @Override
    public AbstractRS pay(UnifiedOrderRQ rq, PayOrder payOrder, MchAppConfigContext mchAppConfigContext) {

        AliQrOrderRQ bizRQ = (AliQrOrderRQ) rq;
        EzfpNormalMchParams params = getEzfpParams(mchAppConfigContext);

        // 构造函数响应数据
        AliQrOrderRS res = ApiResBuilder.buildSuccess(AliQrOrderRS.class);
        ChannelRetMsg channelRetMsg = new ChannelRetMsg();
        res.setChannelRetMsg(channelRetMsg);

        JSONObject resObj = doApiPay(payOrder, mchAppConfigContext, EzfpKit.TYPE_ALIPAY, channelRetMsg);
        if (resObj == null) {
            res.setErrCode(channelRetMsg.getChannelErrCode());
            res.setErrMsg(channelRetMsg.getChannelErrMsg());
            return res;
        }

        if (!fillQrCode(res, bizRQ.getPayDataType(), resObj, params.getGatewayUrl())) {
            channelRetMsg.setChannelState(ChannelRetMsg.ChannelState.CONFIRM_FAIL);
            channelRetMsg.setChannelErrMsg("易支付未返回二维码数据");
            res.setErrMsg(channelRetMsg.getChannelErrMsg());
        }
        return res;
    }

}
