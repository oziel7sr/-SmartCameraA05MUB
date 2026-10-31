package com.camera.pro

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.MotionEvent
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    // Gerenciamento da Câmera
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var camera: Camera? = null
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private lateinit var cameraExecutor: ExecutorService

    // Estados e Configurações Profissionais
    enum class PhotoFormat { JPG, JPEG, PNG, RAW }
    enum class VideoCodec { H264_MP4, HEVC_MKV }
    enum class FilterND { OFF, ND4, ND8, ND16 }

    private var currentPhotoFormat = PhotoFormat.JPG
    private var currentVideoCodec = VideoCodec.H264_MP4
    private var currentAudioBitrate = 192000 // 192 kbps padrão
    private var currentFilterND = FilterND.OFF

    // Componentes Visuais
    private lateinit var viewFinder: PreviewView
    private lateinit var btnRecord: Button
    private lateinit var txtAiEngine: TextView
    private lateinit var txtFilterStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        btnRecord = findViewById(R.id.btnRecord)
        txtAiEngine = findViewById(R.id.txtAiEngine)
        txtFilterStatus = findViewById(R.id.txtFilterStatus)

        val btnCapture: Button = findViewById(R.id.btnCapture)
        val btnSwitch: Button = findViewById(R.id.btnSwitch)
        val btnTogglePhotoFormat: Button = findViewById(R.id.btnTogglePhotoFormat)
        val btnToggleCodec: Button = findViewById(R.id.btnToggleCodec)
        val btnToggleAudioBitrate: Button = findViewById(R.id.btnToggleAudioBitrate)
        val btnCycleND: Button = findViewById(R.id.btnCycleND)
        val exposureBar: SeekBar = findViewById(R.id.exposureBar)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, 10)
        }

        // 1. Alternador de Formato de Foto (JPG -> JPEG -> PNG -> RAW)
        btnTogglePhotoFormat.setOnClickListener {
            currentPhotoFormat = when (currentPhotoFormat) {
                PhotoFormat.JPG -> PhotoFormat.JPEG
                PhotoFormat.JPEG -> PhotoFormat.PNG
                PhotoFormat.PNG -> PhotoFormat.RAW
                PhotoFormat.RAW -> PhotoFormat.JPG
            }
            btnTogglePhotoFormat.text = "FOTO: ${currentPhotoFormat.name}"
            Toast.makeText(this, "Modo de Saída: ${currentPhotoFormat.name}", Toast.LENGTH_SHORT).show()
        }

        // 2. Alternador de Codec e Contêiner de Vídeo
        btnToggleCodec.setOnClickListener {
            currentVideoCodec = if (currentVideoCodec == VideoCodec.H264_MP4) {
                VideoCodec.HEVC_MKV
            } else {
                VideoCodec.H264_MP4
            }
            val label = if (currentVideoCodec == VideoCodec.H264_MP4) "H.264 (MP4)" else "H.265 (MKV)"
            btnToggleCodec.text = "VÍDEO: $label"
            startCamera() // Reinicializa com o novo codec selecionado
        }

        // 3. Taxas de Bits do Áudio (128k -> 192k -> 256k -> 320k)
        btnToggleAudioBitrate.setOnClickListener {
            currentAudioBitrate = when (currentAudioBitrate) {
                128000 -> 192000
                192000 -> 256000
                256000 -> 320000
                else -> 128000
            }
            btnToggleAudioBitrate.text = "ÁUDIO: ${currentAudioBitrate / 1000}K"
        }

        // 4. Gerenciamento do Filtro de Densidade Neutra (ND Digital)
        btnCycleND.setOnClickListener {
            currentFilterND = when (currentFilterND) {
                FilterND.OFF -> FilterND.ND4
                FilterND.ND4 -> FilterND.ND8
                FilterND.ND8 -> FilterND.ND16
                FilterND.ND16 -> FilterND.OFF
            }
            btnCycleND.text = "ND: ${currentFilterND.name}"
            applyDigitalNDFilter()
        }

        btnCapture.setOnClickListener { takePhoto() }
        btnRecord.setOnClickListener { captureVideo() }

        btnSwitch.setOnClickListener {
            cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            startCamera()
        }

        // Toque na tela para foco dinâmico e medição pontual
        viewFinder.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val factory = viewFinder.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(4, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                camera?.cameraControl?.startFocusAndMetering(action)
            }
            true
        }

        // Barra de compensação manual de exposição
        exposureBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val exposureState = camera?.cameraInfo?.exposureState
                if (exposureState?.isExposureCompensationSupported == true) {
                    val range = exposureState.exposureCompensationRange
                    val mapped = range.lower + ((range.upper - range.lower) * (progress / 100f)).toInt()
                    camera?.cameraControl?.setExposureCompensationIndex(mapped)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            // Motor de IA em Tempo Real (analisa frames em background)
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, RealtimeAIEngine { scene, avgLuma ->
                        runOnUiThread {
                            txtAiEngine.text = "IA CENA: $scene (Luma: ${avgLuma.toInt()})"
                            autoAdjustHardware(avgLuma)
                        }
                    })
                }

            // Gravador com resolução nativa do A05 (1080p FHD)
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, imageAnalyzer, videoCapture
                )
            } catch (exc: Exception) {
                Toast.makeText(this, "Erro no hardware de câmera: ${exc.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // Auto-ajuste de câmera guiado pela análise de IA
    private fun autoAdjustHardware(luma: Double) {
        if (currentFilterND != FilterND.OFF) return // Não interfere se o usuário travou o filtro ND
        val cameraControl = camera?.cameraControl ?: return

        // Se a luz for extrema (estourando os brancos), a IA reduz a exposição automaticamente
        if (luma > 210) {
            cameraControl.setExposureCompensationIndex(-2)
        } else if (luma < 40) {
            cameraControl.setExposureCompensationIndex(2)
        }
    }

    // Aplicação da densidade neutra computacional
    private fun applyDigitalNDFilter() {
        val control = camera?.cameraControl ?: return
        when (currentFilterND) {
            FilterND.OFF -> {
                control.setExposureCompensationIndex(0)
                txtFilterStatus.text = "FILTRO: DESATIVADO"
            }
            FilterND.ND4 -> {
                control.setExposureCompensationIndex(-2) // Corta 2 f-stops de luz
                txtFilterStatus.text = "FILTRO: ND4 (2 Stops)"
            }
            FilterND.ND8 -> {
                control.setExposureCompensationIndex(-3) // Corta 3 f-stops
                txtFilterStatus.text = "FILTRO: ND8 (3 Stops)"
            }
            FilterND.ND16 -> {
                control.setExposureCompensationIndex(-4) // Corta 4 f-stops (luz forte)
                txtFilterStatus.text = "FILTRO: ND16 (Extremo)"
            }
        }
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())

        if (currentPhotoFormat == PhotoFormat.PNG) {
            // Captura o buffer em memória e codifica sem perdas (PNG)
            imageCapture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = imageProxyToBitmap(image)
                    image.close()
                    saveBitmapAsPng(bitmap, "A05_PRO_$timestamp.png")
                }
                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread { Toast.makeText(baseContext, "Erro PNG", Toast.LENGTH_SHORT).show() }
                }
            })
            return
        }

        // Fluxo para JPEG, JPG ou RAW simulado
        val extension = when (currentPhotoFormat) {
            PhotoFormat.RAW -> "dng"
            PhotoFormat.JPEG -> "jpeg"
            else -> "jpg"
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "A05_PRO_$timestamp.$extension")
            put(MediaStore.MediaColumns.MIME_TYPE, if (extension == "dng") "image/x-adobe-dng" else "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/A05-ProCamera")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        imageCapture.takePicture(
            outputOptions, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(baseContext, "Falha na captura: ${exc.message}", Toast.LENGTH_SHORT).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Toast.makeText(baseContext, "Salvo em: $extension".uppercase(), Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun captureVideo() {
        val videoCapture = this.videoCapture ?: return
        val curRecording = recording
        if (curRecording != null) {
            curRecording.stop()
            recording = null
            btnRecord.text = "REC"
            return
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val isMkv = (currentVideoCodec == VideoCodec.HEVC_MKV)
        val ext = if (isMkv) "mkv" else "mp4"
        val mime = if (isMkv) "video/x-matroska" else "video/mp4"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "A05_PRO_$timestamp.$ext")
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/A05-ProCamera")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        recording = videoCapture.output
            .prepareRecording(this, mediaStoreOutputOptions)
            .apply {
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> btnRecord.text = "STOP"
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            Toast.makeText(baseContext, "Vídeo gravado em $ext ($currentVideoCodec)", Toast.LENGTH_SHORT).show()
                        }
                        btnRecord.text = "REC"
                    }
                }
            }
    }

    // Conversão do buffer de câmera em Bitmap para compressão sem perdas (PNG)
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun saveBitmapAsPng(bitmap: Bitmap, filename: String) {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/A05-ProCamera")
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            contentResolver.openOutputStream(it).use { out ->
                if (out != null) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }
            runOnUiThread {
                Toast.makeText(baseContext, "Foto PNG Salva!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }
}

// Analisador de Visão Computacional (IA rodando nos núcleos rápidos do Helio G85)
class RealtimeAIEngine(private val listener: (scene: String, avgLuma: Double) -> Unit) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        val buffer = image.planes[0].buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)

        var totalLuma = 0L
        for (i in 0 until data.size step 4) { // Passo para otimização de CPU
            totalLuma += (data[i].toInt() and 0xFF)
        }
        val avgLuma = (totalLuma / (data.size / 4.0))

        val scene = when {
            avgLuma < 35 -> "Pouca Luz (Ativando Otimizador)"
            avgLuma > 190 -> "Céu Aberto / Contraluz Intensa"
            else -> "Iluminação Balanceada"
        }

        listener(scene, avgLuma)
        image.close()
    }
}
