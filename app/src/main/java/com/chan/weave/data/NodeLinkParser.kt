package com.chan.weave.data

import android.net.Uri
import android.util.Base64
import com.chan.weave.core.CoreType
import java.net.URLDecoder

/**
 * Share-link → [Node] parser. Weave is Mist-only since 1.0.30:
 * only mist:// links are accepted. Every other scheme is rejected
 * with a clear message (use SwiftNG for Xray protocols).
 *
 * mist:// format (v0.1):
 *   mist://<clientIdHex>@<address>:<port>?key=<base64url(authKey)>&sni=<雾域名>#<备注>
 * - clientIdHex: 16-byte CID hex-encoded (32 hex chars)
 * - key: 32-byte K_auth base64url-encoded (standard base64 also
 *   accepted); stored in Node.password as standard base64 (44 chars)
 * - sni: 雾域名, optional, defaults to address
 *
 * Field mapping: address=host, port=port, uuid=clientId(hex),
 * password=<redacted> (standard base64), sni=雾域名.
 */
object NodeLinkParser {

    fun parse(rawLink: String): Result<Node> = runCatching {
        val link = rawLink.trim()
        require(link.isNotEmpty()) { "链接为空，请粘贴节点链接" }
        val scheme = link.substringBefore("://", "").lowercase()
        require(scheme.isNotEmpty() && link.contains("://")) { "不是有效的节点链接：缺少协议头" }
        when (scheme) {
            "mist" -> parseMist(link)
            else -> throw IllegalArgumentException("只支持 mist:// 节点（$scheme 已移除，请用 SwiftNG）")
        }
    }

    // ── common helpers ──────────────────────────────────────────

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (_: Exception) {
        s
    }

    private fun checkHostPort(host: String?, port: Int, scheme: String) {
        require(!host.isNullOrBlank()) { "$scheme 链接缺少服务器地址" }
        require(port in 1..65535) { "$scheme 链接端口无效：$port" }
    }

    private fun nodeName(uri: Uri, fallback: String): String {
        val frag = uri.fragment?.let { decode(it) }.orEmpty().trim()
        return frag.ifEmpty { fallback }
    }

    // ── mist ────────────────────────────────────────────────────

    /** Client ID 必须为 32 个 hex 字符。 */
    fun isValidMistClientId(s: String): Boolean =
        s.trim().matches(Regex("[0-9a-fA-F]{32}"))

    /** Auth Key 必须能解成 32 字节（接受标准 base64 或 base64url）。 */
    fun isValidMistKey(s: String): Boolean = normalizeMistKey(s) != null

    /**
     * 把分享链接里的 key 参数归一化为标准 base64（44 字符），
     * 或 null（缺失 / 解码失败 / 不是 32 字节）。
     */
    private fun normalizeMistKey(raw: String): String? {
        val t0 = raw.trim()
        if (t0.isEmpty()) return null
        var t = t0.replace('-', '+').replace('_', '/')
        val pad = t.length % 4
        if (pad > 0) t += "=".repeat(4 - pad)
        return try {
            val bytes = Base64.decode(t, Base64.DEFAULT)
            if (bytes.size != 32) return null
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseMist(link: String): Node {
        val uri = Uri.parse(link)
        val clientId = decode(uri.encodedUserInfo.orEmpty()).trim()
        require(isValidMistClientId(clientId)) { "mist 链接 Client ID 无效（应为 32 个 hex 字符）" }
        checkHostPort(uri.host, uri.port, "mist")
        val authKey = normalizeMistKey(uri.getQueryParameter("key").orEmpty())
        require(authKey != null) { "mist 链接缺少有效的 key（应为 32 字节密钥的 base64/base64url 编码）" }
        val sni = uri.getQueryParameter("sni")?.takeIf { it.isNotBlank() } ?: uri.host!!
        return Node(
            name = nodeName(uri, "${uri.host}:${uri.port}"),
            rawLink = link,
            protocol = "mist",
            host = uri.host!!,
            port = uri.port,
            uuid = clientId.lowercase(),
            password = authKey,
            network = "tcp",
            security = "tls",
            sni = sni,
            coreType = CoreType.MIST
        )
    }
}
