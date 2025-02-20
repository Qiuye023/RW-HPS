/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *  
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.plugin.internal.headless.inject.command

import com.corrodinggames.rts.game.n
import com.corrodinggames.rts.gameFramework.j.NetEnginePackaging
import com.corrodinggames.rts.gameFramework.j.k
import net.rwhps.server.data.global.Data
import net.rwhps.server.data.global.Data.LINE_SEPARATOR
import net.rwhps.server.data.global.NetStaticData
import net.rwhps.server.game.player.PlayerHess
import net.rwhps.server.func.StrCons
import net.rwhps.server.game.manage.HeadlessModuleManage
import net.rwhps.server.game.manage.MapManage
import net.rwhps.server.game.manage.ModManage
import net.rwhps.server.game.event.game.PlayerBanEvent
import net.rwhps.server.io.GameOutputStream
import net.rwhps.server.io.output.CompressOutputStream
import net.rwhps.server.plugin.internal.headless.inject.core.GameEngine
import net.rwhps.server.struct.list.Seq
import net.rwhps.server.util.Font16
import net.rwhps.server.util.IsUtils
import net.rwhps.server.util.PacketType
import net.rwhps.server.util.Time
import net.rwhps.server.util.Time.getTimeFutureMillis
import net.rwhps.server.util.game.command.CommandHandler
import net.rwhps.server.util.log.Log.error
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * @author Dr (dr@der.kim)
 */
