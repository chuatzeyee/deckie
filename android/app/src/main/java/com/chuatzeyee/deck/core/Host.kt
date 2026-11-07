package com.chuatzeyee.deck.core

import kotlinx.serialization.Serializable

/**
 * A user-configured SSH host and the tmux sessions the user has chosen to show for it. Nothing
 * about any particular host is ever hardcoded - every instance comes from what the user typed in
 * the app.
 *
 * @property sessions the user's chosen tmux sessions, in rail order.
 */
@Serializable
data class Host(
    val id: String,
    val name: String,
    val address: String,
    val port: Int = 22,
    val user: String,
    val keyId: String,
    val pinnedHostKey: String? = null,
    val sessions: List<String> = emptyList(),
)

private val HOSTNAME_REGEX = Regex("^[A-Za-z0-9.-]{1,253}$")
private val USER_REGEX = Regex("^[a-z_][a-z0-9_.-]{0,31}$")
private val IPV4_REGEX = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
private val IPV6_GROUP_REGEX = Regex("^[0-9A-Fa-f]{1,4}$")

/**
 * Validates the fields of the host-entry form. Returns a map from field name (`name`, `address`,
 * `port`, `user`) to an error message; a field with no entry is valid. An empty map means the
 * whole form is valid.
 */
fun validateHost(name: String, address: String, port: String, user: String): Map<String, String> {
    val errors = mutableMapOf<String, String>()
    if (name.isBlank() || name.length !in 1..40) {
        errors["name"] = "Name must be 1-40 characters"
    }
    if (!isValidAddress(address)) {
        errors["address"] = "Address must be a valid IPv4 address, IPv6 address, or hostname"
    }
    if (port.toIntOrNull()?.let { it in 1..65535 } != true) {
        errors["port"] = "Port must be a number between 1 and 65535"
    }
    if (!USER_REGEX.matches(user)) {
        errors["user"] = "User must start with a lowercase letter or underscore and contain only " +
            "lowercase letters, digits, '_', '.', or '-'"
    }
    return errors
}

private fun isValidAddress(address: String): Boolean =
    isValidIpv4(address) || isValidIpv6(address) || isValidHostname(address)

/** Dotted-quad IPv4; leading-zero octets (e.g. `"057"`) are accepted as decimal, not octal. */
private fun isValidIpv4(address: String): Boolean {
    val match = IPV4_REGEX.matchEntire(address) ?: return false
    return match.groupValues.drop(1).all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
}

/** Bracketless IPv6 literal, with or without `::` zero-group compression. */
private fun isValidIpv6(address: String): Boolean {
    if (address.contains(".")) return false
    val doubleColonCount = Regex("::").findAll(address).count()
    if (doubleColonCount > 1) return false
    if (doubleColonCount == 0) {
        val groups = address.split(":")
        return groups.size == 8 && groups.all(IPV6_GROUP_REGEX::matches)
    }
    val (left, right) = address.split("::", limit = 2)
    val leftGroups = if (left.isEmpty()) emptyList() else left.split(":")
    val rightGroups = if (right.isEmpty()) emptyList() else right.split(":")
    val allGroups = leftGroups + rightGroups
    return allGroups.size <= 7 && allGroups.all(IPV6_GROUP_REGEX::matches)
}

private fun isValidHostname(address: String): Boolean =
    HOSTNAME_REGEX.matches(address) && !address.startsWith("-")

/** A host Deck can start watching without asking anything: its key is pinned and sessions chosen. */
fun isAutoStartable(host: Host): Boolean = host.pinnedHostKey != null && host.sessions.isNotEmpty()

/** The host to auto-start (ruling R17): the last active one if it is still saved and startable,
 * otherwise the first startable host, otherwise none. */
fun pickAutoStartHost(hosts: List<Host>, lastHostId: String?): Host? =
    hosts.firstOrNull { it.id == lastHostId && isAutoStartable(it) } ?: hosts.firstOrNull(::isAutoStartable)
