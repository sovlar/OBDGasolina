package com.example.obdgasolina

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.github.eltonvs.obd.command.ObdCommand
import com.github.eltonvs.obd.command.ObdRawResponse
import com.github.eltonvs.obd.connection.ObdDeviceConnection
import kotlinx.coroutines.*
import java.io.IOException
import java.util.*
import kotlin.coroutines.CoroutineContext

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

// 2. Clase MainActivity
class MainActivity : AppCompatActivity(), CoroutineScope {

    private var job: Job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private lateinit var pairedDevices: MutableSet<BluetoothDevice>
    private lateinit var devicesListView: ListView
    private lateinit var devicesAdapter: ArrayAdapter<String>
    private var selectedDevice: BluetoothDevice? = null
    private var obdConnection: ObdDeviceConnection? = null
    private var socket: BluetoothSocket? = null
    private val uuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private lateinit var fuelLevelTextView: TextView
    private lateinit var getFuelLevelButton: Button

    private val fuelLevelCommand = FuelLevelInputCommand()

    // 3. Launchers para resultados de actividades
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

    // 4. onCreate
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        devicesListView = findViewById(R.id.devicesListView)
        devicesAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        devicesListView.adapter = devicesAdapter

        fuelLevelTextView = findViewById(R.id.fuelLevelTextView)
        getFuelLevelButton = findViewById(R.id.btnGetFuelLevel)

        devicesListView.setOnItemClickListener { _, _, position, _ ->
            val deviceInfo = devicesAdapter.getItem(position)
            val deviceAddressWithStatus = deviceInfo?.substringAfterLast("\n")
            val deviceAddress = deviceAddressWithStatus?.substringBefore(" ")

            selectedDevice = bluetoothAdapter?.getRemoteDevice(deviceAddress)
            if (selectedDevice != null) {
                Toast.makeText(this, "Dispositivo seleccionado: ${selectedDevice?.name}", Toast.LENGTH_SHORT).show()
                connectToDevice(selectedDevice!!)
            }
        }

        getFuelLevelButton.setOnClickListener {
            if (obdConnection != null) {
                sendObdCommand(fuelLevelCommand)
            } else {
                Toast.makeText(this, "No hay conexión OBD establecida", Toast.LENGTH_SHORT).show()
            }
        }

        checkBluetoothAndFindDevices()
    }

    // 5. Funciones para Bluetooth y permisos
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

    // 6. Conectar al dispositivo Bluetooth
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun connectToDevice(device: BluetoothDevice) {
        try {
            socket = device.createRfcommSocketToServiceRecord(uuid)
            socket?.connect()
            runOnUiThread { Toast.makeText(this, "Conectado a ${device.name}", Toast.LENGTH_SHORT).show() }

            val inputStream = socket?.inputStream
            val outputStream = socket?.outputStream
            if (inputStream != null && outputStream != null) {
                obdConnection = ObdDeviceConnection(inputStream, outputStream)
                startFuelLevelReading()
                runOnUiThread { Toast.makeText(this, "Conexión OBD lista", Toast.LENGTH_SHORT).show() }
            } else {
                runOnUiThread { Toast.makeText(this, "Error al obtener streams del socket", Toast.LENGTH_SHORT).show() }
                socket?.close()
                socket = null
            }

        } catch (e: IOException) {
            e.printStackTrace()
            val errorMessage = "Error al conectar a ${device.name}: ${e.message}"
            runOnUiThread { Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show() }
        } finally {
            if (socket != null && !socket!!.isConnected) {
                try {
                    socket!!.close()
                    socket = null
                } catch (closeException: IOException) {
                    closeException.printStackTrace()
                }
            }
        }
    }

    // 7.  Leer el nivel de combustible cada 5 segundos
    private fun startFuelLevelReading() {
        launch(Dispatchers.IO) {
            while (socket?.isConnected == true) {
                try {
                    val response = obdConnection?.run(fuelLevelCommand)
                    runOnUiThread {
                        if (response != null) {
                            fuelLevelTextView.text = "Nivel: ${response.value} %"
                        } else {
                            fuelLevelTextView.text = "Nivel: N/A"
                        }
                    }
                    delay(10000)
                } catch (e: Exception) {
                    e.printStackTrace()
                    runOnUiThread {
                        fuelLevelTextView.text = "Error: ${e.message}"
                        Toast.makeText(
                            this@MainActivity,
                            "Error al leer el nivel de combustible: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            runOnUiThread {
                Toast.makeText(
                    this@MainActivity,
                    "Lectura periódica del nivel de combustible detenida",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // 8. Función para enviar comandos OBD
    private fun sendObdCommand(command: ObdCommand) {
        runBlocking(Dispatchers.IO) {
            try {
                val response = obdConnection?.run(command)
                runOnUiThread {
                    if (response != null) {
                        val fuelLevel = response.value
                        val unit = response.unit
                        fuelLevelTextView.text = "$fuelLevel $unit"
                        Toast.makeText(
                            this@MainActivity,
                            "Nivel de Gasolina: $fuelLevel $unit",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        fuelLevelTextView.text = "No se pudo leer el nivel de gasolina"
                        Toast.makeText(
                            this@MainActivity,
                            "No se recibió respuesta para el nivel de gasolina",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Error al enviar comando: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // 9. onDestroy
    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        try {
            socket?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            obdConnection = null
            socket = null
        }
    }
}

