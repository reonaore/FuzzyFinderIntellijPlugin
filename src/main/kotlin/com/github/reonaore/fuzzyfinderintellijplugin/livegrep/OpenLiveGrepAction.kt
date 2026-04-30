package com.github.reonaore.fuzzyfinderintellijplugin.livegrep

import com.github.reonaore.fuzzyfinderintellijplugin.MyBundle
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction

class OpenLiveGrepAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.text = MyBundle.message("action.openLiveGrep.text")
        e.presentation.description = MyBundle.message("action.openLiveGrep.description")
        e.presentation.isEnabled = CommonDataKeys.PROJECT.getData(e.dataContext) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val initialQuery = initialLiveGrepQueryFromSelection(
            CommonDataKeys.EDITOR.getData(e.dataContext)?.selectionModel?.selectedText,
        )

        LiveGrepDialog(project, initialQuery).show()
    }
}

internal fun initialLiveGrepQueryFromSelection(selectedText: String?): String {
    return selectedText
        ?.lineSequence()
        ?.map(String::trim)
        ?.firstOrNull(String::isNotEmpty)
        .orEmpty()
}
