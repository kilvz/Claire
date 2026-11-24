package com.wangzan101.mika.core.screen.uistate

/**
 * Traditional Chinese characters folded to simplified: the word lists for buttons, amounts and prompts are written in
 * simplified Chinese, so traditional UIs (where characters such as delete and checkout appear in their traditional
 * forms) are folded first and then judged by the same word lists. Only characters used by the word lists are included;
 * when a word list gains a new character, add it here too.
 */
object Hant {
    internal const val TRADITIONAL ="個買優餘內減刪動單歷發臺後唄塊復寶實對崗應錄總報機溝點現碼確簡紅約紋細結繳羣職聯獲計訂認記論設證評該說請賬貨購費賞車轉輸運進遞錢銀銷鍵預額馬驗帳説傳尋儲註覆佈繫"
    internal const val SIMPLIFIED = "个买优余内减删动单历发台后呗块复宝实对岗应录总报机沟点现码确简红约纹细结缴群职联获计订认记论设证评该说请账货购费赏车转输运进递钱银销键预额马验账说传寻储注复布系"
    private val MAP = TRADITIONAL.zip(SIMPLIFIED).toMap()

    fun fold(text: String): String {
        if (text.none { it in MAP }) return text
        return String(CharArray(text.length) { MAP[text[it]] ?: text[it] })
    }
}
