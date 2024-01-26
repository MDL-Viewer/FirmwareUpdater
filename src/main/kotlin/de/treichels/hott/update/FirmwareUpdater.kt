package de.treichels.hott.update

import de.treichels.hott.decoder.*
import de.treichels.hott.decoder.HoTTFirmwareKt.detectDevice
import de.treichels.hott.decoder.HoTTFirmwareKt.getDeviceType
import de.treichels.hott.model.enums.*
import de.treichels.hott.model.firmware.Firmware
import de.treichels.hott.model.firmware.getFirmware
import de.treichels.hott.serial.SerialPort
import de.treichels.hott.ui.CallbackAdapter
import de.treichels.hott.ui.ExceptionDialog
import de.treichels.hott.ui.MessageDialog
import de.treichels.hott.util.Util
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.input.DragEvent
import javafx.scene.input.TransferMode
import javafx.scene.layout.Priority
import javafx.stage.FileChooser
import tornadofx.*
import java.io.File
import java.io.IOException
import java.util.*

@Suppress("UNCHECKED_CAST")
private val deviceList = (listOf(*ReceiverType.values(), *ModuleType.values(), *SensorType.values(), *ESCType.values()) as List<Registered<*>>).filter { it.orderNo.isNotEmpty() }

fun main(vararg args: String) {
    Thread.setDefaultUncaughtExceptionHandler { _, e -> ExceptionDialog.show(e) }
    launch<FirmwareUpdaterApp>(*args)
}

class FirmwareUpdaterApp : App() {
    override val primaryView = FirmwareUpdater::class
}

class FirmwareUpdater : View() {
    companion object {
        const val PREFERRED_PORT = "preferredPort"
        const val LAST_DIR = "lastDir"
        const val LAST_FILE = "lastFile"
        const val LAST_DEVICE = "lastDevice"

        private fun isFirmware(file: File) = file.exists() && file.isFile && file.canRead() && file.name.endsWith(".bin")
        private fun getFile(ev: DragEvent) = ev.dragboard.files.firstOrNull(::isFirmware)
    }

    // resources
    private val iconImage = resources.image("icon.png")

    // controls
    private var deviceType: ComboBox<Registered<*>> by singleAssign()
    private var textField by singleAssign<TextField>()
    private var portCombo by singleAssign<ComboBox<String>>()
    private var textArea by singleAssign<TextArea>()
    private var progressBar by singleAssign<ProgressBar>()
    private var startButton by singleAssign<Button>()

    // properties
    private val showModules = SimpleBooleanProperty(true)
    private val showReceivers = SimpleBooleanProperty(true)
    private val showSensors = SimpleBooleanProperty(true)
    private val showESCs = SimpleBooleanProperty(true)
    private val serialPortProperty = SimpleObjectProperty<HoTTTransmitter?>(null)
    private var serialPort by serialPortProperty

    // background activity
    private val service = FirmwareUpdateService()

