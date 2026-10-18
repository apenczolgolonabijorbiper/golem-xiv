// SPDX-License-Identifier: AGPL-3.0-or-later
package com.xemantic.ai.golem.cognizer.delegated

import com.xemantic.ai.golem.api.CognitionEvent
import com.xemantic.ai.golem.api.backend.CognitionRepository
import com.xemantic.ai.golem.api.backend.PhenomenalExpressionInfo
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DelegatedTextCognizerTest {
    private val timestamp = "2026-01-01T00:00:00Z"
    private val clock = Clock.fixed(Instant.parse(timestamp), ZoneOffset.UTC)

    private class RecordingRepository {
        val calls = mutableListOf<String>()
        var text: String? = null
        val repository = Proxy.newProxyInstance(CognitionRepository::class.java.classLoader,
            arrayOf(CognitionRepository::class.java)) { _, method, args ->
            calls += method.name
            when (method.name) {
                "initiateExpression" -> PhenomenalExpressionInfo(2, kotlin.time.Instant.parse("2026-01-01T00:00:00Z"))
                "initiateTextPhenomenon" -> 3L
                "culminateTextPhenomenon" -> { text = args[3] as String; Unit }
                "culminateExpression" -> kotlin.time.Instant.parse("2026-01-01T00:00:00Z")
                else -> error("Unexpected repository call: ${method.name}")
            }
        } as CognitionRepository
    }

    private fun answer(job: DelegatedTextJob): DelegatedTextResponse {
        val request = DelegatedTextCognizer.protocolJson.decodeFromString<DelegatedTextRequest>(job.requestJson)
        val hash = MessageDigest.getInstance("SHA-256").digest(job.requestJson.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(hash, job.requestSha256)
        assertEquals("text_only_v1", request.mode)
        assertEquals("42", request.cognition_id)
        assertEquals(listOf("Synthetic test constitution"), request.constitution)
        assertEquals(mapOf("test" to "synthetic"), request.hints)
        return DelegatedTextResponse(1, request.request_id, request.cognition_id, hash,
            "Synthetic final text", "test-model", timestamp)
    }

    private fun cognizer(repo: RecordingRepository, transport: DelegatedTextTransport) =
        DelegatedTextCognizer(repo.repository, 1, transport, "test-vendor", 2, clock)

    @Test
    fun persistsOnlyFinalTextAndEmitsTheExistingEventSequence() = runTest {
        val repo = RecordingRepository()
        val events = cognizer(repo) { answer(it) }.reason(listOf("Synthetic test constitution"),
            42, emptyList(), mapOf("test" to "synthetic")).toList()
        assertEquals("Synthetic final text", repo.text)
        assertEquals(listOf("initiateExpression", "initiateTextPhenomenon",
            "culminateTextPhenomenon", "culminateExpression"), repo.calls)
        assertEquals(5, events.size)
        assertTrue(events[0] is CognitionEvent.ExpressionInitiation)
        assertTrue(events[1] is CognitionEvent.TextInitiation)
        assertTrue(events[2] is CognitionEvent.TextUnfolding)
        assertTrue(events[3] is CognitionEvent.TextCulmination)
        assertTrue(events[4] is CognitionEvent.ExpressionCulmination)
    }

    @Test
    fun invalidRepliesNeverReachPersistence() = runTest {
        val invalid: List<(DelegatedTextResponse) -> DelegatedTextResponse> = listOf(
            { it.copy(schema_version = 2) }, { it.copy(request_id = "unrelated") },
            { it.copy(cognition_id = "999") }, { it.copy(request_sha256 = "0".repeat(64)) },
            { it.copy(final_text = " ") }, { it.copy(final_text = "x".repeat(100001)) },
            { it.copy(model = "") }, { it.copy(model = "x".repeat(201)) },
            { it.copy(completed_at = "2025-12-31T23:59:59Z") },
            { it.copy(completed_at = "2026-01-01T00:00:03Z") }
        )
        for (change in invalid) {
            val repo = RecordingRepository()
            assertFailsWith<IllegalArgumentException> {
                cognizer(repo) { change(answer(it)) }.reason(listOf("Synthetic test constitution"),
                    42, emptyList(), mapOf("test" to "synthetic")).toList()
            }
            assertTrue(repo.calls.isEmpty())
        }
    }

    @Test
    fun timeoutCancelsTheTransportBeforeAnyWrite() = runTest {
        val repo = RecordingRepository()
        var cancelled = false
        assertFailsWith<TimeoutCancellationException> {
            cognizer(repo) { try { awaitCancellation() } finally { cancelled = true } }
                .reason(emptyList(), 42, emptyList(), emptyMap()).toList()
        }
        assertTrue(cancelled)
        assertTrue(repo.calls.isEmpty())
    }

    @Test
    fun strictWireDecoderRejectsToolFields() {
        assertFailsWith<IllegalArgumentException> {
            DelegatedTextCognizer.protocolJson.decodeFromString<DelegatedTextResponse>(
                """{"schema_version":1,"request_id":"x","cognition_id":"42","request_sha256":"x","final_text":"text","model":"test","completed_at":"2026-01-01T00:00:00Z","code":"unexpected"}""")
        }
    }
}