internal class ServerCommands(handler: CommandHandler) {
    private fun registerPlayerCommand(handler: CommandHandler) {
        handler.register("say", "<text...>", "serverCommands.say") { arg: Array<String>, log: StrCons ->
            val msg = arg.joinToString(" ").replace("<>", "")
            room.call.sendSystemMessage(msg)
            log("All players has received the message : {0}", msg)
        }
        handler.register("whisper", "<PlayerPosition/PlayerName> <text...>", "serverCommands.whisper") { arg: Array<String>, log: StrCons ->
            room.playerManage.findPlayer(log, arg[0])?.let {
                val msg = arg.drop(1).joinToString(" ")
                it.sendSystemMessage(msg)
                log("{0} has received the message : {1}", it.name, msg)
            }
        }
        handler.register("gametime", "serverCommands.gametime") { _: Array<String>, log: StrCons ->
            if (room.isStartGame) {
                log("Gameing Time : {0}", Time.format(Time.getTimeSinceSecond(room.startTime) * 1000L, 6))
            } else {
                log("No Start Game")
            }
        }
        handler.register("gameover", "serverCommands.gameover") { _: Array<String>?, _: StrCons ->
            GameEngine.data.room.gr()
        }
        handler.register("save", "serverCommands.save") { _: Array<String>?, log: StrCons ->
            if (room.isStartGame) {
                gameModule.gameLinkFunction.saveGame()
            } else {
                log("No Start Game")
            }
        }
        handler.register(
                "admin", "<add/remove> <PlayerPosition> [SpecialPermissions]", "serverCommands.admin"
        ) { arg: Array<String>, log: StrCons ->
            if (room.isStartGame) {
                log(localeUtil.getinput("err.startGame"))
                return@register
            }
            if (!("add" == arg[0] || "remove" == arg[0])) {
                log("Second parameter must be either 'add' or 'remove'.")
                return@register
            }
            val add = "add" == arg[0]
            val site = arg[1].toInt() - 1
            val player = room.playerManage.getPlayer(site)
            val supAdmin = arg.size > 2
            if (player != null) {
                if (add) {
                    Data.core.admin.addAdmin(player.connectHexID, supAdmin)
                } else {
                    Data.core.admin.removeAdmin(player.connectHexID)
                }

                player.isAdmin = add
                player.superAdmin = supAdmin

                try {
                    player.con!!.sendServerInfo(false)
                } catch (e: IOException) {
                    error("[Player] Send Server Info Error", e)
                }
                log("Changed admin status of player: {0}", player.name)
            }
        }
        handler.register("clearbanall", "serverCommands.clearbanall") { _: Array<String>?, _: StrCons ->
            Data.core.admin.bannedIPs.clear()
            Data.core.admin.bannedUUIDs.clear()
        }
        handler.register("ban", "<PlayerPositionNumber>", "serverCommands.ban") { arg: Array<String>, _: StrCons ->
            val site = arg[0].toInt() - 1
            val player = room.playerManage.getPlayer(site)
            if (player != null) {
                GameEngine.data.eventManage.fire(PlayerBanEvent(player))
            }
        }
        handler.register("mute", "<PlayerPositionNumber> [Time(s)]", "serverCommands.mute") { arg: Array<String>, _: StrCons ->
            val site = arg[0].toInt() - 1
            val player = room.playerManage.getPlayer(site)
            if (player != null) {
                player.muteTime = getTimeFutureMillis(43200 * 1000L)
            }
        }
        handler.register("kick", "<PlayerPositionNumber> [time]", "serverCommands.kick") { arg: Array<String>, _: StrCons ->
            val site = arg[0].toInt() - 1
            val player = room.playerManage.getPlayer(site)
            if (player != null) {
                player.kickTime = if (arg.size > 1) getTimeFutureMillis(
                        arg[1].toInt() * 1000L
                ) else getTimeFutureMillis(60 * 1000L)
                try {
                    player.kickPlayer(localeUtil.getinput("kick.you"))
                } catch (e: IOException) {
                    error("[Player] Send Kick Player Error", e)
                }
            }
        }
        handler.register("kill", "<PlayerPositionNumber>", "serverCommands.kill") { arg: Array<String>, log: StrCons ->
            if (room.isStartGame) {
                val site = arg[0].toInt() - 1
                val player = room.playerManage.getPlayer(site)
                if (player != null) {
                    player.con!!.sendSurrender()
                }
            } else {
                log(localeUtil.getinput("err.noStartGame"))
            }
        }
        handler.register("giveadmin", "<PlayerPositionNumber...>", "serverCommands.giveadmin") { arg: Array<String>, _: StrCons ->
            room.playerManage.playerGroup.eachAllFind({ p: PlayerHess -> p.isAdmin }) { i: PlayerHess ->
                val player = room.playerManage.getPlayer(arg[0].toInt())
                if (player != null) {
                    i.isAdmin = false
                    player.isAdmin = true
                    room.call.sendSystemMessageLocal("give.ok", player.name)
                }
            }
        }
        handler.register("clearmuteall", "serverCommands.clearmuteall") { _: Array<String>?, _: StrCons ->
            room.playerManage.playerGroup.eachAll { e: PlayerHess -> e.muteTime = 0 }
        }

        handler.register("team", "<PlayerPositionNumber> <Team>", "serverCommands.team") { arg: Array<String>, log: StrCons ->
            if (GameEngine.data.room.isStartGame) {
                log(localeUtil.getinput("err.startGame"))
                return@register
            }

            if (IsUtils.notIsNumeric(arg[0]) && IsUtils.notIsNumeric(arg[1])) {
                log(localeUtil.getinput("err.noNumber"))
                return@register
            }
            synchronized(gameModule.gameLinkData.teamOperationsSyncObject) {
                val playerPosition = arg[0].toInt() - 1
                val newPosition = arg[1].toInt() - 1
                n.k(playerPosition).r = newPosition
            }
        }
    }

