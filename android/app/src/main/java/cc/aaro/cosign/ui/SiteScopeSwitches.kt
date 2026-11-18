package cc.aaro.cosign.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * What a site may do with your key (SiteScopes in the signer): at sign-in and in its sheet. Posting is always on (a
 * site that's signed in posts as you); private lists and DMs are each a switch.
 */
@Composable
fun SiteScopeSwitches(lists: Boolean, dms: Boolean, onLists: (Boolean) -> Unit, onDms: (Boolean) -> Unit, modifier: Modifier = Modifier) = Column(modifier.fillMaxWidth()) {
    ScopeRow("Sign in and post as you", true, null)
    ScopeRow("Read your private lists", lists, onLists)
    ScopeRow("Read and send DMs", dms, onDms)
}

@Composable
private fun ScopeRow(text: String, on: Boolean, onChange: ((Boolean) -> Unit)?) = Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(text, color = Vault.Text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    Switch(on, onChange, enabled = onChange != null,
        colors = SwitchDefaults.colors(checkedTrackColor = Vault.Accent, checkedThumbColor = Vault.Ground,
            uncheckedTrackColor = Vault.Ground, uncheckedThumbColor = Vault.Muted, uncheckedBorderColor = Vault.Edge,
            disabledCheckedTrackColor = Vault.Accent.copy(alpha = 0.5f), disabledCheckedThumbColor = Vault.Ground))
}
