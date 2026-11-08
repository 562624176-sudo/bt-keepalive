package com.bt.keepalive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.IOException
import java.util.*

class BluetoothKeepAliveService : Service() {

    companion object {
        private const val TAG = "BTKeepAlive"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "bt_keepalive_channel"

        // 心跳间隔（毫秒）
        private const val HEARTBEAT_INTERVAL = 1540L

        // 纽曼 MC225 T1 的蓝牙地址（从 HCI log 提取）
        private const val TARGET_DEVICE_ADDRESS = "B4:60:ED:DD:C3:9A"

        // HCI Event 心跳包数据（50 字节）
        private val HEARTBEAT_PACKET_50 = byteArrayOf(
            0x04, 0x3e, 0x2f, 0x0d, 0x01, 0x13, 0x00, 0x00,
            0x9a.toByte(), 0xc3.toByte(), 0xdd.toByte(), 0xed.toByte(),
            0x60, 0xb4.toByte(), 0x01, 0x00,
            0xff.toByte(), 0x7f, 0xa7.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x15, 0x02, 0x01, 0x06,
            0x11, 0x16, 0x95.toByte(), 0xfe.toByte(), 0xb0.toByte(), 0x54,
            0x81.toByte(), 0x12, 0x01, 0x9a.toByte(), 0xc3.toByte(), 0xdd.toByte(),
            0xed.toByte(), 0x60, 0xb4.toByte(), 0x08, 0x0e, 0x00
        )

        // 51 字节心跳包
        private val HEARTBEAT_PACKET_51 = byteArrayOf(
            0x04, 0x3e, 0x30, 0x0d, 0x01, 0x1b, 0x00, 0x00,
            0x9a.toByte(), 0xc3.toByte(), 0xdd.toByte(), 0xed.toByte(),
            0x60, 0xb4.toByte(), 0x01, 0x00,
            0xff.toByte(), 0x7f, 0xa7.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x16, 0x15, 0x09, 0x79,
            0x65, 0x65, 0x6c, 0x69, 0x6e, 0x6b, 0x2e, 0x6c,
            0x69, 0x67, 0x68, 0x74, 0x2e, 0x6c, 0x61, 0x6d,
            0x70, 0x32, 0x31
        )
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var targetDevice: BluetoothDevice? = null
    private var socket: BluetoothSocket? = null

    private val handler = Handler(Looper.getMainLooper())
    private var heartbeatRunnable: Runnable? = null

    private var isRunning = false
    private var packetCount = 0

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "服务创建")

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        if (bluetoothAdapter == null) {
            Log.e(TAG, "设备不支持蓝牙")
            stopSelf()
            return
        }

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "服务启动")

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("蓝牙麦防休眠")
            .setContentText("正在保持连接...")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        startHeartbeat()

        return START_STICKY
    }

    private fun startHeartbeat() {
        if (isRunning) return

        try {
            // 查找目标设备
            targetDevice = bluetoothAdapter?.bondedDevices?.find {
                it.address == TARGET_DEVICE_ADDRESS
            }

            if (targetDevice == null) {
                Log.w(TAG, "未找到目标设备: $TARGET_DEVICE_ADDRESS")
                updateNotification("未找到设备\n请先配对纽曼麦克风", android.R.drawable.stat_sys_warning)
                return
            }

            Log.d(TAG, "找到目标设备: ${targetDevice?.name} (${targetDevice?.address})")

            // 连接设备（使用 RFCOMM）
            connectDevice()

            // 启动心跳定时器
            heartbeatRunnable = object : Runnable {
                override fun run() {
                    sendHeartbeat()
                    handler.postDelayed(this, HEARTBEAT_INTERVAL)
                }
            }

            isRunning = true
            handler.post(heartbeatRunnable!!)

            updateNotification("已连接，发送心跳中...", android.R.drawable.stat_sys_data_bluetooth)

        } catch (e: Exception) {
            Log.e(TAG, "启动心跳失败", e)
            updateNotification("启动失败: ${e.message}", android.R.drawable.stat_sys_warning)
        }
    }

    private fun connectDevice() {
        try {
            // 使用标准 SPP UUID
            val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
            socket = targetDevice?.createRfcommSocketToServiceRecord(uuid)
            socket?.connect()
            Log.d(TAG, "蓝牙连接成功")
        } catch (e: IOException) {
            Log.w(TAG, "RFCOMM 连接失败，使用备用方案", e)
            // 某些设备需要反射创建 socket
            try {
                val method = targetDevice?.javaClass?.getMethod(
                    "createRfcommSocket",
                    Int::class.javaPrimitiveType
                )
                socket = method?.invoke(targetDevice, 1) as? BluetoothSocket
                socket?.connect()
                Log.d(TAG, "备用连接成功")
            } catch (e2: Exception) {
                Log.e(TAG, "所有连接方式失败", e2)
            }
        }
    }

    private fun sendHeartbeat() {
        try {
            if (socket?.isConnected == true) {
                val outputStream = socket?.outputStream

                // 交替发送两种心跳包
                val packet = if (packetCount % 2 == 0) {
                    HEARTBEAT_PACKET_50
                } else {
                    HEARTBEAT_PACKET_51
                }

                outputStream?.write(packet)
                outputStream?.flush()

                packetCount++

                Log.d(TAG, "心跳包已发送 #$packetCount (${packet.size} 字节)")

                updateNotification(
                    "已发送 $packetCount 个心跳包\n间隔 ${HEARTBEAT_INTERVAL}ms",
                    android.R.drawable.stat_sys_data_bluetooth
                )
            } else {
                Log.w(TAG, "蓝牙未连接，尝试重连...")
                connectDevice()
            }
        } catch (e: IOException) {
            Log.e(TAG, "发送心跳失败", e)
            // 连接断开，尝试重连
            try {
                socket?.close()
                connectDevice()
            } catch (e2: Exception) {
                Log.e(TAG, "重连失败", e2)
            }
        }
    }

    private fun updateNotification(text: String, icon: Int) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("蓝牙麦防休眠")
            .setContentText(text)
            .setSmallIcon(icon)
            .setOngoing(true)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "蓝牙保活服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持蓝牙麦克风连接"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "服务停止")

        isRunning = false
        heartbeatRunnable?.let { handler.removeCallbacks(it) }

        try {
            socket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "关闭连接失败", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
