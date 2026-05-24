package com.github.xepozz.inline_call.base

import com.github.xepozz.inline_call.base.inlay.Session
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class SessionStorage(val project: Project) {
    private val sessions = ConcurrentHashMap<String, Session>()

    fun getSession(editorId: String): Session? = sessions[editorId]
    fun putSession(editorId: String, session: Session) { sessions[editorId] = session }
    fun remove(editorId: String): Session? = sessions.remove(editorId)

    companion object {
        fun getInstance(project: Project): SessionStorage = project.getService(SessionStorage::class.java)
    }
}