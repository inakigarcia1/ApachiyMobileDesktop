package com.nuvio.app.navigation

import com.nuvio.app.core.account.AccountStatusRepository
import com.nuvio.app.core.account.InactiveSubscriptionNotifier

fun allowPlaybackOrNotify(): Boolean {
    if (!AccountStatusRepository.canStartPlayback()) {
        InactiveSubscriptionNotifier.notifyInactiveSubscription()
        return false
    }
    return true
}
