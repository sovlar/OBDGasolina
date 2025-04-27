package com.example.obdgasolina

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlin.coroutines.CoroutineContext

class MainActivity : AppCompatActivity(), CoroutineScope {

    private var job: Job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private lateinit var pairedDevices: MutableSet<BluetoothDevice>
    private lateinit var devicesListView: ListView
    private lateinit var devicesAdapter: ArrayAdapter<String>
    private var selectedDeviceAddress: String? = null
    private lateinit var getFuelLevelButton: Button
    private var isServiceRunning = false

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                findAvailableDevices()
            } else {
                Toast.makeText(this, "Bluetooth no activado", Toast.LENGTH_SHORT).show()
            }
        }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) {
                findAvailableDevices()
            } else {
                Toast.makeText(this, "Permisos de ubicación necesarios para escanear", Toast.LENGTH_SHORT).show()
            }
        }

    private val bluetoothPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) {
                findAvailableDevices()
            } else {
                Toast.makeText(this, "Permisos de Bluetooth necesarios para escanear/conectar", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        devicesListView = findViewById(R.id.devicesListView)
        devicesAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        devicesListView.adapter = devicesAdapter

        getFuelLevelButton = findViewById(R.id.btnGetFuelLevel)

        devicesListView.setOnItemClickListener { _, _, position, _ ->
            val deviceInfo = devicesAdapter.getItem(position)
            val deviceAddressWithStatus = deviceInfo?.substringAfterLast("\n")
            selectedDeviceAddress = deviceAddressWithStatus?.substringBefore(" ")
            if (selectedDeviceAddress != null) {
                val deviceName = deviceInfo?.substringBefore("\n")
                Toast.makeText(this, "Dispositivo seleccionado: $deviceName", Toast.LENGTH_SHORT).show()
            }
        }

        getFuelLevelButton.setOnClickListener {
            if (selectedDeviceAddress != null) {
                if (!isServiceRunning) {
                    startFuelLevelService(selectedDeviceAddress!!)
                    getFuelLevelButton.text = "Detener Lectura de Gasolina"
                    isServiceRunning = true
                } else {
                    stopFuelLevelService()
                    getFuelLevelButton.text = "Obtener Nivel de Gasolina"
                    isServiceRunning = false
                }
            } else {
                Toast.makeText(this, "Por favor, selecciona un dispositivo primero", Toast.LENGTH_SHORT).show()
            }
        }

        checkBluetoothAndFindDevices()
    }

    private fun checkBluetoothAndFindDevices() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth no soportado en este dispositivo", Toast.LENGTH_SHORT).show()
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        } else {
            checkPermissionsAndFindDevices()
        }
    }

    private fun checkPermissionsAndFindDevices() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val permissionsToRequest = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
            val permissionsNotGranted = permissionsToRequest.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (permissionsNotGranted.isNotEmpty()) {
                bluetoothPermissionLauncher.launch(permissionsNotGranted.toTypedArray())
                return
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
                return
            }
        }
        findAvailableDevices()
    }

    private fun findAvailableDevices() {
        devicesAdapter.clear()
        pairedDevices = bluetoothAdapter?.bondedDevices ?: mutableSetOf()
        pairedDevices.forEach { device ->
            devicesAdapter.add("${device.name}\n${device.address} (Emparejado)")
        }
        devicesAdapter.notifyDataSetChanged()

        Toast.makeText(this, "Mostrando dispositivos emparejados", Toast.LENGTH_SHORT).show()
    }

    private fun startFuelLevelService(deviceAddress: String) {
        Intent(this, FuelLevelService::class.java).apply {
            action = "ACTION_START_READING"
            putExtra("device_address", deviceAddress)
            startForegroundService(this)
        }
    }

    private fun stopFuelLevelService() {
        Intent(this, FuelLevelService::class.java).apply {
            action = "ACTION_STOP_READING"
            startService(this) // No necesitamos foreground para detener
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }
}