package com.github.reonaore.fuzzyfinderintellijplugin.actions

import com.github.reonaore.fuzzyfinderintellijplugin.ui.FuzzyFinderDialog
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

class OpenFuzzyFinderAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.text = ACTION_TEXT
        e.presentation.description = ACTION_DESCRIPTION
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        FuzzyFinderDialog(project).show()
    }

    private companion object {
        const val ACTION_TEXT = "Open Fuzzy Finder"
        const val ACTION_DESCRIPTION = "Search project files with fd and fzf"
    }
}
