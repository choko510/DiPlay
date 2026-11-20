package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.transport.LockdownPairRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Each iPhone keeps its own wired pair record, so two drivers never make each other tap Trust again. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LockdownRecordPersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    @Before
    fun clearPreferences() {
        prefs.edit().clear().commit()
    }

    @Test
    fun eachIphoneLoadsOnlyItsOwnRecord() {
        save(record("a"))
        save(record("b"))
        assertEquals("HOST-a", load("a")?.hostId)
        assertEquals("HOST-b", load("b")?.hostId)
        assertNull(load("c"))
    }

    @Test
    fun clearingOneIphoneLeavesTheOther() {
        save(record("a"))
        save(record("b"))
        AirPlayPersistence.clearLockdownRecord(context, deviceKey("a"))
        assertNull(load("a"))
        assertEquals("HOST-b", load("b")?.hostId)
        assertEquals("host-private-b", String(load("b")!!.hostPrivateKeyPem))
    }

    @Test
    fun clearingAnUnknownIphoneChangesNothing() {
        save(record("a"))
        AirPlayPersistence.clearLockdownRecord(context, deviceKey("never-paired"))
        assertEquals("HOST-a", load("a")?.hostId)
        assertEquals(1, ids().size)
    }

    @Test
    fun clearingAnIphoneRemovesOnlyItsOwnFieldValues() {
        save(record("a"))
        save(record("b"))
        AirPlayPersistence.clearLockdownRecord(context, deviceKey("b"))
        val staleFields = prefs.all.keys.filter { it.startsWith("lockdown.") && it.endsWith(".host_private") }
        assertEquals(1, staleFields.size)
        assertEquals("host-private-a".toByteArray().toHex(), prefs.getString(staleFields.single(), null))
    }

    @Test
    fun savingAnIphoneAgainReplacesItsRecord() {
        save(record("a", hostId = "FIRST"))
        save(record("a", hostId = "SECOND"))
        assertEquals("SECOND", load("a")?.hostId)
        assertTrue(prefs.all.values.none { it == "FIRST" })
    }

    @Test
    fun savingTheSameIphoneTwiceCountsOnce() {
        save(record("a", hostId = "FIRST"))
        save(record("a", hostId = "SECOND"))
        for (phone in 1..7) save(record("p$phone"))
        assertEquals("SECOND", load("a")?.hostId)
        assertEquals(8, ids().size)
        assertNotNull(load("p7"))
    }

    @Test
    fun legacySingleRecordKeepsLoadingForItsIphone() {
        writeLegacy(record("a"))
        assertEquals("HOST-a", load("a")?.hostId)
        assertNull(load("b"))
        assertTrue(LEGACY_KEYS.none(prefs::contains))
        assertEquals("HOST-a", load("a")?.hostId)
    }

    @Test
    fun legacySingleRecordSurvivesAnotherIphonePairing() {
        writeLegacy(record("a"))
        save(record("b"))
        assertEquals("HOST-a", load("a")?.hostId)
        assertEquals("HOST-b", load("b")?.hostId)
        assertTrue(LEGACY_KEYS.none(prefs::contains))
    }

    @Test
    fun migratingTwiceLeavesOneRecordForTheLegacyIphone() {
        writeLegacy(record("a"))
        assertEquals("HOST-a", load("a")?.hostId)
        // A second run sees the converted store; the legacy keys are already gone.
        save(record("a", hostId = "RENEWED"))
        assertEquals("RENEWED", load("a")?.hostId)
        assertEquals(1, ids().size)
        assertTrue(LEGACY_KEYS.none(prefs::contains))
    }

    @Test
    fun anInterruptedMigrationRepeatsWithoutLosingTheRecord() {
        // State after a crash between saving the per-iPhone record and removing the flat keys.
        writeLegacy(record("a"))
        assertEquals("HOST-a", load("a")?.hostId)
        writeLegacy(record("a", hostId = "LEGACY-COPY"))
        assertEquals("HOST-a", load("a")?.hostId)
        assertTrue(LEGACY_KEYS.none(prefs::contains))
        assertEquals(1, ids().size)
    }

    @Test
    fun incompleteLegacyRecordIsDroppedWithoutLoading() {
        prefs.edit().putString("lockdown_host_id", "HOST-a").putString("lockdown_wifi_mac", "aa:bb").commit()
        save(record("b"))
        assertNull(load("a"))
        assertEquals("HOST-b", load("b")?.hostId)
        assertTrue(LEGACY_KEYS.none(prefs::contains))
    }

    @Test
    fun undecodableLegacyRecordIsDropped() {
        prefs.edit()
            .putString("lockdown_host_id", "HOST-a")
            .putString("lockdown_system_buid", "BUID-a")
            .putString("lockdown_wifi_mac", "aa:bb:cc:dd:ee:ff")
            .putString("lockdown_device_public", "not-hex")
            .putString("lockdown_device_cert", "00")
            .putString("lockdown_host_private", "00")
            .putString("lockdown_host_cert", "00")
            .putString("lockdown_root_private", "00")
            .putString("lockdown_root_cert", "00")
            .commit()
        assertNull(load("a"))
        assertTrue(LEGACY_KEYS.none(prefs::contains))
    }

    @Test
    fun ninthIphoneEvictsTheLeastRecentlySaved() {
        for (phone in 1..8) save(record("p$phone"))
        save(record("p1"))
        save(record("p9"))
        assertNull(load("p2"))
        assertTrue(prefs.all.values.none { it == "HOST-p2" })
        for (phone in listOf(1, 3, 4, 5, 6, 7, 8, 9)) assertNotNull("p$phone", load("p$phone"))
    }

    @Test
    fun aConfirmedConnectionMovesItsRecordToTheFrontWithoutRenewingIt() {
        for (phone in 1..8) save(record("p$phone"))
        val before = load("p1")!!
        AirPlayPersistence.markLockdownRecordUsed(context, deviceKey("p1"))
        save(record("p9"))
        assertNotNull(load("p1"))
        assertNull(load("p2"))
        assertEquals(before.hostId, load("p1")?.hostId)
        assertEquals(String(before.hostPrivateKeyPem), String(load("p1")!!.hostPrivateKeyPem))
    }

    @Test
    fun markingAnUnknownIphoneChangesNothing() {
        save(record("a"))
        AirPlayPersistence.markLockdownRecordUsed(context, deviceKey("never-paired"))
        assertEquals(listOf(id("a")), ids())
    }

    @Test
    fun theNewestRecordIsTheLastOneUsed() {
        assertNull(AirPlayPersistence.loadNewestLockdownRecord(context))
        save(record("a"))
        save(record("b"))
        assertEquals("HOST-b", AirPlayPersistence.loadNewestLockdownRecord(context)?.hostId)
        AirPlayPersistence.markLockdownRecordUsed(context, deviceKey("a"))
        assertEquals("HOST-a", AirPlayPersistence.loadNewestLockdownRecord(context)?.hostId)
        save(record("a"))
        assertEquals("HOST-a", AirPlayPersistence.loadNewestLockdownRecord(context)?.hostId)
    }

    @Test
    fun aLegacyRecordIsTheNewest() {
        writeLegacy(record("a"))
        assertEquals("HOST-a", AirPlayPersistence.loadNewestLockdownRecord(context)?.hostId)
    }

    @Test
    fun aCorruptRecordIsSkippedAndDoesNotBlockAnotherIphone() {
        save(record("a"))
        save(record("b"))
        prefs.edit().putString("lockdown.${id("b")}.device_cert", "not-hex").commit()
        assertNull(load("b"))
        assertEquals("HOST-a", load("a")?.hostId)
        // A corrupt newest entry is skipped for the fallback port too.
        assertEquals("HOST-a", AirPlayPersistence.loadNewestLockdownRecord(context)?.hostId)
    }

    @Test
    fun invalidAndDuplicatedIdsInTheStoredListAreIgnored() {
        save(record("a"))
        val stored = "${id("a")},not-a-fingerprint,../escape,${id("a")}"
        prefs.edit().putString("lockdown_record_ids", stored).commit()
        assertEquals("HOST-a", load("a")?.hostId)
        save(record("b"))
        assertEquals(listOf(id("b"), id("a")), ids())
        assertTrue(prefs.getString("lockdown.not-a-fingerprint.host_id", null) == null)
    }

    @Test
    fun anOrphanRecordWithoutAListEntryIsNotOffered() {
        save(record("a"))
        prefs.edit().remove("lockdown_record_ids").commit()
        assertNull(load("a"))
        assertNull(AirPlayPersistence.loadNewestLockdownRecord(context))
        // The bytes stay until the same iPhone pairs again; nothing unrelated is removed.
        assertTrue(prefs.contains("lockdown.${id("a")}.host_id"))
    }

    @Test
    fun concurrentSaveAndClearKeepTheListConsistent() {
        save(record("a"))
        val threads = (1..4).map { worker ->
            Thread {
                for (round in 1..10) {
                    val phone = "w$worker-$round"
                    save(record(phone))
                    AirPlayPersistence.markLockdownRecordUsed(context, deviceKey(phone))
                    if (round % 3 == 0) AirPlayPersistence.clearLockdownRecord(context, deviceKey(phone))
                }
            }
        }
        threads.forEach(Thread::start)
        threads.forEach { it.join(30_000) }
        val ids = ids()
        assertTrue("at most $ids", ids.size <= 8)
        assertEquals(ids.distinct(), ids)
        // Every listed id still names a complete record, and every record that is still listed loads.
        for (id in ids) {
            assertTrue(prefs.contains("lockdown.$id.host_id"))
            assertTrue(prefs.contains("lockdown.$id.host_private"))
        }
        for (phone in listOf("a")) {
            val recordId = id(phone)
            if (recordId in ids) assertEquals("HOST-$phone", load(phone)?.hostId)
        }
    }

    @Test
    fun noPrivateMaterialIsStoredOutsideTheLockdownRecordKeys() {
        save(record("a"))
        val secret = prefs.all.filterValues { it == "host-private-a".toByteArray().toHex() }.keys
        assertEquals(setOf("lockdown.${id("a")}.host_private"), secret)
        assertTrue(LEGACY_KEYS.none(prefs::contains))
    }

    private fun save(record: LockdownPairRecord) = AirPlayPersistence.saveLockdownRecord(context, record)

    private fun load(phone: String) = AirPlayPersistence.loadLockdownRecord(context, deviceKey(phone))

    private fun ids(): List<String> =
        prefs.getString("lockdown_record_ids", null)?.split(',')?.filter(String::isNotEmpty) ?: emptyList()

    private fun id(phone: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(deviceKey(phone))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun deviceKey(phone: String) =
        "-----BEGIN RSA PUBLIC KEY-----\n$phone\n-----END RSA PUBLIC KEY-----\n".toByteArray()

    private fun record(phone: String, hostId: String = "HOST-$phone") = LockdownPairRecord.restore(
        hostId = hostId,
        systemBuid = "BUID-$phone",
        wifiMacAddress = "aa:bb:cc:dd:ee:ff",
        devicePublicKeyPem = deviceKey(phone),
        deviceCertificatePem = "device-cert-$phone".toByteArray(),
        hostPrivateKeyPem = "host-private-$phone".toByteArray(),
        hostCertificatePem = "host-cert-$phone".toByteArray(),
        rootPrivateKeyPem = "root-private-$phone".toByteArray(),
        rootCertificatePem = "root-cert-$phone".toByteArray(),
    )

    /** The nine flat keys releases up to 0.2.22 wrote for their single record. */
    private fun writeLegacy(record: LockdownPairRecord) {
        prefs.edit()
            .putString("lockdown_host_id", record.hostId)
            .putString("lockdown_system_buid", record.systemBuid)
            .putString("lockdown_wifi_mac", record.wifiMacAddress)
            .putString("lockdown_device_public", record.devicePublicKeyPem.toHex())
            .putString("lockdown_device_cert", record.deviceCertificatePem.toHex())
            .putString("lockdown_host_private", record.hostPrivateKeyPem.toHex())
            .putString("lockdown_host_cert", record.hostCertificatePem.toHex())
            .putString("lockdown_root_private", record.rootPrivateKeyPem.toHex())
            .putString("lockdown_root_cert", record.rootCertificatePem.toHex())
            .commit()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        val LEGACY_KEYS = listOf(
            "lockdown_host_id", "lockdown_system_buid", "lockdown_wifi_mac", "lockdown_device_public",
            "lockdown_device_cert", "lockdown_host_private", "lockdown_host_cert", "lockdown_root_private",
            "lockdown_root_cert",
        )
    }
}
