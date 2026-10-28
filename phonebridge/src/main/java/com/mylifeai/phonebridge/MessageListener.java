package com.mylifeai.phonebridge;

import android.app.Notification;
import android.os.Bundle;
import android.os.Build;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.List;
import org.json.JSONObject;

/** Passive reader: never opens, cancels, replies to or marks notifications as seen. */
public final class MessageListener extends NotificationListenerService {
    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (!BridgeStore.enabled(this)) return;
        String source = BridgeRules.sourceForPackage(sbn.getPackageName());
        if (source == null || (sbn.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        try {
            Bundle e = sbn.getNotification().extras;
            CharSequence title = e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
            if (title == null) title = e.getCharSequence(Notification.EXTRA_TITLE, "");
            android.os.Parcelable[] bundledMessages = e.getParcelableArray(Notification.EXTRA_MESSAGES);
            List<Notification.MessagingStyle.Message> messages = Build.VERSION.SDK_INT >= 30 && bundledMessages != null
                ? Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundledMessages)
                : java.util.Collections.emptyList();
            boolean changed = false;
            if (messages != null && !messages.isEmpty()) {
                for (Notification.MessagingStyle.Message m : messages) {
                    String sender = m.getSenderPerson() == null ? "" : String.valueOf(m.getSenderPerson().getName());
                    changed |= save(source, String.valueOf(title), sender, m.getText(), m.getTimestamp() > 0 ? m.getTimestamp() : sbn.getPostTime());
                }
            } else {
                CharSequence body = e.getCharSequence(Notification.EXTRA_BIG_TEXT);
                if (body == null) body = e.getCharSequence(Notification.EXTRA_TEXT);
                changed = save(source, String.valueOf(title), "", body, sbn.getPostTime());
            }
            if (changed) SyncJob.requestSoon(this);
        } catch (Exception ignored) {
            BridgeStore.prefs(this).edit().putString("notification_error", "일부 알림을 해석하지 못했습니다.").apply();
        }
    }
    private boolean save(String source, String room, String sender, CharSequence body, long time) throws Exception {
        if (body == null || body.toString().trim().isEmpty() || BridgeRules.isCredential(body.toString())) return false;
        if (time < System.currentTimeMillis() - BridgeRules.WINDOW_MS) return false;
        String text = body.toString();
        String id = BridgeRules.hash(source + "|" + room + "|" + sender + "|" + time + "|" + text);
        JSONObject item = BridgeStore.event(id, source, time, sender.isEmpty() ? room : sender, text, "notification_only_incomplete_history").put("room", room).put("direction", "notification_observed");
        return BridgeStore.get(this).add(id, source, time, item);
    }
}
