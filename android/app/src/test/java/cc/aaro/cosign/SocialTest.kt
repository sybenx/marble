package cc.aaro.cosign

import cc.aaro.qrst.core.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** What the home screen's search box opens, and how it reads a reply's place in a thread. */
class SocialTest {
    private val fiatjaf = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
    private val note = "9739a916c3b6acb295231ed16654fd1e6379fe214eddec360c4514b495f259c9"

    @Test fun identifiersOpenWhatTheyName() {
        assertEquals(Ref.Profile(fiatjaf), Ref.parse("npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6"))
        assertEquals(Ref.Profile(fiatjaf), Ref.parse(" nostr:npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6 "))
        assertEquals(Ref.Profile(fiatjaf), Ref.parse(fiatjaf.uppercase()))
        // Longer than plain bech32's 90 characters, with relays inside.
        assertEquals(Ref.Profile(fiatjaf), Ref.parse("nprofile1qqsrhuxx8l9ex335q7he0f09aej04zpazpl0ne2cgukyawd24mayt8gpz3mhxue69uhhyetvv9ujuerpd46hxtnfduqs6amnwvaz7tmwdaejumr0dsfg70ls"))
        assertEquals(Ref.Note(note), Ref.parse("nevent1qqsfwwdfzmpmdt9jj533a5tx2n73ucmelcs5ah0vxcxy2995jhe9njgpz3mhxue69uhhyetvv9ujuerpd46hxtnfduy3kz48"))
        assertNull(Ref.parse("bitcoin"))
        assertNull(Ref.parse("#nostr"))
        assertNull(Ref.parse("npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w7")) // bad checksum
    }

    @Test fun approvalsAskAboutWhatHappens() {
        val q = { s: String -> cc.aaro.cosign.ui.question(s) }
        assertEquals("Unfollow 10 people?", q("Remove 10 of your 30 follows"))
        assertEquals("Unfollow 30 people and follow 30 people instead?", q("Remove 30 of your 30 follows (and add 30)"))
        assertEquals("Unfollow all 3 people?", q("Empty your follow list (it has 3 follows)"))
        assertEquals("Delete 5 posts?", q("Delete 5 of your events"))
        assertEquals("Delete 5 posts?", q("Delete 5 events for Approval test")) // a batch, as your servers word it
        assertEquals("Delete your follow list?", q("Delete your follow list"))
        assertEquals("Remove all 4 bookmarks from your bookmarks?", q("Empty your bookmarks (it has 4 bookmarks)"))
        assertEquals("Remove all 5 people from your follow set?", q("Empty your follow set (it has 5 persons)"))
        assertEquals("Clear your profile (picture, about)?", q("Wipe your profile: picture, about"))
        assertEquals("Erase everything you've ever posted?", q("Ask relays to erase everything you have ever published"))
        assertEquals("Allow a note dated 2099-01-01?", q("Note dated in the future (2099-01-01)"))
        assertEquals("Remove 2 of your 3 relays?", q("Remove 2 of your 3 relays"))
    }

    private fun note(text: String, vararg tags: List<String>) = Event(fiatjaf, 0, 1, tags.toList(), text)

    @Test fun languagesAreGuessedOnlyWhenClear() {
        assertEquals(setOf("en"), Lang.guess("I think this is the best thing that happened to my week, and you should try it"))
        assertEquals(setOf("pt"), Lang.guess("Eu não sei o que fazer com isso, mas você tem razão sempre"))
        assertEquals(setOf("de"), Lang.guess("Ich bin nicht sicher, aber das ist auch eine gute Idee für uns"))
        assertEquals(setOf("es"), Lang.guess("No sé qué hacer con esto, pero también es muy bueno para la gente"))
        assertEquals("ja", Lang.guess("今日はとても良い天気ですね。散歩に行きましょう")?.first())
        assertEquals(true, Lang.guess("Привет всем, как у вас дела сегодня?")?.contains("ru"))
        assertNull(Lang.guess("GM ☀️"))                          // too short to tell
        assertNull(Lang.guess("https://example.com/a.jpg #nostr")) // nothing but a link and a tag
        val en = setOf("en")
        assertEquals(true, Lang.readable(note("Eu não sei o que fazer com isso", listOf("l", "en", "ISO-639-1")), en)) // its label wins
        assertEquals(false, Lang.readable(note("Eu não sei o que fazer com isso, mas você tem razão sempre"), en))
        assertEquals(true, Lang.readable(note("Bitcoin 🚀🚀"), en))
    }

    @Test fun sensitiveNotesAreFlagged() {
        assertEquals(true, Safety.flagged(note("look", listOf("content-warning", "spoilers"))))
        assertEquals(true, Safety.flagged(note("hi", listOf("t", "NSFW"))))
        assertEquals(true, Safety.flagged(note("new pics #nsfw")))
        assertEquals(true, Safety.flagged(note("x", listOf("l", "NS-nud", "content-warning"))))
        assertEquals(false, Safety.flagged(note("#nsfwish is not a tag we know")))
        assertEquals(false, Safety.flagged(note("a calm note", listOf("t", "nature"))))
    }

