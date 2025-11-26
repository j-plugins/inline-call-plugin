// ShellRunLineMarkerProvider.kt

package com.github.xepozz.call.shell.run

import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import java.util.regex.Pattern

class ShellRunLineMarkerProvider : com.intellij.execution.lineMarker.RunLineMarkerContributor() {
    
    companion object {
        private val SHELL_PATTERN = Pattern.compile("shell:\\s*(.+)")
    }

    override fun getInfo(element: PsiElement): Info? {
        if (element !is PsiComment) return null
        
        val matcher = SHELL_PATTERN.matcher(element.text)
        if (!matcher.find()) return null
        
        val command = matcher.group(1).trim()
        
        // Получаем стандартные Run/Debug экшены
        val actions = ExecutorAction.getActions(1)
        
        return Info(
            AllIcons.Actions.Execute,
            actions,
            { "Run: $command" }
        )
    }
}