package dev.sunnat629.mba.core.ticket

public const val MBA_TICKET_TITLE_PREFIX: String = "[MBA]"

public fun String.withMbaTicketPrefix(): String {
    val trimmed = trim()
    return if (trimmed.startsWith(MBA_TICKET_TITLE_PREFIX)) {
        trimmed
    } else {
        "$MBA_TICKET_TITLE_PREFIX $trimmed"
    }
}