    private fun by(pk: Char, text: String, vararg tags: List<String>) = Event(pk.toString().repeat(64), 0, 1, tags.toList(), text)

    @Test fun discoverKeepsPostsThatStandOnTheirOwn() {
        assertEquals(true, Curate.keep(note("Baked my first sourdough loaf today and it actually rose")))
        assertEquals(false, Curate.keep(note("Hard agree, this is the way to do it", listOf("e", "a".repeat(64), "", "root"))))   // a reply
        assertEquals(true, Curate.keep(note("Quoting a friend here, worth a read", listOf("e", "a".repeat(64), "", "mention"))))  // a quote is not a reply
        assertEquals(false, Curate.keep(note("Soap dispenser?")))                     // nothing in it
        assertEquals(false, Curate.keep(note("https://example.com/a #nostr @someone")))   // only links and tags
        assertEquals(true, Curate.keep(note("Swim time https://example.com/pool.jpg")))     // short, but a picture
        assertEquals(false, Curate.keep(note("test post")))
        assertEquals(false, Curate.keep(note("Day 1 and I can't sleep. I lost my son yesterday. I just want to die")))
        assertEquals(false, Curate.keep(note("Buried my mum today. Thank you all for the kind words")))
        assertEquals(true, Curate.keep(note("The cancellation fee for this flight is absurd, honestly")))   // "cancer" is a word, not a substring
        // Despair: the wording that got through once, and its kind.
        for (t in listOf("Thinking about how one day the people here will die and those keys will just be left",
                "It feels empty. i dont want to cry, i dont feel like it", "honestly i don't want to be here anymore",
                "I just wanna die tbh", "some days I want to end it all", "nobody would miss me if I was gone",
                "i keep hurting myself and nobody notices", "i can't go on like this, i feel so hopeless"))
            assertEquals(t, false, Curate.keep(note(t)))
        // Everyday words near those stay.
        for (t in listOf("The battery on this old phone is finally dying, time for a new one", "This empty room feels peaceful after the move, finally done",
                "Ending the week with a long walk by the river", "I want to live somewhere with real winters one day"))
            assertEquals(t, true, Curate.keep(note(t)))
    }

    @Test fun aPageHasTwoPostsAPersonAndOneGreeting() {
        val posts = listOf(by('a', "one from a, with plenty of words"), by('a', "two from a, with plenty of words"), by('a', "three from a, too many"),
            by('b', "GM ☀️ https://x.com/sun.jpg"), by('c', "Good morning everyone https://x.com/c.jpg"), by('d', "a post from d with words"))
        val page = Curate.page(posts)
        assertEquals(listOf("one from a, with plenty of words", "two from a, with plenty of words", "GM ☀️ https://x.com/sun.jpg", "a post from d with words"), page.map { it.content })
        // The next page counts what's already shown.
        assertEquals(emptyList<String>(), Curate.page(listOf(by('a', "four from a, still a")), already = page).map { it.content })
    }

    @Test fun searchPutsTheRealPersonFirst() {
        fun hit(pk: Char, name: String, order: Int, followers: Long? = null, nip05: Nip05.Check = Nip05.Check.NONE, fof: Int = 0, follow: Boolean = false) =
            Hit(pk.toString().repeat(64), name, order, follow, fof, followers, nip05)
        // "jack": many people named Jack come back first; the one with a checked address and 174k followers is the one meant.
        val jack = listOf(hit('a', "Jack", 0, 12), hit('b', "jack⚡️", 1, 40), hit('c', "Jacket", 2, 900), hit('d', "Jack K", 3, 300, Nip05.Check.OK),
            hit('e', "jack", 9, 174_538, Nip05.Check.OK), hit('f', "Jack", 4, 0, Nip05.Check.WRONG))
        assertEquals(listOf('e', 'b', 'a'), SearchRank.sort(jack, "jack").take(3).map { it.pubkey[0] })
        // "alex gleason": impersonators share the exact name; his own address can't be reached today (UNKNOWN),
        // his bridged account checks out but few follow it: 19,803 followers still put him first.
        val alex = listOf(hit('1', "Alex Gleason", 0, 0), hit('2', "Alex Gleason", 1, 2), hit('3', "Alex Gleason", 2, 120, Nip05.Check.OK),
            hit('4', "Alex Gleason", 5, 19_803, Nip05.Check.UNKNOWN), hit('5', "Alex Gleason", 3, 0, Nip05.Check.WRONG))
        assertEquals('4', SearchRank.sort(alex, "alex gleason").first().pubkey[0])
        // Someone you follow comes before anyone, and people your follows follow before strangers.
        assertEquals('x', SearchRank.sort(jack + hit('x', "Jackie", 20, 3, follow = true), "jack").first().pubkey[0])
        assertEquals('y', SearchRank.sort(jack.filter { it.pubkey[0] != 'e' } + hit('y', "Jack", 21, 50, fof = 4), "jack").first().pubkey[0])
        assertEquals(true, SearchRank.exact("Alex  Gleason 🦊", "alex gleason"))
    }

