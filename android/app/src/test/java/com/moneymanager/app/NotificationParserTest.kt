package com.moneymanager.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationParserTest {
    @Test
    fun parsesWechatExpense() {
        val tx = NotificationParser.parse("微信支付 向瑞幸咖啡付款 ¥18.50 支付成功")!!
        assertEquals(18.50, tx.amount, 0.001)
        assertEquals("瑞幸咖啡", tx.merchant)
        assertEquals("expense", tx.type)
    }

    @Test
    fun parsesCiticExpense() {
        val tx = NotificationParser.parse(
            "中信银行 尾号1234账户发生消费人民币36.80元，商户：财付通",
            sourcePackage = "com.ecitic.bank.mobile"
        )!!
        assertEquals(36.80, tx.amount, 0.001)
        assertEquals("财付通", tx.merchant)
        assertEquals("expense", tx.type)
    }

    @Test
    fun parsesCiticIncome() {
        val tx = NotificationParser.parse(
            "中信银行 尾号1234账户入账人民币100.00元",
            sourcePackage = "com.ecitic.bank.mobile"
        )!!
        assertEquals("income", tx.type)
    }

    @Test
    fun rejectsCiticPromotion() {
        assertNull(NotificationParser.parse(
            "中信银行 交易有礼，最高可得88元",
            sourcePackage = "com.ecitic.bank.mobile"
        ))
    }

    @Test
    fun paymentScreenMustSaySuccess() {
        assertNull(NotificationParser.parse("红包金额 ¥20.00", requireSuccess = true))
        val tx = NotificationParser.parse("红包已发送 ¥20.00 给 小明", requireSuccess = true)!!
        assertEquals("小明", tx.merchant)
        assertTrue(tx.confidence >= 70)
    }
}
