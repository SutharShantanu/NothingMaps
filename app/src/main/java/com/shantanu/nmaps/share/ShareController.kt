package com.shantanu.nmaps.share

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.shantanu.nmaps.data.Fix
import com.shantanu.nmaps.record.Tracking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Live location: what you share, and the people whose location you follow. */
class ShareController(private val context: Context, private val scope: CoroutineScope) {
    private val store = FriendStore(context)
    private var pollJob: Job? = null

    var friends by mutableStateOf(store.list())
        private set

    fun start(name: String, durationMs: Long): LiveShare.Session {
        val session = LiveShare.start(name, durationMs)
        Tracking.sync(context)
        return session
    }

    /** Tells followers you've stopped, so their map doesn't keep showing a stale position. */
    fun stop(last: Fix?) {
        val session = LiveShare.session.value ?: return
        LiveShare.stop()
        Tracking.sync(context)
        if (last != null) scope.launch {
            val bye = LiveLocation(session.name, last.lat, last.lon, last.accuracy, 0f, null, System.currentTimeMillis(), session.until, stopped = true)
            runCatching { Relay.publish(session.link.topic, ShareCrypto.encrypt(session.link.key, bye.toJson())) }
        }
    }

    /** Adds the person behind a pasted link or message. Returns false if no link was found. */
    fun add(text: String): Boolean {
        val link = ShareLink.find(text) ?: return false
        if (friends.none { it.link == link }) {
            friends = friends + Friend(link, "Shared location")
            store.save(friends)
        }
        scope.launch { pollOnce() }
        startPolling()
        return true
    }

    fun remove(friend: Friend) {
        friends = friends.filterNot { it.link == friend.link }
        store.save(friends)
    }

    /** Polls the relay every 20 s while the app is open and someone is being followed. */
    fun startPolling() {
        if (pollJob?.isActive == true || friends.isEmpty()) return
        pollJob = scope.launch {
            while (isActive && friends.isNotEmpty()) {
                pollOnce()
                delay(20_000)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun pollOnce() {
        var changed = false
        val updated = friends.map { f ->
            val messages = runCatching { Relay.poll(f.link.topic, f.lastId) }.getOrNull() ?: return@map f
            // Newest message that decrypts with this friend's key; anything else on the topic is ignored.
            val latest = messages.asReversed().firstNotNullOfOrNull { (id, body) ->
                ShareCrypto.decrypt(f.link.key, body)?.let { LiveLocation.fromJson(it) }?.let { id to it }
            } ?: return@map f.copy(lastId = messages.lastOrNull()?.first ?: f.lastId)
            changed = true
            f.copy(label = latest.second.name, last = latest.second, lastId = latest.first)
        }
        if (changed || updated != friends) {
            // Keep any edits made while polling (removals) by matching on link.
            friends = friends.map { cur -> updated.firstOrNull { it.link == cur.link } ?: cur }
            store.save(friends)
        }
    }
}
