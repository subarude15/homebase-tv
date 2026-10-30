package com.homebase.tv

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Guards MainActivity staying both a TV app launcher entry and a HOME candidate. */
class LauncherManifestTest {
    private val ns = "http://schemas.android.com/apk/res/android"

    private fun Element.children(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test
    fun mainActivityIsLeanbackLauncherAndHomeCandidate() {
        val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml")).documentElement
        val main = manifest.children("activity").single { it.getAttributeNS(ns, "name") == ".MainActivity" }
        assertEquals("true", main.getAttributeNS(ns, "exported"))

        val filters = main.children("intent-filter").map { f ->
            f.children("action").map { it.getAttributeNS(ns, "name") }.toSet() to
                f.children("category").map { it.getAttributeNS(ns, "name") }.toSet()
        }
        val action = "android.intent.action.MAIN"
        val cat = "android.intent.category."
        assertTrue(filters.any { (a, c) -> action in a && "${cat}LEANBACK_LAUNCHER" in c })
        assertTrue(filters.any { (a, c) -> action in a && "${cat}HOME" in c && "${cat}DEFAULT" in c })
    }
}
