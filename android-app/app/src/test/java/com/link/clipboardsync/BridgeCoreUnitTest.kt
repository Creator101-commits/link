package com.link.clipboardsync

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import android.telephony.TelephonyManager
import android.net.ConnectivityManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class BridgeCoreUnitTest {

    @Test
    fun isPrivateLocalAddress_acceptsPrivateAndLoopback_rejectsPublicAndMalformed() {
        val allowed = listOf(
            "127.0.0.1",
            "localhost",
            "::1",
            "10.0.0.1",
            "10.255.255.254",
            "172.16.0.1",
            "172.31.255.254",
            "192.168.1.42",
            "169.254.10.20",
            "100.64.0.1",
            "100.127.255.254",
            "fe80::1%en0",
            "fd12:3456:789a::1",
            "::ffff:192.168.1.10"
        )
        for (addr in allowed) {
            assertTrue("Expected local/private address to be allowed: $addr", NetworkSyncClient.isPrivateLocalAddress(addr))
        }

        val rejected = listOf(
            null,
            "",
            "   ",
            "8.8.8.8",
            "1.1.1.1",
            "172.15.255.255",
            "172.32.0.1",
            "192.169.1.1",
            "100.63.255.255",
            "100.128.0.1",
            "256.168.1.1",
            "example.com"
        )
        for (addr in rejected) {
            assertFalse("Expected non-private/invalid address to be rejected: $addr", NetworkSyncClient.isPrivateLocalAddress(addr))
        }
    }

    @Test
    fun constantTimeEquals_and_generateSecureNonce_behaveCorrectly() {
        assertTrue(NetworkSyncClient.constantTimeEquals("abc123", "abc123"))
        assertFalse(NetworkSyncClient.constantTimeEquals("abc123", "abc124"))
        assertFalse(NetworkSyncClient.constantTimeEquals("abc", "abcd"))
        assertFalse(NetworkSyncClient.constantTimeEquals("", ""))
        assertFalse(NetworkSyncClient.constantTimeEquals(null, "abc"))

        val n1 = NetworkSyncClient.generateSecureNonce()
        val n2 = NetworkSyncClient.generateSecureNonce()
        assertEquals(32, n1.length)
        assertTrue(n1.matches(Regex("^[0-9a-f]{32}$")))
        assertNotEquals(n1, n2)
    }

    @Test
    fun clipboardPayload_roundTrip_and_sha256() {
        val original = ClipboardPayload(
            type = "clip",
            hash = ClipboardPayload.computeSHA256("hello bridge"),
            text = "hello bridge",
            timestamp = 1700000000000L,
            device = "Pixel 8"
        )
        val parsed = ClipboardPayload.fromJsonString(original.toJsonString())
        assertNotNull(parsed)
        assertEquals("clip", parsed!!.type)
        assertEquals(original.hash, parsed.hash)
        assertEquals("hello bridge", parsed.text)
        assertEquals("Pixel 8", parsed.device)

        assertNull(ClipboardPayload.fromJsonString("not-valid-json"))

        // Deterministic handshake digest vector matching macOS server
        assertEquals(
            "a665a45920422f9d417e4867efdc4fb8a04a1f3fff1fa07e998e86f7f7a27ae3",
            ClipboardPayload.computeSHA256("123")
        )
    }

    @Test
    fun sanitizeFileName_blocksTraversalAndInvalidNames() {
        assertEquals("passwd", BridgeFileTransferManager.sanitizeFileName("../../../../etc/passwd"))
        assertEquals("TransferredFile", BridgeFileTransferManager.sanitizeFileName(".."))
        assertEquals("TransferredFile", BridgeFileTransferManager.sanitizeFileName("."))
        assertEquals("TransferredFile", BridgeFileTransferManager.sanitizeFileName("   "))
        assertEquals("_hidden", BridgeFileTransferManager.sanitizeFileName(".hidden"))
        assertEquals("report.pdf", BridgeFileTransferManager.sanitizeFileName("report\u0000.pdf"))
        assertFalse(BridgeFileTransferManager.sanitizeFileName("..\\..\\evil.txt").contains("\\"))
    }

    @Test
    fun deriveAesKey_and_aesGcmRoundTrip_detectsTampering() {
        val key = BridgeFileTransferManager.deriveAesKey("sample-transfer-token")
        assertEquals("AES", key.algorithm)
        assertEquals(32, key.encoded.size) // 256-bit AES key

        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val plaintext = "Bridge encrypted file chunk payload".toByteArray(Charsets.UTF_8)

        val encCipher = Cipher.getInstance("AES/GCM/NoPadding")
        encCipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ciphertext = encCipher.doFinal(plaintext)

        val decCipher = Cipher.getInstance("AES/GCM/NoPadding")
        decCipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        assertArrayEquals(plaintext, decCipher.doFinal(ciphertext))

        // Flip a byte in ciphertext and ensure GCM tag verification rejects it
        val tampered = ciphertext.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0xFF).toByte()
        val badCipher = Cipher.getInstance("AES/GCM/NoPadding")
        badCipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        var threw = false
        try {
            badCipher.doFinal(tampered)
        } catch (_: AEADBadTagException) {
            threw = true
        }
        assertTrue("Expected AEADBadTagException on tampered ciphertext", threw)
    }

    @Test
    fun fileTransferHelpers_detectExecutablesAndOsMetadata() {
        assertTrue(BridgeFileTransferManager.isExecutable("app.APK"))
        assertTrue(BridgeFileTransferManager.isExecutable("run.sh"))
        assertTrue(BridgeFileTransferManager.isExecutable("setup.exe"))
        assertTrue(BridgeFileTransferManager.isExecutable("autorun.command"))
        assertTrue(BridgeFileTransferManager.isExecutable("payload.workflow"))
        assertTrue(BridgeFileTransferManager.isExecutable("script.scpt"))
        assertTrue(BridgeFileTransferManager.isExecutable("agent.py"))
        assertTrue(BridgeFileTransferManager.isExecutable("launcher.jar"))
        assertTrue(BridgeFileTransferManager.isExecutable("phish.webloc"))
        assertFalse(BridgeFileTransferManager.isExecutable("photo.jpg"))
        assertFalse(BridgeFileTransferManager.isExecutable("notes.pdf"))

        assertTrue(BridgeFileTransferManager.isIgnoredMetadata(".DS_Store"))
        assertTrue(BridgeFileTransferManager.isIgnoredMetadata("__MACOSX"))
        assertTrue(BridgeFileTransferManager.isIgnoredMetadata("._document.pdf"))
        assertFalse(BridgeFileTransferManager.isIgnoredMetadata("document.pdf"))

        assertEquals(
            "abedb85429f8107b6dbb1a10dd1ca078d232b7f52f1e8f621cfb5b23f613e34e",
            BridgeFileTransferManager.computeDataSocketAuth("sample-transfer-token", "tx_12345", 65536L)
        )
    }

    @Test
    fun bridgeSpeke_deterministicVectorMatchesMacOS_andRejectsInvalidGroupElements() {
        val code = "482910"
        val clientNonce = "00112233445566778899aabbccddeeff"
        val serverNonce = "ffeeddccbbaa99887766554433221100"

        val md = MessageDigest.getInstance("SHA-256")
        val clientScalar = BigInteger(1, md.digest("deterministic-client-scalar".toByteArray(Charsets.UTF_8)))
        val serverScalar = BigInteger(1, md.digest("deterministic-server-scalar".toByteArray(Charsets.UTF_8)))

        val clientKP = BridgeSpeke.generateKeyPair(code, clientNonce, clientScalar)
        val serverKP = BridgeSpeke.generateKeyPair(code, clientNonce, serverScalar)

        assertTrue(clientKP.publicHex.startsWith("1b4f3adc32b2cd3737115a7a07b5579d483b136f1784520dd2fd505aa2ec9b4f"))
        assertTrue(serverKP.publicHex.startsWith("3304c34f377eb869ab75eade3a1f7b8e117a73d6f380825895d60e4b0eb5496a"))

        val clientShared = BridgeSpeke.computeSharedSecret(serverKP.publicHex, clientKP.privateScalar)
        val serverShared = BridgeSpeke.computeSharedSecret(clientKP.publicHex, serverKP.privateScalar)
        assertNotNull(clientShared)
        assertEquals(clientShared, serverShared)
        assertTrue(clientShared!!.startsWith("129b5d17f6f0fb9fe3ae58cae7230fa8c4e1b0dfabf044c52005c6e3781f001f"))

        val material = BridgeSpeke.deriveSessionMaterial(
            sharedSecretHex = clientShared,
            pairingCode = code,
            clientNonce = clientNonce,
            serverNonce = serverNonce,
            clientPublicHex = clientKP.publicHex,
            serverPublicHex = serverKP.publicHex
        )
        assertEquals("b80392ad0780478b3bf87f5b61fd2f22a8359d25c7dee09bb93c52e57a690d52", material.clientProof)
        assertEquals("13e5e9de0452dc3611549979cea6eb3f9a5517f81f8273e85535abf06ed3fcf5", material.serverProof)

        // Wrong pairing code produces different proofs
        val wrongKP = BridgeSpeke.generateKeyPair("999999", clientNonce, clientScalar)
        val wrongShared = BridgeSpeke.computeSharedSecret(serverKP.publicHex, wrongKP.privateScalar)!!
        val wrongMaterial = BridgeSpeke.deriveSessionMaterial(
            sharedSecretHex = wrongShared,
            pairingCode = "999999",
            clientNonce = clientNonce,
            serverNonce = serverNonce,
            clientPublicHex = wrongKP.publicHex,
            serverPublicHex = serverKP.publicHex
        )
        assertNotEquals(material.clientProof, wrongMaterial.clientProof)

        // Reject trivial / invalid group elements (0, 1, p-1)
        assertNull(BridgeSpeke.computeSharedSecret("0".repeat(512), clientKP.privateScalar))
        assertNull(BridgeSpeke.computeSharedSecret("0".repeat(511) + "1", clientKP.privateScalar))
    }

    @Test
    fun bridgeSessionCipher_deterministicVectorMatchesMacOS_andEnforcesSeqAndAad() {
        val md = MessageDigest.getInstance("SHA-256")
        val testKey = SecretKeySpec(md.digest("test-session-key".toByteArray(Charsets.UTF_8)), "AES")
        val c2sKey = SecretKeySpec(md.digest("c2s-key".toByteArray(Charsets.UTF_8)), "AES")
        val s2cKey = SecretKeySpec(md.digest("s2c-key".toByteArray(Charsets.UTF_8)), "AES")

        val vecCipher = BridgeSessionCipher(
            sendKey = testKey,
            receiveKey = testKey,
            sendDirection = "c2s",
            receiveDirection = "c2s"
        )
        val salt = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())
        val sealedVec = vecCipher.seal("""{"type":"clip","text":"secret hello"}""", saltOverride = salt)
        val vecObj = JSONObject(sealedVec)
        assertEquals(
            "qrvM3QAAAAAAAAABj9S5pvE1dq/nFETBIMF65kliHlU9lp23sE7XgDN72AYSfHfaknczz+4/TmBF1LvejSdnHHs=",
            vecObj.optString("d")
        )
        assertEquals("""{"type":"clip","text":"secret hello"}""", vecCipher.open(vecObj))

        val clientCipher = BridgeSessionCipher(c2sKey, s2cKey, "c2s", "s2c")
        val serverCipher = BridgeSessionCipher(s2cKey, c2sKey, "s2c", "c2s")

        val frame1 = clientCipher.seal("""{"type":"clip","text":"one"}""")
        val frame2 = clientCipher.seal("""{"type":"clip","text":"two"}""")
        val obj1 = JSONObject(frame1)
        val obj2 = JSONObject(frame2)

        // Out-of-order frame (seq 2 before seq 1) must be rejected
        assertNull(serverCipher.open(obj2))
        // Expected seq 1 succeeds, then seq 2 succeeds
        assertEquals("""{"type":"clip","text":"one"}""", serverCipher.open(obj1))
        assertEquals("""{"type":"clip","text":"two"}""", serverCipher.open(obj2))
        // Replay of seq 1 must be rejected
        assertNull(serverCipher.open(obj1))
        // Reflection attack (feeding client's c2s frame back into client's s2c receiver) must fail
        val clientMirror = BridgeSessionCipher(c2sKey, c2sKey, "c2s", "s2c")
        assertNull(clientMirror.open(obj1))
    }

    @Test
    fun bridgeTransport_buildRelayHeader_and_parseMacInfo() {
        assertArrayEquals(
            "{\"relay\":8765}\n".toByteArray(Charsets.UTF_8),
            BridgeTransport.buildRelayHeader(8765)
        )
        assertArrayEquals(
            "{\"relay\":54321}\n".toByteArray(Charsets.UTF_8),
            BridgeTransport.buildRelayHeader(54321)
        )

        val valid = BridgeTransport.parseMacInfo(
            """{"psm":49153,"id":"a1b2c3d4","name":"MacBook Pro"}"""
        )
        assertNotNull(valid)
        assertEquals(49153, valid!!.psm)
        assertEquals("a1b2c3d4", valid.id)
        assertEquals("MacBook Pro", valid.name)

        assertNull(BridgeTransport.parseMacInfo("not json"))
        assertNull(BridgeTransport.parseMacInfo("""{"psm":0,"id":"a1b2c3d4","name":"x"}"""))
        assertNull(BridgeTransport.parseMacInfo("""{"psm":70000,"id":"a1b2c3d4","name":"x"}"""))
        assertNull(BridgeTransport.parseMacInfo("""{"psm":1234,"id":"A1B2C3D4","name":"x"}"""))
        assertNull(BridgeTransport.parseMacInfo("""{"psm":1234,"id":"xyz","name":"x"}"""))
        assertNull(BridgeTransport.parseMacInfo("""{"id":"a1b2c3d4","name":"x"}"""))

        assertTrue(BridgeTransport.isValidBluetoothId("a1b2c3d4"))
        assertTrue(BridgeTransport.isValidBluetoothId("01234567"))
        assertTrue(BridgeTransport.isValidBluetoothId("ffffffff"))
        assertFalse(BridgeTransport.isValidBluetoothId("A1B2C3D4"))
        assertFalse(BridgeTransport.isValidBluetoothId("a1b2c3d"))
        assertFalse(BridgeTransport.isValidBluetoothId("a1b2c3d45"))
        assertFalse(BridgeTransport.isValidBluetoothId("a1b2c3dg"))
        assertFalse(BridgeTransport.isValidBluetoothId(null))
        assertFalse(BridgeTransport.isValidBluetoothId(""))
    }

    @Test
    fun bridgeFastLink_chooseDataRoute_allBranches() {
        // LAN control host always routes data over LAN.
        assertEquals(
            BridgeFastLink.DataRoute.LAN,
            BridgeFastLink.chooseDataRoute("192.168.1.5", fastLinkEnabled = true, fastLinkSupported = true)
        )
        assertEquals(
            BridgeFastLink.DataRoute.LAN,
            BridgeFastLink.chooseDataRoute("192.168.1.5", fastLinkEnabled = false, fastLinkSupported = false)
        )
        // Bluetooth control + enabled + supported -> fast link.
        assertEquals(
            BridgeFastLink.DataRoute.FAST_LINK,
            BridgeFastLink.chooseDataRoute(BridgeTransport.BLUETOOTH_HOST, true, true)
        )
        // Disabled or unsupported -> stay on the Bluetooth relay.
        assertEquals(
            BridgeFastLink.DataRoute.BLUETOOTH,
            BridgeFastLink.chooseDataRoute(BridgeTransport.BLUETOOTH_HOST, false, true)
        )
        assertEquals(
            BridgeFastLink.DataRoute.BLUETOOTH,
            BridgeFastLink.chooseDataRoute(BridgeTransport.BLUETOOTH_HOST, true, false)
        )
        // No control host -> Bluetooth fallback.
        assertEquals(
            BridgeFastLink.DataRoute.BLUETOOTH,
            BridgeFastLink.chooseDataRoute(null, true, true)
        )
    }

    @Test
    fun bridgeFastLink_parseReady_and_stripQuotes() {
        val session = "abc12345-6789"
        val ok = JSONObject("""{"type":"fastlink_ready","session":"$session","ip":"192.168.43.1"}""")
        assertEquals("192.168.43.1", BridgeFastLink.parseReady(ok, session))

        // Wrong session -> null
        assertNull(BridgeFastLink.parseReady(ok, "other-session"))
        // Public IP -> null
        val pub = JSONObject("""{"session":"$session","ip":"8.8.8.8"}""")
        assertNull(BridgeFastLink.parseReady(pub, session))
        // Loopback -> null
        val loop = JSONObject("""{"session":"$session","ip":"127.0.0.1"}""")
        assertNull(BridgeFastLink.parseReady(loop, session))
        // Missing ip -> null
        val missing = JSONObject("""{"session":"$session"}""")
        assertNull(BridgeFastLink.parseReady(missing, session))

        assertEquals("AndroidShare_1234", BridgeFastLink.stripQuotes("\"AndroidShare_1234\""))
        assertEquals("plain", BridgeFastLink.stripQuotes("plain"))
        assertNull(BridgeFastLink.stripQuotes(null))
        assertEquals("\"unclosed", BridgeFastLink.stripQuotes("\"unclosed"))
    }

    @Test
    fun bridgeFastLink_isInFailureCooldown() {
        val t0 = 1_000_000L
        // Never failed -> not in cooldown
        assertFalse(BridgeFastLink.isInFailureCooldown(t0, 0L))
        // Just failed -> in cooldown
        assertTrue(BridgeFastLink.isInFailureCooldown(t0, t0))
        assertTrue(BridgeFastLink.isInFailureCooldown(t0 + 30_000, t0))
        // At the boundary -> out of cooldown
        assertFalse(BridgeFastLink.isInFailureCooldown(t0 + 60_000, t0))
        assertFalse(BridgeFastLink.isInFailureCooldown(t0 + 120_000, t0))
        // Custom cooldown window
        assertTrue(BridgeFastLink.isInFailureCooldown(t0 + 5_000, t0, cooldownMs = 10_000))
        assertFalse(BridgeFastLink.isInFailureCooldown(t0 + 10_000, t0, cooldownMs = 10_000))
    }

    @Test
    fun isUsableLanHost_rejectsLoopbackAndPublic_acceptsPrivateLan() {
        val rejected = listOf(
            null,
            "",
            "127.0.0.1",
            "127.0.0.2",
            "127.1.2.3",
            "::1",
            "localhost",
            "::ffff:127.0.0.1",
            "8.8.8.8",
            "1.1.1.1",
            BridgeTransport.BLUETOOTH_HOST
        )
        for (addr in rejected) {
            assertFalse("Expected unusable LAN host: $addr", NetworkSyncClient.isUsableLanHost(addr))
        }

        val accepted = listOf(
            "192.168.1.5",
            "10.0.0.8",
            "172.16.5.4",
            "169.254.1.1",
            "100.64.0.10"
        )
        for (addr in accepted) {
            assertTrue("Expected usable LAN host: $addr", NetworkSyncClient.isUsableLanHost(addr))
        }
    }

    @Test
    fun notificationModels_validateAndRoundTrip() {
        val notif = BridgeNotification(
            id = "notif_1",
            pkg = "com.example.chat",
            app = "Chat",
            title = "Alex",
            text = "Ping",
            actions = listOf(BridgeNotificationAction(id = 1, title = "Reply", hasInput = true))
        )
        val parsed = BridgeNotification.fromJson(JSONObject(notif.toPostJsonString()))
        assertNotNull(parsed)
        assertEquals("notif_1", parsed!!.id)
        assertEquals(1, parsed.actions.size)

        assertNull(NotificationActionRequest.fromJson(JSONObject("""{"id":"","actionId":0}""")))
        assertNull(NotificationDismissRequest.fromJson(JSONObject("""{"id":""}""")))
    }

    @Test
    fun frameCodec_roundTripsBothDirections() {
        val keyBytes = ByteArray(32) { (it * 7 + 3).toByte() }
        val key = SecretKeySpec(keyBytes, "AES")
        val plain = ByteArray(64 * 1024) { (it % 251).toByte() }
        val iv = ByteArray(12) { (it * 5 + 1).toByte() }

        // New encoder -> standard Cipher decrypt, checking wire layout:
        // [4-byte BE combined len][12-byte IV][AES-GCM ct + 16-byte tag].
        val frame = ByteArray(4 + 12 + plain.size + 16)
        val encCipher = Cipher.getInstance("AES/GCM/NoPadding")
        val used = encodeFrame(encCipher, key, plain, 0, plain.size, iv, frame)
        assertEquals(16 + plain.size + 16, used)

        val combinedLen = ((frame[0].toInt() and 0xFF) shl 24) or
            ((frame[1].toInt() and 0xFF) shl 16) or
            ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)
        assertEquals(12 + plain.size + 16, combinedLen)
        assertArrayEquals(iv, frame.copyOfRange(4, 16))

        val stdCipher = Cipher.getInstance("AES/GCM/NoPadding")
        stdCipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        val decrypted = stdCipher.doFinal(frame, 16, used - 16)
        assertArrayEquals(plain, decrypted)

        // Standard encrypt -> new decoder.
        val stdEnc = Cipher.getInstance("AES/GCM/NoPadding")
        stdEnc.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ctWithTag = stdEnc.doFinal(plain)
        val wire = ByteArray(12 + ctWithTag.size)
        System.arraycopy(iv, 0, wire, 0, 12)
        System.arraycopy(ctWithTag, 0, wire, 12, ctWithTag.size)
        val out = ByteArray(plain.size + 64)
        val decCipher = Cipher.getInstance("AES/GCM/NoPadding")
        val n = decodeFrame(decCipher, key, wire, 0, wire.size, out)
        assertEquals(plain.size, n)
        assertArrayEquals(plain, out.copyOfRange(0, n))
    }

    @Test
    fun bleLinkTuner_policyHysteresis() {
        val low = BleLinkTuner.Priority.LOW_POWER
        val high = BleLinkTuner.Priority.HIGH
        // Engage HIGH at >=8KB in a 1s window.
        assertEquals(high, BleLinkTuner.desiredPriority(low, 8192, 0))
        assertEquals(high, BleLinkTuner.desiredPriority(low, 65536, 99))
        assertEquals(low, BleLinkTuner.desiredPriority(low, 8191, 0))
        assertEquals(low, BleLinkTuner.desiredPriority(low, 0, 3))
        // Stay HIGH while busy; drop to LOW_POWER only after 5 quiet windows.
        assertEquals(high, BleLinkTuner.desiredPriority(high, 8192, 0))
        assertEquals(high, BleLinkTuner.desiredPriority(high, 100, 4))
        assertEquals(low, BleLinkTuner.desiredPriority(high, 100, 5))
        assertEquals(low, BleLinkTuner.desiredPriority(high, 0, 6))
    }

    @Test
    fun appIconDedup_marksOnceUntilCleared() {
        AppIconDedup.clear()
        assertTrue(AppIconDedup.mark("com.example.a"))
        assertFalse(AppIconDedup.mark("com.example.a"))
        assertTrue(AppIconDedup.mark("com.example.b"))
        // Cleared on (re)connect/disconnect so a new connection resends icons.
        AppIconDedup.clear()
        assertTrue(AppIconDedup.mark("com.example.a"))
    }
}

