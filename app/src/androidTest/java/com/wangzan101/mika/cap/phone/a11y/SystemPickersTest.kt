package com.wangzan101.mika.cap.phone.a11y

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** On a real device: the package names of the system pickers, file picker and permission dialog resolve via standard Intents, and ordinary apps are not counted in. */
@RunWith(AndroidJUnit4::class)
class SystemPickersTest {
    @Test
    fun resolvesThePickersOfThisDevice() {
        val pm = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
        val pickers = SystemPickers.resolve(pm)
        // At least the permission dialog and the file picker resolve (both exist in AOSP)
        assertTrue(pickers.toString(), pickers.size >= 3)
        val documents = pm.resolveActivity(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 0)
            ?.activityInfo?.packageName
        assertTrue("$documents not in $pickers", documents == null || documents in pickers)
        assertTrue(pickers.none { it == "com.android.settings" || it.startsWith("com.wangzan101") })
        assertTrue(SystemPickers.settingsPackage(pm) == DeviceSupport.SETTINGS)
    }
}
