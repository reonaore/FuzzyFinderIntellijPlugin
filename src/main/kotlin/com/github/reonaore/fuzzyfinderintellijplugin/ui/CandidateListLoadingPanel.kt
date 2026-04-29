package com.github.reonaore.fuzzyfinderintellijplugin.ui

import com.github.reonaore.fuzzyfinderintellijplugin.MyBundle
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.OverlayLayout

class CandidateListLoadingPanel internal constructor(
    private val list: JBList<*>,
    listComponent: JComponent,
    private val busyIndicator: CandidateBusyIndicator,
    private val initialEmptyText: String = MyBundle.message("dialog.candidates.empty"),
) {
    constructor(
        list: JBList<*>,
        listComponent: JComponent,
        initialEmptyText: String = MyBundle.message("dialog.candidates.empty"),
    ) : this(list, listComponent, AsyncCandidateBusyIndicator(), initialEmptyText)

    private val messageLabel = JBLabel()
    private val messagePanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        border = JBUI.Borders.empty(8, 12)
        background = UIUtil.getPanelBackground()
        add(busyIndicator.component)
        add(Box.createHorizontalStrut(JBUI.scale(8)))
        add(messageLabel)
    }
    private val overlay = JPanel(GridBagLayout()).apply {
        isOpaque = false
        isVisible = false
        add(messagePanel, GridBagConstraints())
    }
    val component: JComponent = JPanel().apply {
        layout = OverlayLayout(this)
        add(overlay)
        add(listComponent)
    }

    private var state: CandidateListLoadingState = CandidateListLoadingState.Idle

    init {
        busyIndicator.stop()
        list.emptyText.text = initialEmptyText
    }

    fun showSearching(hasExistingCandidates: Boolean) {
        val textKey = if (hasExistingCandidates) {
            "dialog.candidates.updating"
        } else {
            "dialog.candidates.searching"
        }
        showOverlay(
            CandidateListLoadingState.Searching(hasExistingCandidates),
            MyBundle.message(textKey),
            showSpinner = true,
        )
    }

    fun showResults(hasCandidates: Boolean) {
        state = CandidateListLoadingState.Idle
        list.emptyText.text = MyBundle.message("dialog.candidates.empty")
        overlay.isVisible = false
        busyIndicator.stop()
        if (!hasCandidates) {
            list.repaint()
        }
    }

    fun showInitialEmptyText() {
        state = CandidateListLoadingState.Idle
        list.emptyText.text = initialEmptyText
        overlay.isVisible = false
        busyIndicator.stop()
        list.repaint()
    }

    fun showError() {
        showOverlay(
            CandidateListLoadingState.Error,
            MyBundle.message("dialog.candidates.error"),
            showSpinner = false,
        )
        list.emptyText.text = MyBundle.message("dialog.candidates.error")
    }

    fun dispose() {
        busyIndicator.dispose()
    }

    internal fun currentState(): CandidateListLoadingState = state

    internal fun overlayIsVisible(): Boolean = overlay.isVisible

    internal fun overlayText(): String = messageLabel.text

    internal fun spinnerIsVisible(): Boolean = busyIndicator.component.isVisible

    private fun showOverlay(
        nextState: CandidateListLoadingState,
        text: String,
        showSpinner: Boolean,
    ) {
        state = nextState
        messageLabel.text = text
        if (showSpinner) {
            busyIndicator.start()
        } else {
            busyIndicator.stop()
        }
        overlay.isVisible = true
        overlay.repaint()
    }
}

sealed interface CandidateListLoadingState {
    data object Idle : CandidateListLoadingState

    data class Searching(val hasExistingCandidates: Boolean) : CandidateListLoadingState

    data object Error : CandidateListLoadingState
}

internal interface CandidateBusyIndicator {
    val component: JComponent

    fun start()

    fun stop()

    fun dispose()
}

private class AsyncCandidateBusyIndicator : CandidateBusyIndicator {
    private val icon = AsyncProcessIcon("Candidate list loading").apply {
        suspend()
    }

    override val component: JComponent = icon

    override fun start() {
        icon.isVisible = true
        icon.resume()
    }

    override fun stop() {
        icon.suspend()
        icon.isVisible = false
    }

    override fun dispose() {
        icon.dispose()
    }
}
