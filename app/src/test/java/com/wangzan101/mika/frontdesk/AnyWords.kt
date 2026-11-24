package com.wangzan101.mika.frontdesk

import com.wangzan101.mika.core.frontdesk.DeskWords

/** For tests: accepts any words and hands them back as-is (use when the test is not about verification). */
object AnyWords : DeskWords {
    override fun said(fragment: String): String = fragment
    override fun saidNow(fragment: String): String = fragment
}
