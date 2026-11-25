package io.github.abe_ma.headachediary.data

import io.github.abe_ma.headachediary.data.fake.FakeBackupRepository
import io.github.abe_ma.headachediary.domain.BackupException
import io.github.abe_ma.headachediary.domain.BackupInspection
import io.github.abe_ma.headachediary.domain.InvalidReason
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class FakeBackupRepositoryTest {
    private val input get() = ByteArrayInputStream(ByteArray(0))

    @Test
    fun FR_601_fake_write_outputs_fixed_backup_json() = runTest {
        val r = FakeBackupRepository(); val out = ByteArrayOutputStream()
        r.write(out)
        assertTrue(out.toString(Charsets.UTF_8).contains("\"format\":\"headache-diary-backup\""))
        assertEquals(1, r.writeCount)
    }

    @Test
    fun FR_603_fake_inspect_returns_configured_preview_with_counts() = runTest {
        val r = FakeBackupRepository()
        val v = r.inspect(input) as BackupInspection.Valid
        assertEquals(3, v.attackCount); assertEquals(2, v.newAttackCount); assertEquals(1, v.duplicateCount)
        r.nextInspection = BackupInspection.Invalid(InvalidReason.CORRUPT)
        assertEquals(BackupInspection.Invalid(InvalidReason.CORRUPT), r.inspect(input))
    }

    @Test
    fun FR_606_fake_restore_throws_backup_exception_with_reason_when_configured() = runTest {
        val r = FakeBackupRepository()
        assertEquals(2, r.restore(input).addedAttacks)
        r.nextRestoreFailure = InvalidReason.UNSUPPORTED_VERSION
        val e = runCatching { r.restore(input) }.exceptionOrNull() as BackupException
        assertEquals(InvalidReason.UNSUPPORTED_VERSION, e.reason)
        assertEquals(2, r.restoreCount)
    }
}
