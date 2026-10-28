package com.mylifeai.local

object ReceiptAmountParser {
    private val labels = listOf(
        "실결제금액", "최종결제금액", "결제금액", "결제 금액", "카드승인금액", "승인금액",
        "총 결제금액", "총결제", "받을금액", "합계금액", "총액", "합계", "TOTAL", "AMOUNT"
    )
    fun extractCandidate(text: String): Long? {
        val normal = text.replace('￦', '₩').replace('\u00a0', ' ')
        for (label in labels) {
            val regex = Regex(Regex.escape(label) + "(?:\\s|[:：=₩\\\\-]){0,28}([0-9]{1,3}(?:,[0-9]{3})+|[0-9]{3,9})(?:\\s*원)?", RegexOption.IGNORE_CASE)
            val values = regex.findAll(normal).mapNotNull { it.groupValues[1].replace(",", "").toLongOrNull() }.filter { it in 1L..100_000_000L }.toList()
            if (values.isNotEmpty()) return values.last()
        }
        return null
    }
}
