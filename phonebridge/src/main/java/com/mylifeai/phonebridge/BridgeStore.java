package com.mylifeai.phonebridge;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;

public final class BridgeStore extends SQLiteOpenHelper {
    private static BridgeStore singleton;
    public static synchronized BridgeStore get(Context c) {
        if (singleton == null) singleton = new BridgeStore(c.getApplicationContext());
        return singleton;
    }
    private BridgeStore(Context c) { super(c, "phone-briefing.db", null, 1); }
    public static SharedPreferences prefs(Context c) { return c.getSharedPreferences("bridge", Context.MODE_PRIVATE); }
    public static boolean enabled(Context c) { return prefs(c).getBoolean("enabled", false); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE events (id TEXT PRIMARY KEY, source TEXT NOT NULL, time INTEGER NOT NULL, payload TEXT NOT NULL)");
        db.execSQL("CREATE INDEX time_index ON events(time)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }
    public synchronized boolean add(String id, String source, long time, JSONObject payload) {
        ContentValues row = new ContentValues();
        row.put("id", id); row.put("source", source); row.put("time", time); row.put("payload", payload.toString());
        return getWritableDatabase().insertWithOnConflict("events", null, row, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }
    public synchronized JSONArray recent(long now, int cap) throws Exception {
        getWritableDatabase().delete("events", "time < ?", new String[]{Long.toString(now - BridgeRules.WINDOW_MS)});
        JSONArray result = new JSONArray();
        try (Cursor rows = getReadableDatabase().query("events", new String[]{"payload"}, "time >= ?", new String[]{Long.toString(now - BridgeRules.WINDOW_MS)}, null, null, "time DESC", Integer.toString(cap))) {
            while (rows.moveToNext()) result.put(new JSONObject(rows.getString(0)));
        }
        return result;
    }
    public synchronized int recentCount(long now) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM events WHERE time >= ?", new String[]{Long.toString(now - BridgeRules.WINDOW_MS)})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }
    public synchronized void clear() { getWritableDatabase().delete("events", null, null); }
    public static JSONObject event(String id, String source, long time, String who, String text, String coverage) throws Exception {
        return new JSONObject().put("id", id).put("source", source)
            .put("occurredAt", Instant.ofEpochMilli(time).toString())
            .put("senderOrTitle", who == null ? "" : who)
            .put("text", text == null ? "" : text).put("coverage", coverage);
    }
}
