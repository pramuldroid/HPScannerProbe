package com.example.hpscannerprobe

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var usbManager: UsbManager
    private lateinit var tvLog: TextView
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
                    appendLog("\n>>> PERMISSION GRANTED for ${device.productName}\n")
                    inspectDeviceDetails(device)
                } else {
                    appendLog("\n>>> PERMISSION DENIED by user.\n")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        tvLog = findViewById(R.id.tvLog)
        val btnDetect: Button = findViewById(R.id.btnDetect)
        val btnCopy: Button = findViewById(R.id.btnCopy)

        val filter = IntentFilter(actionUsbPermission)
        ContextCompat.registerReceiver(
            this,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )

        btnDetect.setOnClickListener { detectUsbDevices() }

        btnCopy.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("USB Probe Log", tvLog.text.toString())
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }

    private fun detectUsbDevices() {
        tvLog.text = "Scanning USB bus...\n"
        val deviceList = usbManager.deviceList

        if (deviceList.isEmpty()) {
            appendLog("No USB devices detected.\n")
            appendLog("Checklist:\n1. Is the printer powered ON?\n2. Is the OTG adapter firmly seated?\n3. Try a different USB-B cable if available.")
            return
        }

        appendLog("Found ${deviceList.size} device(s):\n")

        for ((_, device) in deviceList) {
            appendLog("----------------------------------------\n")
            appendLog("Device Name: ${device.deviceName}\n")
            appendLog("Manufacturer: ${device.manufacturerName}\n")
            appendLog("Product: ${device.productName}\n")
            appendLog("Vendor ID: 0x${device.vendorId.toString(16).uppercase()} (${device.vendorId})\n")
            appendLog("Product ID: 0x${device.productId.toString(16).uppercase()} (${device.productId})\n")

            if (usbManager.hasPermission(device)) {
                appendLog("Permission: ALREADY GRANTED\n")
                inspectDeviceDetails(device)
            } else {
                appendLog("Permission: Requesting...\n")
                val intent = Intent(actionUsbPermission).apply { setPackage(packageName) }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
                val permissionIntent = PendingIntent.getBroadcast(this, 0, intent, flags)
                usbManager.requestPermission(device, permissionIntent)
            }
        }
    }

    private fun inspectDeviceDetails(device: UsbDevice) {
        appendLog("Total Interfaces: ${device.interfaceCount}\n")

        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            val classLabel = when {
                intf.interfaceClass == 7 && intf.interfaceSubclass == 1 && intf.interfaceProtocol == 4 ->
                    "IPP-USB (Likely supports eSCL Scanning!)"
                intf.interfaceClass == 7 -> "Printer Class"
                intf.interfaceClass == 255 -> "Vendor-Specific (Likely HP LEDM/Scan)"
                else -> "Other (${intf.interfaceClass})"
            }

            appendLog("\n[Interface #$i] ID=${intf.id} | $classLabel\n")
            appendLog("  Class=${intf.interfaceClass}, Subclass=${intf.interfaceSubclass}, Protocol=${intf.interfaceProtocol}\n")
            appendLog("  Name=${intf.name ?: "N/A"}\n")

            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                val type = when (ep.type) {
                    UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                    UsbConstants.USB_ENDPOINT_XFER_INT -> "INTERRUPT"
                    UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
                    UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOCHRONOUS"
                    else -> "UNKNOWN"
                }
                appendLog("    -> Endpoint #$e: $type | $dir | Addr=0x${ep.address.toString(16)} | MaxPacket=${ep.maxPacketSize}\n")
            }
        }

        val connection = usbManager.openDevice(device)
        if (connection != null) {
            appendLog("\n>>> RAW USB CONNECTION OPENED SUCCESSFULLY!\n")
            connection.close()
        } else {
            appendLog("\n>>> FAILED to open raw USB connection.\n")
        }
    }

    private fun appendLog(text: String) {
        tvLog.append(text)
    }
}
