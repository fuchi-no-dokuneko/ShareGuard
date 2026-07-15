package app.shareguard.canonical

import android.app.Application

/**
 * Process root. Long-lived objects are added through an explicit application container so source
 * content is never placed in a global singleton or diagnostic logger.
 */
class ShareGuardApplication : Application()
