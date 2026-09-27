package com.shilapi.xcertplay.shared

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template

class MyCarAppScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val localizedContext = AppLanguage.localizedContext(carContext)
        return MessageTemplate.Builder(localizedContext.getString(R.string.hardware_transport_not_configured))
            .setHeaderAction(Action.APP_ICON)
            .setTitle(localizedContext.getString(R.string.hardware_status_title))
            .build()
    }
}
