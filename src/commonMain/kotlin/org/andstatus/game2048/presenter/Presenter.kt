package org.andstatus.game2048.presenter

import com.soywiz.klock.Stopwatch
import com.soywiz.klock.milliseconds
import com.soywiz.korge.animate.Animator
import com.soywiz.korge.animate.animateSequence
import com.soywiz.korge.input.SwipeDirection
import com.soywiz.korge.tween.get
import com.soywiz.korge.view.Text
import com.soywiz.korio.async.launch
import com.soywiz.korio.async.launchImmediately
import com.soywiz.korio.concurrent.atomic.KorAtomicBoolean
import com.soywiz.korio.concurrent.atomic.KorAtomicInt
import com.soywiz.korio.concurrent.atomic.incrementAndGet
import com.soywiz.korio.lang.substr
import com.soywiz.korio.serialization.json.toJson
import com.soywiz.korma.interpolation.Easing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import org.andstatus.game2048.ai.AiPlayer
import org.andstatus.game2048.closeGameApp
import org.andstatus.game2048.gameStopWatch
import org.andstatus.game2048.loadJsonGameRecord
import org.andstatus.game2048.model.Board
import org.andstatus.game2048.model.GameModeEnum
import org.andstatus.game2048.model.GameRecord
import org.andstatus.game2048.model.History
import org.andstatus.game2048.model.Model
import org.andstatus.game2048.model.MoveDelay
import org.andstatus.game2048.model.MoveLoad
import org.andstatus.game2048.model.MoveMerge
import org.andstatus.game2048.model.MoveOne
import org.andstatus.game2048.model.MovePlace
import org.andstatus.game2048.model.PlacedPiece
import org.andstatus.game2048.model.PlayerMove
import org.andstatus.game2048.model.PlayerMoveEnum
import org.andstatus.game2048.model.Square
import org.andstatus.game2048.myLog
import org.andstatus.game2048.myMeasured
import org.andstatus.game2048.shareText
import org.andstatus.game2048.view.AppBarButtonsEnum
import org.andstatus.game2048.view.ColorThemeEnum
import org.andstatus.game2048.view.ViewData
import org.andstatus.game2048.view.showBookmarks
import org.andstatus.game2048.view.showGameMenu
import org.andstatus.game2048.view.showHelp
import org.andstatus.game2048.view.showRestoreGame

/** @author yvolk@yurivolkov.com */
class Presenter(private val view: ViewData, history: History) {
    private val coroutineScope: CoroutineScope get() = view.gameStage
    val model = Model(history)
    val aiPlayer = AiPlayer(history.settings)
    private val moveIsInProgress = KorAtomicBoolean(false)
    val score get() = model.score
    val bestScore get() = model.bestScore
    var boardViews = BoardViews(view)
    private val gameMode get() = model.gameMode
    private var clickCounter = KorAtomicInt(0)

    private fun presentGameClock(coroutineScope: CoroutineScope, model: Model, textSupplier: () -> Text) {
        coroutineScope.launch {
            while (true) {
                textSupplier().text = model.gameClock.playedSecondsString
                delay(1000)
            }
        }
    }

    fun onAppEntry() = myMeasured("onAppEntry") {
        model.onAppEntry().present()
        presentGameClock(view.gameStage, model) { view.mainView.scoreBar.gameTime }
        if (model.history.prevGames.isEmpty() && model.history.currentGame.score == 0) {
            view.mainView.removeFromParent()
            view.showHelp()
        }
    }

    fun onNoMagicClicked() {
        logClick("NoMagic")
        gameMode.aiEnabled = true
        showMainView()
    }

    fun onMagicClicked() {
        logClick("Magic")
        gameMode.aiEnabled = false
        if (gameMode.modeEnum != GameModeEnum.PLAY) {
            gameMode.modeEnum = GameModeEnum.PLAY
            pauseGame()
        }
        showMainView()
    }

    fun onAiStartClicked() {
        logClick("AiStart")
        gameMode.aiEnabled = true
        startAiPlay()
    }

    fun onAiStopClicked() {
        logClick("AiStop")
        gameMode.modeEnum = GameModeEnum.PLAY
        pauseGame()
        showMainView()
    }

    fun canUndo(): Boolean = model.canUndo()

    fun canRedo(): Boolean = model.canRedo()

