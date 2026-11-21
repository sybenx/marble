package cc.aaro.cosign.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.aaro.cosign.Change
import cc.aaro.cosign.Site

/**
 * The account behind your picture: a short list, and each row opens its own page.
 * A page is a string so it can be saved across Special Follow and back: null is the list,
 * "site:<origin>" is one site's page (under Apps and sites).
 */
internal object AccountPages {
    const val SITES = "sites"
    const val CHANGES = "changes"
    const val DEVICES = "devices"
    const val FOLLOWING = "following"
    const val LANGUAGES = "languages"
    const val SEARCH = "search"
    const val ADVANCED = "advanced"
    private const val SITE = "site:"

    data class Row(val page: String, val label: String, val count: String?)

    /** Most used first: what signs as you and what it changed, then the rest. */
    fun rows(sites: List<Site>, devices: Int) = listOf(
        Row(SITES, "Apps and sites", sites.map { it.origin }.distinct().size.takeIf { it > 0 }?.toString()),
        Row(CHANGES, "Recent changes", null),
        Row(DEVICES, "Devices", devices.toString()),
        Row(FOLLOWING, "Following", null),
        Row(LANGUAGES, "Languages", null),
        Row(SEARCH, "Search", null),
        Row(ADVANCED, "Advanced", null),
    )

    fun site(origin: String) = SITE + origin
    fun origin(page: String?) = page?.takeIf { it.startsWith(SITE) }?.removePrefix(SITE)

    /** Where Back goes: a site to Apps and sites, any other page to the list. */
    fun up(page: String?) = if (origin(page) != null) SITES else null

    fun title(page: String?) = origin(page)?.removePrefix("https://")
        ?: rows(emptyList(), 0).firstOrNull { it.page == page }?.label
        ?: "Your account"

    /** One entry per website, newest used first, with when it was last used. */
    fun siteRows(sites: List<Site>) = sites.groupBy { it.origin }.map { (o, g) -> o to g.maxOf { it.lastUsed } }.sortedByDescending { it.second }

    /** What one site changed, newest first (Recent changes keeps that order). */
    fun changesBy(origin: String, changes: List<Change>) = changes.filter { it.by == origin }
}

/** A row of the short list: its name, a count or a note on the right, and › to its page. */
@Composable
internal fun MenuRow(label: String, count: String?, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Vault.Text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(if (count == null) "›" else "$count  ›", color = Vault.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
    }
