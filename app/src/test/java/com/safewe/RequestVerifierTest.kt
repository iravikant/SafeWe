package com.safewe

import com.safewe.security.B64
import com.safewe.security.Ecc
import com.safewe.transfer.*
import org.junit.Assert.assertEquals
import org.junit.Test

class RequestVerifierTest {
    private val parent = Ecc.newKeyPair()
    private val parentKey = B64.enc(parent.public.encoded)
    private val child = "child-uid"
    private val now = 1_000_000_000L

    private fun signed(type: String = "CAMERA", created: Long = now, expires: Long = now + 300_000): CaptureRequest {
        val r = CaptureRequest("req-1", type, "REQUESTED", "nonce", "", created, expires)
        return r.copy(signature = B64.enc(Ecc.sign(parent.private, Canon.request(child, r))))
    }

    @Test fun validRequestAccepted() = assertEquals(Verdict.OK, RequestVerifier.verify(signed(), child, parentKey, now))

    @Test fun tamperedTypeRejected() =
        assertEquals(Verdict.BAD_SIGNATURE, RequestVerifier.verify(signed().copy(type = "SCREENSHOT"), child, parentKey, now))

    @Test fun otherChildRejected() =
        assertEquals(Verdict.BAD_SIGNATURE, RequestVerifier.verify(signed(), "someone-else", parentKey, now))

    @Test fun wrongParentKeyRejected() =
        assertEquals(Verdict.BAD_SIGNATURE, RequestVerifier.verify(signed(), child, B64.enc(Ecc.newKeyPair().public.encoded), now))

    @Test fun expiredRejected() =
        assertEquals(Verdict.EXPIRED, RequestVerifier.verify(signed(), child, parentKey, now + 400_000))

    @Test fun overlongLifetimeRejected() =
        assertEquals(Verdict.EXPIRED, RequestVerifier.verify(signed(expires = now + 3_600_000), child, parentKey, now))

    @Test fun unknownTypeRejected() =
        assertEquals(Verdict.UNKNOWN_TYPE, RequestVerifier.verify(signed("MICROPHONE"), child, parentKey, now))

    @Test fun missingKeyRejected() =
        assertEquals(Verdict.NO_KEYS, RequestVerifier.verify(signed(), child, "", now))
}