    fun onUndoClick() = afterStop {
        logClick("Undo")
        undo()
    }

    fun undo() {
        if (!moveIsInProgress.compareAndSet(expect = false, update = true)) return

        pauseGame()
        boardViews.removeGameOver() // TODO: make this a move...
        (model.undo() + listOf(PlayerMove.delay()) + model.undo()).presentReversed()
    }

    fun onRedoClick() = afterStop {
        logClick("Redo")
        redo()
    }

    fun redo() {
        if (!moveIsInProgress.compareAndSet(expect = false, update = true)) return

        (model.redo() + listOf(PlayerMove.delay()) + model.redo()).present()
    }

    fun onRestartClick() = afterStop {
        logClick("Restart")
        model.saveCurrent()
        model.restart().present()
    }

    fun onSwipe(swipeDirection: SwipeDirection) {
        when (gameMode.modeEnum) {
            GameModeEnum.BACKWARDS, GameModeEnum.FORWARD, GameModeEnum.STOP -> {
                when (swipeDirection) {
                    SwipeDirection.LEFT -> startAutoReplay(GameModeEnum.BACKWARDS)
                    SwipeDirection.RIGHT -> startAutoReplay(GameModeEnum.FORWARD)
                    SwipeDirection.BOTTOM -> onStopClick()
                    else -> {
                    }
                }
            }
            GameModeEnum.PLAY -> {
                userMove(
                        when (swipeDirection) {
                            SwipeDirection.LEFT -> PlayerMoveEnum.LEFT
                            SwipeDirection.RIGHT -> PlayerMoveEnum.RIGHT
                            SwipeDirection.TOP -> PlayerMoveEnum.UP
                            SwipeDirection.BOTTOM -> PlayerMoveEnum.DOWN
                        }
                )
            }
            GameModeEnum.AI_PLAY -> {
                when (swipeDirection) {
                    SwipeDirection.LEFT -> gameMode.decrementSpeed()
                    SwipeDirection.RIGHT -> gameMode.incrementSpeed()
                    else -> {
                        onAiStopClicked()
                    }
                }
            }
        }

    }

    fun onBookmarkClick() = afterStop {
        logClick("Bookmark")
        model.createBookmark()
        showMainView()
    }

    fun onBookmarkedClick() = afterStop {
        logClick("Bookmarked")
        model.deleteBookmark()
        showMainView()
    }

    fun onPauseClick() = afterStop {
        logClick("Pause")
        pauseGame()
        showMainView()
    }

    fun onPauseEvent() {
        myLog("onPauseEvent${view.id}")
        pauseGame()
        showMainView()
    }

    fun onCloseGameWindowClick() {
        logClick("onCloseGameWindow")
        pauseGame()
        view.gameStage.gameWindow.close()
        view.gameStage.closeGameApp()
    }

    fun onWatchClick() = afterStop {
        logClick("Watch")
        gameMode.modeEnum = GameModeEnum.PLAY
        showMainView()
    }

    fun onPlayClick() = afterStop {
        logClick("Play")
        gameMode.modeEnum = GameModeEnum.STOP
        gameMode.aiEnabled = false
        showMainView()
    }

    fun onBackwardsClick() {
        logClick("Backwards")
        startAutoReplay(GameModeEnum.BACKWARDS)
    }

    fun onStopClick() = afterStop {
        logClick("Stop")
        gameMode.modeEnum = GameModeEnum.STOP
        showMainView()
    }

    fun onForwardClick() {
        logClick("Forward")
        startAutoReplay(GameModeEnum.FORWARD)
    }

    fun onToStartClick() = afterStop {
        logClick("ToStart")
        boardViews.removeGameOver() // TODO: make this a move...
        (model.undoToStart() + listOf(PlayerMove.delay()) + model.redo()).present()
    }

    fun onToCurrentClick() = afterStop {
        logClick("ToCurrent")
        model.redoToCurrent().present()
    }

    fun onGameMenuClick() = afterStop {
        logClick("GameMenu")
        pauseGame()
        view.mainView.removeFromParent()
        view.showGameMenu()
    }

    fun onCloseMyWindowClick() {
        logClick("CloseMyWindow")
        showMainView()
    }

    fun onDeleteGameClick() = afterStop {
        logClick("DeleteGame")
        model.history.deleteCurrent()
        model.restart().present()
    }

