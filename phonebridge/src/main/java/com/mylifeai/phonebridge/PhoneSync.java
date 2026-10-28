package com.mylifeai.phonebridge;

import android.Manifest;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog;
import android.provider.Telephony;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

public final class PhoneSync {
    public static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final int MAX_ROWS = 1000;
    private static final int MAX_CHARS = 900000;
    private PhoneSync() {}
    public static boolean granted(Context c, String permission) { return c.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }
    public static boolean listenerGranted(Context c) {
        NotificationManager m = c.getSystemService(NotificationManager.class);
        return m != null && m.isNotificationListenerAccessGranted(new ComponentName(c, MessageListener.class));
    }
    public static synchronized String publish(Context context) {
        Context c = context.getApplicationContext();
        SharedPreferences prefs = BridgeStore.prefs(c);
        String target = prefs.getString("target_uri", "");
        if (target.isEmpty()) return "Drive 전달 파일을 먼저 선택해 주세요.";
        try {
            long now = System.currentTimeMillis();
            boolean enabled = BridgeStore.enabled(c);
            JSONArray events = new JSONArray();
            JSONObject sources = new JSONObject();
            sources.put("kakao", new JSONObject().put("status", !enabled ? "paused" : listenerGranted(c) ? "notification_only" : "permission_missing")
                .put("history", "not_available_without_import").put("outgoing", "not_guaranteed"));
            if (enabled) {
                JSONArray stored = BridgeStore.get(c).recent(now, MAX_ROWS);
                for (int i = 0; i < stored.length(); i++) events.put(stored.get(i));
                collectSms(c, now, events, sources);
                collectCalls(c, now, events, sources);
            } else {
                sources.put("sms", new JSONObject().put("status", "paused"));
                sources.put("calls", new JSONObject().put("status", "paused"));
            }
            sources.put("voice", new JSONObject().put("status", enabled ? "manual_transcripts_and_voice_memos_only" : "paused")
                .put("audioFilesTranscribed", false).put("automaticCallRecording", false));
            sources.put("notifications", new JSONObject().put("status", enabled && listenerGranted(c) ? "permission_granted" : "inactive")
                .put("warning", prefs.getString("notification_error", "")));
            JSONArray bounded = new JSONArray();
            int budget = 0;
            int omitted = 0;
            for (int i = 0; i < events.length(); i++) {
                JSONObject item = events.getJSONObject(i);
                int size = item.toString().length();
                if (budget + size > MAX_CHARS) { omitted++; continue; }
                bounded.put(item); budget += size;
            }
            JSONObject root = new JSONObject().put("schema", "phone-briefing/v1")
                .put("generatedAt", Instant.ofEpochMilli(now).toString()).put("enabled", enabled)
                .put("windowStart", Instant.ofEpochMilli(now - BridgeRules.WINDOW_MS).toString())
                .put("collectionStartedAt", prefs.getString("started_at", ""))
                .put("sources", sources).put("events", bounded)
                .put("omittedForExportBudget", omitted)
                .put("storedEventsTruncated", enabled && BridgeStore.get(c).recentCount(now) > MAX_ROWS)
                .put("delivery", "written_to_user_selected_document_provider_cloud_visibility_unverified")
                .put("limitations", new JSONArray().put("No complete Kakao history or guaranteed outgoing messages.")
                    .put("Call metadata is not conversation content.").put("Voice recognition needs user review; imported audio is not transcribed.")
                    .put("SMS RCS/MMS bodies are not guaranteed; denied permissions remain unavailable.")
                    .put("Background scheduling and cloud-provider synchronization may be delayed."));
            // The stop switch takes effect before any new provider write starts.
            if (enabled && !BridgeStore.enabled(c)) return "수집이 중지되어 갱신을 취소했습니다.";
            byte[] data = root.toString(2).getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = c.getContentResolver().openOutputStream(Uri.parse(target), "wt")) {
                if (out == null) throw new java.io.IOException("문서 쓰기 불가");
                out.write(data); out.flush();
            }
            prefs.edit().putLong("last_write", now).putString("last_error", "").putInt("last_count", bounded.length()).apply();
            return "전달 문서 갱신: " + bounded.length() + "건\nDrive 서버 반영은 저장 앱의 동기화 상태에 따릅니다.";
        } catch (Exception e) {
            String message = "전달 문서 갱신 실패: " + e.getClass().getSimpleName() + ". 파일 접근권한·네트워크를 확인해 주세요.";
            prefs.edit().putString("last_error", message).apply();
            return message;
        }
    }
    private static void collectSms(Context c, long now, JSONArray items, JSONObject sources) throws Exception {
        JSONObject status = new JSONObject().put("status", granted(c, Manifest.permission.READ_SMS) ? "reading" : "permission_missing_notification_fallback")
            .put("coverage", "sms_provider_only_no_guaranteed_rcs_mms");
        sources.put("sms", status);
        if (!granted(c, Manifest.permission.READ_SMS)) return;
        int count = 0, credentialsSkipped = 0;
        try (Cursor rows = c.getContentResolver().query(Telephony.Sms.CONTENT_URI,
            new String[]{"_id", "address", "body", "date", "type"}, "date >= ?", new String[]{Long.toString(now - BridgeRules.WINDOW_MS)}, "date DESC")) {
            if (rows == null) { status.put("status", "provider_unavailable"); return; }
            while (rows.moveToNext() && count < MAX_ROWS) {
                String text = rows.getString(2);
                if (BridgeRules.isCredential(text)) { credentialsSkipped++; continue; }
                long time = rows.getLong(3);
                String id = "sms-" + rows.getString(0);
                String original = text == null ? "" : text;
                String limited = original.length() > 16000 ? original.substring(0, 16000) : original;
                items.put(BridgeStore.event(id, "sms", time, rows.getString(1), limited, "sms_provider")
                    .put("direction", rows.getInt(4) == 1 ? "received" : rows.getInt(4) == 2 ? "sent" : "draft_or_other")
                    .put("bodyTruncated", original.length() > limited.length()));
                count++;
            }
            status.put("status", "ok").put("count", count).put("rowLimitReached", count >= MAX_ROWS && !rows.isAfterLast())
                .put("credentialsSkipped", credentialsSkipped);
        } catch (Exception e) { status.put("status", "unavailable").put("errorType", e.getClass().getSimpleName()); }
    }
    private static void collectCalls(Context c, long now, JSONArray items, JSONObject sources) throws Exception {
        JSONObject status = new JSONObject().put("status", granted(c, Manifest.permission.READ_CALL_LOG) ? "reading" : "permission_missing")
            .put("coverage", "metadata_only_no_call_audio");
        sources.put("calls", status);
        if (!granted(c, Manifest.permission.READ_CALL_LOG)) return;
        int count = 0;
        try (Cursor rows = c.getContentResolver().query(CallLog.Calls.CONTENT_URI,
            new String[]{"_id", "number", "cached_name", "date", "duration", "type"}, "date >= ?", new String[]{Long.toString(now - BridgeRules.WINDOW_MS)}, "date DESC")) {
            if (rows == null) { status.put("status", "provider_unavailable"); return; }
            while (rows.moveToNext() && count < MAX_ROWS) {
                String name = rows.getString(2);
                String id = "call-" + rows.getString(0);
                items.put(BridgeStore.event(id, "call_log", rows.getLong(3), name == null || name.trim().isEmpty() ? rows.getString(1) : name, "", "metadata_only")
                    .put("number", rows.getString(1)).put("durationSeconds", rows.getLong(4)).put("direction", BridgeRules.callType(rows.getInt(5))));
                count++;
            }
            status.put("status", "ok").put("count", count).put("rowLimitReached", count >= MAX_ROWS && !rows.isAfterLast());
        } catch (Exception e) { status.put("status", "unavailable").put("errorType", e.getClass().getSimpleName()); }
    }
}
