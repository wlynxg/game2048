package org.andstatus.game2048.view

import com.soywiz.klock.TimeSpan
import com.soywiz.korev.PauseEvent
import com.soywiz.korev.ResumeEvent
import com.soywiz.korev.addEventListener
import com.soywiz.korge.animate.Animator
import com.soywiz.korge.view.Container
import com.soywiz.korge.view.Stage
import com.soywiz.korge.view.View
import com.soywiz.korge.view.addTo
import com.soywiz.korge.view.position
import com.soywiz.korge.view.solidRect
import com.soywiz.korim.font.Font
import com.soywiz.korio.lang.Closeable
import com.soywiz.korio.util.OS
import com.soywiz.korma.interpolation.Easing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.andstatus.game2048.Settings
import org.andstatus.game2048.defaultLanguage
import org.andstatus.game2048.loadFont
import org.andstatus.game2048.model.History
import org.andstatus.game2048.model.Square
import org.andstatus.game2048.myLog
import org.andstatus.game2048.myMeasured
import org.andstatus.game2048.presenter.Presenter
import org.andstatus.game2048.view.AppBar.Companion.setupAppBar
import kotlin.properties.Delegates

/** @author yvolk@yurivolkov.com */
suspend fun gameView(stage: Stage, animateViews: Boolean, handler: suspend GameView.() -> Unit = {}) {
    stage.removeChildren()
    coroutineScope {
        val scope: CoroutineScope = if (OS.isWindows) this else CoroutineScope(coroutineContext + Dispatchers.Default)
        scope.initialize(stage, animateViews, handler)
    }
}

private fun CoroutineScope.initialize(stage: Stage, animateViews: Boolean, handler: suspend GameView.() -> Unit = {}) = launch {
    val quick = GameViewQuick(stage, animateViews)
    val splashDefault = stage.splashScreen(ColorThemeEnum.deviceDefault(stage))
    val strings = async { StringResources.load(defaultLanguage) }
    val font = async { loadFont(strings.await()) }
    val settings = async { Settings.load(stage) }
    val history = async { History.load(settings.await()) }
    val gameColors = async { ColorTheme.load(stage, settings.await()) }

    val splashThemed = if (settings.await().colorThemeEnum == ColorThemeEnum.deviceDefault(stage))
        splashDefault else stage.splashScreen(settings.await().colorThemeEnum)
    stage.solidRect(stage.views.virtualWidth, stage.views.virtualHeight,
            color = gameColors.await().stageBackground)

    splashThemed.addTo(stage)
    if (splashThemed != splashDefault) {
        splashDefault.removeFromParent()
    }

    if (!OS.isAndroid) {
        // We set window title in Android via AndroidManifest.xml
        stage.gameWindow.title = strings.await().text("app_name")
    }

    val view = GameView(quick, settings.await(), font.await(), strings.await(), gameColors.await())
    view.presenter = myMeasured("Presenter${view.id} created") { Presenter(view, history.await()) }
    val appBar = async { view.setupAppBar() }
    val scoreBar = async { view.setupScoreBar() }
    val boardView = async { BoardView(view) }

    view.appBar = appBar.await()
    view.scoreBar = scoreBar.await()
    view.boardView = boardView.await()

    splashThemed.removeFromParent()
    view.presenter.onAppEntry()
    view.gameStage.gameWindow.addEventListener<PauseEvent> { view.presenter.onPauseEvent() }
            .also { view.closeables.add(it) }
    view.gameStage.gameWindow.addEventListener<ResumeEvent> { view.presenter.onResumeEvent() }
            .also { view.closeables.add(it) }
    myLog("GameView${view.id} initialized")
    view.handler()
}

class GameView(gameViewQuick: GameViewQuick,
               val settings: Settings,
               val font: Font,
               val stringResources: StringResources,
               val gameColors: ColorTheme): GameViewBase by gameViewQuick, Closeable {

    val cellSize: Double = (gameViewWidth - cellMargin * (settings.boardWidth + 1) - 2 * buttonPadding) / settings.boardWidth
    val boardWidth: Double = cellSize * settings.boardWidth + cellMargin * (settings.boardWidth + 1)

    var presenter: Presenter by Delegates.notNull()
    var appBar: AppBar by Delegates.notNull()
    var scoreBar: ScoreBar by Delegates.notNull()
    var boardView: BoardView by Delegates.notNull()

    val closeables = mutableListOf<Closeable>()

    suspend fun reInitialize(handler: suspend GameView.() -> Unit = {}) {
        this.close()
        gameView(gameStage, animateViews, handler)
    }

    /** Workaround for the bug: https://github.com/korlibs/korge-next/issues/56 */
    fun Container.customOnClick(handler: () -> Unit) = duplicateKeyPressFilter.apply {
        customOnClick(handler)
    }

    fun Container.position(square: Square) {
        position(square.positionX(), square.positionY())
    }

    fun Animator.moveTo(view: View, square: Square, time: TimeSpan, easing: Easing) {
        view.moveTo(square.positionX(), square.positionY(), time, easing)
    }

    private fun Square.positionX() = cellMargin + (cellSize + cellMargin) * x
    private fun Square.positionY() = cellMargin + (cellSize + cellMargin) * y

    fun showControls(appBarButtonsToShow: List<AppBarButtonsEnum>, playSpeed: Int) {
        appBar.show(appBarButtonsToShow)
        scoreBar.show(playSpeed)
        boardView.setOnTop()
    }

    override fun close() {
        closeables.forEach { it.close() }
    }
}
