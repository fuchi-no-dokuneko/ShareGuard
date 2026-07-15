package app.shareguard.core.security

/**
 * A closeable, defensive copy of secret material.
 *
 * Closing performs best-effort in-memory zeroization. The JVM, runtime, hardware, and storage stack can
 * retain additional copies, so this type intentionally makes no physical-sanitization guarantee.
 */
class SecretBytes private constructor(
    private val storage: ByteArray,
) : AutoCloseable {
    private val lock = Any()

    @Volatile
    var isDestroyed: Boolean = false
        private set

    val size: Int
        get() = synchronized(lock) {
            check(!isDestroyed) { "Secret material has been destroyed" }
            storage.size
        }

    /**
     * Supplies a temporary copy and erases that copy when [block] returns or throws. If [block] retains
     * the supplied array, it observes the erased array. Callers should still avoid creating more copies.
     */
    fun <T> access(block: (ByteArray) -> T): T = synchronized(lock) {
        check(!isDestroyed) { "Secret material has been destroyed" }
        val workingCopy = storage.copyOf()
        try {
            block(workingCopy)
        } finally {
            workingCopy.fill(0)
        }
    }

    override fun close() {
        synchronized(lock) {
            if (!isDestroyed) {
                storage.fill(0)
                isDestroyed = true
            }
        }
    }

    internal fun isStorageZeroizedForTest(): Boolean = synchronized(lock) { storage.all { it == 0.toByte() } }

    override fun toString(): String = "SecretBytes(size=redacted, destroyed=$isDestroyed)"

    companion object {
        fun copyOf(bytes: ByteArray): SecretBytes {
            require(bytes.isNotEmpty()) { "Secret material cannot be empty" }
            return SecretBytes(bytes.copyOf())
        }

        /** Copies [bytes] and then best-effort erases the caller-supplied array. */
        fun consume(bytes: ByteArray): SecretBytes = try {
            copyOf(bytes)
        } finally {
            bytes.fill(0)
        }
    }
}
