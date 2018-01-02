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

import com.ryuta46.nemkotlin.account.AccountGenerator
import com.ryuta46.nemkotlin.client.RxNemApiClient
import com.ryuta46.nemkotlin.client.RxNemWebSocketClient
import com.ryuta46.nemkotlin.enums.TransactionType
import com.ryuta46.nemkotlin.enums.Version
import com.ryuta46.nemkotlin.exceptions.NetworkException
import com.ryuta46.nemkotlin.model.Block
import com.ryuta46.nemkotlin.model.Mosaic
import com.ryuta46.nemkotlin.model.TransferTransaction
import com.ryuta46.nemkotlin.util.ConvertUtils
import com.ryuta46.nemkotlin.util.Logger
import com.ryuta46.nempushbotdiscord.model.MosaicWithDivisibility
import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import java.security.SecureRandom

class NemClientController(mainHosts: List<String>,testHosts: List<String>, private val logger: Logger) {
    data class Client(val rx: RxNemApiClient,
                      val webSocket: RxNemWebSocketClient)

    data class Subscription(
            val client: Client,
            val result:(String, String, List<MosaicWithDivisibility>, Boolean) -> Unit,
            val name: String
    )

    // Conversation ID -> ( Address -> Subscription )
    private val subscriptions = mutableMapOf<String, MutableMap<String, Subscription>>()

    var divisibilityMap = mutableMapOf("nem:xem" to 6)

    private val mainClients: List<Client> = mainHosts.map {
        Client(RxNemApiClient("http://$it:7890", logger = logger), RxNemWebSocketClient("http://$it:7778", logger = logger))
    }
    private val testClients: List<Client> = testHosts.map {
        Client(RxNemApiClient("http://$it:7890", logger = logger), RxNemWebSocketClient("http://$it:7778", logger = logger))
    }


    var mainHeight: Int = 0
    var testHeight: Int = 0

    init {
        // Start monitoring
        mainClients.forEach {
            subscribeBlock(it, result = { notifyToConversation(it, Version.Main) }) }
        testClients.forEach{
            subscribeBlock(it, result = { notifyToConversation(it, Version.Test) }) }
    }


