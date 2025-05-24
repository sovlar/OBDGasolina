package com.example.obdgasolina

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import android.content.BroadcastReceiver
import androidx.annotation.RequiresPermission


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
    private lateinit var speedTextView: TextView
    private lateinit var spinnerVehicle: Spinner

    // Valor predeterminado
    private var selectedVehicleId = "1"

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

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d("MainActivity", "Notification permission granted")
                // Permiso concedido, puedes proceder a mostrar notificaciones
                // o reintentar la acción que requería el permiso.
            } else {
                Log.d("MainActivity", "Notification permission denied")
                Toast.makeText(this, "Se necesitan permisos para notificaciones", Toast.LENGTH_SHORT).show() // Permiso denegado. Informa al usuario o deshabilita la funcionalidad.
                // Podrías mostrar un Snackbar explicando por qué necesitas el permiso.
            }
        }


    //@RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

        // Inicializar UI
        devicesListView = findViewById(R.id.devicesListView)
        fuelLevelTextView = findViewById(R.id.fuelLevelTextView)
        btnConnectOBD = findViewById(R.id.btnConnectOBD)
        locationTextView = findViewById(R.id.locationTextView)
        btnStartStopLocation = findViewById(R.id.btnStartStopLocation)
        speedTextView = findViewById(R.id.speedTextView) // <<--- AGREGADO

        // Inicializar Spinner
        spinnerVehicle = findViewById(R.id.spinnerVehicle)
        spinnerVehicle.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedVehicleId = (position + 1).toString() // 1 o 2
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedVehicleId = "1" // Valor predeterminado si no hay selección
            }
        }

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
        val intentFilter = IntentFilter().apply {
            addAction("LOCATION_UPDATE")
            addAction("OBD_UPDATE")
        }
        registerReceiver(broadcastReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)

        // Iniciar escaneo de dispositivos Bluetooth
        checkBluetoothAndFindDevices()
    }

    // Métodos de Bluetooth
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
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

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
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

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
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
                putExtra("VEHICLE_ID", selectedVehicleId) // <<--- AGREGA ESTE EXTRA
                btnConnectOBD.text = "Desconectar OBD"
            }
        }
        startService(intent)
        isOBDServiceRunning = !isOBDServiceRunning
    }

    // Métodos de Ubicación
    @RequiresApi(Build.VERSION_CODES.Q)
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
            putExtra("VEHICLE_ID", selectedVehicleId)
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