package com.shantanu.nmaps.share

import android.content.Context
import com.shantanu.nmaps.data.Http
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption for live location. The key only ever travels inside the share link,
 * so the relay sees opaque bytes.
 */
object ShareCrypto {
    private val random = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val b64d = Base64.getUrlDecoder()

    fun newKey(): ByteArray = ByteArray(32).also(random::nextBytes)

    fun encode(bytes: ByteArray): String = b64.encodeToString(bytes)
    fun decode(text: String): ByteArray = b64d.decode(text)

    /** AES-256-GCM; output is base64url(nonce ‖ ciphertext+tag). */
    fun encrypt(key: ByteArray, plain: String): String {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return encode(nonce + cipher.doFinal(plain.toByteArray()))
    }

    /** Null when the text wasn't encrypted with this key or was tampered with. */
    fun decrypt(key: ByteArray, text: String): String? = runCatching {
        val all = decode(text.trim())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, all, 0, 12))
        String(cipher.doFinal(all, 12, all.size - 12))
    }.getOrNull()
}

/** What someone's link points at: a relay topic and the key to read it. */
data class ShareLink(val topic: String, val key: ByteArray) {
    /** The link people receive: opens the app, or offers the download when it isn't installed. */
    val uri get() = com.shantanu.nmaps.data.AppLinks.live(this)

    override fun equals(other: Any?) = other is ShareLink && other.topic == topic && other.key.contentEquals(key)
    override fun hashCode() = topic.hashCode()

    companion object {
        // The web form, the deep-link form the page uses, and links from older builds.
        private val pattern = Regex("""(?:nmaps://live/|nmaps://open\?live=|https://sutharshantanu\.github\.io/NothingMaps/\?live=)([A-Za-z0-9_-]{16,64})/([A-Za-z0-9_-]{43})""")

        fun new(): ShareLink {
            // 128 random bits for the topic: unguessable, so nobody stumbles onto it.
            val topic = "nm" + ShareCrypto.encode(ShareCrypto.newKey().copyOf(16)).replace("-", "a").replace("_", "b")
            return ShareLink(topic, ShareCrypto.newKey())
        }

        /** Finds a share link anywhere in pasted text, e.g. a whole chat message. */
        fun find(text: String): ShareLink? {
            val m = pattern.find(text) ?: return null
            val key = runCatching { ShareCrypto.decode(m.groupValues[2]) }.getOrNull()?.takeIf { it.size == 32 } ?: return null
            return ShareLink(m.groupValues[1], key)
        }
    }
}

/** One location update as sent over the relay. */
data class LiveLocation(
    val name: String,
    val lat: Double,
    val lon: Double,
    val accuracy: Float,
    val speed: Float,
    val battery: Int?,
    val time: Long,
    /** When sharing ends. */
    val until: Long,
    val stopped: Boolean = false,
    val destination: String? = null,
    val eta: Long? = null,
) {
    fun toJson(): String = JSONObject()
        .put("n", name).put("lat", lat).put("lon", lon).put("acc", accuracy.toDouble()).put("spd", speed.toDouble())
        .put("bat", battery ?: JSONObject.NULL).put("t", time).put("u", until).put("stop", stopped)
        .put("dest", destination ?: JSONObject.NULL).put("eta", eta ?: JSONObject.NULL)
        .toString()

    companion object {
        fun fromJson(s: String): LiveLocation? = runCatching {
            val o = JSONObject(s)
            LiveLocation(
                name = o.getString("n"), lat = o.getDouble("lat"), lon = o.getDouble("lon"),
                accuracy = o.getDouble("acc").toFloat(), speed = o.optDouble("spd", 0.0).toFloat(),
                battery = if (o.isNull("bat")) null else o.getInt("bat"),
                time = o.getLong("t"), until = o.getLong("u"), stopped = o.optBoolean("stop"),
                destination = if (o.isNull("dest")) null else o.getString("dest"),
                eta = if (o.isNull("eta")) null else o.getLong("eta"),
            )
        }.getOrNull()
    }
}

/** ntfy.sh as a dumb encrypted mailbox: publish a message, read back recent ones. */
object Relay {
    private const val BASE = "https://ntfy.sh"

    suspend fun publish(topic: String, body: String) {
        Http.post("$BASE/$topic", body, mapOf("Cache" to "yes"))
    }

    /** Messages since [sinceId] (or the last 12 hours), oldest first, as (id, body). */
    suspend fun poll(topic: String, sinceId: String?): List<Pair<String, String>> {
        val text = Http.text("$BASE/$topic/json?poll=1&since=${sinceId ?: "12h"}")
        return text.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                if (o.optString("event") != "message") null else o.getString("id") to o.getString("message")
            }.getOrNull()
        }.toList()
    }
}

/** The location you're currently sharing, if any. Shared by the UI and the foreground service. */
object LiveShare {
    data class Session(val link: ShareLink, val name: String, val until: Long)

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    /** Last relay error, shown in the share sheet. */
    val error = MutableStateFlow<String?>(null)

    val isActive get() = _session.value?.let { it.until > System.currentTimeMillis() } == true

    fun start(name: String, durationMs: Long): Session =
        Session(ShareLink.new(), name, System.currentTimeMillis() + durationMs).also {
            error.value = null
            _session.value = it
        }

    fun stop() {
        _session.value = null
    }
}

/** A person whose live location you follow. */
data class Friend(val link: ShareLink, val label: String, val last: LiveLocation? = null, val lastId: String? = null)

class FriendStore(context: Context) {
    private val prefs = context.getSharedPreferences("friends", Context.MODE_PRIVATE)

    fun list(): List<Friend> = runCatching {
        val arr = JSONArray(prefs.getString("list", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Friend(
                ShareLink(o.getString("topic"), ShareCrypto.decode(o.getString("key"))),
                o.getString("label"),
                if (o.isNull("last")) null else LiveLocation.fromJson(o.getString("last")),
                if (o.isNull("lastId")) null else o.getString("lastId"),
            )
        }
    }.getOrDefault(emptyList())

    fun save(friends: List<Friend>) {
        val arr = JSONArray(friends.map {
            JSONObject().put("topic", it.link.topic).put("key", ShareCrypto.encode(it.link.key)).put("label", it.label)
                .put("last", it.last?.toJson() ?: JSONObject.NULL).put("lastId", it.lastId ?: JSONObject.NULL)
        })
        prefs.edit().putString("list", arr.toString()).apply()
    }
}
