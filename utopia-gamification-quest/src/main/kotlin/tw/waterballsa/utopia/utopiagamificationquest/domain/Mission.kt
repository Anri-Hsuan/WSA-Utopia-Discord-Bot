package tw.waterballsa.utopia.utopiagamificationquest.domain

import tw.waterballsa.utopia.utopiagamificationquest.repositories.MongoRepositoryImpl.State
import tw.waterballsa.utopia.utopiagamificationquest.repositories.MongoRepositoryImpl.State.*
import java.time.LocalDateTime
import java.time.LocalDateTime.now
import java.util.*
import java.util.UUID.randomUUID

class Mission(
    val id: UUID,
    val player: Player,
    val quest: Quest,
    var state: State,
    var completedTime: LocalDateTime?
) {
    constructor(player: Player, quest: Quest) : this(randomUUID(), player, quest, State.IN_PROGRESS, null)

    fun match(action: Action): Boolean = action.match(quest.criteria)

    fun carryOut(action: Action) {
        if (action.execute(quest.criteria)) {
            state = COMPLETED
            completedTime = now()
        }
    }

    fun isCompleted(): Boolean = state == COMPLETED

    fun rewardPlayer() {
        player.gainExp(quest.reward.exp)
        state = State.CLAIMED
    }

    fun nextMission(): Mission? {
        if (state == State.IN_PROGRESS) {
            return null
        }
        return quest.nextQuest?.let { Mission(player, it) }
    }
}
