package com.example.obdgasolina

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import android.content.*
import android.os.*
import com.google.android.gms.location.*
import java.net.*
import java.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
class MainActivity : AppCompatActivity(), CoroutineScope {

    private var job: Job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    // Agrega esta línea en las variables globales de MainActivity
    private lateinit var devicesAdapter: ArrayAdapter<String>

    // Componentes de la UI
    private lateinit var devicesListView: ListView
    private lateinit var fuelLevelTextView: TextView
    private lateinit var btnConnectOBD: Button
    private lateinit var locationTextView: TextView
    private lateinit var btnStartStopLocation: Button

    // Variables compartidas entre módulos
    private var selectedDeviceAddress: String? = null
    private var isLocationSending = false
    private var fuelLevel: String = "-"

    // Permisos y lanzadores
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val enableBluetoothLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ -> checkBluetoothAndFindDevices() }
    private val bluetoothPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.all { it.value }) findAvailableDevices()
    }
    private val locationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.all { it.value }) checkLocationPermissions()
    }

    private var isOBDServiceRunning = false // <<--- Agrega esta línea en las variables globales

    // Broadcast para recibir actualizaciones
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "LOCATION_UPDATE" -> {
                    val lat = intent.getStringExtra("LATITUD") ?: ""
                    val lon = intent.getStringExtra("LONGITUD") ?: ""
                    val timestamp = intent.getStringExtra("TIMESTAMP") ?: ""
                    locationTextView.text = "Ubicación: Lat $lat, Lon $lon - $timestamp"
                }
                "FUEL_UPDATE" -> {
                    fuelLevel = intent.getStringExtra("FUEL_LEVEL") ?: "-"
                    fuelLevelTextView.text = "Nivel de Gasolina: $fuelLevel"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Inicializar UI
        devicesListView = findViewById(R.id.devicesListView)
        devicesAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1) // <<--- NUEVO
        devicesListView.adapter = devicesAdapter // <<--- NUEVO

        fuelLevelTextView = findViewById(R.id.fuelLevelTextView)
        btnConnectOBD = findViewById(R.id.btnConnectOBD)
        locationTextView = findViewById(R.id.locationTextView)
        btnStartStopLocation = findViewById(R.id.btnStartStopLocation)

        // Configurar Listeners
        devicesListView.setOnItemClickListener { _, _, position, _ ->
            val deviceInfo = devicesAdapter.getItem(position) // Usa devicesAdapter
            if (deviceInfo != null) {
                val deviceAddressWithStatus = deviceInfo.substringAfterLast("\n")
                selectedDeviceAddress = deviceAddressWithStatus.substringBefore(" ")
            }
        }

        btnConnectOBD.setOnClickListener {
            if (selectedDeviceAddress != null) {
                toggleOBDConnection()
                isOBDServiceRunning = !isOBDServiceRunning // <<--- Invierte el estado
            } else {
                Toast.makeText(this, "Conecta un dispositivo OBD2 primero", Toast.LENGTH_SHORT).show()
            }
        }

        btnStartStopLocation.setOnClickListener {
            if (isLocationSending) {
                stopLocationService()
            } else {
                checkLocationPermissions()
            }
        }

        // Registrar BroadcastReceiver
        registerReceiver(broadcastReceiver, IntentFilter().apply {
            addAction("LOCATION_UPDATE")
            addAction("FUEL_UPDATE")
        })

        // Inicializar Bluetooth
        checkBluetoothAndFindDevices()
    }

    // Métodos de Bluetooth
    private fun checkBluetoothAndFindDevices() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth no soportado", Toast.LENGTH_SHORT).show()
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            checkBluetoothPermissions()
        }
    }

    private fun checkBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
            if (!allPermissionsGranted(permissions)) {
                bluetoothPermissionLauncher.launch(permissions)
            } else {
                findAvailableDevices()
            }
        } else {
            findAvailableDevices()
        }
    }

    private fun findAvailableDevices() {
        devicesAdapter.clear()
        val pairedDevices = bluetoothAdapter?.bondedDevices ?: mutableSetOf()
        pairedDevices.forEach { device ->
            devicesAdapter.add("${device.name}\n${device.address} (Emparejado)")
        }
        devicesAdapter.notifyDataSetChanged()
    }


    private fun toggleOBDConnection() {
        val intent = Intent(this, CombinedService::class.java)
        if (isOBDServiceRunning) { // <<--- Define esta variable (explicación abajo)
            intent.action = "ACTION_STOP_OBD"
            stopService(intent)
            btnConnectOBD.text = "Conectar OBD"
        } else {
            intent.action = "ACTION_START_OBD"
            intent.putExtra("device_address", selectedDeviceAddress!!)
            startService(intent)
            btnConnectOBD.text = "Desconectar OBD"
        }
    }

    // Métodos de Ubicación
    private fun checkLocationPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        )
        if (!allPermissionsGranted(permissions)) {
            locationPermissionLauncher.launch(permissions)
        } else {
            startLocationService()
        }
    }

    private fun startLocationService() {
        btnStartStopLocation.text = "Detener Envíos"
        isLocationSending = true
        startService(Intent(this, CombinedService::class.java).apply {
            action = "ACTION_START_LOCATION"
        })
    }

    private fun stopLocationService() {
        btnStartStopLocation.text = "Iniciar Envíos"
        isLocationSending = false
        startService(Intent(this, CombinedService::class.java).apply {
            action = "ACTION_STOP_LOCATION"
        })
    }

    private fun allPermissionsGranted(permissions: Array<String>): Boolean {
        return permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(broadcastReceiver)
        job.cancel()
    }
}