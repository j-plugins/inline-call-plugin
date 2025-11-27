package com.github.xepozz.call.base.inlay.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.OverlayLayout

fun createResultContainer(): JPanel {
   val toolbar = createToolbar()

   val container = JPanel(BorderLayout()).apply {
       layout = OverlayLayout(this)
       border = BorderFactory.createCompoundBorder(
           BorderFactory.createLineBorder(JBColor.border(), 1),
           JBUI.Borders.empty(4)
       )
       background = JBColor.background()
       preferredSize = Dimension(700, 250)
   }

   val toolbarWrapper = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 5)).apply {
       isOpaque = false
       alignmentX = 1.0f
       alignmentY = 0.0f
       add(toolbar.component)
       toolbar.targetComponent = this
   }
   val contentPanel = JPanel(BorderLayout()).apply {
       isOpaque = false
       alignmentX = 1f
       alignmentY = 1f
   }
   container.add(toolbarWrapper)
   container.add(contentPanel)

   return container
}