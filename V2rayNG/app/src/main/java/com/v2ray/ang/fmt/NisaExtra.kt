package com.v2ray.ang.fmt

import android.util.Base64
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.util.JsonUtil

/**
 * Field NISA (SlowDNS, payload/TLS/SNI/proxy SSH) yang tidak punya tempat di format link standar.
 * Dibawa sebagai satu parameter "nisa=<base64url JSON>" di akhir query link (vless/trojan/ss/dst),
 * atau di field JSON "nisa" untuk vmess://. Klien lain mengabaikannya, jadi link tetap kompatibel.
 */
object NisaExtra {
    private const val B64 = Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE
    private val param = Regex("[?&]nisa=([^&]*)")

    private data class Data(
        var sd: Boolean? = null, var sdd: String? = null, var sdk: String? = null, var sdh: String? = null,
        var pl: String? = null, var tls: Boolean? = null, var sni: String? = null, var px: String? = null,
    )

    /** Token terenkode, atau null jika akun tidak memakai field NISA. */
    fun encode(c: ProfileItem): String? {
        val d = Data(
            sd = if (c.slowDnsEnabled) true else null,
            sdd = c.slowDnsDomain.nullIfBlank(), sdk = c.slowDnsPubkey.nullIfBlank(), sdh = c.slowDnsDoh.nullIfBlank(),
            pl = c.sshPayload.nullIfBlank(), tls = if (c.sshTls) true else null,
            sni = c.sshSni.nullIfBlank(), px = c.sshProxy.nullIfBlank(),
        )
        if (d == Data()) return null
        return Base64.encodeToString(JsonUtil.toJson(d).toByteArray(Charsets.UTF_8), B64)
    }

    fun apply(c: ProfileItem, token: String) {
        try {
            val json = Base64.decode(token, B64).toString(Charsets.UTF_8)
            val d = JsonUtil.fromJson(json, Data::class.java) ?: return
            c.slowDnsEnabled = d.sd == true
            c.slowDnsDomain = d.sdd; c.slowDnsPubkey = d.sdk; c.slowDnsDoh = d.sdh
            c.sshPayload = d.pl; c.sshTls = d.tls == true; c.sshSni = d.sni; c.sshProxy = d.px
        } catch (_: Exception) {
        }
    }

    /** Sisipkan token sebelum fragment (#nama). */
    fun attach(link: String, token: String): String {
        val hash = link.indexOf('#')
        val head = if (hash >= 0) link.substring(0, hash) else link
        val tail = if (hash >= 0) link.substring(hash) else ""
        return head + (if (head.contains('?')) "&" else "?") + "nisa=" + token + tail
    }

    /** Lepas parameter nisa dari link; kembalikan (link bersih, token). */
    fun strip(link: String): Pair<String, String?> {
        val hash = link.indexOf('#')
        val head = if (hash >= 0) link.substring(0, hash) else link
        val tail = if (hash >= 0) link.substring(hash) else ""
        val m = param.find(head) ?: return link to null
        var newHead = head.removeRange(m.range)
        if (m.value.startsWith("?") && !newHead.contains('?') && newHead.contains('&')) {
            newHead = newHead.replaceFirst('&', '?')
        }
        return (newHead + tail) to m.groupValues[1]
    }
}
