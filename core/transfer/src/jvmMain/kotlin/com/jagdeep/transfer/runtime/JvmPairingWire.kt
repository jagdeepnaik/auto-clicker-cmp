package com.jagdeep.transfer.runtime

internal fun JvmTlsConnection.sendPairingMessage(message: PairingWireMessage) =
    sendControl(PairingWireMessageCodec.encode(message))

internal fun JvmTlsConnection.receivePairingMessage(): PairingWireMessage =
    PairingWireMessageCodec.decode(receiveControl())
