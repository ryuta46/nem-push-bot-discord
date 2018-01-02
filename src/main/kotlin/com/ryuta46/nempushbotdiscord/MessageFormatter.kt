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

import com.ryuta46.nemkotlin.util.ConvertUtils
import com.ryuta46.nempushbotdiscord.model.MosaicWithDivisibility

class MessageFormatter {


    companion object {
        fun transaction(label: String, message: String, mosaics: List<MosaicWithDivisibility>, isRecipient: Boolean): String =
                """XEM、モザイクを${if (isRecipient) "受け取りました" else "送りました"}
$label
${if (message.isEmpty()) "" else "メッセージ: ${String(ConvertUtils.toByteArray(message))}\n"}${listMosaics(mosaics)}
"""

        fun balance(label: String, mosaics: List<MosaicWithDivisibility>): String =
                """$label
保有XEM、モザイク一覧です
${listMosaics(mosaics)}
"""

        fun listAddress(addresses: List<String>) =
                """管理アドレス一覧です
${addresses.joinToString(separator = "\n") { it }}
"""

        fun listMosaics(mosaics: List<MosaicWithDivisibility>) =
                mosaics.joinToString(separator = "\n") { "${it.fullName}  ${formatAmount(it.quantity, it.divisibility)}" }

        fun usage() =
                """使い方
Bot に対して話しかけてください。npb. をつけるかわりに Bot をメンションしても反応します。

* npb.register WebhookUrl
  このチャンネルを登録します。最初に呼び出してください。
* npb.unregister
  このチャンネルの登録を解除します。
* npb.add アドレス (名前)
  アドレスを指定した名前で管理対象に追加します。
  名前は何でも OK です。このアドレスの出金、入金トランザクションが承認されると、Bot からメッセージが届きます。
* npb.remove アドレス
  アドレスを管理対象から削除します。
* npb.balance
  管理しているアドレスのXEM、モザイク残高を表示します。
* npb.list
  管理しているアドレスを一覧表示します。
"""

        fun formatAmount(quantity: Long, divisibility: Int): String {
            var quantityStr = quantity.toString()
            if (divisibility == 0) {
                return quantityStr
            }
            val need0 = divisibility - quantityStr.length + 1
            for (i in 0 until need0) quantityStr = "0" + quantityStr

            val decimal = quantityStr.substring(quantityStr.length - divisibility, quantityStr.length)
            val integer = quantityStr.dropLast(decimal.length)

            return "$integer.$decimal"
        }
    }
}
