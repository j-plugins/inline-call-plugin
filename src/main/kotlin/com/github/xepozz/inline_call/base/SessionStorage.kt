package com.github.xepozz.inline_call.base

import com.github.xepozz.inline_call.base.api.FeatureMatch
import com.github.xepozz.inline_call.base.handlers.ExecutionState
import com.github.xepozz.inline_call.base.inlay.Session
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/**
 * Project-scoped in-memory storage for [Session] objects keyed by a stable
 * composite key:
 *
 *     ${virtualFile.url}::${featureId}::${valueHash}#${occurrenceIndex}
 *
 * The key is independent of the live [com.intellij.openapi.editor.Editor]
 * identity and of physical line offsets, so it survives:
 *   - editor reopen,
 *   - split-editor / multiple windows of the same project,
 *   - inserting lines above the match,
 *   - any edit that doesn't change `FeatureMatch.value`.
 *
 * Lifecycle cleanup (terminating processes, disposing panels, evicting
 * stale entries) is driven by the lifecycle listeners in
 * `com.github.xepozz.inline_call.base.lifecycle`, all of which use this
 * service as their `Disposable` parent so registration is dropped on
 * project close.
 *
 * [cs] is the project-scoped service coroutine scope provided by the
 * platform; long-running per-session jobs are launched from it and
 * cancelled via [remove] / [Session.job] or [dispose].
 */
@Service(Service.Level.PROJECT)
class SessionStorage(val project: Project, val cs: CoroutineScope) : Disposable {
    private val sessions = ConcurrentHashMap<String, Session>()

    fun getSession(key: String): Session? = sessions[key]

    fun putSession(key: String, session: Session) {
        sessions[key] = session
    }

    fun remove(key: String): Session? = sessions.remove(key)?.also { it.job?.cancel() }

    /**
     * Atomically store [job] on the session for [key] and return the
     * previously stored job (if any) so the caller can cancel it before
     * launching anew. Returns `null` if no session exists for [key] yet.
     */
    fun replaceJob(key: String, job: Job): Job? {
        val s = sessions[key] ?: return null
        val prev = s.job
        s.job = job
        return prev
    }

    /** Snapshot of every (key, session) for inspection by listeners. */
    fun snapshot(): List<Pair<String, Session>> = sessions.entries.map { it.key to it.value }

    /** All sessions belonging to the given virtual-file url. */
    fun sessionsForFile(url: String): List<Pair<String, Session>> =
        sessions.entries.asSequence()
            .filter { it.value.virtualFileUrl == url }
            .map { it.key to it.value }
            .toList()

    /**
     * Iterate every (key, session) under the storage's own lock.
     * Backed by `ConcurrentHashMap.forEach` so traversal is weakly
     * consistent — fine for reapers that only care about a stable enough
     * view.
     */
    fun forEach(action: (String, Session) -> Unit) {
        sessions.forEach { (k, v) -> action(k, v) }
    }

    /**
     * Remove every session for [url] whose key is not in [validKeys].
     * Returns the evicted sessions so callers can terminate processes /
     * dispose panels.
     */
    fun evictStale(url: String, validKeys: Set<String>): List<Session> {
        val toRemove = mutableListOf<Session>()
        val it = sessions.entries.iterator()
        while (it.hasNext()) {
            val (k, v) = it.next()
            if (v.virtualFileUrl == url && k !in validKeys) {
                toRemove += v
                v.job?.cancel()
                it.remove()
            }
        }
        return toRemove
    }

    override fun dispose() {
        // Last-resort cleanup: cancel coroutines and terminate every
        // running process so the OS doesn't inherit them when the
        // project window closes.
        sessions.values.forEach { session ->
            try {
                session.job?.cancel()
            } catch (_: Throwable) {
            }
            try {
                session.processHandler?.destroyProcess()
            } catch (_: Throwable) {
            }
            session.processHandler = null
            session.state = ExecutionState.FINISHED
        }
        sessions.clear()
    }

    companion object {
        fun getInstance(project: Project): SessionStorage =
            project.getService(SessionStorage::class.java)

        /**
         * Stable composite session key. See class KDoc for the shape.
         */
        fun makeKey(file: VirtualFile, featureId: String, valueHash: String, occurrenceIndex: Int): String =
            "${file.url}::${featureId}::${valueHash}#${occurrenceIndex}"

        /**
         * Short hex hash of a [FeatureMatch.value] (or any string).
         * Collision rate on realistic URL/shell payloads within a single
         * file is negligible; the `(featureId, occurrenceIndex)` pair in
         * the key absorbs any practical aliasing.
         */
        fun hashValue(value: String): String =
            Integer.toHexString(value.hashCode())
    }
}

/** Convenience builder so callers don't need to compute the hash themselves. */
fun makeSessionKey(file: VirtualFile, match: FeatureMatch, occurrenceIndex: Int): String =
    SessionStorage.makeKey(file, match.featureId, SessionStorage.hashValue(match.value), occurrenceIndex)
