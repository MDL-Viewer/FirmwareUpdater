package de.treichels.hott.update

import de.treichels.hott.decoder.HoTTFirmwareKt.*
import de.treichels.hott.decoder.HoTTTransmitter
import de.treichels.hott.ui.CallbackAdapter
import javafx.concurrent.Service
import tornadofx.*
import java.io.File

class FirmwareUpdateService : Service<Unit>() {
    lateinit var transmitter: HoTTTransmitter
    lateinit var file: File

    override fun createTask() = task {
        updateDevice(transmitter, file, CallbackAdapter(this))
    }
}