    fun onBookmarksClick() {
        logClick("Bookmarks")
        view.showBookmarks(model.history.currentGame)
    }

    fun onRestoreClick() = afterStop {
        logClick("Restore")
        model.saveCurrent()
        view.showRestoreGame(model.history.prevGames)
    }

    fun onGoToBookmarkClick(board: Board) = afterStop {
        logClick("GoTo${board.moveNumber}")
        if (moveIsInProgress.compareAndSet(expect = false, update = true)) {
            model.gotoBookmark(board).present()
        }
    }

    fun onHistoryItemClick(id: Int) = afterStop {
        logClick("History$id")
        if (moveIsInProgress.compareAndSet(expect = false, update = true)) {
            model.restoreGame(id).present()
        }
    }

    fun onShareClick() = afterStop {
        logClick("Share")
        view.gameStage.shareText(
                view.stringResources.text("share"), model.history.currentGame.shortRecord.jsonFileName,
                model.history.currentGame.toMap().toJson()
        )
    }

    fun onLoadClick() = afterStop {
        logClick("Load")
        view.gameStage.loadJsonGameRecord { json ->
            view.gameStage.launch {
                myLog("Opened game: ${json.substr(0, 140)}")
                GameRecord.fromJson(json, newId = 0)?.let {
                    myLog("Loaded game: $it")
                    model.history.currentGame = it
                    model.saveCurrent()
                    // I noticed some kind of KorGe window reset after return from the other activity:
                    // GLSurfaceView.onSurfaceChanged
                    // If really needed, we could re-create activity...
                    view.reInitialize()
                }
            }
        }
    }

    fun onHelpClick() = afterStop {
        logClick("Help")
        view.showHelp()
    }

    suspend fun onSelectColorTheme(colorThemeEnum: ColorThemeEnum) {
        logClick("onSelectColorTheme $colorThemeEnum")
        if (colorThemeEnum == view.settings.colorThemeEnum) return

        view.settings.colorThemeEnum = colorThemeEnum
        restartTheApp()
    }

    private suspend fun restartTheApp() {
        pauseGame()
        view.settings.save()
        view.reInitialize()
    }

    fun pauseGame() {
        clickCounter.incrementAndGet()
        model.pauseGame()
    }

    private fun startAutoReplay(newMode: GameModeEnum) {
        if (gameMode.modeEnum == newMode) {
            if (newMode == GameModeEnum.BACKWARDS) gameMode.decrementSpeed() else gameMode.incrementSpeed()
        } else if (if (newMode == GameModeEnum.BACKWARDS) canUndo() else canRedo()) {
            afterStop {
                val startCount = clickCounter.incrementAndGet()
                gameMode.modeEnum = newMode
                showMainView()
                coroutineScope.launch {
                    while (startCount == clickCounter.value &&
                        if (gameMode.modeEnum == GameModeEnum.BACKWARDS) canUndo() else canRedo()
                    ) {
                        if (gameMode.speed != 0) {
                            if (gameMode.modeEnum == GameModeEnum.BACKWARDS) undo() else redo()
                        }
                        delay(gameMode.delayMs.toLong())
                    }
                    gameMode.stop()
                    showMainView()
                }
            }
        }
    }

    private fun startAiPlay() {
        afterStop {
            val startCount = clickCounter.incrementAndGet()
            gameMode.modeEnum = GameModeEnum.AI_PLAY
            model.gameClock.start()
            showMainView()
            coroutineScope.launch {
                while (startCount == clickCounter.value && !model.noMoreMoves()
                    && gameMode.modeEnum == GameModeEnum.AI_PLAY) {
                    Stopwatch().start().let { stopWatch ->
                        val nextMove = aiPlayer.nextMove(model.board)
                        if (startCount == clickCounter.value && !moveIsInProgress.value) {
                            userMove(nextMove)
                            delay(gameMode.delayMs.toLong() - stopWatch.elapsed.millisecondsLong)
                        }
                    }
                }
                onAiStopClicked()
            }
        }
    }

    private fun logClick(buttonName: String) {
        myLog("$buttonName-${view.id} clicked ${clickCounter.value}, mode:${gameMode.modeEnum}")
        gameStopWatch.start()
    }

