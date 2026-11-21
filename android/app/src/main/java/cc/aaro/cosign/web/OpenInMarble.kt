package cc.aaro.cosign.web

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cc.aaro.cosign.Ref
import cc.aaro.cosign.pageRef
import cc.aaro.cosign.ui.OpenPost
import cc.aaro.cosign.ui.Vault

/**
 * Over a site's page that is a single post or a person (Jumble's /notes/nevent1…, /users/npub1…, any site's address
 * that names one: pageRef): a small "Open in Marble", which opens it in Marble's own viewer, translated as Marble
 * translates, over the pane (Back returns to the page). Nothing on any other page.
 */
@Composable
internal fun OpenInMarble(state: WebPaneState, modifier: Modifier = Modifier) {
    val ref = pageRef(state.pageUrls[state.site.id]) ?: return
    val what = when (ref) { is Ref.Profile -> "Open profile in Marble"; is Ref.Addr -> "Open in Marble"; is Ref.Note -> "Open post in Marble" }
    Text(what, color = Vault.Ground, style = MaterialTheme.typography.labelLarge,
        // Above where sites keep their own bottom bar (Jumble's, Primal's).
        modifier = modifier.padding(end = 12.dp, bottom = 76.dp).clip(RoundedCornerShape(18.dp)).background(Vault.Accent).border(1.dp, Vault.Accent, RoundedCornerShape(18.dp))
            .clickable(role = Role.Button) { OpenPost.ref.value = ref }.padding(horizontal = 14.dp, vertical = 8.dp))
}
