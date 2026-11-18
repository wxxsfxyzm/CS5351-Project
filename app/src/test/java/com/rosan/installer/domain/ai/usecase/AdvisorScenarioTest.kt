// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2025-2026 InstallerX Revived contributors
package com.rosan.installer.domain.ai.usecase

import com.rosan.installer.domain.ai.fixture.EvidenceFixtures
import com.rosan.installer.domain.ai.model.AdvisorOutcome
import com.rosan.installer.domain.ai.model.AssessmentResult
import com.rosan.installer.domain.ai.model.EvidenceCompleteness
import com.rosan.installer.domain.ai.model.FailureCode
import com.rosan.installer.domain.ai.model.InstallSuccessLikelihood
import com.rosan.installer.domain.ai.model.SecurityRisk
import com.rosan.installer.data.ai.advisory.DefaultAiRiskAdvisor
import com.rosan.installer.domain.ai.repository.AiModelClient
import com.rosan.installer.domain.ai.repository.AiNetworkGate
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies: REQ-IX-FR-003, REQ-IX-FR-004, REQ-IX-SEC-004, REQ-IX-INV-004
 * Offline and deterministic: a recording stub stands in for the model.
 *
 * For every fixed APK evidence set in [EvidenceFixtures] this confirms the
 * same two things:
 *
 * 1. **The request is right.** Only allowlisted fields are sent, the
 *    scenario's own facts are present, and local identifiers never are.
 * 2. **The reply is shaped right.** Whatever the model returns, the
 *    interface produces either a fully-populated fixed-format assessment or
 *    an explicit failure. Never a partial object and never a default.
 */
class AdvisorScenarioTest {

    /** Records what the advisor sent, then replies with a scripted body. */
    private class RecordingClient(private val reply: String) : AiModelClient {
        var sentPayload: String? = null
            private set
        var sentUserPrompt: String? = null
            private set

        override val modelId = "stub"

        override suspend fun complete(request: com.rosan.installer.domain.ai.model.AiInstallRiskRequest): String {
            sentPayload = request.evidencePayloadJson
            sentUserPrompt = request.userPrompt
            return reply
        }
    }

    private class AllowAll : AiNetworkGate {
        override suspend fun isEgressAllowed() = true
    }

    /** A reply in the fixed format that the scenario should yield. */
    private fun fixedReply(
        likelihood: String,
        risk: String,
        reason: String,
        ref: String = "evidence.target_sdk",
    ): String = """{"install_success_likelihood":"$likelihood","security_risk":"$risk",""" +
        """"reasons":["$reason"],"evidence_refs":["$ref"]}"""