    // UI
    override val root = vbox {
        spacing = 10.0
        paddingAll = 5.0

        // device type and com port
        hbox {
            spacing = 10.0

            vbox {
                spacing = 3.0
                hbox {
                    spacing = 5.0
                    label(messages["deviceType"]) {
                        style {
                            underline = true
                        }
                    }

                    checkbox(messages["Modules"], showModules) {
                        disableWhen { service.runningProperty() }
                        action { updateDeviceList() }
                    }
                    checkbox(messages["Receivers"], showReceivers) {
                        disableWhen { service.runningProperty() }
                        action { updateDeviceList() }
                    }
                    checkbox(messages["Sensors"], showSensors) {
                        disableWhen { service.runningProperty() }
                        action { updateDeviceList() }
                    }
                    checkbox(messages["ESCs"], showESCs) {
                        disableWhen { service.runningProperty() }
                        action { updateDeviceList() }
                    }
                }

                hbox {
                    spacing = 5.0
                    deviceType = combobox {
                        items.addAll(deviceList)
                        disableWhen { service.runningProperty() }
                        buttonCell = HoTTDeviceListCell()
                        setCellFactory { HoTTDeviceListCell() }
                        minHeight = 48.0
                        setOnAction { deviceTypeChanged() }
                    }
                    button(messages["detectDevice"]) {
                        minHeight = 48.0
                        disableWhen { serialPortProperty.isNull.or(service.runningProperty()) }
                        action { autoDetectDevice() }
                    }
                }
            }
            region {
                hgrow = Priority.SOMETIMES
            }

            vbox {
                spacing = 3.0
                label(messages["port"]) {
                    style {
                        underline = true
                    }
                }
                portCombo = combobox {
                    disableWhen { service.runningProperty() }
                    setOnAction {
                        val portName = value

                        if (portName != null) {
                            preferences { put(PREFERRED_PORT, portName) }
                            serialPort = HoTTTransmitter(SerialPort.getPort(portName))
                        }
                    }
                }
            }
        }

        // firmware file
        vbox {
            spacing = 3.0

            label(messages["file"]) {
                style {
                    underline = true
                }
            }
            hbox {
                alignment = Pos.CENTER_LEFT
                spacing = 5.0

                button(messages["select"]) {
                    disableWhen { service.runningProperty() }
                    action { selectFile() }
                }
                button(messages["download"]) {
                    disableWhen { deviceType.valueProperty().isNull.or(service.runningProperty()) }
                    action { checkOnline() }
                }
            }
            textField = textfield {
                hgrow = Priority.ALWAYS
                isEditable = false
                promptText = messages["selectFile"]
                prefWidth = 400.0
                disableWhen { service.runningProperty().or(textProperty().isEqualTo(messages["selectFile"])) }
                textProperty().addListener { _ -> fileNameChanged() }
            }
        }

        vbox {
            vgrow = Priority.ALWAYS
            spacing = 3.0

            label(messages["messages"]) {
                style {
                    underline = true
                }
            }
            textArea = textarea {
                prefHeight = 100.0
                isEditable = false
                style = "-fx-font-family: monospace"
                vgrow = Priority.ALWAYS
                hgrow = Priority.ALWAYS
                service.messageProperty().addListener { _, _, newValue -> appendText(newValue) }
            }
        }

        hbox {
            spacing = 5.0

            startButton = button(messages["start"]) {
                disableWhen { serialPortProperty.isNull.or(textField.textProperty().isEmpty) }
                action { if (service.isRunning) doCancel() else doUpdate() }
            }

            anchorpane {
                hgrow = Priority.ALWAYS

                progressBar = progressbar {
                    progress = 0.0
                    anchorpaneConstraints {
                        topAnchor = 0.0
                        leftAnchor = 0.0
                        rightAnchor = 0.0
                        bottomAnchor = 0.0
                    }
                }
            }
        }
    }

    private fun updateDeviceList() {
        deviceType.items = deviceList.filter {
            it is ModuleType && showModules.value || it is ReceiverType && showReceivers.value || it is SensorType && showSensors.value || it is ESCType && showESCs.value
        }.asObservable()
    }

    private fun deviceTypeChanged() {
        if (deviceType.value != null) {
            preferences {
                put(LAST_DEVICE, deviceType.value.name)
            }

            if (textField.text != null) {
                try {
                    // check that the current file matches the selected device type
                    val type = getDeviceType(serialPort,File(textField.text))
                    if (type?.productCode != deviceType.value.productCode) textField.text = null
                } catch (e: IOException) {
                    textField.text = null
                } catch (e: NullPointerException) {
                    textField.text = null
                }
            }
        }
    }

    private fun autoDetectDevice() {
        val dialog = Alert(Alert.AlertType.INFORMATION, messages["connectDevice"], ButtonType.CANCEL).apply {
            headerText = messages["detectDevice"]
            title = messages["deviceType"]
        }
        val task = runAsync {
            detectDevice(serialPort,CallbackAdapter(this))
        }.ui {
            // enable category of detected device
            when (it) {
                is ModuleType -> showModules.value = true
                is ReceiverType -> showReceivers.value = true
                is SensorType -> showSensors.value = true
                is ESCType -> showESCs.value = true
            }
            deviceType.value = it
            dialog.close()
        }.fail {
            dialog.close()
        }

        dialog.showAndWait()
        if (task.isRunning) task.cancel()
    }

    private fun fileNameChanged() {
        if (textField.text != null) {
            val file = File(textField.text)
            if (file.exists()) {
                preferences { put(LAST_FILE, file.absolutePath) }

                try {
                    // read device type from firmware file and update combobox
                    getDeviceType(serialPort,file)?.apply {
                        // some device types share the same product code - update only if product code is different
                        if (deviceType.value == null || productCode != deviceType.value.productCode) deviceType.value = this
                    }
                } catch (e: IOException) {
                    // ignore invalid firmware file
                }
            }
        }
    }

