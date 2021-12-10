package org.andstatus.game2048.model

import com.soywiz.klock.DateTimeTz
import com.soywiz.klock.weeks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.andstatus.game2048.Settings
import org.andstatus.game2048.gameIsLoading
import org.andstatus.game2048.keyCurrentGameId
import org.andstatus.game2048.myLog
import org.andstatus.game2048.myMeasured
import org.andstatus.game2048.myMeasuredIt

const val keyGameMode = "gameMode"

private val gameIdsRange = 1..60
private const val maxOlderGames = 30

/** @author yvolk@yurivolkov.com */
class History(
    val settings: Settings,
    currentGameIn: GameRecord?,
    var recentGames: List<ShortRecord> = emptyList()
) {
    private val keyBest = "best"
    var currentGame: GameRecord = currentGameIn
        ?: GameRecord.newWithPositionAndPlies(
            settings, GamePosition(settings.defaultBoard), idForNewGame(), emptyList(), emptyList()
        )

    // 1. Info on previous games
    var bestScore: Int = settings.storage.getOrNull(keyBest)?.toInt() ?: 0

    // 2. This game, see for the inspiration https://en.wikipedia.org/wiki/Portable_Game_Notation
    /** 0 means that the pointer is turned off */
    var redoPlyPointer: Int = 0
    val gameMode: GameMode = GameMode().apply {
        modeEnum = GameModeEnum.fromId(settings.storage.getOrNull(keyGameMode) ?: "").let {
            when (it) {
                GameModeEnum.AI_PLAY, GameModeEnum.PLAY -> GameModeEnum.PLAY
                else -> GameModeEnum.STOP
            }
        }
    }

    companion object {
        suspend fun load(settings: Settings): History = coroutineScope {
            val dCurrentGame = async {
                myMeasuredIt("Current game loaded") {
                    settings.currentGameId
                        ?.let { GameRecord.fromId(settings, it) }
                }
            }
            History(settings, dCurrentGame.await())
        }

        private fun loadRecentGames(settings: Settings): List<ShortRecord> =
            myMeasured("Recent games loaded") {
                gameIdsRange.fold(emptyList()) { acc, ind ->
                    ShortRecord.fromId(settings, ind)
                        ?.let { acc + it } ?: acc
                }
            }
    }

    fun loadRecentGames(): History {
        recentGames = loadRecentGames(settings)
        return this
    }

    fun openGame(id: Int): GameRecord? =
        if (currentGame.id == id) currentGame
        else GameRecord.fromId(settings, id)
            ?.also { openGame(it, id) }

    fun openGame(game: GameRecord?, id: Int): GameRecord? = game
        ?.also {
            if (it.id == id) {
                myLog("Opened game $it")
            } else {
                myLog("Fixed id $id while opening game $it")
                it.id = id
            }
            currentGame = it
            settings.storage[keyCurrentGameId] = currentGame.id
            gameMode.modeEnum = GameModeEnum.STOP
        }
        ?: run {
            myLog("Failed to open game $id")
            null
        }

    fun saveCurrent(coroutineScope: CoroutineScope): History {
        settings.storage[keyGameMode] = gameMode.modeEnum.id
        if (currentGame.id <= 0) {
            myLog("Nothing to save $currentGame")
            return this
        }

        settings.storage[keyCurrentGameId] = currentGame.id
        val game = currentGame

        coroutineScope.launch {
            myMeasuredIt("Game ${game.id} saved") {
                updateBestScore()
                game.save()
                gameIsLoading.compareAndSet(true, false)
                game
            }
            loadRecentGames()
        }
        return this
    }

    private fun updateBestScore() {
        if (bestScore < currentGame.score) {
            bestScore = currentGame.score
            settings.storage[keyBest] = bestScore.toString()
        }
    }

    fun idForNewGame(): Int = (idToDelete() ?: unusedGameId()).also { GameRecord.delete(settings, it) }

    private fun idToDelete() = if (recentGames.size > maxOlderGames) {
        val keepAfter = DateTimeTz.nowLocal().minus(1.weeks)
        val olderGames = recentGames.filter { it.finalPosition.startingDateTime < keepAfter }
        when {
            olderGames.size > 20 -> olderGames.minByOrNull { it.finalPosition.score }?.id
            recentGames.size >= gameIdsRange.last -> recentGames.minByOrNull { it.finalPosition.score }?.id
            else -> null
        }
    } else null

    private fun unusedGameId() = (gameIdsRange.find { id -> recentGames.none { it.id == id } }
        ?: recentGames.minByOrNull { it.finalPosition.startingDateTime }?.id
        ?: gameIdsRange.first)

    fun deleteCurrent() {
        GameRecord.delete(settings, currentGame.id)
        loadRecentGames()
    }

    private val plyToRedo: Ply?
        get() = when {
            redoPlyPointer < 1 || redoPlyPointer > currentGame.gamePlies.size -> null
            else -> currentGame.gamePlies[redoPlyPointer]
        }

    fun add(position: GamePosition) {
        if (currentGame.notCompleted) return

        currentGame = when (position.prevPly.plyEnum) {
            PlyEnum.LOAD -> {
                GameRecord.newWithPositionAndPlies(settings, position, idForNewGame(), emptyList(), emptyList())
            }
            else -> {
                val bookmarksNew = when {
                    redoPlyPointer < 1 -> {
                        currentGame.shortRecord.bookmarks
                    }
                    redoPlyPointer == 1 -> {
                        emptyList()
                    }
                    else -> {
                        currentGame.shortRecord.bookmarks.filterNot { it.plyNumber >= redoPlyPointer }
                    }
                }
                val gamePliesNew = when {
                    redoPlyPointer < 1 -> {
                        currentGame.gamePlies
                    }
                    redoPlyPointer == 1 -> {
                        GamePlies(currentGame.shortRecord)
                    }
                    else -> {
                        currentGame.gamePlies.take(redoPlyPointer - 1)
                    }
                } + position.prevPly
                with(currentGame.shortRecord) {
                    GameRecord(ShortRecord(settings, board, note, id, start, position, bookmarksNew), gamePliesNew)
                }
            }
        }
        updateBestScore()
        redoPlyPointer = 0
    }

    fun createBookmark(gamePosition: GamePosition) {
        if (currentGame.notCompleted) return

        currentGame = with(currentGame.shortRecord) {
            GameRecord(
                ShortRecord(settings, board, note, id, start, finalPosition,
                    bookmarks.filter { it.plyNumber != gamePosition.plyNumber } +
                            gamePosition.copy()),
                currentGame.gamePlies
            )
        }
    }

    fun deleteBookmark(gamePosition: GamePosition) {
        if (currentGame.notCompleted) return

        currentGame = with(currentGame.shortRecord) {
            GameRecord(
                ShortRecord(settings, board, note, id, start, finalPosition, bookmarks
                    .filterNot { it.plyNumber == gamePosition.plyNumber }),
                currentGame.gamePlies
            )
        }
    }

    fun canUndo(): Boolean = currentGame.isReady &&
            settings.allowUndo &&
            redoPlyPointer != 1 && redoPlyPointer != 2 &&
            currentGame.gamePlies.size > 1 &&
            currentGame.gamePlies.lastOrNull()?.player == PlayerEnum.COMPUTER

    fun undo(): Ply? {
        if (!canUndo()) {
            return null
        } else if (redoPlyPointer < 1 && currentGame.gamePlies.size > 0) {
            // Point to the last ply
            redoPlyPointer = currentGame.gamePlies.size
        } else if (redoPlyPointer > 1 && redoPlyPointer <= currentGame.gamePlies.size + 1)
            redoPlyPointer--
        else {
            return null
        }
        return plyToRedo
    }

    fun canRedo(): Boolean {
        return currentGame.isReady && redoPlyPointer > 0 && redoPlyPointer <= currentGame.gamePlies.size
    }

    fun redo(): Ply? {
        if (canRedo()) {
            return plyToRedo?.also {
                if (redoPlyPointer < currentGame.gamePlies.size)
                    redoPlyPointer++
                else {
                    redoPlyPointer = 0
                }
            }
        }
        redoPlyPointer = 0
        return null
    }

    fun gotoBookmark(position: GamePosition) {
        if (currentGame.notCompleted) return

        if (position.plyNumber >= currentGame.shortRecord.finalPosition.plyNumber) {
            redoPlyPointer = 0
        } else {
            redoPlyPointer = position.plyNumber + 1
        }
    }
}
