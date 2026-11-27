package app.vitranslator.phone.ui

import app.vitranslator.phone.card.UsbCards

/** What the app says about a capture card, at each step where it can fail (UsbCards.check). */
object CardText {
    /** The Source panel's line for the card. */
    fun status(s: UsbCards.Status): String = when (s.kind) {
        UsbCards.Kind.CAMERA -> T("A capture card is plugged in.")
        UsbCards.Kind.USB_NEEDS_PERMISSION -> T("The phone sees {name} over USB. Allow the app to use it.", "name" to s.name)
        UsbCards.Kind.USB_NOT_CAMERA -> notCamera(s)
        UsbCards.Kind.USB_OTHER -> T("A USB device is plugged in ({name}), but not a video one. If it's the capture card: try another cable or adapter, or a powered USB hub (some cards need more power than the phone gives).", "name" to s.name)
        UsbCards.Kind.NOTHING -> T("No capture card found. Plug it in, then check again. Some phones need USB OTG turned on in their settings.")
        UsbCards.Kind.NO_HOST -> T("No USB device found, and this phone doesn't say it supports USB devices (USB OTG). Plug the card in, then check again.")
    }

    private fun notCamera(s: UsbCards.Status) =
        T("The phone sees {name} over USB and the app may use it, but Android doesn't offer it to apps as a camera, which is how the app reads a card for now (some Galaxy phones and tablets do this). Apps like \"USB Camera\" read it with their own USB video driver; VI Translator doesn't have one yet. Use PS5 or Xbox Remote Play meanwhile.", "name" to s.name)

    /** Why translating from the card couldn't start (the card not offered as a camera). */
    fun failure(s: UsbCards.Status): String = when (s.kind) {
        UsbCards.Kind.CAMERA -> T("the capture card could not be opened")
        UsbCards.Kind.USB_NEEDS_PERMISSION, UsbCards.Kind.USB_NOT_CAMERA -> T("the phone sees {name} over USB, but Android doesn't offer it to apps as a camera (see Setup › Source)", "name" to s.name)
        UsbCards.Kind.USB_OTHER -> T("the USB device plugged in isn't a video device (see Setup › Source)")
        UsbCards.Kind.NOTHING, UsbCards.Kind.NO_HOST -> T("no capture card found: is it plugged in?")
    }
}
