package com.wangzan101.mika.settings

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Each vendor's official page for requesting an API key, recognized by the host of baseUrl: whether a preset was chosen or the official address was typed in under custom, a direct link can be provided.
 * Only addresses stated in official docs/consoles are included, following the [ProviderPresets] list; unrecognized ones give null and no link is shown in the UI.
 */
object ProviderKeySites {
    private val byHost: Map<String, String> = mapOf(
        "api.deepseek.com" to "https://platform.deepseek.com/api_keys",
        "dashscope.aliyuncs.com" to "https://bailian.console.aliyun.com/#/api-key",
        "api.moonshot.cn" to "https://platform.moonshot.cn/console/api-keys",
        "open.bigmodel.cn" to "https://bigmodel.cn/usercenter/proj-mgmt/apikeys",
        "api.siliconflow.cn" to "https://cloud.siliconflow.cn/account/ak",
        "ark.cn-beijing.volces.com" to "https://ark.volcengine.com/region:cn-beijing/openManagement?advancedActiveKey=model",
        "api.minimax.cn" to "https://platform.minimaxi.com/user-center/basic-information/interface-key",
        "api.stepfun.com" to "https://platform.stepfun.com/interface-key",
        "tokenhub.tencentmaas.com" to "https://console.cloud.tencent.com/hunyuan/api-key",
        "qianfan.baidubce.com" to "https://console.bce.baidu.com/qianfan/ais/console/apiKey",
        "openrouter.ai" to "https://openrouter.ai/settings/keys",
        "api.orcarouter.ai" to "https://www.orcarouter.ai/console/token",
        "api.anthropic.com" to "https://console.anthropic.com/settings/keys",
        "generativelanguage.googleapis.com" to "https://aistudio.google.com/app/apikey",
        "api.openai.com" to "https://platform.openai.com/api-keys",
        "api.x.ai" to "https://console.x.ai/",
        "api.mistral.ai" to "https://console.mistral.ai/api-keys",
        "api.groq.com" to "https://console.groq.com/keys",
        "api.cloudflare.com" to "https://dash.cloudflare.com/profile/api-tokens",
    )

    /** The official key request page whose host matches [baseUrl]; null when unrecognized (including an invalid address). */
    fun forBaseUrl(baseUrl: String): String? = byHost[baseUrl.trim().toHttpUrlOrNull()?.host]
}