class PhaseAUnitTest {
    @Test
    fun networkLabelMappings() {
        // NR advanced -> 5G+, NR -> 5G
        assertEquals("5G+", networkLabel(ConnectivityManager.TYPE_MOBILE, 21))
        // dataNetworkType NR (20) -> 5G
        assertEquals("5G", networkLabel(20, -1))
        assertEquals("LTE+", networkLabel(ConnectivityManager.TYPE_MOBILE, 19))
        assertEquals("LTE+", networkLabel(ConnectivityManager.TYPE_MOBILE, 29))
        assertEquals("LTE", networkLabel(ConnectivityManager.TYPE_MOBILE, 13))
        assertEquals("3G", networkLabel(ConnectivityManager.TYPE_MOBILE, 5))
        assertEquals("3G", networkLabel(ConnectivityManager.TYPE_MOBILE, 8))
        assertEquals("2G", networkLabel(ConnectivityManager.TYPE_MOBILE, 1))
        assertEquals("2G", networkLabel(ConnectivityManager.TYPE_MOBILE, 2))
        assertEquals("2G", networkLabel(ConnectivityManager.TYPE_MOBILE, 16))
        assertEquals("", networkLabel(ConnectivityManager.TYPE_MOBILE, -1))
        assertEquals("", networkLabel(-1, -1))
        // override wins
        assertEquals("5G", networkLabel(13, 20))
    }

