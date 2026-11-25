package com.myrecord.domain

import java.time.LocalDate

enum class Owner(val title: String) {
    Family("家庭"), Personal("个人")
}

enum class AccountType(val title: String, val cash: Boolean = false) {
    Cash("现金", true), Bank("储蓄卡", true), Credit("信用卡"),
    Wallet("虚拟账户"), InvestmentCash("投资账户资金"), Other("其他资金账户", true)
}

fun ledgerName(raw: String): String = raw.trim().also {
    require(it.length in 1..80) { "名称须为 1 至 80 个字符" }
}

fun parseOpening(raw: String): Long? {
    val value = raw.trim()
    if (value.startsWith("-")) return parseAmount(value.drop(1))?.let { -it }
    return parseAmount(value)
}

fun requireBusinessDate(day: LocalDate): LocalDate = day.also {
    require(it.year in 1..9999) { "年份须为 1 至 9999" }
}
