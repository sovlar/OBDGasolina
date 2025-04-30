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
    private var obdFuelLevel = "0"
    private var currentLocation: Location? = null
    private var timestamp = ""
    private var obdSpeed = "0"

    // Comando para velocidad (PID 0D)
    private val speedCommand = SpeedInputCommand()

    // Componentes de Bluetooth (sin cambios)
    private var bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var selectedDeviceAddress: String? = null
    private var obdConnection: ObdDeviceConnection? = null
    private var socket: BluetoothSocket? = null
    private val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val fuelLevelCommand = FuelLevelInputCommand()

    // Componentes de Ubicación (adaptación de tu método)
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationRequest: LocationRequest? = null
    private var locationCallback: LocationCallback? = null

    // Configuración de UDP
    private val dominios = arrayOf(
        "geofind-an.ddns.net",
        "geofind-al.ddns.net",
        "geofind-fe.ddns.net",
        "geofind-ha.ddns.net"
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

    // Métodos de Bluetooth (sin cambios)
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
                    // Leer combustible
                    val fuelResponse = obdConnection?.run(fuelLevelCommand)
                    if (fuelResponse != null && fuelResponse.value != null) {
                        obdFuelLevel = fuelResponse.value.toString()
                    }

                    // Leer velocidad
                    val speedResponse = obdConnection?.run(speedCommand)
                    if (speedResponse != null && speedResponse.value != null) {
                        obdSpeed = speedResponse.value.toString()
                    }

                    sendBroadcast(Intent("OBD_UPDATE").apply {
                        putExtra("FUEL_LEVEL", obdFuelLevel)
                        putExtra("SPEED", obdSpeed)
                    })
                    delay(5000)
                } catch (e: Exception) {
                    e.printStackTrace()
                    // Si hay error, no actualiza los valores y se mantienen en 0
                }
            }
        }
    }

    // Métodos de Ubicación (adaptación de tu código)
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

                    // <<--- AGREGA ESTE BROADCAST PARA LA UI
                    sendBroadcast(Intent("LOCATION_UPDATE").apply {
                        putExtra("LATITUD", location.latitude.toString())
                        putExtra("LONGITUD", location.longitude.toString())
                        putExtra("TIMESTAMP", timestamp)
                    })
                }
            }
        }


        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("Location", "Permiso de ubicación denegado")
            return
        }

        // <<--- AGREGA ESTE BLOQUE PARA FORZAR INICIO
        fusedLocationClient?.requestLocationUpdates(
            locationRequest ?: return,
            locationCallback ?: return,
            null
        )?.addOnFailureListener {
            Log.e("Location", "Error al iniciar ubicación: ${it.message}")
        }

        showNotification("Ubicación activa")
    }

    private fun stopLocationService() {
        locationCallback?.let { callback ->
            fusedLocationClient?.removeLocationUpdates(callback)
        }
        locationRequest = null
        locationCallback = null
        fusedLocationClient = null
    }

    private fun sendUDPData() {
        val lat = currentLocation?.latitude?.toString() ?: "0.0"
        val lon = currentLocation?.longitude?.toString() ?: "0.0"
        val timestamp = timestamp
        val fuel = obdFuelLevel.ifEmpty { "0" } // <<--- Si está vacío, usa "0"
        val speed = obdSpeed.ifEmpty { "0" }   // <<--- Si está vacío, usa "0"
        val message = "$lat,$lon,$timestamp,$speed,$fuel" // <<--- AGREGA "speed"
        Log.d("UDP", "Enviando: $message")

        dominios.forEach { dominio ->
            Thread {
                try {
                    val address = InetAddress.getByName(dominio)
                    val packet = DatagramPacket(message.toByteArray(), message.length, address, puertoUDP)
                    DatagramSocket().use { socket -> socket.send(packet) }
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

    // Comando OBD (sin cambios)
    private inner class FuelLevelInputCommand : ObdCommand() {
        override val tag = "FUEL_LEVEL"
        override val name = "Fuel Level"
        override val mode = "01"
        override val pid = "2F"
        override val defaultUnit = ""

        override val handler: (ObdRawResponse) -> String = { response ->
            try {
                val hexValue = response.processedValue.substring(8, 10)
                val percentage = (Integer.parseInt(hexValue, 16) * 100 / 255).toInt()
                "$percentage "
            } catch (e: Exception) {
                "0"
            }
        }
    }
    private inner class SpeedInputCommand : ObdCommand() {
        override val tag = "SPEED"
        override val name = "Speed"
        override val mode = "01"
        override val pid = "0D" // PID para velocidad en km/h
        override val defaultUnit = ""

        override val handler: (ObdRawResponse) -> String = { response ->
            try {
                val speedValue = response.processedValue.substring(8, 10) // Primer byte es la velocidad en km/h
                val speed = Integer.parseInt(speedValue, 16).toString()
                "$speed"
            } catch (e: Exception) {
                "0"
            }
        }
    }
}