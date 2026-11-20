package com.example.hpscannerprobe

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var usbManager: UsbManager
    private lateinit var tvLog: TextView
    private lateinit var ivPreview: ImageView
    private lateinit var btnScan: Button
    private lateinit var btnSave: Button
    private lateinit var rbColor: RadioButton

    private var lastScannedJpeg: ByteArray? = null
    private val actionUsbPermission = "com.hpscannerprobe.USB_PERMISSION"

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == actionUsbPermission) {
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted && device != null) {
                    startScanThread(device)
                } else {
                    log("USB permission denied.")
                    btnScan.isEnabled = true
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        tvLog = findViewById(R.id.tvLog)
        ivPreview = findViewById(R.id.ivPreview)
        btnScan = findViewById(R.id.btnScan)
        btnSave = findViewById(R.id.btnSave)
        rbColor = findViewById(R.id.rbColor)
        val btnCopy: Button = findViewById(R.id.btnCopy)

        ContextCompat.registerReceiver(
            this,
            usbReceiver,
            IntentFilter(actionUsbPermission),
            ContextCompat.RECEIVER_EXPORTED
        )

        btnScan.setOnClickListener { initiateScan() }
        btnSave.setOnClickListener { saveLastScanToGallery() }
        btnCopy.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("HP Scan Log", tvLog.text.toString()))
            Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }

    private fun initiateScan() {
        tvLog.text = ""
        btnScan.isEnabled = false
        btnSave.isEnabled = false

        val hpDevice = usbManager.deviceList.values.firstOrNull { it.vendorId == 0x03F0 }
            ?: usbManager.deviceList.values.firstOrNull()

        if (hpDevice == null) {
            log("No USB printer found. Check OTG cable and power.")
            btnScan.isEnabled = true
            return
        }

        log("Found: ${hpDevice.productName}")
        if (usbManager.hasPermission(hpDevice)) {
            startScanThread(hpDevice)
        } else {
            log("Requesting USB permission...")
            val intent = Intent(actionUsbPermission).apply { setPackage(packageName) }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            usbManager.requestPermission(hpDevice, PendingIntent.getBroadcast(this, 0, intent, flags))
        }
    }

    private fun startScanThread(device: UsbDevice) {
        val isColor = rbColor.isChecked
        thread {
            val connection = usbManager.openDevice(device)
            if (connection == null) {
                log("Failed to open USB connection.")
                runOnUiThread { btnScan.isEnabled = true }
                return@thread
            }

            try {
                // Interface #0 (255/204/0) is the confirmed LEDM scanner interface
                var scanIntf: UsbInterface? = null
                for (i in 0 until device.interfaceCount) {
                    val intf = device.getInterface(i)
                    if (intf.interfaceClass == 255 && intf.interfaceSubclass == 204) {
                        scanIntf = intf
                        break
                    }
                }

                if (scanIntf == null) {
                    log("HP LEDM interface (255/204) not found.")
                    return@thread
                }

                if (!connection.claimInterface(scanIntf, true)) {
                    log("Could not claim Interface #${scanIntf.id}.")
                    return@thread
                }

                var epOut: UsbEndpoint? = null
                var epIn: UsbEndpoint? = null
                for (e in 0 until scanIntf.endpointCount) {
                    val ep = scanIntf.getEndpoint(e)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_OUT) epOut = ep
                        if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep
                    }
                }

                var scannedBytes: ByteArray? = null
                if (epOut != null && epIn != null) {
                    drainUsbBuffer(connection, epIn)
                    scannedBytes = tryLedmScan(connection, epOut, epIn, isColor)
                }

                connection.releaseInterface(scanIntf)

                if (scannedBytes != null && scannedBytes.isNotEmpty()) {
                    val bitmap: Bitmap? = BitmapFactory.decodeByteArray(scannedBytes, 0, scannedBytes.size)
                    if (bitmap != null) {
                        lastScannedJpeg = scannedBytes
                        log("Scan complete! ${scannedBytes.size / 1024} KB (${bitmap.width}x${bitmap.height})")
                        runOnUiThread {
                            ivPreview.setImageBitmap(bitmap)
                            btnSave.isEnabled = true
                        }
                    } else {
                        log("Received ${scannedBytes.size} bytes, but could not decode JPEG.")
                    }
                } else {
                    log("Scan failed. If you see 503, turn the printer off & on once.")
                }
            } catch (e: Exception) {
                log("Error: ${e.message}")
            } finally {
                connection.close()
                runOnUiThread { btnScan.isEnabled = true }
            }
        }
    }

    private fun drainUsbBuffer(conn: UsbDeviceConnection, epIn: UsbEndpoint) {
        val junk = ByteArray(16384)
        var drained = 0
        while (true) {
            val r = conn.bulkTransfer(epIn, junk, junk.size, 150)
            if (r > 0) drained += r else break
        }
        if (drained > 0) log("Cleared $drained B of leftover USB data.")
    }

    private fun tryLedmScan(
        conn: UsbDeviceConnection,
        epOut: UsbEndpoint,
        epIn: UsbEndpoint,
        isColor: Boolean
    ): ByteArray? {
        val colorSpace = if (isColor) "Color" else "Gray"
        val scanXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <scan:ScanJob xmlns:scan="http://www.hp.com/schemas/imaging/con/cnx/scan/2008/08/19" xmlns:dd="http://www.hp.com/schemas/imaging/con/dictionaries/1.0/">
              <scan:XResolution>200</scan:XResolution>
              <scan:YResolution>200</scan:YResolution>
              <scan:XStart>0</scan:XStart>
              <scan:YStart>0</scan:YStart>
              <scan:Width>2480</scan:Width>
              <scan:Height>3508</scan:Height>
              <scan:Format>Jpeg</scan:Format>
              <scan:CompressionQFactor>25</scan:CompressionQFactor>
              <scan:ColorSpace>$colorSpace</scan:ColorSpace>
              <scan:BitDepth>8</scan:BitDepth>
              <scan:InputSource>Platen</scan:InputSource>
              <scan:GrayRendering>NTSC</scan:GrayRendering>
              <scan:ContentType>Document</scan:ContentType>
            </scan:ScanJob>
        """.trimIndent()

        val postReq = "POST /Scan/Jobs HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: text/xml\r\n" +
                "Content-Length: ${scanXml.toByteArray().size}\r\n" +
                "\r\n" +
                scanXml

        log("Starting $colorSpace scan (200 DPI)...")
        val postResp = sendHttpOverUsb(conn, epOut, epIn, postReq.toByteArray(), 8000, false) ?: return null
        val respStr = String(postResp, Charsets.ISO_8859_1)
        val firstLine = respStr.lineSequence().firstOrNull() ?: ""
        log("POST /Scan/Jobs -> $firstLine")
        if (!firstLine.contains("201")) return null

        val locationLine = respStr.lineSequence().firstOrNull { it.startsWith("Location:", ignoreCase = true) }
        val rawLocation = locationLine?.substringAfter(":")?.trim() ?: return null
        val jobPath = if (rawLocation.startsWith("http")) "/" + rawLocation.substringAfter("://").substringAfter("/", "") else rawLocation

        var binaryUrl: String? = null
        for (attempt in 1..10) {
            Thread.sleep(800)
            val statusResp = sendHttpOverUsb(conn, epOut, epIn, "GET $jobPath HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray(), 5000, false)
            if (statusResp != null) {
                val statusStr = String(statusResp, Charsets.ISO_8859_1)
                val match = Regex("<[^>]*BinaryURL>([^<]+)</[^>]*BinaryURL>").find(statusStr)
                if (match != null) {
                    binaryUrl = match.groupValues[1].trim()
                    break
                }
            }
        }

        val docUrl = binaryUrl ?: return null
        val docPath = if (docUrl.startsWith("http")) "/" + docUrl.substringAfter("://").substringAfter("/", "") else docUrl
        log("Scanning & downloading... please wait until lamp stops.")
        val docResp = sendHttpOverUsb(conn, epOut, epIn, "GET $docPath HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray(), 25000, true) ?: return null
        return extractHttpBody(docResp)
    }

    private fun sendHttpOverUsb(
        conn: UsbDeviceConnection,
        epOut: UsbEndpoint,
        epIn: UsbEndpoint,
        requestBytes: ByteArray,
        firstReadTimeoutMs: Int,
        isImageStream: Boolean
    ): ByteArray? {
        val sent = conn.bulkTransfer(epOut, requestBytes, requestBytes.size, 5000)
        if (sent < 0) {
            log("USB OUT failed ($sent)")
            return null
        }

        val out = ByteArrayOutputStream()
        val buf = ByteArray(16384)
        var timeout = firstReadTimeoutMs
        var emptyReads = 0
        var headerEnd = -1
        var isChunked = false
        var contentLength = -1

        while (true) {
            val read = conn.bulkTransfer(epIn, buf, buf.size, timeout)
            if (read > 0) {
                out.write(buf, 0, read)
                emptyReads = 0
                timeout = if (isImageStream) 6000 else 1500

                val current = out.toByteArray()
                if (headerEnd == -1) {
                    headerEnd = findHeaderEnd(current)
                    if (headerEnd != -1) {
                        val hdrs = String(current, 0, headerEnd, Charsets.ISO_8859_1)
                        isChunked = hdrs.contains("Transfer-Encoding: chunked", ignoreCase = true)
                        val clLine = hdrs.lineSequence().firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                        contentLength = clLine?.substringAfter(":")?.trim()?.toIntOrNull() ?: -1
                    }
                }

                if (headerEnd != -1) {
                    if (contentLength >= 0 && (current.size - headerEnd) >= contentLength) {
                        break
                    }
                    if (isChunked && endsWithChunkedZero(current)) {
                        break
                    }
                }
            } else {
                emptyReads++
                // For image streams, tolerate up to 2 consecutive timeouts while carriage pauses
                if (!isImageStream || out.size() == 0 || emptyReads >= 2) {
                    break
                }
            }
        }
        return if (out.size() > 0) out.toByteArray() else null
    }

    private fun findHeaderEnd(data: ByteArray): Int {
        for (i in 0 until data.size - 3) {
            if (data[i] == 13.toByte() && data[i + 1] == 10.toByte() &&
                data[i + 2] == 13.toByte() && data[i + 3] == 10.toByte()
            ) {
                return i + 4
            }
        }
        return -1
    }

    private fun endsWithChunkedZero(data: ByteArray): Boolean {
        if (data.size < 5) return false
        val n = data.size
        return data[n - 5] == '0'.code.toByte() &&
                data[n - 4] == 13.toByte() && data[n - 3] == 10.toByte() &&
                data[n - 2] == 13.toByte() && data[n - 1] == 10.toByte()
    }

    private fun extractHttpBody(rawResponse: ByteArray): ByteArray? {
        val headerEnd = findHeaderEnd(rawResponse)
        if (headerEnd == -1) return null

        val headers = String(rawResponse, 0, headerEnd, Charsets.ISO_8859_1)
        val body = rawResponse.copyOfRange(headerEnd, rawResponse.size)

        return if (headers.contains("Transfer-Encoding: chunked", ignoreCase = true)) {
            decodeChunked(body)
        } else {
            body
        }
    }

    private fun decodeChunked(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var pos = 0
        while (pos < data.size) {
            var crlf = pos
            while (crlf < data.size - 1 && !(data[crlf] == 13.toByte() && data[crlf + 1] == 10.toByte())) {
                crlf++
            }
            val sizeHex = String(data, pos, crlf - pos, Charsets.US_ASCII).trim().substringBefore(";")
            val chunkSize = sizeHex.toIntOrNull(16) ?: break
            if (chunkSize == 0) break
            val start = crlf + 2
            if (start + chunkSize > data.size) {
                out.write(data, start, data.size - start)
                break
            }
            out.write(data, start, chunkSize)
            pos = start + chunkSize + 2
        }
        return out.toByteArray()
    }

    private fun saveLastScanToGallery() {
        val bytes = lastScannedJpeg ?: return
        val filename = "HP_Scan_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/HPScanner")
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            Toast.makeText(this, "Saved to Pictures/HPScanner/$filename", Toast.LENGTH_LONG).show()
            log("Saved: Pictures/HPScanner/$filename")
        } else {
            Toast.makeText(this, "Failed to save image", Toast.LENGTH_SHORT).show()
        }
    }

    private fun log(msg: String) {
        runOnUiThread { tvLog.append("$msg\n") }
    }
}