    private fun afterStop(action: () -> Unit) {
        val startCount = clickCounter.incrementAndGet()
        coroutineScope.launch {
            while (gameMode.autoPlaying && startCount == clickCounter.value) {
                delay(100)
            }
            action()
        }
    }

    fun composerMove(board: Board) = model.composerMove(board, false).present()

    fun computerMove() = model.randomComputerMove().present()

    fun computerMove(placedPiece: PlacedPiece) = model.computerMove(placedPiece).present()

    private fun userMove(playerMoveEnum: PlayerMoveEnum) {
        if (!moveIsInProgress.compareAndSet(expect = false, update = true)) return

        if (model.noMoreMoves()) {
            onPresentEnd()
            boardViews = boardViews
                .removeGameOver()
                .copy()
                .apply { gameOver = view.mainView.boardView.showGameOver() }
        } else {
            model.userMove(playerMoveEnum).let {
                if (it.isEmpty()) it else it + model.randomComputerMove()
            }.present()
        }
    }

    private fun List<PlayerMove>.present(index: Int = 0) {
        if (index < size) {
            present(this[index]) {
                present(index + 1)
            }
        } else {
            onPresentEnd()
        }
    }

    private fun onPresentEnd() {
        showMainView()
        moveIsInProgress.value = false
    }

    fun showMainView() {
        view.mainView.show(buttonsToShow(), gameMode.speed)
    }

    private fun buttonsToShow(): List<AppBarButtonsEnum> {
        val list = ArrayList<AppBarButtonsEnum>()
        when (gameMode.modeEnum) {
            GameModeEnum.PLAY -> {
                list.add(AppBarButtonsEnum.PLAY)
                list.add(
                    if (gameMode.aiEnabled) AppBarButtonsEnum.AI_ON else AppBarButtonsEnum.AI_OFF
                )
                if (model.gameClock.started) {
                    list.add(AppBarButtonsEnum.PAUSE)
                    list.add(
                        if (model.isBookmarked) AppBarButtonsEnum.BOOKMARKED else AppBarButtonsEnum.BOOKMARK
                    )
                } else {
                    list.add(AppBarButtonsEnum.APP_LOGO)
                    if (model.history.currentGame.playerMoves.size > 1 && model.isBookmarked) {
                        list.add(AppBarButtonsEnum.BOOKMARKED)
                    } else {
                        list.add(AppBarButtonsEnum.BOOKMARK_PLACEHOLDER)
                    }
                    if (!canRedo()) {
                        list.add(AppBarButtonsEnum.RESTART)
                    }
                }
                if (gameMode.aiEnabled) {
                    list.add(AppBarButtonsEnum.AI_START)
                }

                if (canUndo()) {
                    list.add(AppBarButtonsEnum.UNDO)
                }
                list.add(if (canRedo()) AppBarButtonsEnum.REDO else AppBarButtonsEnum.REDO_PLACEHOLDER)

                list.add(AppBarButtonsEnum.GAME_MENU)
            }
            GameModeEnum.AI_PLAY -> {
                list.add(AppBarButtonsEnum.PLAY)
                list.add(AppBarButtonsEnum.AI_ON)
                list.add(AppBarButtonsEnum.BOOKMARK_PLACEHOLDER)
                list.add(AppBarButtonsEnum.AI_STOP)
            }
            else -> {
                list.add(AppBarButtonsEnum.WATCH)
                if (canUndo()) {
                    if (gameMode.speed == -gameMode.maxSpeed) {
                        list.add(AppBarButtonsEnum.TO_START)
                    } else {
                        list.add(AppBarButtonsEnum.BACKWARDS)
                    }
                }
                if (gameMode.modeEnum == GameModeEnum.STOP) {
                    list.add(AppBarButtonsEnum.APP_LOGO)
                    list.add(AppBarButtonsEnum.STOP_PLACEHOLDER)
                } else {
                    list.add(AppBarButtonsEnum.STOP)
                }
                if (canRedo()) {
                    if (gameMode.speed == gameMode.maxSpeed) {
                        list.add(AppBarButtonsEnum.TO_CURRENT)
                    } else {
                        list.add(AppBarButtonsEnum.FORWARD)
                    }
                } else {
                    list.add(AppBarButtonsEnum.FORWARD_PLACEHOLDER)
                }
                if (gameMode.modeEnum == GameModeEnum.STOP) {
                    list.add(AppBarButtonsEnum.GAME_MENU)
                }
            }
        }
        return list
    }

