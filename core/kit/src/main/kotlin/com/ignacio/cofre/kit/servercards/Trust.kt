package com.ignacio.cofre.kit.servercards

import com.ignacio.cofre.kit.common.Kit
import com.ignacio.cofre.kit.common.FileOps
import com.ignacio.cofre.kit.servercards.ServerJoinError.Reason
import kotlinx.serialization.Serializable
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.io.File

@Serializable data class TrustRecord(val origin: String, val keyID: String, val fingerprint: String)
data class TrustDecision(val firstVisit: Boolean, val origin: String, val fingerprint: String, val keyChanged: Boolean, val pinnedFingerprint: String? = null)

/** TOFU persists only through an explicit confirm call. Corrupt stores fail closed. */
class FileTrustStore(val file: File) {
    @Synchronized fun record(origin: String): TrustRecord? = records()[URLPolicy.origin(origin)]
    @Synchronized fun save(record: TrustRecord) {
        val origin = URLPolicy.origin(record.origin)
        val next = records() + (origin to record.copy(origin = origin))
        FileOps.atomic(file, Kit.json.encodeToString(next).toByteArray())
    }
    private fun records(): Map<String, TrustRecord> {
        if (!file.exists()) return emptyMap()
        if (file.length() > 4L shl 20) fail(Reason.SizeLimit)
        return Kit.json.decodeFromString<Map<String, TrustRecord>>(file.readText())
    }
}

object TrustVerifier {
    fun evaluate(verified: VerifiedCard, store: FileTrustStore): TrustDecision {
        val origin = URLPolicy.origin(verified.card.security.origin)
        val envelope = verified.envelope; val fingerprint = envelope.publicKey
        val old = store.record(origin) ?: return TrustDecision(true, origin, fingerprint, false)
        if (old.fingerprint == fingerprint) return TrustDecision(false, origin, fingerprint, false)
        val rotated = envelope.previousKeySignature?.let { signature ->
            runCatching {
                val oldKey = base64url(old.fingerprint); val newKey = base64url(fingerprint); val sig = base64url(signature)
                oldKey.size == 32 && newKey.size == 32 && sig.size == 64 && Ed25519.verify(sig, 0, oldKey, 0, newKey, 0, newKey.size)
            }.getOrDefault(false)
        } ?: false
        return TrustDecision(false, origin, fingerprint, !rotated, old.fingerprint)
    }
    fun confirm(verified: VerifiedCard, store: FileTrustStore) {
        val decision = evaluate(verified, store)
        if (decision.keyChanged) fail(Reason.KeyChanged)
        store.save(TrustRecord(decision.origin, verified.envelope.keyID, verified.envelope.publicKey))
    }
}
