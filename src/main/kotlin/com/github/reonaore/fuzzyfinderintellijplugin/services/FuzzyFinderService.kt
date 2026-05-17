package com.github.reonaore.fuzzyfinderintellijplugin.services

import com.github.reonaore.fuzzyfinderintellijplugin.settings.FuzzyFinderSettingsService
import com.github.reonaore.fuzzyfinderintellijplugin.settings.SupportedCommand
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class FuzzyFinderService(
    private val project: Project,
) : Disposable {

    private val settingsService: FuzzyFinderSettingsService
        get() = ApplicationManager.getApplication().getService(FuzzyFinderSettingsService::class.java)

    private val runner = IntellijCommandRunner()

    init {
        project.messageBus.connect(this)
    }

    override fun dispose() = Unit

    suspend fun search(query: String, options: FdSearchOptions, limit: Int = MAX_RESULTS): SearchResult {
        val basePath = project.basePath ?: return SearchResult(
            totalCandidates = 0,
            query = query,
            results = emptyList(),
        )

        return searchEngine.search(
            query = query,
            options = options,
            root = Path.of(basePath),
            limit = limit,
        )
    }

    fun grepStream(query: String, options: GrepSearchOptions): Flow<GrepSearchUpdate> {
        val basePath = project.basePath ?: return flowOf(
            GrepSearchUpdate(
                totalMatches = 0,
                query = query,
                matches = emptyList(),
                isComplete = true,
            ),
        )

        return searchEngine.grepStream(
            query = query,
            options = options,
            root = Path.of(basePath),
        )
    }

    suspend fun filterGrepMatches(query: String, matches: List<GrepMatch>, limit: Int = MAX_RESULTS): List<GrepMatch> {
        val basePath = project.basePath ?: return emptyList()

        return searchEngine.filterGrepMatches(
            query = query,
            matches = matches,
            root = Path.of(basePath),
            limit = limit,
        )
    }

    fun notifyError(message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Fuzzy Finder Notifications")
            .createNotification(message, NotificationType.ERROR)
            .notify(project)
    }

    private companion object {
        const val MAX_RESULTS = 200
    }

    private val searchEngine: FuzzyFinderSearchEngine
        get() = FuzzyFinderSearchEngine(
            fdExecutable = settingsService.executablePath(SupportedCommand.FD),
            fzfExecutable = settingsService.executablePath(SupportedCommand.FZF),
            rgExecutable = settingsService.executablePath(SupportedCommand.RG),
            runner = runner,
        )
}
