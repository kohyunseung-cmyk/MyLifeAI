package com.mylifeai.local

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class ScannerActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val scanner = BarcodeScanning.getClient()
    private lateinit var status: TextView
    private var current = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(15, 23, 42))
        }
        val previewView = PreviewView(this)
        root.addView(previewView, LinearLayout.LayoutParams(-1, 0, 1f))
        status = TextView(this).apply {
            text = "카메라를 바코드에 비춰주세요."
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(24, 24, 24, 24)
        }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "네이버에서 상품 검색"
            textSize = 19f
            isAllCaps = false
            setOnClickListener {
                if (current.isNotEmpty()) {
                    val value = URLEncoder.encode(current, "UTF-8")
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://search.shopping.naver.com/search/all?query=" + value)))
                }
            }
        })
        root.addView(Button(this).apply {
            text = "스캔 종료"
            textSize = 19f
            isAllCaps = false
            setOnClickListener {
                setResult(Activity.RESULT_OK, Intent().putExtra("barcode", current))
                finish()
            }
        })
        setContentView(root)
        startScanning(previewView)
    }

    @OptIn(ExperimentalGetImage::class)
    private fun startScanning(view: PreviewView) {
        val pending = ProcessCameraProvider.getInstance(this)
        pending.addListener({
            val provider = runCatching { pending.get() }.getOrElse {
                status.text = "카메라 열기 실패: " + it.message
                return@addListener
            }
            val preview = Preview.Builder().build().apply { surfaceProvider = view.surfaceProvider }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(worker) { proxy ->
                val image = proxy.image
                if (image == null) { proxy.close(); return@setAnalyzer }
                scanner.process(InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees))
                    .addOnSuccessListener { found ->
                        val value = found.firstNotNullOfOrNull { it.rawValue?.takeIf(String::isNotBlank) }
                        if (value != null && value != current) {
                            current = value
                            status.text = "바코드: " + value + "\n상품 검색 중..."
                            searchFood(value)
                        }
                    }
                    .addOnFailureListener { status.text = "인식 실패: " + it.message }
                    .addOnCompleteListener { proxy.close() }
            }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.onFailure { status.text = "카메라 시작 실패: " + it.message }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun searchFood(value: String) {
        Thread {
            val result = try {
                val url = URL("https://world.openfoodfacts.org/api/v2/product/" + URLEncoder.encode(value, "UTF-8") + ".json?fields=product_name,brands,nutriments,quantity")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("User-Agent", "MyLifeAI-Local/0.1 (personal-use)")
                try {
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    if (json.optInt("status") == 1) {
                        val product = json.getJSONObject("product")
                        val kcal = product.optJSONObject("nutriments")?.optString("energy-kcal_100g") ?: "미등록"
                        product.optString("product_name", "상품명 미등록") + "\n" +
                        "브랜드: " + product.optString("brands", "미등록") + "\n" +
                        "열량(100g): " + kcal + "kcal\n" +
                        "등록 데이터이므로 정확도를 확인하세요."
                    } else "식품 DB에 없는 바코드입니다. 네이버 검색을 이용하세요."
                } finally { conn.disconnect() }
            } catch (e: Exception) { "상품 조회 실패. 네이버 검색을 이용하세요." }
            runOnUiThread { if (current == value) status.text = "바코드: " + value + "\n" + result }
        }.start()
    }

    override fun onDestroy() {
        worker.shutdown()
        scanner.close()
        super.onDestroy()
    }
}