    @Test
    fun notificationForwardPolicy() {
        // off always drops
        assertFalse(NotificationForwardPolicy.shouldForward("off", 3, false, false, false))
        // normal/high/quiet/muted forward
        assertTrue(NotificationForwardPolicy.shouldForward("normal", 3, false, false, false))
        assertTrue(NotificationForwardPolicy.shouldForward("high", 3, false, false, false))
        // skipSilent drops low importance + ambient unless call/message
        assertFalse(NotificationForwardPolicy.shouldForward(null, 2, false, true, false))
        assertFalse(NotificationForwardPolicy.shouldForward(null, 3, true, true, false))
        assertTrue(NotificationForwardPolicy.shouldForward(null, 2, false, true, true))
        assertTrue(NotificationForwardPolicy.shouldForward(null, 3, true, true, true))
        // skipSilent off -> always forward (except off rule)
        assertTrue(NotificationForwardPolicy.shouldForward(null, 1, true, false, false))
    }

    @Test
    fun appRulesJsonRoundTrip() {
        val rules = mapOf("com.whatsapp" to "high", "com.instagram" to "muted")
        val json = org.json.JSONObject(rules)
        val back = mutableMapOf<String, String>()
        val keys = json.keys()
        while (keys.hasNext()) { val k = keys.next(); back[k] = json.optString(k) }
        assertEquals(rules, back)
    }
}

