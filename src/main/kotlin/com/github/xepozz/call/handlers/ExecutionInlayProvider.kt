package com.github.xepozz.call.handlers

import com.github.xepozz.call.http.run.HttpRunConfiguration
import com.github.xepozz.call.shell.run.ShellRunConfiguration
import com.intellij.codeInsight.daemon.impl.InlayHintsPassFactoryInternal
import com.intellij.codeInsight.hints.*
import com.intellij.codeInsight.hints.presentation.*
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.ui.ConsoleView
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.ui.JBColor
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.util.concurrent.ConcurrentHashMap
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import com.github.xepozz.call.elements.ExecutionElementsProvider
import com.github.xepozz.call.elements.DefaultCommentElementsProvider

@Suppress("UnstableApiUsage")
abstract class ExecutionInlayProvider : InlayHintsProvider<NoSettings> {

    companion object {
        val activeComponents = ConcurrentHashMap<String, EmbeddedComponentData>()
    }

    override val key = SettingsKey<NoSettings>("execution.inlay.hints")
    override val name = "Execution Preview"
    override val previewText = "// shell: echo hello\n// https://api.example.com"

    override fun createSettings() = NoSettings()
    override fun createConfigurable(settings: NoSettings) = object : ImmediateConfigurable {
        override fun createComponent(listener: ChangeListener) = panel { }
    }

    abstract fun getHandler(): ExecutionHandler

    override fun getCollectorFor(
        file: PsiFile,
        editor: Editor,
        settings: NoSettings,
        sink: InlayHintsSink
    ) = object : FactoryInlayHintsCollector(editor) {
        // Choose elements provider per file once per collector instance
        private val elementsProvider: ExecutionElementsProvider =
            ExecutionElementsProvider.EP_NAME.extensionList.firstOrNull { it.isApplicable(file) }
                ?: DefaultCommentElementsProvider

        override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
            // Ask provider whether this element is suitable; fallback provider uses PsiComment
            if (!elementsProvider.isSuitable(element)) return true

            val text = element.text
            val project = element.project

            val handler = getHandler()
            handler.findMatches(text).forEach { match ->
                val offset = element.textOffset + match.offset
                val key = makeKey(editor, element)
                val data = activeComponents.getOrPut(key) { EmbeddedComponentData() }

                val presentation = buildPresentation(handler, match.value, element, editor, project, key, data)
                sink.addInlineElement(offset, false, presentation, false)
            }

            return true
        }

        private fun buildPresentation(
            handler: ExecutionHandler,
            value: String,
            comment: PsiElement,
            editor: Editor,
            project: Project,
            key: String,
            data: EmbeddedComponentData,
        ): InlayPresentation {
            val presentations = mutableListOf<InlayPresentation>()

            if (data.state != ExecutionState.IDLE) {
                val collapseIcon = if (data.collapsed) AllIcons.General.ArrowRight else AllIcons.General.ArrowDown
                val collapseTooltip = if (data.collapsed) "Expand" else "Collapse"
                presentations.add(
                    createIconButton(collapseIcon, collapseTooltip) {
                        toggleCollapse(key)
                    }
                )
            }
            if (data.state == ExecutionState.RUNNING) {
                presentations.add(
                    createIconButton(AllIcons.Actions.Suspend, "Stop") {
                        stop(key)
                    }
                )
            }

            if (data.state == ExecutionState.IDLE || data.state == ExecutionState.FINISHED) {
                presentations.add(
                    createIconButton(handler.icon, "${handler.tooltipPrefix}: $value") {
                        showRunPopup(handler, value, comment, editor, project, key)
                    }
                )
            }

            if (data.state != ExecutionState.IDLE) {
                presentations.add(
                    createIconButton(AllIcons.Actions.Close, "Close") {
                        close(key)
                    }
                )
            }

            return factory.seq(*presentations.toTypedArray())
        }

        private fun showRunPopup(
            handler: ExecutionHandler,
            value: String,
            element: PsiElement,
            editor: Editor,
            project: Project,
            key: String
        ) {
            val dataContext = SimpleDataContext.builder()
                .add(CommonDataKeys.PSI_ELEMENT, element)
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.EDITOR, editor)
                .add(CommonDataKeys.PSI_FILE, element.containingFile)
                .add(Location.DATA_KEY, PsiLocation.fromPsiElement(element))
                .build()

            val context = ConfigurationContext.getFromContext(dataContext, ActionPlaces.EDITOR_GUTTER)
            val configurations = context.configurationsFromContext

            // Если нет конфигураций от producers — запускаем напрямую через handler
            if (configurations.isNullOrEmpty()) {
                return
            }
            if (configurations.size == 1) {
                runDirect(handler, value, element, editor, project, key)
                return
            }

            // Если одна конфигурация — показываем выбор: Run Configuration или Direct
            // Если несколько — показываем popup со всеми вариантами
            val group = DefaultActionGroup()

            // Добавляем опцию прямого запуска
//            group.add(object : AnAction("Run Inline", "Run directly in editor", handler.icon) {
//                override fun actionPerformed(e: AnActionEvent) {
//                    runDirect(handler, value, element, editor, project, key)
//                }
//            })

            group.addSeparator("Run Configurations")

//            // Добавляем конфигурации от producers
//            configurations.forEach { configFromContext ->
//                val config = configFromContext.configuration
//                group.add(object : AnAction(config.name, "Run via ${config.type.displayName}", config.icon) {
//                    override fun actionPerformed(e: AnActionEvent) {
//                        runViaConfiguration(configFromContext.configurationSettings, element, editor, project, key)
//                    }
//                })
//            }

            val popup = JBPopupFactory.getInstance()
                .createActionGroupPopup(
                    "Run",
                    group,
                    dataContext,
                    JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                    true
                )

