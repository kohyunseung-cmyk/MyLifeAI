package com.mylifeai.local

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.os.StatFs
import android.provider.CalendarContract
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var ai: LocalAi
    private lateinit var db: RecordsDb
    private lateinit var resultText: TextView
    private lateinit var actionArea: LinearLayout
    private var photoMode = "food"
    private var photoUri: Uri? = null
    private var lastFoodResult = ""

    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) copyModel(uri)
    }
    private val gallery = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) analyzePhoto(uri)
    }
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) photoUri?.let { analyzePhoto(it) } else show("촬영이 취소됐습니다.")
    }
    private val scanResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { item ->
        val code = item.data?.getStringExtra("barcode")
        if (!code.isNullOrBlank()) show("최근 스캔 바코드: " + code)
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { yes ->
        if (yes) startBarcode() else show("카메라 접근 권한을 허용해야 합니다.")
    }
    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { item ->
        val spoken = item.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            show("말씀한 내용: " + spoken)
            actions()
            addAction("AI로 명령 분석") {
                promptAi("다음 음성 문장을 일정/할일/소비/메모 중 하나로 분류하고 구체적 제안을 작성하세요. 날짜가 불명확하면 반드시 확인을 요청하세요.\n" + spoken)
            }
            addAction("메모 저장") { db.add("메모", spoken); show("음성 메모가 저장됐습니다.") }
            addAction("캘린더에 입력") { openCalendar(spoken) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ai = LocalAi(applicationContext)
        db = RecordsDb(this)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(25))
            setBackgroundColor(Color.rgb(248, 250, 252))
        }
        scroll.addView(root)
        setContentView(scroll)
        root.addView(label("My Life AI", 32, 15, 23, 42))
        root.addView(label("무료 · 기기 내 AI · 생활 자동화", 17, 71, 85, 105))
        root.addView(label(if (ai.isModelReady()) "Gemma 4 E4B 연결됨" else "Gemma 4 E4B 연결 전: 바코드·OCR·기록 기능은 사용 가능", 16, 71, 85, 105))
        root.addView(label("AI · 카메라", 24, 15, 23, 42))
        root.addView(button("Gemma 4 E4B 모델 파일 연결") { modelPicker.launch(arrayOf("*/*")) })
        root.addView(button("카메라 바코드 연속 스캔") {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                startBarcode()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        })
        root.addView(button("음식 사진 분석") { selectPhoto("food") })
        root.addView(button("영수증 자동 인식") { selectPhoto("receipt") })
        root.addView(button("음성 생활비서") { startSpeech() })
        root.addView(label("일정 · 돈 · 기록", 24, 15, 23, 42))
        root.addView(button("캘린더 일정 만들기") { openCalendar("") })
        root.addView(button("지출 직접 입력") { expenseDialog("지출", "", "") })
        root.addView(button("내 생활기록 보기") {
            show("누적 지출: %,d원\n\n%s".format(db.totalExpenses(), db.recent()))
            actions()
        })
        root.addView(button("Gemma AI에게 질문") { askQuestion() })
        root.addView(label("AI 분석 및 실행 결과", 24, 15, 23, 42))
        resultText = TextView(this).apply {
            text = "위 기능을 누르면 분석 결과가 표시됩니다.\n바코드·영수증 OCR·지출기록은 모델이 없어도 사용할 수 있습니다."
            textSize = 19f
            setTextColor(Color.rgb(30, 41, 59))
            setBackgroundColor(Color.WHITE)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setTextIsSelectable(true)
        }
        root.addView(resultText)
        actionArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(actionArea)
        root.addView(label("개인 기록은 휴대폰에 저장됩니다. 상품 조회·웹 검색은 인터넷을 사용할 수 있고, 기본 음성 인식 기능도 기기 설정에 따라 온라인 연결이 필요할 수 있습니다.", 15, 100, 116, 139))
    }

    private fun startBarcode() {
        scanResult.launch(Intent(this, ScannerActivity::class.java))
    }
    private fun selectPhoto(mode: String) {
        photoMode = mode
        AlertDialog.Builder(this).setTitle(if (mode == "food") "음식 사진" else "영수증 사진")
            .setItems(arrayOf("카메라 촬영", "앨범에서 선택")) { _, index ->
                if (index == 1) gallery.launch("image/*")
                else {
                    val folder = File(cacheDir, "camera").apply { mkdirs() }
                    val file = File.createTempFile("photo_", ".jpg", folder)
                    photoUri = FileProvider.getUriForFile(this, packageName + ".fileprovider", file)
                    camera.launch(checkNotNull(photoUri))
                }
            }.show()
    }
    private fun analyzePhoto(uri: Uri) {
        val bitmap = runCatching {
            val src = ImageDecoder.createSource(contentResolver, uri)
            ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val width = info.size.width
                val height = info.size.height
                val scale = minOf(1f, 1200f / maxOf(width, height))
                decoder.setTargetSize((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
            }
        }.getOrElse { show("사진을 열지 못했습니다: " + it.message); return }
        if (photoMode == "food") {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            promptAi("사진 속 음식을 한국어로 설명하고 음식별 예상 분량, 칼로리, 단백질을 추정 범위로 정리하세요. 불확실한 부분을 확정하지 마세요.", out.toByteArray()) { answer ->
                lastFoodResult = answer
                actions()
                addAction("식단 기록으로 저장") {
                    db.add("식단", "음식 사진", lastFoodResult)
                    show("식단 저장 완료\n\n" + lastFoodResult)
                }
            }
        } else {
            show("영수증 문자를 인식하고 있습니다...")
            val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { item ->
                    val raw = item.text
                    if (raw.isBlank()) {
                        show("영수증 문자를 찾지 못했습니다. 더 선명하게 찍어주세요.")
                    } else {
                        show("영수증 인식 결과\n\n" + raw)
                        actions()
                        addAction("품목과 총액 AI 분석") {
                            promptAi("아래 영수증 문자에서 상호명/날짜/품목/최종결제금액만 추출하고 불명확한 수치는 확인 필요라고 적으세요.\n" + raw)
                        }
                        addAction("금액 확인 후 지출 저장") {
                            val guess = ReceiptAmountParser.extractCandidate(raw)?.toString() ?: ""
                            expenseDialog("영수증", raw, guess)
                        }
                    }
                }.addOnFailureListener { show("영수증 OCR 실패: " + it.message) }
                .addOnCompleteListener { recognizer.close() }
        }
    }

    private fun promptAi(text: String, image: ByteArray? = null, after: ((String) -> Unit)? = null) {
        if (!ai.isModelReady()) {
            show("Gemma 4 E4B .litertlm 파일을 먼저 연결해야 AI 분석을 할 수 있습니다.")
            return
        }
        show("Gemma 4 E4B가 휴대폰 안에서 분석하고 있습니다...")
        actions()
        ai.ask(text, image) { answer ->
            runOnUiThread {
                answer.fold(
                    onSuccess = { show(it); after?.invoke(it) },
                    onFailure = { show("기기 내 AI 실행 실패: " + it.message) }
                )
            }
        }
    }
    private fun askQuestion() {
        val box = EditText(this).apply {
            hint = "예: 오늘 지출을 줄일 방법은?"
            textSize = 19f
        }
        AlertDialog.Builder(this).setTitle("Gemma에게 질문").setView(box)
            .setPositiveButton("질문") { _, _ ->
                val query = box.text.toString().trim()
                if (query.isNotBlank()) promptAi(query)
            }.setNegativeButton("취소", null).show()
    }
    private fun expenseDialog(initial: String, detail: String, guess: String) {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), 0) }
        val name = EditText(this).apply { hint = "지출명"; setText(initial); textSize = 19f }
        val amount = EditText(this).apply {
            hint = "결제금액 (원)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(guess)
            textSize = 19f
        }
        column.addView(name); column.addView(amount)
        AlertDialog.Builder(this).setTitle("금액을 확인하고 저장").setView(column)
            .setPositiveButton("저장") { _, _ ->
                val num = amount.text.toString().toLongOrNull()
                if (num == null || num < 0 || name.text.isBlank()) show("금액 및 지출명을 확인해주세요. 저장하지 않았습니다.")
                else {
                    db.add(if (detail.isEmpty()) "지출" else "영수증", name.text.toString(), detail, num)
                    show("기록 저장: " + name.text + " / %,d원".format(num))
                    actions()
                }
            }.setNegativeButton("취소", null).show()
    }
    private fun openCalendar(title: String) {
        val start = System.currentTimeMillis() + 3600000L
        val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + 3600000L)
        runCatching { startActivity(intent) }.onFailure { show("캘린더를 열지 못했습니다: " + it.message) }
    }
    private fun startSpeech() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREAN.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "할 일·일정·메모를 말씀해주세요.")
        }
        runCatching { speech.launch(intent) }.onFailure { show("음성 인식 앱이 없습니다: " + it.message) }
    }
    private fun copyModel(uri: Uri) {
        val name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) cur.getString(0) else ""
            }
        }.getOrNull() ?: ""
        if (!name.endsWith(".litertlm", true)) { show("LiteRT-LM .litertlm 모델을 선택해주세요."); return }
        show("대용량 모델 복사 중입니다. 앱을 종료하지 마세요.")
        Thread {
            val temp = File(filesDir, "gemma-4-E4B-it.litertlm.part")
            val destination = ai.file
            val result = runCatching {
                if (StatFs(filesDir.absolutePath).availableBytes < 3_900_000_000L)
                    error("저장공간을 약 4GB 이상 확보해주세요.")
                contentResolver.openInputStream(uri)?.use { src ->
                    FileOutputStream(temp).use { dst -> src.copyTo(dst, 1024 * 1024) }
                } ?: error("선택한 모델 파일을 읽을 수 없습니다.")
                check(temp.length() > 2_000_000_000L) { "올바른 Gemma E4B 모델인지 확인해주세요." }
                if (destination.exists() && !destination.delete()) error("기존 모델을 교체할 수 없습니다.")
                check(temp.renameTo(destination)) { "모델 파일 저장에 실패했습니다." }
                ai.reset()
                "Gemma 4 E4B 연결 완료 (" + (destination.length() / 1_000_000L) + "MB)"
            }
            if (result.isFailure) temp.delete()
            runOnUiThread { show(result.getOrElse { "모델 연결 실패: " + it.message }) }
        }.start()
    }
    private fun show(message: String) { resultText.text = message }
    private fun actions() { actionArea.removeAllViews() }
    private fun addAction(title: String, run: () -> Unit) { actionArea.addView(button(title, run)) }
    private fun button(title: String, run: () -> Unit): Button = Button(this).apply {
        text = title
        textSize = 19f
        isAllCaps = false
        setOnClickListener { run() }
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }
    private fun label(title: String, size: Int, r: Int, g: Int, b: Int): TextView = TextView(this).apply {
        text = title
        textSize = size.toFloat()
        setTextColor(Color.rgb(r, g, b))
        setPadding(0, dp(14), 0, dp(10))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onDestroy() {
        ai.close()
        db.close()
        super.onDestroy()
    }
}
