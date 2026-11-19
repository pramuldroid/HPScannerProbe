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
                // Find HP HTTP-over-USB interfaces (Class 255, Subclass 4, Protocol 1)
                val httpInterfaces = mutableListOf<UsbInterface>()
                for (i in 0 until device.interfaceCount) {
                    val intf = device.getInterface(i)
                    if (intf.interfaceClass == 255 && intf.interfaceSubclass == 4 && intf.interfaceProtocol == 1) {
                        httpInterfaces.add(intf)
                    }
                }

                if (httpInterfaces.isEmpty()) {
                    log("No HP HTTP-over-USB interface (255/4/1) found.")
                    return@thread
                }

                var scannedBytes: ByteArray? = null
                for (intf in httpInterfaces) {
                    log("Trying Interface #${intf.id}...")
                    if (!connection.claimInterface(intf, true)) {
                        log("Could not claim Interface #${intf.id}")
                        continue
                    }

                    var epOut: UsbEndpoint? = null
                    var epIn: UsbEndpoint? = null
                    for (e in 0 until intf.endpointCount) {
                        val ep = intf.getEndpoint(e)
                        if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                            if (ep.direction == UsbConstants.USB_DIR_OUT) epOut = ep
                            if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep
                        }
                    }

                    if (epOut != null && epIn != null) {
                        scannedBytes = tryEsclScan(connection, epOut, epIn, isColor)
                    }

                    connection.releaseInterface(intf)
                    if (scannedBytes != null) break
                }

                if (scannedBytes != null && scannedBytes.isNotEmpty()) {
                    val bitmap: Bitmap? = BitmapFactory.decodeByteArray(scannedBytes, 0, scannedBytes.size)
                    if (bitmap != null) {
                        lastScannedJpeg = scannedBytes
                        log("Scan complete! Size: ${scannedBytes.size / 1024} KB (${bitmap.width}x${bitmap.height})")
                        runOnUiThread {
                            ivPreview.setImageBitmap(bitmap)
                            btnSave.isEnabled = true
                        }
                    } else {
                        log("Received ${scannedBytes.size} bytes, but could not decode as image.")
                    }
                } else {
                    log("Scan did not return an image. Tap 'Copy Log' and share it so we can adjust the command.")
                }
            } catch (e: Exception) {
                log("Error: ${e.message}")
            } finally {
                connection.close()
                runOnUiThread { btnScan.isEnabled = true }
            }
        }
    }

    private fun tryEsclScan(
        conn: UsbDeviceConnection,
        epOut: UsbEndpoint,
        epIn: UsbEndpoint,
        isColor: Boolean
    ): ByteArray? {
        val colorMode = if (isColor) "RGB24" else "Grayscale8"
        val scanXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <scan:ScanSettings xmlns:scan="http://schemas.hp.com/imaging/escl/2011/05/03" xmlns:pwg="http://www.pwg.org/schemas/2010/12/sm">
              <pwg:Version>2.0</pwg:Version>
              <pwg:ScanRegions>
                <pwg:ScanRegion>
                  <pwg:Height>3508</pwg:Height>
                  <pwg:ContentRegionUnits>escl:ThreeHundredthsOfInches</pwg:ContentRegionUnits>
                  <pwg:Width>2480</pwg:Width>
                  <pwg:XOffset>0</pwg:XOffset>
                  <pwg:YOffset>0</pwg:YOffset>
                </pwg:ScanRegion>
              </pwg:ScanRegions>
              <scan:ColorMode>$colorMode</scan:ColorMode>
              <pwg:DocumentFormat>image/jpeg</pwg:DocumentFormat>
              <scan:XResolution>200</scan:XResolution>
              <scan:YResolution>200</scan:YResolution>
            </scan:ScanSettings>
        """.trimIndent()

        val postReq = "POST /eSCL/ScanJobs HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: text/xml\r\n" +
                "Content-Length: ${scanXml.toByteArray().size}\r\n" +
                "\r\n" +
                scanXml

        log("Sending eSCL ScanJobs request ($colorMode, 200 DPI)...")
        val postResp = sendHttpOverUsb(conn, epOut, epIn, postReq.toByteArray(), 8000)
        if (postResp == null) {
            log("No response to eSCL ScanJobs.")
            return null
        }

        val respStr = String(postResp, Charsets.ISO_8859_1)
        val firstLine = respStr.lineSequence().firstOrNull() ?: ""
        log("POST Response: $firstLine")

        if (!firstLine.contains("201")) {
            log("Header snippet:\n${respStr.take(300)}")
            return null
        }

        // Extract Location header
        val locationLine = respStr.lineSequence().firstOrNull { it.startsWith("Location:", ignoreCase = true) }
        val rawLocation = locationLine?.substringAfter(":")?.trim() ?: return null
        val jobPath = if (rawLocation.startsWith("http")) {
            rawLocation.substringAfter("://").substringAfter("/", "")
                .let { "/$it" }
        } else {
            rawLocation
        }

        log("Job created at: $jobPath")
        log("Scanning page... please wait (10-20s)...")

        val getReq = "GET $jobPath/NextDocument HTTP/1.1\r\nHost: localhost\r\n\r\n"
        val docResp = sendHttpOverUsb(conn, epOut, epIn, getReq.toByteArray(), 25000)
        if (docResp == null) {
            log("No image data received from NextDocument.")
            return null
        }

        return extractHttpBody(docResp)
    }

    private fun sendHttpOverUsb(
        conn: UsbDeviceConnection,
        epOut: UsbEndpoint,
        epIn: UsbEndpoint,
        requestBytes: ByteArray,
        firstReadTimeoutMs: Int
    ): ByteArray? {
        val sent = conn.bulkTransfer(epOut, requestBytes, requestBytes.size, 5000)
        if (sent < 0) {
            log("USB bulk OUT failed ($sent)")
            return null
        }

        val out = ByteArrayOutputStream()
        val buf = ByteArray(16384)
        var timeout = firstReadTimeoutMs

        while (true) {
            val read = conn.bulkTransfer(epIn, buf, buf.size, timeout)
            if (read > 0) {
                out.write(buf, 0, read)
                timeout = 1500 // Shorter timeout once data starts flowing
            } else {
                break
            }
        }
        return if (out.size() > 0) out.toByteArray() else null
    }

    private fun extractHttpBody(rawResponse: ByteArray): ByteArray? {
        // Find \r\n\r\n separating HTTP headers from JPEG body
        var headerEnd = -1
        for (i in 0 until rawResponse.size - 3) {
            if (rawResponse[i] == 13.toByte() && rawResponse[i + 1] == 10.toByte() &&
                rawResponse[i + 2] == 13.toByte() && rawResponse[i + 3] == 10.toByte()
            ) {
                headerEnd = i + 4
                break
            }
        }
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
