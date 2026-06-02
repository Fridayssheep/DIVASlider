package com.fridayssheep.divaslider.network

internal interface InputSender {
    fun start(onStatus: (String) -> Unit)
    fun stop()
}
