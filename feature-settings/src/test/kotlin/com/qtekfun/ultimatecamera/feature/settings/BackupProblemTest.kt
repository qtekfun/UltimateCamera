package com.qtekfun.ultimatecamera.feature.settings

import com.qtekfun.ultimatecamera.core.backup.BackupException
import java.io.IOException
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupProblemTest {
    @Test
    fun everyBackupFailureHasAMessage() {
        val expected = mapOf(
            BackupException.Reason.NOT_A_BACKUP to BackupProblem.NOT_A_BACKUP,
            BackupException.Reason.PASSWORD_REQUIRED to BackupProblem.WRONG_PASSWORD,
            BackupException.Reason.WRONG_PASSWORD to BackupProblem.WRONG_PASSWORD,
            BackupException.Reason.CORRUPT to BackupProblem.CORRUPT,
            BackupException.Reason.TOO_NEW to BackupProblem.TOO_NEW
        )
        BackupException.Reason.entries.forEach { assertEquals(expected.getValue(it), BackupProblem.of(BackupException(it, "x"))) }
        assertEquals(BackupProblem.STORAGE, BackupProblem.of(IOException("disk full")))
    }

    @Test
    fun suggestedNameCarriesTheDate() {
        assertEquals("UltimateCamera-backup-2026-10-17.zip", BackupViewModel.suggestedName(LocalDate.of(2026, 10, 17)))
    }
}
