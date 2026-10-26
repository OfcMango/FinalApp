package com.neotouch.speechbraille

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val requestCode = 77
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var speechRecognizer: SpeechRecognizer? = null
    private var listening = false
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var output: OutputStream? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val receivedText = StringBuilder()
    private lateinit var status: TextView
    private lateinit var recognized: TextView
    private lateinit var braille: TextView
    private lateinit var received: TextView
    private lateinit var connectButton: Button
    private lateinit var listenButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale.getDefault())
                ttsReady = true
            }
        }
        requestNeededPermissions()
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "Speech recognition unavailable. Enable a speech service on this phone."
            listenButton.isEnabled = false
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(0xFFF5F7FB.toInt())
        }
        fun label(text: String, size: Float, color: Int = 0xFF172033.toInt()): TextView = TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color); setPadding(0, 7, 0, 7)
        }
        root.addView(label("HaptiBraille Communicator", 26f))
        root.addView(label("Two-way prototype: speak normally to show Braille pins, or press the ESP32 Braille keys to receive text and hear it spoken.", 15f, 0xFF4B5563.toInt()))
        status = label("Not connected to ESP32", 15f, 0xFFB45309.toInt())
        root.addView(status)
        connectButton = Button(this).apply { text = "Connect to paired ESP32"; setOnClickListener { showPairedDevices() } }
        root.addView(connectButton, matchWrap())
        root.addView(label("MODE 1 · SPEECH → BRAILLE", 18f))
        listenButton = Button(this).apply { text = "Start listening"; setOnClickListener { if (listening) stopListening() else startListening() } }
        root.addView(listenButton, matchWrap())
        root.addView(label("Recognized speech", 16f))
        recognized = label("Your spoken sentence will appear here.", 16f)
        recognized.setBackgroundColor(0xFFFFFFFF.toInt()); recognized.setPadding(12, 12, 12, 12)
        root.addView(recognized, matchWrap())
        root.addView(label("Braille cells sent to the six outputs", 16f))
        braille = label("Waiting for speech…", 16f)
        braille.setBackgroundColor(0xFFFFFFFF.toInt()); braille.setPadding(12, 12, 12, 12)
        root.addView(braille, matchWrap())
        root.addView(label("MODE 2 · BRAILLE KEYS → SPEECH", 18f))
        received = label("Text from your ESP32 Braille keypad will appear here.", 16f)
        received.setBackgroundColor(0xFFFFFFFF.toInt()); received.setPadding(12, 12, 12, 12)
        root.addView(received, matchWrap())
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply { text = "Speak received text"; setOnClickListener { if (ttsReady) tts?.speak(receivedText.toString(), TextToSpeech.QUEUE_FLUSH, null, "received") else toast("Speech engine is starting") } }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply { text = "Clear"; setOnClickListener { receivedText.setLength(0); received.text = "" } }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row, matchWrap())
        root.addView(label("English Grade 1 prototype. Speech recognition may use an online service. Connect only after uploading the matching firmware. Test outputs with LEDs before actuators.", 13f, 0xFF6B7280.toInt()))
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 6 }

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), requestCode)
    }

    @SuppressLint("MissingPermission")
    private fun showPairedDevices() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) { requestNeededPermissions(); return }
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: run { toast("This phone has no Bluetooth adapter"); return }
        if (!adapter.isEnabled) { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return }
        val devices = adapter.bondedDevices.toList()
        if (devices.isEmpty()) { toast("Pair the ESP32 in Android Bluetooth settings first"); startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)); return }
        AlertDialog.Builder(this).setTitle("Choose paired ESP32").setItems(devices.map { "${it.name ?: "Unnamed device"}\n${it.address}" }.toTypedArray()) { _, which ->
            val device = devices[which]
            status.text = "Connecting to ${device.name ?: device.address}…"
            thread {
                try {
                    try { socket?.close() } catch (_: Exception) {}
                    val s = device.createRfcommSocketToServiceRecord(sppUuid)
                    adapter.cancelDiscovery(); s.connect()
                    socket = s; output = s.outputStream
                    runOnUiThread { status.text = "Connected: ${device.name ?: device.address}"; status.setTextColor(0xFF15803D.toInt()) }
                    readIncoming(s)
                } catch (e: Exception) {
                    runOnUiThread { status.text = "Connection failed: ${e.message ?: "check pairing and firmware"}"; status.setTextColor(0xFFB91C1C.toInt()) }
                }
            }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun readIncoming(s: BluetoothSocket) {
        try {
            val reader = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8))
            while (s.isConnected) {
                val line = reader.readLine() ?: break
                handleIncomingLine(line.trim())
            }
        } catch (_: Exception) { }
        runOnUiThread { if (socket === s) { status.text = "Bluetooth disconnected"; status.setTextColor(0xFFB45309.toInt()); output = null; socket = null } }
    }

    private fun handleIncomingLine(line: String) {
        when {
            line.startsWith("KEY:") -> {
                val mask = line.substringAfter("KEY:").toIntOrNull() ?: return
                val ch = Braille.decodeMask(mask) ?: return
                runOnUiThread {
                    when (ch) {
                        '\b' -> { if (receivedText.isNotEmpty()) receivedText.deleteCharAt(receivedText.length - 1) }
                        else -> receivedText.append(ch)
                    }
                    received.text = receivedText.toString()
                }
            }
            line == "SPACE" -> runOnUiThread { receivedText.append(' '); received.text = receivedText.toString() }
            line == "BACKSPACE" -> runOnUiThread { if (receivedText.isNotEmpty()) receivedText.deleteCharAt(receivedText.length - 1); received.text = receivedText.toString() }
            line.startsWith("TEXT:") -> runOnUiThread { receivedText.append(line.substringAfter("TEXT:")); received.text = receivedText.toString() }
            else -> runOnUiThread { status.text = "ESP32: $line" }
        }
    }

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestNeededPermissions(); return }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { toast("No speech recognition service available"); return }
        listening = true; listenButton.text = "Stop listening"; createRecognizerAndListen()
    }

    private fun createRecognizerAndListen() {
        if (!listening) return
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { sr ->
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { runOnUiThread { if (socket?.isConnected == true) status.text = "Connected · Listening…" else status.text = "Listening · ESP32 not connected" } }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) { if (listening) android.os.Handler(mainLooper).postDelayed({ if (listening) createRecognizerAndListen() }, 350) }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                    if (text.isNotEmpty()) { runOnUiThread { recognized.text = text; braille.text = Braille.toDisplay(text) }; sendSentence(text) }
                    if (listening) android.os.Handler(mainLooper).postDelayed({ if (listening) createRecognizerAndListen() }, 250)
                }
                override fun onPartialResults(partialResults: Bundle?) { val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull(); if (!p.isNullOrBlank()) runOnUiThread { recognized.text = "$p …" } }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            sr.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            })
        }
    }

    private fun stopListening() { listening = false; listenButton.text = "Start listening"; speechRecognizer?.cancel(); speechRecognizer?.destroy(); speechRecognizer = null; status.text = if (socket?.isConnected == true) "Connected · Not listening" else "Not connected to ESP32" }

    private fun sendSentence(text: String) {
        val out = output
        if (out == null || socket?.isConnected != true) { runOnUiThread { status.text = "Speech captured, but ESP32 isn't connected."; status.setTextColor(0xFFB45309.toInt()) }; return }
        val masks = Braille.encode(text)
        thread {
            try {
                for (mask in masks) {
                    if (!listening) break
                    out.write("CELL:$mask\n".toByteArray(Charsets.UTF_8)); out.flush(); Thread.sleep(700)
                }
                out.write("CELL:0\n".toByteArray(Charsets.UTF_8)); out.flush()
                runOnUiThread { status.text = "Sent ${masks.size} Braille cells"; status.setTextColor(0xFF15803D.toInt()) }
            } catch (e: Exception) { runOnUiThread { status.text = "Send failed: ${e.message ?: "Bluetooth disconnected"}" } }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    override fun onDestroy() { listening = false; speechRecognizer?.destroy(); tts?.stop(); tts?.shutdown(); try { socket?.close() } catch (_: Exception) {}; super.onDestroy() }
}

/** Basic English Grade 1 Braille. Bit 0 = dot 1. */
object Braille {
    private val letters = mapOf('a' to 1, 'b' to 3, 'c' to 9, 'd' to 25, 'e' to 17, 'f' to 11, 'g' to 27, 'h' to 19, 'i' to 10, 'j' to 26, 'k' to 5, 'l' to 7, 'm' to 13, 'n' to 29, 'o' to 21, 'p' to 15, 'q' to 31, 'r' to 23, 's' to 14, 't' to 30, 'u' to 37, 'v' to 39, 'w' to 58, 'x' to 45, 'y' to 61, 'z' to 53)
    private val punctuation = mapOf(' ' to 0, ',' to 2, ';' to 6, ':' to 18, '.' to 50, '!' to 22, '?' to 38, '\'' to 4, '-' to 36)
    private val inverse = letters.entries.associate { it.value to it.key }
    fun encode(text: String): List<Int> {
        val out = mutableListOf<Int>()
        for (ch in text.lowercase()) when {
            letters.containsKey(ch) -> out.add(letters.getValue(ch))
            ch in '0'..'9' -> { out.add(60); out.add(letters.getValue(if (ch == '0') 'j' else ('a'.code + (ch - '1')).toChar())) }
            punctuation.containsKey(ch) -> out.add(punctuation.getValue(ch))
            else -> out.add(0)
        }
        return out
    }
    fun decodeMask(mask: Int): Char? = when (mask) { 0 -> ' '; 2 -> ','; 6 -> ';'; 18 -> ':'; 50 -> '.'; 22 -> '!'; 38 -> '?'; 4 -> '\''; 36 -> '-'; else -> inverse[mask] }
    fun toDisplay(text: String): String = encode(text).joinToString("   ") { mask -> (1..6).joinToString("") { dot -> if ((mask and (1 shl (dot - 1))) != 0) "●" else "○" }.chunked(3).joinToString("/") }
}