class CallStateMachineTest {
    private fun states(events: List<CallEvent>) = events.map { it.state }

    @Test
    fun incomingFullFlow() {
        val m = CallStateMachine()
        // callback ringing (no number) then broadcast ringing (with number)
        assertEquals(listOf("ringing"), states(m.onRinging(null)))
        assertEquals(listOf("ringing"), states(m.onRinging("+15551234")))
        val active = m.onOffhook(1000L)
        assertEquals(listOf("active"), states(active))
        assertEquals("incoming", active[0].direction)
        assertEquals(1000L, active[0].startedAt)
        assertEquals(listOf("ended"), states(m.onIdle()))
        assertFalse(m.isInCall)
    }

    @Test
    fun duplicatesProduceNoExtraEvents() {
        val m = CallStateMachine()
        m.onRinging(null)
        // broadcast + callback both fire ringing again — no extra
        assertTrue(m.onRinging(null).isEmpty())
        assertTrue(m.onRinging("+1").isNotEmpty()) // number arrival re-emits once
        assertTrue(m.onRinging("+1").isEmpty())    // same number again: nothing
        // offhook fired twice (receiver + callback)
        assertEquals(1, m.onOffhook(5L).size)
        assertTrue(m.onOffhook(6L).isEmpty())
        // idle fired twice
        assertEquals(1, m.onIdle().size)
        assertTrue(m.onIdle().isEmpty())
    }

