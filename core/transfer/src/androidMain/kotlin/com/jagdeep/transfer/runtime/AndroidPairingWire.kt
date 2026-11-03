package com.jagdeep.transfer.runtime

internal fun AndroidTlsConnection.sendPairingMessage(message: PairingWireMessage) =
    sendControl(PairingWireMessageCodec.encode(message))

internal fun AndroidTlsConnection.receivePairingMessage(): PairingWireMessage =
    PairingWireMessageCodec.decode(receiveControl())
