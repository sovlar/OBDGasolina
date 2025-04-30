package com.example.obdgasolina

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

class MainActivity : AppCompatActivity(), CoroutineScope {

    private var job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    // Componentes de la UI
    private lateinit var devicesListView: ListView
    private lateinit var fuelLevelTextView: TextView
    private lateinit var btnConnectOBD: Button
    private lateinit var locationTextView: TextView
    private lateinit var btnStartStopLocation: Button
    private lateinit var speedTextView: TextView // <<--- AGREGADO

    // Variables compartidas
    private var selectedDeviceAddress: String? = null
    private var isLocationActive = false
    private var isOBDServiceRunning = false

    // Adaptadores y componentes Bluetooth
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private lateinit var devicesAdapter: ArrayAdapter<String>

    // Lanzadores de permisos
    private val enableBluetoothLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
        checkBluetoothAndFindDevices()
    }

    private val bluetoothPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.all { it.value }) {
            findAvailableDevices()
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.all { it.value }) {
            startLocationService()
        } else {
            Toast.makeText(this, "Permisos de ubicación denegados", Toast.LENGTH_SHORT).show()
        }
    }

    // BroadcastReceiver para recibir actualizaciones
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "LOCATION_UPDATE" -> {
                    val lat = intent.getStringExtra("LATITUD") ?: "0.0"
                    val lon = intent.getStringExtra("LONGITUD") ?: "0.0"
                    val timestamp = intent.getStringExtra("TIMESTAMP") ?: "N/A"
                    locationTextView.text = "Ubicación: Lat $lat, Lon $lon - $timestamp"
                }
                "OBD_UPDATE" -> {
                    val fuel = intent.getStringExtra("FUEL_LEVEL") ?: "0"
                    fuelLevelTextView.text = "Nivel de Gasolina: $fuel %"

                    val speed = intent.getStringExtra("SPEED") ?: "0"
                    speedTextView.text = "Velocidad: $speed km/h" // <<--- CORREGIDO
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Inicializar UI
        devicesListView = findViewById(R.id.devicesListView)
        fuelLevelTextView = findViewById(R.id.fuelLevelTextView)
        btnConnectOBD = findViewById(R.id.btnConnectOBD)
        locationTextView = findViewById(R.id.locationTextView)
        btnStartStopLocation = findViewById(R.id.btnStartStopLocation)
        speedTextView = findViewById(R.id.speedTextView) // <<--- AGREGADO

        // Configurar adaptador para Bluetooth
        devicesAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        devicesListView.adapter = devicesAdapter

        // Listener para selección de dispositivo Bluetooth
        devicesListView.setOnItemClickListener { _, _, position, _ ->
            val deviceInfo = devicesAdapter.getItem(position) ?: return@setOnItemClickListener
            val deviceAddress = deviceInfo.substringAfterLast("\n").substringBefore(" ")
            selectedDeviceAddress = deviceAddress
        }

        // Botón de conexión OBD
        btnConnectOBD.setOnClickListener {
            if (selectedDeviceAddress != null) {
                toggleOBDService()
            } else {
                Toast.makeText(this, "Seleccione un dispositivo OBD2 primero", Toast.LENGTH_SHORT).show()
            }
        }

        // Botón de ubicación
        btnStartStopLocation.setOnClickListener {
            if (isLocationActive) {
                stopLocationService()
            } else {
                checkLocationPermissions()
            }
        }

        // Registrar BroadcastReceiver
        registerReceiver(broadcastReceiver, IntentFilter().apply {
            addAction("LOCATION_UPDATE")
            addAction("OBD_UPDATE") // <<--- ENSURE "OBD_UPDATE" IS ADDED
        })

        // Iniciar escaneo de dispositivos Bluetooth
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
        val pairedDevices = bluetoothAdapter?.bondedDevices ?: emptySet()
        pairedDevices.forEach { device ->
            devicesAdapter.add("${device.name}\n${device.address} (Emparejado)")
        }
        devicesAdapter.notifyDataSetChanged()
    }

    private fun toggleOBDService() {
        val intent = Intent(this, CombinedService::class.java).apply {
            if (isOBDServiceRunning) {
                action = "ACTION_STOP_OBD"
                btnConnectOBD.text = "Conectar OBD"
            } else {
                action = "ACTION_START_OBD"
                putExtra("device_address", selectedDeviceAddress!!)
                btnConnectOBD.text = "Desconectar OBD"
            }
        }
        startService(intent)
        isOBDServiceRunning = !isOBDServiceRunning
    }

    // Métodos de Ubicación
    private fun checkLocationPermissions() {
        val foregroundPermissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        val backgroundPermission = arrayOf(
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        )

        if (!allPermissionsGranted(foregroundPermissions)) {
            locationPermissionLauncher.launch(foregroundPermissions)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                !allPermissionsGranted(backgroundPermission)) {
                locationPermissionLauncher.launch(backgroundPermission)
            } else {
                startLocationService()
            }
        }
    }

    private fun allPermissionsGranted(permissions: Array<String>): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun startLocationService() {
        isLocationActive = true
        btnStartStopLocation.text = "Detener Ubicación"
        startService(Intent(this, CombinedService::class.java).apply {
            action = "ACTION_START_LOCATION"
        })
    }

    private fun stopLocationService() {
        isLocationActive = false
        btnStartStopLocation.text = "Iniciar Ubicación"
        startService(Intent(this, CombinedService::class.java).apply {
            action = "ACTION_STOP_LOCATION"
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(broadcastReceiver)
        job.cancel()
        stopLocationService()
        stopOBDService()
    }

    private fun stopOBDService() {
        val intent = Intent(this, CombinedService::class.java).apply {
            action = "ACTION_STOP_OBD"
        }
        startService(intent)
        isOBDServiceRunning = false
        btnConnectOBD.text = "Conectar OBD"
    }
}