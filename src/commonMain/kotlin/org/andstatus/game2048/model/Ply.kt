package org.andstatus.game2048.model

private const val keyPlayerEnum = "player"
private const val keyPlayerEnumV1 = "playerEnum"
private const val keyPlyEnum = "ply"
private const val keyPlyEnumV1 = "moveEnum"
private const val keySeconds = "seconds"
private const val keyMoves = "moves"

/** @author yvolk@yurivolkov.com
 * on Ply term see https://en.wikipedia.org/wiki/Ply_(game_theory)
 * */
data class Ply(val player: PlayerEnum, val plyEnum: PlyEnum, val seconds: Int, val pieceMoves: List<PieceMove>) {

    fun toMap(): Map<String, Any> = mapOf(
        keyPlayerEnum to player.id,
        keyPlyEnum to plyEnum.id,
        keySeconds to seconds,
        keyMoves to pieceMoves.map{ it.toMap() }
    )

    fun isEmpty() = plyEnum.isEmpty()
    fun isNotEmpty() = !isEmpty()

    fun points(): Int = pieceMoves.map { it.points() }.sum()

    companion object{
        val emptyPly = Ply(PlayerEnum.COMPOSER, PlyEnum.EMPTY, 0, emptyList())

        fun composerPly(position: GamePosition) =
                Ply(PlayerEnum.COMPOSER, PlyEnum.LOAD, position.gameClock.playedSeconds, listOf(PieceMoveLoad(position)))

        fun computerPly(placedPiece: PlacedPiece, seconds: Int) =
                Ply(PlayerEnum.COMPUTER, PlyEnum.PLACE, seconds, listOf(PieceMovePlace(placedPiece)))

        fun userPly(plyEnum: PlyEnum, seconds: Int, pieceMoves: List<PieceMove>) =
                Ply(PlayerEnum.USER, plyEnum, seconds, pieceMoves)

        fun delay(delayMs: Int = 500) =
                Ply(PlayerEnum.COMPOSER, PlyEnum.DELAY, 0, listOf(PieceMoveDelay(delayMs)))

        fun fromJson(board: Board, json: Any): Ply? {
            val aMap: Map<String, Any> = json.parseJsonMap()
            val player = (aMap[keyPlayerEnum] ?: aMap[keyPlayerEnumV1])?.let { PlayerEnum.fromId(it.toString()) }
            val plyEnum = (aMap[keyPlyEnum] ?: aMap[keyPlyEnumV1])?.let { PlyEnum.fromId(it.toString()) }
            val seconds: Int = aMap[keySeconds] as Int? ?: 0
            val pieceMoves: List<PieceMove>? = aMap[keyMoves]?.parseJsonArray()?.mapNotNull { PieceMove.fromJson(board, it) }
            return if (player != null && plyEnum != null && pieceMoves != null)
                Ply(player, plyEnum, seconds, pieceMoves)
            else
                null
        }
    }
}