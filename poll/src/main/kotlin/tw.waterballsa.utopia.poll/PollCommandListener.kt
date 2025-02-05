package tw.waterballsa.utopia.poll

import mu.KotlinLogging
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.entities.emoji.EmojiUnion
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent
import net.dv8tion.jda.api.events.message.react.MessageReactionRemoveEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData
import org.springframework.stereotype.Component
import tw.waterballsa.utopia.commons.extensions.scheduleDelay
import tw.waterballsa.utopia.jda.UtopiaListener
import tw.waterballsa.utopia.jda.extensions.getOptionAsLongInRange
import tw.waterballsa.utopia.jda.extensions.getOptionAsStringWithValidation
import java.awt.Color
import java.lang.System.lineSeparator
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val OPTION_TIME = "time"

private const val OPTION_TIMEUNIT = "time-unit"

private const val OPTION_QUESTION = "question"

private const val OPTION_OPTIONS = "options"

private val EMOJI_UNICODES: Array<String> = arrayOf("0️⃣", "1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣", "6️⃣", "7️⃣", "8️⃣", "9️⃣", "\uD83D\uDD1F")

private val log = KotlinLogging.logger {}

private val timer = Timer()

/**
 * /poll time=1 timeUnit=minutes question="Which operating system do you prefer?" options="Windows,MacOS,Linux,Other"
 * @author johnny@waterballsa.tw
 */
@Component
class PollCommandListener : UtopiaListener() {
    // embedded session id (message's id) to polling session
    private val sessionIdToSession: ConcurrentHashMap<String, PollingSession> = ConcurrentHashMap()

    override fun commands(): List<CommandData> {
        return listOf(
                Commands.slash("poll", "Initiate a polling session and permit Discord members to cast their votes for various options within a specified amount of time.")
                        .addRequiredOption(OptionType.INTEGER, OPTION_TIME, "The duration of the poll session")
                        .addRequiredOption(OptionType.STRING, OPTION_TIMEUNIT, "(Day | Minute | Second)")
                        .addRequiredOption(OptionType.STRING, OPTION_QUESTION, "Question")
                        .addRequiredOption(OptionType.STRING, OPTION_OPTIONS, "Options (split by comma)")
        )
    }

    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        with(event) {
            if (!fullCommandName.startsWith("poll")) {
                return
            }
            val pollingSetting = parsePollingSettingFromOptions() ?: return

            val message = channel.sendMessageEmbeds(pollingSetting.toMessageEmbeds()).complete()
            val pollingSession = PollingSession(id = message.id, channelId = channel.id, setting = pollingSetting)
            sessionIdToSession[message.id] = pollingSession

            pollingSetting.options.forEachIndexed { i, _ ->
                message.addReaction(Emoji.fromUnicode(EMOJI_UNICODES[i])).complete()
            }

            scheduleTaskToEndThePollingSession(jda, pollingSession)
        }
    }


    private fun SlashCommandInteractionEvent.parsePollingSettingFromOptions(): PollingSetting? {
        val time = getOptionAsLongInRange(OPTION_TIME, 0..500L)!!
        val timeUnit = getOptionAsStringWithValidation(OPTION_TIMEUNIT, "should be one of (Days | Minutes | Seconds)") {
            TimeUnit.values().any { unit -> unit.name == it.uppercase() }
        }?.let { TimeUnit.valueOf(it.uppercase()) } ?: return null

        val question = getOption(OPTION_QUESTION)!!.asString
        val options = getOption(OPTION_OPTIONS)!!.asString.split(Regex("\\s*,\\s*"))
        if (options.size > EMOJI_UNICODES.size) {
            reply("The number of options cannot be greater than ${EMOJI_UNICODES.size}.").complete()
            return null
        }

        return PollingSetting(time, timeUnit, question, options)
    }

    override fun onMessageReactionAdd(event: MessageReactionAddEvent) {
        with(event) {
            val session = sessionIdToSession[messageId] ?: return
            if (userId == jda.selfUser.id) {
                return
            }
            session.vote(Vote(userId, emoji))
        }
    }

    override fun onMessageReactionRemove(event: MessageReactionRemoveEvent) {
        with(event) {
            val session = sessionIdToSession[messageId] ?: return
            if (userId == jda.selfUser.id) {
                return
            }
            session.devote(Vote(userId, emoji))
        }
    }

    private fun scheduleTaskToEndThePollingSession(jda: JDA, pollingSession: PollingSession) {
        val setting = pollingSession.setting
        timer.scheduleDelay(setting.timeUnit.toMillis(setting.time)) {
            val pollingResult = pollingSession.end()

            sessionIdToSession.remove(pollingSession.id)
            val channel = jda.getTextChannelById(pollingSession.channelId)!!
            val message = channel.retrieveMessageById(pollingSession.id).complete()
            message.reply("The polling session has ended. Result：\n${pollingResult.messageBody}")
                    .complete()
        }
    }
}


data class PollingSetting(val time: Long, val timeUnit: TimeUnit, val question: String, val options: List<String>) {
    private val optionsMessageBody = options.mapIndexed { i, option ->
        "${EMOJI_UNICODES[i]} $option"
    }.joinToString(lineSeparator())


    fun toMessageEmbeds(): Collection<MessageEmbed> =
            listOf(
                    EmbedBuilder()
                            .setTitle("Polling")
                            .setDescription(question)
                            .setColor(Color.GREEN)
                            .build(),
                    EmbedBuilder()
                            .setTitle("Options")
                            .setDescription(optionsMessageBody)
                            .setColor(Color.RED)
                            .build(),
                    EmbedBuilder()
                            .setTitle("Settings")
                            .setDescription("Session ends in $time $timeUnit.")
                            .setColor(Color.BLUE)
                            .build(),
            )

    fun getOption(index: Int): String = options[index]
}

data class Vote(val userId: String, val emoji: EmojiUnion)

class PollingSession(
        val id: String, val channelId: String, val setting: PollingSetting) {
    private val voterIdToVotedOptionIndices = hashMapOf<String, MutableList<Int>>()

    fun vote(vote: Vote) {
        findVoterOptionIndices(vote) {
            log.info { """[Voted] {"userId": ${vote.userId}, "optionIndex": $it}" }""" }
            add(it)
        }
    }

    fun devote(vote: Vote) {
        findVoterOptionIndices(vote) {
            log.info { """[Devoted] {"userId": ${vote.userId}, "optionIndex": $it}" }""" }
            remove(it)
        }
    }

    private fun findVoterOptionIndices(vote: Vote, newIndexConsumer: MutableList<Int>.(int: Int) -> Unit) {
        val emojiIndex = EMOJI_UNICODES.indexOf(vote.emoji.name)
        if (emojiIndex >= 0) {
            newIndexConsumer.invoke(voterIdToVotedOptionIndices.computeIfAbsent(vote.userId) { mutableListOf() }, emojiIndex)
        }
    }

    fun end(): PollingResult {
        return PollingResult(voterIdToVotedOptionIndices, setting)
    }
}

class PollingResult(private val voterIdToVotedOptionIndices: Map<String, MutableList<Int>>, private val setting: PollingSetting) {
    val messageBody: String
        get() {
            return voterIdToVotedOptionIndices.values
                    .flatten()
                    .groupingBy { it }
                    .eachCount()
                    .map { (index, count) -> "${setting.getOption(index)}: $count votes." }
                    .joinToString(lineSeparator())
        }
}

private fun SlashCommandData.addRequiredOption(type: OptionType, name: String, description: String) =
        addOption(type, name, description, true)
