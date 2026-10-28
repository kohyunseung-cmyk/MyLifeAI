package com.mylifeai.phonebridge;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;

public final class BridgeActivity extends Activity {
    private static final int CREATE_DOC = 10, IMPORT_TEXT = 11, PICK_AUDIO = 12, OPEN_DOC = 13, PERMISSIONS = 20, MIC = 21;
    private static final int INSET = 24;
    private TextView status, feedback;
    private Button toggle;
    private SpeechRecognizer speech;
    private boolean recognizing;
    private boolean busy;
    private String importKind = "memo_import";
    private final DateTimeFormatter clock = DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.systemDefault());

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(Color.rgb(244, 247, 248));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackgroundColor(Color.rgb(244, 247, 248));
        body.setPadding(dp(INSET), dp(INSET), dp(INSET), dp(INSET));
        body.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(dp(INSET), dp(INSET) + insets.getSystemWindowInsetTop(), dp(INSET), dp(INSET) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        scroll.addView(body); setContentView(scroll);
        text(body, "업무 브리핑 연결", 28, true);
        text(body, "휴대폰의 업무 단서를 한곳에", 17, false);
        status = text(body, "", 16, false);
        feedback = text(body, "설정 후 ‘지금 갱신’으로 전달 문서를 저장해 주세요.", 15, false);
        toggle = button(body, "수집 시작", this::toggleCollection);
        text(body, "1 · 휴대폰 접근", 21, true);
        button(body, "카카오톡·문자 알림 연결", () -> new AlertDialog.Builder(this)
            .setTitle("알림 접근 허용")
            .setMessage("다음 화면에서 ‘업무 브리핑 연결’을 켜 주세요. 앱은 카카오톡·삼성 메시지·Google 메시지의 알림만 저장합니다. 대화방을 열거나 알림을 지우지 않습니다. 과거 전체 대화와 보낸 카카오톡은 포함되지 않을 수 있습니다.")
            .setPositiveButton("설정 열기", (d,w) -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)))
            .setNegativeButton("취소", null).show());
        button(body, "문자·통화 기록 권한 허용", () -> requestPermissions(new String[]{Manifest.permission.READ_SMS, Manifest.permission.READ_CALL_LOG}, PERMISSIONS));
        text(body, "최근 7일 문자·통화 기록을 읽습니다. 통화 기록에는 대화 음성이 없습니다. 휴대폰이 권한을 거부하면 그 자료는 수집하지 않습니다.", 15, false);
        text(body, "2 · 브리핑 전달", 21, true);
        button(body, "준비된 Drive 전달 파일 선택", () -> startActivityForResult(
            new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), OPEN_DOC));
        button(body, "Google Drive에 연결 파일 만들기", () -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE, BridgeRules.FILE_NAME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            new AlertDialog.Builder(this).setTitle("저장 위치 선택")
                .setMessage("파일 선택기의 왼쪽 메뉴에서 브리핑에 연결된 Google Drive 계정을 선택하세요. 휴대폰 저장소에 저장하면 클라우드 브리핑이 읽을 수 없습니다. 파일에는 수집한 메시지·통화 기록·공유한 전사문이 저장됩니다.")
                .setPositiveButton("파일 만들기", (d,w) -> startActivityForResult(intent, CREATE_DOC)).setNegativeButton("취소", null).show();
        });
        button(body, "지금 갱신", this::publish);
        text(body, "새 알림 후와 약 15분 간격으로 갱신을 요청합니다. 절전·네트워크·Drive 앱 상태에 따라 늦어질 수 있습니다. 처음 설정 후 Drive 앱의 업로드 상태를 확인해 주세요.", 15, false);
        button(body, "휴대폰 앱 설정 열기", () -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        text(body, "3 · 대화·음성 자료", 21, true);
        button(body, "카카오톡 대화 TXT 가져오기", () -> pickText("kakao_export"));
        button(body, "녹음 전사문·메모 TXT 가져오기", () -> pickText("voice_transcript_import"));
        button(body, "기기 내 음성 메모 받아쓰기", this::dictate);
        button(body, "받아쓰기 종료", () -> { if (speech != null && recognizing) speech.stopListening(); });
        button(body, "녹음파일 연결 · 내용 전사 대기", () -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), PICK_AUDIO));
        text(body, "받아쓰기는 기기 내 한국어 인식이 지원될 때만 동작합니다. 기존 녹음은 파일명·길이만 등록되며 내용은 자동 전사되지 않습니다. 녹음 앱에서 만든 전사문을 가져와야 브리핑에 대화 내용을 반영할 수 있습니다.", 15, false);
        button(body, "업무 메모 직접 추가", () -> editImport("memo_import", "업무 메모", ""));
        button(body, "휴대폰에 저장한 수집 자료 삭제", () -> new AlertDialog.Builder(this).setTitle("로컬 수집 자료 삭제")
            .setMessage("수집을 중지하고 앱 내부의 알림·가져온 문서를 삭제합니다. Drive에 이미 저장한 문서는 별도로 남습니다. 문자함·통화 기록·원본 녹음은 삭제하지 않습니다.")
            .setPositiveButton("삭제", (d,w) -> {
                BridgeStore.prefs(this).edit().putBoolean("enabled", false).apply(); SyncJob.stop(this);
                PhoneSync.IO.execute(() -> { BridgeStore.get(this).clear(); PhoneSync.publish(this); runOnUiThread(this::refresh); });
            }).setNegativeButton("취소", null).show());
        text(body, "수집 범위: 알림은 허용 이후에 관찰한 내용만 포함합니다. 인증번호로 보이는 메시지는 제외합니다. 수집 시작 후 가져온 문서도 선택한 전달 파일에 포함됩니다. 수집 중지 후에도 이미 전달된 파일은 Drive에 남습니다.", 14, false);
        refresh();
        if (Intent.ACTION_SEND.equals(getIntent().getAction())) handleShare(getIntent());
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private TextView text(LinearLayout parent, String value, int size, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(Color.rgb(25, 45, 54));
        if (bold) t.setTypeface(null, 1);
        t.setPadding(0, dp(12), 0, dp(10)); parent.addView(t); return t;
    }
    private Button button(LinearLayout parent, String title, Runnable action) {
        Button b = new Button(this); b.setText(title); b.setTextSize(16); b.setAllCaps(false); b.setMinHeight(dp(54));
        b.setTextColor(Color.WHITE); b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(23, 100, 112)));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, dp(5), 0, dp(5)); parent.addView(b,p);
        b.setOnClickListener(v -> action.run()); return b;
    }
    @Override public void onResume() { super.onResume(); if(status != null) refresh(); }
    private void refresh() {
        SharedPreferences p = BridgeStore.prefs(this);
        long last = p.getLong("last_write", 0);
        status.setText("수집: " + (BridgeStore.enabled(this) ? "켜짐" : "꺼짐")
            + "\n알림 접근: " + (PhoneSync.listenerGranted(this) ? "허용" : "미허용")
            + "  ·  문자: " + (PhoneSync.granted(this, Manifest.permission.READ_SMS) ? "허용" : "미허용")
            + "\n통화 기록: " + (PhoneSync.granted(this, Manifest.permission.READ_CALL_LOG) ? "허용" : "미허용")
            + "\n전달 파일: " + (p.getString("target_uri", "").isEmpty() ? "미설정" : "선택됨 · Drive 저장 위치 확인 필요")
            + "\n문서 갱신: " + (last == 0 ? "아직 없음" : clock.format(Instant.ofEpochMilli(last)) + " · " + p.getInt("last_count",0) + "건")
            + (p.getString("last_error", "").isEmpty() ? "" : "\n" + p.getString("last_error", "")));
        toggle.setText(BridgeStore.enabled(this) ? "수집 중지" : "수집 시작");
    }
    private void toggleCollection() {
        if (BridgeStore.enabled(this)) {
            BridgeStore.prefs(this).edit().putBoolean("enabled", false).apply(); SyncJob.stop(this);
            if(speech != null) { speech.cancel(); recognizing = false; }
            publish(); refresh(); return;
        }
        new AlertDialog.Builder(this).setTitle("업무 브리핑 자료 수집")
            .setMessage("허용한 카카오톡·문자 알림, 최근 7일 문자·통화 기록, 직접 가져온 대화·전사문·메모를 앱에 보관하고 선택한 Drive 문서에 전달합니다. 파일은 브리핑 분석에 사용됩니다. 휴대폰 권한은 다음 단계에서 별도로 허용하며, 언제든 수집을 중지할 수 있습니다.")
            .setPositiveButton("수집 시작", (d,w) -> {
                SharedPreferences p = BridgeStore.prefs(this);
                SharedPreferences.Editor e = p.edit().putBoolean("enabled", true);
                if (p.getString("started_at", "").isEmpty()) e.putString("started_at", Instant.now().toString());
                e.apply(); SyncJob.schedule(this); refresh(); publish();
            }).setNegativeButton("취소", null).show();
    }
    private void publish() {
        if(busy) return;
        busy = true; feedback.setText("전달 문서 갱신 중…");
        PhoneSync.IO.execute(() -> { String message = PhoneSync.publish(this); runOnUiThread(() -> { busy = false; feedback.setText(message); refresh(); }); });
    }
    private void pickText(String kind) {
        importKind = kind;
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/*"), IMPORT_TEXT);
    }
    private byte[] readBounded(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if(in == null) throw new java.io.IOException();
            byte[] b = new byte[8192]; int n;
            while((n=in.read(b)) != -1) { if(out.size() + n > 400000) throw new java.io.IOException("파일이 400KB를 넘습니다. 업무별로 나누어 가져와 주세요."); out.write(b,0,n); }
            return out.toByteArray();
        }
    }
    private String name(Uri uri) {
        try(Cursor rows = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null,null,null)) {
            return rows != null && rows.moveToFirst() ? rows.getString(0) : "공유 문서";
        } catch (Exception e) { return "공유 문서"; }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request,result,data);
        if(result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if(request == CREATE_DOC || request == OPEN_DOC) {
            try {
                if(request == OPEN_DOC) {
                    org.json.JSONObject initial = new org.json.JSONObject(BridgeRules.decodeText(readBounded(uri)));
                    if(!"phone-briefing/v1".equals(initial.optString("schema"))) {
                        feedback.setText("업무 브리핑 연결 전용 파일이 아닙니다. 준비된 전달 파일을 선택해 주세요."); return;
                    }
                }
                int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                if((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) throw new SecurityException();
                getContentResolver().takePersistableUriPermission(uri, flags);
                BridgeStore.prefs(this).edit().putString("target_uri", uri.toString()).apply();
                SyncJob.schedule(this); publish();
            } catch(Exception e) { feedback.setText("계속 쓸 수 있는 파일 권한을 받지 못했습니다. 다른 저장 위치를 선택해 주세요."); }
            refresh();
        } else if(request == IMPORT_TEXT) {
            final String kind = importKind;
            PhoneSync.IO.execute(() -> {
                try { String value = BridgeRules.decodeText(readBounded(uri)); String title=name(uri); runOnUiThread(() -> editImport(kind,title,value)); }
                catch(Exception e) { runOnUiThread(() -> feedback.setText("가져오기 실패. UTF-8/UTF-16 TXT, 최대 400KB를 지원합니다.")); }
            });
        } else if(request == PICK_AUDIO) {
            String title = name(uri);
            new AlertDialog.Builder(this).setTitle("녹음 원본 등록")
                .setMessage(title + "\n파일명만 브리핑 자료에 등록합니다. 녹음 내용은 전사되지 않습니다. 내용 반영에는 별도 전사문이 필요합니다.")
                .setPositiveButton("전사 대기로 등록", (d,w) -> saveImport("audio_pending",title,"음성 내용 미전사. 이 파일의 대화 내용을 추정하지 말 것."))
                .setNegativeButton("취소",null).show();
        }
    }
    private void handleShare(Intent intent) {
        CharSequence value = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if(value != null) {
            if(value.length()>400000) { feedback.setText("공유 텍스트가 너무 큽니다. 업무별로 나누어 주세요."); return; }
            editImport("shared_text", "공유한 대화·전사문", value.toString());
        } else {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if(uri != null) PhoneSync.IO.execute(() -> {
                try { String valueText = BridgeRules.decodeText(readBounded(uri)); String title=name(uri); runOnUiThread(() -> editImport("shared_text", title, valueText)); }
                catch(Exception e) { runOnUiThread(() -> feedback.setText("공유한 TXT 파일을 읽지 못했습니다.")); }
            });
        }
    }
    private void editImport(String kind, String title, String text) {
        if(!BridgeStore.enabled(this)) { feedback.setText("먼저 수집 시작을 눌러 주세요. 공유 자료는 아직 저장하지 않았습니다."); return; }
        EditText input = new EditText(this); input.setText(text); input.setMinLines(4); input.setMaxLines(12); input.setTextSize(16);
        input.setHint("상대방·실제 대화 날짜·업무 내용을 함께 적어 주세요.");
        new AlertDialog.Builder(this).setTitle(title).setView(input)
            .setMessage("내용을 확인한 뒤 브리핑 자료로 저장하세요. 음성 인식 오류·이름·날짜를 수정할 수 있습니다.")
            .setPositiveButton("자료에 추가", (d,w) -> saveImport(kind,title,input.getText().toString())).setNegativeButton("취소",null).show();
    }
    private void saveImport(String kind, String title, String value) {
        if(!BridgeStore.enabled(this)) { feedback.setText("수집이 꺼져 있습니다."); return; }
        if(value.trim().isEmpty()) return;
        if(value.length() > 400000) { feedback.setText("내용은 400KB 이내로 나누어 주세요."); return; }
        // Remove credential lines from multi-line exports without discarding the whole document.
        StringBuilder clean = new StringBuilder();
        for(String line:value.split("\\R", -1)) if(!BridgeRules.isCredential(line)) clean.append(line).append('\n');
        String body = clean.toString();
        PhoneSync.IO.execute(() -> {
            try {
                if(!BridgeStore.enabled(this)) return;
                String id=BridgeRules.hash(kind+"|"+title+"|"+body);
                long time=System.currentTimeMillis();
                BridgeStore.get(this).add(id,kind,time,BridgeStore.event(id,kind,time,title,body,
                    kind.equals("audio_pending") ? "metadata_only_not_transcribed" : "user_imported_text_original_dates_inside")
                    .put("importedAt",Instant.ofEpochMilli(time).toString()));
                String message=PhoneSync.publish(this);
                runOnUiThread(() -> { feedback.setText("자료 저장 완료\n"+message); refresh(); });
            } catch(Exception e) { runOnUiThread(() -> feedback.setText("자료 저장 실패")); }
        });
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request,permissions,grants); refresh();
        if(request==MIC) { if(PhoneSync.granted(this, Manifest.permission.RECORD_AUDIO)) dictate(); }
        else { feedback.setText("허용된 자료만 수집합니다. 거부된 권한은 위 상태에 미허용으로 표시됩니다."); publish(); }
    }
    private void dictate() {
        if(!BridgeStore.enabled(this)) { feedback.setText("먼저 수집 시작을 눌러 주세요."); return; }
        if(!PhoneSync.granted(this, Manifest.permission.RECORD_AUDIO)) { requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC); return; }
        if(Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            feedback.setText("이 휴대폰에서 기기 내 음성 인식을 사용할 수 없습니다. 녹음 앱의 전사문을 TXT로 공유해 주세요."); return;
        }
        if(recognizing) return;
        if(speech != null) speech.destroy();
        speech=SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
        speech.setRecognitionListener(new RecognitionListener() {
            public void onReadyForSpeech(Bundle p) { feedback.setText("듣고 있습니다. 짧은 업무 메모를 말해 주세요."); }
            public void onBeginningOfSpeech() { }
            public void onRmsChanged(float n) { }
            public void onBufferReceived(byte[] b) { }
            public void onEndOfSpeech() { feedback.setText("받아쓰기 정리 중…"); }
            public void onError(int error) { recognizing=false; feedback.setText("받아쓰기 실패("+error+"). 한국어 모델·마이크 상태를 확인해 주세요. 내용은 저장하지 않았습니다."); }
            public void onResults(Bundle results) {
                recognizing=false;
                ArrayList<String> values=results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if(values != null && !values.isEmpty() && BridgeStore.enabled(BridgeActivity.this)) editImport("voice_memo_transcript","음성 메모 · "+clock.format(Instant.now()),values.get(0));
            }
            public void onPartialResults(Bundle b) { }
            public void onEvent(int t,Bundle b) { }
        });
        Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE,"ko-KR").putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false);
        recognizing=true;
        try { speech.startListening(intent); } catch(Exception e) { recognizing=false; feedback.setText("기기 내 받아쓰기를 시작하지 못했습니다."); }
    }
    @Override protected void onPause() { if(speech != null && recognizing) { speech.cancel(); recognizing=false; } super.onPause(); }
    @Override public void onDestroy() { if(speech != null) speech.destroy(); super.onDestroy(); }
}