    @Test fun bareLookAlikesDontPassSomeoneStillBeingCounted() {
        // "florian", 2026-11-20: relay.ditto.pub answers first with dozens of bare "Florian"s and their counts (1–3) come in
        // before the real one's (23,910); while his is on the way he stays on top.
        val bare = (0 until 30).map { Hit(('a' + it % 20).toString().repeat(64), "Florian", 1000 + it, followers = (it % 4).toLong(), bare = true) }
        val real = Hit("f".repeat(63) + "0", "florian", 0, followers = null)
        assertEquals(real.pubkey, SearchRank.sort(bare + real, "florian").first().pubkey)
        assertEquals(real.pubkey, SearchRank.sort(bare + real.copy(followers = 23_910), "florian").first().pubkey)
        // Bare is a profile with nothing but a name (a picture doesn't count); an address or an about says more.
        assertEquals(true, Hit.bare(Person("a".repeat(64), "Florian", "https://x/p.jpg", "", null, 0)))
        assertEquals(false, Hit.bare(Person("a".repeat(64), "florian", null, null, "florian@slidestr.net", 0)))
        assertEquals(false, Hit.bare(Person("a".repeat(64), "Florian", null, "builds things", null, 0)))
    }

    @Test fun linksThatCarryAnIdentifierOpenIt() {
        assertEquals(Ref.Profile(fiatjaf), Ref.parse("https://njump.me/npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6"))
        assertEquals(Ref.Note(note), Ref.parse("https://primal.net/e/nevent1qqsfwwdfzmpmdt9jj533a5tx2n73ucmelcs5ah0vxcxy2995jhe9njgpz3mhxue69uhhyetvv9ujuerpd46hxtnfduy3kz48"))
        assertNull(Ref.parse("alex@gleasonator.com"))
        assertEquals(true, Nip05.isAddress("alex@gleasonator.com")); assertEquals(true, Nip05.isAddress("_@fiatjaf.com")); assertEquals(false, Nip05.isAddress("alex gleason"))
    }

    @Test fun lookAlikesFoldUnderTheBestOfThem() {
        fun pk(c: Char) = c.toString().repeat(64)
        fun person(c: Char, name: String, pic: String?, about: String?) = Person(pk(c), name, pic, about, null, 0)
        val bot = "Bridged from Discord by armada-bridge."
        val people = listOf(person('a', "Alex Gleason", "https://x/real.jpg", "Founder of Ditto"), person('b', "Alex Gleason", "https://x/d.jpg", bot),
            person('c', "Alex Gleason", "https://x/d.jpg", bot), person('d', "Alex  Gleason", "https://x/d.jpg", bot), person('e', "Alex Gleason", "https://x/d.jpg", bot),
            person('f', "Alex Gleason", null, null), person('g', "Alex Gleason", null, null)).associateBy { it.pubkey }
        val hits = mapOf(pk('e') to Hit(pk('e'), "Alex Gleason", 5, nip05 = Nip05.Check.OK))   // a checked address is never folded away
        val rows = LookAlikes.fold(listOf('a', 'b', 'c', 'd', 'e', 'f', 'g').map(::pk), people, hits)
        assertEquals(listOf(pk('a'), pk('b'), pk('e'), pk('f'), pk('g')), rows.map { it.pubkey })
        assertEquals(listOf(pk('c'), pk('d')), rows[1].alike)     // folded under the best-ranked copy
        assertEquals(emptyList<String>(), rows[3].alike)        // bare profiles: just a common name, not folded
    }

    private fun ev(vararg tags: List<String>) = Event(fiatjaf, 0, 1, tags.toList(), "")

    @Test fun repliesFindTheirPlace() {
        val root = "a".repeat(64); val parent = "b".repeat(64); val quoted = "c".repeat(64)
        // Marked tags (NIP-10).
        val marked = ev(listOf("e", root, "", "root"), listOf("e", parent, "", "reply"))
        assertEquals(parent, parentOf(marked)); assertEquals(root, rootOf(marked))
        // A direct reply to the first note carries only the root marker.
        assertEquals(root, parentOf(ev(listOf("e", root, "", "root"))))
        // The old positional form: first is the root, last is the parent.
        val positional = ev(listOf("e", root), listOf("e", parent))
        assertEquals(parent, parentOf(positional)); assertEquals(root, rootOf(positional))
        // A note that only mentions another is no reply.
        assertNull(parentOf(ev(listOf("e", quoted, "", "mention"))))
        assertNull(parentOf(ev(listOf("p", fiatjaf))))
    }

