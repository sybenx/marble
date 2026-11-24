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
 * The account's pages, each opened straight from your picture's menu (PictureSheet): there is no list in between.
 * A page is a string so it can be saved across screens and restores: "site:<origin>" is one site's page (under Apps and sites).
 */
internal object AccountPages {
    const val SITES = "sites"
    const val CHANGES = "changes"
    const val DEVICES = "devices"
    const val LANGUAGES = "languages"
    const val SEARCH = "search"
    const val ADVANCED = "advanced"
    const val TRIALS = "trials"
    private const val SITE = "site:"

    /** Each page's name, as its menu row and its title say it. */
    private val titles = mapOf(
        SITES to "Apps and sites",
        CHANGES to "Recent changes",
        DEVICES to "Devices and backup",
        ADVANCED to "How your account works",
        SEARCH to "Search settings",
        LANGUAGES to "Languages and translation",
        TRIALS to "Trials and version",
    )

    /** A page this screen can show (an old saved "following", say, is not one any more). */
    fun known(page: String?) = page != null && (page in titles || origin(page) != null)

    fun site(origin: String) = SITE + origin
    fun origin(page: String?) = page?.takeIf { it.startsWith(SITE) }?.removePrefix(SITE)

    /** Where Back goes: a site to Apps and sites; any other page leaves the screen (null). */
    fun up(page: String?) = if (origin(page) != null) SITES else null

    fun title(page: String?) = origin(page)?.removePrefix("https://") ?: titles[page] ?: titles.getValue(DEVICES)

    /** One entry per website, newest used first, with when it was last used. */
    fun siteRows(sites: List<Site>) = sites.groupBy { it.origin }.map { (o, g) -> o to g.maxOf { it.lastUsed } }.sortedByDescending { it.second }

    /** What one site changed, newest first (Recent changes keeps that order). */
    fun changesBy(origin: String, changes: List<Change>) = changes.filter { it.by == origin }
}

/** A row in Apps and sites: its name, a note on the right, and › to its page. */
@Composable
internal fun MenuRow(label: String, count: String?, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Vault.Text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(if (count == null) "›" else "$count  ›", color = Vault.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
    }
