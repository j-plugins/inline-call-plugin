// HttpRunLineMarkerProvider.kt

package com.github.xepozz.call.http.run

import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import java.util.regex.Pattern

class HttpRunLineMarkerProvider : RunLineMarkerContributor() {
    
    companion object {
        private val HTTP_PATTERN = Pattern.compile("(https?://[\\w\\-._~:/?#\\[\\]@!\$&'()*+,;=%]+)")
    }

    override fun getInfo(element: PsiElement): Info? {
        if (element !is PsiComment) return null
        
        val matcher = HTTP_PATTERN.matcher(element.text)
        if (!matcher.find()) return null
        
        val url = matcher.group(1)
        val actions = ExecutorAction.getActions(1)
        
        return Info(
            AllIcons.General.Web,
            actions,
            { "Fetch: $url" }
        )
    }
}