    // A real comment (NIP-22) answering another comment: lowercase e is the parent (pubkey in the 4th place), uppercase E the root.
    private val commentRoot = "000000b61b2835c10790f2af8be8e276a937e089b82bcba8537c785071c398cf"
    private val commentParent = "000000da7fa206aa3fc03d13d3e6aa1a0ed69c6cdee45a495de871d92887c30a"
    private val adam = "eb7bb10fbb531c5b7b914486d160ff85e3305d5bc88a34b501cdbd3efa9ed505"
    private val comment = Event(fiatjaf, 0, COMMENT, listOf(listOf("p", fiatjaf), listOf("E", commentRoot, "wss://garden.adamiskandar.com/", adam), listOf("P", adam),
        listOf("e", commentParent, "wss://relay.primal.net/", adam), listOf("p", adam), listOf("k", "1111")), "bothy relay runs on cloudflare workers today, partway there.").withId()

    @Test fun commentsFindTheirPlace() {
        assertEquals(commentParent, parentOf(comment)); assertEquals(commentRoot, rootOf(comment))
        assertTrue(isReply(comment))
        // A first comment on a web page answers no note, but is still a reply, never an original post.
        val onPage = Event(fiatjaf, 0, COMMENT, listOf(listOf("I", "https://www.example.com/a"), listOf("K", "web"), listOf("i", "https://www.example.com/a"), listOf("k", "web")), "nice")
        assertNull(parentOf(onPage)); assertNull(rootOf(onPage)); assertTrue(isReply(onPage))
        assertEquals("example.com", commentedOn(onPage))
        assertEquals("an article", commentedOn(Event(fiatjaf, 0, COMMENT, listOf(listOf("A", "30023:$adam:x"), listOf("K", "30023")), "")))
        // A plain note is a reply only when it answers one.
        assertFalse(isReply(ev(listOf("p", fiatjaf))))
        assertTrue(isReply(ev(listOf("e", commentRoot, "", "root"))))
    }

    @Test fun aReplyToACommentIsAComment() {
        val tags = commentReplyTags(comment, listOf(fiatjaf, adam))
        val reply = Event(note, 0, COMMENT, tags, "yes")
        // Same thread root as the comment it answers, and that comment as its parent.
        assertEquals(64, comment.id.length)
        assertEquals(commentRoot, rootOf(reply)); assertEquals(comment.id, parentOf(reply))
        assertEquals(listOf("k", "1111"), tags.first { it[0] == "k" })
        assertEquals(listOf("E", "P"), tags.filter { it[0] in setOf("E", "A", "I", "K", "P") }.map { it[0] })
        assertEquals(listOf(fiatjaf, adam), tags.filter { it[0] == "p" }.map { it[1] })
    }

    @Test fun marblesOwnGrantIsNotListed() {
        val own = { origin: String, via: String?, parent: String? -> cc.aaro.cosign.ui.ownGrant(Site("a".repeat(64), origin, 0, 0, parent, via)) }
        assertEquals(true, own("Marble", "App on this phone", null))
        assertEquals(true, own("Cosign", "App on this phone", null))       // granted before the rename
        assertEquals(false, own("Amethyst", "App on this phone", null))    // another app on the phone
        assertEquals(false, own("https://cosign.example", "Connected by code", null))
        assertEquals(false, own("Marble", "Signed in with Marble", "b".repeat(64)))
    }

    @Test fun aLinkIsActedOnOnce() {
        val links = HandledLinks(FakePrefs())
        val code = "cosign:server?pk=" + "1".repeat(64) + "&t=abc&r=wss%3A%2F%2Fsyb.lol"
        assertEquals(true, links.take(code, fresh = false))      // opened Marble: acted on
        assertEquals(false, links.take(code, fresh = false))     // the same link, handed back when the activity is recreated: not again
        assertEquals(true, links.take(code, fresh = true))       // scanned again on purpose: acted on
        assertEquals(true, links.take("nostrconnect://" + "2".repeat(64) + "?secret=x", fresh = false))
    }

    @Test fun aPostsIdIsAnNeventOtherAppsCanFind() {
        val id = "9739a916c3b6acb295231ed16654fd1e6379fe214eddec360c4514b495f259c9"
        val code = nevent(id, fiatjaf, 1, listOf("wss://relay.damus.io", "wss://nos.lol"))
        assertEquals(true, code.startsWith("nevent1"))
        assertEquals(Ref.Note(id), Ref.parse(code))                  // and Marble reads it back as that post
        assertEquals(Ref.Note(id), Ref.parse("https://njump.me/$code".substringAfterLast('/')))
    }
}
