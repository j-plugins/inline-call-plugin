package com.github.xepozz.inline_call.base.inlay

import com.intellij.codeInsight.hints.declarative.StringInlayActionPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ExecutionInlayPayloadTest {

    @Test
    fun `encode decode round trip`() {
        val original = ExecutionInlayPayload("shell", "abcd1234", 2, 17)
        val decoded = ExecutionInlayPayload.decode(original.toActionPayload())
        assertEquals(original, decoded)
    }

    @Test
    fun `encode produces pipe separated`() {
        val p = ExecutionInlayPayload("http", "ff00aa", 0, 3)
        assertEquals("http|ff00aa|0|3", p.encode())
    }

    @Test
    fun `decode rejects wrong number of parts`() {
        val tooFew = StringInlayActionPayload("shell|abc|0")
        val tooMany = StringInlayActionPayload("shell|abc|0|3|extra")
        assertNull(ExecutionInlayPayload.decode(tooFew))
        assertNull(ExecutionInlayPayload.decode(tooMany))
    }

    @Test
    fun `decode rejects empty featureId`() {
        val p = StringInlayActionPayload("|abc|0|3")
        assertNull(ExecutionInlayPayload.decode(p))
    }

    @Test
    fun `decode rejects non integer occurrence`() {
        val p = StringInlayActionPayload("shell|abc|nope|3")
        assertNull(ExecutionInlayPayload.decode(p))
    }

    @Test
    fun `decode rejects non integer line`() {
        val p = StringInlayActionPayload("shell|abc|0|nope")
        assertNull(ExecutionInlayPayload.decode(p))
    }

    @Test
    fun `decode preserves valueHash even when empty`() {
        // An empty hash is unusual but must not crash decode.
        val p = StringInlayActionPayload("shell||0|3")
        val decoded = ExecutionInlayPayload.decode(p)
        assertNotNull(decoded)
        assertEquals("", decoded!!.valueHash)
    }

    @Test
    fun `handler ids are stable strings`() {
        assertEquals("inline_call.execution.run", InlayActionHandlerIds.RUN)
        assertEquals("inline_call.execution.stop", InlayActionHandlerIds.STOP)
        assertEquals("inline_call.execution.delete", InlayActionHandlerIds.DELETE)
        assertEquals("inline_call.execution.toggle_collapse", InlayActionHandlerIds.TOGGLE_COLLAPSE)
    }
}
