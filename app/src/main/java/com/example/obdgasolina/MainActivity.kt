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
import com.github.eltonvs.obd.command.fuel.FuelLevelCommand // Importa el comando de nivel de combustible
import com.github.eltonvs.obd.connection.ObdDeviceConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.*

class MainActivity : AppCompatActivity() {

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private lateinit var pairedDevices: MutableSet<BluetoothDevice>
    private lateinit var devicesListView: ListView
    private lateinit var devicesAdapter: ArrayAdapter<String>
    private var selectedDevice: BluetoothDevice? = null
    private var obdConnection: ObdDeviceConnection? = null
    private val uuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // UUID estándar para SPP
    private lateinit var fuelLevelTextView: TextView // Para mostrar el nivel de gasolina
    private lateinit var getFuelLevelButton: Button // Botón para solicitar el nivel

    // Launcher para solicitar activar Bluetooth
    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                findAvailableDevices()
            } else {
                Toast.makeText(this, "Bluetooth no activado", Toast.LENGTH_SHORT).show()
            }
        }

    // Launcher para solicitar permisos de ubicación (para escaneo en versiones < Android 12)
    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) {
                findAvailableDevices()
            } else {
                Toast.makeText(this, "Permisos de ubicación necesarios para escanear", Toast.LENGTH_SHORT).show()
            }
        }

    // Launcher para solicitar permisos de conexión/escaneo (para Android 12+)
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
        setContentView(R.layout.activity_main) // Reemplaza con el nombre de tu layout

        devicesListView = findViewById(R.id.devicesListView) // Asegúrate de que el ID coincida en tu layout
        devicesAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
        devicesListView.adapter = devicesAdapter

        fuelLevelTextView = findViewById(R.id.fuelLevelTextView) // Asegúrate de tener un TextView con este ID en tu layout
        getFuelLevelButton = findViewById(R.id.btnGetFuelLevel) // Asegúrate de tener un Button con este ID en tu layout

        devicesListView.setOnItemClickListener { _, _, position, _ ->
            val deviceInfo = devicesAdapter.getItem(position)
            val deviceAddressWithStatus = deviceInfo?.substringAfterLast("\n")
            val deviceAddress = deviceAddressWithStatus?.substringBefore(" ") // Tomar la parte antes del espacio

            selectedDevice = bluetoothAdapter?.getRemoteDevice(deviceAddress)
            if (selectedDevice != null) {
                Toast.makeText(this, "Dispositivo seleccionado: ${selectedDevice?.name}", Toast.LENGTH_SHORT).show()
                connectToDevice(selectedDevice!!)
            }
        }

        getFuelLevelButton.setOnClickListener {
            if (obdConnection != null) {
                sendObdCommand(FuelLevelCommand())
            } else {
                Toast.makeText(this, "No hay conexión OBD establecida", Toast.LENGTH_SHORT).show()
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

        // Para descubrir nuevos dispositivos, podrías usar bluetoothAdapter?.startDiscovery(),
        // pero esto requiere un BroadcastReceiver para escuchar los resultados y puede consumir recursos.
        // Para este ejemplo, nos centraremos en los dispositivos ya emparejados.
    }

    private var socket: BluetoothSocket? = null // Declarar socket como variable de clase

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun connectToDevice(device: BluetoothDevice) {
        try {
            // Crear un socket Bluetooth
            socket = device.createRfcommSocketToServiceRecord(uuid)
            socket?.connect() // Intentar conectar inmediatamente después de crear el socket
            Toast.makeText(this, "Conectado a ${device.name}", Toast.LENGTH_SHORT).show()

            // Inicializar ObdDeviceConnection
            val inputStream = socket?.inputStream
            val outputStream = socket?.outputStream
            if (inputStream != null && outputStream != null) {
                obdConnection = ObdDeviceConnection(inputStream, outputStream)
                // Ahora puedes usar 'obdConnection' para enviar comandos OBD (Paso 5)
                Toast.makeText(this, "Conexión OBD lista", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Error al obtener streams del socket", Toast.LENGTH_SHORT).show()
                // Cerrar el socket si los streams no se obtuvieron correctamente
                socket?.close()
                socket = null
            }

        } catch (e: IOException) {
            e.printStackTrace()
            val errorMessage = "Error al conectar a ${device.name}: ${e.message}"
            runOnUiThread { Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show() }
            // El cierre del socket ahora se manejará en el bloque finally
        } finally {
            // Asegurarse de cerrar el socket si ocurrió una excepción o si los streams no se obtuvieron
            if (socket != null) {
                if (socket!!.isConnected == false) {
                    try {
                        socket!!.close()
                        socket = null
                    } catch (closeException: IOException) {
                        closeException.printStackTrace()
                    }
                }
            }
        }
    }

    // Función para enviar comandos OBD
    private fun sendObdCommand(command: ObdCommand) {
        runBlocking(Dispatchers.IO) {
            try {
                val response = obdConnection?.run(command)
                runOnUiThread {
                    if (response != null) {
                        val fuelLevel = response.value
                        val unit = response.unit
                        fuelLevelTextView.text = "$fuelLevel $unit"
                        Toast.makeText(this@MainActivity, "Nivel de Gasolina: $fuelLevel $unit", Toast.LENGTH_LONG).show()
                        // Aquí procesarás la respuesta (Paso 6)
                    } else {
                        fuelLevelTextView.text = "No se pudo leer el nivel de gasolina"
                        Toast.makeText(this@MainActivity, "No se recibió respuesta para el nivel de gasolina", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
                runOnUiThread { Toast.makeText(this@MainActivity, "Error al enviar comando: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Asegurarse de cerrar la conexión al destruir la actividad
        try {
            //obdConnection?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
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


