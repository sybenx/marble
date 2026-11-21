package cc.aaro.cosign

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Approve / Deny on a request, a batch of deletions, or Undo on a deletion, tapped in its notification. */
class DecisionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val accounts = CosignApp.instance.signers
        val phone = intent.getStringExtra("slot")?.let { accounts.signer(it) } ?: accounts.active
        val approve = intent.getBooleanExtra("approve", false)
        // A question's notification swiped away unanswered: No. A waiting fingerprint's: the request ends.
        intent.getStringExtra("fpDismissed")?.let { return Chip.dismiss(it) }
        intent.getStringExtra("dismissed")?.let { return phone.decide(it, false) }
        intent.getStringExtra("cancel")?.let { return phone.cancelDeletion(it) }
        if (intent.hasExtra("tap")) { androidx.core.app.NotificationManagerCompat.from(context).cancel(("tap" + intent.getStringExtra("tap")).hashCode()); return phone.tap(approve, intent.getStringExtra("tap")) }
        intent.getStringExtra("batch")?.let { return phone.decideBatch(it, approve) }
        val id = intent.getStringExtra("id") ?: return
        phone.decide(id, approve)
    }
}
