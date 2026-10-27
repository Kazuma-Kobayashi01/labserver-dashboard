package com.labserver.lablink.data

/** The fields we care about from a wg-quick style config. */
data class ParsedWg(
    val privateKey: String? = null,
    val address: String? = null,
    val hubPublicKey: String? = null,
    val presharedKey: String? = null,
    val endpoint: String? = null,
    val allowedIPs: String? = null,
    /** From an optional `[tcp]` section (`Url`, `Prefix`) that only this app reads. */
    val tcpUrl: String? = null,
    val tcpPrefix: String? = null,
) {
    val isEmpty get() = listOf(privateKey, address, hubPublicKey, presharedKey, endpoint, allowedIPs, tcpUrl, tcpPrefix).all { it == null }
}

fun parseWgQuick(text: String): ParsedWg {
    var section = ""
    val v = mutableMapOf<String, String>()
    for (raw in text.lineSequence()) {
        val line = raw.substringBefore('#').trim()
        if (line.isEmpty()) continue
        if (line.startsWith("[") && line.endsWith("]")) {
            section = line.trim('[', ']').lowercase()
            continue
        }
        val parts = line.split('=', limit = 2)
        if (parts.size != 2) continue
        v["$section.${parts[0].trim().lowercase()}"] = parts[1].trim()
    }
    return ParsedWg(
        privateKey = v["interface.privatekey"],
        address = v["interface.address"]?.substringBefore(',')?.substringBefore('/')?.trim(),
        hubPublicKey = v["peer.publickey"],
        presharedKey = v["peer.presharedkey"],
        endpoint = v["peer.endpoint"],
        allowedIPs = v["peer.allowedips"]?.replace(" ", ""),
        tcpUrl = v["tcp.url"],
        tcpPrefix = v["tcp.prefix"],
    )
}
