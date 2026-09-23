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
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.jeequan.jeepay.core.constants.CS;
import com.jeequan.jeepay.core.entity.PayOrder;
import com.jeequan.jeepay.core.model.params.ezfp.EzfpNormalMchParams;
import com.jeequan.jeepay.pay.channel.IPayOrderQueryService;
import com.jeequan.jeepay.pay.model.MchAppConfigContext;
import com.jeequan.jeepay.pay.rqrs.msg.ChannelRetMsg;
import com.jeequan.jeepay.pay.service.ConfigContextQueryService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.TreeMap;

/*
 * 易支付 查单接口实现类
 *
 * 上游 /api/findorder 实测响应：
 *   {"code":200,"msg":"获取成功!","data":{"id":6721,"trade_no":"Y...","out_trade_no":"P...",
 *    "type":"alipay","name":"...","money":"0.01","status":0,...}}
 * 注意状态字段是数字 status(0-未支付, 1-已支付)，不存在 trade_status 字段；
 * 且响应不带 sign，无法验签，故只在状态明确为已支付时才判定成功，其余一律"支付中"，
 * 避免把未支付订单误判为已支付。
 *
 * @author jeepay
 * @site https://www.jeequan.com
 * @date 2026/9/22 10:00
 */
@Service
@Slf4j
public class EzfpPayOrderQueryService implements IPayOrderQueryService {

    @Autowired private ConfigContextQueryService configContextQueryService;

    @Override
    public String getIfCode() {
        return CS.IF_CODE.EZFP;
    }

    @Override
    public ChannelRetMsg query(PayOrder payOrder, MchAppConfigContext mchAppConfigContext) {

        EzfpNormalMchParams params = (EzfpNormalMchParams) configContextQueryService.queryNormalMchParams(
                mchAppConfigContext.getMchNo(), mchAppConfigContext.getAppId(), getIfCode());

        Map<String, Object> paramMap = new TreeMap<>();
        // 1:商户订单号, 2:系统订单号
        paramMap.put("order_no", payOrder.getPayOrderId());
        paramMap.put("type", 1);
        paramMap.put("sign", EzfpKit.getSign(paramMap, params.getKey()));

        String queryUrl = EzfpKit.getQueryUrl(params.getGatewayUrl()) + "?" + EzfpKit.buildQueryString(paramMap);

        String resStr;
        int httpStatus;
        try {
            log.info("支付查询[{}]请求：{}", getIfCode(), queryUrl);
            HttpResponse response = HttpUtil.createPost(queryUrl).timeout(60 * 1000).execute();
            httpStatus = response.getStatus();
            resStr = response.body();
            log.info("支付查询[{}]结果：HTTP {} {}", getIfCode(), httpStatus, resStr);
        } catch (Exception e) {
            log.error("请求易支付查单接口异常", e);
            return ChannelRetMsg.waiting();
        }

        if (StringUtils.isEmpty(resStr)) {
            return ChannelRetMsg.waiting();
        }

        JSONObject resObj;
        try {
            resObj = JSONObject.parseObject(resStr);
        } catch (Exception e) {
            // 查单只用于兜底，解析不了时保持"支付中"由下次轮询重试，不直接判定失败
            log.error("易支付查单返回报文解析失败：HTTP {} {}", httpStatus, resStr, e);
            return ChannelRetMsg.unknown("易支付查单返回报文解析失败");
        }

        if (resObj == null || resObj.getIntValue("code") != EzfpKit.CODE_SUCCESS) {
            return ChannelRetMsg.waiting();
        }

        // data 可能是对象，也可能是数组，统一取第一笔订单
        JSONObject orderData = resolveOrderData(resObj.get("data"));
        if (orderData == null) {
            return ChannelRetMsg.waiting();
        }

        String channelOrderId = StringUtils.defaultIfBlank(orderData.getString("trade_no"), orderData.getString("id"));

        // 仅在明确拿到成功标识时才判定成功。
        // 实测上游查单返回: {"code":200,"msg":"获取成功!","data":{...,"status":0}}
        // 状态字段是【数字】status(0-未支付, 1-已支付)，并非字符串 trade_status，
        // 因此这里同时兼容数字 status 与部分分支返回的 trade_status 字符串。
        String tradeStatus = orderData.getString("trade_status");
        String status = orderData.getString("status");
        if (EzfpKit.TRADE_SUCCESS.equalsIgnoreCase(tradeStatus) || "1".equals(status)) {
            return ChannelRetMsg.confirmSuccess(channelOrderId);
        }

        return ChannelRetMsg.waiting();
    }

    /** 兼容 data 为对象或数组两种返回形式 **/
    private JSONObject resolveOrderData(Object data) {
        if (data == null) {
            return null;
        }
        if (data instanceof JSONArray) {
            JSONArray array = (JSONArray) data;
            return array.isEmpty() ? null : array.getJSONObject(0);
        }
        if (data instanceof JSONObject) {
            return (JSONObject) data;
        }
        return null;
    }

}