    private fun checkOnline() {
        root.runAsyncWithOverlay {
            deviceType.value.getFirmware()
        }.ui { firmware ->
            if (firmware.isNotEmpty()) {
                val names = firmware.map { it.file.name }.toSet().sorted()

                if (names.size == 1)
                    download(firmware[0])
                else {
                    val optional = ChoiceDialog(null, firmware.map { it.file.name }.toSet().sorted()).apply {
                        title = messages["downloadTitle"]
                        headerText = messages["downloadHeaderText"]
                    }.showAndWait()

                    // if there is a result, download the file and use it
                    if (optional.isPresent) download(firmware.find { it.file.name == optional.get() })
                }
            }
        }
    }

    private fun download(firmware: Firmware<*>?) {
        if (firmware != null)
            root.runAsyncWithOverlay {
                firmware.download()
            }.ui {
                textField.text = firmware.file.absolutePath
                textField.selectEnd()
            }
    }

    private fun doCancel() {
        service.cancel()
    }

    private fun doUpdate() {
        textArea.clear()
        progressBar.progressProperty().bind(service.progressProperty())
        startButton.text = messages["cancel"]

        service.setOnFailed { ev ->
            ExceptionDialog.show(ev.source.exception)
            reset()
        }

        service.setOnCancelled {
            MessageDialog.show(Alert.AlertType.WARNING, messages["cancelled"], messages["updateCancelled"])
            reset()
        }

        service.setOnSucceeded {
            MessageDialog.show(Alert.AlertType.INFORMATION, messages["success"], messages["updateFinished"])
            reset()
        }

        service.file = File(textField.text)
        service.transmitter = serialPort!!
        service.start()
    }

    private fun reset() {
        service.reset()
        progressBar.progressProperty().unbind()
        progressBar.progress = 0.0
        startButton.text = messages["start"]
    }

    private fun selectFile() {
        FileChooser().apply {
            title = messages["selectTitle"]
            extensionFilters.add(FileChooser.ExtensionFilter(messages["firmwareFiles"], "*.bin"))

            if (textField.text != null) {
                initialDirectory = File(textField.text).parentFile
            } else {
                preferences {
                    val folder = File(get(LAST_DIR, System.getProperty("user.home")))
                    if (folder.exists() && folder.isDirectory)
                        initialDirectory = folder
                }
            }

        }.showOpenDialog(primaryStage)?.apply {
            preferences { put(LAST_DIR, parentFile.absolutePath) }
            textField.text = absolutePath
            textField.selectEnd()
        }
    }

    init {
        Util.enableLogging()

        title = messages["title"] + " " + Util.sourceVersion(FirmwareUpdater::class)
        setStageIcon(iconImage)
        currentStage?.apply {
            minHeight = 300.0
            minWidth = 400.0
        }

        // setup drag/drop
        with(root) {
            setOnDragEntered { ev ->
                opacity = 0.3
                ev.consume()
            }

            setOnDragExited { ev ->
                opacity = 1.0
                ev.consume()
            }

            setOnDragOver { ev ->
                if (getFile(ev) != null) ev.acceptTransferModes(TransferMode.COPY)
                ev.consume()
            }

            setOnDragDropped { ev ->
                val file = getFile(ev)
                if (file != null) {
                    textField.text = file.absolutePath
                    textField.selectEnd()
                    preferences { put(LAST_DIR, file.parentFile.absolutePath) }
                }
                ev.consume()
            }
        }


        // load preferred port from preferences
        runAsync {
            portCombo.items.addAll(SerialPort.getAvailablePorts())
        } success {
            preferences {
                val prefPort: String? = get(PREFERRED_PORT, null)
                if (prefPort != null && portCombo.items.contains(prefPort)) {
                    portCombo.value = prefPort
                }

                get(LAST_DEVICE, null)?.also { name ->
                    deviceType.value = deviceList.find { it.name == name }
                }

                get(LAST_FILE, null)?.apply {
                    File(this).apply {
                        if (exists()) textField.text = absolutePath
                    }
                }
            }
        }
    }
}

/**
 * A ListCell that show an image of the device and it's name
 */
class HoTTDeviceListCell : ListCell<Registered<*>>() {
    companion object {
        private val images = HashMap<Registered<*>, Image>()

        init {
            // load images for all devices
            deviceList.forEach { device ->
                val stream = HoTTDeviceListCell::class.java.getResourceAsStream("images/${device.orderNo}.jpg")
                        ?: HoTTDeviceListCell::class.java.getResourceAsStream("images/missing.png")

                // scale to 40x50 pixels
                images[device] = Image(stream, 150.0, 40.0, true, true)
            }
        }
    }

    override fun updateItem(item: Registered<*>?, empty: Boolean) {
        super.updateItem(item, empty)

        if (empty || item == null) {
            graphic = null
            text = null
        } else {
            graphic = ImageView(images[item])
            text = item.toString()
            minHeight = 40.0
        }
    }
}