    private fun jsonOf(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private suspend fun evaluate(
        client: AiModelClient,
        scenario: EvidenceFixtures.Scenario,
    ): RequestInstallRiskAssessmentUseCase.Status = RequestInstallRiskAssessmentUseCase(
        builder = AiRequestBuilder(),
        advisor = DefaultAiRiskAdvisor(client, AllowAll(), StrictAiOutputValidator()),
    ).invoke(scenario.evidence)

    // --- 1. What the model is sent, per scenario ------------------------------

    /**
     * Verifies: REQ-IX-SEC-004 (the payload never exceeds the allowlist)
     * across every scenario, including the degraded ones.
     */
    @Test
    fun everyScenario_sendsOnlyAllowlistedFields() = runTest {
        for (scenario in EvidenceFixtures.all) {
            val client = RecordingClient(fixedReply("HIGH", "LOW", "ok"))
            evaluate(client, scenario)
            val payload = assertIs<String>(client.sentPayload, "no request sent for ${scenario.name}")
            val keys = jsonOf(payload).keys
            val paths = keys.mapTo(linkedSetOf()) { "evidence.$it" }
            assertTrue(
                AiRequestBuilder.ALLOWLISTED_LEAF_PATHS.containsAll(paths),
                "${scenario.name} sent non-allowlisted field(s): ${paths - AiRequestBuilder.ALLOWLISTED_LEAF_PATHS}",
            )
        }
    }

    /**
     * Verifies: REQ-IX-SEC-004 (NFC-OUT-001) across every scenario: the local
     * package name and APK hash never appear in the payload or the prompt.
     */
    @Test
    fun everyScenario_neverLeaksLocalIdentifiers() = runTest {
        for (scenario in EvidenceFixtures.all) {
            val client = RecordingClient(fixedReply("HIGH", "LOW", "ok"))
            evaluate(client, scenario)
            val everything = (client.sentPayload ?: "") + (client.sentUserPrompt ?: "")
            scenario.evidence.targetPackageName?.let {
                assertFalse(everything.contains(it), "${scenario.name} leaked package name $it")
            }
            scenario.evidence.apkSha256?.let {
                assertFalse(everything.contains(it), "${scenario.name} leaked APK hash")
            }
        }
    }

    /** A: healthy app — the request carries the full compatibility picture. */
    @Test
    fun normalUpdate_sendsCompleteCompatibleEvidence() = runTest {
        val client = RecordingClient(fixedReply("HIGH", "LOW", "target_sdk 35 is current"))
        val status = evaluate(client, EvidenceFixtures.Scenario("A", EvidenceFixtures.normalUpdate))

        val payload = jsonOf(client.sentPayload!!)
        assertEquals(EvidenceCompleteness.COMPLETE, status.completeness)
        assertEquals("COMPATIBLE", payload["platform_compatibility"]!!.jsonPrimitive.content)
        assertEquals("COMPATIBLE", payload["abi_compatibility"]!!.jsonPrimitive.content)
        assertEquals("UPDATE", payload["install_relation"]!!.jsonPrimitive.content)
        assertEquals("MATCH", payload["installed_signature_match"]!!.jsonPrimitive.content)
        assertEquals(3, payload["declared_permissions"]!!.let { Json.parseToJsonElement(it.toString()) }.let {
            (it as kotlinx.serialization.json.JsonArray).size
        })
        assertTrue(client.sentUserPrompt!!.contains("Evidence completeness: COMPLETE"))
    }

    /** C: incompatible platform and ABI — both signals reach the model. */
    @Test
    fun incompatible_sendsBothIncompatibilitySignals() = runTest {
        val client = RecordingClient(fixedReply("LOW", "MEDIUM", "min_sdk exceeds device API level"))
        val status = evaluate(client, EvidenceFixtures.Scenario("C", EvidenceFixtures.incompatible))

        val payload = jsonOf(client.sentPayload!!)
        assertEquals("INCOMPATIBLE", payload["platform_compatibility"]!!.jsonPrimitive.content)
        assertEquals("INCOMPATIBLE", payload["abi_compatibility"]!!.jsonPrimitive.content)
        assertEquals(29, payload["device_api_level"]!!.jsonPrimitive.content.toInt())
        assertTrue(status.isUsable)
        assertEquals(InstallSuccessLikelihood.LOW, status.assessment!!.assessment.installSuccessLikelihood)
    }

    /** D: ABI-only mismatch — platform is still compatible. */
    @Test
    fun abiMismatch_sendsCompatiblePlatformButIncompatibleAbi() = runTest {
        val client = RecordingClient(fixedReply("MEDIUM", "LOW", "no matching ABI", "evidence.abi_compatibility"))
        evaluate(client, EvidenceFixtures.Scenario("D", EvidenceFixtures.abiMismatch))

        val payload = jsonOf(client.sentPayload!!)
        assertEquals("COMPATIBLE", payload["platform_compatibility"]!!.jsonPrimitive.content)
        assertEquals("INCOMPATIBLE", payload["abi_compatibility"]!!.jsonPrimitive.content)
    }

    /** E: high permission risk — the whole permission profile is transmitted. */
    @Test
    fun highPermissionRisk_sendsEverySensitivePermission() = runTest {
        val client = RecordingClient(
            fixedReply("HIGH", "HIGH", "requests READ_SMS and SYSTEM_ALERT_WINDOW", "evidence.declared_permissions"),
        )
        val status = evaluate(client, EvidenceFixtures.Scenario("E", EvidenceFixtures.highPermissionRisk))

        val raw = client.sentPayload!!
        for (permission in EvidenceFixtures.highPermissionRisk.declaredPermissions) {
            assertTrue(raw.contains(permission), "permission $permission was not transmitted")
        }
        val payload = jsonOf(raw)
        assertEquals("true", payload["has_shared_uid"]!!.jsonPrimitive.content)
        assertEquals("true", payload["is_xposed_module"]!!.jsonPrimitive.content)
        assertTrue(client.sentUserPrompt!!.contains("Xposed module: true"))

        val assessment = status.assessment!!.assessment
        assertEquals(SecurityRisk.HIGH, assessment.securityRisk)
        assertEquals(listOf("evidence.declared_permissions"), assessment.evidenceRefs)
    }

    /** G: missing information — completeness is reported, not guessed. */
    @Test
    fun missingCompatibility_reportsKeyUnknownAndOmitsAbsentFields() = runTest {
        val client = RecordingClient(
            fixedReply("UNKNOWN", "UNKNOWN", "compatibility is unknown", "evidence.version_code"),
        )
        val status = evaluate(client, EvidenceFixtures.Scenario("G", EvidenceFixtures.missingCompatibility))

        assertEquals(EvidenceCompleteness.KEY_UNKNOWN, status.completeness)
        val payload = jsonOf(client.sentPayload!!)
        assertFalse(payload.containsKey("platform_compatibility"), "absent field must be omitted")
        assertFalse(payload.containsKey("abi_compatibility"), "absent field must be omitted")
        assertFalse(payload.containsKey("device_api_level"), "absent field must be omitted")
        assertTrue(client.sentUserPrompt!!.contains("Evidence completeness: KEY_UNKNOWN"))
        assertTrue(status.isUsable, "a well-formed UNKNOWN reply is usable, just not favorable")
        assertEquals(InstallSuccessLikelihood.UNKNOWN, status.assessment!!.assessment.installSuccessLikelihood)
        // A question the evidence cannot answer is still marked UNKNOWN, so a
        // degraded APK is never silently reported as low risk.
        assertEquals(SecurityRisk.UNKNOWN, status.assessment!!.assessment.securityRisk)
    }

    /**
     * G/H: a reply that cites compatibility fields the scenario never sent is
     * rejected. Grounding is per-request, not per-allowlist.
     */
    @Test
    fun missingCompatibility_citationToAbsentField_isRejected() = runTest {
        val client = RecordingClient(fixedReply("UNKNOWN", "UNKNOWN", "x", "evidence.platform_compatibility"))
        val status = evaluate(client, EvidenceFixtures.Scenario("G", EvidenceFixtures.missingCompatibility))

        assertFalse(status.isUsable)
        assertEquals(FailureCode.INVALID_OUTPUT, status.failure?.code)
    }

    /** H: nearly no evidence — still a valid request, still a valid shape. */
    @Test
    fun nearlyEmpty_stillProducesAWellFormedRequest() = runTest {
        val client = RecordingClient(
            fixedReply("UNKNOWN", "UNKNOWN", "insufficient evidence", "evidence.version_code"),
        )
        val status = evaluate(client, EvidenceFixtures.Scenario("H", EvidenceFixtures.nearlyEmpty))

        assertEquals(EvidenceCompleteness.KEY_UNKNOWN, status.completeness)
        val keys = jsonOf(client.sentPayload!!).keys
        assertEquals(setOf("version_code", "declared_permissions"), keys)
        assertTrue(status.isUsable)
    }

    /** I: unreadable APK — completeness says so; the request is still bounded. */
    @Test
    fun unreadable_reportsUnreadable() = runTest {
        val client = RecordingClient(fixedReply("UNKNOWN", "UNKNOWN", "evidence unavailable"))
        val status = evaluate(client, EvidenceFixtures.Scenario("I", EvidenceFixtures.unreadable))
        assertEquals(EvidenceCompleteness.UNREADABLE, status.completeness)
    }

    /** J: capped permission list — marked truncated, size bounded. */
    @Test
    fun truncatedPermissionList_isMarkedTruncatedAndCapped() = runTest {
        val client = RecordingClient(fixedReply("MEDIUM", "MEDIUM", "many permissions"))
        val status = evaluate(client, EvidenceFixtures.Scenario("J", EvidenceFixtures.truncatedPermissionList))

        assertEquals(EvidenceCompleteness.TRUNCATED, status.completeness)
        val array = Json.parseToJsonElement(jsonOf(client.sentPayload!!)["declared_permissions"].toString())
        assertEquals(EvidenceSanitizer.MAX_LIST_ITEMS, (array as kotlinx.serialization.json.JsonArray).size)
    }

    // --- 2. The returned format, per scenario --------------------------------

    /**
     * Verifies: REQ-IX-FR-004 — the returned assessment is fully populated
     * with the fixed fields for every scenario, or is an explicit failure.
     */
    @Test
    fun everyScenario_returnsEitherFullFormatOrExplicitFailure() = runTest {
        val replies = listOf(
            fixedReply("HIGH", "LOW", "normal", "evidence.target_sdk"),
            fixedReply("MEDIUM", "HIGH", "risky", "evidence.declared_permissions"),
            fixedReply("LOW", "MEDIUM", "incompatible", "evidence.platform_compatibility"),
        )
        for (scenario in EvidenceFixtures.all) {
            for (reply in replies) {
                val status = evaluate(RecordingClient(reply), scenario)
                val assessment = status.assessment
                if (assessment == null) {
                    assertTrue(status.failure != null, "${scenario.name}: neither assessment nor failure")
                    continue
                }
                val a = assessment.assessment
                assertIs<InstallSuccessLikelihood>(a.installSuccessLikelihood)
                assertIs<SecurityRisk>(a.securityRisk)
                assertTrue(a.reasons.isNotEmpty(), "${scenario.name}: reasons must not be empty")
                assertTrue(a.evidenceRefs.isNotEmpty(), "${scenario.name}: refs must not be empty")
                assertTrue(
                    AiRequestBuilder.ALLOWLISTED_LEAF_PATHS.containsAll(a.evidenceRefs),
                    "${scenario.name}: cited a path that was never allowlisted",
                )
                assertEquals("stub", a.modelId)
                assertEquals(AiRequestBuilder.PROMPT_VERSION, a.promptVersion)
            }
        }
    }

    /**
     * Verifies: REQ-IX-FR-004 — a reply citing a field the scenario did not
     * send is rejected, because the model cannot ground a claim it was not
     * given. Scenario H transmits almost nothing, so most citations are fake.
     */
    @Test
    fun citationToAnUnsentField_isRejected() = runTest {
        val client = RecordingClient(fixedReply("HIGH", "LOW", "looks fine", "evidence.abi_compatibility"))
        val status = evaluate(client, EvidenceFixtures.Scenario("H", EvidenceFixtures.nearlyEmpty))
        assertEquals(null, status.assessment)
        assertEquals(com.rosan.installer.domain.ai.model.FailureCode.INVALID_OUTPUT, status.failure?.code)
    }

    /**
     * Verifies: REQ-IX-INV-004 — for every scenario, a malformed model reply
     * produces a failure status and never a usable assessment. A degraded
     * APK must not become "safe" just because the model answered badly.
     */
    @Test
    fun everyScenario_malformedReplyIsFailureNotSafe() = runTest {
        val malformedReplies = listOf(
            "Looks safe to me.",
            "{}",
            """{"install_success_likelihood":"HIGH"}""",
            """{"install_success_likelihood":"HIGH","security_risk":"SAFE","reasons":["x"],"evidence_refs":["evidence.target_sdk"]}""",
            """{"install_success_likelihood":"HIGH","security_risk":"LOW","action":"install","reasons":["x"],"evidence_refs":["evidence.target_sdk"]}""",
        )
        for (scenario in EvidenceFixtures.all) {
            for (reply in malformedReplies) {
                val status = evaluate(RecordingClient(reply), scenario)
                assertTrue(
                    !status.isUsable,
                    "${scenario.name} accepted a malformed reply as usable: $reply",
                )
            }
        }
    }

    /** The advisor returns an Ok result carrying the parsed shape, one dispatch. */
    @Test
    fun scenarioA_producesExactlyOneDispatch() = runTest {
        val client = RecordingClient(fixedReply("HIGH", "LOW", "ok"))
        val useCase = RequestInstallRiskAssessmentUseCase(
            builder = AiRequestBuilder(),
            advisor = DefaultAiRiskAdvisor(client, AllowAll(), StrictAiOutputValidator()),
        )
        val status = useCase.invoke(EvidenceFixtures.normalUpdate)
        assertEquals(1, status.providerDispatches)
        val assessment: AssessmentResult.Valid = assertIs(status.assessment)
        assertTrue(assessment.assessment.reasons.isNotEmpty())
        assertTrue(
            status.transmittedPaths.isNotEmpty(),
            "scenario A sends several allowlisted fields",
        )
        assertTrue(
            AiRequestBuilder.ALLOWLISTED_LEAF_PATHS.containsAll(status.transmittedPaths),
            "transmitted paths escaped the allowlist",
        )
    }
}
