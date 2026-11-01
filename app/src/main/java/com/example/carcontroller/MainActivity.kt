package com.example.carcontroller

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.*
import android.os.*
import android.view.MotionEvent
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private val sppUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val BLE_SERVICE = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    private val BLE_WRITE = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb")
    private val BLE_NOTIFY = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    private val CLIENT_CHAR_CFG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val PULSE_TO_CM = 0.0005 // 与固件一致：cm/脉冲

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var sppSocket: BluetoothSocket? = null
    private var sppOut: java.io.OutputStream? = null
    private var sppIn: java.io.InputStream? = null
    private var gatt: BluetoothGatt? = null
    private var bleChar: BluetoothGattCharacteristic? = null
    private var bleNotifyChar: BluetoothGattCharacteristic? = null
    private var isBle = false

    private var currentMode = "manual" // manual | auto

    private val devices = ArrayList<DeviceEntry>()
    private lateinit var listAdapter: ArrayAdapter<String>

    private lateinit var tvStatus: TextView
    private lateinit var tvMode: TextView
    private lateinit var tvLog: TextView
    private lateinit var listDevices: ListView
    private lateinit var dpad: GridLayout
    private lateinit var tvSpeed: TextView
    private lateinit var tvDist: TextView
    private lateinit var tvRaw: TextView

    private var telemetryBuffer = StringBuilder()

    private val handler = Handler(Looper.getMainLooper())
    private val PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION)

    data class DeviceEntry(val device: BluetoothDevice, val isBle: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvMode = findViewById(R.id.tvMode)
        tvLog = findViewById(R.id.tvLog)
        listDevices = findViewById(R.id.listDevices)
        dpad = findViewById(R.id.dpad)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvDist = findViewById(R.id.tvDist)
        tvRaw = findViewById(R.id.tvRaw)

        val ba = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        bluetoothAdapter = ba

        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList<String>())
        listDevices.adapter = listAdapter

        findViewById<Button>(R.id.btnScan).setOnClickListener { checkPermissionThenScan() }
        listDevices.setOnItemClickListener { _, _, pos, _ ->
            val entry = devices[pos]
            listDevices.visibility = ListView.GONE
            if (entry.isBle) connectBle(entry.device) else connectSpp(entry.device)
        }

        // 模式切换
        findViewById<Button>(R.id.btnAuto).setOnClickListener { setMode("auto") }
        findViewById<Button>(R.id.btnManual).setOnClickListener { setMode("manual") }

        // 方向键
        bindDpad(R.id.btnF, 'F'); bindDpad(R.id.btnL, 'L'); bindDpad(R.id.btnR, 'R'); bindDpad(R.id.btnB, 'B')
        findViewById<Button>(R.id.btnS).setOnClickListener { send('S') }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            if (currentMode == "auto") setMode("manual") else send('S')
        }
    }

    private fun bindDpad(id: Int, cmd: Char) {
        val b = findViewById<Button>(id)
        b.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { if (currentMode != "auto") send(cmd) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { if (currentMode != "auto") send('S') }
            }
            false
        }
    }

    private fun setMode(mode: String) {
        currentMode = mode
        val btnAuto = findViewById<Button>(R.id.btnAuto)
        val btnManual = findViewById<Button>(R.id.btnManual)
        if (mode == "auto") {
            btnAuto.setBackgroundTintList(ContextCompat.getColorStateList(this, android.R.color.holo_orange_light))
            btnManual.setBackgroundTintList(ContextCompat.getColorStateList(this, android.R.color.darker_gray))
            tvMode.text = "当前：自动避障模式（小车自主行驶，方向键已锁定）"
            send('A'); log("→ 切换到自动避障模式")
        } else {
            btnManual.setBackgroundTintList(ContextCompat.getColorStateList(this, android.R.color.holo_green_light))
            btnAuto.setBackgroundTintList(ContextCompat.getColorStateList(this, android.R.color.darker_gray))
            tvMode.text = "当前：手动模式（按住方向键控制）"
            send('M'); log("→ 切换到手动模式")
        }
    }

    private fun checkPermissionThenScan() {
        if (PERMISSIONS.all { ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            startScan()
        } else {
            ActivityCompat.requestPermissions(this, PERMISSIONS, 1)
        }
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<String>, grant: IntArray) {
        super.onRequestPermissionsResult(req, perms, grant)
        if (req == 1 && grant.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) startScan()
        else log("权限被拒绝，无法扫描蓝牙")
    }

    private fun startScan() {
        if (!bluetoothAdapter.isEnabled) { log("请先打开手机蓝牙"); return }
        devices.clear(); listAdapter.clear()
        setStatus("扫描中…", false)
        log("开始扫描附近蓝牙设备（约 12 秒）…")
        listDevices.visibility = ListView.VISIBLE

        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        registerReceiver(classicReceiver, filter)
        bluetoothAdapter.startDiscovery()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            bluetoothAdapter.bluetoothLeScanner?.startScan(bleScanCallback)
        }
        handler.postDelayed({ finishScan() }, 12000)
    }

    private fun finishScan() {
        try { bluetoothAdapter.cancelDiscovery() } catch (_: Exception) {}
        try { unregisterReceiver(classicReceiver) } catch (_: Exception) {}
        try { bluetoothAdapter.bluetoothLeScanner?.stopScan(bleScanCallback) } catch (_: Exception) {}
        setStatus(if (devices.isEmpty()) "未找到设备" else "扫描完成，点选设备", false)
    }

    private val classicReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val d = i?.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    addDevice(d, false)
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val d = i?.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    if (d.bondState == BluetoothDevice.BOND_BONDED) {
                        val e = devices.find { it.device.address == d.address && !it.isBle }
                        if (e != null) connectSpp(d)
                    }
                }
            }
        }
    }

    private val bleScanCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.device?.let { addDevice(it, true) }
            }
        } else null

    @Suppress("DEPRECATION")
    private fun addDevice(device: BluetoothDevice, isBle: Boolean) {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            device.alias ?: device.name else device.name
        val showName = name ?: "(未知设备)"
        if (devices.any { it.device.address == device.address && it.isBle == isBle }) return
        devices.add(DeviceEntry(device, isBle))
        runOnUiThread {
            listAdapter.add("$showName\n${device.address}  ·  ${if (isBle) "BLE" else "经典SPP"}")
            listAdapter.notifyDataSetChanged()
        }
    }

    private fun connectSpp(device: BluetoothDevice) {
        Thread {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    log("缺少 BLUETOOTH_CONNECT 权限"); return@Thread
                }
                if (device.bondState != BluetoothDevice.BOND_BONDED) {
                    runOnUiThread { log("未配对，发起配对 ${device.name}（PIN 1234）…") }
                    device.createBond()
                    return@Thread
                }
                val sock = device.createRfcommSocketToServiceRecord(sppUuid)
                bluetoothAdapter.cancelDiscovery()
                sock.connect()
                sppSocket = sock
                sppOut = sock.outputStream
                sppIn = sock.inputStream
                isBle = false
                runOnUiThread {
                    setStatus("已连接(SPP)", true)
                    log("SPP 连接成功：${device.name}")
                    setMode("manual")
                }
                startSppReceiver()
            } catch (e: Exception) {
                runOnUiThread { setStatus("SPP 连接失败", false); log("SPP 错误：${e.message}") }
            }
        }.start()
    }

    private fun startSppReceiver() {
        Thread {
            try {
                val buf = ByteArray(1024)
                while (sppSocket?.isConnected == true) {
                    val len = sppIn?.read(buf) ?: -1
                    if (len <= 0) break
                    telemetryBuffer.append(String(buf, 0, len, Charsets.UTF_8))
                    var nl = telemetryBuffer.indexOf("\n")
                    while (nl >= 0) {
                        val line = telemetryBuffer.substring(0, nl).trim()
                        telemetryBuffer.delete(0, nl + 1)
                        if (line.isNotEmpty()) handleTelemetry(line)
                        nl = telemetryBuffer.indexOf("\n")
                    }
                }
            } catch (_: Exception) { }
        }.start()
    }

    private fun connectBle(device: BluetoothDevice) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            log("缺少 BLUETOOTH_CONNECT 权限"); return
        }
        setStatus("BLE 连接中…", false)
        gatt = device.connectGatt(this, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.discoverServices()
                } else {
                    runOnUiThread { setStatus("BLE 已断开", false); log("BLE 断开") }
                }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val svc = g.getService(BLE_SERVICE)
                if (svc == null) { runOnUiThread { setStatus("未找到 FFE0 服务", false); log("该设备没有 FFE0 服务（可能不是 DX-BT18 的 BLE 通道）") }; return }
                bleChar = svc.getCharacteristic(BLE_WRITE)
                if (bleChar == null) { runOnUiThread { setStatus("未找到 FFE2 特征", false) }; return }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) bleChar?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                // 订阅遥测通道 FFE1
                try {
                    bleNotifyChar = svc.getCharacteristic(BLE_NOTIFY)
                    if (bleNotifyChar != null) {
                        g.setCharacteristicNotification(bleNotifyChar, true)
                        val desc = bleNotifyChar?.getDescriptor(CLIENT_CHAR_CFG)
                        desc?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            @Suppress("DEPRECATION") g.writeDescriptor(desc)
                        }
                        runOnUiThread { log("已订阅遥测通道 (FFE1)") }
                    }
                } catch (e: Exception) { runOnUiThread { log("订阅 FFE1 失败：${e.message}") } }
                isBle = true
                runOnUiThread { setStatus("已连接(BLE)", true); log("BLE 连接成功"); setMode("manual") }
            }
            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid == BLE_NOTIFY) {
                    val text = String(characteristic.value, Charsets.UTF_8)
                    telemetryBuffer.append(text)
                    var nl = telemetryBuffer.indexOf("\n")
                    while (nl >= 0) {
                        val line = telemetryBuffer.substring(0, nl).trim()
                        telemetryBuffer.delete(0, nl + 1)
                        if (line.isNotEmpty()) handleTelemetry(line)
                        nl = telemetryBuffer.indexOf("\n")
                    }
                }
            }
        })
    }

    private fun handleTelemetry(line: String) {
        if (!line.startsWith("{")) return
        try {
            val o = JSONObject(line)
            if (o.has("vA") && o.has("vB")) {
                val avg = (Math.abs(o.getInt("vA")) + Math.abs(o.getInt("vB"))) / 2.0
                val cmps = avg * PULSE_TO_CM
                runOnUiThread {
                    tvSpeed.text = String.format("%.1f", cmps)
                    tvRaw.text = "脉冲/s: " + Math.round(avg)
                }
            }
            if (o.has("dist")) {
                val m = o.getDouble("dist") / 100.0
                runOnUiThread { tvDist.text = String.format("%.2f", m) }
            }
        } catch (_: Exception) { }
    }

    private fun send(cmd: Char) {
        if (currentMode == "auto" && cmd != 'A' && cmd != 'M') return
        val bytes = cmd.toString().toByteArray()
        try {
            if (isBle && bleChar != null && gatt != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt?.writeCharacteristic(bleChar, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                } else {
                    @Suppress("DEPRECATION")
                    bleChar?.value = bytes
                    @Suppress("DEPRECATION")
                    gatt?.writeCharacteristic(bleChar)
                }
            } else if (sppOut != null) {
                sppOut?.write(bytes)
                sppOut?.flush()
            } else {
                runOnUiThread { log("未连接，无法发送 $cmd") }
                return
            }
            runOnUiThread { log("→ 发送 $cmd") }
        } catch (e: Exception) {
            runOnUiThread { log("发送失败：${e.message}") }
        }
    }

    private fun setStatus(text: String, ok: Boolean) {
        runOnUiThread {
            tvStatus.text = text
            tvStatus.setTextColor(if (ok) 0xFF2DD4BF.toInt() else 0xFF94A3B8.toInt())
        }
    }
    private fun log(msg: String) {
        runOnUiThread {
            tvLog.append("• $msg\n")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { sppSocket?.close() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
    }
}