    private fun registerPlayerStatusCommand(handler: CommandHandler) {
        handler.register("players", "serverCommands.players") { _: Array<String>?, log: StrCons ->
            if (room.playerManage.playerGroup.size == 0) {
                log("No players are currently in the server.")
            } else {
                log("Players: {0}", room.playerManage.playerGroup.size)
                val data = StringBuilder()
                for (player in room.playerManage.playerGroup) {
                    data.append(LINE_SEPARATOR).append(player.playerInfo)
                }
                log(data.toString())
            }
        }

        handler.register("admins", "serverCommands.admins") { _: Array<String>?, log: StrCons ->
            if (Data.core.admin.playerAdminData.size == 0) {
                log("No admins are currently in the server.")
            } else {
                log("Admins: {0}", Data.core.admin.playerAdminData.size)
                val data = StringBuilder()
                for (player in Data.core.admin.playerAdminData.values) {
                    data.append(LINE_SEPARATOR).append(player.name).append(" / ").append("ID: ").append(player.uuid).append(" / ")
                        .append("Admin: ").append(player.admin).append(" / ").append("SuperAdmin: ").append(player.superAdmin)
                }
                log(data.toString())
            }
        }

        handler.register("reloadmods", "serverCommands.reloadmods") { _: Array<String>?, log: StrCons ->
            if (room.isStartGame) {
                log(localeUtil.getinput("err.startGame"))
            } else {
                log(localeUtil.getinput("server.loadMod", ModManage.reLoadMods()))
            }

        }
        handler.register("reloadmaps", "serverCommands.reloadmaps") { _: Array<String>?, log: StrCons ->
            val size = MapManage.mapsData.size
            MapManage.mapsData.clear()
            MapManage.readMapAndSave()
            // Reload 旧列表的Custom数量 : 新列表的Custom数量
            log("Reload Old Size:New Size is {0}:{1}", size, MapManage.mapsData.size)
        }
        handler.register("mods", "serverCommands.mods") { _: Array<String>?, log: StrCons ->
            for ((index, name) in ModManage.getModsList().withIndex()) {
                log(localeUtil.getinput("mod.info", index, name))
            }
        }
        handler.register("maps", "serverCommands.maps") { _: Array<String>?, log: StrCons ->
            val response = StringBuilder()
            val i = AtomicInteger(0)
            MapManage.mapsData.keys.forEach { k: String? ->
                response.append(localeUtil.getinput("maps.info", i.get(), k)).append(LINE_SEPARATOR)
                i.getAndIncrement()
            }
            log(response.toString())
        }
    }

    private fun registerPlayerCustomEx(handler: CommandHandler) {
        handler.register("addmoney", "<PlayerPositionNumber> <money>", "serverCommands.addmoney") { arg: Array<String>, log: StrCons ->
            if (!room.isStartGame) {
                log(localeUtil.getinput("err.noStartGame"))
                return@register
            }
            val site = arg[0].toInt() - 1
            val player = room.playerManage.getPlayer(site)
            if (player != null) {
                player.credits += arg[1].toInt()
            }
            gameModule.gameLinkFunction.allPlayerSync()
        }

        handler.register(
                "textbuild", "<UnitName> <Text> [index(NeutralByDefault)]", "serverCommands.textbuild"
        ) { arg: Array<String>, _: StrCons ->
            val cache = Seq<Array<ByteArray>>()

            arg[1].forEach {
                cache.add(Font16.resolveString(it.toString()))
            }

            val index = if (arg.size > 2) {
                when {
                    IsUtils.isNumericNegative(arg[2]) -> arg[2].toInt()
                    else -> -1
                }
            } else {
                -1
            }

            // 偏移量
            var off = 0

            cache.eachAll {
                var i = 0
                var lg = true
                for ((height, lineArray) in it.withIndex()) {
                    for ((width, b) in lineArray.withIndex()) {
                        if (lg) {
                            i++
                        }
                        if (b.toInt() == 1) {
                            //Data.game.gameCommandCache.add(NetStaticData.RwHps.abstractNetPacket.gameSummonPacket(index, arg[0], ((off+width)*20).toFloat(), (height*20).toFloat()))
                            try {
                                val commandPacket = GameEngine.gameEngine.cf.b()

                                val out = GameOutputStream()
                                out.flushEncodeData(CompressOutputStream.getGzipOutputStream("c", false).apply {
                                    writeBytes(
                                            NetStaticData.RwHps.abstractNetPacket.gameSummonPacket(
                                                    index, arg[0], ((off + width) * 20).toFloat(), (height * 20).toFloat()
                                            ).bytes
                                    )
                                })

                                commandPacket.a(k(NetEnginePackaging.transformHessPacketNullSource(out.createPacket(PacketType.TICK))))

                                commandPacket.c = GameEngine.data.gameHessData.tickNetHess + 10
                                GameEngine.gameEngine.cf.b.add(commandPacket)
                            } catch (e: Exception) {
                                error(e)
                            }
                        }
                    }
                    lg = false
                }
                i++
                off += i
            }
        }
    }

    companion object {
        private val localeUtil = Data.i18NBundle
        private val gameModule = HeadlessModuleManage.hessLoaderMap[this::class.java.classLoader.toString()]!!
        private val room = gameModule.room
    }

    init {
        registerPlayerCommand(handler)
        registerPlayerStatusCommand(handler)
        registerPlayerCustomEx(handler)
    }
}