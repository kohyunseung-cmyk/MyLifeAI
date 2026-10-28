package com.mylifeai.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiptAmountParserTest {
    @Test fun prioritizesPaymentOverSubtotal() = assertEquals(12300L,
        ReceiptAmountParser.extractCandidate("합계 15,000원\n할인 2,700원\n결제금액 12,300원"))
    @Test fun parsesKoreanReceipt() = assertEquals(42800L,
        ReceiptAmountParser.extractCandidate("영수증\n총액 : 42,800 원"))
    @Test fun parsesNewlineTotal() = assertEquals(19900L,
        ReceiptAmountParser.extractCandidate("TOTAL\n19,900"))
    @Test fun returnsNullForUnknown() = assertNull(ReceiptAmountParser.extractCandidate("상품 2개\n포인트 적립 50"))
    @Test fun preventsSuspiciousLargeValue() = assertNull(
        ReceiptAmountParser.extractCandidate("승인금액 999,999,999원"))
}
