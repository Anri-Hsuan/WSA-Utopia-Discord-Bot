package tw.waterballsa.utopia.RussianRoulette

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.components.buttons.Button
import org.springframework.stereotype.Component
import tw.waterballsa.utopia.commons.config.WsaDiscordProperties
import tw.waterballsa.utopia.jda.UtopiaListener
import java.util.concurrent.TimeUnit


@Component
class RussianRouletteListener(private val wsa: WsaDiscordProperties) : UtopiaListener() {
    private val playerIdToGame = hashMapOf<String, RouletteGame>()
    override fun commands(): List<CommandData> = listOf(
        Commands.slash("roulette", "Start the game.")
    )

    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        with(event) {
            if (fullCommandName != "roulette") {
                return
            }
            val player = member!!
            playerIdToGame[player.id] = RouletteGame()

            channel.sendMessage("${player.asMention}，俄羅斯輪盤開始")
                .addActionRow(Button.primary("trigger", "Shoot"))
                .timeout(1, TimeUnit.MINUTES)
                .queue()
        }
    }

    override fun onButtonInteraction(event: ButtonInteractionEvent) {
        with(event) {
            if ("trigger" != button.id) {
                return
            }
            val player = member!! // !! -> 不可能是null
            val rouletteGame = playerIdToGame[player.id] ?: return // if 是null return
            if (rouletteGame.pullTrigger()) {
                channel.sendMessage("你已中彈，遊戲結束").queue()
                playerIdToGame.remove(player.id)
                return
            }
            channel.sendMessage("你成功躲過一輪，輪到我開槍").queue()
            if (rouletteGame.pullTrigger()) {
                channel.sendMessage("我已中彈，遊戲結束").queue()
                playerIdToGame.remove(player.id)
                return
            }
            channel.sendMessage("我成功躲過一輪，輪到你開槍").queue()
        }
    }
}
/**
 * 1. 玩家輸入指令 /roulette
 * 2. 系統生成輪盤，六個空位中有一個裝有子彈 (ex. 空 空 空 彈 空 空)
 * 3. 玩家選擇由自己先開始 or 機器人先開始
 * 4. 按按鈕開槍 -> 空 -> 換另一位
 *             -> 彈 -> 玩家 / 機器人 已死亡，機器人 / 玩家 存活
 */

