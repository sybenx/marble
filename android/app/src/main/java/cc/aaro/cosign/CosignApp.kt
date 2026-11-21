package cc.aaro.cosign

import android.app.Application
import android.content.Context

/** Runs in both processes (Proc): the signer holds the keys ([signers]), the main process draws ([accounts]). */
class CosignApp : Application() {
    /** The accounts as the UI sees them, through the signer process. Main process only. */
    val accounts: Accounts get() = Cosign.accounts
    /** The keys themselves. Signer process only. */
    val signers: Signers get() = Cosign.signers
    /** The account on screen. Code that serves every account uses [accounts]. */
    val phone get() = accounts.active

    override fun onCreate() {
        super.onCreate()
        instance = this
        Startup.mark("app")
        Cosign.init(this, object : Cosign.Host {
            override fun reveal(context: Context, slot: String) = SignerActivity.open(context, slot)
            override fun joined(context: Context) { try { CosignService.start(context) } catch (_: Exception) { } }
        })
        // After the app's first screen is drawn. Signing never waits on this (it connects by itself); boot and push start it directly.
        // Android refuses this start when the signer was woken in the background (ForegroundServiceStartNotAllowedException):
        // that must not take the signer down. It signs without the service, and opening Marble starts it.
        if (Proc.isSigner && signers.all.any { it.isMember }) android.os.Handler(mainLooper).postDelayed({ try { CosignService.start(this) } catch (_: Exception) { } }, 1500)
        // The feed log (off unless you turned it on): the app's process only.
        if (!Proc.isSigner) FeedLog.load(this)
        Startup.mark("app done")
    }

    companion object {
        lateinit var instance: CosignApp
            private set
    }
}
