package com.qtekfun.ultimatecamera.core.backup

import java.io.IOException

/** Every backup failure, with a [reason] the UI maps to a localised message. */
class BackupException(val reason: Reason, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Reason {
        /** Not an UltimateCamera backup (neither a zip nor an encrypted backup, or no manifest). */
        NOT_A_BACKUP,

        /** The backup is encrypted and no password was given. */
        PASSWORD_REQUIRED,

        /** The password does not open the backup (or its first block is damaged). */
        WRONG_PASSWORD,

        /** Truncated, damaged or tampered with: a checksum, a tag or the zip structure does not hold. */
        CORRUPT,

        /** Written by a newer app version (format or a schema newer than this app knows): refused, nothing changed. */
        TOO_NEW
    }
}
