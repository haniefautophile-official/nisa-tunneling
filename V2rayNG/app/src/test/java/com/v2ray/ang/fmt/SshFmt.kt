package com.v2ray.ang.fmt

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.Utils

/**
 * Format link SSH NISA:
 * ssh://user:pass@host:port?payload=..&tls=1&sni=..&proxy=..&slowdns=1&ns=..&pubkey=..&doh=..#remarks
 * Semua nilai di-URL-encode, jadi password/payload dengan karakter khusus tetap utuh.
 */
object SshFmt {
    private val pattern = Regex("^ssh://(?:([^:@/?#]*)(?::([^@/?#]*))?@)?(\\[[^\\]]+\\]|[^:/?#]+)(?::(\\d+))?/?(?:\\?([^#]*))?(?:#(.*))?$", RegexOption.IGNORE_CASE)

    /** Hasil tanpa skema "ssh://" (skema ditambahkan pemanggil, sama seperti Fmt lain). */
    fun toUri(config: ProfileItem): String {
        val q = linkedMapOf<String, String>()
        config.sshPayload.nullIfBlank()?.let { q["payload"] = it }
        if (config.sshTls) q["tls"] = "1"
        config.sshSni.nullIfBlank()?.let { q["sni"] = it }
        config.sshProxy.nullIfBlank()?.let { q["proxy"] = it }
        if (config.slowDnsEnabled) {
            q["slowdns"] = "1"
            config.slowDnsDomain.nullIfBlank()?.let { q["ns"] = it }
            config.slowDnsPubkey.nullIfBlank()?.let { q["pubkey"] = it }
            config.slowDnsDoh.nullIfBlank()?.let { q["doh"] = it }
        }
        val query = if (q.isEmpty()) "" else "?" + q.entries.joinToString("&") { it.key + "=" + Utils.encodeURIComponent(it.value) }
        val user = Utils.encodeURIComponent(config.username.orEmpty()) + ":" + Utils.encodeURIComponent(config.password.orEmpty())
        val host = Utils.getIpv6Address(HttpUtil.toIdnDomain(config.server.orEmpty()))
        return "$user@$host:${config.serverPort}$query#${Utils.encodeURIComponent(config.remarks)}"
    }

    fun parse(str: String): ProfileItem? {
        val m = pattern.matchEntire(str.trim()) ?: return null
        val (user, pass, host, port, query, frag) = m.destructured
        if (host.isBlank() || user.isBlank()) return null
        val params = query.split("&").filter { it.contains("=") }
            .associate { it.split("=", limit = 2).let { (k, v) -> k to Utils.decodeURIComponent(v) } }

        val c = ProfileItem.create(EConfigType.SSH)
        c.server = host.removePrefix("[").removeSuffix("]")
        c.serverPort = port.ifEmpty { "22" }
        c.username = Utils.decodeURIComponent(user)
        c.password = Utils.decodeURIComponent(pass)
        c.remarks = Utils.decodeURIComponent(frag).ifEmpty { c.server.orEmpty() }
        c.sshPayload = params["payload"].nullIfBlank()
        c.sshTls = params["tls"] == "1"
        c.sshSni = params["sni"].nullIfBlank()
        c.sshProxy = params["proxy"].nullIfBlank()
        c.slowDnsEnabled = params["slowdns"] == "1"
        c.slowDnsDomain = params["ns"].nullIfBlank()
        c.slowDnsPubkey = params["pubkey"].nullIfBlank()
        c.slowDnsDoh = params["doh"].nullIfBlank()
        return c
    }
}
