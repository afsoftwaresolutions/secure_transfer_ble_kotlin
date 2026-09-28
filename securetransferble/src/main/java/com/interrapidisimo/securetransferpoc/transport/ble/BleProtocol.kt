package com.interrapidisimo.securetransferpoc.transport.ble

import java.util.UUID

object BleProtocol {

    val SERVICE_UUID: UUID = UUID.fromString(
        "7d2ea28a-f7bd-485a-bd9d-92ad6ecfe93e"
    )

    val SESSION_CONTROL_UUID: UUID = UUID.fromString(
        "7d2ea28b-f7bd-485a-bd9d-92ad6ecfe93e"
    )

    val DATA_TRANSFER_UUID: UUID = UUID.fromString(
        "7d2ea28c-f7bd-485a-bd9d-92ad6ecfe93e"
    )

    val TRANSFER_STATUS_UUID: UUID = UUID.fromString(
        "7d2ea28d-f7bd-485a-bd9d-92ad6ecfe93e"
    )

    val REVERSE_DATA_UUID: UUID = UUID.fromString(
        "7d2ea28e-f7bd-485a-bd9d-92ad6ecfe93e"
    )

    val CLIENT_CONFIGURATION_UUID: UUID = UUID.fromString(
        "00002902-0000-1000-8000-00805f9b34fb"
    )
}