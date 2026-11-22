package com.wangzan101.mika.cap.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GrantedFolderLocationTest {
    private val external = "com.android.externalstorage.documents"
    private val downloads = "com.android.providers.downloads.documents"
    private val volumes = mapOf("primary" to "/storage/emulated/10", "ABCD-1234" to "/storage/ABCD-1234")
    private fun location(authority: String, id: String) = grantedFolderLocation(authority, id, volumes::get)

    @Test fun sameNameInDifferentParentsAndVolumesHasDistinctLocations() {
        assertEquals("/storage/emulated/10/Download/Claire", location(external, "primary:Download/Claire"))
        assertEquals("/storage/emulated/10/Documents/Claire", location(external, "primary:Documents/Claire"))
        assertEquals("/storage/ABCD-1234/Download/Claire", location(external, "ABCD-1234:Download/Claire"))
        assertEquals("/storage/emulated/10/Download/学习 %2F", location(downloads, "raw:/storage/emulated/10/Download/学习 %2F"))
    }

    @Test fun opaqueIdentifiersAndMalformedPathsDoNotBecomeLocations() {
        assertNull(location("example.cloud", "primary:Download/Claire"))
        assertNull(location(downloads, "msd:123"))
        assertNull(location(external, "unmounted:Download/Claire"))
        assertNull(location(external, "primary:/Download/Claire"))
        assertNull(location(external, "primary:Download/../Claire"))
        assertNull(location(downloads, "raw:Download/Claire"))
    }
}