            popup.showInBestPositionFor(editor)
        }

        /**
         * Прямой запуск через handler.execute() — консоль inline
         */
        private fun runDirect(
            handler: ExecutionHandler,
            value: String,
            comment: PsiElement,
            editor: Editor,
            project: Project,
            key: String
        ) {
            // Закрываем предыдущий если есть
            close(key)

            val console = createConsole(project)
            val wrapper = createWrapper(editor, comment, console.component)

            val data = EmbeddedComponentData(
                wrapper = wrapper,
                console = console,
                state = ExecutionState.RUNNING
            )
            activeComponents[key] = data

            // Вызываем handler.execute() — дочерний класс делает работу
            handler.execute(value, console, project) { processHandler ->
                data.processHandler = processHandler

                processHandler?.addProcessListener(object : com.intellij.execution.process.ProcessListener {
                    override fun processTerminated(event: com.intellij.execution.process.ProcessEvent) {
                        data.state = ExecutionState.FINISHED
                        data.processHandler = null
                        refreshInlays(editor)
                    }
                })

                refreshInlays(editor)
            }
        }

        /**
         * Запуск через Run Configuration — консоль inline, перехватываем процесс
         */
        private fun runViaConfiguration(
            settings: com.intellij.execution.RunnerAndConfigurationSettings,
            element: PsiElement,
            editor: Editor,
            project: Project,
            key: String
        ) {
            // Закрываем предыдущий если есть
            close(key)

            val console = createConsole(project)
            val wrapper = createWrapper(editor, element, console.component)

            val data = EmbeddedComponentData(
                wrapper = wrapper,
                console = console,
                state = ExecutionState.RUNNING
            )
            activeComponents[key] = data

            // Подписываемся на запуск процесса
            val connection = project.messageBus.connect(data.disposable)
            connection.subscribe(
                ExecutionManager.EXECUTION_TOPIC,
                object : ExecutionListener {
                    override fun processStarted(
                        executorId: String,
                        env: com.intellij.execution.runners.ExecutionEnvironment,
                        handler: ProcessHandler
                    ) {
                        // Проверяем что это наша конфигурация
                        if (env.runnerAndConfigurationSettings?.configuration?.name != settings.configuration.name) return

                        invokeLater {
                            // Подключаем консоль к процессу
                            console.attachToProcess(handler)
                            data.processHandler = handler

                            handler.addProcessListener(object : com.intellij.execution.process.ProcessAdapter() {
                                override fun processTerminated(event: com.intellij.execution.process.ProcessEvent) {
                                    data.state = ExecutionState.FINISHED
                                    data.processHandler = null
                                    refreshInlays(editor)
                                }
                            })

                            refreshInlays(editor)
                        }
                    }
                }
            )

            // Запускаем без открытия Run tool window
            val executor = DefaultRunExecutor.getRunExecutorInstance()
            val environment = ExecutionEnvironmentBuilder
                .create(executor, settings)
                .activeTarget()
                .build()

            environment.runner.execute(environment) { descriptor ->
                descriptor?.isActivateToolWindowWhenAdded = false
            }
        }

        private fun createIconButton(
            icon: Icon,
            tooltip: String,
            onClick: () -> Unit
        ): InlayPresentation {
            var p: InlayPresentation = factory.icon(icon)
            p = factory.inset(p, 2, 2, 0, 0)
            p = factory.withTooltip(tooltip, p)
            p = factory.referenceOnHover(p) { _, _ ->
                onClick()
                refreshInlays(editor)
            }
            return p
        }
    }

    private fun createWrapper(
        editor: Editor,
        comment: PsiElement,
        component: JComponent
    ): JComponent {
        val line = editor.document.getLineNumber(comment.textOffset)
        val offset = editor.document.getLineEndOffset(line)

        val wrapper = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1),
                JBUI.Borders.empty(4)
            )
            background = JBColor.background()
            add(component, BorderLayout.CENTER)
            preferredSize = Dimension(700, 250)
        }

        val manager = EditorEmbeddedComponentManager.getInstance()
        val properties = EditorEmbeddedComponentManager.Properties(
            EditorEmbeddedComponentManager.ResizePolicy.any(),
            null,
            true,
            false,
            0,
            offset
        )

        invokeLater {
            manager.addComponent(editor as EditorEx, wrapper, properties)
        }

        return wrapper
    }

    private fun createConsole(project: Project): ConsoleView {
        val console = TextConsoleBuilderFactory.getInstance()
            .createBuilder(project)
            .apply { setViewer(true) }
            .console

        return console
    }

    private fun stop(key: String) {
        activeComponents[key]?.let { data ->
            data.processHandler?.destroyProcess()
            data.state = ExecutionState.FINISHED
            data.processHandler = null
        }
    }

    private fun toggleCollapse(key: String) {
        activeComponents[key]?.let { data ->
            data.collapsed = !data.collapsed
            data.wrapper?.isVisible = !data.collapsed
        }
    }

    private fun close(key: String) {
        activeComponents[key]?.let { data ->
            data.processHandler?.destroyProcess()
            invokeLater {
                data.wrapper?.parent?.remove(data.wrapper)
                Disposer.dispose(data.disposable)
            }
            activeComponents.remove(key)
        }
    }

    private fun refreshInlays(editor: Editor) {
        invokeLater {
            InlayHintsPassFactoryInternal.forceHintsUpdateOnNextPass()
            com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(editor.project ?: return@invokeLater)
                .restart()
        }
    }

    private fun makeKey(editor: Editor, comment: PsiElement): String {
        val line = editor.document.getLineNumber(comment.textOffset)
        return "${editor.hashCode()}_$line"
    }
}