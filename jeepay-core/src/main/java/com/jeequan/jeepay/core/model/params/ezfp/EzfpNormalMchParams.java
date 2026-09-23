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
package com.jeequan.jeepay.core.model.params.ezfp;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.jeequan.jeepay.core.model.params.NormalMchParams;
import com.jeequan.jeepay.core.utils.StringKit;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

/*
 * 易支付 普通商户参数定义
 *
 * 注意：类名必须为 EzfpNormalMchParams，
 * 因为 NormalMchParams.factory 是按 "<ifCode首字母大写>NormalMchParams" 反射加载的。
 *
 * @author jeepay
 * @site https://www.jeequan.com
 * @date 2026/9/22 10:00
 */
@Data
public class EzfpNormalMchParams extends NormalMchParams {

    /** 商户ID(易支付后台的 PID) */
    private String pid;

    /** 商户密钥 */
    private String key;

    /** 支付网关地址, 例如 https://www.ezfpy.cn (不带结尾斜杠亦可) */
    private String gatewayUrl;

    @Override
    public String deSenData() {
        EzfpNormalMchParams mchParams = this;
        if (StringUtils.isNotBlank(this.key)) {
            mchParams.setKey(StringKit.str2Star(this.key, 4, 4, 6));
        }
        return ((JSONObject) JSON.toJSON(mchParams)).toJSONString();
    }

}