    @Test
    fun outgoingFlow() {
        val m = CallStateMachine()
        // offhook with no prior ringing => outgoing
        val ev = m.onOffhook(2000L)
        assertEquals(listOf("active"), states(ev))
        assertEquals("outgoing", ev[0].direction)
        assertEquals(2000L, ev[0].startedAt)
        assertEquals(listOf("ended"), states(m.onIdle()))
    }

    @Test
    fun missedCallRingingThenIdle() {
        val m = CallStateMachine()
        m.onRinging("+1555")
        val ended = m.onIdle()
        assertEquals(listOf("ended"), states(ended))
        assertFalse(m.isInCall)
    }

    @Test
    fun nameArrivalReemitsRinging() {
        val m = CallStateMachine()
        m.onRinging("+15551234")
        // name arrives -> ringing re-emitted with name
        val ev = m.onContactName("Mom")
        assertEquals(listOf("ringing"), states(ev))
        assertEquals("Mom", ev[0].name)
        assertEquals("+15551234", ev[0].number)
        // same name again -> nothing
        assertTrue(m.onContactName("Mom").isEmpty())
    }

    @Test
    fun idleWhenNothingHappeningEmitsNothing() {
        val m = CallStateMachine()
        assertTrue(m.onIdle().isEmpty())
        assertTrue(m.onOffhook(1L).isNotEmpty()) // outgoing still emits active
    }
}
