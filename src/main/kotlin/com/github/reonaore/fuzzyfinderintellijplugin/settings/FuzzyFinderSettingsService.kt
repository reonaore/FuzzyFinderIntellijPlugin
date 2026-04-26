package com.github.reonaore.fuzzyfinderintellijplugin.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(name = "FuzzyFinderSettings", storages = [Storage("fuzzyFinder.xml")])
class FuzzyFinderSettingsService : PersistentStateComponent<FuzzyFinderSettingsState> {

    private var state = FuzzyFinderSettingsState()

    override fun getState(): FuzzyFinderSettingsState = state

    override fun loadState(state: FuzzyFinderSettingsState) {
        this.state = state
    }

    fun executablePath(command: SupportedCommand): String {
        val configured = when (command) {
            SupportedCommand.FD -> state.fdExecutablePath
            SupportedCommand.FZF -> state.fzfExecutablePath
            SupportedCommand.RG -> state.rgExecutablePath
        }.trim()

        return configured.ifEmpty { command.defaultExecutable }
    }

    companion object {
        fun getInstance(): FuzzyFinderSettingsService = service()
    }
}

data class FuzzyFinderSettingsState(
    var fdExecutablePath: String = "",
    var fzfExecutablePath: String = "",
    var rgExecutablePath: String = "",
)

enum class SupportedCommand(val defaultExecutable: String) {
    FD("fd"),
    FZF("fzf"),
    RG("rg"),
}