    private fun present(playerMove: PlayerMove, onEnd: () -> Unit) = view.gameStage.launchImmediately {
        view.gameStage.animateSequence {
            parallel {
                playerMove.moves.forEach { move ->
                    when (move) {
                        is MovePlace -> boardViews.addBlock(move.first)
                        is MoveLoad -> boardViews.load(move.board)
                        is MoveOne -> boardViews[move.first]?.move(this, move.destination)
                        is MoveMerge -> {
                            val firstBlock = boardViews[move.first]
                            val secondBlock = boardViews[move.second]
                            sequence {
                                parallel {
                                    firstBlock?.move(this, move.merged.square)
                                    secondBlock?.move(this, move.merged.square)
                                }
                                block {
                                    firstBlock?.remove()
                                    secondBlock?.remove()
                                    boardViews.addBlock(move.merged)
                                }
                                sequenceLazy {
                                    if (view.animateViews) boardViews[move.merged]
                                        ?.let { animateResultingBlock(this, it) }
                                }
                            }
                        }
                        is MoveDelay -> with(view) {
                            if (animateViews) boardViews.blocks.lastOrNull()?.also {
                                moveTo(it.block, it.square, gameMode.delayMs.milliseconds, Easing.LINEAR)
                            }
                        }
                    }
                }
            }
            block {
                onEnd()
            }
        }
    }

    private fun List<PlayerMove>.presentReversed(index: Int = 0) {
        if (index < size) {
            presentReversed(this[index]) {
                presentReversed(index + 1)
            }
        } else {
            onPresentEnd()
        }
    }

    private fun presentReversed(playerMove: PlayerMove, onEnd: () -> Unit) = view.gameStage.launchImmediately {
        view.gameStage.animateSequence {
            parallel {
                playerMove.moves.asReversed().forEach { move ->
                    when (move) {
                        is MovePlace -> boardViews[move.first]
                            ?.remove()
                            ?: myLog("No Block at destination during undo: $move")
                        is MoveLoad -> boardViews.load(move.board)
                        is MoveOne -> boardViews[PlacedPiece(move.first.piece, move.destination)]
                            ?.move(this, move.first.square)
                            ?: myLog("No Block at destination during undo: $move")
                        is MoveMerge -> {
                            val destination = move.merged.square
                            val effectiveBlock = boardViews[move.merged]
                            sequence {
                                block {
                                    effectiveBlock?.remove()
                                        ?: myLog("No Block at destination during undo: $move")
                                }
                                parallel {
                                    val secondBlock = boardViews.addBlock(PlacedPiece(move.second.piece, destination))
                                    val firstBlock = boardViews.addBlock(PlacedPiece(move.first.piece, destination))
                                    secondBlock.move(this, move.second.square)
                                    firstBlock.move(this, move.first.square)
                                }
                            }
                        }
                        is MoveDelay -> with(view) {
                            if (animateViews) boardViews.blocks.lastOrNull()?.also {
                                moveTo(it.block, it.square, gameMode.delayMs.milliseconds, Easing.LINEAR)
                            }
                        }
                    }
                }
            }
            block {
                onEnd()
            }
        }
    }

    private fun Block.move(animator: Animator, to: Square) {
        with(view) {
            if (animateViews) animator.moveTo(this@move, to, gameMode.moveMs.milliseconds, Easing.LINEAR)
        }
        boardViews[PlacedPiece(piece, to)] = this
    }

    private fun Block.remove() {
        boardViews.removeBlock(this)
    }

    private fun animateResultingBlock(animator: Animator, block: Block) {
        val x = block.x
        val y = block.y
        val scale = block.scale
        val scaleChange = 0.1
        val shift = block.scaledWidth * scaleChange / 2
        animator.tween(
            block::x[x - shift],
            block::y[y - shift],
            block::scale[scale + scaleChange],
            time = gameMode.resultingBlockMs.milliseconds,
            easing = Easing.LINEAR
        )
        animator.tween(
            block::x[x],
            block::y[y],
            block::scale[scale],
            time = gameMode.resultingBlockMs.milliseconds,
            easing = Easing.LINEAR
        )
    }

    fun onResumeEvent() {
        myLog("OnResume")
        with(view) {
            gameStage.launch {
                reInitialize()
            }
        }
    }
}
