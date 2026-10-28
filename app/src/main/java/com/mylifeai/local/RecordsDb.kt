package com.mylifeai.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordsDb(ctx: Context) : SQLiteOpenHelper(ctx, "my_life_ai.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE records(id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, title TEXT NOT NULL, detail TEXT NOT NULL, amount INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun add(type: String, title: String, detail: String = "", amount: Long = 0): Long =
        writableDatabase.insertOrThrow("records", null, ContentValues().apply {
            put("type", type); put("title", title); put("detail", detail)
            put("amount", amount); put("created_at", System.currentTimeMillis())
        })
    fun recent(limit: Int = 60): String {
        val out = StringBuilder()
        val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA)
        readableDatabase.query("records", arrayOf("type", "title", "detail", "amount", "created_at"),
            null, null, null, null, "created_at DESC", limit.toString()).use { cur ->
            while (cur.moveToNext()) {
                out.append("• [").append(cur.getString(0)).append("] ").append(cur.getString(1))
                val money = cur.getLong(3)
                if (money > 0) out.append(" / %,d원".format(money))
                out.append(" (").append(fmt.format(Date(cur.getLong(4)))).append(")\n")
                val detail = cur.getString(2)
                if (!detail.isNullOrBlank()) out.append("  ").append(detail.take(150)).append("\n")
            }
        }
        return if (out.isEmpty()) "아직 기록이 없습니다." else out.toString()
    }
    fun totalExpenses(): Long {
        readableDatabase.rawQuery("SELECT COALESCE(SUM(amount),0) FROM records WHERE type IN ('지출','영수증')", null).use {
            return if (it.moveToFirst()) it.getLong(0) else 0
        }
    }
}
