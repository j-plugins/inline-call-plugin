package com.github.xepozz.inline_call.base.inlay.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.OverlayLayout

fun createResultContainer(): JPanel {
    val container = JPanel(BorderLayout()).apply {
        layout = OverlayLayout(this)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor.border(), 1),
            JBUI.Borders.empty(4)
        )
        background = JBColor.background()
        preferredSize = Dimension(700, 250)
    }

    return container
}