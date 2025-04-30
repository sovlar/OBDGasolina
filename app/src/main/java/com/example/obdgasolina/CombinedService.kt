package com.example.obdgasolina

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
import android.os.*
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import java.io.IOException
import java.net.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.CoroutineContext
import com.github.eltonvs.obd.command.ObdCommand
import com.github.eltonvs.obd.command.ObdRawResponse
import com.github.eltonvs.obd.connection.ObdDeviceConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CombinedService : Service(), CoroutineScope {

    private var serviceJob = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + serviceJob

    // Variables compartidas entre módulos
    private var obdFuelLevel = "-"
    private var currentLocation: Location? = null
    private var timestamp = ""

    // Componentes de Bluetooth
    private var bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var selectedDeviceAddress: String? = null
    private var obdConnection: ObdDeviceConnection? = null
    private var socket: BluetoothSocket? = null
    private val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val fuelLevelCommand = FuelLevelInputCommand()

    // Componentes de Ubicación
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback

    // Configuración de UDP
    private val dominios = arrayOf(
        "geofind-an.duckdns.org",
        "geofind-al.duckdns.org",
        "geofind-fe.duckdns.org",
        "geofind-ha.duckdns.org"
    )
    private val puertoUDP = 59595

    // Notificación
    private val NOTIFICATION_CHANNEL_ID = "CombinedServiceChannel"
    private val NOTIFICATION_ID = 1
    private var notificationManager: NotificationManager? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_START_OBD" -> startOBDService(intent.getStringExtra("device_address") ?: "")
            "ACTION_STOP_OBD" -> stopOBDService()
            "ACTION_START_LOCATION" -> startLocationService()
            "ACTION_STOP_LOCATION" -> stopLocationService()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Métodos de Bluetooth
    private fun startOBDService(deviceAddress: String) {
        selectedDeviceAddress = deviceAddress
        connectToDevice()
    }

    private fun stopOBDService() {
        disconnectFromDevice()
    }

    private fun connectToDevice() {
        val deviceAddress = selectedDeviceAddress ?: return
        val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
        if (device == null) {
            Log.e("Bluetooth", "Dispositivo no encontrado: $deviceAddress")
            return
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        socket = device.createRfcommSocketToServiceRecord(uuid)
        try {
            socket?.connect()
            val inputStream = socket?.inputStream
            val outputStream = socket?.outputStream

            if (inputStream != null && outputStream != null) {
                obdConnection = ObdDeviceConnection(inputStream, outputStream)
                startFuelLevelReading()
            } else {
                Log.e("Bluetooth", "Error: inputStream o outputStream son nulos")
            }
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            if (socket != null && !socket!!.isConnected) {
                try {
                    socket?.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun disconnectFromDevice() {
        socket?.close()
    }

    private fun startFuelLevelReading() {
        GlobalScope.launch {
            while (true) {
                try {
                    val response = obdConnection?.run(fuelLevelCommand)
                    if (response != null) {
                        obdFuelLevel = response.value.toString()
                        sendBroadcast(Intent("FUEL_UPDATE").putExtra("FUEL_LEVEL", obdFuelLevel))
                    }
                    delay(5000)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // Métodos de Ubicación
    private fun startLocationService() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationRequest = LocationRequest.create().apply {
            interval = 5000
            fastestInterval = 4000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    currentLocation = location
                    timestamp = getFormattedTimestamp(location.time)
                    sendUDPData()
                }
            }
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        fusedLocationClient?.requestLocationUpdates(locationRequest, locationCallback, null)
    }

    private fun stopLocationService() {
        fusedLocationClient?.removeLocationUpdates(locationCallback)
    }

    // UDP y notificaciones
    private fun sendUDPData() {
        val lat = currentLocation?.latitude?.toString() ?: "0.0"
        val lon = currentLocation?.longitude?.toString() ?: "0.0"
        val message = "lat=$lat,long=$lon,timestamp=$timestamp,fuel=$obdFuelLevel"
        Log.d("UDP", "Enviando: $message")

        dominios.forEach { dominio ->
            Thread {
                try {
                    val address = InetAddress.getByName(dominio)
                    val packet = DatagramPacket(message.toByteArray(), message.length, address, puertoUDP)
                    DatagramSocket().apply {
                        send(packet)
                        close()
                    }
                } catch (e: Exception) {
                    Log.e("UDP", "Error al enviar a $dominio", e)
                }
            }.start()
        }
    }

    private fun getFormattedTimestamp(timeInMillis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timeInMillis))
    }

    // Notificaciones
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Servicio Combinado",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun showNotification(contentText: String) {
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Servicios Activos")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationService()
        disconnectFromDevice()
        serviceJob.cancel()
    }

    // Comando OBD personalizado
    private inner class FuelLevelInputCommand : ObdCommand() {
        override val tag = "FUEL_LEVEL"
        override val name = "Fuel Level"
        override val mode = "01"
        override val pid = "2F"
        override val defaultUnit = "%"
        override val handler: (ObdRawResponse) -> String = { response ->
            try {
                val hexValue = response.processedValue.substring(8, 10)
                val percentage = (Integer.parseInt(hexValue, 16) * 100 / 255).toInt()
                "$percentage%"
            } catch (e: Exception) {
                "N/A"
            }
        }
    }
}