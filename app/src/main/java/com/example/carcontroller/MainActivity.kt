package com.example.carcontroller

import android.Manifest
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.*
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.*
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.widget.*
import org.json.JSONObject
import java.util.UUID

class MainActivity : Activity() {
    private val sppUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val BLE_SERVICE = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    private val BLE_WRITE = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb")
    private val BLE_NOTIFY = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    private val CLIENT_CHAR_CFG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val PULSE_TO_CM = 0.0005 // 与固件一致：cm/脉冲

    private var bluetoothAdapter: BluetoothAdapter? = null
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

    // ==== v2.1 纯系统 API 兼容层：不引入任何 androidx 依赖 ====
    // appcompat/material 会往 Manifest 合并 androidx.startup.ContentProvider，
    // 在 Application 启动阶段（早于 Activity.onCreate）自动初始化，是开机崩溃的隐患。
    private fun hasPerm(p: String): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
        else true

    private fun requestPerms() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) requestPermissions(PERMISSIONS, 1)
    }

    private fun registerBtReceiver(filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(classicReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else
            @Suppress("DEPRECATION") registerReceiver(classicReceiver, filter)
    }

    private fun tint(b: Button, color: Int) {
        b.setBackgroundTintList(ColorStateList.valueOf(color))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 全局崩溃捕获由 CarApp(Application) 在 attachBaseContext 里安装：覆盖比 onCreate 更早的
        // 启动阶段，并把完整堆栈打进 logcat（Android Studio 可直接看到），不再把异常吞掉。
        super.onCreate(savedInstanceState)

        // 2) 如果上次启动崩溃过，直接把错误显示在屏幕上，用户无需分享文件
        val prior = readCrashReport()
        if (prior != null) {
            showCrashReplay(prior)
            return
        }

        // 3) 先显示一个极简、绝对安全的启动屏，把复杂界面推迟到用户点击“进入控制界面”之后，
        //    这样无论设备/ROM 有什么怪异行为，APP 都能先打开，不再一开就闪退。
        showLauncher()
    }

    // 最安全的启动屏：只放一个 TextView + 一个 Button，没有任何布局/蓝牙依赖
    private fun showLauncher() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            setBackgroundColor(0xFF0B1020.toInt())
        }
        val title = TextView(this).apply {
            text = "小车控制器"
            textSize = 22f
            setTextColor(0xFFE6EDF7.toInt())
            gravity = Gravity.CENTER
        }
        val info = TextView(this).apply {
            text = "【v2.2 可诊断版 · 2026-11-06】\nAndroid ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}\n点击下面按钮进入控制界面"
            textSize = 12f
            setTextColor(0xFF94A3B8.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 24)
        }
        val enter = Button(this).apply {
            text = "进入控制界面"
            setOnClickListener {
                try {
                    initViews()
                } catch (t: Throwable) {
                    showFatal(t)
                }
            }
        }
        root.addView(title)
        root.addView(info)
        root.addView(enter)
        setContentView(root)
    }

    // 读取上一次崩溃记录（应用私有目录，无需任何权限）
    private fun readCrashReport(): String? = try {
        val f = java.io.File(getExternalFilesDir(null), "crash_v2.txt")
        if (f.exists() && f.length() > 0) f.readText() else null
    } catch (_: Exception) { null }

    // 写入崩溃报告：应用私有目录 + 下载目录（Android 11+ 也能用文件管理器取出）
    private fun writeCrashReport(text: String) {
        try {
            java.io.File(getExternalFilesDir(null), "crash_v2.txt").writeText(text)
        } catch (_: Exception) { }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val v = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "car_controller_crash_v2.txt")
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)?.let { uri ->
                    contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                }
            }
        } catch (_: Exception) { }
    }

    // 统一的错误显示：红字正文 + “复制错误”按钮（一键复制到剪贴板，无需打字/分享文件）
    private fun showErrorScreen(intro: String, body: String) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101522.toInt())
        }
        val scroll = ScrollView(this)
        val tv = TextView(this).apply {
            text = "$intro\n\n$body"
            setTextColor(0xFFFF5555.toInt())
            setBackgroundColor(0xFF101522.toInt())
            setPadding(24, 24, 24, 24)
            textSize = 11f
            typeface = Typeface.MONOSPACE
        }
        scroll.addView(tv)
        val copy = Button(this).apply {
            text = "复制错误到剪贴板"
            setBackgroundColor(0xFF243049.toInt())
            setTextColor(0xFFE5E7EB.toInt())
            setOnClickListener {
                try {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("crash", body))
                    Toast.makeText(this@MainActivity, "已复制，直接粘贴发给我即可", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) { }
            }
        }
        val clear = Button(this).apply {
            text = "清除错误并重新开始"
            setBackgroundColor(0xFF243049.toInt())
            setTextColor(0xFFE5E7EB.toInt())
            setOnClickListener {
                // 清掉历史崩溃记录，否则每次启动都会卡在红屏，永远进不了控制界面
                try { java.io.File(getExternalFilesDir(null), "crash_v2.txt").delete() } catch (_: Exception) { }
                packageManager.getLaunchIntentForPackage(packageName)?.let { i ->
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                }
                finish()
            }
        }
        root.addView(scroll)
        root.addView(copy)
        root.addView(clear)
        setContentView(root)
    }

    // 把上一次崩溃直接显示在屏幕上（点“复制错误”后粘贴发我即可，无需分享文件）
    private fun showCrashReplay(text: String) {
        showErrorScreen("本应用上次启动时崩溃，错误信息如下（点“复制错误”后直接粘贴发我，无需分享文件）：", text)
    }

    private fun showFatal(t: Throwable) {
        val msg = (t.message ?: t.toString()) + "\n\n" + t.stackTraceToString()
        showErrorScreen("启动失败，错误信息如下（点“复制错误”后直接粘贴发我，无需分享文件）：", msg)
        writeCrashReport("Crash @ ${java.util.Date()}\n\n$msg")
    }

    private fun initViews() {
        setContentView(R.layout.activity_main)

        // 界面已正常加载：清掉历史崩溃记录，避免下次启动误显示红屏
        try { java.io.File(getExternalFilesDir(null), "crash_v2.txt").delete() } catch (_: Exception) { }

        tvStatus = findViewById(R.id.tvStatus)
        tvMode = findViewById(R.id.tvMode)
        tvLog = findViewById(R.id.tvLog)
        listDevices = findViewById(R.id.listDevices)
        dpad = findViewById(R.id.dpad)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvDist = findViewById(R.id.tvDist)
        tvRaw = findViewById(R.id.tvRaw)

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
            tint(btnAuto, 0xFFFFBB33.toInt())
            tint(btnManual, 0xFFAAAAAA.toInt())
            tvMode.text = "当前：自动避障模式（小车自主行驶，方向键已锁定）"
            send('A'); log("→ 切换到自动避障模式")
        } else {
            tint(btnManual, 0xFF99CC00.toInt())
            tint(btnAuto, 0xFFAAAAAA.toInt())
            tvMode.text = "当前：手动模式（按住方向键控制）"
            send('M'); log("→ 切换到手动模式")
        }
    }

    private fun checkPermissionThenScan() {
        if (PERMISSIONS.all { hasPerm(it) }) startScan() else requestPerms()
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<String>, grant: IntArray) {
        super.onRequestPermissionsResult(req, perms, grant)
        if (req == 1 && grant.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) startScan()
        else log("权限被拒绝，无法扫描蓝牙")
    }

    private fun ensureBt(): Boolean {
        if (bluetoothAdapter == null) {
            bluetoothAdapter = try {
                (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            } catch (_: Exception) { null }
        }
        if (bluetoothAdapter == null) {
            log("无法获取蓝牙适配器（此设备可能不支持蓝牙）")
            return false
        }
        return true
    }

    private fun startScan() {
        if (!ensureBt()) return
        val adapter = bluetoothAdapter!!
        if (!adapter.isEnabled) { log("请先打开手机蓝牙"); return }
        devices.clear(); listAdapter.clear()
        setStatus("扫描中…", false)
        log("开始扫描附近蓝牙设备（约 12 秒）…")
        listDevices.visibility = ListView.VISIBLE

        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        registerBtReceiver(filter)
        adapter.startDiscovery()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            adapter.bluetoothLeScanner?.startScan(bleScanCallback)
        }
        handler.postDelayed({ finishScan() }, 12000)
    }

    private fun finishScan() {
        try { bluetoothAdapter?.cancelDiscovery() } catch (_: Exception) {}
        try { unregisterReceiver(classicReceiver) } catch (_: Exception) {}
        try { bluetoothAdapter?.bluetoothLeScanner?.stopScan(bleScanCallback) } catch (_: Exception) {}
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
                    !hasPerm(Manifest.permission.BLUETOOTH_CONNECT)) {
                    log("缺少 BLUETOOTH_CONNECT 权限"); return@Thread
                }
                if (device.bondState != BluetoothDevice.BOND_BONDED) {
                    runOnUiThread { log("未配对，发起配对 ${device.name}（PIN 1234）…") }
                    device.createBond()
                    return@Thread
                }
                val sock = device.createRfcommSocketToServiceRecord(sppUuid)
                bluetoothAdapter?.cancelDiscovery()
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
            !hasPerm(Manifest.permission.BLUETOOTH_CONNECT)) {
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
                        val desc = bleNotifyChar?.getDescriptor(CLIENT_CHAR_CFG) ?: run {
                            runOnUiThread { log("未找到 CCCD 描述符") }
                            return
                        }
                        desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
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
                val bc = bleChar!!
                val g = gatt!!
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(bc, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                } else {
                    @Suppress("DEPRECATION")
                    bc.value = bytes
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(bc)
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
