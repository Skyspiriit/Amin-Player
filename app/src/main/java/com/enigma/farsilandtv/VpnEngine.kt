package com.enigma.farsilandtv

import libXray.LibXray
import org.json.JSONArray
import org.json.JSONObject

internal class VpnProblem(val reason: VpnFailure) : Exception("VPN operation failed")

internal object VpnEngine {
    fun invoke(method: String, payload: JSONObject = JSONObject()): JSONObject {
        val reply = JSONObject(LibXray.invoke(JSONObject().put("apiVersion", 3)
            .put("method", method).put("payload", payload).toString()))
        // Engine errors can contain endpoint data. Return only our own fixed error category.
        if (!reply.optBoolean("success")) throw VpnProblem(VpnFailure.ENGINE)
        return reply.optJSONObject("data") ?: JSONObject()
    }

    fun nodes(text: String): List<JSONObject> {
        val outbounds = runCatching {
            invoke("convertShareLinksToXrayJson", JSONObject().put("text", text))
        }.getOrElse { throw VpnProblem(VpnFailure.SUBSCRIPTION) }
            .optJSONArray("outbounds") ?: throw VpnProblem(VpnFailure.SUBSCRIPTION)
        val nodes = (0 until outbounds.length()).map { outbounds.getJSONObject(it) }.filter { node ->
            val stream = node.optJSONObject("streamSettings")
            node.optString("protocol") == "vless" && stream != null &&
                stream.optString("security") in setOf("tls", "reality") &&
                stream.optJSONObject("tlsSettings")?.optBoolean("allowInsecure", false) != true
        }
        if (nodes.isEmpty()) throw VpnProblem(VpnFailure.SUBSCRIPTION)
        return nodes
    }

    fun config(node: JSONObject, fd: Int): String {
        val outbound = JSONObject(node.toString()).put("tag", "vpn")
        // Only the parsed VLESS node enters our configuration. Provider routing, listeners,
        // direct fallbacks and logging settings are never applied to the device.
        outbound.remove("sendThrough")
        outbound.remove("proxySettings")
        return JSONObject()
            .put("log", JSONObject().put("loglevel", "none"))
            .put("env", JSONObject().put("xray.tun.fd", fd.toString()))
            .put("inbounds", JSONArray().put(JSONObject().put("tag", "tun").put("protocol", "tun")
                .put("settings", JSONObject().put("name", "aminvpn").put("mtu", 1500))))
            .put("outbounds", JSONArray().put(outbound))
            .toString()
    }

    fun running() = invoke("getXrayState").optBoolean("running")
    fun stop() { invoke("stopXray") }
}
