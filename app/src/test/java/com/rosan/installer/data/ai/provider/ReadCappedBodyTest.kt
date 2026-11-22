// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 InstallerX Revived contributors
package com.rosan.installer.data.ai.provider

import com.rosan.installer.domain.ai.model.AiAdvisorFailure
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import okio.Buffer

/**
 * Verifies: REQ-IX-SEC-004 (response size cap), REQ-IX-INV-004.
 *
 * The OkHttp transport cannot run in JVM tests (BUG-004), so its body read is
 * tested directly on an Okio source. The previous `readByteArray(max + 1)`
 * required exactly max + 1 bytes and threw EOFException for every reply
 * smaller than the cap, so no real model reply was ever read on a device.
 */
class ReadCappedBodyTest {
    private fun source(size: Int) = Buffer().write(ByteArray(size) { 'a'.code.toByte() })

    @Test
    fun smallReplyIsReadWhole() {
        val body = """{"install_success_likelihood":"HIGH"}""".encodeToByteArray()
        assertContentEquals(body, readCappedBody(Buffer().write(body), maxBytes = 64 * 1024))
    }

    @Test
    fun emptyReplyIsEmpty() {
        assertContentEquals(ByteArray(0), readCappedBody(Buffer(), maxBytes = 16))
    }

    @Test
    fun replyExactlyAtCapIsAccepted() {
        assertContentEquals(ByteArray(16) { 'a'.code.toByte() }, readCappedBody(source(16), maxBytes = 16))
    }

    @Test
    fun replyOverCapIsOversize() {
        assertFailsWith<AiAdvisorFailure.OutputOversize> { readCappedBody(source(17), maxBytes = 16) }
    }
}
