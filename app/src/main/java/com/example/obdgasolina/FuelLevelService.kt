package com.example.obdgasolina

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.github.eltonvs.obd.command.ObdCommand
import com.github.eltonvs.obd.command.ObdRawResponse
import com.github.eltonvs.obd.connection.ObdDeviceConnection
import kotlinx.coroutines.*
import java.io.IOException
import java.util.*
import kotlin.coroutines.CoroutineContext

class FuelLevelService : Service(), CoroutineScope {

    private var serviceJob = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + serviceJob

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var selectedDeviceAddress: String? = null
    private var selectedDevice: BluetoothDevice? = null
    private var obdConnection: ObdDeviceConnection? = null
    private var socket: BluetoothSocket? = null
    private val uuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val fuelLevelCommand = FuelLevelInputCommand()
    private val handler = Handler(Looper.getMainLooper())
    private var isReading = false
    private var notificationManager: NotificationManager? = null
    private val NOTIFICATION_CHANNEL_ID = "FuelLevelChannel"
    private val NOTIFICATION_ID = 1

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_START_READING" -> {
                selectedDeviceAddress = intent.getStringExtra("device_address")
                startFuelLevelReading()
                showNotification("Servicio de lectura de gasolina iniciado")
            }
            "ACTION_STOP_READING" -> {
                stopFuelLevelReading()
                stopForeground(true)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // No proporcionaremos enlace en este caso, usaremos Intents para la comunicación
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Nivel de Gasolina en Segundo Plano"
            val descriptionText = "Muestra el nivel de gasolina actual"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun showNotification(contentText: String) {
        val pendingIntent: PendingIntent =
            Intent(this, MainActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(this, 0, notificationIntent,
                    PendingIntent.FLAG_IMMUTABLE)
            }

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle("Nivel de Gasolina")
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setOngoing(true) // Para que no se pueda descartar fácilmente

        startForeground(NOTIFICATION_ID, builder.build())
    }

    private fun startFuelLevelReading() {
        if (isReading) return
        isReading = true
        selectedDeviceAddress?.let { address ->
            selectedDevice = bluetoothAdapter?.getRemoteDevice(address)
            selectedDevice?.let { device ->
                connectToDevice(device)
            } ?: run {
                stopFuelLevelReading()
                showNotification("Error: Dispositivo Bluetooth no encontrado")
            }
        } ?: run {
            stopFuelLevelReading()
            showNotification("Error: Dirección del dispositivo no proporcionada")
        }
    }

    private fun stopFuelLevelReading() {
        isReading = false
        handler.removeCallbacks(periodicFuelLevelReading)
        disconnectFromDevice()
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            showNotification("Permiso de Bluetooth Connect denegado")
            stopSelf()
            return
        }
        launch {
            try {
                socket = device.createRfcommSocketToServiceRecord(uuid)
                socket?.connect()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FuelLevelService, "Conectado a ${device.name}", Toast.LENGTH_SHORT).show()
                }

                val inputStream = socket?.inputStream
                val outputStream = socket?.outputStream
                if (inputStream != null && outputStream != null) {
                    obdConnection = ObdDeviceConnection(inputStream, outputStream)
                    startPeriodicReading()
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FuelLevelService, "Error al obtener streams del socket", Toast.LENGTH_SHORT).show()
                    }
                    disconnectFromDevice()
                }

            } catch (e: IOException) {
                e.printStackTrace()
                val errorMessage = "Error al conectar a ${device.name}: ${e.message}"
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FuelLevelService, errorMessage, Toast.LENGTH_LONG).show()
                }
                disconnectFromDevice()
            }
        }
    }

    private val periodicFuelLevelReading = object : Runnable {
        override fun run() {
            if (socket?.isConnected == true && obdConnection != null) {
                sendObdCommand(fuelLevelCommand)
            } else if (isReading) {
                // Reconectar si se perdió la conexión y todavía se está intentando leer
                selectedDevice?.let { connectToDevice(it) }
            }
            if (isReading) {
                handler.postDelayed(this, 5000) // Leer cada 5 segundos
            }
        }
    }

    private fun startPeriodicReading() {
        handler.post(periodicFuelLevelReading)
    }

    private fun sendObdCommand(command: ObdCommand) {
        launch {
            try {
                val response = obdConnection?.run(command)
                withContext(Dispatchers.Main) {
                    if (response != null) {
                        val fuelLevel = response.value
                        val unit = response.unit
                        showNotification("Nivel de Gasolina: $fuelLevel $unit")
                    } else {
                        showNotification("No se pudo leer el nivel de gasolina")
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FuelLevelService, "Error al enviar comando: ${e.message}", Toast.LENGTH_LONG).show()
                }
                disconnectFromDevice()
            }
        }
    }

    private fun disconnectFromDevice() {
        try {
            socket?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            socket = null
            obdConnection = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopFuelLevelReading()
        serviceJob.cancel()
        disconnectFromDevice()
    }
}

// 1. Definir la clase FuelLevelInputCommand
class FuelLevelInputCommand : ObdCommand() {
    override val tag = "FUEL_LEVEL_INPUT"
    override val name = "Fuel Level Input"
    override val mode = "01"
    override val pid = "2F"  // Cambiar a 2F
    override val defaultUnit = "%"
    override val handler: (ObdRawResponse) -> String = { response ->
        try {
            println("Respuesta OBD cruda: ${response.processedValue}")
            val cleanValue = response.processedValue.replace(" ", "") // Limpiar espacios
            if (cleanValue.length < 6) throw IllegalArgumentException("Respuesta demasiado corta")
            val hexValue = cleanValue.substring(8, 10) // Tomar el byte de datos
            val rawValue = Integer.parseInt(hexValue, 16) // Convertir a entero (0-255)
            val percentage = (rawValue * 100.0 / 255.0).toInt() // Escalar a porcentaje
            println("Valor hexadecimal: $hexValue, Porcentaje: $percentage")
            "$percentage"
        } catch (e: Exception) {
            "N/A"
        }
    }
}