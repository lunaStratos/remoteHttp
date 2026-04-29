package com.lunastratos.remotecontrol.data

import java.util.UUID

data class Device(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    val items: MutableList<DeviceItem> = mutableListOf()
)
