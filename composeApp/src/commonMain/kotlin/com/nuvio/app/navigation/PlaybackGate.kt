package com.nuvio.app.navigation

import com.nuvio.app.core.account.AccountStatusRepository
import com.nuvio.app.core.account.InactiveSubscriptionNotifier
import com.nuvio.app.isDesktop

suspend fun allowPlaybackOrNotify(): Boolean {
    if (!isDesktop) return true
    AccountStatusRepository.refresh()
    if (!AccountStatusRepository.canStartPlayback()) {
        InactiveSubscriptionNotifier.notifyInactiveSubscription()
        return false
    }
    return true
}
