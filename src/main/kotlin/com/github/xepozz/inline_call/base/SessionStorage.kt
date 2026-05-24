package com.github.xepozz.inline_call.base

import com.github.xepozz.inline_call.base.inlay.Session
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class SessionStorage(val project: Project, val cs: CoroutineScope) {
    private val sessions = ConcurrentHashMap<String, Session>()

    fun getSession(editorId: String): Session? = sessions[editorId]
    fun putSession(editorId: String, session: Session) { sessions[editorId] = session }
    fun remove(editorId: String): Session? = sessions.remove(editorId)?.also { it.job?.cancel() }

    /**
     * Atomically store [job] on the session for [key] and return the previously
     * stored job (if any) so the caller can cancel it before launching anew.
     * Returns `null` if no session exists for [key] yet.
     */
    fun replaceJob(key: String, job: Job): Job? {
        val s = sessions[key] ?: return null
        val prev = s.job
        s.job = job
        return prev
    }

    companion object {
        fun getInstance(project: Project): SessionStorage = project.getService(SessionStorage::class.java)
    }
}
