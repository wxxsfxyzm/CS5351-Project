// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 InstallerX Revived contributors
package com.rosan.installer.domain.ai.usecase

import com.rosan.installer.domain.engine.model.source.DataEntity
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Implements: ADR-002.1, ADR-002.7
 * Requirements: REQ-IX-FR-002, REQ-IX-INV-003
 *
 * Computes the SHA-256 of the selected APK's bytes. The parser never fills
 * `AppEntity.BaseEntity.fileHash`, so without this the evidence was always
 * UNREADABLE (DR-03 RECOLLECT) and the approval binding could not be built.
 *
 * Used only on the AI-evaluated path. The binding provider calls it on every
 * read, so a file replaced after approval is detected on revalidation.
 * Returns null when the bytes cannot be read; callers treat that as unknown.
 */
object ApkContentHasher {
    suspend fun sha256(data: DataEntity): String? = withContext(Dispatchers.IO) {
        try {
            val input = data.getInputStreamWhileNotEmpty() ?: return@withContext null
            val digest = MessageDigest.getInstance("SHA-256")
            input.use { stream ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private const val BUFFER_SIZE = 64 * 1024
}
