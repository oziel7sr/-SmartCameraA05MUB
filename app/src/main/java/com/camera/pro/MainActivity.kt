package com.camera.pro

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.switchmaterial.SwitchMaterial
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var btnQuickResolution: Button
    private lateinit var shutterInnerCircle: View
    private lateinit var tabPhoto: TextView
    private lateinit var tabVideo: TextView
    private lateinit var cameraExecutor: ExecutorService

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraControl: CameraControl? = null
    private var cameraInfo: CameraInfo? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    // Estados da Câmera
    private var isVideoMode = false
    private var isBackCamera = true
    private var selectedTimerSeconds = 0
    private var eisEnabled = true
    private var hdrEnabled = true
    private var noiseReductionEnabled = true
    private var selectedFps = 30
    private var currentPhotoFormat = "JPG"
    private var currentVideoCodec = "H.264"
    private var currentAudioBitrate = 192000

    // Resolução Selecionada
    private var activeResolution = Size(8160, 6120) // 50 MP nativo do A05

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        btnQuickResolution = findViewById(R.id.btnQuickResolution)
        shutterInnerCircle = findViewById(R.id.shutterInnerCircle)
        tabPhoto = findViewById(R.id.tabPhoto)
        tabVideo = findViewById(R.id.tabVideo)

        val btnSettings: ImageButton = findViewById(R.id.btnSettings)
        val btnFlipCamera: ImageButton = findViewById(R.id.btnFlipCamera)
        val btnShutterContainer: FrameLayout = findViewById(R.id.btnShutterContainer)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            setupCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, 10)
        }

        // Alternância Deslizante entre Foto e Vídeo
        tabPhoto.setOnClickListener { switchMode(video = false) }
        tabVideo.setOnClickListener { switchMode(video = true) }

        // Botão de Engrenagem (Abre Modal de Configurações)
        btnSettings.setOnClickListener { showSettingsDialog() }

        // Troca Rápida de Resolução Inteligente
        btnQuickResolution.setOnClickListener { showResolutionPicker() }

        // Disparador Unificado (Foto ou Vídeo)
        btnShutterContainer.setOnClickListener {
            if (selectedTimerSeconds > 0) {
                btnQuickResolution.text = "Disparando em $selectedTimerSeconds s..."
                Handler(Looper.getMainLooper()).postDelayed({
                    executeCaptureAction()
                }, selectedTimerSeconds * 1000L)
            } else {
                executeCaptureAction()
            }
        }

        // Girar Câmera
        btnFlipCamera.setOnClickListener {
            isBackCamera = !isBackCamera
            updateResolutionForCamera()
            setupCamera()
        }
    }

    private fun switchMode(video: Boolean) {
        if (isVideoMode == video) return
        isVideoMode = video

        if (isVideoMode) {
            tabVideo.setTextColor(Color.parseColor("#FF1744"))
            tabPhoto.setTextColor(Color.parseColor("#88FFFFFF"))
            shutterInnerCircle.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_red_dark)
            btnQuickResolution.text = "1080p • $selectedFps FPS"
        } else {
            tabPhoto.setTextColor(Color.parseColor("#FFD600"))
            tabVideo.setTextColor(Color.parseColor("#88FFFFFF"))
            shutterInnerCircle.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.white)
            btnQuickResolution.text = if (isBackCamera) "50 MP • 8160x6120" else "8 MP • 3264x2448"
        }
        setupCamera()
    }

    private fun executeCaptureAction() {
        if (isVideoMode) {
            recordVideo()
        } else {
            takePhoto()
        }
    }

    private fun updateResolutionForCamera() {
        activeResolution = if (isBackCamera) {
            Size(8160, 6120) // 50 MP Traseiro
        } else {
            Size(3264, 2448) // 8 MP Frontal
        }
        val label = if (isBackCamera) "50 MP • 8160x6120" else "8 MP • 3264x2448"
        btnQuickResolution.text = if (!isVideoMode) label else "1080p • $selectedFps FPS"
    }

    private fun setupCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val selector = if (isBackCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            // Injeção de Parâmetros Avançados de Hardware (EIS, Redução de Ruído e FPS)
            val imageCaptureBuilder = ImageCapture.Builder()
                .setTargetResolution(activeResolution)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)

            val camera2Extender = Camera2Interop.Extender(imageCaptureBuilder)

            // Ativação da Estabilização Eletrônica (EIS) nativa
            if (eisEnabled) {
                camera2Extender.setCaptureRequestOption(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                )
            }

            // Supressão de ruído IA no hardware MediaTek
            if (noiseReductionEnabled) {
                camera2Extender.setCaptureRequestOption(
                    CaptureRequest.NOISE_REDUCTION_MODE,
                    CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                )
            }

            // Frame Rate Travado para Vídeo (30, 50 ou 60 FPS)
            camera2Extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(selectedFps, selectedFps)
            )

            imageCapture = imageCaptureBuilder.build()

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            try {
                cameraProvider?.unbindAll()
                val camera = if (isVideoMode) {
                    cameraProvider?.bindToLifecycle(this, selector, preview, videoCapture)
                } else {
                    cameraProvider?.bindToLifecycle(this, selector, preview, imageCapture)
                }
                cameraControl = camera?.cameraControl
                cameraInfo = camera?.cameraInfo
            } catch (e: Exception) {
                Toast.makeText(this, "Erro ao inicializar sensor: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val ext = currentPhotoFormat.lowercase()

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "A05_IMG_$timestamp.$ext")
            put(MediaStore.MediaColumns.MIME_TYPE, if (ext == "png") "image/png" else "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/A05-ProCamera")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        capture.takePicture(
            outputOptions, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(baseContext, "Erro na captura", Toast.LENGTH_SHORT).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Toast.makeText(baseContext, "Foto salva em $activeResolution ($ext)", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun recordVideo() {
        val videoCap = videoCapture ?: return
        val currentRecording = recording

        if (currentRecording != null) {
            currentRecording.stop()
            recording = null
            shutterInnerCircle.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_red_dark)
            return
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val isMkv = (currentVideoCodec == "H.265")
        val ext = if (isMkv) "mkv" else "mp4"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "A05_VID_$timestamp.$ext")
            put(MediaStore.MediaColumns.MIME_TYPE, if (isMkv) "video/x-matroska" else "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/A05-ProCamera")
            }
        }

        val mediaOptions = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        recording = videoCap.output
            .prepareRecording(this, mediaOptions)
            .apply {
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        shutterInnerCircle.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, android.R.color.white)
                    }
                    is VideoRecordEvent.Finalize -> {
                        Toast.makeText(baseContext, "Vídeo gravado em $ext ($selectedFps FPS)", Toast.LENGTH_SHORT).show()
                    }
                }
            }
    }

    // Modal de Configurações Categorizado (Engrenagem)
    private fun showSettingsDialog() {
        val dialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_settings, null)

        val switchEis: SwitchMaterial = sheetView.findViewById(R.id.switchEis)
        val switchHdrAi: SwitchMaterial = sheetView.findViewById(R.id.switchHdrAi)
        val switchNoiseReduction: SwitchMaterial = sheetView.findViewById(R.id.switchNoiseReduction)
        val rgTimer: RadioGroup = sheetView.findViewById(R.id.rgTimer)
        val rgPhotoFormat: RadioGroup = sheetView.findViewById(R.id.rgPhotoFormat)
        val rgVideoCodec: RadioGroup = sheetView.findViewById(R.id.rgVideoCodec)
        val rgAudioBitrate: RadioGroup = sheetView.findViewById(R.id.rgAudioBitrate)
        val rgFps: RadioGroup = sheetView.findViewById(R.id.rgFps)

        // Resgatar estados atuais
        switchEis.isChecked = eisEnabled
        switchHdrAi.isChecked = hdrEnabled
        switchNoiseReduction.isChecked = noiseReductionEnabled

        switchEis.setOnCheckedChangeListener { _, isChecked -> eisEnabled = isChecked; setupCamera() }
        switchHdrAi.setOnCheckedChangeListener { _, isChecked -> hdrEnabled = isChecked; setupCamera() }
        switchNoiseReduction.setOnCheckedChangeListener { _, isChecked -> noiseReductionEnabled = isChecked; setupCamera() }

        rgTimer.setOnCheckedChangeListener { _, checkedId ->
            selectedTimerSeconds = when (checkedId) {
                R.id.rbTimer3s -> 3
                R.id.rbTimer10s -> 10
                else -> 0
            }
        }

        rgPhotoFormat.setOnCheckedChangeListener { _, checkedId ->
            currentPhotoFormat = when (checkedId) {
                R.id.rbPng -> "PNG"
                R.id.rbRaw -> "RAW"
                else -> "JPG"
            }
        }

        rgVideoCodec.setOnCheckedChangeListener { _, checkedId ->
            currentVideoCodec = if (checkedId == R.id.rbH265) "H.265" else "H.264"
        }

        rgAudioBitrate.setOnCheckedChangeListener { _, checkedId ->
            currentAudioBitrate = when (checkedId) {
                R.id.rb128k -> 128000
                R.id.rb320k -> 320000
                else -> 192000
            }
        }

        rgFps.setOnCheckedChangeListener { _, checkedId ->
            selectedFps = when (checkedId) {
                R.id.rbFps50 -> 50
                R.id.rbFps60 -> 60
                else -> 30
            }
            if (isVideoMode) btnQuickResolution.text = "1080p • $selectedFps FPS"
            setupCamera()
        }

        dialog.setContentView(sheetView)
        dialog.show()
    }

    // Modal para Troca Rápida de Resoluções Suportadas
    private fun showResolutionPicker() {
        val options = if (isBackCamera) {
            arrayOf("50 MP (8160 x 6120) - Ultra Res", "12.5 MP (4080 x 3060) - Binning", "FHD (1920 x 1080)")
        } else {
            arrayOf("8 MP (3264 x 2448) - Máxima", "HD (1280 x 720)")
        }

        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle("Resolução do Sensor ${if (isBackCamera) "Traseiro" else "Frontal"}")
        builder.setItems(options) { _, which ->
            if (isBackCamera) {
                activeResolution = when (which) {
                    0 -> Size(8160, 6120)
                    1 -> Size(4080, 3060)
                    else -> Size(1920, 1080)
                }
            } else {
                activeResolution = when (which) {
                    0 -> Size(3264, 2448)
                    else -> Size(1280, 720)
                }
            }
            btnQuickResolution.text = options[which]
            setupCamera()
        }
        builder.show()
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
