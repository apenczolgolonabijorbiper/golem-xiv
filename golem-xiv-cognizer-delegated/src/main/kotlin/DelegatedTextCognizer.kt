// SPDX-License-Identifier: AGPL-3.0-or-later
package com.xemantic.ai.golem.cognizer.delegated

import com.xemantic.ai.golem.api.CognitionEvent
import com.xemantic.ai.golem.api.EpistemicAgent
import com.xemantic.ai.golem.api.PhenomenalExpression
import com.xemantic.ai.golem.api.backend.CognitionRepository
import com.xemantic.ai.golem.api.backend.Cognizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Serializable
data class DelegatedTextRequest(
    val schema_version: Int = 1,
    val request_id: String,
    val cognition_id: String,
    val created_at: String,
    val expires_at: String,
    val mode: String = "text_only_v1",
    val constitution: List<String>,
    val phenomenal_flow: List<PhenomenalExpression>,
    val hints: Map<String, String>
)

@Serializable
data class DelegatedTextResponse(
    val schema_version: Int,
    val request_id: String,
    val cognition_id: String,
    val request_sha256: String,
    val final_text: String,
    val model: String,
    val completed_at: String
)

/** Transport must preserve these exact UTF-8 request bytes when calculating the digest. */
data class DelegatedTextJob(val requestJson: String, val requestSha256: String)

/** Host-owned transport, credentials and operator UI. Must cooperate with cancellation. */
fun interface DelegatedTextTransport {
    suspend fun exchange(job: DelegatedTextJob): DelegatedTextResponse
}

/** Final text only: does not execute tools, emit Intent, or call a fallback model. */
class DelegatedTextCognizer(
    private val repository: CognitionRepository,
    private val selfId: Long,
    private val transport: DelegatedTextTransport,
    private val vendor: String,
    private val timeoutSeconds: Long = 1800,
    private val clock: Clock = Clock.systemUTC()
) : Cognizer {
    init {
        require(vendor.isNotBlank() && vendor.length <= 200)
        require(timeoutSeconds in 1..7200)
    }

    override fun reason(
        constitution: List<String>, cognitionId: Long,
        phenomenalFlow: List<PhenomenalExpression>, hints: Map<String, String>
    ): Flow<CognitionEvent> = flow {
        val now = clock.instant()
        val expires = now.plusSeconds(timeoutSeconds)
        val request = DelegatedTextRequest(request_id = UUID.randomUUID().toString(),
            cognition_id = cognitionId.toString(), created_at = now.toString(), expires_at = expires.toString(),
            constitution = constitution, phenomenal_flow = phenomenalFlow, hints = hints)
        val raw = protocolJson.encodeToString(request)
        val bytes = raw.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 500000) { "Delegated context is too large" }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val response = withTimeout(timeoutSeconds * 1000) {
            transport.exchange(DelegatedTextJob(raw, digest))
        }
        require(response.schema_version == 1 && response.request_id == request.request_id
            && response.cognition_id == request.cognition_id) { "Response correlation mismatch" }
        require(response.request_sha256 == digest) { "Request digest mismatch" }
        require(response.final_text.isNotBlank() && response.final_text.length <= 100000)
        require(response.model.isNotBlank() && response.model.length <= 200)
        val completed = Instant.parse(response.completed_at)
        val received = clock.instant()
        require(!received.isAfter(expires) && !completed.isBefore(now)
            && !completed.isAfter(expires) && !completed.isAfter(received.plusSeconds(60))) {
            "Response expired or invalid timestamp"
        }
        // Validate before the first write. Persistence has the same partial-write semantics
        // as existing Cognizers; the host remains responsible for recovery after DB failure.
        val agent = EpistemicAgent.AI(selfId, response.model, vendor)
        val expression = repository.initiateExpression(cognitionId, agent)
        emit(CognitionEvent.ExpressionInitiation(expression.id, agent, expression.initiationMoment))
        val textId = repository.initiateTextPhenomenon(cognitionId, expression.id)
        emit(CognitionEvent.TextInitiation(textId, expression.id))
        repository.culminateTextPhenomenon(cognitionId, expression.id, textId, response.final_text)
        emit(CognitionEvent.TextUnfolding(textId, expression.id, response.final_text))
        emit(CognitionEvent.TextCulmination(textId, expression.id))
        emit(CognitionEvent.ExpressionCulmination(expression.id,
            repository.culminateExpression(cognitionId, expression.id)))
    }

    companion object {
        /** Strict decoder for untrusted transport responses; additional fields are rejected. */
        val protocolJson = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    }
}
