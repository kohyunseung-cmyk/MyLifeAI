package com.mylifeai.phonebridge;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class BridgeRulesTest {
    @Test public void onlyApprovedMessageAppsAreObserved() {
        assertEquals("kakao_notification", BridgeRules.sourceForPackage("com.kakao.talk"));
        assertEquals("sms_notification", BridgeRules.sourceForPackage("com.samsung.android.messaging"));
        assertEquals("sms_notification", BridgeRules.sourceForPackage("com.google.android.apps.messaging"));
        assertNull(BridgeRules.sourceForPackage("com.google.android.gm"));
        assertNull(BridgeRules.sourceForPackage("com.kakao.talk.fake"));
    }
    @Test public void authenticationCodesAreNotExported() {
        assertTrue(BridgeRules.isCredential("인증번호 [123456]를 입력하세요"));
        assertTrue(BridgeRules.isCredential("Your verification code is 987654"));
        assertTrue(BridgeRules.isCredential("123456 인증번호"));
        assertFalse(BridgeRules.isCredential("ISMS 인증 인터뷰는 11월 4일입니다"));
        assertFalse(BridgeRules.isCredential("평가 자료 123456건 집계"));
    }
    @Test public void exportedKoreanTextSupportsCommonBoms() {
        String text = "고현승, 다음 주 월요일 회신";
        assertEquals(text, BridgeRules.decodeText(text.getBytes(StandardCharsets.UTF_8)));
        assertEquals(text, BridgeRules.decodeText(("\ufeff"+text).getBytes(StandardCharsets.UTF_8)));
        byte[] content=text.getBytes(StandardCharsets.UTF_16LE), bytes=new byte[content.length+2];
        bytes[0]=(byte)0xff; bytes[1]=(byte)0xfe; System.arraycopy(content,0,bytes,2,content.length);
        assertEquals(text, BridgeRules.decodeText(bytes));
    }
    @Test public void updatesDeduplicateButDifferentSendersRemainDistinct() {
        String a=BridgeRules.hash("kakao|인사팀|홍길동|1234|오늘까지");
        assertEquals(a,BridgeRules.hash("kakao|인사팀|홍길동|1234|오늘까지"));
        assertNotEquals(a,BridgeRules.hash("kakao|인사팀|김철수|1234|오늘까지"));
        assertEquals(64,a.length());
    }
    @Test public void missedCallsAreNotSuccessfulConversations() {
        assertEquals("missed",BridgeRules.callType(3));
        assertEquals("rejected",BridgeRules.callType(5));
        assertEquals("incoming",BridgeRules.callType(1));
        assertEquals("other",BridgeRules.callType(999));
    }
}