    private fun subscribeBlock(client: Client, result:(Block) -> Unit = {}, fail:(String) -> Unit = {}) {
        client.webSocket.blocks()
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.newThread())
                .onErrorResumeNext { e: Throwable ->
                    // auto retry
                    logger.log(Logger.Level.Debug, "Retry WebSocket connection of blockHeight: ${e.message}")
                    subscribeBlock(client, result, fail)
                    Observable.empty()
                }
                .subscribe { result(it) }
        // do not unsubscribe
    }

    private fun notifyToConversation(block: Block, version: Version) {
        synchronized(this@NemClientController) {
            if (version == Version.Main) {
                if (mainHeight >= block.height) {
                    return
                }
                mainHeight = block.height
            } else {
                if (testHeight >= block.height) {
                    return
                }
                testHeight = block.height
            }
        }

        block.transactions.forEach {
            val transfer = it.asTransfer ?: it.asMultisig?.otherTrans?.asTransfer
            if (transfer != null) {
                notifyToConversation(transfer, version)
            }
        }
    }
    private fun notifyToConversation(transaction: TransferTransaction, version: Version) {
        if (transaction.type != TransactionType.Transfer.rawValue) {
            return
        }
        val sender = AccountGenerator.calculateAddress(ConvertUtils.toByteArray(transaction.signer), version)
        val recipient = transaction.recipient
        val isXem = transaction.mosaics.isEmpty()

        subscriptions.values.forEach {
            if (it.contains(sender) || it.contains(recipient)) {
                val mosaicWithDivisibilities =
                        if (isXem) {
                            listOf(MosaicWithDivisibility("nem:xem", transaction.amount, 6))
                        }
                        else {
                            retrieveMosaicAndDivisibility(transaction.mosaics, version == Version.Test)
                        }
                it[sender]?.let { subscription ->
                    val name = subscription.name
                    subscription.result(if (name.isEmpty()) sender else name, transaction.message?.payload ?: "", mosaicWithDivisibilities, false)
                }
                it[recipient]?.let { subscription ->
                    val name = subscription.name
                    subscription.result(if (name.isEmpty()) sender else name, transaction.message?.payload ?: "", mosaicWithDivisibilities, true)
                }
            }
        }
    }

    fun subscribeTransaction(conversationId: String, address: String, name: String, result:(String, String, List<MosaicWithDivisibility>, Boolean) -> Unit) {
        val isTestNet = isTestNet(address)
        // search client with least connections.
        //val client = (if (isTestNet) testClients.minBy { it.subscribed } else mainClients.minBy { it.subscribed }) ?: return
        val client = selectClient(isTestNet)

        // Refresh subscription
        unsubscribeTransaction(conversationId, address)

        synchronized(this) {
            val addressMap = subscriptions[conversationId] ?: mutableMapOf()
            addressMap.put(address, Subscription(client, result, name))
            subscriptions.put(conversationId, addressMap)
        }
    }

    fun unsubscribeTransaction(conversationId: String, address: String) {
        // Check duplicate address
        synchronized(this) {
            val addressMap = subscriptions[conversationId] ?: return

            addressMap.remove(address)
            if (addressMap.isEmpty()) {
                subscriptions.remove(conversationId)
            }
        }
    }

    fun balance(conversationId: String, result:(String, List<MosaicWithDivisibility>) -> Unit, fail: (String) -> Unit) {
        subscriptions[conversationId]?.entries?.forEach {
            val address = it.key
            val name = it.value.name
            balanceWithAddress(address,
                    result =  {result(if (name.isEmpty()) address else name, it) },
                    fail = {fail(it)})
        }
    }

    fun listAddress(conversationId: String): List<String> =
            subscriptions[conversationId]?.entries?.map {
                val address = it.key
                val name = it.value.name
                if (name.isNotEmpty()) (name + " " + address) else address
            } ?: emptyList()

    private fun balanceWithAddress(address: String, result:(List<MosaicWithDivisibility>) -> Unit, fail:(String) -> Unit, retryCount: Int = 10) {
        val isTestNet = isTestNet(address)

        // search client with least connections.
        //val client = (if (isTestNet) testClients.minBy { it.subscribed } else mainClients.minBy { it.subscribed }) ?: return
        val client = selectClient(isTestNet)

        client.rx.accountMosaicOwned(address)
                .subscribeOn(Schedulers.io())
                .onErrorResumeNext { _: Throwable ->
                    if (retryCount > 0) {
                        balanceWithAddress(address, result, fail, retryCount - 1)
                    } else {
                        fail("$address\n残高取得失敗")
                    }
                    Observable.empty()
                }
                .subscribe { mosaics ->
                    // Get mosaic divisibility
                    try {
                        result(retrieveMosaicAndDivisibility(mosaics, isTestNet))
                    } catch (e: NetworkException) {
                        fail("$address\n残高取得失敗")
                    }
                }
    }


    private fun retrieveMosaicAndDivisibility(mosaics: List<Mosaic>, isTestNet: Boolean, retryCount: Int = 10) : List<MosaicWithDivisibility> {
        val mosaicWitDiv = mutableListOf<MosaicWithDivisibility>()
        val client = selectClient(isTestNet)
        mosaics.forEach {
            val mosaicFullName = "${it.mosaicId.namespaceId}:${it.mosaicId.name}"
            try {
                val divisibility = divisibilityMap[mosaicFullName] ?: {
                    val mosaicDefinition = client.rx.syncClient.namespaceMosaicDefinitionFromName(it.mosaicId.namespaceId, it.mosaicId.name)
                    val divisibility = mosaicDefinition?.mosaic?.divisibility
                    if (mosaicDefinition != null && divisibility != null) {
                        divisibilityMap.put(mosaicFullName, divisibility)
                        divisibility
                    } else {
                        0
                    }
                }()
                mosaicWitDiv.add(MosaicWithDivisibility(mosaicFullName, it.quantity, divisibility))
            } catch (e: NetworkException) {
                if (retryCount > 0) {
                    return retrieveMosaicAndDivisibility(mosaics, isTestNet, retryCount - 1)
                }
                else {
                    throw e
                }
            }
        }
        return mosaicWitDiv
    }

    private fun isTestNet(address: String): Boolean = when {
        address.startsWith("T") -> true
        address.startsWith("N") -> false
        else -> throw IllegalArgumentException("Invalid Address")
    }

    private fun selectClient(isTestNet: Boolean): Client =
            if (isTestNet) {
                testClients[SecureRandom().nextInt(testClients.size)]
            } else {
                mainClients[SecureRandom().nextInt(mainClients.size)]
            }
}