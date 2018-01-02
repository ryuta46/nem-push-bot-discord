/*
 * MIT License
 *
 * Copyright (c) 2017 Taizo Kusuda 
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.ryuta46.nempushbotdiscord

import com.google.common.util.concurrent.FutureCallback
import de.btobastian.javacord.DiscordAPI
import de.btobastian.javacord.Javacord
import de.btobastian.javacord.listener.message.MessageCreateListener
import org.mapdb.BTreeMap
import org.mapdb.DBMaker
import org.mapdb.Serializer
import org.mapdb.serializer.SerializerArrayTuple
import org.slf4j.LoggerFactory
import java.io.File

/**
 * A simple ping-pong bot.
 */
class NemPushBot(token: String) {
    companion object {
        private const val MY_ID = "397481563270545408"
    }
    private val log = LoggerFactory.getLogger(NemPushBot::class.java)

    private val botFrameworkClient = BotFrameworkClient(log)
    private val nemClient = NemClientController(
            mainHosts = listOf(
                    "go.nem.ninja", // OK
                    "hachi.nem.ninja" // OK
            ),
            testHosts = listOf(//"23.228.67.85"
                    "104.128.226.60"
                    //,"192.3.61.243"
                    //,"50.3.87.123"
            ),
            logger = LogbackLogger(log))


    private val db = DBMaker.fileDB(File("subscription.db"))
            .closeOnJvmShutdown()
            .make()

    // (channelId, address) -> (name)
    private val subscriptions: BTreeMap<Array<Any>, String> =
            db.treeMap("subscriptions")
                    .keySerializer(SerializerArrayTuple(Serializer.STRING, Serializer.STRING))
                    .valueSerializer(Serializer.STRING).createOrOpen()

    // channelId -> Webhook URL
    private val hooks: BTreeMap<String, String> =
            db.treeMap("registers")
                    .keySerializer(Serializer.STRING)
                    .valueSerializer(Serializer.STRING).createOrOpen()

    fun postMessage(channelId: String, message: String) {
        hooks[channelId]?.let { url ->
            botFrameworkClient.post(url, message)
        }

    }

    private fun register(channelId: String, url: String) : String {
        log.debug("id $channelId: register $url")
        hooks.put(channelId, url)
        db.commit()
        return "チャンネル登録完了"
    }
    private fun unregister(channelId: String) : String {
        log.debug("id $channelId: unregister")
        hooks.remove(channelId)
        db.commit()
        return "チャンネル登録削除"
    }

    private fun subscribe(channelId: String, address: String, name: String = "", isSilent: Boolean = false): String {
        log.debug("id $channelId: subscribe address = $address, name = $name")
        try {
            nemClient.subscribeTransaction(channelId, address, name){ label, message, mosaics, isRecipient ->
                postMessage(channelId, MessageFormatter.transaction(label, message, mosaics, isRecipient))
            }
            if (!isSilent) {
                // save subscription
                subscriptions.put(arrayOf(channelId, address), name)
                db.commit()
                postMessage(channelId, "アドレスを登録しました")
            }
        } catch (e: IllegalArgumentException) {
            postMessage(channelId, "不正なアドレス: $address")
        }
        return ""
    }

    private fun unsubscribe(channelId: String, address: String): String {
        log.debug("id $channelId: unsubscribe address = $address")
        try {
            nemClient.unsubscribeTransaction(channelId, address)
            postMessage(channelId,"アドレスを登録解除しました")
            val key: Array<Any> = arrayOf(channelId, address)
            subscriptions.remove(key)
        } catch (e: IllegalArgumentException) {
            postMessage(channelId, "不正なアドレス: $address")
        }
        return ""
    }

    private fun balance(channelId: String): String {
        if (findSubscriptions(channelId).isEmpty()) {
            return "アドレスが登録されていません。add でアドレスを登録してください。"
        }

        log.debug("id $channelId: balance")
        nemClient.balance(channelId,
                result = { label, mosaics ->
                    postMessage(channelId, MessageFormatter.balance(label, mosaics))},
                fail = { postMessage(channelId, it) })
        return "残高取得中"
    }

    private fun listAddress(channelId: String): String {
        log.debug("id $channelId: list")
        val addresses = nemClient.listAddress(channelId)
        return MessageFormatter.listAddress(addresses)
    }

    private fun findSubscriptions(channelId: String): List<String> =
            subscriptions.keys.filter { it?.get(0) == channelId }.map { it?.get(1).toString()}

    init {
        // retrieve subscriptions
        hooks.entries.forEach { entry ->
            val channelId = entry.key
            val url = entry.value ?: return@forEach
            register(channelId, url)
        }

        subscriptions.entries.forEach { entry ->
            val channelId = entry.key.getOrNull(0) as? String
            val address = entry.key.getOrNull(1) as? String
            val name = entry.value
            if (channelId == null || address == null || name == null) {
                return@forEach
            }
            subscribe(channelId, address, name, true)
        }


        val api = Javacord.getApi(token, true)
        // connect
        api.connect(object : FutureCallback<DiscordAPI> {
            override fun onSuccess(api: DiscordAPI?) {
                // register listener
                api?.registerListener(MessageCreateListener { api, message ->
                    // check the content of the message

                    val prefix = message.mentions.find { user -> user.id == MY_ID }?.let{ "" } ?: "npb."

                    val commands = message.content.split(" ").filter { !it.startsWith("<@") }
                    if (commands.isEmpty()) {
                        return@MessageCreateListener
                    }

                    val channelId = message.channelReceiver.id

                    if (!commands[0].startsWith(prefix)) {
                        return@MessageCreateListener
                    }

                    val command = commands[0].substring(prefix.length)

                    val reply = when (command) {
                        "help" -> {
                            MessageFormatter.usage()
                        }
                        "register" -> {
                            if (commands.size <= 1) {
                                MessageFormatter.usage()
                            } else {
                                val url = commands[1]
                                register(channelId, url)
                            }
                        }
                        "unregister" -> {
                            unregister(channelId)
                        }
                        else -> {
                            if (hooks[channelId].isNullOrEmpty()) {
                                "Webhook が登録されていません。最初に register で Webhook を登録してください。"
                            } else {
                                when(command) {
                                    "add" -> {
                                        if (commands.size <= 1) {
                                            MessageFormatter.usage()
                                        } else {
                                            val address = commands[1]
                                            val name = if (commands.size <= 2) "" else commands[2]
                                            subscribe(channelId, address.replace("-",""), name)
                                        }
                                    }
                                    "remove" -> {
                                        if (commands.size <= 1) {
                                            MessageFormatter.usage()
                                        }
                                        val address = commands[1]
                                        unsubscribe(channelId, address.replace("-",""))
                                    }
                                    "balance" -> {
                                        balance(channelId)
                                    }

                                    "list" -> {
                                        listAddress(channelId)
                                    }
                                    else -> {
                                        ""
                                    }
                                }
                            }
                        }
                    }
                    if (reply.isNotEmpty()) {
                        message.reply(reply)
                    }

                })

            }

            override fun onFailure(t: Throwable) {
                t.printStackTrace()
            }
        })
    }
}


fun main(args : Array<String>) {
    if (args.isEmpty()) {
        print("Usage nem-push-bot-discord.jar apiKey")
        return
    }
    NemPushBot(args[0])
}
