package cc.aaro.cosign

// "Go to google.com" at the top of search: a typed address, or the name of a well-known site. The list is on
// the phone (the most visited sites, by their usual address); nothing is looked up or sent while you type.

object Sites {
    /** A well-known site's address by its name ("google" → "google.com"). Where two share a name, the bigger one. */
    private val BY_NAME: Map<String, String> = """
        google.com youtube.com facebook.com instagram.com wikipedia.org amazon.com reddit.com x.com twitter.com whatsapp.com
        tiktok.com linkedin.com yahoo.com bing.com netflix.com microsoft.com apple.com live.com office.com openai.com chatgpt.com
        claude.ai anthropic.com github.com gitlab.com stackoverflow.com twitch.tv discord.com telegram.org signal.org pinterest.com
        quora.com tumblr.com medium.com substack.com wordpress.com blogger.com ebay.com etsy.com walmart.com target.com bestbuy.com
        costco.com homedepot.com lowes.com ikea.com aliexpress.com alibaba.com temu.com shein.com wayfair.com zillow.com redfin.com
        craigslist.org indeed.com glassdoor.com paypal.com venmo.com stripe.com chase.com bankofamerica.com wellsfargo.com
        capitalone.com americanexpress.com coinbase.com kraken.com binance.com strike.me cashapp.com robinhood.com fidelity.com
        vanguard.com schwab.com spotify.com soundcloud.com bandcamp.com pandora.com deezer.com tidal.com vimeo.com dailymotion.com
        rumble.com odysee.com hulu.com disneyplus.com max.com peacocktv.com paramountplus.com primevideo.com crunchyroll.com
        imdb.com rottentomatoes.com letterboxd.com goodreads.com audible.com kindle.com archive.org nytimes.com washingtonpost.com
        wsj.com bloomberg.com reuters.com apnews.com bbc.com bbc.co.uk cnn.com foxnews.com nbcnews.com cbsnews.com abcnews.go.com
        npr.org theguardian.com economist.com ft.com forbes.com businessinsider.com cnbc.com axios.com politico.com theatlantic.com
        newyorker.com vox.com theverge.com wired.com arstechnica.com techcrunch.com engadget.com gizmodo.com cnet.com zdnet.com
        macrumors.com 9to5mac.com 9to5google.com androidpolice.com xda-developers.com tomshardware.com anandtech.com pcmag.com
        ign.com gamespot.com polygon.com kotaku.com pcgamer.com steampowered.com store.steampowered.com epicgames.com gog.com
        itch.io roblox.com minecraft.net xbox.com playstation.com nintendo.com ea.com chess.com lichess.org duolingo.com khanacademy.org
        coursera.org udemy.com edx.org wolframalpha.com weather.com accuweather.com wunderground.com espn.com nfl.com nba.com mlb.com
        nhl.com fifa.com uefa.com strava.com fitbit.com allrecipes.com seriouseats.com bonappetit.com foodnetwork.com yelp.com
        tripadvisor.com booking.com airbnb.com expedia.com kayak.com hotels.com uber.com lyft.com doordash.com ubereats.com
        grubhub.com instacart.com maps.google.com openstreetmap.org mail.google.com gmail.com outlook.com proton.me protonmail.com
        tutanota.com fastmail.com icloud.com dropbox.com box.com drive.google.com docs.google.com notion.so evernote.com obsidian.md
        trello.com asana.com slack.com zoom.us teams.microsoft.com figma.com canva.com adobe.com behance.net dribbble.com
        unsplash.com pexels.com flickr.com imgur.com giphy.com deviantart.com artstation.com 500px.com vsco.co mastodon.social
        bsky.app bluesky.social threads.net lemmy.world kbin.social hackernews.com news.ycombinator.com lobste.rs slashdot.org
        producthunt.com indiehackers.com dev.to hashnode.com npmjs.com pypi.org crates.io docker.com kubernetes.io python.org
        rust-lang.org go.dev kotlinlang.org developer.android.com developer.apple.com mozilla.org firefox.com brave.com
        duckduckgo.com startpage.com kagi.com perplexity.ai gemini.google.com bard.google.com huggingface.co kaggle.com arxiv.org
        nature.com science.org nasa.gov noaa.gov cdc.gov nih.gov who.int un.org irs.gov usps.com ups.com fedex.com dhl.com
        stacker.news primal.net damus.io snort.social iris.to coracle.social nostr.band jumble.social ditto.pub njump.me nostr.com
        zap.stream habla.news yakihonne.com highlighter.com olas.app zapstore.dev bitcoin.org mempool.space blockstream.info
        lightning.network getalby.com wikihow.com fandom.com britannica.com dictionary.com merriam-webster.com thesaurus.com
        translate.google.com deepl.com speedtest.net fast.com downdetector.com pastebin.com archive.ph web.archive.org
    """.trim().split(Regex("\\s+")).fold(LinkedHashMap()) { m, d ->
        val name = d.removePrefix("www.").substringBefore('.')
        m.putIfAbsent(name, d); m
    }
    private val ADDRESS = Regex("^(?:https?://)?((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,24})(?:[/:?#]\\S*)?$", RegexOption.IGNORE_CASE)

    /** A site to go to for what's typed, and whether it's a sure thing (Enter opens it) or only a likely one. */
    data class Go(val address: String, val url: String, val sure: Boolean)

    fun match(typed: String): Go? {
        val q = typed.trim()
        if (q.isEmpty() || q.contains(' ')) return null
        // An address: lemmy.world, example.com/page.
        ADDRESS.matchEntire(q)?.let { m ->
            val host = m.groupValues[1].lowercase()
            if (host.substringAfterLast('.') in NOT_TLDS) return null
            val url = if (q.startsWith("http://", true) || q.startsWith("https://", true)) q else "https://$q"
            return Go(host.removePrefix("www."), url, sure = true)
        }
        val name = q.lowercase()
        BY_NAME[name]?.let { return Go(it, "https://$it", sure = true) }
        // The start of one (at least three letters): shown, but Enter still searches.
        if (name.length >= 3 && name.all { it.isLetterOrDigit() || it == '-' }) BY_NAME.entries.firstOrNull { it.key.startsWith(name) }?.let { return Go(it.value, "https://${it.value}", sure = false) }
        return null
    }

    /** Endings that look like an address but aren't one in a search ("node.js", "file.txt"). */
    private val NOT_TLDS = setOf("js", "ts", "kt", "py", "rs", "txt", "md", "json", "png", "jpg", "jpeg", "gif", "pdf", "mp4", "mp3", "exe", "apk", "zip", "html", "css")
}
