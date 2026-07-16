package app.shareguard.core.storage

import java.io.File

/** API-23-compatible canonical containment check that does not confuse sibling path prefixes. */
fun File.isStrictlyInside(approvedRoot: File): Boolean {
    val canonicalCandidate = runCatching { canonicalFile }.getOrNull() ?: return false
    val canonicalRoot = runCatching { approvedRoot.canonicalFile }.getOrNull() ?: return false
    var parent = canonicalCandidate.parentFile
    while (parent != null) {
        if (parent == canonicalRoot) return true
        parent = parent.parentFile
    }
    return false
}

/** Resolves parent links first, then detects whether the final directory entry itself is a link. */
fun File.isSymbolicLinkCompat(): Boolean {
    val parent = parentFile ?: return false
    val resolvedParent = runCatching { parent.canonicalFile }.getOrNull() ?: return true
    val entryWithoutFollowingFinalLink = File(resolvedParent, name).absoluteFile
    val resolvedEntry = runCatching { canonicalFile }.getOrNull() ?: return true
    return entryWithoutFollowingFinalLink != resolvedEntry
}
