package me.capcom.smsgateway.modules.tinytot

import me.capcom.smsgateway.modules.tinytot.api.UpiVerifierClient
import me.capcom.smsgateway.modules.tinytot.vm.UpiPaymentsViewModel
import org.koin.androidx.viewmodel.dsl.viewModelOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val tinytotModule = module {
    single { SecretStore(get()) }
    single { TinyTotSettings(get(), get()) }
    singleOf(::UpiVerifierClient)
    singleOf(::UpiNotifier)
    singleOf(::UpiSmsForwarder)
    singleOf(::UpiQueueDelivery)
    viewModelOf(::UpiPaymentsViewModel)
}

const val MODULE_NAME = "tinytot"
